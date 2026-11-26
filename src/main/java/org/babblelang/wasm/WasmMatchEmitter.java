package org.babblelang.wasm;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.hir.HirVar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits structured WebAssembly 3.0 bytecode for {@link HirExpr.Match} ({@code BIP038-D7}).
 *
 * <p>Uses dense {@code br_table} dispatch for contiguous integer/{@code char} constant runs,
 * {@code str_hash} + {@code str_eq} dispatch for {@code String} constant runs, and structured
 * conditional chains for general patterns (values, type tests, bindings, OR-patterns, relational,
 * zero-allocation {@code BIP031} ranges, and {@code in} membership).
 */
final class WasmMatchEmitter {
    private static final int MIN_SWITCH_ARMS = 3;
    private static final int MAX_BR_TABLE_SPAN = 256;

    private final WasmProgramLinker linker;
    private final WasmFuncEmitter fe;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmRuntimeOpsGen ops;
    private final WasmArrayOpsGen arrOps;
    private final WasmCollectionOpsGen cols;
    private final WasmRangeOpsGen ranges;
    private final WasmObjectSystem objects;

    WasmMatchEmitter(WasmProgramLinker linker, WasmFuncEmitter fe) {
        this.linker = linker;
        this.fe = fe;
        this.ts = linker.types();
        this.rt = linker.runtime();
        this.ops = linker.ops();
        this.arrOps = linker.arrays();
        this.cols = linker.collections();
        this.ranges = linker.ranges();
        this.objects = linker.objects();
    }

    void emitMatch(HirExpr.Match m) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int line = m.meta().line();

        // 1. Evaluate scrutinee once and store in local (and scrutineeVar slot if declared).
        HirVar sv = m.scrutineeVar();
        PrimitiveKind sk = sv != null ? sv.primitiveKind() : m.scrutinee().meta().kind();
        int scrLocal;
        if (sv != null) {
            fe.allocateLocalVar(sv);
            WasmFuncEmitter.VarSlot slot = ctx.vars.get(sv.id());
            fe.emitAs(m.scrutinee(), sk);
            fe.storeVarSlotAndKeep(slot, true);
            if (!slot.isCell() && !slot.isCapturedField() && slot.kind() == sk) {
                c.drop();
                scrLocal = slot.localIdx();
            } else {
                scrLocal = ctx.newLocal("$mScr", ts.valTypeFor(sk));
                c.localSet(scrLocal);
            }
        } else {
            scrLocal = ctx.newLocal("$mScr", ts.valTypeFor(sk));
            fe.emitAs(m.scrutinee(), sk);
            c.localSet(scrLocal);
        }

        // Pre-allocate locals for all arm bound variables and else binding.
        for (HirExpr.MatchArm arm : m.arms()) {
            for (HirVar bv : arm.boundVars()) {
                fe.allocateLocalVar(bv);
            }
        }
        if (m.elseArm() != null && m.elseArm().var() != null) {
            fe.allocateLocalVar(m.elseArm().var());
        }

        // 2. Structured outer result block $match_done
        PrimitiveKind resKind = m.meta().kind();
        int doneDepth = ctx.pushBlock("match_done");
        c.block(ts.valTypeFor(resKind));

        List<HirExpr.MatchArm> arms = m.arms();
        int i = 0;
        while (i < arms.size()) {
            IntRun intRun = tryCollectIntRun(arms, i, sk);
            if (intRun != null) {
                emitIntBrTableRun(arms, i, intRun, scrLocal, resKind, doneDepth);
                i += intRun.armCount;
                continue;
            }
            StringRun strRun = tryCollectStringRun(arms, i, sk);
            if (strRun != null) {
                emitStringHashRun(arms, i, strRun, scrLocal, resKind, doneDepth);
                i += strRun.armCount;
                continue;
            }
            emitSequentialArm(arms.get(i), scrLocal, sk, resKind, doneDepth);
            i++;
        }

        // 3. Else clause or defensive runtime fallback
        if (m.elseArm() != null) {
            HirExpr.MatchElse el = m.elseArm();
            if (el.var() != null) {
                bindVar(el.var(), scrLocal, sk, el.line());
            }
            emitArmBody(el.body(), el.bodyWideningRank(), resKind, el.line());
        } else {
            rt.emitStringLit(c, "No matching case : ");
            c.localGet(scrLocal);
            if (sk.isPrimitive()) {
                c.call(rt.boxFuncFor(sk));
            }
            c.call(rt.fnFormat).call(rt.fnStrConcat)
                    .i32Const(line).call(rt.fnThrowMsg).unreachable();
        }

