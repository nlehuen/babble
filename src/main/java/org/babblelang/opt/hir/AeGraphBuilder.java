package org.babblelang.opt.hir;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.effects.Effect;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.hir.IrEffect;
import org.babblelang.opt.HirEffectAnalysis;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;

/**
 * Dominator-preorder AEGraph builder and simplifier over structured {@link HirExpr} trees ({@code
 * BIP039-D6}, {@code BIP039-I4}).
 *
 * <p>Unifies algebraic E-graph simplification, dominator-scoped GVN/CSE (with commutative operand
 * canonicalization, idempotent call collapsing, {@code ArrayOp.SIZE} CSE, and lazy {@code $cseN}
 * materialization), alias-partitioned store-to-load forwarding, and sound {@code Convert}/{@code
 * As}/{@code Narrow}/{@code NonNull}/{@code Widen} elimination.
 */
final class AeGraphBuilder {
    private final HirModule module;
    private final HirEffectAnalysis fx;
    private final int ownerMethodId;
    private final int[] nextVarId;
    private final int[] rewrites;

    private AeGraphExtractor.StmtScope currentScope;
    private int currentStmtIndex = -1;
    private boolean canHoistInCurrentStmt = false;
    private boolean currentStmtHasPriorEffect = false;

    AeGraphBuilder(
            HirModule module,
            HirEffectAnalysis fx,
            int ownerMethodId,
            int[] nextVarId,
            int[] rewrites) {
        this.module = module;
        this.fx = fx;
        this.ownerMethodId = ownerMethodId;
        this.nextVarId = nextVarId;
        this.rewrites = rewrites;
    }

    record Res(HirExpr expr, int valId) {}

    private List<HirExpr> rewriteStmtList(
            List<HirExpr> stmts,
            AeGraphState state,
            boolean wrapInBlockIfHoisted,
            List<HirVar> outExtraVars,
            int[] outLastValId) {
        AeGraphExtractor.StmtScope prevScope = currentScope;
        int prevIdx = currentStmtIndex;
        boolean prevHoist = canHoistInCurrentStmt;
        boolean prevEffect = currentStmtHasPriorEffect;

        AeGraphExtractor.StmtScope scope =
                new AeGraphExtractor.StmtScope(ownerMethodId, stmts.size());
        currentScope = scope;
        List<HirExpr> copy = null;
        int lastValId = -1;
        for (int i = 0; i < stmts.size(); i++) {
            currentStmtIndex = i;
            canHoistInCurrentStmt = true;
            currentStmtHasPriorEffect = false;
            HirExpr old = stmts.get(i);
            Res r = visit(old, state);
            lastValId = r.valId();
            if (r.expr() != old) {
                if (copy == null) copy = new ArrayList<>(stmts);
                copy.set(i, r.expr());
            }
        }
        if (outLastValId != null) {
            outLastValId[0] = lastValId;
        }
        currentScope = prevScope;
        currentStmtIndex = prevIdx;
        canHoistInCurrentStmt = prevHoist;
        currentStmtHasPriorEffect = prevEffect;

        List<HirExpr> base = copy != null ? copy : stmts;
        List<HirExpr> finalized =
                AeGraphExtractor.finalizeScope(base, scope, wrapInBlockIfHoisted, outExtraVars);
        state.removeCseCandidatesForScope(scope);
        return finalized;
    }

    List<HirExpr> rewriteBody(List<HirExpr> stmts, AeGraphState state, boolean wrapInBlock) {
        return rewriteStmtList(stmts, state, wrapInBlock, new ArrayList<>(), null);
    }

    Res visit(HirExpr expr, AeGraphState state) {
        if (expr == null) {
            return new Res(null, state.internFresh());
        }
        return switch (expr) {
            case HirExpr.Const c -> visitConst(c, state);
            case HirExpr.VarRead v -> visitVarRead(v, state);
            case HirExpr.ThisRef t -> visitThisRef(t, state);
            case HirExpr.Def d -> visitDef(d, state);
            case HirExpr.VarAssign a -> visitVarAssign(a, state);
            case HirExpr.ThisFieldInit a -> visitGeneric(a, state);
            case HirExpr.ThisFieldAssign a -> visitThisFieldAssign(a, state);
            case HirExpr.JavaFieldAssign a -> visitJavaFieldAssign(a, state);
            case HirExpr.ThisFieldGet g -> visitThisFieldGet(g, state);
            case HirExpr.JavaFieldGet g -> visitJavaFieldGet(g, state);
            case HirExpr.ArrayOp op -> visitArrayOp(op, state);
            case HirExpr.ModuleExportGet g -> visitModuleExportGet(g, state);
            case HirExpr.NameRead nr -> visitNameRead(nr, state);
            case HirExpr.Block b -> visitBlock(b, state);
            case HirExpr.If i -> visitIf(i, state);
            case HirExpr.Match m -> visitMatch(m, state);
            case HirExpr.While w -> visitWhile(w, state);
            case HirExpr.For f -> visitFor(f, state);
            case HirExpr.Try t -> visitTry(t, state);
            case HirExpr.Logic l -> visitLogic(l, state);
            case HirExpr.SafeCall sc -> visitSafeCall(sc, state);
            case HirExpr.Convert cv -> visitConvert(cv, state);
            case HirExpr.Widen w -> visitWiden(w, state);
            case HirExpr.As a -> visitAs(a, state);
            case HirExpr.Narrow n -> visitNarrow(n, state);
            case HirExpr.NonNull nn -> visitNonNull(nn, state);
            case HirExpr.Unary u -> visitUnary(u, state);
            case HirExpr.Binary b -> visitBinary(b, state);
            case HirExpr.JavaMethodCall jm -> visitJavaMethodCall(jm, state);
            case HirExpr.DirectBabbleCall dc -> visitDirectBabbleCall(dc, state);
            case HirExpr.DirectThisMethodCall mc -> visitDirectThisMethodCall(mc, state);
            default -> visitGeneric(expr, state);
        };
    }

    // ---------------------------------------------------------------- leaves & locals

    private Res visitConst(HirExpr.Const c, AeGraphState state) {
        HirExpr.Const cur = c;
        if (c.meta().wideningRank() >= 0) {
            HirExpr.Const widened =
                    HirConstFolder.applyWidening(c.value(), c.meta().kind(), c.meta());
            if (widened != null) {
                rewrites[0]++;
                cur = widened;
            }
        }
        int id =
                state.intern(
                        new AeGraphState.ConstKey(
                                cur.meta().kind(), AeGraphState.normalizeConst(cur.value())));
        if (cur.meta().wideningRank() < 0 && cur.meta().checkedType() == null) {
            state.shared.constExprs.putIfAbsent(id, cur);
        }
        if (cur.value() != null) {
            state.shared.nonNullVals.add(id);
        }
        return new Res(cur, id);
    }

