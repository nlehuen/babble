package org.babblelang.engine.impl;

import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

import java.util.List;

public class Interpreter extends BabbleBaseVisitor<Object> {
    private Scope scope;

    public Interpreter(Scope scope) {
        this.scope = scope;
    }

    public Object run(List<BabbleParser.StatementContext> statements) {
        Object result = null;
        for (BabbleParser.StatementContext statement : statements) {
            result = run(statement);
        }
        return result;
    }

    public Object run(BabbleParser.StatementContext statement) {
        return visit(statement);
    }

    @Override
    public Object visitPackageStatement(BabbleParser.PackageStatementContext ctx) {
        String name = ctx.ID().getText();
        scope = scope.enter(name);
        super.visitPackageStatement(ctx);
        scope = scope.leave();
        return null;
    }

    @Override
    public Object visitDefStatement(BabbleParser.DefStatementContext ctx) {
        String id = ctx.ID().getText();
        Object value = null;
        if (ctx.expression() != null) {
            value = visit(ctx.expression());
        }
        scope.define(id, value);

        return value;
    }

    @Override
    public Object visitParenthesis(BabbleParser.ParenthesisContext ctx) {
        return visit(ctx.expression());
    }

    @Override
    public Object visitBlock(BabbleParser.BlockContext ctx) {
        scope = scope.enter(null);
        Object result = null;
        for (BabbleParser.StatementContext statement : ctx.statement()) {
            result = visit(statement);
        }
        scope = scope.leave();
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
                    return ((Number) a).doubleValue() * ((Number) b).doubleValue();
                }

            case BabbleLexer.DIV:
                return ((Number) a).doubleValue() / ((Number) b).doubleValue();

            case BabbleLexer.PLUS:
                if (a instanceof String) {
                    return (String) a + b;
                } else if (a instanceof Integer && b instanceof Integer) {
                    return (Integer) a + (Integer) b;
                } else {
                    return ((Number) a).doubleValue() + ((Number) b).doubleValue();
                }

            case BabbleLexer.MINUS:
                if (a instanceof Integer && b instanceof Integer) {
                    return (Integer) a - (Integer) b;
                } else {
                    return ((Number) a).doubleValue() - ((Number) b).doubleValue();
                }

            case BabbleLexer.LT:
                return ((Comparable) a).compareTo(b) < 0;

            case BabbleLexer.LTE:
                return ((Comparable) a).compareTo(b) <= 0;

            case BabbleLexer.EQ:
                return ((Comparable) a).compareTo(b) == 0;

            case BabbleLexer.GTE:
                return ((Comparable) a).compareTo(b) >= 0;

            case BabbleLexer.GT:
                return ((Comparable) a).compareTo(b) > 0;

            default:
                throw new UnsupportedOperationException("Bad op : " + ctx.op.getText());
        }
    }

    @Override
    public Object visitBooleanOp(BabbleParser.BooleanOpContext ctx) {
        int op = ctx.op.getType();

        boolean a = test(visit(ctx.left));
        if (a) {
            if (op == BabbleLexer.OR) {
                return true;
            } else {
                return test(visit(ctx.right));
            }
        } else {
            if (op == BabbleLexer.OR) {
                return test(visit(ctx.right));
            } else {
                return false;
            }
        }
    }

    @Override
    public Object visitBooleanNot(BabbleParser.BooleanNotContext ctx) {
        return !test(visit(ctx.expression()));
    }

    private boolean test(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0.0;
        } else {
            return value != null;
        }
    }

    @Override
    public Object visitIfStatement(BabbleParser.IfStatementContext ctx) {
        if (test(visit(ctx.expression()))) {
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
    public Object visitWhileStatement(BabbleParser.WhileStatementContext ctx) {
        Object result = null;
        while (test(visit(ctx.expression()))) {
            result = visit(ctx.block());
        }
        return result;
    }

    @Override
    public Object visitAssign(BabbleParser.AssignContext ctx) {
        Object value = visit(ctx.expression());
        scope.assign(ctx.ID().getText(), value);
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
        Callable callable = (Callable) visit(ctx.expression());
        Callable.Parameters params = (Callable.Parameters) visit(ctx.callParameters());
        Scope beforeCall = scope;
        scope = callable.bindParameters(this, scope, params);
        Object result = callable.call(this, scope);
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
