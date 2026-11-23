package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.effects.Effect;
import org.babblelang.ir.egraph.EGraph;
import org.babblelang.ir.egraph.ENode;
import org.babblelang.parser.BabbleParser;
import org.babblelang.parser.StringLiterals;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.Operators;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thin ANTLR AST extraction adapters feeding the ANTLR-free {@link AlgebraicVerifier}
 * (and {@code SizeChangeTermination}) cores ({@code BIP027-P6}).
 */
final class EffectAstExtractors {
    private static final int MAX_TERM_NODES = 64;

    private final EffectChecker ec;

    EffectAstExtractors(EffectChecker ec) {
        this.ec = ec;
    }

    // ---------------------------------------------------------------- algebraic signature & inference entry points

    void validateAlgebraicSignature(Set<Effect> effects, List<Type.Parameter> params, Type result,
                                    boolean mutThis, boolean axiomatic, ParserRuleContext at) {
        List<String> errors = AlgebraicVerifier.validateAlgebraicSignature(
                effects, params, result, mutThis, axiomatic, this::sameDomainType);
        for (String err : errors) {
            ec.checker.effectDiagnostic(err, at);
        }
    }

    void inferAlgebraicProperties(BabbleParser.FunctionLiteralContext fn,
                                  EnumSet<Effect> effs,
                                  boolean[] mutParams,
                                  boolean mutThis) {
        Type fnRaw = ec.checker.rawTypeOf(fn);
        if (!(fnRaw instanceof Type.Function tf)) {
            return;
        }
        List<BabbleParser.FnParamContext> paramDecls = fn.fnParameters().fnParam();
        List<Type.Parameter> params = tf.parameters();
        Type result = tf.result();
        boolean anyMut = mutThis || EffectChecker.anyTrue(mutParams);
        if (!anyMut && effs.contains(Effect.PURE) && effs.contains(Effect.DETERMINISTIC)) {
            AlgebraicVerifier.Term body = extractPureBlock(fn.functionBlock, paramDecls, null, -1, null);
            AlgebraicVerifier.verifyPureProperties(body, params, result, this::sameDomainType, effs);
        } else if (anyMut && effs.contains(Effect.HERMETIC)) {
            AlgebraicVerifier.MutBody mutBody = extractMutBody(fn.functionBlock, paramDecls, params, mutParams, mutThis);
            AlgebraicVerifier.verifyMutProperties(mutBody, effs);
        }
    }

    private boolean sameDomainType(Type a, Type b) {
        if (a == Type.ERROR || b == Type.ERROR) {
            return true;
        }
        return a.equals(b) || (ec.checker.assignable(a, b) && ec.checker.assignable(b, a));
    }

    // ---------------------------------------------------------------- pure expression term extraction

    private record StateField(ParserRuleContext targetParam, String fieldName, int stateParamIndex, Type fieldType) {
        boolean matchesReceiver(BabbleParser.ExpressionContext recv, EffectAstExtractors self) {
            BabbleParser.ExpressionContext u = EffectBodyWalker.unwrapParens(recv);
            if (targetParam == null) {
                return u instanceof BabbleParser.ThisExpressionContext;
            }
            return self.resolveParam(u) == targetParam;
        }
    }

    private AlgebraicVerifier.Term extractPureBlock(BabbleParser.BlockContext block,
                                                    List<BabbleParser.FnParamContext> paramDecls,
                                                    StateField stateField,
                                                    int budgetOverride,
                                                    Map<ParserRuleContext, AlgebraicVerifier.Term> seedLocals) {
        if (block == null || block.expression().isEmpty()) {
            return null;
        }
        Map<ParserRuleContext, Integer> paramIndex = new IdentityHashMap<>();
        for (int i = 0; i < paramDecls.size(); i++) {
            paramIndex.put(paramDecls.get(i), i);
        }
        Map<ParserRuleContext, AlgebraicVerifier.Term> locals = seedLocals != null
                ? new IdentityHashMap<>(seedLocals) : new IdentityHashMap<>();
        int[] budget = {budgetOverride > 0 ? budgetOverride : MAX_TERM_NODES};
        return extractStatements(block.expression(), 0, paramIndex, locals, stateField, budget);
    }

