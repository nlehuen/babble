package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Algebraic signature validation and compositional property verification
 * ({@code idempotent}, {@code commutative}, {@code associative}, {@code monotone},
 * {@code injective}) extracted from {@link EffectChecker} ({@code BIP027-I3};
 * upgraded onto {@code org.babblelang.ir.egraph} in {@code BIP027-P6}).
 */
final class AlgebraicVerifier {
    private final EffectChecker ec;

    AlgebraicVerifier(EffectChecker ec) {
        this.ec = ec;
    }

    // ================================================================ 9. Algebraic Validation & Composition

    void validateAlgebraicSignature(Set<Effect> effects, List<Type.Parameter> params, Type result,
                                            boolean mutThis, boolean axiomatic, ParserRuleContext at) {
        if (effects.isEmpty()) {
            return;
        }
        boolean anyMutParam = params.stream().anyMatch(Type.Parameter::mut);
        boolean hasMut = mutThis || anyMutParam;
        boolean isPure = effects.contains(Effect.PURE);

        if (effects.contains(Effect.ASSOCIATIVE)) {
            if (!isPure || hasMut || params.size() != 2
                    || result == Type.NULL || result == Type.NOTHING
                    || !sameDomainType(params.get(0).type(), params.get(1).type())
                    || !sameDomainType(params.get(0).type(), result)) {
                ec.checker.effectDiagnostic("associative requires a pure binary (T, T) : T signature", at);
            }
        }
        if (effects.contains(Effect.COMMUTATIVE)) {
            if (!hasMut) {
                if (!isPure || params.size() != 2 || !sameDomainType(params.get(0).type(), params.get(1).type())) {
                    ec.checker.effectDiagnostic("commutative without mut requires a pure binary (T, T) : R signature", at);
                }
            } else if (mutThis ? params.isEmpty() : params.size() < 2) {
                ec.checker.effectDiagnostic("mut commutative requires a value parameter on the mutated target", at);
            }
        }
        if (effects.contains(Effect.IDEMPOTENT)) {
            if (!hasMut && !(axiomatic && !isPure)) {
                if (!isPure || params.size() != 1 || result == Type.NULL || !sameDomainType(params.get(0).type(), result)) {
                    ec.checker.effectDiagnostic("idempotent without mut requires a pure unary (T) : T signature or an effectful axiom", at);
                }
            }
        }
        if (effects.contains(Effect.MONOTONE)) {
            if (hasMut || params.isEmpty() || !isOrderedType(params.get(0).type()) || !isOrderedType(result)) {
                ec.checker.effectDiagnostic("monotone requires ordered parameter and return types without mut", at);
            }
        }
        if (effects.contains(Effect.INJECTIVE)) {
            if (hasMut || params.isEmpty() || result == Type.NULL || result == Type.NOTHING || result == Type.BOOL && !isBoolType(params.get(0).type())) {
                if (result == Type.NULL || result == Type.NOTHING || hasMut || params.isEmpty()) {
                    ec.checker.effectDiagnostic("injective requires at least one parameter, no mut, and a non-null return type", at);
                }
            }
        }
    }

    private boolean sameDomainType(Type a, Type b) {
        if (a == Type.ERROR || b == Type.ERROR) {
            return true;
        }
        return a.equals(b) || (ec.checker.assignable(a, b) && ec.checker.assignable(b, a));
    }

    private static boolean isBoolType(Type t) {
        return t == Type.BOOL;
    }

    static boolean isOrderedType(Type t) {
        if (t == Type.ERROR) {
            return true;
        }
        if (t == Type.INT || t == Type.DOUBLE || t == Type.BOOL || t == Type.STRING) {
            return true;
        }
        if (t instanceof Type.JavaInstance ji) {
            return Comparable.class.isAssignableFrom(ji.type());
        }
        return false;
    }

