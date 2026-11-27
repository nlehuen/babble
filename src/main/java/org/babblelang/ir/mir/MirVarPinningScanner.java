package org.babblelang.ir.mir;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.hir.HirVar;

/**
 * Pre-pass over a {@link HirFunction} that registers all {@link HirVar}s and determines which
 * variables must be pinned to memory slots (captured vars, potentially-undefined vars, {@code
 * this}/{@code recurse}, and outer variables written inside {@code try} or {@code catch} of a
 * {@code try-finally}).
 */
final class MirVarPinningScanner {
    record Result(
            Set<Integer> pinnedVarIds,
            Map<Integer, HirVar> pinnedVarsById,
            Map<Integer, HirVar> varById) {}

    private final Set<Integer> pinnedVarIds = new LinkedHashSet<>();
    private final Map<Integer, HirVar> pinnedVarsById = new LinkedHashMap<>();
    private final Map<Integer, HirVar> varById = new HashMap<>();

    private MirVarPinningScanner() {}

    static Result scan(HirFunction fn) {
        MirVarPinningScanner scanner = new MirVarPinningScanner();
        scanner.scanFunction(fn);
        return new Result(scanner.pinnedVarIds, scanner.pinnedVarsById, scanner.varById);
    }

    private void scanFunction(HirFunction fn) {
        for (HirVar v : fn.declaredVars()) {
            registerAndPinIfRequired(v);
        }
        for (HirParam p : fn.params()) {
            registerAndPinIfRequired(p.var());
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

    private void registerAndPinIfRequired(HirVar v) {
        if (v != null) {
            registerVar(v);
            if (mustAlwaysPin(v)) {
                pin(v);
            }
        }
    }

    private static boolean mustAlwaysPin(HirVar v) {
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

    private void scanStmts(
            List<HirExpr> stmts, Set<Integer> visibleVars, Set<Integer> tryOuterVars) {
        for (HirExpr e : stmts) {
            scanExpr(e, visibleVars, tryOuterVars);
        }
    }

    private void scanExpr(HirExpr expr, Set<Integer> visibleVars, Set<Integer> tryOuterVars) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case HirExpr.VarRead v -> registerAndPinIfRequired(v.var());
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
                registerAndPinIfRequired(m.scrutineeVar());
                scanExpr(m.scrutinee(), visibleVars, tryOuterVars);
                for (HirExpr.MatchArm arm : m.arms()) {
                    Set<Integer> armVis = addDeclared(visibleVars, arm.boundVars());
                    for (HirVar bv : arm.boundVars()) {
                        registerAndPinIfRequired(bv);
                    }
                    scanPattern(arm.pattern(), armVis, tryOuterVars);
                    scanExpr(arm.guard(), armVis, tryOuterVars);
                    scanExpr(arm.body(), armVis, tryOuterVars);
                }
                if (m.elseArm() != null) {
                    Set<Integer> elseVis = visibleVars;
                    if (m.elseArm().var() != null) {
                        registerAndPinIfRequired(m.elseArm().var());
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
                registerAndPinIfRequired(f.loopVar());
                Set<Integer> nextVis = addDeclared(visibleVars, f.bodyDeclaredVars());
                if (f.loopVar() != null) {
                    nextVis = new HashSet<>(nextVis);
                    nextVis.add(f.loopVar().id());
                }
                scanStmts(f.body(), nextVis, tryOuterVars);
            }
            case HirExpr.Try t -> {
                // Any variable visible before entering try that is written inside tryBody must
                // be pinned, because a mid-try throw could jump to catch or finally observing
                // that write.
                Set<Integer> outerForTry = new HashSet<>(tryOuterVars);
                outerForTry.addAll(visibleVars);
                Set<Integer> tryVis = addDeclared(visibleVars, t.tryDeclaredVars());
                scanStmts(t.tryBody(), tryVis, outerForTry);

                boolean hasNonEmptyFinally = t.hasFinally() && !t.finallyBody().isEmpty();
                Set<Integer> outerForCatch = hasNonEmptyFinally ? outerForTry : tryOuterVars;
                for (HirExpr.HirCatch cc : t.catches()) {
                    registerAndPinIfRequired(cc.var());
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
                registerAndPinIfRequired(m.var());
                scanExpr(m.operand(), visibleVars, tryOuterVars);
            }
            case HirExpr.Freeze f -> scanExpr(f.operand(), visibleVars, tryOuterVars);
            case HirExpr.Narrow n -> scanExpr(n.operand(), visibleVars, tryOuterVars);
            case HirExpr.Convert cv -> scanExpr(cv.operand(), visibleVars, tryOuterVars);
            case HirExpr.Widen w -> scanExpr(w.operand(), visibleVars, tryOuterVars);
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

    private void scanPattern(HirPattern p, Set<Integer> visibleVars, Set<Integer> tryOuterVars) {
        if (p == null) {
            return;
        }
        switch (p) {
            case HirPattern.Const ignored -> {}
            case HirPattern.Wildcard ignored -> {}
            case HirPattern.TypeTest ignored -> {}
            case HirPattern.ValueExpr v -> scanExpr(v.expr(), visibleVars, tryOuterVars);
            case HirPattern.Bind b -> {
                registerAndPinIfRequired(b.var());
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
                registerAndPinIfRequired(sd.restVar());
            }
        }
    }

    private Set<Integer> addDeclared(Set<Integer> base, List<HirVar> decls) {
        if (decls.isEmpty()) {
            return base;
        }
        Set<Integer> next = new HashSet<>(base);
        for (HirVar v : decls) {
            registerAndPinIfRequired(v);
            next.add(v.id());
        }
        return next;
    }

    private void scanCallArgs(
            List<HirCallArg> args, Set<Integer> visibleVars, Set<Integer> tryOuterVars) {
        for (HirCallArg a : args) {
            scanExpr(a.value(), visibleVars, tryOuterVars);
        }
    }
}
