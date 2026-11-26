package org.babblelang.opt.hir;

import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirStaticBlock;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code O4} — Structured Dead-Code and Dead-Expression Elimination (BIP014-I2).
 *
 * <ul>
 *   <li>Eliminates unreachable statements after {@code return}, {@code break}, or {@code continue}.</li>
 *   <li>Eliminates dead non-final {@code pure nothrow @TERMINATING} expressions (including synthetic
 *       expressions exposed by constant folding or inlining).</li>
 *   <li>Eliminates unused local frame {@code def} bindings whose initializer is
 *       {@code pure nothrow @TERMINATING}.</li>
 *   <li>Flattens single-expression transparent {@link HirExpr.Block} wrappers.</li>
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

        List<HirExpr> newTop = dceBody(module.topLevelStatements(), module, rewrites);
        boolean changed = newTop != module.topLevelStatements();

        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            List<HirParam> np = HirRewriter.rewriteParams(fn.params(), ex -> dceExpr(ex, Set.of(), module, rewrites));
            List<HirExpr> nb = dceBody(fn.body(), module, rewrites);
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
            List<HirParam> np = HirRewriter.rewriteParams(cls.params(), ex -> dceExpr(ex, Set.of(), module, rewrites));
            List<HirExpr> nb = dceBody(cls.body(), module, rewrites);
            HirStaticBlock ns = cls.staticBlock();
            if (ns != null) {
                List<HirExpr> nsb = dceBody(ns.body(), module, rewrites);
                if (nsb != ns.body()) {
                    ns = ns.withBody(nsb);
                }
            }
            if (np != cls.params() || nb != cls.body() || ns != cls.staticBlock()) {
                changed = true;
                cls = cls.withBodyAndStatic(np, nb, ns);
            }
            newClasses.put(e.getKey(), cls);
        }

        Map<Integer, HirInterface> newIfaces = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirInterface> e : module.interfaces().entrySet()) {
            HirInterface iface = e.getValue();
            List<HirExpr> nb = dceBody(iface.body(), module, rewrites);
            HirStaticBlock ns = iface.staticBlock();
            if (ns != null) {
                List<HirExpr> nsb = dceBody(ns.body(), module, rewrites);
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
        return new Result(module.withUpdates(newTop, newFns, newClasses, newIfaces, module.nextVarId(), module.nextOwnerId()), rewrites[0]);
    }

    private List<HirExpr> dceBody(List<HirExpr> stmts, HirModule module, int[] rewrites) {
        Set<Integer> usedVars = new HashSet<>();
        for (HirExpr s : stmts) {
            collectUsedVars(s, usedVars);
        }
        return dceStatements(stmts, usedVars, module, rewrites);
    }

    private List<HirExpr> dceStatements(List<HirExpr> stmts, Set<Integer> usedVars, HirModule module, int[] rewrites) {
        if (stmts.isEmpty()) {
            return stmts;
        }
        List<HirExpr> out = new ArrayList<>(stmts.size());
        boolean changed = false;
        int lastIdx = stmts.size() - 1;
        for (int i = 0; i < stmts.size(); i++) {
            HirExpr old = stmts.get(i);
            HirExpr nw = dceExpr(old, usedVars, module, rewrites);
            if (nw != old) {
                changed = true;
            }
            boolean isLast = (i == lastIdx);
            // Drop dead pure non-final expressions
            if (!isLast && HirPurity.isPureNothrowTerminating(nw, module)) {
                changed = true;
                rewrites[0]++;
                continue;
            }
            // Drop unused local frame def with pure initializer
            if (!isLast && nw instanceof HirExpr.Def d && d.var() != null && !d.var().isCaptured()
                    && d.var().kind() == Resolution.Variable.Kind.DEFINITION
                    && !usedVars.contains(d.var().id())
                    && (d.value() == null || HirPurity.isPureNothrowTerminating(d.value(), module))) {
                changed = true;
                rewrites[0]++;
                continue;
            }
            out.add(nw);
            // Truncate unreachable statements after unconditional jump (return / break / continue)
            if (!isLast && isUnconditionalJump(nw)) {
                changed = true;
                rewrites[0] += (stmts.size() - 1 - i);
                break;
            }
        }
        return changed ? out : stmts;
    }

    private HirExpr dceExpr(HirExpr expr, Set<Integer> usedVars, HirModule module, int[] rewrites) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof HirExpr.Block b) {
            List<HirExpr> ns = dceStatements(b.statements(), usedVars, module, rewrites);
            List<HirVar> ndv = pruneUnusedDeclaredVars(b.declaredVars(), ns);
            HirExpr.Block nb = (ns == b.statements() && ndv == b.declaredVars()) ? b
                    : new HirExpr.Block(ns, b.opensNamespaceScope(), b.declaredNames(), ndv, b.innerWideningRank(), b.meta());
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
            HirExpr ni = f.iterable() != null ? dceExpr(f.iterable(), usedVars, module, rewrites) : null;
            HirExpr.CountedRangeLoop cr = f.countedRange();
            HirExpr.CountedRangeLoop ncr = cr;
            if (cr != null) {
                HirExpr ns = dceExpr(cr.start(), usedVars, module, rewrites);
                HirExpr ne = dceExpr(cr.end(), usedVars, module, rewrites);
                HirExpr nk = cr.step() != null ? dceExpr(cr.step(), usedVars, module, rewrites) : null;
                if (ns != cr.start() || ne != cr.end() || nk != cr.step()) {
                    ncr = new HirExpr.CountedRangeLoop(ns, ne, nk, cr.inclusive(), cr.descending(), cr.elemKind());
                }
            }
            List<HirExpr> nb = dceStatements(f.body(), usedVars, module, rewrites);
            return (ni == f.iterable() && ncr == cr && nb == f.body()) ? f
                    : new HirExpr.For(ni, f.target(), f.loopVar(), f.bodyDeclaredVars(), nb,
                            f.bodyWideningRank(), f.elemSamTarget(), ncr, f.meta());
        }
        if (expr instanceof HirExpr.Try t) {
            List<HirExpr> ntb = dceStatements(t.tryBody(), usedVars, module, rewrites);
            List<HirVar> ntdv = pruneUnusedDeclaredVars(t.tryDeclaredVars(), ntb);
            List<HirExpr> nfb = dceStatements(t.finallyBody(), usedVars, module, rewrites);
            List<HirVar> nfdv = pruneUnusedDeclaredVars(t.finallyDeclaredVars(), nfb);
            // Dead-catch elimination (BIP028 §7.4): when tryBody is pure+nothrow+terminating,
            // no exception can ever be thrown from tryBody.
            if (!t.tryOpensNamespaceScope() && HirPurity.isPureStatements(ntb, ntdv, module)) {
                if (!t.hasFinally() || nfb.isEmpty()) {
                    rewrites[0]++;
                    return new HirExpr.Block(ntb, false, new String[0], ntdv, t.tryWideningRank(), t.meta());
                }
                if (!t.catches().isEmpty()) {
                    rewrites[0]++;
                    return new HirExpr.Try(ntb, ntdv, false, new String[0], t.tryWideningRank(),
                            List.of(), nfb, nfdv, t.finallyOpensNamespaceScope(), t.finallyDeclaredNames(),
                            t.finallyNothrow(), true, t.meta());
                }
            }
            List<HirExpr.HirCatch> ncs = null;
            for (int i = 0; i < t.catches().size(); i++) {
                HirExpr.HirCatch cc = t.catches().get(i);
                List<HirExpr> ncb = dceStatements(cc.body(), usedVars, module, rewrites);
                List<HirVar> ncdv = pruneUnusedDeclaredVars(cc.declaredVars(), ncb);
                if (ncb != cc.body() || ncdv != cc.declaredVars()) {
                    if (ncs == null) {
                        ncs = new ArrayList<>(t.catches());
                    }
                    ncs.set(i, new HirExpr.HirCatch(cc.var(), cc.target(), ncb, ncdv,
                            cc.opensNamespaceScope(), cc.declaredNames(), cc.wideningRank(), cc.line()));
                }
            }
            return (ntb == t.tryBody() && ntdv == t.tryDeclaredVars()
                    && ncs == null && nfb == t.finallyBody() && nfdv == t.finallyDeclaredVars()) ? t
                    : new HirExpr.Try(ntb, ntdv, t.tryOpensNamespaceScope(), t.tryDeclaredNames(),
                            t.tryWideningRank(), ncs != null ? ncs : t.catches(), nfb, nfdv,
                            t.finallyOpensNamespaceScope(), t.finallyDeclaredNames(),
                            t.finallyNothrow(), t.hasFinally(), t.meta());
        }
        HirExpr cur = HirRewriter.rewriteChildren(expr, child -> dceExpr(child, usedVars, module, rewrites));

        // Eliminate discard(pureExpr) when discard is unshadowed at top-level
        if (cur instanceof HirExpr.DynamicCall dc
                && dc.callee() instanceof HirExpr.NameRead nr && "discard".equals(nr.name())
                && dc.args().size() == 1 && dc.args().get(0).name() == null
                && !isTopLevelDeclared(module, "discard")
                && dc.meta().checkedType() == null
                && HirPurity.isPureNothrowTerminating(dc.args().get(0).value(), module)) {
            rewrites[0]++;
            return new HirExpr.Const(null, dc.meta().withKind(PrimitiveKind.NONE).withWideningRank(-1));
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
        return expr instanceof HirExpr.Return || expr instanceof HirExpr.Break
                || expr instanceof HirExpr.Continue || expr instanceof HirExpr.Throw;
    }

    private static List<HirVar> pruneUnusedDeclaredVars(List<HirVar> declared, List<HirExpr> stmts) {
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
            boolean keep = v.isCaptured()
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
                HirRewriter.rewriteChildren(expr, c -> {
                    collectUsedVars(c, out);
                    return c;
                });
            }
            default -> HirRewriter.rewriteChildren(expr, c -> {
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
                HirRewriter.rewriteChildren(expr, c -> {
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
                HirRewriter.rewriteChildren(expr, c -> {
                    collectAllVarRefs(c, out);
                    return c;
                });
            }
            default -> HirRewriter.rewriteChildren(expr, c -> {
                collectAllVarRefs(c, out);
                return c;
            });
        }
    }
}
