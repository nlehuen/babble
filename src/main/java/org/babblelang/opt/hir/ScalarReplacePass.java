package org.babblelang.opt.hir;

import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code O9} — Scalar replacement of aggregates (SROA) for non-escaping {@code record} values
 * and flattened {@code $unboxed} entry points for non-inlined {@code borrow} {@code record}
 * parameters ({@code BIP014-I4}).
 *
 * <p>Consists of three cooperating sub-passes over {@link HirModule}:
 * <ol>
 *   <li><b>Projection beta-reduction</b>: {@code ThisFieldGet(RecordNew(C, args), p)} (including
 *       through non-namespace {@code Block}s produced by {@link InlinePass}) reduces directly to
 *       {@code args[p]} while preserving exact left-to-right side effects.</li>
 *   <li><b>Flattened {@code $unboxed} entry points</b>: for non-method, capture-free functions
 *       taking {@code borrow} {@code record} parameters accessed solely via field projection,
 *       synthesizes a companion {@code HirFunction} accepting the record's fields directly and
 *       rewrites qualifying {@link HirExpr.DirectBabbleCall} sites.</li>
 *   <li><b>Non-escaping local scalarization</b>: replaces non-escaping local {@code record}
 *       variables (including loop-carried variables reassigned to fresh {@code RecordNew}s) with
 *       per-field primitive/reference locals across all 8 {@code PrimitiveKind}s.</li>
 * </ol>
 */
public final class ScalarReplacePass implements HirPass {
    @Override
    public String name() {
        return "ScalarReplace";
    }

    private record RecordLayout(
            int classId,
            String classTypeId,
            String classJvmName,
            PrimitiveKind[] paramKinds,
            int[] memberIndices,
            String[] fieldNames
    ) {
        int fieldCount() {
            return paramKinds.length;
        }

        int paramIndexForMember(int memberIndex) {
            for (int i = 0; i < memberIndices.length; i++) {
                if (memberIndices[i] == memberIndex) {
                    return i;
                }
            }
            return -1;
        }

        boolean sameType(RecordLayout other) {
            return other != null && classTypeId.equals(other.classTypeId)
                    && paramKinds.length == other.paramKinds.length;
        }
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] nextVarId = {module.nextVarId()};
        int[] nextOwnerId = {module.nextOwnerId()};
        int[] rewrites = {0};

        Map<String, RecordLayout> layoutsByTypeId = collectRecordLayouts(module);
        Set<String> initBindings = HirPurity.findDefinitelyInitializedTopLevelBindings(module);

        // Sub-pass 1: Projection beta-reduction everywhere in module.
        HirModule cur = betaReduceModule(module, layoutsByTypeId, initBindings, nextVarId, rewrites);

        // Sub-pass 2: Flattened $unboxed entry points for non-inlined borrow record parameters.
        cur = unboxBorrowParams(cur, layoutsByTypeId, initBindings, nextVarId, nextOwnerId, rewrites);

        // Sub-pass 3: Non-escaping local record scalarization inside frame-scoped functions.
        cur = scalarizeLocalRecords(cur, layoutsByTypeId, initBindings, nextVarId, rewrites);

