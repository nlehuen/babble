package org.babblelang.tests;

import org.antlr.v4.runtime.tree.RuleNode;
import org.babblelang.engine.optimizer.OptimizerBase;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

/**
 * Very basic optimizer, just to check whether tree manipulations are OK with ANTLR 4.
 * WARNING ! This is a very stupid optimizer, since it doesn't check types.
 */
public class SimpleBinaryOpsOptimizer extends OptimizerBase {


    @Override
    public RuleNode visitBinaryOp(BabbleParser.BinaryOpContext ctx) {
        RuleNode result = ctx;
        ctx.left = (BabbleParser.ExpressionContext) visit(ctx.left);
        ctx.right = (BabbleParser.ExpressionContext) visit(ctx.right);

        if (ctx.op.getType() == BabbleLexer.PLUS) {
            if (ctx.left instanceof BabbleParser.IntegerContext && ctx.left.getText().equals("0")) {
                result = replace(ctx, ctx.right);
            } else if (ctx.right instanceof BabbleParser.IntegerContext && ctx.right.getText().equals("0")) {
                result = replace(ctx, ctx.left);
            }
        } else if (ctx.op.getType() == BabbleLexer.MUL) {
            if (ctx.left instanceof BabbleParser.IntegerContext && ctx.left.getText().equals("1")) {
                result = replace(ctx, ctx.right);
            } else if (ctx.right instanceof BabbleParser.IntegerContext && ctx.right.getText().equals("1")) {
                result = replace(ctx, ctx.left);
            }
        }

        return result;
    }
}
