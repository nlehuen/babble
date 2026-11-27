package org.babblelang.ir.mir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.mir.MirSsaBuilder.BlockBuilder;

/**
 * Lowers structured control flow ({@code block}, {@code if}, {@code while}, {@code for}, counted
 * range loops, {@code break}/{@code continue}/{@code return}/{@code throw}, {@code try}/{@code
 * catch}/{@code finally}, and narrowing anchors) into SSA MIR blocks.
 */
final class MirControlFlowLowerer {
    private static final String THROWN_EXCEPTION_JVM = "org/babblelang/runtime/ThrownException";
    private static final LoweringTargets.JavaCallTarget RANGE_VALIDATE_INT_STEP =
            rangeTarget("validateStep", int.class);
    private static final LoweringTargets.JavaCallTarget RANGE_VALIDATE_LONG_STEP =
            rangeTarget("validateStep", long.class);
    private static final LoweringTargets.JavaCallTarget RANGE_LAST_INT =
            rangeTarget("lastIntElement", int.class, int.class, boolean.class, int.class);
    private static final LoweringTargets.JavaCallTarget RANGE_LAST_LONG =
            rangeTarget("lastLongElement", long.class, long.class, boolean.class, long.class);
    private static final LoweringTargets.JavaCallTarget RANGE_LAST_CHAR =
            rangeTarget("lastCharElement", char.class, char.class, boolean.class, int.class);

    private static LoweringTargets.JavaCallTarget rangeTarget(String name, Class<?>... params) {
        try {
            return new LoweringTargets.JavaCallTarget(
                    org.babblelang.runtime.Range.class.getMethod(name, params), true, false);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Range helper : " + name, e);
        }
    }

    private record LoopContext(
            BlockBuilder breakBlock,
            BlockBuilder continueBlock,
            boolean passResultOnContinue,
            MirVal currentResultVal,
            int loopDepth) {}

    private record FinallyContext(
            HirExpr.Try tryNode, int loopDepthAtEntry, boolean hasNonEmptyFinally) {}

    record NarrowHint(HirVar var, MirInst.NarrowKind kind, String desc, boolean onTrueBranch) {}

    private final MirLowerer.FunctionLowerer parent;
    private final MirSsaBuilder ssa;
    private final Deque<LoopContext> loopStack = new ArrayDeque<>();
    private final Deque<FinallyContext> finallyStack = new ArrayDeque<>();

    MirControlFlowLowerer(MirLowerer.FunctionLowerer parent, MirSsaBuilder ssa) {
        this.parent = parent;
        this.ssa = ssa;
    }

    MirVal lowerBlock(HirExpr.Block b) {
        int ln = b.meta().line();
        if (!b.declaredVars().isEmpty()) {
            for (HirVar v : b.declaredVars()) {
                if (ssa.pinnedVarIds.contains(v.id())) {
                    ssa.emit(new MirInst.SlotDecl(v, ln));
                }
            }
            MirVal res = parent.lowerStatements(b.statements(), ln);
            if (!ssa.cur.isTerminated()) {
                res = ssa.applyWideningRank(res, b.innerWideningRank(), ln);
            }
            return res;
        }
        if (b.opensNamespaceScope()) {
            MirVal prevNs = ssa.newVal(PrimitiveKind.NONE, "$prevNs");
            ssa.emit(new MirInst.ScopeEnter(prevNs, b.declaredNames(), ln));
            MirVal res = parent.lowerStatements(b.statements(), ln);
            if (!ssa.cur.isTerminated()) {
                ssa.emit(new MirInst.ScopeExit(prevNs, ln));
                res = ssa.applyWideningRank(res, b.innerWideningRank(), ln);
            }
            return res;
        }
        MirVal res = parent.lowerStatements(b.statements(), ln);
        if (!ssa.cur.isTerminated()) {
            res = ssa.applyWideningRank(res, b.innerWideningRank(), ln);
        }
        return res;
    }

    MirVal lowerIf(HirExpr.If i) {
        int ln = i.meta().line();
        PrimitiveKind target = i.meta().kind();
        MirVal cond = parent.lowerExpr(i.cond());
        if (ssa.cur.isTerminated()) return cond;
        NarrowHint hint = extractNarrowHint(i.cond());

        BlockBuilder bbThen = ssa.newBlock(List.of(), false, false);
        BlockBuilder bbElse = ssa.newBlock(List.of(), false, false);
        MirVal mergeParam = ssa.newVal(target, null);
        BlockBuilder bbMerge = ssa.newBlock(List.of(mergeParam), false, false);

        ssa.terminateBrIf(cond, bbThen, List.of(), bbElse, List.of(), ln);
        ssa.sealBlock(bbThen);
        ssa.sealBlock(bbElse);

        // Then branch
        ssa.cur = bbThen;
        if (hint != null && hint.onTrueBranch) {
            emitNarrowAnchor(hint, ln);
        }
        MirVal thenVal = parent.lowerAs(i.thenBranch(), target);
        if (!ssa.cur.isTerminated()) {
            ssa.terminateBr(bbMerge, List.of(thenVal), ln);
        }

        // Else branch
        ssa.cur = bbElse;
        if (hint != null && !hint.onTrueBranch) {
            emitNarrowAnchor(hint, ln);
        }
        MirVal elseVal;
        if (i.elseBranch() != null) {
            elseVal = parent.lowerAs(i.elseBranch(), target);
        } else {
            elseVal = ssa.convertVal(ssa.emitConst(null, PrimitiveKind.NONE, ln), target, ln);
        }
        if (!ssa.cur.isTerminated()) {
            ssa.terminateBr(bbMerge, List.of(elseVal), ln);
        }

        ssa.sealBlock(bbMerge);
        ssa.cur = bbMerge;
        if (bbMerge.incomingEdges.isEmpty()) {
            ssa.terminateFixed(new MirTerminator.Unreachable(ln));
        }
        return mergeParam;
    }

