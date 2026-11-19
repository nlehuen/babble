package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Binds every name to its declaration at compile time (compiler phase 2, §7.1 of the
 * design). It runs after {@link BlockScopes} and {@link TypeChecks}, on the scope tree
 * they built, and its result is only used by the compiler : the interpreter keeps its
 * namespaces.
 */
final class Resolver {
    /** Scopes compiled into a method of their own. */
    private static final Set<ScopeNode.Kind> METHODS = EnumSet.of(ScopeNode.Kind.FILE, ScopeNode.Kind.FUNCTION, ScopeNode.Kind.CLASS_BODY);
    private final BlockScopes pass;
    private final Resolution resolution = new Resolution();
    private final Map<ScopeNode, Map<String, Resolution.Variable>> variables = new LinkedHashMap<>();

    Resolver(BlockScopes pass) {
        this.pass = pass;
    }

    Resolution run() {
        for (ScopeNode scope : pass.scopeNodes.values()) {
            if (isNamespace(scope)) {
                resolution.namespaceScopes.add(scope.owner);
            } else {
                declareVariables(scope);
            }
        }
        for (BlockScopes.Occurrence occurrence : pass.occurrences) {
            bind(occurrence.ctx(), occurrence.name(), occurrence.scope());
        }
        // Declarations bind to their own variable.
        for (Map.Entry<ParserRuleContext, ScopeNode> definition : pass.definitionScopes.entrySet()) {
            Map<String, Resolution.Variable> declaredHere = variables.get(definition.getValue());
            if (declaredHere != null) {
                String name = definition.getKey() instanceof BabbleParser.DefExpressionContext def
                        ? def.name.getText() : ((BabbleParser.ParameterDeclarationContext) definition.getKey()).ID().getText();
                resolution.bindings.put(definition.getKey(), declaredHere.get(name));
            }
        }
        // Types in implements lists, and super calls' interfaces : their first name.
        for (TypeNode type : pass.types) {
            for (BabbleParser.TypeContext parentType : type.parentTypes) {
                bind(parentType, firstName(parentType), type.scope);
            }
        }
        for (BlockScopes.PendingSuper pending : pass.supers) {
            BabbleParser.TypeContext target = pass.analysis.superTarget(pending.ctx());
            bind(pending.ctx(), firstName(target), pending.scope());
            bind(pending.ctx().getParent(), "this", pending.scope());
        }
        return resolution;
    }

    private static String firstName(BabbleParser.TypeContext type) {
        return ScopeAnalysis.simpleTypePath(ScopeAnalysis.simpleType(type)).get(0);
    }

    // ---------------------------------------------------------------- representation

    private static ScopeNode method(ScopeNode scope) {
        ScopeNode s = scope;
        while (!METHODS.contains(s.kind)) {
            s = s.parent;
        }
        return s;
    }

    private boolean isNamespace(ScopeNode scope) {
        return scope.kind == ScopeNode.Kind.FILE || scope.kind == ScopeNode.Kind.INTERFACE_BODY;
    }

    private void declareVariables(ScopeNode scope) {
        ParserRuleContext method = method(scope).owner;
        Map<String, Resolution.Variable> declaredHere = new LinkedHashMap<>();
        for (Map.Entry<String, ParserRuleContext> name : scope.names.entrySet()) {
            Resolution.Variable.Kind kind = name.getValue() instanceof BabbleParser.DefExpressionContext
                    ? Resolution.Variable.Kind.DEFINITION : Resolution.Variable.Kind.PARAMETER;
            declaredHere.put(name.getKey(), new Resolution.Variable(name.getKey(), kind, method, name.getValue()));
        }
        boolean hasThis = scope.kind == ScopeNode.Kind.CLASS_BODY || scope.kind == ScopeNode.Kind.STATIC_BODY
                || scope.kind == ScopeNode.Kind.FUNCTION && scope.memberOf != null;
        if (hasThis) {
            declaredHere.put("this", new Resolution.Variable("this", Resolution.Variable.Kind.THIS, method, null));
        }
        if (scope.kind == ScopeNode.Kind.FUNCTION) {
            declaredHere.put("$recurse", new Resolution.Variable("$recurse", Resolution.Variable.Kind.RECURSE, method, null));
        }
        variables.put(scope, declaredHere);
        resolution.declared.put(scope.owner, new ArrayList<>(declaredHere.values()));
    }

    // ---------------------------------------------------------------- binding

    /**
     * Finds the declaration a name refers to from a scope. A name found in a namespace
     * scope, or nowhere, is resolved at runtime, through the namespace chain : the same
     * namespace a static walk would find, since frame scopes in between declare nothing in
     * any namespace.
     */
    private void bind(ParserRuleContext occurrence, String name, ScopeNode from) {
        for (ScopeNode s = from; s != null; s = s.parent) {
            Map<String, Resolution.Variable> declaredHere = variables.get(s);
            if (declaredHere != null) {
                Resolution.Variable variable = declaredHere.get(name);
                if (variable != null && !notBoundYet(occurrence, s, variable)) {
                    use(variable, from);
                    resolution.bindings.put(occurrence, variable);
                    return;
                }
            } else if (s.names.containsKey(name)
                    || name.equals("this") && (s.kind == ScopeNode.Kind.CLASS_BODY || s.kind == ScopeNode.Kind.STATIC_BODY
                    || s.kind == ScopeNode.Kind.FUNCTION && s.memberOf != null)
                    || name.equals("$recurse") && s.kind == ScopeNode.Kind.FUNCTION) {
                return;
            }
        }
    }

    /**
     * Parameters are bound in order, and a default value is evaluated before the
     * parameters from its own onwards are bound : those are not visible to it yet, and the
     * name refers to whatever an outer scope declares, as in the interpreter. A function
     * literal in the default runs later, and sees them.
     */
    private static boolean notBoundYet(ParserRuleContext occurrence, ScopeNode scope, Resolution.Variable variable) {
        if (variable.kind() != Resolution.Variable.Kind.PARAMETER) {
            return false;
        }
        for (ParserRuleContext p = occurrence; p != null && p != scope.owner; p = p.getParent()) {
            if (p instanceof BabbleParser.FunctionLiteralContext) {
                return false;
            }
            if (p instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.getParent().getParent() == scope.owner) {
                List<BabbleParser.ParameterDeclarationContext> parameters =
                        ((BabbleParser.ParametersDeclarationContext) parameter.getParent()).parameterDeclaration();
                return parameters.indexOf(parameter) <= parameters.indexOf(variable.declaration());
            }
        }
        return false;
    }

    /**
     * A variable used from another method than its own is captured : it lives in a cell,
     * and every method between the use and the declaration carries it.
     */
    private void use(Resolution.Variable variable, ScopeNode from) {
        variable.markUsed();
        ScopeNode method = method(from);
        while (method.owner != variable.method()) {
            variable.markCell();
            resolution.captured.computeIfAbsent(method.owner, m -> new LinkedHashSet<>()).add(variable);
            method = method(method.parent);
        }
    }
}
