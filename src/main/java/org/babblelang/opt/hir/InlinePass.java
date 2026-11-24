package org.babblelang.opt.hir;

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
 * {@code O8} — Small-Function, {@code record}/Class Method, and {@code borrow}-Lambda Inlining
 * ({@code BIP014-I2}, {@code BIP014-P4}, {@code BIP014-D2}, {@code BIP014-D5}).
 *
 * <p>Inlines:
 * <ul>
 *   <li>Small ({@code <= 35} HIR nodes intra-module; {@code <= 25} cross-module under
 *       {@code babblec} AOT), non-recursive, capture-free free functions ({@code DirectBabbleCall}).</li>
 *   <li>Small, non-recursive, capture-free compiled {@code record}/class methods
 *       ({@code DirectThisMethodCall}), both intra-module and cross-module under {@code babblec} AOT.</li>
 *   <li>{@code borrow} callable arguments supplied as capture-free {@code FunctionRef} literals,
 *       rewriting inlined {@code DynamicCall}s to {@code DirectBabbleCall}s so the next fixed-point
 *       round eliminates the closure allocation.</li>
 * </ul>
 * Preserves every expression's original {@link ExprMeta#line()} for exact error-line parity with
 * {@code Interpreter}.
 */
public final class InlinePass implements HirPass {
    public static final int MAX_INLINE_NODES = 35;
    public static final int MAX_CROSS_MODULE_NODES = 25;
    private static final int MAX_INLINES_PER_OWNER = 32;
    private static final String METHOD_KEY_PREFIX = "@method:";

    @Override
    public String name() {
        return "Inline";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] nextVarId = {module.nextVarId()};
        int[] rewrites = {0};

        Set<String> definitelyInitializedTopFns = org.babblelang.opt.HirPurity.findDefinitelyInitializedTopLevelFns(module);

        Map<Integer, HirFunction> inlineableFns = new HashMap<>();
        Map<Integer, HirFunction> inlineableMethods = new HashMap<>();
        for (HirFunction fn : module.functions().values()) {
            if (isInlineCandidate(fn)) {
                inlineableFns.put(fn.id(), fn);
            } else if (isMethodInlineCandidate(fn, module)) {
                inlineableMethods.put(fn.id(), fn);
            }
        }
        Map<String, HirFunction> crossModule = context.aotCrossModule() ? context.exportedInlineFunctions() : Map.of();
        if (inlineableFns.isEmpty() && inlineableMethods.isEmpty() && crossModule.isEmpty()) {
            return Result.unchanged(module);
        }

