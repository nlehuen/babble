package org.babblelang.ir.mir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirVar;

/**
 * Braun-style SSA basic-block CFG builder shared by {@link MirLowerer} and its control-flow /
 * pattern-matching collaborators. Owns value/block numbering, variable pinning slots, alias
 * metadata, handler stacks, block sealing, and trivial block-parameter elimination.
 */
final class MirSsaBuilder {
    record EdgeRef(BlockBuilder from, List<MirVal> args) {}

    record IncompletePhi(int varId, int paramIndex, MirVal param) {}

    sealed interface PendingTerm {
        record Br(BlockBuilder target, EdgeRef edge, int line) implements PendingTerm {}

        record BrIf(
                MirVal cond,
                BlockBuilder thenTarget,
                EdgeRef thenEdge,
                BlockBuilder elseTarget,
                EdgeRef elseEdge,
                int line)
                implements PendingTerm {}

        record Switch(
                MirVal key,
                int[] keys,
                BlockBuilder[] targets,
                EdgeRef[] edges,
                BlockBuilder defaultTarget,
                EdgeRef defaultEdge,
                int line)
                implements PendingTerm {}

        record Fixed(MirTerminator term) implements PendingTerm {}
    }

    static final class BlockBuilder {
        final int id;
        final List<MirVal> params = new ArrayList<>();
        final List<MirInst> insts = new ArrayList<>();
        final List<MirHandler> handlers;
        final boolean handlerEntry;
        final BlockBuilder handlerDominator;
        final int explicitParamCount;
        final List<EdgeRef> incomingEdges = new ArrayList<>();
        final Map<Integer, MirVal> currentDef = new HashMap<>();
        final List<IncompletePhi> incompletePhis = new ArrayList<>();
        boolean sealed;
        PendingTerm term;

        BlockBuilder(
                int id,
                List<MirVal> explicitParams,
                List<MirHandler> handlers,
                boolean handlerEntry,
                BlockBuilder handlerDominator,
                boolean sealed) {
            this.id = id;
            this.params.addAll(explicitParams);
            this.explicitParamCount = explicitParams.size();
            this.handlers = List.copyOf(handlers);
            this.handlerEntry = handlerEntry;
            this.handlerDominator = handlerDominator;
            this.sealed = sealed;
        }

        boolean isTerminated() {
            return term != null;
        }
    }

    final HirFunction fn;
    final Set<Integer> pinnedVarIds;
    final Map<Integer, HirVar> pinnedVarsById;
    final Map<Integer, HirVar> varById;
    final Map<Integer, MirEffect.AliasClass> valAliases = new HashMap<>();
    final List<BlockBuilder> allBlocks = new ArrayList<>();
    final Deque<List<MirHandler>> handlerStack = new ArrayDeque<>();

    private int nextValId = 0;
    private int nextBlockId = 0;
    BlockBuilder cur;

    MirSsaBuilder(HirFunction fn, MirVarPinningScanner.Result scan) {
        this.fn = fn;
        this.pinnedVarIds = scan.pinnedVarIds();
        this.pinnedVarsById = scan.pinnedVarsById();
        this.varById = scan.varById();
        this.handlerStack.push(List.of());
    }

    int nextValId() {
        return nextValId;
    }

    int nextBlockId() {
        return nextBlockId;
    }

    MirVal newVal(PrimitiveKind kind, String hint) {
        return new MirVal(nextValId++, kind != null ? kind : PrimitiveKind.NONE, hint);
    }

    BlockBuilder newBlock(List<MirVal> explicitParams, boolean handlerEntry, boolean sealed) {
        BlockBuilder b =
                new BlockBuilder(
                        nextBlockId++,
                        explicitParams,
                        handlerStack.peek(),
                        handlerEntry,
                        null,
                        sealed);
        allBlocks.add(b);
        return b;
    }

    BlockBuilder newBlockWithHandlers(
            List<MirVal> explicitParams,
            List<MirHandler> handlers,
            boolean handlerEntry,
            boolean sealed) {
        return newBlockWithHandlers(explicitParams, handlers, handlerEntry, null, sealed);
    }

    BlockBuilder newBlockWithHandlers(
            List<MirVal> explicitParams,
            List<MirHandler> handlers,
            boolean handlerEntry,
            BlockBuilder handlerDominator,
            boolean sealed) {
        BlockBuilder b =
                new BlockBuilder(
                        nextBlockId++,
                        explicitParams,
                        handlers,
                        handlerEntry,
                        handlerDominator,
                        sealed);
        allBlocks.add(b);
        return b;
    }

