package org.babblelang.opt.hir;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.opt.HirPurity;

/**
 * Field layout descriptor and shared lookup/purity helpers for {@code record} SROA sub-passes
 * ({@link ScalarReplacePass}, {@link RecordBetaReducer}, and {@link UnboxedBorrowSpecializer}).
 */
record RecordLayout(
        int classId,
        String classTypeId,
        String classJvmName,
        PrimitiveKind[] paramKinds,
        int[] memberIndices,
        String[] fieldNames) {

    int fieldCount() {
        return paramKinds.length;
    }

    int paramIndexForMember(int memberIndex) {
        for (int i = 0; i < memberIndices.length; i++) {
            if (memberIndices[i] == memberIndex) {
                return i;
            }
        }
        return -1;
    }

    boolean sameType(RecordLayout other) {
        return other != null
                && classTypeId.equals(other.classTypeId)
                && paramKinds.length == other.paramKinds.length;
    }

    static Map<String, RecordLayout> collectRecordLayouts(HirModule module) {
        Map<String, RecordLayout> out = new LinkedHashMap<>();
        for (HirClass cls : module.classes().values()) {
            if (!cls.fastRecord() || cls.params().isEmpty()) {
                continue;
            }
            int n = cls.params().size();
            PrimitiveKind[] kinds = new PrimitiveKind[n];
            int[] memberIndices = new int[n];
            String[] fieldNames = new String[n];
            for (int i = 0; i < n; i++) {
                HirParam p = cls.params().get(i);
                int mi = p.memberIndex();
                kinds[i] =
                        mi >= 0 && mi < cls.memberKinds().length ? cls.memberKinds()[mi] : p.kind();
                memberIndices[i] = mi;
                fieldNames[i] = p.name();
            }
            out.put(
                    cls.shape().id(),
                    new RecordLayout(
                            cls.id(),
                            cls.shape().id(),
                            cls.jvmName(),
                            kinds,
                            memberIndices,
                            fieldNames));
        }
        return out;
    }

    static RecordLayout layoutOf(
            HirExpr.RecordNew r, Map<String, RecordLayout> localLayouts, HirModule module) {
        RecordLayout local = localLayouts.get(r.classTypeId());
        if (local != null) {
            return local;
        }
        int n = r.paramKinds().length;
        if (n == 0) {
            return null;
        }
        int[] memberIndices = new int[n];
        String[] fieldNames = new String[n];
        for (int i = 0; i < n; i++) {
            memberIndices[i] = i;
            fieldNames[i] = "f" + i;
        }
        return new RecordLayout(
                r.classId(),
                r.classTypeId(),
                r.classJvmName(),
                r.paramKinds(),
                memberIndices,
                fieldNames);
    }

    /**
     * Maps parameter index {@code p} to source argument index in {@code r.args()}, or {@code null}
     * if unsupported.
     */
    static int[] resolveArgMapping(HirExpr.RecordNew r) {
        int n = r.paramKinds().length;
        LoweringTargets.ArgumentBinding b = r.binding();
        if (b == null || b.restParam() >= 0 || b.missingParamBits() != 0) {
            return null;
        }
        int[] p2a = b.paramToArg();
        if (p2a.length < n) {
            return null;
        }
        int[] map = new int[n];
        for (int i = 0; i < n; i++) {
            map[i] = p2a[i];
        }
        return map;
    }

    static boolean isPureClassRef(
            HirExpr classRef, boolean isInstance, HirModule module, Set<String> initBindings) {
        if (classRef instanceof HirExpr.NameRead nr) {
            return (nr.finalBinding() && nr.definitelyDefined())
                    || initBindings.contains(nr.name());
        }
        if (classRef instanceof HirExpr.ClassExpr) {
            return true;
        }
        if (classRef instanceof HirExpr.ModuleExportGet meg) {
            return meg.receiver() instanceof HirExpr.NameRead
                    || HirPurity.isPureNothrowTerminating(meg.receiver(), module);
        }
        return HirPurity.isPureNothrowTerminating(classRef, module);
    }

    static int matchingParamIndex(HirExpr.ThisFieldGet g, RecordLayout layout) {
        if (g.meta().checkedType() != null) {
            return -1;
        }
        boolean typeMatch =
                (layout.classId() >= 0 && g.classId() == layout.classId())
                        || (g.externalClassJvmName() != null
                                && g.externalClassJvmName().equals(layout.classJvmName()));
        if (!typeMatch
                || (g.receiverText() != null && (!g.immutableField() || !g.definitelyDefined()))) {
            return -1;
        }
        return layout.paramIndexForMember(g.memberIndex());
    }

    static HirExpr unwrapOwnership(HirExpr expr) {
        while (true) {
            if (expr instanceof HirExpr.Move m) {
                expr = m.operand();
            } else if (expr instanceof HirExpr.Freeze f) {
                expr = f.operand();
            } else if (expr instanceof HirExpr.Block b
                    && b.isTransparentSingleExpr()
                    && b.meta().checkedType() == null) {
                expr = b.statements().get(0);
            } else {
                return expr;
            }
        }
    }
}
