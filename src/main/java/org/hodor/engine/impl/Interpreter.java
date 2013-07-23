package org.hodor.engine.impl;

import org.antlr.v4.runtime.ParserRuleContext;
import org.hodor.engine.BabbleException;
import org.hodor.parser.HodorBaseVisitor;
import org.hodor.parser.HodorLexer;
import org.hodor.parser.HodorParser;

public class Interpreter extends HodorBaseVisitor<Object> {
    private Namespace namespace;
    private ParserRuleContext last;

    public Interpreter(Namespace root) {
        this.namespace = root;
    }

    protected ParserRuleContext getLast() {
        return last;
    }

    @Override
    public Object visitFile(HodorParser.FileContext ctx) {
        last = ctx;
        Object result = null;
        for (HodorParser.ExpressionContext statement : ctx.expression()) {
            result = visit(statement);
        }
        return result;
    }

    @Override
    public Object visitPackageExpression(HodorParser.PackageExpressionContext ctx) {
        last = ctx;
        namespace = namespace.enter(ctx.name.getText());
        visit(ctx.packageBlock);
        last = ctx;
        namespace = namespace.leave();
        return null;
    }

    @Override
    public Object visitObjectExpression(HodorParser.ObjectExpressionContext ctx) {
        last = ctx;
        Scope object = namespace = new HodorObject(namespace);
        visit(ctx.createBlock);
        last = ctx;
        namespace = namespace.leave();
        return object;
    }

    @Override
    public Object visitDefExpression(HodorParser.DefExpressionContext ctx) {
        last = ctx;
        String id = ctx.name.getText();
        Object value = null;
        if (ctx.expression() != null) {
            value = visit(ctx.expression());
        }
        last = ctx;
        namespace.define(id, false).set(value);
        return value;
    }

    @Override
    public Object visitBlock(HodorParser.BlockContext ctx) {
        last = ctx;
        Object result = null;
        for (HodorParser.ExpressionContext statement : ctx.expression()) {
            result = visit(statement);
        }
        last = ctx;
        return result;
    }

    @Override
    public Object visitSelector(HodorParser.SelectorContext ctx) {
        last = ctx;
        Scope base = namespace;
        String name = ctx.ID().getText();
        HodorParser.ExpressionContext expression = ctx.expression();
        if (expression != null) {
            base = (Scope) visit(expression);
        }
        last = ctx;
        return base.get(name).get();
    }

    @Override
    public Object visitNull(HodorParser.NullContext ctx) {
        last = ctx;
        return null;
    }

    @Override
    public Object visitBoolean(HodorParser.BooleanContext ctx) {
        last = ctx;

        String text = ctx.getText();
        return text!=null && text.equals("HOdor");
    }

    @Override
    public Object visitBinaryOp(HodorParser.BinaryOpContext ctx) {
        last = ctx;
        Object a = visit(ctx.left);
        Object b = visit(ctx.right);
        last = ctx;

        switch (ctx.op.getType()) {
            case HodorLexer.MUL:
                // TODO : derive operand types
                if (a instanceof Integer && b instanceof Integer) {
                    return (Integer) a * (Integer) b;
                } else {
                    return number(a, ctx.left).doubleValue() * number(b, ctx.right).doubleValue();
                }

            case HodorLexer.DIV:
                return number(a, ctx.left).doubleValue() / number(b, ctx.right).doubleValue();

            case HodorLexer.PLUS:
                if (a instanceof String) {
                    return (String) a + b;
                } else if (a instanceof Integer && b instanceof Integer) {
                    return (Integer) a + (Integer) b;
                } else {
                    return number(a, ctx.left).doubleValue() + number(b, ctx.right).doubleValue();
                }

            case HodorLexer.MINUS:
                if (a instanceof Integer && b instanceof Integer) {
                    return (Integer) a - (Integer) b;
                } else {
                    return number(a, ctx.left).doubleValue() - number(b, ctx.right).doubleValue();
                }

            case HodorLexer.LT:
                return comparable(a, ctx.left).compareTo(comparable(b, ctx.right)) < 0;

            case HodorLexer.LTE:
                return comparable(a, ctx.left).compareTo(comparable(b, ctx.right)) <= 0;

            case HodorLexer.EQ:
                if (a instanceof Comparable) {
                    return comparable(a, ctx.left).compareTo(comparable(b, ctx.right)) == 0;
                } else {
                    return a == b;
                }

            case HodorLexer.NEQ:
                if (a instanceof Comparable) {
                    return comparable(a, ctx.left).compareTo(comparable(b, ctx.right)) != 0;
                } else {
                    return a != b;
                }

            case HodorLexer.GTE:
                return comparable(a, ctx.left).compareTo(comparable(b, ctx.right)) >= 0;

            case HodorLexer.GT:
                return comparable(a, ctx.left).compareTo(comparable(b, ctx.right)) > 0;

            default:
                throw new IllegalStateException("Bad op : " + ctx.op.getText());
        }
    }