    void emit(MirInst inst) {
        if (!cur.isTerminated()) {
            cur.insts.add(inst);
        }
    }

    void terminateBr(BlockBuilder target, List<MirVal> explicitArgs, int line) {
        if (cur.isTerminated()) {
            return;
        }
        EdgeRef edge = createEdge(cur, target, explicitArgs);
        cur.term = new PendingTerm.Br(target, edge, line);
    }

    void terminateBrIf(
            MirVal cond,
            BlockBuilder thenTarget,
            List<MirVal> thenArgs,
            BlockBuilder elseTarget,
            List<MirVal> elseArgs,
            int line) {
        if (cur.isTerminated()) {
            return;
        }
        EdgeRef thenEdge = createEdge(cur, thenTarget, thenArgs);
        EdgeRef elseEdge = createEdge(cur, elseTarget, elseArgs);
        cur.term = new PendingTerm.BrIf(cond, thenTarget, thenEdge, elseTarget, elseEdge, line);
    }

    void terminateSwitch(
            MirVal key, int[] keys, BlockBuilder[] targets, BlockBuilder defaultTarget, int line) {
        if (cur.isTerminated()) {
            return;
        }
        EdgeRef[] edges = new EdgeRef[targets.length];
        for (int i = 0; i < targets.length; i++) {
            edges[i] = createEdge(cur, targets[i], List.of());
        }
        EdgeRef defEdge = createEdge(cur, defaultTarget, List.of());
        cur.term = new PendingTerm.Switch(key, keys, targets, edges, defaultTarget, defEdge, line);
    }

    void terminateFixed(MirTerminator term) {
        if (!cur.isTerminated()) {
            cur.term = new PendingTerm.Fixed(term);
        }
    }

    private EdgeRef createEdge(BlockBuilder from, BlockBuilder target, List<MirVal> explicitArgs) {
        if (explicitArgs.size() != target.explicitParamCount) {
            throw new IllegalStateException(
                    "Explicit arg count mismatch : "
                            + explicitArgs.size()
                            + " vs "
                            + target.explicitParamCount
                            + " for bb"
                            + target.id);
        }
        List<MirVal> padded = new ArrayList<>(target.params.size());
        padded.addAll(explicitArgs);
        while (padded.size() < target.params.size()) {
            padded.add(null);
        }
        EdgeRef edge = new EdgeRef(from, padded);
        target.incomingEdges.add(edge);
        return edge;
    }

    void writeVar(HirVar v, MirVal val, int line) {
        if (pinnedVarIds.contains(v.id())) {
            MirVal conv = convertVal(val, v.primitiveKind(), line);
            emit(
                    new MirInst.SlotWrite(
                            v, MirInst.SlotOpKind.ASSIGN, conv, line, slotWriteEffect(v)));
            return;
        }
        MirVal conv = convertVal(val, v.primitiveKind(), line);
        cur.currentDef.put(v.id(), conv);
    }

    void defVar(HirVar v, MirVal val, int line) {
        if (pinnedVarIds.contains(v.id())) {
            MirVal conv = convertVal(val, v.primitiveKind(), line);
            emit(new MirInst.SlotWrite(v, MirInst.SlotOpKind.INIT, conv, line, slotWriteEffect(v)));
            return;
        }
        MirVal conv = convertVal(val, v.primitiveKind(), line);
        cur.currentDef.put(v.id(), conv);
    }

    MirVal readVar(HirVar v, int line) {
        if (pinnedVarIds.contains(v.id())) {
            MirVal res = newVal(v.primitiveKind(), v.name());
            emit(new MirInst.SlotRead(res, v, line, slotReadEffect(v)));
            return res;
        }
        return readSsaVar(v, cur, line);
    }

