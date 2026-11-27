package org.babblelang.opt;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.hir.IrEffect;

/**
 * Shared per-function alias partition and {@link IrEffect} analysis over structured {@link HirExpr}
 * trees ({@code BIP039-D2}).
 *
 * <p>Unifies the effect and alias classification rules across {@link HirPurity}, {@code
 * HirLicmPass}, {@code HirDcePass}, {@code HirPredicateReorderPass}, and {@code HirAeGraphPass}.
 */
public final class HirEffectAnalysis {
    private final HirModule module;
    private final Map<Integer, IrEffect.AliasClass> varAliases;

    private HirEffectAnalysis(HirModule module, Map<Integer, IrEffect.AliasClass> varAliases) {
        this.module = module;
        this.varAliases = varAliases;
    }

    /** Builds an effect analysis scoped to a specific {@link HirFunction}. */
    public static HirEffectAnalysis forFunction(HirFunction fn, HirModule module) {
        return new HirEffectAnalysis(module, computeVarAliases(fn));
    }

    /** Builds a conservative module-level effect analysis (for top-level/class/interface code). */
    public static HirEffectAnalysis forModule(HirModule module) {
        return new HirEffectAnalysis(module, Map.of());
    }

    public Map<Integer, IrEffect.AliasClass> varAliases() {
        return varAliases;
    }

    /** Computes immutable per-variable alias partitions for {@code fn}. */
    public static Map<Integer, IrEffect.AliasClass> computeVarAliases(HirFunction fn) {
        if (fn == null) {
            return Map.of();
        }
        Set<Integer> reassigned = new HashSet<>();
        for (HirExpr s : fn.body()) {
            collectAssignedVars(s, reassigned);
        }
        Map<Integer, IrEffect.AliasClass> aliases = new HashMap<>();
        List<HirParam> params = fn.params();
        for (int i = 0; i < params.size(); i++) {
            HirParam p = params.get(i);
            HirVar v = p.var();
            if (v == null || v.primitiveKind().isPrimitive()) {
                continue;
            }
            if (p.isUnique() && !reassigned.contains(v.id()) && !v.cell()) {
                aliases.put(v.id(), IrEffect.AliasClass.uniqueParam(i));
            }
        }
        for (HirExpr s : fn.body()) {
            propagateAliases(s, reassigned, aliases);
        }
        if (!aliases.isEmpty()) {
            Set<Integer> escapedUnique = new HashSet<>();
            for (HirExpr s : fn.body()) {
                collectEscapedUniqueParams(s, aliases, escapedUnique);
            }
            if (!escapedUnique.isEmpty()) {
                aliases.entrySet()
                        .removeIf(e ->
                                e.getValue() instanceof IrEffect.AliasClass.UniqueParam u
                                        && escapedUnique.contains(u.paramIndex()));
            }
        }
        return Map.copyOf(aliases);
    }