        HirModule rewritten = HirRewriter.rewriteModule(module,
                (ownerId, fnId, stmts) -> inlineList(stmts, ownerId, fnId, module, inlineableFns, inlineableMethods,
                        crossModule, definitelyInitializedTopFns, nextVarId, new int[]{MAX_INLINES_PER_OWNER}, rewrites),
                null, () -> nextVarId[0]);
        if (rewritten == module) {
            return Result.unchanged(module);
        }
        return new Result(rewritten, rewrites[0]);
    }

    /**
     * Collects self-contained small exported functions and {@code fastRecord} methods
     * ({@code <= 25} HIR nodes) from {@code module} for {@code babblec} AOT cross-module inlining
     * ({@code BIP014-D2}, {@code BIP014-P4}).
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
                } else if (val instanceof HirExpr.ClassExpr ce) {
                    HirClass cls = module.clazz(ce.classId());
                    if (cls != null && cls.fastRecord() && cls.jvmName() != null) {
                        collectCrossModuleRecordMethods(cls, module, result);
                    }
                }
            }
        }
        return result;
    }

    private static void collectCrossModuleRecordMethods(HirClass cls, HirModule module, Map<String, HirFunction> out) {
        for (Map.Entry<String, Integer> entry : cls.methodFunctionIds().entrySet()) {
            HirFunction fn = module.function(entry.getValue());
            HirFunction exported = prepareCrossModuleMethod(fn, cls, module);
            if (exported != null) {
                out.put(METHOD_KEY_PREFIX + cls.shape().id() + "#" + entry.getKey(), exported);
            }
        }
        for (Map.Entry<String, List<Integer>> entry : cls.overloadMethodFunctionIds().entrySet()) {
            List<Integer> ids = entry.getValue();
            for (int alt = 0; alt < ids.size(); alt++) {
                int fnId = ids.get(alt);
                if (fnId >= 0) {
                    HirFunction fn = module.function(fnId);
                    HirFunction exported = prepareCrossModuleMethod(fn, cls, module);
                    if (exported != null) {
                        out.put(METHOD_KEY_PREFIX + cls.shape().id() + "#" + entry.getKey() + "$" + alt, exported);
                    }
                }
            }
        }
    }

    private static HirFunction prepareCrossModuleMethod(HirFunction fn, HirClass cls, HirModule module) {
        if (!isMethodInlineCandidate(fn, module, MAX_CROSS_MODULE_NODES)) {
            return null;
        }
        if (!org.babblelang.opt.HirPurity.isFunctionPureNothrowTerminating(
                fn, org.babblelang.analysis.EffectSet.EMPTY, module, new HashSet<>())) {
            return null;
        }
        boolean[] ok = {true};
        for (HirExpr stmt : fn.body()) {
            scanCrossModuleMethodSelfContained(stmt, cls.id(), ok);
            if (!ok[0]) {
                return null;
            }
        }
        // Rewrite same-module classId references to external JVM names so the snapshot is self-contained.
        List<HirExpr> detachedBody = HirRewriter.rewriteList(fn.body(), e -> detachMethodExprForCrossModule(e, cls));
        return fn.withBody(detachedBody);
    }

    private static void scanCrossModuleMethodSelfContained(HirExpr expr, int ownerClsId, boolean[] ok) {
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
                if (g.externalClassJvmName() == null && g.classId() != ownerClsId) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.ThisFieldAssign a -> {
                ok[0] = false;
                return;
            }
            case HirExpr.SuperCall c -> {
                ok[0] = false;
                return;
            }
            case HirExpr.RecordNew r -> {
                if (r.classId() != ownerClsId) {
                    ok[0] = false;
                    return;
                }
                // Do not scan r.classRef() since it will be replaced with ThisRef + classRefIsInstance=true.
                for (HirExpr a : r.args()) {
                    scanCrossModuleMethodSelfContained(a, ownerClsId, ok);
                }
                return;
            }
            case HirExpr.RecordReuse r -> {
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
            scanCrossModuleMethodSelfContained(child, ownerClsId, ok);
            return child;
        });
    }

    private static HirExpr detachMethodExprForCrossModule(HirExpr expr, HirClass cls) {
        if (expr == null) {
            return null;
        }
        HirExpr cur = HirRewriter.rewriteChildren(expr, child -> detachMethodExprForCrossModule(child, cls));
        if (cur instanceof HirExpr.ThisFieldGet g && g.externalClassJvmName() == null && g.classId() == cls.id()
                && g.memberIndex() >= 0 && g.memberIndex() < cls.memberKinds().length) {
            PrimitiveKind fk = cls.memberKinds()[g.memberIndex()];
            return new HirExpr.ThisFieldGet(g.receiver(), g.receiverText() != null ? g.receiverText() : "this",
                    -1, cls.jvmName(), g.memberIndex(), g.memberName(), fk, true, true, g.meta());
        }
        if (cur instanceof HirExpr.RecordNew r && r.classId() == cls.id()) {
            HirExpr thisRef = new HirExpr.ThisRef(null, cls.id(), ExprMeta.of(r.meta().line(), PrimitiveKind.NONE));
            return new HirExpr.RecordNew(-1, r.classTypeId(), cls.jvmName(), thisRef, true,
                    r.paramKinds(), r.args(), r.binding(), r.meta());
        }
        return cur;
    }

    public static boolean isCrossModuleInlineCandidate(HirFunction fn, HirModule module) {
        if (!isInlineCandidate(fn, MAX_CROSS_MODULE_NODES)
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
                if (g.externalClassJvmName() == null) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.ThisFieldAssign a -> {
                ok[0] = false;
                return;
            }
            case HirExpr.SuperCall c -> {
                ok[0] = false;
                return;
            }
            case HirExpr.RecordNew r -> {
                if (r.classId() >= 0) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.RecordReuse r -> {
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
     * Checks whether a free {@link HirFunction} is a safe, self-contained inline candidate.
     */
    public static boolean isInlineCandidate(HirFunction fn) {
        return isInlineCandidate(fn, MAX_INLINE_NODES);
    }

    private static boolean isInlineCandidate(HirFunction fn, int maxNodes) {
        if (fn == null || fn.isCompiledClassMethod() || fn.namespaceScope() || !fn.capturedVars().isEmpty()
                || fn.effects().isIO() || fn.body().isEmpty()) {
            return false;
        }
        return checkParamsAndBody(fn, -1, maxNodes);
    }

    /**
     * Checks whether a compiled {@code record}/class method is a safe inline candidate ({@code BIP014-P4}).
     */
    public static boolean isMethodInlineCandidate(HirFunction fn, HirModule module) {
        return isMethodInlineCandidate(fn, module, MAX_INLINE_NODES);
    }

    private static boolean isMethodInlineCandidate(HirFunction fn, HirModule module, int maxNodes) {
        if (fn == null || !fn.isCompiledClassMethod() || fn.namespaceScope() || !fn.capturedVars().isEmpty()
                || fn.effects().isIO() || fn.body().isEmpty()) {
            return false;
        }
        HirClass cls = module.clazz(fn.ownerClassId());
        if (cls == null || !cls.hasCompiledClass() || !cls.capturedVars().isEmpty()) {
            return false;
        }
        return checkParamsAndBody(fn, fn.ownerClassId(), maxNodes);
    }

    private static boolean checkParamsAndBody(HirFunction fn, int allowedThisClassId, int maxNodes) {
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
            HirExpr check = (i == lastIdx && stmt instanceof HirExpr.Return r && r.meta().wideningRank() < 0) ? r.value() : stmt;
            scanCalleeBody(check, fn.id(), allowedThisClassId, maxNodes, nodes, ok);
            if (!ok[0] || nodes[0] > maxNodes) {
                return false;
            }
        }
        return true;
    }

    private static void scanCalleeBody(HirExpr expr, int fnId, int allowedThisClassId, int maxNodes, int[] nodes, boolean[] ok) {
        if (expr == null || !ok[0]) return;
        nodes[0]++;
        if (nodes[0] > maxNodes) {
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
                ok[0] = false;
                return;
            }
            case HirExpr.RecurseRef r -> {
                ok[0] = false;
                return;
            }
            case HirExpr.SuperCall c -> {
                ok[0] = false;
                return;
            }
            case HirExpr.ThisRef t -> {
                if (allowedThisClassId < 0 || t.enclosingClassId() != allowedThisClassId) {
                    ok[0] = false;
                    return;
                }
            }
            case HirExpr.ThisFieldAssign a -> {
                if (allowedThisClassId < 0 || a.classId() != allowedThisClassId) {
                    ok[0] = false;
                    return;
                }
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
            case HirExpr.DirectThisMethodCall c -> {
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
            scanCalleeBody(child, fnId, allowedThisClassId, maxNodes, nodes, ok);
            return child;
        });
    }

    private List<HirExpr> inlineList(List<HirExpr> stmts, int ownerMethodId, int currentFnId, HirModule module,
                                     Map<Integer, HirFunction> inlineableFns, Map<Integer, HirFunction> inlineableMethods,
                                     Map<String, HirFunction> crossModule, Set<String> defInitTopFns,
                                     int[] nextVarId, int[] budget, int[] rewrites) {
        return HirRewriter.rewriteList(stmts, e -> inlineExpr(e, ownerMethodId, currentFnId, module,
                inlineableFns, inlineableMethods, crossModule, defInitTopFns, nextVarId, budget, rewrites));
    }

    private HirExpr inlineExpr(HirExpr expr, int ownerMethodId, int currentFnId, HirModule module,
                               Map<Integer, HirFunction> inlineableFns, Map<Integer, HirFunction> inlineableMethods,
                               Map<String, HirFunction> crossModule, Set<String> defInitTopFns,
                               int[] nextVarId, int[] budget, int[] rewrites) {
        HirExpr cur = HirRewriter.rewriteChildren(expr, child -> inlineExpr(child, ownerMethodId, currentFnId, module,
                inlineableFns, inlineableMethods, crossModule, defInitTopFns, nextVarId, budget, rewrites));

        if (budget[0] <= 0) {
            return cur;
        }
        if (cur instanceof HirExpr.DirectBabbleCall call) {
            int targetId = call.targetFunctionId();
            if (targetId == currentFnId) {
                return cur;
            }
            HirFunction calleeFn = inlineableFns.get(targetId);
            if (calleeFn == null || call.args().size() != calleeFn.params().size()
                    || (call.binding() != null && !call.binding().isIdentityPositional())
                    || !canInlineReturnKind(calleeFn, call.meta())) {
                return cur;
            }
            budget[0]--;
            rewrites[0]++;
            boolean keepCallee = !isDefinitelyDefinedPureCallee(call.callee(), defInitTopFns);
            return performInline(call.callee(), keepCallee, null, null, null, call.args(), call.meta(),
                    calleeFn, module, ownerMethodId, nextVarId);
        }
        if (cur instanceof HirExpr.DirectThisMethodCall call) {
            HirFunction calleeFn = null;
            HirClass ownerCls = null;
            if (call.externalClassJvmName() == null) {
                int targetId = call.targetFunctionId();
                if (targetId == currentFnId) {
                    return cur;
                }
                HirClass candidateCls = module.clazz(call.classId());
                if (candidateCls != null && isMethodDefinitelyDefinedForInline(candidateCls, call.memberName(), ownerMethodId)) {
                    calleeFn = inlineableMethods.get(targetId);
                    ownerCls = candidateCls;
                }
            } else if (!crossModule.isEmpty() && call.classTypeId() != null) {
                String key = METHOD_KEY_PREFIX + call.classTypeId() + "#" + call.memberName()
                        + (call.overloadAltIndex() >= 0 ? "$" + call.overloadAltIndex() : "");
                calleeFn = crossModule.get(key);
            }
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
            return performInline(null, false, call.receiver(), call.receiverText(), ownerCls, posArgs,
                    call.meta(), calleeFn, module, ownerMethodId, nextVarId);
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
            return performInline(exp, true, null, null, null, posArgs, call.meta(),
                    calleeFn, module, ownerMethodId, nextVarId);
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

    private static HirExpr performInline(HirExpr calleeExpr, boolean keepCallee,
                                         HirExpr receiverExpr, String receiverText, HirClass receiverCls,
                                         List<HirExpr> args, ExprMeta callMeta, HirFunction calleeFn,
                                         HirModule module, int ownerMethodId, int[] nextVarId) {
        Set<Integer> assignedInCallee = new HashSet<>();
        for (HirExpr s : calleeFn.body()) {
            collectAssignedVars(s, assignedInCallee);
        }
        Set<Integer> assignedInCallArgs = new HashSet<>();
        for (HirExpr a : args) {
            collectAssignedVars(a, assignedInCallArgs);
        }

        List<HirVar> blockDeclared = new ArrayList<>();
        List<HirExpr> stmts = new ArrayList<>();

        if (keepCallee && calleeExpr != null) {
            stmts.add(calleeExpr);
        }

        // Bind or substitute `this` for method inlining.
        HirExpr thisSubst = null;
        if (receiverExpr != null) {
            HirExpr unwrappedRecv = HirRewriter.unwrapTransparent(receiverExpr);
            if (unwrappedRecv instanceof HirExpr.ThisRef) {
                thisSubst = unwrappedRecv;
            } else if (unwrappedRecv instanceof HirExpr.VarRead vr && !vr.var().cell()
                    && (!vr.var().mayBeUndefined() || vr.var().definitelyDefined())
                    && !assignedInCallArgs.contains(vr.var().id())
                    && !assignedInCallee.contains(vr.var().id())) {
                thisSubst = vr;
            } else {
                HirVar freshThis = new HirVar(nextVarId[0]++, "$this", Resolution.Variable.Kind.DEFINITION,
                        ownerMethodId, PrimitiveKind.NONE, false, true, false, true);
                blockDeclared.add(freshThis);
                ExprMeta defMeta = new ExprMeta(receiverExpr.meta().line(), PrimitiveKind.NONE, -1, null,
                        receiverExpr.meta().effects(), receiverExpr.meta().deeplyImmutable(), false);
                stmts.add(new HirExpr.Def("$this", new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1),
                        freshThis, receiverExpr, defMeta));
                thisSubst = new HirExpr.VarRead(freshThis, ExprMeta.of(receiverExpr.meta().line(), PrimitiveKind.NONE,
                        receiverExpr.meta().deeplyImmutable(), false));
            }
        }

        // Decide which parameters can be substituted directly (Const, read-only VarRead, or borrow FunctionRef).
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
                        && (!vr.var().mayBeUndefined() || vr.var().definitelyDefined())
                        && !assignedInCallArgs.contains(vr.var().id())) {
                    substExprs.put(p.var().id(), vr);
                    substParamIds.add(p.var().id());
                } else if (p.isBorrow() && arg instanceof HirExpr.FunctionRef fref) {
                    HirFunction lambda = module.function(fref.functionId());
                    if (lambda != null && !lambda.isCompiledClassMethod() && lambda.capturedVars().isEmpty()) {
                        substExprs.put(p.var().id(), fref);
                        substParamIds.add(p.var().id());
                    }
                }
            }
        }

        // Alpha-rename remaining declared variables (parameters + body locals) of calleeFn.
        Map<Integer, HirVar> varMap = new HashMap<>();
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
            stmts.add(alphaRename(s, varMap, substExprs, thisSubst, receiverText, receiverCls, calleeFn.ownerClassId(),
                    module, ownerMethodId, nextVarId));
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
                                       HirExpr thisSubst, String receiverText, HirClass receiverCls, int calleeOwnerClsId,
                                       HirModule module, int ownerMethodId, int[] nextVarId) {
        if (expr == null) {
            return null;
        }
        return switch (expr) {
            case HirExpr.ThisRef t -> {
                if (thisSubst != null && t.enclosingClassId() == calleeOwnerClsId) {
                    yield thisSubst;
                }
                yield t;
            }
            case HirExpr.ThisFieldGet g -> {
                HirExpr nr = alphaRename(g.receiver(), varMap, substExprs, thisSubst, receiverText, receiverCls,
                        calleeOwnerClsId, module, ownerMethodId, nextVarId);
                if (thisSubst != null && g.receiver() instanceof HirExpr.ThisRef tr && tr.enclosingClassId() == calleeOwnerClsId) {
                    if (g.externalClassJvmName() != null) {
                        String rt = receiverText != null ? receiverText : g.receiverText();
                        yield new HirExpr.ThisFieldGet(nr, rt, -1, g.externalClassJvmName(), g.memberIndex(),
                                g.memberName(), g.fieldKind(), g.recordField(), g.definitelyDefined(), g.meta());
                    }
                    if (receiverCls != null && g.classId() == receiverCls.id() && g.memberIndex() >= 0) {
                        PrimitiveKind fk = receiverCls.memberKinds()[g.memberIndex()];
                        boolean isRec = receiverCls.shape().isValue();
                        boolean isDef = isRec && (g.memberIndex() >= receiverCls.primDefBits().length
                                || receiverCls.primDefBits()[g.memberIndex()] < 0);
                        String rt = receiverText != null ? receiverText : g.receiverText();
                        yield new HirExpr.ThisFieldGet(nr, rt, g.classId(), null, g.memberIndex(),
                                g.memberName(), fk, isRec, isDef, g.meta());
                    }
                }
                yield nr == g.receiver() ? g : new HirExpr.ThisFieldGet(nr, g.receiverText(), g.classId(),
                        g.externalClassJvmName(), g.memberIndex(), g.memberName(), g.fieldKind(),
                        g.recordField(), g.definitelyDefined(), g.meta());
            }
            case HirExpr.VarRead v -> {
                HirExpr subst = substExprs.get(v.var().id());
                if (subst != null) {
                    if (v.meta().wideningRank() >= 0) {
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
            case HirExpr.Move m -> {
                HirExpr no = alphaRename(m.operand(), varMap, substExprs, thisSubst, receiverText, receiverCls,
                        calleeOwnerClsId, module, ownerMethodId, nextVarId);
                HirVar mv = no instanceof HirExpr.VarRead vr ? vr.var()
                        : (m.var() != null ? varMap.getOrDefault(m.var().id(), m.var()) : null);
                yield new HirExpr.Move(mv, no, m.meta());
            }
            case HirExpr.DynamicCall dc -> {
                HirExpr nc = alphaRename(dc.callee(), varMap, substExprs, thisSubst, receiverText, receiverCls,
                        calleeOwnerClsId, module, ownerMethodId, nextVarId);
                List<HirCallArg> na = HirRewriter.rewriteCallArgs(dc.args(),
                        c -> alphaRename(c, varMap, substExprs, thisSubst, receiverText, receiverCls,
                                calleeOwnerClsId, module, ownerMethodId, nextVarId));
                // borrow-lambda devirtualization: DynamicCall(FunctionRef(lambda), args) -> DirectBabbleCall!
                if (nc instanceof HirExpr.FunctionRef fref && dc.meta().checkedType() == null
                        && (dc.binding() == null || dc.binding().isIdentityPositional())) {
                    HirFunction lambda = module.function(fref.functionId());
                    if (lambda != null && !lambda.isCompiledClassMethod() && lambda.params().size() == na.size()
                            && allPositionalArgs(na) && (lambda.hasSpec() || lambda.isSpread())) {
                        List<HirExpr> posArgs = new ArrayList<>(na.size());
                        for (HirCallArg a : na) {
                            posArgs.add(a.value());
                        }
                        yield new HirExpr.DirectBabbleCall(fref, fref.functionId(), posArgs, dc.binding(), dc.meta());
                    }
                }
                yield (nc == dc.callee() && na == dc.args()) ? dc
                        : new HirExpr.DynamicCall(nc, na, dc.binding(), dc.babbleSig(), dc.callSite(), dc.meta());
            }
            case HirExpr.Def d -> {
                HirVar mapped = d.var() != null ? varMap.get(d.var().id()) : null;
                HirExpr nv = alphaRename(d.value(), varMap, substExprs, thisSubst, receiverText, receiverCls,
                        calleeOwnerClsId, module, ownerMethodId, nextVarId);
                yield (mapped == null && nv == d.value()) ? d
                        : new HirExpr.Def(d.name(), d.target(), mapped != null ? mapped : d.var(), nv, d.meta());
            }
            case HirExpr.VarAssign a -> {
                HirVar mapped = varMap.get(a.var().id());
                HirExpr nv = alphaRename(a.value(), varMap, substExprs, thisSubst, receiverText, receiverCls,
                        calleeOwnerClsId, module, ownerMethodId, nextVarId);
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
                    ns.add(alphaRename(s, innerMap, substExprs, thisSubst, receiverText, receiverCls,
                            calleeOwnerClsId, module, ownerMethodId, nextVarId));
                }
                yield new HirExpr.Block(ns, b.opensNamespaceScope(), b.declaredNames(), freshDecls, b.innerWideningRank(), b.meta());
            }
            case HirExpr.For f -> {
                HirExpr ni = alphaRename(f.iterable(), varMap, substExprs, thisSubst, receiverText, receiverCls,
                        calleeOwnerClsId, module, ownerMethodId, nextVarId);
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
                    nb.add(alphaRename(s, innerMap, substExprs, thisSubst, receiverText, receiverCls,
                            calleeOwnerClsId, module, ownerMethodId, nextVarId));
                }
                yield new HirExpr.For(ni, f.target(), freshLoop, freshDecls, nb, f.bodyWideningRank(),
                        f.elemSamTarget(), f.meta());
            }
            default -> HirRewriter.rewriteChildren(expr, child -> alphaRename(child, varMap, substExprs,
                    thisSubst, receiverText, receiverCls, calleeOwnerClsId, module, ownerMethodId, nextVarId));
        };
    }

    private static boolean isMethodDefinitelyDefinedForInline(HirClass cls, String memberName, int callerOwnerId) {
        if (cls.fastRecord()) {
            return true;
        }
        // Inside the class's own constructor body, a method defined later in the body may not be initialized yet.
        if (callerOwnerId == cls.id()) {
            return false;
        }
        // Outside the constructor body, every method is defined once construction returns—unless an earlier
        // constructor statement passed `this` out to a call before `def memberName` ran.
        for (HirExpr stmt : cls.body()) {
            if (stmt instanceof HirExpr.Def d && d.target().kind() == LoweringTargets.DefKind.MEMBER
                    && memberName.equals(d.name())) {
                return true;
            }
            if (mayInvokeWithThis(stmt, cls.id())) {
                return false;
            }
        }
        return false;
    }

    private static boolean mayInvokeWithThis(HirExpr expr, int clsId) {
        if (expr == null) {
            return false;
        }
        boolean[] leaked = {false};
        scanThisLeak(expr, clsId, leaked);
        return leaked[0];
    }

    private static void scanThisLeak(HirExpr expr, int clsId, boolean[] leaked) {
        if (expr == null || leaked[0]) {
            return;
        }
        if (expr instanceof HirExpr.ThisFieldGet g && g.receiver() instanceof HirExpr.ThisRef tr
                && tr.enclosingClassId() == clsId) {
            return;
        }
        if (expr instanceof HirExpr.ThisRef tr && tr.enclosingClassId() == clsId) {
            leaked[0] = true;
            return;
        }
        HirRewriter.rewriteChildren(expr, child -> {
            scanThisLeak(child, clsId, leaked);
            return child;
        });
    }

    private static boolean allPositionalArgs(List<HirCallArg> args) {
        for (HirCallArg a : args) {
            if (a.name() != null) {
                return false;
            }
        }
        return true;
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
