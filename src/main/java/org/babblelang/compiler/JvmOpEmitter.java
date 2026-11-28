package org.babblelang.compiler;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.function.Consumer;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Shared JVM bytecode emission for primitive constants, unary/binary arithmetic (including
 * power-of-two and non-negative {@code floorDiv}/{@code floorMod} fast paths), and primitive
 * comparisons. Consumed by both the HIR emitter ({@link HirEmitter}, {@link ControlAndOpEmitter})
 * and the SSA MIR emitter ({@link MirEmitter}) so {@code -O0}/{@code -O1} and {@code -O2} share a
 * single implementation of primitive op codegen.
 */
final class JvmOpEmitter {
    private JvmOpEmitter() {}

    static PrimitiveKind constantKind(Object v) {
        if (v == null || v instanceof String) {
            return PrimitiveKind.NONE;
        }
        return switch (v) {
            case Boolean ignored -> PrimitiveKind.BOOL;
            case Byte ignored -> PrimitiveKind.BYTE;
            case Short ignored -> PrimitiveKind.SHORT;
            case Integer ignored -> PrimitiveKind.INT;
            case Long ignored -> PrimitiveKind.LONG;
            case Float ignored -> PrimitiveKind.FLOAT;
            case Double ignored -> PrimitiveKind.DOUBLE;
            case Character ignored -> PrimitiveKind.CHAR;
            default -> null;
        };
    }

    /** Pushes a Babble constant onto the operand stack and returns its {@link PrimitiveKind}. */
    static PrimitiveKind pushConstant(MethodVisitor mv, Object v) {
        if (v == null) {
            mv.visitInsn(ACONST_NULL);
        } else if (v instanceof Boolean b) {
            mv.visitInsn(b ? ICONST_1 : ICONST_0);
        } else if (v instanceof Byte b) {
            ConstantPool.pushInt(mv, b);
        } else if (v instanceof Short sh) {
            ConstantPool.pushInt(mv, sh);
        } else if (v instanceof Integer i) {
            ConstantPool.pushInt(mv, i);
        } else if (v instanceof Long l) {
            if (l == 0L) {
                mv.visitInsn(LCONST_0);
            } else if (l == 1L) {
                mv.visitInsn(LCONST_1);
            } else {
                mv.visitLdcInsn(l);
            }
        } else if (v instanceof Float f) {
            if (Float.floatToRawIntBits(f) == 0) {
                mv.visitInsn(FCONST_0);
            } else if (f == 1.0f) {
                mv.visitInsn(FCONST_1);
            } else if (f == 2.0f) {
                mv.visitInsn(FCONST_2);
            } else {
                mv.visitLdcInsn(f);
            }
        } else if (v instanceof Double d) {
            if (Double.doubleToRawLongBits(d) == 0L) {
                mv.visitInsn(DCONST_0);
            } else if (d == 1.0d) {
                mv.visitInsn(DCONST_1);
            } else {
                mv.visitLdcInsn(d);
            }
        } else if (v instanceof Character ch) {
            ConstantPool.pushInt(mv, ch);
        } else if (v instanceof String str) {
            ConstantPool.pushString(mv, str);
        } else {
            throw new IllegalStateException("Unsupported IR constant : " + v.getClass());
        }
        return constantKind(v);
    }

    /** Materializes a boolean value on the stack from a conditional jump emitter. */
    static void emitBoolFromJump(
            MethodVisitor mv, MethodEmitContext ctx, Consumer<Label> jumpIfTrue) {
        Label isTrue = new Label();
        Label end = new Label();
        jumpIfTrue.accept(isTrue);
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(isTrue);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(end);
        ctx.stackKind = PrimitiveKind.BOOL;
    }