    private Res visitVarRead(HirExpr.VarRead v, AeGraphState state) {
        if (v.var().isCaptured() || v.var().cell()) {
            noteEffect(fx.shallowEffectOf(v), state);
            return new Res(v, state.internFresh());
        }
        int id = state.lookupVarValId(v.var());
        if (v.meta().checkedType() == null
                && v.meta().wideningRank() < 0
                && (!v.var().mayBeUndefined() || v.var().definitelyDefined())) {
            HirExpr dom =
                    AeGraphExtractor.tryExtractDominating(
                            id, v.meta(), state, nextVarId, rewrites, false);
            if (dom != null && !AeGraphExtractor.sameVarRead(dom, v)) {
                rewrites[0]++;
                return new Res(dom, id);
            }
        }
        return new Res(v, id);
    }

    private Res visitThisRef(HirExpr.ThisRef t, AeGraphState state) {
        int id =
                state.intern(
                        new AeGraphState.ThisKey(
                                t.var() != null ? t.var().id() : -1, t.enclosingClassId()));
        state.registerThisRef(id, t);
        state.shared.nonNullVals.add(id);
        return new Res(t, id);
    }

    private Res visitDef(HirExpr.Def d, AeGraphState state) {
        boolean prevHoist = canHoistInCurrentStmt;
        Res rhs = d.value() != null ? visit(d.value(), state) : new Res(null, state.internFresh());
        canHoistInCurrentStmt = prevHoist;
        HirExpr.Def cur =
                rhs.expr() != d.value()
                        ? new HirExpr.Def(d.name(), d.target(), d.var(), rhs.expr(), d.meta())
                        : d;
        if (d.var() != null && !d.var().isCaptured() && !d.var().cell()) {
            boolean kindMatches =
                    rhs.expr() != null
                            && d.meta().wideningRank() < 0
                            && rhs.expr().meta().kind() == d.var().primitiveKind()
                            && rhs.expr().meta().wideningRank() < 0;
            if (kindMatches) {
                state.bindVarToVal(d.var(), rhs.valId());
            } else {
                state.bumpVar(d.var().id());
            }
        } else {
            noteEffect(fx.shallowEffectOf(cur), state);
        }
        return new Res(cur, rhs.valId());
    }

    private Res visitVarAssign(HirExpr.VarAssign a, AeGraphState state) {
        Res rhs = visit(a.value(), state);
        HirExpr.VarAssign cur =
                rhs.expr() != a.value()
                        ? new HirExpr.VarAssign(
                                a.var(),
                                a.operator(),
                                rhs.expr(),
                                a.targetText(),
                                a.valueText(),
                                a.meta())
                        : a;
        HirVar var = a.var();
        if (var.isCaptured() || var.cell()) {
            noteEffect(fx.shallowEffectOf(cur), state);
            return new Res(cur, state.internFresh());
        }
        if (a.operator() == null
                && a.meta().wideningRank() < 0
                && rhs.expr().meta().kind() == var.primitiveKind()
                && rhs.expr().meta().wideningRank() < 0) {
            state.bindVarToVal(var, rhs.valId());
            return new Res(cur, rhs.valId());
        }
        state.bumpVar(var.id());
        if (a.operator() != null) {
            currentStmtHasPriorEffect = true;
        }
        return new Res(cur, state.internFresh());
    }

    // ---------------------------------------------------------------- heap fields & alias RLE

    private Res visitThisFieldAssign(HirExpr.ThisFieldAssign a, AeGraphState state) {
        Res recv = visit(a.receiver(), state);
        Res val = visit(a.value(), state);
        HirExpr.ThisFieldAssign cur =
                (recv.expr() != a.receiver() || val.expr() != a.value())
                        ? new HirExpr.ThisFieldAssign(
                                recv.expr(),
                                a.classId(),
                                a.memberIndex(),
                                a.memberName(),
                                a.operator(),
                                val.expr(),
                                a.targetText(),
                                a.valueText(),
                                a.meta())
                        : a;
        IrEffect eff = fx.shallowEffectOf(cur);
        state.applyMemoryBarrier(eff);
        currentStmtHasPriorEffect = true;
        if (a.operator() == null
                && a.classId() >= 0
                && a.memberIndex() >= 0
                && a.meta().wideningRank() < 0) {
            IrEffect.AliasClass alias = fx.aliasOf(cur.receiver());
            state.recordFieldStore(
                    new AeGraphState.FieldLoc(recv.valId(), a.classId(), a.memberIndex()),
                    new AeGraphState.FieldStore(val.valId(), val.expr().meta().kind(), alias));
        }
        return new Res(cur, val.valId());
    }

    private Res visitJavaFieldAssign(HirExpr.JavaFieldAssign a, AeGraphState state) {
        Res recv =
                a.receiver() != null && (!a.target().isStatic() || !a.pureStaticReceiver())
                        ? visit(a.receiver(), state)
                        : new Res(a.receiver(), -1);
        Res val = visit(a.value(), state);
        HirExpr.JavaFieldAssign cur =
                (recv.expr() != a.receiver() || val.expr() != a.value())
                        ? new HirExpr.JavaFieldAssign(
                                recv.expr(),
                                a.receiverText(),
                                a.pureStaticReceiver(),
                                a.target(),
                                a.operator(),
                                val.expr(),
                                a.targetText(),
                                a.valueText(),
                                a.meta())
                        : a;
        IrEffect eff = fx.shallowEffectOf(cur);
        state.applyMemoryBarrier(eff);
        currentStmtHasPriorEffect = true;
        if (a.operator() == null && a.meta().wideningRank() < 0) {
            IrEffect.AliasClass alias =
                    a.target().isStatic() ? IrEffect.AliasClass.SHARED : fx.aliasOf(cur.receiver());
            int recvId = a.target().isStatic() ? -1 : recv.valId();
            state.recordJavaFieldStore(
                    new AeGraphState.JavaFieldLoc(recvId, a.target().field()),
                    new AeGraphState.FieldStore(val.valId(), val.expr().meta().kind(), alias));
        }
        return new Res(cur, val.valId());
    }

