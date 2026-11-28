package org.babblelang.opt.hir;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.hir.IrEffect;
import org.babblelang.opt.HirEffectAnalysis;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;

/**
 * Soundness-analysis and AST-inspection helpers for {@link HirPipelineLifter} ({@code BIP042-I3},
 * {@code BIP042-D5}).
 */
final class HirPipelineAnalysis {
    private HirPipelineAnalysis() {}

    static boolean areProducersDisjointFromConsumer(
            HirModule module,
            HirEffectAnalysis fx,
            Map<Integer, HirVar> freshLocalLists,
            Map<Integer, HirVar> freshLocalSets,
            List<HirExpr.For> producers,
            List<HirVar> tempVars,
            HirExpr.For consumer) {
        Set<Integer> tempIds = new HashSet<>();
        for (HirVar tv : tempVars) {
            tempIds.add(tv.id());
        }
        Set<Integer> consumerWrittenVars = collectModifiedVars(consumer.body());
        boolean consumerWritesName = containsNameAssign(consumer.body());

        Set<Integer> producerReadVars = new HashSet<>();
        boolean producerReadsMutableName = false;
        Set<IrEffect.AliasClass> producerHeapReads = new HashSet<>();

        for (int i = 0; i < producers.size(); i++) {
            HirExpr.For pf = producers.get(i);
            collectReadVars(pf, producerReadVars);
            if (containsMutableNameRead(pf)) {
                producerReadsMutableName = true;
            }
            if (i == 0 && pf.countedRange() == null && pf.iterable() != null) {
                producerHeapReads.add(fx.aliasOf(pf.iterable()));
            }
            for (HirExpr s : pf.body()) {
                scanProducerHeapReads(module, fx, s, tempIds, producerHeapReads);
            }
        }
        for (int w : consumerWrittenVars) {
            if (producerReadVars.contains(w)) {
                return false;
            }
        }
        if (consumerWritesName && producerReadsMutableName) {
            return false;
        }
        if (producerHeapReads.isEmpty()) {
            return true;
        }
        boolean[] conflict = {false};
        for (HirExpr s : consumer.body()) {
            scanConsumerWrites(
                    fx,
                    freshLocalLists,
                    freshLocalSets,
                    s,
                    producerHeapReads,
                    producerReadVars,
                    conflict);
            if (conflict[0]) {
                return false;
            }
        }
        return true;
    }

    static boolean isCollectionAddOn(HirExpr expr, Predicate<Integer> receiverVarMatches) {
        return expr instanceof HirExpr.JavaMethodCall c
                && !c.target().isStatic()
                && "add".equals(c.target().method().getName())
                && c.args().size() == 1
                && HirRewriter.unwrapTransparent(c.receiver()) instanceof HirExpr.VarRead vr
                && receiverVarMatches.test(vr.var().id());
    }

