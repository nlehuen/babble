package org.babblelang.opt;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.hir.HirVar;

/**
 * Shared backward {@link BitSet} liveness analysis over non-captured frame {@link HirVar}s inside a
 * structured {@link HirFunction} ({@code BIP039-D6}, {@code BIP039-I2}, {@code BIP039-I5}).
 *
 * <p>Reused by {@code HirDcePass} (dead-store &amp; dead loop-carried variable elimination) and
 * {@code HirSlotAllocator} (JVM-category-segregated local slot coloring).
 */
public final class HirLiveness {
    private static final int MAX_LOOP_FIXPOINT_ITERS = 32;

    private final Map<Integer, Integer> varIdToIndex;
    private final List<HirVar> indexToVar;
    private final IdentityHashMap<HirExpr, BitSet> liveInByNode;
    private final IdentityHashMap<HirExpr, BitSet> liveOutByNode;
    private final BitSet tryEdgeLive;

    private HirLiveness(
            Map<Integer, Integer> varIdToIndex,
            List<HirVar> indexToVar,
            IdentityHashMap<HirExpr, BitSet> liveInByNode,
            IdentityHashMap<HirExpr, BitSet> liveOutByNode,
            BitSet tryEdgeLive) {
        this.varIdToIndex = varIdToIndex;
        this.indexToVar = indexToVar;
        this.liveInByNode = liveInByNode;
        this.liveOutByNode = liveOutByNode;
        this.tryEdgeLive = tryEdgeLive;
    }

    /** Computes exact backward liveness for all non-cell frame variables of {@code fn}. */
    public static HirLiveness analyze(HirFunction fn) {
        return analyze(fn, null);
    }

    /**
     * Computes exact backward liveness for a statement sequence (such as a class/record constructor
     * body) given its parameter and declared variables.
     */
    public static HirLiveness analyzeStatements(
            List<HirVar> params, List<HirVar> declaredVars, List<HirExpr> stmts) {
        Map<Integer, Integer> idToIdx = new HashMap<>();
        List<HirVar> idxToVar = new ArrayList<>();
        for (HirVar p : params) {
            registerVar(p, idToIdx, idxToVar);
        }
        for (HirVar v : declaredVars) {
            registerVar(v, idToIdx, idxToVar);
        }
        for (HirExpr s : stmts) {
            collectVars(s, idToIdx, idxToVar);
        }
        IdentityHashMap<HirExpr, BitSet> liveIn = new IdentityHashMap<>();
        IdentityHashMap<HirExpr, BitSet> liveOut = new IdentityHashMap<>();
        BitSet tryEdge = new BitSet(idxToVar.size());
        Analyzer analyzer = new Analyzer(idToIdx, idxToVar.size(), liveIn, liveOut, tryEdge, null);
        BitSet empty = new BitSet(idxToVar.size());
        analyzer.transferList(stmts, empty, empty, empty, empty, empty);
        return new HirLiveness(idToIdx, idxToVar, liveIn, liveOut, tryEdge);
    }

    /**
     * Computes dead-store-aware backward liveness for DCE: when {@code isDeadStoreRemovable} is
     * non-null and returns true for a non-final {@code Def}/{@code VarAssign} whose target variable
     * is dead after the statement, its RHS reads are not marked live, enabling dead loop-carried
     * accumulators to converge to dead in a single fixed-point pass.
     */
    public static HirLiveness analyzeForDce(
            HirFunction fn, Predicate<HirExpr> isDeadStoreRemovable) {
        return analyze(fn, isDeadStoreRemovable);
    }

    private static HirLiveness analyze(HirFunction fn, Predicate<HirExpr> isDeadStoreRemovable) {
        Map<Integer, Integer> idToIdx = new HashMap<>();
        List<HirVar> idxToVar = new ArrayList<>();
        for (HirParam p : fn.params()) {
            registerVar(p.var(), idToIdx, idxToVar);
        }
        for (HirVar v : fn.declaredVars()) {
            registerVar(v, idToIdx, idxToVar);
        }
        for (HirExpr s : fn.body()) {
            collectVars(s, idToIdx, idxToVar);
        }

        IdentityHashMap<HirExpr, BitSet> liveIn = new IdentityHashMap<>();
        IdentityHashMap<HirExpr, BitSet> liveOut = new IdentityHashMap<>();
        BitSet tryEdge = new BitSet(idxToVar.size());

        Analyzer analyzer =
                new Analyzer(
                        idToIdx, idxToVar.size(), liveIn, liveOut, tryEdge, isDeadStoreRemovable);
        BitSet empty = new BitSet(idxToVar.size());
        analyzer.transferList(fn.body(), empty, empty, empty, empty, empty);
        return new HirLiveness(idToIdx, idxToVar, liveIn, liveOut, tryEdge);
    }