    private Res visitThisFieldGet(HirExpr.ThisFieldGet g, AeGraphState state) {
        Res recv = visit(g.receiver(), state);
        HirExpr.ThisFieldGet cur =
                recv.expr() != g.receiver()
                        ? new HirExpr.ThisFieldGet(
                                recv.expr(),
                                g.receiverText(),
                                g.classId(),
                                g.externalClassJvmName(),
                                g.memberIndex(),
                                g.memberName(),
                                g.fieldKind(),
                                g.immutableField(),
                                g.definitelyDefined(),
                                g.meta())
                        : g;
        Res forwarded =
                AeGraphExtractor.tryForwardThisField(cur, recv.valId(), state, nextVarId, rewrites);
        if (forwarded != null) {
            return forwarded;
        }
        IrEffect eff = fx.shallowEffectOf(cur);
        boolean hasKnownField =
                (cur.classId() >= 0 && cur.memberIndex() >= 0)
                        || (cur.externalClassJvmName() != null && cur.memberName() != null);
        boolean eligible =
                hasKnownField
                        && cur.meta().checkedType() == null
                        && cur.meta().wideningRank() < 0
                        && (eff.isPureFloatable()
                                || (eff.kind() == IrEffect.Kind.READ
                                        && eff.deterministic()
                                        && eff.terminates()));
        if (!eligible || state.shared.overBudget()) {
            noteEffect(eff, state);
            return new Res(cur, state.internFresh());
        }
        int epoch = state.effectiveMemEpochFor(eff.alias());
        AeGraphState.ValueKey key =
                new AeGraphState.FieldKey(
                        recv.valId(),
                        cur.classId(),
                        cur.externalClassJvmName(),
                        cur.memberIndex(),
                        cur.memberName(),
                        cur.meta().kind(),
                        epoch);
        return finishValueNumbered(cur, key, eff, state);
    }

    private Res visitJavaFieldGet(HirExpr.JavaFieldGet g, AeGraphState state) {
        Res recv =
                g.receiver() != null && (!g.target().isStatic() || !g.pureStaticReceiver())
                        ? visit(g.receiver(), state)
                        : new Res(g.receiver(), -1);
        HirExpr.JavaFieldGet cur =
                recv.expr() != g.receiver()
                        ? new HirExpr.JavaFieldGet(
                                recv.expr(),
                                g.receiverText(),
                                g.pureStaticReceiver(),
                                g.target(),
                                g.meta())
                        : g;
        int recvId = cur.target().isStatic() ? -1 : recv.valId();
        Res forwarded =
                AeGraphExtractor.tryForwardJavaField(cur, recvId, state, nextVarId, rewrites);
        if (forwarded != null) {
            return forwarded;
        }
        IrEffect eff = fx.shallowEffectOf(cur);
        boolean eligible =
                cur.meta().checkedType() == null
                        && cur.meta().wideningRank() < 0
                        && (eff.isPureFloatable()
                                || (eff.kind() == IrEffect.Kind.READ
                                        && eff.deterministic()
                                        && eff.terminates()));
        if (!eligible || state.shared.overBudget()) {
            noteEffect(eff, state);
            return new Res(cur, state.internFresh());
        }
        int epoch = state.effectiveMemEpochFor(eff.alias());
        AeGraphState.ValueKey key =
                new AeGraphState.JavaFieldKey(
                        recvId, cur.target().field(), cur.meta().kind(), epoch);
        return finishValueNumbered(cur, key, eff, state);
    }

    private Res visitArrayOp(HirExpr.ArrayOp op, AeGraphState state) {
        Res recv = visit(op.receiver(), state);
        List<HirExpr> newArgs = visitExprList(op.args(), state);
        HirExpr.ArrayOp cur =
                (recv.expr() != op.receiver() || newArgs != op.args())
                        ? new HirExpr.ArrayOp(
                                op.kind(),
                                recv.expr(),
                                op.receiverText(),
                                op.elemKind(),
                                op.elementType(),
                                op.arrayClass(),
                                op.componentClass(),
                                newArgs,
                                op.meta())
                        : op;
        IrEffect eff = fx.shallowEffectOf(cur);
        if (cur.kind() == LoweringTargets.ArrayOpKind.SIZE
                && cur.meta().checkedType() == null
                && cur.meta().wideningRank() < 0
                && !state.shared.overBudget()) {
            return finishValueNumbered(cur, new AeGraphState.ArrayLenKey(recv.valId()), eff, state);
        }
        noteEffect(eff, state);
        return new Res(cur, state.internFresh());
    }

    private Res visitModuleExportGet(HirExpr.ModuleExportGet g, AeGraphState state) {
        Res recv = visit(g.receiver(), state);
        HirExpr.ModuleExportGet cur =
                recv.expr() != g.receiver()
                        ? new HirExpr.ModuleExportGet(recv.expr(), g.target(), g.meta())
                        : g;
        IrEffect eff = fx.shallowEffectOf(cur);
        if (cur.meta().checkedType() == null
                && cur.meta().wideningRank() < 0
                && !state.shared.overBudget()) {
            AeGraphState.ValueKey key =
                    new AeGraphState.ModuleExportKey(
                            recv.valId(), cur.target().index(), cur.target().name());
            return finishValueNumbered(cur, key, eff, state);
        }
        noteEffect(eff, state);
        return new Res(cur, state.internFresh());
    }

    private Res visitNameRead(HirExpr.NameRead nr, AeGraphState state) {
        IrEffect eff = fx.shallowEffectOf(nr);
        if (nr.meta().checkedType() != null
                || nr.meta().wideningRank() >= 0
                || state.shared.overBudget()) {
            noteEffect(eff, state);
            return new Res(nr, state.internFresh());
        }
        AeGraphState.ValueKey key;
        if (nr.finalBinding()) {
            key = new AeGraphState.PureNameReadKey(nr.name(), nr.meta().kind());
        } else if (eff.isReadFloatable()) {
            key =
                    new AeGraphState.NameReadKey(
                            nr.name(), nr.meta().kind(), state.effectiveMemEpochFor(eff.alias()));
        } else {
            noteEffect(eff, state);
            return new Res(nr, state.internFresh());
        }
        return finishValueNumbered(nr, key, eff, state);
    }

    // ---------------------------------------------------------------- conversions & guards

