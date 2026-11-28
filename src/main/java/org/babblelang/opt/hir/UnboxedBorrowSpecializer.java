package org.babblelang.opt.hir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirRewriter;

/**
 * Sub-pass 2 of {@link ScalarReplacePass}: synthesizes flattened {@code $unboxed} companion
 * functions for non-inlined capture-free functions taking {@code borrow} {@code record} parameters
 * accessed solely via field projection, and rewrites qualifying {@link HirExpr.DirectBabbleCall}
 * sites.
 */
final class UnboxedBorrowSpecializer {
    private UnboxedBorrowSpecializer() {}

    private record UnboxPlan(
            HirFunction original,
            int unboxedFnId,
            RecordLayout[] paramLayouts // non-null for flattened borrow record params
            ) {}

    static HirModule unboxBorrowParams(
            HirModule module,
            Map<String, RecordLayout> localLayouts,
            Set<String> initBindings,
            int[] nextVarId,
            int[] nextOwnerId,
            int[] rewrites) {
        // 1. Identify candidate functions with borrow record parameters used solely via field
        // projection.
        Map<Integer, RecordLayout[]> candidates = new LinkedHashMap<>();
        for (HirFunction fn : module.functions().values()) {
            if (fn.isCompiledClassMethod()
                    || fn.namespaceScope()
                    || !fn.capturedVars().isEmpty()
                    || fn.restIndex() >= 0
                    || fn.optionalCount() > 0
                    || fn.params().isEmpty()
                    || fn.jvmMethodName().endsWith("$unboxed")) {
                continue;
            }
            if (containsSelfRecursion(fn.body()) || hasCapturedLocals(fn, module)) {
                continue;
            }
            RecordLayout[] plan = analyzeBorrowParams(fn, localLayouts);
            if (plan != null) {
                candidates.put(fn.id(), plan);
            }
        }
        if (candidates.isEmpty()) {
            return module;
        }

        // 2. Check which candidates have at least one qualifying DirectBabbleCall site.
        Set<Integer> usedCandidates = new LinkedHashSet<>();
        findQualifyingUnboxCalls(module, candidates, localLayouts, initBindings, usedCandidates);
        if (usedCandidates.isEmpty()) {
            return module;
        }

        // 3. Synthesize $unboxed companion HirFunctions.
        Map<Integer, HirFunction> newFns = new LinkedHashMap<>(module.functions());
        Map<Integer, UnboxPlan> plans = new LinkedHashMap<>();
        for (int fnId : usedCandidates) {
            HirFunction orig = module.function(fnId);
            RecordLayout[] layouts = candidates.get(fnId);
            int unboxedId = nextOwnerId[0]++;
            HirFunction unboxedFn = synthesizeUnboxedFunction(orig, unboxedId, layouts, nextVarId);
            newFns.put(unboxedId, unboxedFn);
            plans.put(fnId, new UnboxPlan(orig, unboxedId, layouts));
        }

        // 4. Rewrite qualifying DirectBabbleCall sites in top-level and all original functions.
        List<HirExpr> newTop =
                HirRewriter.rewriteList(
                        module.topLevelStatements(),
                        s -> rewriteUnboxCallExpr(s, plans, module, initBindings, rewrites));
        for (Map.Entry<Integer, HirFunction> e : new LinkedHashMap<>(newFns).entrySet()) {
            HirFunction fn = e.getValue();
            List<HirExpr> nb =
                    HirRewriter.rewriteList(
                            fn.body(),
                            s -> rewriteUnboxCallExpr(s, plans, module, initBindings, rewrites));
            if (nb != fn.body()) {
                newFns.put(e.getKey(), fn.withBody(nb));
            }
        }

        return module.withUpdates(
                newTop,
                newFns,
                module.classes(),
                module.interfaces(),
                nextVarId[0],
                nextOwnerId[0]);
    }

    private static boolean containsSelfRecursion(List<HirExpr> body) {
        boolean[] found = {false};
        for (HirExpr s : body) {
            scanSelfRecursion(s, found);
        }
        return found[0];
    }