    void inferAlgebraicProperties(BabbleParser.FunctionLiteralContext fn,
                                          EnumSet<Effect> effs,
                                          boolean[] mutParams,
                                          boolean mutThis) {
        List<BabbleParser.FnParamContext> paramDecls = fn.fnParameters().fnParam();
        Type fnRaw = ec.checker.rawTypeOf(fn);
        if (!(fnRaw instanceof Type.Function tf)) {
            return;
        }
        List<Type.Parameter> params = tf.parameters();
        Type result = tf.result();
        BabbleParser.ExpressionContext single = singleBodyExpression(fn.functionBlock);

        boolean anyMut = mutThis || EffectChecker.anyTrue(mutParams);
        if (!anyMut && effs.contains(Effect.PURE) && effs.contains(Effect.DETERMINISTIC) && single != null) {
            // 1. Compositional monotone & injective in parameter 0 (when other parameters are untouched constants).
            if (!paramDecls.isEmpty()) {
                BabbleParser.FnParamContext p0 = paramDecls.get(0);
                RelProps rel = relationalProps(single, p0);
                if (rel.monotoneSign == 1 && isOrderedType(params.get(0).type()) && isOrderedType(result)) {
                    effs.add(Effect.MONOTONE);
                }
                if (rel.injective && result != Type.NULL && result != Type.NOTHING) {
                    effs.add(Effect.INJECTIVE);
                }
            }
            // 2. Binary single-op wrapper for commutative & associative.
            if (paramDecls.size() == 2 && sameDomainType(params.get(0).type(), params.get(1).type())) {
                BabbleParser.FnParamContext p0 = paramDecls.get(0);
                BabbleParser.FnParamContext p1 = paramDecls.get(1);
                WrapperProps wp = binaryWrapperProps(single, p0, p1);
                if (wp.commutative) {
                    effs.add(Effect.COMMUTATIVE);
                }
                if (wp.associative && sameDomainType(params.get(0).type(), result)
                        && result != Type.NULL && result != Type.NOTHING) {
                    effs.add(Effect.ASSOCIATIVE);
                }
            }
            // 3. Unary single-op wrapper for idempotent.
            if (paramDecls.size() == 1 && sameDomainType(params.get(0).type(), result) && result != Type.NULL) {
                if (isUnaryIdempotentWrapper(single, paramDecls.get(0))) {
                    effs.add(Effect.IDEMPOTENT);
                }
            }
        } else if (anyMut && effs.contains(Effect.HERMETIC) && single != null) {
            // Effect-form single-op wrapper or invariant field overwrite!
            if (isMutIdempotentBody(fn, single, paramDecls, mutParams, mutThis)) {
                effs.add(Effect.IDEMPOTENT);
            }
            if (isMutCommutativeBody(fn, single, paramDecls, mutParams, mutThis)) {
                effs.add(Effect.COMMUTATIVE);
            }
        }
    }

    private record RelProps(int monotoneSign, boolean injective, boolean usesTarget) {
        static final RelProps NONE = new RelProps(0, false, true);
        static final RelProps CONST = new RelProps(0, false, false);
    }

    private record WrapperProps(boolean commutative, boolean associative) {
        static final WrapperProps NONE = new WrapperProps(false, false);
    }

    private static BabbleParser.ExpressionContext singleBodyExpression(BabbleParser.BlockContext block) {
        if (block == null || block.expression().size() != 1 || !block.effectDeclaration().isEmpty()) {
            return null;
        }
        BabbleParser.ExpressionContext e = block.expression(0);
        while (true) {
            if (e instanceof BabbleParser.ReturnExpressionContext ret && ret.expression() != null) {
                e = ret.expression();
            } else if (e instanceof BabbleParser.BlockExpressionContext be
                    && be.block().expression().size() == 1 && be.block().effectDeclaration().isEmpty()) {
                e = be.block().expression(0);
            } else {
                return e;
            }
        }
    }

