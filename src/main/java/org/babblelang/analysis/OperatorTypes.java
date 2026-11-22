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
                    if (!numbers || TypeChecker.isUnresolved(left) || TypeChecker.isUnresolved(right)) {
                        return Type.DOUBLE;
                    }
                    return divisionResult(left, right);
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
                results.add(promoteArithmetic(l, r));
            }
        }
        return Type.union(results);
    }

    private static Type promoteArithmetic(Type l, Type r) {
        int rl = Type.numericRank(l);
        int rr = Type.numericRank(r);
        if (rl < 0 || rr < 0) {
            return Type.DOUBLE;
        }
        int max = Math.max(rl, rr);
        return max <= 2 ? Type.INT : Type.numericOfRank(max);
    }

    static Type divisionResult(Type left, Type right) {
        List<Type> results = new ArrayList<>();
        for (Type l : Type.membersOf(left)) {
            for (Type r : Type.membersOf(right)) {
                results.add(promoteDivision(l, r));
            }
        }
        return Type.union(results);
    }

    private static Type promoteDivision(Type l, Type r) {
        int rl = Type.numericRank(l);
        int rr = Type.numericRank(r);
        if (rl < 0 || rr < 0) {
            return Type.DOUBLE;
        }
        // float / {byte, short, int, float} (either side) stays float; everything else is double.
        boolean hasFloat = (l == Type.FLOAT || r == Type.FLOAT);
        boolean hasWide = (l == Type.DOUBLE || r == Type.DOUBLE || l == Type.LONG || r == Type.LONG);
        return hasFloat && !hasWide ? Type.FLOAT : Type.DOUBLE;
    }

    static Type negateResult(Type operand) {
        int r = Type.numericRank(operand);
        if (r < 0) {
            return Type.DOUBLE;
        }
        return r <= 2 ? Type.INT : operand;
    }

    static final EffectSet PURE_DET_NOTHROW = EffectSet.ofInferred(
            EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, false, false, true);
    static final EffectSet PURE_DET_NOTHROW_UNIQUE = EffectSet.ofInferred(
            EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, false, true, true);

    /** Accepts any value including {@code null} and {@code frozen T} without unwrapping {@code frozen} (BIP007-D8/D9). */
    static final Type ANY_OR_FROZEN = Type.union(Type.NULLABLE_ANY, Type.FROZEN_ANY);

    private static final Type.TypeVar MOVE_TV = new Type.TypeVar(new Type.SymbolId("move"), "T", 0);
    private static final Type.TypeVar FREEZE_TV = new Type.TypeVar(new Type.SymbolId("freeze"), "T", 0);
    static final Type.Function MOVE_FUNCTION = new Type.Function(
            List.of(MOVE_TV),
            List.of(new Type.Parameter("value", MOVE_TV, false, false, false, false, true)),
            MOVE_TV, PURE_DET_NOTHROW_UNIQUE);
    static final Type.Function FREEZE_FUNCTION = new Type.Function(
            List.of(FREEZE_TV),
            List.of(new Type.Parameter("value", FREEZE_TV, false, false, false, false, true)),
            new Type.Frozen(FREEZE_TV), PURE_DET_NOTHROW);
    static final Type.Function DISCARD_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("value", ANY_OR_FROZEN, false, false, true, false)),
            Type.NULL, PURE_DET_NOTHROW);
    static final Type.Function IMPORT_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("name", Type.STRING, false)), Type.ANY);
    static final Type STDOUT_TYPE = new Type.JavaInstance(java.io.PrintWriter.class);
    static final Type ARGS_TYPE = new Type.JavaInstance(java.util.List.class, List.of(Type.STRING));

    /**
     * A name the analysis doesn't declare : a root Java package, special forms
     * ({@code move}, {@code freeze}, {@code discard}, {@code import}, {@code STDOUT}, {@code args}),
     * ordinary built-ins from {@code builtins.ba}, and {@code any} host bindings.
     */
    Type implicitType(String name) {
        return switch (name) {
            case "java", "javax" -> new Type.JavaPackage(name);
            case "move" -> MOVE_FUNCTION;
            case "freeze" -> FREEZE_FUNCTION;
            case "discard" -> DISCARD_FUNCTION;
            case "import", "importe" -> IMPORT_FUNCTION;
            case "STDOUT" -> STDOUT_TYPE;
            case "args" -> ARGS_TYPE;
            default -> {
                Type.Function builtin = BuiltinAxioms.current().builtins().get(name);
                yield builtin != null ? builtin : Type.ANY;
            }
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
        return Type.isNumericScalar(type);
    }

    /** Whether an operand can be a number, reporting it when it can't. */
    boolean number(Type type, BabbleParser.ExpressionContext operand) {
        return number(type, operand.getText(), operand);
    }

    static boolean isNumeric(Type type) {
        if (type instanceof Type.Union union) {
            return union.members().stream().allMatch(OperatorTypes::isNumeric);
        }
        return Type.isNumericScalar(type)
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
        return fn == MOVE_FUNCTION
                || fn == FREEZE_FUNCTION
                || fn == DISCARD_FUNCTION
                || fn == IMPORT_FUNCTION
                || BuiltinAxioms.current().isBuiltinFunction(fn);
    }
}
