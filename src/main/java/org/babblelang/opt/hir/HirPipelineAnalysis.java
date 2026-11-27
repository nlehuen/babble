package org.babblelang.opt.hir;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.egraph.ENode;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.hir.IrEffect;
import org.babblelang.opt.HirEffectAnalysis;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;

/**
 * Soundness-analysis and AST-inspection helpers for {@link HirPipelineLifter} ({@code BIP042-P3},
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

    private static void scanConsumerWrites(
            HirEffectAnalysis fx,
            Map<Integer, HirVar> freshLocalLists,
            Map<Integer, HirVar> freshLocalSets,
            HirExpr expr,
            Set<IrEffect.AliasClass> producerReads,
            Set<Integer> producerReadVars,
            boolean[] conflict) {
        if (expr == null || conflict[0]) {
            return;
        }
        if (expr instanceof HirExpr.JavaMethodCall c
                && !c.target().isStatic()
                && "add".equals(c.target().method().getName())
                && Collection.class.isAssignableFrom(c.target().method().getDeclaringClass())
                && HirRewriter.unwrapTransparent(c.receiver()) instanceof HirExpr.VarRead vr
                && (freshLocalLists.containsKey(vr.var().id())
                        || freshLocalSets.containsKey(vr.var().id()))
                && !producerReadVars.contains(vr.var().id())) {
            for (HirExpr arg : c.args()) {
                scanConsumerWrites(
                        fx,
                        freshLocalLists,
                        freshLocalSets,
                        arg,
                        producerReads,
                        producerReadVars,
                        conflict);
            }
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
        if (expr == null) {
            return;
        }
        if (expr instanceof HirExpr.JavaMethodCall c
                && !c.target().isStatic()
                && "add".equals(c.target().method().getName())
                && HirRewriter.unwrapTransparent(c.receiver()) instanceof HirExpr.VarRead vr
                && tempIds.contains(vr.var().id())) {
            for (HirExpr arg : c.args()) {
                scanProducerHeapReads(module, fx, arg, tempIds, out);
            }
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
        if (v.primitiveKind().isPrimitive()) {
            return true;
        }
        return f.target() != null && f.target().elemKind().isPrimitive();
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
        if (!(stmt instanceof HirExpr.JavaMethodCall c)
                || c.target().isStatic()
                || !"add".equals(c.target().method().getName())
                || c.args().size() != 1
                || !Collection.class.isAssignableFrom(c.target().method().getDeclaringClass())
                || c.meta().checkedType() != null) {
            return null;
        }
        HirExpr recv = HirRewriter.unwrapTransparent(c.receiver());
        if (!(recv instanceof HirExpr.VarRead vr) || vr.var().id() != tmpVar.id()) {
            return null;
        }
        return c.args().get(0);
    }

    static boolean isLowerableComparison(HirExpr.Binary b) {
        if (!b.op().isComparison()) {
            return false;
        }
        if (b.left().meta().wideningRank() >= 0 || b.right().meta().wideningRank() >= 0) {
            return false;
        }
        PrimitiveKind lk = b.left().meta().kind();
        PrimitiveKind rk = b.right().meta().kind();
        if (b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ) {
            return (lk.isPrimitive() && lk == rk)
                    || (b.left().meta().stringType() && b.right().meta().stringType());
        }
        return lk == rk && (HirPurity.isNumericKind(lk) || lk == PrimitiveKind.CHAR);
    }

    static ENode.Op toComparisonOp(HirExpr.BinaryOp op) {
        return switch (op) {
            case EQ -> ENode.Op.EQ;
            case NEQ -> ENode.Op.NEQ;
            case LT -> ENode.Op.LT;
            case LTE -> ENode.Op.LTE;
            case GT -> ENode.Op.GT;
            case GTE -> ENode.Op.GTE;
            default -> throw new IllegalStateException();
        };
    }

    static int countVarRefsInBody(List<HirExpr> stmts, int varId) {
        int total = 0;
        for (HirExpr s : stmts) {
            total += countVarRefs(s, varId);
        }
        return total;
    }

    static int countVarRefs(HirExpr expr, int varId) {
        if (expr == null) {
            return 0;
        }
        int[] count = {0};
        if (expr instanceof HirExpr.VarRead vr && vr.var().id() == varId) {
            count[0]++;
        } else if (expr instanceof HirExpr.VarAssign va && va.var().id() == varId) {
            count[0]++;
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    count[0] += countVarRefs(child, varId);
                    return child;
                });
        return count[0];
    }

    static Set<Integer> collectModifiedVars(List<HirExpr> stmts) {
        Set<Integer> out = new HashSet<>();
        for (HirExpr s : stmts) {
            collectModifiedVarsExpr(s, out);
        }
        return out;
    }

    private static void collectModifiedVarsExpr(HirExpr expr, Set<Integer> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof HirExpr.VarAssign va) {
            out.add(va.var().id());
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    collectModifiedVarsExpr(child, out);
                    return child;
                });
    }

    static void collectReadVars(HirExpr expr, Set<Integer> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof HirExpr.VarRead vr) {
            out.add(vr.var().id());
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    collectReadVars(child, out);
                    return child;
                });
    }

    static void collectReferencedBinders(
            HirExpr expr, Map<Integer, String> varIdToBinder, Set<String> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof HirExpr.VarRead vr) {
            String b = varIdToBinder.get(vr.var().id());
            if (b != null) {
                out.add(b);
            }
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    collectReferencedBinders(child, varIdToBinder, out);
                    return child;
                });
    }

    static boolean containsControlFlowExit(List<HirExpr> stmts) {
        for (HirExpr s : stmts) {
            if (containsControlFlowExitExpr(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsControlFlowExitExpr(HirExpr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof HirExpr.Break
                || expr instanceof HirExpr.Continue
                || expr instanceof HirExpr.Return
                || expr instanceof HirExpr.Throw) {
            return true;
        }
        boolean[] found = {false};
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    if (!found[0] && containsControlFlowExitExpr(child)) {
                        found[0] = true;
                    }
                    return child;
                });
        return found[0];
    }

    static boolean containsNarrowOrRightNarrowed(HirExpr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof HirExpr.Narrow
                || (expr instanceof HirExpr.Logic l && l.rightNarrowed())) {
            return true;
        }
        boolean[] found = {false};
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    if (!found[0] && containsNarrowOrRightNarrowed(child)) {
                        found[0] = true;
                    }
                    return child;
                });
        return found[0];
    }

    static boolean containsNameAssign(List<HirExpr> stmts) {
        for (HirExpr s : stmts) {
            if (containsNameAssignExpr(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNameAssignExpr(HirExpr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof HirExpr.NameAssign) {
            return true;
        }
        boolean[] found = {false};
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    if (!found[0] && containsNameAssignExpr(child)) {
                        found[0] = true;
                    }
                    return child;
                });
        return found[0];
    }

    static boolean containsMutableNameRead(HirExpr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof HirExpr.NameRead nr && !nr.finalBinding()) {
            return true;
        }
        boolean[] found = {false};
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    if (!found[0] && containsMutableNameRead(child)) {
                        found[0] = true;
                    }
                    return child;
                });
        return found[0];
    }

    static boolean containsDeclOrControlFlow(HirExpr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof HirExpr.Def
                || expr instanceof HirExpr.Break
                || expr instanceof HirExpr.Continue
                || expr instanceof HirExpr.Return
                || expr instanceof HirExpr.Throw
                || expr instanceof HirExpr.For
                || expr instanceof HirExpr.While
                || (expr instanceof HirExpr.Block blk && !blk.declaredVars().isEmpty())) {
            return true;
        }
        boolean[] found = {false};
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    if (!found[0] && containsDeclOrControlFlow(child)) {
                        found[0] = true;
                    }
                    return child;
                });
        return found[0];
    }

    static int hirNodeCount(HirExpr expr) {
        if (expr == null) {
            return 0;
        }
        int[] count = {1};
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    count[0] += hirNodeCount(child);
                    return child;
                });
        return count[0];
    }
}
