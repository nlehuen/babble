package org.babblelang.ir.hir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.LoweringTargets;

/**
 * Pass 4 of {@link HirBuilder} ({@code BIP039-D3}): attaches explicit zero-cost {@link
 * HirExpr.Narrow} guard anchors to narrowed local variable reads inside {@code if}, {@code
 * &&}/{@code ||}, {@code while}, and {@code match} guard scopes.
 */
final class HirNarrowAnnotator {
    private record NarrowBinding(HirExpr.NarrowKind kind, int guardId, String targetDesc) {}

    private record NarrowHint(
            HirVar var, HirExpr.NarrowKind kind, String targetDesc, boolean narrowOnTrue) {}

    private int nextGuardId = 1;

    private HirNarrowAnnotator() {}

    static void annotate(
            List<HirExpr> topLevel,
            Map<Integer, HirFunction> functions,
            Map<Integer, HirClass> classes,
            Map<Integer, HirInterface> interfaces) {
        new HirNarrowAnnotator().annotateListInPlace(topLevel, new HashMap<>());
        for (Map.Entry<Integer, HirFunction> entry : functions.entrySet()) {
            HirFunction fn = entry.getValue();
            HirNarrowAnnotator a = new HirNarrowAnnotator();
            List<HirParam> newParams = a.annotateParams(fn.params());
            List<HirExpr> newBody = a.annotateList(fn.body(), new HashMap<>());
            if (newParams != fn.params() || newBody != fn.body()) {
                entry.setValue(fn.withParamsAndBody(newParams, newBody));
            }
        }
        for (Map.Entry<Integer, HirClass> entry : classes.entrySet()) {
            HirClass cls = entry.getValue();
            HirNarrowAnnotator a = new HirNarrowAnnotator();
            List<HirParam> newParams = a.annotateParams(cls.params());
            List<HirExpr> newBody = a.annotateList(cls.body(), new HashMap<>());
            HirStaticBlock newStatic = a.annotateStaticBlock(cls.staticBlock());
            if (newParams != cls.params()
                    || newBody != cls.body()
                    || newStatic != cls.staticBlock()) {
                entry.setValue(cls.withBodyAndStatic(newParams, newBody, newStatic));
            }
        }
        for (Map.Entry<Integer, HirInterface> entry : interfaces.entrySet()) {
            HirInterface iface = entry.getValue();
            HirNarrowAnnotator a = new HirNarrowAnnotator();
            List<HirExpr> newBody = a.annotateList(iface.body(), new HashMap<>());
            HirStaticBlock newStatic = a.annotateStaticBlock(iface.staticBlock());
            if (newBody != iface.body() || newStatic != iface.staticBlock()) {
                entry.setValue(iface.withBodyAndStatic(newBody, newStatic));
            }
        }
    }

    private List<HirParam> annotateParams(List<HirParam> params) {
        List<HirParam> copy = null;
        for (int i = 0; i < params.size(); i++) {
            HirParam p = params.get(i);
            if (p.defaultValue() != null) {
                HirExpr nd = annotateExpr(p.defaultValue(), new HashMap<>());
                if (nd != p.defaultValue()) {
                    if (copy == null) {
                        copy = new ArrayList<>(params);
                    }
                    copy.set(i, p.withDefaultValue(nd));
                }
            }
        }
        return copy != null ? copy : params;
    }

    private HirStaticBlock annotateStaticBlock(HirStaticBlock sb) {
        if (sb == null) {
            return null;
        }
        List<HirExpr> nb = annotateList(sb.body(), new HashMap<>());
        return nb == sb.body() ? sb : sb.withBody(nb);
    }

    private void annotateListInPlace(List<HirExpr> stmts, Map<Integer, NarrowBinding> env) {
        for (int i = 0; i < stmts.size(); i++) {
            HirExpr old = stmts.get(i);
            HirExpr nw = annotateExpr(old, env);
            if (nw != old) {
                stmts.set(i, nw);
            }
        }
    }

    private List<HirExpr> annotateList(List<HirExpr> stmts, Map<Integer, NarrowBinding> env) {
        return HirTreeRewrite.rewriteList(stmts, s -> annotateExpr(s, env));
    }

