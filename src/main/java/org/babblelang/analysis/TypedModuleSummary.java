package org.babblelang.analysis;

import org.babblelang.effects.Effect;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * AST-detached, immutable type and effect summary published by a finished module
 * (or committed REPL input) so importers and subsequent inputs never retain
 * another file's {@link TypeChecker}, {@link EffectChecker}, or ANTLR parse tree
 * ({@code docs/design/bip010_type-checker-simplification.md} §4.6, {@code BIP010-D3}).
 */
final class TypedModuleSummary {
    /** A static package namespace of {@link TypeNode}s, nested {@link StaticPackageMap}s, or {@link ModuleInterface}s. */
    record StaticPackageMap(Map<String, Object> members) {
    }

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
            EffectSet combined = EffectSet.ofInferred(effs, residualEffects.mutThis(), residualEffects.borrowThis());
            return new EffectChecker.CallableSummary(combined, mutParams, borrowParams);
        }
    }

    static final TypedModuleSummary EMPTY = new TypedModuleSummary(
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    private final Map<String, Type> exports;
    private final Map<String, Object> staticSingletons;
    private final Map<TypeNode, List<Type.TypeVar>> typeNodeVars;
    private final Map<TypeNode, List<Type>> parentTypes;
    private final Map<TypeNode, Map<String, Type>> memberTypes;
    private final Map<TypeNode, List<Type.Parameter>> constructorParams;
    private final Map<TypeNode, EffectChecker.CallableSummary> constructorSummaries;
    private final Map<Type.Function, CallableEffectTemplate> callableTemplates;
    private final Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects;

    TypedModuleSummary(
            Map<String, Type> exports,
            Map<String, Object> staticSingletons,
            Map<TypeNode, List<Type.TypeVar>> typeNodeVars,
            Map<TypeNode, List<Type>> parentTypes,
            Map<TypeNode, Map<String, Type>> memberTypes,
            Map<TypeNode, List<Type.Parameter>> constructorParams,
            Map<TypeNode, EffectChecker.CallableSummary> constructorSummaries,
            Map<Type.Function, CallableEffectTemplate> callableTemplates,
            Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects) {
        this.exports = exports != null ? Map.copyOf(exports) : Map.of();
        this.staticSingletons = staticSingletons != null ? Map.copyOf(staticSingletons) : Map.of();
        this.typeNodeVars = typeNodeVars != null ? typeNodeVars : Map.of();
        this.parentTypes = parentTypes != null ? parentTypes : Map.of();
        this.memberTypes = memberTypes != null ? memberTypes : Map.of();
        this.constructorParams = constructorParams != null ? constructorParams : Map.of();
        this.constructorSummaries = constructorSummaries != null ? constructorSummaries : Map.of();
        this.callableTemplates = callableTemplates != null ? callableTemplates : Map.of();
        this.exportedJavaEffects = exportedJavaEffects != null ? Map.copyOf(exportedJavaEffects) : Map.of();
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