    MirVal readSsaVar(HirVar v, BlockBuilder blk, int line) {
        MirVal local = blk.currentDef.get(v.id());
        if (local != null) {
            return local;
        }
        if (!blk.sealed) {
            MirVal bp = newVal(v.primitiveKind(), v.name());
            int idx = blk.params.size();
            blk.params.add(bp);
            for (EdgeRef e : blk.incomingEdges) {
                e.args.add(null);
            }
            blk.incompletePhis.add(new IncompletePhi(v.id(), idx, bp));
            blk.currentDef.put(v.id(), bp);
            return bp;
        }
        if (blk.handlerDominator != null) {
            MirVal domVal = readSsaVar(v, blk.handlerDominator, line);
            blk.currentDef.put(v.id(), domVal);
            return domVal;
        }
        if (blk.incomingEdges.isEmpty()) {
            // Unreachable block or variable not yet defined on entry path: materialize default
            // constant.
            MirVal def = emitDefaultConstIn(blk, v.primitiveKind(), line);
            blk.currentDef.put(v.id(), def);
            return def;
        }
        if (blk.incomingEdges.size() == 1) {
            MirVal predVal = readSsaVar(v, blk.incomingEdges.get(0).from, line);
            blk.currentDef.put(v.id(), predVal);
            return predVal;
        }
        // Sealed block with multiple predecessors: reserve block parameter slot first to break
        // cycles.
        MirVal bp = newVal(v.primitiveKind(), v.name());
        int idx = blk.params.size();
        blk.params.add(bp);
        for (EdgeRef e : blk.incomingEdges) {
            e.args.add(null);
        }
        blk.currentDef.put(v.id(), bp);
        for (EdgeRef e : blk.incomingEdges) {
            MirVal incoming = readSsaVar(v, e.from, line);
            e.args.set(idx, incoming);
        }
        return bp;
    }

    void sealBlock(BlockBuilder blk) {
        if (blk.sealed) {
            return;
        }
        blk.sealed = true;
        for (int i = 0; i < blk.incompletePhis.size(); i++) {
            IncompletePhi ip = blk.incompletePhis.get(i);
            HirVar v = varById.get(ip.varId);
            for (EdgeRef e : blk.incomingEdges) {
                MirVal incoming = readSsaVar(v, e.from, fn.line());
                e.args.set(ip.paramIndex, incoming);
            }
        }
        blk.incompletePhis.clear();
    }

    MirVal emitConst(Object value, PrimitiveKind kind, int line) {
        MirVal v = newVal(kind, null);
        emit(new MirInst.Const(v, value, line));
        return v;
    }

    private MirVal emitDefaultConstIn(BlockBuilder blk, PrimitiveKind kind, int line) {
        MirVal v = newVal(kind, null);
        Object def =
                switch (kind) {
                    case BYTE -> (byte) 0;
                    case SHORT -> (short) 0;
                    case INT -> 0;
                    case LONG -> 0L;
                    case FLOAT -> 0.0f;
                    case DOUBLE -> 0.0d;
                    case BOOL -> Boolean.FALSE;
                    case CHAR -> '\0';
                    case NONE -> null;
                };
        blk.insts.add(0, new MirInst.Const(v, def, line));
        return v;
    }

    MirVal convertVal(MirVal val, PrimitiveKind target, int line) {
        if (val.kind() == target) {
            return val;
        }
        MirVal out = newVal(target, null);
        emit(new MirInst.Convert(out, val, target, line));
        propagateAlias(val, out);
        return out;
    }

    MirVal applyWideningRank(MirVal val, int rank, int line) {
        if (rank < 0) {
            return val;
        }
        PrimitiveKind target = PrimitiveKind.ofNumericRank(rank);
        if (val.kind().isPrimitive() && target != PrimitiveKind.NONE) {
            return convertVal(val, target, line);
        }
        if (rank == 5 && val.kind().isPrimitive() && val.kind() != PrimitiveKind.BOOL) {
            return convertVal(val, PrimitiveKind.DOUBLE, line);
        }
        PrimitiveKind outKind =
                (rank == 5 && val.kind() != PrimitiveKind.NONE)
                        ? PrimitiveKind.DOUBLE
                        : PrimitiveKind.NONE;
        MirVal out = newVal(outKind, null);
        emit(new MirInst.Widen(out, val, rank, line));
        return out;
    }

    MirVal applyCheckedType(MirVal val, String checkedType, int line) {
        if (checkedType == null) {
            return val;
        }
        MirVal boxed = convertVal(val, PrimitiveKind.NONE, line);
        MirVal checked = newVal(PrimitiveKind.NONE, null);
        emit(new MirInst.CheckCast(checked, boxed, null, null, checkedType, line));
        propagateAlias(val, checked);
        return checked;
    }

    void propagateAlias(MirVal src, MirVal dst) {
        MirEffect.AliasClass a = valAliases.get(src.id());
        if (a != null) {
            valAliases.put(dst.id(), a);
        }
    }

    void recordExprAlias(MirVal val, ExprMeta meta) {
        if (meta == null || val == null) {
            return;
        }
        if (meta.frozen() || meta.deeplyImmutable()) {
            valAliases.putIfAbsent(val.id(), MirEffect.AliasClass.FROZEN);
        }
    }