        ctx.popBlock();
        c.end(); // end $match_done
        ctx.stackKind = resKind;
    }

    private void emitArmBody(HirExpr body, int wideningRank, PrimitiveKind resKind, int line) {
        fe.emitAndWiden(body);
        fe.widenStackRank(wideningRank, line);
        fe.convertStack(fe.ctx().stackKind, resKind, line);
    }

    private void emitSequentialArm(HirExpr.MatchArm arm, int scrLocal, PrimitiveKind sk,
                                   PrimitiveKind resKind, int doneDepth) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        emitPatternMatch(arm.pattern(), scrLocal, sk);
        c.if_();
        ctx.pushBlock("match_arm");
        if (arm.guard() != null) {
            fe.emitTruthValue(arm.guard());
            c.if_();
            ctx.pushBlock("match_guard");
            emitArmBody(arm.body(), arm.bodyWideningRank(), resKind, arm.line());
            c.br(ctx.relDepth(doneDepth));
            ctx.popBlock();
            c.end();
        } else {
            emitArmBody(arm.body(), arm.bodyWideningRank(), resKind, arm.line());
            c.br(ctx.relDepth(doneDepth));
        }
        ctx.popBlock();
        c.end();
    }

    // ---------------------------------------------------------------- dense integer/char br_table

    private record IntRun(int armCount, int min, int max, Map<Integer, Integer> keyToArmOffset) {
    }

    private static IntRun tryCollectIntRun(List<HirExpr.MatchArm> arms, int start, PrimitiveKind sk) {
        if (sk != PrimitiveKind.BYTE && sk != PrimitiveKind.SHORT
                && sk != PrimitiveKind.INT && sk != PrimitiveKind.CHAR) {
            return null;
        }
        Map<Integer, Integer> keyToOffset = new LinkedHashMap<>();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int count = 0;
        for (int idx = start; idx < arms.size(); idx++) {
            HirExpr.MatchArm arm = arms.get(idx);
            if (arm.guard() != null || !arm.boundVars().isEmpty()) {
                break;
            }
            List<Integer> keys = extractIntKeys(arm.pattern(), sk == PrimitiveKind.CHAR);
            if (keys == null || keys.isEmpty()) {
                break;
            }
            boolean dup = false;
            for (int k : keys) {
                if (keyToOffset.containsKey(k)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                break;
            }
            int nextMin = min;
            int nextMax = max;
            for (int k : keys) {
                if (k < nextMin) nextMin = k;
                if (k > nextMax) nextMax = k;
            }
            long span = (long) nextMax - (long) nextMin + 1L;
            if (span > MAX_BR_TABLE_SPAN) {
                break;
            }
            for (int k : keys) {
                keyToOffset.put(k, count);
            }
            min = nextMin;
            max = nextMax;
            count++;
        }
        if (count < MIN_SWITCH_ARMS) {
            return null;
        }
        long span = (long) max - (long) min + 1L;
        if (span <= 0 || span > MAX_BR_TABLE_SPAN || span > (long) keyToOffset.size() * 2L + 8L) {
            return null;
        }
        return new IntRun(count, min, max, keyToOffset);
    }

    private static List<Integer> extractIntKeys(HirPattern pat, boolean charScrutinee) {
        List<Integer> out = new ArrayList<>();
        if (!collectIntKeys(pat, charScrutinee, out)) {
            return null;
        }
        return out;
    }

    private static boolean collectIntKeys(HirPattern pat, boolean charScrutinee, List<Integer> out) {
        if (pat instanceof HirPattern.Const c) {
            if (c.isNull() || c.isEnum()) return false;
            Object v = c.value();
            if (charScrutinee) {
                if (v instanceof Character ch) {
                    out.add((int) ch);
                    return true;
                }
                return false;
            }
            if (v instanceof Byte b) {
                out.add((int) b);
                return true;
            }
            if (v instanceof Short s) {
                out.add((int) s);
                return true;
            }
            if (v instanceof Integer iv) {
                out.add(iv);
                return true;
            }
            return false;
        }
        if (pat instanceof HirPattern.Or o) {
            for (HirPattern alt : o.alternatives()) {
                if (!collectIntKeys(alt, charScrutinee, out)) return false;
            }
            return true;
        }
        return false;
    }

    private void emitIntBrTableRun(List<HirExpr.MatchArm> arms, int start, IntRun run,
                                   int scrLocal, PrimitiveKind resKind, int doneDepth) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int k = run.armCount;
        int fallDepth = ctx.pushBlock("brt_fall");
        c.block();
        int[] armDepths = new int[k];
        for (int j = k - 1; j >= 0; j--) {
            armDepths[j] = ctx.pushBlock("brt_arm_" + j);
            c.block();
        }
        int fallRel = ctx.relDepth(fallDepth);
        // Bounds check prevents i32 wraparound on (scr - min)
        c.localGet(scrLocal).i32Const(run.min).op(WasmInstr.SimpleOp.I32_LT_S).brIf(fallRel);
        c.localGet(scrLocal).i32Const(run.max).op(WasmInstr.SimpleOp.I32_GT_S).brIf(fallRel);

        int span = run.max - run.min + 1;
        List<Integer> labels = new ArrayList<>(span);
        for (int off = 0; off < span; off++) {
            Integer armOff = run.keyToArmOffset.get(run.min + off);
            labels.add(armOff != null ? ctx.relDepth(armDepths[armOff]) : fallRel);
        }
        c.localGet(scrLocal);
        if (run.min != 0) {
            c.i32Const(run.min).op(WasmInstr.SimpleOp.I32_SUB);
        }
        c.brTable(labels, fallRel);

        for (int j = 0; j < k; j++) {
            ctx.popBlock();
            c.end(); // end $brt_arm_j
            HirExpr.MatchArm arm = arms.get(start + j);
            emitArmBody(arm.body(), arm.bodyWideningRank(), resKind, arm.line());
            c.br(ctx.relDepth(doneDepth));
        }
        ctx.popBlock();
        c.end(); // end $brt_fall
    }

    // ---------------------------------------------------------------- String hash-switch run

    private record StringRun(int armCount, Map<Integer, List<StringBucketEntry>> buckets) {
    }

    private record StringBucketEntry(String literal, int armOffset) {
    }

    private static StringRun tryCollectStringRun(List<HirExpr.MatchArm> arms, int start, PrimitiveKind sk) {
        if (sk != PrimitiveKind.NONE) {
            return null;
        }
        Map<Integer, List<StringBucketEntry>> buckets = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int count = 0;
        for (int idx = start; idx < arms.size(); idx++) {
            HirExpr.MatchArm arm = arms.get(idx);
            if (arm.guard() != null || !arm.boundVars().isEmpty()) {
                break;
            }
            List<String> strs = extractStringKeys(arm.pattern());
            if (strs == null || strs.isEmpty()) {
                break;
            }
            boolean dup = false;
            for (String s : strs) {
                if (seen.contains(s)) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                break;
            }
            for (String s : strs) {
                seen.add(s);
                buckets.computeIfAbsent(s.hashCode(), h -> new ArrayList<>())
                        .add(new StringBucketEntry(s, count));
            }
            count++;
        }
        if (count < MIN_SWITCH_ARMS) {
            return null;
        }
        return new StringRun(count, buckets);
    }

    private static List<String> extractStringKeys(HirPattern pat) {
        List<String> out = new ArrayList<>();
        if (!collectStringKeys(pat, out)) {
            return null;
        }
        return out;
    }

    private static boolean collectStringKeys(HirPattern pat, List<String> out) {
        if (pat instanceof HirPattern.Const c && !c.isEnum() && c.value() instanceof String s) {
            out.add(s);
            return true;
        }
        if (pat instanceof HirPattern.Or o) {
            for (HirPattern alt : o.alternatives()) {
                if (!collectStringKeys(alt, out)) return false;
            }
            return true;
        }
        return false;
    }

    private void emitStringHashRun(List<HirExpr.MatchArm> arms, int start, StringRun run,
                                   int scrLocal, PrimitiveKind resKind, int doneDepth) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int strLocal = ctx.newLocal("$mStr", WasmValType.refNull(ts.stringType));
        int hashLocal = ctx.newLocal("$mHash", WasmValType.I32);

        c.localGet(scrLocal).refTest(ts.stringType).if_();
        ctx.pushBlock("mstr_is");
        c.localGet(scrLocal).refCast(ts.stringType).localTee(strLocal)
                .call(rt.fnStrHash).localSet(hashLocal);

        for (Map.Entry<Integer, List<StringBucketEntry>> bucket : run.buckets.entrySet()) {
            c.localGet(hashLocal).i32Const(bucket.getKey()).op(WasmInstr.SimpleOp.I32_EQ).if_();
            ctx.pushBlock("mstr_bkt");
            for (StringBucketEntry entry : bucket.getValue()) {
                c.localGet(strLocal).refAsNonNull();
                rt.emitStringLit(c, entry.literal);
                c.call(rt.fnStrEq).if_();
                ctx.pushBlock("mstr_hit");
                HirExpr.MatchArm arm = arms.get(start + entry.armOffset);
                emitArmBody(arm.body(), arm.bodyWideningRank(), resKind, arm.line());
                c.br(ctx.relDepth(doneDepth));
                ctx.popBlock();
                c.end();
            }
            ctx.popBlock();
            c.end();
        }
        ctx.popBlock();
        c.end();
    }

    // ---------------------------------------------------------------- general pattern matching

    /** Emits code testing {@code pat} against {@code valLocal} (of {@code valKind}), leaving i32 0/1 on stack. */
    private void emitPatternMatch(HirPattern pat, int valLocal, PrimitiveKind valKind) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        switch (pat) {
            case HirPattern.Wildcard w -> c.i32Const(1);
            case HirPattern.Const cst -> emitConstPattern(cst, valLocal, valKind);
            case HirPattern.ValueExpr ve -> emitValueExprPattern(ve, valLocal, valKind);
            case HirPattern.TypeTest tt -> emitTypeTestPattern(tt, valLocal, valKind);
            case HirPattern.Bind b -> {
                if (b.inner() != null) {
                    emitPatternMatch(b.inner(), valLocal, valKind);
                    c.if_(WasmValType.I32);
                    ctx.pushBlock("bind_ok");
                    bindVar(b.var(), valLocal, valKind, b.line());
                    c.i32Const(1);
                    c.else_();
                    c.i32Const(0);
                    ctx.popBlock();
                    c.end();
                } else {
                    bindVar(b.var(), valLocal, valKind, b.line());
                    c.i32Const(1);
                }
            }
            case HirPattern.Or o -> emitOrPattern(o.alternatives(), 0, valLocal, valKind);
            case HirPattern.Relational rel -> emitRelationalPattern(rel, valLocal, valKind);
            case HirPattern.RangeTest rangePat -> emitRangeTestPattern(rangePat, valLocal, valKind);
            case HirPattern.Membership mem -> emitMembershipPattern(mem, valLocal, valKind);
        }
    }

    private void bindVar(HirVar v, int valLocal, PrimitiveKind valKind, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        fe.allocateLocalVar(v);
        WasmFuncEmitter.VarSlot slot = ctx.vars.get(v.id());
        ctx.code.localGet(valLocal);
        fe.convertRawValue(valKind, slot.kind(), line);
        fe.storeVarSlotAndKeep(slot, true);
        ctx.code.drop();
    }

    private void emitOrPattern(List<HirPattern> alts, int idx, int valLocal, PrimitiveKind valKind) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        if (idx >= alts.size()) {
            c.i32Const(0);
            return;
        }
        emitPatternMatch(alts.get(idx), valLocal, valKind);
        if (idx == alts.size() - 1) {
            return;
        }
        c.if_(WasmValType.I32);
        ctx.pushBlock("or_alt");
        c.i32Const(1);
        c.else_();
        emitOrPattern(alts, idx + 1, valLocal, valKind);
        ctx.popBlock();
        c.end();
    }

    private void emitConstPattern(HirPattern.Const cst, int valLocal, PrimitiveKind valKind) {
        WasmCodeBuilder c = fe.ctx().code;
        if (cst.isNull()) {
            if (valKind.isPrimitive()) {
                c.i32Const(0);
            } else {
                c.localGet(valLocal).refIsNull();
            }
            return;
        }
        Object v = cst.value();
        if (v instanceof Boolean bv) {
            if (valKind == PrimitiveKind.BOOL) {
                c.localGet(valLocal).i32Const(bv ? 1 : 0).op(WasmInstr.SimpleOp.I32_EQ);
                return;
            }
        } else if (v instanceof Character ch) {
            if (valKind == PrimitiveKind.CHAR) {
                c.localGet(valLocal).i32Const(ch).op(WasmInstr.SimpleOp.I32_EQ);
                return;
            }
        } else if (v instanceof Number num && valKind.numericRank() >= 0) {
            PrimitiveKind ck = numericKindOf(num);
            PrimitiveKind common = valKind.numericRank() >= ck.numericRank() ? valKind : ck;
            if (common.numericRank() <= 2) {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.INT, cst.line());
                c.i32Const(num.intValue()).op(WasmInstr.SimpleOp.I32_EQ);
            } else if (common == PrimitiveKind.LONG) {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.LONG, cst.line());
                c.i64Const(num.longValue()).op(WasmInstr.SimpleOp.I64_EQ);
            } else if (common == PrimitiveKind.FLOAT) {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.FLOAT, cst.line());
                c.f32Const(num.floatValue()).call(rt.fnF32Compare).i32Const(0).op(WasmInstr.SimpleOp.I32_EQ);
            } else {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.DOUBLE, cst.line());
                c.f64Const(num.doubleValue()).call(rt.fnF64Compare).i32Const(0).op(WasmInstr.SimpleOp.I32_EQ);
            }
            return;
        } else if (v instanceof String s) {
            if (valKind.isPrimitive()) {
                c.i32Const(0);
                return;
            }
            c.localGet(valLocal).refTest(ts.stringType).if_(WasmValType.I32)
                    .localGet(valLocal).refCast(ts.stringType);
            rt.emitStringLit(c, s);
            c.call(rt.fnStrEq)
                    .else_().i32Const(0).end();
            return;
        }
        // Fallback: boxed equality
        pushBoxed(valLocal, valKind);
        fe.emitConstValue(v, cst.line());
        fe.convertStack(fe.ctx().stackKind, PrimitiveKind.NONE, cst.line());
        c.call(rt.fnEquals);
    }

    private void emitValueExprPattern(HirPattern.ValueExpr ve, int valLocal, PrimitiveKind valKind) {
        emitEqualityCheck(valLocal, valKind, ve.expr(), ve.line(), false);
    }

    private void emitEqualityCheck(int valLocal, PrimitiveKind valKind, HirExpr rhs, int line, boolean negate) {
        WasmCodeBuilder c = fe.ctx().code;
        PrimitiveKind rk = rhs.meta().kind();
        if ((valKind == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL)
                || (valKind == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR)) {
            c.localGet(valLocal);
            fe.emitAs(rhs, rk);
            c.op(negate ? WasmInstr.SimpleOp.I32_NE : WasmInstr.SimpleOp.I32_EQ);
            return;
        }
        if (valKind.numericRank() >= 0 && rk.numericRank() >= 0) {
            PrimitiveKind common = valKind.numericRank() >= rk.numericRank() ? valKind : rk;
            if (common.numericRank() <= 2) {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.INT, line);
                fe.emitAs(rhs, PrimitiveKind.INT);
                c.op(negate ? WasmInstr.SimpleOp.I32_NE : WasmInstr.SimpleOp.I32_EQ);
            } else if (common == PrimitiveKind.LONG) {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.LONG, line);
                fe.emitAs(rhs, PrimitiveKind.LONG);
                c.op(negate ? WasmInstr.SimpleOp.I64_NE : WasmInstr.SimpleOp.I64_EQ);
            } else if (common == PrimitiveKind.FLOAT) {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.FLOAT, line);
                fe.emitAs(rhs, PrimitiveKind.FLOAT);
                c.call(rt.fnF32Compare).i32Const(0).op(negate ? WasmInstr.SimpleOp.I32_NE : WasmInstr.SimpleOp.I32_EQ);
            } else {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.DOUBLE, line);
                fe.emitAs(rhs, PrimitiveKind.DOUBLE);
                c.call(rt.fnF64Compare).i32Const(0).op(negate ? WasmInstr.SimpleOp.I32_NE : WasmInstr.SimpleOp.I32_EQ);
            }
            return;
        }
        pushBoxed(valLocal, valKind);
        fe.emitBoxed(rhs);
        c.call(rt.fnEquals);
        if (negate) {
            c.op(WasmInstr.SimpleOp.I32_EQZ);
        }
    }

    private void emitTypeTestPattern(HirPattern.TypeTest tt, int valLocal, PrimitiveKind valKind) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int boxLocal = toBoxedLocal(valLocal, valKind);
        if (tt.descriptor() != null && !tt.descriptor().isEmpty()) {
            fe.emitDescCheck(c, boxLocal, WasmTypeDesc.parse(tt.descriptor()), false);
            return;
        }
        if (objects.emitNominalIsCheck(c, boxLocal, tt.target(), tt.intersectionTargets())) {
            return;
        }
        if (tt.builtin() != null) {
            int bid = WasmRuntimeOpsGen.builtinId(tt.builtin());
            if (bid >= 0) {
                c.localGet(boxLocal).i32Const(bid).call(ops.fnIsBuiltin);
                return;
            }
        }
        c.i32Const(1);
    }

    private void emitRelationalPattern(HirPattern.Relational rel, int valLocal, PrimitiveKind valKind) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        HirExpr.BinaryOp op = rel.op();
        if (op == HirExpr.BinaryOp.EQ || op == HirExpr.BinaryOp.NEQ) {
            emitEqualityCheck(valLocal, valKind, rel.rhs(), rel.line(), op == HirExpr.BinaryOp.NEQ);
            return;
        }
        PrimitiveKind rk = rel.rhs().meta().kind();
        if (valKind == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR) {
            c.localGet(valLocal);
            fe.emitAs(rel.rhs(), PrimitiveKind.CHAR);
            c.op(i32CmpOp(op));
            return;
        }
        if (valKind.numericRank() >= 0 && rk.numericRank() >= 0) {
            PrimitiveKind common = valKind.numericRank() >= rk.numericRank() ? valKind : rk;
            if (common.numericRank() <= 2) {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.INT, rel.line());
                fe.emitAs(rel.rhs(), PrimitiveKind.INT);
                c.op(i32CmpOp(op));
            } else if (common == PrimitiveKind.LONG) {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.LONG, rel.line());
                fe.emitAs(rel.rhs(), PrimitiveKind.LONG);
                c.op(i64CmpOp(op));
            } else if (common == PrimitiveKind.FLOAT) {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.FLOAT, rel.line());
                fe.emitAs(rel.rhs(), PrimitiveKind.FLOAT);
                c.call(rt.fnF32Compare).i32Const(0).op(i32CmpOp(op));
            } else {
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.DOUBLE, rel.line());
                fe.emitAs(rel.rhs(), PrimitiveKind.DOUBLE);
                c.call(rt.fnF64Compare).i32Const(0).op(i32CmpOp(op));
            }
            return;
        }
        // Boxed null-safe ordering (BIP038 §4.2: null scrutinee or null rhs never matches)
        int rhsLocal = ctx.newLocal("$relRhs", ts.anyRef());
        fe.emitBoxed(rel.rhs());
        c.localSet(rhsLocal);
        int valBox = toBoxedLocal(valLocal, valKind);
        c.localGet(valBox).refIsNull().localGet(rhsLocal).refIsNull().op(WasmInstr.SimpleOp.I32_OR)
                .if_(WasmValType.I32)
                .i32Const(0)
                .else_()
                .localGet(valBox).localGet(rhsLocal);
        rt.emitStringLit(c, "match");
        rt.emitStringLit(c, "pattern");
        c.i32Const(rel.line()).call(rt.fnCompare).i32Const(0).op(i32CmpOp(op))
                .end();
    }

    private void emitRangeTestPattern(HirPattern.RangeTest rp, int valLocal, PrimitiveKind valKind) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int line = rp.line();
        PrimitiveKind ek = rp.elemKind();
        if (ek == PrimitiveKind.BYTE || ek == PrimitiveKind.SHORT
                || ek == PrimitiveKind.INT || ek == PrimitiveKind.CHAR) {
            int lo = ctx.newLocal("$rLo", WasmValType.I32);
            int hi = ctx.newLocal("$rHi", WasmValType.I32);
            int step = -1;
            fe.emitAs(rp.lo(), ek == PrimitiveKind.CHAR ? PrimitiveKind.CHAR : PrimitiveKind.INT);
            c.localSet(lo);
            fe.emitAs(rp.hi(), ek == PrimitiveKind.CHAR ? PrimitiveKind.CHAR : PrimitiveKind.INT);
            c.localSet(hi);
            if (rp.step() != null) {
                step = ctx.newLocal("$rStep", WasmValType.I32);
                fe.emitAs(rp.step(), PrimitiveKind.INT);
                c.i32Const(line).call(ranges.fnValidateStepInt).localSet(step);
            }
            if ((ek == PrimitiveKind.CHAR && valKind == PrimitiveKind.CHAR)
                    || (ek != PrimitiveKind.CHAR && valKind.numericRank() >= 0 && valKind.numericRank() <= 2)) {
                emitIntRangeCheck(valLocal, lo, hi, step, rp.inclusive());
                return;
            }
            // Boxed or wider numeric scrutinee
            int valBox = toBoxedLocal(valLocal, valKind);
            if (ek == PrimitiveKind.CHAR) {
                int ch = ctx.newLocal("$rCh", WasmValType.I32);
                c.localGet(valBox).refTest(ts.boxCharType).if_(WasmValType.I32);
                c.localGet(valBox).refCast(ts.boxCharType).structGet(ts.boxCharType, 0).localSet(ch);
                emitIntRangeCheck(ch, lo, hi, step, rp.inclusive());
                c.else_().i32Const(0).end();
            } else {
                int vL = ctx.newLocal("$rValL", WasmValType.I64);
                int loL = ctx.newLocal("$rLoL", WasmValType.I64);
                int hiL = ctx.newLocal("$rHiL", WasmValType.I64);
                int stL = -1;
                c.localGet(lo).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S).localSet(loL);
                c.localGet(hi).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S).localSet(hiL);
                if (step >= 0) {
                    stL = ctx.newLocal("$rStL", WasmValType.I64);
                    c.localGet(step).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S).localSet(stL);
                }
                int rnk = ctx.newLocal("$rRnk", WasmValType.I32);
                c.localGet(valBox).call(rt.fnNumRank).localTee(rnk)
                        .i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S)
                        .localGet(rnk).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S)
                        .op(WasmInstr.SimpleOp.I32_AND)
                        .if_(WasmValType.I32);
                c.localGet(valBox).call(rt.fnNumAsLong).localSet(vL);
                emitLongRangeCheck(vL, loL, hiL, stL, rp.inclusive());
                c.else_().i32Const(0).end();
            }
            return;
        }
        if (ek == PrimitiveKind.LONG) {
            int lo = ctx.newLocal("$rLoL", WasmValType.I64);
            int hi = ctx.newLocal("$rHiL", WasmValType.I64);
            int step = -1;
            fe.emitAs(rp.lo(), PrimitiveKind.LONG);
            c.localSet(lo);
            fe.emitAs(rp.hi(), PrimitiveKind.LONG);
            c.localSet(hi);
            if (rp.step() != null) {
                step = ctx.newLocal("$rStepL", WasmValType.I64);
                fe.emitAs(rp.step(), PrimitiveKind.LONG);
                c.i32Const(line).call(ranges.fnValidateStepLong).localSet(step);
            }
            if (valKind.numericRank() >= 0 && valKind.numericRank() <= 3) {
                int vL = ctx.newLocal("$rValL", WasmValType.I64);
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.LONG, line);
                c.localSet(vL);
                emitLongRangeCheck(vL, lo, hi, step, rp.inclusive());
                return;
            }
            int valBox = toBoxedLocal(valLocal, valKind);
            int rnk = ctx.newLocal("$rRnk", WasmValType.I32);
            int vL = ctx.newLocal("$rValL", WasmValType.I64);
            c.localGet(valBox).call(rt.fnNumRank).localTee(rnk)
                    .i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S)
                    .localGet(rnk).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S)
                    .op(WasmInstr.SimpleOp.I32_AND)
                    .if_(WasmValType.I32);
            c.localGet(valBox).call(rt.fnNumAsLong).localSet(vL);
            emitLongRangeCheck(vL, lo, hi, step, rp.inclusive());
            c.else_().i32Const(0).end();
            return;
        }
        if (ek == PrimitiveKind.FLOAT || ek == PrimitiveKind.DOUBLE) {
            int lo = ctx.newLocal("$rLoD", WasmValType.F64);
            int hi = ctx.newLocal("$rHiD", WasmValType.F64);
            fe.emitAs(rp.lo(), PrimitiveKind.DOUBLE);
            c.localSet(lo);
            fe.emitAs(rp.hi(), PrimitiveKind.DOUBLE);
            c.localSet(hi);
            if (valKind.numericRank() >= 0) {
                int vD = ctx.newLocal("$rValD", WasmValType.F64);
                c.localGet(valLocal);
                fe.convertRawValue(valKind, PrimitiveKind.DOUBLE, line);
                c.localSet(vD);
                c.localGet(vD).localGet(lo).op(WasmInstr.SimpleOp.F64_GE)
                        .localGet(vD).localGet(hi).op(rp.inclusive() ? WasmInstr.SimpleOp.F64_LE : WasmInstr.SimpleOp.F64_LT)
                        .op(WasmInstr.SimpleOp.I32_AND);
                return;
            }
            int valBox = toBoxedLocal(valLocal, valKind);
            int vD = ctx.newLocal("$rValD", WasmValType.F64);
            c.localGet(valBox).call(rt.fnNumRank).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S)
                    .if_(WasmValType.I32);
            c.localGet(valBox).call(rt.fnNumAsDouble).localSet(vD);
            c.localGet(vD).localGet(lo).op(WasmInstr.SimpleOp.F64_GE)
                    .localGet(vD).localGet(hi).op(rp.inclusive() ? WasmInstr.SimpleOp.F64_LE : WasmInstr.SimpleOp.F64_LT)
                    .op(WasmInstr.SimpleOp.I32_AND);
            c.else_().i32Const(0).end();
            return;
        }
        // String range
        int loS = ctx.newLocal("$rLoS", ts.anyRef());
        int hiS = ctx.newLocal("$rHiS", ts.anyRef());
        fe.emitBoxed(rp.lo());
        c.localTee(loS).refIsNull().if_();
        rt.emitStringLit(c, "Range bound cannot be null");
        c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        fe.emitBoxed(rp.hi());
        c.localTee(hiS).refIsNull().if_();
        rt.emitStringLit(c, "Range bound cannot be null");
        c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        int valBox = toBoxedLocal(valLocal, valKind);
        c.localGet(valBox).refTest(ts.stringType).if_(WasmValType.I32)
                .localGet(valBox).refCast(ts.stringType)
                .localGet(loS).refCast(ts.stringType)
                .call(rt.fnStrCmp).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S)
                .if_(WasmValType.I32)
                .localGet(valBox).refCast(ts.stringType)
                .localGet(hiS).refCast(ts.stringType)
                .call(rt.fnStrCmp).i32Const(0)
                .op(rp.inclusive() ? WasmInstr.SimpleOp.I32_LE_S : WasmInstr.SimpleOp.I32_LT_S)
                .else_().i32Const(0).end()
                .else_().i32Const(0).end();
    }

    private void emitIntRangeCheck(int vLocal, int loLocal, int hiLocal, int stepLocal, boolean inclusive) {
        WasmCodeBuilder c = fe.ctx().code;
        c.localGet(vLocal).localGet(loLocal).op(WasmInstr.SimpleOp.I32_GE_S)
                .localGet(vLocal).localGet(hiLocal)
                .op(inclusive ? WasmInstr.SimpleOp.I32_LE_S : WasmInstr.SimpleOp.I32_LT_S)
                .op(WasmInstr.SimpleOp.I32_AND);
        if (stepLocal >= 0) {
            c.if_(WasmValType.I32)
                    .localGet(vLocal).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                    .localGet(loLocal).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                    .op(WasmInstr.SimpleOp.I64_SUB)
                    .localGet(stepLocal).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                    .op(WasmInstr.SimpleOp.I64_REM_S)
                    .i64Const(0L).op(WasmInstr.SimpleOp.I64_EQ)
                    .else_().i32Const(0).end();
        }
    }

    private void emitLongRangeCheck(int vLocal, int loLocal, int hiLocal, int stepLocal, boolean inclusive) {
        WasmCodeBuilder c = fe.ctx().code;
        c.localGet(vLocal).localGet(loLocal).op(WasmInstr.SimpleOp.I64_GE_S)
                .localGet(vLocal).localGet(hiLocal)
                .op(inclusive ? WasmInstr.SimpleOp.I64_LE_S : WasmInstr.SimpleOp.I64_LT_S)
                .op(WasmInstr.SimpleOp.I32_AND);
        if (stepLocal >= 0) {
            c.if_(WasmValType.I32)
                    .localGet(vLocal).localGet(loLocal).op(WasmInstr.SimpleOp.I64_SUB)
                    .localGet(stepLocal).op(WasmInstr.SimpleOp.I64_REM_U)
                    .i64Const(0L).op(WasmInstr.SimpleOp.I64_EQ)
                    .else_().i32Const(0).end();
        }
    }

    private void emitMembershipPattern(HirPattern.Membership mem, int valLocal, PrimitiveKind valKind) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int contLocal = ctx.newLocal("$mCont", ts.anyRef());
        fe.emitBoxed(mem.container());
        c.localSet(contLocal);
        int valBox = toBoxedLocal(valLocal, valKind);

        c.localGet(contLocal).refIsNull().if_(WasmValType.I32)
                .i32Const(0)
                .else_();
        switch (mem.kind()) {
            case RANGE -> c.localGet(contLocal).refCast(ts.rangeBaseType)
                    .localGet(valBox).call(ranges.fnRangeContainsBoxed);
            case COLLECTION -> c.localGet(contLocal).refTest(ts.listType).if_(WasmValType.I32)
                    .localGet(contLocal).refCast(ts.listType).localGet(valBox).call(cols.fnListContains)
                    .else_()
                    .localGet(contLocal).refTest(ts.setType).if_(WasmValType.I32)
                    .localGet(contLocal).refCast(ts.setType).localGet(valBox).call(cols.fnSetContains)
                    .else_().i32Const(0).end()
                    .end();
            case MAP -> c.localGet(contLocal).refCast(ts.mapType)
                    .localGet(valBox).call(cols.fnMapContainsKey);
            case ARRAY -> c.localGet(contLocal).refCast(ts.arrayBaseType)
                    .localGet(valBox).call(arrOps.fnArrayContains);
            case DYNAMIC -> {
                c.localGet(contLocal).refTest(ts.rangeBaseType).if_(WasmValType.I32)
                        .localGet(contLocal).refCast(ts.rangeBaseType).localGet(valBox).call(ranges.fnRangeContainsBoxed)
                        .else_()
                        .localGet(contLocal).refTest(ts.listType).if_(WasmValType.I32)
                        .localGet(contLocal).refCast(ts.listType).localGet(valBox).call(cols.fnListContains)
                        .else_()
                        .localGet(contLocal).refTest(ts.setType).if_(WasmValType.I32)
                        .localGet(contLocal).refCast(ts.setType).localGet(valBox).call(cols.fnSetContains)
                        .else_()
                        .localGet(contLocal).refTest(ts.mapType).if_(WasmValType.I32)
                        .localGet(contLocal).refCast(ts.mapType).localGet(valBox).call(cols.fnMapContainsKey)
                        .else_()
                        .localGet(contLocal).refTest(ts.arrayBaseType).if_(WasmValType.I32)
                        .localGet(contLocal).refCast(ts.arrayBaseType).localGet(valBox).call(arrOps.fnArrayContains)
                        .else_();
                rt.emitStringLit(c, "Cannot test membership with 'in' on ");
                c.localGet(contLocal).call(ops.fnDescribeType).call(rt.fnStrConcat)
                        .i32Const(mem.line()).call(rt.fnThrowMsg).unreachable()
                        .end().end().end().end().end();
            }
        }
        c.end();
    }

    // ---------------------------------------------------------------- helpers

    private void pushBoxed(int valLocal, PrimitiveKind valKind) {
        WasmCodeBuilder c = fe.ctx().code;
        c.localGet(valLocal);
        if (valKind.isPrimitive()) {
            c.call(rt.boxFuncFor(valKind));
        }
    }

    private int toBoxedLocal(int valLocal, PrimitiveKind valKind) {
        if (!valKind.isPrimitive()) {
            return valLocal;
        }
        int box = fe.ctx().newLocal("$mBox", ts.anyRef());
        fe.ctx().code.localGet(valLocal).call(rt.boxFuncFor(valKind)).localSet(box);
        return box;
    }

    private static PrimitiveKind numericKindOf(Number n) {
        if (n instanceof Byte) return PrimitiveKind.BYTE;
        if (n instanceof Short) return PrimitiveKind.SHORT;
        if (n instanceof Integer) return PrimitiveKind.INT;
        if (n instanceof Long) return PrimitiveKind.LONG;
        if (n instanceof Float) return PrimitiveKind.FLOAT;
        return PrimitiveKind.DOUBLE;
    }

    private static WasmInstr.SimpleOp i32CmpOp(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> WasmInstr.SimpleOp.I32_LT_S;
            case LTE -> WasmInstr.SimpleOp.I32_LE_S;
            case GT -> WasmInstr.SimpleOp.I32_GT_S;
            case GTE -> WasmInstr.SimpleOp.I32_GE_S;
            default -> throw new IllegalStateException("Unexpected op : " + op);
        };
    }

    private static WasmInstr.SimpleOp i64CmpOp(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> WasmInstr.SimpleOp.I64_LT_S;
            case LTE -> WasmInstr.SimpleOp.I64_LE_S;
            case GT -> WasmInstr.SimpleOp.I64_GT_S;
            case GTE -> WasmInstr.SimpleOp.I64_GE_S;
            default -> throw new IllegalStateException("Unexpected op : " + op);
        };
    }

    private static WasmInstr.SimpleOp f32CmpOp(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> WasmInstr.SimpleOp.F32_LT;
            case LTE -> WasmInstr.SimpleOp.F32_LE;
            case GT -> WasmInstr.SimpleOp.F32_GT;
            case GTE -> WasmInstr.SimpleOp.F32_GE;
            default -> throw new IllegalStateException("Unexpected op : " + op);
        };
    }

    private static WasmInstr.SimpleOp f64CmpOp(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> WasmInstr.SimpleOp.F64_LT;
            case LTE -> WasmInstr.SimpleOp.F64_LE;
            case GT -> WasmInstr.SimpleOp.F64_GT;
            case GTE -> WasmInstr.SimpleOp.F64_GE;
            default -> throw new IllegalStateException("Unexpected op : " + op);
        };
    }
}
