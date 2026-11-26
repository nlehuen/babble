package org.babblelang.compiler;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.mir.MirBlock;
import org.babblelang.ir.mir.MirCfg;
import org.babblelang.ir.mir.MirFunction;
import org.babblelang.ir.mir.MirHandler;
import org.babblelang.ir.mir.MirInst;
import org.babblelang.ir.mir.MirTerminator;
import org.babblelang.ir.mir.MirVal;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Emits JVM bytecode for a single SSA {@link MirFunction} inside {@link HirEmitter}'s active {@link
 * MethodEmitContext} ({@code BIP014-I3} §6.4).
 *
 * <p>Uses {@link MirSlotAllocator} for liveness/slot coloring, rematerialization, branch fusion,
 * and non-negativity analysis; {@link JvmOpEmitter} for shared primitive arithmetic, constants, and
 * comparisons; and {@link MirAbiBridge} for runtime ABI delegation into {@link HirEmitter}'s
 * sub-emitters.
 */
final class MirEmitter {
    private final HirEmitter emitter;
    private final MethodEmitContext ctx;
    private final MethodVisitor mv;
    private final MirFunction fn;
    private final PrimitiveKind methodReturnKind;
    private final MirCfg cfg;
    private final List<MirBlock> rpo;
    private final Map<Integer, MirBlock> blockById;
    private final Map<Integer, Label> blockLabels = new HashMap<>();
    private final List<Runnable> edgeTrampolines = new ArrayList<>();

    private MirSlotAllocator plan;
    private MirAbiBridge abi;

    MirEmitter(
            HirEmitter emitter, MirFunction fn, HirFunction hirFn, PrimitiveKind methodReturnKind) {
        this.emitter = emitter;
        this.ctx = emitter.ctx;
        this.mv = ctx.mv;
        this.fn = fn;
        this.methodReturnKind = methodReturnKind;
        this.cfg = MirCfg.analyze(fn);
        this.rpo = cfg.rpo();
        this.blockById = fn.blockMap();
    }

