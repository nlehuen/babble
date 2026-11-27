package org.babblelang.opt.hir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.ExprMeta;
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
 * Unified constant folding, single-assignment constant propagation, constant branch/loop pruning,
 * whitelisted pure-JDK folding, and E-graph algebraic simplification pass ({@code BIP027-D1},
 * {@code BIP027-D4}; merges former {@code ConstFoldPass} and {@code AlgebraicSimplifyPass}).
 *
 * <p>Delegates deterministic constant/range/JDK folding to {@link HirConstFolder} and HIR-to-{@code
 * EGraph} lowering/saturation/extraction to {@link HirEGraphCodec}. Strictly enforces IEEE-754
 * floating-point semantics via {@link org.babblelang.ir.egraph.AlgebraicRules#optimizerRules()}.
 */
public final class HirEGraphSimplifyPass implements HirPass {
    @Override
    public String name() {
        return "HirEGraphSimplify";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] rewrites = {0};
        HirModule updated =
                HirRewriter.rewriteModule(
                        module,
                        (ownerId, fnId, stmts) -> simplifyBody(stmts, module, rewrites),
                        expr -> simplifyExpr(expr, Map.of(), Set.of(), module, rewrites),
                        module::nextVarId);
        return updated == module ? Result.unchanged(module) : new Result(updated, rewrites[0]);
    }

    private List<HirExpr> simplifyBody(List<HirExpr> stmts, HirModule module, int[] rewrites) {
        Set<Integer> assigned = new HashSet<>();
        for (HirExpr s : stmts) {
            collectAssignedVars(s, assigned);
        }
        return simplifyStatements(stmts, new HashMap<>(), assigned, module, rewrites);
    }

    private List<HirExpr> simplifyStatements(
            List<HirExpr> stmts,
            Map<Integer, HirExpr.Const> env,
            Set<Integer> assigned,
            HirModule module,
            int[] rewrites) {
        List<HirExpr> copy = null;
        for (int i = 0; i < stmts.size(); i++) {
            HirExpr old = stmts.get(i);
            HirExpr nw = simplifyExpr(old, env, assigned, module, rewrites);
            if (nw != old) {
                if (copy == null) {
                    copy = new ArrayList<>(stmts);
                }
                copy.set(i, nw);
            }
            if (nw instanceof HirExpr.Def d
                    && d.var() != null
                    && !d.var().isCaptured()
                    && !assigned.contains(d.var().id())
                    && d.value() instanceof HirExpr.Const c) {
                HirExpr.Const bound =
                        HirConstFolder.coerceConstForVar(c, d.var(), d.meta().wideningRank());
                if (bound != null) {
                    env.put(d.var().id(), bound);
                }
            }
        }
        return copy != null ? copy : stmts;
    }

    private HirExpr simplifyExpr(
            HirExpr expr,
            Map<Integer, HirExpr.Const> env,
            Set<Integer> assigned,
            HirModule module,
            int[] rewrites) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof HirExpr.Block b) {
            Map<Integer, HirExpr.Const> innerEnv = new HashMap<>(env);
            List<HirExpr> ns =
                    simplifyStatements(b.statements(), innerEnv, assigned, module, rewrites);
            HirExpr.Block nb =
                    ns == b.statements()
                            ? b
                            : new HirExpr.Block(
                                    ns,
                                    b.opensNamespaceScope(),
                                    b.declaredNames(),
                                    b.declaredVars(),
                                    b.innerWideningRank(),
                                    b.meta());
            if (nb.isTransparentSingleExpr() && nb.statements().get(0) instanceof HirExpr.Const c) {
                rewrites[0]++;
                return c;
            }
            return nb;
        }
        if (expr instanceof HirExpr.If i) {
            HirExpr nc = simplifyExpr(i.cond(), env, assigned, module, rewrites);
            HirExpr nt =
                    simplifyExpr(i.thenBranch(), new HashMap<>(env), assigned, module, rewrites);
            HirExpr ne =
                    i.elseBranch() != null
                            ? simplifyExpr(
                                    i.elseBranch(), new HashMap<>(env), assigned, module, rewrites)
                            : null;
            HirExpr.If rebuilt =
                    (nc == i.cond() && nt == i.thenBranch() && ne == i.elseBranch())
                            ? i
                            : new HirExpr.If(nc, nt, ne, i.meta());
            HirExpr pruned = HirConstFolder.tryPruneIf(rebuilt);
            if (pruned != null) {
                rewrites[0]++;
                return pruned;
            }
            return rebuilt;
        }
        if (expr instanceof HirExpr.Match m) {
            return simplifyMatch(m, env, assigned, module, rewrites);
        }
        if (expr instanceof HirExpr.Try t) {
            List<HirExpr> ntb =
                    simplifyStatements(t.tryBody(), new HashMap<>(env), assigned, module, rewrites);
            List<HirExpr.HirCatch> ncs = null;
            for (int idx = 0; idx < t.catches().size(); idx++) {
                HirExpr.HirCatch cc = t.catches().get(idx);
                List<HirExpr> ncb =
                        simplifyStatements(
                                cc.body(), new HashMap<>(env), assigned, module, rewrites);
                if (ncb != cc.body()) {
                    if (ncs == null) {
                        ncs = new ArrayList<>(t.catches());
                    }
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
            List<HirExpr> nfb =
                    simplifyStatements(
                            t.finallyBody(), new HashMap<>(env), assigned, module, rewrites);
            return (ntb == t.tryBody() && ncs == null && nfb == t.finallyBody())
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
        }
        if (expr instanceof HirExpr.While w) {
            HirExpr nc = simplifyExpr(w.cond(), env, assigned, module, rewrites);
            if (nc instanceof HirExpr.Const c && !Truth.test(c.value())) {
                rewrites[0]++;
                return new HirExpr.Const(
                        null, w.meta().withKind(PrimitiveKind.NONE).withWideningRank(-1));
            }
            HirExpr nb = simplifyExpr(w.body(), new HashMap<>(env), assigned, module, rewrites);
            return (nc == w.cond() && nb == w.body()) ? w : new HirExpr.While(nc, nb, w.meta());
        }
        if (expr instanceof HirExpr.For f) {
            HirExpr ni =
                    f.iterable() != null
                            ? simplifyExpr(f.iterable(), env, assigned, module, rewrites)
                            : null;
            HirExpr.CountedRangeLoop cr = f.countedRange();
            HirExpr.CountedRangeLoop ncr = cr;
            if (cr != null) {
                HirExpr ns = simplifyExpr(cr.start(), env, assigned, module, rewrites);
                HirExpr ne = simplifyExpr(cr.end(), env, assigned, module, rewrites);
                HirExpr nk =
                        cr.step() != null
                                ? simplifyExpr(cr.step(), env, assigned, module, rewrites)
                                : null;
                if (ns != cr.start() || ne != cr.end() || nk != cr.step()) {
                    ncr =
                            new HirExpr.CountedRangeLoop(
                                    ns, ne, nk, cr.inclusive(), cr.descending(), cr.elemKind());
                }
            }
            List<HirExpr> nb =
                    simplifyStatements(f.body(), new HashMap<>(env), assigned, module, rewrites);
            return (ni == f.iterable() && ncr == cr && nb == f.body())
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
        }

        // Propagate known non-reassigned constant local variables
        if (expr instanceof HirExpr.VarRead v) {
            HirExpr.Const bound = env.get(v.var().id());
            if (bound != null
                    && v.meta().checkedType() == null
                    && (!v.var().mayBeUndefined() || v.var().definitelyDefined())
                    && bound.meta().kind() == v.var().primitiveKind()) {
                HirExpr.Const folded =
                        HirConstFolder.applyWidening(bound.value(), bound.meta().kind(), v.meta());
                if (folded != null) {
                    rewrites[0]++;
                    return folded;
                }
            }
            return v;
        }

        // Rewrite children bottom-up
        HirExpr cur =
                HirRewriter.rewriteChildren(
                        expr, child -> simplifyExpr(child, env, assigned, module, rewrites));

        // Canonicalize Const with pending wideningRank
        if (cur instanceof HirExpr.Const c && c.meta().wideningRank() >= 0) {
            HirExpr.Const widened =
                    HirConstFolder.applyWidening(c.value(), c.meta().kind(), c.meta());
            if (widened != null) {
                rewrites[0]++;
                return widened;
            }
        }

        // Simplify explicit Widen and Convert nodes (BIP039-D4)
        if (cur instanceof HirExpr.Widen w) {
            HirExpr folded = HirConstFolder.tryFoldWiden(w);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }
        if (cur instanceof HirExpr.Convert cv) {
            HirExpr folded = HirConstFolder.tryFoldConvert(cv);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }

        // Short-circuit logic folding
        if (cur instanceof HirExpr.Logic l) {
            HirExpr folded = HirConstFolder.tryFoldLogic(l);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }

        // Whitelisted pure deterministic JDK calls (Math.*, String.*, Range deforestation)
        if (cur instanceof HirExpr.JavaMethodCall jm) {
            HirExpr folded = HirConstFolder.tryFoldJavaCall(jm, module);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }

        // Direct constant folding for Unary / Binary (including wideningRank >= 0 & non-BOOL NOT)
        if (cur instanceof HirExpr.Unary u) {
            HirExpr folded = HirConstFolder.tryFoldUnary(u);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }
        if (cur instanceof HirExpr.Binary b) {
            HirExpr folded = HirConstFolder.tryFoldBinary(b, module);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }

        // E-graph saturation & extraction on eligible pure algebraic / idempotent expressions
        if (HirEGraphCodec.isEGraphCandidateRoot(cur, module)) {
            HirExpr simplified = HirEGraphCodec.tryEGraphSimplify(cur, module);
            if (simplified != null && simplified != cur) {
                rewrites[0]++;
                return simplified;
            }
        }

        // Order-preserving single-node identities for expressions with potentially impure operands
        HirExpr single = tryNonDroppingIdentity(cur, module);
        if (single != null) {
            rewrites[0]++;
            return single;
        }

        return cur;
    }

    /**
     * Single-node order-preserving identities safe even when an operand may throw or have effects.
     */
    static HirExpr tryNonDroppingIdentity(HirExpr cur, HirModule module) {
        if (cur instanceof HirExpr.Unary u
                && u.op() == HirExpr.UnaryOp.NOT
                && u.meta().wideningRank() < 0
                && u.meta().checkedType() == null) {
            HirExpr inner = HirRewriter.unwrapTransparent(u.operand());
            if (inner instanceof HirExpr.Unary innerNot && innerNot.op() == HirExpr.UnaryOp.NOT) {
                HirExpr b = HirRewriter.unwrapTransparent(innerNot.operand());
                if (b.meta().kind() == PrimitiveKind.BOOL && b.meta().wideningRank() < 0) {
                    return b;
                }
            }
            if (inner instanceof HirExpr.Binary b
                    && b.op().isComparison()
                    && b.meta().wideningRank() < 0
                    && b.meta().checkedType() == null) {
                HirExpr.BinaryOp neg =
                        switch (b.op()) {
                            case EQ -> HirExpr.BinaryOp.NEQ;
                            case NEQ -> HirExpr.BinaryOp.EQ;
                            case LT -> HirExpr.BinaryOp.GTE;
                            case LTE -> HirExpr.BinaryOp.GT;
                            case GT -> HirExpr.BinaryOp.LTE;
                            case GTE -> HirExpr.BinaryOp.LT;
                            default -> throw new IllegalStateException();
                        };
                return new HirExpr.Binary(
                        neg,
                        b.left(),
                        b.right(),
                        b.leftText(),
                        b.rightText(),
                        b.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
            }
        }
        if (cur instanceof HirExpr.Binary b
                && b.meta().checkedType() == null
                && b.meta().wideningRank() < 0) {
            PrimitiveKind rk = b.meta().kind();
            if (HirPurity.isIntegralKind(rk)
                    && b.left().meta().kind() == rk
                    && b.right().meta().kind() == rk
                    && b.left().meta().wideningRank() < 0
                    && b.right().meta().wideningRank() < 0) {
                Long lVal = HirConstFolder.asIntegral(HirRewriter.unwrapTransparent(b.left()));
                Long rVal = HirConstFolder.asIntegral(HirRewriter.unwrapTransparent(b.right()));
                if (b.op() == HirExpr.BinaryOp.ADD) {
                    if (rVal != null && rVal == 0L) return b.left();
                    if (lVal != null && lVal == 0L) return b.right();
                } else if (b.op() == HirExpr.BinaryOp.SUB) {
                    if (rVal != null && rVal == 0L) return b.left();
                    if (lVal != null && lVal == 0L) {
                        return new HirExpr.Unary(
                                HirExpr.UnaryOp.NEGATE, b.right(), b.rightText(), b.meta());
                    }
                } else if (b.op() == HirExpr.BinaryOp.MUL) {
                    if (rVal != null && rVal == 1L) return b.left();
                    if (lVal != null && lVal == 1L) return b.right();
                } else if (b.op() == HirExpr.BinaryOp.FLOOR_DIV) {
                    if (rVal != null && rVal == 1L) return b.left();
                    if (rVal != null && rVal == -1L) {
                        return new HirExpr.Unary(
                                HirExpr.UnaryOp.NEGATE, b.left(), b.leftText(), b.meta());
                    }
                } else if (b.op() == HirExpr.BinaryOp.MOD) {
                    if (rVal != null && (rVal == 1L || rVal == -1L)) {
                        Object zeroVal =
                                rk == PrimitiveKind.LONG ? (Object) 0L : Integer.valueOf(0);
                        HirExpr zero = new HirExpr.Const(zeroVal, b.meta());
                        if (HirPurity.isPureNothrowTerminating(b.left(), module)) {
                            return zero;
                        }
                        return new HirExpr.Block(
                                List.of(b.left(), zero),
                                false,
                                new String[0],
                                List.of(),
                                -1,
                                b.meta());
                    }
                    HirExpr innerL = HirRewriter.unwrapTransparent(b.left());
                    if (innerL instanceof HirExpr.Binary innerMod
                            && innerMod.op() == HirExpr.BinaryOp.MOD
                            && innerMod.meta().kind() == rk
                            && HirConstFolder.samePureScalarLeaf(
                                    innerMod.right(), b.right(), module)) {
                        return innerMod;
                    }
                }
            }
        }
        if (cur instanceof HirExpr.DirectBabbleCall outer
                && HirEGraphCodec.isIdempotentBabbleEligible(outer, module)) {
            HirExpr arg = HirRewriter.unwrapTransparent(outer.args().get(0));
            if (arg instanceof HirExpr.DirectBabbleCall inner
                    && HirEGraphCodec.sameDirectCallTarget(inner, outer)
                    && HirEGraphCodec.isIdempotentBabbleEligible(inner, module)) {
                return inner;
            }
        }
        if (cur instanceof HirExpr.JavaMethodCall outer
                && HirEGraphCodec.isIdempotentJavaEligible(outer, module)) {
            if (outer.target().isStatic()) {
                HirExpr arg = HirRewriter.unwrapTransparent(outer.args().get(0));
                if (arg instanceof HirExpr.JavaMethodCall inner
                        && HirEGraphCodec.isIdempotentJavaEligible(inner, module)
                        && inner.target().isStatic()
                        && inner.target().method().equals(outer.target().method())) {
                    return inner;
                }
            } else {
                HirExpr recv = HirRewriter.unwrapTransparent(outer.receiver());
                if (recv instanceof HirExpr.JavaMethodCall inner
                        && HirEGraphCodec.isIdempotentJavaEligible(inner, module)
                        && !inner.target().isStatic()
                        && inner.target().method().equals(outer.target().method())) {
                    return inner;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- match folding & guard
    // pruning (BIP038-I5)

    private HirExpr simplifyMatch(
            HirExpr.Match m,
            Map<Integer, HirExpr.Const> env,
            Set<Integer> assigned,
            HirModule module,
            int[] rewrites) {
        HirExpr ns = simplifyExpr(m.scrutinee(), env, assigned, module, rewrites);
        boolean changed = ns != m.scrutinee();
        List<HirExpr.MatchArm> narms = new ArrayList<>(m.arms().size());
        for (HirExpr.MatchArm arm : m.arms()) {
            HirPattern np =
                    HirRewriter.rewritePattern(
                            arm.pattern(),
                            child -> simplifyExpr(child, env, assigned, module, rewrites));
            Map<Integer, HirExpr.Const> armEnv = new HashMap<>(env);
            for (HirVar bv : arm.boundVars()) {
                armEnv.remove(bv.id());
            }
            HirExpr ng =
                    arm.guard() != null
                            ? simplifyExpr(arm.guard(), armEnv, assigned, module, rewrites)
                            : null;
            if (ng != null) {
                HirExpr unwrappedG = HirRewriter.unwrapTransparent(ng);
                if (unwrappedG instanceof HirExpr.Const gc) {
                    if (Truth.test(gc.value())) {
                        ng = null;
                        changed = true;
                        rewrites[0]++;
                    } else if (HirPurity.isPatternPureNothrowTerminating(np, module)) {
                        changed = true;
                        rewrites[0]++;
                        continue;
                    }
                }
            }
            HirExpr nb =
                    simplifyExpr(arm.body(), new HashMap<>(armEnv), assigned, module, rewrites);
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
            Map<Integer, HirExpr.Const> elseEnv = new HashMap<>(env);
            if (ne.var() != null) {
                elseEnv.remove(ne.var().id());
            }
            HirExpr neb = simplifyExpr(ne.body(), elseEnv, assigned, module, rewrites);
            if (neb != ne.body()) {
                changed = true;
                ne = new HirExpr.MatchElse(ne.var(), neb, ne.bodyWideningRank(), ne.line());
            }
        }
        HirExpr.Match rebuilt =
                changed
                        ? new HirExpr.Match(
                                ns, m.scrutineeVar(), narms, ne, m.exhaustive(), m.meta())
                        : m;

        // Constant-scrutinee folding
        HirExpr unwrappedScrut = HirRewriter.unwrapTransparent(rebuilt.scrutinee());
        if (unwrappedScrut instanceof HirExpr.Const sc && rebuilt.meta().checkedType() == null) {
            HirExpr folded = HirConstFolder.tryFoldConstMatch(sc, rebuilt, module);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }
        return rebuilt;
    }

    static HirExpr.Const applyWidening(Object value, PrimitiveKind currentKind, ExprMeta meta) {
        return HirConstFolder.applyWidening(value, currentKind, meta);
    }

    private static void collectAssignedVars(HirExpr expr, Set<Integer> out) {
        if (expr == null) return;
        if (expr instanceof HirExpr.VarAssign a) {
            out.add(a.var().id());
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    collectAssignedVars(child, out);
                    return child;
                });
    }
}
