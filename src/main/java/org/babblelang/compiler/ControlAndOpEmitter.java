package org.babblelang.compiler;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayList;
import java.util.List;
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

/**
 * Bytecode emission for unary/binary/logical operators, conditions, type checks/casts/SAM
 * adaptations, blocks, and conditionals, extracted from {@link HirEmitter} (BIP027-I2). Structured
 * loops live in {@link LoopEmitter} and exception handling / {@code return} live in {@link
 * TryCatchEmitter}.
 */
final class ControlAndOpEmitter {
    private final HirEmitter emitter;
    final LoopEmitter loops;
    final TryCatchEmitter tryCatch;

    ControlAndOpEmitter(HirEmitter emitter) {
        this.emitter = emitter;
        this.loops = new LoopEmitter(emitter, this);
        this.tryCatch = new TryCatchEmitter(emitter, this);
    }

    private MethodEmitContext ctx() {
        return emitter.ctx;
    }

    // ---------------------------------------------------------------- operators & conditions

    void emitUnary(HirExpr.Unary u) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (u.op() == HirExpr.UnaryOp.NOT) {
            JvmOpEmitter.emitBoolFromJump(
                    mv, c, isTrue -> emitCondition(u.operand(), false, isTrue));
            return;
        }
        if (JvmOpEmitter.emitPrimitiveNegate(
                mv,
                c,
                u.operand().meta().kind(),
                u.meta().kind(),
                target -> emitter.emitAs(u.operand(), target))) {
            return;
        }
        int operand = c.newLocal();
        emitter.emitBoxed(u.operand());
        mv.visitVarInsn(ASTORE, operand);
        Label number = new Label();
        Label end = new Label();
        mv.visitVarInsn(ALOAD, operand);
        mv.visitMethodInsn(
                INVOKESTATIC, BABBLE_OBJECTS, "isInstance", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, number);
        emitOperatorCall(u.meta().line(), u.operandText(), Operators.NEGATE, null, operand, -1);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(number);
        mv.visitVarInsn(ALOAD, operand);
        mv.visitLdcInsn(u.operandText());
        c.line(u.meta().line());
        mv.visitMethodInsn(
                INVOKESTATIC,
                RUNTIME + "Operators",
                "negate",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                false);
        mv.visitLabel(end);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitBinary(HirExpr.Binary b) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        if (b.op().isComparison() && canLowerComparison(b)) {
            JvmOpEmitter.emitBoolFromJump(mv, c, isTrue -> emitComparison(b, true, isTrue));
            return;
        }

        PrimitiveKind resultKind = b.meta().kind();
        PrimitiveKind lk = b.left().meta().kind();
        PrimitiveKind rk = b.right().meta().kind();
        long positiveRightConst = 0L;
        if ((b.op() == HirExpr.BinaryOp.MOD || b.op() == HirExpr.BinaryOp.FLOOR_DIV)
                && (resultKind == PrimitiveKind.INT || resultKind == PrimitiveKind.LONG)
                && b.right() instanceof HirExpr.Const rc
                && rc.value() instanceof Number n) {
            long d = resultKind == PrimitiveKind.LONG ? n.longValue() : n.intValue();
            if (d > 0L) {
                positiveRightConst = d;
            }
        }
        if (JvmOpEmitter.emitPrimitiveBinaryArith(
                mv,
                c,
                b.op(),
                lk,
                rk,
                resultKind,
                positiveRightConst,
                positiveRightConst > 0L
                        && (positiveRightConst & (positiveRightConst - 1L)) != 0L
                        && loops.isStaticallyNonNegative(b.left()),
                b.meta().line(),
                target -> emitter.emitAs(b.left(), target),
                target -> emitter.emitAs(b.right(), target))) {
            return;
        }

