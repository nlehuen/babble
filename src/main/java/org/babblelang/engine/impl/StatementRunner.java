package org.babblelang.engine.impl;

import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

import java.util.List;

public class StatementRunner extends BabbleBaseVisitor<Object> {
    private Scope scope;

    public StatementRunner(Scope scope) {
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
        Object value = visit(ctx.expression());
        scope.put(id, value);
        return value;
    }

    @Override
    public Object visitParen(BabbleParser.ParenContext ctx) {
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
        if (ctx.selector() != null) {
            base = (Scope) visit(ctx.selector());
        }
        String name = ctx.ID().getText();
        if (base.containsKey(name)) {
            return base.get(name);
        } else {
            throw new IllegalArgumentException("ID not found : " + name);
        }
    }

    @Override
    public Object visitBinaryOp(BabbleParser.BinaryOpContext ctx) {
        Object a = visit(ctx.expression(0));
        Object b = visit(ctx.expression(1));

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
    public Object visitIfStatement(BabbleParser.IfStatementContext ctx) {
        Object value = visit(ctx.expression());

        boolean result;
        if (value instanceof Boolean) {
            result = (Boolean) value;
        } else if (value instanceof Number) {
            result = ((Number) value).doubleValue() != 0.0;
        } else {
            result = value != null;
        }

        if (result) {
            return visit(ctx.thenBlock);
        } else {
            return visit(ctx.elseBlock);
        }
    }

    @Override
    public Object visitAssignStatement(BabbleParser.AssignStatementContext ctx) {
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
        return literal.substring(1, literal.length() - 1);
    }
}