    MirEffect.AliasClass aliasOf(MirVal val, ExprMeta exprMeta) {
        if (exprMeta != null && (exprMeta.frozen() || exprMeta.deeplyImmutable())) {
            return MirEffect.AliasClass.FROZEN;
        }
        MirEffect.AliasClass a = valAliases.get(val.id());
        return a != null ? a : MirEffect.AliasClass.SHARED;
    }

    MirEffect slotReadEffect(HirVar v) {
        if (!v.cell()
                && ((v.capturedByValue() && v.ownerMethodId() != fn.id())
                        || v.kind() == Resolution.Variable.Kind.THIS
                        || v.kind() == Resolution.Variable.Kind.RECURSE)) {
            return MirEffect.PURE_NOTHROW;
        }
        boolean nothrow = !v.mayBeUndefined() || v.definitelyDefined();
        return MirEffect.read(MirEffect.AliasClass.SHARED, nothrow, true, true);
    }

    MirEffect slotWriteEffect(HirVar v) {
        boolean nothrow = !v.mayBeUndefined() || v.definitelyDefined();
        return MirEffect.write(MirEffect.AliasClass.SHARED, nothrow);
    }

    // ------------------------------------------------------------------ finalize & trivial phi
    // cleanup

    List<MirBlock> buildAndFinalizeBlocks() {
        for (BlockBuilder b : allBlocks) {
            sealBlock(b);
        }
        return eliminateTrivialBlockParams(buildBlocks());
    }

    private List<MirBlock> buildBlocks() {
        List<MirBlock> out = new ArrayList<>(allBlocks.size());
        for (BlockBuilder b : allBlocks) {
            MirTerminator term;
            if (b.term == null) {
                term = new MirTerminator.Unreachable(fn.line());
            } else {
                term =
                        switch (b.term) {
                            case PendingTerm.Br br ->
                                    new MirTerminator.Br(br.target.id, br.edge.args, br.line);
                            case PendingTerm.BrIf bi ->
                                    new MirTerminator.BrIf(
                                            bi.cond,
                                            bi.thenTarget.id,
                                            bi.thenEdge.args,
                                            bi.elseTarget.id,
                                            bi.elseEdge.args,
                                            bi.line);
                            case PendingTerm.Switch sw -> {
                                int n = sw.keys.length;
                                int[] tIds = new int[n];
                                List<List<MirVal>> tArgs = new ArrayList<>(n);
                                for (int k = 0; k < n; k++) {
                                    tIds[k] = sw.targets[k].id;
                                    tArgs.add(sw.edges[k].args);
                                }
                                yield new MirTerminator.Switch(
                                        sw.key,
                                        sw.keys,
                                        tIds,
                                        tArgs,
                                        sw.defaultTarget.id,
                                        sw.defaultEdge.args,
                                        sw.line);
                            }
                            case PendingTerm.Fixed fx -> fx.term;
                        };
            }
            out.add(new MirBlock(b.id, b.params, b.insts, term, b.handlers, b.handlerEntry));
        }
        return out;
    }

