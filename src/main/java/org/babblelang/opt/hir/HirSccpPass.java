package org.babblelang.opt.hir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;
import org.babblelang.runtime.Truth;

/**
 * Structured Sparse Conditional Constant Propagation ({@code HirSccpPass}) over typed {@link
 * HirModule} ({@code BIP039-D6}, {@code BIP039-I3}).
 *
 * <p>Propagates scalar constants across reassigned locals, conditional branches, {@code match}
 * expressions, loops (via monotonic fixed-point widening), and {@code try/catch/finally} blocks,
 * prunes statically unreachable branches/loops/statements, folds zero-divisor throws, and folds
 * constant-valued pure {@code if}/{@code match} joins.
 */
public final class HirSccpPass implements HirPass {
    private static final int MAX_LOOP_FIXPOINT_ITERS = 32;

    @Override
    public String name() {
        return "HirSccp";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] rewrites = {0};
        HirModule updated =
                HirRewriter.rewriteModule(
                        module,
                        (ownerId, fnId, stmts) -> new Runner(module, rewrites).runBody(stmts),
                        expr -> new Runner(module, rewrites).eval(expr, new Flow(), true).expr(),
                        module::nextVarId);
        return updated == module ? Result.unchanged(module) : new Result(updated, rewrites[0]);
    }

    // ---------------------------------------------------------------- lattice

    private enum LatState {
        TOP,
        CONST,
        BOTTOM
    }

    private record Lat(LatState state, Object value, PrimitiveKind kind) {
        static final Lat TOP = new Lat(LatState.TOP, null, PrimitiveKind.NONE);
        static final Lat BOTTOM = new Lat(LatState.BOTTOM, null, PrimitiveKind.NONE);

        static Lat ofConst(Object value, PrimitiveKind kind) {
            return new Lat(LatState.CONST, value, kind != null ? kind : PrimitiveKind.NONE);
        }

        boolean isConst() {
            return state == LatState.CONST;
        }

        static boolean sameConst(Lat a, Lat b) {
            if (a == b) return true;
            if (a == null || b == null || !a.isConst() || !b.isConst() || a.kind != b.kind) {
                return false;
            }
            if (a.value == b.value) return true;
            if (a.value == null || b.value == null) return false;
            if (a.value instanceof Double da && b.value instanceof Double db) {
                return Double.doubleToRawLongBits(da) == Double.doubleToRawLongBits(db);
            }
            if (a.value instanceof Float fa && b.value instanceof Float fb) {
                return Float.floatToRawIntBits(fa) == Float.floatToRawIntBits(fb);
            }
            return Objects.equals(a.value, b.value);
        }

        static Lat meet(Lat a, Lat b) {
            if (a == null || b == null) return BOTTOM;
            if (a.state == LatState.TOP) return b;
            if (b.state == LatState.TOP) return a;
            return sameConst(a, b) ? a : BOTTOM;
        }
    }

    /** Abstract flow state: absent map entries represent {@link Lat#BOTTOM} (non-constant). */
    private static final class Flow {
        Map<Integer, Lat> env;
        boolean reachable;

        Flow() {
            this(new HashMap<>(), true);
        }

        Flow(Map<Integer, Lat> env, boolean reachable) {
            this.env = env;
            this.reachable = reachable;
        }

        static Flow unreachable() {
            return new Flow(new HashMap<>(), false);
        }

        Flow copy() {
            return new Flow(new HashMap<>(env), reachable);
        }

        void copyFrom(Flow other) {
            this.env = new HashMap<>(other.env);
            this.reachable = other.reachable;
        }

        void putConst(int varId, Lat lat) {
            if (lat != null && lat.isConst()) {
                env.put(varId, lat);
            } else {
                env.remove(varId);
            }
        }

        void remove(int varId) {
            env.remove(varId);
        }

        void removeAll(Set<Integer> varIds) {
            for (int id : varIds) {
                env.remove(id);
            }
        }

        static Flow join(Flow a, Flow b) {
            if (!a.reachable && !b.reachable) return unreachable();
            if (!a.reachable) return b.copy();
            if (!b.reachable) return a.copy();
            Map<Integer, Lat> merged = new HashMap<>();
            for (Map.Entry<Integer, Lat> e : a.env.entrySet()) {
                Lat bv = b.env.get(e.getKey());
                if (bv != null && Lat.sameConst(e.getValue(), bv)) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            return new Flow(merged, true);
        }
    }

    private static final class LoopCtx {
        Flow continueFlow = Flow.unreachable();
        Flow breakFlow = Flow.unreachable();
        Lat breakVal = Lat.TOP;
    }

    private record Eval(HirExpr expr, Lat lat) {}

    private record StmtsEval(List<HirExpr> stmts, Lat lastLat) {}

    // ---------------------------------------------------------------- runner

    private static final class Runner {
        private final HirModule module;
        private final int[] rewrites;
        private final Deque<LoopCtx> loops = new ArrayDeque<>();

        Runner(HirModule module, int[] rewrites) {
            this.module = module;
            this.rewrites = rewrites;
        }

        List<HirExpr> runBody(List<HirExpr> stmts) {
            return evalStatements(stmts, new Flow(), true).stmts();
        }

        private StmtsEval evalStatements(List<HirExpr> stmts, Flow flow, boolean rewriting) {
            List<HirExpr> copy = null;
            Lat lastLat = Lat.ofConst(null, PrimitiveKind.NONE);
            int liveCount = stmts.size();
            for (int i = 0; i < stmts.size(); i++) {
                if (!flow.reachable) {
                    liveCount = i;
                    break;
                }
                HirExpr old = stmts.get(i);
                Eval res = eval(old, flow, rewriting);
                lastLat = res.lat();
                if (rewriting && res.expr() != old) {
                    if (copy == null) copy = new ArrayList<>(stmts);
                    copy.set(i, res.expr());
                }
            }
            if (rewriting && liveCount < stmts.size()) {
                List<HirExpr> src = copy != null ? copy : stmts;
                rewrites[0] += stmts.size() - liveCount;
                return new StmtsEval(new ArrayList<>(src.subList(0, liveCount)), lastLat);
            }
            return new StmtsEval(copy != null ? copy : stmts, lastLat);
        }

        Eval eval(HirExpr expr, Flow flow, boolean rewriting) {
            if (expr == null) {
                return new Eval(null, Lat.ofConst(null, PrimitiveKind.NONE));
            }
            return switch (expr) {
                case HirExpr.Const c -> evalConst(c, rewriting);
                case HirExpr.VarRead v -> evalVarRead(v, flow, rewriting);
                case HirExpr.Def d -> evalDef(d, flow, rewriting);
                case HirExpr.VarAssign a -> evalVarAssign(a, flow, rewriting);
                case HirExpr.Block b -> evalBlock(b, flow, rewriting);
                case HirExpr.If i -> evalIf(i, flow, rewriting);
                case HirExpr.Match m -> evalMatch(m, flow, rewriting);
                case HirExpr.While w -> evalWhile(w, flow, rewriting);
                case HirExpr.For f -> evalFor(f, flow, rewriting);
                case HirExpr.Try t -> evalTry(t, flow, rewriting);
                case HirExpr.Logic l -> evalLogic(l, flow, rewriting);
                case HirExpr.SafeCall sc -> evalSafeCall(sc, flow, rewriting);
                case HirExpr.Return r -> evalReturn(r, flow, rewriting);
                case HirExpr.Throw th -> evalThrow(th, flow, rewriting);
                case HirExpr.Break br -> evalBreak(br, flow, rewriting);
                case HirExpr.Continue co -> evalContinue(co, flow);
                case HirExpr.Narrow n -> evalNarrow(n, flow, rewriting);
                case HirExpr.Unary u -> evalUnary(u, flow, rewriting);
                case HirExpr.Binary b -> evalBinary(b, flow, rewriting);
                case HirExpr.Convert cv -> evalConvert(cv, flow, rewriting);
                case HirExpr.Widen w -> evalWiden(w, flow, rewriting);
                case HirExpr.JavaMethodCall jm -> evalJavaMethodCall(jm, flow, rewriting);
                default -> new Eval(evalChildren(expr, flow, rewriting), Lat.BOTTOM);
            };
        }

        private Eval evalConst(HirExpr.Const c, boolean rewriting) {
            if (c.meta().wideningRank() >= 0) {
                HirExpr.Const widened =
                        HirConstFolder.applyWidening(c.value(), c.meta().kind(), c.meta());
                if (widened != null) {
                    return commitFold(c, widened, rewriting);
                }
            }
            return new Eval(c, Lat.ofConst(c.value(), c.meta().kind()));
        }

        private Eval evalVarRead(HirExpr.VarRead v, Flow flow, boolean rewriting) {
            // Presence in flow.env guarantees a dominating initialization/assignment on every
            // reachable path, so VarRead folding is sound even for mutable locals.
            if (v.meta().checkedType() == null && !v.var().isCaptured() && !v.var().cell()) {
                Lat lat = flow.env.get(v.var().id());
                if (lat != null && lat.isConst() && lat.kind() == v.var().primitiveKind()) {
                    HirExpr.Const folded =
                            HirConstFolder.applyWidening(lat.value(), lat.kind(), v.meta());
                    if (folded != null) {
                        return commitFold(v, folded, rewriting);
                    }
                }
            }
            return new Eval(v, Lat.BOTTOM);
        }

        private Eval evalDef(HirExpr.Def d, Flow flow, boolean rewriting) {
            Eval rhs = eval(d.value(), flow, rewriting);
            HirExpr.Def cur =
                    (rewriting && rhs.expr() != d.value())
                            ? new HirExpr.Def(d.name(), d.target(), d.var(), rhs.expr(), d.meta())
                            : d;
            if (d.var() != null && !d.var().isCaptured() && !d.var().cell()) {
                boolean safeCheck =
                        d.meta().checkedType() == null || d.var().primitiveKind().isPrimitive();
                if (d.value() != null && rhs.lat().isConst() && safeCheck) {
                    HirExpr.Const coerced =
                            HirConstFolder.coerceConstForVar(
                                    synthConst(rhs.lat(), d.meta().line()),
                                    d.var(),
                                    d.meta().wideningRank());
                    flow.putConst(d.var().id(), latOfConst(coerced));
                } else {
                    flow.remove(d.var().id());
                }
            }
            return new Eval(cur, d.value() != null ? rhs.lat() : Lat.BOTTOM);
        }

        private Eval evalVarAssign(HirExpr.VarAssign a, Flow flow, boolean rewriting) {
            HirVar var = a.var();
            // Compound assignment evaluates the LHS value before evaluating RHS (e.g. x += (x = 10;
            // 2)).
            Lat oldLat =
                    (a.operator() != null
                                    && !"=".equals(a.operator())
                                    && !var.isCaptured()
                                    && !var.cell())
                            ? flow.env.get(var.id())
                            : null;
            Eval rhs = eval(a.value(), flow, rewriting);
            HirExpr.VarAssign cur =
                    (rewriting && rhs.expr() != a.value())
                            ? new HirExpr.VarAssign(
                                    var,
                                    a.operator(),
                                    rhs.expr(),
                                    a.targetText(),
                                    a.valueText(),
                                    a.meta())
                            : a;
            if (!flow.reachable) {
                return new Eval(cur, Lat.TOP);
            }
            if (var.isCaptured() || var.cell()) {
                return new Eval(cur, Lat.BOTTOM);
            }
            boolean safeCheck = a.meta().checkedType() == null || var.primitiveKind().isPrimitive();
            if (a.operator() == null || "=".equals(a.operator())) {
                if (rhs.lat().isConst() && safeCheck) {
                    HirExpr.Const coerced =
                            HirConstFolder.coerceConstForVar(
                                    synthConst(rhs.lat(), a.meta().line()),
                                    var,
                                    a.meta().wideningRank());
                    if (coerced != null) {
                        Lat stored = latOfConst(coerced);
                        flow.putConst(var.id(), stored);
                        return new Eval(cur, stored);
                    }
                }
                flow.remove(var.id());
                return new Eval(cur, Lat.BOTTOM);
            }
            HirExpr.BinaryOp bop = compoundBinaryOp(a.operator());
            if (bop != null
                    && oldLat != null
                    && oldLat.isConst()
                    && rhs.lat().isConst()
                    && safeCheck
                    && oldLat.kind() == var.primitiveKind()) {
                ExprMeta binMeta = ExprMeta.of(a.meta().line(), var.primitiveKind());
                HirExpr folded =
                        HirConstFolder.tryFoldBinary(
                                new HirExpr.Binary(
                                        bop,
                                        synthConst(oldLat, a.meta().line()),
                                        synthConst(rhs.lat(), a.meta().line()),
                                        "",
                                        "",
                                        binMeta),
                                module);
                if (folded instanceof HirExpr.Const fc) {
                    Lat stored = latOfConst(fc);
                    flow.putConst(var.id(), stored);
                    if (rewriting
                            && HirPurity.isPureNothrowTerminating(rhs.expr(), module)
                            && a.meta().wideningRank() < 0
                            && fc.meta().kind() == var.primitiveKind()) {
                        rewrites[0]++;
                        return new Eval(
                                new HirExpr.VarAssign(
                                        var, null, fc, a.targetText(), a.valueText(), a.meta()),
                                stored);
                    }
                    return new Eval(cur, stored);
                }
            }
            flow.remove(var.id());
            return new Eval(cur, Lat.BOTTOM);
        }

        private Eval evalBlock(HirExpr.Block b, Flow flow, boolean rewriting) {
            StmtsEval se = evalStatements(b.statements(), flow, rewriting);
            HirExpr.Block cur =
                    (rewriting && se.stmts() != b.statements())
                            ? new HirExpr.Block(
                                    se.stmts(),
                                    b.opensNamespaceScope(),
                                    b.declaredNames(),
                                    b.declaredVars(),
                                    b.innerWideningRank(),
                                    b.meta())
                            : b;
            Lat resLat = coerceResultLat(se.lastLat(), b.innerWideningRank(), b.meta());
            if (rewriting
                    && cur.isTransparentSingleExpr()
                    && cur.statements().get(0) instanceof HirExpr.Const c) {
                rewrites[0]++;
                return new Eval(c, latOfConst(c));
            }
            return new Eval(cur, resLat);
        }

        private Eval evalIf(HirExpr.If i, Flow flow, boolean rewriting) {
            Eval cond = eval(i.cond(), flow, rewriting);
            if (!flow.reachable) {
                return new Eval(
                        rewriting && cond.expr() != i.cond()
                                ? new HirExpr.If(
                                        cond.expr(), i.thenBranch(), i.elseBranch(), i.meta())
                                : i,
                        Lat.TOP);
            }
            if (cond.lat().isConst()) {
                boolean takeThen = Truth.test(cond.lat().value());
                HirExpr rawChosen = takeThen ? i.thenBranch() : i.elseBranch();
                Eval chosen =
                        rawChosen != null
                                ? eval(rawChosen, flow, rewriting)
                                : new Eval(null, Lat.ofConst(null, PrimitiveKind.NONE));
                Lat resLat = coerceResultLat(chosen.lat(), -1, i.meta());
                if (!rewriting) return new Eval(i, resLat);
                HirExpr adapted = HirConstFolder.adaptIfBranch(chosen.expr(), i.meta());
                if (adapted != null) {
                    if (cond.expr() instanceof HirExpr.Const
                            || HirPurity.isPureNothrowTerminating(cond.expr(), module)) {
                        rewrites[0]++;
                        return new Eval(adapted, resLat);
                    }
                    if (adapted.meta().kind() == i.meta().kind()
                            && adapted.meta().wideningRank() < 0
                            && i.meta().wideningRank() < 0) {
                        rewrites[0]++;
                        return new Eval(
                                new HirExpr.Block(
                                        List.of(cond.expr(), adapted),
                                        false,
                                        new String[0],
                                        List.of(),
                                        -1,
                                        i.meta()),
                                resLat);
                    }
                }
                HirExpr nt = takeThen ? chosen.expr() : i.thenBranch();
                HirExpr ne = takeThen ? i.elseBranch() : chosen.expr();
                HirExpr.If rebuilt =
                        (cond.expr() == i.cond() && nt == i.thenBranch() && ne == i.elseBranch())
                                ? i
                                : new HirExpr.If(cond.expr(), nt, ne, i.meta());
                return new Eval(rebuilt, resLat);
            }
            Flow thenFlow = flow.copy();
            Flow elseFlow = flow.copy();
            Eval thenRes = eval(i.thenBranch(), thenFlow, rewriting);
            Eval elseRes =
                    i.elseBranch() != null
                            ? eval(i.elseBranch(), elseFlow, rewriting)
                            : new Eval(null, Lat.ofConst(null, PrimitiveKind.NONE));
            flow.copyFrom(Flow.join(thenFlow, elseFlow));
            Lat thenLat =
                    thenFlow.reachable ? coerceResultLat(thenRes.lat(), -1, i.meta()) : Lat.TOP;
            Lat elseLat =
                    elseFlow.reachable ? coerceResultLat(elseRes.lat(), -1, i.meta()) : Lat.TOP;
            Lat resLat = Lat.meet(thenLat, elseLat);
            if (!rewriting) return new Eval(i, resLat);
            HirExpr.If rebuilt =
                    (cond.expr() == i.cond()
                                    && thenRes.expr() == i.thenBranch()
                                    && elseRes.expr() == i.elseBranch())
                            ? i
                            : new HirExpr.If(cond.expr(), thenRes.expr(), elseRes.expr(), i.meta());
            if (resLat.isConst()
                    && i.meta().checkedType() == null
                    && HirPurity.isPureNothrowTerminating(rebuilt, module)) {
                HirExpr.Const folded =
                        HirConstFolder.convertConstToKind(
                                resLat.value(), resLat.kind(), i.meta().kind(), i.meta());
                if (folded != null) {
                    rewrites[0]++;
                    return new Eval(folded, latOfConst(folded));
                }
            }
            return new Eval(rebuilt, resLat);
        }

        private Eval evalMatch(HirExpr.Match m, Flow flow, boolean rewriting) {
            Eval scrut = eval(m.scrutinee(), flow, rewriting);
            if (!flow.reachable) return new Eval(m, Lat.TOP);
            if (scrut.lat().isConst()
                    && m.meta().checkedType() == null
                    && scrut.expr() instanceof HirExpr.Const) {
                HirExpr folded =
                        HirConstFolder.tryFoldConstMatch(
                                synthConst(scrut.lat(), m.meta().line()), m, module);
                if (folded != null && !(folded instanceof HirExpr.Match)) {
                    Eval armRes = eval(folded, flow, rewriting);
                    if (rewriting) rewrites[0]++;
                    return new Eval(armRes.expr(), coerceResultLat(armRes.lat(), -1, m.meta()));
                }
            }
            boolean changed = rewriting && scrut.expr() != m.scrutinee();
            List<HirExpr.MatchArm> narms = new ArrayList<>(m.arms().size());
            Flow joined = Flow.unreachable();
            Lat resLat = Lat.TOP;
            for (HirExpr.MatchArm arm : m.arms()) {
                HirPattern np =
                        HirRewriter.rewritePattern(
                                arm.pattern(), child -> eval(child, flow.copy(), rewriting).expr());
                Flow armFlow = flow.copy();
                for (HirVar bv : arm.boundVars()) armFlow.remove(bv.id());
                HirExpr ng = arm.guard();
                if (ng != null) {
                    Eval gRes = eval(ng, armFlow, rewriting);
                    ng = gRes.expr();
                    if (gRes.lat().isConst()) {
                        if (Truth.test(gRes.lat().value())) {
                            if (rewriting && HirPurity.isPureNothrowTerminating(ng, module)) {
                                ng = null;
                                changed = true;
                                rewrites[0]++;
                            }
                        } else if (HirPurity.isPatternPureNothrowTerminating(np, module)
                                && HirPurity.isPureNothrowTerminating(ng, module)) {
                            if (rewriting) {
                                changed = true;
                                rewrites[0]++;
                            }
                            continue;
                        }
                    }
                }
                Eval bodyRes = eval(arm.body(), armFlow, rewriting);
                if (armFlow.reachable) {
                    joined = Flow.join(joined, armFlow);
                    resLat =
                            Lat.meet(
                                    resLat,
                                    coerceResultLat(
                                            bodyRes.lat(), arm.bodyWideningRank(), m.meta()));
                }
                if (rewriting
                        && (np != arm.pattern()
                                || ng != arm.guard()
                                || bodyRes.expr() != arm.body())) {
                    changed = true;
                    narms.add(
                            new HirExpr.MatchArm(
                                    np,
                                    arm.boundVars(),
                                    ng,
                                    bodyRes.expr(),
                                    arm.bodyWideningRank(),
                                    arm.line()));
                } else {
                    narms.add(arm);
                }
            }
            HirExpr.MatchElse ne = m.elseArm();
            if (ne != null) {
                Flow elseFlow = flow.copy();
                if (ne.var() != null) elseFlow.remove(ne.var().id());
                Eval ebRes = eval(ne.body(), elseFlow, rewriting);
                if (elseFlow.reachable) {
                    joined = Flow.join(joined, elseFlow);
                    resLat =
                            Lat.meet(
                                    resLat,
                                    coerceResultLat(ebRes.lat(), ne.bodyWideningRank(), m.meta()));
                }
                if (rewriting && ebRes.expr() != ne.body()) {
                    changed = true;
                    ne =
                            new HirExpr.MatchElse(
                                    ne.var(), ebRes.expr(), ne.bodyWideningRank(), ne.line());
                }
            } else if (!m.exhaustive()) {
                joined = Flow.join(joined, flow);
                resLat = Lat.meet(resLat, Lat.ofConst(null, PrimitiveKind.NONE));
            }
            flow.copyFrom(joined);
            if (!rewriting) return new Eval(m, resLat);
            HirExpr.Match rebuilt =
                    changed
                            ? new HirExpr.Match(
                                    scrut.expr(),
                                    m.scrutineeVar(),
                                    narms,
                                    ne,
                                    m.exhaustive(),
                                    m.meta())
                            : m;
            HirExpr unwrappedScrut = HirRewriter.unwrapTransparent(rebuilt.scrutinee());
            if (unwrappedScrut instanceof HirExpr.Const sc
                    && rebuilt.meta().checkedType() == null) {
                HirExpr folded = HirConstFolder.tryFoldConstMatch(sc, rebuilt, module);
                if (folded != null) {
                    rewrites[0]++;
                    return new Eval(folded, resLat);
                }
            }
            return new Eval(rebuilt, resLat);
        }

        private Eval evalWhile(HirExpr.While w, Flow flow, boolean rewriting) {
            Eval entryCond = eval(w.cond(), flow.copy(), false);
            if (entryCond.lat().isConst() && !Truth.test(entryCond.lat().value())) {
                Eval nc = eval(w.cond(), flow, rewriting);
                Lat nullLat = Lat.ofConst(null, PrimitiveKind.NONE);
                if (rewriting
                        && w.meta().kind() == PrimitiveKind.NONE
                        && w.meta().wideningRank() < 0) {
                    HirExpr nullConst = new HirExpr.Const(null, w.meta());
                    rewrites[0]++;
                    if (nc.expr() instanceof HirExpr.Const
                            || HirPurity.isPureNothrowTerminating(nc.expr(), module)) {
                        return new Eval(nullConst, nullLat);
                    }
                    return new Eval(
                            new HirExpr.Block(
                                    List.of(nc.expr(), nullConst),
                                    false,
                                    new String[0],
                                    List.of(),
                                    -1,
                                    w.meta()),
                            nullLat);
                }
                return new Eval(w, nullLat);
            }

            Flow headerFlow = flow.copy();
            boolean converged = false;
            for (int iter = 0; iter < MAX_LOOP_FIXPOINT_ITERS; iter++) {
                Flow afterCond = headerFlow.copy();
                Eval cEval = eval(w.cond(), afterCond, false);
                if (cEval.lat().isConst() && !Truth.test(cEval.lat().value())) {
                    converged = true;
                    break;
                }
                LoopCtx lctx = new LoopCtx();
                loops.push(lctx);
                Flow bodyFlow = afterCond.copy();
                eval(w.body(), bodyFlow, false);
                loops.pop();
                Flow backedge = Flow.join(bodyFlow, lctx.continueFlow);
                if (!backedge.reachable || !widenHeader(headerFlow, backedge)) {
                    converged = true;
                    break;
                }
            }
            if (!converged) {
                Set<Integer> mod = new HashSet<>();
                HirPurity.collectModifiedVars(w.cond(), mod);
                HirPurity.collectModifiedVars(w.body(), mod);
                headerFlow.removeAll(mod);
            }

            Flow afterCond = headerFlow.copy();
            Eval nc = eval(w.cond(), afterCond, rewriting);
            LoopCtx lctx = new LoopCtx();
            loops.push(lctx);
            Eval nb = eval(w.body(), afterCond.copy(), rewriting);
            loops.pop();

            boolean alwaysTrue = nc.lat().isConst() && Truth.test(nc.lat().value());
            flow.copyFrom(alwaysTrue ? lctx.breakFlow : Flow.join(afterCond, lctx.breakFlow));
            Lat resLat =
                    lctx.breakFlow.reachable
                            ? (alwaysTrue
                                    ? lctx.breakVal
                                    : Lat.meet(
                                            Lat.ofConst(null, PrimitiveKind.NONE), lctx.breakVal))
                            : Lat.ofConst(null, PrimitiveKind.NONE);
            HirExpr.While rebuilt =
                    (rewriting && (nc.expr() != w.cond() || nb.expr() != w.body()))
                            ? new HirExpr.While(nc.expr(), nb.expr(), w.meta())
                            : w;
            return new Eval(rebuilt, resLat);
        }

        private Eval evalFor(HirExpr.For f, Flow flow, boolean rewriting) {
            HirExpr ni = f.iterable() != null ? eval(f.iterable(), flow, rewriting).expr() : null;
            HirExpr.CountedRangeLoop cr = f.countedRange();
            HirExpr.CountedRangeLoop ncr = cr;
            if (cr != null) {
                HirExpr ns = eval(cr.start(), flow, rewriting).expr();
                HirExpr ne = eval(cr.end(), flow, rewriting).expr();
                HirExpr nk = cr.step() != null ? eval(cr.step(), flow, rewriting).expr() : null;
                if (rewriting && (ns != cr.start() || ne != cr.end() || nk != cr.step())) {
                    ncr =
                            new HirExpr.CountedRangeLoop(
                                    ns, ne, nk, cr.inclusive(), cr.descending(), cr.elemKind());
                }
            }
            if (!flow.reachable) return new Eval(f, Lat.TOP);
            Flow headerFlow = flow.copy();
            if (f.loopVar() != null) headerFlow.remove(f.loopVar().id());
            boolean converged = false;
            for (int iter = 0; iter < MAX_LOOP_FIXPOINT_ITERS; iter++) {
                LoopCtx lctx = new LoopCtx();
                loops.push(lctx);
                Flow bodyFlow = headerFlow.copy();
                if (f.loopVar() != null) bodyFlow.remove(f.loopVar().id());
                evalStatements(f.body(), bodyFlow, false);
                loops.pop();
                Flow backedge = Flow.join(bodyFlow, lctx.continueFlow);
                if (!backedge.reachable || !widenHeader(headerFlow, backedge)) {
                    converged = true;
                    break;
                }
            }
            if (!converged) {
                Set<Integer> mod = new HashSet<>();
                collectModifiedVars(f.body(), mod);
                headerFlow.removeAll(mod);
            }
            LoopCtx lctx = new LoopCtx();
            loops.push(lctx);
            Flow bodyFlow = headerFlow.copy();
            if (f.loopVar() != null) bodyFlow.remove(f.loopVar().id());
            StmtsEval nb = evalStatements(f.body(), bodyFlow, rewriting);
            loops.pop();
            flow.copyFrom(Flow.join(headerFlow, lctx.breakFlow));
            HirExpr.For rebuilt =
                    (rewriting && (ni != f.iterable() || ncr != cr || nb.stmts() != f.body()))
                            ? new HirExpr.For(
                                    ni,
                                    f.target(),
                                    f.loopVar(),
                                    f.bodyDeclaredVars(),
                                    nb.stmts(),
                                    f.bodyWideningRank(),
                                    f.elemSamTarget(),
                                    ncr,
                                    f.meta())
                            : f;
            return new Eval(rebuilt, Lat.BOTTOM);
        }

        private static boolean widenHeader(Flow header, Flow backedge) {
            List<Integer> toRemove = null;
            for (Map.Entry<Integer, Lat> e : header.env.entrySet()) {
                Lat bv = backedge.env.get(e.getKey());
                if (bv == null || !Lat.sameConst(e.getValue(), bv)) {
                    if (toRemove == null) toRemove = new ArrayList<>();
                    toRemove.add(e.getKey());
                }
            }
            if (toRemove == null) return false;
            for (int id : toRemove) header.env.remove(id);
            return true;
        }

        private Eval evalTry(HirExpr.Try t, Flow flow, boolean rewriting) {
            Set<Integer> tryMod = new HashSet<>();
            collectModifiedVars(t.tryBody(), tryMod);
            Set<Integer> catchMod = new HashSet<>();
            for (HirExpr.HirCatch cc : t.catches()) collectModifiedVars(cc.body(), catchMod);
            Set<Integer> finMod = new HashSet<>();
            if (t.hasFinally()) collectModifiedVars(t.finallyBody(), finMod);

            Flow tryFlow = flow.copy();
            StmtsEval ntb = evalStatements(t.tryBody(), tryFlow, rewriting);
            Flow joined = tryFlow.reachable ? tryFlow.copy() : Flow.unreachable();
            Lat resLat =
                    tryFlow.reachable
                            ? coerceResultLat(ntb.lastLat(), t.tryWideningRank(), t.meta())
                            : Lat.TOP;

            List<HirExpr.HirCatch> ncs = null;
            for (int idx = 0; idx < t.catches().size(); idx++) {
                HirExpr.HirCatch cc = t.catches().get(idx);
                Flow catchFlow = flow.copy();
                catchFlow.removeAll(tryMod);
                if (cc.var() != null) catchFlow.remove(cc.var().id());
                StmtsEval ncb = evalStatements(cc.body(), catchFlow, rewriting);
                if (catchFlow.reachable) {
                    joined = Flow.join(joined, catchFlow);
                    resLat =
                            Lat.meet(
                                    resLat,
                                    coerceResultLat(ncb.lastLat(), cc.wideningRank(), t.meta()));
                }
                if (rewriting && ncb.stmts() != cc.body()) {
                    if (ncs == null) ncs = new ArrayList<>(t.catches());
                    ncs.set(
                            idx,
                            new HirExpr.HirCatch(
                                    cc.var(),
                                    cc.target(),
                                    ncb.stmts(),
                                    cc.declaredVars(),
                                    cc.opensNamespaceScope(),
                                    cc.declaredNames(),
                                    cc.wideningRank(),
                                    cc.line()));
                }
            }

            List<HirExpr> nfb = t.finallyBody();
            if (t.hasFinally()) {
                Flow finIn = flow.copy();
                finIn.removeAll(tryMod);
                finIn.removeAll(catchMod);
                StmtsEval finEval = evalStatements(t.finallyBody(), finIn, rewriting);
                nfb = finEval.stmts();
                if (!finMod.isEmpty()) {
                    for (LoopCtx lc : loops) {
                        lc.breakFlow.removeAll(finMod);
                        lc.continueFlow.removeAll(finMod);
                    }
                }
                if (!finIn.reachable) {
                    flow.reachable = false;
                    flow.env.clear();
                } else {
                    joined.removeAll(finMod);
                    for (int id : finMod) {
                        Lat fv = finIn.env.get(id);
                        if (fv != null && fv.isConst()) joined.putConst(id, fv);
                    }
                    flow.copyFrom(joined);
                }
            } else {
                flow.copyFrom(joined);
            }

            HirExpr.Try rebuilt =
                    (rewriting
                                    && (ntb.stmts() != t.tryBody()
                                            || ncs != null
                                            || nfb != t.finallyBody()))
                            ? new HirExpr.Try(
                                    ntb.stmts(),
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
                                    t.meta())
                            : t;
            return new Eval(rebuilt, resLat);
        }

        private Eval evalLogic(HirExpr.Logic l, Flow flow, boolean rewriting) {
            Eval left = eval(l.left(), flow, rewriting);
            if (!flow.reachable) return new Eval(l, Lat.TOP);
            if (left.lat().isConst()) {
                boolean lv = Truth.test(left.lat().value());
                boolean shortCircuit =
                        (l.op() == HirExpr.LogicOp.AND && !lv)
                                || (l.op() == HirExpr.LogicOp.OR && lv);
                if (shortCircuit) {
                    Lat resLat = Lat.ofConst(lv, PrimitiveKind.BOOL);
                    if (rewriting) {
                        HirExpr bc =
                                new HirExpr.Const(
                                        lv,
                                        l.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
                        if (left.expr() instanceof HirExpr.Const
                                || HirPurity.isPureNothrowTerminating(left.expr(), module)) {
                            rewrites[0]++;
                            return new Eval(bc, resLat);
                        }
                        if (l.meta().wideningRank() < 0) {
                            rewrites[0]++;
                            return new Eval(
                                    new HirExpr.Block(
                                            List.of(left.expr(), bc),
                                            false,
                                            new String[0],
                                            List.of(),
                                            -1,
                                            bc.meta()),
                                    resLat);
                        }
                    }
                    return new Eval(l, resLat);
                }
                Eval right = eval(l.right(), flow, rewriting);
                Lat resLat =
                        right.lat().isConst()
                                ? Lat.ofConst(Truth.test(right.lat().value()), PrimitiveKind.BOOL)
                                : Lat.BOTTOM;
                if (rewriting
                        && (left.expr() instanceof HirExpr.Const
                                || HirPurity.isPureNothrowTerminating(left.expr(), module))
                        && right.expr().meta().kind() == PrimitiveKind.BOOL
                        && l.meta().wideningRank() < 0) {
                    rewrites[0]++;
                    return new Eval(right.expr(), resLat);
                }
                HirExpr.Logic rebuilt =
                        (rewriting && (left.expr() != l.left() || right.expr() != l.right()))
                                ? new HirExpr.Logic(l.op(), left.expr(), right.expr(), l.meta())
                                : l;
                return new Eval(rebuilt, resLat);
            }
            Flow rightFlow = flow.copy();
            Eval right = eval(l.right(), rightFlow, rewriting);
            flow.copyFrom(Flow.join(flow, rightFlow));
            HirExpr.Logic rebuilt =
                    (rewriting && (left.expr() != l.left() || right.expr() != l.right()))
                            ? new HirExpr.Logic(l.op(), left.expr(), right.expr(), l.meta())
                            : l;
            return new Eval(rebuilt, Lat.BOTTOM);
        }

        private Eval evalSafeCall(HirExpr.SafeCall sc, Flow flow, boolean rewriting) {
            Eval recv = eval(sc.receiver(), flow, rewriting);
            if (!flow.reachable) return new Eval(sc, Lat.TOP);
            Flow argFlow = flow.copy();
            boolean changed = rewriting && recv.expr() != sc.receiver();
            List<HirCallArg> newArgs = null;
            for (int i = 0; i < sc.args().size(); i++) {
                HirCallArg a = sc.args().get(i);
                Eval av = eval(a.value(), argFlow, rewriting);
                if (rewriting && av.expr() != a.value()) {
                    changed = true;
                    if (newArgs == null) newArgs = new ArrayList<>(sc.args());
                    newArgs.set(i, a.withValue(av.expr()));
                }
            }
            flow.copyFrom(Flow.join(flow, argFlow));
            return new Eval(
                    changed
                            ? new HirExpr.SafeCall(
                                    recv.expr(),
                                    sc.receiverText(),
                                    sc.memberName(),
                                    sc.selectorLine(),
                                    sc.selectorCheckedType(),
                                    newArgs != null ? newArgs : sc.args(),
                                    sc.binding(),
                                    sc.babbleSig(),
                                    sc.callSite(),
                                    sc.meta())
                            : sc,
                    Lat.BOTTOM);
        }

        private Eval evalReturn(HirExpr.Return r, Flow flow, boolean rewriting) {
            Eval v = eval(r.value(), flow, rewriting);
            flow.reachable = false;
            return new Eval(
                    rewriting && v.expr() != r.value() ? new HirExpr.Return(v.expr(), r.meta()) : r,
                    Lat.TOP);
        }

        private Eval evalThrow(HirExpr.Throw th, Flow flow, boolean rewriting) {
            Eval v = eval(th.value(), flow, rewriting);
            flow.reachable = false;
            return new Eval(
                    rewriting && v.expr() != th.value()
                            ? new HirExpr.Throw(v.expr(), th.isJavaThrowable(), th.meta())
                            : th,
                    Lat.TOP);
        }

        private Eval evalBreak(HirExpr.Break br, Flow flow, boolean rewriting) {
            Eval v = br.value() != null ? eval(br.value(), flow, rewriting) : null;
            if (!loops.isEmpty() && flow.reachable) {
                LoopCtx lc = loops.peek();
                lc.breakFlow = Flow.join(lc.breakFlow, flow);
                lc.breakVal =
                        Lat.meet(
                                lc.breakVal,
                                v != null ? v.lat() : Lat.ofConst(null, PrimitiveKind.NONE));
            }
            flow.reachable = false;
            return new Eval(
                    rewriting && v != null && v.expr() != br.value()
                            ? new HirExpr.Break(v.expr(), br.meta())
                            : br,
                    Lat.TOP);
        }

        private Eval evalContinue(HirExpr.Continue co, Flow flow) {
            if (!loops.isEmpty() && flow.reachable) {
                loops.peek().continueFlow = Flow.join(loops.peek().continueFlow, flow);
            }
            flow.reachable = false;
            return new Eval(co, Lat.TOP);
        }

        private Eval evalNarrow(HirExpr.Narrow n, Flow flow, boolean rewriting) {
            Eval op = eval(n.operand(), flow, rewriting);
            return new Eval(
                    rewriting && op.expr() != n.operand()
                            ? new HirExpr.Narrow(
                                    op.expr(),
                                    n.narrowKind(),
                                    n.guardId(),
                                    n.targetDesc(),
                                    n.meta())
                            : n,
                    Lat.BOTTOM);
        }

        private Eval evalUnary(HirExpr.Unary u, Flow flow, boolean rewriting) {
            Eval op = eval(u.operand(), flow, rewriting);
            HirExpr.Unary cur =
                    (rewriting && op.expr() != u.operand())
                            ? new HirExpr.Unary(u.op(), op.expr(), u.operandText(), u.meta())
                            : u;
            HirExpr folded = HirConstFolder.tryFoldUnary(cur);
            if (folded instanceof HirExpr.Const fc) return commitFold(cur, fc, rewriting);
            if (op.lat().isConst()) {
                HirExpr sFold =
                        HirConstFolder.tryFoldUnary(
                                new HirExpr.Unary(
                                        u.op(),
                                        synthConst(op.lat(), u.meta().line()),
                                        "",
                                        u.meta()));
                if (sFold instanceof HirExpr.Const fc) return commitPureFold(cur, fc, rewriting);
            }
            return new Eval(cur, Lat.BOTTOM);
        }

        private Eval evalBinary(HirExpr.Binary b, Flow flow, boolean rewriting) {
            Eval l = eval(b.left(), flow, rewriting);
            Eval r = eval(b.right(), flow, rewriting);
            HirExpr.Binary cur =
                    (rewriting && (l.expr() != b.left() || r.expr() != b.right()))
                            ? new HirExpr.Binary(
                                    b.op(),
                                    l.expr(),
                                    r.expr(),
                                    b.leftText(),
                                    b.rightText(),
                                    b.meta())
                            : b;
            HirExpr folded = HirConstFolder.tryFoldBinary(cur, module);
            if (folded != null) {
                if (endsInThrow(folded)) {
                    flow.reachable = false;
                    if (rewriting) {
                        rewrites[0]++;
                        return new Eval(folded, Lat.TOP);
                    }
                    return new Eval(cur, Lat.TOP);
                }
                if (folded instanceof HirExpr.Const fc) return commitFold(cur, fc, rewriting);
            }
            if (l.lat().isConst() && r.lat().isConst()) {
                HirExpr.Const lc =
                        new HirExpr.Const(
                                l.lat().value(),
                                b.left().meta().withKind(l.lat().kind()).withWideningRank(-1));
                HirExpr.Const rc =
                        new HirExpr.Const(
                                r.lat().value(),
                                b.right().meta().withKind(r.lat().kind()).withWideningRank(-1));
                HirExpr sFold =
                        HirConstFolder.tryFoldBinary(
                                new HirExpr.Binary(b.op(), lc, rc, "", "", b.meta()), module);
                if (sFold instanceof HirExpr.Const fc) return commitPureFold(cur, fc, rewriting);
            }
            return new Eval(cur, Lat.BOTTOM);
        }

        private Eval evalConvert(HirExpr.Convert cv, Flow flow, boolean rewriting) {
            Eval op = eval(cv.operand(), flow, rewriting);
            HirExpr.Convert cur =
                    (rewriting && op.expr() != cv.operand())
                            ? new HirExpr.Convert(op.expr(), cv.targetKind(), cv.meta())
                            : cv;
            HirExpr folded = HirConstFolder.tryFoldConvert(cur);
            if (folded != null) {
                Lat lat = folded instanceof HirExpr.Const fc ? latOfConst(fc) : op.lat();
                if (rewriting) {
                    rewrites[0]++;
                    return new Eval(folded, lat);
                }
                return new Eval(cur, lat);
            }
            if (op.lat().isConst() && cv.targetKind().isPrimitive()) {
                HirExpr sFold =
                        HirConstFolder.tryFoldConvert(
                                new HirExpr.Convert(
                                        synthConst(op.lat(), cv.meta().line()),
                                        cv.targetKind(),
                                        cv.meta()));
                if (sFold instanceof HirExpr.Const fc) return commitPureFold(cur, fc, rewriting);
            }
            return new Eval(cur, Lat.BOTTOM);
        }

        private Eval evalWiden(HirExpr.Widen w, Flow flow, boolean rewriting) {
            Eval op = eval(w.operand(), flow, rewriting);
            HirExpr.Widen cur =
                    (rewriting && op.expr() != w.operand())
                            ? new HirExpr.Widen(op.expr(), w.wideningRank(), w.meta())
                            : w;
            HirExpr folded = HirConstFolder.tryFoldWiden(cur);
            if (folded != null) {
                Lat lat = folded instanceof HirExpr.Const fc ? latOfConst(fc) : op.lat();
                if (rewriting) {
                    rewrites[0]++;
                    return new Eval(folded, lat);
                }
                return new Eval(cur, lat);
            }
            if (op.lat().isConst()) {
                HirExpr sFold =
                        HirConstFolder.tryFoldWiden(
                                new HirExpr.Widen(
                                        synthConst(op.lat(), w.meta().line()),
                                        w.wideningRank(),
                                        w.meta()));
                if (sFold instanceof HirExpr.Const fc) return commitPureFold(cur, fc, rewriting);
            }
            return new Eval(cur, Lat.BOTTOM);
        }

        private Eval evalJavaMethodCall(HirExpr.JavaMethodCall jm, Flow flow, boolean rewriting) {
            HirExpr cur = evalChildren(jm, flow, rewriting);
            if (cur instanceof HirExpr.JavaMethodCall call) {
                HirExpr folded = HirConstFolder.tryFoldJavaCall(call, module);
                if (folded != null) {
                    Lat lat = folded instanceof HirExpr.Const fc ? latOfConst(fc) : Lat.BOTTOM;
                    if (rewriting) {
                        rewrites[0]++;
                        return new Eval(folded, lat);
                    }
                    return new Eval(call, lat);
                }
            }
            return new Eval(cur, Lat.BOTTOM);
        }

        private Eval commitFold(HirExpr cur, HirExpr.Const fc, boolean rewriting) {
            Lat lat = latOfConst(fc);
            if (rewriting) {
                rewrites[0]++;
                return new Eval(fc, lat);
            }
            return new Eval(cur, lat);
        }

        private Eval commitPureFold(HirExpr cur, HirExpr.Const fc, boolean rewriting) {
            Lat lat = latOfConst(fc);
            if (rewriting && HirPurity.isPureNothrowTerminating(cur, module)) {
                rewrites[0]++;
                return new Eval(fc, lat);
            }
            return new Eval(cur, lat);
        }

        private HirExpr evalChildren(HirExpr expr, Flow flow, boolean rewriting) {
            return HirRewriter.rewriteChildren(
                    expr,
                    child -> {
                        Eval r = eval(child, flow, rewriting);
                        return rewriting ? r.expr() : child;
                    });
        }

        private static HirExpr.Const synthConst(Lat lat, int line) {
            return new HirExpr.Const(lat.value(), ExprMeta.of(line, lat.kind()));
        }

        private static Lat latOfConst(HirExpr.Const c) {
            return c != null ? Lat.ofConst(c.value(), c.meta().kind()) : Lat.BOTTOM;
        }

        private static Lat coerceResultLat(Lat raw, int innerWideningRank, ExprMeta meta) {
            if (raw == null || !raw.isConst()) return raw != null ? raw : Lat.BOTTOM;
            Object val = raw.value();
            PrimitiveKind k = raw.kind();
            if (innerWideningRank >= 0) {
                HirExpr.Const w =
                        HirConstFolder.applyWidening(
                                val, k, meta.withWideningRank(innerWideningRank));
                if (w == null) return Lat.BOTTOM;
                val = w.value();
                k = w.meta().kind();
            }
            HirExpr.Const converted = HirConstFolder.convertConstToKind(val, k, meta.kind(), meta);
            return converted != null ? latOfConst(converted) : Lat.BOTTOM;
        }

        private static boolean endsInThrow(HirExpr expr) {
            if (expr instanceof HirExpr.Throw) return true;
            return expr instanceof HirExpr.Block b
                    && !b.statements().isEmpty()
                    && b.statements().get(b.statements().size() - 1) instanceof HirExpr.Throw;
        }

        private static HirExpr.BinaryOp compoundBinaryOp(String op) {
            if (op == null) return null;
            return switch (op) {
                case "+", "+=" -> HirExpr.BinaryOp.ADD;
                case "-", "-=" -> HirExpr.BinaryOp.SUB;
                case "*", "*=" -> HirExpr.BinaryOp.MUL;
                case "/", "/=" -> HirExpr.BinaryOp.DIV;
                case "//", "//=" -> HirExpr.BinaryOp.FLOOR_DIV;
                case "mod", "mod=" -> HirExpr.BinaryOp.MOD;
                default -> null;
            };
        }

        private static void collectModifiedVars(List<HirExpr> stmts, Set<Integer> out) {
            for (HirExpr s : stmts) collectModifiedVarsExpr(s, out);
        }

        private static void collectModifiedVarsExpr(HirExpr expr, Set<Integer> out) {
            if (expr == null) return;
            if (expr instanceof HirExpr.VarAssign a) out.add(a.var().id());
            else if (expr instanceof HirExpr.Def d && d.var() != null) out.add(d.var().id());
            HirRewriter.rewriteChildren(
                    expr,
                    c -> {
                        collectModifiedVarsExpr(c, out);
                        return c;
                    });
        }
    }
}
