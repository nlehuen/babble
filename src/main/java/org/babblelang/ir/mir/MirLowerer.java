package org.babblelang.ir.mir;

import org.babblelang.analysis.BuiltinAxioms;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.effects.Effect;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.runtime.MatchSupport;
import org.babblelang.runtime.Operators;
import org.babblelang.runtime.Range;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers an ANTLR-free {@link HirModule} into per-function SSA {@link MirModule}
 * ({@code BIP014-I3} §6.4).
 *
 * <p>Every non-namespace-scoped {@link HirFunction} (including methods and functions containing
 * {@code BIP028} {@code try}/{@code catch}/{@code finally}/{@code throw}) is lowered into a
 * flat basic-block SSA CFG using Braun-style block-parameter construction. Namespace-scoped
 * functions are explicitly recorded in {@link MirModule#fallbacks()} with reason
 * {@code "namespace-scoped"} so fallback decisions remain visible in {@link MirCanonicalPrinter}.
 */
public final class MirLowerer {
    private static final String THROWN_EXCEPTION_JVM = "org/babblelang/runtime/ThrownException";
    private static final LoweringTargets.JavaCallTarget RANGE_VALIDATE_INT_STEP =
            rangeTarget("validateStep", int.class);
    private static final LoweringTargets.JavaCallTarget RANGE_VALIDATE_LONG_STEP =
            rangeTarget("validateStep", long.class);
    private static final LoweringTargets.JavaCallTarget RANGE_LAST_INT =
            rangeTarget("lastIntElement", int.class, int.class, boolean.class, int.class);
    private static final LoweringTargets.JavaCallTarget RANGE_LAST_LONG =
            rangeTarget("lastLongElement", long.class, long.class, boolean.class, long.class);
    private static final LoweringTargets.JavaCallTarget RANGE_LAST_CHAR =
            rangeTarget("lastCharElement", char.class, char.class, boolean.class, int.class);

    private static LoweringTargets.JavaCallTarget rangeTarget(String name, Class<?>... params) {
        try {
            return new LoweringTargets.JavaCallTarget(
                    org.babblelang.runtime.Range.class.getMethod(name, params), true, false);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Missing Range helper : " + name, e);
        }
    }

    private MirLowerer() {
    }

    public static MirModule lower(HirModule module) {
        Map<Integer, MirFunction> functions = new LinkedHashMap<>();
        Map<Integer, String> fallbacks = new LinkedHashMap<>();
        for (HirFunction fn : module.functions().values()) {
            if (fn.namespaceScope()) {
                fallbacks.put(fn.id(), "namespace-scoped");
                continue;
            }
            MirFunction mirFn = new FunctionLowerer(module, fn).lower();
            functions.put(fn.id(), mirFn);
        }
        return new MirModule(module, functions, fallbacks);
    }

    /**
     * Deterministic child-expression extractor for {@link MirInst.OpaqueExpr} templates.
     * Shared by {@link MirLowerer} and {@code MirEmitter} so SSA operands align 1-to-1 with
     * the child expressions of any opaque HIR node.
     */
    public static List<HirExpr> opaqueChildExprs(HirExpr expr) {
        return switch (expr) {
            case HirExpr.CoreCollectionCall c -> extractCallArgs(c.args());
            case HirExpr.CoreMethodCall c -> {
                List<HirExpr> out = new ArrayList<>(1 + c.args().size());
                out.add(c.receiver());
                out.addAll(c.args());
                yield out;
            }
            case HirExpr.SuperCall c -> extractCallArgs(c.args());
            case HirExpr.ArrayCreate c -> c.args();
            case HirExpr.ArrayOp op -> {
                List<HirExpr> out = new ArrayList<>(1 + op.args().size());
                out.add(op.receiver());
                out.addAll(op.args());
                yield out;
            }
            case HirExpr.ModuleExportGet g -> List.of(g.receiver());
            case HirExpr.DynamicSelect s -> List.of(s.receiver());
            case HirExpr.SpreadAdapt s -> List.of(s.value());
            case HirExpr.JavaSamAdapt j -> List.of(j.value());
            case HirExpr.MakeOverloadSet m -> m.operands();
            case HirExpr.OverloadSelect s -> List.of(s.set());
            case HirExpr.OverloadDispatch d -> {
                List<HirExpr> out = new ArrayList<>(1 + d.args().size());
                out.add(d.callee());
                out.addAll(d.args());
                yield out;
            }
            case HirExpr.DynamicCall c -> {
                List<HirExpr> out = new ArrayList<>(1 + c.args().size());
                out.add(c.callee());
                out.addAll(extractCallArgs(c.args()));
                yield out;
            }
            case HirExpr.SafeCall c -> {
                List<HirExpr> out = new ArrayList<>(1 + c.args().size());
                out.add(c.receiver());
                out.addAll(extractCallArgs(c.args()));
                yield out;
            }
            case HirExpr.NameAssign a -> List.of(a.value());
            case HirExpr.DynamicFieldAssign a -> List.of(a.receiver(), a.value());
            case HirExpr.Def d -> d.value() != null ? List.of(d.value()) : List.of();
            case HirExpr.IntParseFallback ignored -> List.of();
            case HirExpr.CoreCollectionRef ignored -> List.of();
            case HirExpr.CoreExceptionRef ignored -> List.of();
            case HirExpr.NameRead ignored -> List.of();
            case HirExpr.FunctionRef ignored -> List.of();
            case HirExpr.ClassExpr ignored -> List.of();
            case HirExpr.InterfaceExpr ignored -> List.of();
            default -> throw new IllegalStateException("Unsupported opaque HIR template : " + expr.getClass().getSimpleName());
        };
    }

    private static List<HirExpr> extractCallArgs(List<HirCallArg> args) {
        List<HirExpr> out = new ArrayList<>(args.size());
        for (HirCallArg a : args) {
            out.add(a.value());
        }
        return out;
    }

    // ========================================================================= per-function lowerer

    private static final class FunctionLowerer {
        private final HirModule module;
        private final HirFunction fn;
        private final Set<Integer> pinnedVarIds = new LinkedHashSet<>();
        private final Map<Integer, HirVar> pinnedVarsById = new LinkedHashMap<>();
        private final Map<Integer, HirVar> varById = new HashMap<>();
        private final Map<Integer, MirEffect.AliasClass> valAliases = new HashMap<>();
        private final List<BlockBuilder> allBlocks = new ArrayList<>();
        private final Deque<LoopContext> loopStack = new ArrayDeque<>();
        private final Deque<FinallyContext> finallyStack = new ArrayDeque<>();
        private final Deque<List<MirHandler>> handlerStack = new ArrayDeque<>();

        private int nextValId = 0;
        private int nextBlockId = 0;
        private BlockBuilder cur;

        private record EdgeRef(BlockBuilder from, List<MirVal> args) {
        }

        private record IncompletePhi(int varId, int paramIndex, MirVal param) {
        }

        private sealed interface PendingTerm {
            record Br(BlockBuilder target, EdgeRef edge, int line) implements PendingTerm {
            }

            record BrIf(MirVal cond, BlockBuilder thenTarget, EdgeRef thenEdge,
                        BlockBuilder elseTarget, EdgeRef elseEdge, int line) implements PendingTerm {
            }

            record Switch(MirVal key, int[] keys, BlockBuilder[] targets, EdgeRef[] edges,
                          BlockBuilder defaultTarget, EdgeRef defaultEdge, int line) implements PendingTerm {
            }

            record Fixed(MirTerminator term) implements PendingTerm {
            }
        }

        private final class BlockBuilder {
            final int id;
            final List<MirVal> params = new ArrayList<>();
            final List<MirInst> insts = new ArrayList<>();
            final List<MirHandler> handlers;
            final boolean handlerEntry;
            final BlockBuilder handlerDominator;
            final int explicitParamCount;
            final List<EdgeRef> incomingEdges = new ArrayList<>();
            final Map<Integer, MirVal> currentDef = new HashMap<>();
            final List<IncompletePhi> incompletePhis = new ArrayList<>();
            boolean sealed;
            PendingTerm term;

            BlockBuilder(int id, List<MirVal> explicitParams, List<MirHandler> handlers,
                         boolean handlerEntry, BlockBuilder handlerDominator, boolean sealed) {
                this.id = id;
                this.params.addAll(explicitParams);
                this.explicitParamCount = explicitParams.size();
                this.handlers = List.copyOf(handlers);
                this.handlerEntry = handlerEntry;
                this.handlerDominator = handlerDominator;
                this.sealed = sealed;
            }

            boolean isTerminated() {
                return term != null;
            }
        }

        private record LoopContext(BlockBuilder breakBlock, BlockBuilder continueBlock,
                                   boolean passResultOnContinue, MirVal currentResultVal,
                                   int loopDepth) {
        }

        private record FinallyContext(HirExpr.Try tryNode, int loopDepthAtEntry,
                                      boolean hasNonEmptyFinally) {
        }

        private record NarrowHint(HirVar var, MirInst.NarrowKind kind, String desc, boolean onTrueBranch) {
        }

        FunctionLowerer(HirModule module, HirFunction fn) {
            this.module = module;
            this.fn = fn;
        }

        MirFunction lower() {
            scanVarsAndPinning();
            handlerStack.push(List.of());

            // Create entry block (sealed immediately: no predecessors).
            BlockBuilder entry = newBlock(List.of(), false, true);
            cur = entry;

            // Bind function parameters as SSA values (for non-pinned params) or pinned slots.
            List<MirVal> mirParams = new ArrayList<>(fn.params().size());
            List<HirVar> paramVars = new ArrayList<>(fn.params().size());
            for (int i = 0; i < fn.params().size(); i++) {
                HirParam p = fn.params().get(i);
                HirVar v = p.var();
                if (v != null) {
                    varById.put(v.id(), v);
                    paramVars.add(v);
                    if (!pinnedVarIds.contains(v.id())) {
                        MirVal pv = newVal(v.primitiveKind(), v.name());
                        mirParams.add(pv);
                        entry.currentDef.put(v.id(), pv);
                        if (p.isUnique()) {
                            valAliases.put(pv.id(), MirEffect.AliasClass.uniqueParam(i));
                        }
                    } else {
                        // Still record param MirVal placeholder? No — MirFunction.params() holds SSA params
                        // matching paramVars for non-pinned params only, so MirEmitter knows which params
                        // are read via SSA vs pinned slot.
                        paramVars.remove(paramVars.size() - 1);
                    }
                }
            }

            // Lower function body statements.
            MirVal bodyVal = lowerStatements(fn.body(), fn.line());
            if (!cur.isTerminated()) {
                bodyVal = applyWideningRank(bodyVal, fn.bodyWideningRank(), fn.line());
                bodyVal = convertVal(bodyVal, fn.returnKind(), fn.line());
                terminateFixed(new MirTerminator.Ret(bodyVal, fn.returnKind(), -1, fn.line()));
            }

            // Seal any remaining unsealed blocks (defensive; normally all are sealed).
            for (BlockBuilder b : allBlocks) {
                if (!b.sealed) {
                    sealBlock(b);
                }
            }

            // Finalize immutable MirBlocks and simplify trivial block params.
            List<MirBlock> built = buildBlocks();
            built = eliminateTrivialBlockParams(built);

            String ownerClassName = null;
            if (fn.ownerClassId() >= 0) {
                HirClass owner = module.clazz(fn.ownerClassId());
                if (owner != null) {
                    ownerClassName = owner.shape().name();
                }
            }

            return new MirFunction(
                    fn.id(),
                    fn.name(),
                    ownerClassName,
                    fn.ownerClassId(),
                    mirParams,
                    paramVars,
                    fn.returnKind(),
                    fn.bodyWideningRank(),
                    entry.id,
                    built,
                    nextValId,
                    nextBlockId,
                    List.copyOf(pinnedVarsById.values()),
                    valAliases
            );
        }

        // ------------------------------------------------------------------ var pre-scan & pinning

        private void scanVarsAndPinning() {
            for (HirVar v : fn.declaredVars()) {
                registerVar(v);
                if (mustAlwaysPin(v)) {
                    pin(v);
                }
            }
            for (HirParam p : fn.params()) {
                if (p.var() != null) {
                    registerVar(p.var());
                    if (mustAlwaysPin(p.var())) {
                        pin(p.var());
                    }
                }
            }
            // Walk body to register all block-local vars and pin any outer var written inside try
            // (or inside catch of a try-finally) so exceptions observe exact mutation state.
            Set<Integer> outerDeclared = new HashSet<>();
            for (HirVar v : fn.declaredVars()) {
                outerDeclared.add(v.id());
            }
            for (HirParam p : fn.params()) {
                if (p.var() != null) {
                    outerDeclared.add(p.var().id());
                }
            }
            scanStmts(fn.body(), outerDeclared, Set.of());
        }

        private void registerVar(HirVar v) {
            if (v != null) {
                varById.put(v.id(), v);
            }
        }

        private boolean mustAlwaysPin(HirVar v) {
            return v.isCaptured()
                    || (v.mayBeUndefined() && !v.definitelyDefined())
                    || v.kind() == Resolution.Variable.Kind.THIS
                    || v.kind() == Resolution.Variable.Kind.RECURSE;
        }

        private void pin(HirVar v) {
            if (v != null && pinnedVarIds.add(v.id())) {
                pinnedVarsById.put(v.id(), v);
            }
        }

        private void scanStmts(List<HirExpr> stmts, Set<Integer> visibleVars, Set<Integer> tryOuterVars) {
            for (HirExpr e : stmts) {
                scanExpr(e, visibleVars, tryOuterVars);
            }
        }

        private void scanExpr(HirExpr expr, Set<Integer> visibleVars, Set<Integer> tryOuterVars) {
            if (expr == null) {
                return;
            }
            switch (expr) {
                case HirExpr.VarRead v -> {
                    registerVar(v.var());
                    if (mustAlwaysPin(v.var())) pin(v.var());
                }
                case HirExpr.ThisRef t -> {
                    if (t.var() != null) {
                        registerVar(t.var());
                        pin(t.var());
                    }
                }
                case HirExpr.RecurseRef r -> {
                    if (r.var() != null) {
                        registerVar(r.var());
                        pin(r.var());
                    }
                }
                case HirExpr.Def d -> {
                    if (d.var() != null) {
                        registerVar(d.var());
                        if (mustAlwaysPin(d.var()) || tryOuterVars.contains(d.var().id())) {
                            pin(d.var());
                        }
                    }
                    scanExpr(d.value(), visibleVars, tryOuterVars);
                }
                case HirExpr.VarAssign a -> {
                    registerVar(a.var());
                    if (mustAlwaysPin(a.var()) || tryOuterVars.contains(a.var().id())) {
                        pin(a.var());
                    }
                    scanExpr(a.value(), visibleVars, tryOuterVars);
                }
                case HirExpr.Block b -> {
                    Set<Integer> nextVis = addDeclared(visibleVars, b.declaredVars());
                    scanStmts(b.statements(), nextVis, tryOuterVars);
                }
                case HirExpr.If i -> {
                    scanExpr(i.cond(), visibleVars, tryOuterVars);
                    scanExpr(i.thenBranch(), visibleVars, tryOuterVars);
                    scanExpr(i.elseBranch(), visibleVars, tryOuterVars);
                }
                case HirExpr.Match m -> {
                    if (m.scrutineeVar() != null) {
                        registerVar(m.scrutineeVar());
                        if (mustAlwaysPin(m.scrutineeVar())) pin(m.scrutineeVar());
                    }
                    scanExpr(m.scrutinee(), visibleVars, tryOuterVars);
                    for (HirExpr.MatchArm arm : m.arms()) {
                        Set<Integer> armVis = addDeclared(visibleVars, arm.boundVars());
                        for (HirVar bv : arm.boundVars()) {
                            registerVar(bv);
                            if (mustAlwaysPin(bv)) pin(bv);
                        }
                        scanPattern(arm.pattern(), armVis, tryOuterVars);
                        scanExpr(arm.guard(), armVis, tryOuterVars);
                        scanExpr(arm.body(), armVis, tryOuterVars);
                    }
                    if (m.elseArm() != null) {
                        Set<Integer> elseVis = visibleVars;
                        if (m.elseArm().var() != null) {
                            registerVar(m.elseArm().var());
                            if (mustAlwaysPin(m.elseArm().var())) pin(m.elseArm().var());
                            elseVis = addDeclared(visibleVars, List.of(m.elseArm().var()));
                        }
                        scanExpr(m.elseArm().body(), elseVis, tryOuterVars);
                    }
                }
                case HirExpr.While w -> {
                    scanExpr(w.cond(), visibleVars, tryOuterVars);
                    scanExpr(w.body(), visibleVars, tryOuterVars);
                }
                case HirExpr.For f -> {
                    if (f.countedRange() != null) {
                        scanExpr(f.countedRange().start(), visibleVars, tryOuterVars);
                        scanExpr(f.countedRange().end(), visibleVars, tryOuterVars);
                        scanExpr(f.countedRange().step(), visibleVars, tryOuterVars);
                    } else {
                        scanExpr(f.iterable(), visibleVars, tryOuterVars);
                    }
                    if (f.loopVar() != null) {
                        registerVar(f.loopVar());
                        if (mustAlwaysPin(f.loopVar())) pin(f.loopVar());
                    }
                    Set<Integer> nextVis = addDeclared(visibleVars, f.bodyDeclaredVars());
                    if (f.loopVar() != null) {
                        nextVis = new HashSet<>(nextVis);
                        nextVis.add(f.loopVar().id());
                    }
                    scanStmts(f.body(), nextVis, tryOuterVars);
                }
                case HirExpr.Try t -> {
                    // Any variable visible before entering try that is written inside tryBody must be pinned,
                    // because a mid-try throw could jump to catch or finally observing that write.
                    Set<Integer> outerForTry = new HashSet<>(tryOuterVars);
                    outerForTry.addAll(visibleVars);
                    Set<Integer> tryVis = addDeclared(visibleVars, t.tryDeclaredVars());
                    scanStmts(t.tryBody(), tryVis, outerForTry);

                    boolean hasNonEmptyFinally = t.hasFinally() && !t.finallyBody().isEmpty();
                    Set<Integer> outerForCatch = hasNonEmptyFinally ? outerForTry : tryOuterVars;
                    for (HirExpr.HirCatch cc : t.catches()) {
                        if (cc.var() != null) {
                            registerVar(cc.var());
                            if (mustAlwaysPin(cc.var())) pin(cc.var());
                        }
                        Set<Integer> catchVis = addDeclared(visibleVars, cc.declaredVars());
                        if (cc.var() != null) {
                            catchVis = new HashSet<>(catchVis);
                            catchVis.add(cc.var().id());
                        }
                        scanStmts(cc.body(), catchVis, outerForCatch);
                    }
                    if (hasNonEmptyFinally) {
                        Set<Integer> finVis = addDeclared(visibleVars, t.finallyDeclaredVars());
                        scanStmts(t.finallyBody(), finVis, tryOuterVars);
                    }
                }
                case HirExpr.Unary u -> scanExpr(u.operand(), visibleVars, tryOuterVars);
                case HirExpr.Binary b -> {
                    scanExpr(b.left(), visibleVars, tryOuterVars);
                    scanExpr(b.right(), visibleVars, tryOuterVars);
                }
                case HirExpr.Range r -> {
                    scanExpr(r.start(), visibleVars, tryOuterVars);
                    scanExpr(r.end(), visibleVars, tryOuterVars);
                }
                case HirExpr.Logic l -> {
                    scanExpr(l.left(), visibleVars, tryOuterVars);
                    scanExpr(l.right(), visibleVars, tryOuterVars);
                }
                case HirExpr.Is i -> scanExpr(i.left(), visibleVars, tryOuterVars);
                case HirExpr.As a -> scanExpr(a.left(), visibleVars, tryOuterVars);
                case HirExpr.NonNull n -> scanExpr(n.operand(), visibleVars, tryOuterVars);
                case HirExpr.SpreadAdapt s -> scanExpr(s.value(), visibleVars, tryOuterVars);
                case HirExpr.JavaSamAdapt j -> scanExpr(j.value(), visibleVars, tryOuterVars);
                case HirExpr.TypeApply t -> scanExpr(t.inner(), visibleVars, tryOuterVars);
                case HirExpr.Move m -> {
                    if (m.var() != null) {
                        registerVar(m.var());
                        if (mustAlwaysPin(m.var())) pin(m.var());
                    }
                    scanExpr(m.operand(), visibleVars, tryOuterVars);
                }
                case HirExpr.Freeze f -> scanExpr(f.operand(), visibleVars, tryOuterVars);
                case HirExpr.Break b -> scanExpr(b.value(), visibleVars, tryOuterVars);
                case HirExpr.Return r -> scanExpr(r.value(), visibleVars, tryOuterVars);
                case HirExpr.Throw th -> scanExpr(th.value(), visibleVars, tryOuterVars);
                case HirExpr.ThisFieldGet g -> scanExpr(g.receiver(), visibleVars, tryOuterVars);
                case HirExpr.ThisFieldAssign a -> {
                    scanExpr(a.receiver(), visibleVars, tryOuterVars);
                    scanExpr(a.value(), visibleVars, tryOuterVars);
                }
                case HirExpr.JavaFieldGet g -> scanExpr(g.receiver(), visibleVars, tryOuterVars);
                case HirExpr.JavaFieldAssign a -> {
                    scanExpr(a.receiver(), visibleVars, tryOuterVars);
                    scanExpr(a.value(), visibleVars, tryOuterVars);
                }
                case HirExpr.DynamicFieldAssign a -> {
                    scanExpr(a.receiver(), visibleVars, tryOuterVars);
                    scanExpr(a.value(), visibleVars, tryOuterVars);
                }
                case HirExpr.NameAssign a -> scanExpr(a.value(), visibleVars, tryOuterVars);
                case HirExpr.ArrayCreate c -> scanStmts(c.args(), visibleVars, tryOuterVars);
                case HirExpr.ArrayOp op -> {
                    scanExpr(op.receiver(), visibleVars, tryOuterVars);
                    scanStmts(op.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.ModuleExportGet g -> scanExpr(g.receiver(), visibleVars, tryOuterVars);
                case HirExpr.DynamicSelect s -> scanExpr(s.receiver(), visibleVars, tryOuterVars);
                case HirExpr.CoreCollectionCall c -> scanCallArgs(c.args(), visibleVars, tryOuterVars);
                case HirExpr.SuperCall c -> {
                    if (c.thisVar() != null) {
                        registerVar(c.thisVar());
                        pin(c.thisVar());
                    }
                    scanCallArgs(c.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.SelfRecurseCall c -> scanStmts(c.args(), visibleVars, tryOuterVars);
                case HirExpr.SelfTailCall c -> scanCallArgs(c.args(), visibleVars, tryOuterVars);
                case HirExpr.DirectBabbleCall c -> {
                    scanExpr(c.callee(), visibleVars, tryOuterVars);
                    scanStmts(c.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.DirectThisMethodCall c -> {
                    scanExpr(c.receiver(), visibleVars, tryOuterVars);
                    scanCallArgs(c.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.InterfaceCall c -> {
                    scanExpr(c.receiver(), visibleVars, tryOuterVars);
                    scanStmts(c.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.JavaConstructorCall c -> {
                    scanExpr(c.callee(), visibleVars, tryOuterVars);
                    scanStmts(c.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.JavaMethodCall c -> {
                    scanExpr(c.receiver(), visibleVars, tryOuterVars);
                    scanStmts(c.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.CoreMethodCall c -> {
                    scanExpr(c.receiver(), visibleVars, tryOuterVars);
                    scanStmts(c.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.SafeCall c -> {
                    scanExpr(c.receiver(), visibleVars, tryOuterVars);
                    scanCallArgs(c.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.DynamicCall c -> {
                    scanExpr(c.callee(), visibleVars, tryOuterVars);
                    scanCallArgs(c.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.MakeOverloadSet m -> scanStmts(m.operands(), visibleVars, tryOuterVars);
                case HirExpr.OverloadSelect s -> scanExpr(s.set(), visibleVars, tryOuterVars);
                case HirExpr.OverloadDispatch d -> {
                    scanExpr(d.callee(), visibleVars, tryOuterVars);
                    scanStmts(d.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.RecordNew r -> {
                    scanExpr(r.classRef(), visibleVars, tryOuterVars);
                    scanStmts(r.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.RecordReuse r -> {
                    scanExpr(r.target(), visibleVars, tryOuterVars);
                    scanStmts(r.args(), visibleVars, tryOuterVars);
                }
                case HirExpr.Const ignored -> {}
                case HirExpr.IntParseFallback ignored -> {}
                case HirExpr.CoreCollectionRef ignored -> {}
                case HirExpr.CoreExceptionRef ignored -> {}
                case HirExpr.NameRead ignored -> {}
                case HirExpr.Continue ignored -> {}
                case HirExpr.FunctionRef ignored -> {}
                case HirExpr.ClassExpr ignored -> {}
                case HirExpr.InterfaceExpr ignored -> {}
            }
        }

        private Set<Integer> addDeclared(Set<Integer> base, List<HirVar> decls) {
            if (decls.isEmpty()) {
                return base;
            }
            Set<Integer> next = new HashSet<>(base);
            for (HirVar v : decls) {
                registerVar(v);
                if (mustAlwaysPin(v)) {
                    pin(v);
                }
                next.add(v.id());
            }
            return next;
        }

        private void scanCallArgs(List<HirCallArg> args, Set<Integer> visibleVars, Set<Integer> tryOuterVars) {
            for (HirCallArg a : args) {
                scanExpr(a.value(), visibleVars, tryOuterVars);
            }
        }

        // ------------------------------------------------------------------ SSA & block builders

        private MirVal newVal(PrimitiveKind kind, String hint) {
            return new MirVal(nextValId++, kind != null ? kind : PrimitiveKind.NONE, hint);
        }

        private BlockBuilder newBlock(List<MirVal> explicitParams, boolean handlerEntry, boolean sealed) {
            BlockBuilder b = new BlockBuilder(nextBlockId++, explicitParams, handlerStack.peek(), handlerEntry, null, sealed);
            allBlocks.add(b);
            return b;
        }

        private BlockBuilder newBlockWithHandlers(List<MirVal> explicitParams, List<MirHandler> handlers,
                                                  boolean handlerEntry, boolean sealed) {
            return newBlockWithHandlers(explicitParams, handlers, handlerEntry, null, sealed);
        }

        private BlockBuilder newBlockWithHandlers(List<MirVal> explicitParams, List<MirHandler> handlers,
                                                  boolean handlerEntry, BlockBuilder handlerDominator, boolean sealed) {
            BlockBuilder b = new BlockBuilder(nextBlockId++, explicitParams, handlers, handlerEntry, handlerDominator, sealed);
            allBlocks.add(b);
            return b;
        }

        private void emit(MirInst inst) {
            if (!cur.isTerminated()) {
                cur.insts.add(inst);
            }
        }

        private void terminateBr(BlockBuilder target, List<MirVal> explicitArgs, int line) {
            if (cur.isTerminated()) {
                return;
            }
            EdgeRef edge = createEdge(cur, target, explicitArgs);
            cur.term = new PendingTerm.Br(target, edge, line);
        }

        private void terminateBrIf(MirVal cond, BlockBuilder thenTarget, List<MirVal> thenArgs,
                                   BlockBuilder elseTarget, List<MirVal> elseArgs, int line) {
            if (cur.isTerminated()) {
                return;
            }
            EdgeRef thenEdge = createEdge(cur, thenTarget, thenArgs);
            EdgeRef elseEdge = createEdge(cur, elseTarget, elseArgs);
            cur.term = new PendingTerm.BrIf(cond, thenTarget, thenEdge, elseTarget, elseEdge, line);
        }

        private void terminateSwitch(MirVal key, int[] keys, BlockBuilder[] targets,
                                     BlockBuilder defaultTarget, int line) {
            if (cur.isTerminated()) {
                return;
            }
            EdgeRef[] edges = new EdgeRef[targets.length];
            for (int i = 0; i < targets.length; i++) {
                edges[i] = createEdge(cur, targets[i], List.of());
            }
            EdgeRef defEdge = createEdge(cur, defaultTarget, List.of());
            cur.term = new PendingTerm.Switch(key, keys, targets, edges, defaultTarget, defEdge, line);
        }

        private void terminateFixed(MirTerminator term) {
            if (!cur.isTerminated()) {
                cur.term = new PendingTerm.Fixed(term);
            }
        }

        private EdgeRef createEdge(BlockBuilder from, BlockBuilder target, List<MirVal> explicitArgs) {
            if (explicitArgs.size() != target.explicitParamCount) {
                throw new IllegalStateException("Explicit arg count mismatch : " + explicitArgs.size()
                        + " vs " + target.explicitParamCount + " for bb" + target.id);
            }
            List<MirVal> padded = new ArrayList<>(target.params.size());
            padded.addAll(explicitArgs);
            while (padded.size() < target.params.size()) {
                padded.add(null);
            }
            EdgeRef edge = new EdgeRef(from, padded);
            target.incomingEdges.add(edge);
            return edge;
        }

        private void writeVar(HirVar v, MirVal val, int line) {
            if (pinnedVarIds.contains(v.id())) {
                MirVal conv = convertVal(val, v.primitiveKind(), line);
                emit(new MirInst.SlotWrite(v, MirInst.SlotOpKind.ASSIGN, conv, line, slotWriteEffect(v)));
                return;
            }
            MirVal conv = convertVal(val, v.primitiveKind(), line);
            cur.currentDef.put(v.id(), conv);
        }

        private void defVar(HirVar v, MirVal val, int line) {
            if (pinnedVarIds.contains(v.id())) {
                MirVal conv = convertVal(val, v.primitiveKind(), line);
                emit(new MirInst.SlotWrite(v, MirInst.SlotOpKind.INIT, conv, line, slotWriteEffect(v)));
                return;
            }
            MirVal conv = convertVal(val, v.primitiveKind(), line);
            cur.currentDef.put(v.id(), conv);
        }

        private MirVal readVar(HirVar v, int line) {
            if (pinnedVarIds.contains(v.id())) {
                MirVal res = newVal(v.primitiveKind(), v.name());
                emit(new MirInst.SlotRead(res, v, line, slotReadEffect(v)));
                return res;
            }
            return readSsaVar(v, cur, line);
        }

        private MirVal readSsaVar(HirVar v, BlockBuilder blk, int line) {
            MirVal local = blk.currentDef.get(v.id());
            if (local != null) {
                return local;
            }
            if (!blk.sealed) {
                MirVal bp = newVal(v.primitiveKind(), v.name());
                int idx = blk.params.size();
                blk.params.add(bp);
                for (EdgeRef e : blk.incomingEdges) {
                    e.args.add(null);
                }
                blk.incompletePhis.add(new IncompletePhi(v.id(), idx, bp));
                blk.currentDef.put(v.id(), bp);
                return bp;
            }
            if (blk.handlerDominator != null) {
                MirVal domVal = readSsaVar(v, blk.handlerDominator, line);
                blk.currentDef.put(v.id(), domVal);
                return domVal;
            }
            if (blk.incomingEdges.isEmpty()) {
                // Unreachable block or variable not yet defined on entry path: materialize default constant.
                MirVal def = emitDefaultConstIn(blk, v.primitiveKind(), line);
                blk.currentDef.put(v.id(), def);
                return def;
            }
            if (blk.incomingEdges.size() == 1) {
                MirVal predVal = readSsaVar(v, blk.incomingEdges.get(0).from, line);
                blk.currentDef.put(v.id(), predVal);
                return predVal;
            }
            // Sealed block with multiple predecessors: reserve block parameter slot first to break cycles.
            MirVal bp = newVal(v.primitiveKind(), v.name());
            int idx = blk.params.size();
            blk.params.add(bp);
            for (EdgeRef e : blk.incomingEdges) {
                e.args.add(null);
            }
            blk.currentDef.put(v.id(), bp);
            for (EdgeRef e : blk.incomingEdges) {
                MirVal incoming = readSsaVar(v, e.from, line);
                e.args.set(idx, incoming);
            }
            return bp;
        }

        private void sealBlock(BlockBuilder blk) {
            if (blk.sealed) {
                return;
            }
            blk.sealed = true;
            for (int i = 0; i < blk.incompletePhis.size(); i++) {
                IncompletePhi ip = blk.incompletePhis.get(i);
                HirVar v = varById.get(ip.varId);
                for (EdgeRef e : blk.incomingEdges) {
                    MirVal incoming = readSsaVar(v, e.from, fn.line());
                    e.args.set(ip.paramIndex, incoming);
                }
            }
            blk.incompletePhis.clear();
        }

        private MirVal emitConst(Object value, PrimitiveKind kind, int line) {
            MirVal v = newVal(kind, null);
            emit(new MirInst.Const(v, value, line));
            return v;
        }

        private MirVal emitDefaultConstIn(BlockBuilder blk, PrimitiveKind kind, int line) {
            MirVal v = newVal(kind, null);
            Object def = switch (kind) {
                case BYTE -> (byte) 0;
                case SHORT -> (short) 0;
                case INT -> 0;
                case LONG -> 0L;
                case FLOAT -> 0.0f;
                case DOUBLE -> 0.0d;
                case BOOL -> Boolean.FALSE;
                case CHAR -> '\0';
                case NONE -> null;
            };
            blk.insts.add(0, new MirInst.Const(v, def, line));
            return v;
        }

        private MirVal convertVal(MirVal val, PrimitiveKind target, int line) {
            if (val.kind() == target) {
                return val;
            }
            MirVal out = newVal(target, null);
            emit(new MirInst.Convert(out, val, target, line));
            propagateAlias(val, out);
            return out;
        }

        private MirVal applyWideningRank(MirVal val, int rank, int line) {
            if (rank < 0) {
                return val;
            }
            PrimitiveKind target = targetKindForRank(rank);
            if (val.kind().isPrimitive() && target != PrimitiveKind.NONE) {
                return convertVal(val, target, line);
            }
            if (rank == 5 && val.kind().isPrimitive() && val.kind() != PrimitiveKind.BOOL) {
                return convertVal(val, PrimitiveKind.DOUBLE, line);
            }
            PrimitiveKind outKind = (rank == 5 && val.kind() != PrimitiveKind.NONE) ? PrimitiveKind.DOUBLE : PrimitiveKind.NONE;
            MirVal out = newVal(outKind, null);
            emit(new MirInst.Widen(out, val, rank, line));
            return out;
        }

        private MirVal applyCheckedType(MirVal val, String checkedType, int line) {
            if (checkedType == null) {
                return val;
            }
            MirVal boxed = convertVal(val, PrimitiveKind.NONE, line);
            MirVal checked = newVal(PrimitiveKind.NONE, null);
            emit(new MirInst.CheckCast(checked, boxed, null, null, checkedType, line));
            propagateAlias(val, checked);
            return checked;
        }

        private void propagateAlias(MirVal src, MirVal dst) {
            MirEffect.AliasClass a = valAliases.get(src.id());
            if (a != null) {
                valAliases.put(dst.id(), a);
            }
        }

        private void recordExprAlias(MirVal val, ExprMeta meta) {
            if (meta == null || val == null) {
                return;
            }
            if (meta.frozen() || meta.deeplyImmutable()) {
                valAliases.putIfAbsent(val.id(), MirEffect.AliasClass.FROZEN);
            }
        }

        private MirEffect.AliasClass aliasOf(MirVal val, ExprMeta exprMeta) {
            if (exprMeta != null && (exprMeta.frozen() || exprMeta.deeplyImmutable())) {
                return MirEffect.AliasClass.FROZEN;
            }
            MirEffect.AliasClass a = valAliases.get(val.id());
            return a != null ? a : MirEffect.AliasClass.SHARED;
        }

        private MirEffect slotReadEffect(HirVar v) {
            if (!v.cell() && ((v.capturedByValue() && v.ownerMethodId() != fn.id())
                    || v.kind() == Resolution.Variable.Kind.THIS
                    || v.kind() == Resolution.Variable.Kind.RECURSE)) {
                return MirEffect.PURE_NOTHROW;
            }
            boolean nothrow = !v.mayBeUndefined() || v.definitelyDefined();
            return MirEffect.read(MirEffect.AliasClass.SHARED, nothrow, true, true);
        }

        private MirEffect slotWriteEffect(HirVar v) {
            boolean nothrow = !v.mayBeUndefined() || v.definitelyDefined();
            return MirEffect.write(MirEffect.AliasClass.SHARED, nothrow);
        }

        // ------------------------------------------------------------------ statement & expression lowering

        private MirVal lowerStatements(List<HirExpr> stmts, int fallbackLine) {
            if (stmts.isEmpty()) {
                return emitConst(null, PrimitiveKind.NONE, fallbackLine);
            }
            int last = stmts.size() - 1;
            for (int i = 0; i < last; i++) {
                if (cur.isTerminated()) {
                    return emitConst(null, PrimitiveKind.NONE, stmts.get(i).meta().line());
                }
                lowerExpr(stmts.get(i));
            }
            if (cur.isTerminated()) {
                return emitConst(null, PrimitiveKind.NONE, stmts.get(last).meta().line());
            }
            return lowerAndWiden(stmts.get(last));
        }

        private MirVal lowerAndWiden(HirExpr expr) {
            MirVal v = lowerExpr(expr);
            if (cur.isTerminated()) {
                return v;
            }
            v = applyWideningRank(v, expr.meta().wideningRank(), expr.meta().line());
            recordExprAlias(v, expr.meta());
            return v;
        }

        private MirVal lowerAs(HirExpr expr, PrimitiveKind desired) {
            MirVal v = lowerAndWiden(expr);
            if (cur.isTerminated()) {
                return v;
            }
            if (v.kind() == PrimitiveKind.NONE && desired.isPrimitive()) {
                PrimitiveKind exprKind = expr.meta().kind();
                if (exprKind.isPrimitive() && expr.meta().wideningRank() < 0 && exprKind != desired) {
                    v = convertVal(v, exprKind, expr.meta().line());
                }
            }
            return convertVal(v, desired, expr.meta().line());
        }

        private MirVal lowerBoxed(HirExpr expr) {
            return lowerAs(expr, PrimitiveKind.NONE);
        }

        private MirVal lowerExpr(HirExpr expr) {
            int ln = expr.meta().line();
            MirVal res = switch (expr) {
                case HirExpr.Const c -> emitConst(c.value(), constKind(c.value()), ln);
                case HirExpr.VarRead v -> readVar(v.var(), ln);
                case HirExpr.NameRead nr -> lowerNameRead(nr);
                case HirExpr.ThisRef t -> t.var() != null ? readVar(t.var(), ln) : lowerOpaque(expr);
                case HirExpr.RecurseRef r -> r.var() != null ? readVar(r.var(), ln) : lowerOpaque(expr);
                case HirExpr.Def d -> lowerDef(d);
                case HirExpr.VarAssign a -> lowerVarAssign(a);
                case HirExpr.NameAssign a -> lowerNameAssign(a);
                case HirExpr.ThisFieldGet g -> lowerThisFieldGet(g);
                case HirExpr.ThisFieldAssign a -> lowerThisFieldAssign(a);
                case HirExpr.JavaFieldGet g -> lowerJavaFieldGet(g);
                case HirExpr.JavaFieldAssign a -> lowerJavaFieldAssign(a);
                case HirExpr.ModuleExportGet g -> lowerModuleExportGet(g);
                case HirExpr.ArrayOp op -> lowerArrayOp(op);
                case HirExpr.Unary u -> lowerUnary(u);
                case HirExpr.Binary b -> lowerBinary(b);
                case HirExpr.Range r -> lowerRange(r);
                case HirExpr.Logic l -> lowerLogic(l);
                case HirExpr.Is i -> lowerIs(i);
                case HirExpr.As a -> lowerAsExpr(a);
                case HirExpr.NonNull n -> lowerNonNull(n);
                case HirExpr.TypeApply t -> lowerBoxed(t.inner());
                case HirExpr.Move m -> lowerMove(m);
                case HirExpr.Freeze f -> lowerFreeze(f);
                case HirExpr.Block b -> lowerBlock(b);
                case HirExpr.If i -> lowerIf(i);
                case HirExpr.Match m -> lowerMatch(m);
                case HirExpr.While w -> lowerWhile(w);
                case HirExpr.For f -> lowerFor(f);
                case HirExpr.Try t -> lowerTry(t);
                case HirExpr.Break b -> lowerBreak(b);
                case HirExpr.Continue c -> lowerContinue(c);
                case HirExpr.Return r -> lowerReturn(r);
                case HirExpr.Throw th -> lowerThrow(th);
                case HirExpr.SelfTailCall c -> lowerSelfTailCall(c);
                case HirExpr.SelfRecurseCall c -> lowerSelfRecurseCall(c);
                case HirExpr.DirectBabbleCall c -> lowerDirectBabbleCall(c);
                case HirExpr.DirectThisMethodCall c -> lowerDirectThisMethodCall(c);
                case HirExpr.InterfaceCall c -> lowerInterfaceCall(c);
                case HirExpr.JavaMethodCall c -> lowerJavaMethodCall(c);
                case HirExpr.JavaConstructorCall c -> lowerJavaConstructorCall(c);
                case HirExpr.SafeCall c -> lowerSafeCall(c);
                case HirExpr.RecordNew r -> lowerRecordNew(r);
                case HirExpr.RecordReuse r -> lowerRecordReuse(r);
                default -> lowerOpaque(expr);
            };
            recordExprAlias(res, expr.meta());
            return res;
        }

        // ------------------------------------------------------------------ defs & assignments

        private MirVal lowerDef(HirExpr.Def d) {
            int ln = d.meta().line();
            if (d.target().kind() == LoweringTargets.DefKind.REQUIRED) {
                return emitConst(null, PrimitiveKind.NONE, ln);
            }
            if (d.var() == null) {
                return lowerOpaque(d);
            }
            MirVal v = d.value() != null ? lowerExpr(d.value()) : emitConst(null, PrimitiveKind.NONE, ln);
            if (cur.isTerminated()) {
                return v;
            }
            v = applyWideningRank(v, d.meta().wideningRank(), ln);
            MirVal conv = convertVal(v, d.var().primitiveKind(), ln);
            defVar(d.var(), conv, ln);
            return conv;
        }

        private MirVal lowerVarAssign(HirExpr.VarAssign a) {
            int ln = a.meta().line();
            HirVar v = a.var();
            PrimitiveKind vk = v.primitiveKind();
            if (a.operator() == null) {
                MirVal val = lowerAndWiden(a.value());
                if (cur.isTerminated()) return val;
                val = applyWideningRank(val, a.meta().wideningRank(), ln);
                MirVal conv = convertVal(val, vk, ln);
                writeVar(v, conv, ln);
                return conv;
            }
            // Compound assignment: evaluate current value, then rhs, apply binary op, then store.
            HirExpr.BinaryOp bop = compoundOp(a.operator());
            MirVal curVal = readVar(v, ln);
            if (canLowerPrimitiveCompound(vk, a.value().meta().kind(), a.operator())) {
                MirVal rhs = lowerAs(a.value(), vk);
                if (cur.isTerminated()) return rhs;
                MirVal res = newVal(vk, null);
                MirEffect bopFx = (bop == HirExpr.BinaryOp.FLOOR_DIV && !isNonZeroIntegerConst(a.value()))
                        ? MirEffect.PURE_MAY_THROW : MirEffect.PURE_NOTHROW;
                emit(new MirInst.Binary(res, bop, curVal, rhs, a.targetText(), a.valueText(), false, ln, bopFx));
                writeVar(v, res, ln);
                return res;
            }
            MirVal curBoxed = convertVal(curVal, PrimitiveKind.NONE, ln);
            MirVal rhsBoxed = lowerBoxed(a.value());
            if (cur.isTerminated()) return rhsBoxed;
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(new MirInst.Binary(res, bop, curBoxed, rhsBoxed, a.targetText(), a.valueText(), false, ln,
                    dynamicBinaryEffect(bop, a.meta())));
            res = applyWideningRank(res, a.meta().wideningRank(), ln);
            MirVal conv = convertVal(res, vk, ln);
            writeVar(v, conv, ln);
            return conv;
        }

        private MirVal lowerNameRead(HirExpr.NameRead nr) {
            int ln = nr.meta().line();
            boolean defDef = nr.definitelyDefined();
            MirEffect fx;
            if (nr.finalBinding()) {
                fx = defDef ? MirEffect.PURE_NOTHROW : MirEffect.PURE_MAY_THROW;
            } else {
                fx = defDef ? MirEffect.READ_SHARED : MirEffect.READ_SHARED_THROW;
            }
            MirVal res = newVal(PrimitiveKind.NONE, nr.name());
            emit(new MirInst.NameRead(res, nr.name(), nr.finalBinding(), ln, fx));
            return applyCheckedType(res, nr.meta().checkedType(), ln);
        }

        private MirVal lowerNameAssign(HirExpr.NameAssign a) {
            int ln = a.meta().line();
            MirVal val = lowerAndWiden(a.value());
            if (cur.isTerminated()) return val;
            if (a.operator() == null) {
                val = applyWideningRank(val, a.meta().wideningRank(), ln);
            }
            MirVal boxed = convertVal(val, PrimitiveKind.NONE, ln);
            MirVal res = newVal(PrimitiveKind.NONE, a.name());
            MirEffect fx = a.operator() == null ? MirEffect.WRITE_SHARED_THROW : MirEffect.IMPURE_THROW;
            emit(new MirInst.NameAssign(res, a.name(), a.operator(), boxed, a.targetText(), a.valueText(),
                    a.operator() != null ? a.meta().wideningRank() : -1, ln, fx));
            return applyCheckedType(res, a.meta().checkedType(), ln);
        }

        // ------------------------------------------------------------------ fields & arrays

        private MirVal lowerThisFieldGet(HirExpr.ThisFieldGet g) {
            int ln = g.meta().line();
            MirVal recv = lowerBoxed(g.receiver());
            if (cur.isTerminated()) return recv;
            PrimitiveKind outKind = resolveThisFieldKind(g);
            MirEffect fx = thisFieldReadEffect(g, recv);
            MirVal res = newVal(outKind, g.memberName());
            emit(new MirInst.LoadField(res, recv, g.receiverText(), g.classId(), g.externalClassJvmName(),
                    g.memberIndex(), g.memberName(), g.fieldKind(), g.immutableField(), g.definitelyDefined(), ln, fx));
            return applyCheckedType(res, g.meta().checkedType(), ln);
        }

        private PrimitiveKind resolveThisFieldKind(HirExpr.ThisFieldGet g) {
            if (g.classId() >= 0 && g.memberIndex() >= 0) {
                HirClass cls = module.clazz(g.classId());
                if (cls != null && g.memberIndex() < cls.memberKinds().length) {
                    return cls.memberKinds()[g.memberIndex()];
                }
            }
            if (g.externalClassJvmName() != null && g.fieldKind().isPrimitive()) {
                return g.fieldKind();
            }
            return PrimitiveKind.NONE;
        }

        private MirEffect thisFieldReadEffect(HirExpr.ThisFieldGet g, MirVal recv) {
            MirEffect.AliasClass alias = aliasOf(recv, g.receiver().meta());
            if (g.immutableField()) {
                alias = MirEffect.AliasClass.FROZEN;
            }
            boolean nothrow;
            if (g.receiverText() == null && g.classId() >= 0 && g.memberIndex() >= 0) {
                HirClass cls = module.clazz(g.classId());
                nothrow = cls != null && g.immutableField() && g.definitelyDefined()
                        && (g.memberIndex() >= cls.primDefBits().length || cls.primDefBits()[g.memberIndex()] < 0);
            } else {
                nothrow = g.immutableField() && g.definitelyDefined()
                        && (g.receiver() instanceof HirExpr.ThisRef || g.receiver() instanceof HirExpr.RecordNew
                        || g.receiver() instanceof HirExpr.VarRead);
            }
            if (g.immutableField() && nothrow) {
                return MirEffect.READ_FROZEN;
            }
            return MirEffect.read(alias, nothrow, true, true);
        }

        private MirVal lowerThisFieldAssign(HirExpr.ThisFieldAssign a) {
            int ln = a.meta().line();
            HirClass cls = a.classId() >= 0 ? module.clazz(a.classId()) : null;
            PrimitiveKind mk = (cls != null && a.memberIndex() >= 0 && a.memberIndex() < cls.memberKinds().length)
                    ? cls.memberKinds()[a.memberIndex()] : PrimitiveKind.NONE;
            MirVal recv = lowerBoxed(a.receiver());
            if (cur.isTerminated()) return recv;
            MirEffect.AliasClass alias = aliasOf(recv, a.receiver().meta());
            if (a.operator() == null) {
                MirVal val = lowerAndWiden(a.value());
                if (cur.isTerminated()) return val;
                val = applyWideningRank(val, a.meta().wideningRank(), ln);
                if (mk.isPrimitive() && val.kind() == PrimitiveKind.NONE
                        && a.value().meta().kind() == PrimitiveKind.INT && mk == PrimitiveKind.DOUBLE
                        && a.value().meta().wideningRank() < 0 && a.meta().wideningRank() < 0) {
                    val = convertVal(val, PrimitiveKind.INT, ln);
                }
                MirVal stored = convertVal(val, mk, ln);
                emit(new MirInst.StoreField(recv, a.classId(), a.memberIndex(), a.memberName(), stored,
                        a.targetText(), a.valueText(), ln, MirEffect.write(alias, false)));
                return stored;
            }
            // Compound this.field op= rhs
            HirExpr.BinaryOp bop = compoundOp(a.operator());
            MirVal oldVal = newVal(mk, a.memberName());
            emit(new MirInst.LoadField(oldVal, recv, null, a.classId(), null, a.memberIndex(), a.memberName(),
                    mk, false, false, ln, MirEffect.read(alias, false, true, true)));
            MirVal nextVal;
            if (canLowerPrimitiveCompound(mk, a.value().meta().kind(), a.operator())) {
                MirVal rhs = lowerAs(a.value(), mk);
                if (cur.isTerminated()) return rhs;
                nextVal = newVal(mk, null);
                MirEffect bopFx = (bop == HirExpr.BinaryOp.FLOOR_DIV && !isNonZeroIntegerConst(a.value()))
                        ? MirEffect.PURE_MAY_THROW : MirEffect.PURE_NOTHROW;
                emit(new MirInst.Binary(nextVal, bop, oldVal, rhs, a.targetText(), a.valueText(), false, ln, bopFx));
            } else {
                MirVal oldBox = convertVal(oldVal, PrimitiveKind.NONE, ln);
                MirVal rhsBox = lowerBoxed(a.value());
                if (cur.isTerminated()) return rhsBox;
                MirVal raw = newVal(PrimitiveKind.NONE, null);
                emit(new MirInst.Binary(raw, bop, oldBox, rhsBox, a.targetText(), a.valueText(), false, ln,
                        dynamicBinaryEffect(bop, a.meta())));
                raw = applyWideningRank(raw, a.meta().wideningRank(), ln);
                nextVal = convertVal(raw, mk, ln);
            }
            emit(new MirInst.StoreField(recv, a.classId(), a.memberIndex(), a.memberName(), nextVal,
                    a.targetText(), a.valueText(), ln, MirEffect.write(alias, false)));
            return nextVal;
        }

        private MirVal lowerJavaFieldGet(HirExpr.JavaFieldGet g) {
            int ln = g.meta().line();
            LoweringTargets.JavaFieldTarget target = g.target();
            MirVal recv = null;
            if (!target.isStatic() || !g.pureStaticReceiver()) {
                recv = lowerBoxed(g.receiver());
                if (cur.isTerminated()) return recv;
            }
            Field field = target.field();
            PrimitiveKind outKind = javaPrimitiveKind(field.getType());
            boolean isFinal = Modifier.isFinal(field.getModifiers());
            MirEffect.AliasClass alias = isFinal ? MirEffect.AliasClass.FROZEN
                    : (recv != null ? aliasOf(recv, g.receiver().meta()) : MirEffect.AliasClass.SHARED);
            boolean nothrow = target.isStatic() && g.pureStaticReceiver();
            MirEffect fx = MirEffect.read(alias, nothrow, true, true);
            MirVal res = newVal(outKind, field.getName());
            emit(new MirInst.LoadJavaField(res, recv, g.receiverText(), g.pureStaticReceiver(), target, ln, fx));
            return applyCheckedType(res, g.meta().checkedType(), ln);
        }

        private MirVal lowerJavaFieldAssign(HirExpr.JavaFieldAssign a) {
            int ln = a.meta().line();
            LoweringTargets.JavaFieldTarget target = a.target();
            Field field = target.field();
            PrimitiveKind fk = javaPrimitiveKind(field.getType());
            MirVal recv = null;
            if (!target.isStatic() || !a.pureStaticReceiver()) {
                recv = lowerBoxed(a.receiver());
                if (cur.isTerminated()) return recv;
            }
            MirEffect.AliasClass alias = recv != null ? aliasOf(recv, a.receiver().meta()) : MirEffect.AliasClass.SHARED;
            if (a.operator() == null) {
                MirVal val;
                if (fk == PrimitiveKind.INT || fk == PrimitiveKind.BOOL || fk == PrimitiveKind.DOUBLE) {
                    val = lowerAs(a.value(), fk);
                } else if (fk.isPrimitive()) {
                    val = lowerAs(a.value(), fk);
                } else {
                    val = lowerBoxed(a.value());
                }
                if (cur.isTerminated()) return val;
                emit(new MirInst.StoreJavaField(recv, a.receiverText(), a.pureStaticReceiver(), target, val,
                        a.targetText(), a.valueText(), ln, MirEffect.write(alias, false)));
                return val;
            }
            HirExpr.BinaryOp bop = compoundOp(a.operator());
            MirVal oldVal = newVal(fk, field.getName());
            emit(new MirInst.LoadJavaField(oldVal, recv, a.receiverText(), a.pureStaticReceiver(), target, ln,
                    MirEffect.read(alias, false, true, true)));
            MirVal nextVal;
            if (canLowerPrimitiveCompound(fk, a.value().meta().kind(), a.operator())) {
                MirVal rhs = lowerAs(a.value(), fk);
                if (cur.isTerminated()) return rhs;
                nextVal = newVal(fk, null);
                MirEffect bopFx = (bop == HirExpr.BinaryOp.FLOOR_DIV && !isNonZeroIntegerConst(a.value()))
                        ? MirEffect.PURE_MAY_THROW : MirEffect.PURE_NOTHROW;
                emit(new MirInst.Binary(nextVal, bop, oldVal, rhs, a.targetText(), a.valueText(), false, ln, bopFx));
            } else {
                MirVal oldBox = convertVal(oldVal, PrimitiveKind.NONE, ln);
                MirVal rhsBox = lowerBoxed(a.value());
                if (cur.isTerminated()) return rhsBox;
                MirVal raw = newVal(PrimitiveKind.NONE, null);
                emit(new MirInst.Binary(raw, bop, oldBox, rhsBox, a.targetText(), a.valueText(), false, ln,
                        dynamicBinaryEffect(bop, a.meta())));
                raw = applyWideningRank(raw, a.meta().wideningRank(), ln);
                nextVal = raw;
            }
            emit(new MirInst.StoreJavaField(recv, a.receiverText(), a.pureStaticReceiver(), target, nextVal,
                    a.targetText(), a.valueText(), ln, MirEffect.write(alias, false)));
            return nextVal;
        }

        private MirVal lowerArrayOp(HirExpr.ArrayOp op) {
            int ln = op.meta().line();
            return switch (op.kind()) {
                case SIZE -> {
                    MirVal arr = lowerBoxed(op.receiver());
                    if (cur.isTerminated()) yield arr;
                    MirVal res = newVal(PrimitiveKind.INT, "len");
                    // Array length is immutable once allocated (only null-check may throw).
                    MirEffect fx = MirEffect.read(MirEffect.AliasClass.FROZEN, false, true, true);
                    emit(new MirInst.ArrayLen(res, arr, op.receiverText(), op.elemKind(),
                            op.arrayClass(), op.componentClass(), ln, fx));
                    yield applyCheckedType(res, op.meta().checkedType(), ln);
                }
                case GET -> {
                    MirVal arr = lowerBoxed(op.receiver());
                    if (cur.isTerminated()) yield arr;
                    MirVal idx = lowerAs(op.args().get(0), PrimitiveKind.INT);
                    if (cur.isTerminated()) yield idx;
                    PrimitiveKind outKind = op.elemKind().isPrimitive() ? op.elemKind() : PrimitiveKind.NONE;
                    MirVal res = newVal(outKind, null);
                    MirEffect fx = MirEffect.read(aliasOf(arr, op.receiver().meta()), false, true, true);
                    emit(new MirInst.ArrayGet(res, arr, idx, op.receiverText(), op.elemKind(),
                            op.arrayClass(), op.componentClass(), ln, fx));
                    yield applyCheckedType(res, op.meta().checkedType(), ln);
                }
                case SET -> {
                    MirVal arr = lowerBoxed(op.receiver());
                    if (cur.isTerminated()) yield arr;
                    MirVal idx = lowerAs(op.args().get(0), PrimitiveKind.INT);
                    if (cur.isTerminated()) yield idx;
                    MirVal val = op.elemKind().isPrimitive()
                            ? lowerAs(op.args().get(1), op.elemKind())
                            : lowerBoxed(op.args().get(1));
                    if (cur.isTerminated()) yield val;
                    MirEffect fx = MirEffect.write(aliasOf(arr, op.receiver().meta()), false);
                    emit(new MirInst.ArraySet(arr, idx, val, op.receiverText(), op.elemKind(),
                            op.arrayClass(), op.componentClass(), ln, fx));
                    MirVal nullRes = emitConst(null, PrimitiveKind.NONE, ln);
                    yield applyCheckedType(nullRes, op.meta().checkedType(), ln);
                }
                case EQUALS, HASH_CODE, TO_STRING -> lowerOpaque(op);
            };
        }

        // ------------------------------------------------------------------ unary / binary / logic / types

        private MirVal lowerUnary(HirExpr.Unary u) {
            int ln = u.meta().line();
            if (u.op() == HirExpr.UnaryOp.NOT) {
                MirVal operand = lowerExpr(u.operand());
                if (cur.isTerminated()) return operand;
                MirVal res = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Unary(res, HirExpr.UnaryOp.NOT, operand, u.operandText(), ln, MirEffect.PURE_NOTHROW));
                return res;
            }
            PrimitiveKind ok = u.operand().meta().kind();
            PrimitiveKind rk = u.meta().kind();
            if (rk == PrimitiveKind.INT && isNumericKind(ok) && numericOrder(ok) <= 2) {
                MirVal op = lowerAs(u.operand(), PrimitiveKind.INT);
                if (cur.isTerminated()) return op;
                MirVal res = newVal(PrimitiveKind.INT, null);
                emit(new MirInst.Unary(res, HirExpr.UnaryOp.NEGATE, op, u.operandText(), ln, MirEffect.PURE_NOTHROW));
                return res;
            }
            if (rk == PrimitiveKind.LONG && isNumericKind(ok) && numericOrder(ok) <= 3) {
                MirVal op = lowerAs(u.operand(), PrimitiveKind.LONG);
                if (cur.isTerminated()) return op;
                MirVal res = newVal(PrimitiveKind.LONG, null);
                emit(new MirInst.Unary(res, HirExpr.UnaryOp.NEGATE, op, u.operandText(), ln, MirEffect.PURE_NOTHROW));
                return res;
            }
            if (rk == PrimitiveKind.FLOAT && isNumericKind(ok) && numericOrder(ok) <= 4) {
                MirVal op = lowerAs(u.operand(), PrimitiveKind.FLOAT);
                if (cur.isTerminated()) return op;
                MirVal res = newVal(PrimitiveKind.FLOAT, null);
                emit(new MirInst.Unary(res, HirExpr.UnaryOp.NEGATE, op, u.operandText(), ln, MirEffect.PURE_NOTHROW));
                return res;
            }
            if (rk == PrimitiveKind.DOUBLE && isNumericKind(ok)) {
                MirVal op = lowerAs(u.operand(), PrimitiveKind.DOUBLE);
                if (cur.isTerminated()) return op;
                MirVal res = newVal(PrimitiveKind.DOUBLE, null);
                emit(new MirInst.Unary(res, HirExpr.UnaryOp.NEGATE, op, u.operandText(), ln, MirEffect.PURE_NOTHROW));
                return res;
            }
            MirVal op = lowerBoxed(u.operand());
            if (cur.isTerminated()) return op;
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(new MirInst.Unary(res, HirExpr.UnaryOp.NEGATE, op, u.operandText(), ln, effectFromMeta(u.meta(), false)));
            return res;
        }

        private MirVal lowerBinary(HirExpr.Binary b) {
            int ln = b.meta().line();
            PrimitiveKind lk = b.left().meta().kind();
            PrimitiveKind rk = b.right().meta().kind();
            if (b.op().isComparison() && canLowerComparison(b.op(), lk, rk)) {
                PrimitiveKind common;
                if ((lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL)
                        || (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR)) {
                    common = lk;
                } else {
                    common = numericOrder(lk) >= numericOrder(rk) ? lk : rk;
                }
                MirVal lhs = lowerAs(b.left(), common);
                if (cur.isTerminated()) return lhs;
                MirVal rhs = lowerAs(b.right(), common);
                if (cur.isTerminated()) return rhs;
                MirVal res = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Binary(res, b.op(), lhs, rhs, b.leftText(), b.rightText(), false, ln, MirEffect.PURE_NOTHROW));
                return res;
            }
            PrimitiveKind resultKind = b.meta().kind();
            if (isNumericKind(lk) && isNumericKind(rk)) {
                if ((resultKind == PrimitiveKind.INT || resultKind == PrimitiveKind.LONG)
                        && (b.op() == HirExpr.BinaryOp.ADD || b.op() == HirExpr.BinaryOp.SUB || b.op() == HirExpr.BinaryOp.MUL)) {
                    MirVal lhs = lowerAs(b.left(), resultKind);
                    if (cur.isTerminated()) return lhs;
                    MirVal rhs = lowerAs(b.right(), resultKind);
                    if (cur.isTerminated()) return rhs;
                    MirVal res = newVal(resultKind, null);
                    emit(new MirInst.Binary(res, b.op(), lhs, rhs, b.leftText(), b.rightText(), false, ln, MirEffect.PURE_NOTHROW));
                    return res;
                }
                if ((resultKind == PrimitiveKind.INT || resultKind == PrimitiveKind.LONG)
                        && (b.op() == HirExpr.BinaryOp.MOD || b.op() == HirExpr.BinaryOp.FLOOR_DIV)) {
                    MirVal lhs = lowerAs(b.left(), resultKind);
                    if (cur.isTerminated()) return lhs;
                    MirVal rhs = lowerAs(b.right(), resultKind);
                    if (cur.isTerminated()) return rhs;
                    MirVal res = newVal(resultKind, null);
                    MirEffect fx = isNonZeroIntegerConst(b.right()) ? MirEffect.PURE_NOTHROW : MirEffect.PURE_MAY_THROW;
                    emit(new MirInst.Binary(res, b.op(), lhs, rhs, b.leftText(), b.rightText(), false, ln, fx));
                    return res;
                }
                if ((resultKind == PrimitiveKind.FLOAT || resultKind == PrimitiveKind.DOUBLE) && b.op().isArithmetic()) {
                    MirVal lhs = lowerAs(b.left(), resultKind);
                    if (cur.isTerminated()) return lhs;
                    MirVal rhs = lowerAs(b.right(), resultKind);
                    if (cur.isTerminated()) return rhs;
                    MirVal res = newVal(resultKind, null);
                    emit(new MirInst.Binary(res, b.op(), lhs, rhs, b.leftText(), b.rightText(), false, ln, MirEffect.PURE_NOTHROW));
                    return res;
                }
            }
            MirVal lhs = lowerBoxed(b.left());
            if (cur.isTerminated()) return lhs;
            MirVal rhs = lowerBoxed(b.right());
            if (cur.isTerminated()) return rhs;
            boolean stringConcat = b.op() == HirExpr.BinaryOp.ADD
                    && b.left().meta().stringType() && b.right().meta().stringType();
            boolean pureStringEq = (b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ)
                    && b.left().meta().stringType() && b.right().meta().stringType();
            MirEffect fx = (stringConcat || pureStringEq) ? MirEffect.PURE_NOTHROW : dynamicBinaryEffect(b.op(), b.meta());
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(new MirInst.Binary(res, b.op(), lhs, rhs, b.leftText(), b.rightText(), stringConcat, ln, fx));
            return res;
        }

        private MirVal lowerRange(HirExpr.Range r) {
            int ln = r.meta().line();
            PrimitiveKind ek = r.elemKind();
            MirVal s = ek.isPrimitive() ? lowerAs(r.start(), ek) : lowerBoxed(r.start());
            if (cur.isTerminated()) return s;
            MirVal e = ek.isPrimitive() ? lowerAs(r.end(), ek) : lowerBoxed(r.end());
            if (cur.isTerminated()) return e;
            MirVal res = newVal(PrimitiveKind.NONE, null);
            MirEffect fx = ek.isPrimitive() ? MirEffect.PURE_NOTHROW : MirEffect.PURE_MAY_THROW;
            emit(new MirInst.RangeNew(res, s, e, r.inclusive(), ek, ln, fx));
            return res;
        }

        private MirEffect dynamicBinaryEffect(HirExpr.BinaryOp op, ExprMeta meta) {
            if (op.isComparison()) {
                return MirEffect.PURE_MAY_THROW;
            }
            return effectFromMeta(meta, false);
        }

        private MirVal lowerLogic(HirExpr.Logic l) {
            int ln = l.meta().line();
            MirVal lhs = lowerConditionBool(l.left(), ln);
            if (cur.isTerminated()) return lhs;
            NarrowHint hint = extractNarrowHint(l.left());
            MirVal mergeParam = newVal(PrimitiveKind.BOOL, null);
            BlockBuilder bbRight = newBlock(List.of(), false, false);
            BlockBuilder bbMerge = newBlock(List.of(mergeParam), false, false);
            if (l.op() == HirExpr.LogicOp.AND) {
                MirVal falseVal = emitConst(Boolean.FALSE, PrimitiveKind.BOOL, ln);
                terminateBrIf(lhs, bbRight, List.of(), bbMerge, List.of(falseVal), ln);
            } else {
                MirVal trueVal = emitConst(Boolean.TRUE, PrimitiveKind.BOOL, ln);
                terminateBrIf(lhs, bbMerge, List.of(trueVal), bbRight, List.of(), ln);
            }
            sealBlock(bbRight);
            cur = bbRight;
            if (hint != null && ((l.op() == HirExpr.LogicOp.AND && hint.onTrueBranch)
                    || (l.op() == HirExpr.LogicOp.OR && !hint.onTrueBranch))) {
                emitNarrowAnchor(hint, ln);
            }
            MirVal rhs = lowerConditionBool(l.right(), ln);
            if (!cur.isTerminated()) {
                terminateBr(bbMerge, List.of(rhs), ln);
            }
            sealBlock(bbMerge);
            cur = bbMerge;
            return mergeParam;
        }

        private MirVal lowerConditionBool(HirExpr cond, int ln) {
            MirVal v = lowerExpr(cond);
            if (cur.isTerminated()) {
                return v;
            }
            if (v.kind() == PrimitiveKind.BOOL) {
                return v;
            }
            // Convert truthiness of primitive or reference to BOOL via Unary(NOT) twice? Better: Convert(BOOL) or Binary != 0.
            return switch (v.kind()) {
                case BYTE, SHORT, INT, CHAR -> {
                    MirVal zero = emitConst(0, PrimitiveKind.INT, ln);
                    MirVal iv = convertVal(v, PrimitiveKind.INT, ln);
                    MirVal b = newVal(PrimitiveKind.BOOL, null);
                    emit(new MirInst.Binary(b, HirExpr.BinaryOp.NEQ, iv, zero, null, null, false, ln, MirEffect.PURE_NOTHROW));
                    yield b;
                }
                case LONG -> {
                    MirVal zero = emitConst(0L, PrimitiveKind.LONG, ln);
                    MirVal b = newVal(PrimitiveKind.BOOL, null);
                    emit(new MirInst.Binary(b, HirExpr.BinaryOp.NEQ, v, zero, null, null, false, ln, MirEffect.PURE_NOTHROW));
                    yield b;
                }
                case FLOAT -> {
                    MirVal zero = emitConst(0.0f, PrimitiveKind.FLOAT, ln);
                    MirVal b = newVal(PrimitiveKind.BOOL, null);
                    emit(new MirInst.Binary(b, HirExpr.BinaryOp.NEQ, v, zero, null, null, false, ln, MirEffect.PURE_NOTHROW));
                    yield b;
                }
                case DOUBLE -> {
                    MirVal zero = emitConst(0.0d, PrimitiveKind.DOUBLE, ln);
                    MirVal b = newVal(PrimitiveKind.BOOL, null);
                    emit(new MirInst.Binary(b, HirExpr.BinaryOp.NEQ, v, zero, null, null, false, ln, MirEffect.PURE_NOTHROW));
                    yield b;
                }
                case NONE -> {
                    // Use Truth.test(Object) via Convert? Wait: Convert(NONE -> BOOL) unboxes Boolean, NOT Truth.test!
                    // Unary(NOT) calls Truth.test and negates; double NOT? No, let's check: in Babble strict typing,
                    // if cond is NONE (e.g. any or boxed Boolean or nullable), Truth.test(obj) is exact!
                    // Unary(NOT, x) produces !Truth.test(x); Unary(NOT, Unary(NOT, x)) produces Truth.test(x) cleanly
                    // OR we can handle BrIf(cond) directly with Truth.test when cond.kind() == NONE!
                    // Wait: for Logic(AND/OR), mergeParam is BOOL, so we DO want a BOOL value here.
                    MirVal neg = newVal(PrimitiveKind.BOOL, null);
                    emit(new MirInst.Unary(neg, HirExpr.UnaryOp.NOT, v, null, ln, MirEffect.PURE_NOTHROW));
                    MirVal pos = newVal(PrimitiveKind.BOOL, null);
                    emit(new MirInst.Unary(pos, HirExpr.UnaryOp.NOT, neg, null, ln, MirEffect.PURE_NOTHROW));
                    yield pos;
                }
                case BOOL -> v;
            };
        }

        private MirVal lowerIs(HirExpr.Is i) {
            int ln = i.meta().line();
            MirVal operand = lowerBoxed(i.left());
            if (cur.isTerminated()) return operand;
            PrimitiveKind outKind = i.builtin() != null ? PrimitiveKind.NONE : PrimitiveKind.BOOL;
            MirVal res = newVal(outKind, null);
            emit(new MirInst.IsCheck(res, operand, i.builtin(), i.target(), i.intersectionTargets(), i.descriptor(), ln));
            return res;
        }

        private MirVal lowerAsExpr(HirExpr.As a) {
            int ln = a.meta().line();
            if (a.targetDescriptor() == null && a.samTarget() == null) {
                return lowerAndWiden(a.left());
            }
            MirVal operand = lowerBoxed(a.left());
            if (cur.isTerminated()) return operand;
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(new MirInst.CheckCast(res, operand, a.target(), a.samTarget(), a.targetDescriptor(), ln));
            propagateAlias(operand, res);
            return res;
        }

        private MirVal lowerNonNull(HirExpr.NonNull n) {
            int ln = n.meta().line();
            if (n.expectedType() == null) {
                return lowerExpr(n.operand());
            }
            MirVal operand = lowerBoxed(n.operand());
            if (cur.isTerminated()) return operand;
            PrimitiveKind targetKind = n.meta().kind().isPrimitive() ? n.meta().kind() : PrimitiveKind.NONE;
            MirVal res = newVal(targetKind, null);
            emit(new MirInst.NonNull(res, operand, n.expectedType(), ln));
            propagateAlias(operand, res);
            return res;
        }

        private MirVal lowerMove(HirExpr.Move m) {
            int ln = m.meta().line();
            MirVal op = lowerAndWiden(m.operand());
            if (cur.isTerminated()) return op;
            MirVal res = newVal(op.kind(), m.var() != null ? m.var().name() : null);
            emit(new MirInst.Move(res, m.var(), op, ln));
            propagateAlias(op, res);
            return res;
        }

        private MirVal lowerFreeze(HirExpr.Freeze f) {
            int ln = f.meta().line();
            MirVal op = lowerAndWiden(f.operand());
            if (cur.isTerminated()) return op;
            MirVal res = newVal(op.kind(), null);
            emit(new MirInst.Freeze(res, op, ln));
            valAliases.put(res.id(), MirEffect.AliasClass.FROZEN);
            return res;
        }

        // ------------------------------------------------------------------ control flow

        private MirVal lowerBlock(HirExpr.Block b) {
            int ln = b.meta().line();
            if (!b.declaredVars().isEmpty()) {
                for (HirVar v : b.declaredVars()) {
                    if (pinnedVarIds.contains(v.id())) {
                        emit(new MirInst.SlotDecl(v, ln));
                    }
                }
                MirVal res = lowerStatements(b.statements(), ln);
                if (!cur.isTerminated()) {
                    res = applyWideningRank(res, b.innerWideningRank(), ln);
                }
                return res;
            }
            if (b.opensNamespaceScope()) {
                MirVal prevNs = newVal(PrimitiveKind.NONE, "$prevNs");
                emit(new MirInst.ScopeEnter(prevNs, b.declaredNames(), ln));
                MirVal res = lowerStatements(b.statements(), ln);
                if (!cur.isTerminated()) {
                    emit(new MirInst.ScopeExit(prevNs, ln));
                    res = applyWideningRank(res, b.innerWideningRank(), ln);
                }
                return res;
            }
            MirVal res = lowerStatements(b.statements(), ln);
            if (!cur.isTerminated()) {
                res = applyWideningRank(res, b.innerWideningRank(), ln);
            }
            return res;
        }

        private MirVal lowerIf(HirExpr.If i) {
            int ln = i.meta().line();
            PrimitiveKind target = i.meta().kind();
            MirVal cond = lowerExpr(i.cond());
            if (cur.isTerminated()) return cond;
            NarrowHint hint = extractNarrowHint(i.cond());

            BlockBuilder bbThen = newBlock(List.of(), false, false);
            BlockBuilder bbElse = newBlock(List.of(), false, false);
            MirVal mergeParam = newVal(target, null);
            BlockBuilder bbMerge = newBlock(List.of(mergeParam), false, false);

            terminateBrIf(cond, bbThen, List.of(), bbElse, List.of(), ln);
            sealBlock(bbThen);
            sealBlock(bbElse);

            // Then branch
            cur = bbThen;
            if (hint != null && hint.onTrueBranch) {
                emitNarrowAnchor(hint, ln);
            }
            MirVal thenVal = lowerAs(i.thenBranch(), target);
            if (!cur.isTerminated()) {
                terminateBr(bbMerge, List.of(thenVal), ln);
            }

            // Else branch
            cur = bbElse;
            if (hint != null && !hint.onTrueBranch) {
                emitNarrowAnchor(hint, ln);
            }
            MirVal elseVal;
            if (i.elseBranch() != null) {
                elseVal = lowerAs(i.elseBranch(), target);
            } else {
                elseVal = convertVal(emitConst(null, PrimitiveKind.NONE, ln), target, ln);
            }
            if (!cur.isTerminated()) {
                terminateBr(bbMerge, List.of(elseVal), ln);
            }

            sealBlock(bbMerge);
            cur = bbMerge;
            if (bbMerge.incomingEdges.isEmpty()) {
                terminateFixed(new MirTerminator.Unreachable(ln));
            }
            return mergeParam;
        }

        private static final LoweringTargets.JavaCallTarget MATCH_IN_CONTAINER;
        private static final LoweringTargets.JavaCallTarget MATCH_RELATIONAL;
        private static final LoweringTargets.JavaCallTarget MATCH_RANGE;
        private static final LoweringTargets.JavaCallTarget MATCH_NO_CASE;
        private static final LoweringTargets.JavaCallTarget STRING_HASH_CODE;
        private static final LoweringTargets.JavaCallTarget STRING_EQUALS;
        private static final LoweringTargets.JavaCallTarget ENUM_ORDINAL;
        private static final LoweringTargets.JavaCallTarget OPERATORS_EQUAL;
        private static final LoweringTargets.JavaCallTarget RANGE_VALIDATE_INT;
        private static final LoweringTargets.JavaCallTarget RANGE_VALIDATE_LONG;
        private static final LoweringTargets.JavaCallTarget MATH_FLOOR_MOD_INT;
        private static final LoweringTargets.JavaCallTarget MATH_FLOOR_MOD_LONG;
        private static final LoweringTargets.JavaCallTarget MEMBERS_SELECT;
        private static final LoweringTargets.JavaCallTarget LIST_SIZE;
        private static final LoweringTargets.JavaCallTarget LIST_GET;
        private static final LoweringTargets.JavaCallTarget LIST_SLICE;
        private static final LoweringTargets.JavaCallTarget ARR_IS_ARRAY;
        private static final LoweringTargets.JavaCallTarget ARR_SIZE;
        private static final LoweringTargets.JavaCallTarget ARR_GET;
        private static final LoweringTargets.JavaCallTarget ARR_SLICE;

        static {
            try {
                MATCH_IN_CONTAINER = new LoweringTargets.JavaCallTarget(
                        MatchSupport.class.getMethod("inContainer", Object.class, Object.class), true, false, false);
                MATCH_RELATIONAL = new LoweringTargets.JavaCallTarget(
                        MatchSupport.class.getMethod("relational", Object.class, Object.class, int.class), true, false, false);
                MATCH_RANGE = new LoweringTargets.JavaCallTarget(
                        MatchSupport.class.getMethod("rangeMatch", Object.class, Object.class, Object.class, boolean.class, Object.class), true, false, false);
                MATCH_NO_CASE = new LoweringTargets.JavaCallTarget(
                        MatchSupport.class.getMethod("noMatchingCase", Object.class), true, false, false);
                STRING_HASH_CODE = new LoweringTargets.JavaCallTarget(
                        String.class.getMethod("hashCode"), false, false, false);
                STRING_EQUALS = new LoweringTargets.JavaCallTarget(
                        String.class.getMethod("equals", Object.class), false, false, false);
                ENUM_ORDINAL = new LoweringTargets.JavaCallTarget(
                        Enum.class.getMethod("ordinal"), false, false, false);
                OPERATORS_EQUAL = new LoweringTargets.JavaCallTarget(
                        Operators.class.getMethod("equal", Object.class, Object.class), true, false, false);
                RANGE_VALIDATE_INT = new LoweringTargets.JavaCallTarget(
                        Range.class.getMethod("validateStep", int.class), true, false, false);
                RANGE_VALIDATE_LONG = new LoweringTargets.JavaCallTarget(
                        Range.class.getMethod("validateStep", long.class), true, false, false);
                MATH_FLOOR_MOD_INT = new LoweringTargets.JavaCallTarget(
                        Math.class.getMethod("floorMod", int.class, int.class), true, false, false);
                MATH_FLOOR_MOD_LONG = new LoweringTargets.JavaCallTarget(
                        Math.class.getMethod("floorMod", long.class, long.class), true, false, false);
                MEMBERS_SELECT = new LoweringTargets.JavaCallTarget(
                        org.babblelang.runtime.Members.class.getMethod("select", Object.class, String.class, String.class), true, false, false);
                LIST_SIZE = new LoweringTargets.JavaCallTarget(
                        java.util.List.class.getMethod("size"), false, false, false);
                LIST_GET = new LoweringTargets.JavaCallTarget(
                        java.util.List.class.getMethod("get", int.class), false, false, false);
                LIST_SLICE = new LoweringTargets.JavaCallTarget(
                        org.babblelang.runtime.CoreCollections.class.getMethod("listSlice", java.util.List.class, int.class), true, false, false);
                ARR_IS_ARRAY = new LoweringTargets.JavaCallTarget(
                        org.babblelang.runtime.ArraySupport.class.getMethod("isArray", Object.class), true, false, false);
                ARR_SIZE = new LoweringTargets.JavaCallTarget(
                        org.babblelang.runtime.ArraySupport.class.getMethod("size", Object.class), true, false, false);
                ARR_GET = new LoweringTargets.JavaCallTarget(
                        org.babblelang.runtime.ArraySupport.class.getMethod("get", Object.class, int.class), true, false, false);
                ARR_SLICE = new LoweringTargets.JavaCallTarget(
                        org.babblelang.runtime.ArraySupport.class.getMethod("slice", Object.class, int.class), true, false, false);
            } catch (NoSuchMethodException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private void scanPattern(HirPattern p, Set<Integer> visibleVars, Set<Integer> tryOuterVars) {
            if (p == null) return;
            switch (p) {
                case HirPattern.Const ignored -> {}
                case HirPattern.Wildcard ignored -> {}
                case HirPattern.TypeTest ignored -> {}
                case HirPattern.ValueExpr v -> scanExpr(v.expr(), visibleVars, tryOuterVars);
                case HirPattern.Bind b -> {
                    if (b.var() != null) {
                        registerVar(b.var());
                        if (mustAlwaysPin(b.var())) pin(b.var());
                    }
                    scanPattern(b.inner(), visibleVars, tryOuterVars);
                }
                case HirPattern.Or o -> {
                    for (HirPattern alt : o.alternatives()) {
                        scanPattern(alt, visibleVars, tryOuterVars);
                    }
                }
                case HirPattern.Relational r -> scanExpr(r.rhs(), visibleVars, tryOuterVars);
                case HirPattern.RangeTest r -> {
                    scanExpr(r.lo(), visibleVars, tryOuterVars);
                    scanExpr(r.hi(), visibleVars, tryOuterVars);
                    scanExpr(r.step(), visibleVars, tryOuterVars);
                }
                case HirPattern.Membership m -> scanExpr(m.container(), visibleVars, tryOuterVars);
                case HirPattern.RecordDestructure rd -> {
                    for (HirPattern fp : rd.fieldPatterns()) {
                        scanPattern(fp, visibleVars, tryOuterVars);
                    }
                }
                case HirPattern.SequenceDestructure sd -> {
                    for (HirPattern ep : sd.elemPatterns()) {
                        scanPattern(ep, visibleVars, tryOuterVars);
                    }
                    if (sd.restVar() != null) {
                        registerVar(sd.restVar());
                        if (mustAlwaysPin(sd.restVar())) pin(sd.restVar());
                    }
                }
            }
        }

        private MirVal lowerMatch(HirExpr.Match m) {
            int ln = m.meta().line();
            PrimitiveKind targetKind = m.meta().kind();
            PrimitiveKind scrutKind = m.scrutineeVar().primitiveKind();
            MirVal scrutVal = lowerAs(m.scrutinee(), scrutKind);
            if (cur.isTerminated()) return scrutVal;
            writeVar(m.scrutineeVar(), scrutVal, ln);

            MirVal mergeParam = newVal(targetKind, null);
            BlockBuilder bbMerge = newBlock(List.of(mergeParam), false, false);

            List<HirExpr.MatchArm> arms = m.arms();
            int i = 0;
            while (i < arms.size()) {
                int intRunEnd = scanMirIntSwitchRun(arms, i, scrutKind);
                if (intRunEnd > i) {
                    emitMirIntSwitchRun(arms, i, intRunEnd, scrutVal, targetKind, bbMerge, ln);
                    i = intRunEnd;
                    continue;
                }
                int strRunEnd = scanMirStringSwitchRun(arms, i, scrutKind);
                if (strRunEnd > i) {
                    emitMirStringSwitchRun(arms, i, strRunEnd, scrutVal, false, targetKind, bbMerge, ln);
                    i = strRunEnd;
                    continue;
                }
                int enumRunEnd = scanMirEnumSwitchRun(arms, i, scrutKind);
                if (enumRunEnd > i) {
                    emitMirEnumSwitchRun(arms, i, enumRunEnd, scrutVal, targetKind, bbMerge, ln);
                    i = enumRunEnd;
                    continue;
                }
                HirExpr.MatchArm arm = arms.get(i);
                BlockBuilder bbNext = newBlock(List.of(), false, false);
                lowerMatchPattern(arm.pattern(), scrutVal, bbNext);
                if (!cur.isTerminated() && arm.guard() != null) {
                    MirVal gVal = lowerExpr(arm.guard());
                    if (!cur.isTerminated()) {
                        BlockBuilder bbBody = newBlock(List.of(), false, false);
                        terminateBrIf(gVal, bbBody, List.of(), bbNext, List.of(), arm.guard().meta().line());
                        sealBlock(bbBody);
                        cur = bbBody;
                    }
                }
                if (!cur.isTerminated()) {
                    lowerMatchArmBody(arm.body(), arm.bodyWideningRank(), targetKind, bbMerge, arm.line());
                }
                sealBlock(bbNext);
                cur = bbNext;
                i++;
            }

            if (!cur.isTerminated()) {
                if (m.elseArm() != null) {
                    HirExpr.MatchElse elseArm = m.elseArm();
                    if (elseArm.var() != null) {
                        writeVar(elseArm.var(), scrutVal, elseArm.line());
                    }
                    lowerMatchArmBody(elseArm.body(), elseArm.bodyWideningRank(), targetKind, bbMerge, elseArm.line());
                } else {
                    MirVal boxed = convertVal(scrutVal, PrimitiveKind.NONE, ln);
                    MirVal exn = newVal(PrimitiveKind.NONE, null);
                    emit(new MirInst.CallJava(exn, null, "MatchSupport", ln, true, MATCH_NO_CASE,
                            List.of(boxed), false, false, ln, MirEffect.PURE_NOTHROW));
                    terminateFixed(new MirTerminator.Throw(exn, true, ln));
                }
            }

            sealBlock(bbMerge);
            cur = bbMerge;
            if (bbMerge.incomingEdges.isEmpty()) {
                terminateFixed(new MirTerminator.Unreachable(ln));
            }
            return mergeParam;
        }

        private void lowerMatchArmBody(HirExpr body, int wideningRank, PrimitiveKind targetKind,
                                       BlockBuilder bbMerge, int ln) {
            MirVal v;
            if (wideningRank >= 0) {
                v = lowerExpr(body);
                if (!cur.isTerminated()) {
                    v = applyWideningRank(v, wideningRank, ln);
                    v = convertVal(v, targetKind, ln);
                }
            } else {
                v = lowerAs(body, targetKind);
            }
            if (!cur.isTerminated()) {
                terminateBr(bbMerge, List.of(v), ln);
            }
        }

        private static List<HirPattern.Const> collectMirPureConsts(HirPattern p) {
            if (p instanceof HirPattern.Const c) return List.of(c);
            if (p instanceof HirPattern.Or o) {
                List<HirPattern.Const> out = new ArrayList<>();
                for (HirPattern alt : o.alternatives()) {
                    List<HirPattern.Const> sub = collectMirPureConsts(alt);
                    if (sub == null) return null;
                    out.addAll(sub);
                }
                return out;
            }
            return null;
        }

        private static int scanMirIntSwitchRun(List<HirExpr.MatchArm> arms, int start, PrimitiveKind scrutKind) {
            if (scrutKind != PrimitiveKind.BYTE && scrutKind != PrimitiveKind.SHORT
                    && scrutKind != PrimitiveKind.INT && scrutKind != PrimitiveKind.CHAR) {
                return start;
            }
            Set<Integer> keys = new HashSet<>();
            int end = start;
            while (end < arms.size()) {
                HirExpr.MatchArm arm = arms.get(end);
                if (arm.guard() != null || !arm.boundVars().isEmpty()) break;
                List<HirPattern.Const> cs = collectMirPureConsts(arm.pattern());
                if (cs == null || cs.isEmpty()) break;
                boolean ok = true;
                for (HirPattern.Const c : cs) {
                    Object v = c.value();
                    if (!(v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Character)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) break;
                for (HirPattern.Const c : cs) {
                    keys.add(c.value() instanceof Character ch ? (int) ch : ((Number) c.value()).intValue());
                }
                end++;
            }
            return keys.size() >= 3 ? end : start;
        }

        private void emitMirIntSwitchRun(List<HirExpr.MatchArm> arms, int start, int end,
                                         MirVal scrutVal, PrimitiveKind targetKind, BlockBuilder bbMerge, int ln) {
            int armCount = end - start;
            BlockBuilder[] armBlocks = new BlockBuilder[armCount];
            for (int k = 0; k < armCount; k++) {
                armBlocks[k] = newBlock(List.of(), false, false);
            }
            BlockBuilder bbAfter = newBlock(List.of(), false, false);
            java.util.TreeMap<Integer, BlockBuilder> keyMap = new java.util.TreeMap<>();
            for (int k = 0; k < armCount; k++) {
                for (HirPattern.Const c : collectMirPureConsts(arms.get(start + k).pattern())) {
                    int kv = c.value() instanceof Character ch ? (int) ch : ((Number) c.value()).intValue();
                    keyMap.putIfAbsent(kv, armBlocks[k]);
                }
            }
            int[] keys = new int[keyMap.size()];
            BlockBuilder[] targets = new BlockBuilder[keyMap.size()];
            int idx = 0;
            for (Map.Entry<Integer, BlockBuilder> e : keyMap.entrySet()) {
                keys[idx] = e.getKey();
                targets[idx] = e.getValue();
                idx++;
            }
            MirVal keyInt = convertVal(scrutVal, PrimitiveKind.INT, ln);
            terminateSwitch(keyInt, keys, targets, bbAfter, ln);
            for (int k = 0; k < armCount; k++) {
                sealBlock(armBlocks[k]);
                cur = armBlocks[k];
                HirExpr.MatchArm arm = arms.get(start + k);
                lowerMatchArmBody(arm.body(), arm.bodyWideningRank(), targetKind, bbMerge, arm.line());
            }
            sealBlock(bbAfter);
            cur = bbAfter;
        }

        private static int scanMirStringSwitchRun(List<HirExpr.MatchArm> arms, int start, PrimitiveKind scrutKind) {
            if (scrutKind != PrimitiveKind.NONE) return start;
            Set<String> strs = new HashSet<>();
            int end = start;
            while (end < arms.size()) {
                HirExpr.MatchArm arm = arms.get(end);
                if (arm.guard() != null || !arm.boundVars().isEmpty()) break;
                List<HirPattern.Const> cs = collectMirPureConsts(arm.pattern());
                if (cs == null || cs.isEmpty()) break;
                boolean ok = true;
                for (HirPattern.Const c : cs) {
                    if (!c.isNull() && !(c.value() instanceof String)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) break;
                for (HirPattern.Const c : cs) {
                    if (c.value() instanceof String s) {
                        strs.add(s);
                    }
                }
                end++;
            }
            return strs.size() >= 3 ? end : start;
        }

        private void emitMirStringSwitchRun(List<HirExpr.MatchArm> arms, int start, int end,
                                            MirVal scrutVal, boolean guaranteedString,
                                            PrimitiveKind targetKind, BlockBuilder bbMerge, int ln) {
            int armCount = end - start;
            BlockBuilder[] armBlocks = new BlockBuilder[armCount];
            for (int k = 0; k < armCount; k++) {
                armBlocks[k] = newBlock(List.of(), false, false);
            }
            BlockBuilder bbAfter = newBlock(List.of(), false, false);

            LinkedHashMap<String, Integer> strToArm = new LinkedHashMap<>();
            int nullArm = -1;
            for (int k = 0; k < armCount; k++) {
                for (HirPattern.Const c : collectMirPureConsts(arms.get(start + k).pattern())) {
                    if (c.isNull()) {
                        if (nullArm < 0) nullArm = k;
                    } else {
                        strToArm.putIfAbsent((String) c.value(), k);
                    }
                }
            }
            BlockBuilder bbNonNull = newBlock(List.of(), false, false);
            MirVal nullVal = emitConst(null, PrimitiveKind.NONE, ln);
            MirVal isNull = newVal(PrimitiveKind.BOOL, null);
            emit(new MirInst.Binary(isNull, HirExpr.BinaryOp.EQ, scrutVal, nullVal, "match", "null", false, ln, MirEffect.PURE_NOTHROW));
            terminateBrIf(isNull, nullArm >= 0 ? armBlocks[nullArm] : bbAfter, List.of(), bbNonNull, List.of(), ln);
            sealBlock(bbNonNull);
            cur = bbNonNull;

            if (!guaranteedString) {
                BlockBuilder bbIsStr = newBlock(List.of(), false, false);
                MirVal isStr = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.IsCheck(isStr, scrutVal, null, null, List.of(), "string|string|", ln));
                terminateBrIf(isStr, bbIsStr, List.of(), bbAfter, List.of(), ln);
                sealBlock(bbIsStr);
                cur = bbIsStr;
            }

            java.util.TreeMap<Integer, List<Map.Entry<String, Integer>>> buckets = new java.util.TreeMap<>();
            for (Map.Entry<String, Integer> e : strToArm.entrySet()) {
                buckets.computeIfAbsent(e.getKey().hashCode(), h -> new ArrayList<>()).add(e);
            }
            int bCount = buckets.size();
            int[] hashes = new int[bCount];
            BlockBuilder[] bucketBlocks = new BlockBuilder[bCount];
            List<List<Map.Entry<String, Integer>>> bucketEntries = new ArrayList<>(bCount);
            int idx = 0;
            for (Map.Entry<Integer, List<Map.Entry<String, Integer>>> b : buckets.entrySet()) {
                hashes[idx] = b.getKey();
                bucketBlocks[idx] = newBlock(List.of(), false, false);
                bucketEntries.add(b.getValue());
                idx++;
            }
            MirVal hashVal = newVal(PrimitiveKind.INT, null);
            emit(new MirInst.CallJava(hashVal, scrutVal, "match", ln, false, STRING_HASH_CODE,
                    List.of(), false, true, ln, MirEffect.PURE_NOTHROW));
            terminateSwitch(hashVal, hashes, bucketBlocks, bbAfter, ln);

            for (int b = 0; b < bCount; b++) {
                sealBlock(bucketBlocks[b]);
                cur = bucketBlocks[b];
                List<Map.Entry<String, Integer>> entries = bucketEntries.get(b);
                for (int j = 0; j < entries.size(); j++) {
                    Map.Entry<String, Integer> e = entries.get(j);
                    MirVal strConst = emitConst(e.getKey(), PrimitiveKind.NONE, ln);
                    MirVal eq = newVal(PrimitiveKind.BOOL, null);
                    emit(new MirInst.CallJava(eq, strConst, "pattern", ln, false, STRING_EQUALS,
                            List.of(scrutVal), false, true, ln, MirEffect.PURE_NOTHROW));
                    BlockBuilder bbFail = (j == entries.size() - 1) ? bbAfter : newBlock(List.of(), false, false);
                    terminateBrIf(eq, armBlocks[e.getValue()], List.of(), bbFail, List.of(), ln);
                    if (bbFail != bbAfter) {
                        sealBlock(bbFail);
                        cur = bbFail;
                    }
                }
            }

            for (int k = 0; k < armCount; k++) {
                sealBlock(armBlocks[k]);
                cur = armBlocks[k];
                HirExpr.MatchArm arm = arms.get(start + k);
                lowerMatchArmBody(arm.body(), arm.bodyWideningRank(), targetKind, bbMerge, arm.line());
            }
            sealBlock(bbAfter);
            cur = bbAfter;
        }

        private static int scanMirEnumSwitchRun(List<HirExpr.MatchArm> arms, int start, PrimitiveKind scrutKind) {
            if (scrutKind != PrimitiveKind.NONE) return start;
            Class<?> enumCls = null;
            Set<String> names = new HashSet<>();
            int end = start;
            while (end < arms.size()) {
                HirExpr.MatchArm arm = arms.get(end);
                if (arm.guard() != null || !arm.boundVars().isEmpty()) break;
                List<HirPattern.Const> cs = collectMirPureConsts(arm.pattern());
                if (cs == null || cs.isEmpty()) break;
                boolean ok = true;
                for (HirPattern.Const c : cs) {
                    if (c.isNull()) continue;
                    if (!c.isEnum() || !(c.value() instanceof Enum<?>)) {
                        ok = false;
                        break;
                    }
                    if (enumCls == null) {
                        enumCls = c.enumClass();
                    } else if (enumCls != c.enumClass()) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) break;
                for (HirPattern.Const c : cs) {
                    if (c.isEnum()) {
                        names.add(c.enumConstantName());
                    }
                }
                end++;
            }
            return (enumCls != null && names.size() >= 3) ? end : start;
        }

        private void emitMirEnumSwitchRun(List<HirExpr.MatchArm> arms, int start, int end,
                                          MirVal scrutVal, PrimitiveKind targetKind, BlockBuilder bbMerge, int ln) {
            int armCount = end - start;
            BlockBuilder[] armBlocks = new BlockBuilder[armCount];
            for (int k = 0; k < armCount; k++) {
                armBlocks[k] = newBlock(List.of(), false, false);
            }
            BlockBuilder bbAfter = newBlock(List.of(), false, false);

            Class<?> enumCls = null;
            java.util.TreeMap<Integer, BlockBuilder> ordMap = new java.util.TreeMap<>();
            int nullArm = -1;
            for (int k = 0; k < armCount; k++) {
                for (HirPattern.Const c : collectMirPureConsts(arms.get(start + k).pattern())) {
                    if (c.isNull()) {
                        if (nullArm < 0) nullArm = k;
                    } else if (c.isEnum() && c.value() instanceof Enum<?> ev) {
                        enumCls = c.enumClass();
                        ordMap.putIfAbsent(ev.ordinal(), armBlocks[k]);
                    }
                }
            }
            BlockBuilder bbNonNull = newBlock(List.of(), false, false);
            MirVal nullVal = emitConst(null, PrimitiveKind.NONE, ln);
            MirVal isNull = newVal(PrimitiveKind.BOOL, null);
            emit(new MirInst.Binary(isNull, HirExpr.BinaryOp.EQ, scrutVal, nullVal, "match", "null", false, ln, MirEffect.PURE_NOTHROW));
            terminateBrIf(isNull, nullArm >= 0 ? armBlocks[nullArm] : bbAfter, List.of(), bbNonNull, List.of(), ln);
            sealBlock(bbNonNull);
            cur = bbNonNull;

            BlockBuilder bbIsEnum = newBlock(List.of(), false, false);
            MirVal isEnum = newVal(PrimitiveKind.BOOL, null);
            String enumInternal = enumCls.getName().replace('.', '/');
            emit(new MirInst.IsCheck(isEnum, scrutVal, null, new LoweringTargets.IsTarget(null, null, enumInternal),
                    List.of(), "java|" + enumCls.getName() + "|", ln));
            terminateBrIf(isEnum, bbIsEnum, List.of(), bbAfter, List.of(), ln);
            sealBlock(bbIsEnum);
            cur = bbIsEnum;

            int[] keys = new int[ordMap.size()];
            BlockBuilder[] targets = new BlockBuilder[ordMap.size()];
            int idx = 0;
            for (Map.Entry<Integer, BlockBuilder> e : ordMap.entrySet()) {
                keys[idx] = e.getKey();
                targets[idx] = e.getValue();
                idx++;
            }
            MirVal ordVal = newVal(PrimitiveKind.INT, null);
            emit(new MirInst.CallJava(ordVal, scrutVal, "match", ln, false, ENUM_ORDINAL,
                    List.of(), false, true, ln, MirEffect.PURE_NOTHROW));
            terminateSwitch(ordVal, keys, targets, bbAfter, ln);

            for (int k = 0; k < armCount; k++) {
                sealBlock(armBlocks[k]);
                cur = armBlocks[k];
                HirExpr.MatchArm arm = arms.get(start + k);
                lowerMatchArmBody(arm.body(), arm.bodyWideningRank(), targetKind, bbMerge, arm.line());
            }
            sealBlock(bbAfter);
            cur = bbAfter;
        }

        private void lowerMatchPattern(HirPattern p, MirVal val, BlockBuilder bbFail) {
            if (cur.isTerminated()) return;
            int ln = p.line();
            switch (p) {
                case HirPattern.Wildcard ignored -> {
                }
                case HirPattern.Const pc -> {
                    MirVal cond = emitMirConstMatch(pc, val, ln);
                    branchOnPatternCond(cond, bbFail, ln);
                }
                case HirPattern.ValueExpr ve -> {
                    MirVal rhs = lowerExpr(ve.expr());
                    if (cur.isTerminated()) return;
                    MirVal cond = emitMirEquality(val, rhs, false, ln);
                    branchOnPatternCond(cond, bbFail, ln);
                }
                case HirPattern.TypeTest tt -> {
                    MirVal boxed = convertVal(val, PrimitiveKind.NONE, ln);
                    PrimitiveKind outKind = tt.builtin() != null ? PrimitiveKind.NONE : PrimitiveKind.BOOL;
                    MirVal res = newVal(outKind, null);
                    emit(new MirInst.IsCheck(res, boxed, tt.builtin(), tt.target(), tt.intersectionTargets(), tt.descriptor(), ln));
                    branchOnPatternCond(res, bbFail, ln);
                }
                case HirPattern.Bind b -> {
                    if (b.inner() != null) {
                        lowerMatchPattern(b.inner(), val, bbFail);
                        if (cur.isTerminated()) return;
                    }
                    MirVal bound = convertVal(val, b.var().primitiveKind(), ln);
                    writeVar(b.var(), bound, ln);
                }
                case HirPattern.Or o -> {
                    List<HirPattern> alts = o.alternatives();
                    BlockBuilder bbMatched = newBlock(List.of(), false, false);
                    for (int k = 0; k < alts.size(); k++) {
                        BlockBuilder altFail = (k == alts.size() - 1) ? bbFail : newBlock(List.of(), false, false);
                        lowerMatchPattern(alts.get(k), val, altFail);
                        if (!cur.isTerminated()) {
                            terminateBr(bbMatched, List.of(), ln);
                        }
                        if (altFail != bbFail) {
                            sealBlock(altFail);
                            cur = altFail;
                        }
                    }
                    sealBlock(bbMatched);
                    cur = bbMatched;
                }
                case HirPattern.Relational rel -> {
                    MirVal rhs = lowerExpr(rel.rhs());
                    if (cur.isTerminated()) return;
                    MirVal cond = emitMirRelational(val, rhs, rel.op(), ln);
                    branchOnPatternCond(cond, bbFail, ln);
                }
                case HirPattern.RangeTest rt -> {
                    lowerMirRangePattern(rt, val, bbFail);
                }
                case HirPattern.Membership mp -> {
                    MirVal container = lowerBoxed(mp.container());
                    if (cur.isTerminated()) return;
                    MirVal boxed = convertVal(val, PrimitiveKind.NONE, ln);
                    MirVal res = newVal(PrimitiveKind.BOOL, null);
                    emit(new MirInst.CallJava(res, null, "MatchSupport", ln, true, MATCH_IN_CONTAINER,
                            List.of(boxed, container), false, false, ln, MirEffect.IMPURE_THROW));
                    branchOnPatternCond(res, bbFail, ln);
                }
                case HirPattern.RecordDestructure rd -> lowerMirRecordDestructure(rd, val, bbFail);
                case HirPattern.SequenceDestructure sd -> lowerMirSequenceDestructure(sd, val, bbFail);
            }
        }

        private void lowerMirRecordDestructure(HirPattern.RecordDestructure rd, MirVal val, BlockBuilder bbFail) {
            int ln = rd.line();
            lowerMatchPattern(rd.typeTest(), val, bbFail);
            if (cur.isTerminated()) return;
            MirVal boxed = convertVal(val, PrimitiveKind.NONE, ln);
            if (!rd.isJavaRecord()) {
                boolean direct = rd.babbleClassId() >= 0 || rd.babbleJvmName() != null;
                for (int i = 0; i < rd.fieldPatterns().size(); i++) {
                    String fn = rd.fieldNames().get(i);
                    PrimitiveKind fk = rd.fieldKinds().get(i);
                    MirVal fv;
                    if (direct) {
                        fv = newVal(fk, fn);
                        String extJvm = rd.babbleClassId() >= 0 ? null : rd.babbleJvmName();
                        emit(new MirInst.LoadField(fv, boxed, "match", rd.babbleClassId(), extJvm,
                                rd.babbleMemberIndices()[i], fn, fk, true, true, ln, MirEffect.READ_FROZEN));
                    } else {
                        MirVal fnVal = emitConst(fn, PrimitiveKind.NONE, ln);
                        fv = newVal(PrimitiveKind.NONE, fn);
                        emit(new MirInst.CallJava(fv, null, "Members", ln, true, MEMBERS_SELECT,
                                List.of(boxed, fnVal, fnVal), false, false, ln, MirEffect.READ_SHARED));
                    }
                    lowerMatchPattern(rd.fieldPatterns().get(i), fv, bbFail);
                    if (cur.isTerminated()) return;
                }
                return;
            }
            for (int i = 0; i < rd.fieldPatterns().size(); i++) {
                java.lang.reflect.Method acc = rd.javaAccessors().get(i);
                PrimitiveKind fk = rd.fieldKinds().get(i);
                LoweringTargets.JavaCallTarget accTarget = new LoweringTargets.JavaCallTarget(acc, false, false, false);
                MirVal fv = newVal(fk, rd.fieldNames().get(i));
                emit(new MirInst.CallJava(fv, boxed, rd.javaRecordClass().getSimpleName(), ln, false,
                        accTarget, List.of(), false, false, ln, MirEffect.IMPURE_THROW));
                lowerMatchPattern(rd.fieldPatterns().get(i), fv, bbFail);
                if (cur.isTerminated()) return;
            }
        }

        private void lowerMirSequenceDestructure(HirPattern.SequenceDestructure sd, MirVal val, BlockBuilder bbFail) {
            int ln = sd.line();
            if (sd.typeTest() != null) {
                lowerMatchPattern(sd.typeTest(), val, bbFail);
                if (cur.isTerminated()) return;
            } else if (sd.isArray()) {
                MirVal boxed = convertVal(val, PrimitiveKind.NONE, ln);
                MirVal isArr = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.CallJava(isArr, null, "ArraySupport", ln, true, ARR_IS_ARRAY,
                        List.of(boxed), false, true, ln, MirEffect.PURE_NOTHROW));
                branchOnPatternCond(isArr, bbFail, ln);
            }
            MirVal boxed = convertVal(val, PrimitiveKind.NONE, ln);
            int fixed = sd.elemPatterns().size();
            MirVal fixedConst = emitConst(fixed, PrimitiveKind.INT, ln);
            if (!sd.isArray()) {
                MirVal len = newVal(PrimitiveKind.INT, "$len");
                emit(new MirInst.CallJava(len, boxed, "list", ln, false, LIST_SIZE,
                        List.of(), false, false, ln, MirEffect.READ_SHARED));
                MirVal lenOk = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Binary(lenOk, sd.hasRest() ? HirExpr.BinaryOp.GTE : HirExpr.BinaryOp.EQ,
                        len, fixedConst, "len", "fixed", false, ln, MirEffect.PURE_NOTHROW));
                branchOnPatternCond(lenOk, bbFail, ln);

                for (int i = 0; i < fixed; i++) {
                    MirVal idx = emitConst(i, PrimitiveKind.INT, ln);
                    MirVal elem = newVal(PrimitiveKind.NONE, null);
                    emit(new MirInst.CallJava(elem, boxed, "list", ln, false, LIST_GET,
                            List.of(idx), false, false, ln, MirEffect.READ_SHARED));
                    lowerMatchPattern(sd.elemPatterns().get(i), elem, bbFail);
                    if (cur.isTerminated()) return;
                }
                if (sd.restVar() != null) {
                    MirVal tail = newVal(PrimitiveKind.NONE, sd.restVar().name());
                    emit(new MirInst.CallJava(tail, null, "CoreCollections", ln, true, LIST_SLICE,
                            List.of(boxed, fixedConst), false, false, ln, MirEffect.READ_SHARED));
                    writeVar(sd.restVar(), tail, ln);
                }
                return;
            }
            Class<?> arrCls = sd.arrayClass();
            PrimitiveKind ek = sd.elemKind();
            MirVal len = newVal(PrimitiveKind.INT, "$len");
            if (arrCls != null) {
                emit(new MirInst.ArrayLen(len, boxed, "array", ek, arrCls, arrCls.getComponentType(), ln, MirEffect.PURE_NOTHROW));
            } else {
                emit(new MirInst.CallJava(len, null, "ArraySupport", ln, true, ARR_SIZE,
                        List.of(boxed), false, false, ln, MirEffect.PURE_NOTHROW));
            }
            MirVal lenOk = newVal(PrimitiveKind.BOOL, null);
            emit(new MirInst.Binary(lenOk, sd.hasRest() ? HirExpr.BinaryOp.GTE : HirExpr.BinaryOp.EQ,
                    len, fixedConst, "len", "fixed", false, ln, MirEffect.PURE_NOTHROW));
            branchOnPatternCond(lenOk, bbFail, ln);

            for (int i = 0; i < fixed; i++) {
                MirVal idx = emitConst(i, PrimitiveKind.INT, ln);
                MirVal elem;
                if (arrCls != null) {
                    elem = newVal(ek, null);
                    emit(new MirInst.ArrayGet(elem, boxed, idx, "array", ek, arrCls, arrCls.getComponentType(), ln, MirEffect.READ_SHARED));
                } else {
                    elem = newVal(PrimitiveKind.NONE, null);
                    emit(new MirInst.CallJava(elem, null, "ArraySupport", ln, true, ARR_GET,
                            List.of(boxed, idx), false, false, ln, MirEffect.READ_SHARED));
                }
                lowerMatchPattern(sd.elemPatterns().get(i), elem, bbFail);
                if (cur.isTerminated()) return;
            }
            if (sd.restVar() != null) {
                MirVal tail = newVal(PrimitiveKind.NONE, sd.restVar().name());
                emit(new MirInst.CallJava(tail, null, "ArraySupport", ln, true, ARR_SLICE,
                        List.of(boxed, fixedConst), false, false, ln, MirEffect.READ_SHARED));
                writeVar(sd.restVar(), tail, ln);
            }
        }

        private void branchOnPatternCond(MirVal cond, BlockBuilder bbFail, int ln) {
            BlockBuilder bbPass = newBlock(List.of(), false, false);
            terminateBrIf(cond, bbPass, List.of(), bbFail, List.of(), ln);
            sealBlock(bbPass);
            cur = bbPass;
        }

        private MirVal emitMirConstMatch(HirPattern.Const pc, MirVal val, int ln) {
            if (pc.isNull()) {
                if (val.kind().isPrimitive()) {
                    return emitConst(Boolean.FALSE, PrimitiveKind.BOOL, ln);
                }
                MirVal nullVal = emitConst(null, PrimitiveKind.NONE, ln);
                MirVal res = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Binary(res, HirExpr.BinaryOp.EQ, val, nullVal, "match", "null", false, ln, MirEffect.PURE_NOTHROW));
                return res;
            }
            if (pc.isEnum()) {
                try {
                    java.lang.reflect.Field field = pc.enumClass().getField(pc.enumConstantName());
                    LoweringTargets.JavaFieldTarget ft = new LoweringTargets.JavaFieldTarget(field, true, false);
                    MirVal enumVal = newVal(PrimitiveKind.NONE, pc.enumConstantName());
                    emit(new MirInst.LoadJavaField(enumVal, null, pc.enumClass().getName(), true, ft, ln,
                            MirEffect.read(MirEffect.AliasClass.FROZEN, true, true, true)));
                    return emitMirEquality(val, enumVal, false, ln);
                } catch (NoSuchFieldException e) {
                    return emitConst(Boolean.FALSE, PrimitiveKind.BOOL, ln);
                }
            }
            MirVal constVal = emitConst(pc.value(), pc.kind(), ln);
            return emitMirEquality(val, constVal, false, ln);
        }

        private MirVal emitMirEquality(MirVal left, MirVal right, boolean invert, int ln) {
            PrimitiveKind lk = left.kind();
            PrimitiveKind rk = right.kind();
            HirExpr.BinaryOp op = invert ? HirExpr.BinaryOp.NEQ : HirExpr.BinaryOp.EQ;
            if ((lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL)
                    || (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR)) {
                MirVal res = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Binary(res, op, left, right, "match", "pattern", false, ln, MirEffect.PURE_NOTHROW));
                return res;
            }
            if (lk.numericRank() >= 0 && rk.numericRank() >= 0) {
                PrimitiveKind common = promoteMirNumeric(lk, rk);
                MirVal cl = convertVal(left, common, ln);
                MirVal cr = convertVal(right, common, ln);
                MirVal res = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Binary(res, op, cl, cr, "match", "pattern", false, ln, MirEffect.PURE_NOTHROW));
                return res;
            }
            MirVal bl = convertVal(left, PrimitiveKind.NONE, ln);
            MirVal br = convertVal(right, PrimitiveKind.NONE, ln);
            MirVal eq = newVal(PrimitiveKind.BOOL, null);
            emit(new MirInst.CallJava(eq, null, "Operators", ln, true, OPERATORS_EQUAL,
                    List.of(bl, br), true, true, ln, MirEffect.PURE_NOTHROW));
            if (invert) {
                MirVal neg = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Unary(neg, HirExpr.UnaryOp.NOT, eq, "pattern", ln, MirEffect.PURE_NOTHROW));
                return neg;
            }
            return eq;
        }

        private MirVal emitMirRelational(MirVal left, MirVal right, HirExpr.BinaryOp op, int ln) {
            if (op == HirExpr.BinaryOp.EQ || op == HirExpr.BinaryOp.NEQ) {
                return emitMirEquality(left, right, op == HirExpr.BinaryOp.NEQ, ln);
            }
            PrimitiveKind lk = left.kind();
            PrimitiveKind rk = right.kind();
            if (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR) {
                MirVal res = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Binary(res, op, left, right, "match", "pattern", false, ln, MirEffect.PURE_NOTHROW));
                return res;
            }
            if (lk.numericRank() >= 0 && rk.numericRank() >= 0) {
                PrimitiveKind common = promoteMirNumeric(lk, rk);
                MirVal cl = convertVal(left, common, ln);
                MirVal cr = convertVal(right, common, ln);
                MirVal res = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Binary(res, op, cl, cr, "match", "pattern", false, ln, MirEffect.PURE_NOTHROW));
                return res;
            }
            int opCode = switch (op) {
                case LT -> MatchSupport.OP_LT;
                case LTE -> MatchSupport.OP_LTE;
                case GT -> MatchSupport.OP_GT;
                case GTE -> MatchSupport.OP_GTE;
                default -> throw new IllegalStateException();
            };
            MirVal bl = convertVal(left, PrimitiveKind.NONE, ln);
            MirVal br = convertVal(right, PrimitiveKind.NONE, ln);
            MirVal opVal = emitConst(opCode, PrimitiveKind.INT, ln);
            MirVal res = newVal(PrimitiveKind.BOOL, null);
            emit(new MirInst.CallJava(res, null, "MatchSupport", ln, true, MATCH_RELATIONAL,
                    List.of(bl, br, opVal), false, true, ln, MirEffect.IMPURE_THROW));
            return res;
        }

        private void lowerMirRangePattern(HirPattern.RangeTest rt, MirVal val, BlockBuilder bbFail) {
            int ln = rt.line();
            PrimitiveKind vk = val.kind();
            PrimitiveKind ek = rt.elemKind();
            if (vk.isPrimitive() && ek.isPrimitive() && ek != PrimitiveKind.BOOL) {
                PrimitiveKind cmpKind = ek == PrimitiveKind.CHAR ? PrimitiveKind.CHAR : promoteMirNumeric(vk, ek);
                MirVal lo = lowerAs(rt.lo(), cmpKind);
                if (cur.isTerminated()) return;
                MirVal hi = lowerAs(rt.hi(), cmpKind);
                if (cur.isTerminated()) return;
                MirVal stepVal = null;
                PrimitiveKind stepKind = cmpKind == PrimitiveKind.LONG ? PrimitiveKind.LONG : PrimitiveKind.INT;
                if (rt.step() != null) {
                    MirVal rawStep = lowerAs(rt.step(), stepKind);
                    if (cur.isTerminated()) return;
                    stepVal = newVal(stepKind, null);
                    emit(new MirInst.CallJava(stepVal, null, "Range", ln, true,
                            stepKind == PrimitiveKind.LONG ? RANGE_VALIDATE_LONG : RANGE_VALIDATE_INT,
                            List.of(rawStep), false, true, ln, MirEffect.PURE_MAY_THROW));
                }
                MirVal v = convertVal(val, cmpKind, ln);
                MirVal geLo = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Binary(geLo, HirExpr.BinaryOp.GTE, v, lo, "match", "lo", false, ln, MirEffect.PURE_NOTHROW));
                branchOnPatternCond(geLo, bbFail, ln);

                MirVal leHi = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Binary(leHi, rt.inclusive() ? HirExpr.BinaryOp.LTE : HirExpr.BinaryOp.LT,
                        v, hi, "match", "hi", false, ln, MirEffect.PURE_NOTHROW));
                branchOnPatternCond(leHi, bbFail, ln);

                if (stepVal != null) {
                    MirVal diff = newVal(stepKind, null);
                    MirVal vs = convertVal(v, stepKind, ln);
                    MirVal ls = convertVal(lo, stepKind, ln);
                    emit(new MirInst.Binary(diff, HirExpr.BinaryOp.SUB, vs, ls, "match", "lo", false, ln, MirEffect.PURE_NOTHROW));
                    MirVal rem = newVal(stepKind, null);
                    emit(new MirInst.CallJava(rem, null, "Math", ln, true,
                            stepKind == PrimitiveKind.LONG ? MATH_FLOOR_MOD_LONG : MATH_FLOOR_MOD_INT,
                            List.of(diff, stepVal), false, true, ln, MirEffect.PURE_MAY_THROW));
                    MirVal zero = emitConst(stepKind == PrimitiveKind.LONG ? 0L : 0, stepKind, ln);
                    MirVal onStep = newVal(PrimitiveKind.BOOL, null);
                    emit(new MirInst.Binary(onStep, HirExpr.BinaryOp.EQ, rem, zero, "mod", "0", false, ln, MirEffect.PURE_NOTHROW));
                    branchOnPatternCond(onStep, bbFail, ln);
                }
                return;
            }
            MirVal boxed = convertVal(val, PrimitiveKind.NONE, ln);
            MirVal lo = lowerBoxed(rt.lo());
            if (cur.isTerminated()) return;
            MirVal hi = lowerBoxed(rt.hi());
            if (cur.isTerminated()) return;
            MirVal inc = emitConst(rt.inclusive(), PrimitiveKind.BOOL, ln);
            MirVal step = rt.step() != null ? lowerBoxed(rt.step()) : emitConst(null, PrimitiveKind.NONE, ln);
            if (cur.isTerminated()) return;
            MirVal res = newVal(PrimitiveKind.BOOL, null);
            emit(new MirInst.CallJava(res, null, "MatchSupport", ln, true, MATCH_RANGE,
                    List.of(boxed, lo, hi, inc, step), false, true, ln, MirEffect.IMPURE_THROW));
            branchOnPatternCond(res, bbFail, ln);
        }

        private static PrimitiveKind promoteMirNumeric(PrimitiveKind a, PrimitiveKind b) {
            if (a == PrimitiveKind.DOUBLE || b == PrimitiveKind.DOUBLE) return PrimitiveKind.DOUBLE;
            if (a == PrimitiveKind.FLOAT || b == PrimitiveKind.FLOAT) return PrimitiveKind.FLOAT;
            if (a == PrimitiveKind.LONG || b == PrimitiveKind.LONG) return PrimitiveKind.LONG;
            return PrimitiveKind.INT;
        }

        private MirVal lowerWhile(HirExpr.While w) {
            int ln = w.meta().line();
            MirVal initRes = emitConst(null, PrimitiveKind.NONE, ln);
            MirVal headRes = newVal(PrimitiveKind.NONE, "$loopRes");
            MirVal exitRes = newVal(PrimitiveKind.NONE, "$loopOut");

            BlockBuilder bbHead = newBlock(List.of(headRes), false, false);
            BlockBuilder bbBody = newBlock(List.of(), false, false);
            BlockBuilder bbExit = newBlock(List.of(exitRes), false, false);

            terminateBr(bbHead, List.of(initRes), ln);

            cur = bbHead;
            MirVal cond = lowerExpr(w.cond());
            if (!cur.isTerminated()) {
                terminateBrIf(cond, bbBody, List.of(), bbExit, List.of(headRes), ln);
            }
            sealBlock(bbBody);

            cur = bbBody;
            loopStack.push(new LoopContext(bbExit, bbHead, true, headRes, loopStack.size() + 1));
            MirVal bodyVal;
            try {
                bodyVal = lowerBoxed(w.body());
            } finally {
                loopStack.pop();
            }
            if (!cur.isTerminated()) {
                terminateBr(bbHead, List.of(bodyVal), ln);
            }

            sealBlock(bbHead);
            sealBlock(bbExit);
            cur = bbExit;
            if (bbExit.incomingEdges.isEmpty()) {
                terminateFixed(new MirTerminator.Unreachable(ln));
            }
            return exitRes;
        }

        private MirVal lowerFor(HirExpr.For f) {
            int ln = f.meta().line();
            LoweringTargets.ForLoopTarget target = f.target();
            LoweringTargets.ForKind kind = target != null ? target.kind() : LoweringTargets.ForKind.DYNAMIC_UNION;
            if (kind == LoweringTargets.ForKind.COUNTED_RANGE && f.countedRange() != null) {
                return lowerCountedRangeFor(f, ln);
            }
            boolean isArray = kind == LoweringTargets.ForKind.ARRAY && target.arrayClass() != null;

            MirVal iterableVal = lowerBoxed(f.iterable());
            if (cur.isTerminated()) return iterableVal;

            MirVal iterState = newVal(PrimitiveKind.NONE, "$iter");
            LoweringTargets.ForLoopTarget effectiveTarget = target != null
                    ? target
                    : new LoweringTargets.ForLoopTarget(LoweringTargets.ForKind.DYNAMIC_UNION, null,
                    PrimitiveKind.NONE, null, 0, null, null, null, null, null);
            emit(new MirInst.ForIterInit(iterState, iterableVal, effectiveTarget, ln, MirEffect.IMPURE_THROW));

            MirVal initRes = emitConst(null, PrimitiveKind.NONE, ln);
            MirVal exitRes = newVal(PrimitiveKind.NONE, "$forOut");
            BlockBuilder bbExit = newBlock(List.of(exitRes), false, false);

            if (isArray) {
                MirVal arrLen = newVal(PrimitiveKind.INT, "$len");
                emit(new MirInst.ArrayLen(arrLen, iterState, null,
                        javaPrimitiveKind(target.arrayClass().getComponentType()),
                        target.arrayClass(), target.arrayClass().getComponentType(), ln, MirEffect.READ_FROZEN));
                MirVal zero = emitConst(0, PrimitiveKind.INT, ln);

                MirVal headRes = newVal(PrimitiveKind.NONE, "$forRes");
                MirVal headIdx = newVal(PrimitiveKind.INT, "$idx");
                BlockBuilder bbHead = newBlock(List.of(headRes, headIdx), false, false);
                BlockBuilder bbBody = newBlock(List.of(), false, false);
                MirVal stepRes = newVal(PrimitiveKind.NONE, "$stepRes");
                BlockBuilder bbStep = newBlock(List.of(stepRes), false, false);

                terminateBr(bbHead, List.of(initRes, zero), ln);

                cur = bbHead;
                MirVal hasNext = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.ForIterHasNext(hasNext, iterState, headIdx, arrLen, effectiveTarget, ln, MirEffect.PURE_NOTHROW));
                terminateBrIf(hasNext, bbBody, List.of(), bbExit, List.of(headRes), ln);
                sealBlock(bbBody);

                cur = bbBody;
                PrimitiveKind rawElemKind = javaPrimitiveKind(target.arrayClass().getComponentType());
                MirVal rawElem = newVal(rawElemKind, "$elem");
                emit(new MirInst.ForIterNext(rawElem, iterState, headIdx, effectiveTarget, f.elemSamTarget(), ln,
                        MirEffect.read(aliasOf(iterableVal, f.iterable().meta()), false, true, true)));

                MirVal bodyRes = lowerForBody(f, rawElem, bbExit, bbStep, headRes, ln);
                if (!cur.isTerminated()) {
                    terminateBr(bbStep, List.of(bodyRes), ln);
                }
                sealBlock(bbStep);

                cur = bbStep;
                if (bbStep.incomingEdges.isEmpty()) {
                    terminateFixed(new MirTerminator.Unreachable(ln));
                } else {
                    MirVal one = emitConst(1, PrimitiveKind.INT, ln);
                    MirVal nextIdx = newVal(PrimitiveKind.INT, null);
                    emit(new MirInst.Binary(nextIdx, HirExpr.BinaryOp.ADD, headIdx, one, null, null, false, ln, MirEffect.PURE_NOTHROW));
                    terminateBr(bbHead, List.of(stepRes, nextIdx), ln);
                }
                sealBlock(bbHead);
            } else {
                MirVal headRes = newVal(PrimitiveKind.NONE, "$forRes");
                BlockBuilder bbHead = newBlock(List.of(headRes), false, false);
                BlockBuilder bbBody = newBlock(List.of(), false, false);

                terminateBr(bbHead, List.of(initRes), ln);

                cur = bbHead;
                MirVal hasNext = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.ForIterHasNext(hasNext, iterState, null, null, effectiveTarget, ln, MirEffect.IMPURE_THROW));
                terminateBrIf(hasNext, bbBody, List.of(), bbExit, List.of(headRes), ln);
                sealBlock(bbBody);

                cur = bbBody;
                PrimitiveKind nextOutKind = (kind == LoweringTargets.ForKind.BABBLE_ITERABLE
                        || kind == LoweringTargets.ForKind.BABBLE_ITERATOR)
                        && target != null && target.nextSig() != null
                        ? target.nextSig().returnKind()
                        : PrimitiveKind.NONE;
                MirVal rawElem = newVal(nextOutKind, "$elem");
                emit(new MirInst.ForIterNext(rawElem, iterState, null, effectiveTarget, f.elemSamTarget(), ln, MirEffect.IMPURE_THROW));

                MirVal bodyRes = lowerForBody(f, rawElem, bbExit, bbHead, headRes, ln);
                if (!cur.isTerminated()) {
                    terminateBr(bbHead, List.of(bodyRes), ln);
                }
                sealBlock(bbHead);
            }

            sealBlock(bbExit);
            cur = bbExit;
            if (bbExit.incomingEdges.isEmpty()) {
                terminateFixed(new MirTerminator.Unreachable(ln));
            }
            return exitRes;
        }

        private MirVal lowerCountedRangeFor(HirExpr.For f, int ln) {
            HirExpr.CountedRangeLoop cr = f.countedRange();
            PrimitiveKind rk = cr.elemKind();
            boolean isLong = rk == PrimitiveKind.LONG;
            PrimitiveKind loopKind = isLong ? PrimitiveKind.LONG : PrimitiveKind.INT;

            MirVal startRaw = lowerAs(cr.start(), rk);
            if (cur.isTerminated()) return startRaw;
            MirVal startVal = convertVal(startRaw, loopKind, ln);
            MirVal endRaw = lowerAs(cr.end(), rk);
            if (cur.isTerminated()) return endRaw;
            MirVal endVal = convertVal(endRaw, loopKind, ln);

            boolean hasConstStep = false;
            long constStep = 1L;
            MirVal stepVal = null;
            if (cr.step() == null) {
                hasConstStep = true;
                constStep = 1L;
            } else if (cr.step() instanceof HirExpr.Const sc && sc.value() instanceof Number sn && sn.longValue() > 0L) {
                hasConstStep = true;
                constStep = isLong ? sn.longValue() : sn.intValue();
            } else {
                MirVal rawStep = lowerAs(cr.step(), loopKind);
                if (cur.isTerminated()) return rawStep;
                stepVal = newVal(loopKind, "$step");
                emit(new MirInst.CallJava(stepVal, null, null, ln, true,
                        isLong ? RANGE_VALIDATE_LONG_STEP : RANGE_VALIDATE_INT_STEP,
                        List.of(rawStep), false, false, ln, MirEffect.PURE_MAY_THROW));
            }

            MirVal initRes = emitConst(null, PrimitiveKind.NONE, ln);
            MirVal exitRes = newVal(PrimitiveKind.NONE, "$forOut");
            BlockBuilder bbExit = newBlock(List.of(exitRes), false, false);

            MirVal isNonEmpty = newVal(PrimitiveKind.BOOL, null);
            HirExpr.BinaryOp cmpOp = cr.inclusive() ? HirExpr.BinaryOp.LTE : HirExpr.BinaryOp.LT;
            emit(new MirInst.Binary(isNonEmpty, cmpOp, startVal, endVal, null, null, false, ln, MirEffect.PURE_NOTHROW));
            BlockBuilder bbNonEmpty = newBlock(List.of(), false, false);
            terminateBrIf(isNonEmpty, bbNonEmpty, List.of(), bbExit, List.of(initRes), ln);
            sealBlock(bbNonEmpty);

            cur = bbNonEmpty;
            MirVal lastVal;
            if (hasConstStep && constStep == 1L) {
                if (cr.inclusive()) {
                    lastVal = endVal;
                } else {
                    MirVal one = emitConst(isLong ? (Object) 1L : Integer.valueOf(1), loopKind, ln);
                    lastVal = newVal(loopKind, "$last");
                    emit(new MirInst.Binary(lastVal, HirExpr.BinaryOp.SUB, endVal, one, null, null, false, ln, MirEffect.PURE_NOTHROW));
                }
            } else {
                MirVal stepArg = stepVal != null ? stepVal : emitConst(isLong ? (Object) constStep : Integer.valueOf((int) constStep), loopKind, ln);
                MirVal inclVal = emitConst(cr.inclusive(), PrimitiveKind.BOOL, ln);
                if (isLong) {
                    lastVal = newVal(PrimitiveKind.LONG, "$last");
                    emit(new MirInst.CallJava(lastVal, null, null, ln, true, RANGE_LAST_LONG,
                            List.of(startVal, endVal, inclVal, stepArg), false, false, ln, MirEffect.PURE_NOTHROW));
                } else if (rk == PrimitiveKind.CHAR) {
                    MirVal sChar = convertVal(startVal, PrimitiveKind.CHAR, ln);
                    MirVal eChar = convertVal(endVal, PrimitiveKind.CHAR, ln);
                    MirVal lastChar = newVal(PrimitiveKind.CHAR, "$last");
                    emit(new MirInst.CallJava(lastChar, null, null, ln, true, RANGE_LAST_CHAR,
                            List.of(sChar, eChar, inclVal, stepArg), false, false, ln, MirEffect.PURE_NOTHROW));
                    lastVal = convertVal(lastChar, PrimitiveKind.INT, ln);
                } else {
                    lastVal = newVal(PrimitiveKind.INT, "$last");
                    emit(new MirInst.CallJava(lastVal, null, null, ln, true, RANGE_LAST_INT,
                            List.of(startVal, endVal, inclVal, stepArg), false, false, ln, MirEffect.PURE_NOTHROW));
                }
            }

            MirVal firstIdx = cr.descending() ? lastVal : startVal;
            MirVal stopVal = cr.descending() ? startVal : lastVal;

            MirVal headRes = newVal(PrimitiveKind.NONE, "$forRes");
            MirVal headIdx = newVal(loopKind, "$idx");
            BlockBuilder bbHead = newBlock(List.of(headRes, headIdx), false, false);
            MirVal stepRes = newVal(PrimitiveKind.NONE, "$stepRes");
            BlockBuilder bbStep = newBlock(List.of(stepRes), false, false);
            BlockBuilder bbNext = newBlock(List.of(), false, false);

            terminateBr(bbHead, List.of(initRes, firstIdx), ln);

            cur = bbHead;
            MirVal rawElem = rk == loopKind ? headIdx : convertVal(headIdx, rk, ln);
            MirVal bodyRes = lowerForBody(f, rawElem, bbExit, bbStep, headRes, ln);
            if (!cur.isTerminated()) {
                terminateBr(bbStep, List.of(bodyRes), ln);
            }
            sealBlock(bbStep);

            cur = bbStep;
            if (bbStep.incomingEdges.isEmpty()) {
                terminateFixed(new MirTerminator.Unreachable(ln));
            } else {
                MirVal done = newVal(PrimitiveKind.BOOL, null);
                emit(new MirInst.Binary(done, HirExpr.BinaryOp.EQ, headIdx, stopVal, null, null, false, ln, MirEffect.PURE_NOTHROW));
                terminateBrIf(done, bbExit, List.of(stepRes), bbNext, List.of(), ln);
                sealBlock(bbNext);

                cur = bbNext;
                MirVal delta = stepVal != null ? stepVal : emitConst(isLong ? (Object) constStep : Integer.valueOf((int) constStep), loopKind, ln);
                MirVal nextIdx = newVal(loopKind, null);
                emit(new MirInst.Binary(nextIdx, cr.descending() ? HirExpr.BinaryOp.SUB : HirExpr.BinaryOp.ADD,
                        headIdx, delta, null, null, false, ln, MirEffect.PURE_NOTHROW));
                terminateBr(bbHead, List.of(stepRes, nextIdx), ln);
            }
            sealBlock(bbHead);

            sealBlock(bbExit);
            cur = bbExit;
            if (bbExit.incomingEdges.isEmpty()) {
                terminateFixed(new MirTerminator.Unreachable(ln));
            }
            return exitRes;
        }

        private MirVal lowerForBody(HirExpr.For f, MirVal rawElem, BlockBuilder breakBlock,
                                    BlockBuilder continueBlock, MirVal currentRes, int ln) {
            LoweringTargets.ForLoopTarget target = f.target();
            MirVal elem = rawElem;
            if (target != null && target.elemCheck() != null) {
                elem = applyCheckedType(elem, target.elemCheck(), ln);
            }
            if (f.elemSamTarget() != null) {
                MirVal boxed = convertVal(elem, PrimitiveKind.NONE, ln);
                MirVal adapted = newVal(PrimitiveKind.NONE, null);
                emit(new MirInst.CheckCast(adapted, boxed, null, f.elemSamTarget(), null, ln));
                elem = adapted;
            }
            elem = applyWideningRank(elem, f.meta().wideningRank(), ln);
            PrimitiveKind varKind = target != null ? target.elemKind() : PrimitiveKind.NONE;
            elem = convertVal(elem, varKind, ln);

            for (HirVar v : f.bodyDeclaredVars()) {
                if (pinnedVarIds.contains(v.id())) {
                    emit(new MirInst.SlotDecl(v, ln));
                }
            }
            if (f.loopVar() != null) {
                defVar(f.loopVar(), elem, ln);
            }

            loopStack.push(new LoopContext(breakBlock, continueBlock, true, currentRes, loopStack.size() + 1));
            MirVal res;
            try {
                res = lowerStatements(f.body(), ln);
                if (!cur.isTerminated()) {
                    res = applyWideningRank(res, f.bodyWideningRank(), ln);
                    res = convertVal(res, PrimitiveKind.NONE, ln);
                }
            } finally {
                loopStack.pop();
            }
            return res;
        }

        private MirVal lowerBreak(HirExpr.Break b) {
            int ln = b.meta().line();
            LoopContext loop = loopStack.peek();
            MirVal val;
            if (b.value() != null) {
                val = lowerAndWiden(b.value());
                if (cur.isTerminated()) return val;
                val = convertVal(val, PrimitiveKind.NONE, ln);
            } else {
                val = emitConst(null, PrimitiveKind.NONE, ln);
            }
            emitUnwindFinallies(loop.loopDepth, ln);
            if (!cur.isTerminated()) {
                terminateBr(loop.breakBlock, List.of(val), ln);
            }
            return emitConst(null, PrimitiveKind.NONE, ln);
        }

        private MirVal lowerContinue(HirExpr.Continue c) {
            int ln = c.meta().line();
            LoopContext loop = loopStack.peek();
            emitUnwindFinallies(loop.loopDepth, ln);
            if (!cur.isTerminated()) {
                if (loop.passResultOnContinue) {
                    terminateBr(loop.continueBlock, List.of(loop.currentResultVal), ln);
                } else {
                    terminateBr(loop.continueBlock, List.of(), ln);
                }
            }
            return emitConst(null, PrimitiveKind.NONE, ln);
        }

        private MirVal lowerReturn(HirExpr.Return r) {
            int ln = r.meta().line();
            MirVal val = lowerAndWiden(r.value());
            if (cur.isTerminated()) return val;
            val = applyWideningRank(val, r.meta().wideningRank(), ln);
            val = convertVal(val, fn.returnKind(), ln);
            emitUnwindFinallies(0, ln);
            if (!cur.isTerminated()) {
                terminateFixed(new MirTerminator.Ret(val, fn.returnKind(), -1, ln));
            }
            return emitConst(null, PrimitiveKind.NONE, ln);
        }

        private MirVal lowerThrow(HirExpr.Throw th) {
            int ln = th.meta().line();
            MirVal val = lowerBoxed(th.value());
            if (cur.isTerminated()) return val;
            terminateFixed(new MirTerminator.Throw(val, th.isJavaThrowable(), ln));
            return emitConst(null, PrimitiveKind.NONE, ln);
        }

        // ------------------------------------------------------------------ try / catch / finally (BIP028)

        private void emitUnwindFinallies(int minLoopDepth, int ln) {
            if (finallyStack.isEmpty()) {
                return;
            }
            List<FinallyContext> popped = new ArrayList<>();
            List<List<MirHandler>> poppedHandlers = new ArrayList<>();
            while (!finallyStack.isEmpty() && finallyStack.peek().loopDepthAtEntry >= minLoopDepth) {
                FinallyContext fc = finallyStack.pop();
                popped.add(fc);
                poppedHandlers.add(handlerStack.pop());
                if (fc.hasNonEmptyFinally && !cur.isTerminated()) {
                    // Transition to a fresh block with the outer handler list so this inline finally
                    // is NOT protected by the try's own catch/finally handlers!
                    BlockBuilder bbFin = newBlock(List.of(), false, false);
                    terminateBr(bbFin, List.of(), ln);
                    sealBlock(bbFin);
                    cur = bbFin;
                    lowerTryFinallyBlock(fc.tryNode, ln);
                }
            }
            // Restore stacks for any subsequent unreachable/sibling lowering in the same lexical scope.
            for (int i = popped.size() - 1; i >= 0; i--) {
                handlerStack.push(poppedHandlers.get(i));
                finallyStack.push(popped.get(i));
            }
        }

        private void lowerScopedBody(List<HirExpr> stmts, List<HirVar> declVars,
                                     boolean opensNs, String[] declNames, int ln) {
            if (!declVars.isEmpty()) {
                for (HirVar v : declVars) {
                    if (pinnedVarIds.contains(v.id())) {
                        emit(new MirInst.SlotDecl(v, ln));
                    }
                }
                lowerStatements(stmts, ln);
            } else if (opensNs) {
                MirVal prevNs = newVal(PrimitiveKind.NONE, "$prevNs");
                emit(new MirInst.ScopeEnter(prevNs, declNames, ln));
                lowerStatements(stmts, ln);
                if (!cur.isTerminated()) {
                    emit(new MirInst.ScopeExit(prevNs, ln));
                }
            } else {
                lowerStatements(stmts, ln);
            }
        }

        private MirVal lowerScopedValueBody(List<HirExpr> stmts, List<HirVar> declVars,
                                            boolean opensNs, String[] declNames, int ln) {
            if (!declVars.isEmpty()) {
                for (HirVar v : declVars) {
                    if (pinnedVarIds.contains(v.id())) {
                        emit(new MirInst.SlotDecl(v, ln));
                    }
                }
                return lowerStatements(stmts, ln);
            }
            if (opensNs) {
                MirVal prevNs = newVal(PrimitiveKind.NONE, "$prevNs");
                emit(new MirInst.ScopeEnter(prevNs, declNames, ln));
                MirVal res = lowerStatements(stmts, ln);
                if (!cur.isTerminated()) {
                    emit(new MirInst.ScopeExit(prevNs, ln));
                }
                return res;
            }
            return lowerStatements(stmts, ln);
        }

        private void lowerTryFinallyBlock(HirExpr.Try t, int ln) {
            if (!t.hasFinally() || t.finallyBody().isEmpty()) {
                return;
            }
            lowerScopedBody(t.finallyBody(), t.finallyDeclaredVars(), t.finallyOpensNamespaceScope(),
                    t.finallyDeclaredNames(), ln);
        }

        private MirVal lowerTry(HirExpr.Try t) {
            int ln = t.meta().line();
            PrimitiveKind targetKind = t.meta().kind();
            boolean hasNonEmptyFinally = t.hasFinally() && !t.finallyBody().isEmpty();
            List<HirExpr.HirCatch> catches = t.catches();
            List<MirHandler> outerHandlers = handlerStack.peek();
            BlockBuilder preTryBlock = cur;

            // 1. Pre-create merge block, handler-entry blocks, catch body blocks, and abnormal finally blocks.
            MirVal mergeParam = newVal(targetKind, "$tryRes");
            BlockBuilder bbMerge = newBlockWithHandlers(List.of(mergeParam), outerHandlers, false, false);

            boolean needsThrownDispatcher = false;
            for (HirExpr.HirCatch cc : catches) {
                LoweringTargets.CatchTarget ct = cc.target();
                if (ct != null && (ct.catchesBabble() || ct.catchesNonErrorAny()
                        || ct.catchesJavaThrowable() || ct.skipsThrownException())) {
                    needsThrownDispatcher = true;
                    break;
                }
            }

            // Abnormal finally blocks (run under outerHandlers).
            MirVal finAbnEntryExn = hasNonEmptyFinally ? newVal(PrimitiveKind.NONE, "$abnExn") : null;
            BlockBuilder bbFinallyAbnEntry = hasNonEmptyFinally
                    ? newBlockWithHandlers(List.of(finAbnEntryExn), outerHandlers, true, preTryBlock, true) : null;
            MirVal finAbnBodyExn = hasNonEmptyFinally ? newVal(PrimitiveKind.NONE, "$primaryExn") : null;
            BlockBuilder bbFinallyAbnBody = hasNonEmptyFinally
                    ? newBlockWithHandlers(List.of(finAbnBodyExn), outerHandlers, false, false) : null;

            // Handlers protecting catch bodies (finallyAbnormal -> outerHandlers).
            List<MirHandler> catchBodyHandlers = outerHandlers;
            if (hasNonEmptyFinally) {
                List<MirHandler> list = new ArrayList<>(1 + outerHandlers.size());
                list.add(new MirHandler(null, bbFinallyAbnEntry.id, ln));
                list.addAll(outerHandlers);
                catchBodyHandlers = List.copyOf(list);
            }

            // Per-catch body blocks and JVM catch trampoline blocks.
            BlockBuilder[] catchBodies = new BlockBuilder[catches.size()];
            MirVal[] catchBoundParams = new MirVal[catches.size()];
            BlockBuilder[] jvmCatchEntries = new BlockBuilder[catches.size()];
            MirVal[] jvmCatchParams = new MirVal[catches.size()];
            for (int i = 0; i < catches.size(); i++) {
                HirExpr.HirCatch cc = catches.get(i);
                MirVal bp = newVal(PrimitiveKind.NONE, cc.var() != null ? cc.var().name() : "$caught");
                catchBoundParams[i] = bp;
                catchBodies[i] = newBlockWithHandlers(List.of(bp), catchBodyHandlers, false, false);
                LoweringTargets.CatchTarget ct = cc.target();
                if (ct != null && !ct.jvmCatchTypes().isEmpty()) {
                    MirVal ep = newVal(PrimitiveKind.NONE, "$jexn");
                    jvmCatchParams[i] = ep;
                    jvmCatchEntries[i] = newBlockWithHandlers(List.of(ep), outerHandlers, true, preTryBlock, true);
                }
            }

            // ThrownException dispatcher handler-entry block (runs under outerHandlers).
            MirVal teParam = needsThrownDispatcher ? newVal(PrimitiveKind.NONE, "$thrownEx") : null;
            BlockBuilder bbThrownEntry = needsThrownDispatcher
                    ? newBlockWithHandlers(List.of(teParam), outerHandlers, true, preTryBlock, true) : null;

            // Build tryBody handler list in exact JVM exception_table priority order:
            //   1. ThrownException dispatcher (if needed)
            //   2. Java catch handlers in source order
            //   3. Abnormal finally catch-all (if hasNonEmptyFinally)
            //   4. Outer enclosing handlers
            List<MirHandler> tryHandlers = new ArrayList<>();
            if (needsThrownDispatcher) {
                tryHandlers.add(new MirHandler(THROWN_EXCEPTION_JVM, bbThrownEntry.id, ln));
            }
            for (int i = 0; i < catches.size(); i++) {
                LoweringTargets.CatchTarget ct = catches.get(i).target();
                if (ct != null && jvmCatchEntries[i] != null) {
                    for (String jvmType : ct.jvmCatchTypes()) {
                        tryHandlers.add(new MirHandler(jvmType, jvmCatchEntries[i].id, catches.get(i).line()));
                    }
                }
            }
            if (hasNonEmptyFinally) {
                tryHandlers.add(new MirHandler(null, bbFinallyAbnEntry.id, ln));
            }
            tryHandlers.addAll(outerHandlers);
            List<MirHandler> tryHandlerList = List.copyOf(tryHandlers);

            // 2. Branch from current block into tryStart block (which carries tryHandlerList).
            BlockBuilder bbTryStart = newBlockWithHandlers(List.of(), tryHandlerList, false, false);
            terminateBr(bbTryStart, List.of(), ln);
            sealBlock(bbTryStart);

            // Lower tryBody inside tryHandlerList + finallyStack.
            handlerStack.push(tryHandlerList);
            finallyStack.push(new FinallyContext(t, loopStack.size(), hasNonEmptyFinally));
            MirVal tryVal;
            try {
                cur = bbTryStart;
                tryVal = lowerScopedValueBody(t.tryBody(), t.tryDeclaredVars(), t.tryOpensNamespaceScope(),
                        t.tryDeclaredNames(), ln);
                if (!cur.isTerminated()) {
                    tryVal = applyWideningRank(tryVal, t.tryWideningRank(), ln);
                    tryVal = convertVal(tryVal, targetKind, ln);
                }
            } finally {
                finallyStack.pop();
                handlerStack.pop();
            }
            if (!cur.isTerminated()) {
                if (hasNonEmptyFinally) {
                    // Normal exit from try runs finally outside tryHandlerList (under outerHandlers).
                    BlockBuilder bbTryNormalFin = newBlockWithHandlers(List.of(), outerHandlers, false, false);
                    terminateBr(bbTryNormalFin, List.of(), ln);
                    sealBlock(bbTryNormalFin);
                    cur = bbTryNormalFin;
                    lowerTryFinallyBlock(t, ln);
                }
                if (!cur.isTerminated()) {
                    terminateBr(bbMerge, List.of(tryVal), ln);
                }
            }

            // 3. Emit JVM catch trampolines -> catchBodies[i].
            for (int i = 0; i < catches.size(); i++) {
                if (jvmCatchEntries[i] != null) {
                    cur = jvmCatchEntries[i];
                    terminateBr(catchBodies[i], List.of(jvmCatchParams[i]), catches.get(i).line());
                }
            }

            // 4. Emit ThrownException dispatcher -> catchBodies[i] or finallyAbnormalBody / rethrow.
            if (needsThrownDispatcher) {
                cur = bbThrownEntry;
                MirVal unwrapped = null;
                boolean guaranteedHandled = false;
                for (int i = 0; i < catches.size(); i++) {
                    LoweringTargets.CatchTarget ct = catches.get(i).target();
                    int cln = catches.get(i).line();
                    if (ct == null) {
                        continue;
                    }
                    if (ct.catchesJavaThrowable()) {
                        terminateBr(catchBodies[i], List.of(teParam), cln);
                        guaranteedHandled = true;
                        break;
                    }
                    if (ct.catchesNonErrorAny()) {
                        if (unwrapped == null) {
                            unwrapped = newVal(PrimitiveKind.NONE, "$thrownVal");
                            emit(new MirInst.UnwrapThrown(unwrapped, teParam, cln));
                        }
                        terminateBr(catchBodies[i], List.of(unwrapped), cln);
                        guaranteedHandled = true;
                        break;
                    }
                    if (ct.catchesBabble()) {
                        if (unwrapped == null) {
                            unwrapped = newVal(PrimitiveKind.NONE, "$thrownVal");
                            emit(new MirInst.UnwrapThrown(unwrapped, teParam, cln));
                        }
                        MirVal matched = newVal(PrimitiveKind.BOOL, null);
                        emit(new MirInst.CatchMatch(matched, unwrapped, ct, cln));
                        BlockBuilder bbNext = newBlockWithHandlers(List.of(), outerHandlers, false, false);
                        terminateBrIf(matched, catchBodies[i], List.of(unwrapped), bbNext, List.of(), cln);
                        sealBlock(bbNext);
                        cur = bbNext;
                    }
                }
                if (!guaranteedHandled && !cur.isTerminated()) {
                    if (hasNonEmptyFinally) {
                        terminateBr(bbFinallyAbnBody, List.of(teParam), ln);
                    } else {
                        terminateFixed(new MirTerminator.Throw(teParam, true, ln));
                    }
                }
            }

            // 5. Lower catch bodies (each protected by catchBodyHandlers).
            for (int i = 0; i < catches.size(); i++) {
                HirExpr.HirCatch cc = catches.get(i);
                int cln = cc.line();
                sealBlock(catchBodies[i]);
                cur = catchBodies[i];
                if (catchBodies[i].incomingEdges.isEmpty()) {
                    terminateFixed(new MirTerminator.Unreachable(cln));
                    continue;
                }
                if (hasNonEmptyFinally) {
                    handlerStack.push(catchBodyHandlers);
                    finallyStack.push(new FinallyContext(t, loopStack.size(), true));
                }
                MirVal catchVal;
                try {
                    if (!cc.declaredVars().isEmpty()) {
                        for (HirVar v : cc.declaredVars()) {
                            if (pinnedVarIds.contains(v.id())) {
                                emit(new MirInst.SlotDecl(v, cln));
                            }
                        }
                        if (cc.var() != null) {
                            defVar(cc.var(), catchBoundParams[i], cln);
                        }
                        catchVal = lowerStatements(cc.body(), cln);
                    } else if (cc.opensNamespaceScope()) {
                        MirVal prevNs = newVal(PrimitiveKind.NONE, "$prevNs");
                        emit(new MirInst.ScopeEnter(prevNs, cc.declaredNames(), cln));
                        catchVal = lowerStatements(cc.body(), cln);
                        if (!cur.isTerminated()) {
                            emit(new MirInst.ScopeExit(prevNs, cln));
                        }
                    } else {
                        if (cc.var() != null) {
                            defVar(cc.var(), catchBoundParams[i], cln);
                        }
                        catchVal = lowerStatements(cc.body(), cln);
                    }
                    if (!cur.isTerminated()) {
                        catchVal = applyWideningRank(catchVal, cc.wideningRank(), cln);
                        catchVal = convertVal(catchVal, targetKind, cln);
                    }
                } finally {
                    if (hasNonEmptyFinally) {
                        finallyStack.pop();
                        handlerStack.pop();
                    }
                }
                if (!cur.isTerminated()) {
                    if (hasNonEmptyFinally) {
                        BlockBuilder bbCatchFin = newBlockWithHandlers(List.of(), outerHandlers, false, false);
                        terminateBr(bbCatchFin, List.of(), cln);
                        sealBlock(bbCatchFin);
                        cur = bbCatchFin;
                        lowerTryFinallyBlock(t, cln);
                    }
                    if (!cur.isTerminated()) {
                        terminateBr(bbMerge, List.of(catchVal), cln);
                    }
                }
            }

            // 6. Emit abnormal finally path (with automatic AddSuppressed when !finallyNothrow).
            if (hasNonEmptyFinally) {
                cur = bbFinallyAbnEntry;
                terminateBr(bbFinallyAbnBody, List.of(finAbnEntryExn), ln);
                sealBlock(bbFinallyAbnBody);
                cur = bbFinallyAbnBody;
                if (t.finallyNothrow()) {
                    lowerTryFinallyBlock(t, ln);
                    if (!cur.isTerminated()) {
                        terminateFixed(new MirTerminator.Throw(finAbnBodyExn, true, ln));
                    }
                } else {
                    MirVal secondaryExn = newVal(PrimitiveKind.NONE, "$secondaryExn");
                    BlockBuilder bbSuppress = newBlockWithHandlers(List.of(secondaryExn), outerHandlers, true, bbFinallyAbnBody, true);
                    BlockBuilder bbRethrow = newBlockWithHandlers(List.of(), outerHandlers, false, false);

                    List<MirHandler> finHandlers = new ArrayList<>(1 + outerHandlers.size());
                    finHandlers.add(new MirHandler("java/lang/Throwable", bbSuppress.id, ln));
                    finHandlers.addAll(outerHandlers);
                    BlockBuilder bbFinRun = newBlockWithHandlers(List.of(), finHandlers, false, false);
                    terminateBr(bbFinRun, List.of(), ln);
                    sealBlock(bbFinRun);

                    handlerStack.push(List.copyOf(finHandlers));
                    try {
                        cur = bbFinRun;
                        lowerTryFinallyBlock(t, ln);
                        if (!cur.isTerminated()) {
                            terminateBr(bbRethrow, List.of(), ln);
                        }
                    } finally {
                        handlerStack.pop();
                    }

                    cur = bbSuppress;
                    emit(new MirInst.AddSuppressed(finAbnBodyExn, secondaryExn, ln));
                    terminateBr(bbRethrow, List.of(), ln);

                    sealBlock(bbRethrow);
                    cur = bbRethrow;
                    terminateFixed(new MirTerminator.Throw(finAbnBodyExn, true, ln));
                }
            }

            // 7. Continue after try merge.
            sealBlock(bbMerge);
            cur = bbMerge;
            if (bbMerge.incomingEdges.isEmpty()) {
                terminateFixed(new MirTerminator.Unreachable(ln));
            }
            return mergeParam;
        }

        // ------------------------------------------------------------------ calls & records

        private MirVal lowerSelfTailCall(HirExpr.SelfTailCall c) {
            int ln = c.meta().line();
            List<MirVal> args = new ArrayList<>(c.args().size());
            List<String> argNames = new ArrayList<>(c.args().size());
            for (HirCallArg a : c.args()) {
                args.add(lowerAndWiden(a.value()));
                if (cur.isTerminated()) return args.get(args.size() - 1);
                argNames.add(a.name());
            }
            terminateFixed(new MirTerminator.TailCall(c.functionId(), args, argNames, c.binding(), ln));
            return emitConst(null, PrimitiveKind.NONE, ln);
        }

        private MirVal lowerSelfRecurseCall(HirExpr.SelfRecurseCall c) {
            int ln = c.meta().line();
            HirFunction target = module.function(c.functionId());
            List<MirVal> args = lowerCallExprList(c.args(), target != null ? paramKinds(target) : null, c.binding());
            if (cur.isTerminated()) return emitConst(null, PrimitiveKind.NONE, ln);
            PrimitiveKind retKind = (target != null && target.hasSpec()) ? target.returnKind() : PrimitiveKind.NONE;
            MirEffect fx = callEffect(c.meta(), target);
            boolean comm = c.meta().isCommutative() || (target != null && target.effects().has(Effect.COMMUTATIVE));
            boolean idem = c.meta().isIdempotent() || (target != null && target.effects().has(Effect.IDEMPOTENT));
            MirVal res = newVal(retKind, null);
            emit(new MirInst.CallBabble(res, null, true, c.functionId(), args, c.binding(), comm, idem, ln, fx));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerModuleExportGet(HirExpr.ModuleExportGet g) {
            int ln = g.meta().line();
            MirVal mod = lowerBoxed(g.receiver());
            if (cur.isTerminated()) return mod;
            MirEffect fx = (g.target().definitelyInitialized() && g.meta().checkedType() == null)
                    ? MirEffect.PURE_NOTHROW
                    : new MirEffect(MirEffect.Kind.READ, MirEffect.AliasClass.SHARED, false, true, true);
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(new MirInst.LoadExport(res, mod, g.target(), ln, fx));
            return applyCheckedType(res, g.meta().checkedType(), ln);
        }

        private MirVal lowerDirectBabbleCall(HirExpr.DirectBabbleCall c) {
            int ln = c.meta().line();
            LoweringTargets.ExportedFunctionTarget ext = c.externalTarget();
            HirFunction target = ext == null ? module.function(c.targetFunctionId()) : null;
            MirVal callee = lowerBoxed(c.callee());
            if (cur.isTerminated()) return callee;
            PrimitiveKind[] pKinds = ext != null ? ext.signature().parameterKinds()
                    : (target != null ? paramKinds(target) : null);
            List<MirVal> args = lowerCallExprList(c.args(), pKinds, c.binding());
            if (cur.isTerminated()) return emitConst(null, PrimitiveKind.NONE, ln);
            PrimitiveKind retKind;
            MirEffect fx;
            boolean comm;
            boolean idem;
            if (ext != null) {
                retKind = ext.signature().hasSpec() ? ext.signature().returnKind() : PrimitiveKind.NONE;
                fx = ext.hasMutParam() ? MirEffect.IMPURE_THROW : callEffect(c.meta(), ext.effects());
                comm = c.meta().isCommutative() || ext.effects().has(Effect.COMMUTATIVE);
                idem = c.meta().isIdempotent() || ext.effects().has(Effect.IDEMPOTENT);
            } else {
                retKind = (target != null && target.hasSpec()) ? target.returnKind() : PrimitiveKind.NONE;
                fx = callEffect(c.meta(), target);
                comm = c.meta().isCommutative() || (target != null && target.effects().has(Effect.COMMUTATIVE));
                idem = c.meta().isIdempotent() || (target != null && target.effects().has(Effect.IDEMPOTENT));
            }
            MirVal res = newVal(retKind, null);
            emit(new MirInst.CallBabble(res, callee, false, c.targetFunctionId(), ext, args, c.binding(), comm, idem, ln, fx));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerDirectThisMethodCall(HirExpr.DirectThisMethodCall c) {
            int ln = c.meta().line();
            MirVal recv = lowerBoxed(c.receiver());
            if (cur.isTerminated()) return recv;
            List<MirVal> args = new ArrayList<>(c.args().size());
            List<String> argNames = new ArrayList<>(c.args().size());
            for (HirCallArg a : c.args()) {
                args.add(lowerAndWiden(a.value()));
                if (cur.isTerminated()) return args.get(args.size() - 1);
                argNames.add(a.name());
            }
            HirFunction target = c.targetFunctionId() >= 0 ? module.function(c.targetFunctionId()) : null;
            PrimitiveKind retKind = PrimitiveKind.NONE;
            if (target != null && target.hasSpec()) {
                retKind = target.returnKind();
            } else if (c.externalSignature() != null) {
                retKind = c.externalSignature().returnKind();
            } else if (c.meta().kind().isPrimitive()) {
                retKind = c.meta().kind();
            }
            MirEffect fx = callEffect(c.meta(), target);
            boolean comm = c.meta().isCommutative() || (target != null && target.effects().has(Effect.COMMUTATIVE));
            boolean idem = c.meta().isIdempotent() || (target != null && target.effects().has(Effect.IDEMPOTENT));
            MirVal res = newVal(retKind, null);
            emit(new MirInst.CallMethod(res, recv, c.receiverText(), c.classId(), c.externalClassJvmName(),
                    c.classTypeId(), c.memberName(), c.overloadAltIndex(), c.targetFunctionId(),
                    c.externalSignature(), args, argNames, c.binding(), c.callSite(), comm, idem, ln, fx));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerInterfaceCall(HirExpr.InterfaceCall c) {
            int ln = c.meta().line();
            MirVal recv = lowerBoxed(c.receiver());
            if (cur.isTerminated()) return recv;
            List<MirVal> args = new ArrayList<>(c.args().size());
            for (HirExpr a : c.args()) {
                args.add(lowerAndWiden(a));
                if (cur.isTerminated()) return args.get(args.size() - 1);
            }
            PrimitiveKind retKind = c.signature() != null ? c.signature().returnKind() : PrimitiveKind.NONE;
            MirEffect fx = callEffect(c.meta(), EffectSet.EMPTY);
            MirVal res = newVal(retKind, null);
            emit(new MirInst.CallInterface(res, recv, c.receiverText(), c.selectorLine(), c.interfaceTypeId(),
                    c.memberName(), c.signature(), args, c.binding(), ln, fx));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerJavaMethodCall(HirExpr.JavaMethodCall c) {
            int ln = c.meta().line();
            LoweringTargets.JavaCallTarget target = c.target();
            MirVal recv = null;
            if (!target.isStatic() || !c.pureStaticReceiver()) {
                recv = lowerBoxed(c.receiver());
                if (cur.isTerminated()) return recv;
            }
            Method m = target.method();
            Class<?>[] pTypes = m.getParameterTypes();
            List<MirVal> args = new ArrayList<>(c.args().size());
            for (int i = 0; i < c.args().size(); i++) {
                HirExpr argExpr = c.args().get(i);
                PrimitiveKind desired = (i < pTypes.length) ? javaArgDesiredKind(pTypes[i]) : PrimitiveKind.NONE;
                if (desired == PrimitiveKind.INT || desired == PrimitiveKind.BOOL || desired == PrimitiveKind.DOUBLE) {
                    args.add(lowerAs(argExpr, desired));
                } else {
                    args.add(lowerAndWiden(argExpr));
                }
                if (cur.isTerminated()) return args.get(args.size() - 1);
            }
            PrimitiveKind retKind = javaPrimitiveKind(m.getReturnType());
            boolean foldable = BuiltinAxioms.current().isFoldable(m);
            boolean idempotent = BuiltinAxioms.current().isIdempotent(m) || c.meta().isIdempotent();
            boolean commutative = c.meta().isCommutative();
            MirEffect fx;
            if (foldable && target.isStatic() && c.pureStaticReceiver()) {
                fx = MirEffect.PURE_NOTHROW;
            } else {
                fx = callEffect(c.meta(), EffectSet.EMPTY);
                if (!target.isStatic() && fx.nothrow()
                        && !(c.receiver() instanceof HirExpr.Const rc && rc.value() != null)) {
                    fx = new MirEffect(fx.kind(), fx.alias(), false, fx.terminating(), fx.deterministic());
                }
            }
            MirVal res = newVal(retKind, null);
            emit(new MirInst.CallJava(res, recv, c.receiverText(), c.selectorLine(), c.pureStaticReceiver(),
                    target, args, commutative, idempotent, ln, fx));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerJavaConstructorCall(HirExpr.JavaConstructorCall c) {
            int ln = c.meta().line();
            MirVal callee = null;
            if (!c.pureStaticCallee()) {
                callee = lowerBoxed(c.callee());
                if (cur.isTerminated()) return callee;
            }
            List<MirVal> args = new ArrayList<>(c.args().size());
            for (HirExpr a : c.args()) {
                args.add(lowerAndWiden(a));
                if (cur.isTerminated()) return args.get(args.size() - 1);
            }
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(new MirInst.CallJavaCtor(res, callee, c.calleeText(), c.pureStaticCallee(), c.target(), args, ln,
                    callEffect(c.meta(), EffectSet.EMPTY)));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerSafeCall(HirExpr.SafeCall c) {
            // SafeCall evaluates args ONLY when receiver != null: lower as a CFG diamond!
            int ln = c.meta().line();
            MirVal recv = lowerBoxed(c.receiver());
            if (cur.isTerminated()) return recv;
            MirVal nullConst = emitConst(null, PrimitiveKind.NONE, ln);
            MirVal isNonNull = newVal(PrimitiveKind.BOOL, null);
            emit(new MirInst.Binary(isNonNull, HirExpr.BinaryOp.NEQ, recv, nullConst,
                    c.receiverText(), "null", false, ln, MirEffect.PURE_NOTHROW));

            BlockBuilder bbNonNull = newBlock(List.of(), false, false);
            MirVal mergeParam = newVal(PrimitiveKind.NONE, null);
            BlockBuilder bbMerge = newBlock(List.of(mergeParam), false, false);
            terminateBrIf(isNonNull, bbNonNull, List.of(), bbMerge, List.of(nullConst), ln);
            sealBlock(bbNonNull);

            cur = bbNonNull;
            MirVal narrowed = newVal(PrimitiveKind.NONE, null);
            emit(new MirInst.Narrow(narrowed, MirInst.NarrowKind.NON_NULL, recv, "non-null", ln));
            List<MirVal> ops = new ArrayList<>(1 + c.args().size());
            ops.add(narrowed);
            for (HirCallArg a : c.args()) {
                ops.add(lowerAndWiden(a.value()));
                if (cur.isTerminated()) break;
            }
            if (!cur.isTerminated()) {
                MirVal callRes = newVal(PrimitiveKind.NONE, null);
                emit(new MirInst.OpaqueExpr(callRes, c, ops, ln, MirEffect.IMPURE_THROW));
                terminateBr(bbMerge, List.of(callRes), ln);
            }
            sealBlock(bbMerge);
            cur = bbMerge;
            return mergeParam;
        }

        private MirVal lowerRecordNew(HirExpr.RecordNew r) {
            int ln = r.meta().line();
            MirVal classRef = lowerBoxed(r.classRef());
            if (cur.isTerminated()) return classRef;
            List<MirVal> args = new ArrayList<>(r.args().size());
            for (HirExpr a : r.args()) {
                args.add(lowerAndWiden(a));
                if (cur.isTerminated()) return args.get(args.size() - 1);
            }
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(new MirInst.RecordNew(res, r.classId(), r.classTypeId(), r.classJvmName(), classRef,
                    r.classRefIsInstance(), r.paramKinds(), args, r.binding(), ln, MirEffect.PURE_NOTHROW));
            if (r.meta().frozen() || r.meta().deeplyImmutable()) {
                valAliases.put(res.id(), MirEffect.AliasClass.FROZEN);
            }
            return applyCheckedType(res, r.meta().checkedType(), ln);
        }

        private MirVal lowerRecordReuse(HirExpr.RecordReuse r) {
            int ln = r.meta().line();
            MirVal target = lowerBoxed(r.target());
            if (cur.isTerminated()) return target;
            List<MirVal> args = new ArrayList<>(r.args().size());
            for (HirExpr a : r.args()) {
                args.add(lowerAndWiden(a));
                if (cur.isTerminated()) return args.get(args.size() - 1);
            }
            MirVal res = newVal(PrimitiveKind.NONE, null);
            MirEffect.AliasClass alias = aliasOf(target, r.target().meta());
            emit(new MirInst.RecordReuse(res, target, r.classId(), r.classTypeId(), r.classJvmName(),
                    r.paramKinds(), args, ln, MirEffect.write(alias, true)));
            propagateAlias(target, res);
            return applyCheckedType(res, r.meta().checkedType(), ln);
        }

        private MirVal lowerOpaque(HirExpr expr) {
            int ln = expr.meta().line();
            List<HirExpr> children = opaqueChildExprs(expr);
            List<MirVal> operands = new ArrayList<>(children.size());
            for (HirExpr ch : children) {
                operands.add(lowerAndWiden(ch));
                if (cur.isTerminated()) return operands.get(operands.size() - 1);
            }
            PrimitiveKind outKind = opaqueResultKind(expr);
            MirEffect fx = opaqueEffect(expr, operands);
            MirVal res = newVal(outKind, null);
            emit(new MirInst.OpaqueExpr(res, expr, operands, ln, fx));
            return res;
        }

        private PrimitiveKind opaqueResultKind(HirExpr expr) {
            return switch (expr) {
                case HirExpr.IntParseFallback ignored -> PrimitiveKind.INT;
                case HirExpr.ArrayOp op -> switch (op.kind()) {
                    case EQUALS -> PrimitiveKind.BOOL;
                    case HASH_CODE -> PrimitiveKind.INT;
                    default -> PrimitiveKind.NONE;
                };
                case HirExpr.DynamicCall c -> c.meta().checkedType() == null
                        ? dynamicCallResultKind(c) : PrimitiveKind.NONE;
                case HirExpr.OverloadDispatch d -> d.meta().checkedType() == null
                        ? d.meta().kind() : PrimitiveKind.NONE;
                default -> PrimitiveKind.NONE;
            };
        }

        private static PrimitiveKind dynamicCallResultKind(HirExpr.DynamicCall c) {
            LoweringTargets.FunctionSignature sig = c.babbleSig();
            if (sig == null) {
                return PrimitiveKind.NONE;
            }
            LoweringTargets.ArgumentBinding binding = c.binding();
            if (binding != null) {
                return binding.paramCount() == sig.parameterKinds().length
                        ? sig.returnKind() : PrimitiveKind.NONE;
            }
            for (HirCallArg arg : c.args()) {
                if (arg.name() != null) {
                    return PrimitiveKind.NONE;
                }
            }
            return c.args().size() == sig.parameterKinds().length
                    ? sig.returnKind() : PrimitiveKind.NONE;
        }

        private MirEffect opaqueEffect(HirExpr expr, List<MirVal> operands) {
            return switch (expr) {
                case HirExpr.CoreCollectionRef ignored -> MirEffect.PURE_NOTHROW;
                case HirExpr.CoreExceptionRef ignored -> MirEffect.PURE_NOTHROW;
                case HirExpr.FunctionRef fr -> {
                    HirFunction target = module.function(fr.functionId());
                    yield (target != null && !target.capturedVars().isEmpty())
                            ? MirEffect.READ_SHARED
                            : MirEffect.PURE_NOTHROW;
                }
                case HirExpr.MakeOverloadSet ignored -> MirEffect.PURE_NOTHROW;
                case HirExpr.OverloadSelect s -> s.exactSet() ? MirEffect.PURE_NOTHROW : MirEffect.PURE_MAY_THROW;
                case HirExpr.ArrayOp ignored -> MirEffect.READ_SHARED_THROW;
                case HirExpr.DynamicFieldAssign a -> (a.operator() == null && !operands.isEmpty())
                        ? MirEffect.write(aliasOf(operands.get(0), a.receiver().meta()), false)
                        : MirEffect.IMPURE_THROW;
                default -> MirEffect.IMPURE_THROW;
            };
        }

        private List<MirVal> lowerCallExprList(List<HirExpr> exprs, PrimitiveKind[] paramKinds,
                                               LoweringTargets.ArgumentBinding binding) {
            List<MirVal> out = new ArrayList<>(exprs.size());
            for (int i = 0; i < exprs.size(); i++) {
                out.add(lowerAndWiden(exprs.get(i)));
                if (cur.isTerminated()) break;
            }
            return out;
        }

        // ------------------------------------------------------------------ narrowing anchor detection

        private NarrowHint extractNarrowHint(HirExpr cond) {
            HirExpr unwrapped = unwrapTransparent(cond);
            boolean negated = false;
            while (unwrapped instanceof HirExpr.Unary u && u.op() == HirExpr.UnaryOp.NOT) {
                negated = !negated;
                unwrapped = unwrapTransparent(u.operand());
            }
            if (unwrapped instanceof HirExpr.Binary b
                    && (b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ)) {
                HirExpr l = unwrapTransparent(b.left());
                HirExpr r = unwrapTransparent(b.right());
                HirVar v = null;
                if (l instanceof HirExpr.VarRead vr && r instanceof HirExpr.Const c && c.value() == null) {
                    v = vr.var();
                } else if (r instanceof HirExpr.VarRead vr && l instanceof HirExpr.Const c && c.value() == null) {
                    v = vr.var();
                }
                if (v != null && !pinnedVarIds.contains(v.id()) && !v.primitiveKind().isPrimitive()) {
                    boolean nonNullOnTrue = (b.op() == HirExpr.BinaryOp.NEQ) ^ negated;
                    return new NarrowHint(v, MirInst.NarrowKind.NON_NULL, "non-null", nonNullOnTrue);
                }
            }
            if (unwrapped instanceof HirExpr.Is isExpr) {
                HirExpr l = unwrapTransparent(isExpr.left());
                if (l instanceof HirExpr.VarRead vr && !pinnedVarIds.contains(vr.var().id())) {
                    String desc = isExpr.builtin() != null ? isExpr.builtin()
                            : (isExpr.descriptor() != null ? isExpr.descriptor() : "type");
                    return new NarrowHint(vr.var(), MirInst.NarrowKind.IS_TYPE, desc, !negated);
                }
            }
            return null;
        }

        private void emitNarrowAnchor(NarrowHint hint, int ln) {
            MirVal base = readSsaVar(hint.var, cur, ln);
            MirVal narrowed = newVal(base.kind(), hint.var.name());
            emit(new MirInst.Narrow(narrowed, hint.kind, base, hint.desc, ln));
            propagateAlias(base, narrowed);
            cur.currentDef.put(hint.var.id(), narrowed);
        }

        private static HirExpr unwrapTransparent(HirExpr expr) {
            while (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
                expr = b.statements().get(0);
            }
            return expr;
        }

        // ------------------------------------------------------------------ effect & type helpers

        private MirEffect callEffect(ExprMeta meta, HirFunction target) {
            if (target != null) {
                if (!target.capturedVars().isEmpty()) {
                    return MirEffect.IMPURE_THROW;
                }
                for (HirParam p : target.params()) {
                    if (p.isMut()) {
                        return MirEffect.IMPURE_THROW;
                    }
                }
            }
            return callEffect(meta, target != null ? target.effects() : EffectSet.EMPTY);
        }

        private MirEffect callEffect(ExprMeta meta, EffectSet calleeEffects) {
            EffectSet fx = meta.effects();
            if (fx.isIO() || calleeEffects.isIO() || fx.mutThis() || calleeEffects.mutThis()) {
                return MirEffect.IMPURE_THROW;
            }
            boolean pure = fx.isPure() && (calleeEffects == EffectSet.EMPTY || calleeEffects.isPure());
            boolean nothrow = fx.isNothrow() || calleeEffects.isNothrow();
            boolean terminating = fx.terminating() || calleeEffects.terminating();
            boolean deterministic = fx.isDeterministic() && (calleeEffects == EffectSet.EMPTY || calleeEffects.isDeterministic());
            if (pure && meta.checkedType() == null) {
                return new MirEffect(MirEffect.Kind.PURE, MirEffect.AliasClass.NONE, nothrow, terminating, deterministic);
            }
            return MirEffect.IMPURE_THROW;
        }

        private MirEffect effectFromMeta(ExprMeta meta, boolean defaultNothrow) {
            EffectSet fx = meta.effects();
            if (fx.isPure() && meta.checkedType() == null) {
                return new MirEffect(MirEffect.Kind.PURE, MirEffect.AliasClass.NONE,
                        fx.isNothrow() || defaultNothrow, fx.terminating(), fx.isDeterministic());
            }
            return MirEffect.IMPURE_THROW;
        }

        private static PrimitiveKind constKind(Object v) {
            if (v == null || v instanceof String) return PrimitiveKind.NONE;
            if (v instanceof Boolean) return PrimitiveKind.BOOL;
            if (v instanceof Byte) return PrimitiveKind.BYTE;
            if (v instanceof Short) return PrimitiveKind.SHORT;
            if (v instanceof Integer) return PrimitiveKind.INT;
            if (v instanceof Long) return PrimitiveKind.LONG;
            if (v instanceof Float) return PrimitiveKind.FLOAT;
            if (v instanceof Double) return PrimitiveKind.DOUBLE;
            if (v instanceof Character) return PrimitiveKind.CHAR;
            return PrimitiveKind.NONE;
        }

        private static PrimitiveKind javaPrimitiveKind(Class<?> c) {
            if (c == int.class) return PrimitiveKind.INT;
            if (c == double.class) return PrimitiveKind.DOUBLE;
            if (c == boolean.class) return PrimitiveKind.BOOL;
            if (c == long.class) return PrimitiveKind.LONG;
            if (c == float.class) return PrimitiveKind.FLOAT;
            if (c == byte.class) return PrimitiveKind.BYTE;
            if (c == short.class) return PrimitiveKind.SHORT;
            if (c == char.class) return PrimitiveKind.CHAR;
            return PrimitiveKind.NONE;
        }

        private static PrimitiveKind javaArgDesiredKind(Class<?> c) {
            if (c == int.class) return PrimitiveKind.INT;
            if (c == boolean.class) return PrimitiveKind.BOOL;
            if (c == double.class) return PrimitiveKind.DOUBLE;
            return PrimitiveKind.NONE;
        }

        private static PrimitiveKind targetKindForRank(int rank) {
            return switch (rank) {
                case 1 -> PrimitiveKind.SHORT;
                case 2 -> PrimitiveKind.INT;
                case 3 -> PrimitiveKind.LONG;
                case 4 -> PrimitiveKind.FLOAT;
                case 5 -> PrimitiveKind.DOUBLE;
                default -> PrimitiveKind.NONE;
            };
        }

        private static boolean isNumericKind(PrimitiveKind k) {
            return switch (k) {
                case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> true;
                default -> false;
            };
        }

        private static int numericOrder(PrimitiveKind k) {
            return switch (k) {
                case BYTE -> 0;
                case SHORT -> 1;
                case INT -> 2;
                case LONG -> 3;
                case FLOAT -> 4;
                case DOUBLE -> 5;
                default -> -1;
            };
        }

        private static boolean canLowerComparison(HirExpr.BinaryOp op, PrimitiveKind lk, PrimitiveKind rk) {
            if (lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL) {
                return op == HirExpr.BinaryOp.EQ || op == HirExpr.BinaryOp.NEQ;
            }
            if (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR) {
                return true;
            }
            return isNumericKind(lk) && isNumericKind(rk);
        }

        private static boolean canLowerPrimitiveCompound(PrimitiveKind targetKind, PrimitiveKind rhsKind, String op) {
            if (targetKind == PrimitiveKind.INT) {
                return (rhsKind == PrimitiveKind.INT || rhsKind == PrimitiveKind.BYTE || rhsKind == PrimitiveKind.SHORT)
                        && !"/".equals(op);
            }
            if (targetKind == PrimitiveKind.LONG && "//".equals(op)) {
                return rhsKind == PrimitiveKind.BYTE || rhsKind == PrimitiveKind.SHORT
                        || rhsKind == PrimitiveKind.INT || rhsKind == PrimitiveKind.LONG;
            }
            if (targetKind == PrimitiveKind.DOUBLE) {
                return (rhsKind == PrimitiveKind.INT || rhsKind == PrimitiveKind.DOUBLE) && !"//".equals(op);
            }
            return false;
        }

        private static boolean isNonZeroIntegerConst(HirExpr expr) {
            if (expr instanceof HirExpr.Const c && c.value() instanceof Number n) {
                return n.longValue() != 0L;
            }
            if (expr instanceof HirExpr.Unary u && u.op() == HirExpr.UnaryOp.NEGATE
                    && u.operand() instanceof HirExpr.Const c && c.value() instanceof Number n) {
                return n.longValue() != 0L;
            }
            return false;
        }

        private static HirExpr.BinaryOp compoundOp(String op) {
            return switch (op) {
                case "+" -> HirExpr.BinaryOp.ADD;
                case "-" -> HirExpr.BinaryOp.SUB;
                case "*" -> HirExpr.BinaryOp.MUL;
                case "/" -> HirExpr.BinaryOp.DIV;
                case "//" -> HirExpr.BinaryOp.FLOOR_DIV;
                default -> throw new IllegalStateException("Unexpected compound operator : " + op);
            };
        }

        private static PrimitiveKind[] paramKinds(HirFunction f) {
            PrimitiveKind[] out = new PrimitiveKind[f.params().size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = f.params().get(i).kind();
            }
            return out;
        }

        // ------------------------------------------------------------------ finalize & trivial phi cleanup

        private List<MirBlock> buildBlocks() {
            List<MirBlock> out = new ArrayList<>(allBlocks.size());
            for (BlockBuilder b : allBlocks) {
                MirTerminator term;
                if (b.term == null) {
                    term = new MirTerminator.Unreachable(fn.line());
                } else {
                    term = switch (b.term) {
                        case PendingTerm.Br br -> new MirTerminator.Br(br.target.id, br.edge.args, br.line);
                        case PendingTerm.BrIf bi -> new MirTerminator.BrIf(
                                bi.cond, bi.thenTarget.id, bi.thenEdge.args,
                                bi.elseTarget.id, bi.elseEdge.args, bi.line);
                        case PendingTerm.Switch sw -> {
                            int n = sw.keys.length;
                            int[] tIds = new int[n];
                            List<List<MirVal>> tArgs = new ArrayList<>(n);
                            for (int k = 0; k < n; k++) {
                                tIds[k] = sw.targets[k].id;
                                tArgs.add(sw.edges[k].args);
                            }
                            yield new MirTerminator.Switch(sw.key, sw.keys, tIds, tArgs,
                                    sw.defaultTarget.id, sw.defaultEdge.args, sw.line);
                        }
                        case PendingTerm.Fixed fx -> fx.term;
                    };
                }
                out.add(new MirBlock(b.id, b.params, b.insts, term, b.handlers, b.handlerEntry));
            }
            return out;
        }

        /**
         * Removes trivial block parameters (where every incoming argument is either the parameter
         * itself or one identical SSA value {@code v}), substituting {@code bp -> v} across the CFG.
         */
        private List<MirBlock> eliminateTrivialBlockParams(List<MirBlock> blocks) {
            boolean changed = true;
            List<MirBlock> current = blocks;
            while (changed) {
                changed = false;
                // Collect incoming argument lists per blockId.
                Map<Integer, List<List<MirVal>>> incoming = new HashMap<>();
                for (MirBlock b : current) {
                    incoming.put(b.id(), new ArrayList<>());
                }
                for (MirBlock b : current) {
                    if (b.terminator() instanceof MirTerminator.Br br) {
                        incoming.get(br.targetBlock()).add(br.args());
                    } else if (b.terminator() instanceof MirTerminator.BrIf bi) {
                        incoming.get(bi.thenBlock()).add(bi.thenArgs());
                        incoming.get(bi.elseBlock()).add(bi.elseArgs());
                    } else if (b.terminator() instanceof MirTerminator.Switch sw) {
                        for (int k = 0; k < sw.keys().length; k++) {
                            incoming.get(sw.targetBlocks()[k]).add(sw.targetArgs().get(k));
                        }
                        incoming.get(sw.defaultBlock()).add(sw.defaultArgs());
                    }
                }
                Map<Integer, MirVal> subst = new HashMap<>();
                Map<Integer, Set<Integer>> removeIndices = new HashMap<>();
                for (MirBlock b : current) {
                    if (b.handlerEntry() || b.params().isEmpty()) {
                        continue;
                    }
                    List<List<MirVal>> edges = incoming.get(b.id());
                    if (edges.isEmpty()) {
                        continue;
                    }
                    for (int i = 0; i < b.params().size(); i++) {
                        MirVal bp = b.params().get(i);
                        MirVal same = null;
                        boolean trivial = true;
                        for (List<MirVal> edgeArgs : edges) {
                            MirVal arg = edgeArgs.get(i);
                            if (arg.id() == bp.id()) {
                                continue;
                            }
                            if (same == null) {
                                same = arg;
                            } else if (same.id() != arg.id()) {
                                trivial = false;
                                break;
                            }
                        }
                        if (trivial && same != null) {
                            subst.put(bp.id(), same);
                            removeIndices.computeIfAbsent(b.id(), k -> new HashSet<>()).add(i);
                        }
                    }
                }
                if (subst.isEmpty()) {
                    break;
                }
                changed = true;
                // Resolve transitive substitutions (a -> b -> c).
                Map<Integer, MirVal> resolved = new HashMap<>(subst.size() * 2);
                for (Map.Entry<Integer, MirVal> e : subst.entrySet()) {
                    MirVal v = e.getValue();
                    Set<Integer> seen = new HashSet<>();
                    seen.add(e.getKey());
                    while (subst.containsKey(v.id()) && seen.add(v.id())) {
                        v = subst.get(v.id());
                    }
                    resolved.put(e.getKey(), v);
                }
                List<MirBlock> next = new ArrayList<>(current.size());
                for (MirBlock b : current) {
                    Set<Integer> drop = removeIndices.getOrDefault(b.id(), Set.of());
                    List<MirVal> newParams = drop.isEmpty() ? b.params() : filterOut(b.params(), drop);
                    List<MirInst> newInsts = new ArrayList<>(b.insts().size());
                    for (MirInst inst : b.insts()) {
                        newInsts.add(inst.remapUses(v -> resolved.getOrDefault(v.id(), v)));
                    }
                    MirTerminator term = b.terminator().remapUses(v -> resolved.getOrDefault(v.id(), v));
                    if (term instanceof MirTerminator.Br br) {
                        Set<Integer> td = removeIndices.getOrDefault(br.targetBlock(), Set.of());
                        if (!td.isEmpty()) {
                            term = new MirTerminator.Br(br.targetBlock(), filterOut(br.args(), td), br.line());
                        }
                    } else if (term instanceof MirTerminator.BrIf bi) {
                        Set<Integer> td = removeIndices.getOrDefault(bi.thenBlock(), Set.of());
                        Set<Integer> ed = removeIndices.getOrDefault(bi.elseBlock(), Set.of());
                        if (!td.isEmpty() || !ed.isEmpty()) {
                            term = new MirTerminator.BrIf(bi.cond(),
                                    bi.thenBlock(), td.isEmpty() ? bi.thenArgs() : filterOut(bi.thenArgs(), td),
                                    bi.elseBlock(), ed.isEmpty() ? bi.elseArgs() : filterOut(bi.elseArgs(), ed),
                                    bi.line());
                        }
                    } else if (term instanceof MirTerminator.Switch sw) {
                        boolean swChanged = false;
                        List<List<MirVal>> nta = new ArrayList<>(sw.keys().length);
                        for (int k = 0; k < sw.keys().length; k++) {
                            Set<Integer> td = removeIndices.getOrDefault(sw.targetBlocks()[k], Set.of());
                            if (!td.isEmpty()) {
                                nta.add(filterOut(sw.targetArgs().get(k), td));
                                swChanged = true;
                            } else {
                                nta.add(sw.targetArgs().get(k));
                            }
                        }
                        Set<Integer> dd = removeIndices.getOrDefault(sw.defaultBlock(), Set.of());
                        List<MirVal> nda = dd.isEmpty() ? sw.defaultArgs() : filterOut(sw.defaultArgs(), dd);
                        if (!dd.isEmpty()) {
                            swChanged = true;
                        }
                        if (swChanged) {
                            term = new MirTerminator.Switch(sw.key(), sw.keys(), sw.targetBlocks(), nta,
                                    sw.defaultBlock(), nda, sw.line());
                        }
                    }
                    next.add(new MirBlock(b.id(), newParams, newInsts, term, b.handlers(), b.handlerEntry()));
                }
                current = next;
            }
            return current;
        }

        private static List<MirVal> filterOut(List<MirVal> list, Set<Integer> dropIndices) {
            List<MirVal> out = new ArrayList<>(list.size() - dropIndices.size());
            for (int i = 0; i < list.size(); i++) {
                if (!dropIndices.contains(i)) {
                    out.add(list.get(i));
                }
            }
            return out;
        }
    }
}
