package org.babblelang.opt;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.EffectSet;
import org.babblelang.effects.Effect;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirVar;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared {@code BIP007}/{@code BIP016}-gated soundness queries over {@link HirExpr} (BIP014-I2).
 *
 * <p>Every query here is conservatively sound: returning {@code false} merely skips an
 * optimization, while returning {@code true} guarantees observable equivalence with the
 * reference {@code Interpreter}.
 */
public final class HirPurity {

    private HirPurity() {
    }

    public static boolean isNumericKind(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    public static boolean isIntegralKind(PrimitiveKind kind) {
        return kind == PrimitiveKind.INT || kind == PrimitiveKind.LONG;
    }

    public static boolean canLowerComparison(HirExpr.Binary b) {
        PrimitiveKind lk = b.left().meta().kind();
        PrimitiveKind rk = b.right().meta().kind();
        if (lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL) {
            return b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ;
        }
        if (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR) {
            return true;
        }
        return isNumericKind(lk) && isNumericKind(rk);
    }

    public static boolean isFoldableJava(Method m) {
        return org.babblelang.analysis.BuiltinAxioms.current().isFoldable(m);
    }

    public static boolean isWhitelistedIdempotentJava(Method m) {
        return org.babblelang.analysis.BuiltinAxioms.current().isIdempotent(m);
    }

    public static boolean isStringCheckedType(String checkedType) {
        return checkedType == null || "string|string|".equals(checkedType) || "string".equals(checkedType);
    }

    /**
     * Whether evaluating {@code expr} has zero observable side effects (no mutation, no I/O),
     * cannot throw any exception, and is guaranteed to terminate.
     */
    public static boolean isPureNothrowTerminating(HirExpr expr, HirModule module) {
        return isPureNothrowTerminating(expr, module, new HashSet<>());
    }

    private static boolean isPureNothrowTerminating(HirExpr expr, HirModule module, Set<Integer> visitingFns) {
        if (expr == null) {
            return true;
        }
        if (expr.meta().checkedType() != null) {
            return false;
        }
        return switch (expr) {
            case HirExpr.Const c -> true;
            case HirExpr.CoreCollectionRef r -> true;
            case HirExpr.CoreExceptionRef r -> true;
            case HirExpr.FunctionRef f -> true;
            case HirExpr.VarRead v -> !v.var().mayBeUndefined() || v.var().definitelyDefined();
            case HirExpr.NameRead nr -> nr.finalBinding() && nr.definitelyDefined();
            case HirExpr.ThisRef t -> t.var() != null && (!t.var().mayBeUndefined() || t.var().definitelyDefined());
            case HirExpr.Unary u -> {
                if (!isPureNothrowTerminating(u.operand(), module, visitingFns)) {
                    yield false;
                }
                if (u.op() == HirExpr.UnaryOp.NOT) {
                    yield true;
                }
                yield u.meta().kind().isPrimitive() && isNumericKind(u.operand().meta().kind());
            }
            case HirExpr.Binary b -> {
                if (!isPureNothrowTerminating(b.left(), module, visitingFns)
                        || !isPureNothrowTerminating(b.right(), module, visitingFns)) {
                    yield false;
                }
                if (b.op().isComparison()) {
                    yield canLowerComparison(b)
                            || ((b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ)
                            && b.left().meta().stringType() && b.right().meta().stringType());
                }
                if (b.op() == HirExpr.BinaryOp.MOD || b.op() == HirExpr.BinaryOp.FLOOR_DIV) {
                    yield b.meta().kind().isPrimitive()
                            && isNumericKind(b.left().meta().kind()) && isNumericKind(b.right().meta().kind())
                            && b.right() instanceof HirExpr.Const rc
                            && rc.value() instanceof Number n && n.longValue() != 0L;
                }
                if (b.meta().kind().isPrimitive()
                        && isNumericKind(b.left().meta().kind()) && isNumericKind(b.right().meta().kind())) {
                    yield true;
                }
                yield b.op() == HirExpr.BinaryOp.ADD && b.left().meta().stringType() && b.right().meta().stringType();
            }
            case HirExpr.Range r -> (r.elemKind().isPrimitive() || r.stringElement())
                    && isPureNothrowTerminating(r.start(), module, visitingFns)
                    && isPureNothrowTerminating(r.end(), module, visitingFns);
            case HirExpr.Logic l -> isPureNothrowTerminating(l.left(), module, visitingFns)
                    && isPureNothrowTerminating(l.right(), module, visitingFns);
            case HirExpr.Block b -> !b.opensNamespaceScope() && isPureLocalBlock(b, Set.of(), module, visitingFns);
            case HirExpr.For f -> isPureForWithLocals(f, Set.of(), module, visitingFns);
            case HirExpr.If i -> isPureNothrowTerminating(i.cond(), module, visitingFns)
                    && isPureNothrowTerminating(i.thenBranch(), module, visitingFns)
                    && (i.elseBranch() == null || isPureNothrowTerminating(i.elseBranch(), module, visitingFns));
            case HirExpr.Try t -> isTryPureNothrowTerminating(t, Set.of(), module, visitingFns);
            case HirExpr.Throw th -> false;
            case HirExpr.As a -> a.targetDescriptor() == null && a.samTarget() == null
                    && isPureNothrowTerminating(a.left(), module, visitingFns);
            case HirExpr.TypeApply t -> isPureNothrowTerminating(t.inner(), module, visitingFns);
            case HirExpr.MakeOverloadSet m -> allPureNothrowTerminating(m.operands(), module, visitingFns);
            case HirExpr.OverloadSelect s -> s.exactSet() && isPureNothrowTerminating(s.set(), module, visitingFns);
            case HirExpr.ModuleExportGet g -> g.target().definitelyInitialized()
                    && isPureNothrowTerminating(g.receiver(), module, visitingFns);
            case HirExpr.DirectBabbleCall c -> {
                boolean calleeOk = isPureNothrowTerminating(c.callee(), module, visitingFns)
                        || (c.callee() instanceof HirExpr.NameRead nr
                        && isDefinitelyInitializedTopLevelFn(nr.name(), module));
                if (!calleeOk || !allPureNothrowTerminating(c.args(), module, visitingFns)) {
                    yield false;
                }
                if (c.externalTarget() != null) {
                    LoweringTargets.ExportedFunctionTarget ext = c.externalTarget();
                    EffectSet callFx = c.meta().effects();
                    EffectSet fnFx = ext.effects();
                    yield !ext.hasMutParam()
                            && callFx.isPure() && callFx.isNothrow() && callFx.terminating()
                            && (fnFx == null || (fnFx.isPure() && fnFx.isNothrow() && fnFx.terminating()));
                }
                HirFunction fn = module.function(c.targetFunctionId());
                yield fn != null && isCallSitePure(fn, c.meta().effects())
                        && isFunctionPureNothrowTerminating(fn, c.meta().effects(), module, visitingFns);
            }
            case HirExpr.JavaMethodCall c -> {
                if (c.target().isStatic()) {
                    if (!c.pureStaticReceiver() && !isPureNothrowTerminating(c.receiver(), module, visitingFns)) {
                        yield false;
                    }
                } else {
                    // Non-static call can throw NPE if receiver is null unless proven non-null (e.g. Const String).
                    if (!(c.receiver() instanceof HirExpr.Const rc && rc.value() != null)) {
                        yield false;
                    }
                }
                if (!allPureNothrowTerminating(c.args(), module, visitingFns)) {
                    yield false;
                }
                Method m = c.target().method();
                if (isFoldableJava(m)) {
                    yield true;
                }
                EffectSet fx = c.meta().effects();
                yield fx.isPure() && fx.isNothrow() && fx.terminating() && allArgsPrimitiveOrImmutable(c.args());
            }
            case HirExpr.ThisFieldGet g -> {
                if (g.receiverText() == null) {
                    if (g.classId() < 0 || g.memberIndex() < 0) {
                        yield false;
                    }
                    HirClass cls = module.clazz(g.classId());
                    yield cls != null && g.immutableField() && g.definitelyDefined()
                            && (g.memberIndex() >= cls.primDefBits().length || cls.primDefBits()[g.memberIndex()] < 0)
                            && isPureNothrowTerminating(g.receiver(), module, visitingFns);
                }
                yield g.immutableField() && g.definitelyDefined()
                        && isPureNothrowTerminating(g.receiver(), module, visitingFns)
                        && (g.receiver() instanceof HirExpr.ThisRef || g.receiver() instanceof HirExpr.RecordNew
                        || g.receiver() instanceof HirExpr.VarRead);
            }
            case HirExpr.Move m -> isPureNothrowTerminating(m.operand(), module, visitingFns);
            case HirExpr.Freeze f -> isPureNothrowTerminating(f.operand(), module, visitingFns);
            case HirExpr.RecordNew r -> {
                boolean classRefOk = isPureNothrowTerminating(r.classRef(), module, visitingFns)
                        || (r.classRef() instanceof HirExpr.NameRead nr
                        && isDefinitelyInitializedTopLevelBinding(nr.name(), module));
                yield classRefOk && allPureNothrowTerminating(r.args(), module, visitingFns);
            }
            case HirExpr.DirectThisMethodCall c -> {
                if (!isPureNothrowTerminating(c.receiver(), module, visitingFns)) {
                    yield false;
                }
                for (HirCallArg a : c.args()) {
                    if (!isPureNothrowTerminating(a.value(), module, visitingFns)) {
                        yield false;
                    }
                }
                if (c.receiverText() != null && !(c.receiver() instanceof HirExpr.ThisRef
                        || c.receiver() instanceof HirExpr.RecordNew || c.receiver() instanceof HirExpr.VarRead)) {
                    yield false;
                }
                if (c.targetFunctionId() >= 0) {
                    HirFunction fn = module.function(c.targetFunctionId());
                    yield fn != null && isCallSitePure(fn, c.meta().effects())
                            && isFunctionPureNothrowTerminating(fn, c.meta().effects(), module, visitingFns);
                }
                EffectSet fx = c.meta().effects();
                yield fx.isPure() && fx.isNothrow() && fx.terminating();
            }
            case HirExpr.CoreMethodCall c -> {
                if (!isPureNothrowTerminating(c.receiver(), module, visitingFns)
                        || !allPureNothrowTerminating(c.args(), module, visitingFns)) {
                    yield false;
                }
                EffectSet fx = c.meta().effects();
                yield fx.isPure() && fx.isNothrow() && fx.terminating();
            }
            case HirExpr.InterfaceCall c -> {
                if (!isPureNothrowTerminating(c.receiver(), module, visitingFns)
                        || !allPureNothrowTerminating(c.args(), module, visitingFns)) {
                    yield false;
                }
                EffectSet fx = c.meta().effects();
                yield fx.isPure() && fx.isNothrow() && fx.terminating()
                        && (c.receiver() instanceof HirExpr.ThisRef
                        || c.receiver() instanceof HirExpr.RecordNew
                        || c.receiver() instanceof HirExpr.VarRead);
            }
            default -> false;
        };
    }

    private static boolean isPureLocalBlock(HirExpr.Block b, Set<Integer> outerLocals,
                                            HirModule module, Set<Integer> visitingFns) {
        return isPureStatements(b.statements(), b.declaredVars(), outerLocals, module, visitingFns);
    }

    /** Whether a scoped statement list (e.g. a {@code try} body) is pure, nothrow, and terminating. */
    public static boolean isPureStatements(List<HirExpr> stmts, List<HirVar> declaredVars, HirModule module) {
        return isPureStatements(stmts, declaredVars, Set.of(), module, new HashSet<>());
    }

    private static boolean isPureStatements(List<HirExpr> stmts, List<HirVar> declaredVars,
                                            Set<Integer> outerLocals, HirModule module, Set<Integer> visitingFns) {
        if (declaredVars.isEmpty() && outerLocals.isEmpty()) {
            return allPureNothrowTerminating(stmts, module, visitingFns);
        }
        Set<Integer> localIds = declaredVars.isEmpty() ? outerLocals : new HashSet<>(outerLocals);
        for (HirVar v : declaredVars) {
            if (!v.isCaptured()) {
                localIds.add(v.id());
            }
        }
        return allPureWithLocals(stmts, localIds, module, visitingFns);
    }

    private static boolean isTryPureNothrowTerminating(HirExpr.Try t, Set<Integer> outerLocals,
                                                       HirModule module, Set<Integer> visitingFns) {
        if (t.tryOpensNamespaceScope() || t.finallyOpensNamespaceScope()) {
            return false;
        }
        if (!isPureStatements(t.tryBody(), t.tryDeclaredVars(), outerLocals, module, visitingFns)) {
            return false;
        }
        for (HirExpr.HirCatch cc : t.catches()) {
            if (cc.opensNamespaceScope()
                    || !isPureStatements(cc.body(), cc.declaredVars(), outerLocals, module, visitingFns)) {
                return false;
            }
        }
        return !t.hasFinally()
                || isPureStatements(t.finallyBody(), t.finallyDeclaredVars(), outerLocals, module, visitingFns);
    }

    private static boolean allPureWithLocals(List<HirExpr> stmts, Set<Integer> localIds, HirModule module, Set<Integer> visitingFns) {
        for (HirExpr s : stmts) {
            if (!isPureStmtWithLocals(s, localIds, module, visitingFns)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPureStmtWithLocals(HirExpr s, Set<Integer> localIds, HirModule module, Set<Integer> visitingFns) {
        if (s instanceof HirExpr.Def d && d.var() != null && localIds.contains(d.var().id())) {
            return d.value() == null || isPureNothrowTerminating(d.value(), module, visitingFns);
        }
        if (s instanceof HirExpr.VarAssign a && localIds.contains(a.var().id())) {
            return isPureNothrowTerminating(a.value(), module, visitingFns);
        }
        if (s instanceof HirExpr.Block inner && !inner.opensNamespaceScope()) {
            return isPureLocalBlock(inner, localIds, module, visitingFns);
        }
        if (s instanceof HirExpr.For f) {
            return isPureForWithLocals(f, localIds, module, visitingFns);
        }
        return isPureNothrowTerminating(s, module, visitingFns);
    }

    private static boolean isPureForWithLocals(HirExpr.For f, Set<Integer> localIds, HirModule module, Set<Integer> visitingFns) {
        EffectSet fx = f.meta().effects();
        if (!fx.isPure() || !fx.isNothrow() || !fx.terminating()) {
            return false;
        }
        if (f.countedRange() != null) {
            HirExpr.CountedRangeLoop cr = f.countedRange();
            if (!isPureNothrowTerminating(cr.start(), module, visitingFns)
                    || !isPureNothrowTerminating(cr.end(), module, visitingFns)) {
                return false;
            }
            if (cr.step() != null
                    && !(cr.step() instanceof HirExpr.Const sc && sc.value() instanceof Number sn && sn.longValue() > 0L)) {
                return false;
            }
        } else if (!isPureNothrowTerminating(f.iterable(), module, visitingFns)) {
            return false;
        }
        Set<Integer> extended = new HashSet<>(localIds);
        for (HirVar v : f.bodyDeclaredVars()) {
            if (!v.isCaptured()) {
                extended.add(v.id());
            }
        }
        return allPureWithLocals(f.body(), extended, module, visitingFns);
    }

    private static boolean allArgsPrimitiveOrImmutable(List<HirExpr> args) {
        for (HirExpr a : args) {
            if (!a.meta().kind().isPrimitive() && !a.meta().deeplyImmutable() && !a.meta().stringType()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a direct call of {@code fn} can be pure, as far as its callable arguments go. A
     * function that calls or passes on a function-typed parameter ({@code fn calling f},
     * {@code BIP017}) has its own effects only on condition of what each call passes : a callback
     * may reassign a local of the caller's frame (a {@code borrow} closure, {@code BIP016} §4.5),
     * which is no effect of the caller as a whole, but makes the call itself impure. Only the
     * call site's effects know, so they must be pure too.
     */
    private static boolean isCallSitePure(HirFunction fn, EffectSet callSiteEffects) {
        return callSiteEffects.isPure() || !usesCallableParameter(fn);
    }

    private static boolean usesCallableParameter(HirFunction fn) {
        Set<HirVar> callables = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (org.babblelang.ir.hir.HirParam p : fn.params()) {
            if (p.var() != null && p.checkedType() != null && p.checkedType().startsWith("fn|")) {
                callables.add(p.var());
            }
        }
        if (callables.isEmpty()) {
            return false;
        }
        boolean[] found = {false};
        for (HirExpr stmt : fn.body()) {
            findVarReads(stmt, callables, found);
            if (found[0]) {
                return true;
            }
        }
        return false;
    }

    private static void findVarReads(HirExpr expr, Set<HirVar> vars, boolean[] found) {
        if (expr == null || found[0]) {
            return;
        }
        if (expr instanceof HirExpr.VarRead r && vars.contains(r.var())) {
            found[0] = true;
            return;
        }
        HirRewriter.rewriteChildren(expr, child -> {
            findVarReads(child, vars, found);
            return child;
        });
    }

    public static boolean isFunctionPureNothrowTerminating(HirFunction fn, EffectSet callSiteEffects,
                                                           HirModule module, Set<Integer> visitingFns) {
        if (fn == null || fn.namespaceScope() || !fn.capturedVars().isEmpty()) {
            return false;
        }
        EffectSet fx = fn.effects();
        if (!fx.isPure() || fx.mutThis()) {
            return false;
        }
        boolean nothrow = fx.isNothrow() || callSiteEffects.isNothrow();
        boolean term = fx.terminating() || callSiteEffects.terminating();
        if (nothrow && term) {
            return true;
        }
        // Fallback: check small non-recursive function body directly (handles helpers calling axiom pure nothrow Math.*).
        if (!visitingFns.add(fn.id())) {
            return false;
        }
        try {
            for (HirExpr stmt : fn.body()) {
                if (!isBodyStmtPureNothrowTerminating(stmt, module, visitingFns)) {
                    return false;
                }
            }
            return true;
        } finally {
            visitingFns.remove(fn.id());
        }
    }

    private static boolean isBodyStmtPureNothrowTerminating(HirExpr expr, HirModule module, Set<Integer> visitingFns) {
        if (expr instanceof HirExpr.Def d && d.var() != null && !d.var().isCaptured()) {
            return d.value() == null || isPureNothrowTerminating(d.value(), module, visitingFns);
        }
        if (expr instanceof HirExpr.Return r) {
            return isPureNothrowTerminating(r.value(), module, visitingFns);
        }
        return isPureNothrowTerminating(expr, module, visitingFns);
    }

    /** Whether a pure expression is also guaranteed deterministic (safe to CSE / LICM / fold). */
    public static boolean isDeterministic(HirExpr expr, HirModule module) {
        if (expr == null) {
            return true;
        }
        if (expr instanceof HirExpr.DirectBabbleCall c) {
            EffectSet fx;
            if (c.externalTarget() != null) {
                fx = c.externalTarget().effects();
                if (!fx.isDeterministic() || !c.meta().effects().isDeterministic()) {
                    return false;
                }
            } else {
                HirFunction fn = module.function(c.targetFunctionId());
                fx = fn != null ? fn.effects() : c.meta().effects();
                if (!fx.isDeterministic()) {
                    return false;
                }
            }
        } else if (expr instanceof HirExpr.DirectThisMethodCall c) {
            HirFunction fn = c.targetFunctionId() >= 0 ? module.function(c.targetFunctionId()) : null;
            EffectSet fx = fn != null ? fn.effects() : c.meta().effects();
            if (!fx.isDeterministic()) {
                return false;
            }
        } else if (expr instanceof HirExpr.InterfaceCall c) {
            if (!c.meta().effects().isDeterministic()) {
                return false;
            }
        } else if (expr instanceof HirExpr.CoreMethodCall c) {
            if (!c.meta().effects().isDeterministic()) {
                return false;
            }
        } else if (expr instanceof HirExpr.JavaMethodCall c) {
            if (!isFoldableJava(c.target().method()) && !c.meta().effects().isDeterministic()) {
                return false;
            }
        } else if (expr instanceof HirExpr.For f) {
            if (!f.meta().effects().isDeterministic()) {
                return false;
            }
        }
        boolean[] ok = {true};
        HirRewriter.rewriteChildren(expr, child -> {
            if (ok[0] && !isDeterministic(child, module)) {
                ok[0] = false;
            }
            return child;
        });
        return ok[0];
    }

    public static boolean isIdempotentCall(HirExpr expr, HirModule module) {
        if (expr instanceof HirExpr.DirectBabbleCall c) {
            if (c.externalTarget() != null) {
                EffectSet fx = c.externalTarget().effects();
                return !c.externalTarget().hasMutParam()
                        && ((fx.isPure() && fx.isDeterministic() && fx.has(Effect.IDEMPOTENT))
                        || (c.meta().isPure() && c.meta().isDeterministic() && c.meta().isIdempotent()));
            }
            HirFunction fn = module.function(c.targetFunctionId());
            if (fn == null) return false;
            EffectSet fx = fn.effects();
            return (fx.isPure() && fx.isDeterministic() && fx.has(Effect.IDEMPOTENT))
                    || (c.meta().isPure() && c.meta().isDeterministic() && c.meta().isIdempotent());
        }
        if (expr instanceof HirExpr.JavaMethodCall c) {
            return isWhitelistedIdempotentJava(c.target().method())
                    || (c.meta().isPure() && c.meta().isDeterministic() && c.meta().isIdempotent());
        }
        return false;
    }

    private static boolean allPureNothrowTerminating(List<HirExpr> exprs, HirModule module, Set<Integer> visitingFns) {
        for (HirExpr e : exprs) {
            if (!isPureNothrowTerminating(e, module, visitingFns)) {
                return false;
            }
        }
        return true;
    }

    /** Collects all {@link HirVar#id()} that are written by {@code VarAssign} or bound inside a loop. */
    public static void collectModifiedVars(HirExpr expr, Set<Integer> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof HirExpr.VarAssign a) {
            out.add(a.var().id());
        } else if (expr instanceof HirExpr.Def d && d.var() != null) {
            out.add(d.var().id());
        } else if (expr instanceof HirExpr.Block b) {
            for (HirVar v : b.declaredVars()) {
                out.add(v.id());
            }
        } else if (expr instanceof HirExpr.For f) {
            if (f.loopVar() != null) {
                out.add(f.loopVar().id());
            }
            for (HirVar v : f.bodyDeclaredVars()) {
                out.add(v.id());
            }
        } else if (expr instanceof HirExpr.Try t) {
            for (HirVar v : t.tryDeclaredVars()) {
                out.add(v.id());
            }
            for (HirExpr.HirCatch cc : t.catches()) {
                if (cc.var() != null) {
                    out.add(cc.var().id());
                }
                for (HirVar v : cc.declaredVars()) {
                    out.add(v.id());
                }
            }
            for (HirVar v : t.finallyDeclaredVars()) {
                out.add(v.id());
            }
        }
        HirRewriter.rewriteChildren(expr, child -> {
            collectModifiedVars(child, out);
            return child;
        });
    }

    private static final class TopLevelCache {
        java.lang.ref.WeakReference<HirModule> moduleRef;
        Set<String> fns;
        Set<String> bindings;
    }

    private static final ThreadLocal<TopLevelCache> TOP_LEVEL_CACHE = ThreadLocal.withInitial(TopLevelCache::new);

    public static boolean isDefinitelyInitializedTopLevelFn(String name, HirModule module) {
        return findDefinitelyInitializedTopLevelFns(module).contains(name);
    }

    public static boolean isDefinitelyInitializedTopLevelBinding(String name, HirModule module) {
        return findDefinitelyInitializedTopLevelBindings(module).contains(name);
    }

    /**
     * Collects top-level {@code def name = fn ...} bindings that appear in the leading pure
     * declaration prefix of the module and are never reassigned anywhere in the module.
     */
    public static Set<String> findDefinitelyInitializedTopLevelFns(HirModule module) {
        return cachedTopLevel(module).fns;
    }

    /**
     * Collects top-level {@code def name = fn|class|record|interface|const} bindings that appear
     * in the leading pure declaration prefix of the module and are never reassigned.
     */
    public static Set<String> findDefinitelyInitializedTopLevelBindings(HirModule module) {
        return cachedTopLevel(module).bindings;
    }

    private static TopLevelCache cachedTopLevel(HirModule module) {
        TopLevelCache cache = TOP_LEVEL_CACHE.get();
        if (cache.moduleRef != null && cache.moduleRef.get() == module) {
            return cache;
        }
        Set<String> reassignedNames = new HashSet<>();
        HirRewriter.rewriteModuleBodies(module, stmts -> {
            for (HirExpr s : stmts) {
                collectNameAssigns(s, reassignedNames);
            }
            return stmts;
        });
        Set<String> fns = new HashSet<>();
        Set<String> bindings = new HashSet<>();
        for (HirExpr stmt : module.topLevelStatements()) {
            if (stmt instanceof HirExpr.Def d && d.var() == null && d.target().kind() == LoweringTargets.DefKind.LEXICAL) {
                HirExpr val = unwrapTransparent(d.value());
                if (val instanceof HirExpr.FunctionRef) {
                    if (!reassignedNames.contains(d.name())) {
                        fns.add(d.name());
                        bindings.add(d.name());
                    }
                    continue;
                }
                if (val instanceof HirExpr.Const || val instanceof HirExpr.ClassExpr || val instanceof HirExpr.InterfaceExpr) {
                    if (!reassignedNames.contains(d.name())) {
                        bindings.add(d.name());
                    }
                    continue;
                }
            }
            break;
        }
        cache.moduleRef = new java.lang.ref.WeakReference<>(module);
        cache.fns = java.util.Collections.unmodifiableSet(fns);
        cache.bindings = java.util.Collections.unmodifiableSet(bindings);
        return cache;
    }

    private static HirExpr unwrapTransparent(HirExpr expr) {
        while (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
            expr = b.statements().get(0);
        }
        return expr;
    }

    private static void collectNameAssigns(HirExpr expr, Set<String> out) {
        if (expr == null) return;
        if (expr instanceof HirExpr.NameAssign a) {
            out.add(a.name());
        }
        HirRewriter.rewriteChildren(expr, child -> {
            collectNameAssigns(child, out);
            return child;
        });
    }
}