    private Res visitConvert(HirExpr.Convert cv, AeGraphState state) {
        Res op = visit(cv.operand(), state);
        HirExpr.Convert cur =
                op.expr() != cv.operand()
                        ? new HirExpr.Convert(op.expr(), cv.targetKind(), cv.meta())
                        : cv;
        HirExpr folded = HirConstFolder.tryFoldConvert(cur);
        if (folded != null) {
            rewrites[0]++;
            return visit(folded, state);
        }
        Res simp = AeGraphExtractor.trySimplifyConvert(cur, op, state, fx, nextVarId, rewrites);
        if (simp != null) {
            return simp;
        }
        PrimitiveKind to = cur.targetKind();
        IrEffect eff = fx.shallowEffectOf(cur);
        AeGraphState.ValueKey key = new AeGraphState.ConvertKey(to, op.valId());
        Res res =
                (cur.meta().checkedType() == null
                                && cur.meta().wideningRank() < 0
                                && !state.shared.overBudget())
                        ? finishValueNumbered(cur, key, eff, state)
                        : new Res(cur, state.internFresh());
        if (to == PrimitiveKind.NONE && cur.operand().meta().kind().isPrimitive()) {
            state.shared.boxedOrigins.putIfAbsent(
                    res.valId(),
                    new AeGraphState.BoxedOrigin(cur.operand().meta().kind(), op.valId()));
            state.shared.nonNullVals.add(res.valId());
        }
        return res;
    }

    private Res visitWiden(HirExpr.Widen w, AeGraphState state) {
        Res op = visit(w.operand(), state);
        HirExpr.Widen cur =
                op.expr() != w.operand()
                        ? new HirExpr.Widen(op.expr(), w.wideningRank(), w.meta())
                        : w;
        HirExpr folded = HirConstFolder.tryFoldWiden(cur);
        if (folded != null) {
            rewrites[0]++;
            return visit(folded, state);
        }
        IrEffect eff = fx.shallowEffectOf(cur);
        if (cur.meta().checkedType() == null
                && cur.meta().wideningRank() < 0
                && !state.shared.overBudget()) {
            return finishValueNumbered(
                    cur,
                    new AeGraphState.WidenKey(cur.wideningRank(), cur.meta().kind(), op.valId()),
                    eff,
                    state);
        }
        noteEffect(eff, state);
        return new Res(cur, state.internFresh());
    }

    private Res visitAs(HirExpr.As a, AeGraphState state) {
        Res op = visit(a.left(), state);
        HirExpr.As cur =
                op.expr() != a.left()
                        ? new HirExpr.As(
                                op.expr(),
                                a.target(),
                                a.samTarget(),
                                a.targetDescriptor(),
                                a.meta())
                        : a;
        Res simp = AeGraphExtractor.trySimplifyAs(cur, op, state, nextVarId, rewrites);
        if (simp != null) {
            return simp;
        }
        noteEffect(fx.shallowEffectOf(cur), state);
        return new Res(cur, state.internFresh());
    }

    private Res visitNarrow(HirExpr.Narrow n, AeGraphState state) {
        Res op = visit(n.operand(), state);
        HirExpr.Narrow cur =
                op.expr() != n.operand()
                        ? new HirExpr.Narrow(
                                op.expr(), n.narrowKind(), n.guardId(), n.targetDesc(), n.meta())
                        : n;
        return AeGraphExtractor.handleNarrow(cur, op, state, nextVarId, rewrites);
    }

    private Res visitNonNull(HirExpr.NonNull nn, AeGraphState state) {
        Res op = visit(nn.operand(), state);
        HirExpr.NonNull cur =
                op.expr() != nn.operand()
                        ? new HirExpr.NonNull(op.expr(), nn.expectedType(), nn.meta())
                        : nn;
        noteEffect(fx.shallowEffectOf(cur), state);
        int id = state.internFresh();
        state.shared.nonNullVals.add(id);
        AeGraphExtractor.recordNonNullOrigin(cur, op, id, state);
        return new Res(cur, id);
    }

    // ---------------------------------------------------------------- unary, binary & calls

    private Res visitUnary(HirExpr.Unary u, AeGraphState state) {
        Res op = visit(u.operand(), state);
        HirExpr.Unary cur =
                op.expr() != u.operand()
                        ? new HirExpr.Unary(u.op(), op.expr(), u.operandText(), u.meta())
                        : u;
        HirExpr folded = HirConstFolder.tryFoldUnary(cur);
        if (folded != null) {
            rewrites[0]++;
            return visit(folded, state);
        }
        HirExpr alg = AeGraphExtractor.tryAlgebraicAndIdentity(cur, module);
        if (alg != null) {
            rewrites[0]++;
            return visit(alg, state);
        }
        IrEffect eff = fx.shallowEffectOf(cur);
        if (eff.isPureFloatable()
                && AeGraphExtractor.isCseReturnable(cur.meta())
                && cur.meta().checkedType() == null
                && cur.meta().wideningRank() < 0
                && !state.shared.overBudget()) {
            return finishValueNumbered(
                    cur,
                    new AeGraphState.UnaryKey(cur.op(), cur.meta().kind(), op.valId()),
                    eff,
                    state);
        }
        noteEffect(eff, state);
        return new Res(cur, state.internFresh());
    }

    private Res visitBinary(HirExpr.Binary b, AeGraphState state) {
        Res l = visit(b.left(), state);
        Res r = visit(b.right(), state);
        HirExpr.Binary cur =
                (l.expr() != b.left() || r.expr() != b.right())
                        ? new HirExpr.Binary(
                                b.op(), l.expr(), r.expr(), b.leftText(), b.rightText(), b.meta())
                        : b;
        HirExpr folded = HirConstFolder.tryFoldBinary(cur, module);
        if (folded != null) {
            rewrites[0]++;
            return visit(folded, state);
        }
        HirExpr alg = AeGraphExtractor.tryAlgebraicAndIdentity(cur, module);
        if (alg != null) {
            rewrites[0]++;
            return visit(alg, state);
        }
        IrEffect eff = fx.shallowEffectOf(cur);
        if (eff.isPureFloatable()
                && AeGraphExtractor.isCseReturnable(cur.meta())
                && cur.meta().checkedType() == null
                && cur.meta().wideningRank() < 0
                && !state.shared.overBudget()) {
            boolean strConcat =
                    cur.op() == HirExpr.BinaryOp.ADD
                            && cur.left().meta().stringType()
                            && cur.right().meta().stringType();
            int lid = l.valId();
            int rid = r.valId();
            if (AeGraphExtractor.isCommutative(cur.op(), strConcat) && lid > rid) {
                int t = lid;
                lid = rid;
                rid = t;
            }
            AeGraphState.ValueKey key =
                    new AeGraphState.BinaryKey(cur.op(), strConcat, cur.meta().kind(), lid, rid);
            return finishValueNumbered(cur, key, eff, state);
        }
        noteEffect(eff, state);
        return new Res(cur, state.internFresh());
    }

