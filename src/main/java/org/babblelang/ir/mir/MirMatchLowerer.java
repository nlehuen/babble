package org.babblelang.ir.mir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirLoweringRules;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.mir.MirSsaBuilder.BlockBuilder;
import org.babblelang.runtime.ArraySupport;
import org.babblelang.runtime.CoreCollections;
import org.babblelang.runtime.MatchSupport;
import org.babblelang.runtime.Members;
import org.babblelang.runtime.Operators;
import org.babblelang.runtime.Range;

/**
 * Lowers {@link HirExpr.Match} expressions (int/string/enum jump-table runs, general patterns,
 * record/sequence destructuring, and range patterns) into SSA MIR blocks.
 */
final class MirMatchLowerer {
    private static final LoweringTargets.JavaCallTarget MATCH_IN_CONTAINER =
            staticTarget(MatchSupport.class, "inContainer", Object.class, Object.class);
    private static final LoweringTargets.JavaCallTarget MATCH_RELATIONAL =
            staticTarget(MatchSupport.class, "relational", Object.class, Object.class, int.class);
    private static final LoweringTargets.JavaCallTarget MATCH_RANGE =
            staticTarget(
                    MatchSupport.class,
                    "rangeMatch",
                    Object.class,
                    Object.class,
                    Object.class,
                    boolean.class,
                    Object.class);
    private static final LoweringTargets.JavaCallTarget MATCH_NO_CASE =
            staticTarget(MatchSupport.class, "noMatchingCase", Object.class);
    private static final LoweringTargets.JavaCallTarget STRING_HASH_CODE =
            virtualTarget(String.class, "hashCode");
    private static final LoweringTargets.JavaCallTarget STRING_EQUALS =
            virtualTarget(String.class, "equals", Object.class);
    private static final LoweringTargets.JavaCallTarget ENUM_ORDINAL =
            virtualTarget(Enum.class, "ordinal");
    private static final LoweringTargets.JavaCallTarget OPERATORS_EQUAL =
            staticTarget(Operators.class, "equal", Object.class, Object.class);
    private static final LoweringTargets.JavaCallTarget RANGE_VALIDATE_INT =
            staticTarget(Range.class, "validateStep", int.class);
    private static final LoweringTargets.JavaCallTarget RANGE_VALIDATE_LONG =
            staticTarget(Range.class, "validateStep", long.class);
    private static final LoweringTargets.JavaCallTarget MATH_FLOOR_MOD_INT =
            staticTarget(Math.class, "floorMod", int.class, int.class);
    private static final LoweringTargets.JavaCallTarget MATH_FLOOR_MOD_LONG =
            staticTarget(Math.class, "floorMod", long.class, long.class);
    private static final LoweringTargets.JavaCallTarget MEMBERS_SELECT =
            staticTarget(Members.class, "select", Object.class, String.class, String.class);
    private static final LoweringTargets.JavaCallTarget LIST_SIZE =
            virtualTarget(List.class, "size");
    private static final LoweringTargets.JavaCallTarget LIST_GET =
            virtualTarget(List.class, "get", int.class);
    private static final LoweringTargets.JavaCallTarget LIST_SLICE =
            staticTarget(CoreCollections.class, "listSlice", List.class, int.class);
    private static final LoweringTargets.JavaCallTarget ARR_IS_ARRAY =
            staticTarget(ArraySupport.class, "isArray", Object.class);
    private static final LoweringTargets.JavaCallTarget ARR_SIZE =
            staticTarget(ArraySupport.class, "size", Object.class);
    private static final LoweringTargets.JavaCallTarget ARR_GET =
            staticTarget(ArraySupport.class, "get", Object.class, int.class);
    private static final LoweringTargets.JavaCallTarget ARR_SLICE =
            staticTarget(ArraySupport.class, "slice", Object.class, int.class);

