package org.babblelang.tests;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

/**
 * Replaces all integers by 0.
 */
public class StupidOptimizer extends BabbleBaseVisitor<Object> {
    @Override
    public Object visitInteger(BabbleParser.IntegerContext ctx) {
        ctx.children.clear();
        ctx.children.add(new TerminalNodeImpl(new CommonToken(BabbleLexer.INT, "0")));
        return super.visitInteger(ctx);
    }
}
