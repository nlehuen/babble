package org.babblelang.compiler;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.List;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.runtime.CallSite;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Bytecode emission for structured loops ({@code while} and {@code for} across counted ranges,
 * arrays, Java iterators, Babble iterators, and dynamic unions) and loop-control jumps ({@code
 * break}/{@code continue}), extracted from {@link ControlAndOpEmitter}.
 */
final class LoopEmitter {
    private final HirEmitter emitter;
    private final ControlAndOpEmitter controlOps;

    LoopEmitter(HirEmitter emitter, ControlAndOpEmitter controlOps) {
        this.emitter = emitter;
        this.controlOps = controlOps;
    }

    private MethodEmitContext ctx() {
        return emitter.ctx;
    }

    void emitWhile(HirExpr.While w) {
        emitWhile(w, true);
    }

    void emitWhile(HirExpr.While w, boolean valueNeeded) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        Label head = new Label();
        Label end = new Label();
        int result = -1;
        if (valueNeeded) {
            result = c.newLocal();
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, result);
        }
        mv.visitLabel(head);
        controlOps.emitCondition(w.cond(), false, end);
        c.loopTargets.push(new MethodEmitContext.LoopTarget(end, head, result));
        try {
            if (valueNeeded) {
                emitter.emitBoxed(w.body());
            } else {
                emitter.emitStmt(w.body());
            }
        } finally {
            c.loopTargets.pop();
        }
        if (valueNeeded) {
            mv.visitVarInsn(ASTORE, result);
        }
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        if (valueNeeded) {
            mv.visitVarInsn(ALOAD, result);
        }
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitFor(HirExpr.For f) {
        emitFor(f, true);
    }

    void emitFor(HirExpr.For f, boolean valueNeeded) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = f.meta().line();
        LoweringTargets.ForLoopTarget target = f.target();
        LoweringTargets.ForKind kind =
                target != null ? target.kind() : LoweringTargets.ForKind.DYNAMIC_UNION;
        Label head = new Label();
        Label end = new Label();
        int result = -1;
        if (valueNeeded) {
            result = c.newLocal();
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, result);
        }

        if (kind == LoweringTargets.ForKind.COUNTED_RANGE && f.countedRange() != null) {
            emitCountedRangeFor(f, result, head, end, ln);
            return;
        }
        if (kind == LoweringTargets.ForKind.ARRAY && target.arrayClass() != null) {
            emitArrayFor(f, target.arrayClass(), result, head, end);
            return;
        }
        if (kind == LoweringTargets.ForKind.JAVA_ITERABLE
                || kind == LoweringTargets.ForKind.JAVA_ITERATOR
                || kind == LoweringTargets.ForKind.BABBLE_TO_JAVA_ITERABLE) {
            emitJavaIteratorFor(f, kind, result, head, end, ln);
            return;
        }
        if (kind == LoweringTargets.ForKind.BABBLE_ITERABLE
                || kind == LoweringTargets.ForKind.BABBLE_ITERATOR) {
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
        } else if (cr.step() instanceof HirExpr.Const sc
                && sc.value() instanceof Number sn
                && sn.longValue() > 0L) {
            constStep = isLong ? sn.longValue() : sn.intValue();
        } else {
            stepLocal = c.newLocal(loopKind);
            emitter.emitAs(cr.step(), loopKind);
            c.line(ln);
            mv.visitMethodInsn(
                    INVOKESTATIC, rangeOwner, "validateStep", isLong ? "(J)J" : "(I)I", true);
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

        boolean markNonNeg =
                f.loopVar() != null
                        && !f.loopVar().isCaptured()
                        && (rk == PrimitiveKind.CHAR || isStaticallyNonNegative(cr.start()))
                        && !isVarAssignedIn(f.body(), f.loopVar().id());
        if (markNonNeg) {
            c.nonNegativeVars.add(f.loopVar().id());
        }
        mv.visitLabel(head);
        loadKindOn(mv, loopKind, idxLocal);
        try {
            emitForIterationBody(f, result, end, stepLabel, rk);
        } finally {
            if (markNonNeg) {
                c.nonNegativeVars.remove(f.loopVar().id());
            }
        }

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
        if (result >= 0) {
            mv.visitVarInsn(ALOAD, result);
        }
        c.stackKind = PrimitiveKind.NONE;
    }

    boolean isStaticallyNonNegative(HirExpr expr) {
        while (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
            expr = b.statements().get(0);
        }
        if (expr == null) {
            return false;
        }
        if (expr.meta().kind() == PrimitiveKind.CHAR) {
            return true;
        }
        return switch (expr) {
            case HirExpr.Const c ->
                    (c.value() instanceof Number n && n.longValue() >= 0L)
                            || c.value() instanceof Character;
            case HirExpr.VarRead v ->
                    v.var().primitiveKind() == PrimitiveKind.CHAR
                            || ctx().nonNegativeVars.contains(v.var().id());
            case HirExpr.ArrayOp op -> op.kind() == LoweringTargets.ArrayOpKind.SIZE;
            case HirExpr.CoreMethodCall c ->
                    "size".equals(c.memberName()) || "length".equals(c.memberName());
            case HirExpr.JavaMethodCall c -> {
                java.lang.reflect.Method m = c.target().method();
                yield ("length".equals(m.getName()) && m.getDeclaringClass() == String.class)
                        || ("size".equals(m.getName())
                                && (java.util.Collection.class.isAssignableFrom(
                                                m.getDeclaringClass())
                                        || java.util.Map.class.isAssignableFrom(
                                                m.getDeclaringClass())));
            }
            case HirExpr.Binary b ->
                    b.op() == HirExpr.BinaryOp.MOD
                            && (b.meta().kind() == PrimitiveKind.INT
                                    || b.meta().kind() == PrimitiveKind.LONG)
                            && b.right() instanceof HirExpr.Const rc
                            && rc.value() instanceof Number rn
                            && (b.meta().kind() == PrimitiveKind.LONG
                                    ? rn.longValue() > 0L
                                    : rn.intValue() > 0);
            default -> false;
        };
    }

    private static boolean isVarAssignedIn(List<HirExpr> stmts, int varId) {
        boolean[] assigned = {false};
        for (HirExpr s : stmts) {
            checkVarAssigned(s, varId, assigned);
            if (assigned[0]) {
                return true;
            }
        }
        return false;
    }

    private static void checkVarAssigned(HirExpr expr, int varId, boolean[] assigned) {
        if (expr == null || assigned[0]) {
            return;
        }
        if (expr instanceof HirExpr.VarAssign a && a.var().id() == varId) {
            assigned[0] = true;
            return;
        }
        org.babblelang.opt.HirRewriter.rewriteChildren(
                expr,
                child -> {
                    checkVarAssigned(child, varId, assigned);
                    return child;
                });
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
        mv.visitMethodInsn(
                INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
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
            int aload =
                    switch (elemStackKind) {
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
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "fromJava",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
        }
        emitForIterationBody(f, result, end, step, elemStackKind);
        mv.visitLabel(step);
        mv.visitIincInsn(idxLocal, 1);
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        if (result >= 0) {
            mv.visitVarInsn(ALOAD, result);
        }
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitJavaIteratorFor(
            HirExpr.For f,
            LoweringTargets.ForKind kind,
            int result,
            Label head,
            Label end,
            int ln) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int itLocal = c.newLocal();
        if (kind == LoweringTargets.ForKind.BABBLE_TO_JAVA_ITERABLE) {
            int recvLocal = c.newLocal();
            emitter.emitBoxed(f.iterable());
            mv.visitVarInsn(ASTORE, recvLocal);
            emitZeroArgBabbleCall(recvLocal, "iterator", null, ln);
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "unwrap",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
        } else {
            emitter.emitBoxed(f.iterable());
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    VALUES,
                    "unwrap",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            if (kind == LoweringTargets.ForKind.JAVA_ITERABLE) {
                mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
                c.line(ln);
                mv.visitMethodInsn(
                        INVOKEINTERFACE,
                        "java/lang/Iterable",
                        "iterator",
                        "()Ljava/util/Iterator;",
                        true);
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
        mv.visitMethodInsn(
                INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(
                INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        emitForIterationBody(f, result, end, head, PrimitiveKind.NONE);
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        if (result >= 0) {
            mv.visitVarInsn(ALOAD, result);
        }
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitBabbleIteratorFor(
            HirExpr.For f,
            LoweringTargets.ForKind kind,
            LoweringTargets.ForLoopTarget target,
            int result,
            Label head,
            Label end,
            int ln) {
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
        emitZeroArgBabbleCall(
                iterLocal, "hasNext", target != null ? target.hasNextSig() : null, ln);
        if (c.stackKind == PrimitiveKind.BOOL) {
            mv.visitJumpInsn(IFEQ, end);
        } else {
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            mv.visitMethodInsn(
                    INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(IFEQ, end);
        }
        emitZeroArgBabbleCall(iterLocal, "next", target != null ? target.nextSig() : null, ln);
        emitForIterationBody(f, result, end, head, c.stackKind);
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        if (result >= 0) {
            mv.visitVarInsn(ALOAD, result);
        }
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
        mv.visitMethodInsn(
                INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, rawLocal);
        mv.visitMethodInsn(
                INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "isArray", "()Z", false);
        mv.visitJumpInsn(IFEQ, notArr);

        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/reflect/Array",
                "getLength",
                "(Ljava/lang/Object;)I",
                false);
        mv.visitVarInsn(ISTORE, lenLocal);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, idxLocal);
        mv.visitLabel(arrLoop);
        mv.visitVarInsn(ILOAD, idxLocal);
        mv.visitVarInsn(ILOAD, lenLocal);
        mv.visitJumpInsn(IF_ICMPGE, end);
        mv.visitVarInsn(ALOAD, rawLocal);
        mv.visitVarInsn(ILOAD, idxLocal);
        mv.visitMethodInsn(
                INVOKESTATIC,
                "java/lang/reflect/Array",
                "get",
                "(Ljava/lang/Object;I)Ljava/lang/Object;",
                false);
        mv.visitMethodInsn(
                INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
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
        mv.visitVarInsn(ALOAD, c.rtLocal);
        emitter.constantPool.emitCallSite(
                mv,
                new CallSite(emitter.sourceName, f.meta().line(), "iterator", new String[0]),
                false);
        mv.visitMethodInsn(
                INVOKESTATIC,
                RUNTIME + "CoreCollections",
                "toJavaIterator",
                "(Ljava/lang/Object;L"
                        + SCRIPT_RUNTIME
                        + ";L"
                        + CALL_SITE
                        + ";)Ljava/util/Iterator;",
                false);
        mv.visitVarInsn(ASTORE, itLocal);

        mv.visitLabel(iterLoop);
        mv.visitVarInsn(ALOAD, itLocal);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitJumpInsn(IFEQ, end);
        mv.visitVarInsn(ALOAD, itLocal);
        mv.visitMethodInsn(
                INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(
                INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        emitForIterationBody(f, result, end, iterLoop, PrimitiveKind.NONE);
        mv.visitJumpInsn(GOTO, iterLoop);

        mv.visitLabel(end);
        if (result >= 0) {
            mv.visitVarInsn(ALOAD, result);
        }
        c.stackKind = PrimitiveKind.NONE;
    }

    private void emitForIterationBody(
            HirExpr.For f,
            int resultSlot,
            Label breakLabel,
            Label continueLabel,
            PrimitiveKind elemStackKind) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        LoweringTargets.ForLoopTarget target = f.target();
        c.stackKind = elemStackKind;
        if (target != null && target.elemCheck() != null) {
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            c.line(f.meta().line());
            controlOps.checkType(target.elemCheck());
            c.stackKind = PrimitiveKind.NONE;
        }
        if (f.elemSamTarget() != null) {
            emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            controlOps.emitToJavaSamOnStack(f.elemSamTarget(), f.meta().line());
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
                if (resultSlot >= 0) {
                    emitter.statements(f.body());
                    emitter.widenStackRank(f.bodyWideningRank());
                    emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
                    mv.visitVarInsn(ASTORE, resultSlot);
                } else {
                    emitter.statementsVoid(f.body());
                }
            });
        } finally {
            c.loopTargets.pop();
        }
    }

    void emitZeroArgBabbleCall(
            int receiverLocal,
            String memberName,
            LoweringTargets.FunctionSignature sig,
            int lineNumber) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        CallSite site = new CallSite(emitter.sourceName, lineNumber, memberName, new String[0]);
        mv.visitVarInsn(ALOAD, receiverLocal);
        c.line(lineNumber);
        mv.visitInvokeDynamicInsn(
                "select",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                SELECT_BOOTSTRAP,
                memberName,
                memberName);
        emitter.constantPool.emitCallSite(mv, site, false);
        mv.visitMethodInsn(
                INVOKESTATIC,
                RUNTIME + "Calls",
                "callable",
                "(Ljava/lang/Object;L" + CALL_SITE + ";)L" + CALLABLE + ";",
                false);
        mv.visitVarInsn(ALOAD, c.rtLocal);
        if (sig != null && sig.returnKind().isPrimitive()) {
            emitter.constantPool.emitCallSite(mv, site, false);
            String desc =
                    "(L"
                            + CALLABLE
                            + ";L"
                            + SCRIPT_RUNTIME
                            + ";L"
                            + CALL_SITE
                            + ";)"
                            + jvmDescriptor(sig.returnKind());
            c.line(lineNumber);
            mv.visitInvokeDynamicInsn("typedCall", desc, TYPED_CALL_BOOTSTRAP);
            c.stackKind = sig.returnKind();
        } else {
            mv.visitInsn(ACONST_NULL);
            emitter.constantPool.emitCallSite(mv, site, false);
            c.line(lineNumber);
            mv.visitInvokeDynamicInsn(
                    "call",
                    "(L"
                            + CALLABLE
                            + ";L"
                            + SCRIPT_RUNTIME
                            + ";[Ljava/lang/String;L"
                            + CALL_SITE
                            + ";)Ljava/lang/Object;",
                    CALL_BOOTSTRAP);
            c.stackKind = PrimitiveKind.NONE;
        }
    }

    void emitBreak(HirExpr.Break b) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        MethodEmitContext.LoopTarget loop = c.loopTargets.peek();
        if (loop.resultSlot() >= 0) {
            if (b.value() != null) {
                emitter.emitAndWiden(b.value());
                emitter.convertStack(c.stackKind, PrimitiveKind.NONE);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitVarInsn(ASTORE, loop.resultSlot());
        } else if (b.value() != null) {
            emitter.emitStmt(b.value());
        }
        int currentLoopDepth = c.loopTargets.size();
        List<MethodEmitContext.ActiveFinally> popped =
                controlOps.tryCatch.unwindFinallies(currentLoopDepth);
        mv.visitJumpInsn(GOTO, loop.breakLabel());
        controlOps.tryCatch.restoreFinallies(popped);
        mv.visitInsn(ACONST_NULL);
        c.stackKind = PrimitiveKind.NONE;
    }

    void emitContinue() {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        MethodEmitContext.LoopTarget loop = c.loopTargets.peek();
        int currentLoopDepth = c.loopTargets.size();
        List<MethodEmitContext.ActiveFinally> popped =
                controlOps.tryCatch.unwindFinallies(currentLoopDepth);
        mv.visitJumpInsn(GOTO, loop.continueLabel());
        controlOps.tryCatch.restoreFinallies(popped);
        mv.visitInsn(ACONST_NULL);
        c.stackKind = PrimitiveKind.NONE;
    }
}
