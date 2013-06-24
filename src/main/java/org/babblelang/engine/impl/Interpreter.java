package org.babblelang.engine.impl;

import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

public class Interpreter extends BabbleBaseVisitor<Object> {
    private Scope scope;

    public Interpreter(Scope root) {
        this.scope = root;
    }

    @Override
    public Object visitFile(BabbleParser.FileContext ctx) {
        Object result = null;
        for (BabbleParser.ExpressionContext statement : ctx.expression()) {
            result = visit(statement);
        }
        return result;
    }

    @Override
    public Object visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
        scope = scope.enter(ctx.name.getText());
        visit(ctx.packageBlock);
        scope = scope.leave();
        return null;
    }

    @Override
    public Object visitObjectExpression(BabbleParser.ObjectExpressionContext ctx) {
        Scope object = scope = scope.enter(null);
        visit(ctx.createBlock);
        scope = scope.leave();
        return object;
    }

    @Override
    public Object visitDefExpression(BabbleParser.DefExpressionContext ctx) {
        String id = ctx.name.getText();
        Object value = null;
        if (ctx.expression() != null) {
            value = visit(ctx.expression());
        }
        scope.define(id, value);

        return value;
    }

    @Override
    public Object visitBlock(BabbleParser.BlockContext ctx) {
        Object result = null;
        for (BabbleParser.ExpressionContext statement : ctx.expression()) {
            result = visit(statement);
        }
        return result;
    }

    @Override
    public Object visitSelector(BabbleParser.SelectorContext ctx) {
        Scope base = scope;
        String name = ctx.ID().getText();
        BabbleParser.ExpressionContext expression = ctx.expression();
        if (expression != null) {
            base = (Scope) visit(expression);
        }
        return base.get(name);
    }

    @Override
    public Object visitNull(BabbleParser.NullContext ctx) {
        return null;
    }

    @Override
    public Object visitBoolean(BabbleParser.BooleanContext ctx) {
        return Boolean.parseBoolean(ctx.getText());
    }

    @Override
    public Object visitId(BabbleParser.IdContext ctx) {
        return scope.get(ctx.getText());
    }

    @Override
    public Object visitBinaryOp(BabbleParser.BinaryOpContext ctx) {
        Object a = visit(ctx.left);
        Object b = visit(ctx.right);

        switch (ctx.op.getType()) {
            case BabbleLexer.MUL:
                // TODO : derive operand types
                if (a instanceof Integer && b instanceof Integer) {
                    return (Integer) a * (Integer) b;
                } else {
                    return number(a, ctx.left).doubleValue() * number(b, ctx.right).doubleValue();
                }

            case BabbleLexer.DIV:
                return number(a, ctx.left).doubleValue() / number(b, ctx.right).doubleValue();

            case BabbleLexer.PLUS:
                if (a instanceof String) {
                    return (String) a + b;
                } else if (a instanceof Integer && b instanceof Integer) {
                    return (Integer) a + (Integer) b;
                } else {
                    return number(a, ctx.left).doubleValue() + number(b, ctx.right).doubleValue();
                }

            case BabbleLexer.MINUS:
                if (a instanceof Integer && b instanceof Integer) {
                    return (Integer) a - (Integer) b;
                } else {
                    return number(a, ctx.left).doubleValue() - number(b, ctx.right).doubleValue();
                }

            case BabbleLexer.LT:
                return comparable(a, ctx.left).compareTo(comparable(b, ctx.right)) < 0;

            case BabbleLexer.LTE:
                return comparable(a, ctx.left).compareTo(comparable(b, ctx.right)) <= 0;

            case BabbleLexer.EQ:
                if (a instanceof Comparable) {
                    return comparable(a, ctx.left).compareTo(comparable(b, ctx.right)) == 0;
                } else {
                    return a == b;
                }

            case BabbleLexer.GTE:
                return comparable(a, ctx.left).compareTo(comparable(b, ctx.right)) >= 0;

            case BabbleLexer.GT:
                return comparable(a, ctx.left).compareTo(comparable(b, ctx.right)) > 0;

            default:
                throw new UnsupportedOperationException("Bad op : " + ctx.op.getText());
        }
    }

    private Number number(Object a, BabbleParser.ExpressionContext expr) {
        if (a instanceof Number) {
            return (Number) a;
        } else {
            throw new RuntimeException("Line " + expr.getStart().getLine() + ", not a number : " + expr.getText());
        }
    }

    private Comparable comparable(Object a, BabbleParser.ExpressionContext expr) {
        if (a instanceof Comparable) {
            return (Comparable) a;
        } else {
            throw new RuntimeException("Line " + expr.getStart().getLine() + ", not comparable : " + expr.getText());
        }
    }

    @Override
    public Object visitBooleanOp(BabbleParser.BooleanOpContext ctx) {
        int op = ctx.op.getType();

        boolean a = truth(visit(ctx.left));
        if (a) {
            if (op == BabbleLexer.OR) {
                return true;
            } else {
                return truth(visit(ctx.right));
            }
        } else {
            if (op == BabbleLexer.OR) {
                return truth(visit(ctx.right));
            } else {
                return false;
            }
        }
    }

    @Override
    public Object visitBooleanNot(BabbleParser.BooleanNotContext ctx) {
        return !truth(visit(ctx.expression()));
    }

    public boolean truth(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0;
        } else {
            return value != null;
        }
    }

    @Override
    public Object visitIfExpression(BabbleParser.IfExpressionContext ctx) {
        if (truth(visit(ctx.test))) {
            return visit(ctx.thenBlock);
        } else {
            if (ctx.elseBlock != null) {
                return visit(ctx.elseBlock);
            } else {
                return null;
            }
        }
    }

    @Override
    public Object visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
        Object result = null;
        while (truth(visit(ctx.test))) {
            result = visit(ctx.whileBlock);
        }
        return result;
    }

    @Override
    public Object visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
        Object value = visit(ctx.value);
        scope.assign(ctx.name.getText(), value);
        return value;
    }

    @Override
    public Object visitInteger(BabbleParser.IntegerContext ctx) {
        return Integer.parseInt(ctx.getText());
    }

    @Override
    public Object visitDouble(BabbleParser.DoubleContext ctx) {
        return Double.parseDouble(ctx.getText());
    }

    @Override
    public Object visitString(BabbleParser.StringContext ctx) {
        String literal = ctx.STRING().getText();
        literal = literal.substring(1, literal.length() - 1);
        literal = literal.replace("\\\\", "\\").replace("\\\"", "\"");
        return literal;
    }

    @Override
    public Object visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
        return new Function(ctx, scope);
    }

    @Override
    public Object visitCall(BabbleParser.CallContext ctx) {
        Object expr = visit(ctx.expression());
        if (!(expr instanceof Callable)) {
            throw new RuntimeException(ctx.expression().getText() + " is not callable");
        }
        Callable callable = (Callable) expr;
        Callable.Parameters params = (Callable.Parameters) visit(ctx.callParameters());
        Scope beforeCall = scope;
        scope = callable.bindParameters(this, ctx, scope, params);
        Object result = callable.call(this, ctx, scope);
        scope = beforeCall;
        return result;
    }

    @Override
    public Object visitCallParameters(BabbleParser.CallParametersContext ctx) {
        int count = 0;
        Callable.Parameters params = new Callable.Parameters();
        for (BabbleParser.CallParameterContext cp : ctx.callParameter()) {
            String name = "$" + (count++);
            if (cp.ID() != null) {
                name = cp.ID().getText();
            }
            Object value = visit(cp.expression());
            params.put(name, value);
        }
        return params;
    }

    @Override
    public Object visitRecurse(BabbleParser.RecurseContext ctx) {
        return scope.get("$recurse");
    }
}
