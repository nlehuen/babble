package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the analysis remembers between the inputs of an interactive session
 * (docs/design/bip004_repl.md), so that an input sees the definitions of the earlier ones : it
 * can implement an interface defined earlier, name an earlier class in a type
 * annotation, and have its calls to earlier functions checked.
 * <p>
 * Each input that defines names is analysed as a scope nested in the previous one, as
 * the session nests their namespaces at runtime. A name defined again shadows the earlier
 * definition for the inputs that follow ; earlier inputs keep referring to what they were
 * analysed against.
 * <p>
 * An input is analysed with {@link BlockScopes#analyze(org.babblelang.parser.BabbleParser.FileContext,
 * ScopeAnalysis)}, then {@link #commit committed} if it is
 * going to run.
 */
public final class AnalysisSession {
    /** The file scope of the last input that defined names : the next input's is nested in it. */
    ScopeNode scope;
    final Map<ParserRuleContext, TypeNode> typeNodes = new HashMap<>();
    final Map<ParserRuleContext, ScopeNode> definitionScopes = new HashMap<>();
    final Map<ParserRuleContext, ScopeNode> scopeNodes = new HashMap<>();
    final Map<ParserRuleContext, ScopeNode> occurrenceScopes = new HashMap<>();
    /** The interfaces of the files earlier inputs imported statically, by import call. */
    final Map<org.babblelang.parser.BabbleParser.CallContext, ModuleInterface> modules = new LinkedHashMap<>();
    /** Frozen inferred declaration types from earlier inputs (§6, Q5). */
    final Map<ParserRuleContext, Type> declarationTypes = new HashMap<>();
    /** Frozen inferred member and static types from earlier inputs (§6, Q5). */
    final Map<ParserRuleContext, Type> memberTypes = new HashMap<>();
    /** Committed BIP019 {@code axiom X: ...} tables from earlier inputs. */
    AxiomData.AxiomTable axioms = AxiomData.AxiomTable.EMPTY;
    /** Converged caller-visible summaries of functions and constructors from earlier inputs. */
    final Map<ParserRuleContext, EffectChecker.CallableSummary> callableSummaries = new HashMap<>();
    /** Detached type-node and HOF effect summaries from earlier inputs and their imports (BIP010-D3). */
    final Map<TypeNode, List<Type.TypeVar>> typeNodeVars = new HashMap<>();
    final Map<TypeNode, List<Type>> parentTypes = new HashMap<>();
    final Map<TypeNode, Map<String, Type>> typeMemberTypes = new HashMap<>();
    final Map<TypeNode, List<Type.Parameter>> constructorParams = new HashMap<>();
    final Map<TypeNode, EffectChecker.CallableSummary> constructorSummaries = new HashMap<>();
    final Map<Type.Function, TypedModuleSummary.CallableEffectTemplate> callableTemplates = new IdentityHashMap<>();
    final Map<ParserRuleContext, Object> staticSingletons = new HashMap<>();
    private final List<ClassLoader> classLoaders = new ArrayList<>();
    private final Map<String, String> interfaceJvmNames = new LinkedHashMap<>();
    private final Map<String, String> classJvmNames = new LinkedHashMap<>();
    private final Map<String, LoweringTargets.TypeHierarchyInfo> typeHierarchies = new LinkedHashMap<>();
    /** Committed input file scopes whose evaluation finished normally (BIP034-D6). */
    private final Set<ScopeNode> cleanScopes = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Makes an analysed input part of the session, for the inputs that follow.
     *
     * @return whether the input opened a scope of its own : it defines names,
     * and must then run in a namespace nested in the previous one
     */
    public boolean commit(ScopeAnalysis analysis) {
        BlockScopes pass = analysis.pass();
        typeNodes.putAll(pass.typeNodes);
        definitionScopes.putAll(pass.definitionScopes);
        scopeNodes.putAll(pass.scopeNodes);
        modules.putAll(pass.modules);
        typeHierarchies.putAll(analysis.hierarchy().typeHierarchies());
        TypedModuleSummary summary = pass.summary;
        if (summary != null) {
            typeNodeVars.putAll(summary.typeNodeVarsMap());
            parentTypes.putAll(summary.parentTypesMap());
            typeMemberTypes.putAll(summary.memberTypesMap());
            constructorParams.putAll(summary.constructorParamsMap());
            constructorSummaries.putAll(summary.constructorSummariesMap());
            callableTemplates.putAll(summary.callableTemplatesMap());
            TypedModuleSummary.Session input = summary.session();
            if (input != null) {
                staticSingletons.putAll(input.staticSingletons());
                declarationTypes.putAll(input.declarationTypes());
                memberTypes.putAll(input.memberTypes());
                if (input.axioms() != null && !input.axioms().isEmpty()) {
                    axioms = axioms.mergeOver(input.axioms());
                }
                callableSummaries.putAll(input.callableSummaries());
            }
        }
        for (BlockScopes.Occurrence occurrence : pass.occurrences) {
            occurrenceScopes.put(occurrence.ctx(), occurrence.scope());
        }
        ScopeNode file = pass.fileScope;
        if (file.names.isEmpty()) {
            // Nothing the following inputs could see : they stay nested in the same scope.
            return false;
        }
        scope = file;
        return true;
    }

    /** Records that the committed input completed runtime evaluation without throwing (BIP034-D6). */
    public void markScopeClean(ScopeAnalysis analysis) {
        if (analysis != null && analysis.pass() != null && analysis.pass().fileScope != null) {
            cleanScopes.add(analysis.pass().fileScope);
        }
    }

    boolean isCleanScope(ScopeNode scope) {
        return scope != null && cleanScopes.contains(scope);
    }

    public void recordCompilation(ClassLoader loader, Map<String, String> interfaceJvmNames) {
        recordCompilation(loader, interfaceJvmNames, Map.of());
    }

    public void recordCompilation(ClassLoader loader, Map<String, String> interfaceJvmNames, Map<String, String> classJvmNames) {
        if (loader != null) {
            boolean seen = false;
            for (ClassLoader existing : classLoaders) {
                if (existing == loader) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                classLoaders.add(loader);
            }
        }
        if (interfaceJvmNames != null && !interfaceJvmNames.isEmpty()) {
            this.interfaceJvmNames.putAll(interfaceJvmNames);
        }
        if (classJvmNames != null && !classJvmNames.isEmpty()) {
            this.classJvmNames.putAll(classJvmNames);
        }
    }

    public List<ClassLoader> classLoaders() {
        return List.copyOf(classLoaders);
    }

    public Map<String, String> interfaceJvmNames() {
        return Map.copyOf(interfaceJvmNames);
    }

    public Map<String, String> classJvmNames() {
        return Map.copyOf(classJvmNames);
    }

    public LoweringTargets.TypeHierarchyInfo typeHierarchy(String id) {
        return id != null ? typeHierarchies.get(id) : null;
    }
}
