package org.babblelang.compiler;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.mir.MirInst;
import org.babblelang.ir.mir.MirLowerer;
import org.babblelang.ir.mir.MirTerminator;
import org.babblelang.ir.mir.MirVal;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * ABI delegation bridge for {@link MirEmitter}: lowers MIR instructions that share the Babble/Java
 * runtime ABI with {@link HirEmitter} (calls, fields, arrays, records, checks, ranges, iterators,
 * catch matching, and opaque templates) via {@link MethodEmitContext#mirOverrides}.
 */
final class MirAbiBridge {
    private final HirEmitter emitter;
    private final MethodEmitContext ctx;
    private final MethodVisitor mv;
    private final MirSlotAllocator plan;
    private final MirEmitter mirEmitter;

    MirAbiBridge(HirEmitter emitter, MirSlotAllocator plan, MirEmitter mirEmitter) {
        this.emitter = emitter;
        this.ctx = emitter.ctx;
        this.mv = ctx.mv;
        this.plan = plan;
        this.mirEmitter = mirEmitter;
    }

    // ------------------------------------------------------------------ unary / binary / range

    void emitUnaryFallback(MirInst.Unary u) {
        withSingle(
                u.operand(),
                u.line(),
                opPh ->
                        emitter.controlOps.emitUnary(
                                new HirExpr.Unary(
                                        u.op(),
                                        opPh,
                                        u.operandText(),
                                        ExprMeta.of(u.line(), u.result().kind()))));
        mirEmitter.storeResult(u.result());
    }

    void emitBinaryFallback(MirInst.Binary b) {
        withPair(
                b.left(),
                b.right(),
                b.line(),
                (lPh, rPh) ->
                        emitter.controlOps.emitBinary(
                                new HirExpr.Binary(
                                        b.op(),
                                        lPh,
                                        rPh,
                                        b.leftText(),
                                        b.rightText(),
                                        ExprMeta.of(b.line(), b.result().kind()))));
        mirEmitter.storeResult(b.result());
    }

    void emitRangeNew(MirInst.RangeNew rn) {
        withPair(
                rn.start(),
                rn.end(),
                rn.line(),
                (sPh, ePh) ->
                        emitter.controlOps.emitRange(
                                new HirExpr.Range(
                                        sPh,
                                        ePh,
                                        rn.inclusive(),
                                        rn.elemKind(),
                                        false,
                                        ExprMeta.of(rn.line(), PrimitiveKind.NONE))));
        mirEmitter.storeResult(rn.result());
    }

    // ------------------------------------------------------------------ type checks & fields

    void emitCheckCast(MirInst.CheckCast cc) {
        withSingle(
                cc.operand(),
                cc.line(),
                ph ->
                        emitter.controlOps.emitAsExpr(
                                new HirExpr.As(
                                        ph,
                                        cc.target(),
                                        cc.samTarget(),
                                        cc.descriptor(),
                                        ExprMeta.of(cc.line(), cc.result().kind()))));
        mirEmitter.storeResult(cc.result());
    }

    void emitNonNull(MirInst.NonNull nn) {
        withSingle(
                nn.operand(),
                nn.line(),
                ph ->
                        emitter.controlOps.emitNonNull(
                                new HirExpr.NonNull(
                                        ph,
                                        nn.expectedType(),
                                        ExprMeta.of(nn.line(), nn.result().kind()))));
        mirEmitter.storeResult(nn.result());
    }

    void emitIsCheck(MirInst.IsCheck ic) {
        withSingle(
                ic.operand(),
                ic.line(),
                ph ->
                        emitter.controlOps.emitIs(
                                new HirExpr.Is(
                                        ph,
                                        ic.builtin(),
                                        ic.target(),
                                        ic.intersectionTargets(),
                                        ic.descriptor(),
                                        ExprMeta.of(ic.line(), ic.result().kind()))));
        mirEmitter.storeResult(ic.result());
    }

    void emitLoadField(MirInst.LoadField lf) {
        withSingle(
                lf.receiver(),
                lf.line(),
                ph ->
                        emitter.assigns.emitThisFieldGet(
                                new HirExpr.ThisFieldGet(
                                        ph,
                                        lf.receiverText(),
                                        lf.classId(),
                                        lf.externalClassJvmName(),
                                        lf.memberIndex(),
                                        lf.memberName(),
                                        lf.fieldKind(),
                                        lf.immutableField(),
                                        lf.definitelyDefined(),
                                        ExprMeta.of(lf.line(), lf.result().kind()))));
        mirEmitter.storeResult(lf.result());
    }

    void emitNameAssign(MirInst.NameAssign na) {
        ExprMeta meta =
                new ExprMeta(
                        na.line(),
                        PrimitiveKind.NONE,
                        na.wideningRank(),
                        null,
                        org.babblelang.analysis.EffectSet.EMPTY,
                        false,
                        false);
        withSingle(
                na.value(),
                na.line(),
                ph ->
                        emitter.assigns.emitNameAssign(
                                new HirExpr.NameAssign(
                                        na.name(),
                                        na.operator(),
                                        ph,
                                        na.targetText(),
                                        na.valueText(),
                                        meta)));
        mirEmitter.storeResult(na.result());
    }

    void emitStoreField(MirInst.StoreField sf) {
        withPair(
                sf.receiver(),
                sf.value(),
                sf.line(),
                (rPh, vPh) ->
                        emitter.assigns.emitThisFieldAssign(
                                new HirExpr.ThisFieldAssign(
                                        rPh,
                                        sf.classId(),
                                        sf.memberIndex(),
                                        sf.memberName(),
                                        null,
                                        vPh,
                                        sf.targetText(),
                                        sf.valueText(),
                                        ExprMeta.of(sf.line(), sf.value().kind()))));
        popOn(mv, ctx.stackKind);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void emitLoadJavaField(MirInst.LoadJavaField lj) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1);
        HirExpr rPh = optionalPlaceholder(lj.receiver(), lj.line(), ov);
        withOverrides(
                ov,
                () ->
                        emitter.assigns.emitJavaFieldGet(
                                new HirExpr.JavaFieldGet(
                                        rPh,
                                        lj.receiverText(),
                                        lj.pureStaticReceiver(),
                                        lj.target(),
                                        ExprMeta.of(lj.line(), lj.result().kind()))));
        mirEmitter.storeResult(lj.result());
    }

    void emitStoreJavaField(MirInst.StoreJavaField sj) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(2);
        HirExpr rPh = optionalPlaceholder(sj.receiver(), sj.line(), ov);
        HirExpr vPh = addPlaceholder(sj.value(), sj.line(), ov);
        withOverrides(
                ov,
                () ->
                        emitter.assigns.emitJavaFieldAssignExpr(
                                new HirExpr.JavaFieldAssign(
                                        rPh,
                                        sj.receiverText(),
                                        sj.pureStaticReceiver(),
                                        sj.target(),
                                        null,
                                        vPh,
                                        sj.targetText(),
                                        sj.valueText(),
                                        ExprMeta.of(sj.line(), sj.value().kind()))));
        popOn(mv, ctx.stackKind);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ------------------------------------------------------------------ arrays & calls

    void emitArrayGet(MirInst.ArrayGet ag) {
        withPair(
                ag.array(),
                ag.index(),
                ag.line(),
                (aPh, iPh) ->
                        emitter.assigns.emitArrayOp(
                                new HirExpr.ArrayOp(
                                        LoweringTargets.ArrayOpKind.GET,
                                        aPh,
                                        ag.receiverText(),
                                        ag.elemKind(),
                                        ag.arrayClass(),
                                        ag.componentClass(),
                                        List.of(iPh),
                                        ExprMeta.of(ag.line(), ag.result().kind()))));
        mirEmitter.storeResult(ag.result());
    }

    void emitArraySet(MirInst.ArraySet as) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(3);
        HirExpr aPh = addPlaceholder(as.array(), as.line(), ov);
        HirExpr iPh = addPlaceholder(as.index(), as.line(), ov);
        HirExpr vPh = addPlaceholder(as.value(), as.line(), ov);
        withOverrides(
                ov,
                () ->
                        emitter.assigns.emitArrayOp(
                                new HirExpr.ArrayOp(
                                        LoweringTargets.ArrayOpKind.SET,
                                        aPh,
                                        as.receiverText(),
                                        as.elemKind(),
                                        as.arrayClass(),
                                        as.componentClass(),
                                        List.of(iPh, vPh),
                                        ExprMeta.of(as.line(), PrimitiveKind.NONE))));
        popOn(mv, ctx.stackKind);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void emitArrayLen(MirInst.ArrayLen al) {
        if (al.receiverText() == null) {
            mv.visitVarInsn(ALOAD, plan.slotOf(al.array()));
            mv.visitInsn(ARRAYLENGTH);
            ctx.stackKind = PrimitiveKind.INT;
            mirEmitter.storeResult(al.result());
            return;
        }
        withSingle(
                al.array(),
                al.line(),
                aPh ->
                        emitter.assigns.emitArrayOp(
                                new HirExpr.ArrayOp(
                                        LoweringTargets.ArrayOpKind.SIZE,
                                        aPh,
                                        al.receiverText(),
                                        al.elemKind(),
                                        al.arrayClass(),
                                        al.componentClass(),
                                        List.of(),
                                        ExprMeta.of(al.line(), PrimitiveKind.INT))));
        mirEmitter.storeResult(al.result());
    }

    void emitCallBabble(MirInst.CallBabble cb) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cb.args().size());
        List<HirExpr> argPhs = makePlaceholders(cb.args(), cb.line(), ov);
        if (cb.selfRecurse()) {
            withOverrides(
                    ov,
                    () ->
                            emitter.calls.emitSelfRecurseCall(
                                    new HirExpr.SelfRecurseCall(
                                            cb.targetFunctionId(),
                                            argPhs,
                                            cb.binding(),
                                            ExprMeta.of(cb.line(), cb.result().kind()))));
        } else {
            HirExpr cPh = addPlaceholder(cb.callee(), cb.line(), ov);
            withOverrides(
                    ov,
                    () ->
                            emitter.calls.emitDirectBabbleCall(
                                    new HirExpr.DirectBabbleCall(
                                            cPh,
                                            cb.targetFunctionId(),
                                            cb.externalTarget(),
                                            argPhs,
                                            cb.binding(),
                                            ExprMeta.of(cb.line(), cb.result().kind()))));
        }
        mirEmitter.storeResult(cb.result());
    }

    void emitCallMethod(MirInst.CallMethod cm) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cm.args().size());
        HirExpr rPh = addPlaceholder(cm.receiver(), cm.line(), ov);
        List<HirCallArg> callArgs = makeNamedPlaceholders(cm.args(), cm.argNames(), cm.line(), ov);
        withOverrides(
                ov,
                () ->
                        emitter.calls.emitDirectThisMethodCall(
                                new HirExpr.DirectThisMethodCall(
                                        rPh,
                                        cm.receiverText(),
                                        cm.classId(),
                                        cm.externalClassJvmName(),
                                        cm.classTypeId(),
                                        cm.memberName(),
                                        cm.overloadAltIndex(),
                                        cm.targetFunctionId(),
                                        cm.externalSignature(),
                                        callArgs,
                                        cm.binding(),
                                        cm.callSite(),
                                        ExprMeta.of(cm.line(), cm.result().kind()))));
        mirEmitter.storeResult(cm.result());
    }

    void emitCallInterface(MirInst.CallInterface ci) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + ci.args().size());
        HirExpr rPh = addPlaceholder(ci.receiver(), ci.line(), ov);
        List<HirExpr> argPhs = makePlaceholders(ci.args(), ci.line(), ov);
        withOverrides(
                ov,
                () ->
                        emitter.calls.emitInterfaceCall(
                                new HirExpr.InterfaceCall(
                                        rPh,
                                        ci.receiverText(),
                                        ci.selectorLine(),
                                        ci.interfaceTypeId(),
                                        ci.memberName(),
                                        ci.signature(),
                                        argPhs,
                                        ci.binding(),
                                        ExprMeta.of(ci.line(), ci.result().kind()))));
        mirEmitter.storeResult(ci.result());
    }

    void emitCallJava(MirInst.CallJava cj) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cj.args().size());
        HirExpr rPh = optionalPlaceholder(cj.receiver(), cj.line(), ov);
        List<HirExpr> argPhs = makePlaceholders(cj.args(), cj.line(), ov);
        withOverrides(
                ov,
                () ->
                        emitter.calls.emitJavaMethodCall(
                                new HirExpr.JavaMethodCall(
                                        rPh,
                                        cj.receiverText(),
                                        cj.selectorLine(),
                                        cj.pureStaticReceiver(),
                                        cj.target(),
                                        argPhs,
                                        ExprMeta.of(cj.line(), cj.result().kind()))));
        mirEmitter.storeResult(cj.result());
    }

    void emitCallJavaCtor(MirInst.CallJavaCtor cc) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cc.args().size());
        HirExpr cPh = optionalPlaceholder(cc.callee(), cc.line(), ov);
        List<HirExpr> argPhs = makePlaceholders(cc.args(), cc.line(), ov);
        withOverrides(
                ov,
                () ->
                        emitter.calls.emitJavaConstructorCall(
                                new HirExpr.JavaConstructorCall(
                                        cPh,
                                        cc.calleeText(),
                                        cc.pureStaticCallee(),
                                        cc.target(),
                                        argPhs,
                                        ExprMeta.of(cc.line(), cc.result().kind()))));
        mirEmitter.storeResult(cc.result());
    }

    void emitRecordNew(MirInst.RecordNew rn) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + rn.args().size());
        HirExpr crPh = addPlaceholder(rn.classRef(), rn.line(), ov);
        List<HirExpr> argPhs = makePlaceholders(rn.args(), rn.line(), ov);
        withOverrides(
                ov,
                () ->
                        emitter.calls.emitRecordNew(
                                new HirExpr.RecordNew(
                                        rn.classId(),
                                        rn.classTypeId(),
                                        rn.classJvmName(),
                                        crPh,
                                        rn.classRefIsInstance(),
                                        rn.paramKinds(),
                                        argPhs,
                                        rn.binding(),
                                        ExprMeta.of(rn.line(), PrimitiveKind.NONE))));
        mirEmitter.storeResult(rn.result());
    }

    void emitRecordReuse(MirInst.RecordReuse rr) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + rr.args().size());
        HirExpr tPh = addPlaceholder(rr.target(), rr.line(), ov);
        List<HirExpr> argPhs = makePlaceholders(rr.args(), rr.line(), ov);
        withOverrides(
                ov,
                () ->
                        emitter.calls.emitRecordReuse(
                                new HirExpr.RecordReuse(
                                        tPh,
                                        rr.classId(),
                                        rr.classTypeId(),
                                        rr.classJvmName(),
                                        rr.paramKinds(),
                                        argPhs,
                                        ExprMeta.of(rr.line(), PrimitiveKind.NONE))));
        mirEmitter.storeResult(rr.result());
    }

    void emitOpaque(MirInst.OpaqueExpr op) {
        List<HirExpr> children = MirLowerer.opaqueChildExprs(op.template());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            ov.put(children.get(i), plan.operandOf(op.operands().get(i)));
        }
        withOverrides(ov, () -> emitter.emitExpr(op.template()));
        mirEmitter.storeResult(op.result());
    }

    void emitTailCall(MirTerminator.TailCall tc) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(tc.args().size());
        List<HirCallArg> callArgs = makeNamedPlaceholders(tc.args(), tc.argNames(), tc.line(), ov);
        withOverrides(
                ov,
                () ->
                        emitter.calls.emitSelfTailCall(
                                new HirExpr.SelfTailCall(
                                        tc.functionId(),
                                        callArgs,
                                        tc.binding(),
                                        ExprMeta.of(tc.line(), PrimitiveKind.NONE))));
    }

    // ------------------------------------------------------------------ for-loop & catch matching

    void emitForIterInit(MirInst.ForIterInit fi) {
        int ln = fi.line();
        LoweringTargets.ForLoopTarget target = fi.target();
        LoweringTargets.ForKind kind = target.kind();
        int recvSlot = plan.slotOf(fi.iterable());
        int dstSlot = plan.slotOf(fi.result());
        switch (kind) {
            case ARRAY -> {
                String arrDesc = Type.getDescriptor(target.arrayClass());
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        VALUES,
                        "unwrap",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
                mv.visitTypeInsn(CHECKCAST, arrDesc);
                mv.visitVarInsn(ASTORE, dstSlot);
            }
            case JAVA_ITERABLE -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        VALUES,
                        "unwrap",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
                ctx.line(ln);
                mv.visitMethodInsn(
                        INVOKEINTERFACE,
                        "java/lang/Iterable",
                        "iterator",
                        "()Ljava/util/Iterator;",
                        true);
                mv.visitVarInsn(ASTORE, dstSlot);
            }
            case JAVA_ITERATOR -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        VALUES,
                        "unwrap",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
                mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
                mv.visitVarInsn(ASTORE, dstSlot);
            }
            case BABBLE_TO_JAVA_ITERABLE -> {
                emitter.controlOps.loops.emitZeroArgBabbleCall(recvSlot, "iterator", null, ln);
                emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        VALUES,
                        "unwrap",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
                mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
                mv.visitVarInsn(ASTORE, dstSlot);
            }
            case BABBLE_ITERABLE -> {
                emitter.controlOps.loops.emitZeroArgBabbleCall(recvSlot, "iterator", null, ln);
                emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
                mv.visitVarInsn(ASTORE, dstSlot);
            }
            case BABBLE_ITERATOR -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitVarInsn(ASTORE, dstSlot);
            }
            case DYNAMIC_UNION -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                ctx.line(ln);
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        VALUES,
                        "asIterator",
                        "(Ljava/lang/Object;)Ljava/util/Iterator;",
                        false);
                mv.visitVarInsn(ASTORE, dstSlot);
            }
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void emitForIterHasNext(MirInst.ForIterHasNext fh) {
        int ln = fh.line();
        LoweringTargets.ForLoopTarget target = fh.target();
        LoweringTargets.ForKind kind = target.kind();
        if (kind == LoweringTargets.ForKind.ARRAY) {
            mirEmitter.emitPrimitiveComparison(HirExpr.BinaryOp.LT, fh.index(), fh.length());
            mirEmitter.storeResult(fh.result());
            return;
        }
        if (kind == LoweringTargets.ForKind.BABBLE_ITERABLE
                || kind == LoweringTargets.ForKind.BABBLE_ITERATOR) {
            emitter.controlOps.loops.emitZeroArgBabbleCall(
                    plan.slotOf(fh.iterator()), "hasNext", target.hasNextSig(), ln);
            if (ctx.stackKind != PrimitiveKind.BOOL) {
                emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
                mv.visitMethodInsn(
                        INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
                ctx.stackKind = PrimitiveKind.BOOL;
            }
            mirEmitter.storeResult(fh.result());
            return;
        }
        mv.visitVarInsn(ALOAD, plan.slotOf(fh.iterator()));
        ctx.line(ln);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        ctx.stackKind = PrimitiveKind.BOOL;
        mirEmitter.storeResult(fh.result());
    }

    void emitForIterNext(MirInst.ForIterNext fnInst) {
        int ln = fnInst.line();
        LoweringTargets.ForLoopTarget target = fnInst.target();
        LoweringTargets.ForKind kind = target.kind();
        if (kind == LoweringTargets.ForKind.ARRAY) {
            Class<?> comp = target.arrayClass().getComponentType();
            PrimitiveKind elemKind = javaPrimitiveKind(comp);
            mv.visitVarInsn(ALOAD, plan.slotOf(fnInst.iterator()));
            mv.visitVarInsn(ILOAD, plan.slotOf(fnInst.index()));
            if (elemKind != PrimitiveKind.NONE) {
                mv.visitInsn(
                        switch (elemKind) {
                            case BYTE, BOOL -> BALOAD;
                            case SHORT -> SALOAD;
                            case INT -> IALOAD;
                            case LONG -> LALOAD;
                            case FLOAT -> FALOAD;
                            case DOUBLE -> DALOAD;
                            case CHAR -> CALOAD;
                            case NONE -> AALOAD;
                        });
                ctx.stackKind = elemKind;
            } else {
                mv.visitInsn(AALOAD);
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        VALUES,
                        "fromJava",
                        "(Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
                ctx.stackKind = PrimitiveKind.NONE;
            }
            mirEmitter.storeResult(fnInst.result());
            return;
        }
        if (kind == LoweringTargets.ForKind.BABBLE_ITERABLE
                || kind == LoweringTargets.ForKind.BABBLE_ITERATOR) {
            emitter.controlOps.loops.emitZeroArgBabbleCall(
                    plan.slotOf(fnInst.iterator()), "next", target.nextSig(), ln);
            mirEmitter.storeResult(fnInst.result());
            return;
        }
        mv.visitVarInsn(ALOAD, plan.slotOf(fnInst.iterator()));
        ctx.line(ln);
        mv.visitMethodInsn(
                INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(
                INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        ctx.stackKind = PrimitiveKind.NONE;
        mirEmitter.storeResult(fnInst.result());
    }

    void emitCatchMatch(MirInst.CatchMatch cm) {
        LoweringTargets.CatchTarget ct = cm.target();
        int valSlot = plan.slotOf(cm.value());
        List<String> directJvms =
                emitter.controlOps.tryCatch.resolveBabbleCatchJvms(ct.babbleTargets());
        if (!directJvms.isEmpty()) {
            if (directJvms.size() == 1) {
                mv.visitVarInsn(ALOAD, valSlot);
                mv.visitTypeInsn(INSTANCEOF, directJvms.get(0));
            } else {
                Label matched = new Label();
                Label done = new Label();
                for (String jvm : directJvms) {
                    mv.visitVarInsn(ALOAD, valSlot);
                    mv.visitTypeInsn(INSTANCEOF, jvm);
                    mv.visitJumpInsn(IFNE, matched);
                }
                mv.visitInsn(ICONST_0);
                mv.visitJumpInsn(GOTO, done);
                mv.visitLabel(matched);
                mv.visitInsn(ICONST_1);
                mv.visitLabel(done);
            }
        } else if (ct.typeDescriptor() != null) {
            mv.visitVarInsn(ALOAD, valSlot);
            ConstantPool.pushString(mv, ct.typeDescriptor());
            ctx.line(cm.line());
            mv.visitMethodInsn(
                    INVOKESTATIC,
                    RUNTIME + "Types",
                    "matches",
                    "(Ljava/lang/Object;Ljava/lang/String;)Z",
                    false);
        } else {
            mv.visitInsn(ICONST_0);
        }
        ctx.stackKind = PrimitiveKind.BOOL;
        mirEmitter.storeResult(cm.result());
    }

    // ------------------------------------------------------------------ mirOverrides helpers

    private static HirExpr placeholder(MirVal v, int line) {
        return new HirExpr.Const(null, ExprMeta.of(line, v.kind()));
    }

    private HirExpr addPlaceholder(
            MirVal v, int line, Map<HirExpr, MethodEmitContext.MirOperand> ov) {
        HirExpr ph = placeholder(v, line);
        ov.put(ph, plan.operandOf(v));
        return ph;
    }

    private HirExpr optionalPlaceholder(
            MirVal v, int line, Map<HirExpr, MethodEmitContext.MirOperand> ov) {
        return v != null ? addPlaceholder(v, line, ov) : null;
    }

    private List<HirExpr> makePlaceholders(
            List<MirVal> vals, int line, Map<HirExpr, MethodEmitContext.MirOperand> ov) {
        List<HirExpr> out = new ArrayList<>(vals.size());
        for (MirVal v : vals) {
            out.add(addPlaceholder(v, line, ov));
        }
        return out;
    }

    private List<HirCallArg> makeNamedPlaceholders(
            List<MirVal> vals,
            List<String> names,
            int line,
            Map<HirExpr, MethodEmitContext.MirOperand> ov) {
        List<HirCallArg> out = new ArrayList<>(vals.size());
        for (int i = 0; i < vals.size(); i++) {
            out.add(new HirCallArg(names.get(i), addPlaceholder(vals.get(i), line, ov)));
        }
        return out;
    }

    private void withSingle(MirVal v, int line, Consumer<HirExpr> body) {
        HirExpr ph = placeholder(v, line);
        withOverrides(Map.of(ph, plan.operandOf(v)), () -> body.accept(ph));
    }

    private void withPair(MirVal a, MirVal b, int line, BiConsumer<HirExpr, HirExpr> body) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(2);
        HirExpr aPh = addPlaceholder(a, line, ov);
        HirExpr bPh = addPlaceholder(b, line, ov);
        withOverrides(ov, () -> body.accept(aPh, bPh));
    }

    private void withOverrides(
            Map<HirExpr, MethodEmitContext.MirOperand> overrides, Runnable body) {
        Map<HirExpr, MethodEmitContext.MirOperand> prev = ctx.mirOverrides;
        ctx.mirOverrides = overrides;
        try {
            body.run();
        } finally {
            ctx.mirOverrides = prev;
        }
    }
}
