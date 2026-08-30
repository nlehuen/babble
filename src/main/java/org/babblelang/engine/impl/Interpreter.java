package org.babblelang.engine.impl;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.engine.BabbleException;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

public class Interpreter extends BabbleBaseVisitor<Object> {
    private Namespace namespace;
    private ParserRuleContext last;

    public Interpreter(Namespace root) {
        this.namespace = root;
    }

    ParserRuleContext getLast() {
        return last;
    }

    @Override
    public Object visitFile(BabbleParser.FileContext ctx) {
        last = ctx;
        Object result = null;
        for (BabbleParser.ExpressionContext statement : ctx.expression()) {
            result = visit(statement);
        }
        return result;
    }

    @Override
    public Object visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
        last = ctx;
        namespace = namespace.enter(ctx.name.getText());
        visit(ctx.packageBlock);
        last = ctx;
        namespace = namespace.leave();
        return null;
    }

    @Override
    public Object visitObjectExpression(BabbleParser.ObjectExpressionContext ctx) {
        last = ctx;
        Scope<Object> object = namespace = new BabbleObject(namespace);
        visit(ctx.createBlock);
        last = ctx;
        namespace = namespace.leave();
        return object;
    }

    @Override
    public Object visitDefExpression(BabbleParser.DefExpressionContext ctx) {
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
    public Object visitBlock(BabbleParser.BlockContext ctx) {
        last = ctx;
        Object result = null;
        for (BabbleParser.ExpressionContext statement : ctx.expression()) {
            result = visit(statement);
        }
        last = ctx;
        return result;
    }

    @Override
    public Object visitSelector(BabbleParser.SelectorContext ctx) {
        last = ctx;
        Scope<?> base = namespace;
        String name = ctx.ID().getText();
        BabbleParser.ExpressionContext expression = ctx.expression();
        if (expression != null) {
            base = (Scope<?>) visit(expression);
        }
        last = ctx;
        return base.get(name).get();
    }

    @Override
    public Object visitNull(BabbleParser.NullContext ctx) {
        last = ctx;
        return null;
    }

    @Override
    public Object visitBoolean(BabbleParser.BooleanContext ctx) {
        last = ctx;
        return Boolean.parseBoolean(ctx.getText());
    }

    @Override
    public Object visitBinaryOp(BabbleParser.BinaryOpContext ctx) {
        last = ctx;
        Object a = visit(ctx.left);
        Object b = visit(ctx.right);
        last = ctx;

        // Arms return rather than yield: a switch expression would apply binary numeric
        // promotion across the arms and turn integer results into doubles.
        switch (ctx.op.getType()) {
            case BabbleLexer.MUL -> {
                // TODO : derive operand types
                if (a instanceof Integer ia && b instanceof Integer ib) {
                    return ia * ib;
                }
                return number(a, ctx.left).doubleValue() * number(b, ctx.right).doubleValue();
            }

            case BabbleLexer.DIV -> {
                return number(a, ctx.left).doubleValue() / number(b, ctx.right).doubleValue();
            }

            case BabbleLexer.PLUS -> {
                if (a instanceof String sa) {
                    return sa + b;
                }
                if (a instanceof Integer ia && b instanceof Integer ib) {
                    return ia + ib;
                }
                return number(a, ctx.left).doubleValue() + number(b, ctx.right).doubleValue();
            }

            case BabbleLexer.MINUS -> {
                if (a instanceof Integer ia && b instanceof Integer ib) {
                    return ia - ib;
                }
                return number(a, ctx.left).doubleValue() - number(b, ctx.right).doubleValue();
            }

            case BabbleLexer.LT -> {
                return compare(a, b, ctx) < 0;
            }

            case BabbleLexer.LTE -> {
                return compare(a, b, ctx) <= 0;
            }

            case BabbleLexer.EQ -> {
                return equal(a, b);
            }

            case BabbleLexer.NEQ -> {
                return !equal(a, b);
            }

            case BabbleLexer.GTE -> {
                return compare(a, b, ctx) >= 0;
            }

            case BabbleLexer.GT -> {
                return compare(a, b, ctx) > 0;
            }

            default -> throw new IllegalStateException("Bad op : " + ctx.op.getText());
        }
    }

    /**
     * Three-way comparison for the ordering operators. Values that have no ordering
     * relative to each other are a hard error : "1 &lt; \"x\"" is meaningless, and
     * silently answering true or false would hide the mistake.
     */
    private int compare(Object a, Object b, BabbleParser.BinaryOpContext ctx) {
        Integer comparison = compareOrNull(a, b);
        if (comparison == null) {
            throw new BabbleException("Not comparable : " + ctx.left.getText() + " and " + ctx.right.getText());
        }
        return comparison;
    }

    /**
     * Equality never fails : operands that cannot be ordered are simply not equal,
     * so "1 == \"x\"" is false rather than a type error.
     */
    private boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        Integer comparison = compareOrNull(a, b);
        return comparison != null ? comparison == 0 : a == b;
    }

    /**
     * Orders two values, or returns null when they are not comparable to each other.
     * Numbers are compared numerically across types so that "1 == 1.0" holds; other
     * values must be Comparable and of compatible runtime types.
     */
    private Integer compareOrNull(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            if (isIntegral(na) && isIntegral(nb)) {
                return Long.compare(na.longValue(), nb.longValue());
            }
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Comparable<?> && a.getClass().isInstance(b)) {
            //noinspection unchecked
            return ((Comparable<Object>) a).compareTo(b);
        }
        return null;
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte;
    }

    private Number number(Object a, BabbleParser.ExpressionContext expr) {
        if (a instanceof Number n) {
            return n;
        }
        throw new BabbleException("Not a number : " + expr.getText());
    }

    @Override
    public Object visitBooleanOp(BabbleParser.BooleanOpContext ctx) {
        last = ctx;
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
        last = ctx;
        return !truth(visit(ctx.expression()));
    }

    private boolean truth(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        } else if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        } else {
            return value != null;
        }
    }

    @Override
    public Object visitIfExpression(BabbleParser.IfExpressionContext ctx) {
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
    public Object visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
        last = ctx;
        Object result = null;
        while (truth(visit(ctx.test))) {
            result = visit(ctx.whileBlock);
        }
        return result;
    }

    @Override
    public Object visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
        last = ctx;
        Scope<Object> scope = this.namespace;
        if (ctx.namespace != null) {
            try {
                //noinspection unchecked
                scope = (Scope<Object>) visit(ctx.namespace);
            } catch (ClassCastException cce) {
                throw new BabbleException("Not an assignable scope : " + ctx.namespace.getText());
            }
        }
        last = ctx;
        Object value = visit(ctx.value);
        last = ctx;
        scope.get(ctx.name.getText()).set(value);
        return value;
    }

    @Override
    public Object visitInteger(BabbleParser.IntegerContext ctx) {
        last = ctx;
        return Integer.parseInt(ctx.getText());
    }

    @Override
    public Object visitDouble(BabbleParser.DoubleContext ctx) {
        last = ctx;
        return Double.parseDouble(ctx.getText());
    }

    @Override
    public Object visitString(BabbleParser.StringContext ctx) {
        last = ctx;
        String literal = ctx.STRING().getText();
        literal = literal.substring(1, literal.length() - 1);
        literal = literal.replace("\\\\", "\\").replace("\\\"", "\"");
        return literal;
    }

    @Override
    public Object visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
        last = ctx;
        return new Function<>(ctx, namespace);
    }

    @Override
    public Object visitCall(BabbleParser.CallContext ctx) {
        last = ctx;
        Object expr = visit(ctx.expression());
        last = ctx;
        if (!(expr instanceof Callable<?> callable)) {
            throw new BabbleException(ctx.expression().getText() + " is not callable");
        }
        Parameters params = (Parameters) visit(ctx.callParameters());
        last = ctx;
        Namespace beforeCall = namespace;
        namespace = callable.bindParameters(this, ctx, namespace, params);
        Object result = callable.call(this, ctx, namespace);
        namespace = beforeCall;
        return result;
    }

    @Override
    public Object visitCallParameters(BabbleParser.CallParametersContext ctx) {
        last = ctx;
        int count = 0;
        Parameters params = new Parameters();
        for (BabbleParser.CallParameterContext cp : ctx.callParameter()) {
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
    public Object visitRecurse(BabbleParser.RecurseContext ctx) {
        last = ctx;
        return namespace.get("$recurse").get();
    }
}
