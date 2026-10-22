package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the compiler needs to resolve names statically (compiler phase 2) : the variable
 * each name refers to, how each scope is represented, and what each method captures.
 * <p>
 * A scope is either a <em>frame</em> scope, whose variables are JVM locals of the method
 * it is compiled into, or a <em>namespace</em> scope, whose names stay in a runtime
 * {@code Namespace} as in phase 1 : the file, packages, interface bodies, and any
 * function, class body or static block that declares a package somewhere in it, since a
 * package needs a namespace to live in.
 * <p>
 * The methods of the generated class are the file's {@code run}, one per function
 * literal, and one per class or object body (its constructor). Everything else is
 * compiled inline into the method that encloses it.
 */
public final class Resolution {
    /** A variable of a frame scope. */
    public static final class Variable {
        public enum Kind {
            PARAMETER, DEFINITION, THIS, RECURSE
        }

        private final String name;
        private final Kind kind;
        private final ParserRuleContext method;
        private final ParserRuleContext declaration;
        private boolean cell;
        private boolean used;

        Variable(String name, Kind kind, ParserRuleContext method, ParserRuleContext declaration) {
            this.name = name;
            this.kind = kind;
            this.method = method;
            this.declaration = declaration;
        }

        /** The def or parameter declaration, or null for this and recurse. */
        ParserRuleContext declaration() {
            return declaration;
        }

        public String name() {
            return name;
        }

        public Kind kind() {
            return kind;
        }

        /** The function literal, class or object expression, or file whose method holds the variable. */
        public ParserRuleContext method() {
            return method;
        }

        /** Whether a nested method captures it : it then lives in a {@code Cell}. */
        public boolean isCell() {
            return cell;
        }

        /** Whether anything reads or assigns it. */
        public boolean isUsed() {
            return used;
        }

        /**
         * Hoisted definitions exist before their def has run. So does the cell of a captured
         * parameter, created before any default value runs, so that a function literal in an
         * earlier default can capture it. This and recurse are always defined.
         */
        public boolean mayBeUndefined() {
            return kind == Kind.DEFINITION || kind == Kind.PARAMETER && cell;
        }

        void markCell() {
            cell = true;
        }

        void markUsed() {
            used = true;
        }

        @Override
        public String toString() {
            return name + (cell ? " (cell)" : "");
        }
    }

    final Map<ParserRuleContext, Variable> bindings = new HashMap<>();
    final Map<ParserRuleContext, List<Variable>> declared = new HashMap<>();
    final Map<ParserRuleContext, LinkedHashSet<Variable>> captured = new HashMap<>();
    final Set<ParserRuleContext> namespaceScopes = new HashSet<>();

    Resolution() {
    }

    /**
     * The variable a name occurrence refers to : a plain name, an unqualified assignment,
     * {@code this}, {@code recurse}, a lexical def, a parameter declaration, the first name
     * of a type in an implements list, or a super call's interface. Null when the name is
     * resolved dynamically, in a namespace.
     */
    public Variable variable(ParserRuleContext occurrence) {
        return bindings.get(occurrence);
    }

    /** The variables a frame scope declares, to create when it is entered. */
    public List<Variable> declared(ParserRuleContext scopeOwner) {
        return declared.getOrDefault(scopeOwner, List.of());
    }

    /** Whether a scope keeps a runtime namespace rather than JVM locals. */
    public boolean isNamespaceScope(ParserRuleContext scopeOwner) {
        return namespaceScopes.contains(scopeOwner);
    }

    /**
     * The variables of enclosing methods that a method uses, directly or through methods
     * nested in it, in a fixed order : the index of each is its place in the method's
     * captured array.
     */
    public List<Variable> captured(ParserRuleContext method) {
        LinkedHashSet<Variable> variables = captured.get(method);
        return variables == null ? List.of() : List.copyOf(variables);
    }
}
