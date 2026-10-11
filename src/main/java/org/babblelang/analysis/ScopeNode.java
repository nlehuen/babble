package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A lexical scope, as the analysis sees it.
 */
final class ScopeNode {
    enum Kind {
        FILE, FUNCTION, PACKAGE,
        /** A class or object body : the constructor's scope. Its defs are members. */
        CLASS_BODY,
        /** An interface body : its defs are required members or default methods. */
        INTERFACE_BODY,
        /** A static block : its defs are static members of the class or interface value. */
        STATIC_BODY,
        BLOCK
    }

    final Kind kind;
    final ScopeNode parent;
    final ParserRuleContext owner;
    /** Lexical names : DefExpressionContext or ParameterDeclarationContext. */
    final Map<String, ParserRuleContext> names = new HashMap<>();
    final Map<String, List<BabbleParser.PackageExpressionContext>> packages = new HashMap<>();
    /** The names defined here, declared when the scope is entered (hoisting). */
    final List<String> declared = new ArrayList<>();
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
