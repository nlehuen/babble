package org.babblelang.opt;

import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.ScopeAnalysis;
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

    public static boolean isNumericKind(ScopeAnalysis.PrimitiveKind kind) {
        return switch (kind) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    public static boolean isIntegralKind(ScopeAnalysis.PrimitiveKind kind) {
        return kind == ScopeAnalysis.PrimitiveKind.INT || kind == ScopeAnalysis.PrimitiveKind.LONG;
    }

    public static boolean canLowerComparison(HirExpr.Binary b) {
        ScopeAnalysis.PrimitiveKind lk = b.left().meta().kind();
        ScopeAnalysis.PrimitiveKind rk = b.right().meta().kind();
        if (lk == ScopeAnalysis.PrimitiveKind.BOOL && rk == ScopeAnalysis.PrimitiveKind.BOOL) {
            return b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ;
        }
        if (lk == ScopeAnalysis.PrimitiveKind.CHAR && rk == ScopeAnalysis.PrimitiveKind.CHAR) {
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
            case HirExpr.FunctionRef f -> true;
            case HirExpr.VarRead v -> !v.var().mayBeUndefined() || v.var().definitelyDefined();
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
                    yield canLowerComparison(b);
                }
                if (b.meta().kind().isPrimitive()
                        && isNumericKind(b.left().meta().kind()) && isNumericKind(b.right().meta().kind())) {
                    yield true;
                }
                yield b.op() == HirExpr.BinaryOp.ADD && b.left().meta().stringType() && b.right().meta().stringType();
            }
            case HirExpr.Logic l -> isPureNothrowTerminating(l.left(), module, visitingFns)
                    && isPureNothrowTerminating(l.right(), module, visitingFns);
            case HirExpr.Block b -> !b.opensNamespaceScope() && allPureNothrowTerminating(b.statements(), module, visitingFns);
            case HirExpr.If i -> isPureNothrowTerminating(i.cond(), module, visitingFns)
                    && isPureNothrowTerminating(i.thenBranch(), module, visitingFns)
                    && (i.elseBranch() == null || isPureNothrowTerminating(i.elseBranch(), module, visitingFns));
            case HirExpr.As a -> a.targetDescriptor() == null && isPureNothrowTerminating(a.left(), module, visitingFns);
            case HirExpr.TypeApply t -> isPureNothrowTerminating(t.inner(), module, visitingFns);
            case HirExpr.MakeOverloadSet m -> allPureNothrowTerminating(m.operands(), module, visitingFns);
            case HirExpr.OverloadSelect s -> s.exactSet() && isPureNothrowTerminating(s.set(), module, visitingFns);
            case HirExpr.DirectBabbleCall c -> {
                boolean calleeOk = isPureNothrowTerminating(c.callee(), module, visitingFns)
                        || (c.callee() instanceof HirExpr.NameRead nr
                        && isDefinitelyInitializedTopLevelFn(nr.name(), module));
                if (!calleeOk || !allPureNothrowTerminating(c.args(), module, visitingFns)) {
                    yield false;
                }
                HirFunction fn = module.function(c.targetFunctionId());
                yield fn != null && isFunctionPureNothrowTerminating(fn, c.meta().effects(), module, visitingFns);
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
                if (g.classId() < 0 || g.memberIndex() < 0) {
                    yield false;
                }
                HirClass cls = module.clazz(g.classId());
                yield cls != null && cls.shape().isValue()
                        && (g.memberIndex() >= cls.primDefBits().length || cls.primDefBits()[g.memberIndex()] < 0)
                        && isPureNothrowTerminating(g.receiver(), module, visitingFns);
            }
            default -> false;
        };
    }

    private static boolean allArgsPrimitiveOrImmutable(List<HirExpr> args) {
        for (HirExpr a : args) {
            if (!a.meta().kind().isPrimitive() && !a.meta().deeplyImmutable() && !a.meta().stringType()) {
                return false;
            }
        }
        return true;
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
        if (expr instanceof HirExpr.Def d && d.var() != null && !d.var().cell()) {
            return d.value() == null || isPureNothrowTerminating(d.value(), module, visitingFns);
        }
        if (expr instanceof HirExpr.Return r) {
            return isPureNothrowTerminating(r.value(), module, visitingFns);
        }
        return isPureNothrowTerminating(expr, module, visitingFns);
    }

    /** Whether a pure expression is also guaranteed deterministic (safe to CSE / LICM / fold). */
    public static boolean isDeterministic(HirExpr expr, HirModule module) {
        return isDeterministic(expr, module, new HashSet<>());
    }

    private static boolean isDeterministic(HirExpr expr, HirModule module, Set<Integer> visitingFns) {
        if (expr == null) {
            return true;
        }
        return switch (expr) {
            case HirExpr.Const c -> true;
            case HirExpr.CoreCollectionRef r -> true;
            case HirExpr.FunctionRef f -> true;
            case HirExpr.VarRead v -> true;
            case HirExpr.ThisRef t -> true;
            case HirExpr.Unary u -> isDeterministic(u.operand(), module, visitingFns);
            case HirExpr.Binary b -> isDeterministic(b.left(), module, visitingFns)
                    && isDeterministic(b.right(), module, visitingFns);
            case HirExpr.Logic l -> isDeterministic(l.left(), module, visitingFns)
                    && isDeterministic(l.right(), module, visitingFns);
            case HirExpr.Block b -> b.statements().stream().allMatch(s -> isDeterministic(s, module, visitingFns));
            case HirExpr.If i -> isDeterministic(i.cond(), module, visitingFns)
                    && isDeterministic(i.thenBranch(), module, visitingFns)
                    && (i.elseBranch() == null || isDeterministic(i.elseBranch(), module, visitingFns));
            case HirExpr.As a -> isDeterministic(a.left(), module, visitingFns);
            case HirExpr.TypeApply t -> isDeterministic(t.inner(), module, visitingFns);
            case HirExpr.ThisFieldGet g -> isDeterministic(g.receiver(), module, visitingFns);
            case HirExpr.ArrayOp op -> op.kind() == ScopeAnalysis.ArrayOpKind.SIZE
                    && isDeterministic(op.receiver(), module, visitingFns);
            case HirExpr.DirectBabbleCall c -> {
                boolean calleeOk = isDeterministic(c.callee(), module, visitingFns)
                        || (c.callee() instanceof HirExpr.NameRead nr
                        && isDefinitelyInitializedTopLevelFn(nr.name(), module));
                if (!calleeOk) yield false;
                for (HirExpr a : c.args()) {
                    if (!isDeterministic(a, module, visitingFns)) yield false;
                }
                HirFunction fn = module.function(c.targetFunctionId());
                if (fn == null || fn.namespaceScope() || !fn.capturedVars().isEmpty()) yield false;
                EffectSet fx = fn.effects();
                yield fx.isDeterministic();
            }
            case HirExpr.JavaMethodCall c -> {
                if (!c.target().isStatic() && !isDeterministic(c.receiver(), module, visitingFns)) yield false;
                for (HirExpr a : c.args()) {
                    if (!isDeterministic(a, module, visitingFns)) yield false;
                }
                Method m = c.target().method();
                yield isFoldableJava(m) || c.meta().effects().isDeterministic();
            }
            default -> false;
        };
    }

    public static boolean isIdempotentCall(HirExpr expr, HirModule module) {
        if (expr instanceof HirExpr.DirectBabbleCall c) {
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
        switch (expr) {
            case HirExpr.VarAssign a -> {
                out.add(a.var().id());
                collectModifiedVars(a.value(), out);
            }
            case HirExpr.Def d -> {
                if (d.var() != null) out.add(d.var().id());
                collectModifiedVars(d.value(), out);
            }
            case HirExpr.NameAssign a -> collectModifiedVars(a.value(), out);
            case HirExpr.ThisFieldAssign a -> {
                collectModifiedVars(a.receiver(), out);
                collectModifiedVars(a.value(), out);
            }
            case HirExpr.JavaFieldAssign a -> {
                collectModifiedVars(a.receiver(), out);
                collectModifiedVars(a.value(), out);
            }
            case HirExpr.DynamicFieldAssign a -> {
                collectModifiedVars(a.receiver(), out);
                collectModifiedVars(a.value(), out);
            }
            case HirExpr.ThisFieldGet g -> collectModifiedVars(g.receiver(), out);
            case HirExpr.JavaFieldGet g -> collectModifiedVars(g.receiver(), out);
            case HirExpr.ArrayCreate c -> {
                for (HirExpr a : c.args()) collectModifiedVars(a, out);
            }
            case HirExpr.ArrayOp op -> {
                collectModifiedVars(op.receiver(), out);
                for (HirExpr a : op.args()) collectModifiedVars(a, out);
            }
            case HirExpr.ModuleExportGet g -> collectModifiedVars(g.receiver(), out);
            case HirExpr.DynamicSelect s -> collectModifiedVars(s.receiver(), out);
            case HirExpr.Unary u -> collectModifiedVars(u.operand(), out);
            case HirExpr.Binary b -> {
                collectModifiedVars(b.left(), out);
                collectModifiedVars(b.right(), out);
            }
            case HirExpr.Logic l -> {
                collectModifiedVars(l.left(), out);
                collectModifiedVars(l.right(), out);
            }
            case HirExpr.Is i -> collectModifiedVars(i.left(), out);
            case HirExpr.As a -> collectModifiedVars(a.left(), out);
            case HirExpr.NonNull n -> collectModifiedVars(n.operand(), out);
            case HirExpr.SpreadAdapt s -> collectModifiedVars(s.value(), out);
            case HirExpr.TypeApply t -> collectModifiedVars(t.inner(), out);
            case HirExpr.Block b -> {
                for (HirVar v : b.declaredVars()) out.add(v.id());
                for (HirExpr s : b.statements()) collectModifiedVars(s, out);
            }
            case HirExpr.If i -> {
                collectModifiedVars(i.cond(), out);
                collectModifiedVars(i.thenBranch(), out);
                collectModifiedVars(i.elseBranch(), out);
            }
            case HirExpr.While w -> {
                collectModifiedVars(w.cond(), out);
                collectModifiedVars(w.body(), out);
            }
            case HirExpr.For f -> {
                collectModifiedVars(f.iterable(), out);
                if (f.loopVar() != null) out.add(f.loopVar().id());
                for (HirVar v : f.bodyDeclaredVars()) out.add(v.id());
                for (HirExpr s : f.body()) collectModifiedVars(s, out);
            }
            case HirExpr.Break b -> collectModifiedVars(b.value(), out);
            case HirExpr.Return r -> collectModifiedVars(r.value(), out);
            case HirExpr.CoreCollectionCall c -> collectCallArgsModified(c.args(), out);
            case HirExpr.SuperCall c -> collectCallArgsModified(c.args(), out);
            case HirExpr.SelfRecurseCall c -> {
                for (HirExpr a : c.args()) collectModifiedVars(a, out);
            }
            case HirExpr.SelfTailCall c -> collectCallArgsModified(c.args(), out);
            case HirExpr.DirectBabbleCall c -> {
                collectModifiedVars(c.callee(), out);
                for (HirExpr a : c.args()) collectModifiedVars(a, out);
            }
            case HirExpr.DirectThisMethodCall c -> {
                collectModifiedVars(c.receiver(), out);
                collectCallArgsModified(c.args(), out);
            }
            case HirExpr.InterfaceCall c -> {
                collectModifiedVars(c.receiver(), out);
                for (HirExpr a : c.args()) collectModifiedVars(a, out);
            }
            case HirExpr.JavaConstructorCall c -> {
                collectModifiedVars(c.callee(), out);
                for (HirExpr a : c.args()) collectModifiedVars(a, out);
            }
            case HirExpr.JavaMethodCall c -> {
                collectModifiedVars(c.receiver(), out);
                for (HirExpr a : c.args()) collectModifiedVars(a, out);
            }
            case HirExpr.SafeCall c -> {
                collectModifiedVars(c.receiver(), out);
                collectCallArgsModified(c.args(), out);
            }
            case HirExpr.DynamicCall c -> {
                collectModifiedVars(c.callee(), out);
                collectCallArgsModified(c.args(), out);
            }
            case HirExpr.MakeOverloadSet m -> {
                for (HirExpr o : m.operands()) collectModifiedVars(o, out);
            }
            case HirExpr.OverloadSelect s -> collectModifiedVars(s.set(), out);
            case HirExpr.OverloadDispatch d -> {
                collectModifiedVars(d.callee(), out);
                for (HirExpr a : d.args()) collectModifiedVars(a, out);
            }
            case HirExpr.Const c -> { }
            case HirExpr.IntParseFallback f -> { }
            case HirExpr.CoreCollectionRef r -> { }
            case HirExpr.VarRead v -> { }
            case HirExpr.NameRead n -> { }
            case HirExpr.ThisRef t -> { }
            case HirExpr.RecurseRef r -> { }
            case HirExpr.Continue c -> { }
            case HirExpr.FunctionRef f -> { }
            case HirExpr.ClassExpr c -> { }
            case HirExpr.InterfaceExpr i -> { }
        }
    }

    private static void collectCallArgsModified(List<HirCallArg> args, Set<Integer> out) {
        for (HirCallArg a : args) {
            collectModifiedVars(a.value(), out);
        }
    }

    public static boolean isDefinitelyInitializedTopLevelFn(String name, HirModule module) {
        return findDefinitelyInitializedTopLevelFns(module).contains(name);
    }

    /**
     * Collects top-level {@code def name = fn ...} bindings that appear in the leading pure
     * declaration prefix of the module and are never reassigned anywhere in the module.
     */
    public static Set<String> findDefinitelyInitializedTopLevelFns(HirModule module) {
        Set<String> reassignedNames = new HashSet<>();
        HirRewriter.rewriteModuleBodies(module, stmts -> {
            for (HirExpr s : stmts) {
                collectNameAssigns(s, reassignedNames);
            }
            return stmts;
        });
        Set<String> result = new HashSet<>();
        for (HirExpr stmt : module.topLevelStatements()) {
            if (stmt instanceof HirExpr.Def d && d.var() == null && d.target().kind() == ScopeAnalysis.DefKind.LEXICAL) {
                HirExpr val = unwrapTransparent(d.value());
                if (val instanceof HirExpr.FunctionRef) {
                    if (!reassignedNames.contains(d.name())) {
                        result.add(d.name());
                    }
                    continue;
                }
                if (val instanceof HirExpr.Const || val instanceof HirExpr.ClassExpr || val instanceof HirExpr.InterfaceExpr) {
                    continue;
                }
            }
            break;
        }
        return result;
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