    MirVal lowerWhile(HirExpr.While w) {
        int ln = w.meta().line();
        MirVal initRes = ssa.emitConst(null, PrimitiveKind.NONE, ln);
        MirVal headRes = ssa.newVal(PrimitiveKind.NONE, "$loopRes");
        MirVal exitRes = ssa.newVal(PrimitiveKind.NONE, "$loopOut");

        BlockBuilder bbHead = ssa.newBlock(List.of(headRes), false, false);
        BlockBuilder bbBody = ssa.newBlock(List.of(), false, false);
        BlockBuilder bbExit = ssa.newBlock(List.of(exitRes), false, false);

        ssa.terminateBr(bbHead, List.of(initRes), ln);

        ssa.cur = bbHead;
        MirVal cond = parent.lowerExpr(w.cond());
        if (!ssa.cur.isTerminated()) {
            ssa.terminateBrIf(cond, bbBody, List.of(), bbExit, List.of(headRes), ln);
        }
        ssa.sealBlock(bbBody);

        ssa.cur = bbBody;
        loopStack.push(new LoopContext(bbExit, bbHead, true, headRes, loopStack.size() + 1));
        MirVal bodyVal;
        try {
            bodyVal = parent.lowerBoxed(w.body());
        } finally {
            loopStack.pop();
        }
        if (!ssa.cur.isTerminated()) {
            ssa.terminateBr(bbHead, List.of(bodyVal), ln);
        }

        ssa.sealBlock(bbHead);
        ssa.sealBlock(bbExit);
        ssa.cur = bbExit;
        if (bbExit.incomingEdges.isEmpty()) {
            ssa.terminateFixed(new MirTerminator.Unreachable(ln));
        }
        return exitRes;
    }

    MirVal lowerFor(HirExpr.For f) {
        int ln = f.meta().line();
        LoweringTargets.ForLoopTarget target = f.target();
        LoweringTargets.ForKind kind =
                target != null ? target.kind() : LoweringTargets.ForKind.DYNAMIC_UNION;
        if (kind == LoweringTargets.ForKind.COUNTED_RANGE && f.countedRange() != null) {
            return lowerCountedRangeFor(f, ln);
        }
        boolean isArray = kind == LoweringTargets.ForKind.ARRAY && target.arrayClass() != null;

        MirVal iterableVal = parent.lowerBoxed(f.iterable());
        if (ssa.cur.isTerminated()) return iterableVal;

        MirVal iterState = ssa.newVal(PrimitiveKind.NONE, "$iter");
        LoweringTargets.ForLoopTarget effectiveTarget =
                target != null
                        ? target
                        : new LoweringTargets.ForLoopTarget(
                                LoweringTargets.ForKind.DYNAMIC_UNION,
                                null,
                                PrimitiveKind.NONE,
                                null,
                                0,
                                null,
                                null,
                                null,
                                null,
                                null);
        ssa.emit(
                new MirInst.ForIterInit(
                        iterState, iterableVal, effectiveTarget, ln, MirEffect.IMPURE_THROW));

        MirVal initRes = ssa.emitConst(null, PrimitiveKind.NONE, ln);
        MirVal exitRes = ssa.newVal(PrimitiveKind.NONE, "$forOut");
        BlockBuilder bbExit = ssa.newBlock(List.of(exitRes), false, false);

        if (isArray) {
            MirVal arrLen = ssa.newVal(PrimitiveKind.INT, "$len");
            ssa.emit(
                    new MirInst.ArrayLen(
                            arrLen,
                            iterState,
                            null,
                            PrimitiveKind.ofJavaClass(target.arrayClass().getComponentType()),
                            target.arrayClass(),
                            target.arrayClass().getComponentType(),
                            ln,
                            MirEffect.READ_FROZEN));
            MirVal zero = ssa.emitConst(0, PrimitiveKind.INT, ln);

            MirVal headRes = ssa.newVal(PrimitiveKind.NONE, "$forRes");
            MirVal headIdx = ssa.newVal(PrimitiveKind.INT, "$idx");
            BlockBuilder bbHead = ssa.newBlock(List.of(headRes, headIdx), false, false);
            BlockBuilder bbBody = ssa.newBlock(List.of(), false, false);
            MirVal stepRes = ssa.newVal(PrimitiveKind.NONE, "$stepRes");
            BlockBuilder bbStep = ssa.newBlock(List.of(stepRes), false, false);

            ssa.terminateBr(bbHead, List.of(initRes, zero), ln);

            ssa.cur = bbHead;
            MirVal hasNext = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.ForIterHasNext(
                            hasNext,
                            iterState,
                            headIdx,
                            arrLen,
                            effectiveTarget,
                            ln,
                            MirEffect.PURE_NOTHROW));
            ssa.terminateBrIf(hasNext, bbBody, List.of(), bbExit, List.of(headRes), ln);
            ssa.sealBlock(bbBody);

            ssa.cur = bbBody;
            PrimitiveKind rawElemKind =
                    PrimitiveKind.ofJavaClass(target.arrayClass().getComponentType());
            MirVal rawElem = ssa.newVal(rawElemKind, "$elem");
            ssa.emit(
                    new MirInst.ForIterNext(
                            rawElem,
                            iterState,
                            headIdx,
                            effectiveTarget,
                            f.elemSamTarget(),
                            ln,
                            MirEffect.read(
                                    ssa.aliasOf(iterableVal, f.iterable().meta()),
                                    false,
                                    true,
                                    true)));

            MirVal bodyRes = lowerForBody(f, rawElem, bbExit, bbStep, headRes, ln);
            if (!ssa.cur.isTerminated()) {
                ssa.terminateBr(bbStep, List.of(bodyRes), ln);
            }
            ssa.sealBlock(bbStep);

            ssa.cur = bbStep;
            if (bbStep.incomingEdges.isEmpty()) {
                ssa.terminateFixed(new MirTerminator.Unreachable(ln));
            } else {
                MirVal one = ssa.emitConst(1, PrimitiveKind.INT, ln);
                MirVal nextIdx = ssa.newVal(PrimitiveKind.INT, null);
                ssa.emit(
                        new MirInst.Binary(
                                nextIdx,
                                HirExpr.BinaryOp.ADD,
                                headIdx,
                                one,
                                null,
                                null,
                                false,
                                ln,
                                MirEffect.PURE_NOTHROW));
                ssa.terminateBr(bbHead, List.of(stepRes, nextIdx), ln);
            }
            ssa.sealBlock(bbHead);
        } else {
            MirVal headRes = ssa.newVal(PrimitiveKind.NONE, "$forRes");
            BlockBuilder bbHead = ssa.newBlock(List.of(headRes), false, false);
            BlockBuilder bbBody = ssa.newBlock(List.of(), false, false);

            ssa.terminateBr(bbHead, List.of(initRes), ln);

            ssa.cur = bbHead;
            MirVal hasNext = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.ForIterHasNext(
                            hasNext,
                            iterState,
                            null,
                            null,
                            effectiveTarget,
                            ln,
                            MirEffect.IMPURE_THROW));
            ssa.terminateBrIf(hasNext, bbBody, List.of(), bbExit, List.of(headRes), ln);
            ssa.sealBlock(bbBody);

