package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the analysis remembers between the inputs of an interactive session
 * (docs/design/repl.md), so that an input sees the definitions of the earlier ones : it
 * can implement an interface defined earlier, name an earlier class in a type
 * annotation, and have its calls to earlier functions checked.
 * <p>
 * Each input that defines names is analysed as a scope nested in the previous one, as
 * the session nests their namespaces at runtime. A name defined again shadows the earlier
 * definition for the inputs that follow ; earlier inputs keep referring to what they were
 * analysed against.
 * <p>
 * An input is analysed with {@link BlockScopes#analyze(org.babblelang.parser.BabbleParser.FileContext,
 * ScopeAnalysis.Typecheck, AnalysisSession)}, then {@link #commit committed} if it is
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
    final Map<org.babblelang.parser.BabbleParser.CallContext, ModuleInterface> modules = new HashMap<>();
    /** The definitions some input assigned : nothing static is known of them any more. */
    final Set<ParserRuleContext> reassigned = new HashSet<>();

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
}
