package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

/** A lexical scope, as the analysis sees it. */
final class ScopeNode {
    enum Kind {
        FILE,
        FUNCTION,
        /** A class or object body : the constructor's scope. Its defs are members. */
        CLASS_BODY,
        /** A parameterless {@code init (...)} block inside a class, record, or object body. */
        INIT_BLOCK,
        /** A parameterized {@code init (params) -> (...)} constructor scope ({@code BIP040}). */
        INIT_BODY,
        /** An interface body : its defs are required members or default methods. */
        INTERFACE_BODY,
        /** A static block : its defs are static members of the class or interface value. */
        STATIC_BODY,
        BLOCK
    }

    final Kind kind;
    final ScopeNode parent;
    final ParserRuleContext owner;

    /**
     * Lexical names, in declaration order : DefExpressionContext or ParameterDeclarationContext.
     */
    final Map<String, ParserRuleContext> names = new LinkedHashMap<>();

    /**
     * Scoped Babble-name axioms ({@code axiom foo: fn ...}) applying to this scope and enclosed
     * scopes (BIP019 §2.3).
     */
    final Map<String, BabbleParser.AxiomExpressionContext> babbleAxioms = new LinkedHashMap<>();

    /**
     * Scoped WebAssembly host import axioms ({@code axiom wasm.<mod>.<name>: fn ...}) ({@code
     * BIP025-D9}).
     */
    final Map<String, Map<String, BabbleParser.AxiomExpressionContext>> wasmAxioms =
            new LinkedHashMap<>();

    /** The names defined here, declared when the scope is entered (hoisting). */
    final List<String> declared = new ArrayList<>();

    /**
     * Parallel finality flags for {@link #declared} ({@code true} for {@code def}, {@code false}
     * for {@code var}).
     */
    final List<Boolean> declaredFinals = new ArrayList<>();

    /** For class, interface and static bodies : the type. */
    final TypeNode type;

    /** For a function scope : the type when the function is a method, default or static method. */
    TypeNode memberOf;

    boolean staticMethod;
    int whileTests = 0;

    ScopeNode(Kind kind, ScopeNode parent, ParserRuleContext owner, TypeNode type) {
        this.kind = kind;
        this.parent = parent;
        this.owner = owner;
        this.type = type;
    }
}
