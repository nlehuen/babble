package org.babblelang.opt.hir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;

/**
 * {@code O9} — Scalar replacement of aggregates (SROA) for non-escaping {@code record} values and
 * flattened {@code $unboxed} entry points for non-inlined {@code borrow} {@code record} parameters
 * ({@code BIP014-I4}).
 *
 * <p>Coordinates three cooperating sub-passes over {@link HirModule}:
 *
 * <ol>
 *   <li><b>Projection beta-reduction</b> ({@link RecordBetaReducer}): {@code
 *       ThisFieldGet(RecordNew(C, args), p)} and static {@code match} destructuring over {@code
 *       RecordNew} reduce directly to field expressions while preserving exact left-to-right side
 *       effects.
 *   <li><b>Flattened {@code $unboxed} entry points</b> ({@link UnboxedBorrowSpecializer}): for
 *       non-method, capture-free functions taking {@code borrow} {@code record} parameters accessed
 *       solely via field projection, synthesizes a companion {@code HirFunction} accepting the
 *       record's fields directly and rewrites qualifying {@link HirExpr.DirectBabbleCall} sites.
 *   <li><b>Non-escaping local scalarization</b> (this class): replaces non-escaping local {@code
 *       record} variables (including loop-carried variables reassigned to fresh {@code RecordNew}s)
 *       with per-field primitive/reference locals across all 8 {@code PrimitiveKind}s.
 * </ol>
 */
public final class ScalarReplacePass implements HirPass {
    @Override
    public String name() {
        return "ScalarReplace";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] nextVarId = {module.nextVarId()};
        int[] nextOwnerId = {module.nextOwnerId()};
        int[] rewrites = {0};

        Map<String, RecordLayout> layoutsByTypeId = RecordLayout.collectRecordLayouts(module);
        Set<String> initBindings = HirPurity.findDefinitelyInitializedTopLevelBindings(module);

        // Sub-pass 1: Projection beta-reduction everywhere in module.
        HirModule cur =
                RecordBetaReducer.betaReduceModule(
                        module, layoutsByTypeId, initBindings, nextVarId, rewrites);

        // Sub-pass 2: Flattened $unboxed entry points for non-inlined borrow record parameters.
        cur =
                UnboxedBorrowSpecializer.unboxBorrowParams(
                        cur, layoutsByTypeId, initBindings, nextVarId, nextOwnerId, rewrites);

        // Sub-pass 3: Non-escaping local record scalarization inside frame-scoped functions.
        cur = scalarizeLocalRecords(cur, layoutsByTypeId, initBindings, nextVarId, rewrites);