    private AlgebraicVerifier.Term extractStatements(List<BabbleParser.ExpressionContext> stmts,
                                                     int startIdx,
                                                     Map<ParserRuleContext, Integer> paramIndex,
                                                     Map<ParserRuleContext, AlgebraicVerifier.Term> locals,
                                                     StateField stateField,
                                                     int[] budget) {
        if (startIdx >= stmts.size()) {
            return null;
        }
        for (int i = startIdx; i < stmts.size(); i++) {
            BabbleParser.ExpressionContext stmt = EffectBodyWalker.unwrapParens(stmts.get(i));
            boolean isLast = (i == stmts.size() - 1);
            if (stmt instanceof BabbleParser.ReturnExpressionContext ret) {
                if (ret.expression() == null) {
                    return new AlgebraicVerifier.Const(null, Type.NULL, PrimitiveKind.NONE);
                }
                return extractExpr(ret.expression(), paramIndex, locals, stateField, budget);
            }
            if (stmt instanceof BabbleParser.DefExpressionContext def) {
                if (isLast || def.value == null) {
                    return null;
                }
                AlgebraicVerifier.Term val = extractExpr(def.value, paramIndex, locals, stateField, budget);
                if (val == null) {
                    return null;
                }
                locals.put(def, val);
                continue;
            }
            if (stmt instanceof BabbleParser.AssignExpressionContext assign && ScopeAnalysis.assignReceiver(assign) == null) {
                String name = ScopeAnalysis.assignName(assign);
                Object resolved = ec.checker.resolveNameForEffects(name, ec.checker.occurrenceScopeOf(assign));
                if (!(resolved instanceof ParserRuleContext decl)
                        || (!locals.containsKey(decl) && !paramIndex.containsKey(decl))) {
                    return null;
                }
                AlgebraicVerifier.Term rhs = extractExpr(assign.value, paramIndex, locals, stateField, budget);
                if (rhs == null) {
                    return null;
                }
                String compoundOp = Operators.compound(assign.op.getText());
                AlgebraicVerifier.Term nextVal = rhs;
                if (compoundOp != null) {
                    AlgebraicVerifier.Term cur = locals.containsKey(decl)
                            ? locals.get(decl)
                            : paramTerm(paramIndex.get(decl), ec.checker.typeOfDeclarationForEffects(decl));
                    Type resType = ec.checker.rawTypeOf(assign);
                    nextVal = buildBinaryTerm(compoundOp, cur, rhs, resType, budget);
                    if (nextVal == null) {
                        return null;
                    }
                }
                locals.put(decl, nextVal);
                if (isLast) {
                    return nextVal;
                }
                continue;
            }
            if (!isLast && stmt instanceof BabbleParser.IfExpressionContext ie) {
                AlgebraicVerifier.Term early = tryExtractEarlyReturnIf(ie.ifExpr(), stmts, i + 1, paramIndex, locals, stateField, budget);
                if (early != null) {
                    return early;
                }
                return null;
            }
            if (isLast) {
                return extractExpr(stmt, paramIndex, locals, stateField, budget);
            }
            return null;
        }
        return null;
    }

