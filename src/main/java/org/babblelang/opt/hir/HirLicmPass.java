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
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.hir.IrEffect;
import org.babblelang.opt.HirEffectAnalysis;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;

/**
 * {@code O6} — Full Loop-Invariant Code Motion over {@code while} (both header condition and body)
 * and {@code for} loops inside frame-scoped functions ({@code BIP014-I2}, {@code BIP039-I2}).
 *
 * <p>Uses {@link HirEffectAnalysis} and {@link IrEffect} to hoist:
 *
 * <ul>
 *   <li>Loop-invariant {@code PURE} ({@code nothrow @TERMINATING deterministic}) calls, arithmetic,
 *       conversions, {@code NameRead}s, and {@code ModuleExportGet}s.
 *   <li>Loop-invariant {@code READ}s ({@code ThisFieldGet}, {@code JavaFieldGet}, {@code
 *       ArrayOp.SIZE}) whenever no write or impure barrier inside the loop overlaps their {@link
 *       IrEffect.AliasClass} ({@code FROZEN}, disjoint {@code UniqueParam(k)}, or un-clobbered
 *       {@code Shared}).
 *   <li>Expressions from both {@code While.cond()} and {@code While.body()} (as well as {@code
 *       For.body()}), while blocking motion of reads anchored to loop-internal {@link
 *       HirExpr.Narrow} guards.
 * </ul>
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
                HirEffectAnalysis fx = HirEffectAnalysis.forFunction(fn, module);
                List<HirExpr> nb =
                        HirRewriter.rewriteList(
                                fn.body(),
                                stmt -> hoistInExpr(stmt, fnId, module, fx, nextVarId, rewrites));
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
            HirExpr expr,
            int ownerFnId,
            HirModule module,
            HirEffectAnalysis fx,
            int[] nextVarId,
            int[] rewrites) {
        // Recurse inner loops first (bottom-up), then hoist out of this loop.
        HirExpr cur =
                HirRewriter.rewriteChildren(
                        expr,
                        child -> hoistInExpr(child, ownerFnId, module, fx, nextVarId, rewrites));

        if (cur instanceof HirExpr.While w) {
            return hoistFromWhile(w, ownerFnId, module, fx, nextVarId, rewrites);
        }
        if (cur instanceof HirExpr.For f) {
            return hoistFromFor(f, ownerFnId, module, fx, nextVarId, rewrites);
        }
        return cur;
    }

    private HirExpr hoistFromWhile(
            HirExpr.While w,
            int ownerFnId,
            HirModule module,
            HirEffectAnalysis fx,
            int[] nextVarId,
            int[] rewrites) {
        Set<Integer> modified = new HashSet<>();
        HirPurity.collectModifiedVars(w.cond(), modified);
        HirPurity.collectModifiedVars(w.body(), modified);

        List<IrEffect> barriers = new ArrayList<>();
        collectLoopBarriers(w.cond(), fx, barriers);
        collectLoopBarriers(w.body(), fx, barriers);

        Set<Integer> localGuardVars = new HashSet<>();
        collectGuardTestedVars(w.cond(), localGuardVars);
        collectGuardTestedVarsInTree(w.body(), localGuardVars);
        // Any guard inside cond itself only guards cond's own RHS (e.g. s != null && s.length() >
        // i)
        Set<Integer> condGuardVars = new HashSet<>();
        collectGuardTestedVarsInTree(w.cond(), condGuardVars);

        List<HirVar> hoistedVars = new ArrayList<>();
        List<HirExpr> hoistedDefs = new ArrayList<>();

        HirExpr newCond =
                extractInvariants(
                        w.cond(),
                        true,
                        modified,
                        barriers,
                        condGuardVars,
                        ownerFnId,
                        module,
                        fx,
                        nextVarId,
                        hoistedVars,
                        hoistedDefs);
        HirExpr newBody =
                extractInvariants(
                        w.body(),
                        false,
                        modified,
                        barriers,
                        localGuardVars,
                        ownerFnId,
                        module,
                        fx,
                        nextVarId,
                        hoistedVars,
                        hoistedDefs);
        if (hoistedDefs.isEmpty()) {
            return w;
        }
        rewrites[0] += hoistedDefs.size();
        List<HirExpr> blockStmts = new ArrayList<>(hoistedDefs.size() + 1);
        blockStmts.addAll(hoistedDefs);
        blockStmts.add(new HirExpr.While(newCond, newBody, w.meta()));
        return new HirExpr.Block(blockStmts, false, new String[0], hoistedVars, -1, w.meta());
    }

    private HirExpr hoistFromFor(
            HirExpr.For f,
            int ownerFnId,
            HirModule module,
            HirEffectAnalysis fx,
            int[] nextVarId,
            int[] rewrites) {
        Set<Integer> modified = new HashSet<>();
        if (f.loopVar() != null) modified.add(f.loopVar().id());
        for (HirVar v : f.bodyDeclaredVars()) modified.add(v.id());
        for (HirExpr s : f.body()) HirPurity.collectModifiedVars(s, modified);

        List<IrEffect> barriers = new ArrayList<>();
        for (HirExpr s : f.body()) {
            collectLoopBarriers(s, fx, barriers);
        }

        Set<Integer> localGuardVars = new HashSet<>();
        for (HirExpr s : f.body()) {
            collectGuardTestedVarsInTree(s, localGuardVars);
        }

        List<HirVar> hoistedVars = new ArrayList<>();
        List<HirExpr> hoistedDefs = new ArrayList<>();

        List<HirExpr> newBody =
                HirRewriter.rewriteList(
                        f.body(),
                        s ->
                                extractInvariants(
                                        s,
                                        false,
                                        modified,
                                        barriers,
                                        localGuardVars,
                                        ownerFnId,
                                        module,
                                        fx,
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
            boolean inHeader,
            Set<Integer> modified,
            List<IrEffect> barriers,
            Set<Integer> localGuardVars,
            int ownerFnId,
            HirModule module,
            HirEffectAnalysis fx,
            int[] nextVarId,
            List<HirVar> hoistedVars,
            List<HirExpr> hoistedDefs) {
        if (expr == null) {
            return null;
        }
        if (isWorthHoisting(expr, inHeader, fx)
                && isLoopInvariant(expr, modified, barriers, localGuardVars, module, fx)) {
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
                            expr.meta().stringType(),
                            expr.meta().frozen(),
                            expr.meta().unique());
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
                            expr.meta().stringType(),
                            expr.meta().frozen(),
                            expr.meta().unique());
            return new HirExpr.VarRead(var, readMeta);
        }

        // Do not hoist a loop-local guard's condition expression into a parent if it's a Logic
        // short-circuit where the RHS relies on the LHS narrowing; descending into children
        // naturally respects localGuardVars.
        return HirRewriter.rewriteChildren(
                expr,
                child ->
                        extractInvariants(
                                child,
                                inHeader,
                                modified,
                                barriers,
                                localGuardVars,
                                ownerFnId,
                                module,
                                fx,
                                nextVarId,
                                hoistedVars,
                                hoistedDefs));
    }

    private static boolean isWorthHoisting(HirExpr expr, boolean inHeader, HirEffectAnalysis fx) {
        if (expr instanceof HirExpr.DirectBabbleCall
                || expr instanceof HirExpr.DirectThisMethodCall
                || expr instanceof HirExpr.InterfaceCall
                || expr instanceof HirExpr.CoreMethodCall
                || expr instanceof HirExpr.JavaMethodCall) {
            return true;
        }
        if (expr instanceof HirExpr.ThisFieldGet || expr instanceof HirExpr.JavaFieldGet) {
            IrEffect efx = fx.shallowEffectOf(expr);
            return efx.isPureFloatable() || efx.isReadFloatable();
        }
        if (expr instanceof HirExpr.ArrayOp op && op.kind() == LoweringTargets.ArrayOpKind.SIZE) {
            return true;
        }
        if (expr instanceof HirExpr.ModuleExportGet) {
            return true;
        }
        if (expr instanceof HirExpr.Binary b) {
            if (inHeader && !b.op().isComparison()) {
                return true;
            }
            return containsCallOrNestedOp(b);
        }
        if (expr instanceof HirExpr.Unary u) {
            return containsCallOrNestedOp(u);
        }
        return false;
    }

    private static boolean containsCallOrNestedOp(HirExpr expr) {
        return switch (expr) {
            case HirExpr.DirectBabbleCall ignored -> true;
            case HirExpr.DirectThisMethodCall ignored -> true;
            case HirExpr.InterfaceCall ignored -> true;
            case HirExpr.JavaMethodCall ignored -> true;
            case HirExpr.CoreMethodCall ignored -> true;
            case HirExpr.ThisFieldGet ignored -> true;
            case HirExpr.JavaFieldGet ignored -> true;
            case HirExpr.ArrayOp op -> op.kind() == LoweringTargets.ArrayOpKind.SIZE;
            case HirExpr.Binary b ->
                    containsCallOrNestedOp(b.left())
                            || containsCallOrNestedOp(b.right())
                            || b.left() instanceof HirExpr.Binary
                            || b.right() instanceof HirExpr.Binary
                            || b.op() == HirExpr.BinaryOp.DIV;
            case HirExpr.Unary u -> containsCallOrNestedOp(u.operand());
            case HirExpr.Convert cv -> containsCallOrNestedOp(cv.operand());
            case HirExpr.Widen w -> containsCallOrNestedOp(w.operand());
            default -> false;
        };
    }

    private static boolean isLoopInvariant(
            HirExpr expr,
            Set<Integer> modified,
            List<IrEffect> barriers,
            Set<Integer> localGuardVars,
            HirModule module,
            HirEffectAnalysis fx) {
        if (expr.meta().checkedType() != null) {
            return false;
        }
        // Never hoist fresh mutable allocations or calls returning mutable objects.
        boolean isFieldOrLen =
                expr instanceof HirExpr.ThisFieldGet
                        || expr instanceof HirExpr.JavaFieldGet
                        || (expr instanceof HirExpr.ArrayOp op
                                && op.kind() == LoweringTargets.ArrayOpKind.SIZE)
                        || expr instanceof HirExpr.ModuleExportGet;
        if (!isFieldOrLen && !expr.meta().deeplyImmutable()) {
            return false;
        }
        IrEffect efx = fx.effectOf(expr);
        if (!efx.isPureFloatable()) {
            if (!efx.isReadFloatable()) {
                return false;
            }
            for (IrEffect barrier : barriers) {
                if (barrier.isMemoryBarrierFor(efx.alias())) {
                    return false;
                }
            }
        }
        // Keep full HirPurity verification for calls whose body might read mutable state.
        if ((expr instanceof HirExpr.DirectBabbleCall
                        || expr instanceof HirExpr.DirectThisMethodCall
                        || expr instanceof HirExpr.InterfaceCall
                        || expr instanceof HirExpr.CoreMethodCall
                        || expr instanceof HirExpr.JavaMethodCall)
                && (!HirPurity.isPureNothrowTerminating(expr, module)
                        || !HirPurity.isDeterministic(expr, module))) {
            return false;
        }
        boolean[] ok = {true};
        checkOperandsInvariant(expr, modified, localGuardVars, ok);
        return ok[0];
    }

    private static void checkOperandsInvariant(
            HirExpr expr, Set<Integer> modified, Set<Integer> localGuardVars, boolean[] ok) {
        if (expr == null || !ok[0]) return;
        switch (expr) {
            case HirExpr.VarRead v -> {
                if (v.var().cell()
                        || modified.contains(v.var().id())
                        || (v.var().mayBeUndefined() && !v.var().definitelyDefined())) {
                    ok[0] = false;
                }
            }
            case HirExpr.NameRead n -> {
                if (!(n.finalBinding() && n.definitelyDefined())) {
                    ok[0] = false;
                }
            }
            case HirExpr.ThisRef t -> {
                if (t.var() == null || t.var().cell() || modified.contains(t.var().id())) {
                    ok[0] = false;
                }
            }
            case HirExpr.Narrow n -> {
                HirExpr inner = n.operand();
                if (inner instanceof HirExpr.VarRead vr && localGuardVars.contains(vr.var().id())) {
                    ok[0] = false;
                    return;
                }
                checkOperandsInvariant(inner, modified, localGuardVars, ok);
            }
            case HirExpr.DirectBabbleCall c -> {
                if (!(c.callee() instanceof HirExpr.NameRead
                        || c.callee() instanceof HirExpr.FunctionRef)) {
                    checkOperandsInvariant(c.callee(), modified, localGuardVars, ok);
                }
                for (HirExpr arg : c.args()) {
                    checkOperandsInvariant(arg, modified, localGuardVars, ok);
                }
            }
            case HirExpr.DirectThisMethodCall c -> {
                if (!c.receiver().meta().deeplyImmutable() && !c.receiver().meta().frozen()) {
                    ok[0] = false;
                    return;
                }
                checkOperandsInvariant(c.receiver(), modified, localGuardVars, ok);
                for (HirCallArg arg : c.args()) {
                    checkOperandsInvariant(arg.value(), modified, localGuardVars, ok);
                }
            }
            case HirExpr.InterfaceCall c -> {
                if (!c.receiver().meta().deeplyImmutable() && !c.receiver().meta().frozen()) {
                    ok[0] = false;
                    return;
                }
                checkOperandsInvariant(c.receiver(), modified, localGuardVars, ok);
                for (HirExpr arg : c.args()) {
                    checkOperandsInvariant(arg, modified, localGuardVars, ok);
                }
            }
            case HirExpr.JavaMethodCall c -> {
                if (!c.target().isStatic() || !c.pureStaticReceiver()) {
                    checkOperandsInvariant(c.receiver(), modified, localGuardVars, ok);
                }
                for (HirExpr arg : c.args()) {
                    checkOperandsInvariant(arg, modified, localGuardVars, ok);
                }
            }
            case HirExpr.CoreMethodCall c -> {
                checkOperandsInvariant(c.receiver(), modified, localGuardVars, ok);
                for (HirExpr arg : c.args()) {
                    checkOperandsInvariant(arg, modified, localGuardVars, ok);
                }
            }
            case HirExpr.Const ignored -> {}
            case HirExpr.Unary u ->
                    checkOperandsInvariant(u.operand(), modified, localGuardVars, ok);
            case HirExpr.Binary b -> {
                checkOperandsInvariant(b.left(), modified, localGuardVars, ok);
                checkOperandsInvariant(b.right(), modified, localGuardVars, ok);
            }
            case HirExpr.Logic l -> {
                checkOperandsInvariant(l.left(), modified, localGuardVars, ok);
                checkOperandsInvariant(l.right(), modified, localGuardVars, ok);
            }
            case HirExpr.ThisFieldGet g ->
                    checkOperandsInvariant(g.receiver(), modified, localGuardVars, ok);
            case HirExpr.JavaFieldGet g -> {
                if (!g.target().isStatic() || !g.pureStaticReceiver()) {
                    checkOperandsInvariant(g.receiver(), modified, localGuardVars, ok);
                }
            }
            case HirExpr.ArrayOp op -> {
                if (op.kind() != LoweringTargets.ArrayOpKind.SIZE) {
                    ok[0] = false;
                    return;
                }
                checkOperandsInvariant(op.receiver(), modified, localGuardVars, ok);
            }
            case HirExpr.ModuleExportGet g -> {
                if (!g.target().definitelyInitialized()) {
                    ok[0] = false;
                    return;
                }
                checkOperandsInvariant(g.receiver(), modified, localGuardVars, ok);
            }
            case HirExpr.Move m ->
                    checkOperandsInvariant(m.operand(), modified, localGuardVars, ok);
            case HirExpr.Freeze f ->
                    checkOperandsInvariant(f.operand(), modified, localGuardVars, ok);
            case HirExpr.Convert cv ->
                    checkOperandsInvariant(cv.operand(), modified, localGuardVars, ok);
            case HirExpr.Widen w ->
                    checkOperandsInvariant(w.operand(), modified, localGuardVars, ok);
            case HirExpr.Block b -> {
                if (b.opensNamespaceScope() || !b.declaredVars().isEmpty()) {
                    ok[0] = false;
                    return;
                }
                for (HirExpr s : b.statements()) {
                    checkOperandsInvariant(s, modified, localGuardVars, ok);
                }
            }
            case HirExpr.As a -> {
                if (a.targetDescriptor() != null || a.samTarget() != null) {
                    ok[0] = false;
                    return;
                }
                checkOperandsInvariant(a.left(), modified, localGuardVars, ok);
            }
            default -> ok[0] = false;
        }
    }

    private static void collectLoopBarriers(
            HirExpr expr, HirEffectAnalysis fx, List<IrEffect> barriers) {
        if (expr == null) {
            return;
        }
        IrEffect own = fx.shallowEffectOf(expr);
        if (own.kind() == IrEffect.Kind.WRITE || own.kind() == IrEffect.Kind.IMPURE) {
            barriers.add(own);
        }
        HirRewriter.rewriteChildren(
                expr,
                c -> {
                    collectLoopBarriers(c, fx, barriers);
                    return c;
                });
    }

    private static void collectGuardTestedVarsInTree(HirExpr expr, Set<Integer> out) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case HirExpr.If i -> collectGuardTestedVars(i.cond(), out);
            case HirExpr.Logic l -> collectGuardTestedVars(l.left(), out);
            case HirExpr.While w -> collectGuardTestedVars(w.cond(), out);
            case HirExpr.Match m -> {
                for (HirExpr.MatchArm arm : m.arms()) {
                    if (arm.guard() != null) {
                        collectGuardTestedVars(arm.guard(), out);
                    }
                }
            }
            default -> {}
        }
        HirRewriter.rewriteChildren(
                expr,
                c -> {
                    collectGuardTestedVarsInTree(c, out);
                    return c;
                });
    }

    private static void collectGuardTestedVars(HirExpr cond, Set<Integer> out) {
        if (cond == null) {
            return;
        }
        HirExpr cur = HirRewriter.unwrapTransparent(cond);
        while (cur instanceof HirExpr.Unary u && u.op() == HirExpr.UnaryOp.NOT) {
            cur = HirRewriter.unwrapTransparent(u.operand());
        }
        if (cur instanceof HirExpr.Logic l) {
            collectGuardTestedVars(l.left(), out);
            collectGuardTestedVars(l.right(), out);
            return;
        }
        if (cur instanceof HirExpr.Binary b
                && (b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ)) {
            HirExpr l = unwrapNarrow(b.left());
            HirExpr r = unwrapNarrow(b.right());
            if (l instanceof HirExpr.VarRead vr
                    && r instanceof HirExpr.Const c
                    && c.value() == null) {
                out.add(vr.var().id());
            } else if (r instanceof HirExpr.VarRead vr
                    && l instanceof HirExpr.Const c
                    && c.value() == null) {
                out.add(vr.var().id());
            }
            return;
        }
        if (cur instanceof HirExpr.Is isExpr) {
            HirExpr l = unwrapNarrow(isExpr.left());
            if (l instanceof HirExpr.VarRead vr) {
                out.add(vr.var().id());
            }
        }
    }

    private static HirExpr unwrapNarrow(HirExpr expr) {
        HirExpr cur = HirRewriter.unwrapTransparent(expr);
        while (cur instanceof HirExpr.Narrow n) {
            cur = HirRewriter.unwrapTransparent(n.operand());
        }
        return cur;
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
