package org.babblelang.analysis;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.JavaTypes;

/**
 * Callable type inference, contextual typing, return-type synthesis, subsumption/coercion/SAM
 * adaptation, SAM member field demotion, and inferred-binding recording for {@link TypeChecker}.
 */
final class CallableTyper {
    private final TypeChecker checker;

    CallableTyper(TypeChecker checker) {
        this.checker = checker;
    }

    boolean isExported(BabbleParser.DefExpressionContext def) {
        return def != null && checker.inFile(def) && def.EXPORT() != null;
    }

    private static boolean implementsInterfaceMethod(TypeNode node, String name) {
        return implementsInterfaceMethod(
                node, name, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean implementsInterfaceMethod(
            TypeNode node, String name, Set<TypeNode> seen) {
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode parentNode) {
                if (seen.add(parentNode)) {
                    if (parentNode.members.containsKey(name)
                            || implementsInterfaceMethod(parentNode, name, seen)) {
                        return true;
                    }
                }
            } else if (parent instanceof Class<?> javaIface) {
                if (JavaTypeBridge.hasJavaMethod(javaIface, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean isInferrableCallable(ParserRuleContext owner) {
        if (owner == null || !checker.inFile(owner) || checker.escapingTargets.contains(owner)) {
            return false;
        }
        if (owner instanceof BabbleParser.ClassExpressionContext cls) {
            TypeNode node = checker.pass.typeNodes.get(cls);
            if (node == null || checker.exportedTypeNodes.contains(node)) {
                return false;
            }
            BabbleParser.DefExpressionContext def = checker.owningDefExpr(cls);
            return def != null && !ScopeAnalysis.isVar(def) && !isExported(def);
        }
        if (owner instanceof BabbleParser.FunctionLiteralContext fn) {
            ScopeNode fnScope = checker.scopeOf(fn);
            if (fnScope.memberOf != null) {
                if (fnScope.memberOf.isInterface()) {
                    return false;
                }
                if (!(fn.getParent() instanceof BabbleParser.DefExpressionContext def
                        && def.value == fn)) {
                    return false;
                }
                String memberName = def.name.getText();
                TypeNode.Member member =
                        fnScope.staticMethod
                                ? fnScope.memberOf.statics.get(memberName)
                                : fnScope.memberOf.members.get(memberName);
                boolean isPrivate = member != null && member.isPrivate();
                if (checker.exportedTypeNodes.contains(fnScope.memberOf) && !isPrivate) {
                    return false;
                }
                if (!fnScope.staticMethod
                        && !isPrivate
                        && implementsInterfaceMethod(fnScope.memberOf, memberName)) {
                    return false;
                }
                return true;
            }
            BabbleParser.DefExpressionContext def = checker.owningDefExpr(fn);
            return def != null && !ScopeAnalysis.isVar(def) && !isExported(def);
        }
        return false;
    }

    List<Type.Parameter> functionParameters(BabbleParser.FunctionLiteralContext literal) {
        ScopeNode scope = checker.scopeOf(literal);
        EffectChecker.CallableSummary summary =
                checker.effectChecker != null
                        ? checker.effectChecker.visibleSummary(literal)
                        : null;
        if (summary == null && !checker.inFile(literal) && checker.pass.session != null) {
            summary = checker.pass.session.callableSummaries.get(literal);
        }
        if (checker.effectChecker != null && checker.checkingEffects && checker.inFile(literal)) {
            checker.effectChecker.parseFunctionPrefix(literal, true);
        }
        boolean untyped =
                checker.effectChecker != null && checker.effectChecker.isCompletelyUntyped(literal);
        List<BabbleParser.FnParamContext> decls = literal.fnParameters().fnParam();
        int kwStart = ScopeAnalysis.keywordOnlyStart(literal.fnParameters());
        List<Type.Parameter> result = new ArrayList<>(decls.size());
        for (int i = 0; i < decls.size(); i++) {
            BabbleParser.FnParamContext parameter = decls.get(i);
            boolean rest = ScopeAnalysis.isParamRest(parameter);
            Type type =
                    rest
                            ? restElementType(parameter, scope)
                            : checker.parameterType(parameter, scope);
            EffectChecker.ParamMods pm =
                    checker.effectChecker != null
                            ? checker.effectChecker.parseParamMods(
                                    parameter, checker.checkingEffects && checker.inFile(literal))
                            : EffectChecker.ParamMods.NONE;
            boolean mut =
                    summary != null && i < summary.mutParams().length
                            ? summary.mutParams()[i]
                            : pm.mut();
            boolean borrow =
                    summary != null && i < summary.borrowParams().length
                            ? summary.borrowParams()[i]
                            : (checker.effectChecker != null
                                    && !untyped
                                    && !pm.shared()
                                    && !pm.unique());
            boolean unique =
                    summary != null && i < summary.uniqueParams().length
                            ? summary.uniqueParams()[i]
                            : pm.unique();
            result.add(
                    new Type.Parameter(
                            parameter.ID().getText(),
                            type,
                            parameter.defaultValue != null,
                            i >= kwStart,
                            rest,
                            mut,
                            borrow,
                            pm.shared(),
                            unique));
        }
        return result;
    }

    Type restElementType(BabbleParser.FnParamContext parameter, ScopeNode scope) {
        Type elem = checker.annotator.annotation(parameter.type(), scope);
        if (elem != Type.ERROR && ScopeAnalysis.isParamRestNullable(parameter)) {
            elem = Type.nullable(elem);
        }
        return elem;
    }

    void typeDefaultValues(BabbleParser.FunctionLiteralContext literal) {
        ScopeNode scope = checker.scopeOf(literal);
        for (BabbleParser.FnParamContext parameter : literal.fnParameters().fnParam()) {
            if (parameter.defaultValue != null) {
                Type expected =
                        parameter.type() != null
                                ? checker.annotator.annotation(parameter.type(), scope)
                                : checker.declarationTypes.get(parameter);
                if (!checker.types.containsKey(parameter.defaultValue)) {
                    Type actual = checker.check(parameter.defaultValue, expected);
                    if (expected != null && expected != Type.ERROR) {
                        subsumes(parameter.defaultValue, null, actual, expected);
                        coerceSam(parameter.defaultValue, actual, expected);
                    }
                } else if (expected != null && expected != Type.ERROR) {
                    Type actual = checker.types.get(parameter.defaultValue);
                    subsumes(parameter.defaultValue, null, actual, expected);
                    coerceSam(parameter.defaultValue, actual, expected);
                }
            }
        }
    }

    void applyContextualType(BabbleParser.FunctionLiteralContext literal, Type expected) {
        if (expected == null
                && literal.getParent() instanceof BabbleParser.DefExpressionContext def
                && def.value == literal
                && def.type() != null) {
            expected = checker.annotator.declaredType(def);
        }
        if (expected == null) {
            return;
        }
        Type base = Type.withoutNull(expected);
        List<Type> expectedParamTypes = null;
        Type expectedResult = null;
        if (base instanceof Type.Function fn) {
            expectedParamTypes = fn.parameters().stream().map(Type.Parameter::type).toList();
            expectedResult = fn.result();
        } else if (JavaTypeBridge.resolveJavaSam(base)
                instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
            Type.Function sam =
                    JavaTypeBridge.samFunctionType(valid.samType(), checker.effectChecker);
            if (sam != null) {
                expectedParamTypes = sam.parameters().stream().map(Type.Parameter::type).toList();
                expectedResult = sam.result();
            }
        }
        if (expectedParamTypes == null) {
            return;
        }
        List<BabbleParser.FnParamContext> params = literal.fnParameters().fnParam();
        int required = 0;
        boolean hasRest = false;
        for (BabbleParser.FnParamContext param : params) {
            if (param.rest != null) {
                hasRest = true;
            } else if (param.defaultValue == null) {
                required++;
            }
        }
        boolean appliedAny = false;
        if (required <= expectedParamTypes.size()
                && (hasRest || params.size() >= expectedParamTypes.size())) {
            int limit = Math.min(params.size(), expectedParamTypes.size());
            for (int i = 0; i < limit; i++) {
                BabbleParser.FnParamContext param = params.get(i);
                if (param.rest != null) {
                    break;
                }
                if (param.type() == null && !checker.declarationTypes.containsKey(param)) {
                    Type paramType = expectedParamTypes.get(i);
                    if (paramType != null
                            && paramType != Type.ERROR
                            && !paramType.hasSyntheticTypeVar()) {
                        checker.declarationTypes.put(param, paramType);
                        appliedAny = true;
                    }
                }
            }
        }
        if (literal.type() == null
                && expectedResult != null
                && expectedResult != Type.NULL
                && !TypeChecker.isUnresolved(expectedResult)
                && !expectedResult.hasSyntheticTypeVar()) {
            checker.contextualReturnTypes.putIfAbsent(literal, expectedResult);
            appliedAny = true;
        }
        if (appliedAny
                && base instanceof Type.Function fn
                && !fn.typeParameters().isEmpty()
                && literal.typeParameters() == null
                && checker.annotator.typeVarsOf(checker.owningDef(literal)).isEmpty()) {
            checker.contextualTypeParams.putIfAbsent(literal, fn.typeParameters());
        }
    }

    Type expectedReturnType(BabbleParser.FunctionLiteralContext function) {
        if (function.type() != null) {
            return checker.annotator.annotation(function.type(), checker.scopeOf(function));
        }
        return checker.contextualReturnTypes.get(function);
    }

    /**
     * The inferred result of an unannotated function. A self-recursive function reads its own
     * result from its result cell, NOTHING at first : while the checker.slots converge, each
     * evaluation records the result it finds, waking the unit until the result is stable, and the
     * final walk checker.types the body once against the converged result. A function whose result
     * comes only from its recursive calls has none.
     */
    Type inferFunctionResult(BabbleParser.FunctionLiteralContext literal) {
        if (literal.functionBlock == null) {
            return Type.ERROR;
        }
        Type converged = checker.slots.readResult(literal);
        boolean provisional = !checker.slots.converging() && converged != null;
        if (provisional) {
            checker.provisionalResults.put(literal, converged);
        }
        checker.inferringResult.add(literal);
        checker.inferenceStack.push(literal);
        TypeEnv savedEnv = checker.env;
        checker.env = savedEnv.enterCallable();
        Type result;
        try {
            result = computeBodyResult(literal, checker.contextualReturnTypes.get(literal));
        } finally {
            checker.env = savedEnv;
            checker.inferenceStack.pop();
            checker.inferringResult.remove(literal);
            if (provisional) {
                checker.provisionalResults.remove(literal);
            }
        }
        Type candidate = checker.inferred(result);
        if (checker.recursiveFunctions.remove(literal)) {
            if (candidate != Type.ERROR) {
                checker.slots.recordResult(literal, candidate);
            } else if (result == Type.NOTHING) {
                BabbleParser.DefExpressionContext def = checker.owningDef(literal);
                if (def != null) {
                    checker.warn("Missing type : " + def.name.getText() + " (recursive)", def);
                    checker.failedDefs.add(def);
                } else {
                    checker.warn("Missing type : function (recursive)", literal);
                }
            }
        }
        return candidate;
    }

    private Type computeBodyResult(
            BabbleParser.FunctionLiteralContext literal, Type expectedReturn) {
        Type bodyType = checker.check(literal.functionBlock, expectedReturn);
        Type result = bodyType;
        List<BabbleParser.ReturnExpressionContext> returns =
                checker.functionReturns.getOrDefault(literal, List.of());
        List<Type> returnTypes = new ArrayList<>(returns.size());
        for (BabbleParser.ReturnExpressionContext ret : returns) {
            Type retType =
                    ret.expression() != null
                            ? checker.check(ret.expression(), expectedReturn)
                            : Type.NULL;
            returnTypes.add(retType);
            Type next = checker.algebra.join(result, retType);
            if (next == null) {
                if (!TypeChecker.isUnresolved(result) && !TypeChecker.isUnresolved(retType)) {
                    checker.warn(
                            "Type error : incompatible return checker.types : "
                                    + result
                                    + " and "
                                    + retType,
                            ret);
                }
                next = Type.ERROR;
            }
            result = next;
        }
        if (literal.type() == null) {
            Type target =
                    expectedReturn != null && expectedReturn != Type.ERROR
                            ? expectedReturn
                            : result;
            coerce(literal.functionBlock, bodyType, target);
            for (int i = 0; i < returns.size(); i++) {
                BabbleParser.ReturnExpressionContext ret = returns.get(i);
                if (ret.expression() != null) {
                    coerce(ret, returnTypes.get(i), target);
                }
            }
            if (expectedReturn != null && expectedReturn != Type.ERROR) {
                int targetRank = TypeAlgebra.numericWideningTargetRank(result, expectedReturn);
                if (targetRank >= 0) {
                    result = widenNumericType(result, targetRank);
                }
            }
        }
        return result;
    }

    private static Type widenNumericType(Type type, int targetRank) {
        Type target = Type.numericOfRank(targetRank);
        if (Type.isNumericScalar(type) && Type.numericRank(type) < targetRank) {
            return target;
        }
        if (type instanceof Type.Union union) {
            List<Type> updated = new ArrayList<>(union.members().size());
            for (Type member : union.members()) {
                updated.add(
                        Type.isNumericScalar(member) && Type.numericRank(member) < targetRank
                                ? target
                                : member);
            }
            return Type.union(updated);
        }
        return type;
    }

    void clearSubtreeTypes(ParseTree tree) {
        if (tree instanceof ParserRuleContext ctx) {
            checker.types.remove(ctx);
            checker.contextualReturnTypes.remove(ctx);
            checker.contextualTypeParams.remove(ctx);
            checker.widened.remove(ctx);
            checker.samTargets.remove(ctx);
            if (ctx instanceof BabbleParser.ForExpressionContext forCtx) {
                checker.forElemSamTargets.remove(forCtx);
            }
            if (ctx instanceof BabbleParser.AsExpressionContext asCtx) {
                checker.asSamTargets.remove(asCtx);
            }
            checker.resolvedLiterals.remove(ctx);
            if (ctx instanceof BabbleParser.CatchClauseContext cc) {
                checker.catchTargetTypes.remove(cc);
                checker.declarationTypes.remove(cc);
            }
            if (ctx instanceof BabbleParser.BindingPatternContext
                    || ctx instanceof BabbleParser.RestPatternContext
                    || ctx instanceof BabbleParser.ElseClauseContext) {
                checker.declarationTypes.remove(ctx);
            }
            checker.matchTyper.clearSubtree(ctx);
            if (ctx instanceof BabbleParser.DefExpressionContext
                    || ScopeAnalysis.isParamDecl(ctx)) {
                checker.declarationTypes.remove(ctx);
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            clearSubtreeTypes(tree.getChild(i));
        }
    }

    static BabbleParser.FunctionLiteralContext unwrapFunctionLiteral(
            BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext block
                && block.block().expression().size() == 1) {
            expr = block.block().expression(0);
        }
        return expr instanceof BabbleParser.FunctionLiteralContext literal ? literal : null;
    }

    boolean isGenericFunctionSelector(BabbleParser.SelectorContext selector) {
        if (selector.expression() == null) {
            Object resolved =
                    checker.resolveName(
                            selector,
                            selector.ID().getText(),
                            checker.occurrenceScopes.get(selector));
            if (resolved instanceof BabbleParser.DefExpressionContext def) {
                if (def.typeParameters() != null) {
                    return true;
                }
                BabbleParser.FunctionLiteralContext lit = unwrapFunctionLiteral(def.value);
                if (lit != null && lit.typeParameters() != null) {
                    return true;
                }
            }
            if (resolved instanceof ParserRuleContext decl) {
                Type declType = checker.annotator.declaredType(decl);
                return declType instanceof Type.Function fn && !fn.typeParameters().isEmpty();
            }
            return false;
        }
        if (selector.expression() instanceof BabbleParser.SelectorContext) {
            Type t = checker.typer.selectUninstantiated(selector);
            return t instanceof Type.Function fn && !fn.typeParameters().isEmpty();
        }
        return false;
    }

    /** A function's value, its last statement, must match its annotated result. */
    void checkResult(BabbleParser.FunctionLiteralContext function) {
        if (function.type() == null) {
            return;
        }
        Type expected = checker.annotator.annotation(function.type(), checker.scopeOf(function));
        List<BabbleParser.ExpressionContext> statements = function.functionBlock.expression();
        if (statements.isEmpty()) {
            subsumes(function.functionBlock, null, Type.NULL, expected);
        } else {
            BabbleParser.ExpressionContext last = statements.get(statements.size() - 1);
            subsumes(last, function.functionBlock, checker.synth(last), expected);
        }
    }

    void subsumes(ParserRuleContext site, Type actual, Type expected) {
        subsumes(site, site, actual, expected);
    }

    void subsumes(
            ParserRuleContext errorSite, ParserRuleContext widenSite, Type actual, Type expected) {
        if (actual != Type.ERROR
                && expected != Type.ERROR
                && !checker.algebra.assignable(actual, expected)) {
            reportTypeMismatch(actual, expected, errorSite);
        }
        coerce(widenSite, actual, expected);
    }

    void subsumesBorrow(ParserRuleContext site, Type actual, Type expected) {
        if (actual != Type.ERROR
                && expected != Type.ERROR
                && !checker.algebra.borrowAssignable(actual, expected)) {
            reportTypeMismatch(actual, expected, site);
        }
        coerce(site, actual, expected);
    }

    /**
     * The one coercion point : records that the value {@code site} produces is checker.widened
     * numerically along {@code byte < short < int < long < float < double} where {@code target} is
     * expected.
     */
    void coerce(ParserRuleContext site, Type actual, Type target) {
        int targetRank = TypeAlgebra.numericWideningTargetRank(actual, target);
        if (site != null && targetRank >= 0) {
            checker.widened.merge(site, targetRank, Math::max);
        }
        if (site != null && actual instanceof Type.Overloaded ov && target != null) {
            Type targetBase = Type.withoutNull(target);
            int idx = -1;
            if (targetBase instanceof Type.Function targetFn) {
                idx =
                        OverloadSolver.selectAlternativeForFunction(
                                checker.algebra, ov, targetFn, checker.checkingEffects);
            } else if (JavaTypeBridge.resolveJavaSam(targetBase)
                    instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
                idx = OverloadSolver.selectAlternativeForSam(checker.algebra, ov, valid.samType());
            }
            if (idx >= 0) {
                Type.Function chosen = ov.alternatives().get(idx);
                ParserRuleContext exprSite =
                        site instanceof BabbleParser.DefExpressionContext def && def.value != null
                                ? def.value
                                : site instanceof BabbleParser.AssignExpressionContext assign
                                                && assign.value != null
                                        ? assign.value
                                        : site instanceof BabbleParser.ReturnExpressionContext ret
                                                        && ret.expression() != null
                                                ? ret.expression()
                                                : site;
                boolean exactSet = isExactOverloadSetExpr(exprSite);
                checker.overloadSelections.put(
                        exprSite,
                        new LoweringTargets.OverloadSelection(
                                idx, OverloadSolver.signatureKey(chosen), exactSet));
            }
        }
        coerceSam(site, actual, target);
    }

    void coerceSam(ParserRuleContext site, Type actual, Type target) {
        if (site == null || !TypeAlgebra.mayContainBabbleCallable(actual)) {
            return;
        }
        JavaTypeBridge.JavaSamResolution.Valid valid = resolveSamTargetForFlow(actual, target);
        if (valid != null) {
            checker.samTargets.put(site, valid.toTarget());
        }
    }

    private static List<Type> callableAlternatives(Type type) {
        List<Type> result = new ArrayList<>();
        for (Type alt : TypeAlgebra.alternatives(type)) {
            Type unwrapped = TypeAlgebra.unwrapFrozen(alt);
            if (TypeAlgebra.isCallable(unwrapped) || unwrapped == Type.ANY) {
                result.add(alt);
            }
        }
        return result;
    }

    JavaTypeBridge.JavaSamResolution.Valid resolveSamTargetForFlow(Type actual, Type target) {
        if (target == null || target == Type.ERROR || target == Type.NOTHING) {
            return null;
        }
        JavaTypeBridge.JavaSamResolution direct = JavaTypeBridge.resolveJavaSam(target);
        if (direct instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
            return valid;
        }
        if (Type.withoutNull(target) instanceof Type.Union u) {
            List<Type> callableAlts = callableAlternatives(actual);
            if (callableAlts.isEmpty()) {
                return null;
            }
            for (Type member : u.members()) {
                Type mb = Type.withoutNull(member);
                if (mb instanceof Type.Function
                        || mb instanceof Type.Overloaded
                        || mb == Type.ANY) {
                    for (Type c : callableAlts) {
                        if (checker.algebra.assignable(c, member)) {
                            return null;
                        }
                    }
                }
            }
            JavaTypeBridge.JavaSamResolution.Valid match = null;
            for (Type member : u.members()) {
                if (JavaTypeBridge.resolveJavaSam(member)
                        instanceof JavaTypeBridge.JavaSamResolution.Valid v) {
                    boolean matchesAny = false;
                    for (Type c : callableAlts) {
                        if (TypeAlgebra.unwrapFrozen(c) == Type.ANY
                                || checker.algebra.assignable(c, member)) {
                            matchesAny = true;
                            break;
                        }
                    }
                    if (matchesAny) {
                        if (match != null && !match.equals(v)) {
                            return null;
                        }
                        match = v;
                    }
                }
            }
            return match;
        }
        return null;
    }

    JavaTypeBridge.JavaSamResolution.Valid resolveSamTargetForAs(Type source, Type target) {
        if (target == null
                || target == Type.ERROR
                || target == Type.NOTHING
                || !TypeAlgebra.mayContainBabbleCallable(source)) {
            return null;
        }
        JavaTypeBridge.JavaSamResolution direct = JavaTypeBridge.resolveJavaSam(target);
        if (direct instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
            return valid;
        }
        if (Type.withoutNull(target) instanceof Type.Union u) {
            for (Type member : u.members()) {
                if (Type.withoutNull(member) == Type.ANY) {
                    return null;
                }
            }
            List<Type> callableAlts = callableAlternatives(source);
            if (callableAlts.isEmpty()) {
                return null;
            }
            JavaTypeBridge.JavaSamResolution.Valid match = null;
            for (Type member : u.members()) {
                if (JavaTypeBridge.resolveJavaSam(member)
                        instanceof JavaTypeBridge.JavaSamResolution.Valid v) {
                    boolean matchesAny = false;
                    for (Type c : callableAlts) {
                        if (TypeAlgebra.unwrapFrozen(c) == Type.ANY
                                || checker.algebra.assignable(c, member)) {
                            matchesAny = true;
                            break;
                        }
                    }
                    if (matchesAny) {
                        if (match != null && !match.equals(v)) {
                            return null;
                        }
                        match = v;
                    }
                }
            }
            return match;
        }
        return null;
    }

    boolean demoteSamMemberFields() {
        boolean demotedAny = false;
        for (TypeNode node : checker.pass.types) {
            if (node.ctx == null || !checker.inFile(node.ctx)) {
                continue;
            }
            boolean nodeDemoted = false;
            if (!node.isInterface()) {
                for (TypeNode.Member member : node.members.values()) {
                    if (maybeDemoteSamMember(node, member, false)) {
                        demotedAny = true;
                        nodeDemoted = true;
                    }
                }
            }
            for (TypeNode.Member member : node.statics.values()) {
                if (maybeDemoteSamMember(node, member, true)) {
                    demotedAny = true;
                    nodeDemoted = true;
                }
            }
            if (nodeDemoted) {
                checker.pass.analysis.hierarchy().setShape(node.ctx, node.shape());
            }
        }
        return demotedAny;
    }

    private boolean maybeDemoteSamMember(TypeNode owner, TypeNode.Member member, boolean isStatic) {
        if (!member.isMethod()
                || !(member.declaration instanceof BabbleParser.DefExpressionContext def)
                || def.type() == null) {
            return false;
        }
        Type declared = checker.annotator.declaredType(def);
        if (!isSamAdapterFieldType(declared)) {
            return false;
        }
        demoteMemberToField(owner, member, def, isStatic);
        return true;
    }

    private boolean isSamAdapterFieldType(Type declared) {
        if (declared == null || declared == Type.ERROR || declared == Type.NOTHING) {
            return false;
        }
        JavaTypeBridge.JavaSamResolution res = JavaTypeBridge.resolveJavaSam(declared);
        if (res instanceof JavaTypeBridge.JavaSamResolution.Valid
                || res instanceof JavaTypeBridge.JavaSamResolution.NotFunctional) {
            return true;
        }
        if (Type.withoutNull(declared) instanceof Type.Union u) {
            boolean hasSam = false;
            for (Type member : u.members()) {
                Type mb = Type.withoutNull(member);
                if (mb instanceof Type.Function
                        || mb instanceof Type.Overloaded
                        || mb == Type.ANY) {
                    return false;
                }
                if (JavaTypeBridge.resolveJavaSam(member)
                        instanceof JavaTypeBridge.JavaSamResolution.Valid) {
                    hasSam = true;
                }
            }
            return hasSam;
        }
        return false;
    }

    void demoteMemberToField(
            TypeNode owner,
            TypeNode.Member member,
            BabbleParser.DefExpressionContext def,
            boolean isStatic) {
        List<BabbleParser.FunctionLiteralContext> lits = new ArrayList<>();
        if (member.function != null) {
            lits.add(member.function);
        }
        lits.addAll(member.overloadFunctions);
        for (BabbleParser.FunctionLiteralContext lit : lits) {
            ScopeNode fnScope = checker.scopeOf(lit);
            if (fnScope != null) {
                fnScope.memberOf = null;
                fnScope.staticMethod = false;
                for (BlockScopes.PendingSuper pending : checker.pass.supers) {
                    if (isScopeInside(pending.scope(), fnScope)) {
                        checker.demoteDiagnostics.add(
                                new TypeChecker.Diagnostic(
                                        pending.ctx().getStart().getLine(),
                                        pending.ctx().getStart().getCharPositionInLine() + 1,
                                        "super outside of a method"));
                    }
                }
            }
        }
        member.demoteSamField();
        if (!isStatic) {
            Set<Class<?>> javaParents = new LinkedHashSet<>();
            collectJavaParents(
                    owner, javaParents, Collections.newSetFromMap(new IdentityHashMap<>()));
            for (Class<?> jc : javaParents) {
                for (Method m : JavaTypes.abstractMethods(jc)) {
                    if (m.getName().equals(member.name)
                            && !hasBabbleDefault(
                                    owner,
                                    member.name,
                                    Collections.newSetFromMap(new IdentityHashMap<>()))) {
                        int line =
                                owner.ctx != null
                                        ? owner.ctx.getStart().getLine()
                                        : def.getStart().getLine();
                        int col =
                                owner.ctx != null
                                        ? owner.ctx.getStart().getCharPositionInLine() + 1
                                        : 1;
                        checker.demoteDiagnostics.add(
                                new TypeChecker.Diagnostic(
                                        line,
                                        col,
                                        owner.name
                                                + " does not implement "
                                                + jc.getName()
                                                + "."
                                                + member.name));
                        break;
                    }
                }
            }
        }
    }

    private static boolean isScopeInside(ScopeNode inner, ScopeNode target) {
        for (ScopeNode s = inner; s != null; s = s.parent) {
            if (s == target) {
                return true;
            }
            if (s.kind == ScopeNode.Kind.CLASS_BODY
                    || s.kind == ScopeNode.Kind.INTERFACE_BODY
                    || s.kind == ScopeNode.Kind.STATIC_BODY) {
                return false;
            }
        }
        return false;
    }

    private static void collectJavaParents(TypeNode node, Set<Class<?>> out, Set<TypeNode> seen) {
        if (!seen.add(node)) {
            return;
        }
        for (Object parent : node.parents) {
            if (parent instanceof Class<?> jc) {
                out.add(jc);
            } else if (parent instanceof TypeNode pn) {
                collectJavaParents(pn, out, seen);
            }
        }
    }

    private static boolean hasBabbleDefault(TypeNode owner, String memberName, Set<TypeNode> seen) {
        for (Object parent : owner.parents) {
            if (parent instanceof TypeNode pn && seen.add(pn)) {
                if (pn.defaults != null && pn.defaults.containsKey(memberName)) {
                    return true;
                }
                if (hasBabbleDefault(pn, memberName, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether {@code expr} is statically known to evaluate to the exact overload set of its static
     * type (rather than a wider runtime set passed through a set-typed parameter or interface
     * member).
     */
    boolean isExactOverloadSetExpr(ParserRuleContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1) {
            expr = b.block().expression(0);
        }
        if (expr instanceof BabbleParser.OverloadExpressionContext) {
            return true;
        }
        if (expr instanceof BabbleParser.SelectorContext sel) {
            if (sel.expression() == null) {
                ScopeNode scope = checker.occurrenceScopes.get(sel);
                Object resolved =
                        scope != null ? checker.resolveName(sel, sel.ID().getText(), scope) : null;
                if (resolved instanceof BabbleParser.DefExpressionContext def
                        && !ScopeAnalysis.isVar(def)) {
                    return def.type() == null
                            && def.value instanceof BabbleParser.OverloadExpressionContext;
                }
                return false;
            }
            Type recv = checker.types.get(sel.expression());
            if (recv instanceof Type.Module) {
                return true;
            }
            String memberName = ScopeAnalysis.selectorName(sel);
            TypeNode.Member member = null;
            if (recv instanceof Type.Instance inst && !inst.node().isInterface()) {
                member = inst.node().members.get(memberName);
            } else if (recv instanceof Type.ClassValue cv) {
                member = cv.node().statics.get(memberName);
            }
            if (member != null
                    && member.declaration instanceof BabbleParser.DefExpressionContext def) {
                return def.type() == null
                        && def.value instanceof BabbleParser.OverloadExpressionContext;
            }
        }
        return false;
    }

    /**
     * Resolves the flattened list of local {@link BabbleParser.FunctionLiteralContext} alternatives
     * for an immutable local overload set expression (entries may be {@code null} for non-literal
     * or imported alternatives), or {@code null} if {@code expr} is not a known local overload set.
     */
    List<BabbleParser.FunctionLiteralContext> resolveOverloadLiterals(ParserRuleContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1) {
            expr = b.block().expression(0);
        }
        if (expr instanceof BabbleParser.OverloadExpressionContext ov) {
            return checker.overloadAltLiterals.get(ov);
        }
        if (expr instanceof BabbleParser.SelectorContext sel) {
            if (sel.expression() == null) {
                ScopeNode scope = checker.occurrenceScopes.get(sel);
                Object resolved =
                        scope != null ? checker.resolveName(sel, sel.ID().getText(), scope) : null;
                if (resolved instanceof BabbleParser.DefExpressionContext def
                        && !ScopeAnalysis.isVar(def)
                        && def.value instanceof BabbleParser.OverloadExpressionContext ov
                        && checker.inFile(ov)) {
                    return checker.overloadAltLiterals.get(ov);
                }
                return null;
            }
            Type recv = checker.types.get(sel.expression());
            String memberName = ScopeAnalysis.selectorName(sel);
            TypeNode.Member member = null;
            if (recv instanceof Type.Instance inst && !inst.node().isInterface()) {
                member = inst.node().members.get(memberName);
            } else if (recv instanceof Type.ClassValue cv) {
                member = cv.node().statics.get(memberName);
            }
            if (member != null
                    && member.declaration instanceof BabbleParser.DefExpressionContext def
                    && def.value instanceof BabbleParser.OverloadExpressionContext ov
                    && checker.inFile(ov)) {
                return checker.overloadAltLiterals.get(ov);
            }
        }
        return null;
    }

    /**
     * A value flowing into an unannotated def or member : recorded in its slot, and coerced to the
     * slot's type.
     */
    void flowInto(
            ParserRuleContext declaration,
            boolean member,
            Type value,
            ParserRuleContext errorSite,
            ParserRuleContext widenSite) {
        if (member) {
            recordInferredMember(declaration, value, errorSite);
        } else {
            recordInferredDef(declaration, value, errorSite);
        }
        Type inferred = (member ? checker.inferredMembers : checker.inferredDefs).get(declaration);
        if (inferred != null) {
            coerce(widenSite, value, inferred);
        }
    }

    void reportTypeMismatch(Type actual, Type expected, ParserRuleContext at) {
        if (TypeAlgebra.isCallable(actual)
                && JavaTypeBridge.resolveJavaSam(expected)
                        instanceof JavaTypeBridge.JavaSamResolution.NotFunctional nf) {
            checker.warn("Not a functional interface : " + nf.intersection(), at);
        } else if (actual instanceof Type.Function aFn
                && expected instanceof Type.Function eFn
                && (checker.algebra.assignableIgnoringEffects(aFn, eFn)
                        || !eFn.typeParameters().isEmpty()
                        || !aFn.typeParameters().isEmpty())) {
            checker.warn(
                    "Type error : expected "
                            + eFn.describeAgainst(eFn)
                            + ", got "
                            + aFn.describeAgainst(eFn),
                    at);
        } else {
            checker.warn("Type error : expected " + expected + ", got " + actual, at);
        }
    }

    /**
     * The type of {@code this} : the class or interface whose body or method it is in, or the class
     * value in a static block.
     */
    Type thisType(ScopeNode scope) {
        ScopeNode thisScope = BlockScopes.thisScope(scope);
        if (thisScope == null) {
            return Type.ERROR;
        }
        if (thisScope.kind == ScopeNode.Kind.STATIC_BODY) {
            return new Type.ClassValue(thisScope.type);
        }
        List<Type.TypeVar> vars = checker.annotator.typeVarsOf(thisScope.type);
        return vars.isEmpty()
                ? new Type.Instance(thisScope.type)
                : new Type.Instance(thisScope.type, List.copyOf(vars));
    }

    void recordInferredDef(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        checker.slots.contribute(SlotInference.DEFS, decl, valueType, at);
    }

    void recordInferredMember(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        checker.slots.contribute(SlotInference.MEMBERS, decl, valueType, at);
    }

    void recordInferredParam(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        if (TypeChecker.isUnresolved(valueType)) {
            return;
        }
        if (containsUnresolved(valueType)) {
            return;
        }
        if (hasForeignTypeVar(valueType, decl)) {
            Type currentInferred = checker.inferredParams.get(decl);
            if (currentInferred != null) {
                subsumes(at, null, valueType, currentInferred);
            }
            return;
        }
        checker.slots.contribute(SlotInference.PARAMS, decl, valueType, at);
    }

    private static boolean containsUnresolved(Type type) {
        return type == null
                || type.anyMatch(t ->
                        TypeChecker.isUnresolved(t)
                                || (t instanceof Type.TypeVar tv && tv.owner() == null));
    }

    private boolean hasForeignTypeVar(Type type, ParserRuleContext decl) {
        return type != null
                && type.anyMatch(t -> t instanceof Type.TypeVar tv && !isTypeVarInScope(tv, decl));
    }

    private boolean isTypeVarInScope(Type.TypeVar tv, ParserRuleContext decl) {
        if (tv.owner() == null) {
            return false;
        }
        for (ParserRuleContext p = decl; p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.DefExpressionContext def
                    && checker.annotator.symbolOf(def) == tv.owner()) {
                return true;
            }
            if (p.getParent() instanceof BabbleParser.ClassExpressionContext cls
                    && cls.staticBlock == p) {
                BabbleParser.DefExpressionContext owningDef = checker.owningDefExpr(cls);
                if (owningDef != null && checker.annotator.symbolOf(owningDef) == tv.owner()) {
                    return false;
                }
            }
        }
        return false;
    }

    private static BabbleParser.ClassExpressionContext unwrapClassExpression(
            BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext block
                && block.block().expression().size() == 1) {
            expr = block.block().expression(0);
        }
        return expr instanceof BabbleParser.ClassExpressionContext cls ? cls : null;
    }

    void checkSelectorEscape(BabbleParser.SelectorContext ctx, Type result, TypeChecker.Role role) {
        boolean callee = role.isCallee();
        boolean classSafe = role.isClassSafe();
        if (!callee || !classSafe) {
            for (Type member : Type.membersOf(result)) {
                if (member instanceof Type.Function fn) {
                    BabbleParser.FunctionLiteralContext lit = checker.functionLiterals.get(fn);
                    if (lit != null && checker.inFile(lit) && !callee) {
                        checker.nextEscapingTargets.add(lit);
                    }
                } else if (member instanceof Type.ClassValue cv
                        && cv.node().ctx instanceof BabbleParser.ClassExpressionContext cls
                        && checker.inFile(cls)
                        && !classSafe) {
                    checker.nextEscapingTargets.add(cls);
                }
            }
            if (result == Type.ERROR && ctx.expression() == null) {
                Object resolved =
                        checker.resolveName(
                                ctx, ctx.ID().getText(), checker.occurrenceScopes.get(ctx));
                if (resolved instanceof BabbleParser.DefExpressionContext def
                        && checker.inFile(def)) {
                    BabbleParser.FunctionLiteralContext lit = unwrapFunctionLiteral(def.value);
                    if (lit != null && !callee) {
                        checker.nextEscapingTargets.add(lit);
                    }
                    BabbleParser.ClassExpressionContext cls = unwrapClassExpression(def.value);
                    if (cls != null && !classSafe) {
                        checker.nextEscapingTargets.add(cls);
                    }
                }
            }
        }
    }
}