    private Res visitJavaMethodCall(HirExpr.JavaMethodCall jm, AeGraphState state) {
        Res recv =
                jm.receiver() != null && (!jm.target().isStatic() || !jm.pureStaticReceiver())
                        ? visit(jm.receiver(), state)
                        : new Res(jm.receiver(), -1);
        List<Integer> argIds = new ArrayList<>(jm.args().size());
        List<HirExpr> newArgs = null;
        for (int i = 0; i < jm.args().size(); i++) {
            HirExpr old = jm.args().get(i);
            Res ar = visit(old, state);
            argIds.add(ar.valId());
            if (ar.expr() != old) {
                if (newArgs == null) newArgs = new ArrayList<>(jm.args());
                newArgs.set(i, ar.expr());
            }
        }
        HirExpr.JavaMethodCall cur =
                (recv.expr() != jm.receiver() || newArgs != null)
                        ? new HirExpr.JavaMethodCall(
                                recv.expr(),
                                jm.receiverText(),
                                jm.selectorLine(),
                                jm.pureStaticReceiver(),
                                jm.target(),
                                newArgs != null ? newArgs : jm.args(),
                                jm.meta())
                        : jm;
        HirExpr folded = HirConstFolder.tryFoldJavaCall(cur, module);
        if (folded != null) {
            rewrites[0]++;
            return visit(folded, state);
        }
        HirExpr alg = AeGraphExtractor.tryAlgebraicAndIdentity(cur, module);
        if (alg != null) {
            rewrites[0]++;
            return visit(alg, state);
        }
        IrEffect eff = fx.shallowEffectOf(cur);
        Method m = cur.target().method();
        boolean idem = HirEGraphCodec.isIdempotentJavaEligible(cur, module);
        String idemSym =
                idem
                        ? ((cur.target().isStatic() ? "jstatic#" : "jinst#")
                                + m.getDeclaringClass().getName()
                                + "."
                                + m.getName()
                                + ":"
                                + m.getParameterCount())
                        : null;
        int idemArgId = idem ? (cur.target().isStatic() ? argIds.get(0) : recv.valId()) : -1;
        if (idem && eff.isPureFloatable()) {
            AeGraphState.IdemOrigin io = state.shared.idemOrigins.get(idemArgId);
            if (io != null && idemSym.equals(io.callSymbol())) {
                HirExpr dom =
                        AeGraphExtractor.tryExtractDominating(
                                idemArgId, cur.meta(), state, nextVarId, rewrites, false);
                if (dom != null) {
                    rewrites[0]++;
                    return new Res(dom, idemArgId);
                }
            }
        }
        if (eff.isPureFloatable()
                && AeGraphExtractor.isCseReturnable(cur.meta())
                && cur.meta().checkedType() == null
                && cur.meta().wideningRank() < 0
                && !state.shared.overBudget()) {
            boolean comm = cur.meta().isCommutative();
            List<Integer> canonArgs = AeGraphExtractor.canonicalArgs(argIds, comm);
            int recvId = cur.target().isStatic() ? -1 : recv.valId();
            AeGraphState.ValueKey key =
                    new AeGraphState.CallJavaKey(recvId, m, cur.meta().kind(), canonArgs);
            Res res = finishValueNumbered(cur, key, eff, state);
            if (idem) {
                state.shared.idemOrigins.putIfAbsent(
                        res.valId(), new AeGraphState.IdemOrigin(idemSym, idemArgId));
            }
            return res;
        }
        noteEffect(eff, state);
        return new Res(cur, state.internFresh());
    }

    private Res visitDirectBabbleCall(HirExpr.DirectBabbleCall dc, AeGraphState state) {
        HirExpr newCallee = visit(dc.callee(), state).expr();
        List<Integer> argIds = new ArrayList<>(dc.args().size());
        List<HirExpr> newArgs = null;
        for (int i = 0; i < dc.args().size(); i++) {
            HirExpr old = dc.args().get(i);
            Res ar = visit(old, state);
            argIds.add(ar.valId());
            if (ar.expr() != old) {
                if (newArgs == null) newArgs = new ArrayList<>(dc.args());
                newArgs.set(i, ar.expr());
            }
        }
        HirExpr.DirectBabbleCall cur =
                (newCallee != dc.callee() || newArgs != null)
                        ? new HirExpr.DirectBabbleCall(
                                newCallee,
                                dc.targetFunctionId(),
                                dc.externalTarget(),
                                newArgs != null ? newArgs : dc.args(),
                                dc.binding(),
                                dc.meta())
                        : dc;
        HirExpr alg = AeGraphExtractor.tryAlgebraicAndIdentity(cur, module);
        if (alg != null) {
            rewrites[0]++;
            return visit(alg, state);
        }
        IrEffect eff = fx.shallowEffectOf(cur);
        boolean idem = HirEGraphCodec.isIdempotentBabbleEligible(cur, module);
        String idemSym =
                idem
                        ? (cur.externalTarget() != null
                                ? "babble-ext#" + cur.externalTarget().canonicalKey()
                                : "babble#" + cur.targetFunctionId())
                        : null;
        if (idem && eff.isPureFloatable()) {
            int argId = argIds.get(0);
            AeGraphState.IdemOrigin io = state.shared.idemOrigins.get(argId);
            if (io != null && idemSym.equals(io.callSymbol())) {
                HirExpr dom =
                        AeGraphExtractor.tryExtractDominating(
                                argId, cur.meta(), state, nextVarId, rewrites, false);
                if (dom != null) {
                    rewrites[0]++;
                    return new Res(dom, argId);
                }
            }
        }
        if (eff.isPureFloatable()
                && AeGraphExtractor.isCseReturnable(cur.meta())
                && cur.meta().checkedType() == null
                && cur.meta().wideningRank() < 0
                && (cur.binding() == null || cur.binding().isIdentityPositional())
                && !state.shared.overBudget()) {
            boolean comm = AeGraphExtractor.isCommutativeBabbleCall(cur, module);
            List<Integer> canonArgs = AeGraphExtractor.canonicalArgs(argIds, comm);
            String extKey =
                    cur.externalTarget() != null ? cur.externalTarget().canonicalKey() : null;
            AeGraphState.ValueKey key =
                    new AeGraphState.CallBabbleKey(
                            cur.targetFunctionId(), extKey, cur.meta().kind(), canonArgs);
            Res res = finishValueNumbered(cur, key, eff, state);
            if (idem) {
                state.shared.idemOrigins.putIfAbsent(
                        res.valId(), new AeGraphState.IdemOrigin(idemSym, argIds.get(0)));
            }
            return res;
        }
        noteEffect(eff, state);
        return new Res(cur, state.internFresh());
    }

