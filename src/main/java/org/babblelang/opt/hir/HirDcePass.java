package org.babblelang.opt.hir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirStaticBlock;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirEffectAnalysis;
import org.babblelang.opt.HirLiveness;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;

/**
 * {@code O4} — Structured Backward-Liveness Dead-Code and Dead-Store Elimination ({@code
 * BIP014-I2}, {@code BIP039-I2}).
 *
 * <ul>
 *   <li>Uses {@link HirLiveness} and {@link HirEffectAnalysis} on frame-scoped functions to
 *       eliminate dead {@code VarAssign} stores (including dead loop-carried accumulators) and dead
 *       local {@code Def}s while preserving impure RHS expressions.
 *   <li>Eliminates unreachable statements after {@code return}, {@code break}, {@code continue}, or
 *       {@code throw}.
 *   <li>Eliminates dead non-final pure expressions, dead {@code catch} clauses on pure {@code try}
 *       blocks, and pure {@code discard(...)} calls.
 *   <li>Flattens single-expression transparent {@link HirExpr.Block} wrappers and prunes
 *       unreferenced {@code declaredVars}.
 * </ul>
 */
public final class HirDcePass implements HirPass {
    @Override
    public String name() {
        return "HirDce";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] rewrites = {0};

        HirEffectAnalysis modFx = HirEffectAnalysis.forModule(module);
        List<HirExpr> newTop = dceBody(module.topLevelStatements(), null, modFx, module, rewrites);
        boolean changed = newTop != module.topLevelStatements();

        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            HirEffectAnalysis fnFx = HirEffectAnalysis.forFunction(fn, module);
            HirLiveness liveness =
                    fn.namespaceScope()
                            ? null
                            : HirLiveness.analyzeForDce(
                                    fn, stmt -> isDeadStoreRemovable(stmt, fnFx, module));
            List<HirParam> np =
                    HirRewriter.rewriteParams(
                            fn.params(), ex -> dceExpr(ex, Set.of(), null, fnFx, module, rewrites));
            List<HirExpr> nb = dceBody(fn.body(), liveness, fnFx, module, rewrites);
            List<HirVar> ndv = pruneUnusedDeclaredVars(fn.declaredVars(), nb);
            if (np != fn.params() || nb != fn.body() || ndv != fn.declaredVars()) {
                changed = true;
                fn = fn.withParamsAndBody(np, nb).withBodyAndDeclaredVars(nb, ndv);
            }
            newFns.put(e.getKey(), fn);
        }

        Map<Integer, HirClass> newClasses = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirClass> e : module.classes().entrySet()) {
            HirClass cls = e.getValue();
            List<HirParam> np =
                    HirRewriter.rewriteParams(
                            cls.params(),
                            ex -> dceExpr(ex, Set.of(), null, modFx, module, rewrites));
            List<HirExpr> nb = dceBody(cls.body(), null, modFx, module, rewrites);
            List<org.babblelang.ir.hir.HirConstructor> newCtors = cls.constructors();
            boolean ctorsChanged = false;
            if (!cls.constructors().isEmpty()) {
                List<org.babblelang.ir.hir.HirConstructor> rewritten =
                        new ArrayList<>(cls.constructors().size());
                for (org.babblelang.ir.hir.HirConstructor ctor : cls.constructors()) {
                    List<HirParam> cnp =
                            HirRewriter.rewriteParams(
                                    ctor.params(),
                                    ex -> dceExpr(ex, Set.of(), null, modFx, module, rewrites));
                    List<HirExpr> cnPre =
                            dceBody(ctor.preDelegateBody(), null, modFx, module, rewrites);
                    List<HirCallArg> cnArgs = ctor.delegateArgs();
                    boolean argsChanged = false;
                    if (!cnArgs.isEmpty()) {
                        List<HirCallArg> rebuiltArgs = new ArrayList<>(cnArgs.size());
                        for (HirCallArg a : cnArgs) {
                            HirExpr nv =
                                    dceExpr(a.value(), Set.of(), null, modFx, module, rewrites);
                            if (nv != a.value()) {
                                argsChanged = true;
                            }
                            rebuiltArgs.add(nv == a.value() ? a : new HirCallArg(a.name(), nv));
                        }
                        if (argsChanged) {
                            cnArgs = rebuiltArgs;
                        }
                    }
                    List<HirExpr> cnBody = dceBody(ctor.body(), null, modFx, module, rewrites);
                    if (cnp != ctor.params()
                            || cnPre != ctor.preDelegateBody()
                            || argsChanged
                            || cnBody != ctor.body()) {
                        ctorsChanged = true;
                        rewritten.add(ctor.withBodies(cnp, cnPre, cnArgs, cnBody));
                    } else {
                        rewritten.add(ctor);
                    }
                }
                if (ctorsChanged) {
                    newCtors = rewritten;
                }
            }
            HirStaticBlock ns = cls.staticBlock();
            if (ns != null) {
                List<HirExpr> nsb = dceBody(ns.body(), null, modFx, module, rewrites);
                if (nsb != ns.body()) {
                    ns = ns.withBody(nsb);
                }
            }
            if (np != cls.params() || nb != cls.body() || ctorsChanged || ns != cls.staticBlock()) {
                changed = true;
                cls = cls.withBodyAndStatic(np, nb, newCtors, ns);
            }
            newClasses.put(e.getKey(), cls);
        }

        Map<Integer, HirInterface> newIfaces = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirInterface> e : module.interfaces().entrySet()) {
            HirInterface iface = e.getValue();
            List<HirExpr> nb = dceBody(iface.body(), null, modFx, module, rewrites);
            HirStaticBlock ns = iface.staticBlock();
            if (ns != null) {
                List<HirExpr> nsb = dceBody(ns.body(), null, modFx, module, rewrites);
                if (nsb != ns.body()) {
                    ns = ns.withBody(nsb);
                }
            }
            if (nb != iface.body() || ns != iface.staticBlock()) {
                changed = true;
                iface = iface.withBodyAndStatic(nb, ns);
            }
            newIfaces.put(e.getKey(), iface);
        }

        if (!changed) {
            return Result.unchanged(module);
        }
        return new Result(
                module.withUpdates(
                        newTop,
                        newFns,
                        newClasses,
                        newIfaces,
                        module.nextVarId(),
                        module.nextOwnerId()),
                rewrites[0]);
    }

    private List<HirExpr> dceBody(
            List<HirExpr> stmts,
            HirLiveness liveness,
            HirEffectAnalysis fx,
            HirModule module,
            int[] rewrites) {
        Set<Integer> usedVars = new HashSet<>();
        for (HirExpr s : stmts) {
            collectUsedVars(s, usedVars);
        }
        return dceStatements(stmts, usedVars, liveness, fx, module, rewrites);
    }

    private List<HirExpr> dceStatements(
            List<HirExpr> stmts,
            Set<Integer> usedVars,
            HirLiveness liveness,
            HirEffectAnalysis fx,
            HirModule module,
            int[] rewrites) {
        if (stmts.isEmpty()) {
            return stmts;
        }
        List<HirExpr> out = new ArrayList<>(stmts.size());
        boolean changed = false;
        int lastIdx = stmts.size() - 1;
        for (int i = 0; i < stmts.size(); i++) {
            HirExpr old = stmts.get(i);
            boolean isLast = (i == lastIdx);

            // Backward-liveness dead store / dead def elimination (using original AST node identity
            // in HirLiveness before rewriting children).
            if (liveness != null && !isLast) {
                if (old instanceof HirExpr.VarAssign a
                        && !a.var().cell()
                        && !a.var().isCaptured()
                        && (!a.var().mayBeUndefined() || a.var().definitelyDefined())
                        && !liveness.isLiveOut(old, a.var().id())) {
                    if (a.operator() == null) {
                        HirExpr rhs = dceExpr(a.value(), usedVars, liveness, fx, module, rewrites);
                        changed = true;
                        rewrites[0]++;
                        if (!isRemovableExpr(rhs, fx, module)) {
                            out.add(rhs);
                        }
                        continue;
                    }
                    // Dead compound assign (e.g. dead += 1 inside a loop) when the op is removable.
                    if (isRemovableCompoundAssign(a, fx, module)) {
                        changed = true;
                        rewrites[0]++;
                        continue;
                    }
                }
                if (old instanceof HirExpr.Def d
                        && d.var() != null
                        && !d.var().cell()
                        && !d.var().isCaptured()
                        && d.var().kind() == Resolution.Variable.Kind.DEFINITION
                        && !liveness.isLiveOut(old, d.var().id())) {
                    HirExpr rhs =
                            d.value() != null
                                    ? dceExpr(d.value(), usedVars, liveness, fx, module, rewrites)
                                    : null;
                    changed = true;
                    rewrites[0]++;
                    if (rhs != null && !isRemovableExpr(rhs, fx, module)) {
                        out.add(rhs);
                    }
                    continue;
                }
            }

            HirExpr nw = dceExpr(old, usedVars, liveness, fx, module, rewrites);
            if (nw != old) {
                changed = true;
            }
            // Drop dead pure non-final expressions
            if (!isLast && isRemovableExpr(nw, fx, module)) {
                changed = true;
                rewrites[0]++;
                continue;
            }
            // Fallback (top-level / class / interface bodies without liveness): drop unused local
            // frame def with pure initializer.
            if (!isLast
                    && nw instanceof HirExpr.Def d
                    && d.var() != null
                    && !d.var().isCaptured()
                    && d.var().kind() == Resolution.Variable.Kind.DEFINITION
                    && !usedVars.contains(d.var().id())
                    && (d.value() == null || isRemovableExpr(d.value(), fx, module))) {
                changed = true;
                rewrites[0]++;
                continue;
            }
            out.add(nw);
            // Truncate unreachable statements after unconditional jump (return / break / continue /
            // throw)
            if (!isLast && isUnconditionalJump(nw)) {
                changed = true;
                rewrites[0] += (stmts.size() - 1 - i);
                break;
            }
        }
        return changed ? out : stmts;
    }

    private static boolean isDeadStoreRemovable(
            HirExpr stmt, HirEffectAnalysis fx, HirModule module) {
        if (stmt instanceof HirExpr.VarAssign a) {
            if (a.var().mayBeUndefined() && !a.var().definitelyDefined()) {
                return false;
            }
            if (a.operator() == null) {
                return isRemovableExpr(a.value(), fx, module);
            }
            return isRemovableCompoundAssign(a, fx, module);
        }
        if (stmt instanceof HirExpr.Def d) {
            return d.var() != null
                    && d.var().kind() == Resolution.Variable.Kind.DEFINITION
                    && (d.value() == null || isRemovableExpr(d.value(), fx, module));
        }
        return false;
    }

    private static boolean isRemovableExpr(HirExpr expr, HirEffectAnalysis fx, HirModule module) {
        if (expr == null) {
            return true;
        }
        return HirPurity.isPureNothrowTerminating(expr, module)
                || fx.effectOf(expr).isRemovableIfUnused();
    }

    private static boolean isRemovableCompoundAssign(
            HirExpr.VarAssign a, HirEffectAnalysis fx, HirModule module) {
        PrimitiveKind vk = a.var().primitiveKind();
        if (!vk.isPrimitive() || !a.value().meta().kind().isNumeric()) {
            return false;
        }
        if ("//".equals(a.operator()) || "mod".equals(a.operator())) {
            HirExpr rhs = HirRewriter.unwrapTransparent(a.value());
            if (!(rhs instanceof HirExpr.Const c
                    && c.value() instanceof Number n
                    && n.longValue() != 0L)) {
                return false;
            }
        }
        if (a.var().mayBeUndefined() && !a.var().definitelyDefined()) {
            return false;
        }
        return isRemovableExpr(a.value(), fx, module);
    }

    private HirExpr dceExpr(
            HirExpr expr,
            Set<Integer> usedVars,
            HirLiveness liveness,
            HirEffectAnalysis fx,
            HirModule module,
            int[] rewrites) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof HirExpr.Block b) {
            List<HirExpr> ns =
                    dceStatements(b.statements(), usedVars, liveness, fx, module, rewrites);
            List<HirVar> ndv = pruneUnusedDeclaredVars(b.declaredVars(), ns);
            HirExpr.Block nb =
                    (ns == b.statements() && ndv == b.declaredVars())
                            ? b
                            : new HirExpr.Block(
                                    ns,
                                    b.opensNamespaceScope(),
                                    b.declaredNames(),
                                    ndv,
                                    b.innerWideningRank(),
                                    b.meta());
            if (nb.isTransparentSingleExpr()) {
                HirExpr inner = nb.statements().get(0);
                if (inner.meta().kind() == nb.meta().kind()) {
                    rewrites[0]++;
                    return inner;
                }
            }
            return nb;
        }
        if (expr instanceof HirExpr.For f) {
            HirExpr ni =
                    f.iterable() != null
                            ? dceExpr(f.iterable(), usedVars, liveness, fx, module, rewrites)
                            : null;
            HirExpr.CountedRangeLoop cr = f.countedRange();
            HirExpr.CountedRangeLoop ncr = cr;
            if (cr != null) {
                HirExpr ns = dceExpr(cr.start(), usedVars, liveness, fx, module, rewrites);
                HirExpr ne = dceExpr(cr.end(), usedVars, liveness, fx, module, rewrites);
                HirExpr nk =
                        cr.step() != null
                                ? dceExpr(cr.step(), usedVars, liveness, fx, module, rewrites)
                                : null;
                if (ns != cr.start() || ne != cr.end() || nk != cr.step()) {
                    ncr =
                            new HirExpr.CountedRangeLoop(
                                    ns, ne, nk, cr.inclusive(), cr.descending(), cr.elemKind());
                }
            }
            List<HirExpr> nb = dceStatements(f.body(), usedVars, liveness, fx, module, rewrites);
            List<HirVar> nbdv = pruneUnusedDeclaredVars(f.bodyDeclaredVars(), nb);
            return (ni == f.iterable()
                            && ncr == cr
                            && nb == f.body()
                            && nbdv == f.bodyDeclaredVars())
                    ? f
                    : new HirExpr.For(
                            ni,
                            f.target(),
                            f.loopVar(),
                            nbdv,
                            nb,
                            f.bodyWideningRank(),
                            f.elemSamTarget(),
                            ncr,
                            f.meta());
        }
        if (expr instanceof HirExpr.Try t) {
            List<HirExpr> ntb =
                    dceStatements(t.tryBody(), usedVars, liveness, fx, module, rewrites);
            List<HirVar> ntdv = pruneUnusedDeclaredVars(t.tryDeclaredVars(), ntb);
            List<HirExpr> nfb =
                    dceStatements(t.finallyBody(), usedVars, liveness, fx, module, rewrites);
            List<HirVar> nfdv = pruneUnusedDeclaredVars(t.finallyDeclaredVars(), nfb);
            // Dead-catch elimination (BIP028 §7.4): when tryBody is pure+nothrow+terminating,
            // no exception can ever be thrown from tryBody.
            if (!t.tryOpensNamespaceScope() && HirPurity.isPureStatements(ntb, ntdv, module)) {
                if (!t.hasFinally() || nfb.isEmpty()) {
                    rewrites[0]++;
                    return new HirExpr.Block(
                            ntb, false, new String[0], ntdv, t.tryWideningRank(), t.meta());
                }
                if (!t.catches().isEmpty()) {
                    rewrites[0]++;
                    return new HirExpr.Try(
                            ntb,
                            ntdv,
                            false,
                            new String[0],
                            t.tryWideningRank(),
                            List.of(),
                            nfb,
                            nfdv,
                            t.finallyOpensNamespaceScope(),
                            t.finallyDeclaredNames(),
                            t.finallyNothrow(),
                            true,
                            t.meta());
                }
            }
            List<HirExpr.HirCatch> ncs = null;
            for (int i = 0; i < t.catches().size(); i++) {
                HirExpr.HirCatch cc = t.catches().get(i);
                List<HirExpr> ncb =
                        dceStatements(cc.body(), usedVars, liveness, fx, module, rewrites);
                List<HirVar> ncdv = pruneUnusedDeclaredVars(cc.declaredVars(), ncb);
                if (ncb != cc.body() || ncdv != cc.declaredVars()) {
                    if (ncs == null) {
                        ncs = new ArrayList<>(t.catches());
                    }
                    ncs.set(
                            i,
                            new HirExpr.HirCatch(
                                    cc.var(),
                                    cc.target(),
                                    ncb,
                                    ncdv,
                                    cc.opensNamespaceScope(),
                                    cc.declaredNames(),
                                    cc.wideningRank(),
                                    cc.line()));
                }
            }
            return (ntb == t.tryBody()
                            && ntdv == t.tryDeclaredVars()
                            && ncs == null
                            && nfb == t.finallyBody()
                            && nfdv == t.finallyDeclaredVars())
                    ? t
                    : new HirExpr.Try(
                            ntb,
                            ntdv,
                            t.tryOpensNamespaceScope(),
                            t.tryDeclaredNames(),
                            t.tryWideningRank(),
                            ncs != null ? ncs : t.catches(),
                            nfb,
                            nfdv,
                            t.finallyOpensNamespaceScope(),
                            t.finallyDeclaredNames(),
                            t.finallyNothrow(),
                            t.hasFinally(),
                            t.meta());
        }
        HirExpr cur =
                HirRewriter.rewriteChildren(
                        expr, child -> dceExpr(child, usedVars, liveness, fx, module, rewrites));

        // Eliminate discard(pureExpr) when discard is unshadowed at top-level
        if (cur instanceof HirExpr.DynamicCall dc
                && dc.callee() instanceof HirExpr.NameRead nr
                && "discard".equals(nr.name())
                && dc.args().size() == 1
                && dc.args().get(0).name() == null
                && !isTopLevelDeclared(module, "discard")
                && dc.meta().checkedType() == null
                && isRemovableExpr(dc.args().get(0).value(), fx, module)) {
            rewrites[0]++;
            return new HirExpr.Const(
                    null, dc.meta().withKind(PrimitiveKind.NONE).withWideningRank(-1));
        }

        return cur;
    }

    private static boolean isTopLevelDeclared(HirModule module, String name) {
        for (String decl : module.topLevelDeclaredNames()) {
            if (name.equals(decl)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnconditionalJump(HirExpr expr) {
        return expr instanceof HirExpr.Return
                || expr instanceof HirExpr.Break
                || expr instanceof HirExpr.Continue
                || expr instanceof HirExpr.Throw;
    }

    private static List<HirVar> pruneUnusedDeclaredVars(
            List<HirVar> declared, List<HirExpr> stmts) {
        if (declared.isEmpty()) {
            return declared;
        }
        Set<Integer> referenced = new HashSet<>();
        for (HirExpr s : stmts) {
            collectAllVarRefs(s, referenced);
        }
        List<HirVar> kept = null;
        for (int i = 0; i < declared.size(); i++) {
            HirVar v = declared.get(i);
            boolean keep =
                    v.isCaptured()
                            || v.kind() != Resolution.Variable.Kind.DEFINITION
                            || referenced.contains(v.id());
            if (!keep) {
                if (kept == null) {
                    kept = new ArrayList<>(declared.subList(0, i));
                }
            } else if (kept != null) {
                kept.add(v);
            }
        }
        return kept != null ? kept : declared;
    }

    /** Collects var IDs that are read or assigned (excluding initial Def target). */
    private static void collectUsedVars(HirExpr expr, Set<Integer> out) {
        if (expr == null) return;
        switch (expr) {
            case HirExpr.VarRead v -> out.add(v.var().id());
            case HirExpr.VarAssign a -> {
                out.add(a.var().id());
                collectUsedVars(a.value(), out);
            }
            case HirExpr.ThisRef t -> {
                if (t.var() != null) out.add(t.var().id());
            }
            case HirExpr.RecurseRef r -> {
                if (r.var() != null) out.add(r.var().id());
            }
            case HirExpr.SuperCall s -> {
                if (s.thisVar() != null) out.add(s.thisVar().id());
                HirRewriter.rewriteChildren(
                        expr,
                        c -> {
                            collectUsedVars(c, out);
                            return c;
                        });
            }
            default ->
                    HirRewriter.rewriteChildren(
                            expr,
                            c -> {
                                collectUsedVars(c, out);
                                return c;
                            });
        }
    }

    /** Collects every var ID referenced anywhere in {@code expr}, including Def targets. */
    private static void collectAllVarRefs(HirExpr expr, Set<Integer> out) {
        if (expr == null) return;
        switch (expr) {
            case HirExpr.VarRead v -> out.add(v.var().id());
            case HirExpr.Def d -> {
                if (d.var() != null) out.add(d.var().id());
                collectAllVarRefs(d.value(), out);
            }
            case HirExpr.VarAssign a -> {
                out.add(a.var().id());
                collectAllVarRefs(a.value(), out);
            }
            case HirExpr.ThisRef t -> {
                if (t.var() != null) out.add(t.var().id());
            }
            case HirExpr.RecurseRef r -> {
                if (r.var() != null) out.add(r.var().id());
            }
            case HirExpr.SuperCall s -> {
                if (s.thisVar() != null) out.add(s.thisVar().id());
                HirRewriter.rewriteChildren(
                        expr,
                        c -> {
                            collectAllVarRefs(c, out);
                            return c;
                        });
            }
            case HirExpr.Match m -> {
                if (m.scrutineeVar() != null) out.add(m.scrutineeVar().id());
                for (HirExpr.MatchArm arm : m.arms()) {
                    for (HirVar bv : arm.boundVars()) {
                        out.add(bv.id());
                    }
                }
                if (m.elseArm() != null && m.elseArm().var() != null) {
                    out.add(m.elseArm().var().id());
                }
                HirRewriter.rewriteChildren(
                        expr,
                        c -> {
                            collectAllVarRefs(c, out);
                            return c;
                        });
            }
            default ->
                    HirRewriter.rewriteChildren(
                            expr,
                            c -> {
                                collectAllVarRefs(c, out);
                                return c;
                            });
        }
    }
}
