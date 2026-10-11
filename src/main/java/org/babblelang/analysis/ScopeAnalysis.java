package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.babblelang.parser.BabbleParser;

/**
 * What {@link BlockScopes} computes for a file : which constructs open a scope, and the
 * names each scope defines.
 */
public final class ScopeAnalysis {
    private static final String[] NONE = new String[0];

    private final ParseTreeProperty<String[]> declared = new ParseTreeProperty<>();
    private final ParseTreeProperty<String[]> parameters = new ParseTreeProperty<>();

    void setDeclared(ParserRuleContext owner, String[] names) {
        declared.put(owner, names);
    }

    void setParameters(BabbleParser.FunctionLiteralContext function, String[] names) {
        parameters.put(function, names);
    }

    /**
     * The names defined in the scope that {@code owner} opens, which is the file, a
     * function literal, a package, an object, or a block. Parameters are not included.
     * Empty for a construct that opens no scope of its own.
     */
    public String[] declared(ParserRuleContext owner) {
        String[] names = declared.get(owner);
        return names != null ? names : NONE;
    }

    /**
     * Whether a block needs a namespace of its own : it is a scope, rather than the body
     * of a function, package or object, and it defines names. A block that defines
     * nothing behaves the same without one.
     */
    public boolean opensScope(BabbleParser.BlockContext block) {
        String[] names = declared.get(block);
        return names != null && names.length > 0;
    }

    public String[] parameters(BabbleParser.FunctionLiteralContext function) {
        return parameters.get(function);
    }

    /**
     * The name a function literal is defined with, for display, or null if it is
     * anonymous.
     */
    public static String functionName(BabbleParser.FunctionLiteralContext function) {
        if (function.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == function) {
            return def.name.getText();
        }
        return null;
    }
}