    private Res visitDirectThisMethodCall(HirExpr.DirectThisMethodCall mc, AeGraphState state) {
        Res recv = visit(mc.receiver(), state);
        List<Integer> argIds = new ArrayList<>(mc.args().size());
        List<HirCallArg> newArgs = null;
        for (int i = 0; i < mc.args().size(); i++) {
            HirCallArg old = mc.args().get(i);
            Res ar = visit(old.value(), state);
            argIds.add(ar.valId());
            if (ar.expr() != old.value()) {
                if (newArgs == null) newArgs = new ArrayList<>(mc.args());
                newArgs.set(i, old.withValue(ar.expr()));
            }
        }
        HirExpr.DirectThisMethodCall cur =
                (recv.expr() != mc.receiver() || newArgs != null)
                        ? new HirExpr.DirectThisMethodCall(
                                recv.expr(),
                                mc.receiverText(),
                                mc.classId(),
                                mc.externalClassJvmName(),
                                mc.classTypeId(),
                                mc.memberName(),
                                mc.overloadAltIndex(),
                                mc.targetFunctionId(),
                                mc.externalSignature(),
                                newArgs != null ? newArgs : mc.args(),
                                mc.binding(),
                                mc.callSite(),
                                mc.meta())
                        : mc;
        IrEffect eff = fx.shallowEffectOf(cur);
        if (eff.isPureFloatable()
                && AeGraphExtractor.isCseReturnable(cur.meta())
                && cur.targetFunctionId() >= 0
                && cur.meta().checkedType() == null
                && cur.meta().wideningRank() < 0
                && (cur.binding() == null || cur.binding().isIdentityPositional())
                && !state.shared.overBudget()) {
            HirFunction fn = module.function(cur.targetFunctionId());
            boolean comm =
                    cur.meta().isCommutative()
                            || (fn != null && fn.effects().has(Effect.COMMUTATIVE));
            AeGraphState.ValueKey key =
                    new AeGraphState.CallMethodKey(
                            recv.valId(),
                            cur.targetFunctionId(),
                            cur.meta().kind(),
                            AeGraphExtractor.canonicalArgs(argIds, comm));
            return finishValueNumbered(cur, key, eff, state);
        }
        noteEffect(eff, state);
        return new Res(cur, state.internFresh());
    }

    // ---------------------------------------------------------------- structured control flow

    private Res visitBlock(HirExpr.Block b, AeGraphState state) {
        if (b.isTransparentSingleExpr()) {
            return visit(b.statements().get(0), state);
        }
        // Scope block-local declarations so they never leak to sibling/following statements!
        AeGraphState blockState = state.childScope();
        List<HirVar> extraVars = new ArrayList<>();
        int[] lastVal = {-1};
        List<HirExpr> ns = rewriteStmtList(b.statements(), blockState, false, extraVars, lastVal);
        state.absorbBranchEffects(blockState);
        List<HirVar> allDecl = b.declaredVars();
        if (!extraVars.isEmpty()) {
            allDecl = new ArrayList<>(b.declaredVars().size() + extraVars.size());
            allDecl.addAll(b.declaredVars());
            allDecl.addAll(extraVars);
        }
        HirExpr.Block nb =
                (ns == b.statements() && allDecl == b.declaredVars())
                        ? b
                        : new HirExpr.Block(
                                ns,
                                b.opensNamespaceScope(),
                                b.declaredNames(),
                                allDecl,
                                b.innerWideningRank(),
                                b.meta());
        if (nb.isTransparentSingleExpr() && nb.statements().get(0) instanceof HirExpr.Const c) {
            rewrites[0]++;
            return visitConst(c, state);
        }
        boolean prefixPure = true;
        Set<Integer> localIds = null;
        if (!nb.declaredVars().isEmpty()) {
            localIds = new HashSet<>(nb.declaredVars().size());
            for (HirVar dv : nb.declaredVars()) {
                if (!dv.cell()) {
                    localIds.add(dv.id());
                }
            }
        }
        for (int i = 0; i < nb.statements().size() - 1; i++) {
            HirExpr st = nb.statements().get(i);
            if (st instanceof HirExpr.Def d
                    && d.var() != null
                    && localIds != null
                    && localIds.contains(d.var().id())
                    && (d.value() == null || fx.effectOf(d.value()).isPureFloatable())) {
                continue;
            }
            if (!fx.effectOf(st).isPureFloatable()) {
                prefixPure = false;
                break;
            }
        }
        int resId =
                (prefixPure
                                && lastVal[0] >= 0
                                && nb.innerWideningRank() < 0
                                && nb.meta().wideningRank() < 0
                                && !nb.statements().isEmpty()
                                && nb.statements().get(nb.statements().size() - 1).meta().kind()
                                        == nb.meta().kind())
                        ? lastVal[0]
                        : state.internFresh();
        return new Res(nb, resId);
    }

    private HirExpr visitBranchExpr(HirExpr branch, AeGraphState branchState) {
        if (branch == null) return null;
        if (branch instanceof HirExpr.Block b && !b.isTransparentSingleExpr()) {
            return visitBlock(b, branchState).expr();
        }
        List<HirExpr> wrapped = rewriteBody(List.of(branch), branchState, true);
        return wrapped.size() == 1 ? wrapped.get(0) : branch;
    }

    private Res visitIf(HirExpr.If i, AeGraphState state) {
        Res cond = visit(i.cond(), state);
        boolean prevHoist = canHoistInCurrentStmt;
        canHoistInCurrentStmt = false;

        AeGraphState thenState = state.childScope();
        HirExpr nt = visitBranchExpr(i.thenBranch(), thenState);
        AeGraphState elseState = state.childScope();
        HirExpr ne = visitBranchExpr(i.elseBranch(), elseState);

        canHoistInCurrentStmt = prevHoist;
        state.absorbBranchEffects(thenState);
        state.absorbBranchEffects(elseState);
        currentStmtHasPriorEffect = true;

        HirExpr.If rebuilt =
                (cond.expr() == i.cond() && nt == i.thenBranch() && ne == i.elseBranch())
                        ? i
                        : new HirExpr.If(cond.expr(), nt, ne, i.meta());
        HirExpr pruned = HirConstFolder.tryPruneIf(rebuilt);
        if (pruned != null) {
            rewrites[0]++;
            return new Res(pruned, state.internFresh());
        }
        return new Res(rebuilt, state.internFresh());
    }