    /**
     * Removes trivial block parameters (where every incoming argument is either the parameter
     * itself or one identical SSA value {@code v}), substituting {@code bp -> v} across the CFG.
     */
    private List<MirBlock> eliminateTrivialBlockParams(List<MirBlock> blocks) {
        boolean changed = true;
        List<MirBlock> current = blocks;
        while (changed) {
            changed = false;
            Map<Integer, List<List<MirVal>>> incoming = new HashMap<>();
            for (MirBlock b : current) {
                incoming.put(b.id(), new ArrayList<>());
            }
            for (MirBlock b : current) {
                if (b.terminator() instanceof MirTerminator.Br br) {
                    incoming.get(br.targetBlock()).add(br.args());
                } else if (b.terminator() instanceof MirTerminator.BrIf bi) {
                    incoming.get(bi.thenBlock()).add(bi.thenArgs());
                    incoming.get(bi.elseBlock()).add(bi.elseArgs());
                } else if (b.terminator() instanceof MirTerminator.Switch sw) {
                    for (int k = 0; k < sw.keys().length; k++) {
                        incoming.get(sw.targetBlocks()[k]).add(sw.targetArgs().get(k));
                    }
                    incoming.get(sw.defaultBlock()).add(sw.defaultArgs());
                }
            }
            Map<Integer, MirVal> subst = new HashMap<>();
            Map<Integer, Set<Integer>> removeIndices = new HashMap<>();
            for (MirBlock b : current) {
                if (b.handlerEntry() || b.params().isEmpty()) {
                    continue;
                }
                List<List<MirVal>> edges = incoming.get(b.id());
                if (edges.isEmpty()) {
                    continue;
                }
                for (int i = 0; i < b.params().size(); i++) {
                    MirVal bp = b.params().get(i);
                    MirVal same = null;
                    boolean trivial = true;
                    for (List<MirVal> edgeArgs : edges) {
                        MirVal arg = edgeArgs.get(i);
                        if (arg.id() == bp.id()) {
                            continue;
                        }
                        if (same == null) {
                            same = arg;
                        } else if (same.id() != arg.id()) {
                            trivial = false;
                            break;
                        }
                    }
                    if (trivial && same != null) {
                        subst.put(bp.id(), same);
                        removeIndices.computeIfAbsent(b.id(), k -> new HashSet<>()).add(i);
                    }
                }
            }
            if (subst.isEmpty()) {
                break;
            }
            changed = true;
            Map<Integer, MirVal> resolved = new HashMap<>(subst.size() * 2);
            for (Map.Entry<Integer, MirVal> e : subst.entrySet()) {
                MirVal v = e.getValue();
                Set<Integer> seen = new HashSet<>();
                seen.add(e.getKey());
                while (subst.containsKey(v.id()) && seen.add(v.id())) {
                    v = subst.get(v.id());
                }
                resolved.put(e.getKey(), v);
            }
            List<MirBlock> next = new ArrayList<>(current.size());
            for (MirBlock b : current) {
                Set<Integer> drop = removeIndices.getOrDefault(b.id(), Set.of());
                List<MirVal> newParams = drop.isEmpty() ? b.params() : filterOut(b.params(), drop);
                List<MirInst> newInsts = new ArrayList<>(b.insts().size());
                for (MirInst inst : b.insts()) {
                    newInsts.add(inst.remapUses(v -> resolved.getOrDefault(v.id(), v)));
                }
                MirTerminator term =
                        b.terminator().remapUses(v -> resolved.getOrDefault(v.id(), v));
                if (term instanceof MirTerminator.Br br) {
                    Set<Integer> td = removeIndices.getOrDefault(br.targetBlock(), Set.of());
                    if (!td.isEmpty()) {
                        term =
                                new MirTerminator.Br(
                                        br.targetBlock(), filterOut(br.args(), td), br.line());
                    }
                } else if (term instanceof MirTerminator.BrIf bi) {
                    Set<Integer> td = removeIndices.getOrDefault(bi.thenBlock(), Set.of());
                    Set<Integer> ed = removeIndices.getOrDefault(bi.elseBlock(), Set.of());
                    if (!td.isEmpty() || !ed.isEmpty()) {
                        term =
                                new MirTerminator.BrIf(
                                        bi.cond(),
                                        bi.thenBlock(),
                                        td.isEmpty() ? bi.thenArgs() : filterOut(bi.thenArgs(), td),
                                        bi.elseBlock(),
                                        ed.isEmpty() ? bi.elseArgs() : filterOut(bi.elseArgs(), ed),
                                        bi.line());
                    }
                } else if (term instanceof MirTerminator.Switch sw) {
                    boolean swChanged = false;
                    List<List<MirVal>> nta = new ArrayList<>(sw.keys().length);
                    for (int k = 0; k < sw.keys().length; k++) {
                        Set<Integer> td =
                                removeIndices.getOrDefault(sw.targetBlocks()[k], Set.of());
                        if (!td.isEmpty()) {
                            nta.add(filterOut(sw.targetArgs().get(k), td));
                            swChanged = true;
                        } else {
                            nta.add(sw.targetArgs().get(k));
                        }
                    }
                    Set<Integer> dd = removeIndices.getOrDefault(sw.defaultBlock(), Set.of());
                    List<MirVal> nda =
                            dd.isEmpty() ? sw.defaultArgs() : filterOut(sw.defaultArgs(), dd);
                    if (!dd.isEmpty()) {
                        swChanged = true;
                    }
                    if (swChanged) {
                        term =
                                new MirTerminator.Switch(
                                        sw.key(),
                                        sw.keys(),
                                        sw.targetBlocks(),
                                        nta,
                                        sw.defaultBlock(),
                                        nda,
                                        sw.line());
                    }
                }
                next.add(
                        new MirBlock(
                                b.id(), newParams, newInsts, term, b.handlers(), b.handlerEntry()));
            }
            current = next;
        }
        return current;
    }

    private static List<MirVal> filterOut(List<MirVal> list, Set<Integer> dropIndices) {
        List<MirVal> out = new ArrayList<>(list.size() - dropIndices.size());
        for (int i = 0; i < list.size(); i++) {
            if (!dropIndices.contains(i)) {
                out.add(list.get(i));
            }
        }
        return out;
    }
}
