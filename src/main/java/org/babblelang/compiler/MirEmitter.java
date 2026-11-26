package org.babblelang.compiler;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.mir.MirBlock;
import org.babblelang.ir.mir.MirCfg;
import org.babblelang.ir.mir.MirFunction;
import org.babblelang.ir.mir.MirHandler;
import org.babblelang.ir.mir.MirInst;
import org.babblelang.ir.mir.MirLowerer;
import org.babblelang.ir.mir.MirTerminator;
import org.babblelang.ir.mir.MirVal;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Emits JVM bytecode for a single SSA {@link MirFunction} inside {@link HirEmitter}'s active
 * {@link MethodEmitContext} ({@code BIP014-I3} §6.4).
 *
 * <p>Performs backward CFG liveness and JVM-type-segregated local slot coloring, zero-copy
 * aliasing for {@link MirInst.Narrow}/{@link MirInst.Move}/{@link MirInst.Freeze}, cycle-breaking
 * parallel-copy lowering on {@code Br}/{@code BrIf} edges, contiguous RPO {@code visitTryCatchBlock}
 * range merging, and zero-duplication ABI delegation into {@link HirEmitter}'s sub-emitters via
 * {@link MethodEmitContext#mirOverrides}.
 */
final class MirEmitter {
    private final HirEmitter emitter;
    private final MethodEmitContext ctx;
    private final MethodVisitor mv;
    private final MirFunction fn;
    private final HirFunction hirFn;
    private final PrimitiveKind methodReturnKind;
    private final MirCfg cfg;
    private final List<MirBlock> rpo;
    private final Map<Integer, MirBlock> blockById;
    private final Map<Integer, Label> blockLabels = new HashMap<>();
    private final Map<Integer, Integer> valSlots = new HashMap<>();
    private final Map<Integer, MirVal> aliasTarget = new HashMap<>();
    /** Constants pushed again at each use instead of living in a local, by value id. */
    private final Map<Integer, MirInst.Const> rematerialized = new HashMap<>();
    /** Comparisons and negations emitted as the conditional jump of their block's {@code BrIf}, by result id. */
    private final Map<Integer, MirInst> fusedConditions = new HashMap<>();
    /** Values left on the operand stack by their definition for the next instruction, which loads them first. */
    private final Set<Integer> stacked = new HashSet<>();
    /** Side-effecting instruction results with zero uses : popped on the stack instead of stored in a local. */
    private final Set<Integer> discarded = new HashSet<>();
    /** Instructions not emitted where they stand : rematerialized constants and fused conditions. */
    private final Set<MirInst> deferred = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Runnable> edgeTrampolines = new ArrayList<>();

    MirEmitter(HirEmitter emitter, MirFunction fn, HirFunction hirFn, PrimitiveKind methodReturnKind) {
        this.emitter = emitter;
        this.ctx = emitter.ctx;
        this.mv = ctx.mv;
        this.fn = fn;
        this.hirFn = hirFn;
        this.methodReturnKind = methodReturnKind;
        this.cfg = MirCfg.analyze(fn);
        this.rpo = cfg.rpo();
        this.blockById = fn.blockMap();
    }

    void emit() {
        ctx.cacheNameReads = true;
        ensurePinnedSlots();
        resolveAliasesAndAllocateSlots();
        for (MirBlock b : rpo) {
            blockLabels.put(b.id(), new Label());
        }

        // Emit blocks in RPO order, recording start/end labels for contiguous exception-table ranges.
        Label[] startLabels = new Label[rpo.size()];
        Label[] endLabels = new Label[rpo.size()];
        boolean[] hasCode = new boolean[rpo.size()];
        for (int i = 0; i < rpo.size(); i++) {
            MirBlock b = rpo.get(i);
            Label start = blockLabels.get(b.id());
            startLabels[i] = start;
            mv.visitLabel(start);

            boolean emitted = false;
            if (b.handlerEntry() && !b.params().isEmpty()) {
                // JVM exception dispatch pushes the caught Throwable on the operand stack.
                mv.visitVarInsn(ASTORE, slotOf(b.params().get(0)));
                emitted = true;
            }
            for (MirInst inst : b.insts()) {
                if (deferred.contains(inst)) {
                    continue;
                }
                emitInst(inst);
                if (!(inst instanceof MirInst.Narrow || inst instanceof MirInst.Move || inst instanceof MirInst.Freeze)) {
                    emitted = true;
                }
            }
            MirBlock nextBlock = (i + 1 < rpo.size()) ? rpo.get(i + 1) : null;
            if (emitTerminator(b.terminator(), nextBlock)) {
                emitted = true;
            }

            Label end = new Label();
            mv.visitLabel(end);
            endLabels[i] = end;
            hasCode[i] = emitted;
        }

        // Emit BrIf edge-copy trampolines after main RPO blocks (pure local moves, cannot throw).
        for (Runnable trampoline : edgeTrampolines) {
            trampoline.run();
        }

        // Register merged contiguous try-catch ranges in exact innermost-to-outermost handler order,
        // skipping zero-instruction fallthrough blocks so every JVM range satisfies start_pc < end_pc.
        int i = 0;
        while (i < rpo.size()) {
            List<MirHandler> handlers = rpo.get(i).handlers();
            if (handlers.isEmpty() || !hasCode[i]) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < rpo.size() && rpo.get(j + 1).handlers().equals(handlers)) {
                j++;
            }
            for (MirHandler h : handlers) {
                Label handlerLabel = blockLabels.get(h.handlerBlockId());
                if (handlerLabel != null) {
                    mv.visitTryCatchBlock(startLabels[i], endLabels[j], handlerLabel, h.catchJvmType());
                }
            }
            i = j + 1;
        }
    }

    // ------------------------------------------------------------------ pinned slots & SSA slot coloring

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
        // 1. Bind non-pinned function parameters directly to the slots initialized by HirEmitter's prologue.
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
        //    Value ids are dense : per-block sets are bit sets, per-value facts arrays.
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
                    // A fused condition reads its operands at the branch ; a constant reads nothing.
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
                if (res != null && !aliasTarget.containsKey(res.id())
                        && !stacked.contains(res.id()) && !discarded.contains(res.id())) {
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
                if (rematerialized.containsKey(root) || fusedConditions.containsKey(root) || stacked.contains(root)) {
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
                    if (si == null) continue;
                    // out += liveIn(succ) - params(succ)
                    scratch.clear();
                    scratch.or(liveIn[si]);
                    scratch.andNot(blockParamIds[si]);
                    scratch.andNot(out);
                    if (!scratch.isEmpty()) {
                        out.or(scratch);
                        changed = true;
                    }
                }
                // in += out - defs
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
        record Interval(int valId, int start, int end, int category, PrimitiveKind kind) {
        }
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
                // Parallel copy writes block parameters at the end of every predecessor block.
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
        // A value live into or out of a block covers the whole block : one walk of the live sets.
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
            intervals.add(new Interval(vid, rangeStart[vid], rangeEnd[vid], jvmSlotCategory(pk), pk));
        }
        intervals.sort((x, y) -> x.start != y.start ? Integer.compare(x.start, y.start) : Integer.compare(x.valId, y.valId));

        // 5. Linear-scan coloring segregated by JVM slot verification category (I/J/F/D/A).
        Map<Integer, List<int[]>> activeByCat = new HashMap<>(); // cat -> list of [endPos, slot]
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
            active.add(new int[]{iv.end, slot});
        }
    }

    /**
     * Rematerialized constants : a primitive of its own kind, a string or null, pushed again at
     * each use (by {@link #loadVal}, {@link #operandOf} or a parallel copy) instead of stored in a
     * local and loaded back, unless an instruction reads it from a slot directly. Fused
     * conditions : a primitive comparison or boolean negation whose only use is the
     * {@code BrIf} of its block, emitted as that branch's conditional jump instead of a 0 or 1
     * tested again (see {@link #emitBranchOnTruth}). Both keep the bytecode of a function as
     * compact as {@code HirEmitter}'s, whose size the JIT's inlining heuristics go by.
     */
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
                if (res != null && aliasTarget.containsKey(res.id())
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
            while (cond != null && useCount.getOrDefault(cond.id(), 0) == 1 && !needsSlot.contains(cond.id())) {
                MirInst def = defs.get(cond.id());
                MirVal next = null;
                if (def instanceof MirInst.Binary bin && bin.op().isComparison()
                        && bin.result().kind() == PrimitiveKind.BOOL
                        && canEmitPrimitiveCmp(bin.op(), bin.left().kind(), bin.right().kind())) {
                    // ends the chain
                } else if (def instanceof MirInst.Unary un && un.op() == HirExpr.UnaryOp.NOT
                        && un.operand().kind() == PrimitiveKind.BOOL && un.result().kind() == PrimitiveKind.BOOL) {
                    next = un.operand();
                } else {
                    break;
                }
                fusedConditions.put(cond.id(), def);
                deferred.add(def);
                cond = next;
            }
        }
        // Pop zero-use results directly, and keep a single-use value on the stack when the next
        // emitted instruction (or block terminator / Br parallel copy) consumes it first.
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
                if (res == null || !leavesResultViaStore(producer) || aliasTarget.containsKey(res.id())
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
                    if (first != null && resolveRoot(first).id() == res.id() && first.kind() == res.kind()) {
                        stacked.add(res.id());
                    }
                } else {
                    MirVal first = firstLoadedOperand(b.terminator());
                    if (first != null && resolveRoot(first).id() == res.id() && first.kind() == res.kind()) {
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

    /** Whether an instruction's emission ends in {@link #storeResult}, which can leave the result on the stack instead. */
    private static boolean leavesResultViaStore(MirInst inst) {
        return !(inst instanceof MirInst.ForIterInit || inst instanceof MirInst.UnwrapThrown
                || inst instanceof MirInst.ScopeEnter || inst instanceof MirInst.Narrow
                || inst instanceof MirInst.Move || inst instanceof MirInst.Freeze);
    }

    /**
     * The operand an instruction loads first on an empty stack (either natively or as the first
     * child emitted via {@link MethodEmitContext#mirOverrides}), or null if none is guaranteed.
     */
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
            case MirInst.OpaqueExpr op -> !op.operands().isEmpty()
                    && (op.template() instanceof HirExpr.DynamicCall
                    || op.template() instanceof HirExpr.ModuleExportGet
                    || op.template() instanceof HirExpr.DynamicSelect
                    || op.template() instanceof HirExpr.OverloadSelect
                    || op.template() instanceof HirExpr.OverloadDispatch
                    || op.template() instanceof HirExpr.SafeCall
                    || op.template() instanceof HirExpr.SpreadAdapt
                    || op.template() instanceof HirExpr.JavaSamAdapt)
                    ? op.operands().get(0) : null;
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
        return null;
    }

    /** The operands an instruction reads from their local directly, rather than through {@link #loadVal}. */
    private static List<MirVal> slotOperands(MirInst inst) {
        List<MirVal> operands = switch (inst) {
            case MirInst.ScopeExit sx -> java.util.Collections.singletonList(sx.prevNamespace());
            case MirInst.UnwrapThrown ut -> java.util.Collections.singletonList(ut.thrownExn());
            case MirInst.AddSuppressed as -> java.util.Arrays.asList(as.primary(), as.secondary());
            case MirInst.ArrayLen al -> java.util.Collections.singletonList(al.array());
            case MirInst.ForIterInit fi -> java.util.Collections.singletonList(fi.iterable());
            case MirInst.ForIterHasNext fh -> fh.target().kind() == LoweringTargets.ForKind.ARRAY
                    ? java.util.Collections.<MirVal>emptyList() : java.util.Collections.singletonList(fh.iterator());
            case MirInst.ForIterNext fnInst -> java.util.Arrays.asList(fnInst.iterator(), fnInst.index());
            case MirInst.CatchMatch cm -> java.util.Collections.singletonList(cm.value());
            default -> java.util.Collections.<MirVal>emptyList();
        };
        // Some operands are absent (the index of a loop over an iterator).
        return operands.stream().filter(java.util.Objects::nonNull).toList();
    }

    private static boolean isRematerializable(MirInst.Const c) {
        Object v = c.value();
        if (v == null || v instanceof String) {
            return c.result().kind() == PrimitiveKind.NONE;
        }
        PrimitiveKind pushed = constantKind(v);
        return pushed != null && pushed.isPrimitive() && pushed == c.result().kind();
    }

    /** The kind of what {@link #pushConstant} leaves on the stack for {@code v}, or null for no constant. */
    private static PrimitiveKind constantKind(Object v) {
        if (v == null || v instanceof String) {
            return PrimitiveKind.NONE;
        }
        return switch (v) {
            case Boolean ignored -> PrimitiveKind.BOOL;
            case Byte ignored -> PrimitiveKind.BYTE;
            case Short ignored -> PrimitiveKind.SHORT;
            case Integer ignored -> PrimitiveKind.INT;
            case Long ignored -> PrimitiveKind.LONG;
            case Float ignored -> PrimitiveKind.FLOAT;
            case Double ignored -> PrimitiveKind.DOUBLE;
            case Character ignored -> PrimitiveKind.CHAR;
            default -> null;
        };
    }

    /** Pushes a MIR constant, and returns the kind now on the stack. */
    static PrimitiveKind pushConstant(MethodVisitor mv, Object v) {
        if (v == null) {
            mv.visitInsn(ACONST_NULL);
        } else if (v instanceof Boolean b) {
            mv.visitInsn(b ? ICONST_1 : ICONST_0);
        } else if (v instanceof Byte b) {
            ConstantPool.pushInt(mv, b);
        } else if (v instanceof Short sh) {
            ConstantPool.pushInt(mv, sh);
        } else if (v instanceof Integer i) {
            ConstantPool.pushInt(mv, i);
        } else if (v instanceof Long l) {
            if (l == 0L) {
                mv.visitInsn(LCONST_0);
            } else if (l == 1L) {
                mv.visitInsn(LCONST_1);
            } else {
                mv.visitLdcInsn(l);
            }
        } else if (v instanceof Float f) {
            if (Float.floatToRawIntBits(f) == 0) {
                mv.visitInsn(FCONST_0);
            } else if (f == 1.0f) {
                mv.visitInsn(FCONST_1);
            } else if (f == 2.0f) {
                mv.visitInsn(FCONST_2);
            } else {
                mv.visitLdcInsn(f);
            }
        } else if (v instanceof Double d) {
            if (Double.doubleToRawLongBits(d) == 0L) {
                mv.visitInsn(DCONST_0);
            } else if (d == 1.0d) {
                mv.visitInsn(DCONST_1);
            } else {
                mv.visitLdcInsn(d);
            }
        } else if (v instanceof Character ch) {
            ConstantPool.pushInt(mv, ch);
        } else if (v instanceof String str) {
            ConstantPool.pushString(mv, str);
        } else {
            throw new IllegalStateException("Unsupported MIR constant : " + v.getClass());
        }
        PrimitiveKind kind = constantKind(v);
        return kind;
    }

    /** The largest value id the function defines or uses (ids are dense, from 0). */
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

    private MirVal resolveRoot(MirVal v) {
        MirVal cur = v;
        while (aliasTarget.containsKey(cur.id())) {
            cur = aliasTarget.get(cur.id());
        }
        return cur;
    }

    private int slotOf(MirVal v) {
        Integer slot = valSlots.get(resolveRoot(v).id());
        if (slot == null) {
            throw new IllegalStateException("No local for MIR value " + v + " in " + fn.name());
        }
        return slot;
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

    private void loadVal(MirVal v) {
        MirInst.Const c = rematerialized.get(resolveRoot(v).id());
        if (stacked.contains(resolveRoot(v).id())) {
            // Already on the stack : its definition was the previous instruction.
        } else if (c != null) {
            pushConstant(mv, c.value());
        } else {
            loadKindOn(mv, v.kind(), slotOf(v));
        }
        ctx.stackKind = v.kind();
    }

    private void storeResult(MirVal res) {
        if (discarded.contains(res.id())) {
            if (ctx.stackKind == PrimitiveKind.NONE && res.kind().isPrimitive()) {
                emitter.convertStack(ctx.stackKind, res.kind());
            }
            popOn(mv, ctx.stackKind);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        emitter.convertStack(ctx.stackKind, res.kind());
        if (stacked.contains(res.id())) {
            ctx.stackKind = res.kind();
            return;
        }
        storeKindOn(mv, res.kind(), slotOf(res));
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ------------------------------------------------------------------ instruction emission

    private void emitInst(MirInst inst) {
        switch (inst) {
            case MirInst.Const c -> emitConst(c);
            case MirInst.Copy c -> {
                loadVal(c.source());
                storeResult(c.result());
            }
            case MirInst.Narrow ignored -> {} // zero-cost alias
            case MirInst.Move ignored -> {}   // zero-cost alias
            case MirInst.Freeze ignored -> {} // zero-cost alias
            case MirInst.Unary u -> emitUnary(u);
            case MirInst.Binary b -> emitBinary(b);
            case MirInst.Widen w -> {
                loadVal(w.operand());
                emitter.widenStackRank(w.wideningRank());
                storeResult(w.result());
            }
            case MirInst.Convert c -> {
                loadVal(c.operand());
                emitter.convertStack(c.operand().kind(), c.targetKind());
                storeResult(c.result());
            }
            case MirInst.CheckCast cc -> emitCheckCast(cc);
            case MirInst.NonNull nn -> emitNonNull(nn);
            case MirInst.IsCheck ic -> emitIsCheck(ic);
            case MirInst.LoadField lf -> emitLoadField(lf);
            case MirInst.StoreField sf -> emitStoreField(sf);
            case MirInst.LoadJavaField lj -> emitLoadJavaField(lj);
            case MirInst.StoreJavaField sj -> emitStoreJavaField(sj);
            case MirInst.ArrayGet ag -> emitArrayGet(ag);
            case MirInst.ArraySet as -> emitArraySet(as);
            case MirInst.ArrayLen al -> emitArrayLen(al);
            case MirInst.CallBabble cb -> emitCallBabble(cb);
            case MirInst.CallMethod cm -> emitCallMethod(cm);
            case MirInst.CallInterface ci -> emitCallInterface(ci);
            case MirInst.CallJava cj -> emitCallJava(cj);
            case MirInst.CallJavaCtor cc -> emitCallJavaCtor(cc);
            case MirInst.RecordNew rn -> emitRecordNew(rn);
            case MirInst.RecordReuse rr -> emitRecordReuse(rr);
            case MirInst.RangeNew rn -> emitRangeNew(rn);
            case MirInst.SlotDecl sd -> emitSlotDecl(sd);
            case MirInst.SlotRead sr -> {
                emitter.loadVariable(sr.var(), sr.line());
                storeResult(sr.result());
            }
            case MirInst.SlotWrite sw -> {
                loadVal(sw.value());
                PrimitiveKind vk = sw.var().primitiveKind();
                emitter.convertStack(ctx.stackKind, vk);
                emitter.storeVariable(sw.var(), sw.opKind() == MirInst.SlotOpKind.INIT, vk, sw.line());
                popOn(mv, ctx.stackKind);
                ctx.stackKind = PrimitiveKind.NONE;
            }
            case MirInst.ScopeEnter se -> {
                mv.visitVarInsn(ALOAD, ctx.nsLocal);
                mv.visitVarInsn(ASTORE, slotOf(se.result()));
                mv.visitVarInsn(ALOAD, ctx.nsLocal);
                emitter.getStringArray(se.declaredNames());
                mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "child", "([Ljava/lang/String;)L" + NAMESPACE + ";", false);
                mv.visitVarInsn(ASTORE, ctx.nsLocal);
            }
            case MirInst.ScopeExit sx -> {
                mv.visitVarInsn(ALOAD, slotOf(sx.prevNamespace()));
                mv.visitVarInsn(ASTORE, ctx.nsLocal);
            }
            case MirInst.ForIterInit fi -> emitForIterInit(fi);
            case MirInst.ForIterHasNext fh -> emitForIterHasNext(fh);
            case MirInst.ForIterNext fnInst -> emitForIterNext(fnInst);
            case MirInst.UnwrapThrown ut -> {
                mv.visitVarInsn(ALOAD, slotOf(ut.thrownExn()));
                mv.visitMethodInsn(INVOKEVIRTUAL, RUNTIME + "ThrownException", "value", "()Ljava/lang/Object;", false);
                mv.visitVarInsn(ASTORE, slotOf(ut.result()));
            }
            case MirInst.CatchMatch cm -> emitCatchMatch(cm);
            case MirInst.AddSuppressed as -> {
                mv.visitVarInsn(ALOAD, slotOf(as.primary()));
                mv.visitVarInsn(ALOAD, slotOf(as.secondary()));
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Throwables", "addSuppressed",
                        "(Ljava/lang/Throwable;Ljava/lang/Throwable;)V", false);
            }
            case MirInst.NameRead nr -> emitNameRead(nr);
            case MirInst.NameAssign na -> emitNameAssign(na);
            case MirInst.LoadExport le -> emitLoadExport(le);
            case MirInst.OpaqueExpr op -> emitOpaque(op);
        }
    }

    private void emitConst(MirInst.Const c) {
        ctx.stackKind = pushConstant(mv, c.value());
        storeResult(c.result());
    }

    private void emitUnary(MirInst.Unary u) {
        if (u.op() == HirExpr.UnaryOp.NOT) {
            if (u.operand().kind() == PrimitiveKind.BOOL) {
                loadVal(u.operand());
                mv.visitInsn(ICONST_1);
                mv.visitInsn(IXOR);
                ctx.stackKind = PrimitiveKind.BOOL;
                storeResult(u.result());
                return;
            }
            Label isTrue = new Label();
            Label end = new Label();
            emitBranchOnTruth(u.operand(), false, isTrue);
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(isTrue);
            mv.visitInsn(ICONST_1);
            mv.visitLabel(end);
            ctx.stackKind = PrimitiveKind.BOOL;
            storeResult(u.result());
            return;
        }
        PrimitiveKind rk = u.result().kind();
        if (rk == PrimitiveKind.INT || rk == PrimitiveKind.LONG
                || rk == PrimitiveKind.FLOAT || rk == PrimitiveKind.DOUBLE) {
            loadVal(u.operand());
            emitter.convertStack(u.operand().kind(), rk);
            mv.visitInsn(switch (rk) {
                case INT -> INEG;
                case LONG -> LNEG;
                case FLOAT -> FNEG;
                case DOUBLE -> DNEG;
                default -> throw new IllegalStateException();
            });
            ctx.stackKind = rk;
            storeResult(u.result());
            return;
        }
        HirExpr opPh = placeholder(u.operand(), u.line());
        withOverrides(Map.of(opPh, operandOf(u.operand())), () ->
                emitter.controlOps.emitUnary(new HirExpr.Unary(u.op(), opPh, u.operandText(),
                        ExprMeta.of(u.line(), rk))));
        storeResult(u.result());
    }

    private void emitBinary(MirInst.Binary b) {
        PrimitiveKind lk = b.left().kind();
        PrimitiveKind rk = b.right().kind();
        PrimitiveKind out = b.result().kind();

        if (b.op().isComparison() && out == PrimitiveKind.BOOL && canEmitPrimitiveCmp(b.op(), lk, rk)) {
            emitPrimitiveComparison(b.op(), b.left(), b.right());
            storeResult(b.result());
            return;
        }
        if (b.op().isArithmetic() && isNumericKind(lk) && isNumericKind(rk)) {
            if ((out == PrimitiveKind.INT || out == PrimitiveKind.LONG)
                    && (b.op() == HirExpr.BinaryOp.ADD || b.op() == HirExpr.BinaryOp.SUB || b.op() == HirExpr.BinaryOp.MUL)) {
                loadVal(b.left());
                emitter.convertStack(lk, out);
                loadVal(b.right());
                emitter.convertStack(rk, out);
                int insn = out == PrimitiveKind.INT
                        ? switch (b.op()) { case ADD -> IADD; case SUB -> ISUB; case MUL -> IMUL; default -> throw new IllegalStateException(); }
                        : switch (b.op()) { case ADD -> LADD; case SUB -> LSUB; case MUL -> LMUL; default -> throw new IllegalStateException(); };
                mv.visitInsn(insn);
                ctx.stackKind = out;
                storeResult(b.result());
                return;
            }
            if ((out == PrimitiveKind.INT || out == PrimitiveKind.LONG)
                    && (b.op() == HirExpr.BinaryOp.MOD || b.op() == HirExpr.BinaryOp.FLOOR_DIV)) {
                MirInst.Const rConst = rematerialized.get(resolveRoot(b.right()).id());
                long posPow2 = 0L;
                if (rConst != null && rConst.value() instanceof Number rn) {
                    long v = out == PrimitiveKind.LONG ? rn.longValue() : rn.intValue();
                    if (v > 0L && (v & (v - 1L)) == 0L) {
                        posPow2 = v;
                    }
                }
                loadVal(b.left());
                emitter.convertStack(lk, out);
                if (posPow2 > 0L) {
                    if (b.op() == HirExpr.BinaryOp.MOD) {
                        if (out == PrimitiveKind.LONG) {
                            mv.visitLdcInsn(posPow2 - 1L);
                            mv.visitInsn(LAND);
                        } else {
                            ConstantPool.pushInt(mv, (int) (posPow2 - 1L));
                            mv.visitInsn(IAND);
                        }
                    } else {
                        int shift = Long.numberOfTrailingZeros(posPow2);
                        ConstantPool.pushInt(mv, shift);
                        mv.visitInsn(out == PrimitiveKind.LONG ? LSHR : ISHR);
                    }
                } else {
                    loadVal(b.right());
                    emitter.convertStack(rk, out);
                    ctx.line(b.line());
                    String mathMethod = b.op() == HirExpr.BinaryOp.MOD ? "floorMod" : "floorDiv";
                    String desc = out == PrimitiveKind.LONG ? "(JJ)J" : "(II)I";
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", mathMethod, desc, false);
                }
                ctx.stackKind = out;
                storeResult(b.result());
                return;
            }
            if (out == PrimitiveKind.FLOAT || out == PrimitiveKind.DOUBLE) {
                loadVal(b.left());
                emitter.convertStack(lk, out);
                loadVal(b.right());
                emitter.convertStack(rk, out);
                int insn = out == PrimitiveKind.FLOAT
                        ? switch (b.op()) { case ADD -> FADD; case SUB -> FSUB; case MUL -> FMUL; case DIV -> FDIV; default -> throw new IllegalStateException(); }
                        : switch (b.op()) { case ADD -> DADD; case SUB -> DSUB; case MUL -> DMUL; case DIV -> DDIV; default -> throw new IllegalStateException(); };
                mv.visitInsn(insn);
                ctx.stackKind = out;
                storeResult(b.result());
                return;
            }
        }

        // Dynamic / string / operator-method binary op delegated cleanly to ControlAndOpEmitter.
        HirExpr lPh = placeholder(b.left(), b.line());
        HirExpr rPh = placeholder(b.right(), b.line());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(2);
        ov.put(lPh, operandOf(b.left()));
        ov.put(rPh, operandOf(b.right()));
        withOverrides(ov, () -> emitter.controlOps.emitBinary(
                new HirExpr.Binary(b.op(), lPh, rPh, b.leftText(), b.rightText(), ExprMeta.of(b.line(), out))));
        storeResult(b.result());
    }

    private void emitRangeNew(MirInst.RangeNew rn) {
        HirExpr sPh = placeholder(rn.start(), rn.line());
        HirExpr ePh = placeholder(rn.end(), rn.line());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(2);
        ov.put(sPh, operandOf(rn.start()));
        ov.put(ePh, operandOf(rn.end()));
        withOverrides(ov, () -> emitter.controlOps.emitRange(
                new HirExpr.Range(sPh, ePh, rn.inclusive(), rn.elemKind(), false, ExprMeta.of(rn.line(), PrimitiveKind.NONE))));
        storeResult(rn.result());
    }

    private static boolean canEmitPrimitiveCmp(HirExpr.BinaryOp op, PrimitiveKind lk, PrimitiveKind rk) {
        if (lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL) {
            return op == HirExpr.BinaryOp.EQ || op == HirExpr.BinaryOp.NEQ;
        }
        if (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR) {
            return true;
        }
        return isNumericKind(lk) && isNumericKind(rk);
    }

    private void emitPrimitiveComparison(HirExpr.BinaryOp op, MirVal left, MirVal right) {
        Label isTrue = new Label();
        Label end = new Label();
        emitComparisonJump(op, left, right, true, isTrue);
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(isTrue);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(end);
        ctx.stackKind = PrimitiveKind.BOOL;
    }

    /** Jumps to {@code target} when {@code left op right} is {@code branchIfTrue}, with the ordering of the other modes. */
    private void emitComparisonJump(HirExpr.BinaryOp op, MirVal left, MirVal right, boolean branchIfTrue, Label target) {
        HirExpr.BinaryOp jumpOp = branchIfTrue ? op : negate(op);
        PrimitiveKind lk = left.kind();
        PrimitiveKind rk = right.kind();
        if ((lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL)
                || (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR)) {
            loadVal(left);
            loadVal(right);
            mv.visitJumpInsn(icmpJump(jumpOp), target);
            return;
        }
        PrimitiveKind common = numericOrder(lk) >= numericOrder(rk) ? lk : rk;
        loadVal(left);
        emitter.convertStack(lk, common);
        loadVal(right);
        emitter.convertStack(rk, common);
        if (numericOrder(common) <= 2) {
            mv.visitJumpInsn(icmpJump(jumpOp), target);
            return;
        }
        if (common == PrimitiveKind.LONG) {
            mv.visitInsn(LCMP);
        } else if (common == PrimitiveKind.FLOAT) {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
        }
        // A three-way result : its integer negation is exact, NaN included.
        mv.visitJumpInsn(switch (jumpOp) {
            case LT -> IFLT;
            case LTE -> IFLE;
            case EQ -> IFEQ;
            case NEQ -> IFNE;
            case GTE -> IFGE;
            case GT -> IFGT;
            default -> throw new IllegalStateException();
        }, target);
    }

    private static HirExpr.BinaryOp negate(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> HirExpr.BinaryOp.GTE;
            case LTE -> HirExpr.BinaryOp.GT;
            case EQ -> HirExpr.BinaryOp.NEQ;
            case NEQ -> HirExpr.BinaryOp.EQ;
            case GTE -> HirExpr.BinaryOp.LT;
            case GT -> HirExpr.BinaryOp.LTE;
            default -> throw new IllegalStateException();
        };
    }

    private static int icmpJump(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> IF_ICMPLT;
            case LTE -> IF_ICMPLE;
            case EQ -> IF_ICMPEQ;
            case NEQ -> IF_ICMPNE;
            case GTE -> IF_ICMPGE;
            case GT -> IF_ICMPGT;
            default -> throw new IllegalStateException();
        };
    }

    private void emitSlotDecl(MirInst.SlotDecl sd) {
        HirVar v = sd.var();
        PrimitiveKind vk = v.primitiveKind();
        int slot = ctx.slots.get(v.id());
        if (!v.cell() && vk.isPrimitive() && v.mayBeUndefined() && !v.definitelyDefined()) {
            Integer defSlot = ctx.definedSlots.get(v.id());
            if (defSlot != null) {
                mv.visitInsn(ICONST_0);
                mv.visitVarInsn(ISTORE, defSlot);
            }
        }
        if (v.cell()) {
            String cc = cellClass(vk);
            mv.visitMethodInsn(INVOKESTATIC, cc, "undefined", "()L" + cc + ";", false);
            mv.visitVarInsn(ASTORE, slot);
        } else if (vk.isPrimitive()) {
            emitDefaultValueOn(mv, vk);
            storeKindOn(mv, vk, slot);
        } else {
            mv.visitFieldInsn(GETSTATIC, RUNTIME + "Undefined", "UNDEFINED", "L" + RUNTIME + "Undefined;");
            mv.visitVarInsn(ASTORE, slot);
        }
    }

    // ------------------------------------------------------------------ delegated ABI instructions

    private void emitCheckCast(MirInst.CheckCast cc) {
        HirExpr ph = placeholder(cc.operand(), cc.line());
        withOverrides(Map.of(ph, operandOf(cc.operand())), () -> emitter.controlOps.emitAsExpr(
                new HirExpr.As(ph, cc.target(), cc.samTarget(), cc.descriptor(), ExprMeta.of(cc.line(), cc.result().kind()))));
        storeResult(cc.result());
    }

    private void emitNonNull(MirInst.NonNull nn) {
        HirExpr ph = placeholder(nn.operand(), nn.line());
        withOverrides(Map.of(ph, operandOf(nn.operand())), () -> emitter.controlOps.emitNonNull(
                new HirExpr.NonNull(ph, nn.expectedType(), ExprMeta.of(nn.line(), nn.result().kind()))));
        storeResult(nn.result());
    }

    private void emitIsCheck(MirInst.IsCheck ic) {
        HirExpr ph = placeholder(ic.operand(), ic.line());
        withOverrides(Map.of(ph, operandOf(ic.operand())), () -> emitter.controlOps.emitIs(
                new HirExpr.Is(ph, ic.builtin(), ic.target(), ic.intersectionTargets(), ic.descriptor(),
                        ExprMeta.of(ic.line(), ic.result().kind()))));
        storeResult(ic.result());
    }

    private void emitLoadField(MirInst.LoadField lf) {
        HirExpr ph = placeholder(lf.receiver(), lf.line());
        withOverrides(Map.of(ph, operandOf(lf.receiver())), () -> emitter.assigns.emitThisFieldGet(
                new HirExpr.ThisFieldGet(ph, lf.receiverText(), lf.classId(), lf.externalClassJvmName(),
                        lf.memberIndex(), lf.memberName(), lf.fieldKind(), lf.immutableField(), lf.definitelyDefined(),
                        ExprMeta.of(lf.line(), lf.result().kind()))));
        storeResult(lf.result());
    }

    private void emitNameRead(MirInst.NameRead nr) {
        // Final and nothrow : the HIR read was final and definitely defined.
        emitter.loadName(nr.name(), nr.finalBinding() && nr.effect().nothrow(), nr.line());
        storeResult(nr.result());
    }

    private void emitNameAssign(MirInst.NameAssign na) {
        HirExpr ph = placeholder(na.value(), na.line());
        ExprMeta meta = new ExprMeta(na.line(), PrimitiveKind.NONE, na.wideningRank(), null,
                org.babblelang.analysis.EffectSet.EMPTY, false, false);
        withOverrides(Map.of(ph, operandOf(na.value())), () -> emitter.assigns.emitNameAssign(
                new HirExpr.NameAssign(na.name(), na.operator(), ph, na.targetText(), na.valueText(), meta)));
        storeResult(na.result());
    }

    private void emitStoreField(MirInst.StoreField sf) {
        HirExpr rPh = placeholder(sf.receiver(), sf.line());
        HirExpr vPh = placeholder(sf.value(), sf.line());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(2);
        ov.put(rPh, operandOf(sf.receiver()));
        ov.put(vPh, operandOf(sf.value()));
        withOverrides(ov, () -> emitter.assigns.emitThisFieldAssign(
                new HirExpr.ThisFieldAssign(rPh, sf.classId(), sf.memberIndex(), sf.memberName(), null, vPh,
                        sf.targetText(), sf.valueText(), ExprMeta.of(sf.line(), sf.value().kind()))));
        popOn(mv, ctx.stackKind);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitLoadJavaField(MirInst.LoadJavaField lj) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1);
        HirExpr rPh = null;
        if (lj.receiver() != null) {
            rPh = placeholder(lj.receiver(), lj.line());
            ov.put(rPh, operandOf(lj.receiver()));
        }
        HirExpr finalR = rPh;
        withOverrides(ov, () -> emitter.assigns.emitJavaFieldGet(
                new HirExpr.JavaFieldGet(finalR, lj.receiverText(), lj.pureStaticReceiver(), lj.target(),
                        ExprMeta.of(lj.line(), lj.result().kind()))));
        storeResult(lj.result());
    }

    private void emitStoreJavaField(MirInst.StoreJavaField sj) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(2);
        HirExpr rPh = null;
        if (sj.receiver() != null) {
            rPh = placeholder(sj.receiver(), sj.line());
            ov.put(rPh, operandOf(sj.receiver()));
        }
        HirExpr vPh = placeholder(sj.value(), sj.line());
        ov.put(vPh, operandOf(sj.value()));
        HirExpr finalR = rPh;
        withOverrides(ov, () -> emitter.assigns.emitJavaFieldAssignExpr(
                new HirExpr.JavaFieldAssign(finalR, sj.receiverText(), sj.pureStaticReceiver(), sj.target(), null,
                        vPh, sj.targetText(), sj.valueText(), ExprMeta.of(sj.line(), sj.value().kind()))));
        popOn(mv, ctx.stackKind);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitArrayGet(MirInst.ArrayGet ag) {
        HirExpr aPh = placeholder(ag.array(), ag.line());
        HirExpr iPh = placeholder(ag.index(), ag.line());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(2);
        ov.put(aPh, operandOf(ag.array()));
        ov.put(iPh, operandOf(ag.index()));
        withOverrides(ov, () -> emitter.assigns.emitArrayOp(
                new HirExpr.ArrayOp(LoweringTargets.ArrayOpKind.GET, aPh, ag.receiverText(), ag.elemKind(),
                        ag.arrayClass(), ag.componentClass(), List.of(iPh), ExprMeta.of(ag.line(), ag.result().kind()))));
        storeResult(ag.result());
    }

    private void emitArraySet(MirInst.ArraySet as) {
        HirExpr aPh = placeholder(as.array(), as.line());
        HirExpr iPh = placeholder(as.index(), as.line());
        HirExpr vPh = placeholder(as.value(), as.line());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(3);
        ov.put(aPh, operandOf(as.array()));
        ov.put(iPh, operandOf(as.index()));
        ov.put(vPh, operandOf(as.value()));
        withOverrides(ov, () -> emitter.assigns.emitArrayOp(
                new HirExpr.ArrayOp(LoweringTargets.ArrayOpKind.SET, aPh, as.receiverText(), as.elemKind(),
                        as.arrayClass(), as.componentClass(), List.of(iPh, vPh), ExprMeta.of(as.line(), PrimitiveKind.NONE))));
        popOn(mv, ctx.stackKind);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitArrayLen(MirInst.ArrayLen al) {
        if (al.receiverText() == null) {
            // Array length inside typed for-loop where array is already checked & unwrapped.
            mv.visitVarInsn(ALOAD, slotOf(al.array()));
            mv.visitInsn(ARRAYLENGTH);
            ctx.stackKind = PrimitiveKind.INT;
            storeResult(al.result());
            return;
        }
        HirExpr aPh = placeholder(al.array(), al.line());
        withOverrides(Map.of(aPh, operandOf(al.array())), () -> emitter.assigns.emitArrayOp(
                new HirExpr.ArrayOp(LoweringTargets.ArrayOpKind.SIZE, aPh, al.receiverText(), al.elemKind(),
                        al.arrayClass(), al.componentClass(), List.of(), ExprMeta.of(al.line(), PrimitiveKind.INT))));
        storeResult(al.result());
    }

    private void emitLoadExport(MirInst.LoadExport le) {
        loadVal(le.module());
        mv.visitTypeInsn(CHECKCAST, RUNTIME + "Module");
        ConstantPool.pushInt(mv, le.target().index());
        ctx.line(le.line());
        mv.visitMethodInsn(INVOKEVIRTUAL, RUNTIME + "Module", "exportAt", "(I)Ljava/lang/Object;", false);
        ctx.stackKind = PrimitiveKind.NONE;
        storeResult(le.result());
    }

    private void emitCallBabble(MirInst.CallBabble cb) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cb.args().size());
        List<HirExpr> argPhs = makePlaceholders(cb.args(), cb.line(), ov);
        if (cb.selfRecurse()) {
            withOverrides(ov, () -> emitter.calls.emitSelfRecurseCall(
                    new HirExpr.SelfRecurseCall(cb.targetFunctionId(), argPhs, cb.binding(),
                            ExprMeta.of(cb.line(), cb.result().kind()))));
        } else {
            HirExpr cPh = placeholder(cb.callee(), cb.line());
            ov.put(cPh, operandOf(cb.callee()));
            withOverrides(ov, () -> emitter.calls.emitDirectBabbleCall(
                    new HirExpr.DirectBabbleCall(cPh, cb.targetFunctionId(), cb.externalTarget(), argPhs, cb.binding(),
                            ExprMeta.of(cb.line(), cb.result().kind()))));
        }
        storeResult(cb.result());
    }

    private void emitCallMethod(MirInst.CallMethod cm) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cm.args().size());
        HirExpr rPh = placeholder(cm.receiver(), cm.line());
        ov.put(rPh, operandOf(cm.receiver()));
        List<HirCallArg> callArgs = new ArrayList<>(cm.args().size());
        for (int i = 0; i < cm.args().size(); i++) {
            MirVal a = cm.args().get(i);
            HirExpr ph = placeholder(a, cm.line());
            ov.put(ph, operandOf(a));
            callArgs.add(new HirCallArg(cm.argNames().get(i), ph));
        }
        withOverrides(ov, () -> emitter.calls.emitDirectThisMethodCall(
                new HirExpr.DirectThisMethodCall(rPh, cm.receiverText(), cm.classId(), cm.externalClassJvmName(),
                        cm.classTypeId(), cm.memberName(), cm.overloadAltIndex(), cm.targetFunctionId(),
                        cm.externalSignature(), callArgs, cm.binding(), cm.callSite(),
                        ExprMeta.of(cm.line(), cm.result().kind()))));
        storeResult(cm.result());
    }

    private void emitCallInterface(MirInst.CallInterface ci) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + ci.args().size());
        HirExpr rPh = placeholder(ci.receiver(), ci.line());
        ov.put(rPh, operandOf(ci.receiver()));
        List<HirExpr> argPhs = makePlaceholders(ci.args(), ci.line(), ov);
        withOverrides(ov, () -> emitter.calls.emitInterfaceCall(
                new HirExpr.InterfaceCall(rPh, ci.receiverText(), ci.selectorLine(), ci.interfaceTypeId(),
                        ci.memberName(), ci.signature(), argPhs, ci.binding(), ExprMeta.of(ci.line(), ci.result().kind()))));
        storeResult(ci.result());
    }

    private void emitCallJava(MirInst.CallJava cj) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cj.args().size());
        HirExpr rPh = null;
        if (cj.receiver() != null) {
            rPh = placeholder(cj.receiver(), cj.line());
            ov.put(rPh, operandOf(cj.receiver()));
        }
        List<HirExpr> argPhs = makePlaceholders(cj.args(), cj.line(), ov);
        HirExpr finalR = rPh;
        withOverrides(ov, () -> emitter.calls.emitJavaMethodCall(
                new HirExpr.JavaMethodCall(finalR, cj.receiverText(), cj.selectorLine(), cj.pureStaticReceiver(),
                        cj.target(), argPhs, ExprMeta.of(cj.line(), cj.result().kind()))));
        storeResult(cj.result());
    }

    private void emitCallJavaCtor(MirInst.CallJavaCtor cc) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cc.args().size());
        HirExpr cPh = null;
        if (cc.callee() != null) {
            cPh = placeholder(cc.callee(), cc.line());
            ov.put(cPh, operandOf(cc.callee()));
        }
        List<HirExpr> argPhs = makePlaceholders(cc.args(), cc.line(), ov);
        HirExpr finalC = cPh;
        withOverrides(ov, () -> emitter.calls.emitJavaConstructorCall(
                new HirExpr.JavaConstructorCall(finalC, cc.calleeText(), cc.pureStaticCallee(), cc.target(),
                        argPhs, ExprMeta.of(cc.line(), cc.result().kind()))));
        storeResult(cc.result());
    }

    private void emitRecordNew(MirInst.RecordNew rn) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + rn.args().size());
        HirExpr crPh = placeholder(rn.classRef(), rn.line());
        ov.put(crPh, operandOf(rn.classRef()));
        List<HirExpr> argPhs = makePlaceholders(rn.args(), rn.line(), ov);
        withOverrides(ov, () -> emitter.calls.emitRecordNew(
                new HirExpr.RecordNew(rn.classId(), rn.classTypeId(), rn.classJvmName(), crPh,
                        rn.classRefIsInstance(), rn.paramKinds(), argPhs, rn.binding(),
                        ExprMeta.of(rn.line(), PrimitiveKind.NONE))));
        storeResult(rn.result());
    }

    private void emitRecordReuse(MirInst.RecordReuse rr) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + rr.args().size());
        HirExpr tPh = placeholder(rr.target(), rr.line());
        ov.put(tPh, operandOf(rr.target()));
        List<HirExpr> argPhs = makePlaceholders(rr.args(), rr.line(), ov);
        withOverrides(ov, () -> emitter.calls.emitRecordReuse(
                new HirExpr.RecordReuse(tPh, rr.classId(), rr.classTypeId(), rr.classJvmName(),
                        rr.paramKinds(), argPhs, ExprMeta.of(rr.line(), PrimitiveKind.NONE))));
        storeResult(rr.result());
    }

    private void emitOpaque(MirInst.OpaqueExpr op) {
        List<HirExpr> children = MirLowerer.opaqueChildExprs(op.template());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            ov.put(children.get(i), operandOf(op.operands().get(i)));
        }
        withOverrides(ov, () -> emitter.emitExpr(op.template()));
        storeResult(op.result());
    }

    // ------------------------------------------------------------------ for-loop & try-catch instructions

    private void emitForIterInit(MirInst.ForIterInit fi) {
        int ln = fi.line();
        LoweringTargets.ForLoopTarget target = fi.target();
        LoweringTargets.ForKind kind = target.kind();
        int recvSlot = slotOf(fi.iterable());
        switch (kind) {
            case ARRAY -> {
                String arrDesc = Type.getDescriptor(target.arrayClass());
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, arrDesc);
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case JAVA_ITERABLE -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
                ctx.line(ln);
                mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case JAVA_ITERATOR -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case BABBLE_TO_JAVA_ITERABLE -> {
                emitter.controlOps.emitZeroArgBabbleCall(recvSlot, "iterator", null, ln);
                emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case BABBLE_ITERABLE -> {
                emitter.controlOps.emitZeroArgBabbleCall(recvSlot, "iterator", null, ln);
                emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case BABBLE_ITERATOR -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case DYNAMIC_UNION -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                ctx.line(ln);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "asIterator", "(Ljava/lang/Object;)Ljava/util/Iterator;", false);
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitForIterHasNext(MirInst.ForIterHasNext fh) {
        int ln = fh.line();
        LoweringTargets.ForLoopTarget target = fh.target();
        LoweringTargets.ForKind kind = target.kind();
        if (kind == LoweringTargets.ForKind.ARRAY) {
            emitPrimitiveComparison(HirExpr.BinaryOp.LT, fh.index(), fh.length());
            storeResult(fh.result());
            return;
        }
        if (kind == LoweringTargets.ForKind.BABBLE_ITERABLE || kind == LoweringTargets.ForKind.BABBLE_ITERATOR) {
            emitter.controlOps.emitZeroArgBabbleCall(slotOf(fh.iterator()), "hasNext", target.hasNextSig(), ln);
            if (ctx.stackKind != PrimitiveKind.BOOL) {
                emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
                ctx.stackKind = PrimitiveKind.BOOL;
            }
            storeResult(fh.result());
            return;
        }
        mv.visitVarInsn(ALOAD, slotOf(fh.iterator()));
        ctx.line(ln);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        ctx.stackKind = PrimitiveKind.BOOL;
        storeResult(fh.result());
    }

    private void emitForIterNext(MirInst.ForIterNext fnInst) {
        int ln = fnInst.line();
        LoweringTargets.ForLoopTarget target = fnInst.target();
        LoweringTargets.ForKind kind = target.kind();
        if (kind == LoweringTargets.ForKind.ARRAY) {
            Class<?> comp = target.arrayClass().getComponentType();
            PrimitiveKind elemKind = javaPrimitiveKind(comp);
            mv.visitVarInsn(ALOAD, slotOf(fnInst.iterator()));
            mv.visitVarInsn(ILOAD, slotOf(fnInst.index()));
            if (elemKind != PrimitiveKind.NONE) {
                mv.visitInsn(switch (elemKind) {
                    case BYTE, BOOL -> BALOAD;
                    case SHORT -> SALOAD;
                    case INT -> IALOAD;
                    case LONG -> LALOAD;
                    case FLOAT -> FALOAD;
                    case DOUBLE -> DALOAD;
                    case CHAR -> CALOAD;
                    case NONE -> AALOAD;
                });
                ctx.stackKind = elemKind;
            } else {
                mv.visitInsn(AALOAD);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                ctx.stackKind = PrimitiveKind.NONE;
            }
            storeResult(fnInst.result());
            return;
        }
        if (kind == LoweringTargets.ForKind.BABBLE_ITERABLE || kind == LoweringTargets.ForKind.BABBLE_ITERATOR) {
            emitter.controlOps.emitZeroArgBabbleCall(slotOf(fnInst.iterator()), "next", target.nextSig(), ln);
            storeResult(fnInst.result());
            return;
        }
        mv.visitVarInsn(ALOAD, slotOf(fnInst.iterator()));
        ctx.line(ln);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        ctx.stackKind = PrimitiveKind.NONE;
        storeResult(fnInst.result());
    }

    private void emitCatchMatch(MirInst.CatchMatch cm) {
        LoweringTargets.CatchTarget ct = cm.target();
        int valSlot = slotOf(cm.value());
        List<String> directJvms = emitter.controlOps.resolveBabbleCatchJvms(ct.babbleTargets());
        if (!directJvms.isEmpty()) {
            if (directJvms.size() == 1) {
                mv.visitVarInsn(ALOAD, valSlot);
                mv.visitTypeInsn(INSTANCEOF, directJvms.get(0));
            } else {
                Label matched = new Label();
                Label done = new Label();
                for (String jvm : directJvms) {
                    mv.visitVarInsn(ALOAD, valSlot);
                    mv.visitTypeInsn(INSTANCEOF, jvm);
                    mv.visitJumpInsn(IFNE, matched);
                }
                mv.visitInsn(ICONST_0);
                mv.visitJumpInsn(GOTO, done);
                mv.visitLabel(matched);
                mv.visitInsn(ICONST_1);
                mv.visitLabel(done);
            }
        } else if (ct.typeDescriptor() != null) {
            mv.visitVarInsn(ALOAD, valSlot);
            ConstantPool.pushString(mv, ct.typeDescriptor());
            ctx.line(cm.line());
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Types", "matches", "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
        } else {
            mv.visitInsn(ICONST_0);
        }
        ctx.stackKind = PrimitiveKind.BOOL;
        storeResult(cm.result());
    }

    // ------------------------------------------------------------------ terminators & parallel copy

    private boolean emitTerminator(MirTerminator term, MirBlock nextBlock) {
        switch (term) {
            case MirTerminator.Br br -> {
                boolean copied = hasNonTrivialCopies(br.args(), blockById.get(br.targetBlock()).params());
                emitParallelCopy(br.args(), blockById.get(br.targetBlock()).params());
                if (nextBlock == null || nextBlock.id() != br.targetBlock()) {
                    mv.visitJumpInsn(GOTO, blockLabels.get(br.targetBlock()));
                    return true;
                }
                return copied;
            }
            case MirTerminator.BrIf bi -> {
                MirBlock thenTarget = blockById.get(bi.thenBlock());
                MirBlock elseTarget = blockById.get(bi.elseBlock());
                boolean thenHasCopies = hasNonTrivialCopies(bi.thenArgs(), thenTarget.params());
                boolean elseHasCopies = hasNonTrivialCopies(bi.elseArgs(), elseTarget.params());
                if (!thenHasCopies && !elseHasCopies) {
                    if (nextBlock != null && nextBlock.id() == bi.thenBlock()) {
                        emitBranchOnTruth(bi.cond(), false, blockLabels.get(bi.elseBlock()));
                    } else if (nextBlock != null && nextBlock.id() == bi.elseBlock()) {
                        emitBranchOnTruth(bi.cond(), true, blockLabels.get(bi.thenBlock()));
                    } else {
                        emitBranchOnTruth(bi.cond(), true, blockLabels.get(bi.thenBlock()));
                        mv.visitJumpInsn(GOTO, blockLabels.get(bi.elseBlock()));
                    }
                } else {
                    Label thenJump = thenHasCopies ? new Label() : blockLabels.get(bi.thenBlock());
                    emitBranchOnTruth(bi.cond(), true, thenJump);
                    // Inline else-edge copy + jump to elseBlock.
                    emitParallelCopy(bi.elseArgs(), elseTarget.params());
                    if (nextBlock == null || nextBlock.id() != bi.elseBlock()) {
                        mv.visitJumpInsn(GOTO, blockLabels.get(bi.elseBlock()));
                    }
                    if (thenHasCopies) {
                        edgeTrampolines.add(() -> {
                            mv.visitLabel(thenJump);
                            emitParallelCopy(bi.thenArgs(), thenTarget.params());
                            mv.visitJumpInsn(GOTO, blockLabels.get(bi.thenBlock()));
                        });
                    }
                }
                return true;
            }
            case MirTerminator.Ret ret -> {
                loadVal(ret.value());
                emitter.widenStackRank(ret.wideningRank());
                emitter.convertStack(ctx.stackKind, methodReturnKind);
                mv.visitInsn(returnInsn(methodReturnKind));
                return true;
            }
            case MirTerminator.Throw th -> {
                loadVal(th.value());
                emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
                ctx.line(th.line());
                if (th.isJavaThrowable()) {
                    mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Throwable");
                } else {
                    mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Throwables", "toThrowable",
                            "(Ljava/lang/Object;)Ljava/lang/Throwable;", false);
                }
                mv.visitInsn(ATHROW);
                return true;
            }
            case MirTerminator.TailCall tc -> {
                Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(tc.args().size());
                List<HirCallArg> callArgs = new ArrayList<>(tc.args().size());
                for (int i = 0; i < tc.args().size(); i++) {
                    MirVal a = tc.args().get(i);
                    HirExpr ph = placeholder(a, tc.line());
                    ov.put(ph, operandOf(a));
                    callArgs.add(new HirCallArg(tc.argNames().get(i), ph));
                }
                withOverrides(ov, () -> emitter.calls.emitSelfTailCall(
                        new HirExpr.SelfTailCall(tc.functionId(), callArgs, tc.binding(),
                                ExprMeta.of(tc.line(), PrimitiveKind.NONE))));
                return true;
            }
            case MirTerminator.Unreachable ignored -> {
                mv.visitInsn(ACONST_NULL);
                mv.visitInsn(ATHROW);
                return true;
            }
        }
    }

    private void emitBranchOnTruth(MirVal cond, boolean branchIfTrue, Label target) {
        MirInst fused = fusedConditions.get(cond.id());
        if (fused instanceof MirInst.Unary not) {
            emitBranchOnTruth(not.operand(), !branchIfTrue, target);
            return;
        }
        if (fused instanceof MirInst.Binary cmp) {
            emitComparisonJump(cmp.op(), cmp.left(), cmp.right(), branchIfTrue, target);
            return;
        }
        loadVal(cond);
        switch (cond.kind()) {
            case BOOL, BYTE, SHORT, INT, CHAR -> mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            case LONG -> {
                mv.visitInsn(LCONST_0);
                mv.visitInsn(LCMP);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
            case FLOAT -> {
                mv.visitInsn(FCONST_0);
                mv.visitInsn(FCMPL);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
            case DOUBLE -> {
                mv.visitInsn(DCONST_0);
                mv.visitInsn(DCMPL);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
            case NONE -> {
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
        }
    }

    private boolean hasNonTrivialCopies(List<MirVal> args, List<MirVal> params) {
        for (int i = 0; i < args.size(); i++) {
            int rootId = resolveRoot(args.get(i)).id();
            if (stacked.contains(rootId) || rematerialized.containsKey(rootId)
                    || slotOf(args.get(i)) != slotOf(params.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cycle-safe parallel copy of edge {@code args} into target block {@code params}.
     */
    private void emitParallelCopy(List<MirVal> args, List<MirVal> params) {
        final class MoveOp {
            int src;
            final int dst;
            final PrimitiveKind kind;

            MoveOp(int src, int dst, PrimitiveKind kind) {
                this.src = src;
                this.dst = dst;
                this.kind = kind;
            }
        }
        List<MoveOp> pending = new ArrayList<>();
        // Stacked and constant edge args read no local : written after pending local moves,
        // they cannot clobber the source of any pending move.
        int stackedMove = -1;
        List<Integer> constantMoves = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            int rootId = resolveRoot(args.get(i)).id();
            if (stacked.contains(rootId)) {
                stackedMove = i;
                continue;
            }
            if (rematerialized.containsKey(rootId)) {
                constantMoves.add(i);
                continue;
            }
            int src = slotOf(args.get(i));
            int dst = slotOf(params.get(i));
            if (src != dst) {
                pending.add(new MoveOp(src, dst, params.get(i).kind()));
            }
        }
        while (!pending.isEmpty()) {
            MoveOp ready = null;
            for (MoveOp m : pending) {
                boolean dstUsedAsSrc = false;
                for (MoveOp other : pending) {
                    if (other != m && other.src == m.dst) {
                        dstUsedAsSrc = true;
                        break;
                    }
                }
                if (!dstUsedAsSrc) {
                    ready = m;
                    break;
                }
            }
            if (ready != null) {
                loadKindOn(mv, ready.kind, ready.src);
                storeKindOn(mv, ready.kind, ready.dst);
                pending.remove(ready);
            } else {
                // Break cycle by spilling the first move's source into a fresh temporary of matching kind.
                MoveOp cyc = pending.get(0);
                int tmp = ctx.newLocal(cyc.kind);
                loadKindOn(mv, cyc.kind, cyc.src);
                storeKindOn(mv, cyc.kind, tmp);
                cyc.src = tmp;
            }
        }
        if (stackedMove >= 0) {
            MirVal param = params.get(stackedMove);
            storeKindOn(mv, param.kind(), slotOf(param));
            ctx.stackKind = PrimitiveKind.NONE;
        }
        for (int i : constantMoves) {
            MirVal param = params.get(i);
            loadVal(args.get(i));
            emitter.convertStack(ctx.stackKind, param.kind());
            storeKindOn(mv, param.kind(), slotOf(param));
            ctx.stackKind = PrimitiveKind.NONE;
        }
    }

    // ------------------------------------------------------------------ mirOverrides helpers

    private HirExpr placeholder(MirVal v, int line) {
        return new HirExpr.Const(null, ExprMeta.of(line, v.kind()));
    }

    private MethodEmitContext.MirOperand operandOf(MirVal v) {
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

    private List<HirExpr> makePlaceholders(List<MirVal> vals, int line, Map<HirExpr, MethodEmitContext.MirOperand> ov) {
        List<HirExpr> out = new ArrayList<>(vals.size());
        for (MirVal v : vals) {
            HirExpr ph = placeholder(v, line);
            ov.put(ph, operandOf(v));
            out.add(ph);
        }
        return out;
    }

    private void withOverrides(Map<HirExpr, MethodEmitContext.MirOperand> overrides, Runnable body) {
        Map<HirExpr, MethodEmitContext.MirOperand> prev = ctx.mirOverrides;
        ctx.mirOverrides = overrides;
        try {
            body.run();
        } finally {
            ctx.mirOverrides = prev;
        }
    }
}
