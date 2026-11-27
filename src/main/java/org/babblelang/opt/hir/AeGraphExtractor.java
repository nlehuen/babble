package org.babblelang.opt.hir;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirRewriter;

/**
 * Dominating-value extraction and lazy {@code $cseN} materializer for {@link HirAeGraphPass}
 * ({@code BIP039-D6}, {@code BIP039-P4}).
 *
 * <p>Prefers zero-cost constants and already-live dominating {@link HirExpr.VarRead}s; only
 * allocates a fresh {@code $cseN} local and hoists {@code (def $cseN expr)} before the dominating
 * first occurrence's statement when a dominated second hit actually reuses the value.
 */
final class AeGraphExtractor {

    /** Tracks pending lazy {@code $cseN} hoists for one enclosing statement list. */
    static final class StmtScope {
        final int ownerMethodId;
        final List<List<HoistEntry>> hoistsByStmt;
        final IdentityHashMap<HirExpr, HirVar> firstOccReplacements = new IdentityHashMap<>();
        final List<HirVar> allHoistedVars = new ArrayList<>();

        StmtScope(int ownerMethodId, int stmtCount) {
            this.ownerMethodId = ownerMethodId;
            this.hoistsByStmt = new ArrayList<>(stmtCount);
            for (int i = 0; i < stmtCount; i++) {
                this.hoistsByStmt.add(null);
            }
        }

        void addHoist(int stmtIndex, HirVar var, HirExpr origExpr) {
            List<HoistEntry> list = hoistsByStmt.get(stmtIndex);
            if (list == null) {
                list = new ArrayList<>();
                hoistsByStmt.set(stmtIndex, list);
            }
            list.add(new HoistEntry(var, origExpr));
            allHoistedVars.add(var);
            firstOccReplacements.put(origExpr, var);
        }

        boolean hasHoists() {
            return !allHoistedVars.isEmpty();
        }
    }

    record HoistEntry(HirVar var, HirExpr origExpr) {}

    private AeGraphExtractor() {}

    /**
     * Attempts to extract a dominating zero-cost or already-computed HIR expression for {@code
     * valId} matching {@code wantMeta}. Allocates a lazy {@code $cseN} binding only if a dominating
     * {@link AeGraphState.CseCandidate} exists and no cheaper constant/var is available.
     */
    static HirExpr tryExtractDominating(
            int valId,
            ExprMeta wantMeta,
            AeGraphState state,
            int[] nextVarId,
            int[] rewrites,
            boolean allowCseMaterialize) {
        if (wantMeta.checkedType() != null || wantMeta.wideningRank() >= 0) {
            return null;
        }
        PrimitiveKind wantKind = wantMeta.kind();

        // 1. Constant (cost 0)
        HirExpr.Const c = state.shared.constExprs.get(valId);
        if (c != null && c.meta().kind() == wantKind && c.meta().wideningRank() < 0) {
            return new HirExpr.Const(c.value(), wantMeta);
        }

        // 2. Dominating ThisRef
        HirExpr.ThisRef thisRef = state.dominatingThisFor(valId);
        if (thisRef != null && wantKind == PrimitiveKind.NONE) {
            return new HirExpr.ThisRef(thisRef.var(), thisRef.enclosingClassId(), wantMeta);
        }

        // 3. Dominating live VarRead of matching kind
        AeGraphState.VarBinding binding = state.dominatingVarFor(valId, wantKind);
        if (binding != null
                && !binding.var().isCaptured()
                && !binding.var().cell()
                && (!binding.var().mayBeUndefined() || binding.var().definitelyDefined())) {
            return new HirExpr.VarRead(binding.var(), wantMeta);
        }

        // 4. Lazy $cseN materialization on dominated second hit
        if (allowCseMaterialize) {
            AeGraphState.CseCandidate cand = state.cseCandidateFor(valId);
            if (cand != null
                    && cand.expr.meta().kind() == wantKind
                    && cand.expr.meta().wideningRank() < 0
                    && cand.expr.meta().checkedType() == null
                    && cand.scope != null) {
                if (cand.cseVar == null) {
                    int id = nextVarId[0]++;
                    int idx = state.shared.nextCseIndex++;
                    cand.cseVar =
                            new HirVar(
                                    id,
                                    "$cse" + idx,
                                    Resolution.Variable.Kind.DEFINITION,
                                    cand.scope.ownerMethodId,
                                    wantKind,
                                    false,
                                    true,
                                    false,
                                    true);
                    cand.scope.addHoist(cand.stmtIndex, cand.cseVar, cand.expr);
                    rewrites[0]++;
                }
                return new HirExpr.VarRead(cand.cseVar, wantMeta);
            }
        }
        return null;
    }

