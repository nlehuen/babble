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
            case HirExpr.Block b -> !b.opensNamespaceScope() && isPureLocalBlock(b, Set.of(), module, visitingFns);
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
            if (!v.cell()) {
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
            EffectSet fx = f.meta().effects();
            if (!fx.isPure() || !fx.isNothrow() || !fx.terminating()
                    || !isPureNothrowTerminating(f.iterable(), module, visitingFns)) {
                return false;
            }
            Set<Integer> extended = new HashSet<>(localIds);
            for (HirVar v : f.bodyDeclaredVars()) {
                if (!v.cell()) {
                    extended.add(v.id());
                }
            }
            return allPureWithLocals(f.body(), extended, module, visitingFns);
        }
        return isPureNothrowTerminating(s, module, visitingFns);
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
        if (expr == null) {
            return true;
        }
        if (expr instanceof HirExpr.DirectBabbleCall c) {
            HirFunction fn = module.function(c.targetFunctionId());
            EffectSet fx = fn != null ? fn.effects() : c.meta().effects();
            if (!fx.isDeterministic()) {
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
            if (stmt instanceof HirExpr.Def d && d.var() == null && d.target().kind() == LoweringTargets.DefKind.LEXICAL) {
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
