package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AST-detached, immutable type and effect summary published by a finished module
 * (or committed REPL input) so importers and subsequent inputs never retain
 * another file's {@link TypeChecker}, {@link EffectChecker}, or ANTLR parse tree
 * ({@code docs/design/bip010_type-checker-simplification.md} §4.6, {@code BIP010-D3}).
 */
final class TypedModuleSummary {

    /**
     * AST-free higher-order effect specialization template for an exported or
     * session-committed callable.
     */
    record CallableEffectTemplate(
            EffectChecker.CallableSummary base,
            boolean hasExplicitContract,
            EffectSet residualEffects,
            boolean[] residualMutParams,
            boolean[] residualBorrowParams,
            boolean[] forwardedCallbackParams,
            EffectSet[] defaultCallbackEffects,
            long[][] paramMutFromCallbackArg) {

        EffectChecker.CallableSummary specialize(Type.Function[] boundCallbacks) {
            if (hasExplicitContract || forwardedCallbackParams.length == 0) {
                return base;
            }
            boolean anyProvided = false;
            for (int i = 0; i < forwardedCallbackParams.length && i < boundCallbacks.length; i++) {
                if (forwardedCallbackParams[i] && boundCallbacks[i] != null) {
                    anyProvided = true;
                    break;
                }
            }
            if (!anyProvided) {
                return base;
            }
            EnumSet<Effect> effs = EffectSet.copySet(residualEffects.effects());
            boolean terminating = residualEffects.terminating();
            boolean[] mutParams = residualMutParams.clone();
            boolean[] borrowParams = residualBorrowParams.clone();
            for (int i = 0; i < forwardedCallbackParams.length; i++) {
                if (!forwardedCallbackParams[i]) {
                    continue;
                }
                Type.Function argFn = i < boundCallbacks.length ? boundCallbacks[i] : null;
                EffectSet cbEffects = argFn != null
                        ? argFn.effects()
                        : (i < defaultCallbackEffects.length ? defaultCallbackEffects[i] : null);
                if (cbEffects != null) {
                    effs.retainAll(cbEffects.effects());
                    terminating &= cbEffects.terminating();
                }
                if (argFn != null && paramMutFromCallbackArg != null && i < paramMutFromCallbackArg.length) {
                    long[] argMasks = paramMutFromCallbackArg[i];
                    List<Type.Parameter> cbParams = argFn.parameters();
                    for (int m = 0; m < cbParams.size() && m < argMasks.length; m++) {
                        if (cbParams.get(m).mut()) {
                            long mask = argMasks[m];
                            for (int j = 0; j < mutParams.length; j++) {
                                if ((mask & (1L << j)) != 0) {
                                    mutParams[j] = true;
                                    if (j < borrowParams.length) {
                                        borrowParams[j] = false;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            boolean anyMut = residualEffects.mutThis();
            for (boolean m : mutParams) {
                anyMut |= m;
            }
            if (anyMut) {
                effs.remove(Effect.PURE);
            }
            boolean isIO = !effs.contains(Effect.HERMETIC);
            boolean isVol = !isIO && !effs.contains(Effect.DETERMINISTIC);
            EffectSet combined = new EffectSet(effs, EnumSet.noneOf(Effect.class),
                    residualEffects.mutThis(), residualEffects.borrowThis(), residualEffects.explicitSharedThis(),
                    residualEffects.uniqueResult(), residualEffects.explicitUniqueResult(),
                    terminating, isIO, isVol, false);
            return new EffectChecker.CallableSummary(combined, mutParams, borrowParams, base.uniqueParams(),
                    base.frameReadOwners(), base.frameWriteOwners(), base.deepMutThis(),
                    base.returnParams(), base.returnThis(), base.returnExternal(), base.returnKnown(),
                    base.ctorStoredParams(), base.readParams(), base.readsThis());
        }
    }

    /**
     * What the following inputs of a session read of an input's declarations : the types of its
     * defs and parameters and of its members, its static singletons, and its effects.
     */
    record Session(
            Map<ParserRuleContext, Type> declarationTypes,
            Map<ParserRuleContext, Type> memberTypes,
            Map<ParserRuleContext, Object> staticSingletons,
            Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> javaEffects,
            AxiomData.AxiomTable axioms,
            Map<ParserRuleContext, EffectChecker.CallableSummary> callableSummaries) {
    }

    static final TypedModuleSummary EMPTY = new TypedModuleSummary(
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), AxiomData.AxiomTable.EMPTY, null);

    private final Map<String, Type> exports;
    private final Map<String, Object> staticSingletons;
    private final Map<TypeNode, List<Type.TypeVar>> typeNodeVars;
    private final Map<TypeNode, List<Type>> parentTypes;
    private final Map<TypeNode, Map<String, Type>> memberTypes;
    private final Map<TypeNode, List<Type.Parameter>> constructorParams;
    private final Map<TypeNode, EffectChecker.CallableSummary> constructorSummaries;
    private final Map<Type.Function, CallableEffectTemplate> callableTemplates;
    private final Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects;
    private final AxiomData.AxiomTable exportedAxioms;
    private final Session session;

    TypedModuleSummary(
            Map<String, Type> exports,
            Map<String, Object> staticSingletons,
            Map<TypeNode, List<Type.TypeVar>> typeNodeVars,
            Map<TypeNode, List<Type>> parentTypes,
            Map<TypeNode, Map<String, Type>> memberTypes,
            Map<TypeNode, List<Type.Parameter>> constructorParams,
            Map<TypeNode, EffectChecker.CallableSummary> constructorSummaries,
            Map<Type.Function, CallableEffectTemplate> callableTemplates,
            Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects,
            AxiomData.AxiomTable exportedAxioms,
            Session session) {
        this.session = session;
        this.exports = exports != null ? Map.copyOf(exports) : Map.of();
        this.staticSingletons = staticSingletons != null ? Map.copyOf(staticSingletons) : Map.of();
        this.typeNodeVars = typeNodeVars != null ? typeNodeVars : Map.of();
        this.parentTypes = parentTypes != null ? parentTypes : Map.of();
        this.memberTypes = memberTypes != null ? memberTypes : Map.of();
        this.constructorParams = constructorParams != null ? constructorParams : Map.of();
        this.constructorSummaries = constructorSummaries != null ? constructorSummaries : Map.of();
        this.callableTemplates = callableTemplates != null ? callableTemplates : Map.of();
        this.exportedJavaEffects = exportedJavaEffects != null ? Map.copyOf(exportedJavaEffects) : Map.of();
        this.exportedAxioms = exportedAxioms != null ? exportedAxioms : AxiomData.AxiomTable.EMPTY;
    }

    /**
     * The summary of the file {@code checker} analysed : its exports and static singletons, its
     * classes and interfaces, the effect templates of its callables, and, for a session input,
     * what the following inputs read of its declarations.
     */
    static TypedModuleSummary build(BlockScopes pass, TypeChecker checker, StaticRefs refs) {
        Map<String, Type> exports = new LinkedHashMap<>();
        Map<String, Object> staticSingletons = new LinkedHashMap<>();
        for (Map.Entry<String, ParserRuleContext> entry : pass.fileScope.names.entrySet()) {
            String name = entry.getKey();
            ParserRuleContext decl = entry.getValue();
            Type t = checker.typeOfDeclaration(decl);
            if (t != null) {
                exports.put(name, t);
            }
            if (decl instanceof BabbleParser.DefExpressionContext def && !checker.isReassigned(def)) {
                Object ref = refs.freeze(refs.resolve(def.value, 0));
                if (ref != null) {
                    staticSingletons.put(name, ref);
                }
            }
        }
        Map<TypeNode, List<Type.TypeVar>> typeNodeVars = new IdentityHashMap<>();
        Map<TypeNode, List<Type>> parentTypes = new IdentityHashMap<>();
        Map<TypeNode, Map<String, Type>> memberTypes = new IdentityHashMap<>();
        Map<TypeNode, List<Type.Parameter>> constructorParams = new IdentityHashMap<>();
        Map<TypeNode, EffectChecker.CallableSummary> constructorSummaries = new IdentityHashMap<>();
        for (TypeNode node : pass.types) {
            List<Type.TypeVar> vars = checker.annotator().typeVarsOf(node);
            if (!vars.isEmpty()) {
                typeNodeVars.put(node, vars);
            }
            if (!node.parents.isEmpty()) {
                List<Type> parents = new ArrayList<>(node.parents.size());
                for (int i = 0; i < node.parents.size(); i++) {
                    parents.add(checker.annotator().parentAnnotation(node, i));
                }
                parentTypes.put(node, List.copyOf(parents));
            }
            Map<String, Type> members = new LinkedHashMap<>();
            for (TypeNode.Member m : node.members.values()) {
                Type t = checker.memberType(node, m);
                if (t != null) {
                    members.put(m.name, t);
                }
            }
            for (TypeNode.Member m : node.statics.values()) {
                Type t = checker.memberType(node, m);
                if (t != null) {
                    members.put(m.name, t);
                }
            }
            if (!members.isEmpty()) {
                memberTypes.put(node, Map.copyOf(members));
            }
            if (node.ctx instanceof BabbleParser.ClassExpressionContext) {
                List<Type.Parameter> params = checker.constructorParameters(node);
                if (params != null) {
                    constructorParams.put(node, List.copyOf(params));
                }
                if (checker.effectChecker != null) {
                    EffectChecker.CallableSummary cs = checker.effectChecker.constructorSummary(node);
                    if (cs != null) {
                        constructorSummaries.put(node, cs);
                    }
                }
            }
        }
        Map<Type.Function, TypedModuleSummary.CallableEffectTemplate> callableTemplates = new IdentityHashMap<>();
        if (checker.effectChecker != null) {
            for (Map.Entry<Type.Function, BabbleParser.FunctionLiteralContext> entry : checker.functionLiterals().entrySet()) {
                if (checker.inFile(entry.getValue())) {
                    TypedModuleSummary.CallableEffectTemplate tpl = checker.effectChecker.buildCallableTemplate(entry.getValue(), entry.getKey());
                    if (tpl != null) {
                        callableTemplates.put(entry.getKey(), tpl);
                    }
                }
            }
        }
        return new TypedModuleSummary(
                exports,
                staticSingletons,
                typeNodeVars,
                parentTypes,
                memberTypes,
                constructorParams,
                constructorSummaries,
                callableTemplates,
                checker.exportedJavaEffects(),
                checker.exportedAxioms(),
                pass.session == null ? null : new Session(
                        frozenDeclarations(checker),
                        frozenMembers(pass, checker),
                        refs.frozenSingletons(),
                        checker.effectChecker != null ? checker.effectChecker.committedJavaEffects() : Map.of(),
                        checker.effectChecker != null ? checker.effectChecker.committedAxioms() : AxiomData.AxiomTable.EMPTY,
                        checker.effectChecker != null ? checker.effectChecker.committedCallableSummaries() : Map.of()));
    }

    private static Map<ParserRuleContext, Type> frozenDeclarations(TypeChecker checker) {
        Map<ParserRuleContext, Type> frozen = new HashMap<>();
        for (Map.Entry<ParserRuleContext, Type> entry : checker.declarationTypes().entrySet()) {
            if (checker.inFile(entry.getKey()) && !TypeChecker.isUnresolved(entry.getValue()) && entry.getValue() != Type.NULL) {
                frozen.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<ParserRuleContext, Type> entry : checker.inferredDefs().entrySet()) {
            if (checker.inFile(entry.getKey()) && !TypeChecker.isUnresolved(entry.getValue()) && entry.getValue() != Type.NULL) {
                frozen.put(entry.getKey(), entry.getValue());
            }
        }
        return frozen;
    }

    private static Map<ParserRuleContext, Type> frozenMembers(BlockScopes pass, TypeChecker checker) {
        Map<ParserRuleContext, Type> frozen = new HashMap<>();
        for (TypeNode node : pass.types) {
            for (TypeNode.Member member : node.members.values()) {
                freezeMember(checker, node, member, frozen);
            }
            for (TypeNode.Member member : node.statics.values()) {
                freezeMember(checker, node, member, frozen);
            }
        }
        return frozen;
    }

    private static void freezeMember(TypeChecker checker, TypeNode owner, TypeNode.Member member, Map<ParserRuleContext, Type> frozen) {
        if (checker.inFile(member.declaration)) {
            Type type = checker.memberType(owner, member);
            if (!TypeChecker.isUnresolved(type) && type != Type.NULL) {
                frozen.put(member.declaration, type);
            }
        }
    }

    /** The session part, or null when the file isn't a session input. */
    Session session() {
        return session;
    }

    Type exportType(String name) {
        return exports.get(name);
    }

    Object staticSingleton(String name) {
        return staticSingletons.get(name);
    }

    List<Type.TypeVar> typeVarsOf(TypeNode node) {
        return typeNodeVars.get(node);
    }

    List<Type> parentTypesOf(TypeNode node) {
        return parentTypes.get(node);
    }

    Type memberType(TypeNode node, String name) {
        Map<String, Type> map = memberTypes.get(node);
        return map != null ? map.get(name) : null;
    }

    List<Type.Parameter> constructorParams(TypeNode node) {
        return constructorParams.get(node);
    }

    EffectChecker.CallableSummary constructorSummary(TypeNode node) {
        return constructorSummaries.get(node);
    }

    CallableEffectTemplate callableTemplate(Type.Function fn) {
        return callableTemplates.get(fn);
    }

    Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects() {
        return exportedJavaEffects;
    }

    AxiomData.AxiomTable exportedAxioms() {
        return exportedAxioms;
    }

    Map<TypeNode, List<Type.TypeVar>> typeNodeVarsMap() {
        return typeNodeVars;
    }

    Map<TypeNode, List<Type>> parentTypesMap() {
        return parentTypes;
    }

    Map<TypeNode, Map<String, Type>> memberTypesMap() {
        return memberTypes;
    }

    Map<TypeNode, List<Type.Parameter>> constructorParamsMap() {
        return constructorParams;
    }

    Map<TypeNode, EffectChecker.CallableSummary> constructorSummariesMap() {
        return constructorSummaries;
    }

    Map<Type.Function, CallableEffectTemplate> callableTemplatesMap() {
        return callableTemplates;
    }
}
