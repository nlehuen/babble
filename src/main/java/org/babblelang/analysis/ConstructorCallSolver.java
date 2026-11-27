package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

/**
 * Class/record constructor call typing, visibility enforcement, and delegating {@code this(...)}
 * dispatch ({@code BIP040}), extracted from {@link CallSolver}.
 */
final class ConstructorCallSolver {
    private final TypeChecker checker;
    private final CallSolver solver;
    private final OverloadCallResolver overloads;

    ConstructorCallSolver(TypeChecker checker, CallSolver solver, OverloadCallResolver overloads) {
        this.checker = checker;
        this.solver = solver;
        this.overloads = overloads;
    }

    Type callClassConstructor(
            TypeNode node,
            List<Type> explicitTypeArgs,
            List<BabbleParser.CallParameterContext> arguments,
            BabbleParser.CallContext ctx,
            Type expected,
            boolean delegating) {
        int ctorCount = checker.constructorCount(node);
        List<Type.TypeVar> classVars = checker.annotator().typeVarsOf(node);
        if (ctorCount <= 1) {
            checkConstructorVisibility(node, 0, ctx);
            checker.recordConstructorCall(ctx, 0, delegating);
            return bindSingleConstructor(
                    node, 0, classVars, explicitTypeArgs, arguments, ctx, expected);
        }
        return overloads.overloadedConstructorCall(
                node, classVars, explicitTypeArgs, arguments, ctx, expected, delegating);
    }

    Type callDelegatingConstructor(
            TypeNode node,
            List<Type.TypeVar> classVars,
            BabbleParser.CallContext ctx,
            Type expected) {
        List<Type> rigidArgs = classVars.isEmpty() ? List.of() : List.copyOf(classVars);
        return callClassConstructor(
                node, rigidArgs, ctx.callParameters().callParameter(), ctx, expected, true);
    }

    void checkConstructorVisibility(TypeNode node, int ctorIndex, BabbleParser.CallContext ctx) {
        if (!checker.constructorIsPrivate(node, ctorIndex)) {
            return;
        }
        for (ScopeNode s = checker.occurrenceScopeOf(ctx); s != null; s = s.parent) {
            if (s.type == node || s.memberOf == node) {
                return;
            }
        }
        checker.warn("Constructor is private : " + node.name, ctx);
    }

    Type bindSingleConstructor(
            TypeNode node,
            int ctorIndex,
            List<Type.TypeVar> classVars,
            List<Type> explicitTypeArgs,
            List<BabbleParser.CallParameterContext> arguments,
            BabbleParser.CallContext ctx,
            Type expected) {
        List<Type.Parameter> parameters = checker.constructorParameters(node, ctorIndex);
        List<? extends ParserRuleContext> decls = constructorParamDecls(node, ctorIndex);
        Set<Integer> forwarded = constructorForwardedParams(node, ctorIndex, parameters);
        EffectChecker.CallableSummary cs =
                checker.effectChecker() != null
                        ? checker.effectChecker().constructorSummary(node, ctorIndex)
                        : null;
        boolean uniqueResult = cs != null && cs.effects().uniqueResult();
        if (explicitTypeArgs != null && !explicitTypeArgs.isEmpty()) {
            Map<Type.TypeVar, Type> env = new HashMap<>();
            for (int i = 0; i < explicitTypeArgs.size() && i < classVars.size(); i++) {
                env.put(classVars.get(i), explicitTypeArgs.get(i));
            }
            List<Type.Parameter> substituted = new ArrayList<>(parameters.size());
            for (Type.Parameter p : parameters) {
                substituted.add(p.withType(checker.algebra().substitute(p.type(), env)));
            }
            Type result = new Type.Instance(node, explicitTypeArgs);
            boolean frozenBorrowSafe = uniqueResult || checker.isDeeplyImmutable(result);
            solver.bind(substituted, decls, arguments, ctx, frozenBorrowSafe, forwarded);
            return result;
        }
        if (classVars.isEmpty()) {
            Type result = new Type.Instance(node);
            boolean frozenBorrowSafe = uniqueResult || checker.isDeeplyImmutable(result);
            solver.bind(parameters, decls, arguments, ctx, frozenBorrowSafe, forwarded);
            return result;
        }
        return solver.genericBabbleCall(
                classVars,
                parameters,
                decls,
                new Type.Instance(node, List.copyOf(classVars)),
                uniqueResult,
                forwarded,
                node,
                arguments,
                ctx,
                node.name,
                expected);
    }

    private List<? extends ParserRuleContext> constructorParamDecls(TypeNode node, int ctorIndex) {
        if (node == null || ctorIndex < 0 || ctorIndex >= node.constructors.size()) {
            return null;
        }
        TypeNode.Constructor ctor = node.constructors.get(ctorIndex);
        if (ctor.isPrimary) {
            return node.ctx instanceof BabbleParser.ClassExpressionContext cls
                            && cls.classParameters() != null
                    ? cls.classParameters().classParam()
                    : List.of();
        }
        return ctor.initBlock != null && ctor.initBlock.fnParameters() != null
                ? ctor.initBlock.fnParameters().fnParam()
                : List.of();
    }

    private Set<Integer> constructorForwardedParams(
            TypeNode node, int ctorIndex, List<Type.Parameter> parameters) {
        if (node == null || ctorIndex < 0 || ctorIndex >= node.constructors.size()) {
            return Set.of();
        }
        TypeNode.Constructor ctor = node.constructors.get(ctorIndex);
        if (ctor.initBlock == null || ctor.initBlock.effectPrefix() == null) {
            return Set.of();
        }
        return TypeAnnotations.resolveForwardedParams(ctor.initBlock.effectPrefix(), parameters);
    }
}