    private Number number(Object a, HodorParser.ExpressionContext expr) {
        if (a instanceof Number) {
            return (Number) a;
        } else {
            throw new BabbleException("Not a number : " + expr.getText());
        }
    }

    private Comparable comparable(Object a, HodorParser.ExpressionContext expr) {
        if (a instanceof Comparable) {
            return (Comparable) a;
        } else {
            throw new BabbleException("Not comparable : " + expr.getText());
        }
    }

    @Override
    public Object visitBooleanOp(HodorParser.BooleanOpContext ctx) {
        last = ctx;
        int op = ctx.op.getType();

        boolean a = truth(visit(ctx.left));
        if (a) {
            if (op == HodorLexer.OR) {
                return true;
            } else {
                return truth(visit(ctx.right));
            }
        } else {
            if (op == HodorLexer.OR) {
                return truth(visit(ctx.right));
            } else {
                return false;
            }
        }
    }

    @Override
    public Object visitBooleanNot(HodorParser.BooleanNotContext ctx) {
        last = ctx;
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
    public Object visitIfExpression(HodorParser.IfExpressionContext ctx) {
        last = ctx;
        if (truth(visit(ctx.test))) {
            last = ctx;
            return visit(ctx.thenBlock);
        } else {
            last = ctx;
            if (ctx.elseBlock != null) {
                return visit(ctx.elseBlock);
            } else {
                return null;
            }
        }
    }

    @Override
    public Object visitWhileExpression(HodorParser.WhileExpressionContext ctx) {
        last = ctx;
        Object result = null;
        while (truth(visit(ctx.test))) {
            result = visit(ctx.whileBlock);
        }
        return result;
    }

    @Override
    public Object visitAssignExpression(HodorParser.AssignExpressionContext ctx) {
        last = ctx;
        Scope scope = this.namespace;
        if (ctx.namespace != null) {
            scope = (Scope) visit(ctx.namespace);
        }
        last = ctx;
        Object value = visit(ctx.value);
        last = ctx;
        scope.get(ctx.name.getText()).set(value);
        return value;
    }

    @Override
    public Object visitInteger(HodorParser.IntegerContext ctx) {
        last = ctx;
        return Integer.parseInt(ctx.getText());
    }

    @Override
    public Object visitDouble(HodorParser.DoubleContext ctx) {
        last = ctx;
        return Double.parseDouble(ctx.getText());
    }

    @Override
    public Object visitString(HodorParser.StringContext ctx) {
        last = ctx;
        String literal = ctx.STRING().getText();
        literal = literal.substring(1, literal.length() - 1);
        literal = literal.replace("\\\\", "\\").replace("\\\"", "\"");
        return literal;
    }

    @Override
    public Object visitFunctionLiteral(HodorParser.FunctionLiteralContext ctx) {
        last = ctx;
        return new Function(ctx, namespace);
    }

    @Override
    public Object visitCall(HodorParser.CallContext ctx) {
        last = ctx;
        Object expr = visit(ctx.expression());
        last = ctx;
        if (!(expr instanceof Callable)) {
            throw new BabbleException(ctx.expression().getText() + " is not callable");
        }
        Callable callable = (Callable) expr;
        Callable.Parameters params = (Callable.Parameters) visit(ctx.callParameters());
        last = ctx;
        Namespace beforeCall = namespace;
        namespace = callable.bindParameters(this, ctx, namespace, params);
        Object result = callable.call(this, ctx, namespace);
        namespace = beforeCall;
        return result;
    }

    @Override
    public Object visitCallParameters(HodorParser.CallParametersContext ctx) {
        last = ctx;
        int count = 0;
        Callable.Parameters params = new Callable.Parameters();
        for (HodorParser.CallParameterContext cp : ctx.callParameter()) {
            String name = "$" + (count++);
            if (cp.ID() != null) {
                name = cp.ID().getText();
            }
            Object value = visit(cp.expression());
            last = ctx;
            params.put(name, value);
        }
        return params;
    }

    @Override
    public Object visitRecurse(HodorParser.RecurseContext ctx) {
        last = ctx;
        return namespace.get("$recurse").get();
    }
}
