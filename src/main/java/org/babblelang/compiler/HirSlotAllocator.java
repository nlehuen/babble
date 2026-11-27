package org.babblelang.compiler;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirLiveness;
import org.babblelang.opt.HirRewriter;

/**
 * JVM-category-segregated local-slot allocator over structured {@link HirFunction}s ({@code
 * BIP039-D6}, {@code BIP039-P5}).
 *
 * <p>Consumes {@link HirLiveness#analyze(HirFunction)} to build an exact interference graph over
 * non-pinned frame locals and greedily colors non-interfering locals of the same JVM verification
 * category ({@code I}/{@code F}/{@code A}/{@code J}/{@code D}) onto shared physical slots.
 * Parameters, captured/cell variables, potentially-undefined locals, and {@code tryEdgeLive}
 * variables remain pinned to dedicated slots.
 */
final class HirSlotAllocator {
    private static final int CAT_INT = 0;
    private static final int CAT_FLOAT = 1;
    private static final int CAT_REF = 2;
    private static final int CAT_LONG = 3;
    private static final int CAT_DOUBLE = 4;

    private HirSlotAllocator() {}

    static void allocate(HirFunction fn, MethodEmitContext ctx) {
        if (fn == null || fn.namespaceScope()) {
            return;
        }
        HirLiveness liveness = HirLiveness.analyze(fn);
        int nVars = liveness.varCount();
        if (nVars == 0) {
            return;
        }

        // 1. Identify pinned variable IDs (never share slots).
        Set<Integer> pinnedIds = new HashSet<>();
        for (HirParam p : fn.params()) {
            if (p.var() != null) {
                pinnedIds.add(p.var().id());
            }
        }
        BitSet tryLive = liveness.tryEdgeLive();
        for (int i = 0; i < nVars; i++) {
            HirVar v = liveness.varAt(i);
            if (tryLive.get(i)
                    || v.cell()
                    || v.isCaptured()
                    || v.kind() != Resolution.Variable.Kind.DEFINITION
                    || (v.mayBeUndefined() && !v.definitelyDefined())) {
                pinnedIds.add(v.id());
            }
        }

        // 2. Build interference graph over all liveness-tracked variables.
        BitSet[] conflicts = new BitSet[nVars];
        for (int i = 0; i < nVars; i++) {
            conflicts[i] = new BitSet(nVars);
        }
        for (HirExpr s : fn.body()) {
            collectConflicts(s, liveness, conflicts);
        }

        // 3. Greedy category-segregated coloring for non-pinned frame variables.
        Map<Integer, Integer> allocated = new HashMap<>();
        int[] assignedSlot = new int[nVars];
        java.util.Arrays.fill(assignedSlot, -1);
        @SuppressWarnings("unchecked")
        List<Integer>[] poolByCat = new List[5];
        for (int c = 0; c < 5; c++) {
            poolByCat[c] = new ArrayList<>();
        }

        for (int i = 0; i < nVars; i++) {
            HirVar v = liveness.varAt(i);
            if (pinnedIds.contains(v.id())) {
                continue;
            }
            int cat = jvmSlotCategory(v.primitiveKind());
            BitSet usedSlots = new BitSet();
            BitSet conf = conflicts[i];
            for (int j = conf.nextSetBit(0); j >= 0; j = conf.nextSetBit(j + 1)) {
                if (j != i && assignedSlot[j] >= 0) {
                    usedSlots.set(assignedSlot[j]);
                }
            }
            int chosen = -1;
            for (int candidate : poolByCat[cat]) {
                if (!usedSlots.get(candidate)) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen < 0) {
                chosen = ctx.newLocal(v.primitiveKind());
                poolByCat[cat].add(chosen);
            }
            assignedSlot[i] = chosen;
            allocated.put(v.id(), chosen);
        }

        if (!allocated.isEmpty()) {
            ctx.preallocatedSlots = allocated;
        }
    }

    private static void collectConflicts(HirExpr expr, HirLiveness liveness, BitSet[] conflicts) {
        if (expr == null) {
            return;
        }
        BitSet in = liveness.liveIn(expr);
        BitSet out = liveness.liveOut(expr);
        addClique(in, conflicts);
        addClique(out, conflicts);

        switch (expr) {
            case HirExpr.Def d -> addDefConflicts(d.var(), out, liveness, conflicts);
            case HirExpr.VarAssign a -> addDefConflicts(a.var(), out, liveness, conflicts);
            case HirExpr.Block b -> {
                for (HirVar v : b.declaredVars()) {
                    addDefConflicts(v, in, liveness, conflicts);
                }
                addVarListClique(b.declaredVars(), liveness, conflicts);
            }
            case HirExpr.For f -> {
                addDefConflicts(f.loopVar(), in, liveness, conflicts);
                for (HirVar v : f.bodyDeclaredVars()) {
                    addDefConflicts(v, in, liveness, conflicts);
                }
                addVarListClique(f.bodyDeclaredVars(), liveness, conflicts);
            }
            case HirExpr.Match m -> {
                addDefConflicts(m.scrutineeVar(), in, liveness, conflicts);
                for (HirExpr.MatchArm arm : m.arms()) {
                    for (HirVar v : arm.boundVars()) {
                        addDefConflicts(v, in, liveness, conflicts);
                    }
                    addVarListClique(arm.boundVars(), liveness, conflicts);
                }
                if (m.elseArm() != null) {
                    addDefConflicts(m.elseArm().var(), in, liveness, conflicts);
                }
            }
            case HirExpr.Try t -> {
                for (HirVar v : t.tryDeclaredVars()) {
                    addDefConflicts(v, in, liveness, conflicts);
                }
                for (HirExpr.HirCatch cc : t.catches()) {
                    addDefConflicts(cc.var(), in, liveness, conflicts);
                    for (HirVar v : cc.declaredVars()) {
                        addDefConflicts(v, in, liveness, conflicts);
                    }
                }
                for (HirVar v : t.finallyDeclaredVars()) {
                    addDefConflicts(v, in, liveness, conflicts);
                }
            }
            default -> {}
        }

        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    collectConflicts(child, liveness, conflicts);
                    return child;
                });
    }

    private static void addClique(BitSet set, BitSet[] conflicts) {
        if (set == null || set.cardinality() < 2) {
            return;
        }
        for (int i = set.nextSetBit(0); i >= 0; i = set.nextSetBit(i + 1)) {
            conflicts[i].or(set);
        }
    }

    private static void addDefConflicts(
            HirVar var, BitSet live, HirLiveness liveness, BitSet[] conflicts) {
        if (var == null || live == null || live.isEmpty()) {
            return;
        }
        int idx = liveness.indexOf(var.id());
        if (idx < 0) {
            return;
        }
        conflicts[idx].or(live);
        for (int j = live.nextSetBit(0); j >= 0; j = live.nextSetBit(j + 1)) {
            conflicts[j].set(idx);
        }
    }

    private static void addVarListClique(
            List<HirVar> vars, HirLiveness liveness, BitSet[] conflicts) {
        if (vars.size() < 2) {
            return;
        }
        BitSet bs = new BitSet();
        for (HirVar v : vars) {
            int idx = liveness.indexOf(v.id());
            if (idx >= 0) {
                bs.set(idx);
            }
        }
        addClique(bs, conflicts);
    }

    private static int jvmSlotCategory(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> CAT_INT;
            case FLOAT -> CAT_FLOAT;
            case LONG -> CAT_LONG;
            case DOUBLE -> CAT_DOUBLE;
            case NONE -> CAT_REF;
        };
    }
}
