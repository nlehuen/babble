package org.babblelang.opt.hir;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirPass;
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
 * {@code O8} — Small-Function Inlining (BIP014-I2, BIP014-D2, BIP014-D5).
 *
 * <p>Inlines small ({@code <= 25} HIR nodes), non-recursive, capture-free {@code DirectBabbleCall}s
 * intra-module (and exported small functions cross-module under {@code babblec} AOT only),
 * alpha-renaming parameter and local {@link HirVar}s and preserving every expression's original
 * {@link ExprMeta#line()} for exact error-line parity with {@code Interpreter}.
 */
public final class InlinePass implements HirPass {
    public static final int MAX_INLINE_NODES = 25;
    private static final int MAX_INLINES_PER_OWNER = 8;

    @Override
    public String name() {
        return "Inline";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] nextVarId = {module.nextVarId()};
        int[] rewrites = {0};

        // Compute top-level function definitions that are guaranteed initialized before any user code can run.
        Set<String> definitelyInitializedTopFns = org.babblelang.opt.HirPurity.findDefinitelyInitializedTopLevelFns(module);

        // Precompute which functions are eligible inline targets.
        Map<Integer, HirFunction> inlineable = new HashMap<>();
        for (HirFunction fn : module.functions().values()) {
            if (isInlineCandidate(fn)) {
                inlineable.put(fn.id(), fn);
            }
        }
        Map<String, HirFunction> crossModule = context.aotCrossModule() ? context.exportedInlineFunctions() : Map.of();
        if (inlineable.isEmpty() && crossModule.isEmpty()) {
            return Result.unchanged(module);
        }

        HirModule rewritten = HirRewriter.rewriteModule(module,
                (ownerId, fnId, stmts) -> inlineList(stmts, ownerId, fnId, inlineable, crossModule,
                        definitelyInitializedTopFns, nextVarId, new int[]{MAX_INLINES_PER_OWNER}, rewrites),
                null, () -> nextVarId[0]);
        if (rewritten == module) {
            return Result.unchanged(module);
        }
        return new Result(rewritten, rewrites[0]);
    }

    /**
     * Collects self-contained small exported functions ({@code <= 25} HIR nodes) from {@code module}
     * for {@code babblec} AOT cross-module inlining ({@code BIP014-D2}).
     */
    public static Map<String, HirFunction> collectCrossModuleExports(HirModule module) {
        Set<String> exports = Set.of(module.exports());
        if (exports.isEmpty()) {
            return Map.of();
        }
        Map<String, HirFunction> result = new LinkedHashMap<>();
        for (HirExpr stmt : module.topLevelStatements()) {
            if (stmt instanceof HirExpr.Def d && d.var() == null && exports.contains(d.name())) {
                HirExpr val = HirRewriter.unwrapTransparent(d.value());
                if (val instanceof HirExpr.FunctionRef ref) {
                    HirFunction fn = module.function(ref.functionId());
                    if (isCrossModuleInlineCandidate(fn, module)) {
                        result.put(module.sourceName() + "#" + d.name(), fn);
                    }
                }
            }
        }
        return result;
    }

    public static boolean isCrossModuleInlineCandidate(HirFunction fn, HirModule module) {
        if (!isInlineCandidate(fn)
                || !org.babblelang.opt.HirPurity.isFunctionPureNothrowTerminating(
                fn, org.babblelang.analysis.EffectSet.EMPTY, module, new HashSet<>())) {
            return false;
        }
        boolean[] ok = {true};
        for (HirExpr stmt : fn.body()) {
            scanCrossModuleSelfContained(stmt, ok);
            if (!ok[0]) return false;
        }
        return true;
    }

    private static void scanCrossModuleSelfContained(HirExpr expr, boolean[] ok) {
        if (expr == null || !ok[0]) return;
        switch (expr) {
            case HirExpr.NameRead n -> {
                if (!"java".equals(n.name()) && !"javax".equals(n.name())) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.NameAssign a -> {
                ok[0] = false;
                return;
            }
            case HirExpr.DirectBabbleCall c -> {
                ok[0] = false;
                return;
            }
            case HirExpr.DirectThisMethodCall c -> {
                ok[0] = false;
                return;
            }
            case HirExpr.ThisFieldGet g -> {
                ok[0] = false;
                return;
            }
            case HirExpr.ThisFieldAssign a -> {
                ok[0] = false;
                return;
            }
            case HirExpr.SuperCall c -> {
                ok[0] = false;
                return;
            }
            case HirExpr.ArrayCreate c -> {
                if (c.componentClass() == null && !c.elemKind().isPrimitive()) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.JavaSamAdapt j -> {
                if (!isSamTargetCrossModuleAccessible(j.target())) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.As a -> {
                if (a.samTarget() != null && !isSamTargetCrossModuleAccessible(a.samTarget())) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.For f -> {
                if (f.elemSamTarget() != null && !isSamTargetCrossModuleAccessible(f.elemSamTarget())) {
                    ok[0] = false;
                    return;
                }
            }
            default -> {
            }
        }
        HirRewriter.rewriteChildren(expr, child -> {
            scanCrossModuleSelfContained(child, ok);
            return child;
        });
    }

    private static boolean isSamTargetCrossModuleAccessible(LoweringTargets.JavaSamTarget target) {
        if (target == null || !org.babblelang.runtime.JavaTypes.isAccessible(target.samInterface())) {
            return false;
        }
        for (Class<?> m : target.markers()) {
            if (!org.babblelang.runtime.JavaTypes.isAccessible(m)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether a {@link HirFunction} is a safe, self-contained inline candidate
     * (used both intra-module and for {@code babblec} AOT cross-module small-export summaries).
     */
    public static boolean isInlineCandidate(HirFunction fn) {
        if (fn == null || fn.isCompiledClassMethod() || fn.namespaceScope() || !fn.capturedVars().isEmpty()
                || fn.effects().isIO()) {
            return false;
        }
        if (fn.body().isEmpty()) {
            return false;
        }
        // Reject parameters that are cells, namespace-bound, or require Java SAM entry adaptation
        for (HirParam p : fn.params()) {
            if (p.var() == null || p.var().cell() || p.samTarget() != null) {
                return false;
            }
        }
        for (HirVar v : fn.declaredVars()) {
            if (v.cell()) {
                return false;
            }
        }
        int[] nodes = {0};
        boolean[] ok = {true};
        int lastIdx = fn.body().size() - 1;
        for (int i = 0; i < fn.body().size(); i++) {
            HirExpr stmt = fn.body().get(i);
            // Allow trailing top-level return, reject any other return
            HirExpr check = (i == lastIdx && stmt instanceof HirExpr.Return r && r.meta().wideningRank() < 0) ? r.value() : stmt;
            scanCalleeBody(check, fn.id(), nodes, ok);
            if (!ok[0] || nodes[0] > MAX_INLINE_NODES) {
                return false;
            }
        }
        return true;
    }

    private static void scanCalleeBody(HirExpr expr, int fnId, int[] nodes, boolean[] ok) {
        if (expr == null || !ok[0]) return;
        nodes[0]++;
        if (nodes[0] > MAX_INLINE_NODES) {
            ok[0] = false;
            return;
        }
        switch (expr) {
            case HirExpr.Return r -> {
                ok[0] = false;
                return;
            }
            case HirExpr.Try t -> {
                ok[0] = false;
                return;
            }
            case HirExpr.SelfRecurseCall c -> {
                ok[0] = false;
                return;
            }
            case HirExpr.SelfTailCall c -> {
                // Its jump only makes sense in the callee's own method (BIP024).
                ok[0] = false;
                return;
            }
            case HirExpr.RecurseRef r -> {
                ok[0] = false;
                return;
            }
            case HirExpr.ThisRef t -> {
                ok[0] = false;
                return;
            }
            case HirExpr.FunctionRef f -> {
                ok[0] = false;
                return;
            }
            case HirExpr.ClassExpr c -> {
                ok[0] = false;
                return;
            }
            case HirExpr.InterfaceExpr i -> {
                ok[0] = false;
                return;
            }
            case HirExpr.DirectBabbleCall c -> {
                if (c.targetFunctionId() == fnId) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.Block b -> {
                if (b.opensNamespaceScope()) {
                    ok[0] = false;
                    return;
                }
                for (HirVar v : b.declaredVars()) {
                    if (v.cell()) {
                        ok[0] = false;
                        return;
                    }
                }
            }
            case HirExpr.For f -> {
                if (f.loopVar() != null && f.loopVar().cell()) {
                    ok[0] = false;
                    return;
                }
                for (HirVar v : f.bodyDeclaredVars()) {
                    if (v.cell()) {
                        ok[0] = false;
                        return;
                    }
                }
            }
            default -> {
            }
        }
        HirRewriter.rewriteChildren(expr, child -> {
            scanCalleeBody(child, fnId, nodes, ok);
            return child;
        });
    }

    private List<HirExpr> inlineList(List<HirExpr> stmts, int ownerMethodId, int currentFnId,
                                     Map<Integer, HirFunction> inlineable, Map<String, HirFunction> crossModule,
                                     Set<String> defInitTopFns, int[] nextVarId, int[] budget, int[] rewrites) {
        return HirRewriter.rewriteList(stmts, e -> inlineExpr(e, ownerMethodId, currentFnId, inlineable,
                crossModule, defInitTopFns, nextVarId, budget, rewrites));
    }

    private HirExpr inlineExpr(HirExpr expr, int ownerMethodId, int currentFnId,
                               Map<Integer, HirFunction> inlineable, Map<String, HirFunction> crossModule,
                               Set<String> defInitTopFns, int[] nextVarId, int[] budget, int[] rewrites) {
        HirExpr cur = HirRewriter.rewriteChildren(expr, child -> inlineExpr(child, ownerMethodId, currentFnId,
                inlineable, crossModule, defInitTopFns, nextVarId, budget, rewrites));

        if (budget[0] <= 0) {
            return cur;
        }
        if (cur instanceof HirExpr.DirectBabbleCall call) {
            int targetId = call.targetFunctionId();
            if (targetId == currentFnId) {
                return cur;
            }
            HirFunction calleeFn = inlineable.get(targetId);
            if (calleeFn == null || call.args().size() != calleeFn.params().size()
                    || (call.binding() != null && !call.binding().isIdentityPositional())
                    || !canInlineReturnKind(calleeFn, call.meta())) {
                return cur;
            }
            budget[0]--;
            rewrites[0]++;
            boolean keepCallee = !isDefinitelyDefinedPureCallee(call.callee(), defInitTopFns);
            return performInline(call.callee(), keepCallee, call.args(), call.meta(), calleeFn, ownerMethodId, nextVarId);
        }
        if (!crossModule.isEmpty() && cur instanceof HirExpr.DynamicCall call
                && call.callee() instanceof HirExpr.ModuleExportGet exp
                && exp.target().moduleName() != null) {
            HirFunction calleeFn = crossModule.get(exp.target().moduleName() + "#" + exp.target().name());
            if (calleeFn == null || call.args().size() != calleeFn.params().size()
                    || (call.binding() != null && !call.binding().isIdentityPositional())
                    || !canInlineReturnKind(calleeFn, call.meta())) {
                return cur;
            }
            List<HirExpr> posArgs = new ArrayList<>(call.args().size());
            for (HirCallArg a : call.args()) {
                if (a.name() != null) {
                    return cur;
                }
                posArgs.add(a.value());
            }
            budget[0]--;
            rewrites[0]++;
            // Keep module receiver evaluation if it might have side effects / undefined check.
            return performInline(exp, true, posArgs, call.meta(), calleeFn, ownerMethodId, nextVarId);
        }
        return cur;
    }

    private static boolean canInlineReturnKind(HirFunction calleeFn, ExprMeta callMeta) {
        if (callMeta.checkedType() != null) {
            return false;
        }
        HirExpr lastRaw = calleeFn.body().get(calleeFn.body().size() - 1);
        HirExpr lastExpr = (lastRaw instanceof HirExpr.Return r) ? r.value() : lastRaw;
        if (calleeFn.returnKind() != callMeta.kind() && callMeta.kind().isPrimitive()) {
            return false;
        }
        return lastExpr.meta().kind() == calleeFn.returnKind() || calleeFn.bodyWideningRank() >= 0;
    }

    private static HirExpr performInline(HirExpr calleeExpr, boolean keepCallee, List<HirExpr> args,
                                         ExprMeta callMeta, HirFunction calleeFn,
                                         int ownerMethodId, int[] nextVarId) {
        Set<Integer> assignedInCallee = new HashSet<>();
        for (HirExpr s : calleeFn.body()) {
            collectAssignedVars(s, assignedInCallee);
        }

        // Decide which parameters can be substituted directly (Const or definitely-defined non-cell VarRead).
        Map<Integer, HirExpr> substExprs = new HashMap<>();
        Set<Integer> substParamIds = new HashSet<>();
        for (int i = 0; i < calleeFn.params().size(); i++) {
            HirParam p = calleeFn.params().get(i);
            HirExpr arg = HirRewriter.unwrapTransparent(args.get(i));
            boolean noOpParamWiden = p.wideningRank() < 0 || isNoOpWidening(p.kind(), p.wideningRank());
            if (p.var() != null && !assignedInCallee.contains(p.var().id())
                    && noOpParamWiden && arg.meta().wideningRank() < 0
                    && arg.meta().checkedType() == null
                    && arg.meta().kind() == p.kind()) {
                if (arg instanceof HirExpr.Const) {
                    substExprs.put(p.var().id(), arg);
                    substParamIds.add(p.var().id());
                } else if (arg instanceof HirExpr.VarRead vr && !vr.var().cell()
                        && (!vr.var().mayBeUndefined() || vr.var().definitelyDefined())) {
                    substExprs.put(p.var().id(), vr);
                    substParamIds.add(p.var().id());
                }
            }
        }

        // Alpha-rename remaining declared variables (parameters + body locals) of calleeFn.
        Map<Integer, HirVar> varMap = new HashMap<>();
        List<HirVar> blockDeclared = new ArrayList<>();

        for (HirVar v : calleeFn.declaredVars()) {
            if (v.kind() == Resolution.Variable.Kind.THIS || v.kind() == Resolution.Variable.Kind.RECURSE) {
                continue;
            }
            if (substParamIds.contains(v.id())) {
                continue;
            }
            HirVar fresh = new HirVar(
                    nextVarId[0]++,
                    v.name(),
                    Resolution.Variable.Kind.DEFINITION,
                    ownerMethodId,
                    v.primitiveKind(),
                    false,
                    v.used(),
                    v.mayBeUndefined(),
                    v.definitelyDefined()
            );
            varMap.put(v.id(), fresh);
            blockDeclared.add(fresh);
        }

        List<HirExpr> stmts = new ArrayList<>();

        // Preserve callee side effects / undefined-check if callee isn't provably already defined.
        if (keepCallee) {
            stmts.add(calleeExpr);
        }

        // Bind each non-substituted argument expression once into the alpha-renamed parameter variable.
        for (int i = 0; i < calleeFn.params().size(); i++) {
            HirParam p = calleeFn.params().get(i);
            if (substParamIds.contains(p.var().id())) {
                continue;
            }
            HirVar freshParam = varMap.get(p.var().id());
            if (freshParam == null) {
                freshParam = new HirVar(nextVarId[0]++, p.name(), Resolution.Variable.Kind.DEFINITION,
                        ownerMethodId, p.kind(), false, true, false, true);
                varMap.put(p.var().id(), freshParam);
                blockDeclared.add(freshParam);
            }
            HirExpr arg = args.get(i);
            ExprMeta defMeta = new ExprMeta(arg.meta().line(), freshParam.primitiveKind(), p.wideningRank(),
                    null, arg.meta().effects(), arg.meta().deeplyImmutable(), arg.meta().stringType());
            stmts.add(new HirExpr.Def(p.name(), new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1),
                    freshParam, arg, defMeta));
        }

        // Clone & alpha-rename callee body statements, preserving every ExprMeta.line (BIP014-D5).
        int lastIdx = calleeFn.body().size() - 1;
        for (int i = 0; i < calleeFn.body().size(); i++) {
            HirExpr s = calleeFn.body().get(i);
            if (i == lastIdx && s instanceof HirExpr.Return r) {
                s = r.value();
            }
            stmts.add(alphaRename(s, varMap, substExprs, ownerMethodId, nextVarId));
        }

        if (blockDeclared.isEmpty() && stmts.size() == 1 && calleeFn.bodyWideningRank() < 0
                && callMeta.wideningRank() < 0 && callMeta.checkedType() == null
                && stmts.get(0).meta().kind() == callMeta.kind()) {
            return stmts.get(0);
        }
        return new HirExpr.Block(stmts, false, new String[0], blockDeclared, calleeFn.bodyWideningRank(), callMeta);
    }

    private static boolean isNoOpWidening(PrimitiveKind kind, int rank) {
        return switch (rank) {
            case 1 -> kind == PrimitiveKind.SHORT;
            case 2 -> kind == PrimitiveKind.INT;
            case 3 -> kind == PrimitiveKind.LONG;
            case 4 -> kind == PrimitiveKind.FLOAT;
            case 5 -> kind == PrimitiveKind.DOUBLE;
            default -> false;
        };
    }

    private static void collectAssignedVars(HirExpr expr, Set<Integer> out) {
        if (expr == null) return;
        if (expr instanceof HirExpr.VarAssign a) {
            out.add(a.var().id());
        }
        HirRewriter.rewriteChildren(expr, child -> {
            collectAssignedVars(child, out);
            return child;
        });
    }

    private static HirExpr alphaRename(HirExpr expr, Map<Integer, HirVar> varMap, Map<Integer, HirExpr> substExprs,
                                       int ownerMethodId, int[] nextVarId) {
        if (expr == null) {
            return null;
        }
        return switch (expr) {
            case HirExpr.VarRead v -> {
                HirExpr subst = substExprs.get(v.var().id());
                if (subst != null) {
                    if (v.meta().wideningRank() >= 0) {
                        // Apply the VarRead's widening onto the substituted expression.
                        if (subst instanceof HirExpr.Const c) {
                            yield new HirExpr.Const(c.value(), c.meta().withWideningRank(v.meta().wideningRank()));
                        }
                        if (subst instanceof HirExpr.VarRead vr) {
                            yield new HirExpr.VarRead(vr.var(), vr.meta().withWideningRank(v.meta().wideningRank()));
                        }
                    }
                    yield subst;
                }
                HirVar mapped = varMap.get(v.var().id());
                yield mapped == null ? v : new HirExpr.VarRead(mapped, v.meta());
            }
            case HirExpr.Def d -> {
                HirVar mapped = d.var() != null ? varMap.get(d.var().id()) : null;
                HirExpr nv = alphaRename(d.value(), varMap, substExprs, ownerMethodId, nextVarId);
                yield (mapped == null && nv == d.value()) ? d
                        : new HirExpr.Def(d.name(), d.target(), mapped != null ? mapped : d.var(), nv, d.meta());
            }
            case HirExpr.VarAssign a -> {
                HirVar mapped = varMap.get(a.var().id());
                HirExpr nv = alphaRename(a.value(), varMap, substExprs, ownerMethodId, nextVarId);
                yield (mapped == null && nv == a.value()) ? a
                        : new HirExpr.VarAssign(mapped != null ? mapped : a.var(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
            }
            case HirExpr.Block b -> {
                Map<Integer, HirVar> innerMap = new HashMap<>(varMap);
                List<HirVar> freshDecls = new ArrayList<>(b.declaredVars().size());
                for (HirVar dv : b.declaredVars()) {
                    HirVar fresh = dv.withIdAndOwner(nextVarId[0]++, ownerMethodId);
                    innerMap.put(dv.id(), fresh);
                    freshDecls.add(fresh);
                }
                List<HirExpr> ns = new ArrayList<>(b.statements().size());
                for (HirExpr s : b.statements()) {
                    ns.add(alphaRename(s, innerMap, substExprs, ownerMethodId, nextVarId));
                }
                yield new HirExpr.Block(ns, b.opensNamespaceScope(), b.declaredNames(), freshDecls, b.innerWideningRank(), b.meta());
            }
            case HirExpr.For f -> {
                HirExpr ni = alphaRename(f.iterable(), varMap, substExprs, ownerMethodId, nextVarId);
                Map<Integer, HirVar> innerMap = new HashMap<>(varMap);
                HirVar freshLoop = f.loopVar() != null ? f.loopVar().withIdAndOwner(nextVarId[0]++, ownerMethodId) : null;
                if (freshLoop != null) {
                    innerMap.put(f.loopVar().id(), freshLoop);
                }
                List<HirVar> freshDecls = new ArrayList<>(f.bodyDeclaredVars().size());
                for (HirVar dv : f.bodyDeclaredVars()) {
                    HirVar fresh = innerMap.computeIfAbsent(dv.id(), k -> dv.withIdAndOwner(nextVarId[0]++, ownerMethodId));
                    freshDecls.add(fresh);
                }
                List<HirExpr> nb = new ArrayList<>(f.body().size());
                for (HirExpr s : f.body()) {
                    nb.add(alphaRename(s, innerMap, substExprs, ownerMethodId, nextVarId));
                }
                yield new HirExpr.For(ni, f.target(), freshLoop, freshDecls, nb, f.bodyWideningRank(),
                        f.elemSamTarget(), f.meta());
            }
            default -> HirRewriter.rewriteChildren(expr, child -> alphaRename(child, varMap, substExprs, ownerMethodId, nextVarId));
        };
    }

    private static boolean isDefinitelyDefinedPureCallee(HirExpr callee, Set<String> defInitTopFns) {
        HirExpr u = HirRewriter.unwrapTransparent(callee);
        if (u instanceof HirExpr.FunctionRef) {
            return true;
        }
        if (u instanceof HirExpr.VarRead v) {
            return !v.var().mayBeUndefined() || v.var().definitelyDefined();
        }
        if (u instanceof HirExpr.NameRead n) {
            return defInitTopFns.contains(n.name());
        }
        return false;
    }
}
