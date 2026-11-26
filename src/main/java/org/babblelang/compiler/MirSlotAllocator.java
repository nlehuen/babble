package org.babblelang.compiler;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.mir.MirBlock;
import org.babblelang.ir.mir.MirCfg;
import org.babblelang.ir.mir.MirFunction;
import org.babblelang.ir.mir.MirInst;
import org.babblelang.ir.mir.MirTerminator;
import org.babblelang.ir.mir.MirVal;

/**
 * Pre-emission SSA analysis and JVM local-slot allocator for {@link MirEmitter}.
 *
 * <p>Computes the emission plan for a single {@link MirFunction} before any ASM bytecode is
 * emitted: pinned {@link HirVar} slots, zero-copy aliases ({@link MirInst.Narrow}/{@link
 * MirInst.Move}/{@link MirInst.Freeze}), rematerialized constants, branch-fused comparisons,
 * operand-stack pass-through defs, zero-use discarded defs, backward CFG liveness and
 * JVM-category-segregated linear-scan slot coloring, and counted-loop non-negativity facts.
 */
final class MirSlotAllocator {
    private final MirFunction fn;
    private final MethodEmitContext ctx;
    private final MirCfg cfg;
    private final List<MirBlock> rpo;
    private final Map<Integer, MirBlock> blockById;

    private final Map<Integer, Integer> valSlots = new HashMap<>();
    private final Map<Integer, MirVal> aliasTarget = new HashMap<>();
    private final Map<Integer, MirInst.Const> rematerialized = new HashMap<>();
    private final Map<Integer, MirInst> fusedConditions = new HashMap<>();
    private final Set<Integer> stacked = new HashSet<>();
    private final Set<Integer> discarded = new HashSet<>();
    private final Set<MirInst> deferred = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Integer, MirInst> defByValId = new HashMap<>();
    private final Set<Integer> nonNegativeBlockParams = new HashSet<>();

    private MirSlotAllocator(
            MirFunction fn,
            MethodEmitContext ctx,
            MirCfg cfg,
            List<MirBlock> rpo,
            Map<Integer, MirBlock> blockById) {
        this.fn = fn;
        this.ctx = ctx;
        this.cfg = cfg;
        this.rpo = rpo;
        this.blockById = blockById;
    }

    static MirSlotAllocator analyze(
            MirFunction fn,
            MethodEmitContext ctx,
            MirCfg cfg,
            List<MirBlock> rpo,
            Map<Integer, MirBlock> blockById) {
        MirSlotAllocator plan = new MirSlotAllocator(fn, ctx, cfg, rpo, blockById);
        plan.ensurePinnedSlots();
        plan.resolveAliasesAndAllocateSlots();
        plan.analyzeNonNegative();
        return plan;
    }

    // ------------------------------------------------------------------ plan queries

    MirVal resolveRoot(MirVal v) {
        MirVal cur = v;
        while (aliasTarget.containsKey(cur.id())) {
            cur = aliasTarget.get(cur.id());
        }
        return cur;
    }

    int slotOf(MirVal v) {
        Integer slot = valSlots.get(resolveRoot(v).id());
        if (slot == null) {
            throw new IllegalStateException("No local for MIR value " + v + " in " + fn.name());
        }
        return slot;
    }

    boolean isDeferred(MirInst inst) {
        return deferred.contains(inst);
    }

    boolean isStacked(int rootId) {
        return stacked.contains(rootId);
    }

    boolean isDiscarded(int valId) {
        return discarded.contains(valId);
    }

    MirInst.Const rematerialized(int rootId) {
        return rematerialized.get(rootId);
    }

    MirInst fusedCondition(int valId) {
        return fusedConditions.get(valId);
    }

    MethodEmitContext.MirOperand operandOf(MirVal v) {
        int rootId = resolveRoot(v).id();
        if (stacked.contains(rootId)) {
            return MethodEmitContext.MirOperand.stacked(v.kind());
        }
        MirInst.Const c = rematerialized.get(rootId);
        if (c != null) {
            return MethodEmitContext.MirOperand.constant(c.value(), v.kind());
        }
        return new MethodEmitContext.MirOperand(slotOf(v), v.kind());
    }