        if (rewrites[0] == 0) {
            return Result.unchanged(module);
        }
        return new Result(cur, rewrites[0]);
    }

    // ---------------------------------------------------------------- layout discovery

    private static Map<String, RecordLayout> collectRecordLayouts(HirModule module) {
        Map<String, RecordLayout> out = new LinkedHashMap<>();
        for (HirClass cls : module.classes().values()) {
            if (!cls.fastRecord() || cls.params().isEmpty()) {
                continue;
            }
            int n = cls.params().size();
            PrimitiveKind[] kinds = new PrimitiveKind[n];
            int[] memberIndices = new int[n];
            String[] fieldNames = new String[n];
            for (int i = 0; i < n; i++) {
                HirParam p = cls.params().get(i);
                int mi = p.memberIndex();
                kinds[i] = mi >= 0 && mi < cls.memberKinds().length ? cls.memberKinds()[mi] : p.kind();
                memberIndices[i] = mi;
                fieldNames[i] = p.name();
            }
            out.put(cls.shape().id(), new RecordLayout(cls.id(), cls.shape().id(), cls.jvmName(), kinds, memberIndices, fieldNames));
        }
        return out;
    }

    private static RecordLayout layoutOf(HirExpr.RecordNew r, Map<String, RecordLayout> localLayouts, HirModule module) {
        RecordLayout local = localLayouts.get(r.classTypeId());
        if (local != null) {
            return local;
        }
        int n = r.paramKinds().length;
        if (n == 0) {
            return null;
        }
        int[] memberIndices = new int[n];
        String[] fieldNames = new String[n];
        for (int i = 0; i < n; i++) {
            memberIndices[i] = i;
            fieldNames[i] = "f" + i;
        }
        return new RecordLayout(r.classId(), r.classTypeId(), r.classJvmName(), r.paramKinds(), memberIndices, fieldNames);
    }

    /** Maps parameter index {@code p} to source argument index in {@code r.args()}, or {@code null} if unsupported. */
    private static int[] resolveArgMapping(HirExpr.RecordNew r) {
        int n = r.paramKinds().length;
        LoweringTargets.ArgumentBinding b = r.binding();
        if (b == null || b.restParam() >= 0 || b.missingParamBits() != 0) {
            return null;
        }
        int[] p2a = b.paramToArg();
        if (p2a.length < n) {
            return null;
        }
        int[] map = new int[n];
        for (int i = 0; i < n; i++) {
            map[i] = p2a[i];
        }
        return map;
    }

    private static boolean isPureClassRef(HirExpr classRef, boolean isInstance, HirModule module, Set<String> initBindings) {
        if (classRef instanceof HirExpr.NameRead nr) {
            return (nr.finalBinding() && nr.definitelyDefined()) || initBindings.contains(nr.name());
        }
        if (classRef instanceof HirExpr.ClassExpr) {
            return true;
        }
        if (classRef instanceof HirExpr.ModuleExportGet meg) {
            return meg.receiver() instanceof HirExpr.NameRead || HirPurity.isPureNothrowTerminating(meg.receiver(), module);
        }
        return HirPurity.isPureNothrowTerminating(classRef, module);
    }

    private static int matchingParamIndex(HirExpr.ThisFieldGet g, RecordLayout layout) {
        if (g.meta().checkedType() != null) {
            return -1;
        }
        boolean typeMatch = (layout.classId() >= 0 && g.classId() == layout.classId())
                || (g.externalClassJvmName() != null && g.externalClassJvmName().equals(layout.classJvmName()));
        if (!typeMatch || (g.receiverText() != null && (!g.immutableField() || !g.definitelyDefined()))) {
            return -1;
        }
        return layout.paramIndexForMember(g.memberIndex());
    }

    // ---------------------------------------------------------------- Sub-pass 1: projection beta-reduction

    private HirModule betaReduceModule(HirModule module, Map<String, RecordLayout> localLayouts,
                                       Set<String> initBindings, int[] nextVarId, int[] rewrites) {
        boolean[] changed = {false};
        List<HirExpr> newTop = HirRewriter.rewriteList(module.topLevelStatements(),
                s -> betaReduceExpr(s, -1, module, localLayouts, initBindings, nextVarId, rewrites, changed));
        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            int ownerId = fn.namespaceScope() ? -1 : fn.id();
            List<HirExpr> nb = HirRewriter.rewriteList(fn.body(),
                    s -> betaReduceExpr(s, ownerId, module, localLayouts, initBindings, nextVarId, rewrites, changed));
            if (nb != fn.body()) {
                fn = fn.withBody(nb);
            }
            newFns.put(e.getKey(), fn);
        }
        if (!changed[0]) {
            return module;
        }
        return module.withUpdates(newTop, newFns, module.classes(), module.interfaces(),
                nextVarId[0], module.nextOwnerId());
    }

    private HirExpr betaReduceExpr(HirExpr expr, int ownerFnId, HirModule module,
                                   Map<String, RecordLayout> localLayouts, Set<String> initBindings,
                                   int[] nextVarId, int[] rewrites, boolean[] changed) {
        HirExpr cur = HirRewriter.rewriteChildren(expr,
                c -> betaReduceExpr(c, ownerFnId, module, localLayouts, initBindings, nextVarId, rewrites, changed));
        if (!(cur instanceof HirExpr.ThisFieldGet g) || g.meta().checkedType() != null) {
            return cur;
        }
        HirExpr recv = unwrapOwnership(g.receiver());
        if (recv instanceof HirExpr.RecordNew r) {
            HirExpr reduced = reduceRecordProjection(g, r, ownerFnId, module, localLayouts, initBindings, nextVarId);
            if (reduced != null) {
                rewrites[0]++;
                changed[0] = true;
                return reduced;
            }
        } else if (recv instanceof HirExpr.Block b && !b.opensNamespaceScope() && !b.statements().isEmpty()
                && b.innerWideningRank() < 0 && b.meta().wideningRank() < 0 && b.meta().checkedType() == null) {
            int last = b.statements().size() - 1;
            HirExpr tail = unwrapOwnership(b.statements().get(last));
            if (tail instanceof HirExpr.RecordNew r) {
                HirExpr reduced = reduceRecordProjection(g, r, ownerFnId, module, localLayouts, initBindings, nextVarId);
                if (reduced != null) {
                    List<HirExpr> stmts = new ArrayList<>(b.statements());
                    stmts.set(last, reduced);
                    rewrites[0]++;
                    changed[0] = true;
                    return new HirExpr.Block(stmts, false, new String[0], b.declaredVars(), -1, g.meta());
                }
            }
        }
        return cur;
    }

    private static HirExpr unwrapOwnership(HirExpr expr) {
        while (true) {
            if (expr instanceof HirExpr.Move m) {
                expr = m.operand();
            } else if (expr instanceof HirExpr.Freeze f) {
                expr = f.operand();
            } else if (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr() && b.meta().checkedType() == null) {
                expr = b.statements().get(0);
            } else {
                return expr;
            }
        }
    }

    private HirExpr reduceRecordProjection(HirExpr.ThisFieldGet g, HirExpr.RecordNew r, int ownerFnId,
                                           HirModule module, Map<String, RecordLayout> localLayouts,
                                           Set<String> initBindings, int[] nextVarId) {
        RecordLayout layout = layoutOf(r, localLayouts, module);
        if (layout == null) {
            return null;
        }
        int p = matchingParamIndex(g, layout);
        if (p < 0) {
            return null;
        }
        int[] argMap = resolveArgMapping(r);
        if (argMap == null) {
            return null;
        }
        int chosenIdx = argMap[p];
        HirExpr chosenArg = r.args().get(chosenIdx);
        PrimitiveKind fieldKind = layout.paramKinds()[p];

        boolean classRefPure = isPureClassRef(r.classRef(), r.classRefIsInstance(), module, initBindings);
        boolean allSiblingsPure = true;
        for (int i = 0; i < r.args().size(); i++) {
            if (i != chosenIdx && !HirPurity.isPureNothrowTerminating(r.args().get(i), module)) {
                allSiblingsPure = false;
                break;
            }
        }

        if (classRefPure && allSiblingsPure) {
            return adaptProjectedArg(chosenArg, fieldKind, g.meta());
        }
        // Preserve left-to-right side effects only inside frame-scoped functions where we can bind a temp local.
        if (ownerFnId < 0) {
            return null;
        }
        List<HirExpr> stmts = new ArrayList<>();
        List<HirVar> temps = new ArrayList<>();
        if (!classRefPure) {
            stmts.add(r.classRef());
        }
        for (int i = 0; i < chosenIdx; i++) {
            if (!HirPurity.isPureNothrowTerminating(r.args().get(i), module)) {
                stmts.add(r.args().get(i));
            }
        }
        int tmpId = nextVarId[0]++;
        HirVar tmp = new HirVar(tmpId, "$sr_p" + tmpId, Resolution.Variable.Kind.DEFINITION, ownerFnId,
                fieldKind, false, true, false, true);
        temps.add(tmp);
        ExprMeta defMeta = new ExprMeta(chosenArg.meta().line(), fieldKind, chosenArg.meta().wideningRank(),
                null, EffectSet.EMPTY, g.meta().deeplyImmutable(), g.meta().stringType());
        stmts.add(new HirExpr.Def(tmp.name(), new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1),
                tmp, chosenArg, defMeta));
        for (int i = chosenIdx + 1; i < r.args().size(); i++) {
            if (!HirPurity.isPureNothrowTerminating(r.args().get(i), module)) {
                stmts.add(r.args().get(i));
            }
        }
        ExprMeta readMeta = new ExprMeta(g.meta().line(), fieldKind, g.meta().wideningRank(),
                null, EffectSet.EMPTY, g.meta().deeplyImmutable(), g.meta().stringType());
        stmts.add(new HirExpr.VarRead(tmp, readMeta));
        return new HirExpr.Block(stmts, false, new String[0], temps, -1, g.meta());
    }

    private static HirExpr adaptProjectedArg(HirExpr arg, PrimitiveKind fieldKind, ExprMeta getMeta) {
        int fieldRank = fieldKind.numericRank();
        boolean kindMatch = arg.meta().kind() == fieldKind
                || (fieldRank >= 1 && arg.meta().wideningRank() == fieldRank);
        if (getMeta.wideningRank() < 0 && kindMatch) {
            return arg;
        }
        int targetRank = getMeta.wideningRank() >= 0 ? getMeta.wideningRank()
                : (arg.meta().kind() != fieldKind && fieldRank >= 1 ? fieldRank : -1);
        ExprMeta wrapMeta = new ExprMeta(getMeta.line(), fieldKind, targetRank, null,
                arg.meta().effects(), getMeta.deeplyImmutable(), getMeta.stringType(),
                arg.meta().frozen(), arg.meta().unique());
        return new HirExpr.Block(List.of(arg), false, new String[0], List.of(), -1, wrapMeta);
    }

    // ---------------------------------------------------------------- Sub-pass 2: flattened $unboxed borrow entry points

    private record UnboxPlan(
            HirFunction original,
            int unboxedFnId,
            RecordLayout[] paramLayouts // non-null for flattened borrow record params
    ) {
    }

    private HirModule unboxBorrowParams(HirModule module, Map<String, RecordLayout> localLayouts,
                                        Set<String> initBindings, int[] nextVarId, int[] nextOwnerId, int[] rewrites) {
        // 1. Identify candidate functions with borrow record parameters used solely via field projection.
        Map<Integer, RecordLayout[]> candidates = new LinkedHashMap<>();
        for (HirFunction fn : module.functions().values()) {
            if (fn.isCompiledClassMethod() || fn.namespaceScope() || !fn.capturedVars().isEmpty()
                    || fn.restIndex() >= 0 || fn.optionalCount() > 0 || fn.params().isEmpty()
                    || fn.jvmMethodName().endsWith("$unboxed")) {
                continue;
            }
            if (containsSelfRecursion(fn.body())) {
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
        Set<Integer> usedCandidates = new HashSet<>();
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
        List<HirExpr> newTop = HirRewriter.rewriteList(module.topLevelStatements(),
                s -> rewriteUnboxCallExpr(s, plans, module, initBindings, rewrites));
        for (Map.Entry<Integer, HirFunction> e : new LinkedHashMap<>(newFns).entrySet()) {
            HirFunction fn = e.getValue();
            List<HirExpr> nb = HirRewriter.rewriteList(fn.body(),
                    s -> rewriteUnboxCallExpr(s, plans, module, initBindings, rewrites));
            if (nb != fn.body()) {
                newFns.put(e.getKey(), fn.withBody(nb));
            }
        }

        return module.withUpdates(newTop, newFns, module.classes(), module.interfaces(),
                nextVarId[0], nextOwnerId[0]);
    }

    private static boolean containsSelfRecursion(List<HirExpr> body) {
        boolean[] found = {false};
        for (HirExpr s : body) {
            scanSelfRecursion(s, found);
        }
        return found[0];
    }

    private static void scanSelfRecursion(HirExpr expr, boolean[] found) {
        if (expr == null || found[0]) return;
        if (expr instanceof HirExpr.RecurseRef || expr instanceof HirExpr.SelfRecurseCall || expr instanceof HirExpr.SelfTailCall) {
            found[0] = true;
            return;
        }
        HirRewriter.rewriteChildren(expr, c -> {
            scanSelfRecursion(c, found);
            return c;
        });
    }

    private static RecordLayout[] analyzeBorrowParams(HirFunction fn, Map<String, RecordLayout> localLayouts) {
        List<HirParam> params = fn.params();
        Map<Integer, RecordLayout> borrowVarLayouts = new HashMap<>();
        RecordLayout[] result = new RecordLayout[params.size()];
        int totalFlattened = 0;
        int totalSlots = 3; // self, this, rt
        boolean anyFlattened = false;

        for (int i = 0; i < params.size(); i++) {
            HirParam p = params.get(i);
            RecordLayout layout = (p.isBorrow() && p.recordTypeId() != null && p.var() != null && !p.var().isCaptured()
                    && p.samTarget() == null)
                    ? localLayouts.get(p.recordTypeId()) : null;
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

    private static void checkBorrowParamUses(HirExpr expr, Map<Integer, RecordLayout> borrowVarLayouts,
                                             Set<Integer> disqualified) {
        if (expr == null) return;
        if (expr instanceof HirExpr.ThisFieldGet g && g.receiver() instanceof HirExpr.VarRead vr) {
            RecordLayout layout = borrowVarLayouts.get(vr.var().id());
            if (layout != null) {
                if (matchingParamIndex(g, layout) < 0) {
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
        HirRewriter.rewriteChildren(expr, c -> {
            checkBorrowParamUses(c, borrowVarLayouts, disqualified);
            return c;
        });
    }

    private static void findQualifyingUnboxCalls(HirModule module, Map<Integer, RecordLayout[]> candidates,
                                                 Map<String, RecordLayout> localLayouts, Set<String> initBindings,
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

    private static void scanUnboxCalls(HirExpr expr, Map<Integer, RecordLayout[]> candidates,
                                       HirModule module, Set<String> initBindings, Set<Integer> used) {
        if (expr == null) return;
        if (expr instanceof HirExpr.DirectBabbleCall c) {
            RecordLayout[] plan = candidates.get(c.targetFunctionId());
            if (plan != null && canRewriteCallToUnboxed(c, plan, module, initBindings)) {
                used.add(c.targetFunctionId());
            }
        }
        HirRewriter.rewriteChildren(expr, child -> {
            scanUnboxCalls(child, candidates, module, initBindings, used);
            return child;
        });
    }

    private static boolean canRewriteCallToUnboxed(HirExpr.DirectBabbleCall c, RecordLayout[] plan,
                                                   HirModule module, Set<String> initBindings) {
        if (c.meta().checkedType() != null || c.args().size() != plan.length
                || (c.binding() != null && !c.binding().isIdentityPositional())) {
            return false;
        }
        for (int i = 0; i < plan.length; i++) {
            RecordLayout layout = plan[i];
            if (layout == null) {
                continue;
            }
            HirExpr arg = unwrapOwnership(c.args().get(i));
            if (arg instanceof HirExpr.RecordNew r) {
                if (!r.classTypeId().equals(layout.classTypeId())
                        || !isPureClassRef(r.classRef(), r.classRefIsInstance(), module, initBindings)
                        || (r.binding() != null && !r.binding().isIdentityPositional())
                        || r.args().size() != layout.fieldCount()) {
                    return false;
                }
            } else if (arg instanceof HirExpr.VarRead vr) {
                if (vr.var().isCaptured() || (vr.var().mayBeUndefined() && !vr.var().definitelyDefined())) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    private static HirFunction synthesizeUnboxedFunction(HirFunction orig, int unboxedId,
                                                         RecordLayout[] plan, int[] nextVarId) {
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
                    HirVar fv = new HirVar(id, fname, Resolution.Variable.Kind.PARAMETER, unboxedId,
                            fk, false, true, false, true);
                    fieldVars[f] = fv;
                    newDeclaredVars.add(fv);
                    newParams.add(new HirParam(fname, fv, fk, -1, null, -1, null, null,
                            LoweringTargets.ParamOwnership.DEFAULT, p.line()));
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
                newParams.add(new HirParam(p.name(), newVar, p.kind(), p.wideningRank(), p.checkedType(),
                        p.memberIndex(), p.defaultValue(), p.samTarget(), p.ownership(), p.mutable(), p.line()));
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
        List<HirExpr> newBody = rewriteUnboxedBody(orig.body(), unboxedId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId);

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
                orig.line()
        );
    }

    private static List<HirVar> remapVarList(List<HirVar> vars, int newOwnerId,
                                             Map<Integer, HirVar> varRemap, int[] nextVarId) {
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

    private static List<HirExpr> rewriteUnboxedBody(List<HirExpr> stmts, int newOwnerId,
                                                    Map<Integer, HirVar> varRemap,
                                                    Map<Integer, HirVar[]> flattenedParamVars,
                                                    Map<Integer, RecordLayout> flattenedLayouts,
                                                    int[] nextVarId) {
        List<HirExpr> out = new ArrayList<>(stmts.size());
        for (HirExpr s : stmts) {
            out.add(rewriteUnboxedExpr(s, newOwnerId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId));
        }
        return out;
    }

    private static HirExpr rewriteUnboxedExpr(HirExpr expr, int newOwnerId,
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
                int pIdx = matchingParamIndex(g, layout);
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
            HirExpr nv = rewriteUnboxedExpr(d.value(), newOwnerId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId);
            return new HirExpr.Def(d.name(), d.target(), mapped, nv, d.meta());
        }
        if (expr instanceof HirExpr.VarAssign a) {
            HirVar mapped = varRemap.getOrDefault(a.var().id(), a.var());
            HirExpr nv = rewriteUnboxedExpr(a.value(), newOwnerId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId);
            return new HirExpr.VarAssign(mapped, a.operator(), nv, a.targetText(), a.valueText(), a.meta());
        }
        if (expr instanceof HirExpr.Move m) {
            HirVar mapped = m.var() != null ? varRemap.getOrDefault(m.var().id(), m.var()) : null;
            HirExpr op = rewriteUnboxedExpr(m.operand(), newOwnerId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId);
            return new HirExpr.Move(mapped, op, m.meta());
        }
        if (expr instanceof HirExpr.Block b) {
            List<HirVar> newDecls = remapVarList(b.declaredVars(), newOwnerId, varRemap, nextVarId);
            List<HirExpr> ns = rewriteUnboxedBody(b.statements(), newOwnerId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId);
            return new HirExpr.Block(ns, b.opensNamespaceScope(), b.declaredNames(), newDecls, b.innerWideningRank(), b.meta());
        }
        if (expr instanceof HirExpr.For f) {
            HirExpr iter = rewriteUnboxedExpr(f.iterable(), newOwnerId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId);
            HirVar loopVar = f.loopVar() != null ? remapVarList(List.of(f.loopVar()), newOwnerId, varRemap, nextVarId).get(0) : null;
            List<HirVar> decls = remapVarList(f.bodyDeclaredVars(), newOwnerId, varRemap, nextVarId);
            List<HirExpr> nb = rewriteUnboxedBody(f.body(), newOwnerId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId);
            return new HirExpr.For(iter, f.target(), loopVar, decls, nb, f.bodyWideningRank(), f.elemSamTarget(), f.meta());
        }
        if (expr instanceof HirExpr.Try t) {
            List<HirVar> td = remapVarList(t.tryDeclaredVars(), newOwnerId, varRemap, nextVarId);
            List<HirExpr> tb = rewriteUnboxedBody(t.tryBody(), newOwnerId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId);
            List<HirExpr.HirCatch> ncs = new ArrayList<>(t.catches().size());
            for (HirExpr.HirCatch cc : t.catches()) {
                HirVar cv = cc.var() != null ? remapVarList(List.of(cc.var()), newOwnerId, varRemap, nextVarId).get(0) : null;
                List<HirVar> cd = remapVarList(cc.declaredVars(), newOwnerId, varRemap, nextVarId);
                List<HirExpr> cb = rewriteUnboxedBody(cc.body(), newOwnerId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId);
                ncs.add(new HirExpr.HirCatch(cv, cc.target(), cb, cd, cc.opensNamespaceScope(), cc.declaredNames(), cc.wideningRank(), cc.line()));
            }
            List<HirVar> fd = remapVarList(t.finallyDeclaredVars(), newOwnerId, varRemap, nextVarId);
            List<HirExpr> fb = rewriteUnboxedBody(t.finallyBody(), newOwnerId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId);
            return new HirExpr.Try(tb, td, t.tryOpensNamespaceScope(), t.tryDeclaredNames(), t.tryWideningRank(),
                    ncs, fb, fd, t.finallyOpensNamespaceScope(), t.finallyDeclaredNames(), t.finallyNothrow(), t.hasFinally(), t.meta());
        }
        return HirRewriter.rewriteChildren(expr,
                c -> rewriteUnboxedExpr(c, newOwnerId, varRemap, flattenedParamVars, flattenedLayouts, nextVarId));
    }

    private HirExpr rewriteUnboxCallExpr(HirExpr expr, Map<Integer, UnboxPlan> plans, HirModule module,
                                         Set<String> initBindings, int[] rewrites) {
        HirExpr cur = HirRewriter.rewriteChildren(expr, c -> rewriteUnboxCallExpr(c, plans, module, initBindings, rewrites));
        if (!(cur instanceof HirExpr.DirectBabbleCall c)) {
            return cur;
        }
        UnboxPlan plan = plans.get(c.targetFunctionId());
        if (plan == null || !canRewriteCallToUnboxed(c, plan.paramLayouts(), module, initBindings)) {
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
            HirExpr unwrapped = unwrapOwnership(arg);
            if (unwrapped instanceof HirExpr.RecordNew r) {
                flatArgs.addAll(r.args());
            } else if (unwrapped instanceof HirExpr.VarRead vr) {
                for (int f = 0; f < layout.fieldCount(); f++) {
                    PrimitiveKind fk = layout.paramKinds()[f];
                    ExprMeta fm = new ExprMeta(c.meta().line(), fk, -1, null, EffectSet.EMPTY, fk.isPrimitive(), false);
                    flatArgs.add(new HirExpr.ThisFieldGet(
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
        return new HirExpr.DirectBabbleCall(c.callee(), plan.unboxedFnId(), flatArgs, null, c.meta());
    }

    // ---------------------------------------------------------------- Sub-pass 3: non-escaping local record scalarization

    private HirModule scalarizeLocalRecords(HirModule module, Map<String, RecordLayout> localLayouts,
                                            Set<String> initBindings, int[] nextVarId, int[] rewrites) {
        boolean changed = false;
        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            if (!fn.namespaceScope()) {
                HirFunction updated = scalarizeInFunction(fn, module, localLayouts, initBindings, nextVarId, rewrites);
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
        return module.withUpdates(module.topLevelStatements(), newFns, module.classes(), module.interfaces(),
                nextVarId[0], module.nextOwnerId());
    }

    private HirFunction scalarizeInFunction(HirFunction fn, HirModule module,
                                            Map<String, RecordLayout> localLayouts, Set<String> initBindings,
                                            int[] nextVarId, int[] rewrites) {
        // 1. Collect eligible non-cell local HirVars declared inside fn (excluding params, for-loop vars, catch vars).
        Map<Integer, HirVar> localVars = new LinkedHashMap<>();
        collectEligibleLocalVars(fn, localVars);
        if (localVars.isEmpty()) {
            return fn;
        }

        // 2. Discover candidate record layouts from Def / VarAssign RHSs (iterative propagation across copy defs).
        Map<Integer, RecordLayout> candidates = new LinkedHashMap<>();
        Set<Integer> disqualified = new HashSet<>();
        discoverInitialCandidates(fn.body(), localVars, localLayouts, module, candidates, disqualified);
        if (candidates.isEmpty()) {
            return fn;
        }

        // 3. Fixed-point escape & soundness verification: remove any var that escapes, has a non-record RHS,
        //    is defined/assigned in a value-used position, or is used in any way other than valid ThisFieldGet
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
                scalars[i] = new HirVar(sid, sname, Resolution.Variable.Kind.DEFINITION, fn.id(),
                        layout.paramKinds()[i], false, true, false, true);
            }
            scalarVars.put(e.getKey(), scalars);
        }

        // 5. Rewrite function declaredVars and body.
        List<HirVar> newDeclVars = expandDeclaredVars(fn.declaredVars(), scalarVars);
        List<HirExpr> newBody = rewriteScalarizedStmts(fn.body(), fn.id(), candidates, scalarVars,
                localLayouts, module, initBindings, nextVarId);
        rewrites[0] += candidates.size();
        return fn.withBodyAndDeclaredVars(newBody, newDeclVars);
    }

    private static void addEligibleVars(List<HirVar> vars, Set<Integer> exclude, Map<Integer, HirVar> out) {
        for (HirVar v : vars) {
            if (!v.isCaptured() && v.kind() == Resolution.Variable.Kind.DEFINITION
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
        HirRewriter.rewriteChildren(expr, c -> {
            collectBlockLocalVars(c, out);
            return c;
        });
    }

    private static void discoverInitialCandidates(List<HirExpr> body, Map<Integer, HirVar> localVars,
                                                  Map<String, RecordLayout> localLayouts, HirModule module,
                                                  Map<Integer, RecordLayout> candidates, Set<Integer> disqualified) {
        // Iteratively propagate layouts from RecordNew and candidate VarReads.
        boolean progress = true;
        while (progress) {
            progress = false;
            int beforeSize = candidates.size();
            for (HirExpr s : body) {
                scanCandidateAssignments(s, localVars, localLayouts, module, candidates, disqualified);
            }
            if (candidates.size() != beforeSize) {
                progress = true;
            }
        }
        for (int bad : disqualified) {
            candidates.remove(bad);
        }
    }

    private static void recordCandidateLayout(int vid, HirExpr value, Map<String, RecordLayout> localLayouts,
                                              HirModule module, Map<Integer, RecordLayout> candidates,
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

    private static void scanCandidateAssignments(HirExpr expr, Map<Integer, HirVar> localVars,
                                                 Map<String, RecordLayout> localLayouts, HirModule module,
                                                 Map<Integer, RecordLayout> candidates, Set<Integer> disqualified) {
        if (expr == null) return;
        if (expr instanceof HirExpr.Def d && d.var() != null && localVars.containsKey(d.var().id())) {
            int vid = d.var().id();
            if (d.value() == null) {
                disqualified.add(vid);
            } else {
                recordCandidateLayout(vid, d.value(), localLayouts, module, candidates, disqualified);
            }
        } else if (expr instanceof HirExpr.VarAssign a && localVars.containsKey(a.var().id())) {
            int vid = a.var().id();
            if (a.operator() != null) {
                disqualified.add(vid);
            } else {
                recordCandidateLayout(vid, a.value(), localLayouts, module, candidates, disqualified);
            }
        }
        HirRewriter.rewriteChildren(expr, c -> {
            scanCandidateAssignments(c, localVars, localLayouts, module, candidates, disqualified);
            return c;
        });
    }

    private static RecordLayout inferRecordLayout(HirExpr expr, Map<Integer, RecordLayout> candidates,
                                                  Map<String, RecordLayout> localLayouts, HirModule module) {
        if (expr == null) return null;
        if (expr instanceof HirExpr.RecordNew r) {
            return resolveArgMapping(r) != null ? layoutOf(r, localLayouts, module) : null;
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
        if (expr instanceof HirExpr.Block b && !b.opensNamespaceScope() && !b.statements().isEmpty()
                && b.meta().checkedType() == null) {
            return inferRecordLayout(b.statements().get(b.statements().size() - 1), candidates, localLayouts, module);
        }
        if (expr instanceof HirExpr.If i && i.elseBranch() != null && i.meta().checkedType() == null) {
            RecordLayout tl = inferRecordLayout(i.thenBranch(), candidates, localLayouts, module);
            RecordLayout el = inferRecordLayout(i.elseBranch(), candidates, localLayouts, module);
            if (tl != null && tl.sameType(el)) {
                return tl;
            }
            return tl != null ? tl : el;
        }
        return null;
    }

    private static void verifyBodyStatements(List<HirExpr> stmts, boolean lastValueUsed,
                                             Map<Integer, RecordLayout> candidates,
                                             Map<String, RecordLayout> localLayouts,
                                             HirModule module, Set<Integer> bad) {
        int last = stmts.size() - 1;
        for (int i = 0; i < stmts.size(); i++) {
            verifyExpr(stmts.get(i), i == last && lastValueUsed, candidates, localLayouts, module, bad);
        }
    }

    private static void verifyExpr(HirExpr expr, boolean valueUsed,
                                   Map<Integer, RecordLayout> candidates,
                                   Map<String, RecordLayout> localLayouts,
                                   HirModule module, Set<Integer> bad) {
        if (expr == null) return;
        switch (expr) {
            case HirExpr.Def d -> {
                if (d.var() != null && candidates.containsKey(d.var().id())) {
                    int vid = d.var().id();
                    if (valueUsed || d.value() == null || d.meta().checkedType() != null) {
                        bad.add(vid);
                        verifyExpr(d.value(), true, candidates, localLayouts, module, bad);
                    } else if (!verifyDestructuredRhs(d.value(), candidates.get(vid), candidates, localLayouts, module, bad)) {
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
                    } else if (!verifyDestructuredRhs(a.value(), candidates.get(vid), candidates, localLayouts, module, bad)) {
                        bad.add(vid);
                    }
                    return;
                }
                verifyExpr(a.value(), true, candidates, localLayouts, module, bad);
            }
            case HirExpr.ThisFieldGet g -> {
                if (g.receiver() instanceof HirExpr.VarRead vr && candidates.containsKey(vr.var().id())) {
                    RecordLayout layout = candidates.get(vr.var().id());
                    if (matchingParamIndex(g, layout) < 0) {
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
            case HirExpr.Block b -> verifyBodyStatements(b.statements(), valueUsed, candidates, localLayouts, module, bad);
            case HirExpr.If i -> {
                verifyExpr(i.cond(), true, candidates, localLayouts, module, bad);
                verifyExpr(i.thenBranch(), valueUsed, candidates, localLayouts, module, bad);
                if (i.elseBranch() != null) {
                    verifyExpr(i.elseBranch(), valueUsed, candidates, localLayouts, module, bad);
                }
            }
            case HirExpr.While w -> {
                verifyExpr(w.cond(), true, candidates, localLayouts, module, bad);
                verifyExpr(w.body(), false, candidates, localLayouts, module, bad);
            }
            case HirExpr.For f -> {
                verifyExpr(f.iterable(), true, candidates, localLayouts, module, bad);
                verifyBodyStatements(f.body(), false, candidates, localLayouts, module, bad);
            }
            case HirExpr.Try t -> {
                verifyBodyStatements(t.tryBody(), valueUsed, candidates, localLayouts, module, bad);
                for (HirExpr.HirCatch cc : t.catches()) {
                    verifyBodyStatements(cc.body(), valueUsed, candidates, localLayouts, module, bad);
                }
                verifyBodyStatements(t.finallyBody(), false, candidates, localLayouts, module, bad);
            }
            default -> HirRewriter.rewriteChildren(expr, c -> {
                verifyExpr(c, true, candidates, localLayouts, module, bad);
                return c;
            });
        }
    }

    /** Verifies {@code rhs} produces {@code expected} via RecordNew/candidate copy/Block/If and walks nested expressions. */
    private static boolean verifyDestructuredRhs(HirExpr rhs, RecordLayout expected,
                                                 Map<Integer, RecordLayout> candidates,
                                                 Map<String, RecordLayout> localLayouts,
                                                 HirModule module, Set<Integer> bad) {
        if (rhs == null || rhs.meta().checkedType() != null) {
            verifyExpr(rhs, true, candidates, localLayouts, module, bad);
            return false;
        }
        if (rhs instanceof HirExpr.RecordNew r) {
            RecordLayout rl = layoutOf(r, localLayouts, module);
            if (!expected.sameType(rl) || resolveArgMapping(r) == null) {
                verifyExpr(r, true, candidates, localLayouts, module, bad);
                return false;
            }
            // If classRef is an instance VarRead of a candidate (from InlinePass), it does not escape because r is scalarized.
            if (!(r.classRefIsInstance() && r.classRef() instanceof HirExpr.VarRead cr && candidates.containsKey(cr.var().id()))) {
                verifyExpr(r.classRef(), true, candidates, localLayouts, module, bad);
            }
            for (HirExpr arg : r.args()) {
                verifyExpr(arg, true, candidates, localLayouts, module, bad);
            }
            return true;
        }
        if (rhs instanceof HirExpr.Move m) {
            return verifyDestructuredRhs(m.operand(), expected, candidates, localLayouts, module, bad);
        }
        if (rhs instanceof HirExpr.Freeze f) {
            return verifyDestructuredRhs(f.operand(), expected, candidates, localLayouts, module, bad);
        }
        if (rhs instanceof HirExpr.VarRead vr) {
            RecordLayout ul = candidates.get(vr.var().id());
            if (ul != null && expected.sameType(ul)) {
                return true;
            }
            return false;
        }
        if (rhs instanceof HirExpr.Block b && !b.opensNamespaceScope() && !b.statements().isEmpty()
                && b.innerWideningRank() < 0 && b.meta().wideningRank() < 0) {
            int last = b.statements().size() - 1;
            for (int i = 0; i < last; i++) {
                verifyExpr(b.statements().get(i), false, candidates, localLayouts, module, bad);
            }
            return verifyDestructuredRhs(b.statements().get(last), expected, candidates, localLayouts, module, bad);
        }
        if (rhs instanceof HirExpr.If i && i.elseBranch() != null && i.meta().wideningRank() < 0) {
            verifyExpr(i.cond(), true, candidates, localLayouts, module, bad);
            boolean tok = verifyDestructuredRhs(i.thenBranch(), expected, candidates, localLayouts, module, bad);
            boolean eok = verifyDestructuredRhs(i.elseBranch(), expected, candidates, localLayouts, module, bad);
            return tok && eok;
        }
        verifyExpr(rhs, true, candidates, localLayouts, module, bad);
        return false;
    }

    private static List<HirVar> expandDeclaredVars(List<HirVar> orig, Map<Integer, HirVar[]> scalarVars) {
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

    private List<HirExpr> rewriteScalarizedStmts(List<HirExpr> stmts, int ownerFnId,
                                                 Map<Integer, RecordLayout> candidates,
                                                 Map<Integer, HirVar[]> scalarVars,
                                                 Map<String, RecordLayout> localLayouts,
                                                 HirModule module, Set<String> initBindings,
                                                 int[] nextVarId) {
        List<HirExpr> out = new ArrayList<>(stmts.size());
        for (HirExpr s : stmts) {
            out.add(rewriteScalarizedExpr(s, ownerFnId, candidates, scalarVars, localLayouts, module, initBindings, nextVarId));
        }
        return out;
    }

    private HirExpr rewriteScalarizedExpr(HirExpr expr, int ownerFnId,
                                          Map<Integer, RecordLayout> candidates,
                                          Map<Integer, HirVar[]> scalarVars,
                                          Map<String, RecordLayout> localLayouts,
                                          HirModule module, Set<String> initBindings,
                                          int[] nextVarId) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof HirExpr.ThisFieldGet g && g.receiver() instanceof HirExpr.VarRead vr) {
            HirVar[] scalars = scalarVars.get(vr.var().id());
            if (scalars != null) {
                RecordLayout layout = candidates.get(vr.var().id());
                int p = matchingParamIndex(g, layout);
                return new HirExpr.VarRead(scalars[p], g.meta());
            }
        }
        if (expr instanceof HirExpr.Def d && d.var() != null && scalarVars.containsKey(d.var().id())) {
            return emitDestructuredStore(d.value(), scalarVars.get(d.var().id()), candidates.get(d.var().id()),
                    true, d.meta().line(), ownerFnId, candidates, scalarVars, localLayouts, module, initBindings, nextVarId);
        }
        if (expr instanceof HirExpr.VarAssign a && scalarVars.containsKey(a.var().id())) {
            return emitDestructuredStore(a.value(), scalarVars.get(a.var().id()), candidates.get(a.var().id()),
                    false, a.meta().line(), ownerFnId, candidates, scalarVars, localLayouts, module, initBindings, nextVarId);
        }
        if (expr instanceof HirExpr.Block b) {
            List<HirVar> newDecls = expandDeclaredVars(b.declaredVars(), scalarVars);
            List<HirExpr> newStmts = rewriteScalarizedStmts(b.statements(), ownerFnId, candidates, scalarVars,
                    localLayouts, module, initBindings, nextVarId);
            return new HirExpr.Block(newStmts, b.opensNamespaceScope(), b.declaredNames(), newDecls, b.innerWideningRank(), b.meta());
        }
        if (expr instanceof HirExpr.For f) {
            HirExpr iter = rewriteScalarizedExpr(f.iterable(), ownerFnId, candidates, scalarVars,
                    localLayouts, module, initBindings, nextVarId);
            List<HirVar> newDecls = expandDeclaredVars(f.bodyDeclaredVars(), scalarVars);
            List<HirExpr> newBody = rewriteScalarizedStmts(f.body(), ownerFnId, candidates, scalarVars,
                    localLayouts, module, initBindings, nextVarId);
            return new HirExpr.For(iter, f.target(), f.loopVar(), newDecls, newBody, f.bodyWideningRank(), f.elemSamTarget(), f.meta());
        }
        if (expr instanceof HirExpr.Try t) {
            List<HirVar> newTryDecls = expandDeclaredVars(t.tryDeclaredVars(), scalarVars);
            List<HirExpr> newTryBody = rewriteScalarizedStmts(t.tryBody(), ownerFnId, candidates, scalarVars,
                    localLayouts, module, initBindings, nextVarId);
            List<HirExpr.HirCatch> newCatches = new ArrayList<>(t.catches().size());
            for (HirExpr.HirCatch cc : t.catches()) {
                List<HirVar> cd = expandDeclaredVars(cc.declaredVars(), scalarVars);
                List<HirExpr> cb = rewriteScalarizedStmts(cc.body(), ownerFnId, candidates, scalarVars,
                        localLayouts, module, initBindings, nextVarId);
                newCatches.add(new HirExpr.HirCatch(cc.var(), cc.target(), cb, cd, cc.opensNamespaceScope(),
                        cc.declaredNames(), cc.wideningRank(), cc.line()));
            }
            List<HirVar> newFinDecls = expandDeclaredVars(t.finallyDeclaredVars(), scalarVars);
            List<HirExpr> newFinBody = rewriteScalarizedStmts(t.finallyBody(), ownerFnId, candidates, scalarVars,
                    localLayouts, module, initBindings, nextVarId);
            return new HirExpr.Try(newTryBody, newTryDecls, t.tryOpensNamespaceScope(), t.tryDeclaredNames(),
                    t.tryWideningRank(), newCatches, newFinBody, newFinDecls, t.finallyOpensNamespaceScope(),
                    t.finallyDeclaredNames(), t.finallyNothrow(), t.hasFinally(), t.meta());
        }
        return HirRewriter.rewriteChildren(expr, c -> rewriteScalarizedExpr(c, ownerFnId, candidates, scalarVars,
                localLayouts, module, initBindings, nextVarId));
    }

    private HirExpr emitDestructuredStore(HirExpr rhs, HirVar[] targetScalars, RecordLayout layout,
                                          boolean isDef, int line, int ownerFnId,
                                          Map<Integer, RecordLayout> candidates,
                                          Map<Integer, HirVar[]> scalarVars,
                                          Map<String, RecordLayout> localLayouts,
                                          HirModule module, Set<String> initBindings,
                                          int[] nextVarId) {
        if (rhs instanceof HirExpr.Move m) {
            return emitDestructuredStore(m.operand(), targetScalars, layout, isDef, line, ownerFnId,
                    candidates, scalarVars, localLayouts, module, initBindings, nextVarId);
        }
        if (rhs instanceof HirExpr.Freeze f) {
            return emitDestructuredStore(f.operand(), targetScalars, layout, isDef, line, ownerFnId,
                    candidates, scalarVars, localLayouts, module, initBindings, nextVarId);
        }
        ExprMeta voidMeta = new ExprMeta(line, PrimitiveKind.NONE, -1, null, EffectSet.EMPTY, true, false);
        if (rhs instanceof HirExpr.VarRead vr) {
            HirVar[] srcScalars = scalarVars.get(vr.var().id());
            if (srcScalars == targetScalars) {
                return new HirExpr.Const(null, voidMeta);
            }
            List<HirExpr> stmts = new ArrayList<>(targetScalars.length);
            for (int i = 0; i < targetScalars.length; i++) {
                PrimitiveKind fk = layout.paramKinds()[i];
                ExprMeta fm = new ExprMeta(line, fk, -1, null, EffectSet.EMPTY, fk.isPrimitive(), false);
                HirExpr read = new HirExpr.VarRead(srcScalars[i], fm);
                stmts.add(makeScalarStore(targetScalars[i], read, isDef, line, fk));
            }
            return new HirExpr.Block(stmts, false, new String[0], List.of(), -1, voidMeta);
        }
        if (rhs instanceof HirExpr.RecordNew r) {
            int[] argMap = resolveArgMapping(r);
            int n = layout.fieldCount();
            List<HirExpr> rewrittenArgs = new ArrayList<>(r.args().size());
            for (HirExpr arg : r.args()) {
                rewrittenArgs.add(rewriteScalarizedExpr(arg, ownerFnId, candidates, scalarVars,
                        localLayouts, module, initBindings, nextVarId));
            }
            List<HirExpr> stmts = new ArrayList<>();
            boolean skipClassRef = (r.classRefIsInstance() && r.classRef() instanceof HirExpr.VarRead cr
                    && scalarVars.containsKey(cr.var().id()))
                    || isPureClassRef(r.classRef(), r.classRefIsInstance(), module, initBindings);
            if (!skipClassRef) {
                stmts.add(rewriteScalarizedExpr(r.classRef(), ownerFnId, candidates, scalarVars,
                        localLayouts, module, initBindings, nextVarId));
            }
            boolean inOrder = true;
            for (int i = 0; i < n; i++) {
                if (argMap[i] != i) {
                    inOrder = false;
                    break;
                }
            }
            // For simple in-order Def (not VarAssign), emit Def(targetScalars[i], arg[i]) directly so
            // HirEGraphSimplifyPass can constant-propagate immutable fields.
            if (isDef && inOrder) {
                for (int i = 0; i < n; i++) {
                    HirExpr a = rewrittenArgs.get(i);
                    stmts.add(makeScalarStore(targetScalars[i], a, true, a.meta().line(), layout.paramKinds()[i]));
                }
                return new HirExpr.Block(stmts, false, new String[0], List.of(), -1, voidMeta);
            }
            // Evaluate all arguments left-to-right into fresh temps before storing (safe for swaps like p = Vec3(p.y, p.x)).
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
                HirVar tmp = new HirVar(tid, "$sr_t" + tid, Resolution.Variable.Kind.DEFINITION, ownerFnId,
                        ak, false, true, false, true);
                temps.add(tmp);
                argTempBySourceIdx[aIdx] = tmp;
                stmts.add(makeScalarStore(tmp, a, true, a.meta().line(), ak));
            }
            for (int p = 0; p < n; p++) {
                HirVar tmp = argTempBySourceIdx[argMap[p]];
                PrimitiveKind fk = layout.paramKinds()[p];
                ExprMeta rm = new ExprMeta(line, fk, -1, null, EffectSet.EMPTY, fk.isPrimitive(), false);
                stmts.add(makeScalarStore(targetScalars[p], new HirExpr.VarRead(tmp, rm), isDef, line, fk));
            }
            return new HirExpr.Block(stmts, false, new String[0], temps, -1, voidMeta);
        }
        if (rhs instanceof HirExpr.Block b) {
            List<HirVar> newDecls = expandDeclaredVars(b.declaredVars(), scalarVars);
            int last = b.statements().size() - 1;
            List<HirExpr> stmts = new ArrayList<>(b.statements().size());
            for (int i = 0; i < last; i++) {
                stmts.add(rewriteScalarizedExpr(b.statements().get(i), ownerFnId, candidates, scalarVars,
                        localLayouts, module, initBindings, nextVarId));
            }
            stmts.add(emitDestructuredStore(b.statements().get(last), targetScalars, layout, isDef, line, ownerFnId,
                    candidates, scalarVars, localLayouts, module, initBindings, nextVarId));
            return new HirExpr.Block(stmts, false, new String[0], newDecls, -1, voidMeta);
        }
        if (rhs instanceof HirExpr.If i) {
            HirExpr cond = rewriteScalarizedExpr(i.cond(), ownerFnId, candidates, scalarVars,
                    localLayouts, module, initBindings, nextVarId);
            // Both branches store into already-declared outer targetScalars using VarAssign.
            HirExpr tb = emitDestructuredStore(i.thenBranch(), targetScalars, layout, false, line, ownerFnId,
                    candidates, scalarVars, localLayouts, module, initBindings, nextVarId);
            HirExpr eb = emitDestructuredStore(i.elseBranch(), targetScalars, layout, false, line, ownerFnId,
                    candidates, scalarVars, localLayouts, module, initBindings, nextVarId);
            return new HirExpr.If(cond, tb, eb, voidMeta);
        }
        throw new IllegalStateException("ScalarReplacePass : unexpected destructured RHS " + rhs.getClass().getSimpleName());
    }

    private static HirExpr makeScalarStore(HirVar target, HirExpr value, boolean isDef, int line, PrimitiveKind kind) {
        ExprMeta meta = new ExprMeta(line, kind, value.meta().wideningRank(), null,
                EffectSet.EMPTY, kind.isPrimitive(), value.meta().stringType());
        if (isDef) {
            return new HirExpr.Def(target.name(),
                    new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1),
                    target, value, meta);
        }
        return new HirExpr.VarAssign(target, null, value, target.name(), "", meta);
    }
}