    /**
     * Emits primitive numeric negation ({@code INEG}/{@code LNEG}/{@code FNEG}/{@code DNEG}) if
     * statically applicable, returning {@code true} when handled.
     */
    static boolean emitPrimitiveNegate(
            MethodVisitor mv,
            MethodEmitContext ctx,
            PrimitiveKind operandKind,
            PrimitiveKind resultKind,
            Consumer<PrimitiveKind> emitOperand) {
        if (!isNumericKind(operandKind)) {
            return false;
        }
        int insn =
                switch (resultKind) {
                    case INT -> numericOrder(operandKind) <= 2 ? INEG : -1;
                    case LONG -> numericOrder(operandKind) <= 3 ? LNEG : -1;
                    case FLOAT -> numericOrder(operandKind) <= 4 ? FNEG : -1;
                    case DOUBLE -> DNEG;
                    default -> -1;
                };
        if (insn < 0) {
            return false;
        }
        emitOperand.accept(resultKind);
        mv.visitInsn(insn);
        ctx.stackKind = resultKind;
        return true;
    }

    /**
     * Emits primitive binary arithmetic ({@code +}, {@code -}, {@code *}, {@code /}, {@code //},
     * {@code %}) if statically applicable, returning {@code true} when handled.
     *
     * @param positiveRightConst positive constant RHS value (for {@code INT}/{@code LONG} {@code %}
     *     and {@code //}), or {@code <= 0} when unknown / non-positive
     * @param leftNonNegative whether the LHS is statically proven non-negative
     */
    static boolean emitPrimitiveBinaryArith(
            MethodVisitor mv,
            MethodEmitContext ctx,
            HirExpr.BinaryOp op,
            PrimitiveKind lk,
            PrimitiveKind rk,
            PrimitiveKind out,
            long positiveRightConst,
            boolean leftNonNegative,
            int line,
            Consumer<PrimitiveKind> emitLeft,
            Consumer<PrimitiveKind> emitRight) {
        if (!op.isArithmetic() || !isNumericKind(lk) || !isNumericKind(rk)) {
            return false;
        }
        if ((out == PrimitiveKind.INT || out == PrimitiveKind.LONG)
                && (op == HirExpr.BinaryOp.ADD
                        || op == HirExpr.BinaryOp.SUB
                        || op == HirExpr.BinaryOp.MUL)) {
            emitLeft.accept(out);
            emitRight.accept(out);
            int insn =
                    out == PrimitiveKind.INT
                            ? switch (op) {
                                case ADD -> IADD;
                                case SUB -> ISUB;
                                case MUL -> IMUL;
                                default -> throw new IllegalStateException();
                            }
                            : switch (op) {
                                case ADD -> LADD;
                                case SUB -> LSUB;
                                case MUL -> LMUL;
                                default -> throw new IllegalStateException();
                            };
            mv.visitInsn(insn);
            ctx.stackKind = out;
            return true;
        }
        if ((out == PrimitiveKind.INT || out == PrimitiveKind.LONG)
                && (op == HirExpr.BinaryOp.MOD || op == HirExpr.BinaryOp.FLOOR_DIV)) {
            emitLeft.accept(out);
            if (positiveRightConst > 0L && (positiveRightConst & (positiveRightConst - 1L)) == 0L) {
                if (op == HirExpr.BinaryOp.MOD) {
                    if (out == PrimitiveKind.LONG) {
                        mv.visitLdcInsn(positiveRightConst - 1L);
                        mv.visitInsn(LAND);
                    } else {
                        ConstantPool.pushInt(mv, (int) (positiveRightConst - 1L));
                        mv.visitInsn(IAND);
                    }
                } else if (positiveRightConst > 1L) {
                    ConstantPool.pushInt(mv, Long.numberOfTrailingZeros(positiveRightConst));
                    mv.visitInsn(out == PrimitiveKind.LONG ? LSHR : ISHR);
                }
            } else if (positiveRightConst > 0L && leftNonNegative) {
                if (out == PrimitiveKind.LONG) {
                    mv.visitLdcInsn(positiveRightConst);
                    mv.visitInsn(op == HirExpr.BinaryOp.MOD ? LREM : LDIV);
                } else {
                    ConstantPool.pushInt(mv, (int) positiveRightConst);
                    mv.visitInsn(op == HirExpr.BinaryOp.MOD ? IREM : IDIV);
                }
            } else {
                emitRight.accept(out);
                ctx.line(line);
                String mathMethod = op == HirExpr.BinaryOp.MOD ? "floorMod" : "floorDiv";
                String desc = out == PrimitiveKind.LONG ? "(JJ)J" : "(II)I";
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", mathMethod, desc, false);
            }
            ctx.stackKind = out;
            return true;
        }
        if (out == PrimitiveKind.FLOAT || out == PrimitiveKind.DOUBLE) {
            emitLeft.accept(out);
            emitRight.accept(out);
            int insn =
                    out == PrimitiveKind.FLOAT
                            ? switch (op) {
                                case ADD -> FADD;
                                case SUB -> FSUB;
                                case MUL -> FMUL;
                                case DIV -> FDIV;
                                default -> throw new IllegalStateException();
                            }
                            : switch (op) {
                                case ADD -> DADD;
                                case SUB -> DSUB;
                                case MUL -> DMUL;
                                case DIV -> DDIV;
                                default -> throw new IllegalStateException();
                            };
            mv.visitInsn(insn);
            ctx.stackKind = out;
            return true;
        }
        return false;
    }