        String method =
                switch (b.op()) {
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
            mv.visitFieldInsn(
                    GETSTATIC,
                    emitter.className,
                    emitter.constantPool.opSiteField(b.leftText(), b.rightText()),
                    "L" + OP_SITE + ";");
            c.line(b.meta().line());
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    RUNTIME + "Operators",
                    method,
                    "(Ljava/lang/Object;Ljava/lang/Object;L" + OP_SITE + ";)Ljava/lang/Object;",
                    false);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        int left = c.newLocal();
        int right = c.newLocal();
        emitter.emitBoxed(b.left());
        mv.visitVarInsn(ASTORE, left);
        emitter.emitBoxed(b.right());
        mv.visitVarInsn(ASTORE, right);
        emitDynamicArithmetic(
                b.meta().line(), b.op().symbol(), b.leftText(), b.rightText(), left, right);
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
                mv.visitMethodInsn(
                        INVOKESTATIC, rangeOwner, "ofByte", "(BBZ)L" + RUNTIME + "IntRange;", true);
            }
            case SHORT -> {
                emitter.emitAs(r.start(), PrimitiveKind.SHORT);
                emitter.emitAs(r.end(), PrimitiveKind.SHORT);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        rangeOwner,
                        "ofShort",
                        "(SSZ)L" + RUNTIME + "IntRange;",
                        true);
            }
            case INT -> {
                emitter.emitAs(r.start(), PrimitiveKind.INT);
                emitter.emitAs(r.end(), PrimitiveKind.INT);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(
                        INVOKESTATIC, rangeOwner, "ofInt", "(IIZ)L" + RUNTIME + "IntRange;", true);
            }
            case LONG -> {
                emitter.emitAs(r.start(), PrimitiveKind.LONG);
                emitter.emitAs(r.end(), PrimitiveKind.LONG);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        rangeOwner,
                        "ofLong",
                        "(JJZ)L" + RUNTIME + "LongRange;",
                        true);
            }
            case CHAR -> {
                emitter.emitAs(r.start(), PrimitiveKind.CHAR);
                emitter.emitAs(r.end(), PrimitiveKind.CHAR);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        rangeOwner,
                        "ofChar",
                        "(CCZ)L" + RUNTIME + "CharRange;",
                        true);
            }
            case FLOAT -> {
                emitter.emitAs(r.start(), PrimitiveKind.FLOAT);
                emitter.emitAs(r.end(), PrimitiveKind.FLOAT);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        rangeOwner,
                        "ofFloat",
                        "(FFZ)L" + RUNTIME + "DoubleRange;",
                        true);
            }
            case DOUBLE -> {
                emitter.emitAs(r.start(), PrimitiveKind.DOUBLE);
                emitter.emitAs(r.end(), PrimitiveKind.DOUBLE);
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        rangeOwner,
                        "ofDouble",
                        "(DDZ)L" + RUNTIME + "DoubleRange;",
                        true);
            }
            default -> {
                emitter.emitBoxed(r.start());
                mv.visitTypeInsn(CHECKCAST, "java/lang/Comparable");
                emitter.emitBoxed(r.end());
                mv.visitTypeInsn(CHECKCAST, "java/lang/Comparable");
                mv.visitInsn(r.inclusive() ? ICONST_1 : ICONST_0);
                c.line(r.meta().line());
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        rangeOwner,
                        "ofComparable",
                        "(Ljava/lang/Comparable;Ljava/lang/Comparable;Z)L"
                                + RUNTIME
                                + "ComparableRange;",
                        true);
            }
        }
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitDynamicArithmetic(
            int lineNumber,
            String operator,
            String leftText,
            String rightText,
            int left,
            int right) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        String method =
                switch (operator) {
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
        mv.visitMethodInsn(
                INVOKESTATIC, BABBLE_OBJECTS, "isInstance", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, numbers);
        emitOperatorCall(lineNumber, leftText, Operators.method(operator), rightText, left, right);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(numbers);
        mv.visitVarInsn(ALOAD, left);
        mv.visitVarInsn(ALOAD, right);
        mv.visitFieldInsn(
                GETSTATIC,
                emitter.className,
                emitter.constantPool.opSiteField(leftText, rightText),
                "L" + OP_SITE + ";");
        c.line(lineNumber);
        mv.visitMethodInsn(
                INVOKESTATIC,
                RUNTIME + "Operators",
                method,
                "(Ljava/lang/Object;Ljava/lang/Object;L" + OP_SITE + ";)Ljava/lang/Object;",
                false);
        mv.visitLabel(end);
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitOperatorCall(
            int lineNumber,
            String receiver,
            String method,
            String argumentText,
            int receiverLocal,
            int argument) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        String[] argTexts = argumentText == null ? new String[0] : new String[] {argumentText};
        CallSite site =
                new CallSite(emitter.sourceName, lineNumber, receiver + "." + method, argTexts);
        String field = emitter.constantPool.callSiteField(site);
        mv.visitVarInsn(ALOAD, receiverLocal);
        c.line(lineNumber);
        mv.visitInvokeDynamicInsn(
                "select",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                SELECT_BOOTSTRAP,
                method,
                receiver);
        mv.visitFieldInsn(GETSTATIC, emitter.className, field, "L" + CALL_SITE + ";");
        mv.visitMethodInsn(
                INVOKESTATIC,
                RUNTIME + "Calls",
                "callable",
                "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";",
                false);
        mv.visitVarInsn(ALOAD, c.rtLocal);
        mv.visitInsn(ACONST_NULL);
        mv.visitFieldInsn(GETSTATIC, emitter.className, field, "L" + CALL_SITE + ";");
        if (argument >= 0) {
            mv.visitVarInsn(ALOAD, argument);
        }
        c.line(lineNumber);
        mv.visitInvokeDynamicInsn(
                "call",
                "(L"
                        + CALLABLE
                        + ";L"
                        + SCRIPT_RUNTIME
                        + ";[Ljava/lang/String;L"
                        + CALL_SITE
                        + ";"
                        + (argument >= 0 ? "Ljava/lang/Object;" : "")
                        + ")Ljava/lang/Object;",
                CALL_BOOTSTRAP);
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
            case BOOL, BYTE, SHORT, INT, CHAR ->
                    mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
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
                mv.visitMethodInsn(
                        INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(branchIfTrue ? IFNE : IFEQ, target);
            }
        }
    }

    private static boolean canLowerComparison(HirExpr.Binary b) {
        return org.babblelang.ir.hir.HirLoweringRules.canLowerComparison(b);
    }

    private void emitComparison(HirExpr.Binary b, boolean branchIfTrue, Label target) {
        JvmOpEmitter.emitPrimitiveComparisonJump(
                ctx().mv,
                b.op(),
                b.left().meta().kind(),
                b.right().meta().kind(),
                branchIfTrue,
                target,
                kind -> emitter.emitAs(b.left(), kind),
                kind -> emitter.emitAs(b.right(), kind));
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
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    RUNTIME + "Values",
                    "isBuiltin",
                    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                    false);
            c.stackKind = PrimitiveKind.NONE;
            return;
        }
        String targetJvm = resolveIsTargetJvm(i.target());
        if (targetJvm != null) {
            if (i.target().jvmInternalName() != null) {
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        VALUES,
                        "unwrap",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
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
                    mv.visitMethodInsn(
                            INVOKESTATIC,
                            VALUES,
                            "unwrap",
                            "(Ljava/lang/Object;)Ljava/lang/Object;",
                            false);
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
        mv.visitMethodInsn(
                INVOKESTATIC,
                RUNTIME + "Types",
                "matches",
                "(Ljava/lang/Object;Ljava/lang/String;)Z",
                false);
        c.stackKind = PrimitiveKind.BOOL;
    }

    String resolveIsTargetJvm(LoweringTargets.IsTarget target) {
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
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "toJavaSam",
                    "(Ljava/lang/Object;Ljava/lang/Class;[Ljava/lang/Class;L"
                            + SCRIPT_RUNTIME
                            + ";)Ljava/lang/Object;",
                    false);
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
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "toJavaSamByName",
                    "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/String;L"
                            + SCRIPT_RUNTIME
                            + ";)Ljava/lang/Object;",
                    false);
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
        mv.visitMethodInsn(
                INVOKESTATIC,
                JAVA_TYPES,
                "checkNotNull",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                false);
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
        mv.visitMethodInsn(
                INVOKESTATIC,
                TYPES,
                "check",
                "(Ljava/lang/Object;L" + TYPES_TYPE + ";)Ljava/lang/Object;",
                false);
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
            mv.visitMethodInsn(
                    INVOKEVIRTUAL,
                    NAMESPACE,
                    "child",
                    "([Ljava/lang/String;)L" + NAMESPACE + ";",
                    false);
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
}
