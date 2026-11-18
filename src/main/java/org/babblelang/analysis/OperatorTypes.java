package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.effects.Effect;
import org.babblelang.runtime.Operators;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;


/**
 * Operators and implicits : the types of arithmetic, comparisons and equality on numbers,
 * strings and booleans, operator methods on instances ({@code a + b} as {@code a.plus(b)}), and
 * the built-in names ({@code print}, {@code int}, {@code assert}, {@code import}, ...).
 */
final class OperatorTypes {
    private final TypeChecker checker;

    OperatorTypes(TypeChecker checker) {
        this.checker = checker;
    }

    Type binaryOp(BabbleParser.BinaryOpContext ctx, Type left, Type right) {
        return binaryOp(ctx.op.getType(), ctx.op.getText(), ctx, ctx.left.getText(), ctx.left, left, ctx.right.getText(), ctx.right, right);
    }

    /**
     * {@code left op right}, given the operands' source texts and where to report each : the
     * operands of a binary expression, or the target and the value of a compound assignment.
     */
    Type binaryOp(int op, String operator, ParserRuleContext ctx, String leftText, ParserRuleContext leftAt, Type left,
                          String rightText, ParserRuleContext rightAt, Type right) {
        if (op == BabbleLexer.EQ || op == BabbleLexer.NEQ) {
            return Type.BOOL;
        }
        boolean seeding = checker.seedingRecursion();
        String method = Operators.method(operator);
        if (method != null && left instanceof Type.Instance) {
            return operatorCall(left, method, rightAt, right, ctx, leftText);
        }
        if (method != null && left instanceof Type.Union union
                && !Type.containsNull(union)
                && union.members().stream().allMatch(m -> m instanceof Type.Instance)) {
            List<Type> results = new ArrayList<>(union.members().size());
            for (Type m : union.members()) {
                Type r = operatorCall(m, method, rightAt, right, ctx, leftText);
                if (r == Type.ERROR) {
                    return Type.ERROR;
                }
                results.add(r);
            }
            return Type.union(results);
        }
        switch (op) {
            case BabbleLexer.PLUS:
                if (left == Type.STRING) {
                    return Type.STRING;
                }
                if (TypeChecker.isUnresolved(left) && !(seeding && left == Type.NOTHING && isNumber(right))) {
                    return left;
                }
                // Fall through : anything but a string on the left adds numbers.
            case BabbleLexer.MINUS:
            case BabbleLexer.MUL:
            case BabbleLexer.DIV:
                if (left == Type.ERROR) {
                    // Already failed : suppress cascading operator errors.
                    return Type.ERROR;
                }
                boolean numbers = number(left, leftText, leftAt) & number(right, rightText, rightAt);
                if (op == BabbleLexer.DIV) {
                    return Type.DOUBLE;
                }
                if (seeding && numbers) {
                    if (left == Type.NOTHING && isNumber(right)) {
                        return right;
                    }
                    if (right == Type.NOTHING && isNumber(left)) {
                        return left;
                    }
                }
                if (!numbers || TypeChecker.isUnresolved(left) || TypeChecker.isUnresolved(right)) {
                    return Type.ERROR;
                }
                return arithmeticResult(left, right);
            default:
                if (!comparable(left, right)) {
                    checker.warn("Not comparable : " + leftText + " and " + rightText, ctx);
                }
                return Type.BOOL;
        }
    }

    static Type arithmeticResult(Type left, Type right) {
        List<Type> results = new ArrayList<>();
        for (Type l : Type.membersOf(left)) {
            for (Type r : Type.membersOf(right)) {
                results.add(l == Type.INT && r == Type.INT ? Type.INT : Type.DOUBLE);
            }
        }
        return Type.union(results);
    }

