package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    /** The definitions some input assigned : nothing static is known of them any more. */
    final Set<ParserRuleContext> reassigned = new HashSet<>();
    /** Frozen inferred declaration types from earlier inputs (§6, Q5). */
    final Map<ParserRuleContext, Type> declarationTypes = new HashMap<>();
    /** Frozen inferred member and static types from earlier inputs (§6, Q5). */
    final Map<ParserRuleContext, Type> memberTypes = new HashMap<>();
    /** Committed top-level {@code axiom} and {@code impure} Java overrides from earlier inputs. */
    final Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> javaEffects = new HashMap<>();
    /** Converged caller-visible summaries of functions and constructors from earlier inputs. */
    final Map<ParserRuleContext, EffectChecker.CallableSummary> callableSummaries = new HashMap<>();
    /** Checkers of prior inputs and their imported modules by FileContext. */
    final Map<ParserRuleContext, TypeChecker> owners = new HashMap<>();
    private final List<ClassLoader> classLoaders = new ArrayList<>();
    private final Map<String, String> interfaceJvmNames = new LinkedHashMap<>();
    private final Map<String, String> classJvmNames = new LinkedHashMap<>();
    private final Map<String, ScopeAnalysis.TypeHierarchyInfo> typeHierarchies = new LinkedHashMap<>();

    /**
     * Makes an analysed input part of the session, for the inputs that follow.
     *
     * @return whether the input opened a scope of its own : it defines names or
     * packages, and must then run in a namespace nested in the previous one
     */
    public boolean commit(ScopeAnalysis analysis) {
        BlockScopes pass = analysis.pass();
        typeNodes.putAll(pass.typeNodes);
        definitionScopes.putAll(pass.definitionScopes);
        scopeNodes.putAll(pass.scopeNodes);
        modules.putAll(pass.modules);
        typeHierarchies.putAll(analysis.typeHierarchies());
        if (pass.checker != null) {
            if (pass.checker.file() != null) {
                owners.put(pass.checker.file(), pass.checker);
            }
            owners.putAll(pass.checker.ownersMap());
            declarationTypes.putAll(pass.checker.frozenDeclarations());
            memberTypes.putAll(pass.checker.frozenMembers());
            javaEffects.putAll(pass.checker.committedJavaEffects());
            callableSummaries.putAll(pass.checker.committedCallableSummaries());
        }
        for (BlockScopes.Occurrence occurrence : pass.occurrences) {
            occurrenceScopes.put(occurrence.ctx(), occurrence.scope());
        }
        for (BlockScopes.PendingAssignment assignment : pass.assignments) {
            ParserRuleContext definition = TypeChecks.resolveName(assignment.name(), assignment.scope());
            if (definition != null) {
                reassigned.add(definition);
            }
        }
        ScopeNode file = pass.fileScope;
        if (file.names.isEmpty() && pass.packages.isEmpty()) {
            // Nothing the following inputs could see : they stay nested in the same scope.
            return false;
        }
        scope = file;
        return true;
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

    public ScopeAnalysis.TypeHierarchyInfo typeHierarchy(String id) {
        return id != null ? typeHierarchies.get(id) : null;
    }
}