    private HirExpr annotateExpr(HirExpr expr, Map<Integer, NarrowBinding> env) {
        if (expr == null) {
            return null;
        }
        return switch (expr) {
            case HirExpr.VarRead vr -> {
                NarrowBinding binding = env.get(vr.var().id());
                if (binding != null
                        && !vr.var().cell()
                        && !vr.var().primitiveKind().isPrimitive()) {
                    yield new HirExpr.Narrow(
                            vr, binding.kind, binding.guardId, binding.targetDesc, vr.meta());
                }
                yield vr;
            }
            case HirExpr.Narrow n -> n;
            case HirExpr.Def d -> {
                HirExpr nv = annotateExpr(d.value(), env);
                if (d.var() != null) {
                    env.remove(d.var().id());
                }
                yield nv == d.value()
                        ? d
                        : new HirExpr.Def(d.name(), d.target(), d.var(), nv, d.meta());
            }
            case HirExpr.VarAssign a -> {
                HirExpr nv = annotateExpr(a.value(), env);
                env.remove(a.var().id());
                yield nv == a.value()
                        ? a
                        : new HirExpr.VarAssign(
                                a.var(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
            }
            case HirExpr.If i -> {
                HirExpr nc = annotateExpr(i.cond(), env);
                List<NarrowHint> hints = extractHints(i.cond());
                int guardId = hints.isEmpty() ? -1 : nextGuardId++;
                Map<Integer, NarrowBinding> thenEnv = new HashMap<>(env);
                Map<Integer, NarrowBinding> elseEnv = new HashMap<>(env);
                for (NarrowHint h : hints) {
                    if (h.narrowOnTrue) {
                        addBinding(thenEnv, h, guardId);
                    } else {
                        addBinding(elseEnv, h, guardId);
                    }
                }
                HirExpr nt = annotateExpr(i.thenBranch(), thenEnv);
                HirExpr ne = i.elseBranch() != null ? annotateExpr(i.elseBranch(), elseEnv) : null;
                invalidateMutated(i.thenBranch(), env);
                invalidateMutated(i.elseBranch(), env);
                yield (nc == i.cond() && nt == i.thenBranch() && ne == i.elseBranch())
                        ? i
                        : new HirExpr.If(nc, nt, ne, i.meta());
            }
            case HirExpr.Logic l -> {
                HirExpr nl = annotateExpr(l.left(), env);
                List<NarrowHint> hints = extractHints(l.left());
                int guardId = hints.isEmpty() ? -1 : nextGuardId++;
                Map<Integer, NarrowBinding> rightEnv = new HashMap<>(env);
                boolean wantTrue = l.op() == HirExpr.LogicOp.AND;
                for (NarrowHint h : hints) {
                    if (h.narrowOnTrue == wantTrue) {
                        addBinding(rightEnv, h, guardId);
                    }
                }
                HirExpr nr = annotateExpr(l.right(), rightEnv);
                invalidateMutated(l.right(), env);
                yield (nl == l.left() && nr == l.right())
                        ? l
                        : new HirExpr.Logic(l.op(), nl, nr, l.rightNarrowed(), l.meta());
            }
            case HirExpr.While w -> {
                invalidateMutated(w.cond(), env);
                invalidateMutated(w.body(), env);
                HirExpr nc = annotateExpr(w.cond(), new HashMap<>(env));
                List<NarrowHint> hints = extractHints(w.cond());
                int guardId = hints.isEmpty() ? -1 : nextGuardId++;
                Map<Integer, NarrowBinding> bodyEnv = new HashMap<>(env);
                for (NarrowHint h : hints) {
                    if (h.narrowOnTrue) {
                        addBinding(bodyEnv, h, guardId);
                    }
                }
                HirExpr nb = annotateExpr(w.body(), bodyEnv);
                yield (nc == w.cond() && nb == w.body()) ? w : new HirExpr.While(nc, nb, w.meta());
            }
            case HirExpr.For f -> {
                HirExpr ni = f.iterable() != null ? annotateExpr(f.iterable(), env) : null;
                HirExpr.CountedRangeLoop cr = f.countedRange();
                HirExpr.CountedRangeLoop ncr = cr;
                if (cr != null) {
                    HirExpr ns = annotateExpr(cr.start(), env);
                    HirExpr ne = annotateExpr(cr.end(), env);
                    HirExpr nk = cr.step() != null ? annotateExpr(cr.step(), env) : null;
                    if (ns != cr.start() || ne != cr.end() || nk != cr.step()) {
                        ncr =
                                new HirExpr.CountedRangeLoop(
                                        ns, ne, nk, cr.inclusive(), cr.descending(), cr.elemKind());
                    }
                }
                for (HirExpr s : f.body()) {
                    invalidateMutated(s, env);
                }
                if (f.loopVar() != null) {
                    env.remove(f.loopVar().id());
                }
                List<HirExpr> nb = annotateList(f.body(), new HashMap<>(env));
                yield (ni == f.iterable() && ncr == cr && nb == f.body())
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
            case HirExpr.Match m -> annotateMatch(m, env);
            case HirExpr.Try t -> annotateTry(t, env);
            case HirExpr.Block b -> {
                Map<Integer, NarrowBinding> inner = new HashMap<>(env);
                List<HirExpr> ns = annotateList(b.statements(), inner);
                invalidateMutated(b, env);
                yield ns == b.statements()
                        ? b
                        : new HirExpr.Block(
                                ns,
                                b.opensNamespaceScope(),
                                b.declaredNames(),
                                b.declaredVars(),
                                b.innerWideningRank(),
                                b.meta());
            }
            default -> HirTreeRewrite.rewriteChildren(expr, c -> annotateExpr(c, env));
        };
    }

    private HirExpr annotateMatch(HirExpr.Match m, Map<Integer, NarrowBinding> env) {
        HirExpr ns = annotateExpr(m.scrutinee(), env);
        List<HirExpr.MatchArm> narms = null;
        for (int idx = 0; idx < m.arms().size(); idx++) {
            HirExpr.MatchArm arm = m.arms().get(idx);
            Map<Integer, NarrowBinding> armEnv = new HashMap<>(env);
            HirPattern np =
                    HirTreeRewrite.rewritePattern(arm.pattern(), c -> annotateExpr(c, armEnv));
            HirExpr ng = arm.guard() != null ? annotateExpr(arm.guard(), armEnv) : null;
            if (arm.guard() != null) {
                List<NarrowHint> hints = extractHints(arm.guard());
                if (!hints.isEmpty()) {
                    int gid = nextGuardId++;
                    for (NarrowHint h : hints) {
                        if (h.narrowOnTrue) {
                            addBinding(armEnv, h, gid);
                        }
                    }
                }
            }
            HirExpr nb = annotateExpr(arm.body(), armEnv);
            invalidateMutated(arm.guard(), env);
            invalidateMutated(arm.body(), env);
            if (np != arm.pattern() || ng != arm.guard() || nb != arm.body()) {
                if (narms == null) {
                    narms = new ArrayList<>(m.arms());
                }
                narms.set(
                        idx,
                        new HirExpr.MatchArm(
                                np, arm.boundVars(), ng, nb, arm.bodyWideningRank(), arm.line()));
            }
        }
        HirExpr.MatchElse ne = m.elseArm();
        if (ne != null) {
            HirExpr neb = annotateExpr(ne.body(), new HashMap<>(env));
            invalidateMutated(ne.body(), env);
            if (neb != ne.body()) {
                ne = new HirExpr.MatchElse(ne.var(), neb, ne.bodyWideningRank(), ne.line());
            }
        }
        return (ns == m.scrutinee() && narms == null && ne == m.elseArm())
                ? m
                : new HirExpr.Match(
                        ns,
                        m.scrutineeVar(),
                        narms != null ? narms : m.arms(),
                        ne,
                        m.exhaustive(),
                        m.meta());
    }

    private HirExpr annotateTry(HirExpr.Try t, Map<Integer, NarrowBinding> env) {
        List<HirExpr> ntb = annotateList(t.tryBody(), new HashMap<>(env));
        for (HirExpr s : t.tryBody()) {
            invalidateMutated(s, env);
        }
        List<HirExpr.HirCatch> ncs = null;
        for (int idx = 0; idx < t.catches().size(); idx++) {
            HirExpr.HirCatch cc = t.catches().get(idx);
            List<HirExpr> ncb = annotateList(cc.body(), new HashMap<>(env));
            for (HirExpr s : cc.body()) {
                invalidateMutated(s, env);
            }
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
        List<HirExpr> nfb = annotateList(t.finallyBody(), new HashMap<>(env));
        for (HirExpr s : t.finallyBody()) {
            invalidateMutated(s, env);
        }
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

    private static void addBinding(Map<Integer, NarrowBinding> env, NarrowHint hint, int guardId) {
        NarrowBinding existing = env.get(hint.var.id());
        if (existing != null
                && (existing.kind == hint.kind
                        || (existing.kind == HirExpr.NarrowKind.IS_TYPE
                                && hint.kind == HirExpr.NarrowKind.NON_NULL))) {
            return;
        }
        env.put(hint.var.id(), new NarrowBinding(hint.kind, guardId, hint.targetDesc));
    }

    private static List<NarrowHint> extractHints(HirExpr cond) {
        List<NarrowHint> out = new ArrayList<>(2);
        extractHintsRec(cond, false, out);
        return out;
    }

    private static void extractHintsRec(HirExpr cond, boolean negated, List<NarrowHint> out) {
        HirExpr cur = unwrapTransparent(cond);
        while (cur instanceof HirExpr.Unary u && u.op() == HirExpr.UnaryOp.NOT) {
            negated = !negated;
            cur = unwrapTransparent(u.operand());
        }
        if (cur instanceof HirExpr.Logic l) {
            if (!negated && l.op() == HirExpr.LogicOp.AND) {
                extractHintsRec(l.left(), false, out);
                extractHintsRec(l.right(), false, out);
                return;
            }
            if (negated && l.op() == HirExpr.LogicOp.OR) {
                extractHintsRec(l.left(), true, out);
                extractHintsRec(l.right(), true, out);
                return;
            }
            return;
        }
        if (cur instanceof HirExpr.Binary b
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
            if (v != null && !v.cell() && !v.primitiveKind().isPrimitive()) {
                boolean nonNullOnTrue = (b.op() == HirExpr.BinaryOp.NEQ) ^ negated;
                out.add(new NarrowHint(v, HirExpr.NarrowKind.NON_NULL, "non-null", nonNullOnTrue));
            }
            return;
        }
        if (cur instanceof HirExpr.Is isExpr) {
            HirExpr l = unwrapTransparent(isExpr.left());
            if (l instanceof HirExpr.VarRead vr
                    && !vr.var().cell()
                    && !vr.var().primitiveKind().isPrimitive()) {
                out.add(
                        new NarrowHint(
                                vr.var(),
                                HirExpr.NarrowKind.IS_TYPE,
                                describeIsTarget(isExpr),
                                !negated));
            }
        }
    }

    private static String describeIsTarget(HirExpr.Is isExpr) {
        if (isExpr.builtin() != null) {
            return isExpr.builtin();
        }
        if (!isExpr.intersectionTargets().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < isExpr.intersectionTargets().size(); i++) {
                if (i > 0) sb.append('&');
                LoweringTargets.IsTarget t = isExpr.intersectionTargets().get(i);
                sb.append(t.typeId() != null ? t.typeId() : t.jvmInternalName());
            }
            return sb.toString();
        }
        if (isExpr.target() != null && isExpr.target().typeId() != null) {
            return isExpr.target().typeId();
        }
        return isExpr.descriptor() != null ? isExpr.descriptor() : "type";
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

    private static void invalidateMutated(HirExpr expr, Map<Integer, NarrowBinding> env) {
        if (expr == null || env.isEmpty()) {
            return;
        }
        Set<Integer> mutated = new HashSet<>();
        collectMutatedVars(expr, mutated);
        for (int id : mutated) {
            env.remove(id);
        }
    }

    private static void collectMutatedVars(HirExpr expr, Set<Integer> out) {
        if (expr == null) {
            return;
        }
        if (expr instanceof HirExpr.VarAssign a) {
            out.add(a.var().id());
        }
        HirTreeRewrite.rewriteChildren(
                expr,
                child -> {
                    collectMutatedVars(child, out);
                    return child;
                });
    }
}
