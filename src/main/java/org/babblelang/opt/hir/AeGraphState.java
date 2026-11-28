package org.babblelang.opt.hir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.hir.IrEffect;

/**
 * Lexical-dominator scoped value-numbering state, variable epochs, alias-partition memory epochs,
 * store-to-load forwarding table, and boxing/idempotence origin metadata for {@link HirAeGraphPass}
 * ({@code BIP039-D6}, {@code BIP039-I4}).
 */
final class AeGraphState {
    /** Per-function shared counter and global origin metadata (monotonically grows per fn). */
    static final class Shared {
        static final int MAX_VALUE_NODES = 1024;

        int nextValId = 1;
        int nextVarEpoch = 1;
        int nextMemEpoch = 1;
        int nextCseIndex = 0;

        /** Boxed e-class -> primitive source info for {@code unbox/as/narrow(box(x)) -> x}. */
        final Map<Integer, BoxedOrigin> boxedOrigins = new HashMap<>();

        /** Primitive e-class -> safe boxed source for {@code box(unbox(x)) -> x}. */
        final Map<Integer, UnboxedOrigin> unboxedOrigins = new HashMap<>();

        /**
         * Unary algebraic call result e-class -> origin metadata for {@code f(f(x)) -> f(x)} and
         * cross-statement {@code injective}/{@code monotone} comparison cancellation ({@code
         * BIP042-I1}).
         */
        final Map<Integer, UnaryCallOrigin> unaryCallOrigins = new HashMap<>();

        /** Constant value recorded for a constant e-class. */
        final Map<Integer, HirExpr.Const> constExprs = new HashMap<>();

        /** Value IDs of expressions guaranteed non-null (from ThisRef/Const/NonNull/CheckCast). */
        final Set<Integer> nonNullVals = new HashSet<>();

        int freshValId() {
            return nextValId++;
        }

        int freshVarEpoch() {
            return nextVarEpoch++;
        }

        int freshMemEpoch() {
            return nextMemEpoch++;
        }

        boolean overBudget() {
            return nextValId > MAX_VALUE_NODES;
        }
    }

    record BoxedOrigin(PrimitiveKind primKind, int sourceValId) {}

    record UnboxedOrigin(PrimitiveKind primKind, int sourceValId, HirExpr safeBoxedExpr) {}

    record UnaryCallOrigin(
            String callSymbol,
            int argValId,
            PrimitiveKind argKind,
            boolean argStringType,
            boolean idempotent,
            boolean monotone,
            boolean injective) {}

    /** A dominating variable binding for an e-class, valid only while {@code varEpoch} matches. */
    record VarBinding(HirVar var, int epoch, PrimitiveKind kind) {}

    /** A stored field value available for store-to-load forwarding. */
    record FieldStore(int valId, PrimitiveKind storedKind, IrEffect.AliasClass alias) {}

    record FieldLoc(int recvValId, int classId, int memberIndex) {}

    record JavaFieldLoc(int recvValId, Field field) {}

    /** A dominating non-leaf expression eligible for lazy {@code $cseN} materialization. */
    static final class CseCandidate {
        final int valId;
        final HirExpr expr;
        final AeGraphExtractor.StmtScope scope;
        final int stmtIndex;
        final IrEffect effect;
        HirVar cseVar;

        CseCandidate(
                int valId,
                HirExpr expr,
                AeGraphExtractor.StmtScope scope,
                int stmtIndex,
                IrEffect effect) {
            this.valId = valId;
            this.expr = expr;
            this.scope = scope;
            this.stmtIndex = stmtIndex;
            this.effect = effect;
        }
    }

    final Shared shared;
    private final Map<ValueKey, Integer> valueTable;
    private final Map<Integer, Integer> varEpoch;
    private final Map<Integer, Integer> varToValId;
    private final Map<Integer, VarBinding> valIdToVar;
    private final Map<Integer, HirExpr.ThisRef> valIdToThis;
    private final Map<Integer, CseCandidate> cseCandidates;
    private final Map<FieldLoc, FieldStore> knownFields;
    private final Map<JavaFieldLoc, FieldStore> knownJavaFields;
    private int sharedEpoch;
    private final Map<Integer, Integer> uniqueEpochs;

    /** Tracks vars modified and write barriers performed inside this scope (for branch merges). */
    final Set<Integer> modifiedVars = new HashSet<>();

    final List<IrEffect> writeBarriers = new ArrayList<>();

    AeGraphState(Shared shared) {
        this.shared = shared;
        this.valueTable = new HashMap<>();
        this.varEpoch = new HashMap<>();
        this.varToValId = new HashMap<>();
        this.valIdToVar = new HashMap<>();
        this.valIdToThis = new HashMap<>();
        this.cseCandidates = new HashMap<>();
        this.knownFields = new HashMap<>();
        this.knownJavaFields = new HashMap<>();
        this.sharedEpoch = 0;
        this.uniqueEpochs = new HashMap<>();
    }

