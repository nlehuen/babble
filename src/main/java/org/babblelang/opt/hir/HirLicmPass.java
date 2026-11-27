package org.babblelang.opt.hir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;

/**
 * {@code O6} (structured subset) — Loop-Invariant Code Motion over {@code while} and {@code for}
 * loops inside frame-scoped functions (BIP014-I2, BIP014-D5).
 *
 * <p>Hoists nontrivial {@code pure deterministic nothrow @TERMINATING} expressions whose result is
 * deeply immutable and whose operands are loop-invariant into a preheader {@link HirExpr.Block}
 * immediately before the loop. Preserves exact zero-iteration semantics, HotSpot OSR empty-stack
 * loop heads, and each hoisted expression's original {@link ExprMeta#line()}.
 */
public final class HirLicmPass implements HirPass {
    @Override
    public String name() {
        return "HirLicm";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] nextVarId = {module.nextVarId()};
        int[] rewrites = {0};
        boolean changed = false;

        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            if (!fn.namespaceScope()) {
                int fnId = fn.id();
                List<HirExpr> nb =
                        HirRewriter.rewriteList(
                                fn.body(),
                                stmt -> hoistInExpr(stmt, fnId, module, nextVarId, rewrites));
                if (nb != fn.body()) {
                    changed = true;
                    fn = fn.withBody(nb);
                }
            }
            newFns.put(e.getKey(), fn);
        }

        if (!changed) {
            return Result.unchanged(module);
        }
        return new Result(
                module.withUpdates(
                        module.topLevelStatements(),
                        newFns,
                        module.classes(),
                        module.interfaces(),
                        nextVarId[0],
                        module.nextOwnerId()),
                rewrites[0]);
    }

    private HirExpr hoistInExpr(
            HirExpr expr, int ownerFnId, HirModule module, int[] nextVarId, int[] rewrites) {
        // Recurse inner loops first (bottom-up), then hoist out of this loop.
        HirExpr cur =
                HirRewriter.rewriteChildren(
                        expr, child -> hoistInExpr(child, ownerFnId, module, nextVarId, rewrites));

        if (cur instanceof HirExpr.While w) {
            return hoistFromWhile(w, ownerFnId, module, nextVarId, rewrites);
        }
        if (cur instanceof HirExpr.For f) {
            return hoistFromFor(f, ownerFnId, module, nextVarId, rewrites);
        }
        return cur;
    }

    private HirExpr hoistFromWhile(
            HirExpr.While w, int ownerFnId, HirModule module, int[] nextVarId, int[] rewrites) {
        Set<Integer> modified = new HashSet<>();
        HirPurity.collectModifiedVars(w.cond(), modified);
        HirPurity.collectModifiedVars(w.body(), modified);

        List<HirVar> hoistedVars = new ArrayList<>();
        List<HirExpr> hoistedDefs = new ArrayList<>();

        HirExpr newBody =
                extractInvariants(
                        w.body(), modified, ownerFnId, module, nextVarId, hoistedVars, hoistedDefs);
        if (hoistedDefs.isEmpty()) {
            return w;
        }
        rewrites[0] += hoistedDefs.size();
        List<HirExpr> blockStmts = new ArrayList<>(hoistedDefs.size() + 1);
        blockStmts.addAll(hoistedDefs);
        blockStmts.add(new HirExpr.While(w.cond(), newBody, w.meta()));
        return new HirExpr.Block(blockStmts, false, new String[0], hoistedVars, -1, w.meta());
    }

    private HirExpr hoistFromFor(
            HirExpr.For f, int ownerFnId, HirModule module, int[] nextVarId, int[] rewrites) {
        Set<Integer> modified = new HashSet<>();
        if (f.loopVar() != null) modified.add(f.loopVar().id());
        for (HirVar v : f.bodyDeclaredVars()) modified.add(v.id());
        for (HirExpr s : f.body()) HirPurity.collectModifiedVars(s, modified);

        List<HirVar> hoistedVars = new ArrayList<>();
        List<HirExpr> hoistedDefs = new ArrayList<>();

        List<HirExpr> newBody =
                HirRewriter.rewriteList(
                        f.body(),
                        s ->
                                extractInvariants(
                                        s,
                                        modified,
                                        ownerFnId,
                                        module,
                                        nextVarId,
                                        hoistedVars,
                                        hoistedDefs));
        if (hoistedDefs.isEmpty()) {
            return f;
        }
        rewrites[0] += hoistedDefs.size();
        List<HirExpr> blockStmts = new ArrayList<>(hoistedDefs.size() + 1);
        blockStmts.addAll(hoistedDefs);
        blockStmts.add(
                new HirExpr.For(
                        f.iterable(),
                        f.target(),
                        f.loopVar(),
                        f.bodyDeclaredVars(),
                        newBody,
                        f.bodyWideningRank(),
                        f.elemSamTarget(),
                        f.countedRange(),
                        f.meta()));
        return new HirExpr.Block(blockStmts, false, new String[0], hoistedVars, -1, f.meta());
    }

    private HirExpr extractInvariants(
            HirExpr expr,
            Set<Integer> modified,
            int ownerFnId,
            HirModule module,
            int[] nextVarId,
            List<HirVar> hoistedVars,
            List<HirExpr> hoistedDefs) {
        if (expr == null) {
            return null;
        }
        // Top-down: if this whole subexpression is a nontrivial loop-invariant pure expression,
        // hoist it as one unit!
        if (isWorthHoisting(expr) && isLoopInvariant(expr, modified, module)) {
            PrimitiveKind varKind = effectiveKind(expr.meta());
            int id = nextVarId[0]++;
            String name = "$licm" + id;
            HirVar var =
                    new HirVar(
                            id,
                            name,
                            Resolution.Variable.Kind.DEFINITION,
                            ownerFnId,
                            varKind,
                            false,
                            true,
                            false,
                            true);
            hoistedVars.add(var);
            ExprMeta defMeta =
                    new ExprMeta(
                            expr.meta().line(),
                            varKind,
                            expr.meta().wideningRank(),
                            null,
                            EffectSet.EMPTY,
                            expr.meta().deeplyImmutable(),
                            expr.meta().stringType());
            hoistedDefs.add(
                    new HirExpr.Def(
                            name,
                            new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1),
                            var,
                            expr,
                            defMeta));
            ExprMeta readMeta =
                    new ExprMeta(
                            expr.meta().line(),
                            varKind,
                            -1,
                            null,
                            EffectSet.EMPTY,
                            expr.meta().deeplyImmutable(),
                            expr.meta().stringType());
            return new HirExpr.VarRead(var, readMeta);
        }

        // Do not descend into statements that define/assign to hoist the whole Def/Assign node
        // itself;
        // descend into their RHS value and subexpressions.
        return HirRewriter.rewriteChildren(
                expr,
                child ->
                        extractInvariants(
                                child,
                                modified,
                                ownerFnId,
                                module,
                                nextVarId,
                                hoistedVars,
                                hoistedDefs));
    }

    /**
     * Only hoist expressions containing a call, immutable field load, or nontrivial computation
     * (never bare Const/VarRead).
     */
    private static boolean isWorthHoisting(HirExpr expr) {
        if (expr instanceof HirExpr.DirectBabbleCall
                || expr instanceof HirExpr.DirectThisMethodCall
                || expr instanceof HirExpr.InterfaceCall
                || expr instanceof HirExpr.CoreMethodCall
                || expr instanceof HirExpr.JavaMethodCall) {
            return true;
        }
        if (expr instanceof HirExpr.ThisFieldGet g
                && ((g.immutableField() && g.definitelyDefined())
                        || g.receiver().meta().frozen())) {
            return true;
        }
        if (expr instanceof HirExpr.Binary || expr instanceof HirExpr.Unary) {
            return containsCallOrNestedOp(expr);
        }
        return false;
    }

    private static boolean containsCallOrNestedOp(HirExpr expr) {
        return switch (expr) {
            case HirExpr.DirectBabbleCall c -> true;
            case HirExpr.DirectThisMethodCall c -> true;
            case HirExpr.InterfaceCall c -> true;
            case HirExpr.JavaMethodCall c -> true;
            case HirExpr.CoreMethodCall c -> true;
            case HirExpr.ThisFieldGet g ->
                    (g.immutableField() && g.definitelyDefined()) || g.receiver().meta().frozen();
            case HirExpr.Binary b ->
                    containsCallOrNestedOp(b.left())
                            || containsCallOrNestedOp(b.right())
                            || b.left() instanceof HirExpr.Binary
                            || b.right() instanceof HirExpr.Binary
                            || b.op() == HirExpr.BinaryOp.DIV;
            case HirExpr.Unary u -> containsCallOrNestedOp(u.operand());
            default -> false;
        };
    }

    private static boolean isLoopInvariant(HirExpr expr, Set<Integer> modified, HirModule module) {
        if (expr.meta().checkedType() != null || !expr.meta().deeplyImmutable()) {
            return false;
        }
        if (!HirPurity.isPureNothrowTerminating(expr, module)
                || !HirPurity.isDeterministic(expr, module)) {
            return false;
        }
        boolean[] ok = {true};
        checkOperandsInvariant(expr, modified, ok);
        return ok[0];
    }

    private static void checkOperandsInvariant(HirExpr expr, Set<Integer> modified, boolean[] ok) {
        if (expr == null || !ok[0]) return;
        switch (expr) {
            case HirExpr.VarRead v -> {
                if (v.var().cell()
                        || modified.contains(v.var().id())
                        || (v.var().mayBeUndefined() && !v.var().definitelyDefined())) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.NameRead n -> {
                if (!(n.finalBinding() && n.definitelyDefined())) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.ThisRef t -> {
                if (t.var() == null || t.var().cell() || modified.contains(t.var().id())) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.DirectBabbleCall c -> {
                if (!(c.callee() instanceof HirExpr.NameRead
                        || c.callee() instanceof HirExpr.FunctionRef)) {
                    checkOperandsInvariant(c.callee(), modified, ok);
                }
                for (HirExpr arg : c.args()) {
                    checkOperandsInvariant(arg, modified, ok);
                }
                return;
            }
            case HirExpr.DirectThisMethodCall c -> {
                if (!c.receiver().meta().deeplyImmutable() && !c.receiver().meta().frozen()) {
                    ok[0] = false;
                    return;
                }
                checkOperandsInvariant(c.receiver(), modified, ok);
                for (org.babblelang.ir.hir.HirCallArg arg : c.args()) {
                    checkOperandsInvariant(arg.value(), modified, ok);
                }
                return;
            }
            case HirExpr.InterfaceCall c -> {
                if (!c.receiver().meta().deeplyImmutable() && !c.receiver().meta().frozen()) {
                    ok[0] = false;
                    return;
                }
                checkOperandsInvariant(c.receiver(), modified, ok);
                for (HirExpr arg : c.args()) {
                    checkOperandsInvariant(arg, modified, ok);
                }
                return;
            }
            case HirExpr.JavaMethodCall c -> {
                if (!c.target().isStatic() || !c.pureStaticReceiver()) {
                    checkOperandsInvariant(c.receiver(), modified, ok);
                }
                for (HirExpr arg : c.args()) {
                    checkOperandsInvariant(arg, modified, ok);
                }
                return;
            }
            case HirExpr.CoreMethodCall c -> {
                checkOperandsInvariant(c.receiver(), modified, ok);
                for (HirExpr arg : c.args()) {
                    checkOperandsInvariant(arg, modified, ok);
                }
                return;
            }
            case HirExpr.Const c -> {
                return;
            }
            case HirExpr.Unary u -> {
                checkOperandsInvariant(u.operand(), modified, ok);
                return;
            }
            case HirExpr.Binary b -> {
                checkOperandsInvariant(b.left(), modified, ok);
                checkOperandsInvariant(b.right(), modified, ok);
                return;
            }
            case HirExpr.Logic l -> {
                checkOperandsInvariant(l.left(), modified, ok);
                checkOperandsInvariant(l.right(), modified, ok);
                return;
            }
            case HirExpr.ThisFieldGet g -> {
                checkOperandsInvariant(g.receiver(), modified, ok);
                return;
            }
            case HirExpr.ModuleExportGet g -> {
                if (!g.target().definitelyInitialized()) {
                    ok[0] = false;
                    return;
                }
                checkOperandsInvariant(g.receiver(), modified, ok);
                return;
            }
            case HirExpr.Move m -> {
                checkOperandsInvariant(m.operand(), modified, ok);
                return;
            }
            case HirExpr.Freeze f -> {
                checkOperandsInvariant(f.operand(), modified, ok);
                return;
            }
            case HirExpr.Narrow n -> {
                // Conservative in P1: guardId loop-local check is upgraded in BIP039-P2.
                ok[0] = false;
                return;
            }
            case HirExpr.Convert cv -> {
                checkOperandsInvariant(cv.operand(), modified, ok);
                return;
            }
            case HirExpr.Widen w -> {
                checkOperandsInvariant(w.operand(), modified, ok);
                return;
            }
            case HirExpr.Block b -> {
                if (b.opensNamespaceScope() || !b.declaredVars().isEmpty()) {
                    ok[0] = false;
                    return;
                }
                for (HirExpr s : b.statements()) {
                    checkOperandsInvariant(s, modified, ok);
                }
                return;
            }
            case HirExpr.As a -> {
                if (a.targetDescriptor() != null || a.samTarget() != null) {
                    ok[0] = false;
                    return;
                }
                checkOperandsInvariant(a.left(), modified, ok);
                return;
            }
            default -> {
                ok[0] = false;
                return;
            }
        }
    }

    private static PrimitiveKind effectiveKind(ExprMeta meta) {
        if (meta.wideningRank() < 0) {
            return meta.kind();
        }
        return switch (meta.wideningRank()) {
            case 1 -> PrimitiveKind.SHORT;
            case 2 -> PrimitiveKind.INT;
            case 3 -> PrimitiveKind.LONG;
            case 4 -> PrimitiveKind.FLOAT;
            case 5 -> PrimitiveKind.DOUBLE;
            default -> meta.kind();
        };
    }
}