    private static void scanConsumerWrites(
            HirEffectAnalysis fx,
            Map<Integer, HirVar> freshLocalLists,
            Map<Integer, HirVar> freshLocalSets,
            HirExpr expr,
            Set<IrEffect.AliasClass> producerReads,
            Set<Integer> producerReadVars,
            boolean[] conflict) {
        if (conflict[0]) {
            return;
        }
        if (isCollectionAddOn(
                expr,
                id ->
                        (freshLocalLists.containsKey(id) || freshLocalSets.containsKey(id))
                                && !producerReadVars.contains(id))) {
            HirExpr.JavaMethodCall c = (HirExpr.JavaMethodCall) expr;
            scanConsumerWrites(
                    fx,
                    freshLocalLists,
                    freshLocalSets,
                    c.args().get(0),
                    producerReads,
                    producerReadVars,
                    conflict);
            return;
        }
        if (expr instanceof HirExpr.VarAssign va
                && !va.var().cell()
                && (va.operator() == null || va.var().primitiveKind().isPrimitive())) {
            scanConsumerWrites(
                    fx,
                    freshLocalLists,
                    freshLocalSets,
                    va.value(),
                    producerReads,
                    producerReadVars,
                    conflict);
            return;
        }
        IrEffect eff = fx.shallowEffectOf(expr);
        if (eff.kind() == IrEffect.Kind.IMPURE || eff.kind() == IrEffect.Kind.WRITE) {
            for (IrEffect.AliasClass readAlias : producerReads) {
                if (readAlias.overlapsWrite(eff.alias())) {
                    conflict[0] = true;
                    return;
                }
            }
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    scanConsumerWrites(
                            fx,
                            freshLocalLists,
                            freshLocalSets,
                            child,
                            producerReads,
                            producerReadVars,
                            conflict);
                    return child;
                });
    }

    private static void scanProducerHeapReads(
            HirModule module,
            HirEffectAnalysis fx,
            HirExpr expr,
            Set<Integer> tempIds,
            Set<IrEffect.AliasClass> out) {
        if (isCollectionAddOn(expr, tempIds::contains)) {
            scanProducerHeapReads(
                    module, fx, ((HirExpr.JavaMethodCall) expr).args().get(0), tempIds, out);
            return;
        }
        if (expr instanceof HirExpr.DirectBabbleCall c
                && HirPurity.isPureNothrowTerminating(c, module)) {
            for (HirExpr arg : c.args()) {
                scanProducerHeapReads(module, fx, arg, tempIds, out);
            }
            return;
        }
        IrEffect eff = fx.shallowEffectOf(expr);
        if (eff.kind() == IrEffect.Kind.READ) {
            if (eff.alias() != IrEffect.AliasClass.FROZEN) {
                out.add(eff.alias());
            }
        } else if (eff.kind() == IrEffect.Kind.WRITE || eff.kind() == IrEffect.Kind.IMPURE) {
            out.add(IrEffect.AliasClass.SHARED);
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    scanProducerHeapReads(module, fx, child, tempIds, out);
                    return child;
                });
    }

    static boolean iteratesVar(HirExpr.For f, HirVar var) {
        return f.countedRange() == null
                && f.iterable() != null
                && HirRewriter.unwrapTransparent(f.iterable()) instanceof HirExpr.VarRead vr
                && vr.var().id() == var.id();
    }

    static boolean isUnitStep(HirExpr step) {
        if (step == null) {
            return true;
        }
        HirExpr u = HirRewriter.unwrapTransparent(step);
        return u instanceof HirExpr.Const c && c.value() instanceof Number n && n.longValue() == 1L;
    }

    static boolean isVarReflexiveEq(HirVar v, HirExpr.For f) {
        return v.primitiveKind().isPrimitive();
    }

    static HirExpr unwrapStmtConvert(HirExpr expr) {
        HirExpr cur = HirRewriter.unwrapTransparent(expr);
        while (cur instanceof HirExpr.Convert cv && cv.targetKind() == PrimitiveKind.NONE) {
            cur = HirRewriter.unwrapTransparent(cv.operand());
        }
        return cur;
    }

    static HirExpr unwrapBoxConvert(HirExpr expr) {
        HirExpr cur = HirRewriter.unwrapTransparent(expr);
        if (cur instanceof HirExpr.Convert cv
                && cv.targetKind() == PrimitiveKind.NONE
                && cv.operand().meta().kind().isPrimitive()
                && cv.meta().checkedType() == null) {
            return HirRewriter.unwrapTransparent(cv.operand());
        }
        return cur;
    }

    static HirExpr extractListAddArg(HirExpr stmt, HirVar tmpVar) {
        if (!isCollectionAddOn(stmt, id -> id == tmpVar.id())) {
            return null;
        }
        HirExpr.JavaMethodCall c = (HirExpr.JavaMethodCall) stmt;
        return c.meta().checkedType() == null ? c.args().get(0) : null;
    }

    private static final Set<Class<?>> CONTROL_FLOW_EXIT_TYPES =
            Set.of(
                    HirExpr.Break.class,
                    HirExpr.Continue.class,
                    HirExpr.Return.class,
                    HirExpr.Throw.class);

    private static final Set<Class<?>> DECL_OR_CONTROL_FLOW_TYPES =
            Set.of(
                    HirExpr.Def.class,
                    HirExpr.Break.class,
                    HirExpr.Continue.class,
                    HirExpr.Return.class,
                    HirExpr.Throw.class,
                    HirExpr.For.class,
                    HirExpr.While.class);

    static void walkExpr(HirExpr expr, Consumer<HirExpr> visitor) {
        visitor.accept(expr);
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    walkExpr(child, visitor);
                    return child;
                });
    }

    static int countVarRefsInBody(List<HirExpr> stmts, int varId) {
        int total = 0;
        for (HirExpr s : stmts) {
            total += countVarRefs(s, varId);
        }
        return total;
    }

    static int countVarRefs(HirExpr expr, int varId) {
        int[] count = {0};
        walkExpr(
                expr,
                e -> {
                    if ((e instanceof HirExpr.VarRead vr && vr.var().id() == varId)
                            || (e instanceof HirExpr.VarAssign va && va.var().id() == varId)) {
                        count[0]++;
                    }
                });
        return count[0];
    }

    static Set<Integer> collectModifiedVars(List<HirExpr> stmts) {
        Set<Integer> out = new HashSet<>();
        for (HirExpr s : stmts) {
            walkExpr(
                    s,
                    e -> {
                        if (e instanceof HirExpr.VarAssign va) {
                            out.add(va.var().id());
                        }
                    });
        }
        return out;
    }

    /**
     * Collects the locals assigned in the body of any {@link HirExpr.For} in {@code stmts}
     * (including nested loops) : the values a lifted pipeline must not treat as loop-invariant.
     */
    static Set<Integer> collectLoopVariantVars(List<HirExpr> stmts) {
        Set<Integer> out = new HashSet<>();
        for (HirExpr s : stmts) {
            walkExpr(
                    s,
                    e -> {
                        if (e instanceof HirExpr.For f) {
                            out.addAll(collectModifiedVars(f.body()));
                        }
                    });
        }
        return out;
    }

    static void collectReadVars(HirExpr expr, Set<Integer> out) {
        walkExpr(
                expr,
                e -> {
                    if (e instanceof HirExpr.VarRead vr) {
                        out.add(vr.var().id());
                    }
                });
    }

    static void collectReferencedBinders(
            HirExpr expr, Map<Integer, String> varIdToBinder, Set<String> out) {
        walkExpr(
                expr,
                e -> {
                    if (e instanceof HirExpr.VarRead vr) {
                        String b = varIdToBinder.get(vr.var().id());
                        if (b != null) {
                            out.add(b);
                        }
                    }
                });
    }

    static boolean anyExpr(HirExpr expr, Predicate<HirExpr> predicate) {
        boolean[] found = {false};
        walkExpr(
                expr,
                e -> {
                    if (!found[0] && predicate.test(e)) {
                        found[0] = true;
                    }
                });
        return found[0];
    }

    private static boolean anyStmt(List<HirExpr> stmts, Predicate<HirExpr> predicate) {
        for (HirExpr s : stmts) {
            if (anyExpr(s, predicate)) {
                return true;
            }
        }
        return false;
    }

    static boolean containsControlFlowExit(List<HirExpr> stmts) {
        return anyStmt(stmts, e -> CONTROL_FLOW_EXIT_TYPES.contains(e.getClass()));
    }

    static boolean containsNarrowOrRightNarrowed(HirExpr expr) {
        return anyExpr(
                expr,
                e ->
                        e instanceof HirExpr.Narrow
                                || (e instanceof HirExpr.Logic l && l.rightNarrowed()));
    }

    static boolean containsNameAssign(List<HirExpr> stmts) {
        return anyStmt(stmts, e -> e instanceof HirExpr.NameAssign);
    }

    static boolean containsMutableNameRead(HirExpr expr) {
        return anyExpr(expr, e -> e instanceof HirExpr.NameRead nr && !nr.finalBinding());
    }

    static boolean containsDeclOrControlFlow(HirExpr expr) {
        return anyExpr(
                expr,
                e ->
                        DECL_OR_CONTROL_FLOW_TYPES.contains(e.getClass())
                                || (e instanceof HirExpr.Block blk
                                        && !blk.declaredVars().isEmpty()));
    }

    static int hirNodeCount(HirExpr expr) {
        int[] count = {0};
        walkExpr(expr, e -> count[0]++);
        return count[0];
    }
}