    /**
     * Applies any materialized {@code $cseN} definitions to {@code stmts}: rewrites each first
     * occurrence to {@code VarRead($cseN)} and splices {@code Def($cseN, origExpr)} immediately
     * before its host statement.
     *
     * @param wrapInBlockIfHoisted when {@code true} (for function/catch/for/try bodies that cannot
     *     add directly to {@code Block.declaredVars}), wraps the spliced list starting at the first
     *     hoist into a {@link HirExpr.Block} owning {@code scope.allHoistedVars}.
     */
    static List<HirExpr> finalizeScope(
            List<HirExpr> stmts,
            StmtScope scope,
            boolean wrapInBlockIfHoisted,
            List<HirVar> outExtraDeclaredVars) {
        if (!scope.hasHoists()) {
            return stmts;
        }
        int firstHoistIdx = -1;
        for (int i = 0; i < stmts.size(); i++) {
            if (scope.hoistsByStmt.get(i) != null) {
                firstHoistIdx = i;
                break;
            }
        }
        if (firstHoistIdx < 0) {
            return stmts;
        }

        List<HirExpr> spliced = new ArrayList<>(stmts.size() + scope.allHoistedVars.size());
        if (!wrapInBlockIfHoisted) {
            outExtraDeclaredVars.addAll(scope.allHoistedVars);
            for (int i = 0; i < stmts.size(); i++) {
                appendHoistsAndStmt(spliced, stmts.get(i), scope.hoistsByStmt.get(i), scope);
            }
            return spliced;
        }

        // Prefix statements before the first hoist remain untouched in the outer list.
        for (int i = 0; i < firstHoistIdx; i++) {
            spliced.add(stmts.get(i));
        }
        List<HirExpr> tail =
                new ArrayList<>(stmts.size() - firstHoistIdx + scope.allHoistedVars.size());
        for (int i = firstHoistIdx; i < stmts.size(); i++) {
            appendHoistsAndStmt(tail, stmts.get(i), scope.hoistsByStmt.get(i), scope);
        }
        HirExpr last = stmts.get(stmts.size() - 1);
        ExprMeta blockMeta = last.meta().withWideningRank(-1);
        spliced.add(
                new HirExpr.Block(
                        tail,
                        false,
                        new String[0],
                        List.copyOf(scope.allHoistedVars),
                        -1,
                        blockMeta));
        return spliced;
    }

