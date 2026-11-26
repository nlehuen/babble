package org.babblelang.compiler;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.runtime.MatchSupport;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.babblelang.compiler.JvmPrimitives.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * JVM bytecode emission for {@link HirExpr.Match} ({@code BIP038-D10}): consecutive pure constant
 * runs lower to {@code TABLESWITCH}, {@code LOOKUPSWITCH}, AOT Java {@code enum} switch-maps, and
 * {@code String.hashCode()} lookup switches; general patterns lower to direct {@code INSTANCEOF},
 * primitive comparisons, zero-allocation range checks, and runtime helpers preserving strict
 * top-to-bottom evaluation order.
 */
final class MatchEmitter {
    private static final String MATCH_SUPPORT = RUNTIME + "MatchSupport";
    private static final String RANGE_CLASS = RUNTIME + "Range";
    private static final int MIN_SWITCH_KEYS = 3;

    private final HirEmitter emitter;

    MatchEmitter(HirEmitter emitter) {
        this.emitter = emitter;
    }

    private MethodEmitContext ctx() {
        return emitter.ctx;
    }

    void emitMatch(HirExpr.Match m) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        PrimitiveKind targetKind = m.meta().kind();
        PrimitiveKind scrutKind = m.scrutineeVar().primitiveKind();

        int scrutSlot = c.newLocal(scrutKind);
        c.slots.put(m.scrutineeVar().id(), scrutSlot);
        emitter.emitAs(m.scrutinee(), scrutKind);
        storeKindOn(mv, scrutKind, scrutSlot);

        Label endLabel = new Label();
        List<HirExpr.MatchArm> arms = m.arms();
        int i = 0;
        while (i < arms.size()) {
            ConstantRun run = detectConstantRun(arms, i, scrutKind, false);
            if (run != null) {
                emitConstantRun(run, arms, scrutSlot, scrutKind, targetKind, endLabel);
                i = run.endExclusive;
            } else {
                Label nextArm = new Label();
                emitGeneralArm(arms.get(i), scrutSlot, scrutKind, targetKind, nextArm, endLabel);
                mv.visitLabel(nextArm);
                i++;
            }
        }

