package org.babblelang.compiler;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.mir.MirBlock;
import org.babblelang.ir.mir.MirCfg;
import org.babblelang.ir.mir.MirFunction;
import org.babblelang.ir.mir.MirHandler;
import org.babblelang.ir.mir.MirInst;
import org.babblelang.ir.mir.MirLowerer;
import org.babblelang.ir.mir.MirTerminator;
import org.babblelang.ir.mir.MirVal;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Emits JVM bytecode for a single SSA {@link MirFunction} inside {@link HirEmitter}'s active
 * {@link MethodEmitContext} ({@code BIP014-P3} §6.4).
 *
 * <p>Performs backward CFG liveness and JVM-type-segregated local slot coloring, zero-copy
 * aliasing for {@link MirInst.Narrow}/{@link MirInst.Move}/{@link MirInst.Freeze}, cycle-breaking
 * parallel-copy lowering on {@code Br}/{@code BrIf} edges, contiguous RPO {@code visitTryCatchBlock}
 * range merging, and zero-duplication ABI delegation into {@link HirEmitter}'s sub-emitters via
 * {@link MethodEmitContext#mirOverrides}.
 */
final class MirEmitter {
    private final HirEmitter emitter;
    private final MethodEmitContext ctx;
    private final MethodVisitor mv;
    private final MirFunction fn;
    private final HirFunction hirFn;
    private final PrimitiveKind methodReturnKind;
    private final List<MirBlock> rpo;
    private final Map<Integer, MirBlock> blockById;
    private final Map<Integer, Label> blockLabels = new HashMap<>();
    private final Map<Integer, Integer> valSlots = new HashMap<>();
    private final Map<Integer, MirVal> aliasTarget = new HashMap<>();
    private final List<Runnable> edgeTrampolines = new ArrayList<>();

    MirEmitter(HirEmitter emitter, MirFunction fn, HirFunction hirFn, PrimitiveKind methodReturnKind) {
        this.emitter = emitter;
        this.ctx = emitter.ctx;
        this.mv = ctx.mv;
        this.fn = fn;
        this.hirFn = hirFn;
        this.methodReturnKind = methodReturnKind;
        MirCfg cfg = MirCfg.analyze(fn);
        this.rpo = cfg.rpo();
        this.blockById = fn.blockMap();
    }

    void emit() {
        ensurePinnedSlots();
        resolveAliasesAndAllocateSlots();
        for (MirBlock b : rpo) {
            blockLabels.put(b.id(), new Label());
        }

        // Emit blocks in RPO order, recording start/end labels for contiguous exception-table ranges.
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
                mv.visitVarInsn(ASTORE, slotOf(b.params().get(0)));
                emitted = true;
            }
            for (MirInst inst : b.insts()) {
                emitInst(inst);
                if (!(inst instanceof MirInst.Narrow || inst instanceof MirInst.Move || inst instanceof MirInst.Freeze)) {
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

        // Register merged contiguous try-catch ranges in exact innermost-to-outermost handler order,
        // skipping zero-instruction fallthrough blocks so every JVM range satisfies start_pc < end_pc.
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
                    mv.visitTryCatchBlock(startLabels[i], endLabels[j], handlerLabel, h.catchJvmType());
                }
            }
            i = j + 1;
        }
    }

    // ------------------------------------------------------------------ pinned slots & SSA slot coloring

    private void ensurePinnedSlots() {
        for (HirVar v : fn.pinnedVars()) {
            if (!ctx.slots.containsKey(v.id())) {
                PrimitiveKind vk = v.primitiveKind();
                int slot = ctx.newLocal(v.cell() ? PrimitiveKind.NONE : vk);
                ctx.slots.put(v.id(), slot);
                if (!v.cell() && vk.isPrimitive() && v.mayBeUndefined() && !v.definitelyDefined()) {
                    int defSlot = ctx.newLocal(PrimitiveKind.BOOL);
                    ctx.definedSlots.put(v.id(), defSlot);
                }
            }
        }
    }

    private void resolveAliasesAndAllocateSlots() {
        // 1. Bind non-pinned function parameters directly to the slots initialized by HirEmitter's prologue.
        for (int i = 0; i < fn.params().size(); i++) {
            MirVal pv = fn.params().get(i);
            HirVar hv = fn.paramVars().get(i);
            valSlots.put(pv.id(), ctx.slots.get(hv.id()));
        }

        // 2. Record zero-copy aliases (Narrow, Move, Freeze) to their root SSA value.
        for (MirBlock b : rpo) {
            for (MirInst inst : b.insts()) {
                if (inst instanceof MirInst.Narrow n) {
                    aliasTarget.put(n.result().id(), resolveRoot(n.operand()));
                } else if (inst instanceof MirInst.Move m) {
                    aliasTarget.put(m.result().id(), resolveRoot(m.operand()));
                } else if (inst instanceof MirInst.Freeze f) {
                    aliasTarget.put(f.result().id(), resolveRoot(f.operand()));
                }
            }
        }

        // 3. Compute backward CFG liveness on canonical root values for sound interval coloring.
        Map<Integer, Integer> bStart = new HashMap<>(rpo.size() * 2);
        Map<Integer, Integer> bEnd = new HashMap<>(rpo.size() * 2);
        Map<Integer, Set<Integer>> uses = new HashMap<>(rpo.size() * 2);
        Map<Integer, Set<Integer>> defs = new HashMap<>(rpo.size() * 2);
        Map<Integer, Set<Integer>> blockParamIds = new HashMap<>(rpo.size() * 2);
        Map<Integer, Integer> defPos = new HashMap<>();
        Map<Integer, Integer> lastUsePos = new HashMap<>();
        Map<Integer, PrimitiveKind> valKind = new HashMap<>();

        int pos = 0;
        for (MirBlock b : rpo) {
            int start = pos++;
            bStart.put(b.id(), start);
            Set<Integer> bUses = new HashSet<>();
            Set<Integer> bDefs = new HashSet<>();
            Set<Integer> bpIds = new HashSet<>();
            for (MirVal bp : b.params()) {
                bpIds.add(bp.id());
                bDefs.add(bp.id());
                defPos.put(bp.id(), start);
                valKind.put(bp.id(), bp.kind());
            }
            for (MirInst inst : b.insts()) {
                int ipos = pos++;
                for (MirVal u : inst.usedValues()) {
                    MirVal root = resolveRoot(u);
                    if (!bDefs.contains(root.id())) {
                        bUses.add(root.id());
                    }
                    lastUsePos.merge(root.id(), ipos, Math::max);
                }
                MirVal res = inst.result();
                if (res != null && !aliasTarget.containsKey(res.id())) {
                    bDefs.add(res.id());
                    defPos.put(res.id(), ipos);
                    valKind.put(res.id(), res.kind());
                }
            }
            int tpos = pos++;
            for (MirVal u : b.terminator().usedValues()) {
                MirVal root = resolveRoot(u);
                if (!bDefs.contains(root.id())) {
                    bUses.add(root.id());
                }
                lastUsePos.merge(root.id(), tpos, Math::max);
            }
            bEnd.put(b.id(), tpos);
            uses.put(b.id(), bUses);
            defs.put(b.id(), bDefs);
            blockParamIds.put(b.id(), bpIds);
        }

        MirCfg cfg = MirCfg.analyze(fn);
        Map<Integer, Set<Integer>> liveIn = new HashMap<>(rpo.size() * 2);
        Map<Integer, Set<Integer>> liveOut = new HashMap<>(rpo.size() * 2);
        for (MirBlock b : rpo) {
            liveIn.put(b.id(), new HashSet<>(uses.get(b.id())));
            liveOut.put(b.id(), new HashSet<>());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = rpo.size() - 1; i >= 0; i--) {
                int bid = rpo.get(i).id();
                Set<Integer> out = liveOut.get(bid);
                for (int succId : cfg.successors(bid)) {
                    Set<Integer> sIn = liveIn.get(succId);
                    if (sIn == null) continue;
                    Set<Integer> sParams = blockParamIds.get(succId);
                    for (int v : sIn) {
                        if (!sParams.contains(v) && out.add(v)) {
                            changed = true;
                        }
                    }
                }
                Set<Integer> in = liveIn.get(bid);
                Set<Integer> bDefs = defs.get(bid);
                for (int v : out) {
                    if (!bDefs.contains(v) && in.add(v)) {
                        changed = true;
                    }
                }
            }
        }

        // 4. Build [start, end] live intervals for all non-param, non-alias SSA values.
        record Interval(int valId, int start, int end, int category, PrimitiveKind kind) {
        }
        List<Interval> intervals = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : defPos.entrySet()) {
            int vid = e.getKey();
            if (valSlots.containsKey(vid)) {
                continue;
            }
            int s = e.getValue();
            int end = Math.max(s, lastUsePos.getOrDefault(vid, s));
            for (MirBlock b : rpo) {
                int bid = b.id();
                if (liveIn.get(bid).contains(vid)) {
                    s = Math.min(s, bStart.get(bid));
                    end = Math.max(end, bEnd.get(bid));
                }
                if (liveOut.get(bid).contains(vid)) {
                    end = Math.max(end, bEnd.get(bid));
                }
            }
            PrimitiveKind pk = valKind.get(vid);
            intervals.add(new Interval(vid, s, end, jvmSlotCategory(pk), pk));
        }
        intervals.sort((a, b) -> a.start != b.start ? Integer.compare(a.start, b.start) : Integer.compare(a.valId, b.valId));

        // 5. Linear-scan coloring segregated by JVM slot verification category (I/J/F/D/A).
        Map<Integer, List<int[]>> activeByCat = new HashMap<>(); // cat -> list of [endPos, slot]
        Map<Integer, List<Integer>> freeByCat = new HashMap<>();
        for (Interval iv : intervals) {
            List<int[]> active = activeByCat.computeIfAbsent(iv.category, k -> new ArrayList<>());
            List<Integer> free = freeByCat.computeIfAbsent(iv.category, k -> new ArrayList<>());
            for (int k = active.size() - 1; k >= 0; k--) {
                if (active.get(k)[0] < iv.start) {
                    free.add(active.remove(k)[1]);
                }
            }
            int slot = free.isEmpty() ? ctx.newLocal(iv.kind) : free.remove(free.size() - 1);
            valSlots.put(iv.valId, slot);
            active.add(new int[]{iv.end, slot});
        }
    }

    private MirVal resolveRoot(MirVal v) {
        MirVal cur = v;
        while (aliasTarget.containsKey(cur.id())) {
            cur = aliasTarget.get(cur.id());
        }
        return cur;
    }

    private int slotOf(MirVal v) {
        return valSlots.get(resolveRoot(v).id());
    }

    private static int jvmSlotCategory(PrimitiveKind k) {
        return switch (k) {
            case BYTE, SHORT, INT, BOOL, CHAR -> 0;
            case LONG -> 1;
            case FLOAT -> 2;
            case DOUBLE -> 3;
            case NONE -> 4;
        };
    }

    private void loadVal(MirVal v) {
        loadKindOn(mv, v.kind(), slotOf(v));
        ctx.stackKind = v.kind();
    }

    private void storeResult(MirVal res) {
        emitter.convertStack(ctx.stackKind, res.kind());
        storeKindOn(mv, res.kind(), slotOf(res));
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ------------------------------------------------------------------ instruction emission

    private void emitInst(MirInst inst) {
        switch (inst) {
            case MirInst.Const c -> emitConst(c);
            case MirInst.Copy c -> {
                loadVal(c.source());
                storeResult(c.result());
            }
            case MirInst.Narrow ignored -> {} // zero-cost alias
            case MirInst.Move ignored -> {}   // zero-cost alias
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
            case MirInst.CheckCast cc -> emitCheckCast(cc);
            case MirInst.NonNull nn -> emitNonNull(nn);
            case MirInst.IsCheck ic -> emitIsCheck(ic);
            case MirInst.LoadField lf -> emitLoadField(lf);
            case MirInst.StoreField sf -> emitStoreField(sf);
            case MirInst.LoadJavaField lj -> emitLoadJavaField(lj);
            case MirInst.StoreJavaField sj -> emitStoreJavaField(sj);
            case MirInst.ArrayGet ag -> emitArrayGet(ag);
            case MirInst.ArraySet as -> emitArraySet(as);
            case MirInst.ArrayLen al -> emitArrayLen(al);
            case MirInst.CallBabble cb -> emitCallBabble(cb);
            case MirInst.CallMethod cm -> emitCallMethod(cm);
            case MirInst.CallInterface ci -> emitCallInterface(ci);
            case MirInst.CallJava cj -> emitCallJava(cj);
            case MirInst.CallJavaCtor cc -> emitCallJavaCtor(cc);
            case MirInst.RecordNew rn -> emitRecordNew(rn);
            case MirInst.RecordReuse rr -> emitRecordReuse(rr);
            case MirInst.SlotDecl sd -> emitSlotDecl(sd);
            case MirInst.SlotRead sr -> {
                emitter.loadVariable(sr.var(), sr.line());
                storeResult(sr.result());
            }
            case MirInst.SlotWrite sw -> {
                loadVal(sw.value());
                PrimitiveKind vk = sw.var().primitiveKind();
                emitter.convertStack(ctx.stackKind, vk);
                emitter.storeVariable(sw.var(), sw.opKind() == MirInst.SlotOpKind.INIT, vk, sw.line());
                popOn(mv, ctx.stackKind);
                ctx.stackKind = PrimitiveKind.NONE;
            }
            case MirInst.ScopeEnter se -> {
                mv.visitVarInsn(ALOAD, ctx.nsLocal);
                mv.visitVarInsn(ASTORE, slotOf(se.result()));
                mv.visitVarInsn(ALOAD, ctx.nsLocal);
                emitter.getStringArray(se.declaredNames());
                mv.visitMethodInsn(INVOKEVIRTUAL, NAMESPACE, "child", "([Ljava/lang/String;)L" + NAMESPACE + ";", false);
                mv.visitVarInsn(ASTORE, ctx.nsLocal);
            }
            case MirInst.ScopeExit sx -> {
                mv.visitVarInsn(ALOAD, slotOf(sx.prevNamespace()));
                mv.visitVarInsn(ASTORE, ctx.nsLocal);
            }
            case MirInst.ForIterInit fi -> emitForIterInit(fi);
            case MirInst.ForIterHasNext fh -> emitForIterHasNext(fh);
            case MirInst.ForIterNext fnInst -> emitForIterNext(fnInst);
            case MirInst.UnwrapThrown ut -> {
                mv.visitVarInsn(ALOAD, slotOf(ut.thrownExn()));
                mv.visitMethodInsn(INVOKEVIRTUAL, RUNTIME + "ThrownException", "value", "()Ljava/lang/Object;", false);
                mv.visitVarInsn(ASTORE, slotOf(ut.result()));
            }
            case MirInst.CatchMatch cm -> emitCatchMatch(cm);
            case MirInst.AddSuppressed as -> {
                mv.visitVarInsn(ALOAD, slotOf(as.primary()));
                mv.visitVarInsn(ALOAD, slotOf(as.secondary()));
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Throwables", "addSuppressed",
                        "(Ljava/lang/Throwable;Ljava/lang/Throwable;)V", false);
            }
            case MirInst.OpaqueExpr op -> emitOpaque(op);
        }
    }

    private void emitConst(MirInst.Const c) {
        Object v = c.value();
        if (v == null) {
            mv.visitInsn(ACONST_NULL);
            ctx.stackKind = PrimitiveKind.NONE;
        } else if (v instanceof Boolean b) {
            mv.visitInsn(b ? ICONST_1 : ICONST_0);
            ctx.stackKind = PrimitiveKind.BOOL;
        } else if (v instanceof Byte b) {
            ConstantPool.pushInt(mv, b);
            ctx.stackKind = PrimitiveKind.BYTE;
        } else if (v instanceof Short s) {
            ConstantPool.pushInt(mv, s);
            ctx.stackKind = PrimitiveKind.SHORT;
        } else if (v instanceof Integer i) {
            ConstantPool.pushInt(mv, i);
            ctx.stackKind = PrimitiveKind.INT;
        } else if (v instanceof Long l) {
            mv.visitLdcInsn(l);
            ctx.stackKind = PrimitiveKind.LONG;
        } else if (v instanceof Float f) {
            mv.visitLdcInsn(f);
            ctx.stackKind = PrimitiveKind.FLOAT;
        } else if (v instanceof Double d) {
            mv.visitLdcInsn(d);
            ctx.stackKind = PrimitiveKind.DOUBLE;
        } else if (v instanceof Character ch) {
            ConstantPool.pushInt(mv, ch);
            ctx.stackKind = PrimitiveKind.CHAR;
        } else if (v instanceof String s) {
            ConstantPool.pushString(mv, s);
            ctx.stackKind = PrimitiveKind.NONE;
        } else {
            throw new IllegalStateException("Unsupported MIR constant : " + v.getClass());
        }
        storeResult(c.result());
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
            Label isTrue = new Label();
            Label end = new Label();
            emitBranchOnTruth(u.operand(), false, isTrue);
            mv.visitInsn(ICONST_0);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(isTrue);
            mv.visitInsn(ICONST_1);
            mv.visitLabel(end);
            ctx.stackKind = PrimitiveKind.BOOL;
            storeResult(u.result());
            return;
        }
        PrimitiveKind rk = u.result().kind();
        if (rk == PrimitiveKind.INT || rk == PrimitiveKind.LONG
                || rk == PrimitiveKind.FLOAT || rk == PrimitiveKind.DOUBLE) {
            loadVal(u.operand());
            emitter.convertStack(u.operand().kind(), rk);
            mv.visitInsn(switch (rk) {
                case INT -> INEG;
                case LONG -> LNEG;
                case FLOAT -> FNEG;
                case DOUBLE -> DNEG;
                default -> throw new IllegalStateException();
            });
            ctx.stackKind = rk;
            storeResult(u.result());
            return;
        }
        HirExpr opPh = placeholder(u.operand(), u.line());
        withOverrides(Map.of(opPh, operandOf(u.operand())), () ->
                emitter.controlOps.emitUnary(new HirExpr.Unary(u.op(), opPh, u.operandText(),
                        ExprMeta.of(u.line(), rk))));
        storeResult(u.result());
    }

    private void emitBinary(MirInst.Binary b) {
        PrimitiveKind lk = b.left().kind();
        PrimitiveKind rk = b.right().kind();
        PrimitiveKind out = b.result().kind();

        if (b.op().isComparison() && out == PrimitiveKind.BOOL && canEmitPrimitiveCmp(b.op(), lk, rk)) {
            emitPrimitiveComparison(b.op(), b.left(), b.right());
            storeResult(b.result());
            return;
        }
        if (b.op().isArithmetic() && isNumericKind(lk) && isNumericKind(rk)) {
            if ((out == PrimitiveKind.INT || out == PrimitiveKind.LONG)
                    && (b.op() == HirExpr.BinaryOp.ADD || b.op() == HirExpr.BinaryOp.SUB || b.op() == HirExpr.BinaryOp.MUL)) {
                loadVal(b.left());
                emitter.convertStack(lk, out);
                loadVal(b.right());
                emitter.convertStack(rk, out);
                int insn = out == PrimitiveKind.INT
                        ? switch (b.op()) { case ADD -> IADD; case SUB -> ISUB; case MUL -> IMUL; default -> throw new IllegalStateException(); }
                        : switch (b.op()) { case ADD -> LADD; case SUB -> LSUB; case MUL -> LMUL; default -> throw new IllegalStateException(); };
                mv.visitInsn(insn);
                ctx.stackKind = out;
                storeResult(b.result());
                return;
            }
            if (out == PrimitiveKind.FLOAT || out == PrimitiveKind.DOUBLE) {
                loadVal(b.left());
                emitter.convertStack(lk, out);
                loadVal(b.right());
                emitter.convertStack(rk, out);
                int insn = out == PrimitiveKind.FLOAT
                        ? switch (b.op()) { case ADD -> FADD; case SUB -> FSUB; case MUL -> FMUL; case DIV -> FDIV; default -> throw new IllegalStateException(); }
                        : switch (b.op()) { case ADD -> DADD; case SUB -> DSUB; case MUL -> DMUL; case DIV -> DDIV; default -> throw new IllegalStateException(); };
                mv.visitInsn(insn);
                ctx.stackKind = out;
                storeResult(b.result());
                return;
            }
        }

        // Dynamic / string / operator-method binary op delegated cleanly to ControlAndOpEmitter.
        HirExpr lPh = placeholder(b.left(), b.line());
        HirExpr rPh = placeholder(b.right(), b.line());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(2);
        ov.put(lPh, operandOf(b.left()));
        ov.put(rPh, operandOf(b.right()));
        withOverrides(ov, () -> emitter.controlOps.emitBinary(
                new HirExpr.Binary(b.op(), lPh, rPh, b.leftText(), b.rightText(), ExprMeta.of(b.line(), out))));
        storeResult(b.result());
    }

    private static boolean canEmitPrimitiveCmp(HirExpr.BinaryOp op, PrimitiveKind lk, PrimitiveKind rk) {
        if (lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL) {
            return op == HirExpr.BinaryOp.EQ || op == HirExpr.BinaryOp.NEQ;
        }
        if (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR) {
            return true;
        }
        return isNumericKind(lk) && isNumericKind(rk);
    }

    private void emitPrimitiveComparison(HirExpr.BinaryOp op, MirVal left, MirVal right) {
        Label isTrue = new Label();
        Label end = new Label();
        PrimitiveKind lk = left.kind();
        PrimitiveKind rk = right.kind();
        if ((lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL)
                || (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR)) {
            loadVal(left);
            loadVal(right);
            mv.visitJumpInsn(icmpJump(op), isTrue);
        } else {
            PrimitiveKind common = numericOrder(lk) >= numericOrder(rk) ? lk : rk;
            loadVal(left);
            emitter.convertStack(lk, common);
            loadVal(right);
            emitter.convertStack(rk, common);
            if (numericOrder(common) <= 2) {
                mv.visitJumpInsn(icmpJump(op), isTrue);
            } else {
                if (common == PrimitiveKind.LONG) {
                    mv.visitInsn(LCMP);
                } else if (common == PrimitiveKind.FLOAT) {
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false);
                } else {
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
                }
                mv.visitJumpInsn(switch (op) {
                    case LT -> IFLT;
                    case LTE -> IFLE;
                    case EQ -> IFEQ;
                    case NEQ -> IFNE;
                    case GTE -> IFGE;
                    case GT -> IFGT;
                    default -> throw new IllegalStateException();
                }, isTrue);
            }
        }
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(isTrue);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(end);
        ctx.stackKind = PrimitiveKind.BOOL;
    }

    private static int icmpJump(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> IF_ICMPLT;
            case LTE -> IF_ICMPLE;
            case EQ -> IF_ICMPEQ;
            case NEQ -> IF_ICMPNE;
            case GTE -> IF_ICMPGE;
            case GT -> IF_ICMPGT;
            default -> throw new IllegalStateException();
        };
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
            mv.visitFieldInsn(GETSTATIC, RUNTIME + "Undefined", "UNDEFINED", "L" + RUNTIME + "Undefined;");
            mv.visitVarInsn(ASTORE, slot);
        }
    }

    // ------------------------------------------------------------------ delegated ABI instructions

    private void emitCheckCast(MirInst.CheckCast cc) {
        HirExpr ph = placeholder(cc.operand(), cc.line());
        withOverrides(Map.of(ph, operandOf(cc.operand())), () -> emitter.controlOps.emitAsExpr(
                new HirExpr.As(ph, cc.target(), cc.samTarget(), cc.descriptor(), ExprMeta.of(cc.line(), cc.result().kind()))));
        storeResult(cc.result());
    }

    private void emitNonNull(MirInst.NonNull nn) {
        HirExpr ph = placeholder(nn.operand(), nn.line());
        withOverrides(Map.of(ph, operandOf(nn.operand())), () -> emitter.controlOps.emitNonNull(
                new HirExpr.NonNull(ph, nn.expectedType(), ExprMeta.of(nn.line(), nn.result().kind()))));
        storeResult(nn.result());
    }

    private void emitIsCheck(MirInst.IsCheck ic) {
        HirExpr ph = placeholder(ic.operand(), ic.line());
        withOverrides(Map.of(ph, operandOf(ic.operand())), () -> emitter.controlOps.emitIs(
                new HirExpr.Is(ph, ic.builtin(), ic.target(), ic.intersectionTargets(), ic.descriptor(),
                        ExprMeta.of(ic.line(), ic.result().kind()))));
        storeResult(ic.result());
    }

    private void emitLoadField(MirInst.LoadField lf) {
        HirExpr ph = placeholder(lf.receiver(), lf.line());
        withOverrides(Map.of(ph, operandOf(lf.receiver())), () -> emitter.assigns.emitThisFieldGet(
                new HirExpr.ThisFieldGet(ph, lf.receiverText(), lf.classId(), lf.externalClassJvmName(),
                        lf.memberIndex(), lf.memberName(), lf.fieldKind(), lf.recordField(), lf.definitelyDefined(),
                        ExprMeta.of(lf.line(), lf.result().kind()))));
        storeResult(lf.result());
    }

    private void emitStoreField(MirInst.StoreField sf) {
        HirExpr rPh = placeholder(sf.receiver(), sf.line());
        HirExpr vPh = placeholder(sf.value(), sf.line());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(2);
        ov.put(rPh, operandOf(sf.receiver()));
        ov.put(vPh, operandOf(sf.value()));
        withOverrides(ov, () -> emitter.assigns.emitThisFieldAssign(
                new HirExpr.ThisFieldAssign(rPh, sf.classId(), sf.memberIndex(), sf.memberName(), null, vPh,
                        sf.targetText(), sf.valueText(), ExprMeta.of(sf.line(), sf.value().kind()))));
        popOn(mv, ctx.stackKind);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitLoadJavaField(MirInst.LoadJavaField lj) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1);
        HirExpr rPh = null;
        if (lj.receiver() != null) {
            rPh = placeholder(lj.receiver(), lj.line());
            ov.put(rPh, operandOf(lj.receiver()));
        }
        HirExpr finalR = rPh;
        withOverrides(ov, () -> emitter.assigns.emitJavaFieldGet(
                new HirExpr.JavaFieldGet(finalR, lj.receiverText(), lj.pureStaticReceiver(), lj.target(),
                        ExprMeta.of(lj.line(), lj.result().kind()))));
        storeResult(lj.result());
    }

    private void emitStoreJavaField(MirInst.StoreJavaField sj) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(2);
        HirExpr rPh = null;
        if (sj.receiver() != null) {
            rPh = placeholder(sj.receiver(), sj.line());
            ov.put(rPh, operandOf(sj.receiver()));
        }
        HirExpr vPh = placeholder(sj.value(), sj.line());
        ov.put(vPh, operandOf(sj.value()));
        HirExpr finalR = rPh;
        withOverrides(ov, () -> emitter.assigns.emitJavaFieldAssignExpr(
                new HirExpr.JavaFieldAssign(finalR, sj.receiverText(), sj.pureStaticReceiver(), sj.target(), null,
                        vPh, sj.targetText(), sj.valueText(), ExprMeta.of(sj.line(), sj.value().kind()))));
        popOn(mv, ctx.stackKind);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitArrayGet(MirInst.ArrayGet ag) {
        HirExpr aPh = placeholder(ag.array(), ag.line());
        HirExpr iPh = placeholder(ag.index(), ag.line());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(2);
        ov.put(aPh, operandOf(ag.array()));
        ov.put(iPh, operandOf(ag.index()));
        withOverrides(ov, () -> emitter.assigns.emitArrayOp(
                new HirExpr.ArrayOp(LoweringTargets.ArrayOpKind.GET, aPh, ag.receiverText(), ag.elemKind(),
                        ag.arrayClass(), ag.componentClass(), List.of(iPh), ExprMeta.of(ag.line(), ag.result().kind()))));
        storeResult(ag.result());
    }

    private void emitArraySet(MirInst.ArraySet as) {
        HirExpr aPh = placeholder(as.array(), as.line());
        HirExpr iPh = placeholder(as.index(), as.line());
        HirExpr vPh = placeholder(as.value(), as.line());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(3);
        ov.put(aPh, operandOf(as.array()));
        ov.put(iPh, operandOf(as.index()));
        ov.put(vPh, operandOf(as.value()));
        withOverrides(ov, () -> emitter.assigns.emitArrayOp(
                new HirExpr.ArrayOp(LoweringTargets.ArrayOpKind.SET, aPh, as.receiverText(), as.elemKind(),
                        as.arrayClass(), as.componentClass(), List.of(iPh, vPh), ExprMeta.of(as.line(), PrimitiveKind.NONE))));
        popOn(mv, ctx.stackKind);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitArrayLen(MirInst.ArrayLen al) {
        if (al.receiverText() == null) {
            // Array length inside typed for-loop where array is already checked & unwrapped.
            mv.visitVarInsn(ALOAD, slotOf(al.array()));
            mv.visitInsn(ARRAYLENGTH);
            ctx.stackKind = PrimitiveKind.INT;
            storeResult(al.result());
            return;
        }
        HirExpr aPh = placeholder(al.array(), al.line());
        withOverrides(Map.of(aPh, operandOf(al.array())), () -> emitter.assigns.emitArrayOp(
                new HirExpr.ArrayOp(LoweringTargets.ArrayOpKind.SIZE, aPh, al.receiverText(), al.elemKind(),
                        al.arrayClass(), al.componentClass(), List.of(), ExprMeta.of(al.line(), PrimitiveKind.INT))));
        storeResult(al.result());
    }

    private void emitCallBabble(MirInst.CallBabble cb) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cb.args().size());
        List<HirExpr> argPhs = makePlaceholders(cb.args(), cb.line(), ov);
        if (cb.selfRecurse()) {
            withOverrides(ov, () -> emitter.calls.emitSelfRecurseCall(
                    new HirExpr.SelfRecurseCall(cb.targetFunctionId(), argPhs, cb.binding(),
                            ExprMeta.of(cb.line(), cb.result().kind()))));
        } else {
            HirExpr cPh = placeholder(cb.callee(), cb.line());
            ov.put(cPh, operandOf(cb.callee()));
            withOverrides(ov, () -> emitter.calls.emitDirectBabbleCall(
                    new HirExpr.DirectBabbleCall(cPh, cb.targetFunctionId(), argPhs, cb.binding(),
                            ExprMeta.of(cb.line(), cb.result().kind()))));
        }
        storeResult(cb.result());
    }

    private void emitCallMethod(MirInst.CallMethod cm) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cm.args().size());
        HirExpr rPh = placeholder(cm.receiver(), cm.line());
        ov.put(rPh, operandOf(cm.receiver()));
        List<HirCallArg> callArgs = new ArrayList<>(cm.args().size());
        for (int i = 0; i < cm.args().size(); i++) {
            MirVal a = cm.args().get(i);
            HirExpr ph = placeholder(a, cm.line());
            ov.put(ph, operandOf(a));
            callArgs.add(new HirCallArg(cm.argNames().get(i), ph));
        }
        withOverrides(ov, () -> emitter.calls.emitDirectThisMethodCall(
                new HirExpr.DirectThisMethodCall(rPh, cm.receiverText(), cm.classId(), cm.externalClassJvmName(),
                        cm.classTypeId(), cm.memberName(), cm.overloadAltIndex(), cm.targetFunctionId(),
                        cm.externalSignature(), callArgs, cm.binding(), cm.callSite(),
                        ExprMeta.of(cm.line(), cm.result().kind()))));
        storeResult(cm.result());
    }

    private void emitCallInterface(MirInst.CallInterface ci) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + ci.args().size());
        HirExpr rPh = placeholder(ci.receiver(), ci.line());
        ov.put(rPh, operandOf(ci.receiver()));
        List<HirExpr> argPhs = makePlaceholders(ci.args(), ci.line(), ov);
        withOverrides(ov, () -> emitter.calls.emitInterfaceCall(
                new HirExpr.InterfaceCall(rPh, ci.receiverText(), ci.selectorLine(), ci.interfaceTypeId(),
                        ci.memberName(), ci.signature(), argPhs, ci.binding(), ExprMeta.of(ci.line(), ci.result().kind()))));
        storeResult(ci.result());
    }

    private void emitCallJava(MirInst.CallJava cj) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cj.args().size());
        HirExpr rPh = null;
        if (cj.receiver() != null) {
            rPh = placeholder(cj.receiver(), cj.line());
            ov.put(rPh, operandOf(cj.receiver()));
        }
        List<HirExpr> argPhs = makePlaceholders(cj.args(), cj.line(), ov);
        HirExpr finalR = rPh;
        withOverrides(ov, () -> emitter.calls.emitJavaMethodCall(
                new HirExpr.JavaMethodCall(finalR, cj.receiverText(), cj.selectorLine(), cj.pureStaticReceiver(),
                        cj.target(), argPhs, ExprMeta.of(cj.line(), cj.result().kind()))));
        storeResult(cj.result());
    }

    private void emitCallJavaCtor(MirInst.CallJavaCtor cc) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + cc.args().size());
        HirExpr cPh = null;
        if (cc.callee() != null) {
            cPh = placeholder(cc.callee(), cc.line());
            ov.put(cPh, operandOf(cc.callee()));
        }
        List<HirExpr> argPhs = makePlaceholders(cc.args(), cc.line(), ov);
        HirExpr finalC = cPh;
        withOverrides(ov, () -> emitter.calls.emitJavaConstructorCall(
                new HirExpr.JavaConstructorCall(finalC, cc.calleeText(), cc.pureStaticCallee(), cc.target(),
                        argPhs, ExprMeta.of(cc.line(), cc.result().kind()))));
        storeResult(cc.result());
    }

    private void emitRecordNew(MirInst.RecordNew rn) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + rn.args().size());
        HirExpr crPh = placeholder(rn.classRef(), rn.line());
        ov.put(crPh, operandOf(rn.classRef()));
        List<HirExpr> argPhs = makePlaceholders(rn.args(), rn.line(), ov);
        withOverrides(ov, () -> emitter.calls.emitRecordNew(
                new HirExpr.RecordNew(rn.classId(), rn.classTypeId(), rn.classJvmName(), crPh,
                        rn.classRefIsInstance(), rn.paramKinds(), argPhs, rn.binding(),
                        ExprMeta.of(rn.line(), PrimitiveKind.NONE))));
        storeResult(rn.result());
    }

    private void emitRecordReuse(MirInst.RecordReuse rr) {
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(1 + rr.args().size());
        HirExpr tPh = placeholder(rr.target(), rr.line());
        ov.put(tPh, operandOf(rr.target()));
        List<HirExpr> argPhs = makePlaceholders(rr.args(), rr.line(), ov);
        withOverrides(ov, () -> emitter.calls.emitRecordReuse(
                new HirExpr.RecordReuse(tPh, rr.classId(), rr.classTypeId(), rr.classJvmName(),
                        rr.paramKinds(), argPhs, ExprMeta.of(rr.line(), PrimitiveKind.NONE))));
        storeResult(rr.result());
    }

    private void emitOpaque(MirInst.OpaqueExpr op) {
        List<HirExpr> children = MirLowerer.opaqueChildExprs(op.template());
        Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            ov.put(children.get(i), operandOf(op.operands().get(i)));
        }
        withOverrides(ov, () -> emitter.emitExpr(op.template()));
        storeResult(op.result());
    }

    // ------------------------------------------------------------------ for-loop & try-catch instructions

    private void emitForIterInit(MirInst.ForIterInit fi) {
        int ln = fi.line();
        LoweringTargets.ForLoopTarget target = fi.target();
        LoweringTargets.ForKind kind = target.kind();
        int recvSlot = slotOf(fi.iterable());
        switch (kind) {
            case ARRAY -> {
                String arrDesc = Type.getDescriptor(target.arrayClass());
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, arrDesc);
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case JAVA_ITERABLE -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
                ctx.line(ln);
                mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case JAVA_ITERATOR -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case BABBLE_TO_JAVA_ITERABLE -> {
                emitter.controlOps.emitZeroArgBabbleCall(recvSlot, "iterator", null, ln);
                emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case BABBLE_ITERABLE -> {
                emitter.controlOps.emitZeroArgBabbleCall(recvSlot, "iterator", null, ln);
                emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case BABBLE_ITERATOR -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
            case DYNAMIC_UNION -> {
                mv.visitVarInsn(ALOAD, recvSlot);
                ctx.line(ln);
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "asIterator", "(Ljava/lang/Object;)Ljava/util/Iterator;", false);
                mv.visitVarInsn(ASTORE, slotOf(fi.result()));
            }
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitForIterHasNext(MirInst.ForIterHasNext fh) {
        int ln = fh.line();
        LoweringTargets.ForLoopTarget target = fh.target();
        LoweringTargets.ForKind kind = target.kind();
        if (kind == LoweringTargets.ForKind.ARRAY) {
            emitPrimitiveComparison(HirExpr.BinaryOp.LT, fh.index(), fh.length());
            storeResult(fh.result());
            return;
        }
        if (kind == LoweringTargets.ForKind.BABBLE_ITERABLE || kind == LoweringTargets.ForKind.BABBLE_ITERATOR) {
            emitter.controlOps.emitZeroArgBabbleCall(slotOf(fh.iterator()), "hasNext", target.hasNextSig(), ln);
            if (ctx.stackKind != PrimitiveKind.BOOL) {
                emitter.convertStack(ctx.stackKind, PrimitiveKind.NONE);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Truth", "test", "(Ljava/lang/Object;)Z", false);
                ctx.stackKind = PrimitiveKind.BOOL;
            }
            storeResult(fh.result());
            return;
        }
        mv.visitVarInsn(ALOAD, slotOf(fh.iterator()));
        ctx.line(ln);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        ctx.stackKind = PrimitiveKind.BOOL;
        storeResult(fh.result());
    }

    private void emitForIterNext(MirInst.ForIterNext fnInst) {
        int ln = fnInst.line();
        LoweringTargets.ForLoopTarget target = fnInst.target();
        LoweringTargets.ForKind kind = target.kind();
        if (kind == LoweringTargets.ForKind.ARRAY) {
            Class<?> comp = target.arrayClass().getComponentType();
            PrimitiveKind elemKind = javaPrimitiveKind(comp);
            mv.visitVarInsn(ALOAD, slotOf(fnInst.iterator()));
            mv.visitVarInsn(ILOAD, slotOf(fnInst.index()));
            if (elemKind != PrimitiveKind.NONE) {
                mv.visitInsn(switch (elemKind) {
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
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                ctx.stackKind = PrimitiveKind.NONE;
            }
            storeResult(fnInst.result());
            return;
        }
        if (kind == LoweringTargets.ForKind.BABBLE_ITERABLE || kind == LoweringTargets.ForKind.BABBLE_ITERATOR) {
            emitter.controlOps.emitZeroArgBabbleCall(slotOf(fnInst.iterator()), "next", target.nextSig(), ln);
            storeResult(fnInst.result());
            return;
        }
        mv.visitVarInsn(ALOAD, slotOf(fnInst.iterator()));
        ctx.line(ln);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        ctx.stackKind = PrimitiveKind.NONE;
        storeResult(fnInst.result());
    }

    private void emitCatchMatch(MirInst.CatchMatch cm) {
        LoweringTargets.CatchTarget ct = cm.target();
        int valSlot = slotOf(cm.value());
        List<String> directJvms = emitter.controlOps.resolveBabbleCatchJvms(ct.babbleTargets());
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
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Types", "matches", "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
        } else {
            mv.visitInsn(ICONST_0);
        }
        ctx.stackKind = PrimitiveKind.BOOL;
        storeResult(cm.result());
    }

    // ------------------------------------------------------------------ terminators & parallel copy

    private boolean emitTerminator(MirTerminator term, MirBlock nextBlock) {
        switch (term) {
            case MirTerminator.Br br -> {
                boolean copied = hasNonTrivialCopies(br.args(), blockById.get(br.targetBlock()).params());
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
                    // Inline else-edge copy + jump to elseBlock.
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
                    mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Throwable");
                } else {
                    mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Throwables", "toThrowable",
                            "(Ljava/lang/Object;)Ljava/lang/Throwable;", false);
                }
                mv.visitInsn(ATHROW);
                return true;
            }
            case MirTerminator.TailCall tc -> {
                Map<HirExpr, MethodEmitContext.MirOperand> ov = new IdentityHashMap<>(tc.args().size());
                List<HirCallArg> callArgs = new ArrayList<>(tc.args().size());
                for (int i = 0; i < tc.args().size(); i++) {
                    MirVal a = tc.args().get(i);
                    HirExpr ph = placeholder(a, tc.line());
                    ov.put(ph, operandOf(a));
                    callArgs.add(new HirCallArg(tc.argNames().get(i), ph));
                }
                withOverrides(ov, () -> emitter.calls.emitSelfTailCall(
                        new HirExpr.SelfTailCall(tc.functionId(), callArgs, tc.binding(),
                                ExprMeta.of(tc.line(), PrimitiveKind.NONE))));
                return true;
            }
            case MirTerminator.Unreachable ignored -> {
                mv.visitInsn(ACONST_NULL);
                mv.visitInsn(ATHROW);
                return true;
            }
        }
    }

    private void emitBranchOnTruth(MirVal cond, boolean branchIfTrue, Label target) {
        loadVal(cond);
        switch (cond.kind()) {
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

    private boolean hasNonTrivialCopies(List<MirVal> args, List<MirVal> params) {
        for (int i = 0; i < args.size(); i++) {
            if (slotOf(args.get(i)) != slotOf(params.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cycle-safe parallel copy of edge {@code args} into target block {@code params}.
     */
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
        for (int i = 0; i < args.size(); i++) {
            int src = slotOf(args.get(i));
            int dst = slotOf(params.get(i));
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
                // Break cycle by spilling the first move's source into a fresh temporary of matching kind.
                MoveOp cyc = pending.get(0);
                int tmp = ctx.newLocal(cyc.kind);
                loadKindOn(mv, cyc.kind, cyc.src);
                storeKindOn(mv, cyc.kind, tmp);
                cyc.src = tmp;
            }
        }
    }

    // ------------------------------------------------------------------ mirOverrides helpers

    private HirExpr placeholder(MirVal v, int line) {
        return new HirExpr.Const(null, ExprMeta.of(line, v.kind()));
    }

    private MethodEmitContext.MirOperand operandOf(MirVal v) {
        return new MethodEmitContext.MirOperand(slotOf(v), v.kind());
    }

    private List<HirExpr> makePlaceholders(List<MirVal> vals, int line, Map<HirExpr, MethodEmitContext.MirOperand> ov) {
        List<HirExpr> out = new ArrayList<>(vals.size());
        for (MirVal v : vals) {
            HirExpr ph = placeholder(v, line);
            ov.put(ph, operandOf(v));
            out.add(ph);
        }
        return out;
    }

    private void withOverrides(Map<HirExpr, MethodEmitContext.MirOperand> overrides, Runnable body) {
        Map<HirExpr, MethodEmitContext.MirOperand> prev = ctx.mirOverrides;
        ctx.mirOverrides = overrides;
        try {
            body.run();
        } finally {
            ctx.mirOverrides = prev;
        }
    }
}