    private static void collectAssignedVars(HirExpr expr, Set<Integer> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof HirExpr.VarAssign a) {
            out.add(a.var().id());
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    collectAssignedVars(child, out);
                    return child;
                });
    }

    private static void propagateAliases(
            HirExpr expr, Set<Integer> reassigned, Map<Integer, IrEffect.AliasClass> aliases) {
        if (expr == null) {
            return;
        }
        if (expr instanceof HirExpr.Def d
                && d.var() != null
                && d.value() != null
                && !d.var().cell()
                && !reassigned.contains(d.var().id())) {
            IrEffect.AliasClass rhsAlias = staticAliasOf(d.value(), aliases);
            if (rhsAlias != IrEffect.AliasClass.SHARED) {
                aliases.put(d.var().id(), rhsAlias);
            }
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    propagateAliases(child, reassigned, aliases);
                    return child;
                });
    }

    private static void collectEscapedUniqueParams(
            HirExpr expr, Map<Integer, IrEffect.AliasClass> aliases, Set<Integer> escaped) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case HirExpr.VarRead vr -> {
                if (aliases.get(vr.var().id()) instanceof IrEffect.AliasClass.UniqueParam u) {
                    escaped.add(u.paramIndex());
                }
            }
            case HirExpr.ThisFieldGet g -> visitNonEscaping(g.receiver(), aliases, escaped);
            case HirExpr.ThisFieldAssign a -> {
                visitNonEscaping(a.receiver(), aliases, escaped);
                collectEscapedUniqueParams(a.value(), aliases, escaped);
            }
            case HirExpr.JavaFieldGet g -> visitNonEscaping(g.receiver(), aliases, escaped);
            case HirExpr.JavaFieldAssign a -> {
                visitNonEscaping(a.receiver(), aliases, escaped);
                collectEscapedUniqueParams(a.value(), aliases, escaped);
            }
            case HirExpr.ArrayOp op -> {
                visitNonEscaping(op.receiver(), aliases, escaped);
                for (HirExpr arg : op.args()) {
                    collectEscapedUniqueParams(arg, aliases, escaped);
                }
            }
            case HirExpr.Def d -> {
                if (d.var() != null && aliases.containsKey(d.var().id())) {
                    visitNonEscaping(d.value(), aliases, escaped);
                } else {
                    collectEscapedUniqueParams(d.value(), aliases, escaped);
                }
            }
            case HirExpr.Binary b -> {
                if (b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ) {
                    visitNonEscaping(b.left(), aliases, escaped);
                    visitNonEscaping(b.right(), aliases, escaped);
                } else {
                    collectEscapedUniqueParams(b.left(), aliases, escaped);
                    collectEscapedUniqueParams(b.right(), aliases, escaped);
                }
            }
            case HirExpr.Is is -> visitNonEscaping(is.left(), aliases, escaped);
            case HirExpr.DirectBabbleCall c -> {
                collectEscapedUniqueParams(c.callee(), aliases, escaped);
                boolean noEscape = isNonEscapingCall(c.meta());
                for (HirExpr arg : c.args()) {
                    if (noEscape) {
                        visitNonEscaping(arg, aliases, escaped);
                    } else {
                        collectEscapedUniqueParams(arg, aliases, escaped);
                    }
                }
            }
            case HirExpr.DirectThisMethodCall c -> {
                boolean noEscape = isNonEscapingCall(c.meta());
                if (noEscape) {
                    visitNonEscaping(c.receiver(), aliases, escaped);
                } else {
                    collectEscapedUniqueParams(c.receiver(), aliases, escaped);
                }
                for (HirCallArg arg : c.args()) {
                    if (noEscape) {
                        visitNonEscaping(arg.value(), aliases, escaped);
                    } else {
                        collectEscapedUniqueParams(arg.value(), aliases, escaped);
                    }
                }
            }
            case HirExpr.JavaMethodCall c -> {
                boolean noEscape = isNonEscapingCall(c.meta());
                if (noEscape) {
                    visitNonEscaping(c.receiver(), aliases, escaped);
                } else {
                    collectEscapedUniqueParams(c.receiver(), aliases, escaped);
                }
                for (HirExpr arg : c.args()) {
                    if (noEscape) {
                        visitNonEscaping(arg, aliases, escaped);
                    } else {
                        collectEscapedUniqueParams(arg, aliases, escaped);
                    }
                }
            }
            case HirExpr.InterfaceCall c -> {
                boolean noEscape = isNonEscapingCall(c.meta());
                if (noEscape) {
                    visitNonEscaping(c.receiver(), aliases, escaped);
                } else {
                    collectEscapedUniqueParams(c.receiver(), aliases, escaped);
                }
                for (HirExpr arg : c.args()) {
                    if (noEscape) {
                        visitNonEscaping(arg, aliases, escaped);
                    } else {
                        collectEscapedUniqueParams(arg, aliases, escaped);
                    }
                }
            }
            case HirExpr.CoreMethodCall c -> {
                boolean noEscape = isNonEscapingCall(c.meta());
                if (noEscape) {
                    visitNonEscaping(c.receiver(), aliases, escaped);
                } else {
                    collectEscapedUniqueParams(c.receiver(), aliases, escaped);
                }
                for (HirExpr arg : c.args()) {
                    if (noEscape) {
                        visitNonEscaping(arg, aliases, escaped);
                    } else {
                        collectEscapedUniqueParams(arg, aliases, escaped);
                    }
                }
            }
            default ->
                    HirRewriter.rewriteChildren(
                            expr,
                            child -> {
                                collectEscapedUniqueParams(child, aliases, escaped);
                                return child;
                            });
        }
    }

    private static void visitNonEscaping(
            HirExpr expr, Map<Integer, IrEffect.AliasClass> aliases, Set<Integer> escaped) {
        if (expr == null) {
            return;
        }
        HirExpr cur = unwrapAliasForwarding(expr);
        if (cur instanceof HirExpr.VarRead) {
            return;
        }
        collectEscapedUniqueParams(cur, aliases, escaped);
    }

    private static boolean isNonEscapingCall(ExprMeta meta) {
        return meta.effects().isHermetic()
                && (meta.kind().isPrimitive() || meta.deeplyImmutable() || meta.frozen());
    }

    private static IrEffect.AliasClass staticAliasOf(
            HirExpr expr, Map<Integer, IrEffect.AliasClass> aliases) {
        if (expr == null) {
            return IrEffect.AliasClass.SHARED;
        }
        ExprMeta meta = expr.meta();
        if (meta.frozen() || meta.deeplyImmutable()) {
            return IrEffect.AliasClass.FROZEN;
        }
        HirExpr cur = unwrapAliasForwarding(expr);
        if (cur instanceof HirExpr.Freeze) {
            return IrEffect.AliasClass.FROZEN;
        }
        if (cur instanceof HirExpr.VarRead vr) {
            return aliases.getOrDefault(vr.var().id(), IrEffect.AliasClass.SHARED);
        }
        return IrEffect.AliasClass.SHARED;
    }

    /** Returns the alias partition of the object referenced by {@code expr}. */
    public IrEffect.AliasClass aliasOf(HirExpr expr) {
        return staticAliasOf(expr, varAliases);
    }

    private static HirExpr unwrapAliasForwarding(HirExpr expr) {
        while (true) {
            if (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
                expr = b.statements().get(0);
            } else if (expr instanceof HirExpr.Narrow n) {
                expr = n.operand();
            } else if (expr instanceof HirExpr.Move m) {
                expr = m.operand();
            } else if (expr instanceof HirExpr.Convert cv
                    && cv.targetKind() == PrimitiveKind.NONE
                    && cv.operand().meta().kind() == PrimitiveKind.NONE) {
                expr = cv.operand();
            } else {
                return expr;
            }
        }
    }

    /**
     * Returns the shallow effect of {@code expr}'s own operation (excluding child sub-expressions).
     */
    public IrEffect shallowEffectOf(HirExpr expr) {
        if (expr == null) {
            return IrEffect.PURE_NOTHROW;
        }
        boolean hasCheck = expr.meta().checkedType() != null;
        return switch (expr) {
            case HirExpr.Const ignored ->
                    hasCheck ? IrEffect.PURE_MAY_THROW : IrEffect.PURE_NOTHROW;
            case HirExpr.CoreCollectionRef ignored -> IrEffect.PURE_NOTHROW;
            case HirExpr.CoreExceptionRef ignored -> IrEffect.PURE_NOTHROW;
            case HirExpr.FunctionRef ignored -> IrEffect.PURE_NOTHROW;
            case HirExpr.IntParseFallback ignored -> IrEffect.PURE_MAY_THROW;
            case HirExpr.ThisRef t ->
                    t.var() != null ? varReadEffect(t.var(), hasCheck) : IrEffect.READ_SHARED_THROW;
            case HirExpr.RecurseRef r ->
                    r.var() != null ? varReadEffect(r.var(), hasCheck) : IrEffect.READ_SHARED_THROW;
            case HirExpr.VarRead v -> varReadEffect(v.var(), hasCheck);
            case HirExpr.NameRead nr -> {
                boolean nothrow =
                        (nr.definitelyDefined()
                                        || HirPurity.isDefinitelyInitializedTopLevelFn(
                                                nr.name(), module))
                                && !hasCheck;
                if (nr.finalBinding()) {
                    yield nothrow ? IrEffect.PURE_NOTHROW : IrEffect.PURE_MAY_THROW;
                }
                yield nothrow ? IrEffect.READ_SHARED : IrEffect.READ_SHARED_THROW;
            }
            case HirExpr.ThisFieldGet g -> thisFieldGetEffect(g, hasCheck);
            case HirExpr.JavaFieldGet g -> javaFieldGetEffect(g, hasCheck);
            case HirExpr.ModuleExportGet g -> {
                boolean nothrow =
                        g.target().definitelyInitialized() && !hasCheck && isNonNull(g.receiver());
                // Module exports are immutable bindings once initialized.
                yield nothrow ? IrEffect.PURE_NOTHROW : IrEffect.PURE_MAY_THROW;
            }
            case HirExpr.ArrayOp op ->
                    switch (op.kind()) {
                        case SIZE ->
                                IrEffect.read(
                                        IrEffect.AliasClass.FROZEN,
                                        isNonNull(op.receiver()) && !hasCheck,
                                        true,
                                        true);
                        case GET -> IrEffect.read(aliasOf(op.receiver()), false, true, true);
                        case SET -> IrEffect.write(aliasOf(op.receiver()), false);
                        case EQUALS, HASH_CODE, TO_STRING -> IrEffect.IMPURE_THROW;
                    };
            case HirExpr.ThisFieldInit a -> IrEffect.write(IrEffect.AliasClass.SHARED, true);
            case HirExpr.ThisFieldAssign a ->
                    (a.operator() == null && a.classId() >= 0 && a.memberIndex() >= 0)
                            ? IrEffect.write(aliasOf(a.receiver()), false)
                            : IrEffect.IMPURE_THROW;
            case HirExpr.JavaFieldAssign a ->
                    a.operator() == null
                            ? IrEffect.write(
                                    a.target().isStatic()
                                            ? IrEffect.AliasClass.SHARED
                                            : aliasOf(a.receiver()),
                                    false)
                            : IrEffect.IMPURE_THROW;
            case HirExpr.Def d ->
                    (d.var() != null && !d.var().cell())
                            ? IrEffect.write(IrEffect.AliasClass.NONE, true)
                            : IrEffect.WRITE_SHARED_THROW;
            case HirExpr.VarAssign a ->
                    (a.operator() == null && !a.var().cell())
                            ? IrEffect.write(IrEffect.AliasClass.NONE, true)
                            : IrEffect.IMPURE_THROW;
            case HirExpr.NameAssign ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.DynamicFieldAssign a ->
                    a.operator() == null
                            ? IrEffect.write(aliasOf(a.receiver()), false)
                            : IrEffect.IMPURE_THROW;
            case HirExpr.DynamicSelect ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.ArrayCreate ignored -> IrEffect.PURE_MAY_THROW;
            case HirExpr.Unary u -> {
                if (u.op() == HirExpr.UnaryOp.NOT) {
                    yield hasCheck ? IrEffect.PURE_MAY_THROW : IrEffect.PURE_NOTHROW;
                }
                if (u.meta().kind().isPrimitive() && u.operand().meta().kind().isNumeric()) {
                    yield hasCheck ? IrEffect.PURE_MAY_THROW : IrEffect.PURE_NOTHROW;
                }
                yield effectFromEffectSet(u.meta().effects(), false);
            }
            case HirExpr.Binary b -> binaryShallowEffect(b, hasCheck);
            case HirExpr.Range r ->
                    r.elemKind().isPrimitive() ? IrEffect.PURE_NOTHROW : IrEffect.PURE_MAY_THROW;
            case HirExpr.Logic ignored ->
                    hasCheck ? IrEffect.PURE_MAY_THROW : IrEffect.PURE_NOTHROW;
            case HirExpr.Is ignored -> hasCheck ? IrEffect.PURE_MAY_THROW : IrEffect.PURE_NOTHROW;
            case HirExpr.As a ->
                    (a.targetDescriptor() == null && a.samTarget() == null)
                            ? IrEffect.PURE_NOTHROW
                            : IrEffect.PURE_MAY_THROW;
            case HirExpr.NonNull n ->
                    n.expectedType() == null ? IrEffect.PURE_NOTHROW : IrEffect.PURE_MAY_THROW;
            case HirExpr.Narrow ignored -> IrEffect.PURE_NOTHROW;
            case HirExpr.Move ignored -> IrEffect.PURE_NOTHROW;
            case HirExpr.Freeze ignored -> IrEffect.PURE_NOTHROW;
            case HirExpr.Convert cv -> convertShallowEffect(cv);
            case HirExpr.Widen w ->
                    w.operand().meta().kind().isPrimitive()
                            ? IrEffect.PURE_NOTHROW
                            : IrEffect.PURE_MAY_THROW;
            case HirExpr.TypeApply ignored -> IrEffect.PURE_NOTHROW;
            case HirExpr.SpreadAdapt ignored -> IrEffect.PURE_MAY_THROW;
            case HirExpr.JavaSamAdapt ignored -> IrEffect.PURE_MAY_THROW;
            case HirExpr.Block ignored -> IrEffect.PURE_NOTHROW;
            case HirExpr.If ignored -> IrEffect.PURE_NOTHROW;
            case HirExpr.Match m ->
                    m.exhaustive() ? IrEffect.PURE_NOTHROW : IrEffect.PURE_MAY_THROW;
            case HirExpr.While ignored ->
                    new IrEffect(IrEffect.Kind.PURE, IrEffect.AliasClass.NONE, true, false, true);
            case HirExpr.For ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.Try ignored -> IrEffect.PURE_NOTHROW;
            case HirExpr.Break ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.Continue ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.Return ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.Throw ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.RecordNew r ->
                    r.meta().deeplyImmutable()
                            ? (hasCheck ? IrEffect.PURE_MAY_THROW : IrEffect.PURE_NOTHROW)
                            : IrEffect.IMPURE_THROW;
            case HirExpr.RecordReuse ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.DirectBabbleCall c -> {
                EffectSet fx = c.meta().effects();
                IrEffect base = callEffect(fx, hasCheck);
                boolean targetMut = c.externalTarget() != null && c.externalTarget().hasMutParam();
                if (c.targetFunctionId() >= 0) {
                    HirFunction target = module.function(c.targetFunctionId());
                    if (target != null) {
                        if (hasMutParam(target) || target.effects().mutThis()) {
                            targetMut = true;
                        }
                        if (!target.effects().isPure() || target.effects().mutThis()) {
                            base =
                                    IrEffect.combine(
                                            base, effectFromEffectSet(target.effects(), hasCheck));
                        }
                    }
                }
                if (targetMut && base.kind() == IrEffect.Kind.PURE) {
                    base =
                            new IrEffect(
                                    IrEffect.Kind.WRITE,
                                    IrEffect.AliasClass.SHARED,
                                    base.nothrow(),
                                    base.terminates(),
                                    base.deterministic());
                }
                yield callWriteBarrier(base, null, c.args());
            }
            case HirExpr.DirectThisMethodCall c -> {
                EffectSet fx = c.meta().effects();
                IrEffect base = callEffect(fx, hasCheck);
                boolean targetMut = false;
                if (c.targetFunctionId() >= 0) {
                    HirFunction target = module.function(c.targetFunctionId());
                    if (target != null) {
                        if (hasMutParam(target) || target.effects().mutThis()) {
                            targetMut = true;
                        }
                        if (!target.effects().isPure() || target.effects().mutThis()) {
                            base =
                                    IrEffect.combine(
                                            base, effectFromEffectSet(target.effects(), hasCheck));
                        }
                    }
                }
                if (targetMut && base.kind() == IrEffect.Kind.PURE) {
                    base =
                            new IrEffect(
                                    IrEffect.Kind.WRITE,
                                    IrEffect.AliasClass.SHARED,
                                    base.nothrow(),
                                    base.terminates(),
                                    base.deterministic());
                }
                yield callWriteBarrierArgs(base, c.receiver(), c.args());
            }
            case HirExpr.JavaMethodCall c -> {
                EffectSet fx = c.meta().effects();
                boolean staticOrNonNull =
                        (c.target().isStatic() && c.pureStaticReceiver())
                                || isNonNull(c.receiver());
                IrEffect base = callEffect(fx, hasCheck || !staticOrNonNull);
                HirExpr recv = c.target().isStatic() ? null : c.receiver();
                yield callWriteBarrier(base, recv, c.args());
            }
            case HirExpr.JavaConstructorCall c ->
                    callWriteBarrier(callEffect(c.meta().effects(), hasCheck), null, c.args());
            case HirExpr.InterfaceCall c ->
                    callWriteBarrier(
                            callEffect(c.meta().effects(), hasCheck), c.receiver(), c.args());
            case HirExpr.CoreMethodCall c ->
                    callWriteBarrier(
                            callEffect(c.meta().effects(), hasCheck), c.receiver(), c.args());
            case HirExpr.SelfRecurseCall c ->
                    callWriteBarrier(callEffect(c.meta().effects(), hasCheck), null, c.args());
            case HirExpr.SelfTailCall ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.CoreCollectionCall ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.SuperCall ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.SafeCall ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.DynamicCall ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.MakeOverloadSet ignored -> IrEffect.PURE_NOTHROW;
            case HirExpr.OverloadSelect ignored -> IrEffect.PURE_MAY_THROW;
            case HirExpr.OverloadDispatch ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.ClassExpr ignored -> IrEffect.IMPURE_THROW;
            case HirExpr.InterfaceExpr ignored -> IrEffect.IMPURE_THROW;
        };
    }

    /**
     * Computes the conservative deep {@link IrEffect} of evaluating {@code expr} and all of its
     * sub-expressions. Writes to non-cell local variables declared inside a nested {@link
     * HirExpr.Block} are invisible outside that block and do not taint its external purity.
     */
    public IrEffect effectOf(HirExpr expr) {
        return effectOf(expr, null);
    }

    private IrEffect effectOf(HirExpr expr, Set<Integer> localDeclared) {
        if (expr == null) {
            return IrEffect.PURE_NOTHROW;
        }
        IrEffect own = shallowEffectOf(expr);
        if (localDeclared != null) {
            if (expr instanceof HirExpr.Def d
                    && d.var() != null
                    && !d.var().cell()
                    && localDeclared.contains(d.var().id())) {
                own = IrEffect.PURE_NOTHROW;
            } else if (expr instanceof HirExpr.VarAssign a
                    && a.operator() == null
                    && !a.var().cell()
                    && localDeclared.contains(a.var().id())) {
                own = IrEffect.PURE_NOTHROW;
            }
        }
        if (own == IrEffect.IMPURE_THROW) {
            return own;
        }
        IrEffect[] acc = {own};
        switch (expr) {
            case HirExpr.Block b -> {
                Set<Integer> innerDeclared = localDeclared;
                if (!b.declaredVars().isEmpty()) {
                    innerDeclared =
                            localDeclared != null ? new HashSet<>(localDeclared) : new HashSet<>();
                    for (HirVar v : b.declaredVars()) {
                        if (!v.cell()) {
                            innerDeclared.add(v.id());
                        }
                    }
                }
                for (HirExpr s : b.statements()) {
                    acc[0] = IrEffect.combine(acc[0], effectOf(s, innerDeclared));
                }
            }
            case HirExpr.JavaMethodCall c -> {
                if (!c.target().isStatic() || !c.pureStaticReceiver()) {
                    acc[0] = IrEffect.combine(acc[0], effectOf(c.receiver(), localDeclared));
                }
                for (HirExpr a : c.args()) {
                    acc[0] = IrEffect.combine(acc[0], effectOf(a, localDeclared));
                }
            }
            case HirExpr.JavaConstructorCall c -> {
                if (!c.pureStaticCallee() && c.callee() != null) {
                    acc[0] = IrEffect.combine(acc[0], effectOf(c.callee(), localDeclared));
                }
                for (HirExpr a : c.args()) {
                    acc[0] = IrEffect.combine(acc[0], effectOf(a, localDeclared));
                }
            }
            case HirExpr.JavaFieldGet g -> {
                if (!g.target().isStatic() || !g.pureStaticReceiver()) {
                    acc[0] = IrEffect.combine(acc[0], effectOf(g.receiver(), localDeclared));
                }
            }
            case HirExpr.DirectBabbleCall c -> {
                if (!(c.callee() instanceof HirExpr.NameRead nr
                        && HirPurity.isDefinitelyInitializedTopLevelFn(nr.name(), module))) {
                    acc[0] = IrEffect.combine(acc[0], effectOf(c.callee(), localDeclared));
                }
                for (HirExpr a : c.args()) {
                    acc[0] = IrEffect.combine(acc[0], effectOf(a, localDeclared));
                }
            }
            case HirExpr.RecordNew r -> {
                if (!(r.classRef() instanceof HirExpr.NameRead nr
                        && HirPurity.isDefinitelyInitializedTopLevelBinding(nr.name(), module))) {
                    acc[0] = IrEffect.combine(acc[0], effectOf(r.classRef(), localDeclared));
                }
                for (HirExpr a : r.args()) {
                    acc[0] = IrEffect.combine(acc[0], effectOf(a, localDeclared));
                }
            }
            default -> {
                Set<Integer> scoped = localDeclared;
                HirRewriter.rewriteChildren(
                        expr,
                        child -> {
                            acc[0] = IrEffect.combine(acc[0], effectOf(child, scoped));
                            return child;
                        });
            }
        }
        return acc[0];
    }

    /**
     * True if {@code expr} is statically guaranteed to be non-null (no NPE on field/array access).
     */
    public static boolean isNonNull(HirExpr expr) {
        if (expr == null) {
            return false;
        }
        return switch (expr) {
            case HirExpr.ThisRef ignored -> true;
            case HirExpr.RecordNew ignored -> true;
            case HirExpr.ArrayCreate ignored -> true;
            case HirExpr.Range ignored -> true;
            case HirExpr.Narrow n ->
                    n.narrowKind() == HirExpr.NarrowKind.NON_NULL
                            || n.narrowKind() == HirExpr.NarrowKind.IS_TYPE;
            case HirExpr.Const c -> c.value() != null;
            case HirExpr.Block b -> b.isTransparentSingleExpr() && isNonNull(b.statements().get(0));
            case HirExpr.Move m -> isNonNull(m.operand());
            case HirExpr.Freeze f -> isNonNull(f.operand());
            default -> expr.meta().kind().isPrimitive();
        };
    }

    private IrEffect varReadEffect(HirVar v, boolean hasCheck) {
        boolean nothrow = (!v.mayBeUndefined() || v.definitelyDefined()) && !hasCheck;
        if (!v.cell()) {
            return nothrow ? IrEffect.PURE_NOTHROW : IrEffect.PURE_MAY_THROW;
        }
        if (v.kind() == Resolution.Variable.Kind.THIS
                || v.kind() == Resolution.Variable.Kind.RECURSE) {
            return nothrow ? IrEffect.PURE_NOTHROW : IrEffect.PURE_MAY_THROW;
        }
        return nothrow ? IrEffect.READ_SHARED : IrEffect.READ_SHARED_THROW;
    }

    private IrEffect thisFieldGetEffect(HirExpr.ThisFieldGet g, boolean hasCheck) {
        boolean isExternal =
                g.classId() < 0 && g.externalClassJvmName() != null && g.memberName() != null;
        boolean isValue = false;
        if (!isExternal) {
            if (g.classId() < 0 || g.memberIndex() < 0) {
                return IrEffect.IMPURE_THROW;
            }
            HirClass cls = module.clazz(g.classId());
            if (cls == null || !cls.hasCompiledClass()) {
                return IrEffect.IMPURE_THROW;
            }
            isValue = cls.shape().isValue();
        }
        boolean isFinal =
                g.immutableField()
                        || isValue
                        || g.receiver().meta().frozen()
                        || g.receiver().meta().deeplyImmutable();
        IrEffect.AliasClass alias = isFinal ? IrEffect.AliasClass.FROZEN : aliasOf(g.receiver());
        boolean nothrow = !hasCheck && g.definitelyDefined() && isNonNullReceiver(g.receiver());
        return IrEffect.read(alias, nothrow, true, true);
    }

    /**
     * True if {@code receiver} of a compiled Babble member/method access is statically guaranteed
     * non-null (in Babble's strict non-null type system, a non-nullable local {@code VarRead}
     * passed to a direct {@code ThisFieldGet} cannot be null).
     */
    private static boolean isNonNullReceiver(HirExpr receiver) {
        return isNonNull(receiver) || receiver instanceof HirExpr.VarRead;
    }

    private IrEffect javaFieldGetEffect(HirExpr.JavaFieldGet g, boolean hasCheck) {
        Field field = g.target().field();
        boolean isFinal = Modifier.isFinal(field.getModifiers());
        IrEffect.AliasClass alias =
                isFinal
                        ? IrEffect.AliasClass.FROZEN
                        : (g.target().isStatic()
                                ? IrEffect.AliasClass.SHARED
                                : aliasOf(g.receiver()));
        boolean nothrow =
                !hasCheck
                        && ((g.target().isStatic() && g.pureStaticReceiver())
                                || isNonNull(g.receiver()));
        return IrEffect.read(alias, nothrow, true, true);
    }

    private IrEffect binaryShallowEffect(HirExpr.Binary b, boolean hasCheck) {
        PrimitiveKind lk = b.left().meta().kind();
        PrimitiveKind rk = b.right().meta().kind();
        if (b.op().isComparison() && HirPurity.canLowerComparison(b)) {
            return hasCheck ? IrEffect.PURE_MAY_THROW : IrEffect.PURE_NOTHROW;
        }
        if (b.op() == HirExpr.BinaryOp.ADD
                && b.left().meta().stringType()
                && b.right().meta().stringType()) {
            return hasCheck ? IrEffect.PURE_MAY_THROW : IrEffect.PURE_NOTHROW;
        }
        if (b.meta().kind().isPrimitive() && lk.isNumeric() && rk.isNumeric()) {
            if ((b.op() == HirExpr.BinaryOp.MOD || b.op() == HirExpr.BinaryOp.FLOOR_DIV)
                    && (b.meta().kind() == PrimitiveKind.INT
                            || b.meta().kind() == PrimitiveKind.LONG)) {
                boolean nonZero = isNonZeroIntegerConst(b.right());
                return (nonZero && !hasCheck) ? IrEffect.PURE_NOTHROW : IrEffect.PURE_MAY_THROW;
            }
            return hasCheck ? IrEffect.PURE_MAY_THROW : IrEffect.PURE_NOTHROW;
        }
        return effectFromEffectSet(b.meta().effects(), hasCheck);
    }

    private static IrEffect convertShallowEffect(HirExpr.Convert cv) {
        PrimitiveKind from = cv.operand().meta().kind();
        PrimitiveKind to = cv.targetKind();
        if (from == to || to == PrimitiveKind.NONE) {
            return IrEffect.PURE_NOTHROW;
        }
        if (from.isPrimitive() && to.isPrimitive()) {
            int fr = from.numericRank();
            int tr = to.numericRank();
            if (fr >= 0 && tr >= 0 && fr <= tr) {
                return IrEffect.PURE_NOTHROW;
            }
        }
        return IrEffect.PURE_MAY_THROW;
    }

    private static IrEffect callEffect(EffectSet fx, boolean forceMayThrow) {
        if (fx == null) {
            return IrEffect.IMPURE_THROW;
        }
        return effectFromEffectSet(fx, forceMayThrow);
    }

    private static IrEffect effectFromEffectSet(EffectSet fx, boolean forceMayThrow) {
        if (fx == null) {
            return IrEffect.IMPURE_THROW;
        }
        boolean nothrow = !forceMayThrow && fx.isNothrow();
        boolean terminates = fx.terminating();
        boolean det = fx.isDeterministic();
        if (fx.isIO() || !fx.isHermetic()) {
            return new IrEffect(
                    IrEffect.Kind.IMPURE, IrEffect.AliasClass.SHARED, nothrow, terminates, det);
        }
        if (fx.isPure() && !fx.mutThis()) {
            return new IrEffect(
                    IrEffect.Kind.PURE, IrEffect.AliasClass.NONE, nothrow, terminates, det);
        }
        return new IrEffect(
                IrEffect.Kind.WRITE, IrEffect.AliasClass.SHARED, nothrow, terminates, det);
    }

    private static boolean hasMutParam(HirFunction fn) {
        for (HirParam p : fn.params()) {
            if (p.isMut()) {
                return true;
            }
        }
        return false;
    }

    private IrEffect callWriteBarrier(IrEffect base, HirExpr receiver, List<HirExpr> args) {
        if (base.kind() != IrEffect.Kind.WRITE) {
            return base;
        }
        IrEffect.AliasClass unique = null;
        boolean multipleUnique = false;
        boolean touchesShared = false;
        if (receiver != null && isMutableRef(receiver)) {
            IrEffect.AliasClass a = aliasOf(receiver);
            if (a instanceof IrEffect.AliasClass.UniqueParam) {
                unique = a;
            } else if (a instanceof IrEffect.AliasClass.Shared) {
                touchesShared = true;
            }
        }
        for (HirExpr arg : args) {
            if (arg == null || !isMutableRef(arg)) {
                continue;
            }
            IrEffect.AliasClass a = aliasOf(arg);
            if (a instanceof IrEffect.AliasClass.UniqueParam) {
                if (unique == null) {
                    unique = a;
                } else if (!unique.equals(a)) {
                    multipleUnique = true;
                }
            } else if (a instanceof IrEffect.AliasClass.Shared) {
                touchesShared = true;
            }
        }
        if (unique == null) {
            return base;
        }
        if (multipleUnique || touchesShared) {
            return new IrEffect(
                    IrEffect.Kind.IMPURE,
                    IrEffect.AliasClass.SHARED,
                    base.nothrow(),
                    base.terminates(),
                    base.deterministic());
        }
        return new IrEffect(
                IrEffect.Kind.WRITE,
                unique,
                base.nothrow(),
                base.terminates(),
                base.deterministic());
    }

    private IrEffect callWriteBarrierArgs(IrEffect base, HirExpr receiver, List<HirCallArg> args) {
        if (base.kind() != IrEffect.Kind.WRITE) {
            return base;
        }
        List<HirExpr> vals = new java.util.ArrayList<>(args.size());
        for (HirCallArg arg : args) {
            vals.add(arg.value());
        }
        return callWriteBarrier(base, receiver, vals);
    }

    private static boolean isMutableRef(HirExpr expr) {
        ExprMeta meta = expr.meta();
        return !meta.kind().isPrimitive() && !meta.deeplyImmutable() && !meta.frozen();
    }

    private static boolean isNonZeroIntegerConst(HirExpr expr) {
        HirExpr cur = HirRewriter.unwrapTransparent(expr);
        return cur instanceof HirExpr.Const c
                && c.value() instanceof Number n
                && n.longValue() != 0L;
    }
}