    private Res visitMatch(HirExpr.Match m, AeGraphState state) {
        Res scrut = visit(m.scrutinee(), state);
        boolean prevHoist = canHoistInCurrentStmt;
        canHoistInCurrentStmt = false;

        boolean changed = scrut.expr() != m.scrutinee();
        List<HirExpr.MatchArm> narms = new ArrayList<>(m.arms().size());
        List<AeGraphState> branchStates = new ArrayList<>();
        for (HirExpr.MatchArm arm : m.arms()) {
            AeGraphState armState = state.childScope();
            HirPattern np =
                    HirRewriter.rewritePattern(
                            arm.pattern(), child -> visit(child, armState).expr());
            for (HirVar bv : arm.boundVars()) armState.bumpVar(bv.id());
            HirExpr ng = arm.guard() != null ? visit(arm.guard(), armState).expr() : null;
            HirExpr nb = visit(arm.body(), armState).expr();
            branchStates.add(armState);
            if (np != arm.pattern() || ng != arm.guard() || nb != arm.body()) {
                changed = true;
                narms.add(
                        new HirExpr.MatchArm(
                                np, arm.boundVars(), ng, nb, arm.bodyWideningRank(), arm.line()));
            } else {
                narms.add(arm);
            }
        }
        HirExpr.MatchElse ne = m.elseArm();
        if (ne != null) {
            AeGraphState elseState = state.childScope();
            if (ne.var() != null) elseState.bumpVar(ne.var().id());
            HirExpr neb = visit(ne.body(), elseState).expr();
            branchStates.add(elseState);
            if (neb != ne.body()) {
                changed = true;
                ne = new HirExpr.MatchElse(ne.var(), neb, ne.bodyWideningRank(), ne.line());
            }
        }
        canHoistInCurrentStmt = prevHoist;
        for (AeGraphState bs : branchStates) {
            state.absorbBranchEffects(bs);
        }
        currentStmtHasPriorEffect = true;

        HirExpr.Match rebuilt =
                changed
                        ? new HirExpr.Match(
                                scrut.expr(), m.scrutineeVar(), narms, ne, m.exhaustive(), m.meta())
                        : m;
        HirExpr unwrappedScrut = HirRewriter.unwrapTransparent(rebuilt.scrutinee());
        if (unwrappedScrut instanceof HirExpr.Const sc && rebuilt.meta().checkedType() == null) {
            HirExpr folded = HirConstFolder.tryFoldConstMatch(sc, rebuilt, module);
            if (folded != null) {
                rewrites[0]++;
                return new Res(folded, state.internFresh());
            }
        }
        return new Res(rebuilt, state.internFresh());
    }

    private Res visitWhile(HirExpr.While w, AeGraphState state) {
        Set<Integer> loopMod = new HashSet<>();
        HirPurity.collectModifiedVars(w.cond(), loopMod);
        HirPurity.collectModifiedVars(w.body(), loopMod);
        state.bumpVars(loopMod);
        AeGraphExtractor.applySubtreeWriteBarriers(w.cond(), fx, state);
        AeGraphExtractor.applySubtreeWriteBarriers(w.body(), fx, state);

        boolean prevHoist = canHoistInCurrentStmt;
        canHoistInCurrentStmt = false;
        AeGraphState loopState = state.childScope();
        HirExpr nc = visit(w.cond(), loopState).expr();
        HirExpr nb = visit(w.body(), loopState).expr();
        canHoistInCurrentStmt = prevHoist;
        state.absorbBranchEffects(loopState);
        currentStmtHasPriorEffect = true;

        HirExpr.While rebuilt =
                (nc == w.cond() && nb == w.body()) ? w : new HirExpr.While(nc, nb, w.meta());
        return new Res(rebuilt, state.internFresh());
    }

    private Res visitFor(HirExpr.For f, AeGraphState state) {
        HirExpr ni = f.iterable() != null ? visit(f.iterable(), state).expr() : null;
        HirExpr.CountedRangeLoop cr = f.countedRange();
        HirExpr.CountedRangeLoop ncr = cr;
        if (cr != null) {
            HirExpr ns = visit(cr.start(), state).expr();
            HirExpr ne = visit(cr.end(), state).expr();
            HirExpr nk = cr.step() != null ? visit(cr.step(), state).expr() : null;
            if (ns != cr.start() || ne != cr.end() || nk != cr.step()) {
                ncr =
                        new HirExpr.CountedRangeLoop(
                                ns, ne, nk, cr.inclusive(), cr.descending(), cr.elemKind());
            }
        }
        Set<Integer> loopMod = new HashSet<>();
        if (f.loopVar() != null) loopMod.add(f.loopVar().id());
        for (HirExpr s : f.body()) HirPurity.collectModifiedVars(s, loopMod);
        state.bumpVars(loopMod);
        if (f.countedRange() == null
                && f.target() != null
                && f.target().kind() != org.babblelang.analysis.LoweringTargets.ForKind.ARRAY
                && f.target().kind()
                        != org.babblelang.analysis.LoweringTargets.ForKind.COUNTED_RANGE
                && (!f.meta().effects().isHermetic()
                        || f.target().kind()
                                != org.babblelang.analysis.LoweringTargets.ForKind.JAVA_ITERABLE)) {
            noteEffect(IrEffect.IMPURE_THROW, state);
        }
        for (HirExpr s : f.body()) AeGraphExtractor.applySubtreeWriteBarriers(s, fx, state);

        AeGraphState loopState = state.childScope();
        List<HirExpr> nb = rewriteBody(f.body(), loopState, true);
        state.absorbBranchEffects(loopState);
        currentStmtHasPriorEffect = true;

        HirExpr.For rebuilt =
                (ni == f.iterable() && ncr == cr && nb == f.body())
                        ? f
                        : new HirExpr.For(
                                ni,
                                f.target(),
                                f.loopVar(),
                                f.bodyDeclaredVars(),
                                nb,
                                f.bodyWideningRank(),
                                f.elemSamTarget(),
                                ncr,
                                f.meta());
        return new Res(rebuilt, state.internFresh());
    }