    private AeGraphState(AeGraphState parent) {
        this.shared = parent.shared;
        this.valueTable = new HashMap<>(parent.valueTable);
        this.varEpoch = new HashMap<>(parent.varEpoch);
        this.varToValId = new HashMap<>(parent.varToValId);
        this.valIdToVar = new HashMap<>(parent.valIdToVar);
        this.valIdToThis = new HashMap<>(parent.valIdToThis);
        this.cseCandidates = new HashMap<>(parent.cseCandidates);
        this.knownFields = new HashMap<>(parent.knownFields);
        this.knownJavaFields = new HashMap<>(parent.knownJavaFields);
        this.sharedEpoch = parent.sharedEpoch;
        this.impureEpoch = parent.impureEpoch;
        this.uniqueEpochs = new HashMap<>(parent.uniqueEpochs);
    }

    AeGraphState childScope() {
        return new AeGraphState(this);
    }

    // ---------------------------------------------------------------- value numbering

    Integer lookup(ValueKey key) {
        return valueTable.get(key);
    }

    int intern(ValueKey key) {
        Integer existing = valueTable.get(key);
        if (existing != null) {
            return existing;
        }
        int id = shared.freshValId();
        valueTable.put(key, id);
        return id;
    }

    int internFresh() {
        return shared.freshValId();
    }

    void bindKey(ValueKey key, int valId) {
        valueTable.put(key, valId);
    }

    // ---------------------------------------------------------------- variable epochs & bindings

    int varEpoch(int varId) {
        return varEpoch.getOrDefault(varId, 0);
    }

    int lookupVarValId(HirVar var) {
        Integer existing = varToValId.get(var.id());
        if (existing != null) {
            return existing;
        }
        int ep = varEpoch(var.id());
        int id = intern(new VarKey(var.id(), ep, var.primitiveKind()));
        varToValId.put(var.id(), id);
        valIdToVar.putIfAbsent(id, new VarBinding(var, ep, var.primitiveKind()));
        return id;
    }

    void bindVarToVal(HirVar var, int valId) {
        int ep = shared.freshVarEpoch();
        varEpoch.put(var.id(), ep);
        modifiedVars.add(var.id());
        varToValId.put(var.id(), valId);
        // Only register as preferred dominating VarRead if no earlier VarRead already holds valId.
        VarBinding cur = valIdToVar.get(valId);
        if (cur == null || !isBindingLive(cur)) {
            valIdToVar.put(valId, new VarBinding(var, ep, var.primitiveKind()));
        }
    }

    void bumpVar(int varId) {
        varEpoch.put(varId, shared.freshVarEpoch());
        varToValId.remove(varId);
        modifiedVars.add(varId);
    }

    void bumpVars(Set<Integer> varIds) {
        for (int id : varIds) {
            bumpVar(id);
        }
    }

    boolean isBindingLive(VarBinding binding) {
        return binding != null && varEpoch(binding.var().id()) == binding.epoch();
    }

    VarBinding dominatingVarFor(int valId, PrimitiveKind wantKind) {
        VarBinding b = valIdToVar.get(valId);
        if (b != null && b.kind() == wantKind && isBindingLive(b)) {
            return b;
        }
        return null;
    }

    void registerThisRef(int valId, HirExpr.ThisRef thisRef) {
        valIdToThis.putIfAbsent(valId, thisRef);
    }

    HirExpr.ThisRef dominatingThisFor(int valId) {
        return valIdToThis.get(valId);
    }

    // ---------------------------------------------------------------- CSE candidates

    CseCandidate cseCandidateFor(int valId) {
        return cseCandidates.get(valId);
    }

    void registerCseCandidate(CseCandidate cand) {
        cseCandidates.putIfAbsent(cand.valId, cand);
    }

    /** Removes all CSE candidates bound to a finalized {@link AeGraphExtractor.StmtScope}. */
    void removeCseCandidatesForScope(AeGraphExtractor.StmtScope scope) {
        cseCandidates.entrySet().removeIf(e -> e.getValue().scope == scope);
    }

    // ---------------------------------------------------------------- memory epochs & forwarding

    int memEpochFor(IrEffect.AliasClass alias) {
        if (alias instanceof IrEffect.AliasClass.Frozen
                || alias instanceof IrEffect.AliasClass.None) {
            return 0;
        }
        if (alias instanceof IrEffect.AliasClass.UniqueParam u) {
            return uniqueEpochs.getOrDefault(u.paramIndex(), 0);
        }
        return sharedEpoch;
    }