    private AlgebraicVerifier.Term tryExtractEarlyReturnIf(BabbleParser.IfExprContext ifCtx,
                                                           List<BabbleParser.ExpressionContext> outerStmts,
                                                           int nextIdx,
                                                           Map<ParserRuleContext, Integer> paramIndex,
                                                           Map<ParserRuleContext, AlgebraicVerifier.Term> locals,
                                                           StateField stateField,
                                                           int[] budget) {
        if (ifCtx.elseBlock != null || ifCtx.elseIf != null) {
            return null;
        }
        if (ifCtx.thenBlock == null || ifCtx.thenBlock.expression().isEmpty()) {
            return null;
        }
        List<BabbleParser.ExpressionContext> thenStmts = ifCtx.thenBlock.expression();
        BabbleParser.ExpressionContext lastThen = EffectBodyWalker.unwrapParens(thenStmts.get(thenStmts.size() - 1));
        if (!(lastThen instanceof BabbleParser.ReturnExpressionContext)) {
            return null;
        }
        AlgebraicVerifier.Term cond = extractExpr(ifCtx.test, paramIndex, locals, stateField, budget);
        if (cond == null) {
            return null;
        }
        Map<ParserRuleContext, AlgebraicVerifier.Term> thenLocals = new IdentityHashMap<>(locals);
        AlgebraicVerifier.Term thenTerm = extractStatements(thenStmts, 0, paramIndex, thenLocals, stateField, budget);
        if (thenTerm == null) {
            return null;
        }
        Map<ParserRuleContext, AlgebraicVerifier.Term> elseLocals = new IdentityHashMap<>(locals);
        AlgebraicVerifier.Term elseTerm = extractStatements(outerStmts, nextIdx, paramIndex, elseLocals, stateField, budget);
        if (elseTerm == null) {
            return null;
        }
        if (!consumeNode(budget)) {
            return null;
        }
        Type resType = thenTerm.type();
        return new AlgebraicVerifier.Ite(cond, thenTerm, elseTerm, resType, AlgebraicVerifier.primitiveKindOf(resType));
    }