    private Res visitTry(HirExpr.Try t, AeGraphState state) {
        // Exception-handler dominance safety: nothing computed inside tryBody dominates catches or
        // finallyBody!
        AeGraphState tryState = state.childScope();
        List<HirExpr> ntb = rewriteBody(t.tryBody(), tryState, true);

        Set<Integer> tryMod = new HashSet<>();
        for (HirExpr s : t.tryBody()) HirPurity.collectModifiedVars(s, tryMod);

        List<HirExpr.HirCatch> ncs = null;
        List<AeGraphState> catchStates = new ArrayList<>();
        for (int idx = 0; idx < t.catches().size(); idx++) {
            HirExpr.HirCatch cc = t.catches().get(idx);
            AeGraphState catchState = state.childScope();
            catchState.bumpVars(tryMod);
            for (IrEffect b : tryState.writeBarriers) catchState.applyMemoryBarrier(b);
            if (cc.var() != null) catchState.bumpVar(cc.var().id());
            List<HirExpr> ncb = rewriteBody(cc.body(), catchState, true);
            catchStates.add(catchState);
            if (ncb != cc.body()) {
                if (ncs == null) ncs = new ArrayList<>(t.catches());
                ncs.set(
                        idx,
                        new HirExpr.HirCatch(
                                cc.var(),
                                cc.target(),
                                ncb,
                                cc.declaredVars(),
                                cc.opensNamespaceScope(),
                                cc.declaredNames(),
                                cc.wideningRank(),
                                cc.line()));
            }
        }

        state.absorbBranchEffects(tryState);
        for (AeGraphState cs : catchStates) state.absorbBranchEffects(cs);

        List<HirExpr> nfb = t.finallyBody();
        if (t.hasFinally()) {
            AeGraphState finState = state.childScope();
            nfb = rewriteBody(t.finallyBody(), finState, true);
            state.absorbBranchEffects(finState);
        }
        currentStmtHasPriorEffect = true;

        HirExpr.Try rebuilt =
                (ntb == t.tryBody() && ncs == null && nfb == t.finallyBody())
                        ? t
                        : new HirExpr.Try(
                                ntb,
                                t.tryDeclaredVars(),
                                t.tryOpensNamespaceScope(),
                                t.tryDeclaredNames(),
                                t.tryWideningRank(),
                                ncs != null ? ncs : t.catches(),
                                nfb,
                                t.finallyDeclaredVars(),
                                t.finallyOpensNamespaceScope(),
                                t.finallyDeclaredNames(),
                                t.finallyNothrow(),
                                t.hasFinally(),
                                t.meta());
        return new Res(rebuilt, state.internFresh());
    }

    private Res visitLogic(HirExpr.Logic l, AeGraphState state) {
        Res left = visit(l.left(), state);
        boolean prevHoist = canHoistInCurrentStmt;
        canHoistInCurrentStmt = false;
        AeGraphState rightState = state.childScope();
        HirExpr nr = visit(l.right(), rightState).expr();
        canHoistInCurrentStmt = prevHoist;
        state.absorbBranchEffects(rightState);
        HirExpr.Logic cur =
                (left.expr() == l.left() && nr == l.right())
                        ? l
                        : new HirExpr.Logic(l.op(), left.expr(), nr, l.meta());
        HirExpr folded = HirConstFolder.tryFoldLogic(cur);
        if (folded != null) {
            rewrites[0]++;
            return visit(folded, state);
        }
        return new Res(cur, state.internFresh());
    }

    private Res visitSafeCall(HirExpr.SafeCall sc, AeGraphState state) {
        Res recv = visit(sc.receiver(), state);
        boolean prevHoist = canHoistInCurrentStmt;
        canHoistInCurrentStmt = false;
        AeGraphState argState = state.childScope();
        List<HirCallArg> newArgs = null;
        for (int i = 0; i < sc.args().size(); i++) {
            HirCallArg a = sc.args().get(i);
            HirExpr nv = visit(a.value(), argState).expr();
            if (nv != a.value()) {
                if (newArgs == null) newArgs = new ArrayList<>(sc.args());
                newArgs.set(i, a.withValue(nv));
            }
        }
        canHoistInCurrentStmt = prevHoist;
        state.absorbBranchEffects(argState);
        noteEffect(IrEffect.IMPURE_THROW, state);
        HirExpr.SafeCall rebuilt =
                (recv.expr() == sc.receiver() && newArgs == null)
                        ? sc
                        : new HirExpr.SafeCall(
                                recv.expr(),
                                sc.receiverText(),
                                sc.memberName(),
                                sc.selectorLine(),
                                sc.selectorCheckedType(),
                                newArgs != null ? newArgs : sc.args(),
                                sc.binding(),
                                sc.babbleSig(),
                                sc.callSite(),
                                sc.meta());
        return new Res(rebuilt, state.internFresh());
    }

    private Res visitGeneric(HirExpr expr, AeGraphState state) {
        HirExpr cur = HirRewriter.rewriteChildren(expr, child -> visit(child, state).expr());
        noteEffect(fx.shallowEffectOf(cur), state);
        return new Res(cur, state.internFresh());
    }

    // ---------------------------------------------------------------- shared helpers

    private Res finishValueNumbered(
            HirExpr cur, AeGraphState.ValueKey key, IrEffect eff, AeGraphState state) {
        IrEffect fullEff = fx.effectOf(cur);
        boolean subtreeSafe =
                fullEff.kind() == IrEffect.Kind.PURE || fullEff.kind() == IrEffect.Kind.READ;
        if (!subtreeSafe) {
            noteEffect(eff, state);
            return new Res(cur, state.internFresh());
        }
        Integer existing = state.lookup(key);
        if (existing != null) {
            HirExpr dom =
                    AeGraphExtractor.tryExtractDominating(
                            existing, cur.meta(), state, nextVarId, rewrites, true);
            if (dom != null) {
                rewrites[0]++;
                return new Res(dom, existing);
            }
            noteEffect(eff, state);
            return new Res(cur, existing);
        }
        int id = state.intern(key);
        boolean safeToHoist =
                canHoistInCurrentStmt
                        && currentScope != null
                        && currentStmtIndex >= 0
                        && AeGraphExtractor.isWorthCse(cur)
                        && !AeGraphExtractor.containsNarrow(cur)
                        && (fullEff.isPureFloatable() || !currentStmtHasPriorEffect);
        if (safeToHoist) {
            state.registerCseCandidate(
                    new AeGraphState.CseCandidate(
                            id, cur, currentScope, currentStmtIndex, fullEff));
        }
        noteEffect(eff, state);
        return new Res(cur, id);
    }

    private void noteEffect(IrEffect eff, AeGraphState state) {
        if (eff == null) return;
        state.applyMemoryBarrier(eff);
        if (!eff.isPureFloatable()) {
            currentStmtHasPriorEffect = true;
        }
    }

    private List<HirExpr> visitExprList(List<HirExpr> exprs, AeGraphState state) {
        List<HirExpr> copy = null;
        for (int i = 0; i < exprs.size(); i++) {
            HirExpr old = exprs.get(i);
            HirExpr nw = visit(old, state).expr();
            if (nw != old) {
                if (copy == null) copy = new ArrayList<>(exprs);
                copy.set(i, nw);
            }
        }
        return copy != null ? copy : exprs;
    }
}