    void applyMemoryBarrier(IrEffect effect) {
        if (effect == null
                || effect.kind() == IrEffect.Kind.PURE
                || effect.kind() == IrEffect.Kind.READ) {
            return;
        }
        if (effect.kind() == IrEffect.Kind.WRITE
                && (effect.alias() instanceof IrEffect.AliasClass.None
                        || effect.alias() instanceof IrEffect.AliasClass.Frozen)) {
            return;
        }
        writeBarriers.add(effect);
        if (effect.kind() == IrEffect.Kind.IMPURE) {
            sharedEpoch = shared.freshMemEpoch();
            for (Integer idx : new ArrayList<>(uniqueEpochs.keySet())) {
                uniqueEpochs.put(idx, shared.freshMemEpoch());
            }
            // Also invalidate any future unique param indices by setting a baseline?
            // Simpler: bump all currently known unique indices AND clear all non-frozen stores;
            // to ensure even a unique param not yet in uniqueEpochs gets a fresh epoch, store
            // a global impure watermark? Handled cleanly below via impureEpoch!
            impureEpoch = shared.freshMemEpoch();
        } else if (effect.alias() instanceof IrEffect.AliasClass.Shared) {
            sharedEpoch = shared.freshMemEpoch();
        } else if (effect.alias() instanceof IrEffect.AliasClass.UniqueParam u) {
            uniqueEpochs.put(u.paramIndex(), shared.freshMemEpoch());
        }
        knownFields.entrySet().removeIf(e -> effect.isMemoryBarrierFor(e.getValue().alias()));
        knownJavaFields.entrySet().removeIf(e -> effect.isMemoryBarrierFor(e.getValue().alias()));
    }

    private int impureEpoch = 0;

    int effectiveMemEpochFor(IrEffect.AliasClass alias) {
        if (alias instanceof IrEffect.AliasClass.Frozen
                || alias instanceof IrEffect.AliasClass.None) {
            return 0;
        }
        if (alias instanceof IrEffect.AliasClass.UniqueParam u) {
            int ue = uniqueEpochs.getOrDefault(u.paramIndex(), 0);
            return Math.max(ue, impureEpoch);
        }
        return Math.max(sharedEpoch, impureEpoch);
    }

    void recordFieldStore(FieldLoc loc, FieldStore store) {
        knownFields.put(loc, store);
    }

    FieldStore lookupFieldStore(FieldLoc loc) {
        return knownFields.get(loc);
    }

    void recordJavaFieldStore(JavaFieldLoc loc, FieldStore store) {
        knownJavaFields.put(loc, store);
    }

    FieldStore lookupJavaFieldStore(JavaFieldLoc loc) {
        return knownJavaFields.get(loc);
    }

    /** Absorbs variable modifications and memory write barriers from a conditional child scope. */
    void absorbBranchEffects(AeGraphState child) {
        bumpVars(child.modifiedVars);
        for (IrEffect barrier : child.writeBarriers) {
            applyMemoryBarrier(barrier);
        }
    }

    // ---------------------------------------------------------------- value keys

    sealed interface ValueKey {}

    private enum NullMarker {
        INSTANCE
    }

    private record DoubleBits(long bits) {}

    private record FloatBits(int bits) {}

    static Object normalizeConst(Object v) {
        if (v == null) return NullMarker.INSTANCE;
        if (v instanceof Double d) return new DoubleBits(Double.doubleToRawLongBits(d));
        if (v instanceof Float f) return new FloatBits(Float.floatToRawIntBits(f));
        return v;
    }

    record ConstKey(PrimitiveKind kind, Object normalizedVal) implements ValueKey {}

    record VarKey(int varId, int epoch, PrimitiveKind kind) implements ValueKey {}

    record ThisKey(int varId, int classId) implements ValueKey {}

    record UnaryKey(HirExpr.UnaryOp op, PrimitiveKind kind, int operandValId) implements ValueKey {}

    record BinaryKey(
            HirExpr.BinaryOp op,
            boolean stringConcat,
            PrimitiveKind kind,
            int leftValId,
            int rightValId)
            implements ValueKey {}

    record ConvertKey(PrimitiveKind targetKind, int operandValId) implements ValueKey {}

    record WidenKey(int rank, PrimitiveKind kind, int operandValId) implements ValueKey {}

    record ArrayLenKey(int arrayValId) implements ValueKey {}

    record FieldKey(
            int recvValId,
            int classId,
            String externalClassJvmName,
            int memberIndex,
            String memberName,
            PrimitiveKind kind,
            int memEpoch)
            implements ValueKey {}

    record JavaFieldKey(int recvValId, Field field, PrimitiveKind kind, int memEpoch)
            implements ValueKey {}

    record ModuleExportKey(int recvValId, int exportIndex, String name) implements ValueKey {}

    record PureNameReadKey(String name, PrimitiveKind kind) implements ValueKey {}

    record NameReadKey(String name, PrimitiveKind kind, int memEpoch) implements ValueKey {}

    record CallBabbleKey(int fnId, String externalKey, PrimitiveKind kind, List<Integer> argValIds)
            implements ValueKey {}

    record CallJavaKey(int recvValId, Method method, PrimitiveKind kind, List<Integer> argValIds)
            implements ValueKey {}

    record CallMethodKey(int recvValId, int fnId, PrimitiveKind kind, List<Integer> argValIds)
            implements ValueKey {}
}
