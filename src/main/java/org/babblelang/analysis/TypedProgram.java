package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Read-only typed-program view shared by {@link TypeChecker} with {@link EffectChecker}
 * and {@link LoweringAnalyzer} ({@code docs/design/bip010_type-checker-simplification.md} §4.6).
 */
final class TypedProgram {
    private final TypeChecker checker;

    TypedProgram(TypeChecker checker) {
        this.checker = checker;
    }

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
        return checker.isExportedDef(def);
    }

    boolean isReassigned(ParserRuleContext decl) {
        return checker.isReassigned(decl);
    }

    BabbleParser.FunctionLiteralContext enclosingFunctionOf(ParserRuleContext ctx) {
        return checker.enclosingFunctionOf(ctx);
    }

    Object resolveNameForEffects(String name, ScopeNode scope) {
        return checker.resolveNameForEffects(name, scope);
    }

    Object resolveNamespaceMemberDecl(Type nsType, String name) {
        return checker.resolveNamespaceMemberDecl(nsType, name);
    }

    Type rawTypeOf(ParserRuleContext ctx) {
        return checker.rawTypeOf(ctx);
    }

    Type typeOfDeclarationForEffects(ParserRuleContext decl) {
        return checker.typeOfDeclarationForEffects(decl);
    }

    Type annotationTypeOf(BabbleParser.FunctionTypeContext ft) {
        return checker.annotationTypeOf(ft);
    }

    Type implicitTypeForEffects(String name) {
        return checker.implicitTypeForEffects(name);
    }

    Type moduleMemberTypeForEffects(ModuleInterface mod, String name) {
        return checker.moduleMemberTypeForEffects(mod, name);
    }

    Type packageMemberTypeForEffects(Type.Package pkg, String name) {
        return checker.packageMemberTypeForEffects(pkg, name);
    }

    Type memberTypeForEffects(TypeNode owner, TypeNode.Member member) {
        return checker.memberTypeForEffects(owner, member);
    }

    Type instanceMemberTypeForEffects(Type.Instance inst, TypeNode.Member member) {
        return checker.instanceMemberTypeForEffects(inst, member);
    }

    TypeNode.Member findMemberOnNode(TypeNode node, String name) {
        return checker.findMemberOnNode(node, name);
    }

    List<Type.Parameter> constructorParamsForEffects(TypeNode node) {
        return checker.constructorParamsForEffects(node);
    }

    boolean isModuleExportImmutable(ModuleInterface mod, String name, Type resType) {
        return checker.isModuleExportImmutable(mod, name, resType);
    }

    boolean isBuiltinFunction(Type.Function fn) {
        return checker.isBuiltinFunction(fn);
    }

    boolean assignable(Type from, Type to) {
        return checker.algebra().assignable(from, to);
    }

    boolean assignableNoWidening(Type from, Type to) {
        return checker.algebra().subtype(from, to);
    }

    Class<?> findJavaClass(String name) {
        return checker.findJavaClass(name);
    }

    Class<?> findMemberJavaClass(Class<?> owner, String name) {
        return checker.findMemberJavaClass(owner, name);
    }

    Method resolvedJavaMethodOf(BabbleParser.CallContext call) {
        return checker.resolvedJavaMethodOf(call);
    }

    Constructor<?> resolvedJavaConstructorOf(BabbleParser.CallContext call) {
        return checker.resolvedJavaConstructorOf(call);
    }

    ScopeAnalysis.JavaFieldTarget javaFieldTargetOf(ParserRuleContext sel) {
        return checker.javaFieldTargetOf(sel);
    }

    boolean hasCallCheck(ParserRuleContext ctx) {
        return checker.hasCallCheck(ctx);
    }

    BabbleParser.FunctionLiteralContext literalOf(Type.Function fn) {
        return checker.literalOf(fn);
    }

    int parameterIndexForArg(List<Type.Parameter> parameters,
                             List<BabbleParser.CallParameterContext> arguments,
                             BabbleParser.CallParameterContext targetArg) {
        return checker.parameterIndexForArg(parameters, arguments, targetArg);
    }

    TypeChecker.NarrowingInfo extractNarrowingForEffects(BabbleParser.ExpressionContext expr) {
        return checker.extractNarrowingForEffects(expr);
    }

    EffectChecker.CallableSummary remoteConstructorSummary(TypeNode node) {
        return checker.remoteConstructorSummary(node);
    }

    TypedModuleSummary.CallableEffectTemplate remoteCallableTemplate(Type.Function fn) {
        return checker.remoteCallableTemplate(fn);
    }

    void effectDiagnostic(String message, ParserRuleContext at) {
        checker.effectDiagnostic(message, at);
    }
}