    private static void appendHoistsAndStmt(
            List<HirExpr> out, HirExpr stmt, List<HoistEntry> hoists, StmtScope scope) {
        if (hoists != null) {
            for (HoistEntry h : hoists) {
                // Rewrite nested earlier hoists inside h.origExpr() if any!
                HirExpr rhs =
                        replaceFirstOccurrences(
                                h.origExpr(), scope.firstOccReplacements, h.origExpr());
                out.add(
                        new HirExpr.Def(
                                h.var().name(),
                                new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1),
                                h.var(),
                                rhs,
                                rhs.meta().withWideningRank(-1)));
            }
        }
        out.add(replaceFirstOccurrences(stmt, scope.firstOccReplacements, null));
    }

    private static HirExpr replaceFirstOccurrences(
            HirExpr expr, Map<HirExpr, HirVar> map, HirExpr skipRoot) {
        if (expr == null || map.isEmpty()) {
            return expr;
        }
        if (expr != skipRoot) {
            HirVar replacement = map.get(expr);
            if (replacement != null) {
                return new HirExpr.VarRead(replacement, expr.meta());
            }
        }
        return HirRewriter.rewriteChildren(
                expr, child -> replaceFirstOccurrences(child, map, null));
    }

    static boolean isSafeBoxedOriginLive(AeGraphState.UnboxedOrigin uo, AeGraphState state) {
        HirExpr e = uo.safeBoxedExpr();
        HirExpr inner = e instanceof HirExpr.Narrow n ? n.operand() : e;
        if (inner instanceof HirExpr.VarRead vr) {
            AeGraphState.VarBinding b =
                    state.dominatingVarFor(uo.sourceValId(), PrimitiveKind.NONE);
            return b != null && b.var().id() == vr.var().id();
        }
        return false;
    }

    static boolean isWorthCse(HirExpr expr) {
        return !(expr instanceof HirExpr.Const
                || expr instanceof HirExpr.VarRead
                || expr instanceof HirExpr.ThisRef);
    }

    /**
     * Returns {@code true} when a pure call's return value is referentially transparent for CSE
     * (primitive, string, frozen, or deeply immutable — never a freshly allocated mutable object or
     * stateful closure).
     */
    static boolean isCseReturnable(ExprMeta meta) {
        return meta.kind().isPrimitive()
                || meta.deeplyImmutable()
                || meta.frozen()
                || meta.stringType();
    }

    static boolean containsNarrow(HirExpr expr) {
        if (expr == null) return false;
        if (expr instanceof HirExpr.Narrow) return true;
        boolean[] found = {false};
        HirRewriter.rewriteChildren(
                expr,
                c -> {
                    if (!found[0] && containsNarrow(c)) found[0] = true;
                    return c;
                });
        return found[0];
    }

    static boolean isCommutative(HirExpr.BinaryOp op, boolean strConcat) {
        if (strConcat) return false;
        return op == HirExpr.BinaryOp.ADD
                || op == HirExpr.BinaryOp.MUL
                || op == HirExpr.BinaryOp.EQ
                || op == HirExpr.BinaryOp.NEQ;
    }

    static List<Integer> canonicalArgs(List<Integer> argIds, boolean commutative) {
        if (commutative && argIds.size() == 2) {
            int a = argIds.get(0);
            int b = argIds.get(1);
            return a <= b ? List.of(a, b) : List.of(b, a);
        }
        return List.copyOf(argIds);
    }

    static String exactPrimitiveDescriptor(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> "byte|byte|";
            case SHORT -> "short|short|";
            case INT -> "int|int|";
            case LONG -> "long|long|";
            case FLOAT -> "float|float|";
            case DOUBLE -> "double|double|";
            case BOOL -> "bool|bool|";
            case CHAR -> "char|char|";
            case NONE -> "";
        };
    }

    static boolean matchesExactPrimitiveNarrow(PrimitiveKind expected, String desc) {
        if (desc == null) return false;
        if (exactPrimitiveDescriptor(expected).equals(desc)) return true;
        return switch (expected) {
            case BYTE -> "byte".equals(desc);
            case SHORT -> "short".equals(desc);
            case INT -> "int".equals(desc);
            case LONG -> "long".equals(desc);
            case FLOAT -> "float".equals(desc);
            case DOUBLE -> "double".equals(desc);
            case BOOL -> "bool".equals(desc);
            case CHAR -> "char".equals(desc);
            case NONE -> false;
        };
    }

    /**
     * Simplifies {@code unbox(box(a)) -> a} and sound {@code box(unbox(a)) -> a} across statements.
     */
    static AeGraphBuilder.Res trySimplifyConvert(
            HirExpr.Convert cur,
            AeGraphBuilder.Res op,
            AeGraphState state,
            org.babblelang.opt.HirEffectAnalysis fx,
            int[] nextVarId,
            int[] rewrites) {
        PrimitiveKind to = cur.targetKind();
        if (to.isPrimitive() && cur.meta().checkedType() == null && cur.meta().wideningRank() < 0) {
            AeGraphState.BoxedOrigin bo = state.shared.boxedOrigins.get(op.valId());
            if (bo != null && bo.primKind() == to) {
                HirExpr dom =
                        tryExtractDominating(
                                bo.sourceValId(), cur.meta(), state, nextVarId, rewrites, false);
                if (dom != null) {
                    rewrites[0]++;
                    return new AeGraphBuilder.Res(dom, bo.sourceValId());
                }
            }
        }
        if (to == PrimitiveKind.NONE
                && cur.meta().checkedType() == null
                && cur.meta().wideningRank() < 0) {
            AeGraphState.UnboxedOrigin uo = state.shared.unboxedOrigins.get(op.valId());
            if (uo != null
                    && uo.primKind() == cur.operand().meta().kind()
                    && uo.safeBoxedExpr() != null
                    && isSafeBoxedOriginLive(uo, state)) {
                org.babblelang.ir.hir.IrEffect opEff = fx.effectOf(cur.operand());
                if (opEff.nothrow()
                        && opEff.terminates()
                        && (opEff.kind() == org.babblelang.ir.hir.IrEffect.Kind.PURE
                                || opEff.alias()
                                        instanceof
                                        org.babblelang.ir.hir.IrEffect.AliasClass.None)) {
                    rewrites[0]++;
                    return new AeGraphBuilder.Res(uo.safeBoxedExpr(), uo.sourceValId());
                }
            }
        }
        return null;
    }

    /** Simplifies {@code As(box(x), exactPrimDesc) -> x}. */
    static AeGraphBuilder.Res trySimplifyAs(
            HirExpr.As cur,
            AeGraphBuilder.Res op,
            AeGraphState state,
            int[] nextVarId,
            int[] rewrites) {
        if (cur.samTarget() == null
                && cur.targetDescriptor() != null
                && cur.meta().kind().isPrimitive()
                && cur.meta().wideningRank() < 0
                && exactPrimitiveDescriptor(cur.meta().kind()).equals(cur.targetDescriptor())) {
            AeGraphState.BoxedOrigin bo = state.shared.boxedOrigins.get(op.valId());
            if (bo != null && bo.primKind() == cur.meta().kind()) {
                ExprMeta cleanMeta = ExprMeta.of(cur.meta().line(), cur.meta().kind());
                HirExpr dom =
                        tryExtractDominating(
                                bo.sourceValId(), cleanMeta, state, nextVarId, rewrites, false);
                if (dom != null) {
                    rewrites[0]++;
                    return new AeGraphBuilder.Res(dom, bo.sourceValId());
                }
            }
        }
        return null;
    }

    /**
     * Simplifies {@code narrow(box(x)) -> x} and records safe boxed origins for {@code
     * box(unbox(narrow(x))) -> narrow(x):obj}.
     */
    static AeGraphBuilder.Res handleNarrow(
            HirExpr.Narrow cur,
            AeGraphBuilder.Res op,
            AeGraphState state,
            int[] nextVarId,
            int[] rewrites) {
        if (cur.meta().kind().isPrimitive() && cur.meta().wideningRank() < 0) {
            AeGraphState.BoxedOrigin bo = state.shared.boxedOrigins.get(op.valId());
            boolean kindOk =
                    bo != null
                            && bo.primKind() == cur.meta().kind()
                            && (cur.narrowKind() == HirExpr.NarrowKind.NON_NULL
                                    || matchesExactPrimitiveNarrow(
                                            cur.meta().kind(), cur.targetDesc()));
            if (kindOk) {
                ExprMeta cleanMeta = ExprMeta.of(cur.meta().line(), cur.meta().kind());
                HirExpr dom =
                        tryExtractDominating(
                                bo.sourceValId(), cleanMeta, state, nextVarId, rewrites, false);
                if (dom != null) {
                    rewrites[0]++;
                    return new AeGraphBuilder.Res(dom, bo.sourceValId());
                }
            }
            int id = state.internFresh();
            if (cur.operand() instanceof HirExpr.VarRead vr
                    && vr.var().primitiveKind() == PrimitiveKind.NONE
                    && !vr.var().isCaptured()
                    && !vr.var().cell()) {
                HirExpr boxedRead =
                        new HirExpr.VarRead(
                                vr.var(), ExprMeta.of(cur.meta().line(), PrimitiveKind.NONE));
                HirExpr boxedNarrow =
                        new HirExpr.Narrow(
                                boxedRead,
                                cur.narrowKind(),
                                cur.guardId(),
                                cur.targetDesc(),
                                ExprMeta.of(cur.meta().line(), PrimitiveKind.NONE));
                state.shared.unboxedOrigins.put(
                        id,
                        new AeGraphState.UnboxedOrigin(cur.meta().kind(), op.valId(), boxedNarrow));
            }
            return new AeGraphBuilder.Res(cur, id);
        }
        return new AeGraphBuilder.Res(cur, state.internFresh());
    }

    /** Records safe boxed origin for {@code NonNull} on a nullable primitive local variable. */
    static void recordNonNullOrigin(
            HirExpr.NonNull cur, AeGraphBuilder.Res op, int id, AeGraphState state) {
        if (cur.meta().kind().isPrimitive()
                && cur.operand() instanceof HirExpr.VarRead vr
                && vr.var().primitiveKind() == PrimitiveKind.NONE
                && !vr.var().isCaptured()
                && !vr.var().cell()) {
            HirExpr safeBoxed =
                    new HirExpr.VarRead(
                            vr.var(), ExprMeta.of(cur.meta().line(), PrimitiveKind.NONE));
            state.shared.unboxedOrigins.put(
                    id, new AeGraphState.UnboxedOrigin(cur.meta().kind(), op.valId(), safeBoxed));
        }
    }

    /** Attempts store-to-load forwarding on {@link HirExpr.ThisFieldGet}. */
    static AeGraphBuilder.Res tryForwardThisField(
            HirExpr.ThisFieldGet cur,
            int recvValId,
            AeGraphState state,
            int[] nextVarId,
            int[] rewrites) {
        if (cur.classId() < 0
                || cur.memberIndex() < 0
                || cur.meta().checkedType() != null
                || cur.meta().wideningRank() >= 0) {
            return null;
        }
        AeGraphState.FieldStore store =
                state.lookupFieldStore(
                        new AeGraphState.FieldLoc(recvValId, cur.classId(), cur.memberIndex()));
        if (store == null) {
            return null;
        }
        if (store.storedKind() == cur.meta().kind()) {
            HirExpr dom =
                    tryExtractDominating(
                            store.valId(), cur.meta(), state, nextVarId, rewrites, true);
            if (dom != null) {
                rewrites[0]++;
                return new AeGraphBuilder.Res(dom, store.valId());
            }
            return null;
        }
        if (store.storedKind().isPrimitive() && cur.meta().kind() == PrimitiveKind.NONE) {
            int boxedId =
                    state.intern(new AeGraphState.ConvertKey(PrimitiveKind.NONE, store.valId()));
            state.shared.boxedOrigins.putIfAbsent(
                    boxedId, new AeGraphState.BoxedOrigin(store.storedKind(), store.valId()));
            state.shared.nonNullVals.add(boxedId);
            HirExpr domBoxed =
                    tryExtractDominating(boxedId, cur.meta(), state, nextVarId, rewrites, false);
            if (domBoxed != null) {
                rewrites[0]++;
                return new AeGraphBuilder.Res(domBoxed, boxedId);
            }
            HirExpr domPrim =
                    tryExtractDominating(
                            store.valId(),
                            ExprMeta.of(cur.meta().line(), store.storedKind()),
                            state,
                            nextVarId,
                            rewrites,
                            false);
            if (domPrim != null) {
                rewrites[0]++;
                return new AeGraphBuilder.Res(
                        new HirExpr.Convert(domPrim, PrimitiveKind.NONE, cur.meta()), boxedId);
            }
            return new AeGraphBuilder.Res(cur, boxedId);
        }
        return null;
    }

    /** Attempts store-to-load forwarding on {@link HirExpr.JavaFieldGet}. */
    static AeGraphBuilder.Res tryForwardJavaField(
            HirExpr.JavaFieldGet cur,
            int recvId,
            AeGraphState state,
            int[] nextVarId,
            int[] rewrites) {
        if (cur.meta().checkedType() != null || cur.meta().wideningRank() >= 0) {
            return null;
        }
        AeGraphState.FieldStore store =
                state.lookupJavaFieldStore(
                        new AeGraphState.JavaFieldLoc(recvId, cur.target().field()));
        if (store != null && store.storedKind() == cur.meta().kind()) {
            HirExpr dom =
                    tryExtractDominating(
                            store.valId(), cur.meta(), state, nextVarId, rewrites, true);
            if (dom != null) {
                rewrites[0]++;
                return new AeGraphBuilder.Res(dom, store.valId());
            }
        }
        return null;
    }

    static HirExpr tryAlgebraicAndIdentity(HirExpr cur, org.babblelang.ir.hir.HirModule module) {
        if (HirEGraphCodec.isEGraphCandidateRoot(cur, module)) {
            HirExpr simplified = HirEGraphCodec.tryEGraphSimplify(cur, module);
            if (simplified != null && simplified != cur) {
                return simplified;
            }
        }
        return HirEGraphSimplifyPass.tryNonDroppingIdentity(cur, module);
    }

    static void applySubtreeWriteBarriers(
            HirExpr expr, org.babblelang.opt.HirEffectAnalysis fx, AeGraphState state) {
        if (expr == null) return;
        org.babblelang.ir.hir.IrEffect eff = fx.shallowEffectOf(expr);
        if (eff.kind() == org.babblelang.ir.hir.IrEffect.Kind.WRITE
                || eff.kind() == org.babblelang.ir.hir.IrEffect.Kind.IMPURE) {
            state.applyMemoryBarrier(eff);
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    applySubtreeWriteBarriers(child, fx, state);
                    return child;
                });
    }

    static boolean isCommutativeBabbleCall(
            HirExpr.DirectBabbleCall c, org.babblelang.ir.hir.HirModule module) {
        if (c.meta().isCommutative()) return true;
        if (c.externalTarget() != null
                && c.externalTarget().effects().has(org.babblelang.effects.Effect.COMMUTATIVE)) {
            return true;
        }
        if (c.targetFunctionId() >= 0) {
            org.babblelang.ir.hir.HirFunction fn = module.function(c.targetFunctionId());
            if (fn != null && fn.effects().has(org.babblelang.effects.Effect.COMMUTATIVE)) {
                return true;
            }
        }
        return false;
    }

    static boolean sameVarRead(HirExpr dom, HirExpr.VarRead v) {
        return dom instanceof HirExpr.VarRead dv && dv.var().id() == v.var().id();
    }
}