        if (rewrites[0] == 0) {
            return Result.unchanged(module);
        }
        return new Result(cur, rewrites[0]);
    }

    // ---------------------------------------------------------------- Sub-pass 3: non-escaping
    // local record scalarization

    private HirModule scalarizeLocalRecords(
            HirModule module,
            Map<String, RecordLayout> localLayouts,
            Set<String> initBindings,
            int[] nextVarId,
            int[] rewrites) {
        boolean changed = false;
        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            if (!fn.namespaceScope()) {
                HirFunction updated =
                        scalarizeInFunction(
                                fn, module, localLayouts, initBindings, nextVarId, rewrites);
                if (updated != fn) {
                    changed = true;
                    fn = updated;
                }
            }
            newFns.put(e.getKey(), fn);
        }
        if (!changed) {
            return module;
        }
        return module.withUpdates(
                module.topLevelStatements(),
                newFns,
                module.classes(),
                module.interfaces(),
                nextVarId[0],
                module.nextOwnerId());
    }

    private HirFunction scalarizeInFunction(
            HirFunction fn,
            HirModule module,
            Map<String, RecordLayout> localLayouts,
            Set<String> initBindings,
            int[] nextVarId,
            int[] rewrites) {
        // 1. Collect eligible non-cell local HirVars declared inside fn (excluding params, for-loop
        // vars, catch vars).
        Map<Integer, HirVar> localVars = new LinkedHashMap<>();
        collectEligibleLocalVars(fn, localVars);
        if (localVars.isEmpty()) {
            return fn;
        }

        // 2. Discover candidate record layouts from Def / VarAssign RHSs (iterative propagation
        // across copy defs).
        Map<Integer, RecordLayout> candidates = new LinkedHashMap<>();
        Set<Integer> disqualified = new HashSet<>();
        discoverInitialCandidates(
                fn.body(), localVars, localLayouts, module, candidates, disqualified);
        if (candidates.isEmpty()) {
            return fn;
        }

        // 3. Fixed-point escape & soundness verification: remove any var that escapes, has a
        // non-record RHS,
        //    is defined/assigned in a value-used position, or is used in any way other than valid
        // ThisFieldGet
        //    or copy/destructuring into another surviving candidate.
        while (!candidates.isEmpty()) {
            Set<Integer> bad = new HashSet<>();
            verifyBodyStatements(fn.body(), true, candidates, localLayouts, module, bad);
            if (bad.isEmpty()) {
                break;
            }
            for (int id : bad) {
                candidates.remove(id);
            }
        }
        if (candidates.isEmpty()) {
            return fn;
        }

        // 4. Allocate scalar replacement HirVars for every surviving candidate var.
        Map<Integer, HirVar[]> scalarVars = new LinkedHashMap<>();
        for (Map.Entry<Integer, RecordLayout> e : candidates.entrySet()) {
            HirVar orig = localVars.get(e.getKey());
            RecordLayout layout = e.getValue();
            HirVar[] scalars = new HirVar[layout.fieldCount()];
            for (int i = 0; i < layout.fieldCount(); i++) {
                int sid = nextVarId[0]++;
                String sname = "$sr_" + orig.name() + "_" + i;
                scalars[i] =
                        new HirVar(
                                sid,
                                sname,
                                Resolution.Variable.Kind.DEFINITION,
                                fn.id(),
                                layout.paramKinds()[i],
                                false,
                                true,
                                false,
                                true);
            }
            scalarVars.put(e.getKey(), scalars);
        }

        // 5. Rewrite function declaredVars and body.
        List<HirVar> newDeclVars = expandDeclaredVars(fn.declaredVars(), scalarVars);
        List<HirExpr> newBody =
                rewriteScalarizedStmts(
                        fn.body(),
                        fn.id(),
                        candidates,
                        scalarVars,
                        localLayouts,
                        module,
                        initBindings,
                        nextVarId);
        rewrites[0] += candidates.size();
        return fn.withBodyAndDeclaredVars(newBody, newDeclVars);
    }

    private static void addEligibleVars(
            List<HirVar> vars, Set<Integer> exclude, Map<Integer, HirVar> out) {
        for (HirVar v : vars) {
            if (!v.isCaptured()
                    && v.kind() == Resolution.Variable.Kind.DEFINITION
                    && (exclude == null || !exclude.contains(v.id()))
                    && (!v.mayBeUndefined() || v.definitelyDefined())) {
                out.put(v.id(), v);
            }
        }
    }

    private static void collectEligibleLocalVars(HirFunction fn, Map<Integer, HirVar> out) {
        Set<Integer> paramIds = new HashSet<>();
        for (HirParam p : fn.params()) {
            if (p.var() != null) {
                paramIds.add(p.var().id());
            }
        }
        addEligibleVars(fn.declaredVars(), paramIds, out);
        for (HirExpr s : fn.body()) {
            collectBlockLocalVars(s, out);
        }
    }

    private static void collectBlockLocalVars(HirExpr expr, Map<Integer, HirVar> out) {
        if (expr == null) return;
        if (expr instanceof HirExpr.Block b) {
            addEligibleVars(b.declaredVars(), null, out);
        } else if (expr instanceof HirExpr.For f) {
            addEligibleVars(f.bodyDeclaredVars(), null, out);
        } else if (expr instanceof HirExpr.Try t) {
            addEligibleVars(t.tryDeclaredVars(), null, out);
            for (HirExpr.HirCatch cc : t.catches()) {
                addEligibleVars(cc.declaredVars(), null, out);
            }
            addEligibleVars(t.finallyDeclaredVars(), null, out);
        }
        HirRewriter.rewriteChildren(
                expr,
                c -> {
                    collectBlockLocalVars(c, out);
                    return c;
                });
    }

    private static void discoverInitialCandidates(
            List<HirExpr> body,
            Map<Integer, HirVar> localVars,
            Map<String, RecordLayout> localLayouts,
            HirModule module,
            Map<Integer, RecordLayout> candidates,
            Set<Integer> disqualified) {
        // Iteratively propagate layouts from RecordNew and candidate VarReads.
        boolean progress = true;
        while (progress) {
            progress = false;
            int beforeSize = candidates.size();
            for (HirExpr s : body) {
                scanCandidateAssignments(
                        s, localVars, localLayouts, module, candidates, disqualified);
            }
            if (candidates.size() != beforeSize) {
                progress = true;
            }
        }
        for (int bad : disqualified) {
            candidates.remove(bad);
        }
    }

    private static void recordCandidateLayout(
            int vid,
            HirExpr value,
            Map<String, RecordLayout> localLayouts,
            HirModule module,
            Map<Integer, RecordLayout> candidates,
            Set<Integer> disqualified) {
        RecordLayout layout = inferRecordLayout(value, candidates, localLayouts, module);
        if (layout != null) {
            RecordLayout existing = candidates.get(vid);
            if (existing == null) {
                candidates.put(vid, layout);
            } else if (!existing.sameType(layout)) {
                disqualified.add(vid);
            }
        }
    }

    private static void scanCandidateAssignments(
            HirExpr expr,
            Map<Integer, HirVar> localVars,
            Map<String, RecordLayout> localLayouts,
            HirModule module,
            Map<Integer, RecordLayout> candidates,
            Set<Integer> disqualified) {
        if (expr == null) return;
        if (expr instanceof HirExpr.Def d
                && d.var() != null
                && localVars.containsKey(d.var().id())) {
            int vid = d.var().id();
            if (d.value() == null) {
                disqualified.add(vid);
            } else {
                recordCandidateLayout(
                        vid, d.value(), localLayouts, module, candidates, disqualified);
            }
        } else if (expr instanceof HirExpr.VarAssign a && localVars.containsKey(a.var().id())) {
            int vid = a.var().id();
            if (a.operator() != null) {
                disqualified.add(vid);
            } else {
                recordCandidateLayout(
                        vid, a.value(), localLayouts, module, candidates, disqualified);
            }
        }
        HirRewriter.rewriteChildren(
                expr,
                c -> {
                    scanCandidateAssignments(
                            c, localVars, localLayouts, module, candidates, disqualified);
                    return c;
                });
    }

    private static RecordLayout inferRecordLayout(
            HirExpr expr,
            Map<Integer, RecordLayout> candidates,
            Map<String, RecordLayout> localLayouts,
            HirModule module) {
        if (expr == null) return null;
        if (expr instanceof HirExpr.RecordNew r) {
            return RecordLayout.resolveArgMapping(r) != null
                    ? RecordLayout.layoutOf(r, localLayouts, module)
                    : null;
        }
        if (expr instanceof HirExpr.Move m) {
            return inferRecordLayout(m.operand(), candidates, localLayouts, module);
        }
        if (expr instanceof HirExpr.Freeze f) {
            return inferRecordLayout(f.operand(), candidates, localLayouts, module);
        }
        if (expr instanceof HirExpr.VarRead vr) {
            return candidates.get(vr.var().id());
        }
        if (expr instanceof HirExpr.Block b
                && !b.opensNamespaceScope()
                && !b.statements().isEmpty()
                && b.meta().checkedType() == null) {
            return inferRecordLayout(
                    b.statements().get(b.statements().size() - 1),
                    candidates,
                    localLayouts,
                    module);
        }
        if (expr instanceof HirExpr.If i
                && i.elseBranch() != null
                && i.meta().checkedType() == null) {
            RecordLayout tl = inferRecordLayout(i.thenBranch(), candidates, localLayouts, module);
            RecordLayout el = inferRecordLayout(i.elseBranch(), candidates, localLayouts, module);
            if (tl != null && tl.sameType(el)) {
                return tl;
            }
            return tl != null ? tl : el;
        }
        return null;
    }

    private static void verifyBodyStatements(
            List<HirExpr> stmts,
            boolean lastValueUsed,
            Map<Integer, RecordLayout> candidates,
            Map<String, RecordLayout> localLayouts,
            HirModule module,
            Set<Integer> bad) {
        int last = stmts.size() - 1;
        for (int i = 0; i < stmts.size(); i++) {
            verifyExpr(
                    stmts.get(i),
                    i == last && lastValueUsed,
                    candidates,
                    localLayouts,
                    module,
                    bad);
        }
    }

    private static void verifyExpr(
            HirExpr expr,
            boolean valueUsed,
            Map<Integer, RecordLayout> candidates,
            Map<String, RecordLayout> localLayouts,
            HirModule module,
            Set<Integer> bad) {
        if (expr == null) return;
        switch (expr) {
            case HirExpr.Def d -> {
                if (d.var() != null && candidates.containsKey(d.var().id())) {
                    int vid = d.var().id();
                    if (valueUsed || d.value() == null || d.meta().checkedType() != null) {
                        bad.add(vid);
                        verifyExpr(d.value(), true, candidates, localLayouts, module, bad);
                    } else if (!verifyDestructuredRhs(
                            d.value(),
                            candidates.get(vid),
                            candidates,
                            localLayouts,
                            module,
                            bad)) {
                        bad.add(vid);
                    }
                    return;
                }
                verifyExpr(d.value(), true, candidates, localLayouts, module, bad);
            }
            case HirExpr.VarAssign a -> {
                if (candidates.containsKey(a.var().id())) {
                    int vid = a.var().id();
                    if (valueUsed || a.operator() != null || a.meta().checkedType() != null) {
                        bad.add(vid);
                        verifyExpr(a.value(), true, candidates, localLayouts, module, bad);
                    } else if (!verifyDestructuredRhs(
                            a.value(),
                            candidates.get(vid),
                            candidates,
                            localLayouts,
                            module,
                            bad)) {
                        bad.add(vid);
                    }
                    return;
                }
                verifyExpr(a.value(), true, candidates, localLayouts, module, bad);
            }
            case HirExpr.ThisFieldGet g -> {
                if (g.receiver() instanceof HirExpr.VarRead vr
                        && candidates.containsKey(vr.var().id())) {
                    RecordLayout layout = candidates.get(vr.var().id());
                    if (RecordLayout.matchingParamIndex(g, layout) < 0) {
                        bad.add(vr.var().id());
                    }
                    return;
                }
                verifyExpr(g.receiver(), true, candidates, localLayouts, module, bad);
            }
            case HirExpr.VarRead vr -> {
                if (candidates.containsKey(vr.var().id())) {
                    bad.add(vr.var().id());
                }
            }
            case HirExpr.Block b ->
                    verifyBodyStatements(
                            b.statements(), valueUsed, candidates, localLayouts, module, bad);
            case HirExpr.If i -> {
                verifyExpr(i.cond(), true, candidates, localLayouts, module, bad);
                verifyExpr(i.thenBranch(), valueUsed, candidates, localLayouts, module, bad);
                if (i.elseBranch() != null) {
                    verifyExpr(i.elseBranch(), valueUsed, candidates, localLayouts, module, bad);
                }
            }
            case HirExpr.Match m -> {
                if (m.meta().checkedType() == null
                        && m.scrutinee() instanceof HirExpr.VarRead vr
                        && candidates.containsKey(vr.var().id())) {
                    RecordBetaReducer.StaticMatchResult res =
                            RecordBetaReducer.findStaticMatchArm(
                                    m, candidates.get(vr.var().id()), module);
                    if (res != null) {
                        verifyExpr(
                                res.isElse() ? m.elseArm().body() : res.arm().body(),
                                valueUsed,
                                candidates,
                                localLayouts,
                                module,
                                bad);
                        return;
                    }
                }
                verifyExpr(m.scrutinee(), true, candidates, localLayouts, module, bad);
                for (HirExpr.MatchArm arm : m.arms()) {
                    verifyPattern(arm.pattern(), candidates, localLayouts, module, bad);
                    if (arm.guard() != null) {
                        verifyExpr(arm.guard(), true, candidates, localLayouts, module, bad);
                    }
                    verifyExpr(arm.body(), valueUsed, candidates, localLayouts, module, bad);
                }
                if (m.elseArm() != null) {
                    verifyExpr(
                            m.elseArm().body(), valueUsed, candidates, localLayouts, module, bad);
                }
            }
            case HirExpr.While w -> {
                verifyExpr(w.cond(), true, candidates, localLayouts, module, bad);
                verifyExpr(w.body(), false, candidates, localLayouts, module, bad);
            }
            case HirExpr.For f -> {
                if (f.countedRange() != null) {
                    verifyExpr(
                            f.countedRange().start(), true, candidates, localLayouts, module, bad);
                    verifyExpr(f.countedRange().end(), true, candidates, localLayouts, module, bad);
                    if (f.countedRange().step() != null) {
                        verifyExpr(
                                f.countedRange().step(),
                                true,
                                candidates,
                                localLayouts,
                                module,
                                bad);
                    }
                } else {
                    verifyExpr(f.iterable(), true, candidates, localLayouts, module, bad);
                }
                verifyBodyStatements(f.body(), false, candidates, localLayouts, module, bad);
            }
            case HirExpr.Try t -> {
                verifyBodyStatements(t.tryBody(), valueUsed, candidates, localLayouts, module, bad);
                for (HirExpr.HirCatch cc : t.catches()) {
                    verifyBodyStatements(
                            cc.body(), valueUsed, candidates, localLayouts, module, bad);
                }
                verifyBodyStatements(t.finallyBody(), false, candidates, localLayouts, module, bad);
            }
            default ->
                    HirRewriter.rewriteChildren(
                            expr,
                            c -> {
                                verifyExpr(c, true, candidates, localLayouts, module, bad);
                                return c;
                            });
        }
    }

    /**
     * Verifies {@code rhs} produces {@code expected} via RecordNew/candidate copy/Block/If and
     * walks nested expressions.
     */
    private static boolean verifyDestructuredRhs(
            HirExpr rhs,
            RecordLayout expected,
            Map<Integer, RecordLayout> candidates,
            Map<String, RecordLayout> localLayouts,
            HirModule module,
            Set<Integer> bad) {
        if (rhs == null || rhs.meta().checkedType() != null) {
            verifyExpr(rhs, true, candidates, localLayouts, module, bad);
            return false;
        }
        if (rhs instanceof HirExpr.RecordNew r) {
            RecordLayout rl = RecordLayout.layoutOf(r, localLayouts, module);
            if (!expected.sameType(rl) || RecordLayout.resolveArgMapping(r) == null) {
                verifyExpr(r, true, candidates, localLayouts, module, bad);
                return false;
            }
            // If classRef is an instance VarRead of a candidate (from InlinePass), it does not
            // escape because r is scalarized.
            if (!(r.classRefIsInstance()
                    && r.classRef() instanceof HirExpr.VarRead cr
                    && candidates.containsKey(cr.var().id()))) {
                verifyExpr(r.classRef(), true, candidates, localLayouts, module, bad);
            }
            for (HirExpr arg : r.args()) {
                verifyExpr(arg, true, candidates, localLayouts, module, bad);
            }
            return true;
        }
        if (rhs instanceof HirExpr.Move m) {
            return verifyDestructuredRhs(
                    m.operand(), expected, candidates, localLayouts, module, bad);
        }
        if (rhs instanceof HirExpr.Freeze f) {
            return verifyDestructuredRhs(
                    f.operand(), expected, candidates, localLayouts, module, bad);
        }
        if (rhs instanceof HirExpr.VarRead vr) {
            RecordLayout ul = candidates.get(vr.var().id());
            if (ul != null && expected.sameType(ul)) {
                return true;
            }
            return false;
        }
        if (rhs instanceof HirExpr.Block b
                && !b.opensNamespaceScope()
                && !b.statements().isEmpty()
                && b.innerWideningRank() < 0
                && b.meta().wideningRank() < 0) {
            int last = b.statements().size() - 1;
            for (int i = 0; i < last; i++) {
                verifyExpr(b.statements().get(i), false, candidates, localLayouts, module, bad);
            }
            return verifyDestructuredRhs(
                    b.statements().get(last), expected, candidates, localLayouts, module, bad);
        }
        if (rhs instanceof HirExpr.If i && i.elseBranch() != null && i.meta().wideningRank() < 0) {
            verifyExpr(i.cond(), true, candidates, localLayouts, module, bad);
            boolean tok =
                    verifyDestructuredRhs(
                            i.thenBranch(), expected, candidates, localLayouts, module, bad);
            boolean eok =
                    verifyDestructuredRhs(
                            i.elseBranch(), expected, candidates, localLayouts, module, bad);
            return tok && eok;
        }
        verifyExpr(rhs, true, candidates, localLayouts, module, bad);
        return false;
    }

    private static void verifyPattern(
            org.babblelang.ir.hir.HirPattern pattern,
            Map<Integer, RecordLayout> candidates,
            Map<String, RecordLayout> localLayouts,
            HirModule module,
            Set<Integer> bad) {
        if (pattern == null) {
            return;
        }
        switch (pattern) {
            case org.babblelang.ir.hir.HirPattern.Const ignored -> {}
            case org.babblelang.ir.hir.HirPattern.Wildcard ignored -> {}
            case org.babblelang.ir.hir.HirPattern.TypeTest ignored -> {}
            case org.babblelang.ir.hir.HirPattern.ValueExpr v ->
                    verifyExpr(v.expr(), true, candidates, localLayouts, module, bad);
            case org.babblelang.ir.hir.HirPattern.Bind b ->
                    verifyPattern(b.inner(), candidates, localLayouts, module, bad);
            case org.babblelang.ir.hir.HirPattern.Or o -> {
                for (org.babblelang.ir.hir.HirPattern alt : o.alternatives()) {
                    verifyPattern(alt, candidates, localLayouts, module, bad);
                }
            }
            case org.babblelang.ir.hir.HirPattern.Relational r ->
                    verifyExpr(r.rhs(), true, candidates, localLayouts, module, bad);
            case org.babblelang.ir.hir.HirPattern.RangeTest r -> {
                verifyExpr(r.lo(), true, candidates, localLayouts, module, bad);
                verifyExpr(r.hi(), true, candidates, localLayouts, module, bad);
                if (r.step() != null) {
                    verifyExpr(r.step(), true, candidates, localLayouts, module, bad);
                }
            }
            case org.babblelang.ir.hir.HirPattern.Membership m ->
                    verifyExpr(m.container(), true, candidates, localLayouts, module, bad);
            case org.babblelang.ir.hir.HirPattern.RecordDestructure rd -> {
                for (org.babblelang.ir.hir.HirPattern fp : rd.fieldPatterns()) {
                    verifyPattern(fp, candidates, localLayouts, module, bad);
                }
            }
            case org.babblelang.ir.hir.HirPattern.SequenceDestructure sd -> {
                for (org.babblelang.ir.hir.HirPattern ep : sd.elemPatterns()) {
                    verifyPattern(ep, candidates, localLayouts, module, bad);
                }
            }
        }
    }

    private static List<HirVar> expandDeclaredVars(
            List<HirVar> orig, Map<Integer, HirVar[]> scalarVars) {
        if (orig.isEmpty()) {
            return orig;
        }
        boolean any = false;
        for (HirVar v : orig) {
            if (scalarVars.containsKey(v.id())) {
                any = true;
                break;
            }
        }
        if (!any) {
            return orig;
        }
        List<HirVar> out = new ArrayList<>();
        for (HirVar v : orig) {
            HirVar[] scalars = scalarVars.get(v.id());
            if (scalars != null) {
                for (HirVar sv : scalars) {
                    out.add(sv);
                }
            } else {
                out.add(v);
            }
        }
        return out;
    }

    private List<HirExpr> rewriteScalarizedStmts(
            List<HirExpr> stmts,
            int ownerFnId,
            Map<Integer, RecordLayout> candidates,
            Map<Integer, HirVar[]> scalarVars,
            Map<String, RecordLayout> localLayouts,
            HirModule module,
            Set<String> initBindings,
            int[] nextVarId) {
        List<HirExpr> out = new ArrayList<>(stmts.size());
        for (HirExpr s : stmts) {
            out.add(
                    rewriteScalarizedExpr(
                            s,
                            ownerFnId,
                            candidates,
                            scalarVars,
                            localLayouts,
                            module,
                            initBindings,
                            nextVarId));
        }
        return out;
    }

    private HirExpr rewriteScalarizedExpr(
            HirExpr expr,
            int ownerFnId,
            Map<Integer, RecordLayout> candidates,
            Map<Integer, HirVar[]> scalarVars,
            Map<String, RecordLayout> localLayouts,
            HirModule module,
            Set<String> initBindings,
            int[] nextVarId) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof HirExpr.ThisFieldGet g && g.receiver() instanceof HirExpr.VarRead vr) {
            HirVar[] scalars = scalarVars.get(vr.var().id());
            if (scalars != null) {
                RecordLayout layout = candidates.get(vr.var().id());
                int p = RecordLayout.matchingParamIndex(g, layout);
                return new HirExpr.VarRead(scalars[p], g.meta());
            }
        }
        if (expr instanceof HirExpr.Match m
                && m.meta().checkedType() == null
                && m.scrutinee() instanceof HirExpr.VarRead vr) {
            HirVar[] scalars = scalarVars.get(vr.var().id());
            if (scalars != null) {
                RecordLayout layout = candidates.get(vr.var().id());
                RecordBetaReducer.StaticMatchResult res =
                        RecordBetaReducer.findStaticMatchArm(m, layout, module);
                if (res != null) {
                    List<HirExpr> stmts = new ArrayList<>();
                    List<HirVar> decls = List.of();
                    if (!res.isElse()
                            && res.arm().pattern() instanceof HirPattern.RecordDestructure rd) {
                        int[] memberIndices = rd.babbleMemberIndices();
                        for (int k = 0; k < rd.fieldPatterns().size(); k++) {
                            if (rd.fieldPatterns().get(k) instanceof HirPattern.Bind b) {
                                int p = layout.paramIndexForMember(memberIndices[k]);
                                PrimitiveKind fk = layout.paramKinds()[p];
                                ExprMeta rm =
                                        new ExprMeta(
                                                b.line(),
                                                fk,
                                                -1,
                                                null,
                                                EffectSet.EMPTY,
                                                fk.isPrimitive(),
                                                false);
                                stmts.add(
                                        makeScalarStore(
                                                b.var(),
                                                new HirExpr.VarRead(scalars[p], rm),
                                                true,
                                                b.line(),
                                                fk));
                            }
                        }
                        decls = res.arm().boundVars();
                    }
                    HirExpr body =
                            rewriteScalarizedExpr(
                                    res.isElse() ? m.elseArm().body() : res.arm().body(),
                                    ownerFnId,
                                    candidates,
                                    scalarVars,
                                    localLayouts,
                                    module,
                                    initBindings,
                                    nextVarId);
                    int bodyWiden =
                            res.isElse()
                                    ? m.elseArm().bodyWideningRank()
                                    : res.arm().bodyWideningRank();
                    stmts.add(body);
                    return new HirExpr.Block(
                            stmts, false, new String[0], decls, bodyWiden, m.meta());
                }
            }
        }
        if (expr instanceof HirExpr.Def d
                && d.var() != null
                && scalarVars.containsKey(d.var().id())) {
            return emitDestructuredStore(
                    d.value(),
                    scalarVars.get(d.var().id()),
                    candidates.get(d.var().id()),
                    true,
                    d.meta().line(),
                    ownerFnId,
                    candidates,
                    scalarVars,
                    localLayouts,
                    module,
                    initBindings,
                    nextVarId);
        }
        if (expr instanceof HirExpr.VarAssign a && scalarVars.containsKey(a.var().id())) {
            return emitDestructuredStore(
                    a.value(),
                    scalarVars.get(a.var().id()),
                    candidates.get(a.var().id()),
                    false,
                    a.meta().line(),
                    ownerFnId,
                    candidates,
                    scalarVars,
                    localLayouts,
                    module,
                    initBindings,
                    nextVarId);
        }
        if (expr instanceof HirExpr.Block b) {
            List<HirVar> newDecls = expandDeclaredVars(b.declaredVars(), scalarVars);
            List<HirExpr> newStmts =
                    rewriteScalarizedStmts(
                            b.statements(),
                            ownerFnId,
                            candidates,
                            scalarVars,
                            localLayouts,
                            module,
                            initBindings,
                            nextVarId);
            return new HirExpr.Block(
                    newStmts,
                    b.opensNamespaceScope(),
                    b.declaredNames(),
                    newDecls,
                    b.innerWideningRank(),
                    b.meta());
        }
        if (expr instanceof HirExpr.For f) {
            HirExpr iter =
                    f.iterable() != null
                            ? rewriteScalarizedExpr(
                                    f.iterable(),
                                    ownerFnId,
                                    candidates,
                                    scalarVars,
                                    localLayouts,
                                    module,
                                    initBindings,
                                    nextVarId)
                            : null;
            HirExpr.CountedRangeLoop cr = f.countedRange();
            if (cr != null) {
                cr =
                        new HirExpr.CountedRangeLoop(
                                rewriteScalarizedExpr(
                                        cr.start(),
                                        ownerFnId,
                                        candidates,
                                        scalarVars,
                                        localLayouts,
                                        module,
                                        initBindings,
                                        nextVarId),
                                rewriteScalarizedExpr(
                                        cr.end(),
                                        ownerFnId,
                                        candidates,
                                        scalarVars,
                                        localLayouts,
                                        module,
                                        initBindings,
                                        nextVarId),
                                cr.step() != null
                                        ? rewriteScalarizedExpr(
                                                cr.step(),
                                                ownerFnId,
                                                candidates,
                                                scalarVars,
                                                localLayouts,
                                                module,
                                                initBindings,
                                                nextVarId)
                                        : null,
                                cr.inclusive(),
                                cr.descending(),
                                cr.elemKind());
            }
            List<HirVar> newDecls = expandDeclaredVars(f.bodyDeclaredVars(), scalarVars);
            List<HirExpr> newBody =
                    rewriteScalarizedStmts(
                            f.body(),
                            ownerFnId,
                            candidates,
                            scalarVars,
                            localLayouts,
                            module,
                            initBindings,
                            nextVarId);
            return new HirExpr.For(
                    iter,
                    f.target(),
                    f.loopVar(),
                    newDecls,
                    newBody,
                    f.bodyWideningRank(),
                    f.elemSamTarget(),
                    cr,
                    f.meta());
        }
        if (expr instanceof HirExpr.Try t) {
            List<HirVar> newTryDecls = expandDeclaredVars(t.tryDeclaredVars(), scalarVars);
            List<HirExpr> newTryBody =
                    rewriteScalarizedStmts(
                            t.tryBody(),
                            ownerFnId,
                            candidates,
                            scalarVars,
                            localLayouts,
                            module,
                            initBindings,
                            nextVarId);
            List<HirExpr.HirCatch> newCatches = new ArrayList<>(t.catches().size());
            for (HirExpr.HirCatch cc : t.catches()) {
                List<HirVar> cd = expandDeclaredVars(cc.declaredVars(), scalarVars);
                List<HirExpr> cb =
                        rewriteScalarizedStmts(
                                cc.body(),
                                ownerFnId,
                                candidates,
                                scalarVars,
                                localLayouts,
                                module,
                                initBindings,
                                nextVarId);
                newCatches.add(
                        new HirExpr.HirCatch(
                                cc.var(),
                                cc.target(),
                                cb,
                                cd,
                                cc.opensNamespaceScope(),
                                cc.declaredNames(),
                                cc.wideningRank(),
                                cc.line()));
            }
            List<HirVar> newFinDecls = expandDeclaredVars(t.finallyDeclaredVars(), scalarVars);
            List<HirExpr> newFinBody =
                    rewriteScalarizedStmts(
                            t.finallyBody(),
                            ownerFnId,
                            candidates,
                            scalarVars,
                            localLayouts,
                            module,
                            initBindings,
                            nextVarId);
            return new HirExpr.Try(
                    newTryBody,
                    newTryDecls,
                    t.tryOpensNamespaceScope(),
                    t.tryDeclaredNames(),
                    t.tryWideningRank(),
                    newCatches,
                    newFinBody,
                    newFinDecls,
                    t.finallyOpensNamespaceScope(),
                    t.finallyDeclaredNames(),
                    t.finallyNothrow(),
                    t.hasFinally(),
                    t.meta());
        }
        return HirRewriter.rewriteChildren(
                expr,
                c ->
                        rewriteScalarizedExpr(
                                c,
                                ownerFnId,
                                candidates,
                                scalarVars,
                                localLayouts,
                                module,
                                initBindings,
                                nextVarId));
    }

    private HirExpr emitDestructuredStore(
            HirExpr rhs,
            HirVar[] targetScalars,
            RecordLayout layout,
            boolean isDef,
            int line,
            int ownerFnId,
            Map<Integer, RecordLayout> candidates,
            Map<Integer, HirVar[]> scalarVars,
            Map<String, RecordLayout> localLayouts,
            HirModule module,
            Set<String> initBindings,
            int[] nextVarId) {
        if (rhs instanceof HirExpr.Move m) {
            return emitDestructuredStore(
                    m.operand(),
                    targetScalars,
                    layout,
                    isDef,
                    line,
                    ownerFnId,
                    candidates,
                    scalarVars,
                    localLayouts,
                    module,
                    initBindings,
                    nextVarId);
        }
        if (rhs instanceof HirExpr.Freeze f) {
            return emitDestructuredStore(
                    f.operand(),
                    targetScalars,
                    layout,
                    isDef,
                    line,
                    ownerFnId,
                    candidates,
                    scalarVars,
                    localLayouts,
                    module,
                    initBindings,
                    nextVarId);
        }
        ExprMeta voidMeta =
                new ExprMeta(line, PrimitiveKind.NONE, -1, null, EffectSet.EMPTY, true, false);
        if (rhs instanceof HirExpr.VarRead vr) {
            HirVar[] srcScalars = scalarVars.get(vr.var().id());
            if (srcScalars == targetScalars) {
                return new HirExpr.Const(null, voidMeta);
            }
            List<HirExpr> stmts = new ArrayList<>(targetScalars.length);
            for (int i = 0; i < targetScalars.length; i++) {
                PrimitiveKind fk = layout.paramKinds()[i];
                ExprMeta fm =
                        new ExprMeta(line, fk, -1, null, EffectSet.EMPTY, fk.isPrimitive(), false);
                HirExpr read = new HirExpr.VarRead(srcScalars[i], fm);
                stmts.add(makeScalarStore(targetScalars[i], read, isDef, line, fk));
            }
            return new HirExpr.Block(stmts, false, new String[0], List.of(), -1, voidMeta);
        }
        if (rhs instanceof HirExpr.RecordNew r) {
            int[] argMap = RecordLayout.resolveArgMapping(r);
            int n = layout.fieldCount();
            List<HirExpr> rewrittenArgs = new ArrayList<>(r.args().size());
            for (HirExpr arg : r.args()) {
                rewrittenArgs.add(
                        rewriteScalarizedExpr(
                                arg,
                                ownerFnId,
                                candidates,
                                scalarVars,
                                localLayouts,
                                module,
                                initBindings,
                                nextVarId));
            }
            List<HirExpr> stmts = new ArrayList<>();
            boolean skipClassRef =
                    (r.classRefIsInstance()
                                    && r.classRef() instanceof HirExpr.VarRead cr
                                    && scalarVars.containsKey(cr.var().id()))
                            || RecordLayout.isPureClassRef(
                                    r.classRef(), r.classRefIsInstance(), module, initBindings);
            if (!skipClassRef) {
                stmts.add(
                        rewriteScalarizedExpr(
                                r.classRef(),
                                ownerFnId,
                                candidates,
                                scalarVars,
                                localLayouts,
                                module,
                                initBindings,
                                nextVarId));
            }
            boolean inOrder = true;
            for (int i = 0; i < n; i++) {
                if (argMap[i] != i) {
                    inOrder = false;
                    break;
                }
            }
            // For simple in-order Def (not VarAssign), emit Def(targetScalars[i], arg[i]) directly
            // so
            // HirEGraphSimplifyPass can constant-propagate immutable fields.
            if (isDef && inOrder) {
                for (int i = 0; i < n; i++) {
                    HirExpr a = rewrittenArgs.get(i);
                    stmts.add(
                            makeScalarStore(
                                    targetScalars[i],
                                    a,
                                    true,
                                    a.meta().line(),
                                    layout.paramKinds()[i]));
                }
                return new HirExpr.Block(stmts, false, new String[0], List.of(), -1, voidMeta);
            }
            // Evaluate all arguments left-to-right into fresh temps before storing (safe for swaps
            // like p = Vec3(p.y, p.x)).
            List<HirVar> temps = new ArrayList<>(r.args().size());
            HirVar[] argTempBySourceIdx = new HirVar[r.args().size()];
            PrimitiveKind[] sourceKind = new PrimitiveKind[r.args().size()];
            for (int p = 0; p < n; p++) {
                sourceKind[argMap[p]] = layout.paramKinds()[p];
            }
            for (int aIdx = 0; aIdx < rewrittenArgs.size(); aIdx++) {
                HirExpr a = rewrittenArgs.get(aIdx);
                PrimitiveKind ak = sourceKind[aIdx] != null ? sourceKind[aIdx] : a.meta().kind();
                int tid = nextVarId[0]++;
                HirVar tmp =
                        new HirVar(
                                tid,
                                "$sr_t" + tid,
                                Resolution.Variable.Kind.DEFINITION,
                                ownerFnId,
                                ak,
                                false,
                                true,
                                false,
                                true);
                temps.add(tmp);
                argTempBySourceIdx[aIdx] = tmp;
                stmts.add(makeScalarStore(tmp, a, true, a.meta().line(), ak));
            }
            for (int p = 0; p < n; p++) {
                HirVar tmp = argTempBySourceIdx[argMap[p]];
                PrimitiveKind fk = layout.paramKinds()[p];
                ExprMeta rm =
                        new ExprMeta(line, fk, -1, null, EffectSet.EMPTY, fk.isPrimitive(), false);
                stmts.add(
                        makeScalarStore(
                                targetScalars[p], new HirExpr.VarRead(tmp, rm), isDef, line, fk));
            }
            return new HirExpr.Block(stmts, false, new String[0], temps, -1, voidMeta);
        }
        if (rhs instanceof HirExpr.Block b) {
            List<HirVar> newDecls = expandDeclaredVars(b.declaredVars(), scalarVars);
            int last = b.statements().size() - 1;
            List<HirExpr> stmts = new ArrayList<>(b.statements().size());
            for (int i = 0; i < last; i++) {
                stmts.add(
                        rewriteScalarizedExpr(
                                b.statements().get(i),
                                ownerFnId,
                                candidates,
                                scalarVars,
                                localLayouts,
                                module,
                                initBindings,
                                nextVarId));
            }
            stmts.add(
                    emitDestructuredStore(
                            b.statements().get(last),
                            targetScalars,
                            layout,
                            isDef,
                            line,
                            ownerFnId,
                            candidates,
                            scalarVars,
                            localLayouts,
                            module,
                            initBindings,
                            nextVarId));
            return new HirExpr.Block(stmts, false, new String[0], newDecls, -1, voidMeta);
        }
        if (rhs instanceof HirExpr.If i) {
            HirExpr cond =
                    rewriteScalarizedExpr(
                            i.cond(),
                            ownerFnId,
                            candidates,
                            scalarVars,
                            localLayouts,
                            module,
                            initBindings,
                            nextVarId);
            // Both branches store into already-declared outer targetScalars using VarAssign.
            HirExpr tb =
                    emitDestructuredStore(
                            i.thenBranch(),
                            targetScalars,
                            layout,
                            false,
                            line,
                            ownerFnId,
                            candidates,
                            scalarVars,
                            localLayouts,
                            module,
                            initBindings,
                            nextVarId);
            HirExpr eb =
                    emitDestructuredStore(
                            i.elseBranch(),
                            targetScalars,
                            layout,
                            false,
                            line,
                            ownerFnId,
                            candidates,
                            scalarVars,
                            localLayouts,
                            module,
                            initBindings,
                            nextVarId);
            return new HirExpr.If(cond, tb, eb, voidMeta);
        }
        throw new IllegalStateException(
                "ScalarReplacePass : unexpected destructured RHS "
                        + rhs.getClass().getSimpleName());
    }

    private static HirExpr makeScalarStore(
            HirVar target, HirExpr value, boolean isDef, int line, PrimitiveKind kind) {
        ExprMeta meta =
                new ExprMeta(
                        line,
                        kind,
                        value.meta().wideningRank(),
                        null,
                        EffectSet.EMPTY,
                        kind.isPrimitive(),
                        value.meta().stringType());
        if (isDef) {
            return new HirExpr.Def(
                    target.name(),
                    new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1),
                    target,
                    value,
                    meta);
        }
        return new HirExpr.VarAssign(target, null, value, target.name(), "", meta);
    }
}