    public int varCount() {
        return indexToVar.size();
    }

    public HirVar varAt(int index) {
        return indexToVar.get(index);
    }

    public int indexOf(int varId) {
        Integer idx = varIdToIndex.get(varId);
        return idx != null ? idx : -1;
    }

    /** Returns true if {@code varId} is tracked and live immediately after {@code node}. */
    public boolean isLiveOut(HirExpr node, int varId) {
        Integer idx = varIdToIndex.get(varId);
        if (idx == null) {
            return true;
        }
        BitSet out = liveOutByNode.get(node);
        return out == null || out.get(idx);
    }

    /** Returns the live-in bitset before {@code node}, or null if {@code node} was not visited. */
    public BitSet liveIn(HirExpr node) {
        return liveInByNode.get(node);
    }

    /** Returns the live-out bitset after {@code node}, or null if {@code node} was not visited. */
    public BitSet liveOut(HirExpr node) {
        return liveOutByNode.get(node);
    }

    /** BitSet of variable indices live across any {@code try} catch/finally exception edge. */
    public BitSet tryEdgeLive() {
        return tryEdgeLive;
    }

    private static void registerVar(
            HirVar v, Map<Integer, Integer> idToIdx, List<HirVar> idxToVar) {
        if (v == null || v.cell() || v.isCaptured()) {
            return;
        }
        if (!idToIdx.containsKey(v.id())) {
            idToIdx.put(v.id(), idxToVar.size());
            idxToVar.add(v);
        }
    }

