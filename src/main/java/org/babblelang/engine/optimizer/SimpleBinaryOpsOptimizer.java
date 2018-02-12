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
            case BabbleLexer.PLUS:
                if (ctx.left instanceof BabbleParser.IntegerContext && ctx.left.getText().equals("0")) {
                    result = replace(ctx, ctx.right);
                } else if (ctx.right instanceof BabbleParser.IntegerContext && ctx.right.getText().equals("0")) {
                    result = replace(ctx, ctx.left);
                }
                break;

            case BabbleLexer.MINUS:
                if (ctx.right instanceof BabbleParser.IntegerContext && ctx.right.getText().equals("0")) {
                    result = replace(ctx, ctx.left);
                }
                break;

            case BabbleLexer.MUL:
                if (ctx.left instanceof BabbleParser.IntegerContext) {
                    if (ctx.left.getText().equals("1")) {
                        result = replace(ctx, ctx.right);
                        break;
                    } else if (ctx.left.getText().equals("0")) {
                        result = replace(ctx, ctx.left);
                        break;
                    }
                }
                if (ctx.right instanceof BabbleParser.IntegerContext) {
                    if (ctx.right.getText().equals("1")) {
                        result = replace(ctx, ctx.left);
                    } else if (ctx.right.getText().equals("0")) {
                        result = replace(ctx, ctx.right);
                    }
                }
                break;

            case BabbleLexer.DIV:
                if (ctx.right instanceof BabbleParser.IntegerContext && ctx.right.getText().equals("1")) {
                    result = replace(ctx, ctx.left);
                }
                break;
        }
        return result;
    }

    @Override
    public RuleNode visitBlockExpression(BabbleParser.BlockExpressionContext ctx) {
        RuleNode result = super.visitBlockExpression(ctx);
        if (!(result instanceof BabbleParser.BlockExpressionContext)) {
            return result;
        }
        ctx = (BabbleParser.BlockExpressionContext) result;
        if (ctx.block().expression().size() == 1) {
            return replace(ctx, ctx.block().expression(0));
        }
        return ctx;
    }

    @Override
    public RuleNode visitBooleanNot(BabbleParser.BooleanNotContext ctx) {
        RuleNode result = super.visitBooleanNot(ctx);
        if (!(result instanceof BabbleParser.BooleanNotContext)) {
            return result;
        }
        ctx = (BabbleParser.BooleanNotContext) result;
        BabbleParser.ExpressionContext child =
                ctx.expression();
        if (child instanceof BabbleParser.BooleanNotContext) {
            return replace(ctx, ((BabbleParser.BooleanNotContext) ctx.expression()).expression());
        }
        // TODO: replace "not false" with "true", and "not true" with "false".
        return ctx;
    }
}