    MirInst.Const constOf(MirVal v) {
        int id = resolveRoot(v).id();
        MirInst.Const c = rematerialized.get(id);
        if (c != null) {
            return c;
        }
        return defByValId.get(id) instanceof MirInst.Const mc ? mc : null;
    }

    boolean isStaticallyNonNegative(MirVal val) {
        return isStaticallyNonNegative(val, 0);
    }

    // ------------------------------------------------------------------ pinned slots & coloring

    private void ensurePinnedSlots() {
        for (HirVar v : fn.pinnedVars()) {
            if (!ctx.slots.containsKey(v.id())) {
                PrimitiveKind vk = v.primitiveKind();
                int slot = ctx.newLocal(v.cell() ? PrimitiveKind.NONE : vk);
                ctx.slots.put(v.id(), slot);
                if (!v.cell() && vk.isPrimitive() && v.mayBeUndefined() && !v.definitelyDefined()) {
                    int defSlot = ctx.newLocal(PrimitiveKind.BOOL);
                    ctx.definedSlots.put(v.id(), defSlot);
                }
            }
        }
    }

    private void resolveAliasesAndAllocateSlots() {
        // 1. Bind non-pinned function parameters directly to the slots initialized by HirEmitter's
        // prologue.
        for (int i = 0; i < fn.params().size(); i++) {
            MirVal pv = fn.params().get(i);
            HirVar hv = fn.paramVars().get(i);
            valSlots.put(pv.id(), ctx.slots.get(hv.id()));
        }

        // 2. Record zero-copy aliases (Narrow, Move, Freeze) to their root SSA value.
        for (MirBlock b : rpo) {
            for (MirInst inst : b.insts()) {
                if (inst instanceof MirInst.Narrow n) {
                    aliasTarget.put(n.result().id(), resolveRoot(n.operand()));
                } else if (inst instanceof MirInst.Move m) {
                    aliasTarget.put(m.result().id(), resolveRoot(m.operand()));
                } else if (inst instanceof MirInst.Freeze f) {
                    aliasTarget.put(f.result().id(), resolveRoot(f.operand()));
                }
            }
        }

        // 2b. Pick the constants to rematerialize and the conditions to fuse into their branch.
        selectRematerializedAndFused();

        // 3. Compute backward CFG liveness on canonical root values for sound interval coloring.
        int valCount = maxValId() + 1;
        int blockCount = rpo.size();
        Map<Integer, Integer> rpoIndex = new HashMap<>(blockCount * 2);
        for (int i = 0; i < blockCount; i++) {
            rpoIndex.put(rpo.get(i).id(), i);
        }
        int[] bStart = new int[blockCount];
        int[] bEnd = new int[blockCount];
        BitSet[] uses = new BitSet[blockCount];
        BitSet[] defs = new BitSet[blockCount];
        BitSet[] blockParamIds = new BitSet[blockCount];
        int[] defPos = new int[valCount];
        int[] lastUsePos = new int[valCount];
        PrimitiveKind[] valKind = new PrimitiveKind[valCount];
        int[] paramOwnerBlock = new int[valCount];
        java.util.Arrays.fill(defPos, -1);
        java.util.Arrays.fill(lastUsePos, -1);
        java.util.Arrays.fill(paramOwnerBlock, -1);

        int pos = 0;
        for (int bi = 0; bi < blockCount; bi++) {
            MirBlock b = rpo.get(bi);
            int start = pos++;
            bStart[bi] = start;
            BitSet bUses = new BitSet(valCount);
            BitSet bDefs = new BitSet(valCount);
            BitSet bpIds = new BitSet(valCount);
            for (MirVal bp : b.params()) {
                bpIds.set(bp.id());
                bDefs.set(bp.id());
                defPos[bp.id()] = start;
                valKind[bp.id()] = bp.kind();
                paramOwnerBlock[bp.id()] = b.id();
            }
            List<MirVal> fusedUses = new ArrayList<>();
            for (MirInst inst : b.insts()) {
                int ipos = pos++;
                if (deferred.contains(inst)) {
                    if (fusedConditions.containsKey(inst.result().id())) {
                        fusedUses.addAll(inst.usedValues());
                    }
                    continue;
                }
                for (MirVal u : inst.usedValues()) {
                    int root = resolveRoot(u).id();
                    if (rematerialized.containsKey(root) || stacked.contains(root)) {
                        continue;
                    }
                    if (!bDefs.get(root)) {
                        bUses.set(root);
                    }
                    lastUsePos[root] = Math.max(lastUsePos[root], ipos);
                }
                MirVal res = inst.result();
                if (res != null
                        && !aliasTarget.containsKey(res.id())
                        && !stacked.contains(res.id())
                        && !discarded.contains(res.id())) {
                    bDefs.set(res.id());
                    defPos[res.id()] = ipos;
                    valKind[res.id()] = res.kind();
                }
            }
            int tpos = pos++;
            List<MirVal> termUses = new ArrayList<>(b.terminator().usedValues());
            termUses.addAll(fusedUses);
            for (MirVal u : termUses) {
                int root = resolveRoot(u).id();
                if (rematerialized.containsKey(root)
                        || fusedConditions.containsKey(root)
                        || stacked.contains(root)) {
                    continue;
                }
                if (!bDefs.get(root)) {
                    bUses.set(root);
                }
                lastUsePos[root] = Math.max(lastUsePos[root], tpos);
            }
            bEnd[bi] = tpos;
            uses[bi] = bUses;
            defs[bi] = bDefs;
            blockParamIds[bi] = bpIds;
        }

        BitSet[] liveIn = new BitSet[blockCount];
        BitSet[] liveOut = new BitSet[blockCount];
        for (int bi = 0; bi < blockCount; bi++) {
            liveIn[bi] = (BitSet) uses[bi].clone();
            liveOut[bi] = new BitSet(valCount);
        }
        BitSet scratch = new BitSet(valCount);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = blockCount - 1; i >= 0; i--) {
                BitSet out = liveOut[i];
                for (int succId : cfg.successors(rpo.get(i).id())) {
                    Integer si = rpoIndex.get(succId);
                    if (si == null) {
                        continue;
                    }
                    scratch.clear();
                    scratch.or(liveIn[si]);
                    scratch.andNot(blockParamIds[si]);
                    scratch.andNot(out);
                    if (!scratch.isEmpty()) {
                        out.or(scratch);
                        changed = true;
                    }
                }
                scratch.clear();
                scratch.or(out);
                scratch.andNot(defs[i]);
                scratch.andNot(liveIn[i]);
                if (!scratch.isEmpty()) {
                    liveIn[i].or(scratch);
                    changed = true;
                }
            }
        }

        // 4. Build [start, end] live intervals for all non-param, non-alias SSA values.
        record Interval(int valId, int start, int end, int category, PrimitiveKind kind) {}
        int[] rangeStart = new int[valCount];
        int[] rangeEnd = new int[valCount];
        BitSet ranged = new BitSet(valCount);
        for (int vid = 0; vid < valCount; vid++) {
            if (defPos[vid] < 0 || valSlots.containsKey(vid)) {
                continue;
            }
            int st = defPos[vid];
            int end = Math.max(st, lastUsePos[vid] >= 0 ? lastUsePos[vid] : st);
            if (paramOwnerBlock[vid] >= 0) {
                for (int predId : cfg.predecessors(paramOwnerBlock[vid])) {
                    Integer pi = rpoIndex.get(predId);
                    if (pi != null) {
                        st = Math.min(st, bEnd[pi]);
                        end = Math.max(end, bEnd[pi]);
                    }
                }
            }
            rangeStart[vid] = st;
            rangeEnd[vid] = end;
            ranged.set(vid);
        }
        for (int bi = 0; bi < blockCount; bi++) {
            scratch.clear();
            scratch.or(liveIn[bi]);
            scratch.or(liveOut[bi]);
            scratch.and(ranged);
            for (int vid = scratch.nextSetBit(0); vid >= 0; vid = scratch.nextSetBit(vid + 1)) {
                rangeStart[vid] = Math.min(rangeStart[vid], bStart[bi]);
                rangeEnd[vid] = Math.max(rangeEnd[vid], bEnd[bi]);
            }
        }
        List<Interval> intervals = new ArrayList<>(ranged.cardinality());
        for (int vid = ranged.nextSetBit(0); vid >= 0; vid = ranged.nextSetBit(vid + 1)) {
            PrimitiveKind pk = valKind[vid];
            intervals.add(
                    new Interval(vid, rangeStart[vid], rangeEnd[vid], jvmSlotCategory(pk), pk));
        }
        intervals.sort((x, y) ->
                x.start != y.start
                        ? Integer.compare(x.start, y.start)
                        : Integer.compare(x.valId, y.valId));

        // 5. Linear-scan coloring segregated by JVM slot verification category (I/J/F/D/A).
        Map<Integer, List<int[]>> activeByCat = new HashMap<>();
        Map<Integer, List<Integer>> freeByCat = new HashMap<>();
        for (Interval iv : intervals) {
            List<int[]> active = activeByCat.computeIfAbsent(iv.category, k -> new ArrayList<>());
            List<Integer> free = freeByCat.computeIfAbsent(iv.category, k -> new ArrayList<>());
            for (int k = active.size() - 1; k >= 0; k--) {
                if (active.get(k)[0] < iv.start) {
                    free.add(active.remove(k)[1]);
                }
            }
            int slot = free.isEmpty() ? ctx.newLocal(iv.kind) : free.remove(free.size() - 1);
            valSlots.put(iv.valId, slot);
            active.add(new int[] {iv.end, slot});
        }
    }

    private void selectRematerializedAndFused() {
        Map<Integer, Integer> useCount = new HashMap<>();
        Set<Integer> needsSlot = new HashSet<>();
        Map<Integer, MirInst.Const> constants = new HashMap<>();
        for (MirBlock b : rpo) {
            for (MirInst inst : b.insts()) {
                for (MirVal u : inst.usedValues()) {
                    useCount.merge(resolveRoot(u).id(), 1, Integer::sum);
                }
                for (MirVal u : slotOperands(inst)) {
                    needsSlot.add(resolveRoot(u).id());
                }
                if (inst instanceof MirInst.Const c && isRematerializable(c)) {
                    constants.put(c.result().id(), c);
                }
                MirVal res = inst.result();
                if (res != null
                        && aliasTarget.containsKey(res.id())
                        && resolveRoot(res).kind() != res.kind()) {
                    needsSlot.add(resolveRoot(res).id());
                }
            }
            for (MirVal u : b.terminator().usedValues()) {
                useCount.merge(resolveRoot(u).id(), 1, Integer::sum);
            }
        }
        for (MirInst.Const c : constants.values()) {
            if (!needsSlot.contains(c.result().id())) {
                rematerialized.put(c.result().id(), c);
                deferred.add(c);
            }
        }
        for (MirBlock b : rpo) {
            if (!(b.terminator() instanceof MirTerminator.BrIf bi)) {
                continue;
            }
            Map<Integer, MirInst> defs = new HashMap<>();
            for (MirInst inst : b.insts()) {
                if (inst.result() != null) {
                    defs.put(inst.result().id(), inst);
                }
            }
            MirVal cond = bi.cond();
            while (cond != null
                    && useCount.getOrDefault(cond.id(), 0) == 1
                    && !needsSlot.contains(cond.id())) {
                MirInst def = defs.get(cond.id());
                MirVal next = null;
                if (def instanceof MirInst.Binary bin
                        && bin.op().isComparison()
                        && bin.result().kind() == PrimitiveKind.BOOL
                        && JvmOpEmitter.canEmitPrimitiveCmp(
                                bin.op(), bin.left().kind(), bin.right().kind())) {
                    // ends the chain
                } else if (def instanceof MirInst.Unary un
                        && un.op() == HirExpr.UnaryOp.NOT
                        && un.operand().kind() == PrimitiveKind.BOOL
                        && un.result().kind() == PrimitiveKind.BOOL) {
                    next = un.operand();
                } else {
                    break;
                }
                fusedConditions.put(cond.id(), def);
                deferred.add(def);
                cond = next;
            }
        }
        for (MirBlock b : rpo) {
            List<MirInst> emitted = new ArrayList<>(b.insts().size());
            for (MirInst inst : b.insts()) {
                if (!deferred.contains(inst)) {
                    emitted.add(inst);
                }
            }
            for (int i = 0; i < emitted.size(); i++) {
                MirInst producer = emitted.get(i);
                MirVal res = producer.result();
                if (res == null
                        || !leavesResultViaStore(producer)
                        || aliasTarget.containsKey(res.id())
                        || needsSlot.contains(res.id())) {
                    continue;
                }
                int uses = useCount.getOrDefault(res.id(), 0);
                if (uses == 0) {
                    discarded.add(res.id());
                    continue;
                }
                if (uses != 1) {
                    continue;
                }
                if (i + 1 < emitted.size()) {
                    MirVal first = firstLoadedOperand(emitted.get(i + 1));
                    if (first != null
                            && resolveRoot(first).id() == res.id()
                            && first.kind() == res.kind()) {
                        stacked.add(res.id());
                    }
                } else {
                    MirVal first = firstLoadedOperand(b.terminator());
                    if (first != null
                            && resolveRoot(first).id() == res.id()
                            && first.kind() == res.kind()) {
                        stacked.add(res.id());
                    } else if (b.terminator() instanceof MirTerminator.Br br) {
                        MirBlock target = blockById.get(br.targetBlock());
                        for (int k = 0; k < br.args().size(); k++) {
                            MirVal arg = br.args().get(k);
                            if (resolveRoot(arg).id() == res.id()
                                    && arg.kind() == res.kind()
                                    && target.params().get(k).kind() == res.kind()) {
                                stacked.add(res.id());
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean leavesResultViaStore(MirInst inst) {
        return !(inst instanceof MirInst.ForIterInit
                || inst instanceof MirInst.UnwrapThrown
                || inst instanceof MirInst.ScopeEnter
                || inst instanceof MirInst.Narrow
                || inst instanceof MirInst.Move
                || inst instanceof MirInst.Freeze);
    }

    private MirVal firstLoadedOperand(MirInst inst) {
        return switch (inst) {
            case MirInst.Copy c -> c.source();
            case MirInst.Widen w -> w.operand();
            case MirInst.Convert c -> c.operand();
            case MirInst.Unary u -> u.operand();
            case MirInst.Binary b -> b.left();
            case MirInst.SlotWrite sw -> sw.value();
            case MirInst.LoadField lf -> lf.receiver();
            case MirInst.LoadExport le -> le.module();
            case MirInst.CallMethod cm -> cm.receiver();
            case MirInst.CallInterface ci -> ci.receiver();
            case MirInst.RecordNew rn -> rn.classRef();
            case MirInst.RecordReuse rr -> rr.target();
            case MirInst.RangeNew rn -> rn.start();
            case MirInst.CheckCast cc -> cc.operand();
            case MirInst.NonNull nn -> nn.operand();
            case MirInst.IsCheck ic -> ic.operand();
            case MirInst.CallBabble cb -> !cb.selfRecurse() ? cb.callee() : null;
            case MirInst.OpaqueExpr op ->
                    !op.operands().isEmpty()
                                    && (op.template() instanceof HirExpr.DynamicCall
                                            || op.template() instanceof HirExpr.ModuleExportGet
                                            || op.template() instanceof HirExpr.DynamicSelect
                                            || op.template() instanceof HirExpr.OverloadSelect
                                            || op.template() instanceof HirExpr.OverloadDispatch
                                            || op.template() instanceof HirExpr.SafeCall
                                            || op.template() instanceof HirExpr.SpreadAdapt
                                            || op.template() instanceof HirExpr.JavaSamAdapt)
                            ? op.operands().get(0)
                            : null;
            default -> null;
        };
    }

    private MirVal firstLoadedOperand(MirTerminator term) {
        if (term instanceof MirTerminator.Ret ret) {
            return ret.value();
        }
        if (term instanceof MirTerminator.Throw th) {
            return th.value();
        }
        if (term instanceof MirTerminator.BrIf bi) {
            MirVal cond = bi.cond();
            MirInst fused;
            while ((fused = fusedConditions.get(cond.id())) != null) {
                if (fused instanceof MirInst.Binary cmp) {
                    return cmp.left();
                }
                cond = ((MirInst.Unary) fused).operand();
            }
            return cond;
        }
        if (term instanceof MirTerminator.Switch sw) {
            return sw.key();
        }
        return null;
    }

    private static List<MirVal> slotOperands(MirInst inst) {
        List<MirVal> operands =
                switch (inst) {
                    case MirInst.ScopeExit sx -> Collections.singletonList(sx.prevNamespace());
                    case MirInst.UnwrapThrown ut -> Collections.singletonList(ut.thrownExn());
                    case MirInst.AddSuppressed as ->
                            java.util.Arrays.asList(as.primary(), as.secondary());
                    case MirInst.ArrayLen al -> Collections.singletonList(al.array());
                    case MirInst.ForIterInit fi -> Collections.singletonList(fi.iterable());
                    case MirInst.ForIterHasNext fh ->
                            fh.target().kind() == LoweringTargets.ForKind.ARRAY
                                    ? Collections.<MirVal>emptyList()
                                    : Collections.singletonList(fh.iterator());
                    case MirInst.ForIterNext fnInst ->
                            java.util.Arrays.asList(fnInst.iterator(), fnInst.index());
                    case MirInst.CatchMatch cm -> Collections.singletonList(cm.value());
                    default -> Collections.<MirVal>emptyList();
                };
        return operands.stream().filter(java.util.Objects::nonNull).toList();
    }

    private static boolean isRematerializable(MirInst.Const c) {
        Object v = c.value();
        if (v == null || v instanceof String) {
            return c.result().kind() == PrimitiveKind.NONE;
        }
        PrimitiveKind pushed = JvmOpEmitter.constantKind(v);
        return pushed != null && pushed.isPrimitive() && pushed == c.result().kind();
    }

    private int maxValId() {
        int max = Math.max(fn.nextValId() - 1, 0);
        for (MirVal p : fn.params()) {
            max = Math.max(max, p.id());
        }
        for (MirBlock b : rpo) {
            for (MirVal p : b.params()) {
                max = Math.max(max, p.id());
            }
            for (MirInst inst : b.insts()) {
                if (inst.result() != null) {
                    max = Math.max(max, inst.result().id());
                }
                for (MirVal u : inst.usedValues()) {
                    max = Math.max(max, u.id());
                }
            }
            for (MirVal u : b.terminator().usedValues()) {
                max = Math.max(max, u.id());
            }
        }
        return max;
    }

    private static int jvmSlotCategory(PrimitiveKind k) {
        return switch (k) {
            case BYTE, SHORT, INT, BOOL, CHAR -> 0;
            case LONG -> 1;
            case FLOAT -> 2;
            case DOUBLE -> 3;
            case NONE -> 4;
        };
    }

    // ------------------------------------------------------------------ non-negativity analysis

    private void analyzeNonNegative() {
        for (MirBlock b : rpo) {
            for (MirInst inst : b.insts()) {
                if (inst.result() != null) {
                    defByValId.put(inst.result().id(), inst);
                }
            }
        }
        record InEdge(MirBlock from, List<MirVal> args, boolean isThenEdge) {}
        Map<Integer, List<InEdge>> incoming = new HashMap<>();
        for (MirBlock b : rpo) {
            if (b.terminator() instanceof MirTerminator.Br br) {
                incoming.computeIfAbsent(br.targetBlock(), k -> new ArrayList<>())
                        .add(new InEdge(b, br.args(), false));
            } else if (b.terminator() instanceof MirTerminator.BrIf bi) {
                incoming.computeIfAbsent(bi.thenBlock(), k -> new ArrayList<>())
                        .add(new InEdge(b, bi.thenArgs(), true));
                incoming.computeIfAbsent(bi.elseBlock(), k -> new ArrayList<>())
                        .add(new InEdge(b, bi.elseArgs(), false));
            } else if (b.terminator() instanceof MirTerminator.Switch sw) {
                for (int i = 0; i < sw.keys().length; i++) {
                    incoming.computeIfAbsent(sw.targetBlocks()[i], k -> new ArrayList<>())
                            .add(new InEdge(b, sw.targetArgs().get(i), false));
                }
                incoming.computeIfAbsent(sw.defaultBlock(), k -> new ArrayList<>())
                        .add(new InEdge(b, sw.defaultArgs(), false));
            }
        }
        for (MirBlock b : rpo) {
            if (b.handlerEntry() || b.params().isEmpty()) {
                continue;
            }
            List<InEdge> edges = incoming.get(b.id());
            if (edges == null || edges.size() != 2) {
                continue;
            }
            for (int k = 0; k < b.params().size(); k++) {
                MirVal param = b.params().get(k);
                if (param.kind() != PrimitiveKind.INT && param.kind() != PrimitiveKind.LONG) {
                    continue;
                }
                MirVal a0 = edges.get(0).args().get(k);
                MirVal a1 = edges.get(1).args().get(k);
                InEdge initEdge;
                InEdge backEdge;
                MirVal initVal;
                MirVal backVal;
                if (isStepOf(a1, param)) {
                    initEdge = edges.get(0);
                    backEdge = edges.get(1);
                    initVal = a0;
                    backVal = a1;
                } else if (isStepOf(a0, param)) {
                    initEdge = edges.get(1);
                    backEdge = edges.get(0);
                    initVal = a1;
                    backVal = a0;
                } else {
                    continue;
                }
                MirInst.Binary stepBin = (MirInst.Binary) defByValId.get(resolveRoot(backVal).id());
                boolean ascending = stepBin.op() == HirExpr.BinaryOp.ADD;
                List<InEdge> backPreds = incoming.get(backEdge.from().id());
                if (backPreds == null || backPreds.size() != 1) {
                    continue;
                }
                MirBlock stepGuardBlock = backPreds.get(0).from();
                if (!(stepGuardBlock.terminator() instanceof MirTerminator.BrIf guardBr)
                        || guardBr.elseBlock() != backEdge.from().id()) {
                    continue;
                }
                MirVal stopVal = matchEqWith(guardBr.cond(), param);
                if (stopVal == null) {
                    continue;
                }
                if (ascending) {
                    if (!isStaticallyNonNegative(initVal, 0)) {
                        continue;
                    }
                    if (isCountedAscendingBounded(
                            initEdge.from(), initEdge.isThenEdge(), initVal, stopVal, incoming)) {
                        nonNegativeBlockParams.add(resolveRoot(param).id());
                    }
                } else {
                    if (isStaticallyNonNegative(stopVal, 0) && isRangeLastCall(initVal, stopVal)) {
                        nonNegativeBlockParams.add(resolveRoot(param).id());
                    }
                }
            }
        }
    }

    private boolean isStepOf(MirVal candidate, MirVal param) {
        MirInst def = defByValId.get(resolveRoot(candidate).id());
        if (!(def instanceof MirInst.Binary bin)) {
            return false;
        }
        if (bin.op() != HirExpr.BinaryOp.ADD && bin.op() != HirExpr.BinaryOp.SUB) {
            return false;
        }
        if (resolveRoot(bin.left()).id() != resolveRoot(param).id()) {
            return false;
        }
        MirInst.Const c = constOf(bin.right());
        if (c != null && c.value() instanceof Number n) {
            long v = bin.result().kind() == PrimitiveKind.LONG ? n.longValue() : n.intValue();
            return v > 0L;
        }
        return false;
    }

    private MirVal matchEqWith(MirVal cond, MirVal param) {
        MirInst def = defByValId.get(resolveRoot(cond).id());
        if (!(def instanceof MirInst.Binary bin) || bin.op() != HirExpr.BinaryOp.EQ) {
            return null;
        }
        int pid = resolveRoot(param).id();
        if (resolveRoot(bin.left()).id() == pid) {
            return bin.right();
        }
        if (resolveRoot(bin.right()).id() == pid) {
            return bin.left();
        }
        return null;
    }

    private boolean isRangeLastCall(MirVal val, MirVal expectedStart) {
        MirInst def = defByValId.get(resolveRoot(val).id());
        if (def instanceof MirInst.Convert cv) {
            def = defByValId.get(resolveRoot(cv.operand()).id());
        }
        if (!(def instanceof MirInst.CallJava cj) || !cj.target().isStatic()) {
            return false;
        }
        java.lang.reflect.Method m = cj.target().method();
        if (m.getDeclaringClass() != org.babblelang.runtime.Range.class) {
            return false;
        }
        String name = m.getName();
        if (!"lastIntElement".equals(name)
                && !"lastLongElement".equals(name)
                && !"lastCharElement".equals(name)) {
            return false;
        }
        return !cj.args().isEmpty()
                && resolveRoot(cj.args().get(0)).id() == resolveRoot(expectedStart).id();
    }

    private boolean isCountedAscendingBounded(
            MirBlock initFrom,
            boolean initIsThenEdge,
            MirVal startVal,
            MirVal stopVal,
            Map<Integer, ?> incoming) {
        if (initIsThenEdge && initFrom.terminator() instanceof MirTerminator.BrIf bi) {
            MirInst cmpDef = defByValId.get(resolveRoot(bi.cond()).id());
            if (cmpDef instanceof MirInst.Binary cmp
                    && cmp.op() == HirExpr.BinaryOp.LTE
                    && resolveRoot(cmp.left()).id() == resolveRoot(startVal).id()
                    && resolveRoot(cmp.right()).id() == resolveRoot(stopVal).id()) {
                return true;
            }
        }
        Object predsObj = incoming.get(initFrom.id());
        if (!(predsObj instanceof List<?> preds) || preds.size() != 1) {
            return false;
        }
        MirBlock guardBlock = null;
        for (MirBlock b : rpo) {
            if (b.terminator() instanceof MirTerminator.BrIf bi
                    && bi.thenBlock() == initFrom.id()) {
                guardBlock = b;
                break;
            }
        }
        if (guardBlock == null || !(guardBlock.terminator() instanceof MirTerminator.BrIf bi)) {
            return false;
        }
        MirInst cmpDef = defByValId.get(resolveRoot(bi.cond()).id());
        if (!(cmpDef instanceof MirInst.Binary cmp)
                || resolveRoot(cmp.left()).id() != resolveRoot(startVal).id()) {
            return false;
        }
        MirVal endVal = cmp.right();
        if (cmp.op() == HirExpr.BinaryOp.LTE
                && resolveRoot(endVal).id() == resolveRoot(stopVal).id()) {
            return true;
        }
        if (cmp.op() == HirExpr.BinaryOp.LT) {
            MirInst stopDef = defByValId.get(resolveRoot(stopVal).id());
            if (stopDef instanceof MirInst.Binary sub
                    && sub.op() == HirExpr.BinaryOp.SUB
                    && resolveRoot(sub.left()).id() == resolveRoot(endVal).id()) {
                MirInst.Const one = constOf(sub.right());
                if (one != null && one.value() instanceof Number n && n.longValue() == 1L) {
                    return true;
                }
            }
        }
        return (cmp.op() == HirExpr.BinaryOp.LT || cmp.op() == HirExpr.BinaryOp.LTE)
                && isRangeLastCall(stopVal, startVal);
    }

    private boolean isStaticallyNonNegative(MirVal val, int depth) {
        if (val == null || depth > 8) {
            return false;
        }
        MirVal root = resolveRoot(val);
        if (root.kind() == PrimitiveKind.CHAR || nonNegativeBlockParams.contains(root.id())) {
            return true;
        }
        MirInst.Const c = constOf(root);
        if (c != null) {
            return (c.value() instanceof Number n && n.longValue() >= 0L)
                    || c.value() instanceof Character;
        }
        MirInst def = defByValId.get(root.id());
        if (def == null) {
            return false;
        }
        return switch (def) {
            case MirInst.Copy cp -> isStaticallyNonNegative(cp.source(), depth + 1);
            case MirInst.Convert cv ->
                    cv.operand().kind() == PrimitiveKind.CHAR
                            || ((cv.operand().kind() == PrimitiveKind.BYTE
                                            || cv.operand().kind() == PrimitiveKind.SHORT
                                            || cv.operand().kind() == PrimitiveKind.INT)
                                    && (cv.targetKind() == PrimitiveKind.INT
                                            || cv.targetKind() == PrimitiveKind.LONG)
                                    && isStaticallyNonNegative(cv.operand(), depth + 1));
            case MirInst.ArrayLen ignored -> true;
            case MirInst.CallJava cj -> {
                java.lang.reflect.Method m = cj.target().method();
                yield ("length".equals(m.getName()) && m.getDeclaringClass() == String.class)
                        || ("size".equals(m.getName())
                                && (java.util.Collection.class.isAssignableFrom(
                                                m.getDeclaringClass())
                                        || java.util.Map.class.isAssignableFrom(
                                                m.getDeclaringClass())));
            }
            case MirInst.Binary b -> {
                if (b.op() != HirExpr.BinaryOp.MOD
                        || (b.result().kind() != PrimitiveKind.INT
                                && b.result().kind() != PrimitiveKind.LONG)) {
                    yield false;
                }
                MirInst.Const rc = constOf(b.right());
                yield rc != null
                        && rc.value() instanceof Number rn
                        && (b.result().kind() == PrimitiveKind.LONG
                                ? rn.longValue() > 0L
                                : rn.intValue() > 0);
            }
            default -> false;
        };
    }
}