    private static LoweringTargets.JavaCallTarget staticTarget(
            Class<?> owner, String name, Class<?>... params) {
        try {
            return new LoweringTargets.JavaCallTarget(
                    owner.getMethod(name, params), true, false, false);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static LoweringTargets.JavaCallTarget virtualTarget(
            Class<?> owner, String name, Class<?>... params) {
        try {
            return new LoweringTargets.JavaCallTarget(
                    owner.getMethod(name, params), false, false, false);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final MirLowerer.FunctionLowerer parent;
    private final MirSsaBuilder ssa;

    MirMatchLowerer(MirLowerer.FunctionLowerer parent, MirSsaBuilder ssa) {
        this.parent = parent;
        this.ssa = ssa;
    }

    MirVal lowerMatch(HirExpr.Match m) {
        int ln = m.meta().line();
        PrimitiveKind targetKind = m.meta().kind();
        PrimitiveKind scrutKind = m.scrutineeVar().primitiveKind();
        MirVal scrutVal = parent.lowerAs(m.scrutinee(), scrutKind);
        if (ssa.cur.isTerminated()) return scrutVal;
        ssa.writeVar(m.scrutineeVar(), scrutVal, ln);

        MirVal mergeParam = ssa.newVal(targetKind, null);
        BlockBuilder bbMerge = ssa.newBlock(List.of(mergeParam), false, false);

        List<HirExpr.MatchArm> arms = m.arms();
        int i = 0;
        while (i < arms.size()) {
            HirLoweringRules.MatchConstantRun run =
                    HirLoweringRules.detectMatchConstantRun(arms, i, scrutKind, false);
            if (run != null) {
                switch (run.kind()) {
                    case INT_OR_CHAR ->
                            emitMirIntSwitchRun(
                                    arms, i, run.endExclusive(), scrutVal, targetKind, bbMerge, ln);
                    case STRING ->
                            emitMirStringSwitchRun(
                                    arms,
                                    i,
                                    run.endExclusive(),
                                    scrutVal,
                                    false,
                                    targetKind,
                                    bbMerge,
                                    ln);
                    case ENUM ->
                            emitMirEnumSwitchRun(
                                    arms, i, run.endExclusive(), scrutVal, targetKind, bbMerge, ln);
                }
                i = run.endExclusive();
                continue;
            }
            HirExpr.MatchArm arm = arms.get(i);
            BlockBuilder bbNext = ssa.newBlock(List.of(), false, false);
            lowerMatchPattern(arm.pattern(), scrutVal, bbNext);
            if (!ssa.cur.isTerminated() && arm.guard() != null) {
                MirVal gVal = parent.lowerExpr(arm.guard());
                if (!ssa.cur.isTerminated()) {
                    BlockBuilder bbBody = ssa.newBlock(List.of(), false, false);
                    ssa.terminateBrIf(
                            gVal, bbBody, List.of(), bbNext, List.of(), arm.guard().meta().line());
                    ssa.sealBlock(bbBody);
                    ssa.cur = bbBody;
                }
            }
            if (!ssa.cur.isTerminated()) {
                lowerMatchArmBody(
                        arm.body(), arm.bodyWideningRank(), targetKind, bbMerge, arm.line());
            }
            ssa.sealBlock(bbNext);
            ssa.cur = bbNext;
            i++;
        }

        if (!ssa.cur.isTerminated()) {
            if (m.elseArm() != null) {
                HirExpr.MatchElse elseArm = m.elseArm();
                if (elseArm.var() != null) {
                    ssa.writeVar(elseArm.var(), scrutVal, elseArm.line());
                }
                lowerMatchArmBody(
                        elseArm.body(),
                        elseArm.bodyWideningRank(),
                        targetKind,
                        bbMerge,
                        elseArm.line());
            } else {
                MirVal boxed = ssa.convertVal(scrutVal, PrimitiveKind.NONE, ln);
                MirVal exn = ssa.newVal(PrimitiveKind.NONE, null);
                ssa.emit(
                        new MirInst.CallJava(
                                exn,
                                null,
                                "MatchSupport",
                                ln,
                                true,
                                MATCH_NO_CASE,
                                List.of(boxed),
                                false,
                                false,
                                ln,
                                MirEffect.PURE_NOTHROW));
                ssa.terminateFixed(new MirTerminator.Throw(exn, true, ln));
            }
        }

        ssa.sealBlock(bbMerge);
        ssa.cur = bbMerge;
        if (bbMerge.incomingEdges.isEmpty()) {
            ssa.terminateFixed(new MirTerminator.Unreachable(ln));
        }
        return mergeParam;
    }

    private void lowerMatchArmBody(
            HirExpr body,
            int wideningRank,
            PrimitiveKind targetKind,
            BlockBuilder bbMerge,
            int ln) {
        MirVal v;
        if (wideningRank >= 0) {
            v = parent.lowerExpr(body);
            if (!ssa.cur.isTerminated()) {
                v = ssa.applyWideningRank(v, wideningRank, ln);
                v = ssa.convertVal(v, targetKind, ln);
            }
        } else {
            v = parent.lowerAs(body, targetKind);
        }
        if (!ssa.cur.isTerminated()) {
            ssa.terminateBr(bbMerge, List.of(v), ln);
        }
    }

    private void emitMirIntSwitchRun(
            List<HirExpr.MatchArm> arms,
            int start,
            int end,
            MirVal scrutVal,
            PrimitiveKind targetKind,
            BlockBuilder bbMerge,
            int ln) {
        int armCount = end - start;
        BlockBuilder[] armBlocks = new BlockBuilder[armCount];
        for (int k = 0; k < armCount; k++) {
            armBlocks[k] = ssa.newBlock(List.of(), false, false);
        }
        BlockBuilder bbAfter = ssa.newBlock(List.of(), false, false);
        java.util.TreeMap<Integer, BlockBuilder> keyMap = new java.util.TreeMap<>();
        for (int k = 0; k < armCount; k++) {
            for (HirPattern.Const c :
                    HirLoweringRules.collectPureConsts(arms.get(start + k).pattern())) {
                keyMap.putIfAbsent(HirLoweringRules.intConstValue(c), armBlocks[k]);
            }
        }
        int[] keys = new int[keyMap.size()];
        BlockBuilder[] targets = new BlockBuilder[keyMap.size()];
        int idx = 0;
        for (Map.Entry<Integer, BlockBuilder> e : keyMap.entrySet()) {
            keys[idx] = e.getKey();
            targets[idx] = e.getValue();
            idx++;
        }
        MirVal keyInt = ssa.convertVal(scrutVal, PrimitiveKind.INT, ln);
        ssa.terminateSwitch(keyInt, keys, targets, bbAfter, ln);
        for (int k = 0; k < armCount; k++) {
            ssa.sealBlock(armBlocks[k]);
            ssa.cur = armBlocks[k];
            HirExpr.MatchArm arm = arms.get(start + k);
            lowerMatchArmBody(arm.body(), arm.bodyWideningRank(), targetKind, bbMerge, arm.line());
        }
        ssa.sealBlock(bbAfter);
        ssa.cur = bbAfter;
    }

    private void emitMirStringSwitchRun(
            List<HirExpr.MatchArm> arms,
            int start,
            int end,
            MirVal scrutVal,
            boolean guaranteedString,
            PrimitiveKind targetKind,
            BlockBuilder bbMerge,
            int ln) {
        int armCount = end - start;
        BlockBuilder[] armBlocks = new BlockBuilder[armCount];
        for (int k = 0; k < armCount; k++) {
            armBlocks[k] = ssa.newBlock(List.of(), false, false);
        }
        BlockBuilder bbAfter = ssa.newBlock(List.of(), false, false);

        LinkedHashMap<String, Integer> strToArm = new LinkedHashMap<>();
        int nullArm = -1;
        for (int k = 0; k < armCount; k++) {
            for (HirPattern.Const c :
                    HirLoweringRules.collectPureConsts(arms.get(start + k).pattern())) {
                if (c.isNull()) {
                    if (nullArm < 0) nullArm = k;
                } else {
                    strToArm.putIfAbsent((String) c.value(), k);
                }
            }
        }
        BlockBuilder bbNonNull = ssa.newBlock(List.of(), false, false);
        MirVal nullVal = ssa.emitConst(null, PrimitiveKind.NONE, ln);
        MirVal isNull = ssa.newVal(PrimitiveKind.BOOL, null);
        ssa.emit(
                new MirInst.Binary(
                        isNull,
                        HirExpr.BinaryOp.EQ,
                        scrutVal,
                        nullVal,
                        "match",
                        "null",
                        false,
                        ln,
                        MirEffect.PURE_NOTHROW));
        ssa.terminateBrIf(
                isNull,
                nullArm >= 0 ? armBlocks[nullArm] : bbAfter,
                List.of(),
                bbNonNull,
                List.of(),
                ln);
        ssa.sealBlock(bbNonNull);
        ssa.cur = bbNonNull;

        if (!guaranteedString) {
            BlockBuilder bbIsStr = ssa.newBlock(List.of(), false, false);
            MirVal isStr = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.IsCheck(
                            isStr, scrutVal, null, null, List.of(), "string|string|", ln));
            ssa.terminateBrIf(isStr, bbIsStr, List.of(), bbAfter, List.of(), ln);
            ssa.sealBlock(bbIsStr);
            ssa.cur = bbIsStr;
        }

        java.util.TreeMap<Integer, List<Map.Entry<String, Integer>>> buckets =
                new java.util.TreeMap<>();
        for (Map.Entry<String, Integer> e : strToArm.entrySet()) {
            buckets.computeIfAbsent(e.getKey().hashCode(), h -> new ArrayList<>()).add(e);
        }
        int bCount = buckets.size();
        int[] hashes = new int[bCount];
        BlockBuilder[] bucketBlocks = new BlockBuilder[bCount];
        List<List<Map.Entry<String, Integer>>> bucketEntries = new ArrayList<>(bCount);
        int idx = 0;
        for (Map.Entry<Integer, List<Map.Entry<String, Integer>>> b : buckets.entrySet()) {
            hashes[idx] = b.getKey();
            bucketBlocks[idx] = ssa.newBlock(List.of(), false, false);
            bucketEntries.add(b.getValue());
            idx++;
        }
        MirVal hashVal = ssa.newVal(PrimitiveKind.INT, null);
        ssa.emit(
                new MirInst.CallJava(
                        hashVal,
                        scrutVal,
                        "match",
                        ln,
                        false,
                        STRING_HASH_CODE,
                        List.of(),
                        false,
                        true,
                        ln,
                        MirEffect.PURE_NOTHROW));
        ssa.terminateSwitch(hashVal, hashes, bucketBlocks, bbAfter, ln);

        for (int b = 0; b < bCount; b++) {
            ssa.sealBlock(bucketBlocks[b]);
            ssa.cur = bucketBlocks[b];
            List<Map.Entry<String, Integer>> entries = bucketEntries.get(b);
            for (int j = 0; j < entries.size(); j++) {
                Map.Entry<String, Integer> e = entries.get(j);
                MirVal strConst = ssa.emitConst(e.getKey(), PrimitiveKind.NONE, ln);
                MirVal eq = ssa.newVal(PrimitiveKind.BOOL, null);
                ssa.emit(
                        new MirInst.CallJava(
                                eq,
                                strConst,
                                "pattern",
                                ln,
                                false,
                                STRING_EQUALS,
                                List.of(scrutVal),
                                false,
                                true,
                                ln,
                                MirEffect.PURE_NOTHROW));
                BlockBuilder bbFail =
                        (j == entries.size() - 1) ? bbAfter : ssa.newBlock(List.of(), false, false);
                ssa.terminateBrIf(eq, armBlocks[e.getValue()], List.of(), bbFail, List.of(), ln);
                if (bbFail != bbAfter) {
                    ssa.sealBlock(bbFail);
                    ssa.cur = bbFail;
                }
            }
        }

        for (int k = 0; k < armCount; k++) {
            ssa.sealBlock(armBlocks[k]);
            ssa.cur = armBlocks[k];
            HirExpr.MatchArm arm = arms.get(start + k);
            lowerMatchArmBody(arm.body(), arm.bodyWideningRank(), targetKind, bbMerge, arm.line());
        }
        ssa.sealBlock(bbAfter);
        ssa.cur = bbAfter;
    }

    private void emitMirEnumSwitchRun(
            List<HirExpr.MatchArm> arms,
            int start,
            int end,
            MirVal scrutVal,
            PrimitiveKind targetKind,
            BlockBuilder bbMerge,
            int ln) {
        int armCount = end - start;
        BlockBuilder[] armBlocks = new BlockBuilder[armCount];
        for (int k = 0; k < armCount; k++) {
            armBlocks[k] = ssa.newBlock(List.of(), false, false);
        }
        BlockBuilder bbAfter = ssa.newBlock(List.of(), false, false);

        Class<?> enumCls = null;
        java.util.TreeMap<Integer, BlockBuilder> ordMap = new java.util.TreeMap<>();
        int nullArm = -1;
        for (int k = 0; k < armCount; k++) {
            for (HirPattern.Const c :
                    HirLoweringRules.collectPureConsts(arms.get(start + k).pattern())) {
                if (c.isNull()) {
                    if (nullArm < 0) nullArm = k;
                } else if (c.isEnum() && c.value() instanceof Enum<?> ev) {
                    enumCls = c.enumClass();
                    ordMap.putIfAbsent(ev.ordinal(), armBlocks[k]);
                }
            }
        }
        BlockBuilder bbNonNull = ssa.newBlock(List.of(), false, false);
        MirVal nullVal = ssa.emitConst(null, PrimitiveKind.NONE, ln);
        MirVal isNull = ssa.newVal(PrimitiveKind.BOOL, null);
        ssa.emit(
                new MirInst.Binary(
                        isNull,
                        HirExpr.BinaryOp.EQ,
                        scrutVal,
                        nullVal,
                        "match",
                        "null",
                        false,
                        ln,
                        MirEffect.PURE_NOTHROW));
        ssa.terminateBrIf(
                isNull,
                nullArm >= 0 ? armBlocks[nullArm] : bbAfter,
                List.of(),
                bbNonNull,
                List.of(),
                ln);
        ssa.sealBlock(bbNonNull);
        ssa.cur = bbNonNull;

        BlockBuilder bbIsEnum = ssa.newBlock(List.of(), false, false);
        MirVal isEnum = ssa.newVal(PrimitiveKind.BOOL, null);
        String enumInternal = enumCls.getName().replace('.', '/');
        ssa.emit(
                new MirInst.IsCheck(
                        isEnum,
                        scrutVal,
                        null,
                        new LoweringTargets.IsTarget(null, null, enumInternal),
                        List.of(),
                        "java|" + enumCls.getName() + "|",
                        ln));
        ssa.terminateBrIf(isEnum, bbIsEnum, List.of(), bbAfter, List.of(), ln);
        ssa.sealBlock(bbIsEnum);
        ssa.cur = bbIsEnum;

        int[] keys = new int[ordMap.size()];
        BlockBuilder[] targets = new BlockBuilder[ordMap.size()];
        int idx = 0;
        for (Map.Entry<Integer, BlockBuilder> e : ordMap.entrySet()) {
            keys[idx] = e.getKey();
            targets[idx] = e.getValue();
            idx++;
        }
        MirVal ordVal = ssa.newVal(PrimitiveKind.INT, null);
        ssa.emit(
                new MirInst.CallJava(
                        ordVal,
                        scrutVal,
                        "match",
                        ln,
                        false,
                        ENUM_ORDINAL,
                        List.of(),
                        false,
                        true,
                        ln,
                        MirEffect.PURE_NOTHROW));
        ssa.terminateSwitch(ordVal, keys, targets, bbAfter, ln);

        for (int k = 0; k < armCount; k++) {
            ssa.sealBlock(armBlocks[k]);
            ssa.cur = armBlocks[k];
            HirExpr.MatchArm arm = arms.get(start + k);
            lowerMatchArmBody(arm.body(), arm.bodyWideningRank(), targetKind, bbMerge, arm.line());
        }
        ssa.sealBlock(bbAfter);
        ssa.cur = bbAfter;
    }

    private void lowerMatchPattern(HirPattern p, MirVal val, BlockBuilder bbFail) {
        if (ssa.cur.isTerminated()) return;
        int ln = p.line();
        switch (p) {
            case HirPattern.Wildcard ignored -> {}
            case HirPattern.Const pc -> {
                MirVal cond = emitMirConstMatch(pc, val, ln);
                branchOnPatternCond(cond, bbFail, ln);
            }
            case HirPattern.ValueExpr ve -> {
                MirVal rhs = parent.lowerExpr(ve.expr());
                if (ssa.cur.isTerminated()) return;
                MirVal cond = emitMirEquality(val, rhs, false, ln);
                branchOnPatternCond(cond, bbFail, ln);
            }
            case HirPattern.TypeTest tt -> {
                MirVal boxed = ssa.convertVal(val, PrimitiveKind.NONE, ln);
                PrimitiveKind outKind =
                        tt.builtin() != null ? PrimitiveKind.NONE : PrimitiveKind.BOOL;
                MirVal res = ssa.newVal(outKind, null);
                ssa.emit(
                        new MirInst.IsCheck(
                                res,
                                boxed,
                                tt.builtin(),
                                tt.target(),
                                tt.intersectionTargets(),
                                tt.descriptor(),
                                ln));
                branchOnPatternCond(res, bbFail, ln);
            }
            case HirPattern.Bind b -> {
                if (b.inner() != null) {
                    lowerMatchPattern(b.inner(), val, bbFail);
                    if (ssa.cur.isTerminated()) return;
                }
                MirVal bound = ssa.convertVal(val, b.var().primitiveKind(), ln);
                ssa.writeVar(b.var(), bound, ln);
            }
            case HirPattern.Or o -> {
                List<HirPattern> alts = o.alternatives();
                BlockBuilder bbMatched = ssa.newBlock(List.of(), false, false);
                for (int k = 0; k < alts.size(); k++) {
                    BlockBuilder altFail =
                            (k == alts.size() - 1) ? bbFail : ssa.newBlock(List.of(), false, false);
                    lowerMatchPattern(alts.get(k), val, altFail);
                    if (!ssa.cur.isTerminated()) {
                        ssa.terminateBr(bbMatched, List.of(), ln);
                    }
                    if (altFail != bbFail) {
                        ssa.sealBlock(altFail);
                        ssa.cur = altFail;
                    }
                }
                ssa.sealBlock(bbMatched);
                ssa.cur = bbMatched;
            }
            case HirPattern.Relational rel -> {
                MirVal rhs = parent.lowerExpr(rel.rhs());
                if (ssa.cur.isTerminated()) return;
                MirVal cond = emitMirRelational(val, rhs, rel.op(), ln);
                branchOnPatternCond(cond, bbFail, ln);
            }
            case HirPattern.RangeTest rt -> {
                lowerMirRangePattern(rt, val, bbFail);
            }
            case HirPattern.Membership mp -> {
                MirVal container = parent.lowerBoxed(mp.container());
                if (ssa.cur.isTerminated()) return;
                MirVal boxed = ssa.convertVal(val, PrimitiveKind.NONE, ln);
                MirVal res = ssa.newVal(PrimitiveKind.BOOL, null);
                ssa.emit(
                        new MirInst.CallJava(
                                res,
                                null,
                                "MatchSupport",
                                ln,
                                true,
                                MATCH_IN_CONTAINER,
                                List.of(boxed, container),
                                false,
                                false,
                                ln,
                                MirEffect.IMPURE_THROW));
                branchOnPatternCond(res, bbFail, ln);
            }
            case HirPattern.RecordDestructure rd -> lowerMirRecordDestructure(rd, val, bbFail);
            case HirPattern.SequenceDestructure sd -> lowerMirSequenceDestructure(sd, val, bbFail);
        }
    }

    private void lowerMirRecordDestructure(
            HirPattern.RecordDestructure rd, MirVal val, BlockBuilder bbFail) {
        int ln = rd.line();
        lowerMatchPattern(rd.typeTest(), val, bbFail);
        if (ssa.cur.isTerminated()) return;
        MirVal boxed = ssa.convertVal(val, PrimitiveKind.NONE, ln);
        if (!rd.isJavaRecord()) {
            boolean direct = rd.babbleClassId() >= 0 || rd.babbleJvmName() != null;
            for (int i = 0; i < rd.fieldPatterns().size(); i++) {
                String fn = rd.fieldNames().get(i);
                PrimitiveKind fk = rd.fieldKinds().get(i);
                MirVal fv;
                if (direct) {
                    fv = ssa.newVal(fk, fn);
                    String extJvm = rd.babbleClassId() >= 0 ? null : rd.babbleJvmName();
                    ssa.emit(
                            new MirInst.LoadField(
                                    fv,
                                    boxed,
                                    "match",
                                    rd.babbleClassId(),
                                    extJvm,
                                    rd.babbleMemberIndices()[i],
                                    fn,
                                    fk,
                                    true,
                                    true,
                                    ln,
                                    MirEffect.READ_FROZEN));
                } else {
                    MirVal fnVal = ssa.emitConst(fn, PrimitiveKind.NONE, ln);
                    fv = ssa.newVal(PrimitiveKind.NONE, fn);
                    ssa.emit(
                            new MirInst.CallJava(
                                    fv,
                                    null,
                                    "Members",
                                    ln,
                                    true,
                                    MEMBERS_SELECT,
                                    List.of(boxed, fnVal, fnVal),
                                    false,
                                    false,
                                    ln,
                                    MirEffect.READ_SHARED));
                }
                lowerMatchPattern(rd.fieldPatterns().get(i), fv, bbFail);
                if (ssa.cur.isTerminated()) return;
            }
            return;
        }
        for (int i = 0; i < rd.fieldPatterns().size(); i++) {
            java.lang.reflect.Method acc = rd.javaAccessors().get(i);
            PrimitiveKind fk = rd.fieldKinds().get(i);
            LoweringTargets.JavaCallTarget accTarget =
                    new LoweringTargets.JavaCallTarget(acc, false, false, false);
            MirVal fv = ssa.newVal(fk, rd.fieldNames().get(i));
            ssa.emit(
                    new MirInst.CallJava(
                            fv,
                            boxed,
                            rd.javaRecordClass().getSimpleName(),
                            ln,
                            false,
                            accTarget,
                            List.of(),
                            false,
                            false,
                            ln,
                            MirEffect.IMPURE_THROW));
            lowerMatchPattern(rd.fieldPatterns().get(i), fv, bbFail);
            if (ssa.cur.isTerminated()) return;
        }
    }

    private void lowerMirSequenceDestructure(
            HirPattern.SequenceDestructure sd, MirVal val, BlockBuilder bbFail) {
        int ln = sd.line();
        if (sd.typeTest() != null) {
            lowerMatchPattern(sd.typeTest(), val, bbFail);
            if (ssa.cur.isTerminated()) return;
        } else if (sd.isArray()) {
            MirVal boxed = ssa.convertVal(val, PrimitiveKind.NONE, ln);
            MirVal isArr = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.CallJava(
                            isArr,
                            null,
                            "ArraySupport",
                            ln,
                            true,
                            ARR_IS_ARRAY,
                            List.of(boxed),
                            false,
                            true,
                            ln,
                            MirEffect.PURE_NOTHROW));
            branchOnPatternCond(isArr, bbFail, ln);
        }
        MirVal boxed = ssa.convertVal(val, PrimitiveKind.NONE, ln);
        int fixed = sd.elemPatterns().size();
        MirVal fixedConst = ssa.emitConst(fixed, PrimitiveKind.INT, ln);
        if (!sd.isArray()) {
            MirVal len = ssa.newVal(PrimitiveKind.INT, "$len");
            ssa.emit(
                    new MirInst.CallJava(
                            len,
                            boxed,
                            "list",
                            ln,
                            false,
                            LIST_SIZE,
                            List.of(),
                            false,
                            false,
                            ln,
                            MirEffect.READ_SHARED));
            MirVal lenOk = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.Binary(
                            lenOk,
                            sd.hasRest() ? HirExpr.BinaryOp.GTE : HirExpr.BinaryOp.EQ,
                            len,
                            fixedConst,
                            "len",
                            "fixed",
                            false,
                            ln,
                            MirEffect.PURE_NOTHROW));
            branchOnPatternCond(lenOk, bbFail, ln);

            for (int i = 0; i < fixed; i++) {
                MirVal idx = ssa.emitConst(i, PrimitiveKind.INT, ln);
                MirVal elem = ssa.newVal(PrimitiveKind.NONE, null);
                ssa.emit(
                        new MirInst.CallJava(
                                elem,
                                boxed,
                                "list",
                                ln,
                                false,
                                LIST_GET,
                                List.of(idx),
                                false,
                                false,
                                ln,
                                MirEffect.READ_SHARED));
                lowerMatchPattern(sd.elemPatterns().get(i), elem, bbFail);
                if (ssa.cur.isTerminated()) return;
            }
            if (sd.restVar() != null) {
                MirVal tail = ssa.newVal(PrimitiveKind.NONE, sd.restVar().name());
                ssa.emit(
                        new MirInst.CallJava(
                                tail,
                                null,
                                "CoreCollections",
                                ln,
                                true,
                                LIST_SLICE,
                                List.of(boxed, fixedConst),
                                false,
                                false,
                                ln,
                                MirEffect.READ_SHARED));
                ssa.writeVar(sd.restVar(), tail, ln);
            }
            return;
        }
        Class<?> arrCls = sd.arrayClass();
        PrimitiveKind ek = sd.elemKind();
        MirVal len = ssa.newVal(PrimitiveKind.INT, "$len");
        if (arrCls != null) {
            ssa.emit(
                    new MirInst.ArrayLen(
                            len,
                            boxed,
                            "array",
                            ek,
                            arrCls,
                            arrCls.getComponentType(),
                            ln,
                            MirEffect.PURE_NOTHROW));
        } else {
            ssa.emit(
                    new MirInst.CallJava(
                            len,
                            null,
                            "ArraySupport",
                            ln,
                            true,
                            ARR_SIZE,
                            List.of(boxed),
                            false,
                            false,
                            ln,
                            MirEffect.PURE_NOTHROW));
        }
        MirVal lenOk = ssa.newVal(PrimitiveKind.BOOL, null);
        ssa.emit(
                new MirInst.Binary(
                        lenOk,
                        sd.hasRest() ? HirExpr.BinaryOp.GTE : HirExpr.BinaryOp.EQ,
                        len,
                        fixedConst,
                        "len",
                        "fixed",
                        false,
                        ln,
                        MirEffect.PURE_NOTHROW));
        branchOnPatternCond(lenOk, bbFail, ln);

        for (int i = 0; i < fixed; i++) {
            MirVal idx = ssa.emitConst(i, PrimitiveKind.INT, ln);
            MirVal elem;
            if (arrCls != null) {
                elem = ssa.newVal(ek, null);
                ssa.emit(
                        new MirInst.ArrayGet(
                                elem,
                                boxed,
                                idx,
                                "array",
                                ek,
                                arrCls,
                                arrCls.getComponentType(),
                                ln,
                                MirEffect.READ_SHARED));
            } else {
                elem = ssa.newVal(PrimitiveKind.NONE, null);
                ssa.emit(
                        new MirInst.CallJava(
                                elem,
                                null,
                                "ArraySupport",
                                ln,
                                true,
                                ARR_GET,
                                List.of(boxed, idx),
                                false,
                                false,
                                ln,
                                MirEffect.READ_SHARED));
            }
            lowerMatchPattern(sd.elemPatterns().get(i), elem, bbFail);
            if (ssa.cur.isTerminated()) return;
        }
        if (sd.restVar() != null) {
            MirVal tail = ssa.newVal(PrimitiveKind.NONE, sd.restVar().name());
            ssa.emit(
                    new MirInst.CallJava(
                            tail,
                            null,
                            "ArraySupport",
                            ln,
                            true,
                            ARR_SLICE,
                            List.of(boxed, fixedConst),
                            false,
                            false,
                            ln,
                            MirEffect.READ_SHARED));
            ssa.writeVar(sd.restVar(), tail, ln);
        }
    }

    private void branchOnPatternCond(MirVal cond, BlockBuilder bbFail, int ln) {
        BlockBuilder bbPass = ssa.newBlock(List.of(), false, false);
        ssa.terminateBrIf(cond, bbPass, List.of(), bbFail, List.of(), ln);
        ssa.sealBlock(bbPass);
        ssa.cur = bbPass;
    }

    private MirVal emitMirConstMatch(HirPattern.Const pc, MirVal val, int ln) {
        if (pc.isNull()) {
            if (val.kind().isPrimitive()) {
                return ssa.emitConst(Boolean.FALSE, PrimitiveKind.BOOL, ln);
            }
            MirVal nullVal = ssa.emitConst(null, PrimitiveKind.NONE, ln);
            MirVal res = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.Binary(
                            res,
                            HirExpr.BinaryOp.EQ,
                            val,
                            nullVal,
                            "match",
                            "null",
                            false,
                            ln,
                            MirEffect.PURE_NOTHROW));
            return res;
        }
        if (pc.isEnum()) {
            try {
                java.lang.reflect.Field field = pc.enumClass().getField(pc.enumConstantName());
                LoweringTargets.JavaFieldTarget ft =
                        new LoweringTargets.JavaFieldTarget(field, true, false);
                MirVal enumVal = ssa.newVal(PrimitiveKind.NONE, pc.enumConstantName());
                ssa.emit(
                        new MirInst.LoadJavaField(
                                enumVal,
                                null,
                                pc.enumClass().getName(),
                                true,
                                ft,
                                ln,
                                MirEffect.read(MirEffect.AliasClass.FROZEN, true, true, true)));
                return emitMirEquality(val, enumVal, false, ln);
            } catch (NoSuchFieldException e) {
                return ssa.emitConst(Boolean.FALSE, PrimitiveKind.BOOL, ln);
            }
        }
        MirVal constVal = ssa.emitConst(pc.value(), pc.kind(), ln);
        return emitMirEquality(val, constVal, false, ln);
    }

