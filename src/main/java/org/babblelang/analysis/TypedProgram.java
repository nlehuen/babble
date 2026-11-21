package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The typed program {@link EffectChecker} reads ({@code BIP010-D6}) : the type of every
 * expression where it occurs (narrowed by the tests that guard it), the declarations names
 * resolve to and their converged types, members, the Babble and Java callables each call
 * resolved to, and the summaries of other files. Queries only : effects never change types.
 */
final class TypedProgram {
    private final TypeChecker checker;

    TypedProgram(TypeChecker checker) {
        this.checker = checker;
    }

    // ---------------------------------------------------------------- names and scopes

    ScopeNode scopeOf(ParserRuleContext ctx) {
        return checker.scopeOf(ctx);
    }

    ScopeNode occurrenceScopeOf(ParserRuleContext ctx) {
        return checker.occurrenceScopeOf(ctx);
    }

    boolean inFile(ParserRuleContext ctx) {
        return checker.inFile(ctx);
    }

    boolean isExportedDef(BabbleParser.DefExpressionContext def) {
        return checker.isExported(def);
    }

    boolean isReassigned(ParserRuleContext decl) {
        return checker.isReassigned(decl);
    }

    /** The function literal {@code ctx} is in, or null. */
    BabbleParser.FunctionLiteralContext enclosingFunctionOf(ParserRuleContext ctx) {
        for (ParserRuleContext p = ctx.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.FunctionLiteralContext function) {
                return function;
            }
        }
        return null;
    }

    /** What a name refers to from a scope : a declaration, or null. */
    Object resolveNameForEffects(String name, ScopeNode scope) {
        return TypeChecker.resolveName(null, name, scope);
    }

    // ---------------------------------------------------------------- types

    /** The type of an expression where it occurs. */
    Type rawTypeOf(ParserRuleContext ctx) {
        if (ctx == null) {
            return Type.ERROR;
        }
        Type t = checker.types().get(ctx);
        return t != null ? t : checker.synth(ctx);
    }

    Type typeOfDeclarationForEffects(ParserRuleContext decl) {
        return checker.typeOfDeclaration(decl);
    }

    Type annotationTypeOf(BabbleParser.FunctionTypeContext ft) {
        return checker.annotator().nonParenAnnotation(ft, checker.scopeOf(ft), false);
    }

    /** The type of an implicit name, or null for one that has no precise type. */
    Type implicitTypeForEffects(String name) {
        Type t = checker.operators().implicitType(name);
        return t == Type.ANY ? null : t;
    }

    Type moduleMemberTypeForEffects(ModuleInterface mod, String name) {
        return MemberTypes.exportType(mod, name);
    }

    Type memberTypeForEffects(TypeNode owner, TypeNode.Member member) {
        return checker.memberType(owner, member);
    }

    /** A member's type as seen through an instance, with its type arguments. */
    Type instanceMemberTypeForEffects(Type.Instance inst, TypeNode.Member member) {
        return checker.algebra().substitute(checker.memberType(inst.node(), member), checker.algebra().instanceEnv(inst));
    }

    TypeNode.Member findMemberOnNode(TypeNode node, String name) {
        return MemberTypes.findMember(node, name);
    }

    List<Type.Parameter> constructorParamsForEffects(TypeNode node) {
        List<Type.Parameter> params = checker.constructorParameters(node);
        return params != null ? params : List.of();
    }

    TypeChecker.ForIterableInfo forIterableInfo(BabbleParser.ForExpressionContext ctx) {
        return checker.forIterableInfo(ctx);
    }

    boolean isModuleExportImmutable(ModuleInterface mod, String name, Type resType) {
        return checker.effectChecker != null && checker.effectChecker.isImmutableConstantValue(resType);
    }

    boolean isBuiltinFunction(Type.Function fn) {
        return OperatorTypes.isBuiltinFunction(fn);
    }

    boolean assignable(Type from, Type to) {
        return checker.algebra().assignable(from, to);
    }

    boolean assignableNoWidening(Type from, Type to) {
        return checker.algebra().subtype(from, to);
    }

    // ---------------------------------------------------------------- calls and Java

    Class<?> findJavaClass(String name) {
        return JavaTypeBridge.loadTopLevel(name);
    }

    Class<?> findMemberJavaClass(Class<?> owner, String name) {
        return JavaTypeBridge.publicMemberClass(owner, name);
    }

    Method resolvedJavaMethodOf(ParserRuleContext call) {
        return checker.callSolver().resolvedMethod(call);
    }

    Constructor<?> resolvedJavaConstructorOf(ParserRuleContext call) {
        return checker.callSolver().resolvedConstructor(call);
    }

    boolean isResolvedJavaVarArgs(ParserRuleContext call) {
        return checker.callSolver().isResolvedVarArgs(call);
    }

    /** The Java field a selection reads, when it has a static target. */
    ScopeAnalysis.JavaFieldTarget javaFieldTargetOf(BabbleParser.SelectorContext sel) {
        return sel.expression() == null || sel.safe != null ? null
                : LoweringAnalyzer.fieldReadTarget(checker.types().get(sel.expression()), ScopeAnalysis.selectorName(sel), checker.algebra());
    }

    /** Whether the value of {@code ctx} is checked at runtime (a Java generic result, T9). */
    boolean hasCallCheck(ParserRuleContext ctx) {
        return checker.callChecks().containsKey(ctx);
    }

    /** The function literal a function type was typed from, or null. */
    BabbleParser.FunctionLiteralContext literalOf(Type.Function fn) {
        return checker.functionLiterals().get(fn);
    }

    /** The index of the parameter an argument binds to, or -1. */
    int parameterIndexForArg(List<Type.Parameter> parameters,
                             List<BabbleParser.CallParameterContext> arguments,
                             BabbleParser.CallParameterContext targetArg) {
        String argName = ScopeAnalysis.callParamName(targetArg);
        if (argName != null) {
            for (int i = 0; i < parameters.size(); i++) {
                if (parameters.get(i).name().equals(argName)) {
                    return i;
                }
            }
            return -1;
        }
        Type.Parameter[] mapped = CallSolver.mapArgumentsToParameters(parameters, arguments);
        int argPos = arguments.indexOf(targetArg);
        if (argPos < 0 || argPos >= mapped.length || mapped[argPos] == null) {
            return -1;
        }
        return parameters.indexOf(mapped[argPos]);
    }

    // ---------------------------------------------------------------- other files

    EffectChecker.CallableSummary remoteConstructorSummary(TypeNode node) {
        return checker.imported().constructorSummary(node);
    }

    TypedModuleSummary.CallableEffectTemplate remoteCallableTemplate(Type.Function fn) {
        return checker.imported().callableTemplate(fn);
    }

    boolean hasTypeDiagnosticIn(ParserRuleContext ctx) {
        return checker.hasDiagnosticIn(ctx);
    }

    void effectDiagnostic(String message, ParserRuleContext at) {
        checker.effectDiagnostic(message, at);
    }

    void effectWarning(String message, ParserRuleContext at) {
        checker.analysisWarn(message, at);
    }
}