    static BabbleParser.ExpressionContext unwrapParens(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext be
                && be.block().expression().size() == 1 && be.block().effectDeclaration().isEmpty()) {
            expr = be.block().expression(0);
        }
        return expr;
    }

    private RelProps relationalProps(BabbleParser.ExpressionContext raw, BabbleParser.FnParamContext targetParam) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        if (!referencesDecl(expr, targetParam)) {
            return RelProps.CONST;
        }
        if (expr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            Object resolved = ec.checker.resolveNameForEffects(sel.ID().getText(), ec.checker.occurrenceScopeOf(sel));
            if (resolved == targetParam) {
                Type t = ec.checker.rawTypeOf(sel);
                return new RelProps(isOrderedType(t) ? 1 : 0, true, true);
            }
            return RelProps.CONST;
        }
        if (expr instanceof BabbleParser.NegateContext neg) {
            RelProps sub = relationalProps(neg.operand, targetParam);
            if (!sub.usesTarget) {
                return RelProps.CONST;
            }
            Type resType = ec.checker.rawTypeOf(neg);
            if (resType == Type.INT) {
                // Wrapping 32-bit negation is an involution (injective), NOT monotone!
                return new RelProps(0, sub.injective, true);
            }
            if (resType == Type.DOUBLE) {
                return new RelProps(-sub.monotoneSign, sub.injective, true);
            }
            return RelProps.NONE;
        }
        if (expr instanceof BabbleParser.BinaryOpContext bin) {
            String op = bin.op.getText();
            RelProps l = relationalProps(bin.left, targetParam);
            RelProps r = relationalProps(bin.right, targetParam);
            Type resType = ec.checker.rawTypeOf(bin);
            if (resType == Type.INT) {
                // Wrapping 32-bit int arithmetic is NEVER monotone (§3.2, §4.6)!
                if (l.usesTarget && !r.usesTarget) {
                    boolean inj = switch (op) {
                        case "+", "-" -> l.injective;
                        case "*" -> l.injective && isOddIntConstant(bin.right);
                        default -> false;
                    };
                    return new RelProps(0, inj, true);
                }
                if (!l.usesTarget && r.usesTarget) {
                    boolean inj = switch (op) {
                        case "+", "-" -> r.injective;
                        case "*" -> r.injective && isOddIntConstant(bin.left);
                        default -> false;
                    };
                    return new RelProps(0, inj, true);
                }
                return RelProps.NONE;
            }
            if (resType == Type.DOUBLE) {
                // IEEE-754 double arithmetic: monotone on +, -, and * (k >= 0), NOT injective (§4.6).
                if ("+".equals(op)) {
                    int sign = combineAddSigns(l, r);
                    return new RelProps(sign, false, true);
                }
                if ("-".equals(op)) {
                    RelProps negR = r.usesTarget ? new RelProps(-r.monotoneSign, false, true) : RelProps.CONST;
                    int sign = combineAddSigns(l, negR);
                    return new RelProps(sign, false, true);
                }
                if ("*".equals(op)) {
                    if (l.usesTarget && !r.usesTarget) {
                        Double k = doubleConstant(bin.right);
                        int sign = k == null ? 0 : (k >= 0.0 ? l.monotoneSign : -l.monotoneSign);
                        return new RelProps(sign, false, true);
                    }
                    if (!l.usesTarget && r.usesTarget) {
                        Double k = doubleConstant(bin.left);
                        int sign = k == null ? 0 : (k >= 0.0 ? r.monotoneSign : -r.monotoneSign);
                        return new RelProps(sign, false, true);
                    }
                }
                return RelProps.NONE;
            }
            if (resType == Type.STRING && "+".equals(op)) {
                Type lt = ec.checker.rawTypeOf(bin.left);
                Type rt = ec.checker.rawTypeOf(bin.right);
                if (l.usesTarget && !r.usesTarget) {
                    boolean inj = l.injective && isLosslessToStringType(lt);
                    return new RelProps(0, inj, true);
                }
                if (!l.usesTarget && r.usesTarget) {
                    boolean inj = r.injective && isLosslessToStringType(rt);
                    return new RelProps(0, inj, true);
                }
                return RelProps.NONE;
            }
            return RelProps.NONE;
        }
        if (expr instanceof BabbleParser.CallContext call) {
            List<BabbleParser.CallParameterContext> args = call.callParameters().callParameter();
            if (args.isEmpty()) {
                return RelProps.NONE;
            }
            for (int i = 1; i < args.size(); i++) {
                if (referencesDecl(args.get(i).expression(), targetParam)) {
                    return RelProps.NONE;
                }
            }
            if (call.expression() instanceof BabbleParser.SelectorContext sel && sel.expression() != null
                    && referencesDecl(sel.expression(), targetParam)) {
                return RelProps.NONE;
            }
            RelProps arg0 = relationalProps(args.get(0).expression(), targetParam);
            if (!arg0.usesTarget) {
                return RelProps.CONST;
            }
            EffectSet calleeEffects = calleeIntrinsicOrResolvedEffects(call);
            if (calleeEffects == null || !calleeEffects.isPure() || !calleeEffects.isDeterministic()) {
                return RelProps.NONE;
            }
            int mono = (calleeEffects.has(Effect.MONOTONE) && arg0.monotoneSign == 1) ? 1 : 0;
            boolean inj = calleeEffects.has(Effect.INJECTIVE) && arg0.injective;
            return new RelProps(mono, inj, true);
        }
        if (expr instanceof BabbleParser.AsExpressionContext asExpr) {
            Type src = ec.checker.rawTypeOf(asExpr.left);
            Type dst = ec.checker.rawTypeOf(asExpr);
            if (src == Type.INT && dst == Type.DOUBLE) {
                return relationalProps(asExpr.left, targetParam);
            }
        }
        return RelProps.NONE;
    }

    private static int combineAddSigns(RelProps l, RelProps r) {
        if (l.usesTarget && !r.usesTarget) {
            return l.monotoneSign;
        }
        if (!l.usesTarget && r.usesTarget) {
            return r.monotoneSign;
        }
        if (l.usesTarget && r.usesTarget && l.monotoneSign != 0 && l.monotoneSign == r.monotoneSign) {
            return l.monotoneSign;
        }
        return 0;
    }

    private static boolean isLosslessToStringType(Type type) {
        return type == Type.STRING || type == Type.INT || type == Type.BOOL;
    }

    private static boolean isOddIntConstant(BabbleParser.ExpressionContext raw) {
        BabbleParser.ExpressionContext e = unwrapParens(raw);
        if (e instanceof BabbleParser.NegateContext neg) {
            return isOddIntConstant(neg.operand);
        }
        if (e instanceof BabbleParser.IntegerContext ic) {
            try {
                int v = Integer.parseInt(ic.getText());
                return (v & 1) != 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private static Double doubleConstant(BabbleParser.ExpressionContext raw) {
        BabbleParser.ExpressionContext e = unwrapParens(raw);
        if (e instanceof BabbleParser.NegateContext neg) {
            Double sub = doubleConstant(neg.operand);
            return sub != null ? -sub : null;
        }
        if (e instanceof BabbleParser.IntegerContext ic) {
            try {
                return (double) Integer.parseInt(ic.getText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (e instanceof BabbleParser.DoubleContext dc) {
            try {
                double d = Double.parseDouble(dc.getText());
                return Double.isNaN(d) ? null : d;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private WrapperProps binaryWrapperProps(BabbleParser.ExpressionContext raw,
                                            BabbleParser.FnParamContext p0,
                                            BabbleParser.FnParamContext p1) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        if (expr instanceof BabbleParser.BinaryOpContext bin) {
            int order = matchBinaryParams(bin.left, bin.right, p0, p1);
            if (order == 0) {
                return WrapperProps.NONE;
            }
            String op = bin.op.getText();
            Type res = ec.checker.rawTypeOf(bin);
            Type lt = ec.checker.rawTypeOf(bin.left);
            Type rt = ec.checker.rawTypeOf(bin.right);
            if ("+".equals(op)) {
                if (res == Type.INT) {
                    return new WrapperProps(true, true);
                }
                if (res == Type.DOUBLE) {
                    return new WrapperProps(true, false);
                }
                if (lt == Type.STRING && rt == Type.STRING) {
                    return new WrapperProps(false, order == 1);
                }
            } else if ("*".equals(op)) {
                if (res == Type.INT) {
                    return new WrapperProps(true, true);
                }
                if (res == Type.DOUBLE) {
                    return new WrapperProps(true, false);
                }
            } else if ("==".equals(op) || "!=".equals(op) || "<>".equals(op)) {
                return new WrapperProps(true, false);
            }
            return WrapperProps.NONE;
        }
        if (expr instanceof BabbleParser.CallContext call) {
            List<BabbleParser.CallParameterContext> args = call.callParameters().callParameter();
            if (args.size() != 2 || ScopeAnalysis.callParamName(args.get(0)) != null || ScopeAnalysis.callParamName(args.get(1)) != null) {
                return WrapperProps.NONE;
            }
            int order = matchBinaryParams(args.get(0).expression(), args.get(1).expression(), p0, p1);
            if (order == 0) {
                return WrapperProps.NONE;
            }
            EffectSet effs = calleeIntrinsicOrResolvedEffects(call);
            if (effs == null || !effs.isPure()) {
                return WrapperProps.NONE;
            }
            return new WrapperProps(effs.has(Effect.COMMUTATIVE), order == 1 && effs.has(Effect.ASSOCIATIVE));
        }
        return WrapperProps.NONE;
    }

    private int matchBinaryParams(BabbleParser.ExpressionContext a, BabbleParser.ExpressionContext b,
                                  BabbleParser.FnParamContext p0,
                                  BabbleParser.FnParamContext p1) {
        Object ra = resolvePlainParam(unwrapParens(a));
        Object rb = resolvePlainParam(unwrapParens(b));
        if (ra == p0 && rb == p1) {
            return 1;
        }
        if (ra == p1 && rb == p0) {
            return -1;
        }
        return 0;
    }

    private boolean isUnaryIdempotentWrapper(BabbleParser.ExpressionContext raw,
                                             BabbleParser.FnParamContext p0) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        if (resolvePlainParam(expr) == p0) {
            // Identity function `(x) -> (x)` is idempotent!
            return true;
        }
        if (expr instanceof BabbleParser.CallContext call) {
            List<BabbleParser.CallParameterContext> args = call.callParameters().callParameter();
            if (args.size() == 1 && ScopeAnalysis.callParamName(args.get(0)) == null && resolvePlainParam(unwrapParens(args.get(0).expression())) == p0) {
                EffectSet effs = calleeIntrinsicOrResolvedEffects(call);
                return effs != null && effs.isPure() && effs.has(Effect.IDEMPOTENT);
            }
            if (args.isEmpty() && call.expression() instanceof BabbleParser.SelectorContext sel && sel.expression() != null
                    && resolvePlainParam(unwrapParens(sel.expression())) == p0) {
                EffectSet effs = calleeIntrinsicOrResolvedEffects(call);
                return effs != null && effs.isPure() && effs.has(Effect.IDEMPOTENT);
            }
        }
        return false;
    }

    private boolean isMutIdempotentBody(BabbleParser.FunctionLiteralContext fn,
                                        BabbleParser.ExpressionContext raw,
                                        List<BabbleParser.FnParamContext> params,
                                        boolean[] mutParams,
                                        boolean mutThis) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        // 1. Direct overwrite `this.field = val` or `mutParam.field = val` where `val` does not read the overwritten field!
        if (expr instanceof BabbleParser.AssignExpressionContext assign && "=".equals(assign.op.getText()) && ScopeAnalysis.assignReceiver(assign) != null) {
            BabbleParser.ExpressionContext ns = unwrapParens(ScopeAnalysis.assignReceiver(assign));
            if ((mutThis && ns instanceof BabbleParser.ThisExpressionContext)
                    || (resolvePlainParam(ns) instanceof BabbleParser.FnParamContext p
                    && params.indexOf(p) >= 0 && mutParams[params.indexOf(p)])) {
                if (!readsField(assign.value, ScopeAnalysis.assignName(assign))) {
                    return true;
                }
            }
        }
        // 2. Forwarding to an `idempotent` mut method!
        if (expr instanceof BabbleParser.CallContext call) {
            EffectSet effs = calleeIntrinsicOrResolvedEffects(call);
            return effs != null && effs.has(Effect.IDEMPOTENT);
        }
        return false;
    }

    private boolean isMutCommutativeBody(BabbleParser.FunctionLiteralContext fn,
                                         BabbleParser.ExpressionContext raw,
                                         List<BabbleParser.FnParamContext> params,
                                         boolean[] mutParams,
                                         boolean mutThis) {
        BabbleParser.ExpressionContext expr = unwrapParens(raw);
        // `this.x += delta` on int (`+` is associative & commutative!) or forwarding to a `mut commutative` method!
        if (expr instanceof BabbleParser.AssignExpressionContext assign && ScopeAnalysis.assignReceiver(assign) != null) {
            BabbleParser.ExpressionContext recv = ScopeAnalysis.assignReceiver(assign);
            String op = assign.op.getText();
            Type targetType = ec.checker.rawTypeOf(assign);
            if ("+=".equals(op) && targetType == Type.INT && params.size() == 1
                    && resolvePlainParam(unwrapParens(assign.value)) == params.get(0)) {
                return true;
            }
            if ("=".equals(op) && targetType == Type.INT && params.size() == 1
                    && assign.value instanceof BabbleParser.BinaryOpContext bin && "+".equals(bin.op.getText())) {
                String assignedName = ScopeAnalysis.assignName(assign);
                if ((sameTargetField(recv, assignedName, bin.left)
                        && resolvePlainParam(unwrapParens(bin.right)) == params.get(0))
                        || (sameTargetField(recv, assignedName, bin.right)
                        && resolvePlainParam(unwrapParens(bin.left)) == params.get(0))) {
                    return true;
                }
            }
        }
        if (expr instanceof BabbleParser.CallContext call) {
            EffectSet effs = calleeIntrinsicOrResolvedEffects(call);
            return effs != null && effs.has(Effect.COMMUTATIVE);
        }
        return false;
    }

    private boolean sameTargetField(BabbleParser.ExpressionContext ns, String field, BabbleParser.ExpressionContext expr) {
        expr = unwrapParens(expr);
        if (!(expr instanceof BabbleParser.SelectorContext sel) || sel.expression() == null || !field.equals(ScopeAnalysis.selectorName(sel))) {
            return false;
        }
        BabbleParser.ExpressionContext r1 = unwrapParens(ns);
        BabbleParser.ExpressionContext r2 = unwrapParens(sel.expression());
        return r1 instanceof BabbleParser.ThisExpressionContext && r2 instanceof BabbleParser.ThisExpressionContext;
    }

    private static boolean readsField(ParseTree tree, String fieldName) {
        if (tree instanceof BabbleParser.SelectorContext sel && sel.expression() != null
                && fieldName.equals(ScopeAnalysis.selectorName(sel))) {
            return true;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (readsField(tree.getChild(i), fieldName)) {
                return true;
            }
        }
        return false;
    }

    private Object resolvePlainParam(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            Object r = ec.checker.resolveNameForEffects(sel.ID().getText(), ec.checker.occurrenceScopeOf(sel));
            if (r instanceof ParserRuleContext prc && ScopeAnalysis.isParamDecl(prc)) {
                return r;
            }
        }
        return null;
    }

    private boolean referencesDecl(ParseTree tree, ParserRuleContext targetDecl) {
        if (tree instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            return ec.checker.resolveNameForEffects(sel.ID().getText(), ec.checker.occurrenceScopeOf(sel)) == targetDecl;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (referencesDecl(tree.getChild(i), targetDecl)) {
                return true;
            }
        }
        return false;
    }

    EffectSet calleeIntrinsicOrResolvedEffects(BabbleParser.CallContext call) {
        Method rm = ec.checker.resolvedJavaMethodOf(call);
        if (rm != null) {
            return ec.javaResolver.resolveJavaMethodProfile(rm.getDeclaringClass(), rm.getName(), rm).effects();
        }
        Constructor<?> rc = ec.checker.resolvedJavaConstructorOf(call);
        if (rc != null) {
            return ec.javaResolver.resolveJavaConstructorProfile(rc.getDeclaringClass(), rc).effects();
        }
        Type callee = ec.checker.rawTypeOf(call.expression());
        if (callee instanceof Type.JavaMethod jm) {
            return ec.javaResolver.resolveJavaMethodProfile(jm.owner(), jm.name(), null).effects();
        }
        if (callee instanceof Type.JavaClassValue jcv) {
            return ec.javaResolver.resolveJavaConstructorProfile(jcv.type(), null).effects();
        }
        if (callee instanceof Type.ClassValue cv) {
            EffectChecker.CallableSummary cs = ec.constructorSummary(cv.node());
            if (cs != null) {
                boolean isVal = cv.node().kind == ClassShape.Kind.VALUE_CLASS;
                EnumSet<Effect> set = EffectSet.copySet(cs.effects().effects());
                if (isVal && set.contains(Effect.PURE) && set.contains(Effect.DETERMINISTIC) && !cv.node().parameters.isEmpty()) {
                    set.add(Effect.INJECTIVE);
                }
                return EffectSet.ofInferred(set, false, false);
            }
        }
        if (callee instanceof Type.Function fn) {
            BabbleParser.FunctionLiteralContext lit = ec.checker.literalOf(fn);
            if (lit != null && ec.summaries.containsKey(lit)) {
                return ec.summaries.get(lit).effects();
            }
            return fn.effects();
        }
        return null;
    }

}