    private static void collectVars(
            HirExpr expr, Map<Integer, Integer> idToIdx, List<HirVar> idxToVar) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case HirExpr.VarRead v -> registerVar(v.var(), idToIdx, idxToVar);
            case HirExpr.Def d -> registerVar(d.var(), idToIdx, idxToVar);
            case HirExpr.VarAssign a -> registerVar(a.var(), idToIdx, idxToVar);
            case HirExpr.ThisRef t -> registerVar(t.var(), idToIdx, idxToVar);
            case HirExpr.RecurseRef r -> registerVar(r.var(), idToIdx, idxToVar);
            case HirExpr.Block b -> {
                for (HirVar v : b.declaredVars()) registerVar(v, idToIdx, idxToVar);
            }
            case HirExpr.For f -> {
                registerVar(f.loopVar(), idToIdx, idxToVar);
                for (HirVar v : f.bodyDeclaredVars()) registerVar(v, idToIdx, idxToVar);
            }
            case HirExpr.Try t -> {
                for (HirVar v : t.tryDeclaredVars()) registerVar(v, idToIdx, idxToVar);
                for (HirExpr.HirCatch cc : t.catches()) {
                    registerVar(cc.var(), idToIdx, idxToVar);
                    for (HirVar v : cc.declaredVars()) registerVar(v, idToIdx, idxToVar);
                }
                for (HirVar v : t.finallyDeclaredVars()) registerVar(v, idToIdx, idxToVar);
            }
            case HirExpr.Match m -> {
                registerVar(m.scrutineeVar(), idToIdx, idxToVar);
                for (HirExpr.MatchArm arm : m.arms()) {
                    for (HirVar v : arm.boundVars()) registerVar(v, idToIdx, idxToVar);
                }
                if (m.elseArm() != null) registerVar(m.elseArm().var(), idToIdx, idxToVar);
            }
            default -> {}
        }
        HirRewriter.rewriteChildren(
                expr,
                c -> {
                    collectVars(c, idToIdx, idxToVar);
                    return c;
                });
    }

    private static final class Analyzer {
        private final Map<Integer, Integer> idToIdx;
        private final int nVars;
        private final IdentityHashMap<HirExpr, BitSet> liveIn;
        private final IdentityHashMap<HirExpr, BitSet> liveOut;
        private final BitSet tryEdge;
        private final Predicate<HirExpr> isDeadStoreRemovable;

        Analyzer(
                Map<Integer, Integer> idToIdx,
                int nVars,
                IdentityHashMap<HirExpr, BitSet> liveIn,
                IdentityHashMap<HirExpr, BitSet> liveOut,
                BitSet tryEdge,
                Predicate<HirExpr> isDeadStoreRemovable) {
            this.idToIdx = idToIdx;
            this.nVars = nVars;
            this.liveIn = liveIn;
            this.liveOut = liveOut;
            this.tryEdge = tryEdge;
            this.isDeadStoreRemovable = isDeadStoreRemovable;
        }

        private BitSet copy(BitSet b) {
            return (BitSet) b.clone();
        }

        private BitSet union(BitSet a, BitSet b) {
            BitSet r = copy(a);
            r.or(b);
            return r;
        }

        private void useVar(BitSet bs, HirVar v) {
            if (v == null) return;
            Integer idx = idToIdx.get(v.id());
            if (idx != null) {
                bs.set(idx);
            }
        }

        private void killVar(BitSet bs, HirVar v) {
            if (v == null) return;
            Integer idx = idToIdx.get(v.id());
            if (idx != null) {
                bs.clear(idx);
            }
        }

        private void record(HirExpr node, BitSet in, BitSet out) {
            liveIn.put(node, copy(in));
            liveOut.put(node, copy(out));
        }

        BitSet transferList(
                List<HirExpr> stmts,
                BitSet out,
                BitSet breakOut,
                BitSet continueIn,
                BitSet returnOut,
                BitSet exnOut) {
            BitSet cur = copy(out);
            int last = stmts.size() - 1;
            for (int i = last; i >= 0; i--) {
                HirExpr s = stmts.get(i);
                if (isDeadStoreRemovable != null
                        && i < last
                        && isSkippableDeadStore(s, cur, exnOut)) {
                    record(s, cur, cur);
                    continue;
                }
                cur = transferExpr(s, cur, breakOut, continueIn, returnOut, exnOut);
            }
            return cur;
        }

        private boolean isSkippableDeadStore(HirExpr s, BitSet out, BitSet exnOut) {
            HirVar target = null;
            if (s instanceof HirExpr.VarAssign a) {
                target = a.var();
            } else if (s instanceof HirExpr.Def d) {
                target = d.var();
            }
            if (target == null || target.cell() || target.isCaptured()) {
                return false;
            }
            Integer idx = idToIdx.get(target.id());
            if (idx == null || out.get(idx) || exnOut.get(idx)) {
                return false;
            }
            return isDeadStoreRemovable.test(s);
        }

        private BitSet transferExpr(
                HirExpr expr,
                BitSet out,
                BitSet breakOut,
                BitSet continueIn,
                BitSet returnOut,
                BitSet exnOut) {
            if (expr == null) {
                return copy(out);
            }
            // Any expression evaluated inside a try block may transfer to exnOut on exception.
            BitSet effectiveOut = exnOut.isEmpty() ? out : union(out, exnOut);
            BitSet in =
                    switch (expr) {
                        case HirExpr.Const ignored -> copy(out);
                        case HirExpr.IntParseFallback ignored -> copy(effectiveOut);
                        case HirExpr.CoreCollectionRef ignored -> copy(out);
                        case HirExpr.CoreExceptionRef ignored -> copy(out);
                        case HirExpr.FunctionRef ignored -> copy(out);
                        case HirExpr.ClassExpr ignored -> copy(effectiveOut);
                        case HirExpr.InterfaceExpr ignored -> copy(effectiveOut);
                        case HirExpr.NameRead ignored -> copy(effectiveOut);
                        case HirExpr.VarRead v -> {
                            BitSet r = copy(effectiveOut);
                            useVar(r, v.var());
                            yield r;
                        }
                        case HirExpr.ThisRef t -> {
                            BitSet r = copy(effectiveOut);
                            useVar(r, t.var());
                            yield r;
                        }
                        case HirExpr.RecurseRef r -> {
                            BitSet bs = copy(effectiveOut);
                            useVar(bs, r.var());
                            yield bs;
                        }
                        case HirExpr.Def d -> {
                            BitSet afterKill = copy(effectiveOut);
                            killVar(afterKill, d.var());
                            yield d.value() != null
                                    ? transferExpr(
                                            d.value(),
                                            afterKill,
                                            breakOut,
                                            continueIn,
                                            returnOut,
                                            exnOut)
                                    : afterKill;
                        }
                        case HirExpr.VarAssign a -> {
                            if (a.operator() == null) {
                                BitSet afterKill = copy(effectiveOut);
                                killVar(afterKill, a.var());
                                yield transferExpr(
                                        a.value(),
                                        afterKill,
                                        breakOut,
                                        continueIn,
                                        returnOut,
                                        exnOut);
                            }
                            // Compound assign: reads var, evaluates rhs, writes var.
                            BitSet afterRhs =
                                    transferExpr(
                                            a.value(),
                                            effectiveOut,
                                            breakOut,
                                            continueIn,
                                            returnOut,
                                            exnOut);
                            useVar(afterRhs, a.var());
                            yield afterRhs;
                        }
                        case HirExpr.Block b -> {
                            BitSet r =
                                    transferList(
                                            b.statements(),
                                            out,
                                            breakOut,
                                            continueIn,
                                            returnOut,
                                            exnOut);
                            for (HirVar v : b.declaredVars()) killVar(r, v);
                            yield r;
                        }
                        case HirExpr.If i -> {
                            BitSet thenIn =
                                    transferExpr(
                                            i.thenBranch(),
                                            out,
                                            breakOut,
                                            continueIn,
                                            returnOut,
                                            exnOut);
                            BitSet elseIn =
                                    i.elseBranch() != null
                                            ? transferExpr(
                                                    i.elseBranch(),
                                                    out,
                                                    breakOut,
                                                    continueIn,
                                                    returnOut,
                                                    exnOut)
                                            : copy(out);
                            BitSet joined = union(thenIn, elseIn);
                            yield transferExpr(
                                    i.cond(), joined, breakOut, continueIn, returnOut, exnOut);
                        }
                        case HirExpr.Logic l -> {
                            BitSet rightIn =
                                    transferExpr(
                                            l.right(),
                                            out,
                                            breakOut,
                                            continueIn,
                                            returnOut,
                                            exnOut);
                            BitSet joined = union(out, rightIn);
                            yield transferExpr(
                                    l.left(), joined, breakOut, continueIn, returnOut, exnOut);
                        }
                        case HirExpr.While w -> transferWhile(w, out, returnOut, exnOut);
                        case HirExpr.For f -> transferFor(f, out, returnOut, exnOut);
                        case HirExpr.Try t ->
                                transferTry(t, out, breakOut, continueIn, returnOut, exnOut);
                        case HirExpr.Match m ->
                                transferMatch(m, out, breakOut, continueIn, returnOut, exnOut);
                        case HirExpr.Return r ->
                                transferExpr(
                                        r.value(),
                                        union(returnOut, exnOut),
                                        breakOut,
                                        continueIn,
                                        returnOut,
                                        exnOut);
                        case HirExpr.Throw th ->
                                transferExpr(
                                        th.value(),
                                        exnOut,
                                        breakOut,
                                        continueIn,
                                        returnOut,
                                        exnOut);
                        case HirExpr.Break b ->
                                b.value() != null
                                        ? transferExpr(
                                                b.value(),
                                                union(breakOut, exnOut),
                                                breakOut,
                                                continueIn,
                                                returnOut,
                                                exnOut)
                                        : copy(breakOut);
                        case HirExpr.Continue ignored -> copy(continueIn);
                        default ->
                                transferLinearChildren(
                                        expr,
                                        effectiveOut,
                                        breakOut,
                                        continueIn,
                                        returnOut,
                                        exnOut);
                    };
            record(expr, in, out);
            return in;
        }

        private BitSet transferWhile(HirExpr.While w, BitSet out, BitSet returnOut, BitSet exnOut) {
            BitSet condIn = transferExpr(w.cond(), out, out, out, returnOut, exnOut);
            boolean converged = false;
            for (int iter = 0; iter < MAX_LOOP_FIXPOINT_ITERS; iter++) {
                BitSet bodyIn = transferExpr(w.body(), condIn, out, condIn, returnOut, exnOut);
                BitSet condOut = union(out, bodyIn);
                BitSet nextCondIn = transferExpr(w.cond(), condOut, out, condIn, returnOut, exnOut);
                nextCondIn.or(condIn);
                if (nextCondIn.equals(condIn)) {
                    converged = true;
                    break;
                }
                condIn = nextCondIn;
            }
            if (!converged) {
                condIn = copy(condIn);
                condIn.set(0, idToIdx.size());
            }
            // Final pass with converged condIn so every inner node's liveIn/liveOut reflects the
            // fixed point.
            BitSet bodyIn = transferExpr(w.body(), condIn, out, condIn, returnOut, exnOut);
            return transferExpr(w.cond(), union(out, bodyIn), out, condIn, returnOut, exnOut);
        }

        private BitSet transferFor(HirExpr.For f, BitSet out, BitSet returnOut, BitSet exnOut) {
            BitSet headerIn = copy(out);
            boolean converged = false;
            for (int iter = 0; iter < MAX_LOOP_FIXPOINT_ITERS; iter++) {
                BitSet bodyIn = transferList(f.body(), headerIn, out, headerIn, returnOut, exnOut);
                killVar(bodyIn, f.loopVar());
                for (HirVar v : f.bodyDeclaredVars()) killVar(bodyIn, v);
                BitSet next = union(headerIn, bodyIn);
                if (next.equals(headerIn)) {
                    converged = true;
                    break;
                }
                headerIn = next;
            }
            if (!converged) {
                headerIn = copy(headerIn);
                headerIn.set(0, idToIdx.size());
                killVar(headerIn, f.loopVar());
                for (HirVar v : f.bodyDeclaredVars()) killVar(headerIn, v);
            }
            BitSet bodyIn = transferList(f.body(), headerIn, out, headerIn, returnOut, exnOut);
            killVar(bodyIn, f.loopVar());
            for (HirVar v : f.bodyDeclaredVars()) killVar(bodyIn, v);
            BitSet preLoop = union(out, bodyIn);
            if (f.countedRange() != null) {
                HirExpr.CountedRangeLoop cr = f.countedRange();
                if (cr.step() != null) {
                    preLoop = transferExpr(cr.step(), preLoop, out, preLoop, returnOut, exnOut);
                }
                preLoop = transferExpr(cr.end(), preLoop, out, preLoop, returnOut, exnOut);
                preLoop = transferExpr(cr.start(), preLoop, out, preLoop, returnOut, exnOut);
            } else if (f.iterable() != null) {
                preLoop = transferExpr(f.iterable(), preLoop, out, preLoop, returnOut, exnOut);
            }
            return preLoop;
        }

        private BitSet transferTry(
                HirExpr.Try t,
                BitSet out,
                BitSet breakOut,
                BitSet continueIn,
                BitSet returnOut,
                BitSet exnOut) {
            BitSet finIn =
                    t.hasFinally()
                            ? transferList(
                                    t.finallyBody(), out, breakOut, continueIn, returnOut, exnOut)
                            : copy(out);
            for (HirVar v : t.finallyDeclaredVars()) killVar(finIn, v);

            // Any abrupt exit (return/break/continue/exception) from try or catch runs finally.
            BitSet innerReturn = t.hasFinally() ? union(returnOut, finIn) : returnOut;
            BitSet innerBreak = t.hasFinally() ? union(breakOut, finIn) : breakOut;
            BitSet innerCont = t.hasFinally() ? union(continueIn, finIn) : continueIn;
            BitSet catchExn = t.hasFinally() ? union(exnOut, finIn) : exnOut;

            BitSet handlersIn = copy(catchExn);
            for (HirExpr.HirCatch cc : t.catches()) {
                BitSet cIn =
                        transferList(
                                cc.body(), finIn, innerBreak, innerCont, innerReturn, catchExn);
                killVar(cIn, cc.var());
                for (HirVar v : cc.declaredVars()) killVar(cIn, v);
                handlersIn.or(cIn);
            }
            tryEdge.or(handlersIn);
            if (t.hasFinally()) {
                tryEdge.or(finIn);
            }
            BitSet tryIn =
                    transferList(
                            t.tryBody(), finIn, innerBreak, innerCont, innerReturn, handlersIn);
            for (HirVar v : t.tryDeclaredVars()) killVar(tryIn, v);
            return union(tryIn, handlersIn);
        }

        private BitSet transferMatch(
                HirExpr.Match m,
                BitSet out,
                BitSet breakOut,
                BitSet continueIn,
                BitSet returnOut,
                BitSet exnOut) {
            BitSet acc = m.exhaustive() ? new BitSet(nVars) : copy(out);
            if (m.elseArm() != null) {
                BitSet eIn =
                        transferExpr(
                                m.elseArm().body(), out, breakOut, continueIn, returnOut, exnOut);
                killVar(eIn, m.elseArm().var());
                acc.or(eIn);
            }
            for (int i = m.arms().size() - 1; i >= 0; i--) {
                HirExpr.MatchArm arm = m.arms().get(i);
                BitSet bodyIn =
                        transferExpr(arm.body(), out, breakOut, continueIn, returnOut, exnOut);
                BitSet armIn = union(acc, bodyIn);
                if (arm.guard() != null) {
                    armIn =
                            transferExpr(
                                    arm.guard(), armIn, breakOut, continueIn, returnOut, exnOut);
                }
                for (HirVar bv : arm.boundVars()) killVar(armIn, bv);
                armIn =
                        transferPattern(
                                arm.pattern(), armIn, breakOut, continueIn, returnOut, exnOut);
                acc = armIn;
            }
            killVar(acc, m.scrutineeVar());
            return transferExpr(m.scrutinee(), acc, breakOut, continueIn, returnOut, exnOut);
        }

        private BitSet transferPattern(
                HirPattern p,
                BitSet out,
                BitSet breakOut,
                BitSet continueIn,
                BitSet returnOut,
                BitSet exnOut) {
            return switch (p) {
                case HirPattern.Const ignored -> out;
                case HirPattern.Wildcard ignored -> out;
                case HirPattern.TypeTest ignored -> out;
                case HirPattern.ValueExpr v ->
                        transferExpr(v.expr(), out, breakOut, continueIn, returnOut, exnOut);
                case HirPattern.Bind b -> {
                    BitSet r = copy(out);
                    killVar(r, b.var());
                    yield b.inner() != null
                            ? transferPattern(b.inner(), r, breakOut, continueIn, returnOut, exnOut)
                            : r;
                }
                case HirPattern.Or o -> {
                    BitSet r = copy(out);
                    for (int i = o.alternatives().size() - 1; i >= 0; i--) {
                        r =
                                union(
                                        r,
                                        transferPattern(
                                                o.alternatives().get(i),
                                                r,
                                                breakOut,
                                                continueIn,
                                                returnOut,
                                                exnOut));
                    }
                    yield r;
                }
                case HirPattern.Relational r ->
                        transferExpr(r.rhs(), out, breakOut, continueIn, returnOut, exnOut);
                case HirPattern.RangeTest rt -> {
                    BitSet cur = out;
                    if (rt.step() != null) {
                        cur = transferExpr(rt.step(), cur, breakOut, continueIn, returnOut, exnOut);
                    }
                    cur = transferExpr(rt.hi(), cur, breakOut, continueIn, returnOut, exnOut);
                    yield transferExpr(rt.lo(), cur, breakOut, continueIn, returnOut, exnOut);
                }
                case HirPattern.Membership mem ->
                        transferExpr(mem.container(), out, breakOut, continueIn, returnOut, exnOut);
                case HirPattern.RecordDestructure rd -> {
                    BitSet cur = out;
                    for (int i = rd.fieldPatterns().size() - 1; i >= 0; i--) {
                        cur =
                                transferPattern(
                                        rd.fieldPatterns().get(i),
                                        cur,
                                        breakOut,
                                        continueIn,
                                        returnOut,
                                        exnOut);
                    }
                    yield cur;
                }
                case HirPattern.SequenceDestructure sd -> {
                    BitSet cur = copy(out);
                    killVar(cur, sd.restVar());
                    for (int i = sd.elemPatterns().size() - 1; i >= 0; i--) {
                        cur =
                                transferPattern(
                                        sd.elemPatterns().get(i),
                                        cur,
                                        breakOut,
                                        continueIn,
                                        returnOut,
                                        exnOut);
                    }
                    yield cur;
                }
            };
        }

        private BitSet transferLinearChildren(
                HirExpr expr,
                BitSet out,
                BitSet breakOut,
                BitSet continueIn,
                BitSet returnOut,
                BitSet exnOut) {
            List<HirExpr> children = new ArrayList<>(4);
            HirRewriter.rewriteChildren(
                    expr,
                    c -> {
                        if (c != null) children.add(c);
                        return c;
                    });
            BitSet cur = copy(out);
            if (expr instanceof HirExpr.SuperCall s) {
                useVar(cur, s.thisVar());
            } else if (expr instanceof HirExpr.Move m) {
                useVar(cur, m.var());
            }
            for (int i = children.size() - 1; i >= 0; i--) {
                cur = transferExpr(children.get(i), cur, breakOut, continueIn, returnOut, exnOut);
            }
            return cur;
        }
    }
}