    private MirVal emitPrimitiveMatchCompare(
            MirVal left, MirVal right, HirExpr.BinaryOp op, boolean allowBool, int ln) {
        PrimitiveKind lk = left.kind();
        PrimitiveKind rk = right.kind();
        if ((allowBool && lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL)
                || (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR)) {
            MirVal res = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.Binary(
                            res,
                            op,
                            left,
                            right,
                            "match",
                            "pattern",
                            false,
                            ln,
                            MirEffect.PURE_NOTHROW));
            return res;
        }
        if (lk.isNumeric() && rk.isNumeric()) {
            PrimitiveKind common = PrimitiveKind.promoteNumeric(lk, rk);
            MirVal cl = ssa.convertVal(left, common, ln);
            MirVal cr = ssa.convertVal(right, common, ln);
            MirVal res = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.Binary(
                            res,
                            op,
                            cl,
                            cr,
                            "match",
                            "pattern",
                            false,
                            ln,
                            MirEffect.PURE_NOTHROW));
            return res;
        }
        return null;
    }

    private MirVal emitMirEquality(MirVal left, MirVal right, boolean invert, int ln) {
        HirExpr.BinaryOp op = invert ? HirExpr.BinaryOp.NEQ : HirExpr.BinaryOp.EQ;
        MirVal prim = emitPrimitiveMatchCompare(left, right, op, true, ln);
        if (prim != null) {
            return prim;
        }
        MirVal bl = ssa.convertVal(left, PrimitiveKind.NONE, ln);
        MirVal br = ssa.convertVal(right, PrimitiveKind.NONE, ln);
        MirVal eq = ssa.newVal(PrimitiveKind.BOOL, null);
        ssa.emit(
                new MirInst.CallJava(
                        eq,
                        null,
                        "Operators",
                        ln,
                        true,
                        OPERATORS_EQUAL,
                        List.of(bl, br),
                        true,
                        true,
                        ln,
                        MirEffect.PURE_NOTHROW));
        if (invert) {
            MirVal neg = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.Unary(
                            neg, HirExpr.UnaryOp.NOT, eq, "pattern", ln, MirEffect.PURE_NOTHROW));
            return neg;
        }
        return eq;
    }

    private MirVal emitMirRelational(MirVal left, MirVal right, HirExpr.BinaryOp op, int ln) {
        if (op == HirExpr.BinaryOp.EQ || op == HirExpr.BinaryOp.NEQ) {
            return emitMirEquality(left, right, op == HirExpr.BinaryOp.NEQ, ln);
        }
        MirVal prim = emitPrimitiveMatchCompare(left, right, op, false, ln);
        if (prim != null) {
            return prim;
        }
        int opCode =
                switch (op) {
                    case LT -> MatchSupport.OP_LT;
                    case LTE -> MatchSupport.OP_LTE;
                    case GT -> MatchSupport.OP_GT;
                    case GTE -> MatchSupport.OP_GTE;
                    default -> throw new IllegalStateException();
                };
        MirVal bl = ssa.convertVal(left, PrimitiveKind.NONE, ln);
        MirVal br = ssa.convertVal(right, PrimitiveKind.NONE, ln);
        MirVal opVal = ssa.emitConst(opCode, PrimitiveKind.INT, ln);
        MirVal res = ssa.newVal(PrimitiveKind.BOOL, null);
        ssa.emit(
                new MirInst.CallJava(
                        res,
                        null,
                        "MatchSupport",
                        ln,
                        true,
                        MATCH_RELATIONAL,
                        List.of(bl, br, opVal),
                        false,
                        true,
                        ln,
                        MirEffect.IMPURE_THROW));
        return res;
    }

    private void lowerMirRangePattern(HirPattern.RangeTest rt, MirVal val, BlockBuilder bbFail) {
        int ln = rt.line();
        PrimitiveKind vk = val.kind();
        PrimitiveKind ek = rt.elemKind();
        if (vk.isPrimitive() && ek.isPrimitive() && ek != PrimitiveKind.BOOL) {
            PrimitiveKind cmpKind =
                    ek == PrimitiveKind.CHAR
                            ? PrimitiveKind.CHAR
                            : PrimitiveKind.promoteNumeric(vk, ek);
            MirVal lo = parent.lowerAs(rt.lo(), cmpKind);
            if (ssa.cur.isTerminated()) return;
            MirVal hi = parent.lowerAs(rt.hi(), cmpKind);
            if (ssa.cur.isTerminated()) return;
            MirVal stepVal = null;
            PrimitiveKind stepKind =
                    cmpKind == PrimitiveKind.LONG ? PrimitiveKind.LONG : PrimitiveKind.INT;
            if (rt.step() != null) {
                MirVal rawStep = parent.lowerAs(rt.step(), stepKind);
                if (ssa.cur.isTerminated()) return;
                stepVal = ssa.newVal(stepKind, null);
                ssa.emit(
                        new MirInst.CallJava(
                                stepVal,
                                null,
                                "Range",
                                ln,
                                true,
                                stepKind == PrimitiveKind.LONG
                                        ? RANGE_VALIDATE_LONG
                                        : RANGE_VALIDATE_INT,
                                List.of(rawStep),
                                false,
                                true,
                                ln,
                                MirEffect.PURE_MAY_THROW));
            }
            MirVal v = ssa.convertVal(val, cmpKind, ln);
            MirVal geLo = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.Binary(
                            geLo,
                            HirExpr.BinaryOp.GTE,
                            v,
                            lo,
                            "match",
                            "lo",
                            false,
                            ln,
                            MirEffect.PURE_NOTHROW));
            branchOnPatternCond(geLo, bbFail, ln);

            MirVal leHi = ssa.newVal(PrimitiveKind.BOOL, null);
            ssa.emit(
                    new MirInst.Binary(
                            leHi,
                            rt.inclusive() ? HirExpr.BinaryOp.LTE : HirExpr.BinaryOp.LT,
                            v,
                            hi,
                            "match",
                            "hi",
                            false,
                            ln,
                            MirEffect.PURE_NOTHROW));
            branchOnPatternCond(leHi, bbFail, ln);

            if (stepVal != null) {
                MirVal diff = ssa.newVal(stepKind, null);
                MirVal vs = ssa.convertVal(v, stepKind, ln);
                MirVal ls = ssa.convertVal(lo, stepKind, ln);
                ssa.emit(
                        new MirInst.Binary(
                                diff,
                                HirExpr.BinaryOp.SUB,
                                vs,
                                ls,
                                "match",
                                "lo",
                                false,
                                ln,
                                MirEffect.PURE_NOTHROW));
                MirVal rem = ssa.newVal(stepKind, null);
                ssa.emit(
                        new MirInst.CallJava(
                                rem,
                                null,
                                "Math",
                                ln,
                                true,
                                stepKind == PrimitiveKind.LONG
                                        ? MATH_FLOOR_MOD_LONG
                                        : MATH_FLOOR_MOD_INT,
                                List.of(diff, stepVal),
                                false,
                                true,
                                ln,
                                MirEffect.PURE_MAY_THROW));
                MirVal zero = ssa.emitConst(stepKind == PrimitiveKind.LONG ? 0L : 0, stepKind, ln);
                MirVal onStep = ssa.newVal(PrimitiveKind.BOOL, null);
                ssa.emit(
                        new MirInst.Binary(
                                onStep,
                                HirExpr.BinaryOp.EQ,
                                rem,
                                zero,
                                "mod",
                                "0",
                                false,
                                ln,
                                MirEffect.PURE_NOTHROW));
                branchOnPatternCond(onStep, bbFail, ln);
            }
            return;
        }
        MirVal boxed = ssa.convertVal(val, PrimitiveKind.NONE, ln);
        MirVal lo = parent.lowerBoxed(rt.lo());
        if (ssa.cur.isTerminated()) return;
        MirVal hi = parent.lowerBoxed(rt.hi());
        if (ssa.cur.isTerminated()) return;
        MirVal inc = ssa.emitConst(rt.inclusive(), PrimitiveKind.BOOL, ln);
        MirVal step =
                rt.step() != null
                        ? parent.lowerBoxed(rt.step())
                        : ssa.emitConst(null, PrimitiveKind.NONE, ln);
        if (ssa.cur.isTerminated()) return;
        MirVal res = ssa.newVal(PrimitiveKind.BOOL, null);
        ssa.emit(
                new MirInst.CallJava(
                        res,
                        null,
                        "MatchSupport",
                        ln,
                        true,
                        MATCH_RANGE,
                        List.of(boxed, lo, hi, inc, step),
                        false,
                        true,
                        ln,
                        MirEffect.IMPURE_THROW));
        branchOnPatternCond(res, bbFail, ln);
    }
}