    static boolean canEmitPrimitiveCmp(HirExpr.BinaryOp op, PrimitiveKind lk, PrimitiveKind rk) {
        if (lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL) {
            return op == HirExpr.BinaryOp.EQ || op == HirExpr.BinaryOp.NEQ;
        }
        if (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR) {
            return true;
        }
        return isNumericKind(lk) && isNumericKind(rk);
    }

    static HirExpr.BinaryOp negateCmp(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> HirExpr.BinaryOp.GTE;
            case LTE -> HirExpr.BinaryOp.GT;
            case EQ -> HirExpr.BinaryOp.NEQ;
            case NEQ -> HirExpr.BinaryOp.EQ;
            case GTE -> HirExpr.BinaryOp.LT;
            case GT -> HirExpr.BinaryOp.LTE;
            default -> throw new IllegalStateException();
        };
    }

    static int icmpJump(HirExpr.BinaryOp op, boolean branchIfTrue) {
        HirExpr.BinaryOp jumpOp = branchIfTrue ? op : negateCmp(op);
        return switch (jumpOp) {
            case LT -> IF_ICMPLT;
            case LTE -> IF_ICMPLE;
            case EQ -> IF_ICMPEQ;
            case NEQ -> IF_ICMPNE;
            case GTE -> IF_ICMPGE;
            case GT -> IF_ICMPGT;
            default -> throw new IllegalStateException();
        };
    }

    /**
     * Jumps to {@code target} when {@code left op right} evaluates to {@code branchIfTrue}, using
     * {@code Float.compare}/{@code Double.compare} for floating-point values so NaN ordering and
     * negation match Babble's reference interpreter.
     */
    static void emitPrimitiveComparisonJump(
            MethodVisitor mv,
            HirExpr.BinaryOp op,
            PrimitiveKind lk,
            PrimitiveKind rk,
            boolean branchIfTrue,
            Label target,
            Consumer<PrimitiveKind> emitLeft,
            Consumer<PrimitiveKind> emitRight) {
        if ((lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL)
                || (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR)) {
            emitLeft.accept(lk);
            emitRight.accept(rk);
            mv.visitJumpInsn(icmpJump(op, branchIfTrue), target);
            return;
        }
        PrimitiveKind common = PrimitiveKind.comparisonKind(lk, rk);
        emitLeft.accept(common);
        emitRight.accept(common);
        if (numericOrder(common) <= 2) {
            mv.visitJumpInsn(icmpJump(op, branchIfTrue), target);
            return;
        }
        if (common == PrimitiveKind.LONG) {
            mv.visitInsn(LCMP);
        } else if (common == PrimitiveKind.FLOAT) {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
        }
        HirExpr.BinaryOp jumpOp = branchIfTrue ? op : negateCmp(op);
        mv.visitJumpInsn(
                switch (jumpOp) {
                    case LT -> IFLT;
                    case LTE -> IFLE;
                    case EQ -> IFEQ;
                    case NEQ -> IFNE;
                    case GTE -> IFGE;
                    case GT -> IFGT;
                    default -> throw new IllegalStateException();
                },
                target);
    }
}