            ssa.cur = bbBody;
            PrimitiveKind nextOutKind =
                    (kind == LoweringTargets.ForKind.BABBLE_ITERABLE
                                            || kind == LoweringTargets.ForKind.BABBLE_ITERATOR)
                                    && target != null
                                    && target.nextSig() != null
                            ? target.nextSig().returnKind()
                            : PrimitiveKind.NONE;
            MirVal rawElem = ssa.newVal(nextOutKind, "$elem");
            ssa.emit(
                    new MirInst.ForIterNext(
                            rawElem,
                            iterState,
                            null,
                            effectiveTarget,
                            f.elemSamTarget(),
                            ln,
                            MirEffect.IMPURE_THROW));

            MirVal bodyRes = lowerForBody(f, rawElem, bbExit, bbHead, headRes, ln);
            if (!ssa.cur.isTerminated()) {
                ssa.terminateBr(bbHead, List.of(bodyRes), ln);
            }
            ssa.sealBlock(bbHead);
        }

        ssa.sealBlock(bbExit);
        ssa.cur = bbExit;
        if (bbExit.incomingEdges.isEmpty()) {
            ssa.terminateFixed(new MirTerminator.Unreachable(ln));
        }
        return exitRes;
    }

    private MirVal lowerCountedRangeFor(HirExpr.For f, int ln) {
        HirExpr.CountedRangeLoop cr = f.countedRange();
        PrimitiveKind rk = cr.elemKind();
        boolean isLong = rk == PrimitiveKind.LONG;
        PrimitiveKind loopKind = isLong ? PrimitiveKind.LONG : PrimitiveKind.INT;

        MirVal startRaw = parent.lowerAs(cr.start(), rk);
        if (ssa.cur.isTerminated()) return startRaw;
        MirVal startVal = ssa.convertVal(startRaw, loopKind, ln);
        MirVal endRaw = parent.lowerAs(cr.end(), rk);
        if (ssa.cur.isTerminated()) return endRaw;
        MirVal endVal = ssa.convertVal(endRaw, loopKind, ln);

        boolean hasConstStep = false;
        long constStep = 1L;
        MirVal stepVal = null;
        if (cr.step() == null) {
            hasConstStep = true;
            constStep = 1L;
        } else if (cr.step() instanceof HirExpr.Const sc
                && sc.value() instanceof Number sn
                && sn.longValue() > 0L) {
            hasConstStep = true;
            constStep = isLong ? sn.longValue() : sn.intValue();
        } else {
            MirVal rawStep = parent.lowerAs(cr.step(), loopKind);
            if (ssa.cur.isTerminated()) return rawStep;
            stepVal = ssa.newVal(loopKind, "$step");
            ssa.emit(
                    new MirInst.CallJava(
                            stepVal,
                            null,
                            null,
                            ln,
                            true,
                            isLong ? RANGE_VALIDATE_LONG_STEP : RANGE_VALIDATE_INT_STEP,
                            List.of(rawStep),
                            false,
                            false,
                            ln,
                            MirEffect.PURE_MAY_THROW));
        }

        MirVal initRes = ssa.emitConst(null, PrimitiveKind.NONE, ln);
        MirVal exitRes = ssa.newVal(PrimitiveKind.NONE, "$forOut");
        BlockBuilder bbExit = ssa.newBlock(List.of(exitRes), false, false);

        MirVal isNonEmpty = ssa.newVal(PrimitiveKind.BOOL, null);
        HirExpr.BinaryOp cmpOp = cr.inclusive() ? HirExpr.BinaryOp.LTE : HirExpr.BinaryOp.LT;
        ssa.emit(
                new MirInst.Binary(
                        isNonEmpty,
                        cmpOp,
                        startVal,
                        endVal,
                        null,
                        null,
                        false,
                        ln,
                        MirEffect.PURE_NOTHROW));
        BlockBuilder bbNonEmpty = ssa.newBlock(List.of(), false, false);
        ssa.terminateBrIf(isNonEmpty, bbNonEmpty, List.of(), bbExit, List.of(initRes), ln);
        ssa.sealBlock(bbNonEmpty);

        ssa.cur = bbNonEmpty;
        MirVal lastVal;
        if (hasConstStep && constStep == 1L) {
            if (cr.inclusive()) {
                lastVal = endVal;
            } else {
                MirVal one = ssa.emitConst(isLong ? (Object) 1L : Integer.valueOf(1), loopKind, ln);
                lastVal = ssa.newVal(loopKind, "$last");
                ssa.emit(
                        new MirInst.Binary(
                                lastVal,
                                HirExpr.BinaryOp.SUB,
                                endVal,
                                one,
                                null,
                                null,
                                false,
                                ln,
                                MirEffect.PURE_NOTHROW));
            }
        } else {
            MirVal stepArg =
                    stepVal != null
                            ? stepVal
                            : ssa.emitConst(
                                    isLong ? (Object) constStep : Integer.valueOf((int) constStep),
                                    loopKind,
                                    ln);
            MirVal inclVal = ssa.emitConst(cr.inclusive(), PrimitiveKind.BOOL, ln);
            if (isLong) {
                lastVal = ssa.newVal(PrimitiveKind.LONG, "$last");
                ssa.emit(
                        new MirInst.CallJava(
                                lastVal,
                                null,
                                null,
                                ln,
                                true,
                                RANGE_LAST_LONG,
                                List.of(startVal, endVal, inclVal, stepArg),
                                false,
                                false,
                                ln,
                                MirEffect.PURE_NOTHROW));
            } else if (rk == PrimitiveKind.CHAR) {
                MirVal sChar = ssa.convertVal(startVal, PrimitiveKind.CHAR, ln);
                MirVal eChar = ssa.convertVal(endVal, PrimitiveKind.CHAR, ln);
                MirVal lastChar = ssa.newVal(PrimitiveKind.CHAR, "$last");
                ssa.emit(
                        new MirInst.CallJava(
                                lastChar,
                                null,
                                null,
                                ln,
                                true,
                                RANGE_LAST_CHAR,
                                List.of(sChar, eChar, inclVal, stepArg),
                                false,
                                false,
                                ln,
                                MirEffect.PURE_NOTHROW));
                lastVal = ssa.convertVal(lastChar, PrimitiveKind.INT, ln);
            } else {
                lastVal = ssa.newVal(PrimitiveKind.INT, "$last");
                ssa.emit(
                        new MirInst.CallJava(
                                lastVal,
                                null,
                                null,
                                ln,
                                true,
                                RANGE_LAST_INT,
                                List.of(startVal, endVal, inclVal, stepArg),
                                false,
                                false,
                                ln,
                                MirEffect.PURE_NOTHROW));
            }
        }

        MirVal firstIdx = cr.descending() ? lastVal : startVal;
        MirVal stopVal = cr.descending() ? startVal : lastVal;

        MirVal headRes = ssa.newVal(PrimitiveKind.NONE, "$forRes");
        MirVal headIdx = ssa.newVal(loopKind, "$idx");
        BlockBuilder bbHead = ssa.newBlock(List.of(headRes, headIdx), false, false);
        MirVal stepRes = ssa.newVal(PrimitiveKind.NONE, "$stepRes");
        BlockBuilder bbStep = ssa.newBlock(List.of(stepRes), false, false);
        BlockBuilder bbNext = ssa.newBlock(List.of(), false, false);

        ssa.terminateBr(bbHead, List.of(initRes, firstIdx), ln);

        ssa.cur = bbHead;
        MirVal rawElem = rk == loopKind ? headIdx : ssa.convertVal(headIdx, rk, ln);
        MirVal bodyRes = lowerForBody(f, rawElem, bbExit, bbStep, headRes, ln);
        if (!ssa.cur.isTerminated()) {
            ssa.terminateBr(bbStep, List.of(bodyRes), ln);
        }
        ssa.sealBlock(bbStep);

        ssa.cur = bbStep;
        if (bbStep.incomingEdges.isEmpty()) {
            ssa.terminateFixed(new MirTerminator.Unreachable(ln));
        } else {
            MirVal done = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.Binary(
                            done,
                            HirExpr.BinaryOp.EQ,
                            headIdx,
                            stopVal,
                            null,
                            null,
                            false,
                            ln,
                            MirEffect.PURE_NOTHROW));
            ssa.terminateBrIf(done, bbExit, List.of(stepRes), bbNext, List.of(), ln);
            ssa.sealBlock(bbNext);

            ssa.cur = bbNext;
            MirVal delta =
                    stepVal != null
                            ? stepVal
                            : ssa.emitConst(
                                    isLong ? (Object) constStep : Integer.valueOf((int) constStep),
                                    loopKind,
                                    ln);
            MirVal nextIdx = ssa.newVal(loopKind, null);
            ssa.emit(
                    new MirInst.Binary(
                            nextIdx,
                            cr.descending() ? HirExpr.BinaryOp.SUB : HirExpr.BinaryOp.ADD,
                            headIdx,
                            delta,
                            null,
                            null,
                            false,
                            ln,
                            MirEffect.PURE_NOTHROW));
            ssa.terminateBr(bbHead, List.of(stepRes, nextIdx), ln);
        }
        ssa.sealBlock(bbHead);

        ssa.sealBlock(bbExit);
        ssa.cur = bbExit;
        if (bbExit.incomingEdges.isEmpty()) {
            ssa.terminateFixed(new MirTerminator.Unreachable(ln));
        }
        return exitRes;
    }

    private MirVal lowerForBody(
            HirExpr.For f,
            MirVal rawElem,
            BlockBuilder breakBlock,
            BlockBuilder continueBlock,
            MirVal currentRes,
            int ln) {
        LoweringTargets.ForLoopTarget target = f.target();
        MirVal elem = rawElem;
        if (target != null && target.elemCheck() != null) {
            elem = ssa.applyCheckedType(elem, target.elemCheck(), ln);
        }
        if (f.elemSamTarget() != null) {
            MirVal boxed = ssa.convertVal(elem, PrimitiveKind.NONE, ln);
            MirVal adapted = ssa.newVal(PrimitiveKind.NONE, null);
            ssa.emit(new MirInst.CheckCast(adapted, boxed, null, f.elemSamTarget(), null, ln));
            elem = adapted;
        }
        elem = ssa.applyWideningRank(elem, f.meta().wideningRank(), ln);
        PrimitiveKind varKind = target != null ? target.elemKind() : PrimitiveKind.NONE;
        elem = ssa.convertVal(elem, varKind, ln);

        for (HirVar v : f.bodyDeclaredVars()) {
            if (ssa.pinnedVarIds.contains(v.id())) {
                ssa.emit(new MirInst.SlotDecl(v, ln));
            }
        }
        if (f.loopVar() != null) {
            ssa.defVar(f.loopVar(), elem, ln);
        }

        loopStack.push(
                new LoopContext(breakBlock, continueBlock, true, currentRes, loopStack.size() + 1));
        MirVal res;
        try {
            res = parent.lowerStatements(f.body(), ln);
            if (!ssa.cur.isTerminated()) {
                res = ssa.applyWideningRank(res, f.bodyWideningRank(), ln);
                res = ssa.convertVal(res, PrimitiveKind.NONE, ln);
            }
        } finally {
            loopStack.pop();
        }
        return res;
    }

    MirVal lowerBreak(HirExpr.Break b) {
        int ln = b.meta().line();
        LoopContext loop = loopStack.peek();
        MirVal val;
        if (b.value() != null) {
            val = parent.lowerAndWiden(b.value());
            if (ssa.cur.isTerminated()) return val;
            val = ssa.convertVal(val, PrimitiveKind.NONE, ln);
        } else {
            val = ssa.emitConst(null, PrimitiveKind.NONE, ln);
        }
        emitUnwindFinallies(loop.loopDepth, ln);
        if (!ssa.cur.isTerminated()) {
            ssa.terminateBr(loop.breakBlock, List.of(val), ln);
        }
        return ssa.emitConst(null, PrimitiveKind.NONE, ln);
    }

    MirVal lowerContinue(HirExpr.Continue c) {
        int ln = c.meta().line();
        LoopContext loop = loopStack.peek();
        emitUnwindFinallies(loop.loopDepth, ln);
        if (!ssa.cur.isTerminated()) {
            if (loop.passResultOnContinue) {
                ssa.terminateBr(loop.continueBlock, List.of(loop.currentResultVal), ln);
            } else {
                ssa.terminateBr(loop.continueBlock, List.of(), ln);
            }
        }
        return ssa.emitConst(null, PrimitiveKind.NONE, ln);
    }

    MirVal lowerReturn(HirExpr.Return r) {
        int ln = r.meta().line();
        MirVal val = parent.lowerAndWiden(r.value());
        if (ssa.cur.isTerminated()) return val;
        val = ssa.applyWideningRank(val, r.meta().wideningRank(), ln);
        val = ssa.convertVal(val, parent.fn.returnKind(), ln);
        emitUnwindFinallies(0, ln);
        if (!ssa.cur.isTerminated()) {
            ssa.terminateFixed(new MirTerminator.Ret(val, parent.fn.returnKind(), -1, ln));
        }
        return ssa.emitConst(null, PrimitiveKind.NONE, ln);
    }

    MirVal lowerThrow(HirExpr.Throw th) {
        int ln = th.meta().line();
        MirVal val = parent.lowerBoxed(th.value());
        if (ssa.cur.isTerminated()) return val;
        ssa.terminateFixed(new MirTerminator.Throw(val, th.isJavaThrowable(), ln));
        return ssa.emitConst(null, PrimitiveKind.NONE, ln);
    }

    // ------------------------------------------------------------------ try / catch / finally
    // (BIP028)

    private void emitUnwindFinallies(int minLoopDepth, int ln) {
        if (finallyStack.isEmpty()) {
            return;
        }
        List<FinallyContext> popped = new ArrayList<>();
        List<List<MirHandler>> poppedHandlers = new ArrayList<>();
        while (!finallyStack.isEmpty() && finallyStack.peek().loopDepthAtEntry >= minLoopDepth) {
            FinallyContext fc = finallyStack.pop();
            popped.add(fc);
            poppedHandlers.add(ssa.handlerStack.pop());
            if (fc.hasNonEmptyFinally && !ssa.cur.isTerminated()) {
                // Transition to a fresh block with the outer handler list so this inline
                // finally
                // is NOT protected by the try's own catch/finally handlers!
                BlockBuilder bbFin = ssa.newBlock(List.of(), false, false);
                ssa.terminateBr(bbFin, List.of(), ln);
                ssa.sealBlock(bbFin);
                ssa.cur = bbFin;
                lowerTryFinallyBlock(fc.tryNode, ln);
            }
        }
        // Restore stacks for any subsequent unreachable/sibling lowering in the same lexical
        // scope.
        for (int i = popped.size() - 1; i >= 0; i--) {
            ssa.handlerStack.push(poppedHandlers.get(i));
            finallyStack.push(popped.get(i));
        }
    }

    private void lowerScopedBody(
            List<HirExpr> stmts,
            List<HirVar> declVars,
            boolean opensNs,
            String[] declNames,
            int ln) {
        if (!declVars.isEmpty()) {
            for (HirVar v : declVars) {
                if (ssa.pinnedVarIds.contains(v.id())) {
                    ssa.emit(new MirInst.SlotDecl(v, ln));
                }
            }
            parent.lowerStatements(stmts, ln);
        } else if (opensNs) {
            MirVal prevNs = ssa.newVal(PrimitiveKind.NONE, "$prevNs");
            ssa.emit(new MirInst.ScopeEnter(prevNs, declNames, ln));
            parent.lowerStatements(stmts, ln);
            if (!ssa.cur.isTerminated()) {
                ssa.emit(new MirInst.ScopeExit(prevNs, ln));
            }
        } else {
            parent.lowerStatements(stmts, ln);
        }
    }

    private MirVal lowerScopedValueBody(
            List<HirExpr> stmts,
            List<HirVar> declVars,
            boolean opensNs,
            String[] declNames,
            int ln) {
        if (!declVars.isEmpty()) {
            for (HirVar v : declVars) {
                if (ssa.pinnedVarIds.contains(v.id())) {
                    ssa.emit(new MirInst.SlotDecl(v, ln));
                }
            }
            return parent.lowerStatements(stmts, ln);
        }
        if (opensNs) {
            MirVal prevNs = ssa.newVal(PrimitiveKind.NONE, "$prevNs");
            ssa.emit(new MirInst.ScopeEnter(prevNs, declNames, ln));
            MirVal res = parent.lowerStatements(stmts, ln);
            if (!ssa.cur.isTerminated()) {
                ssa.emit(new MirInst.ScopeExit(prevNs, ln));
            }
            return res;
        }
        return parent.lowerStatements(stmts, ln);
    }

    private void lowerTryFinallyBlock(HirExpr.Try t, int ln) {
        if (!t.hasFinally() || t.finallyBody().isEmpty()) {
            return;
        }
        lowerScopedBody(
                t.finallyBody(),
                t.finallyDeclaredVars(),
                t.finallyOpensNamespaceScope(),
                t.finallyDeclaredNames(),
                ln);
    }

    MirVal lowerTry(HirExpr.Try t) {
        int ln = t.meta().line();
        PrimitiveKind targetKind = t.meta().kind();
        boolean hasNonEmptyFinally = t.hasFinally() && !t.finallyBody().isEmpty();
        List<HirExpr.HirCatch> catches = t.catches();
        List<MirHandler> outerHandlers = ssa.handlerStack.peek();
        BlockBuilder preTryBlock = ssa.cur;

        // 1. Pre-create merge block, handler-entry blocks, catch body blocks, and abnormal
        // finally blocks.
        MirVal mergeParam = ssa.newVal(targetKind, "$tryRes");
        BlockBuilder bbMerge =
                ssa.newBlockWithHandlers(List.of(mergeParam), outerHandlers, false, false);

        boolean needsThrownDispatcher = false;
        for (HirExpr.HirCatch cc : catches) {
            LoweringTargets.CatchTarget ct = cc.target();
            if (ct != null
                    && (ct.catchesBabble()
                            || ct.catchesNonErrorAny()
                            || ct.catchesJavaThrowable()
                            || ct.skipsThrownException())) {
                needsThrownDispatcher = true;
                break;
            }
        }

        // Abnormal finally blocks (run under outerHandlers).
        MirVal finAbnEntryExn =
                hasNonEmptyFinally ? ssa.newVal(PrimitiveKind.NONE, "$abnExn") : null;
        BlockBuilder bbFinallyAbnEntry =
                hasNonEmptyFinally
                        ? ssa.newBlockWithHandlers(
                                List.of(finAbnEntryExn), outerHandlers, true, preTryBlock, true)
                        : null;
        MirVal finAbnBodyExn =
                hasNonEmptyFinally ? ssa.newVal(PrimitiveKind.NONE, "$primaryExn") : null;
        BlockBuilder bbFinallyAbnBody =
                hasNonEmptyFinally
                        ? ssa.newBlockWithHandlers(
                                List.of(finAbnBodyExn), outerHandlers, false, false)
                        : null;

        // Handlers protecting catch bodies (finallyAbnormal -> outerHandlers).
        List<MirHandler> catchBodyHandlers = outerHandlers;
        if (hasNonEmptyFinally) {
            List<MirHandler> list = new ArrayList<>(1 + outerHandlers.size());
            list.add(new MirHandler(null, bbFinallyAbnEntry.id, ln));
            list.addAll(outerHandlers);
            catchBodyHandlers = List.copyOf(list);
        }

        // Per-catch body blocks and JVM catch trampoline blocks.
        BlockBuilder[] catchBodies = new BlockBuilder[catches.size()];
        MirVal[] catchBoundParams = new MirVal[catches.size()];
        BlockBuilder[] jvmCatchEntries = new BlockBuilder[catches.size()];
        MirVal[] jvmCatchParams = new MirVal[catches.size()];
        for (int i = 0; i < catches.size(); i++) {
            HirExpr.HirCatch cc = catches.get(i);
            MirVal bp =
                    ssa.newVal(PrimitiveKind.NONE, cc.var() != null ? cc.var().name() : "$caught");
            catchBoundParams[i] = bp;
            catchBodies[i] = ssa.newBlockWithHandlers(List.of(bp), catchBodyHandlers, false, false);
            LoweringTargets.CatchTarget ct = cc.target();
            if (ct != null && !ct.jvmCatchTypes().isEmpty()) {
                MirVal ep = ssa.newVal(PrimitiveKind.NONE, "$jexn");
                jvmCatchParams[i] = ep;
                jvmCatchEntries[i] =
                        ssa.newBlockWithHandlers(
                                List.of(ep), outerHandlers, true, preTryBlock, true);
            }
        }

        // ThrownException dispatcher handler-entry block (runs under outerHandlers).
        MirVal teParam = needsThrownDispatcher ? ssa.newVal(PrimitiveKind.NONE, "$thrownEx") : null;
        BlockBuilder bbThrownEntry =
                needsThrownDispatcher
                        ? ssa.newBlockWithHandlers(
                                List.of(teParam), outerHandlers, true, preTryBlock, true)
                        : null;

        // Build tryBody handler list in exact JVM exception_table priority order:
        //   1. ThrownException dispatcher (if needed)
        //   2. Java catch handlers in source order
        //   3. Abnormal finally catch-all (if hasNonEmptyFinally)
        //   4. Outer enclosing handlers
        List<MirHandler> tryHandlers = new ArrayList<>();
        if (needsThrownDispatcher) {
            tryHandlers.add(new MirHandler(THROWN_EXCEPTION_JVM, bbThrownEntry.id, ln));
        }
        for (int i = 0; i < catches.size(); i++) {
            LoweringTargets.CatchTarget ct = catches.get(i).target();
            if (ct != null && jvmCatchEntries[i] != null) {
                for (String jvmType : ct.jvmCatchTypes()) {
                    tryHandlers.add(
                            new MirHandler(jvmType, jvmCatchEntries[i].id, catches.get(i).line()));
                }
            }
        }
        if (hasNonEmptyFinally) {
            tryHandlers.add(new MirHandler(null, bbFinallyAbnEntry.id, ln));
        }
        tryHandlers.addAll(outerHandlers);
        List<MirHandler> tryHandlerList = List.copyOf(tryHandlers);

        // 2. Branch from current block into tryStart block (which carries tryHandlerList).
        BlockBuilder bbTryStart = ssa.newBlockWithHandlers(List.of(), tryHandlerList, false, false);
        ssa.terminateBr(bbTryStart, List.of(), ln);
        ssa.sealBlock(bbTryStart);

        // Lower tryBody inside tryHandlerList + finallyStack.
        ssa.handlerStack.push(tryHandlerList);
        finallyStack.push(new FinallyContext(t, loopStack.size(), hasNonEmptyFinally));
        MirVal tryVal;
        try {
            ssa.cur = bbTryStart;
            tryVal =
                    lowerScopedValueBody(
                            t.tryBody(),
                            t.tryDeclaredVars(),
                            t.tryOpensNamespaceScope(),
                            t.tryDeclaredNames(),
                            ln);
            if (!ssa.cur.isTerminated()) {
                tryVal = ssa.applyWideningRank(tryVal, t.tryWideningRank(), ln);
                tryVal = ssa.convertVal(tryVal, targetKind, ln);
            }
        } finally {
            finallyStack.pop();
            ssa.handlerStack.pop();
        }
        if (!ssa.cur.isTerminated()) {
            if (hasNonEmptyFinally) {
                // Normal exit from try runs finally outside tryHandlerList (under
                // outerHandlers).
                BlockBuilder bbTryNormalFin =
                        ssa.newBlockWithHandlers(List.of(), outerHandlers, false, false);
                ssa.terminateBr(bbTryNormalFin, List.of(), ln);
                ssa.sealBlock(bbTryNormalFin);
                ssa.cur = bbTryNormalFin;
                lowerTryFinallyBlock(t, ln);
            }
            if (!ssa.cur.isTerminated()) {
                ssa.terminateBr(bbMerge, List.of(tryVal), ln);
            }
        }

        // 3. Emit JVM catch trampolines -> catchBodies[i].
        for (int i = 0; i < catches.size(); i++) {
            if (jvmCatchEntries[i] != null) {
                ssa.cur = jvmCatchEntries[i];
                ssa.terminateBr(catchBodies[i], List.of(jvmCatchParams[i]), catches.get(i).line());
            }
        }

        // 4. Emit ThrownException dispatcher -> catchBodies[i] or finallyAbnormalBody /
        // rethrow.
        if (needsThrownDispatcher) {
            ssa.cur = bbThrownEntry;
            MirVal unwrapped = null;
            boolean guaranteedHandled = false;
            for (int i = 0; i < catches.size(); i++) {
                LoweringTargets.CatchTarget ct = catches.get(i).target();
                int cln = catches.get(i).line();
                if (ct == null) {
                    continue;
                }
                if (ct.catchesJavaThrowable()) {
                    ssa.terminateBr(catchBodies[i], List.of(teParam), cln);
                    guaranteedHandled = true;
                    break;
                }
                if (ct.catchesNonErrorAny()) {
                    if (unwrapped == null) {
                        unwrapped = ssa.newVal(PrimitiveKind.NONE, "$thrownVal");
                        ssa.emit(new MirInst.UnwrapThrown(unwrapped, teParam, cln));
                    }
                    ssa.terminateBr(catchBodies[i], List.of(unwrapped), cln);
                    guaranteedHandled = true;
                    break;
                }
                if (ct.catchesBabble()) {
                    if (unwrapped == null) {
                        unwrapped = ssa.newVal(PrimitiveKind.NONE, "$thrownVal");
                        ssa.emit(new MirInst.UnwrapThrown(unwrapped, teParam, cln));
                    }
                    MirVal matched = ssa.newVal(PrimitiveKind.BOOL, null);
                    ssa.emit(new MirInst.CatchMatch(matched, unwrapped, ct, cln));
                    BlockBuilder bbNext =
                            ssa.newBlockWithHandlers(List.of(), outerHandlers, false, false);
                    ssa.terminateBrIf(
                            matched, catchBodies[i], List.of(unwrapped), bbNext, List.of(), cln);
                    ssa.sealBlock(bbNext);
                    ssa.cur = bbNext;
                }
            }
            if (!guaranteedHandled && !ssa.cur.isTerminated()) {
                if (hasNonEmptyFinally) {
                    ssa.terminateBr(bbFinallyAbnBody, List.of(teParam), ln);
                } else {
                    ssa.terminateFixed(new MirTerminator.Throw(teParam, true, ln));
                }
            }
        }

        // 5. Lower catch bodies (each protected by catchBodyHandlers).
        for (int i = 0; i < catches.size(); i++) {
            HirExpr.HirCatch cc = catches.get(i);
            int cln = cc.line();
            ssa.sealBlock(catchBodies[i]);
            ssa.cur = catchBodies[i];
            if (catchBodies[i].incomingEdges.isEmpty()) {
                ssa.terminateFixed(new MirTerminator.Unreachable(cln));
                continue;
            }
            if (hasNonEmptyFinally) {
                ssa.handlerStack.push(catchBodyHandlers);
                finallyStack.push(new FinallyContext(t, loopStack.size(), true));
            }
            MirVal catchVal;
            try {
                if (!cc.declaredVars().isEmpty()) {
                    for (HirVar v : cc.declaredVars()) {
                        if (ssa.pinnedVarIds.contains(v.id())) {
                            ssa.emit(new MirInst.SlotDecl(v, cln));
                        }
                    }
                    if (cc.var() != null) {
                        ssa.defVar(cc.var(), catchBoundParams[i], cln);
                    }
                    catchVal = parent.lowerStatements(cc.body(), cln);
                } else if (cc.opensNamespaceScope()) {
                    MirVal prevNs = ssa.newVal(PrimitiveKind.NONE, "$prevNs");
                    ssa.emit(new MirInst.ScopeEnter(prevNs, cc.declaredNames(), cln));
                    catchVal = parent.lowerStatements(cc.body(), cln);
                    if (!ssa.cur.isTerminated()) {
                        ssa.emit(new MirInst.ScopeExit(prevNs, cln));
                    }
                } else {
                    if (cc.var() != null) {
                        ssa.defVar(cc.var(), catchBoundParams[i], cln);
                    }
                    catchVal = parent.lowerStatements(cc.body(), cln);
                }
                if (!ssa.cur.isTerminated()) {
                    catchVal = ssa.applyWideningRank(catchVal, cc.wideningRank(), cln);
                    catchVal = ssa.convertVal(catchVal, targetKind, cln);
                }
            } finally {
                if (hasNonEmptyFinally) {
                    finallyStack.pop();
                    ssa.handlerStack.pop();
                }
            }
            if (!ssa.cur.isTerminated()) {
                if (hasNonEmptyFinally) {
                    BlockBuilder bbCatchFin =
                            ssa.newBlockWithHandlers(List.of(), outerHandlers, false, false);
                    ssa.terminateBr(bbCatchFin, List.of(), cln);
                    ssa.sealBlock(bbCatchFin);
                    ssa.cur = bbCatchFin;
                    lowerTryFinallyBlock(t, cln);
                }
                if (!ssa.cur.isTerminated()) {
                    ssa.terminateBr(bbMerge, List.of(catchVal), cln);
                }
            }
        }

        // 6. Emit abnormal finally path (with automatic AddSuppressed when !finallyNothrow).
        if (hasNonEmptyFinally) {
            ssa.cur = bbFinallyAbnEntry;
            ssa.terminateBr(bbFinallyAbnBody, List.of(finAbnEntryExn), ln);
            ssa.sealBlock(bbFinallyAbnBody);
            ssa.cur = bbFinallyAbnBody;
            if (t.finallyNothrow()) {
                lowerTryFinallyBlock(t, ln);
                if (!ssa.cur.isTerminated()) {
                    ssa.terminateFixed(new MirTerminator.Throw(finAbnBodyExn, true, ln));
                }
            } else {
                MirVal secondaryExn = ssa.newVal(PrimitiveKind.NONE, "$secondaryExn");
                BlockBuilder bbSuppress =
                        ssa.newBlockWithHandlers(
                                List.of(secondaryExn), outerHandlers, true, bbFinallyAbnBody, true);
                BlockBuilder bbRethrow =
                        ssa.newBlockWithHandlers(List.of(), outerHandlers, false, false);

                List<MirHandler> finHandlers = new ArrayList<>(1 + outerHandlers.size());
                finHandlers.add(new MirHandler("java/lang/Throwable", bbSuppress.id, ln));
                finHandlers.addAll(outerHandlers);
                BlockBuilder bbFinRun =
                        ssa.newBlockWithHandlers(List.of(), finHandlers, false, false);
                ssa.terminateBr(bbFinRun, List.of(), ln);
                ssa.sealBlock(bbFinRun);

                ssa.handlerStack.push(List.copyOf(finHandlers));
                try {
                    ssa.cur = bbFinRun;
                    lowerTryFinallyBlock(t, ln);
                    if (!ssa.cur.isTerminated()) {
                        ssa.terminateBr(bbRethrow, List.of(), ln);
                    }
                } finally {
                    ssa.handlerStack.pop();
                }

                ssa.cur = bbSuppress;
                ssa.emit(new MirInst.AddSuppressed(finAbnBodyExn, secondaryExn, ln));
                ssa.terminateBr(bbRethrow, List.of(), ln);

                ssa.sealBlock(bbRethrow);
                ssa.cur = bbRethrow;
                ssa.terminateFixed(new MirTerminator.Throw(finAbnBodyExn, true, ln));
            }
        }

        // 7. Continue after try merge.
        ssa.sealBlock(bbMerge);
        ssa.cur = bbMerge;
        if (bbMerge.incomingEdges.isEmpty()) {
            ssa.terminateFixed(new MirTerminator.Unreachable(ln));
        }
        return mergeParam;
    }

    // ------------------------------------------------------------------ narrowing anchor
    // detection

    NarrowHint extractNarrowHint(HirExpr cond) {
        HirExpr unwrapped = unwrapTransparent(cond);
        boolean negated = false;
        while (unwrapped instanceof HirExpr.Unary u && u.op() == HirExpr.UnaryOp.NOT) {
            negated = !negated;
            unwrapped = unwrapTransparent(u.operand());
        }
        if (unwrapped instanceof HirExpr.Binary b
                && (b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ)) {
            HirExpr l = unwrapTransparent(b.left());
            HirExpr r = unwrapTransparent(b.right());
            HirVar v = null;
            if (l instanceof HirExpr.VarRead vr
                    && r instanceof HirExpr.Const c
                    && c.value() == null) {
                v = vr.var();
            } else if (r instanceof HirExpr.VarRead vr
                    && l instanceof HirExpr.Const c
                    && c.value() == null) {
                v = vr.var();
            }
            if (v != null
                    && !ssa.pinnedVarIds.contains(v.id())
                    && !v.primitiveKind().isPrimitive()) {
                boolean nonNullOnTrue = (b.op() == HirExpr.BinaryOp.NEQ) ^ negated;
                return new NarrowHint(v, MirInst.NarrowKind.NON_NULL, "non-null", nonNullOnTrue);
            }
        }
        if (unwrapped instanceof HirExpr.Is isExpr) {
            HirExpr l = unwrapTransparent(isExpr.left());
            if (l instanceof HirExpr.VarRead vr && !ssa.pinnedVarIds.contains(vr.var().id())) {
                String desc =
                        isExpr.builtin() != null
                                ? isExpr.builtin()
                                : (isExpr.descriptor() != null ? isExpr.descriptor() : "type");
                return new NarrowHint(vr.var(), MirInst.NarrowKind.IS_TYPE, desc, !negated);
            }
        }
        return null;
    }

    void emitNarrowAnchor(NarrowHint hint, int ln) {
        MirVal base = ssa.readSsaVar(hint.var, ssa.cur, ln);
        MirVal narrowed = ssa.newVal(base.kind(), hint.var.name());
        ssa.emit(new MirInst.Narrow(narrowed, hint.kind, base, hint.desc, ln));
        ssa.propagateAlias(base, narrowed);
        ssa.cur.currentDef.put(hint.var.id(), narrowed);
    }

    private static HirExpr unwrapTransparent(HirExpr expr) {
        while (true) {
            if (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
                expr = b.statements().get(0);
            } else if (expr instanceof HirExpr.Narrow n) {
                expr = n.operand();
            } else {
                return expr;
            }
        }
    }
}