    static final EffectSet PURE_DET = EffectSet.ofInferred(
            EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, false);
    static final EffectSet PURE_DET_NOTHROW = EffectSet.ofInferred(
            EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, false);
    static final EffectSet SAME_EFFECTS = EffectSet.ofInferred(
            EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW, Effect.COMMUTATIVE), false, false);

    static final Type.Function INT_CONVERSION = new Type.Function(
            List.of(new Type.Parameter("value", Type.ANY, false, false, true, false)), Type.INT, PURE_DET);
    static final Type.Function DOUBLE_CONVERSION = new Type.Function(
            List.of(new Type.Parameter("value", Type.ANY, false, false, true, false)), Type.DOUBLE, PURE_DET);
    static final Type.Function ASSERT_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("c", Type.BOOL, false, false, true, false),
                    new Type.Parameter("message", Type.NULLABLE_ANY, true, false, true, false)),
            Type.BOOL, PURE_DET);
    static final Type.Function SAME_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("a", Type.NULLABLE_ANY, false, false, true, false),
                    new Type.Parameter("b", Type.NULLABLE_ANY, false, false, true, false)),
            Type.BOOL, SAME_EFFECTS);
    static final Type.Function CLASSOF_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("v", Type.NULLABLE_ANY, false, false, true, false)),
            Type.NULLABLE_ANY, PURE_DET_NOTHROW);
    static final Type.Function IMPORT_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("name", Type.STRING, false)), Type.ANY);
    static final Type STDOUT_TYPE = new Type.JavaInstance(java.io.PrintWriter.class);
    static final Type ARGS_TYPE = new Type.JavaInstance(java.util.List.class, List.of(Type.STRING));

    /**
     * A name the analysis doesn't declare : a root Java package, a number conversion
     * ({@code int(x)}, {@code double(x)}), implicit signatures and {@code any} bindings.
     */
    Type implicitType(String name) {
        return switch (name) {
            case "java", "javax" -> new Type.JavaPackage(name);
            case "int" -> INT_CONVERSION;
            case "double" -> DOUBLE_CONVERSION;
            case "print", "println", "affiche", "afficherc" -> Type.PRINT;
            case "assert", "suppose" -> ASSERT_FUNCTION;
            case "same" -> SAME_FUNCTION;
            case "classof" -> CLASSOF_FUNCTION;
            case "import", "importe" -> IMPORT_FUNCTION;
            case "STDOUT" -> STDOUT_TYPE;
            case "args" -> ARGS_TYPE;
            default -> Type.ANY;
        };
    }

    /** The lexer's token for an arithmetic operator. */
    static int arithmeticToken(String operator) {
        return switch (operator) {
            case "+" -> BabbleLexer.PLUS;
            case "-" -> BabbleLexer.MINUS;
            case "*" -> BabbleLexer.MUL;
            case "/" -> BabbleLexer.DIV;
            default -> throw new IllegalArgumentException(operator);
        };
    }

    static boolean isNumber(Type type) {
        if (type instanceof Type.Union union) {
            return union.members().stream().allMatch(OperatorTypes::isNumber);
        }
        return type == Type.INT || type == Type.DOUBLE;
    }

    /** Whether an operand can be a number, reporting it when it can't. */
    boolean number(Type type, BabbleParser.ExpressionContext operand) {
        return number(type, operand.getText(), operand);
    }

    static boolean isNumeric(Type type) {
        if (type instanceof Type.Union union) {
            return union.members().stream().allMatch(OperatorTypes::isNumeric);
        }
        return type == Type.INT || type == Type.DOUBLE
                || type instanceof Type.JavaInstance instance && Number.class.isAssignableFrom(instance.type());
    }

    boolean number(Type type, String text, ParserRuleContext at) {
        if (TypeChecker.isUnresolved(type) || isNumeric(type)) {
            return true;
        }
        checker.warn("Not a number : " + text, at);
        return false;
    }

    /** {@code Operators.compare} : two numbers, or a Comparable and a value of its class. */
    static boolean comparable(Type left, Type right) {
        if (TypeChecker.isUnresolved(left) || TypeChecker.isUnresolved(right)) {
            return true;
        }
        if (left instanceof Type.Union uLeft) {
            return uLeft.members().stream().allMatch(m -> comparable(m, right));
        }
        if (right instanceof Type.Union uRight) {
            return uRight.members().stream().allMatch(m -> comparable(left, m));
        }
        if (isNumeric(left) && isNumeric(right)) {
            return true;
        }
        Class<?> leftClass = JavaTypeBridge.javaClassOf(left);
        Class<?> rightClass = JavaTypeBridge.javaClassOf(right);
        return leftClass != null && rightClass != null && Comparable.class.isAssignableFrom(leftClass) && leftClass.isAssignableFrom(rightClass);
    }

    /**
     * {@code a + b}, or {@code -a}, on an instance of a class or interface : typed as the call
     * of its operator method, {@code a.plus(b)} ({@code argument} null for {@code a.neg()}).
     */
    Type operatorCall(Type receiver, String method, ParserRuleContext argument, Type argumentType,
                              ParserRuleContext at, String receiverText) {
        return checker.callSolver().operatorCall(receiver, method, argument, argumentType, at, receiverText);
    }

    // ---------------------------------------------------------------- EffectChecker bridge

    /** Whether a function type is one of the built-in functions'. */
    static boolean isBuiltinFunction(Type.Function fn) {
        return fn == INT_CONVERSION
                || fn == DOUBLE_CONVERSION
                || fn == ASSERT_FUNCTION
                || fn == SAME_FUNCTION
                || fn == CLASSOF_FUNCTION
                || fn == IMPORT_FUNCTION;
    }
}
