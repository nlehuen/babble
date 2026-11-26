package org.babblelang.compiler;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Operators;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Bytecode emission for unary/binary/logical operators, conditions, type checks/casts/SAM
 * adaptations, blocks, conditionals, loops ({@code while}/{@code for}), and jumps
 * ({@code break}/{@code continue}/{@code return}), extracted from {@link HirEmitter} (BIP027-I2).
 */
final class ControlAndOpEmitter {
    private final HirEmitter emitter;

    ControlAndOpEmitter(HirEmitter emitter) {
        this.emitter = emitter;
    }

    private MethodEmitContext ctx() {
        return emitter.ctx;
    }

    // ---------------------------------------------------------------- operators & conditions

    void emitUnary(HirExpr.Unary u) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (u.op() == HirExpr.UnaryOp.NOT) {
            Label isTrue = new Label();
            Label end = new Label();
            emitCondition(u.operand(), false, isTrue);
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(isTrue);
            mv.visitInsn(ICONST_1);
            mv.visitLabel(end);
            c.stackKind = PrimitiveKind.BOOL;
            return;
        }
        PrimitiveKind ok = u.operand().meta().kind();
        PrimitiveKind rk = u.meta().kind();
        if (rk == PrimitiveKind.INT && isNumericKind(ok) && numericOrder(ok) <= 2) {
            emitter.emitAs(u.operand(), PrimitiveKind.INT);
            mv.visitInsn(INEG);
            c.stackKind = PrimitiveKind.INT;
            return;
        }
        if (rk == PrimitiveKind.LONG && isNumericKind(ok) && numericOrder(ok) <= 3) {
            emitter.emitAs(u.operand(), PrimitiveKind.LONG);
            mv.visitInsn(LNEG);
            c.stackKind = PrimitiveKind.LONG;
            return;
        }
        if (rk == PrimitiveKind.FLOAT && isNumericKind(ok) && numericOrder(ok) <= 4) {
            emitter.emitAs(u.operand(), PrimitiveKind.FLOAT);
            mv.visitInsn(FNEG);
            c.stackKind = PrimitiveKind.FLOAT;
            return;
        }
        if (rk == PrimitiveKind.DOUBLE && isNumericKind(ok)) {
            emitter.emitAs(u.operand(), PrimitiveKind.DOUBLE);
            mv.visitInsn(DNEG);
            c.stackKind = PrimitiveKind.DOUBLE;
            return;
        }
        int operand = c.newLocal();
        emitter.emitBoxed(u.operand());
        mv.visitVarInsn(ASTORE, operand);
        Label number = new Label();
        Label end = new Label();
        mv.visitVarInsn(ALOAD, operand);
        mv.visitMethodInsn(INVOKESTATIC, BABBLE_OBJECTS, "isInstance", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, number);
        emitOperatorCall(u.meta().line(), u.operandText(), Operators.NEGATE, null, operand, -1);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(number);
        mv.visitVarInsn(ALOAD, operand);
        mv.visitLdcInsn(u.operandText());
        c.line(u.meta().line());
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", "negate", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        mv.visitLabel(end);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitBinary(HirExpr.Binary b) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (b.op().isComparison() && canLowerComparison(b)) {
            Label isTrue = new Label();
            Label end = new Label();
            emitComparison(b, true, isTrue);
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(isTrue);
            mv.visitInsn(ICONST_1);
            mv.visitLabel(end);
            c.stackKind = PrimitiveKind.BOOL;
            return;
        }

        PrimitiveKind resultKind = b.meta().kind();
        PrimitiveKind lk = b.left().meta().kind();
        PrimitiveKind rk = b.right().meta().kind();
        if (isNumericKind(lk) && isNumericKind(rk)
                && (b.op() == HirExpr.BinaryOp.ADD || b.op() == HirExpr.BinaryOp.SUB || b.op() == HirExpr.BinaryOp.MUL)) {
            if (resultKind == PrimitiveKind.INT) {
                emitter.emitAs(b.left(), PrimitiveKind.INT);
                emitter.emitAs(b.right(), PrimitiveKind.INT);
                mv.visitInsn(switch (b.op()) {
                    case ADD -> IADD;
                    case SUB -> ISUB;
                    case MUL -> IMUL;
                    default -> throw new IllegalStateException();
                });
                c.stackKind = PrimitiveKind.INT;
                return;
            }
            if (resultKind == PrimitiveKind.LONG) {
                emitter.emitAs(b.left(), PrimitiveKind.LONG);
                emitter.emitAs(b.right(), PrimitiveKind.LONG);
                mv.visitInsn(switch (b.op()) {
                    case ADD -> LADD;
                    case SUB -> LSUB;
                    case MUL -> LMUL;
                    default -> throw new IllegalStateException();
                });
                c.stackKind = PrimitiveKind.LONG;
                return;
            }
        }

        if (isNumericKind(lk) && isNumericKind(rk)
                && (b.op() == HirExpr.BinaryOp.MOD || b.op() == HirExpr.BinaryOp.FLOOR_DIV)) {
            if (resultKind == PrimitiveKind.INT) {
                emitter.emitAs(b.left(), PrimitiveKind.INT);
                if (b.right() instanceof HirExpr.Const rc && rc.value() instanceof Number n) {
                    int d = n.intValue();
                    if (d > 0 && (d & (d - 1)) == 0) {
                        if (b.op() == HirExpr.BinaryOp.MOD) {
                            ConstantPool.pushInt(mv, d - 1);
                            mv.visitInsn(IAND);
                        } else if (d > 1) {
                            ConstantPool.pushInt(mv, Integer.numberOfTrailingZeros(d));
                            mv.visitInsn(ISHR);
                        }
                        c.stackKind = PrimitiveKind.INT;
                        return;
                    }
                }
                emitter.emitAs(b.right(), PrimitiveKind.INT);
                c.line(b.meta().line());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math",
                        b.op() == HirExpr.BinaryOp.MOD ? "floorMod" : "floorDiv", "(II)I", false);
                c.stackKind = PrimitiveKind.INT;
                return;
            }
            if (resultKind == PrimitiveKind.LONG) {
                emitter.emitAs(b.left(), PrimitiveKind.LONG);
                if (b.right() instanceof HirExpr.Const rc && rc.value() instanceof Number n) {
                    long d = n.longValue();
                    if (d > 0L && (d & (d - 1L)) == 0L) {
                        if (b.op() == HirExpr.BinaryOp.MOD) {
                            mv.visitLdcInsn(d - 1L);
                            mv.visitInsn(LAND);
                        } else if (d > 1L) {
                            ConstantPool.pushInt(mv, Long.numberOfTrailingZeros(d));
                            mv.visitInsn(LSHR);
                        }
                        c.stackKind = PrimitiveKind.LONG;
                        return;
                    }
                }
                emitter.emitAs(b.right(), PrimitiveKind.LONG);
                c.line(b.meta().line());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math",
                        b.op() == HirExpr.BinaryOp.MOD ? "floorMod" : "floorDiv", "(JJ)J", false);
                c.stackKind = PrimitiveKind.LONG;
                return;
            }
        }

        if (resultKind == PrimitiveKind.FLOAT && isNumericKind(lk) && isNumericKind(rk) && b.op().isArithmetic()) {
            emitter.emitAs(b.left(), PrimitiveKind.FLOAT);
            emitter.emitAs(b.right(), PrimitiveKind.FLOAT);
            mv.visitInsn(switch (b.op()) {
                case ADD -> FADD;
                case SUB -> FSUB;
                case MUL -> FMUL;
                case DIV -> FDIV;
                default -> throw new IllegalStateException();
            });
            c.stackKind = PrimitiveKind.FLOAT;
            return;
        }

        if (resultKind == PrimitiveKind.DOUBLE && isNumericKind(lk) && isNumericKind(rk) && b.op().isArithmetic()) {
            emitter.emitAs(b.left(), PrimitiveKind.DOUBLE);
            emitter.emitAs(b.right(), PrimitiveKind.DOUBLE);
            mv.visitInsn(switch (b.op()) {
                case ADD -> DADD;
                case SUB -> DSUB;
                case MUL -> DMUL;
                case DIV -> DDIV;
                default -> throw new IllegalStateException();
            });
            c.stackKind = PrimitiveKind.DOUBLE;
            return;
        }

        String method = switch (b.op()) {
            case MUL -> "mul";
            case DIV -> "div";
            case FLOOR_DIV -> "floorDiv";
            case MOD -> "mod";
            case ADD -> "add";
            case SUB -> "sub";
            case LT -> "lt";
            case LTE -> "le";
            case EQ -> "eq";
            case NEQ -> "ne";
            case GTE -> "ge";
            case GT -> "gt";
        };
        String operatorMethod = Operators.method(b.op().symbol());
        if (operatorMethod == null) {
            emitter.emitBoxed(b.left());
            emitter.emitBoxed(b.right());
            mv.visitFieldInsn(GETSTATIC, emitter.className, emitter.constantPool.opSiteField(b.leftText(), b.rightText()), "L" + OP_SITE + ";");
            c.line(b.meta().line());
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", method, "(Ljava/lang/Object;Ljava/lang/Object;L" + OP_SITE + ";)Ljava/lang/Object;", false);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        int left = c.newLocal();
        int right = c.newLocal();
        emitter.emitBoxed(b.left());
        mv.visitVarInsn(ASTORE, left);
        emitter.emitBoxed(b.right());
        mv.visitVarInsn(ASTORE, right);
        emitDynamicArithmetic(b.meta().line(), b.op().symbol(), b.leftText(), b.rightText(), left, right);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitRange(HirExpr.Range r) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        PrimitiveKind ek = r.elemKind();
        String rangeOwner = RUNTIME + "Range";
        switch (ek) {
            case BYTE -> {
                emitter.emitAs(r.start(), PrimitiveKind.BYTE);
                emitter.emitAs(r.end(), PrimitiveKind.BYTE);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "ofByte", "(BBZ)L" + RUNTIME + "IntRange;", true);
            }
            case SHORT -> {
                emitter.emitAs(r.start(), PrimitiveKind.SHORT);
                emitter.emitAs(r.end(), PrimitiveKind.SHORT);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "ofShort", "(SSZ)L" + RUNTIME + "IntRange;", true);
            }
            case INT -> {
                emitter.emitAs(r.start(), PrimitiveKind.INT);
                emitter.emitAs(r.end(), PrimitiveKind.INT);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "ofInt", "(IIZ)L" + RUNTIME + "IntRange;", true);
            }
            case LONG -> {
                emitter.emitAs(r.start(), PrimitiveKind.LONG);
                emitter.emitAs(r.end(), PrimitiveKind.LONG);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "ofLong", "(JJZ)L" + RUNTIME + "LongRange;", true);
            }
            case CHAR -> {
                emitter.emitAs(r.start(), PrimitiveKind.CHAR);
                emitter.emitAs(r.end(), PrimitiveKind.CHAR);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "ofChar", "(CCZ)L" + RUNTIME + "CharRange;", true);
            }
            case FLOAT -> {
                emitter.emitAs(r.start(), PrimitiveKind.FLOAT);
                emitter.emitAs(r.end(), PrimitiveKind.FLOAT);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "ofFloat", "(FFZ)L" + RUNTIME + "DoubleRange;", true);
            }
            case DOUBLE -> {
                emitter.emitAs(r.start(), PrimitiveKind.DOUBLE);
                emitter.emitAs(r.end(), PrimitiveKind.DOUBLE);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "ofDouble", "(DDZ)L" + RUNTIME + "DoubleRange;", true);
            }
            default -> {
                emitter.emitBoxed(r.start());
                mv.visitTypeInsn(CHECKCAST, "java/lang/Comparable");
                emitter.emitBoxed(r.end());
                mv.visitTypeInsn(CHECKCAST, "java/lang/Comparable");
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "ofComparable",
                        "(Ljava/lang/Comparable;Ljava/lang/Comparable;Z)L" + RUNTIME + "ComparableRange;", true);
            }
        }
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitDynamicArithmetic(int lineNumber, String operator, String leftText, String rightText, int left, int right) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        String method = switch (operator) {
            case "*" -> "mul";
            case "/" -> "div";
            case "//" -> "floorDiv";
            case "mod" -> "mod";
            case "+" -> "add";
            case "-" -> "sub";
            default -> throw new IllegalStateException("Bad op : " + operator);
        };
        Label numbers = new Label();
        Label end = new Label();
        mv.visitVarInsn(ALOAD, left);
        mv.visitMethodInsn(INVOKESTATIC, BABBLE_OBJECTS, "isInstance", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, numbers);
        emitOperatorCall(lineNumber, leftText, Operators.method(operator), rightText, left, right);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(numbers);
        mv.visitVarInsn(ALOAD, left);
        mv.visitVarInsn(ALOAD, right);
        mv.visitFieldInsn(GETSTATIC, emitter.className, emitter.constantPool.opSiteField(leftText, rightText), "L" + OP_SITE + ";");
        c.line(lineNumber);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", method, "(Ljava/lang/Object;Ljava/lang/Object;L" + OP_SITE + ";)Ljava/lang/Object;", false);
        mv.visitLabel(end);
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitOperatorCall(int lineNumber, String receiver, String method, String argumentText, int receiverLocal, int argument) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        String[] argTexts = argumentText == null ? new String[0] : new String[]{argumentText};
        CallSite site = new CallSite(emitter.sourceName, lineNumber, receiver + "." + method, argTexts);
        String field = emitter.constantPool.callSiteField(site);
        mv.visitVarInsn(ALOAD, receiverLocal);
        c.line(lineNumber);
        mv.visitInvokeDynamicInsn("select", "(Ljava/lang/Object;)Ljava/lang/Object;", SELECT_BOOTSTRAP, method, receiver);
        mv.visitFieldInsn(GETSTATIC, emitter.className, field, "L" + CALL_SITE + ";");
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);
        mv.visitVarInsn(ALOAD, c.rtLocal);
        mv.visitInsn(ACONST_NULL);
        mv.visitFieldInsn(GETSTATIC, emitter.className, field, "L" + CALL_SITE + ";");
        if (argument >= 0) {
            mv.visitVarInsn(ALOAD, argument);
        }
        c.line(lineNumber);
        mv.visitInvokeDynamicInsn("call", "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/String;L" + CALL_SITE + ";"
                + (argument >= 0 ? "Ljava/lang/Object;" : "") + ")Ljava/lang/Object;", CALL_BOOTSTRAP);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitLogic(HirExpr.Logic l) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        Label isTrue = new Label();
        Label end = new Label();
        emitCondition(l, true, isTrue);
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(isTrue);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(end);
        c.stackKind = PrimitiveKind.BOOL;
    }

    void emitCondition(HirExpr expr, boolean branchIfTrue, Label target) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        while (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
            expr = b.statements().get(0);
        }
        if (expr instanceof HirExpr.Unary u && u.op() == HirExpr.UnaryOp.NOT) {
            emitCondition(u.operand(), !branchIfTrue, target);
            return;
        }
        if (expr instanceof HirExpr.Logic l) {
            boolean or = l.op() == HirExpr.LogicOp.OR;
            if (or == branchIfTrue) {
                emitCondition(l.left(), branchIfTrue, target);
                emitCondition(l.right(), branchIfTrue, target);
            } else {
                Label fallThrough = new Label();
                emitCondition(l.left(), !branchIfTrue, fallThrough);
                emitCondition(l.right(), branchIfTrue, target);
                mv.visitLabel(fallThrough);
            }
            return;
        }
        if (expr instanceof HirExpr.Binary b && b.op().isComparison() && canLowerComparison(b)) {
            emitComparison(b, branchIfTrue, target);
            return;
        }
        emitter.emitExpr(expr);
        switch (c.stackKind) {
            case BOOL, BYTE, SHORT, INT, CHAR -> mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            case LONG -> {
                mv.visitInsn(LCONST_0);
                mv.visitInsn(LCMP);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
            case FLOAT -> {
                mv.visitInsn(FCONST_0);
                mv.visitInsn(FCMPL);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
            case DOUBLE -> {
                mv.visitInsn(DCONST_0);
                mv.visitInsn(DCMPL);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
            case NONE -> {
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
        }
    }

    private static boolean canLowerComparison(HirExpr.Binary b) {
        PrimitiveKind lk = b.left().meta().kind();
        PrimitiveKind rk = b.right().meta().kind();
        if (lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL) {
            return b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ;
        }
        if (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR) {
            return true;
        }
        return isNumericKind(lk) && isNumericKind(rk);
    }

    private void emitComparison(HirExpr.Binary b, boolean branchIfTrue, Label target) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        PrimitiveKind lk = b.left().meta().kind();
        PrimitiveKind rk = b.right().meta().kind();
        if ((lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL)
                || (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR)) {
            emitter.emitAs(b.left(), lk);
            emitter.emitAs(b.right(), rk);
            mv.visitJumpInsn(icmpJump(b.op(), branchIfTrue), target);
            return;
        }
        PrimitiveKind common = numericOrder(lk) >= numericOrder(rk) ? lk : rk;
        if (numericOrder(common) <= 2) {
            emitter.emitAs(b.left(), common);
            emitter.emitAs(b.right(), common);
            mv.visitJumpInsn(icmpJump(b.op(), branchIfTrue), target);
            return;
        }
        emitter.emitAs(b.left(), common);
        emitter.emitAs(b.right(), common);
        if (common == PrimitiveKind.LONG) {
            mv.visitInsn(LCMP);
        } else if (common == PrimitiveKind.FLOAT) {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
        }
        int jump = switch (b.op()) {
            case LT -> branchIfTrue ? IFLT : IFGE;
            case LTE -> branchIfTrue ? IFLE : IFGT;
            case EQ -> branchIfTrue ? IFEQ : IFNE;
            case NEQ -> branchIfTrue ? IFNE : IFEQ;
            case GTE -> branchIfTrue ? IFGE : IFLT;
            case GT -> branchIfTrue ? IFGT : IFLE;
            default -> throw new IllegalStateException();
        };
        mv.visitJumpInsn(jump, target);
    }

    private static int icmpJump(HirExpr.BinaryOp op, boolean branchIfTrue) {
        return switch (op) {
            case LT -> branchIfTrue ? IF_ICMPLT : IF_ICMPGE;
            case LTE -> branchIfTrue ? IF_ICMPLE : IF_ICMPGT;
            case EQ -> branchIfTrue ? IF_ICMPEQ : IF_ICMPNE;
            case NEQ -> branchIfTrue ? IF_ICMPNE : IF_ICMPEQ;
            case GTE -> branchIfTrue ? IF_ICMPGE : IF_ICMPLT;
            case GT -> branchIfTrue ? IF_ICMPGT : IF_ICMPLE;
            default -> throw new IllegalStateException();
        };
    }

    // ---------------------------------------------------------------- type operations

    void emitIs(HirExpr.Is i) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = i.meta().line();
        emitter.emitBoxed(i.left());
        if (i.builtin() != null) {
            mv.visitLdcInsn(i.builtin());
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Values", "isBuiltin", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        String targetJvm = resolveIsTargetJvm(i.target());
        if (targetJvm != null) {
            if (i.target().jvmInternalName() != null) {
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            c.line(ln);
            mv.visitTypeInsn(INSTANCEOF, targetJvm);
            c.stackKind = PrimitiveKind.BOOL;
            return;
        }
        if (!i.intersectionTargets().isEmpty()) {
            List<String> jvms = new ArrayList<>(i.intersectionTargets().size());
            boolean unwrap = false;
            for (LoweringTargets.IsTarget target : i.intersectionTargets()) {
                String jvm = resolveIsTargetJvm(target);
                if (jvm == null) {
                    jvms.clear();
                    break;
                }
                if (target.jvmInternalName() != null) {
                    unwrap = true;
                }
                jvms.add(jvm);
            }
            if (!jvms.isEmpty()) {
                if (unwrap) {
                    mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                }
                c.line(ln);
                if (jvms.size() == 1) {
                    mv.visitTypeInsn(INSTANCEOF, jvms.get(0));
                } else {
                    Label fail = new Label();
                    Label done = new Label();
                    for (int k = 0; k < jvms.size() - 1; k++) {
                        mv.visitInsn(DUP);
                        mv.visitTypeInsn(INSTANCEOF, jvms.get(k));
                        mv.visitJumpInsn(IFEQ, fail);
                    }
                    mv.visitTypeInsn(INSTANCEOF, jvms.get(jvms.size() - 1));
                    mv.visitJumpInsn(GOTO, done);
                    mv.visitLabel(fail);
                    mv.visitInsn(POP);
                    mv.visitInsn(ICONST_0);
                    mv.visitLabel(done);
                }
                c.stackKind = PrimitiveKind.BOOL;
                return;
            }
        }
        if (i.descriptor() == null) {
            mv.visitInsn(POP);
            mv.visitInsn(ICONST_1);
            c.stackKind = PrimitiveKind.BOOL;
            return;
        }
        ConstantPool.pushString(mv, i.descriptor());
        c.line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Types", "matches", "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
        c.stackKind = PrimitiveKind.BOOL;
    }

    private String resolveIsTargetJvm(LoweringTargets.IsTarget target) {
        if (target == null) {
            return null;
        }
        if (target.jvmInternalName() != null) {
            return target.jvmInternalName();
        }
        return target.kind() == ClassShape.Kind.INTERFACE
                ? emitter.allInterfaceJvmNames.get(target.typeId())
                : emitter.allClassJvmNames.get(target.typeId());
    }

    void emitAsExpr(HirExpr.As a) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (a.targetDescriptor() == null && a.samTarget() == null) {
            emitter.emitAndWiden(a.left());
            return;
        }
        emitter.emitBoxed(a.left());
        if (a.samTarget() != null) {
            emitToJavaSamOnStack(a.samTarget(), a.meta().line());
        }
        if (a.targetDescriptor() == null) {
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        String targetJvm = resolveIsTargetJvm(a.target());
        if (targetJvm != null) {
            Label slow = new Label();
            Label done = new Label();
            mv.visitInsn(DUP);
            mv.visitTypeInsn(INSTANCEOF, targetJvm);
            mv.visitJumpInsn(IFEQ, slow);
            mv.visitTypeInsn(CHECKCAST, targetJvm);
            mv.visitJumpInsn(GOTO, done);
            mv.visitLabel(slow);
            c.line(a.meta().line());
            checkType(a.targetDescriptor());
            mv.visitLabel(done);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        c.line(a.meta().line());
        checkType(a.targetDescriptor());
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitJavaSamAdapt(HirExpr.JavaSamAdapt j) {
        MethodEmitContext c = ctx();
        emitter.emitBoxed(j.value());
        emitToJavaSamOnStack(j.target(), j.meta().line());
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitToJavaSamOnStack(LoweringTargets.JavaSamTarget target, int ln) {
        if (target == null) {
            return;
        }
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        Class<?> sam = target.samInterface();
        Class<?>[] markers = target.markers();
        boolean accessible = JavaTypes.isAccessible(sam);
        if (accessible) {
            for (Class<?> marker : markers) {
                if (!JavaTypes.isAccessible(marker)) {
                    accessible = false;
                    break;
                }
            }
        }
        if (accessible) {
            mv.visitLdcInsn(Type.getType(sam));
            if (markers.length == 0) {
                mv.visitInsn(ACONST_NULL);
            } else {
                String field = emitter.constantPool.classArrayField(markers);
                mv.visitFieldInsn(GETSTATIC, emitter.className, field, "[Ljava/lang/Class;");
            }
            mv.visitVarInsn(ALOAD, c.rtLocal);
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJavaSam",
                    "(Ljava/lang/Object;Ljava/lang/Class;[Ljava/lang/Class;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
        } else {
            mv.visitLdcInsn(sam.getName());
            if (markers.length == 0) {
                mv.visitInsn(ACONST_NULL);
            } else {
                String[] names = new String[markers.length];
                for (int i = 0; i < markers.length; i++) {
                    names[i] = markers[i].getName();
                }
                String field = emitter.constantPool.stringArrayField(names);
                mv.visitFieldInsn(GETSTATIC, emitter.className, field, "[Ljava/lang/String;");
            }
            mv.visitVarInsn(ALOAD, c.rtLocal);
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "toJavaSamByName",
                    "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/String;L" + SCRIPT_RUNTIME + ";)Ljava/lang/Object;", false);
        }
    }

    void emitNonNull(HirExpr.NonNull n) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (n.expectedType() == null) {
            emitter.emitExpr(n.operand());
            return;
        }
        emitter.emitBoxed(n.operand());
        mv.visitLdcInsn(n.expectedType());
        c.line(n.meta().line());
        mv.visitMethodInsn(INVOKESTATIC, JAVA_TYPES, "checkNotNull", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        PrimitiveKind targetKind = n.meta().kind();
        if (targetKind.isPrimitive()) {
            unboxOn(mv, targetKind);
            c.stackKind = targetKind;
        } else {
            c.stackKind = PrimitiveKind.NONE;
        }
    }

    void checkType(String descriptor) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        String field = emitter.constantPool.typeField(descriptor);
        mv.visitFieldInsn(GETSTATIC, emitter.className, field, "L" + TYPES_TYPE + ";");
        mv.visitMethodInsn(INVOKESTATIC, TYPES, "check", "(Ljava/lang/Object;L" + TYPES_TYPE + ";)Ljava/lang/Object;", false);
        c.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- control flow

    void emitBlock(HirExpr.Block b) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (!b.declaredVars().isEmpty()) {
            emitter.inFrameScope(() -> {
                emitter.declareVariables(b.declaredVars());
                emitter.statements(b.statements());
            });
        } else if (b.opensNamespaceScope()) {
            mv.visitVarInsn(ALOAD, c.nsLocal);
            emitter.getStringArray(b.declaredNames());
            mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "child", "([Ljava/lang/String;)L" + NAMESPACE + ";", false);
            int namespace = c.newLocal();
            mv.visitVarInsn(ASTORE, namespace);
            emitter.withNamespace(namespace, () -> emitter.statements(b.statements()));
        } else {
            emitter.statements(b.statements());
        }
        emitter.widenStackRank(b.innerWideningRank());
    }

    void emitIf(HirExpr.If i) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        PrimitiveKind target = i.meta().kind();
        Label otherwise = new Label();
        Label end = new Label();
        emitCondition(i.cond(), false, otherwise);
        emitter.emitAs(i.thenBranch(), target);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(otherwise);
        if (i.elseBranch() != null) {
            emitter.emitAs(i.elseBranch(), target);
        } else {
            mv.visitInsn(ACONST_NULL);
            emitter.convertStack(PrimitiveKind.NONE, target);
        }
        mv.visitLabel(end);
        c.stackKind = target;
    }

    void emitWhile(HirExpr.While w) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        Label head = new Label();
        Label end = new Label();
        int result = c.newLocal();
        mv.visitInsn(ACONST_NULL);
        mv.visitVarInsn(ASTORE, result);
        mv.visitLabel(head);
        emitCondition(w.cond(), false, end);
        c.loopTargets.push(new MethodEmitContext.LoopTarget(end, head, result));
        try {
            emitter.emitBoxed(w.body());
        } finally {
            c.loopTargets.pop();
        }
        mv.visitVarInsn(ASTORE, result);
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        mv.visitVarInsn(ALOAD, result);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitFor(HirExpr.For f) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = f.meta().line();
        LoweringTargets.ForLoopTarget target = f.target();
        LoweringTargets.ForKind kind = target != null ? target.kind() : LoweringTargets.ForKind.DYNAMIC_UNION;
        Label head = new Label();
        Label end = new Label();
        int result = c.newLocal();
        mv.visitInsn(ACONST_NULL);
        mv.visitVarInsn(ASTORE, result);

        if (kind == LoweringTargets.ForKind.COUNTED_RANGE && f.countedRange() != null) {
            emitCountedRangeFor(f, result, head, end, ln);
            return;
        }
        if (kind == LoweringTargets.ForKind.ARRAY && target.arrayClass() != null) {
            emitArrayFor(f, target.arrayClass(), result, head, end);
            return;
        }
        if (kind == LoweringTargets.ForKind.JAVA_ITERABLE || kind == LoweringTargets.ForKind.JAVA_ITERATOR
                || kind == LoweringTargets.ForKind.BABBLE_TO_JAVA_ITERABLE) {
            emitJavaIteratorFor(f, kind, result, head, end, ln);
            return;
        }
        if (kind == LoweringTargets.ForKind.BABBLE_ITERABLE || kind == LoweringTargets.ForKind.BABBLE_ITERATOR) {
            emitBabbleIteratorFor(f, kind, target, result, head, end, ln);
            return;
        }
        emitDynamicUnionFor(f, result, end);
    }

    private void emitCountedRangeFor(HirExpr.For f, int result, Label head, Label end, int ln) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        HirExpr.CountedRangeLoop cr = f.countedRange();
        PrimitiveKind rk = cr.elemKind();
        boolean isLong = rk == PrimitiveKind.LONG;
        PrimitiveKind loopKind = isLong ? PrimitiveKind.LONG : PrimitiveKind.INT;
        String rangeOwner = RUNTIME + "Range";

        // Evaluate start, end, and optional step once, left to right.
        int startLocal = c.newLocal(loopKind);
        int endLocal = c.newLocal(loopKind);
        emitter.emitAs(cr.start(), rk);
        storeKindOn(mv, loopKind, startLocal);
        emitter.emitAs(cr.end(), rk);
        storeKindOn(mv, loopKind, endLocal);

        long constStep = -1L;
        int stepLocal = -1;
        if (cr.step() == null) {
            constStep = 1L;
        } else if (cr.step() instanceof HirExpr.Const sc && sc.value() instanceof Number sn && sn.longValue() > 0L) {
            constStep = isLong ? sn.longValue() : sn.intValue();
        } else {
            stepLocal = c.newLocal(loopKind);
            emitter.emitAs(cr.step(), loopKind);
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "validateStep", isLong ? "(J)J" : "(I)I", true);
            storeKindOn(mv, loopKind, stepLocal);
        }

        // Exit immediately if range is empty.
        if (isLong) {
            mv.visitVarInsn(LLOAD, startLocal);
            mv.visitVarInsn(LLOAD, endLocal);
            mv.visitInsn(LCMP);
            mv.visitJumpInsn(cr.inclusive() ? IFGT : IFGE, end);
        } else {
            mv.visitVarInsn(ILOAD, startLocal);
            mv.visitVarInsn(ILOAD, endLocal);
            mv.visitJumpInsn(cr.inclusive() ? IF_ICMPGT : IF_ICMPGE, end);
        }

        // Precompute overflow-safe last included element.
        int lastLocal = c.newLocal(loopKind);
        if (constStep == 1L) {
            if (cr.inclusive()) {
                loadKindOn(mv, loopKind, endLocal);
                storeKindOn(mv, loopKind, lastLocal);
            } else if (isLong) {
                mv.visitVarInsn(LLOAD, endLocal);
                mv.visitInsn(LCONST_1);
                mv.visitInsn(LSUB);
                mv.visitVarInsn(LSTORE, lastLocal);
            } else {
                mv.visitVarInsn(ILOAD, endLocal);
                mv.visitInsn(ICONST_1);
                mv.visitInsn(ISUB);
                mv.visitVarInsn(ISTORE, lastLocal);
            }
        } else {
            loadKindOn(mv, loopKind, startLocal);
            loadKindOn(mv, loopKind, endLocal);
            mv.visitInsn(cr.inclusive() ? ICONST_1 : ICONST_0);
            if (stepLocal >= 0) {
                loadKindOn(mv, loopKind, stepLocal);
            } else if (isLong) {
                mv.visitLdcInsn(constStep);
            } else {
                ConstantPool.pushInt(mv, (int) constStep);
            }
            if (isLong) {
                mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "lastLongElement", "(JJZJ)J", true);
            } else if (rk == PrimitiveKind.CHAR) {
                mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "lastCharElement", "(CCZI)C", true);
            } else {
                mv.visitMethodInsn(INVOKESTATIC, rangeOwner, "lastIntElement", "(IIZI)I", true);
            }
            storeKindOn(mv, loopKind, lastLocal);
        }

        int idxLocal = cr.descending() ? lastLocal : startLocal;
        int stopLocal = cr.descending() ? startLocal : lastLocal;
        Label stepLabel = new Label();

        mv.visitLabel(head);
        loadKindOn(mv, loopKind, idxLocal);
        emitForIterationBody(f, result, end, stepLabel, rk);

        mv.visitLabel(stepLabel);
        if (isLong) {
            mv.visitVarInsn(LLOAD, idxLocal);
            mv.visitVarInsn(LLOAD, stopLocal);
            mv.visitInsn(LCMP);
            mv.visitJumpInsn(IFEQ, end);
            mv.visitVarInsn(LLOAD, idxLocal);
            if (stepLocal >= 0) {
                mv.visitVarInsn(LLOAD, stepLocal);
            } else {
                mv.visitLdcInsn(constStep);
            }
            mv.visitInsn(cr.descending() ? LSUB : LADD);
            mv.visitVarInsn(LSTORE, idxLocal);
        } else {
            mv.visitVarInsn(ILOAD, idxLocal);
            mv.visitVarInsn(ILOAD, stopLocal);
            mv.visitJumpInsn(IF_ICMPEQ, end);
            if (stepLocal < 0 && constStep <= Short.MAX_VALUE) {
                mv.visitIincInsn(idxLocal, cr.descending() ? -(int) constStep : (int) constStep);
            } else {
                mv.visitVarInsn(ILOAD, idxLocal);
                if (stepLocal >= 0) {
                    mv.visitVarInsn(ILOAD, stepLocal);
                } else {
                    ConstantPool.pushInt(mv, (int) constStep);
                }
                mv.visitInsn(cr.descending() ? ISUB : IADD);
                mv.visitVarInsn(ISTORE, idxLocal);
            }
        }
        mv.visitJumpInsn(GOTO, head);

        mv.visitLabel(end);
        mv.visitVarInsn(ALOAD, result);
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitArrayFor(HirExpr.For f, Class<?> arrClass, int result, Label head, Label end) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        Class<?> comp = arrClass.getComponentType();
        String arrDesc = Type.getDescriptor(arrClass);
        int arrLocal = c.newLocal();
        int lenLocal = c.newLocal(PrimitiveKind.INT);
        int idxLocal = c.newLocal(PrimitiveKind.INT);
        Label step = new Label();

        emitter.emitBoxed(f.iterable());
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, arrDesc);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, arrLocal);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitVarInsn(ISTORE, lenLocal);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, idxLocal);

        mv.visitLabel(head);
        mv.visitVarInsn(ILOAD, idxLocal);
        mv.visitVarInsn(ILOAD, lenLocal);
        mv.visitJumpInsn(IF_ICMPGE, end);

        mv.visitVarInsn(ALOAD, arrLocal);
        mv.visitVarInsn(ILOAD, idxLocal);
        PrimitiveKind elemStackKind = javaPrimitiveKind(comp);
        if (elemStackKind != PrimitiveKind.NONE) {
            int aload = switch (elemStackKind) {
                case BYTE, BOOL -> BALOAD;
                case SHORT -> SALOAD;
                case INT -> IALOAD;
                case LONG -> LALOAD;
                case FLOAT -> FALOAD;
                case DOUBLE -> DALOAD;
                case CHAR -> CALOAD;
                case NONE -> AALOAD;
            };
            mv.visitInsn(aload);
        } else {
            mv.visitInsn(AALOAD);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        }
        emitForIterationBody(f, result, end, step, elemStackKind);
        mv.visitLabel(step);
        mv.visitIincInsn(idxLocal, 1);
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        mv.visitVarInsn(ALOAD, result);
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitJavaIteratorFor(HirExpr.For f, LoweringTargets.ForKind kind, int result, Label head, Label end, int ln) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int itLocal = c.newLocal();
        if (kind == LoweringTargets.ForKind.BABBLE_TO_JAVA_ITERABLE) {
            int recvLocal = c.newLocal();
            emitter.emitBoxed(f.iterable());
            mv.visitVarInsn(ASTORE, recvLocal);
            emitZeroArgBabbleCall(recvLocal, "iterator", null, ln);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
        } else {
            emitter.emitBoxed(f.iterable());
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            if (kind == LoweringTargets.ForKind.JAVA_ITERABLE) {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
                c.line(ln);
                mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
            } else {
                mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
            }
        }
        mv.visitVarInsn(ASTORE, itLocal);

        mv.visitLabel(head);
        mv.visitVarInsn(ALOAD, itLocal);
        c.line(ln);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(IFEQ, end);
        mv.visitVarInsn(ALOAD, itLocal);
        c.line(ln);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        emitForIterationBody(f, result, end, head, PrimitiveKind.NONE);
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        mv.visitVarInsn(ALOAD, result);
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitBabbleIteratorFor(HirExpr.For f, LoweringTargets.ForKind kind,
                                       LoweringTargets.ForLoopTarget target, int result, Label head, Label end, int ln) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int iterLocal = c.newLocal();
        emitter.emitBoxed(f.iterable());
        mv.visitVarInsn(ASTORE, iterLocal);
        if (kind == LoweringTargets.ForKind.BABBLE_ITERABLE) {
            emitZeroArgBabbleCall(iterLocal, "iterator", null, ln);
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            mv.visitVarInsn(ASTORE, iterLocal);
        }
        mv.visitLabel(head);
        emitZeroArgBabbleCall(iterLocal, "hasNext", target != null ? target.hasNextSig() : null, ln);
        if (c.stackKind == PrimitiveKind.BOOL) {
            mv.visitJumpInsn(IFEQ, end);
        } else {
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(IFEQ, end);
        }
        emitZeroArgBabbleCall(iterLocal, "next", target != null ? target.nextSig() : null, ln);
        emitForIterationBody(f, result, end, head, c.stackKind);
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        mv.visitVarInsn(ALOAD, result);
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitDynamicUnionFor(HirExpr.For f, int result, Label end) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int rawLocal = c.newLocal();
        int idxLocal = c.newLocal(PrimitiveKind.INT);
        int lenLocal = c.newLocal(PrimitiveKind.INT);
        int itLocal = c.newLocal();
        Label arrLoop = new Label();
        Label arrStep = new Label();
        Label notArr = new Label();
        Label notIter = new Label();
        Label iterLoop = new Label();

        emitter.emitBoxed(f.iterable());
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, rawLocal);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "isArray", "()Z", false);
        mv.visitJumpInsn(IFEQ, notArr);

        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/reflect/Array", "getLength", "(Ljava/lang/Object;)I", false);
        mv.visitVarInsn(ISTORE, lenLocal);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, idxLocal);
        mv.visitLabel(arrLoop);
        mv.visitVarInsn(ILOAD, idxLocal);
        mv.visitVarInsn(ILOAD, lenLocal);
        mv.visitJumpInsn(IF_ICMPGE, end);
        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitVarInsn(ILOAD, idxLocal);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/reflect/Array", "get", "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        emitForIterationBody(f, result, end, arrStep, PrimitiveKind.NONE);
        mv.visitLabel(arrStep);
        mv.visitIincInsn(idxLocal, 1);
        mv.visitJumpInsn(GOTO, arrLoop);

        mv.visitLabel(notArr);
        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitTypeInsn(INSTANCEOF, "java/util/Iterator");
        mv.visitJumpInsn(IFEQ, notIter);
        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
        mv.visitVarInsn(ASTORE, itLocal);
        mv.visitJumpInsn(GOTO, iterLoop);

        mv.visitLabel(notIter);
        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
        mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(ASTORE, itLocal);

        mv.visitLabel(iterLoop);
        mv.visitVarInsn(ALOAD, itLocal);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(IFEQ, end);
        mv.visitVarInsn(ALOAD, itLocal);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        emitForIterationBody(f, result, end, iterLoop, PrimitiveKind.NONE);
        mv.visitJumpInsn(GOTO, iterLoop);

        mv.visitLabel(end);
        mv.visitVarInsn(ALOAD, result);
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitForIterationBody(HirExpr.For f, int resultSlot, Label breakLabel, Label continueLabel,
                                      PrimitiveKind elemStackKind) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        LoweringTargets.ForLoopTarget target = f.target();
        c.stackKind = elemStackKind;
        if (target != null && target.elemCheck() != null) {
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            c.line(f.meta().line());
            checkType(target.elemCheck());
            c.stackKind = PrimitiveKind.NONE;
        }
        if (f.elemSamTarget() != null) {
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            emitToJavaSamOnStack(f.elemSamTarget(), f.meta().line());
            c.stackKind = PrimitiveKind.NONE;
        }
        emitter.widenStackRank(f.meta().wideningRank());
        PrimitiveKind varKind = target != null ? target.elemKind() : PrimitiveKind.NONE;
        emitter.convertStack(c.stackKind, varKind);
        int elemTmp = c.newLocal(varKind);
        storeKindOn(mv, varKind, elemTmp);

        c.loopTargets.push(new MethodEmitContext.LoopTarget(breakLabel, continueLabel, resultSlot));
        try {
            emitter.inFrameScope(() -> {
                emitter.declareVariables(f.bodyDeclaredVars());
                if (f.loopVar() != null) {
                    loadKindOn(mv, varKind, elemTmp);
                    emitter.initVariable(f.loopVar(), varKind);
                }
                emitter.statements(f.body());
                emitter.widenStackRank(f.bodyWideningRank());
                emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
                mv.visitVarInsn(ASTORE, resultSlot);
            });
        } finally {
            c.loopTargets.pop();
        }
    }

    void emitZeroArgBabbleCall(int receiverLocal, String memberName,
                               LoweringTargets.FunctionSignature sig, int lineNumber) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        String site = emitter.constantPool.callSiteField(new CallSite(emitter.sourceName, lineNumber, memberName, new String[0]));
        mv.visitVarInsn(ALOAD, receiverLocal);
        c.line(lineNumber);
        mv.visitInvokeDynamicInsn("select", "(Ljava/lang/Object;)Ljava/lang/Object;", SELECT_BOOTSTRAP, memberName, memberName);
        mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Calls", "callable", "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";", false);
        mv.visitVarInsn(ALOAD, c.rtLocal);
        if (sig != null && sig.returnKind().isPrimitive()) {
            mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
            String desc = "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";L" + CALL_SITE + ";)" + jvmDescriptor(sig.returnKind());
            c.line(lineNumber);
            mv.visitInvokeDynamicInsn("typedCall", desc, TYPED_CALL_BOOTSTRAP);
            c.stackKind = sig.returnKind();
        } else {
            mv.visitInsn(ACONST_NULL);
            mv.visitFieldInsn(GETSTATIC, emitter.className, site, "L" + CALL_SITE + ";");
            c.line(lineNumber);
            mv.visitInvokeDynamicInsn("call", "(L" + CALLABLE + ";L" + SCRIPT_RUNTIME + ";[Ljava/lang/String;L" + CALL_SITE + ";)Ljava/lang/Object;", CALL_BOOTSTRAP);
            c.stackKind = PrimitiveKind.NONE;
        }
    }

    void emitBreak(HirExpr.Break b) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        MethodEmitContext.LoopTarget loop = c.loopTargets.peek();
        if (b.value() != null) {
            emitter.emitAndWiden(b.value());
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitVarInsn(ASTORE, loop.resultSlot());
        int currentLoopDepth = c.loopTargets.size();
        List<MethodEmitContext.ActiveFinally> popped = unwindFinallies(currentLoopDepth);
        mv.visitJumpInsn(GOTO, loop.breakLabel());
        restoreFinallies(popped);
        mv.visitInsn(ACONST_NULL);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitContinue() {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        MethodEmitContext.LoopTarget loop = c.loopTargets.peek();
        int currentLoopDepth = c.loopTargets.size();
        List<MethodEmitContext.ActiveFinally> popped = unwindFinallies(currentLoopDepth);
        mv.visitJumpInsn(GOTO, loop.continueLabel());
        restoreFinallies(popped);
        mv.visitInsn(ACONST_NULL);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitReturn(HirExpr.Return r) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        emitter.emitAndWiden(r.value());
        emitter.widenStackRank(r.meta().wideningRank());
        emitter.convertStack(c.stackKind, c.methodReturnKind);
        if (!c.activeFinallies.isEmpty()) {
            int retTmp = c.newLocal(c.methodReturnKind);
            storeKindOn(mv, c.methodReturnKind, retTmp);
            List<MethodEmitContext.ActiveFinally> popped = unwindFinallies(0);
            loadKindOn(mv, c.methodReturnKind, retTmp);
            mv.visitInsn(returnInsn(c.methodReturnKind));
            restoreFinallies(popped);
        } else {
            mv.visitInsn(returnInsn(c.methodReturnKind));
        }
        mv.visitInsn(ACONST_NULL);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitThrow(HirExpr.Throw th) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        emitter.emitBoxed(th.value());
        c.line(th.meta().line());
        if (th.isJavaThrowable()) {
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, "java/lang/Throwable");
        } else {
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Throwables", "toThrowable",
                    "(Ljava/lang/Object;)Ljava/lang/Throwable;", false);
        }
        mv.visitInsn(ATHROW);
        mv.visitInsn(ACONST_NULL);
        c.stackKind = PrimitiveKind.NONE;
    }

    private List<MethodEmitContext.ActiveFinally> unwindFinallies(int minLoopDepth) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (c.activeFinallies.isEmpty()) {
            return List.of();
        }
        List<MethodEmitContext.ActiveFinally> popped = new ArrayList<>();
        while (!c.activeFinallies.isEmpty() && c.activeFinallies.peek().loopDepthAtEntry >= minLoopDepth) {
            MethodEmitContext.ActiveFinally af = c.activeFinallies.pop();
            popped.add(af);
            af.splitBefore(mv);
            if (af.hasFinally) {
                emitTryFinallyBlock(af.tryNode);
            }
        }
        return popped;
    }

    private void restoreFinallies(List<MethodEmitContext.ActiveFinally> popped) {
        if (popped.isEmpty()) {
            return;
        }
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        for (int i = popped.size() - 1; i >= 0; i--) {
            MethodEmitContext.ActiveFinally af = popped.get(i);
            c.activeFinallies.push(af);
            af.resumeAfter(mv);
        }
    }

    private void emitScopedBody(List<HirExpr> stmts, List<org.babblelang.ir.hir.HirVar> declVars,
                                boolean opensNs, String[] declNames) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (!declVars.isEmpty()) {
            emitter.inFrameScope(() -> {
                emitter.declareVariables(declVars);
                emitter.statements(stmts);
            });
        } else if (opensNs) {
            mv.visitVarInsn(ALOAD, c.nsLocal);
            emitter.getStringArray(declNames);
            mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "child", "([Ljava/lang/String;)L" + NAMESPACE + ";", false);
            int ns = c.newLocal();
            mv.visitVarInsn(ASTORE, ns);
            emitter.withNamespace(ns, () -> emitter.statements(stmts));
        } else {
            emitter.statements(stmts);
        }
    }

    private void emitTryFinallyBlock(HirExpr.Try t) {
        if (!t.hasFinally() || t.finallyBody().isEmpty()) {
            return;
        }
        MethodEmitContext c = ctx();
        emitScopedBody(t.finallyBody(), t.finallyDeclaredVars(), t.finallyOpensNamespaceScope(), t.finallyDeclaredNames());
        popOn(c.mv, c.stackKind);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitTry(HirExpr.Try t) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        PrimitiveKind targetKind = t.meta().kind();
        int resultLocal = c.newLocal(targetKind);
        boolean hasNonEmptyFinally = t.hasFinally() && !t.finallyBody().isEmpty();
        List<HirExpr.HirCatch> catches = t.catches();

        Label tryEnd = new Label();
        Label finallyAbnormal = hasNonEmptyFinally ? new Label() : null;

        // 1. Emit tryBody inside an ActiveFinally range (even without finally, so inner return/break
        //    running an outer finally splits this try's catch ranges cleanly!).
        Label tryStart = new Label();
        mv.visitLabel(tryStart);
        MethodEmitContext.ActiveFinally tryRange = new MethodEmitContext.ActiveFinally(
                t, c.loopTargets.size(), tryStart, hasNonEmptyFinally);
        c.activeFinallies.push(tryRange);
        try {
            emitScopedBody(t.tryBody(), t.tryDeclaredVars(), t.tryOpensNamespaceScope(), t.tryDeclaredNames());
            emitter.widenStackRank(t.tryWideningRank());
            emitter.convertStack(c.stackKind, targetKind);
            storeKindOn(mv, targetKind, resultLocal);
        } finally {
            c.activeFinallies.pop();
        }
        tryRange.close(mv);
        if (hasNonEmptyFinally) {
            emitTryFinallyBlock(t);
        }
        mv.visitJumpInsn(GOTO, tryEnd);

        // 2. Determine whether we need a ThrownException dispatcher ahead of Java handlers.
        boolean needsThrownDispatcher = false;
        for (HirExpr.HirCatch cc : catches) {
            LoweringTargets.CatchTarget ct = cc.target();
            if (ct != null && (ct.catchesBabble() || ct.catchesNonErrorAny()
                    || ct.catchesJavaThrowable() || ct.skipsThrownException())) {
                needsThrownDispatcher = true;
                break;
            }
        }

        Label[] catchLabels = new Label[catches.size()];
        for (int i = 0; i < catches.size(); i++) {
            catchLabels[i] = new Label();
        }
        Label thrownExHandler = needsThrownDispatcher ? new Label() : null;

        // Register catch handlers for tryBody segments (innermost tries already registered theirs).
        if (needsThrownDispatcher) {
            for (int seg = 0; seg < tryRange.rangeStarts.size(); seg++) {
                mv.visitTryCatchBlock(tryRange.rangeStarts.get(seg), tryRange.rangeEnds.get(seg),
                        thrownExHandler, RUNTIME + "ThrownException");
            }
        }
        for (int i = 0; i < catches.size(); i++) {
            LoweringTargets.CatchTarget ct = catches.get(i).target();
            if (ct != null) {
                for (String jvmType : ct.jvmCatchTypes()) {
                    for (int seg = 0; seg < tryRange.rangeStarts.size(); seg++) {
                        mv.visitTryCatchBlock(tryRange.rangeStarts.get(seg), tryRange.rangeEnds.get(seg),
                                catchLabels[i], jvmType);
                    }
                }
            }
        }

        // 3. Emit ThrownException dispatcher (preserves exact source order across Java & Babble catches).
        if (needsThrownDispatcher) {
            mv.visitLabel(thrownExHandler);
            // Stack: [ThrownException]
            int teLocal = c.newLocal();
            mv.visitVarInsn(ASTORE, teLocal);
            int valLocal = -1;
            boolean guaranteedHandled = false;
            for (int i = 0; i < catches.size(); i++) {
                LoweringTargets.CatchTarget ct = catches.get(i).target();
                if (ct == null) {
                    continue;
                }
                if (ct.catchesJavaThrowable()) {
                    mv.visitVarInsn(ALOAD, teLocal);
                    mv.visitJumpInsn(GOTO, catchLabels[i]);
                    guaranteedHandled = true;
                    break;
                }
                if (ct.catchesNonErrorAny()) {
                    mv.visitVarInsn(ALOAD, teLocal);
                    mv.visitMethodInsn(INVOKEVIRTUAL, RUNTIME + "ThrownException", "value", "()Ljava/lang/Object;", false);
                    mv.visitJumpInsn(GOTO, catchLabels[i]);
                    guaranteedHandled = true;
                    break;
                }
                if (ct.catchesBabble()) {
                    if (valLocal < 0) {
                        valLocal = c.newLocal();
                        mv.visitVarInsn(ALOAD, teLocal);
                        mv.visitMethodInsn(INVOKEVIRTUAL, RUNTIME + "ThrownException", "value", "()Ljava/lang/Object;", false);
                        mv.visitVarInsn(ASTORE, valLocal);
                    }
                    Label nextClause = new Label();
                    Label matchedClause = new Label();
                    List<String> directJvms = resolveBabbleCatchJvms(ct.babbleTargets());
                    if (!directJvms.isEmpty()) {
                        for (String jvm : directJvms) {
                            mv.visitVarInsn(ALOAD, valLocal);
                            mv.visitTypeInsn(INSTANCEOF, jvm);
                            mv.visitJumpInsn(IFNE, matchedClause);
                        }
                        mv.visitJumpInsn(GOTO, nextClause);
                    } else if (ct.typeDescriptor() != null) {
                        mv.visitVarInsn(ALOAD, valLocal);
                        ConstantPool.pushString(mv, ct.typeDescriptor());
                        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Types", "matches",
                                "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
                        mv.visitJumpInsn(IFNE, matchedClause);
                        mv.visitJumpInsn(GOTO, nextClause);
                    } else {
                        mv.visitJumpInsn(GOTO, nextClause);
                    }
                    mv.visitLabel(matchedClause);
                    mv.visitVarInsn(ALOAD, valLocal);
                    mv.visitJumpInsn(GOTO, catchLabels[i]);
                    mv.visitLabel(nextClause);
                }
            }
            if (!guaranteedHandled) {
                // No catch clause matched the ThrownException: run finally (if any) or rethrow.
                mv.visitVarInsn(ALOAD, teLocal);
                if (hasNonEmptyFinally) {
                    mv.visitJumpInsn(GOTO, finallyAbnormal);
                } else {
                    mv.visitInsn(ATHROW);
                }
            }
        }

        // 4. Emit catch clauses (each covered by finallyAbnormal if hasNonEmptyFinally).
        List<MethodEmitContext.ActiveFinally> catchRanges = new ArrayList<>();
        for (int i = 0; i < catches.size(); i++) {
            HirExpr.HirCatch cc = catches.get(i);
            mv.visitLabel(catchLabels[i]);
            // Stack: [Object boundValue]
            int boundTmp = -1;
            if (cc.var() != null) {
                boundTmp = c.newLocal();
                mv.visitVarInsn(ASTORE, boundTmp);
            } else {
                mv.visitInsn(POP);
            }
            Label catchSegStart = new Label();
            mv.visitLabel(catchSegStart);
            MethodEmitContext.ActiveFinally catchRange = hasNonEmptyFinally
                    ? new MethodEmitContext.ActiveFinally(t, c.loopTargets.size(), catchSegStart, true)
                    : null;
            if (catchRange != null) {
                c.activeFinallies.push(catchRange);
            }
            try {
                final int boundSlot = boundTmp;
                if (!cc.declaredVars().isEmpty()) {
                    emitter.inFrameScope(() -> {
                        emitter.declareVariables(cc.declaredVars());
                        if (cc.var() != null) {
                            mv.visitVarInsn(ALOAD, boundSlot);
                            emitter.initVariable(cc.var(), PrimitiveKind.NONE);
                        }
                        emitter.statements(cc.body());
                    });
                } else if (cc.opensNamespaceScope()) {
                    mv.visitVarInsn(ALOAD, c.nsLocal);
                    emitter.getStringArray(cc.declaredNames());
                    mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "child", "([Ljava/lang/String;)L" + NAMESPACE + ";", false);
                    int ns = c.newLocal();
                    mv.visitVarInsn(ASTORE, ns);
                    emitter.withNamespace(ns, () -> emitter.statements(cc.body()));
                } else {
                    emitter.statements(cc.body());
                }
                emitter.widenStackRank(cc.wideningRank());
                emitter.convertStack(c.stackKind, targetKind);
                storeKindOn(mv, targetKind, resultLocal);
            } finally {
                if (catchRange != null) {
                    c.activeFinallies.pop();
                    catchRange.close(mv);
                    catchRanges.add(catchRange);
                }
            }
            if (hasNonEmptyFinally) {
                emitTryFinallyBlock(t);
            }
            mv.visitJumpInsn(GOTO, tryEnd);
        }

        // 5. Emit abnormal finally handler with automatic addSuppressed when !finallyNothrow (BIP028-D6).
        if (hasNonEmptyFinally) {
            for (int seg = 0; seg < tryRange.rangeStarts.size(); seg++) {
                mv.visitTryCatchBlock(tryRange.rangeStarts.get(seg), tryRange.rangeEnds.get(seg),
                        finallyAbnormal, null);
            }
            for (MethodEmitContext.ActiveFinally cr : catchRanges) {
                for (int seg = 0; seg < cr.rangeStarts.size(); seg++) {
                    mv.visitTryCatchBlock(cr.rangeStarts.get(seg), cr.rangeEnds.get(seg),
                            finallyAbnormal, null);
                }
            }
            mv.visitLabel(finallyAbnormal);
            // Stack: [Throwable primary]
            int primaryLocal = c.newLocal();
            mv.visitVarInsn(ASTORE, primaryLocal);
            if (t.finallyNothrow()) {
                emitTryFinallyBlock(t);
            } else {
                Label finStart = new Label();
                Label finEnd = new Label();
                Label suppressHandler = new Label();
                Label rethrowLabel = new Label();
                mv.visitLabel(finStart);
                emitTryFinallyBlock(t);
                mv.visitLabel(finEnd);
                mv.visitJumpInsn(GOTO, rethrowLabel);

                mv.visitLabel(suppressHandler);
                // Stack: [Throwable secondary]
                mv.visitVarInsn(ALOAD, primaryLocal);
                mv.visitInsn(SWAP);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Throwables", "addSuppressed",
                        "(Ljava/lang/Throwable;Ljava/lang/Throwable;)V", false);
                mv.visitLabel(rethrowLabel);
                mv.visitTryCatchBlock(finStart, finEnd, suppressHandler, "java/lang/Throwable");
            }
            mv.visitVarInsn(ALOAD, primaryLocal);
            mv.visitInsn(ATHROW);
        }

        // 6. Merge point: load result.
        mv.visitLabel(tryEnd);
        loadKindOn(mv, targetKind, resultLocal);
        c.stackKind = targetKind;
    }

    List<String> resolveBabbleCatchJvms(List<LoweringTargets.IsTarget> targets) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        List<String> jvms = new ArrayList<>(targets.size());
        for (LoweringTargets.IsTarget target : targets) {
            String jvm = resolveIsTargetJvm(target);
            if (jvm == null) {
                return List.of();
            }
            jvms.add(jvm);
        }
        return jvms;
    }
}