    private AlgebraicVerifier.Term extractExpr(BabbleParser.ExpressionContext raw,
                                               Map<ParserRuleContext, Integer> paramIndex,
                                               Map<ParserRuleContext, AlgebraicVerifier.Term> locals,
                                               StateField stateField,
                                               int[] budget) {
        if (raw == null || !consumeNode(budget)) {
            return null;
        }
        BabbleParser.ExpressionContext expr = EffectBodyWalker.unwrapParens(raw);
        Type type = ec.checker.rawTypeOf(expr);
        PrimitiveKind kind = AlgebraicVerifier.primitiveKindOf(type);

        if (expr instanceof BabbleParser.BlockExpressionContext be) {
            Map<ParserRuleContext, AlgebraicVerifier.Term> nested = new IdentityHashMap<>(locals);
            return extractStatements(be.block().expression(), 0, paramIndex, nested, stateField, budget);
        }
        if (expr instanceof BabbleParser.ReturnExpressionContext ret) {
            return ret.expression() != null
                    ? extractExpr(ret.expression(), paramIndex, locals, stateField, budget)
                    : new AlgebraicVerifier.Const(null, Type.NULL, PrimitiveKind.NONE);
        }
        if (expr instanceof BabbleParser.NullContext) {
            return new AlgebraicVerifier.Const(null, Type.NULL, PrimitiveKind.NONE);
        }
        if (expr instanceof BabbleParser.BooleanContext bc) {
            return new AlgebraicVerifier.Const("true".equals(bc.getText()), Type.BOOL, PrimitiveKind.BOOL);
        }
        if (expr instanceof BabbleParser.IntegerContext ic) {
            try {
                int v = Integer.parseInt(ic.getText());
                if (kind == PrimitiveKind.LONG) {
                    return new AlgebraicVerifier.Const((long) v, Type.LONG, PrimitiveKind.LONG);
                }
                if (kind == PrimitiveKind.DOUBLE) {
                    return new AlgebraicVerifier.Const((double) v, Type.DOUBLE, PrimitiveKind.DOUBLE);
                }
                if (kind == PrimitiveKind.FLOAT) {
                    return new AlgebraicVerifier.Const((float) v, Type.FLOAT, PrimitiveKind.FLOAT);
                }
                return new AlgebraicVerifier.Const(v, type, kind == PrimitiveKind.NONE ? PrimitiveKind.INT : kind);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (expr instanceof BabbleParser.LongLiteralContext lc) {
            try {
                String txt = lc.getText();
                long v = Long.parseLong(txt.substring(0, txt.length() - 1));
                return new AlgebraicVerifier.Const(v, Type.LONG, PrimitiveKind.LONG);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (expr instanceof BabbleParser.FloatLiteralContext fc) {
            try {
                String txt = fc.getText();
                float v = Float.parseFloat(txt.substring(0, txt.length() - 1));
                return new AlgebraicVerifier.Const(v, Type.FLOAT, PrimitiveKind.FLOAT);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (expr instanceof BabbleParser.DoubleContext dc) {
            try {
                double v = Double.parseDouble(dc.getText());
                return new AlgebraicVerifier.Const(v, Type.DOUBLE, PrimitiveKind.DOUBLE);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (expr instanceof BabbleParser.CharLiteralContext cc) {
            try {
                return new AlgebraicVerifier.Const(StringLiterals.decodeChar(cc.getText()), Type.CHAR, PrimitiveKind.CHAR);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (expr instanceof BabbleParser.StringContext sc) {
            try {
                return new AlgebraicVerifier.Const(StringLiterals.decode(sc), Type.STRING, PrimitiveKind.NONE);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (expr instanceof BabbleParser.SelectorContext sel) {
            if (sel.expression() == null) {
                Object resolved = ec.checker.resolveNameForEffects(sel.ID().getText(), ec.checker.occurrenceScopeOf(sel));
                if (resolved instanceof ParserRuleContext decl) {
                    if (locals.containsKey(decl)) {
                        return locals.get(decl);
                    }
                    Integer idx = paramIndex.get(decl);
                    if (idx != null) {
                        return paramTerm(idx, type);
                    }
                    if (!ec.checker.isReassigned(decl) && ec.javaResolver.isImmutableConstantValue(type)) {
                        return new AlgebraicVerifier.Opaque("ext@" + System.identityHashCode(decl), type, kind);
                    }
                }
                return null;
            }
            if (stateField != null
                    && stateField.fieldName().equals(ScopeAnalysis.selectorName(sel))
                    && stateField.matchesReceiver(sel.expression(), this)) {
                return paramTerm(stateField.stateParamIndex(), stateField.fieldType());
            }
            return null;
        }
        if (expr instanceof BabbleParser.NegateContext neg) {
            AlgebraicVerifier.Term sub = extractExpr(neg.operand, paramIndex, locals, stateField, budget);
            if (sub == null) {
                return null;
            }
            return new AlgebraicVerifier.Unary(ENode.Op.NEG, widenIfNeeded(sub, type, kind, budget), type, kind);
        }
        if (expr instanceof BabbleParser.BooleanNotContext notCtx) {
            AlgebraicVerifier.Term sub = extractExpr(notCtx.expression(), paramIndex, locals, stateField, budget);
            return sub != null ? new AlgebraicVerifier.Unary(ENode.Op.NOT, sub, Type.BOOL, PrimitiveKind.BOOL) : null;
        }
        if (expr instanceof BabbleParser.BooleanOpContext boolOp) {
            AlgebraicVerifier.Term l = extractExpr(boolOp.left, paramIndex, locals, stateField, budget);
            AlgebraicVerifier.Term r = extractExpr(boolOp.right, paramIndex, locals, stateField, budget);
            if (l == null || r == null) {
                return null;
            }
            ENode.Op op = "and".equals(boolOp.op.getText()) ? ENode.Op.AND : ENode.Op.OR;
            return new AlgebraicVerifier.Binary(op, l, r, Type.BOOL, PrimitiveKind.BOOL);
        }
        if (expr instanceof BabbleParser.BinaryOpContext bin) {
            AlgebraicVerifier.Term l = extractExpr(bin.left, paramIndex, locals, stateField, budget);
            AlgebraicVerifier.Term r = extractExpr(bin.right, paramIndex, locals, stateField, budget);
            if (l == null || r == null) {
                return null;
            }
            return buildBinaryTerm(bin.op.getText(), l, r, type, budget);
        }
        if (expr instanceof BabbleParser.AsExpressionContext asExpr) {
            AlgebraicVerifier.Term sub = extractExpr(asExpr.left, paramIndex, locals, stateField, budget);
            if (sub == null) {
                return null;
            }
            return new AlgebraicVerifier.Cast(sub, sub.type(), type, kind);
        }
        if (expr instanceof BabbleParser.IfExpressionContext ifWrap) {
            return extractIf(ifWrap.ifExpr(), paramIndex, locals, stateField, budget);
        }
        if (expr instanceof BabbleParser.CallContext call) {
            return extractCall(call, paramIndex, locals, stateField, budget);
        }
        return null;
    }

    private AlgebraicVerifier.Term extractIf(BabbleParser.IfExprContext ifCtx,
                                             Map<ParserRuleContext, Integer> paramIndex,
                                             Map<ParserRuleContext, AlgebraicVerifier.Term> locals,
                                             StateField stateField,
                                             int[] budget) {
        AlgebraicVerifier.Term cond = extractExpr(ifCtx.test, paramIndex, locals, stateField, budget);
        if (cond == null || ifCtx.thenBlock == null) {
            return null;
        }
        Map<ParserRuleContext, AlgebraicVerifier.Term> thenLocals = new IdentityHashMap<>(locals);
        AlgebraicVerifier.Term thenTerm = extractStatements(ifCtx.thenBlock.expression(), 0, paramIndex, thenLocals, stateField, budget);
        if (thenTerm == null) {
            return null;
        }
        AlgebraicVerifier.Term elseTerm;
        if (ifCtx.elseIf != null) {
            elseTerm = extractIf(ifCtx.elseIf, paramIndex, locals, stateField, budget);
        } else if (ifCtx.elseBlock != null) {
            Map<ParserRuleContext, AlgebraicVerifier.Term> elseLocals = new IdentityHashMap<>(locals);
            elseTerm = extractStatements(ifCtx.elseBlock.expression(), 0, paramIndex, elseLocals, stateField, budget);
        } else {
            elseTerm = new AlgebraicVerifier.Const(null, Type.NULL, PrimitiveKind.NONE);
        }
        if (elseTerm == null) {
            return null;
        }
        Type resType = ec.checker.rawTypeOf(ifCtx);
        if (resType == null || resType == Type.ERROR) {
            resType = thenTerm.type();
        }
        PrimitiveKind kind = AlgebraicVerifier.primitiveKindOf(resType);
        return new AlgebraicVerifier.Ite(cond,
                widenIfNeeded(thenTerm, resType, kind, budget),
                widenIfNeeded(elseTerm, resType, kind, budget),
                resType, kind);
    }

    private AlgebraicVerifier.Term extractCall(BabbleParser.CallContext call,
                                               Map<ParserRuleContext, Integer> paramIndex,
                                               Map<ParserRuleContext, AlgebraicVerifier.Term> locals,
                                               StateField stateField,
                                               int[] budget) {
        List<BabbleParser.CallParameterContext> argParams = call.callParameters().callParameter();
        for (BabbleParser.CallParameterContext ap : argParams) {
            if (ScopeAnalysis.callParamName(ap) != null || ap.spread != null) {
                return null;
            }
        }
        Type resType = ec.checker.rawTypeOf(call);
        PrimitiveKind resKind = AlgebraicVerifier.primitiveKindOf(resType);

        // Core scalar conversions `double(x)`, `int(x)`, etc.
        if (call.expression() instanceof BabbleParser.CoreScalarRefContext && argParams.size() == 1) {
            AlgebraicVerifier.Term arg = extractExpr(argParams.get(0).expression(), paramIndex, locals, stateField, budget);
            return arg != null ? new AlgebraicVerifier.Cast(arg, arg.type(), resType, resKind) : null;
        }

        // Babble class / record constructor
        Type calleeType = ec.checker.rawTypeOf(call.expression());
        if (calleeType instanceof Type.ClassValue cv && !cv.node().isInterface()) {
            List<AlgebraicVerifier.Term> args = extractArgTerms(argParams, paramIndex, locals, stateField, budget);
            if (args == null) {
                return null;
            }
            EffectChecker.CallableSummary cs = ec.constructorSummary(cv.node());
            boolean isVal = cv.node().kind == ClassShape.Kind.VALUE_CLASS;
            boolean inj = isVal && cs != null && cs.effects().isPure() && cs.effects().isDeterministic()
                    && !cv.node().parameters.isEmpty();
            return new AlgebraicVerifier.Ctor("babble:ctor@" + System.identityHashCode(cv.node()), args, inj, resType);
        }

        EffectSet fx = calleeIntrinsicOrResolvedEffects(call);
        if (fx == null || !fx.isPure() || !fx.isDeterministic()) {
            return null;
        }
        String symbol = callSymbol(call, calleeType);
        if (symbol == null) {
            return null;
        }
        List<AlgebraicVerifier.Term> allArgs = new ArrayList<>(argParams.size() + 1);
        BabbleParser.ExpressionContext unwrappedCallee = call.expression() instanceof BabbleParser.TypeApplyContext ta
                ? ta.expression() : call.expression();
        if (unwrappedCallee instanceof BabbleParser.SelectorContext sel && sel.expression() != null) {
            Type recvType = ec.checker.rawTypeOf(sel.expression());
            if (!(recvType instanceof Type.JavaClassValue || recvType instanceof Type.ClassValue
                    || recvType instanceof Type.Module || recvType instanceof Type.JavaPackage)) {
                AlgebraicVerifier.Term recvTerm = extractExpr(sel.expression(), paramIndex, locals, stateField, budget);
                if (recvTerm == null) {
                    return null;
                }
                allArgs.add(recvTerm);
            }
        }
        List<AlgebraicVerifier.Term> explicitArgs = extractArgTerms(argParams, paramIndex, locals, stateField, budget);
        if (explicitArgs == null) {
            return null;
        }
        allArgs.addAll(explicitArgs);
        EGraph.CallProps props = new EGraph.CallProps(
                fx.has(Effect.IDEMPOTENT),
                fx.has(Effect.ASSOCIATIVE),
                fx.has(Effect.COMMUTATIVE),
                false);
        return new AlgebraicVerifier.Call(symbol, List.copyOf(allArgs), fx, props, resType, resKind);
    }

    private List<AlgebraicVerifier.Term> extractArgTerms(List<BabbleParser.CallParameterContext> argParams,
                                                         Map<ParserRuleContext, Integer> paramIndex,
                                                         Map<ParserRuleContext, AlgebraicVerifier.Term> locals,
                                                         StateField stateField,
                                                         int[] budget) {
        List<AlgebraicVerifier.Term> out = new ArrayList<>(argParams.size());
        for (BabbleParser.CallParameterContext ap : argParams) {
            AlgebraicVerifier.Term t = extractExpr(ap.expression(), paramIndex, locals, stateField, budget);
            if (t == null) {
                return null;
            }
            out.add(t);
        }
        return out;
    }

    private AlgebraicVerifier.Term buildBinaryTerm(String op, AlgebraicVerifier.Term l, AlgebraicVerifier.Term r,
                                                   Type resType, int[] budget) {
        PrimitiveKind resKind = AlgebraicVerifier.primitiveKindOf(resType);
        return switch (op) {
            case "+" -> {
                if (resType == Type.STRING) {
                    yield new AlgebraicVerifier.Binary(ENode.Op.STR_ADD, l, r, Type.STRING, PrimitiveKind.NONE);
                }
                if (!Type.isNumericScalar(resType)) {
                    yield null;
                }
                yield new AlgebraicVerifier.Binary(ENode.Op.ADD,
                        widenIfNeeded(l, resType, resKind, budget),
                        widenIfNeeded(r, resType, resKind, budget),
                        resType, resKind);
            }
            case "-" -> Type.isNumericScalar(resType)
                    ? new AlgebraicVerifier.Binary(ENode.Op.SUB,
                    widenIfNeeded(l, resType, resKind, budget),
                    widenIfNeeded(r, resType, resKind, budget),
                    resType, resKind)
                    : null;
            case "*" -> Type.isNumericScalar(resType)
                    ? new AlgebraicVerifier.Binary(ENode.Op.MUL,
                    widenIfNeeded(l, resType, resKind, budget),
                    widenIfNeeded(r, resType, resKind, budget),
                    resType, resKind)
                    : null;
            case "/" -> Type.isNumericScalar(resType)
                    ? new AlgebraicVerifier.Binary(ENode.Op.DIV,
                    widenIfNeeded(l, resType, resKind, budget),
                    widenIfNeeded(r, resType, resKind, budget),
                    resType, resKind)
                    : null;
            case "==" -> new AlgebraicVerifier.Binary(ENode.Op.EQ, l, r, Type.BOOL, PrimitiveKind.BOOL);
            case "!=", "<>" -> new AlgebraicVerifier.Binary(ENode.Op.NEQ, l, r, Type.BOOL, PrimitiveKind.BOOL);
            case "<" -> new AlgebraicVerifier.Binary(ENode.Op.LT, l, r, Type.BOOL, PrimitiveKind.BOOL);
            case "<=" -> new AlgebraicVerifier.Binary(ENode.Op.LTE, l, r, Type.BOOL, PrimitiveKind.BOOL);
            case ">" -> new AlgebraicVerifier.Binary(ENode.Op.GT, l, r, Type.BOOL, PrimitiveKind.BOOL);
            case ">=" -> new AlgebraicVerifier.Binary(ENode.Op.GTE, l, r, Type.BOOL, PrimitiveKind.BOOL);
            default -> null;
        };
    }

    private static AlgebraicVerifier.Term widenIfNeeded(AlgebraicVerifier.Term term, Type targetType,
                                                        PrimitiveKind targetKind, int[] budget) {
        if (term.type().equals(targetType) || targetKind == PrimitiveKind.NONE || !Type.isNumericScalar(term.type())) {
            return term;
        }
        if (term instanceof AlgebraicVerifier.Const c && c.value() instanceof Number n) {
            Object converted = switch (targetKind) {
                case LONG -> n.longValue();
                case FLOAT -> n.floatValue();
                case DOUBLE -> n.doubleValue();
                case INT -> n.intValue();
                default -> null;
            };
            if (converted != null) {
                return new AlgebraicVerifier.Const(converted, targetType, targetKind);
            }
        }
        if (!consumeNode(budget)) {
            return term;
        }
        return new AlgebraicVerifier.Cast(term, term.type(), targetType, targetKind);
    }

    private static AlgebraicVerifier.Term paramTerm(int idx, Type type) {
        return new AlgebraicVerifier.Param(idx, type, AlgebraicVerifier.primitiveKindOf(type));
    }

    private static boolean consumeNode(int[] budget) {
        return --budget[0] >= 0;
    }

    // ---------------------------------------------------------------- mutating body extraction

    private AlgebraicVerifier.MutBody extractMutBody(BabbleParser.BlockContext block,
                                                     List<BabbleParser.FnParamContext> paramDecls,
                                                     List<Type.Parameter> params,
                                                     boolean[] mutParams,
                                                     boolean mutThis) {
        if (block == null || block.expression().isEmpty()) {
            return null;
        }
        Map<ParserRuleContext, Integer> paramIndex = new IdentityHashMap<>();
        List<PrimitiveKind> paramKinds = new ArrayList<>(params.size());
        for (int i = 0; i < paramDecls.size(); i++) {
            paramIndex.put(paramDecls.get(i), i);
            paramKinds.add(i < params.size() ? AlgebraicVerifier.primitiveKindOf(params.get(i).type()) : PrimitiveKind.NONE);
        }
        Map<ParserRuleContext, AlgebraicVerifier.Term> locals = new IdentityHashMap<>();
        int[] budget = {MAX_TERM_NODES};

        // Allow leading pure local `def`s followed by one mutating call or one target-field assignment.
        List<BabbleParser.ExpressionContext> stmts = block.expression();
        int n = stmts.size();
        for (int i = 0; i < n - 1; i++) {
            BabbleParser.ExpressionContext s = EffectBodyWalker.unwrapParens(stmts.get(i));
            if (s instanceof BabbleParser.DefExpressionContext def && def.value != null) {
                AlgebraicVerifier.Term val = extractExpr(def.value, paramIndex, locals, null, budget);
                if (val == null) {
                    return null;
                }
                locals.put(def, val);
                continue;
            }
            return null;
        }

        BabbleParser.ExpressionContext last = EffectBodyWalker.unwrapParens(stmts.get(n - 1));
        if (last instanceof BabbleParser.ReturnExpressionContext ret && ret.expression() != null) {
            last = EffectBodyWalker.unwrapParens(ret.expression());
        }
        // 1. Forwarding to an `idempotent` / `commutative` mut method
        if (last instanceof BabbleParser.CallContext call) {
            return new AlgebraicVerifier.ForwardMutCall(calleeIntrinsicOrResolvedEffects(call));
        }
        // 2. Single-field state transition `this.field <op>= rhs` or `mutParam.field <op>= rhs`
        if (last instanceof BabbleParser.AssignExpressionContext assign && ScopeAnalysis.assignReceiver(assign) != null) {
            BabbleParser.ExpressionContext recv = EffectBodyWalker.unwrapParens(ScopeAnalysis.assignReceiver(assign));
            int targetIdx = -1;
            ParserRuleContext targetParam = null;
            if (mutThis && recv instanceof BabbleParser.ThisExpressionContext) {
                targetIdx = -1;
            } else if (resolveParam(recv) instanceof BabbleParser.FnParamContext p) {
                int idx = paramDecls.indexOf(p);
                if (idx < 0 || idx >= mutParams.length || !mutParams[idx]) {
                    return null;
                }
                targetIdx = idx;
                targetParam = p;
            } else {
                return null;
            }
            String fieldName = ScopeAnalysis.assignName(assign);
            Type fieldType = ec.checker.rawTypeOf(assign);
            PrimitiveKind fieldKind = AlgebraicVerifier.primitiveKindOf(fieldType);
            int stateIdx = paramDecls.size();
            StateField sf = new StateField(targetParam, fieldName, stateIdx, fieldType);

            AlgebraicVerifier.Term rhs = extractExpr(assign.value, paramIndex, locals, sf, budget);
            if (rhs == null) {
                return null;
            }
            String op = assign.op.getText();
            AlgebraicVerifier.Term nextState;
            if ("=".equals(op)) {
                nextState = rhs;
            } else {
                String binOp = Operators.compound(op);
                if (binOp == null) {
                    return null;
                }
                AlgebraicVerifier.Term curState = paramTerm(stateIdx, fieldType);
                nextState = buildBinaryTerm(binOp, curState, rhs, fieldType, budget);
                if (nextState == null) {
                    return null;
                }
            }
            return new AlgebraicVerifier.FieldTransition(stateIdx, targetIdx, paramDecls.size(),
                    List.copyOf(paramKinds), fieldKind, nextState);
        }
        return null;
    }

    // ---------------------------------------------------------------- callee resolution helpers

    private Object resolveParam(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            Object r = ec.checker.resolveNameForEffects(sel.ID().getText(), ec.checker.occurrenceScopeOf(sel));
            if (r instanceof ParserRuleContext prc && ScopeAnalysis.isParamDecl(prc)) {
                return r;
            }
        }
        return null;
    }

    private String callSymbol(BabbleParser.CallContext call, Type calleeType) {
        Method rm = ec.checker.resolvedJavaMethodOf(call);
        if (rm != null) {
            return "java:" + rm.getDeclaringClass().getName() + "." + rm.getName() + ":" + rm.getParameterCount();
        }
        if (calleeType instanceof Type.JavaMethod jm) {
            return "java:" + jm.owner().getName() + "." + jm.name() + ":" + call.callParameters().callParameter().size();
        }
        if (calleeType instanceof Type.Function fn) {
            BabbleParser.FunctionLiteralContext lit = ec.checker.literalOf(fn);
            if (lit != null) {
                return "babble:fn@" + System.identityHashCode(lit);
            }
        }
        BabbleParser.ExpressionContext calleeExpr = EffectBodyWalker.unwrapParens(call.expression());
        if (calleeExpr instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            Object resolved = ec.checker.resolveNameForEffects(sel.ID().getText(), ec.checker.occurrenceScopeOf(sel));
            if (resolved instanceof ParserRuleContext decl && !ec.checker.isReassigned(decl)) {
                return "babble:decl@" + System.identityHashCode(decl);
            }
        }
        return null;
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