    /**
     * Cloning a function into a new owner with remapped variable IDs would break any nested closure
     * or class expression that still captures the original function's {@link HirVar} IDs.
     */
    private static boolean hasCapturedLocals(HirFunction fn, HirModule module) {
        for (HirParam p : fn.params()) {
            if (p.var() != null && (p.var().isCaptured() || p.var().cell())) {
                return true;
            }
        }
        for (HirVar v : fn.declaredVars()) {
            if (v.isCaptured() || v.cell()) {
                return true;
            }
        }
        boolean[] found = {false};
        for (HirExpr s : fn.body()) {
            scanNestedCaptures(s, module, found);
            if (found[0]) {
                return true;
            }
        }
        return false;
    }

    private static void scanNestedCaptures(HirExpr expr, HirModule module, boolean[] found) {
        if (expr == null || found[0]) {
            return;
        }
        if (expr instanceof HirExpr.FunctionRef fr) {
            HirFunction inner = module.function(fr.functionId());
            if (inner != null && !inner.capturedVars().isEmpty()) {
                found[0] = true;
                return;
            }
        } else if (expr instanceof HirExpr.ClassExpr ce) {
            var cls = module.classes().get(ce.classId());
            if (cls != null && !cls.capturedVars().isEmpty()) {
                found[0] = true;
                return;
            }
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    scanNestedCaptures(child, module, found);
                    return child;
                });
    }

    private static void scanSelfRecursion(HirExpr expr, boolean[] found) {
        if (expr == null || found[0]) return;
        if (expr instanceof HirExpr.RecurseRef
                || expr instanceof HirExpr.SelfRecurseCall
                || expr instanceof HirExpr.SelfTailCall) {
            found[0] = true;
            return;
        }
        HirRewriter.rewriteChildren(
                expr,
                c -> {
                    scanSelfRecursion(c, found);
                    return c;
                });
    }

    private static RecordLayout[] analyzeBorrowParams(
            HirFunction fn, Map<String, RecordLayout> localLayouts) {
        List<HirParam> params = fn.params();
        Map<Integer, RecordLayout> borrowVarLayouts = new HashMap<>();
        RecordLayout[] result = new RecordLayout[params.size()];
        int totalFlattened = 0;
        int totalSlots = 3; // self, this, rt
        boolean anyFlattened = false;

        for (int i = 0; i < params.size(); i++) {
            HirParam p = params.get(i);
            RecordLayout layout =
                    (p.isBorrow()
                                    && p.recordTypeId() != null
                                    && p.var() != null
                                    && !p.var().isCaptured()
                                    && p.samTarget() == null)
                            ? localLayouts.get(p.recordTypeId())
                            : null;
            if (layout != null) {
                result[i] = layout;
                borrowVarLayouts.put(p.var().id(), layout);
                totalFlattened += layout.fieldCount();
                for (PrimitiveKind fk : layout.paramKinds()) {
                    totalSlots += fk.slots();
                }
                anyFlattened = true;
            } else {
                totalFlattened++;
                totalSlots += p.kind().slots();
            }
        }
        if (!anyFlattened || totalFlattened > HirFunction.MAX_SPREAD || totalSlots > 250) {
            return null;
        }

        // Verify each borrow param var is NEVER reassigned and ONLY read via valid ThisFieldGet.
        Set<Integer> disqualified = new HashSet<>();
        for (HirExpr stmt : fn.body()) {
            checkBorrowParamUses(stmt, borrowVarLayouts, disqualified);
        }
        if (!disqualified.isEmpty()) {
            anyFlattened = false;
            totalFlattened = 0;
            totalSlots = 3;
            for (int i = 0; i < params.size(); i++) {
                HirParam p = params.get(i);
                if (result[i] != null && disqualified.contains(p.var().id())) {
                    result[i] = null;
                }
                if (result[i] != null) {
                    anyFlattened = true;
                    totalFlattened += result[i].fieldCount();
                    for (PrimitiveKind fk : result[i].paramKinds()) {
                        totalSlots += fk.slots();
                    }
                } else {
                    totalFlattened++;
                    totalSlots += p.kind().slots();
                }
            }
            if (!anyFlattened || totalFlattened > HirFunction.MAX_SPREAD || totalSlots > 250) {
                return null;
            }
        }
        return result;
    }

    private static void checkBorrowParamUses(
            HirExpr expr, Map<Integer, RecordLayout> borrowVarLayouts, Set<Integer> disqualified) {
        if (expr == null) return;
        if (expr instanceof HirExpr.ThisFieldGet g && g.receiver() instanceof HirExpr.VarRead vr) {
            RecordLayout layout = borrowVarLayouts.get(vr.var().id());
            if (layout != null) {
                if (RecordLayout.matchingParamIndex(g, layout) < 0) {
                    disqualified.add(vr.var().id());
                }
                return;
            }
        }
        if (expr instanceof HirExpr.VarRead vr) {
            if (borrowVarLayouts.containsKey(vr.var().id())) {
                disqualified.add(vr.var().id());
            }
            return;
        }
        if (expr instanceof HirExpr.VarAssign va) {
            if (borrowVarLayouts.containsKey(va.var().id())) {
                disqualified.add(va.var().id());
            }
            checkBorrowParamUses(va.value(), borrowVarLayouts, disqualified);
            return;
        }
        HirRewriter.rewriteChildren(
                expr,
                c -> {
                    checkBorrowParamUses(c, borrowVarLayouts, disqualified);
                    return c;
                });
    }

    private static void findQualifyingUnboxCalls(
            HirModule module,
            Map<Integer, RecordLayout[]> candidates,
            Map<String, RecordLayout> localLayouts,
            Set<String> initBindings,
            Set<Integer> used) {
        for (HirExpr s : module.topLevelStatements()) {
            scanUnboxCalls(s, candidates, module, initBindings, used);
        }
        for (HirFunction fn : module.functions().values()) {
            for (HirExpr s : fn.body()) {
                scanUnboxCalls(s, candidates, module, initBindings, used);
            }
        }
    }

    private static void scanUnboxCalls(
            HirExpr expr,
            Map<Integer, RecordLayout[]> candidates,
            HirModule module,
            Set<String> initBindings,
            Set<Integer> used) {
        if (expr == null) return;
        if (expr instanceof HirExpr.DirectBabbleCall c && c.targetFunctionId() >= 0) {
            RecordLayout[] plan = candidates.get(c.targetFunctionId());
            if (plan != null && canRewriteCallToUnboxed(c, plan, module, initBindings)) {
                used.add(c.targetFunctionId());
            }
        }
        HirRewriter.rewriteChildren(
                expr,
                child -> {
                    scanUnboxCalls(child, candidates, module, initBindings, used);
                    return child;
                });
    }

    private static boolean canRewriteCallToUnboxed(
            HirExpr.DirectBabbleCall c,
            RecordLayout[] plan,
            HirModule module,
            Set<String> initBindings) {
        if (c.meta().checkedType() != null
                || c.args().size() != plan.length
                || (c.binding() != null && !c.binding().isIdentityPositional())) {
            return false;
        }
        for (int i = 0; i < plan.length; i++) {
            RecordLayout layout = plan[i];
            if (layout == null) {
                continue;
            }
            HirExpr arg = RecordLayout.unwrapOwnership(c.args().get(i));
            if (arg instanceof HirExpr.RecordNew r) {
                if (!r.classTypeId().equals(layout.classTypeId())
                        || !RecordLayout.isPureClassRef(
                                r.classRef(), r.classRefIsInstance(), module, initBindings)
                        || (r.binding() != null && !r.binding().isIdentityPositional())
                        || r.args().size() != layout.fieldCount()) {
                    return false;
                }
            } else if (arg instanceof HirExpr.VarRead vr) {
                if (vr.var().isCaptured()
                        || (vr.var().mayBeUndefined() && !vr.var().definitelyDefined())) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private static HirFunction synthesizeUnboxedFunction(
            HirFunction orig, int unboxedId, RecordLayout[] plan, int[] nextVarId) {
        Map<Integer, HirVar> varRemap = new HashMap<>();
        Map<Integer, HirVar[]> flattenedParamVars = new HashMap<>();
        Map<Integer, RecordLayout> flattenedLayouts = new HashMap<>();
        List<HirParam> newParams = new ArrayList<>();

        List<HirVar> newDeclaredVars = new ArrayList<>();
        for (int i = 0; i < orig.params().size(); i++) {
            HirParam p = orig.params().get(i);
            RecordLayout layout = plan[i];
            if (layout != null) {
                HirVar[] fieldVars = new HirVar[layout.fieldCount()];
                for (int f = 0; f < layout.fieldCount(); f++) {
                    int id = nextVarId[0]++;
                    String fname = p.name() + "$" + layout.fieldNames()[f];
                    PrimitiveKind fk = layout.paramKinds()[f];
                    HirVar fv =
                            new HirVar(
                                    id,
                                    fname,
                                    Resolution.Variable.Kind.PARAMETER,
                                    unboxedId,
                                    fk,
                                    false,
                                    true,
                                    false,
                                    true);
                    fieldVars[f] = fv;
                    newDeclaredVars.add(fv);
                    newParams.add(
                            new HirParam(
                                    fname,
                                    fv,
                                    fk,
                                    -1,
                                    null,
                                    -1,
                                    null,
                                    null,
                                    LoweringTargets.ParamOwnership.DEFAULT,
                                    p.line()));
                }
                flattenedParamVars.put(p.var().id(), fieldVars);
                flattenedLayouts.put(p.var().id(), layout);
            } else {
                HirVar newVar = null;
                if (p.var() != null) {
                    int id = nextVarId[0]++;
                    newVar = p.var().withIdAndOwner(id, unboxedId);
                    varRemap.put(p.var().id(), newVar);
                    newDeclaredVars.add(newVar);
                }
                newParams.add(
                        new HirParam(
                                p.name(),
                                newVar,
                                p.kind(),
                                p.wideningRank(),
                                p.checkedType(),
                                p.memberIndex(),
                                p.defaultValue(),
                                p.samTarget(),
                                p.ownership(),
                                p.mutable(),
                                p.line()));
            }
        }

        for (HirVar v : orig.declaredVars()) {
            if (flattenedParamVars.containsKey(v.id()) || varRemap.containsKey(v.id())) {
                continue;
            }
            HirVar mapped = v.withIdAndOwner(nextVarId[0]++, unboxedId);
            varRemap.put(v.id(), mapped);
            newDeclaredVars.add(mapped);
        }
        List<HirExpr> newBody =
                rewriteUnboxedBody(
                        orig.body(),
                        unboxedId,
                        varRemap,
                        flattenedParamVars,
                        flattenedLayouts,
                        nextVarId);

        return new HirFunction(
                unboxedId,
                orig.name() != null ? orig.name() + "$unboxed" : null,
                orig.jvmMethodName() + "$unboxed",
                -1,
                null,
                false,
                new String[0],
                newDeclaredVars,
                List.of(),
                newParams,
                newParams.size(),
                newParams.size(),
                -1,
                orig.returnKind(),
                orig.bodyWideningRank(),
                orig.effects(),
                newBody,
                orig.line());
    }

    private static List<HirVar> remapVarList(
            List<HirVar> vars, int newOwnerId, Map<Integer, HirVar> varRemap, int[] nextVarId) {
        if (vars.isEmpty()) {
            return List.of();
        }
        List<HirVar> out = new ArrayList<>(vars.size());
        for (HirVar v : vars) {
            HirVar mapped = varRemap.get(v.id());
            if (mapped == null) {
                mapped = v.withIdAndOwner(nextVarId[0]++, newOwnerId);
                varRemap.put(v.id(), mapped);
            }
            out.add(mapped);
        }
        return out;
    }

    private static List<HirExpr> rewriteUnboxedBody(
            List<HirExpr> stmts,
            int newOwnerId,
            Map<Integer, HirVar> varRemap,
            Map<Integer, HirVar[]> flattenedParamVars,
            Map<Integer, RecordLayout> flattenedLayouts,
            int[] nextVarId) {
        List<HirExpr> out = new ArrayList<>(stmts.size());
        for (HirExpr s : stmts) {
            out.add(
                    rewriteUnboxedExpr(
                            s,
                            newOwnerId,
                            varRemap,
                            flattenedParamVars,
                            flattenedLayouts,
                            nextVarId));
        }
        return out;
    }

    private static HirExpr rewriteUnboxedExpr(
            HirExpr expr,
            int newOwnerId,
            Map<Integer, HirVar> varRemap,
            Map<Integer, HirVar[]> flattenedParamVars,
            Map<Integer, RecordLayout> flattenedLayouts,
            int[] nextVarId) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof HirExpr.ThisFieldGet g && g.receiver() instanceof HirExpr.VarRead vr) {
            HirVar[] fieldVars = flattenedParamVars.get(vr.var().id());
            if (fieldVars != null) {
                RecordLayout layout = flattenedLayouts.get(vr.var().id());
                int pIdx = RecordLayout.matchingParamIndex(g, layout);
                HirVar fv = fieldVars[pIdx];
                return new HirExpr.VarRead(fv, g.meta());
            }
        }
        if (expr instanceof HirExpr.VarRead vr) {
            HirVar mapped = varRemap.get(vr.var().id());
            return mapped != null ? new HirExpr.VarRead(mapped, vr.meta()) : vr;
        }
        if (expr instanceof HirExpr.Def d) {
            HirVar mapped = d.var() != null ? varRemap.getOrDefault(d.var().id(), d.var()) : null;
            HirExpr nv =
                    rewriteUnboxedExpr(
                            d.value(),
                            newOwnerId,
                            varRemap,
                            flattenedParamVars,
                            flattenedLayouts,
                            nextVarId);
            return new HirExpr.Def(d.name(), d.target(), mapped, nv, d.meta());
        }
        if (expr instanceof HirExpr.VarAssign a) {
            HirVar mapped = varRemap.getOrDefault(a.var().id(), a.var());
            HirExpr nv =
                    rewriteUnboxedExpr(
                            a.value(),
                            newOwnerId,
                            varRemap,
                            flattenedParamVars,
                            flattenedLayouts,
                            nextVarId);
            return new HirExpr.VarAssign(
                    mapped, a.operator(), nv, a.targetText(), a.valueText(), a.meta());
        }
        if (expr instanceof HirExpr.Move m) {
            HirVar mapped = m.var() != null ? varRemap.getOrDefault(m.var().id(), m.var()) : null;
            HirExpr op =
                    rewriteUnboxedExpr(
                            m.operand(),
                            newOwnerId,
                            varRemap,
                            flattenedParamVars,
                            flattenedLayouts,
                            nextVarId);
            return new HirExpr.Move(mapped, op, m.meta());
        }
        if (expr instanceof HirExpr.Block b) {
            List<HirVar> newDecls = remapVarList(b.declaredVars(), newOwnerId, varRemap, nextVarId);
            List<HirExpr> ns =
                    rewriteUnboxedBody(
                            b.statements(),
                            newOwnerId,
                            varRemap,
                            flattenedParamVars,
                            flattenedLayouts,
                            nextVarId);
            return new HirExpr.Block(
                    ns,
                    b.opensNamespaceScope(),
                    b.declaredNames(),
                    newDecls,
                    b.innerWideningRank(),
                    b.meta());
        }
        if (expr instanceof HirExpr.For f) {
            HirExpr iter =
                    f.iterable() != null
                            ? rewriteUnboxedExpr(
                                    f.iterable(),
                                    newOwnerId,
                                    varRemap,
                                    flattenedParamVars,
                                    flattenedLayouts,
                                    nextVarId)
                            : null;
            HirExpr.CountedRangeLoop cr = f.countedRange();
            if (cr != null) {
                cr =
                        new HirExpr.CountedRangeLoop(
                                rewriteUnboxedExpr(
                                        cr.start(),
                                        newOwnerId,
                                        varRemap,
                                        flattenedParamVars,
                                        flattenedLayouts,
                                        nextVarId),
                                rewriteUnboxedExpr(
                                        cr.end(),
                                        newOwnerId,
                                        varRemap,
                                        flattenedParamVars,
                                        flattenedLayouts,
                                        nextVarId),
                                cr.step() != null
                                        ? rewriteUnboxedExpr(
                                                cr.step(),
                                                newOwnerId,
                                                varRemap,
                                                flattenedParamVars,
                                                flattenedLayouts,
                                                nextVarId)
                                        : null,
                                cr.inclusive(),
                                cr.descending(),
                                cr.elemKind());
            }
            HirVar loopVar =
                    f.loopVar() != null
                            ? remapVarList(List.of(f.loopVar()), newOwnerId, varRemap, nextVarId)
                                    .get(0)
                            : null;
            List<HirVar> decls =
                    remapVarList(f.bodyDeclaredVars(), newOwnerId, varRemap, nextVarId);
            List<HirExpr> nb =
                    rewriteUnboxedBody(
                            f.body(),
                            newOwnerId,
                            varRemap,
                            flattenedParamVars,
                            flattenedLayouts,
                            nextVarId);
            return new HirExpr.For(
                    iter,
                    f.target(),
                    loopVar,
                    decls,
                    nb,
                    f.bodyWideningRank(),
                    f.elemSamTarget(),
                    cr,
                    f.meta());
        }
        if (expr instanceof HirExpr.Try t) {
            List<HirVar> td = remapVarList(t.tryDeclaredVars(), newOwnerId, varRemap, nextVarId);
            List<HirExpr> tb =
                    rewriteUnboxedBody(
                            t.tryBody(),
                            newOwnerId,
                            varRemap,
                            flattenedParamVars,
                            flattenedLayouts,
                            nextVarId);
            List<HirExpr.HirCatch> ncs = new ArrayList<>(t.catches().size());
            for (HirExpr.HirCatch cc : t.catches()) {
                HirVar cv =
                        cc.var() != null
                                ? remapVarList(List.of(cc.var()), newOwnerId, varRemap, nextVarId)
                                        .get(0)
                                : null;
                List<HirVar> cd = remapVarList(cc.declaredVars(), newOwnerId, varRemap, nextVarId);
                List<HirExpr> cb =
                        rewriteUnboxedBody(
                                cc.body(),
                                newOwnerId,
                                varRemap,
                                flattenedParamVars,
                                flattenedLayouts,
                                nextVarId);
                ncs.add(
                        new HirExpr.HirCatch(
                                cv,
                                cc.target(),
                                cb,
                                cd,
                                cc.opensNamespaceScope(),
                                cc.declaredNames(),
                                cc.wideningRank(),
                                cc.line()));
            }
            List<HirVar> fd =
                    remapVarList(t.finallyDeclaredVars(), newOwnerId, varRemap, nextVarId);
            List<HirExpr> fb =
                    rewriteUnboxedBody(
                            t.finallyBody(),
                            newOwnerId,
                            varRemap,
                            flattenedParamVars,
                            flattenedLayouts,
                            nextVarId);
            return new HirExpr.Try(
                    tb,
                    td,
                    t.tryOpensNamespaceScope(),
                    t.tryDeclaredNames(),
                    t.tryWideningRank(),
                    ncs,
                    fb,
                    fd,
                    t.finallyOpensNamespaceScope(),
                    t.finallyDeclaredNames(),
                    t.finallyNothrow(),
                    t.hasFinally(),
                    t.meta());
        }
        return HirRewriter.rewriteChildren(
                expr,
                c ->
                        rewriteUnboxedExpr(
                                c,
                                newOwnerId,
                                varRemap,
                                flattenedParamVars,
                                flattenedLayouts,
                                nextVarId));
    }

    private static HirExpr rewriteUnboxCallExpr(
            HirExpr expr,
            Map<Integer, UnboxPlan> plans,
            HirModule module,
            Set<String> initBindings,
            int[] rewrites) {
        HirExpr cur =
                HirRewriter.rewriteChildren(
                        expr, c -> rewriteUnboxCallExpr(c, plans, module, initBindings, rewrites));
        if (!(cur instanceof HirExpr.DirectBabbleCall c) || c.targetFunctionId() < 0) {
            return cur;
        }
        UnboxPlan plan = plans.get(c.targetFunctionId());
        if (plan == null
                || !canRewriteCallToUnboxed(c, plan.paramLayouts(), module, initBindings)) {
            return cur;
        }
        List<HirExpr> flatArgs = new ArrayList<>();
        RecordLayout[] layouts = plan.paramLayouts();
        for (int i = 0; i < layouts.length; i++) {
            RecordLayout layout = layouts[i];
            HirExpr arg = c.args().get(i);
            if (layout == null) {
                flatArgs.add(arg);
                continue;
            }
            HirExpr unwrapped = RecordLayout.unwrapOwnership(arg);
            if (unwrapped instanceof HirExpr.RecordNew r) {
                flatArgs.addAll(r.args());
            } else if (unwrapped instanceof HirExpr.VarRead vr) {
                for (int f = 0; f < layout.fieldCount(); f++) {
                    PrimitiveKind fk = layout.paramKinds()[f];
                    ExprMeta fm =
                            new ExprMeta(
                                    c.meta().line(),
                                    fk,
                                    -1,
                                    null,
                                    EffectSet.EMPTY,
                                    fk.isPrimitive(),
                                    false);
                    flatArgs.add(
                            new HirExpr.ThisFieldGet(
                                    vr,
                                    vr.var().name(),
                                    layout.classId(),
                                    layout.classId() < 0 ? layout.classJvmName() : null,
                                    layout.memberIndices()[f],
                                    layout.fieldNames()[f],
                                    fk,
                                    true,
                                    true,
                                    fm));
                }
            }
        }
        rewrites[0]++;
        return new HirExpr.DirectBabbleCall(
                c.callee(), plan.unboxedFnId(), flatArgs, null, c.meta());
    }
}
