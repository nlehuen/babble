package org.babblelang.tests;

import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.babblelang.engine.optimizer.OptimizerBase;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

/**
 * Replaces all integers by 0, and minus operators by plus.
 */
public class StupidOptimizer extends OptimizerBase {
    @Override
    public Object visitInteger(BabbleParser.IntegerContext ctx) {
        ctx.children.set(0, new TerminalNodeImpl(new CommonToken(BabbleLexer.INT, "0")));
        return super.visitInteger(ctx);
    }

    @Override
    public Object visitBinaryOp(BabbleParser.BinaryOpContext ctx) {
        if (ctx.op.getType() == BabbleLexer.MINUS) {
            ctx.op = new CommonToken(BabbleLexer.PLUS, "+");
            ctx.children.set(1, new TerminalNodeImpl(ctx.op));
        }
        return super.visitBinaryOp(ctx);
    }
}
