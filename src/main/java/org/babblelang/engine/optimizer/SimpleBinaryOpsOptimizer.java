package org.babblelang.engine.optimizer;

import org.antlr.v4.runtime.tree.RuleNode;
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

        switch (ctx.op.getType()) {
            case BabbleLexer.PLUS -> {
                if (isIntegerLiteral(ctx.left, "0")) {
                    result = replace(ctx, ctx.right);
                } else if (isIntegerLiteral(ctx.right, "0")) {
                    result = replace(ctx, ctx.left);
                }
            }

            case BabbleLexer.MINUS -> {
                if (isIntegerLiteral(ctx.right, "0")) {
                    result = replace(ctx, ctx.left);
                }
            }

            // A left operand that is an integer other than 0 or 1 falls through to the
            // right-hand checks, so these have to stay a single else-if chain.
            case BabbleLexer.MUL -> {
                if (isIntegerLiteral(ctx.left, "1")) {
                    result = replace(ctx, ctx.right);
                } else if (isIntegerLiteral(ctx.left, "0")) {
                    result = replace(ctx, ctx.left);
                } else if (isIntegerLiteral(ctx.right, "1")) {
                    result = replace(ctx, ctx.left);
                } else if (isIntegerLiteral(ctx.right, "0")) {
                    result = replace(ctx, ctx.right);
                }
            }

            case BabbleLexer.DIV -> {
                if (isIntegerLiteral(ctx.right, "1")) {
                    result = replace(ctx, ctx.left);
                }
            }

            default -> {
                // not a simplifiable operator
            }
        }
        return result;
    }

    private static boolean isIntegerLiteral(BabbleParser.ExpressionContext ctx, String text) {
        return ctx instanceof BabbleParser.IntegerContext && ctx.getText().equals(text);
    }

    @Override
    public RuleNode visitBlockExpression(BabbleParser.BlockExpressionContext ctx) {
        RuleNode result = super.visitBlockExpression(ctx);
        if (!(result instanceof BabbleParser.BlockExpressionContext block)) {
            return result;
        }
        if (block.block().expression().size() == 1) {
            return replace(block, block.block().expression(0));
        }
        return block;
    }

    @Override
    public RuleNode visitBooleanNot(BabbleParser.BooleanNotContext ctx) {
        RuleNode result = super.visitBooleanNot(ctx);
        if (!(result instanceof BabbleParser.BooleanNotContext not)) {
            return result;
        }
        if (not.expression() instanceof BabbleParser.BooleanNotContext inner) {
            return replace(not, inner.expression());
        }
        // TODO: replace "not false" with "true", and "not true" with "false".
        return not;
    }
}