    void emit() {
        ctx.cacheNameReads = true;
        this.plan = MirSlotAllocator.analyze(fn, ctx, cfg, rpo, blockById);
        this.abi = new MirAbiBridge(emitter, plan, this);
        for (MirBlock b : rpo) {
            blockLabels.put(b.id(), new Label());
        }

        // Emit blocks in RPO order, recording start/end labels for contiguous exception-table
        // ranges.
        Label[] startLabels = new Label[rpo.size()];
        Label[] endLabels = new Label[rpo.size()];
        boolean[] hasCode = new boolean[rpo.size()];
        for (int i = 0; i < rpo.size(); i++) {
            MirBlock b = rpo.get(i);
            Label start = blockLabels.get(b.id());
            startLabels[i] = start;
            mv.visitLabel(start);

            boolean emitted = false;
            if (b.handlerEntry() && !b.params().isEmpty()) {
                // JVM exception dispatch pushes the caught Throwable on the operand stack.
                mv.visitVarInsn(ASTORE, plan.slotOf(b.params().get(0)));
                emitted = true;
            }
            for (MirInst inst : b.insts()) {
                if (plan.isDeferred(inst)) {
                    continue;
                }
                emitInst(inst);
                if (!(inst instanceof MirInst.Narrow
                        || inst instanceof MirInst.Move
                        || inst instanceof MirInst.Freeze)) {
                    emitted = true;
                }
            }
            MirBlock nextBlock = (i + 1 < rpo.size()) ? rpo.get(i + 1) : null;
            if (emitTerminator(b.terminator(), nextBlock)) {
                emitted = true;
            }

            Label end = new Label();
            mv.visitLabel(end);
            endLabels[i] = end;
            hasCode[i] = emitted;
        }

        // Emit BrIf edge-copy trampolines after main RPO blocks (pure local moves, cannot throw).
        for (Runnable trampoline : edgeTrampolines) {
            trampoline.run();
        }

        // Register merged contiguous try-catch ranges in exact innermost-to-outermost handler
        // order, skipping zero-instruction fallthrough blocks so every JVM range satisfies
        // start_pc < end_pc.
        int i = 0;
        while (i < rpo.size()) {
            List<MirHandler> handlers = rpo.get(i).handlers();
            if (handlers.isEmpty() || !hasCode[i]) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < rpo.size() && rpo.get(j + 1).handlers().equals(handlers)) {
                j++;
            }
            for (MirHandler h : handlers) {
                Label handlerLabel = blockLabels.get(h.handlerBlockId());
                if (handlerLabel != null) {
                    mv.visitTryCatchBlock(
                            startLabels[i], endLabels[j], handlerLabel, h.catchJvmType());
                }
            }
            i = j + 1;
        }
    }

    // ------------------------------------------------------------------ stack loads & stores

    private void loadVal(MirVal v) {
        int rootId = plan.resolveRoot(v).id();
        MirInst.Const c = plan.rematerialized(rootId);
        if (plan.isStacked(rootId)) {
            // Already on the stack : its definition was the previous instruction.
        } else if (c != null) {
            JvmOpEmitter.pushConstant(mv, c.value());
        } else {
            loadKindOn(mv, v.kind(), plan.slotOf(v));
        }
        ctx.stackKind = v.kind();
    }

    private void loadValAs(MirVal v, PrimitiveKind target) {
        loadVal(v);
        emitter.convertStack(v.kind(), target);
    }

    void storeResult(MirVal res) {
        if (plan.isDiscarded(res.id())) {
            if (ctx.stackKind == PrimitiveKind.NONE && res.kind().isPrimitive()) {
                emitter.convertStack(ctx.stackKind, res.kind());
            }
            popOn(mv, ctx.stackKind);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        emitter.convertStack(ctx.stackKind, res.kind());
        if (plan.isStacked(res.id())) {
            ctx.stackKind = res.kind();
            return;
        }
        storeKindOn(mv, res.kind(), plan.slotOf(res));
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ------------------------------------------------------------------ instruction emission

    private void emitInst(MirInst inst) {
        switch (inst) {
            case MirInst.Const c -> {
                ctx.stackKind = JvmOpEmitter.pushConstant(mv, c.value());
                storeResult(c.result());
            }
            case MirInst.Copy c -> {
                loadVal(c.source());
                storeResult(c.result());
            }
            case MirInst.Narrow ignored -> {} // zero-cost alias
            case MirInst.Move ignored -> {} // zero-cost alias
            case MirInst.Freeze ignored -> {} // zero-cost alias
            case MirInst.Unary u -> emitUnary(u);
            case MirInst.Binary b -> emitBinary(b);
            case MirInst.Widen w -> {
                loadVal(w.operand());
                emitter.widenStackRank(w.wideningRank());
                storeResult(w.result());
            }
            case MirInst.Convert c -> {
                loadVal(c.operand());
                emitter.convertStack(c.operand().kind(), c.targetKind());
                storeResult(c.result());
            }
            case MirInst.CheckCast cc -> abi.emitCheckCast(cc);
            case MirInst.NonNull nn -> abi.emitNonNull(nn);
            case MirInst.IsCheck ic -> abi.emitIsCheck(ic);
            case MirInst.LoadField lf -> abi.emitLoadField(lf);
            case MirInst.StoreField sf -> abi.emitStoreField(sf);
            case MirInst.LoadJavaField lj -> abi.emitLoadJavaField(lj);
            case MirInst.StoreJavaField sj -> abi.emitStoreJavaField(sj);
            case MirInst.ArrayGet ag -> abi.emitArrayGet(ag);
            case MirInst.ArraySet as -> abi.emitArraySet(as);
            case MirInst.ArrayLen al -> abi.emitArrayLen(al);
            case MirInst.CallBabble cb -> abi.emitCallBabble(cb);
            case MirInst.CallMethod cm -> abi.emitCallMethod(cm);
            case MirInst.CallInterface ci -> abi.emitCallInterface(ci);
            case MirInst.CallJava cj -> abi.emitCallJava(cj);
            case MirInst.CallJavaCtor cc -> abi.emitCallJavaCtor(cc);
            case MirInst.RecordNew rn -> abi.emitRecordNew(rn);
            case MirInst.RecordReuse rr -> abi.emitRecordReuse(rr);
            case MirInst.RangeNew rn -> abi.emitRangeNew(rn);
            case MirInst.SlotDecl sd -> emitSlotDecl(sd);
            case MirInst.SlotRead sr -> {
                emitter.loadVariable(sr.var(), sr.line());
                storeResult(sr.result());
            }
            case MirInst.SlotWrite sw -> {
                loadVal(sw.value());
                PrimitiveKind vk = sw.var().primitiveKind();
                emitter.convertStack(ctx.stackKind, vk);
                emitter.storeVariable(
                        sw.var(), sw.opKind() == MirInst.SlotOpKind.INIT, vk, sw.line());
                popOn(mv, ctx.stackKind);
                ctx.stackKind = PrimitiveKind.NONE;
            }
            case MirInst.ScopeEnter se -> {
                mv.visitVarInsn(ALOAD, ctx.nsLocal);
                mv.visitVarInsn(ASTORE, plan.slotOf(se.result()));
                mv.visitVarInsn(ALOAD, ctx.nsLocal);
                emitter.getStringArray(se.declaredNames());
                mv.visitMethodInsn(
                        INVOKEVIRTUAL,
                        NAMESPACE,
                        "child",
                        "([Ljava/lang/String;)L" + NAMESPACE + ";",
                        false);
                mv.visitVarInsn(ASTORE, ctx.nsLocal);
            }
            case MirInst.ScopeExit sx -> {
                mv.visitVarInsn(ALOAD, plan.slotOf(sx.prevNamespace()));
                mv.visitVarInsn(ASTORE, ctx.nsLocal);
            }
            case MirInst.ForIterInit fi -> abi.emitForIterInit(fi);
            case MirInst.ForIterHasNext fh -> abi.emitForIterHasNext(fh);
            case MirInst.ForIterNext fnInst -> abi.emitForIterNext(fnInst);
            case MirInst.UnwrapThrown ut -> {
                mv.visitVarInsn(ALOAD, plan.slotOf(ut.thrownExn()));
                mv.visitMethodInsn(
                        INVOKEVIRTUAL,
                        RUNTIME + "ThrownException",
                        "value",
                        "()Ljava/lang/Object;",
                        false);
                mv.visitVarInsn(ASTORE, plan.slotOf(ut.result()));
            }
            case MirInst.CatchMatch cm -> abi.emitCatchMatch(cm);
            case MirInst.AddSuppressed as -> {
                mv.visitVarInsn(ALOAD, plan.slotOf(as.primary()));
                mv.visitVarInsn(ALOAD, plan.slotOf(as.secondary()));
                mv.visitMethodInsn(
                        INVOKESTATIC,
                        RUNTIME + "Throwables",
                        "addSuppressed",
                        "(Ljava/lang/Throwable;Ljava/lang/Throwable;)V",
                        false);
            }
            case MirInst.NameRead nr -> {
                emitter.loadName(nr.name(), nr.finalBinding() && nr.effect().nothrow(), nr.line());
                storeResult(nr.result());
            }
            case MirInst.NameAssign na -> abi.emitNameAssign(na);
            case MirInst.LoadExport le -> {
                loadVal(le.module());
                mv.visitTypeInsn(CHECKCAST, RUNTIME + "Module");
                ConstantPool.pushInt(mv, le.target().index());
                ctx.line(le.line());
                mv.visitMethodInsn(
                        INVOKEVIRTUAL,
                        RUNTIME + "Module",
                        "exportAt",
                        "(I)Ljava/lang/Object;",
                        false);
                ctx.stackKind = PrimitiveKind.NONE;
                storeResult(le.result());
            }
            case MirInst.OpaqueExpr op -> abi.emitOpaque(op);
        }
    }

    private void emitUnary(MirInst.Unary u) {
        if (u.op() == HirExpr.UnaryOp.NOT) {
            if (u.operand().kind() == PrimitiveKind.BOOL) {
                loadVal(u.operand());
                mv.visitInsn(ICONST_1);
                mv.visitInsn(IXOR);
                ctx.stackKind = PrimitiveKind.BOOL;
                storeResult(u.result());
                return;
            }
            JvmOpEmitter.emitBoolFromJump(
                    mv, ctx, isTrue -> emitBranchOnTruth(u.operand(), false, isTrue));
            storeResult(u.result());
            return;
        }
        if (JvmOpEmitter.emitPrimitiveNegate(
                mv,
                ctx,
                u.operand().kind(),
                u.result().kind(),
                target -> loadValAs(u.operand(), target))) {
            storeResult(u.result());
            return;
        }
        abi.emitUnaryFallback(u);
    }

    private void emitBinary(MirInst.Binary b) {
        PrimitiveKind lk = b.left().kind();
        PrimitiveKind rk = b.right().kind();
        PrimitiveKind out = b.result().kind();

        if (b.op().isComparison()
                && out == PrimitiveKind.BOOL
                && JvmOpEmitter.canEmitPrimitiveCmp(b.op(), lk, rk)) {
            emitPrimitiveComparison(b.op(), b.left(), b.right());
            storeResult(b.result());
            return;
        }
        long posConst = 0L;
        if ((out == PrimitiveKind.INT || out == PrimitiveKind.LONG)
                && (b.op() == HirExpr.BinaryOp.MOD || b.op() == HirExpr.BinaryOp.FLOOR_DIV)) {
            MirInst.Const rConst = plan.constOf(b.right());
            if (rConst != null && rConst.value() instanceof Number rn) {
                long v = out == PrimitiveKind.LONG ? rn.longValue() : rn.intValue();
                if (v > 0L) {
                    posConst = v;
                }
            }
        }
        if (JvmOpEmitter.emitPrimitiveBinaryArith(
                mv,
                ctx,
                b.op(),
                lk,
                rk,
                out,
                posConst,
                posConst > 0L
                        && (posConst & (posConst - 1L)) != 0L
                        && plan.isStaticallyNonNegative(b.left()),
                b.line(),
                target -> loadValAs(b.left(), target),
                target -> loadValAs(b.right(), target))) {
            storeResult(b.result());
            return;
        }
        abi.emitBinaryFallback(b);
    }

    void emitPrimitiveComparison(HirExpr.BinaryOp op, MirVal left, MirVal right) {
        JvmOpEmitter.emitBoolFromJump(
                mv, ctx, isTrue -> emitComparisonJump(op, left, right, true, isTrue));
    }

    private void emitComparisonJump(
            HirExpr.BinaryOp op, MirVal left, MirVal right, boolean branchIfTrue, Label target) {
        JvmOpEmitter.emitPrimitiveComparisonJump(
                mv,
                op,
                left.kind(),
                right.kind(),
                branchIfTrue,
                target,
                kind -> loadValAs(left, kind),
                kind -> loadValAs(right, kind));
    }

    private void emitSlotDecl(MirInst.SlotDecl sd) {
        HirVar v = sd.var();
        PrimitiveKind vk = v.primitiveKind();
        int slot = ctx.slots.get(v.id());
        if (!v.cell() && vk.isPrimitive() && v.mayBeUndefined() && !v.definitelyDefined()) {
            Integer defSlot = ctx.definedSlots.get(v.id());
            if (defSlot != null) {
                mv.visitInsn(ICONST_0);
                mv.visitVarInsn(ISTORE, defSlot);
            }
        }
        if (v.cell()) {
            String cc = cellClass(vk);
            mv.visitMethodInsn(INVOKESTATIC, cc, "undefined", "()L" + cc + ";", false);
            mv.visitVarInsn(ASTORE, slot);
        } else if (vk.isPrimitive()) {
            emitDefaultValueOn(mv, vk);
            storeKindOn(mv, vk, slot);
        } else {
            mv.visitFieldInsn(
                    GETSTATIC, RUNTIME + "Undefined", "UNDEFINED", "L" + RUNTIME + "Undefined;");
            mv.visitVarInsn(ASTORE, slot);
        }
    }

    // ------------------------------------------------------------------ terminators & parallel
    // copy

    private boolean emitTerminator(MirTerminator term, MirBlock nextBlock) {
        switch (term) {
            case MirTerminator.Br br -> {
                boolean copied =
                        hasNonTrivialCopies(br.args(), blockById.get(br.targetBlock()).params());
                emitParallelCopy(br.args(), blockById.get(br.targetBlock()).params());
                if (nextBlock == null || nextBlock.id() != br.targetBlock()) {
                    mv.visitJumpInsn(GOTO, blockLabels.get(br.targetBlock()));
                    return true;
                }
                return copied;
            }
            case MirTerminator.BrIf bi -> {
                MirBlock thenTarget = blockById.get(bi.thenBlock());
                MirBlock elseTarget = blockById.get(bi.elseBlock());
                boolean thenHasCopies = hasNonTrivialCopies(bi.thenArgs(), thenTarget.params());
                boolean elseHasCopies = hasNonTrivialCopies(bi.elseArgs(), elseTarget.params());
                if (!thenHasCopies && !elseHasCopies) {
                    if (nextBlock != null && nextBlock.id() == bi.thenBlock()) {
                        emitBranchOnTruth(bi.cond(), false, blockLabels.get(bi.elseBlock()));
                    } else if (nextBlock != null && nextBlock.id() == bi.elseBlock()) {
                        emitBranchOnTruth(bi.cond(), true, blockLabels.get(bi.thenBlock()));
                    } else {
                        emitBranchOnTruth(bi.cond(), true, blockLabels.get(bi.thenBlock()));
                        mv.visitJumpInsn(GOTO, blockLabels.get(bi.elseBlock()));
                    }
                } else {
                    Label thenJump = thenHasCopies ? new Label() : blockLabels.get(bi.thenBlock());
                    emitBranchOnTruth(bi.cond(), true, thenJump);
                    emitParallelCopy(bi.elseArgs(), elseTarget.params());
                    if (nextBlock == null || nextBlock.id() != bi.elseBlock()) {
                        mv.visitJumpInsn(GOTO, blockLabels.get(bi.elseBlock()));
                    }
                    if (thenHasCopies) {
                        edgeTrampolines.add(() -> {
                            mv.visitLabel(thenJump);
                            emitParallelCopy(bi.thenArgs(), thenTarget.params());
                            mv.visitJumpInsn(GOTO, blockLabels.get(bi.thenBlock()));
                        });
                    }
                }
                return true;
            }
            case MirTerminator.Switch sw -> {
                loadVal(sw.key());
                emitter.convertStack(ctx.stackKind, PrimitiveKind.INT);
                int n = sw.keys().length;
                Label[] targetLabels = new Label[n];
                for (int i = 0; i < n; i++) {
                    targetLabels[i] = edgeLabel(sw.targetBlocks()[i], sw.targetArgs().get(i));
                }
                Label defLabel = edgeLabel(sw.defaultBlock(), sw.defaultArgs());
                ctx.line(sw.line());
                MatchEmitter.emitIntSwitch(mv, sw.keys(), targetLabels, defLabel);
                return true;
            }
            case MirTerminator.Ret ret -> {
                loadVal(ret.value());
                emitter.widenStackRank(ret.wideningRank());
                emitter.convertStack(ctx.stackKind, methodReturnKind);
                mv.visitInsn(returnInsn(methodReturnKind));
                return true;
            }
            case MirTerminator.Throw th -> {
                loadVal(th.value());
                emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
                ctx.line(th.line());
                if (th.isJavaThrowable()) {
                    mv.visitMethodInsn(
                            INVOKESTATIC,
                            VALUES,
                            "unwrap",
                            "(Ljava/lang/Object;)Ljava/lang/Object;",
                            false);
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Throwable");
                } else {
                    mv.visitMethodInsn(
                            INVOKESTATIC,
                            RUNTIME + "Throwables",
                            "toThrowable",
                            "(Ljava/lang/Object;)Ljava/lang/Throwable;",
                            false);
                }
                mv.visitInsn(ATHROW);
                return true;
            }
            case MirTerminator.TailCall tc -> {
                abi.emitTailCall(tc);
                return true;
            }
            case MirTerminator.Unreachable ignored -> {
                mv.visitInsn(ACONST_NULL);
                mv.visitInsn(ATHROW);
                return true;
            }
        }
    }

    private Label edgeLabel(int targetBlockId, List<MirVal> args) {
        MirBlock target = blockById.get(targetBlockId);
        if (!hasNonTrivialCopies(args, target.params())) {
            return blockLabels.get(targetBlockId);
        }
        Label tramp = new Label();
        edgeTrampolines.add(() -> {
            mv.visitLabel(tramp);
            emitParallelCopy(args, target.params());
            mv.visitJumpInsn(GOTO, blockLabels.get(targetBlockId));
        });
        return tramp;
    }

    private void emitBranchOnTruth(MirVal cond, boolean branchIfTrue, Label target) {
        MirInst fused = plan.fusedCondition(cond.id());
        if (fused instanceof MirInst.Unary not) {
            emitBranchOnTruth(not.operand(), !branchIfTrue, target);
            return;
        }
        if (fused instanceof MirInst.Binary cmp) {
            emitComparisonJump(cmp.op(), cmp.left(), cmp.right(), branchIfTrue, target);
            return;
        }
        loadVal(cond);
        switch (cond.kind()) {
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

    private boolean hasNonTrivialCopies(List<MirVal> args, List<MirVal> params) {
        for (int i = 0; i < args.size(); i++) {
            int rootId = plan.resolveRoot(args.get(i)).id();
            if (plan.isStacked(rootId)
                    || plan.rematerialized(rootId) != null
                    || plan.slotOf(args.get(i)) != plan.slotOf(params.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** Cycle-safe parallel copy of edge {@code args} into target block {@code params}. */
    private void emitParallelCopy(List<MirVal> args, List<MirVal> params) {
        final class MoveOp {
            int src;
            final int dst;
            final PrimitiveKind kind;

            MoveOp(int src, int dst, PrimitiveKind kind) {
                this.src = src;
                this.dst = dst;
                this.kind = kind;
            }
        }
        List<MoveOp> pending = new ArrayList<>();
        int stackedMove = -1;
        List<Integer> constantMoves = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            int rootId = plan.resolveRoot(args.get(i)).id();
            if (plan.isStacked(rootId)) {
                stackedMove = i;
                continue;
            }
            if (plan.rematerialized(rootId) != null) {
                constantMoves.add(i);
                continue;
            }
            int src = plan.slotOf(args.get(i));
            int dst = plan.slotOf(params.get(i));
            if (src != dst) {
                pending.add(new MoveOp(src, dst, params.get(i).kind()));
            }
        }
        while (!pending.isEmpty()) {
            MoveOp ready = null;
            for (MoveOp m : pending) {
                boolean dstUsedAsSrc = false;
                for (MoveOp other : pending) {
                    if (other != m && other.src == m.dst) {
                        dstUsedAsSrc = true;
                        break;
                    }
                }
                if (!dstUsedAsSrc) {
                    ready = m;
                    break;
                }
            }
            if (ready != null) {
                loadKindOn(mv, ready.kind, ready.src);
                storeKindOn(mv, ready.kind, ready.dst);
                pending.remove(ready);
            } else {
                MoveOp cyc = pending.get(0);
                int tmp = ctx.newLocal(cyc.kind);
                loadKindOn(mv, cyc.kind, cyc.src);
                storeKindOn(mv, cyc.kind, tmp);
                cyc.src = tmp;
            }
        }
        if (stackedMove >= 0) {
            MirVal param = params.get(stackedMove);
            storeKindOn(mv, param.kind(), plan.slotOf(param));
            ctx.stackKind = PrimitiveKind.NONE;
        }
        for (int i : constantMoves) {
            MirVal param = params.get(i);
            loadVal(args.get(i));
            emitter.convertStack(ctx.stackKind, param.kind());
            storeKindOn(mv, param.kind(), plan.slotOf(param));
            ctx.stackKind = PrimitiveKind.NONE;
        }
    }
}