        if (m.elseArm() != null) {
            HirExpr.MatchElse elseArm = m.elseArm();
            if (elseArm.var() != null) {
                bindVarFromSlot(elseArm.var(), scrutSlot, scrutKind);
            }
            emitArmBody(elseArm.body(), elseArm.bodyWideningRank(), targetKind);
        } else {
            loadBoxed(scrutSlot, scrutKind);
            c.line(m.meta().line());
            mv.visitMethodInsn(INVOKESTATIC, MATCH_SUPPORT, "noMatchingCase",
                    "(Ljava/lang/Object;)L" + RUNTIME + "BabbleException;", false);
            mv.visitInsn(ATHROW);
        }
        mv.visitLabel(endLabel);
        c.stackKind = targetKind;
    }

    private void emitArmBody(HirExpr body, int wideningRank, PrimitiveKind targetKind) {
        MethodEmitContext c = ctx();
        if (wideningRank >= 0) {
            emitter.emitExpr(body);
            emitter.widenStackRank(wideningRank);
            emitter.convertStack(c.stackKind, targetKind);
        } else {
            emitter.emitAs(body, targetKind);
        }
    }

    private void emitGeneralArm(HirExpr.MatchArm arm, int scrutSlot, PrimitiveKind scrutKind,
                                PrimitiveKind targetKind, Label failLabel, Label endLabel) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        emitPatternTest(arm.pattern(), scrutSlot, scrutKind, failLabel);
        if (arm.guard() != null) {
            emitter.controlOps.emitCondition(arm.guard(), false, failLabel);
        }
        emitArmBody(arm.body(), arm.bodyWideningRank(), targetKind);
        mv.visitJumpInsn(GOTO, endLabel);
    }

    // ---------------------------------------------------------------- jump-table runs

    private enum RunKind {
        INT_OR_CHAR,
        ENUM,
        STRING
    }

    private record ConstantRun(
            RunKind kind,
            int startInclusive,
            int endExclusive,
            Class<?> enumClass,
            boolean scrutineeGuaranteedString,
            int nullArmIndex,
            Map<Integer, Integer> intKeysToArm,
            Map<String, Integer> stringOrEnumKeysToArm
    ) {
    }

    private ConstantRun detectConstantRun(List<HirExpr.MatchArm> arms, int start,
                                          PrimitiveKind scrutKind, boolean scrutIsString) {
        boolean canIntSwitch = scrutKind == PrimitiveKind.BYTE || scrutKind == PrimitiveKind.SHORT
                || scrutKind == PrimitiveKind.INT || scrutKind == PrimitiveKind.CHAR;
        if (canIntSwitch) {
            ConstantRun intRun = scanIntRun(arms, start);
            if (intRun != null) {
                return intRun;
            }
        }
        if (scrutKind == PrimitiveKind.NONE) {
            ConstantRun enumRun = scanEnumRun(arms, start);
            if (enumRun != null) {
                return enumRun;
            }
            ConstantRun strRun = scanStringRun(arms, start, scrutIsString);
            if (strRun != null) {
                return strRun;
            }
        }
        return null;
    }

    private static ConstantRun scanIntRun(List<HirExpr.MatchArm> arms, int start) {
        TreeMap<Integer, Integer> keys = new TreeMap<>();
        int end = start;
        while (end < arms.size()) {
            HirExpr.MatchArm arm = arms.get(end);
            if (arm.guard() != null || !arm.boundVars().isEmpty()) {
                break;
            }
            List<HirPattern.Const> consts = collectPureConsts(arm.pattern());
            if (consts == null || consts.isEmpty()) {
                break;
            }
            boolean allInt = true;
            for (HirPattern.Const c : consts) {
                if (!isSwitchableIntConst(c)) {
                    allInt = false;
                    break;
                }
            }
            if (!allInt) {
                break;
            }
            for (HirPattern.Const c : consts) {
                keys.putIfAbsent(intConstValue(c), end);
            }
            end++;
        }
        if (keys.size() < MIN_SWITCH_KEYS) {
            return null;
        }
        return new ConstantRun(RunKind.INT_OR_CHAR, start, end, null, false, -1, keys, Map.of());
    }

    private static ConstantRun scanEnumRun(List<HirExpr.MatchArm> arms, int start) {
        Class<?> enumClass = null;
        LinkedHashMap<String, Integer> constToArm = new LinkedHashMap<>();
        int nullArm = -1;
        int end = start;
        while (end < arms.size()) {
            HirExpr.MatchArm arm = arms.get(end);
            if (arm.guard() != null || !arm.boundVars().isEmpty()) {
                break;
            }
            List<HirPattern.Const> consts = collectPureConsts(arm.pattern());
            if (consts == null || consts.isEmpty()) {
                break;
            }
            boolean ok = true;
            for (HirPattern.Const c : consts) {
                if (c.isNull()) {
                    continue;
                }
                if (!c.isEnum()) {
                    ok = false;
                    break;
                }
                if (enumClass == null) {
                    enumClass = c.enumClass();
                } else if (enumClass != c.enumClass()) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                break;
            }
            for (HirPattern.Const c : consts) {
                if (c.isNull()) {
                    if (nullArm < 0) {
                        nullArm = end;
                    }
                } else {
                    constToArm.putIfAbsent(c.enumConstantName(), end);
                }
            }
            end++;
        }
        if (enumClass == null || constToArm.size() < MIN_SWITCH_KEYS) {
            return null;
        }
        return new ConstantRun(RunKind.ENUM, start, end, enumClass, false, nullArm, Map.of(), constToArm);
    }

    private static ConstantRun scanStringRun(List<HirExpr.MatchArm> arms, int start, boolean scrutIsString) {
        LinkedHashMap<String, Integer> strToArm = new LinkedHashMap<>();
        int nullArm = -1;
        int end = start;
        while (end < arms.size()) {
            HirExpr.MatchArm arm = arms.get(end);
            if (arm.guard() != null || !arm.boundVars().isEmpty()) {
                break;
            }
            List<HirPattern.Const> consts = collectPureConsts(arm.pattern());
            if (consts == null || consts.isEmpty()) {
                break;
            }
            boolean ok = true;
            for (HirPattern.Const c : consts) {
                if (!c.isNull() && !(c.value() instanceof String)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                break;
            }
            for (HirPattern.Const c : consts) {
                if (c.isNull()) {
                    if (nullArm < 0) {
                        nullArm = end;
                    }
                } else {
                    strToArm.putIfAbsent((String) c.value(), end);
                }
            }
            end++;
        }
        if (strToArm.size() < MIN_SWITCH_KEYS) {
            return null;
        }
        return new ConstantRun(RunKind.STRING, start, end, null, scrutIsString, nullArm, Map.of(), strToArm);
    }

    private static List<HirPattern.Const> collectPureConsts(HirPattern p) {
        if (p instanceof HirPattern.Const c) {
            return List.of(c);
        }
        if (p instanceof HirPattern.Or o) {
            List<HirPattern.Const> out = new ArrayList<>(o.alternatives().size());
            for (HirPattern alt : o.alternatives()) {
                List<HirPattern.Const> sub = collectPureConsts(alt);
                if (sub == null) {
                    return null;
                }
                out.addAll(sub);
            }
            return out;
        }
        return null;
    }

    private static boolean isSwitchableIntConst(HirPattern.Const c) {
        Object v = c.value();
        return v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Character;
    }

    private static int intConstValue(HirPattern.Const c) {
        Object v = c.value();
        if (v instanceof Character ch) {
            return ch;
        }
        return ((Number) v).intValue();
    }

    private void emitConstantRun(ConstantRun run, List<HirExpr.MatchArm> arms, int scrutSlot,
                                 PrimitiveKind scrutKind, PrimitiveKind targetKind, Label endLabel) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int armCount = run.endExclusive - run.startInclusive;
        Label[] armLabels = new Label[armCount];
        for (int k = 0; k < armCount; k++) {
            armLabels[k] = new Label();
        }
        Label afterRun = new Label();

        switch (run.kind) {
            case INT_OR_CHAR -> {
                loadKindOn(mv, scrutKind, scrutSlot);
                int count = run.intKeysToArm.size();
                int[] keys = new int[count];
                Label[] targets = new Label[count];
                int idx = 0;
                for (Map.Entry<Integer, Integer> e : run.intKeysToArm.entrySet()) {
                    keys[idx] = e.getKey();
                    targets[idx] = armLabels[e.getValue() - run.startInclusive];
                    idx++;
                }
                emitIntSwitch(mv, keys, targets, afterRun);
            }
            case ENUM -> {
                String enumInternal = run.enumClass.getName().replace('.', '/');
                Label nullLabel = run.nullArmIndex >= 0 ? armLabels[run.nullArmIndex - run.startInclusive] : afterRun;
                mv.visitVarInsn(ALOAD, scrutSlot);
                mv.visitJumpInsn(IFNULL, nullLabel);
                mv.visitVarInsn(ALOAD, scrutSlot);
                mv.visitTypeInsn(INSTANCEOF, enumInternal);
                mv.visitJumpInsn(IFEQ, afterRun);

                int n = run.stringOrEnumKeysToArm.size();
                String[] names = new String[n];
                Label[] denseTargets = new Label[n];
                int idx = 0;
                for (Map.Entry<String, Integer> e : run.stringOrEnumKeysToArm.entrySet()) {
                    names[idx] = e.getKey();
                    denseTargets[idx] = armLabels[e.getValue() - run.startInclusive];
                    idx++;
                }
                String mapField = emitter.constantPool.enumSwitchMapField(run.enumClass, names);
                mv.visitFieldInsn(GETSTATIC, emitter.className, mapField, "[I");
                mv.visitVarInsn(ALOAD, scrutSlot);
                mv.visitTypeInsn(CHECKCAST, enumInternal);
                mv.visitMethodInsn(INVOKEVIRTUAL, enumInternal, "ordinal", "()I", false);
                mv.visitInsn(IALOAD);
                mv.visitTableSwitchInsn(0, n - 1, afterRun, denseTargets);
            }
            case STRING -> {
                Label nullLabel = run.nullArmIndex >= 0 ? armLabels[run.nullArmIndex - run.startInclusive] : afterRun;
                mv.visitVarInsn(ALOAD, scrutSlot);
                mv.visitJumpInsn(IFNULL, nullLabel);
                if (!run.scrutineeGuaranteedString) {
                    mv.visitVarInsn(ALOAD, scrutSlot);
                    mv.visitTypeInsn(INSTANCEOF, "java/lang/String");
                    mv.visitJumpInsn(IFEQ, afterRun);
                }
                TreeMap<Integer, List<Map.Entry<String, Integer>>> buckets = new TreeMap<>();
                for (Map.Entry<String, Integer> e : run.stringOrEnumKeysToArm.entrySet()) {
                    buckets.computeIfAbsent(e.getKey().hashCode(), h -> new ArrayList<>()).add(e);
                }
                int bCount = buckets.size();
                int[] hashes = new int[bCount];
                Label[] bucketLabels = new Label[bCount];
                List<List<Map.Entry<String, Integer>>> bucketEntries = new ArrayList<>(bCount);
                int idx = 0;
                for (Map.Entry<Integer, List<Map.Entry<String, Integer>>> b : buckets.entrySet()) {
                    hashes[idx] = b.getKey();
                    bucketLabels[idx] = new Label();
                    bucketEntries.add(b.getValue());
                    idx++;
                }
                mv.visitVarInsn(ALOAD, scrutSlot);
                mv.visitTypeInsn(CHECKCAST, "java/lang/String");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false);
                mv.visitLookupSwitchInsn(afterRun, hashes, bucketLabels);
                for (int b = 0; b < bCount; b++) {
                    mv.visitLabel(bucketLabels[b]);
                    for (Map.Entry<String, Integer> e : bucketEntries.get(b)) {
                        ConstantPool.pushString(mv, e.getKey());
                        mv.visitVarInsn(ALOAD, scrutSlot);
                        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
                        mv.visitJumpInsn(IFNE, armLabels[e.getValue() - run.startInclusive]);
                    }
                    mv.visitJumpInsn(GOTO, afterRun);
                }
            }
        }

        for (int k = 0; k < armCount; k++) {
            HirExpr.MatchArm arm = arms.get(run.startInclusive + k);
            mv.visitLabel(armLabels[k]);
            emitArmBody(arm.body(), arm.bodyWideningRank(), targetKind);
            mv.visitJumpInsn(GOTO, endLabel);
        }
        mv.visitLabel(afterRun);
    }

    static void emitIntSwitch(MethodVisitor mv, int[] sortedKeys, Label[] targets, Label defaultLabel) {
        int min = sortedKeys[0];
        int max = sortedKeys[sortedKeys.length - 1];
        long span = (long) max - (long) min + 1L;
        if (span <= 65536L && sortedKeys.length * 2L >= span) {
            Label[] dense = new Label[(int) span];
            java.util.Arrays.fill(dense, defaultLabel);
            for (int i = 0; i < sortedKeys.length; i++) {
                dense[sortedKeys[i] - min] = targets[i];
            }
            mv.visitTableSwitchInsn(min, max, defaultLabel, dense);
        } else {
            mv.visitLookupSwitchInsn(defaultLabel, sortedKeys, targets);
        }
    }

    // ---------------------------------------------------------------- pattern tests

    private void emitPatternTest(HirPattern pattern, int valSlot, PrimitiveKind valKind, Label failLabel) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        c.line(pattern.line());
        switch (pattern) {
            case HirPattern.Wildcard ignored -> {
            }
            case HirPattern.Const pc -> emitConstTest(pc, valSlot, valKind, failLabel);
            case HirPattern.ValueExpr ve -> emitValueEqualityTest(ve.expr(), valSlot, valKind, false, failLabel);
            case HirPattern.TypeTest tt -> emitTypeTest(tt, valSlot, valKind, failLabel);
            case HirPattern.Bind b -> {
                if (b.inner() != null) {
                    emitPatternTest(b.inner(), valSlot, valKind, failLabel);
                }
                bindVarFromSlot(b.var(), valSlot, valKind);
            }
            case HirPattern.Or o -> {
                List<HirPattern> alts = o.alternatives();
                Label matched = new Label();
                for (int i = 0; i < alts.size() - 1; i++) {
                    Label nextAlt = new Label();
                    emitPatternTest(alts.get(i), valSlot, valKind, nextAlt);
                    mv.visitJumpInsn(GOTO, matched);
                    mv.visitLabel(nextAlt);
                }
                emitPatternTest(alts.get(alts.size() - 1), valSlot, valKind, failLabel);
                mv.visitLabel(matched);
            }
            case HirPattern.Relational rel -> emitRelationalTest(rel, valSlot, valKind, failLabel);
            case HirPattern.RangeTest rt -> emitRangeTest(rt, valSlot, valKind, failLabel);
            case HirPattern.Membership mp -> {
                loadBoxed(valSlot, valKind);
                emitter.emitBoxed(mp.container());
                c.line(mp.line());
                mv.visitMethodInsn(INVOKESTATIC, MATCH_SUPPORT, "inContainer",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(IFEQ, failLabel);
            }
            case HirPattern.RecordDestructure rd -> emitRecordDestructure(rd, valSlot, valKind, failLabel);
            case HirPattern.SequenceDestructure sd -> emitSequenceDestructure(sd, valSlot, valKind, failLabel);
        }
    }

    private void emitRecordDestructure(HirPattern.RecordDestructure rd, int valSlot, PrimitiveKind valKind, Label failLabel) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = rd.line();
        emitTypeTest(rd.typeTest(), valSlot, valKind, failLabel);
        if (!rd.isJavaRecord()) {
            String jvmName = rd.babbleJvmName();
            ClassGen gen = rd.babbleClassId() >= 0 ? emitter.classGen(rd.babbleClassId()) : null;
            if (gen != null) {
                jvmName = gen.name;
            }
            if (jvmName != null) {
                int recSlot = c.newLocal();
                loadBoxed(valSlot, valKind);
                mv.visitTypeInsn(CHECKCAST, jvmName);
                mv.visitVarInsn(ASTORE, recSlot);
                for (int i = 0; i < rd.fieldPatterns().size(); i++) {
                    String fn = rd.fieldNames().get(i);
                    PrimitiveKind fk = rd.fieldKinds().get(i);
                    int fSlot = c.newLocal(fk);
                    mv.visitVarInsn(ALOAD, recSlot);
                    c.line(ln);
                    mv.visitFieldInsn(GETFIELD, jvmName, fn, jvmDescriptor(fk));
                    storeKindOn(mv, fk, fSlot);
                    emitPatternTest(rd.fieldPatterns().get(i), fSlot, fk, failLabel);
                }
                return;
            }
            for (int i = 0; i < rd.fieldPatterns().size(); i++) {
                String fn = rd.fieldNames().get(i);
                int fSlot = c.newLocal();
                loadBoxed(valSlot, valKind);
                mv.visitLdcInsn(fn);
                mv.visitLdcInsn(fn);
                c.line(ln);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Members", "select",
                        "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
                mv.visitVarInsn(ASTORE, fSlot);
                emitPatternTest(rd.fieldPatterns().get(i), fSlot, PrimitiveKind.NONE, failLabel);
            }
            return;
        }
        String recInternal = org.objectweb.asm.Type.getInternalName(rd.javaRecordClass());
        int recSlot = c.newLocal();
        loadBoxed(valSlot, valKind);
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, recInternal);
        mv.visitVarInsn(ASTORE, recSlot);
        for (int i = 0; i < rd.fieldPatterns().size(); i++) {
            java.lang.reflect.Method acc = rd.javaAccessors().get(i);
            PrimitiveKind fk = rd.fieldKinds().get(i);
            int fSlot = c.newLocal(fk);
            mv.visitVarInsn(ALOAD, recSlot);
            c.line(ln);
            mv.visitMethodInsn(INVOKEVIRTUAL, recInternal, acc.getName(),
                    org.objectweb.asm.Type.getMethodDescriptor(acc), false);
            if (!fk.isPrimitive()) {
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "fromJava", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            storeKindOn(mv, fk, fSlot);
            emitPatternTest(rd.fieldPatterns().get(i), fSlot, fk, failLabel);
        }
    }

    private void emitSequenceDestructure(HirPattern.SequenceDestructure sd, int valSlot, PrimitiveKind valKind, Label failLabel) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = sd.line();
        if (sd.typeTest() != null) {
            emitTypeTest(sd.typeTest(), valSlot, valKind, failLabel);
        } else if (sd.isArray()) {
            loadBoxed(valSlot, valKind);
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "isArray", "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(IFEQ, failLabel);
        }
        int fixed = sd.elemPatterns().size();
        if (!sd.isArray()) {
            int listSlot = c.newLocal();
            loadBoxed(valSlot, valKind);
            mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, "java/util/List");
            mv.visitVarInsn(ASTORE, listSlot);

            mv.visitVarInsn(ALOAD, listSlot);
            c.line(ln);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true);
            ConstantPool.pushInt(mv, fixed);
            mv.visitJumpInsn(sd.hasRest() ? IF_ICMPLT : IF_ICMPNE, failLabel);

            for (int i = 0; i < fixed; i++) {
                int eSlot = c.newLocal();
                mv.visitVarInsn(ALOAD, listSlot);
                ConstantPool.pushInt(mv, i);
                c.line(ln);
                mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
                mv.visitVarInsn(ASTORE, eSlot);
                emitPatternTest(sd.elemPatterns().get(i), eSlot, PrimitiveKind.NONE, failLabel);
            }
            if (sd.restVar() != null) {
                int tailSlot = c.newLocal();
                mv.visitVarInsn(ALOAD, listSlot);
                ConstantPool.pushInt(mv, fixed);
                c.line(ln);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "CoreCollections", "listSlice",
                        "(Ljava/util/List;I)Ljava/util/List;", false);
                mv.visitVarInsn(ASTORE, tailSlot);
                bindVarFromSlot(sd.restVar(), tailSlot, PrimitiveKind.NONE);
            }
            return;
        }
        int arrSlot = c.newLocal();
        loadBoxed(valSlot, valKind);
        mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        Class<?> arrCls = sd.arrayClass();
        PrimitiveKind ek = sd.elemKind();
        if (arrCls != null) {
            String arrInternal = org.objectweb.asm.Type.getInternalName(arrCls);
            mv.visitTypeInsn(CHECKCAST, arrInternal);
            mv.visitVarInsn(ASTORE, arrSlot);

            mv.visitVarInsn(ALOAD, arrSlot);
            mv.visitInsn(ARRAYLENGTH);
            ConstantPool.pushInt(mv, fixed);
            mv.visitJumpInsn(sd.hasRest() ? IF_ICMPLT : IF_ICMPNE, failLabel);

            int loadOp = arrayLoadOpcode(ek);
            for (int i = 0; i < fixed; i++) {
                int eSlot = c.newLocal(ek);
                mv.visitVarInsn(ALOAD, arrSlot);
                ConstantPool.pushInt(mv, i);
                mv.visitInsn(loadOp);
                storeKindOn(mv, ek, eSlot);
                emitPatternTest(sd.elemPatterns().get(i), eSlot, ek, failLabel);
            }
        } else {
            mv.visitVarInsn(ASTORE, arrSlot);
            mv.visitVarInsn(ALOAD, arrSlot);
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "size", "(Ljava/lang/Object;)I", false);
            ConstantPool.pushInt(mv, fixed);
            mv.visitJumpInsn(sd.hasRest() ? IF_ICMPLT : IF_ICMPNE, failLabel);

            for (int i = 0; i < fixed; i++) {
                int eSlot = c.newLocal();
                mv.visitVarInsn(ALOAD, arrSlot);
                ConstantPool.pushInt(mv, i);
                c.line(ln);
                mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "get",
                        "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
                mv.visitVarInsn(ASTORE, eSlot);
                emitPatternTest(sd.elemPatterns().get(i), eSlot, PrimitiveKind.NONE, failLabel);
            }
        }
        if (sd.restVar() != null) {
            int tailSlot = c.newLocal();
            mv.visitVarInsn(ALOAD, arrSlot);
            ConstantPool.pushInt(mv, fixed);
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "ArraySupport", "slice",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, tailSlot);
            bindVarFromSlot(sd.restVar(), tailSlot, PrimitiveKind.NONE);
        }
    }

    private static int arrayLoadOpcode(PrimitiveKind ek) {
        return switch (ek) {
            case BYTE, BOOL -> BALOAD;
            case SHORT -> SALOAD;
            case INT -> IALOAD;
            case LONG -> LALOAD;
            case FLOAT -> FALOAD;
            case DOUBLE -> DALOAD;
            case CHAR -> CALOAD;
            case NONE -> AALOAD;
        };
    }

    private void emitConstTest(HirPattern.Const pc, int valSlot, PrimitiveKind valKind, Label failLabel) {
        MethodVisitor mv = ctx().mv;
        if (pc.isNull()) {
            if (valKind.isPrimitive()) {
                mv.visitJumpInsn(GOTO, failLabel);
            } else {
                mv.visitVarInsn(ALOAD, valSlot);
                mv.visitJumpInsn(IFNONNULL, failLabel);
            }
            return;
        }
        if (pc.isEnum()) {
            String enumInternal = pc.enumClass().getName().replace('.', '/');
            loadBoxed(valSlot, valKind);
            mv.visitFieldInsn(GETSTATIC, enumInternal, pc.enumConstantName(), "L" + enumInternal + ";");
            mv.visitJumpInsn(IF_ACMPNE, failLabel);
            return;
        }
        Object v = pc.value();
        if (v instanceof Boolean b && valKind == PrimitiveKind.BOOL) {
            mv.visitVarInsn(ILOAD, valSlot);
            mv.visitJumpInsn(b ? IFEQ : IFNE, failLabel);
            return;
        }
        if (v instanceof Character ch && valKind == PrimitiveKind.CHAR) {
            mv.visitVarInsn(ILOAD, valSlot);
            ConstantPool.pushInt(mv, ch);
            mv.visitJumpInsn(IF_ICMPNE, failLabel);
            return;
        }
        if (v instanceof Number n && isNumericKind(valKind)) {
            PrimitiveKind ck = pc.kind();
            if (isNumericKind(ck)) {
                PrimitiveKind common = promoteNumeric(valKind, ck);
                loadKindAs(valSlot, valKind, common);
                pushNumericConst(mv, n, common);
                emitPrimNotEqualJump(mv, common, failLabel);
                return;
            }
        }
        if (v instanceof String s) {
            ConstantPool.pushString(mv, s);
            loadBoxed(valSlot, valKind);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(IFEQ, failLabel);
            return;
        }
        loadBoxed(valSlot, valKind);
        pushBoxedConst(mv, v);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", "equal", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, failLabel);
    }

    private void emitValueEqualityTest(HirExpr rhs, int valSlot, PrimitiveKind valKind,
                                       boolean invert, Label failLabel) {
        MethodVisitor mv = ctx().mv;
        PrimitiveKind rk = rhs.meta().kind();
        if (valKind == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL) {
            mv.visitVarInsn(ILOAD, valSlot);
            emitter.emitAs(rhs, PrimitiveKind.BOOL);
            mv.visitJumpInsn(invert ? IF_ICMPEQ : IF_ICMPNE, failLabel);
            return;
        }
        if (valKind == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR) {
            mv.visitVarInsn(ILOAD, valSlot);
            emitter.emitAs(rhs, PrimitiveKind.CHAR);
            mv.visitJumpInsn(invert ? IF_ICMPEQ : IF_ICMPNE, failLabel);
            return;
        }
        if (isNumericKind(valKind) && isNumericKind(rk)) {
            PrimitiveKind common = promoteNumeric(valKind, rk);
            loadKindAs(valSlot, valKind, common);
            emitter.emitAs(rhs, common);
            if (!invert) {
                emitPrimNotEqualJump(mv, common, failLabel);
            } else {
                emitPrimEqualJump(mv, common, failLabel);
            }
            return;
        }
        loadBoxed(valSlot, valKind);
        emitter.emitBoxed(rhs);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Operators", "equal", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(invert ? IFNE : IFEQ, failLabel);
    }

    private void emitRelationalTest(HirPattern.Relational rel, int valSlot, PrimitiveKind valKind, Label failLabel) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        HirExpr.BinaryOp op = rel.op();
        if (op == HirExpr.BinaryOp.EQ || op == HirExpr.BinaryOp.NEQ) {
            emitValueEqualityTest(rel.rhs(), valSlot, valKind, op == HirExpr.BinaryOp.NEQ, failLabel);
            return;
        }
        PrimitiveKind rk = rel.rhs().meta().kind();
        if (valKind == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR) {
            mv.visitVarInsn(ILOAD, valSlot);
            emitter.emitAs(rel.rhs(), PrimitiveKind.CHAR);
            mv.visitJumpInsn(invertedIntCompareOpcode(op), failLabel);
            return;
        }
        if (isNumericKind(valKind) && isNumericKind(rk)) {
            PrimitiveKind common = promoteNumeric(valKind, rk);
            loadKindAs(valSlot, valKind, common);
            emitter.emitAs(rel.rhs(), common);
            emitPrimOrderingFailJump(mv, common, op, failLabel);
            return;
        }
        loadBoxed(valSlot, valKind);
        emitter.emitBoxed(rel.rhs());
        int opCode = switch (op) {
            case LT -> MatchSupport.OP_LT;
            case LTE -> MatchSupport.OP_LTE;
            case GT -> MatchSupport.OP_GT;
            case GTE -> MatchSupport.OP_GTE;
            default -> throw new IllegalStateException("Unexpected relational op : " + op);
        };
        ConstantPool.pushInt(mv, opCode);
        c.line(rel.line());
        mv.visitMethodInsn(INVOKESTATIC, MATCH_SUPPORT, "relational",
                "(Ljava/lang/Object;Ljava/lang/Object;I)Z", false);
        mv.visitJumpInsn(IFEQ, failLabel);
    }

    private void emitRangeTest(HirPattern.RangeTest rt, int valSlot, PrimitiveKind valKind, Label failLabel) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        PrimitiveKind ek = rt.elemKind();
        if (valKind.isPrimitive() && ek.isPrimitive() && ek != PrimitiveKind.BOOL) {
            PrimitiveKind cmpKind = ek == PrimitiveKind.CHAR ? PrimitiveKind.CHAR
                    : promoteNumeric(valKind, ek);
            int loLocal = c.newLocal(cmpKind);
            int hiLocal = c.newLocal(cmpKind);
            emitter.emitAs(rt.lo(), cmpKind);
            storeKindOn(mv, cmpKind, loLocal);
            emitter.emitAs(rt.hi(), cmpKind);
            storeKindOn(mv, cmpKind, hiLocal);

            int stepLocal = -1;
            PrimitiveKind stepKind = cmpKind == PrimitiveKind.LONG ? PrimitiveKind.LONG : PrimitiveKind.INT;
            if (rt.step() != null) {
                stepLocal = c.newLocal(stepKind);
                emitter.emitAs(rt.step(), stepKind);
                c.line(rt.line());
                if (stepKind == PrimitiveKind.LONG) {
                    mv.visitMethodInsn(INVOKESTATIC, RANGE_CLASS, "validateStep", "(J)J", true);
                    mv.visitVarInsn(LSTORE, stepLocal);
                } else {
                    mv.visitMethodInsn(INVOKESTATIC, RANGE_CLASS, "validateStep", "(I)I", true);
                    mv.visitVarInsn(ISTORE, stepLocal);
                }
            }

            // val >= lo
            loadKindAs(valSlot, valKind, cmpKind);
            loadKindOn(mv, cmpKind, loLocal);
            emitPrimOrderingFailJump(mv, cmpKind, HirExpr.BinaryOp.GTE, failLabel);

            // val <= hi (or < hi)
            loadKindAs(valSlot, valKind, cmpKind);
            loadKindOn(mv, cmpKind, hiLocal);
            emitPrimOrderingFailJump(mv, cmpKind, rt.inclusive() ? HirExpr.BinaryOp.LTE : HirExpr.BinaryOp.LT, failLabel);

            if (stepLocal >= 0) {
                if (stepKind == PrimitiveKind.LONG) {
                    loadKindAs(valSlot, valKind, PrimitiveKind.LONG);
                    mv.visitVarInsn(LLOAD, loLocal);
                    mv.visitInsn(LSUB);
                    mv.visitVarInsn(LLOAD, stepLocal);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorMod", "(JJ)J", false);
                    mv.visitInsn(LCONST_0);
                    mv.visitInsn(LCMP);
                    mv.visitJumpInsn(IFNE, failLabel);
                } else {
                    loadKindAs(valSlot, valKind, PrimitiveKind.INT);
                    loadKindOn(mv, cmpKind, loLocal);
                    mv.visitInsn(ISUB);
                    mv.visitVarInsn(ILOAD, stepLocal);
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorMod", "(II)I", false);
                    mv.visitJumpInsn(IFNE, failLabel);
                }
            }
            return;
        }
        loadBoxed(valSlot, valKind);
        emitter.emitBoxed(rt.lo());
        emitter.emitBoxed(rt.hi());
        mv.visitInsn(rt.inclusive() ? ICONST_1 : ICONST_0);
        if (rt.step() != null) {
            emitter.emitBoxed(rt.step());
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        c.line(rt.line());
        mv.visitMethodInsn(INVOKESTATIC, MATCH_SUPPORT, "rangeMatch",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;ZLjava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, failLabel);
    }

    private void emitTypeTest(HirPattern.TypeTest tt, int valSlot, PrimitiveKind valKind, Label failLabel) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        int ln = tt.line();
        loadBoxed(valSlot, valKind);
        if (tt.builtin() != null) {
            mv.visitLdcInsn(tt.builtin());
            c.line(ln);
            mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Values", "isBuiltin",
                    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
            mv.visitJumpInsn(IF_ACMPNE, failLabel);
            return;
        }
        String targetJvm = emitter.controlOps.resolveIsTargetJvm(tt.target());
        if (targetJvm != null) {
            if (tt.target().jvmInternalName() != null) {
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "unwrap", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            c.line(ln);
            mv.visitTypeInsn(INSTANCEOF, targetJvm);
            mv.visitJumpInsn(IFEQ, failLabel);
            return;
        }
        if (!tt.intersectionTargets().isEmpty()) {
            List<String> jvms = new ArrayList<>(tt.intersectionTargets().size());
            boolean unwrap = false;
            for (LoweringTargets.IsTarget target : tt.intersectionTargets()) {
                String jvm = emitter.controlOps.resolveIsTargetJvm(target);
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
                for (int k = 0; k < jvms.size() - 1; k++) {
                    mv.visitInsn(DUP);
                    mv.visitTypeInsn(INSTANCEOF, jvms.get(k));
                    Label ok = new Label();
                    mv.visitJumpInsn(IFNE, ok);
                    mv.visitInsn(POP);
                    mv.visitJumpInsn(GOTO, failLabel);
                    mv.visitLabel(ok);
                }
                mv.visitTypeInsn(INSTANCEOF, jvms.get(jvms.size() - 1));
                mv.visitJumpInsn(IFEQ, failLabel);
                return;
            }
        }
        if (tt.descriptor() == null) {
            mv.visitInsn(POP);
            return;
        }
        ConstantPool.pushString(mv, tt.descriptor());
        c.line(ln);
        mv.visitMethodInsn(INVOKESTATIC, RUNTIME + "Types", "matches", "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
        mv.visitJumpInsn(IFEQ, failLabel);
    }

    // ---------------------------------------------------------------- local binding & primitive helpers

    private void bindVarFromSlot(HirVar var, int valSlot, PrimitiveKind valKind) {
        MethodEmitContext c = ctx();
        MethodVisitor mv = c.mv;
        PrimitiveKind vk = var.primitiveKind();
        if (!c.slots.containsKey(var.id())) {
            int slot = c.newLocal(var.cell() ? PrimitiveKind.NONE : vk);
            c.slots.put(var.id(), slot);
        }
        loadKindOn(mv, valKind, valSlot);
        c.stackKind = valKind;
        emitter.convertStack(valKind, vk);
        emitter.initVariable(var, vk);
    }

    private void loadBoxed(int slot, PrimitiveKind kind) {
        MethodEmitContext c = ctx();
        loadKindOn(c.mv, kind, slot);
        c.stackKind = kind;
        emitter.convertStack(kind, PrimitiveKind.NONE);
    }

    private void loadKindAs(int slot, PrimitiveKind from, PrimitiveKind to) {
        MethodEmitContext c = ctx();
        loadKindOn(c.mv, from, slot);
        c.stackKind = from;
        emitter.convertStack(from, to);
    }

    private static PrimitiveKind promoteNumeric(PrimitiveKind a, PrimitiveKind b) {
        int max = Math.max(numericOrder(a), numericOrder(b));
        return switch (max) {
            case 0, 1, 2 -> PrimitiveKind.INT;
            case 3 -> PrimitiveKind.LONG;
            case 4 -> PrimitiveKind.FLOAT;
            default -> PrimitiveKind.DOUBLE;
        };
    }

    private static void pushNumericConst(MethodVisitor mv, Number n, PrimitiveKind kind) {
        switch (kind) {
            case BYTE, SHORT, INT -> ConstantPool.pushInt(mv, n.intValue());
            case LONG -> mv.visitLdcInsn(n.longValue());
            case FLOAT -> mv.visitLdcInsn(n.floatValue());
            case DOUBLE -> mv.visitLdcInsn(n.doubleValue());
            default -> throw new IllegalStateException("Not numeric : " + kind);
        }
    }

    private static void pushBoxedConst(MethodVisitor mv, Object v) {
        switch (v) {
            case null -> mv.visitInsn(ACONST_NULL);
            case Boolean b -> mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", b ? "TRUE" : "FALSE", "Ljava/lang/Boolean;");
            case Byte b -> {
                ConstantPool.pushInt(mv, b);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
            }
            case Short s -> {
                ConstantPool.pushInt(mv, s);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
            }
            case Integer i -> {
                ConstantPool.pushInt(mv, i);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            }
            case Long l -> {
                mv.visitLdcInsn(l);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            }
            case Float f -> {
                mv.visitLdcInsn(f);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            }
            case Double d -> {
                mv.visitLdcInsn(d);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
            }
            case Character ch -> {
                ConstantPool.pushInt(mv, ch);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
            }
            case String s -> ConstantPool.pushString(mv, s);
            default -> throw new IllegalStateException("Unexpected constant : " + v);
        }
    }

    private static void emitPrimNotEqualJump(MethodVisitor mv, PrimitiveKind kind, Label failLabel) {
        switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> mv.visitJumpInsn(IF_ICMPNE, failLabel);
            case LONG -> {
                mv.visitInsn(LCMP);
                mv.visitJumpInsn(IFNE, failLabel);
            }
            case FLOAT -> {
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false);
                mv.visitJumpInsn(IFNE, failLabel);
            }
            case DOUBLE -> {
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
                mv.visitJumpInsn(IFNE, failLabel);
            }
            default -> throw new IllegalStateException();
        }
    }

    private static void emitPrimEqualJump(MethodVisitor mv, PrimitiveKind kind, Label failLabel) {
        switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> mv.visitJumpInsn(IF_ICMPEQ, failLabel);
            case LONG -> {
                mv.visitInsn(LCMP);
                mv.visitJumpInsn(IFEQ, failLabel);
            }
            case FLOAT -> {
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false);
                mv.visitJumpInsn(IFEQ, failLabel);
            }
            case DOUBLE -> {
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
                mv.visitJumpInsn(IFEQ, failLabel);
            }
            default -> throw new IllegalStateException();
        }
    }

    private static void emitPrimOrderingFailJump(MethodVisitor mv, PrimitiveKind kind,
                                                 HirExpr.BinaryOp op, Label failLabel) {
        switch (kind) {
            case BYTE, SHORT, INT, CHAR -> mv.visitJumpInsn(invertedIntCompareOpcode(op), failLabel);
            case LONG -> {
                mv.visitInsn(LCMP);
                mv.visitJumpInsn(invertedZeroCompareOpcode(op), failLabel);
            }
            case FLOAT -> {
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "compare", "(FF)I", false);
                mv.visitJumpInsn(invertedZeroCompareOpcode(op), failLabel);
            }
            case DOUBLE -> {
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "compare", "(DD)I", false);
                mv.visitJumpInsn(invertedZeroCompareOpcode(op), failLabel);
            }
            default -> throw new IllegalStateException();
        }
    }

    private static int invertedIntCompareOpcode(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> IF_ICMPGE;
            case LTE -> IF_ICMPGT;
            case GT -> IF_ICMPLE;
            case GTE -> IF_ICMPLT;
            default -> throw new IllegalStateException();
        };
    }

    private static int invertedZeroCompareOpcode(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> IFGE;
            case LTE -> IFGT;
            case GT -> IFLE;
            case GTE -> IFLT;
            default -> throw new IllegalStateException();
        };
    }
}
