package org.babblelang.opt.hir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.opt.HirEffectAnalysis;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;

/**
 * {@code O11} — Narrowing-safe Pure Predicate Reordering over structured {@link HirExpr.Logic}
 * chains and nested pure {@link HirExpr.If} guards ({@code BIP039-D6}, {@code BIP039-I2}).
 *
 * <p>Flattens same-operator {@code and}/{@code or} chains into individual predicate atoms and
 * stably moves cheaper {@code pure nothrow @TERMINATING deterministic} atoms ahead of more
 * expensive ones without ever crossing a {@code rightNarrowed} boundary or an atom containing a
 * {@link HirExpr.Narrow} guard anchor.
 */
public final class HirPredicateReorderPass implements HirPass {
    @Override
    public String name() {
        return "HirPredicateReorder";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] rewrites = {0};
        boolean changed = false;

        HirEffectAnalysis modFx = HirEffectAnalysis.forModule(module);
        List<HirExpr> newTop =
                HirRewriter.rewriteList(
                        module.topLevelStatements(), s -> reorderExpr(s, modFx, rewrites));
        if (newTop != module.topLevelStatements()) {
            changed = true;
        }

        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            HirEffectAnalysis fx = HirEffectAnalysis.forFunction(fn, module);
            List<HirExpr> nb =
                    HirRewriter.rewriteList(fn.body(), s -> reorderExpr(s, fx, rewrites));
            if (nb != fn.body()) {
                changed = true;
                fn = fn.withBody(nb);
            }
            newFns.put(e.getKey(), fn);
        }

        if (!changed) {
            return Result.unchanged(module);
        }
        return new Result(
                module.withUpdates(
                        newTop,
                        newFns,
                        module.classes(),
                        module.interfaces(),
                        module.nextVarId(),
                        module.nextOwnerId()),
                rewrites[0]);
    }

    private static HirExpr reorderExpr(HirExpr expr, HirEffectAnalysis fx, int[] rewrites) {
        if (expr == null) {
            return null;
        }
        // Only flatten at the root of a same-operator Logic chain so nested chains aren't re-walked
        // redundantly; children of non-same-op nodes are rewritten first.
        if (expr instanceof HirExpr.Logic l) {
            return reorderLogicChain(l, fx, rewrites);
        }

        HirExpr cur = HirRewriter.rewriteChildren(expr, c -> reorderExpr(c, fx, rewrites));

        if (cur instanceof HirExpr.If outer) {
            HirExpr unwrappedThen = HirRewriter.unwrapTransparent(outer.thenBranch());
            if (unwrappedThen instanceof HirExpr.If inner
                    && outer.meta().kind() == inner.meta().kind()
                    && Objects.equals(outer.elseBranch(), inner.elseBranch())
                    && !containsNarrow(inner.cond())
                    && !containsNarrow(outer.cond())
                    && fx.effectOf(outer.cond()).isPureFloatable()
                    && fx.effectOf(inner.cond()).isPureFloatable()
                    && exprCost(inner.cond()) < exprCost(outer.cond())) {
                rewrites[0]++;
                HirExpr swappedInner =
                        new HirExpr.If(
                                outer.cond(), inner.thenBranch(), inner.elseBranch(), inner.meta());
                return new HirExpr.If(inner.cond(), swappedInner, outer.elseBranch(), outer.meta());
            }
        }

        return cur;
    }

    private record LogicItem(HirExpr expr, boolean barrierBefore) {}

    private static HirExpr reorderLogicChain(
            HirExpr.Logic root, HirEffectAnalysis fx, int[] rewrites) {
        HirExpr.LogicOp op = root.op();
        List<LogicItem> items = new ArrayList<>(4);
        flattenLogic(root, op, false, items);

        // Rewrite children inside each individual predicate atom first.
        boolean childChanged = false;
        for (int i = 0; i < items.size(); i++) {
            LogicItem it = items.get(i);
            HirExpr rewritten = reorderExpr(it.expr, fx, rewrites);
            if (rewritten != it.expr) {
                items.set(i, new LogicItem(rewritten, it.barrierBefore));
                childChanged = true;
            }
        }

        // Stable adjacent-swap pass: only swap adjacent pure-floatable, narrow-free atoms with no
        // narrowing barrier between them when right is strictly cheaper than left.
        boolean swappedAny = false;
        int n = items.size();
        boolean[] floatable = new boolean[n];
        int[] costs = new int[n];
        for (int i = 0; i < n; i++) {
            HirExpr e = items.get(i).expr;
            floatable[i] = !containsNarrow(e) && fx.effectOf(e).isPureFloatable();
            costs[i] = exprCost(e);
        }
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i + 1 < n; i++) {
                if (items.get(i + 1).barrierBefore) {
                    continue;
                }
                if (floatable[i] && floatable[i + 1] && costs[i + 1] < costs[i]) {
                    LogicItem tmpItem = items.get(i);
                    items.set(i, new LogicItem(items.get(i + 1).expr, tmpItem.barrierBefore));
                    items.set(i + 1, new LogicItem(tmpItem.expr, false));
                    int tmpCost = costs[i];
                    costs[i] = costs[i + 1];
                    costs[i + 1] = tmpCost;
                    swappedAny = true;
                    progress = true;
                    rewrites[0]++;
                }
            }
        }

        if (!childChanged && !swappedAny) {
            return root;
        }
        // Rebuild left-associative chain preserving root metadata.
        HirExpr acc = items.get(0).expr;
        for (int i = 1; i < n; i++) {
            LogicItem it = items.get(i);
            acc = new HirExpr.Logic(op, acc, it.expr, it.barrierBefore, root.meta());
        }
        return acc;
    }

    private static void flattenLogic(
            HirExpr expr, HirExpr.LogicOp op, boolean barrierBefore, List<LogicItem> out) {
        if (expr instanceof HirExpr.Logic l && l.op() == op) {
            flattenLogic(l.left(), op, barrierBefore, out);
            flattenLogic(l.right(), op, l.rightNarrowed(), out);
        } else {
            out.add(new LogicItem(expr, barrierBefore));
        }
    }

    private static boolean containsNarrow(HirExpr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof HirExpr.Narrow) {
            return true;
        }
        boolean[] found = {false};
        HirRewriter.rewriteChildren(
                expr,
                c -> {
                    if (!found[0] && containsNarrow(c)) {
                        found[0] = true;
                    }
                    return c;
                });
        return found[0];
    }

    /**
     * Heuristic evaluation cost matching legacy {@code PredicateReorderPass.blockCost}: calls = 10,
     * field/array loads = 4, export/name reads = 3, scalar ops = 1, constants/locals = 0.
     */
    static int exprCost(HirExpr expr) {
        if (expr == null) {
            return 0;
        }
        return switch (expr) {
            case HirExpr.Const ignored -> 0;
            case HirExpr.VarRead ignored -> 0;
            case HirExpr.ThisRef ignored -> 0;
            case HirExpr.RecurseRef ignored -> 0;
            case HirExpr.Narrow n -> exprCost(n.operand());
            case HirExpr.Move m -> exprCost(m.operand());
            case HirExpr.Freeze f -> exprCost(f.operand());
            case HirExpr.Block b ->
                    b.isTransparentSingleExpr() ? exprCost(b.statements().get(0)) : 5;
            case HirExpr.DirectBabbleCall c -> 10 + listCost(c.args());
            case HirExpr.DirectThisMethodCall c ->
                    10 + exprCost(c.receiver()) + callArgsCost(c.args());
            case HirExpr.InterfaceCall c -> 10 + exprCost(c.receiver()) + listCost(c.args());
            case HirExpr.JavaMethodCall c ->
                    10
                            + (c.target().isStatic() && c.pureStaticReceiver()
                                    ? 0
                                    : exprCost(c.receiver()))
                            + listCost(c.args());
            case HirExpr.CoreMethodCall c -> 10 + exprCost(c.receiver()) + listCost(c.args());
            case HirExpr.SelfRecurseCall c -> 10 + listCost(c.args());
            case HirExpr.JavaConstructorCall c -> 10 + listCost(c.args());
            case HirExpr.RecordNew r -> 10 + listCost(r.args());
            case HirExpr.ThisFieldGet g -> 4 + exprCost(g.receiver());
            case HirExpr.JavaFieldGet g ->
                    4
                            + (g.target().isStatic() && g.pureStaticReceiver()
                                    ? 0
                                    : exprCost(g.receiver()));
            case HirExpr.ArrayOp op ->
                    (op.kind() == LoweringTargets.ArrayOpKind.SIZE ? 1 : 4)
                            + exprCost(op.receiver())
                            + listCost(op.args());
            case HirExpr.ModuleExportGet g -> 3 + exprCost(g.receiver());
            case HirExpr.NameRead ignored -> 3;
            case HirExpr.Unary u -> 1 + exprCost(u.operand());
            case HirExpr.Binary b -> 1 + exprCost(b.left()) + exprCost(b.right());
            case HirExpr.Logic l -> 1 + exprCost(l.left()) + exprCost(l.right());
            case HirExpr.Is i -> 1 + exprCost(i.left());
            case HirExpr.Convert cv -> 1 + exprCost(cv.operand());
            case HirExpr.Widen w -> 1 + exprCost(w.operand());
            default -> 2;
        };
    }

    private static int listCost(List<HirExpr> list) {
        int sum = 0;
        for (HirExpr e : list) sum += exprCost(e);
        return sum;
    }

    private static int callArgsCost(List<HirCallArg> args) {
        int sum = 0;
        for (HirCallArg a : args) sum += exprCost(a.value());
        return sum;
    }
}
