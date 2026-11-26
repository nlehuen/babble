package org.babblelang.opt.hir;

import org.babblelang.analysis.BuiltinAxioms;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.egraph.AlgebraicRules;
import org.babblelang.ir.egraph.EGraph;
import org.babblelang.ir.egraph.ENode;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirPass;
import org.babblelang.opt.HirPurity;
import org.babblelang.opt.HirRewriter;
import org.babblelang.opt.PassContext;
import org.babblelang.runtime.Truth;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unified constant folding, single-assignment constant propagation, constant branch/loop
 * pruning, whitelisted pure-JDK folding, and E-graph algebraic simplification pass
 * ({@code BIP027-D1}, {@code BIP027-D4}; merges former {@code ConstFoldPass} and
 * {@code AlgebraicSimplifyPass}).
 *
 * <p>Strictly enforces IEEE-754 floating-point semantics via {@link AlgebraicRules#optimizerRules()}.
 */
public final class HirEGraphSimplifyPass implements HirPass {
    private static final int EGRAPH_MAX_ITERS = 6;
    private static final int EGRAPH_MAX_NODES = 96;

    @Override
    public String name() {
        return "HirEGraphSimplify";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] rewrites = {0};
        HirModule updated = HirRewriter.rewriteModule(
                module,
                (ownerId, fnId, stmts) -> simplifyBody(stmts, module, rewrites),
                expr -> simplifyExpr(expr, Map.of(), Set.of(), module, rewrites),
                module::nextVarId);
        return updated == module ? Result.unchanged(module) : new Result(updated, rewrites[0]);
    }

    private List<HirExpr> simplifyBody(List<HirExpr> stmts, HirModule module, int[] rewrites) {
        Set<Integer> assigned = new HashSet<>();
        for (HirExpr s : stmts) {
            collectAssignedVars(s, assigned);
        }
        return simplifyStatements(stmts, new HashMap<>(), assigned, module, rewrites);
    }

    private List<HirExpr> simplifyStatements(List<HirExpr> stmts, Map<Integer, HirExpr.Const> env,
                                             Set<Integer> assigned, HirModule module, int[] rewrites) {
        List<HirExpr> copy = null;
        for (int i = 0; i < stmts.size(); i++) {
            HirExpr old = stmts.get(i);
            HirExpr nw = simplifyExpr(old, env, assigned, module, rewrites);
            if (nw != old) {
                if (copy == null) {
                    copy = new ArrayList<>(stmts);
                }
                copy.set(i, nw);
            }
            if (nw instanceof HirExpr.Def d && d.var() != null && !d.var().isCaptured()
                    && !assigned.contains(d.var().id())
                    && d.value() instanceof HirExpr.Const c) {
                HirExpr.Const bound = coerceConstForVar(c, d.var(), d.meta().wideningRank());
                if (bound != null) {
                    env.put(d.var().id(), bound);
                }
            }
        }
        return copy != null ? copy : stmts;
    }

    private HirExpr simplifyExpr(HirExpr expr, Map<Integer, HirExpr.Const> env, Set<Integer> assigned,
                                 HirModule module, int[] rewrites) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof HirExpr.Block b) {
            Map<Integer, HirExpr.Const> innerEnv = new HashMap<>(env);
            List<HirExpr> ns = simplifyStatements(b.statements(), innerEnv, assigned, module, rewrites);
            HirExpr.Block nb = ns == b.statements() ? b
                    : new HirExpr.Block(ns, b.opensNamespaceScope(), b.declaredNames(), b.declaredVars(), b.innerWideningRank(), b.meta());
            if (nb.isTransparentSingleExpr() && nb.statements().get(0) instanceof HirExpr.Const c) {
                rewrites[0]++;
                return c;
            }
            return nb;
        }
        if (expr instanceof HirExpr.If i) {
            HirExpr nc = simplifyExpr(i.cond(), env, assigned, module, rewrites);
            HirExpr nt = simplifyExpr(i.thenBranch(), new HashMap<>(env), assigned, module, rewrites);
            HirExpr ne = i.elseBranch() != null ? simplifyExpr(i.elseBranch(), new HashMap<>(env), assigned, module, rewrites) : null;
            HirExpr.If rebuilt = (nc == i.cond() && nt == i.thenBranch() && ne == i.elseBranch()) ? i
                    : new HirExpr.If(nc, nt, ne, i.meta());
            HirExpr pruned = tryPruneIf(rebuilt);
            if (pruned != null) {
                rewrites[0]++;
                return pruned;
            }
            return rebuilt;
        }
        if (expr instanceof HirExpr.Try t) {
            List<HirExpr> ntb = simplifyStatements(t.tryBody(), new HashMap<>(env), assigned, module, rewrites);
            List<HirExpr.HirCatch> ncs = null;
            for (int idx = 0; idx < t.catches().size(); idx++) {
                HirExpr.HirCatch cc = t.catches().get(idx);
                List<HirExpr> ncb = simplifyStatements(cc.body(), new HashMap<>(env), assigned, module, rewrites);
                if (ncb != cc.body()) {
                    if (ncs == null) {
                        ncs = new ArrayList<>(t.catches());
                    }
                    ncs.set(idx, new HirExpr.HirCatch(cc.var(), cc.target(), ncb, cc.declaredVars(),
                            cc.opensNamespaceScope(), cc.declaredNames(), cc.wideningRank(), cc.line()));
                }
            }
            List<HirExpr> nfb = simplifyStatements(t.finallyBody(), new HashMap<>(env), assigned, module, rewrites);
            return (ntb == t.tryBody() && ncs == null && nfb == t.finallyBody()) ? t
                    : new HirExpr.Try(ntb, t.tryDeclaredVars(), t.tryOpensNamespaceScope(), t.tryDeclaredNames(),
                            t.tryWideningRank(), ncs != null ? ncs : t.catches(), nfb,
                            t.finallyDeclaredVars(), t.finallyOpensNamespaceScope(), t.finallyDeclaredNames(),
                            t.finallyNothrow(), t.hasFinally(), t.meta());
        }
        if (expr instanceof HirExpr.While w) {
            HirExpr nc = simplifyExpr(w.cond(), env, assigned, module, rewrites);
            if (nc instanceof HirExpr.Const c && !Truth.test(c.value())) {
                rewrites[0]++;
                return new HirExpr.Const(null, w.meta().withKind(PrimitiveKind.NONE).withWideningRank(-1));
            }
            HirExpr nb = simplifyExpr(w.body(), new HashMap<>(env), assigned, module, rewrites);
            return (nc == w.cond() && nb == w.body()) ? w : new HirExpr.While(nc, nb, w.meta());
        }
        if (expr instanceof HirExpr.For f) {
            HirExpr ni = simplifyExpr(f.iterable(), env, assigned, module, rewrites);
            List<HirExpr> nb = simplifyStatements(f.body(), new HashMap<>(env), assigned, module, rewrites);
            return (ni == f.iterable() && nb == f.body()) ? f
                    : new HirExpr.For(ni, f.target(), f.loopVar(), f.bodyDeclaredVars(), nb,
                            f.bodyWideningRank(), f.elemSamTarget(), f.meta());
        }

        // Propagate known non-reassigned constant local variables
        if (expr instanceof HirExpr.VarRead v) {
            HirExpr.Const bound = env.get(v.var().id());
            if (bound != null && v.meta().checkedType() == null
                    && (!v.var().mayBeUndefined() || v.var().definitelyDefined())
                    && bound.meta().kind() == v.var().primitiveKind()) {
                HirExpr.Const folded = applyWidening(bound.value(), bound.meta().kind(), v.meta());
                if (folded != null) {
                    rewrites[0]++;
                    return folded;
                }
            }
            return v;
        }

        // Rewrite children bottom-up
        HirExpr cur = HirRewriter.rewriteChildren(expr, child -> simplifyExpr(child, env, assigned, module, rewrites));

        // Canonicalize Const with pending wideningRank
        if (cur instanceof HirExpr.Const c && c.meta().wideningRank() >= 0) {
            HirExpr.Const widened = applyWidening(c.value(), c.meta().kind(), c.meta());
            if (widened != null) {
                rewrites[0]++;
                return widened;
            }
        }

        // Short-circuit logic folding
        if (cur instanceof HirExpr.Logic l) {
            HirExpr folded = tryFoldLogic(l);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }

        // Whitelisted pure deterministic JDK calls (Math.*, String.*)
        if (cur instanceof HirExpr.JavaMethodCall jm) {
            HirExpr folded = tryFoldJavaCall(jm);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }

        // Direct constant folding for Unary / Binary (including wideningRank >= 0 & non-BOOL NOT)
        if (cur instanceof HirExpr.Unary u) {
            HirExpr folded = tryFoldUnary(u);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }
        if (cur instanceof HirExpr.Binary b) {
            HirExpr folded = tryFoldBinary(b);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }

        // E-graph saturation & extraction on eligible pure algebraic / idempotent expressions
        if (isEGraphCandidateRoot(cur, module)) {
            HirExpr simplified = tryEGraphSimplify(cur, module);
            if (simplified != null && simplified != cur) {
                rewrites[0]++;
                return simplified;
            }
        }

        // Order-preserving single-node identities for expressions with potentially impure operands
        HirExpr single = tryNonDroppingIdentity(cur, module);
        if (single != null) {
            rewrites[0]++;
            return single;
        }

        return cur;
    }

    // ---------------------------------------------------------------- E-graph bridge (BIP027-D1)

    private static boolean isEGraphCandidateRoot(HirExpr expr, HirModule module) {
        if (expr instanceof HirExpr.Unary u) {
            return u.meta().wideningRank() < 0 && u.meta().checkedType() == null
                    && HirPurity.isPureNothrowTerminating(u, module);
        }
        if (expr instanceof HirExpr.Binary b) {
            return b.meta().wideningRank() < 0 && b.meta().checkedType() == null
                    && HirPurity.isPureNothrowTerminating(b, module);
        }
        if (expr instanceof HirExpr.DirectBabbleCall c) {
            return isIdempotentBabbleEligible(c, module) && HirPurity.isPureNothrowTerminating(c.args().get(0), module);
        }
        if (expr instanceof HirExpr.JavaMethodCall c) {
            return isIdempotentJavaEligible(c, module)
                    && (c.target().isStatic()
                            ? HirPurity.isPureNothrowTerminating(c.args().get(0), module)
                            : HirPurity.isPureNothrowTerminating(c.receiver(), module));
        }
        return false;
    }

    private static final class LowerContext {
        final EGraph graph = new EGraph();
        final HirModule module;
        final Map<String, HirExpr> leaves = new HashMap<>();
        final Map<String, HirExpr> callTemplates = new HashMap<>();
        int nextOpaque = 0;

        LowerContext(HirModule module) {
            this.module = module;
        }

        private static boolean isLowerableComparison(HirExpr.Binary b) {
            if (!b.op().isComparison()) {
                return false;
            }
            if (b.left().meta().wideningRank() >= 0 || b.right().meta().wideningRank() >= 0) {
                return false;
            }
            PrimitiveKind lk = b.left().meta().kind();
            PrimitiveKind rrk = b.right().meta().kind();
            if (b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ) {
                if (lk.isPrimitive() && lk == rrk) {
                    return true;
                }
                return b.left().meta().stringType() && b.right().meta().stringType();
            }
            return lk == rrk && (HirPurity.isNumericKind(lk) || lk == PrimitiveKind.CHAR);
        }

        private static ENode.Op toComparisonOp(HirExpr.BinaryOp op) {
            return switch (op) {
                case EQ -> ENode.Op.EQ;
                case NEQ -> ENode.Op.NEQ;
                case LT -> ENode.Op.LT;
                case LTE -> ENode.Op.LTE;
                case GT -> ENode.Op.GT;
                case GTE -> ENode.Op.GTE;
                default -> throw new IllegalStateException();
            };
        }

        int lower(HirExpr raw) {
            HirExpr expr = HirRewriter.unwrapTransparent(raw);
            if (expr instanceof HirExpr.Const c && c.meta().wideningRank() < 0 && c.meta().checkedType() == null) {
                return graph.add(ENode.constVal(c.value(), c.meta().kind()));
            }
            if (expr instanceof HirExpr.VarRead v && v.meta().wideningRank() < 0 && v.meta().checkedType() == null
                    && HirPurity.isPureNothrowTerminating(v, module)) {
                String key = "v#" + v.var().id();
                leaves.putIfAbsent(key, v);
                int id = graph.add(ENode.opaque(key, v.meta().kind()));
                if (v.meta().stringType()) {
                    graph.markReflexiveEq(id);
                }
                return id;
            }
            if (expr instanceof HirExpr.Unary u && u.meta().wideningRank() < 0 && u.meta().checkedType() == null) {
                if (u.op() == HirExpr.UnaryOp.NOT && u.operand().meta().kind() == PrimitiveKind.BOOL
                        && u.operand().meta().wideningRank() < 0) {
                    return graph.add(ENode.unary(ENode.Op.NOT, PrimitiveKind.BOOL, lower(u.operand())));
                }
                if (u.op() == HirExpr.UnaryOp.NEGATE && HirPurity.isIntegralKind(u.meta().kind())
                        && u.operand().meta().kind() == u.meta().kind() && u.operand().meta().wideningRank() < 0) {
                    return graph.add(ENode.unary(ENode.Op.NEG, u.meta().kind(), lower(u.operand())));
                }
            }
            if (expr instanceof HirExpr.Binary b && b.meta().wideningRank() < 0 && b.meta().checkedType() == null) {
                PrimitiveKind rk = b.meta().kind();
                PrimitiveKind lk = b.left().meta().kind();
                PrimitiveKind rrk = b.right().meta().kind();
                if (HirPurity.isIntegralKind(rk) && lk == rk && rrk == rk
                        && b.left().meta().wideningRank() < 0 && b.right().meta().wideningRank() < 0) {
                    ENode.Op op = switch (b.op()) {
                        case ADD -> ENode.Op.ADD;
                        case SUB -> ENode.Op.SUB;
                        case MUL -> ENode.Op.MUL;
                        default -> null;
                    };
                    if (op != null) {
                        return graph.add(ENode.binary(op, rk, lower(b.left()), lower(b.right())));
                    }
                }
                if (isLowerableComparison(b)) {
                    return graph.add(ENode.binary(toComparisonOp(b.op()), PrimitiveKind.BOOL, lower(b.left()), lower(b.right())));
                }
                if (b.op() == HirExpr.BinaryOp.ADD && b.left().meta().stringType() && b.right().meta().stringType()
                        && b.left().meta().wideningRank() < 0 && b.right().meta().wideningRank() < 0) {
                    return graph.add(ENode.binary(ENode.Op.STR_ADD, PrimitiveKind.NONE, lower(b.left()), lower(b.right())));
                }
            }
            if (expr instanceof HirExpr.DirectBabbleCall c && isIdempotentBabbleEligible(c, module)) {
                String sym = c.externalTarget() != null
                        ? "babble-ext#" + c.externalTarget().canonicalKey()
                        : "babble#" + c.targetFunctionId();
                callTemplates.putIfAbsent(sym, c);
                graph.registerCallProps(sym, new EGraph.CallProps(true, false, false, false));
                int id = graph.add(ENode.call(sym, c.meta().kind(), lower(c.args().get(0))));
                if (c.meta().stringType()) {
                    graph.markReflexiveEq(id);
                }
                return id;
            }
            if (expr instanceof HirExpr.JavaMethodCall c && isIdempotentJavaEligible(c, module)) {
                Method m = c.target().method();
                String sym = (c.target().isStatic() ? "jstatic#" : "jinst#")
                        + m.getDeclaringClass().getName() + "." + m.getName() + ":" + m.getParameterCount();
                callTemplates.putIfAbsent(sym, c);
                graph.registerCallProps(sym, new EGraph.CallProps(true, false, false, false));
                int child = c.target().isStatic() ? lower(c.args().get(0)) : lower(c.receiver());
                int id = graph.add(ENode.call(sym, c.meta().kind(), child));
                if (c.meta().stringType()) {
                    graph.markReflexiveEq(id);
                }
                return id;
            }
            String key = "p#" + (nextOpaque++);
            leaves.put(key, raw);
            int id = graph.add(ENode.opaque(key, raw.meta().kind()));
            if (raw.meta().stringType()) {
                graph.markReflexiveEq(id);
            }
            return id;
        }

        int treeCost(HirExpr raw) {
            HirExpr expr = HirRewriter.unwrapTransparent(raw);
            if (expr instanceof HirExpr.Const) {
                return EGraph.defaultCost(ENode.constVal(null, PrimitiveKind.NONE));
            }
            if (expr instanceof HirExpr.Unary u && u.meta().wideningRank() < 0 && u.meta().checkedType() == null
                    && ((u.op() == HirExpr.UnaryOp.NOT && u.operand().meta().kind() == PrimitiveKind.BOOL)
                    || (u.op() == HirExpr.UnaryOp.NEGATE && HirPurity.isIntegralKind(u.meta().kind())))) {
                return 3 + treeCost(u.operand());
            }
            if (expr instanceof HirExpr.Binary b && b.meta().wideningRank() < 0 && b.meta().checkedType() == null) {
                PrimitiveKind rk = b.meta().kind();
                if ((HirPurity.isIntegralKind(rk) && b.left().meta().kind() == rk && b.right().meta().kind() == rk
                        && (b.op() == HirExpr.BinaryOp.ADD || b.op() == HirExpr.BinaryOp.SUB || b.op() == HirExpr.BinaryOp.MUL))
                        || isLowerableComparison(b)
                        || (b.op() == HirExpr.BinaryOp.ADD && b.left().meta().stringType() && b.right().meta().stringType())) {
                    return 4 + treeCost(b.left()) + treeCost(b.right());
                }
            }
            if (expr instanceof HirExpr.DirectBabbleCall c && isIdempotentBabbleEligible(c, module)) {
                return 10 + treeCost(c.args().get(0));
            }
            if (expr instanceof HirExpr.JavaMethodCall c && isIdempotentJavaEligible(c, module)) {
                return 10 + treeCost(c.target().isStatic() ? c.args().get(0) : c.receiver());
            }
            return 2;
        }

        HirExpr rebuild(EGraph.ExtractedTree tree, ExprMeta rootMeta) {
            ENode n = tree.node();
            return switch (n.op()) {
                case CONST -> {
                    boolean isStr = n.literal() instanceof String;
                    ExprMeta m = isStr
                            ? new ExprMeta(rootMeta.line(), PrimitiveKind.NONE, -1, null, rootMeta.effects(), true, true)
                            : rootMeta.withKind(n.kind()).withWideningRank(-1);
                    yield new HirExpr.Const(n.literal(), m);
                }
                case OPAQUE -> leaves.get(n.symbol());
                case NOT -> {
                    HirExpr op = rebuild(tree.children().get(0), rootMeta);
                    yield new HirExpr.Unary(HirExpr.UnaryOp.NOT, op, "", rootMeta.withKind(PrimitiveKind.BOOL).withWideningRank(-1));
                }
                case NEG -> {
                    HirExpr op = rebuild(tree.children().get(0), rootMeta);
                    yield new HirExpr.Unary(HirExpr.UnaryOp.NEGATE, op, "", rootMeta.withKind(n.kind()).withWideningRank(-1));
                }
                case ADD, SUB, MUL, EQ, NEQ, LT, LTE, GT, GTE -> {
                    HirExpr l = rebuild(tree.children().get(0), rootMeta);
                    HirExpr r = rebuild(tree.children().get(1), rootMeta);
                    HirExpr.BinaryOp bop = switch (n.op()) {
                        case ADD -> HirExpr.BinaryOp.ADD;
                        case SUB -> HirExpr.BinaryOp.SUB;
                        case MUL -> HirExpr.BinaryOp.MUL;
                        case EQ -> HirExpr.BinaryOp.EQ;
                        case NEQ -> HirExpr.BinaryOp.NEQ;
                        case LT -> HirExpr.BinaryOp.LT;
                        case LTE -> HirExpr.BinaryOp.LTE;
                        case GT -> HirExpr.BinaryOp.GT;
                        case GTE -> HirExpr.BinaryOp.GTE;
                        default -> throw new IllegalStateException();
                    };
                    yield new HirExpr.Binary(bop, l, r, "", "", rootMeta.withKind(n.kind()).withWideningRank(-1));
                }
                case STR_ADD -> {
                    HirExpr l = rebuild(tree.children().get(0), rootMeta);
                    HirExpr r = rebuild(tree.children().get(1), rootMeta);
                    ExprMeta sm = new ExprMeta(rootMeta.line(), PrimitiveKind.NONE, -1, null, rootMeta.effects(), true, true);
                    yield new HirExpr.Binary(HirExpr.BinaryOp.ADD, l, r, "", "", sm);
                }
                case CALL -> {
                    HirExpr tmpl = callTemplates.get(n.symbol());
                    HirExpr arg = rebuild(tree.children().get(0), rootMeta);
                    if (tmpl instanceof HirExpr.DirectBabbleCall dc) {
                        yield new HirExpr.DirectBabbleCall(dc.callee(), dc.targetFunctionId(), dc.externalTarget(),
                                List.of(arg), dc.binding(), dc.meta());
                    }
                    HirExpr.JavaMethodCall jc = (HirExpr.JavaMethodCall) tmpl;
                    if (jc.target().isStatic()) {
                        yield new HirExpr.JavaMethodCall(jc.receiver(), jc.receiverText(), jc.selectorLine(),
                                jc.pureStaticReceiver(), jc.target(), List.of(arg), jc.meta());
                    }
                    yield new HirExpr.JavaMethodCall(arg, jc.receiverText(), jc.selectorLine(),
                            jc.pureStaticReceiver(), jc.target(), jc.args(), jc.meta());
                }
                default -> throw new IllegalStateException("Unexpected extracted op: " + n.op());
            };
        }
    }

    private static HirExpr tryEGraphSimplify(HirExpr root, HirModule module) {
        LowerContext lc = new LowerContext(module);
        int origCost = lc.treeCost(root);
        int rootId = lc.lower(root);
        lc.graph.saturate(AlgebraicRules.optimizerRules(), EGRAPH_MAX_ITERS, EGRAPH_MAX_NODES);
        EGraph.ExtractedTree best = lc.graph.extract(rootId, EGraph::defaultCost);
        int newCost = extractedCost(best);
        boolean canonicalizedConstRight = newCost == origCost
                && root instanceof HirExpr.Binary b
                && (b.op() == HirExpr.BinaryOp.ADD || b.op() == HirExpr.BinaryOp.MUL)
                && HirPurity.isIntegralKind(b.meta().kind())
                && HirRewriter.unwrapTransparent(b.left()) instanceof HirExpr.Const
                && !(HirRewriter.unwrapTransparent(b.right()) instanceof HirExpr.Const)
                && best.children().size() == 2
                && best.children().get(1).node().isConst()
                && !best.children().get(0).node().isConst();
        if (newCost < origCost || canonicalizedConstRight) {
            return lc.rebuild(best, root.meta());
        }
        return null;
    }

    private static int extractedCost(EGraph.ExtractedTree tree) {
        int sum = EGraph.defaultCost(tree.node());
        for (EGraph.ExtractedTree kid : tree.children()) {
            sum += extractedCost(kid);
        }
        return sum;
    }

    private static boolean isIdempotentBabbleEligible(HirExpr.DirectBabbleCall c, HirModule module) {
        if (c.args().size() != 1 || c.meta().checkedType() != null || c.meta().wideningRank() >= 0
                || (c.binding() != null && !c.binding().isIdentityPositional())) {
            return false;
        }
        if (!HirPurity.isIdempotentCall(c, module)) {
            return false;
        }
        return HirPurity.isPureNothrowTerminating(c.callee(), module)
                || (c.callee() instanceof HirExpr.NameRead nr
                && HirPurity.isDefinitelyInitializedTopLevelFn(nr.name(), module));
    }

    private static boolean sameDirectCallTarget(HirExpr.DirectBabbleCall a, HirExpr.DirectBabbleCall b) {
        if (a.externalTarget() != null || b.externalTarget() != null) {
            return a.externalTarget() != null && b.externalTarget() != null
                    && a.externalTarget().canonicalKey().equals(b.externalTarget().canonicalKey());
        }
        return a.targetFunctionId() == b.targetFunctionId();
    }

    private static boolean isIdempotentJavaEligible(HirExpr.JavaMethodCall c, HirModule module) {
        if (c.meta().wideningRank() >= 0 || !HirPurity.isIdempotentCall(c, module)) {
            return false;
        }
        boolean isWhitelistedStr = c.target().method().getDeclaringClass() == String.class
                && BuiltinAxioms.current().isFoldable(c.target().method());
        if (c.meta().checkedType() != null && !(isWhitelistedStr && HirPurity.isStringCheckedType(c.meta().checkedType()))) {
            return false;
        }
        return (c.target().isStatic() && c.pureStaticReceiver() && c.args().size() == 1)
                || (!c.target().isStatic() && c.args().isEmpty());
    }

    /** Single-node order-preserving identities safe even when an operand may throw or have effects. */
    private static HirExpr tryNonDroppingIdentity(HirExpr cur, HirModule module) {
        if (cur instanceof HirExpr.Unary u && u.op() == HirExpr.UnaryOp.NOT
                && u.meta().wideningRank() < 0 && u.meta().checkedType() == null) {
            HirExpr inner = HirRewriter.unwrapTransparent(u.operand());
            if (inner instanceof HirExpr.Unary innerNot && innerNot.op() == HirExpr.UnaryOp.NOT) {
                HirExpr b = HirRewriter.unwrapTransparent(innerNot.operand());
                if (b.meta().kind() == PrimitiveKind.BOOL && b.meta().wideningRank() < 0) {
                    return b;
                }
            }
            if (inner instanceof HirExpr.Binary b && b.op().isComparison()
                    && b.meta().wideningRank() < 0 && b.meta().checkedType() == null) {
                HirExpr.BinaryOp neg = switch (b.op()) {
                    case EQ -> HirExpr.BinaryOp.NEQ;
                    case NEQ -> HirExpr.BinaryOp.EQ;
                    case LT -> HirExpr.BinaryOp.GTE;
                    case LTE -> HirExpr.BinaryOp.GT;
                    case GT -> HirExpr.BinaryOp.LTE;
                    case GTE -> HirExpr.BinaryOp.LT;
                    default -> throw new IllegalStateException();
                };
                return new HirExpr.Binary(neg, b.left(), b.right(), b.leftText(), b.rightText(),
                        b.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
            }
        }
        if (cur instanceof HirExpr.Binary b && b.meta().checkedType() == null && b.meta().wideningRank() < 0) {
            PrimitiveKind rk = b.meta().kind();
            if (HirPurity.isIntegralKind(rk) && b.left().meta().kind() == rk && b.right().meta().kind() == rk
                    && b.left().meta().wideningRank() < 0 && b.right().meta().wideningRank() < 0) {
                Long lVal = asIntegral(HirRewriter.unwrapTransparent(b.left()));
                Long rVal = asIntegral(HirRewriter.unwrapTransparent(b.right()));
                if (b.op() == HirExpr.BinaryOp.ADD) {
                    if (rVal != null && rVal == 0L) return b.left();
                    if (lVal != null && lVal == 0L) return b.right();
                } else if (b.op() == HirExpr.BinaryOp.SUB) {
                    if (rVal != null && rVal == 0L) return b.left();
                    if (lVal != null && lVal == 0L) {
                        return new HirExpr.Unary(HirExpr.UnaryOp.NEGATE, b.right(), b.rightText(), b.meta());
                    }
                } else if (b.op() == HirExpr.BinaryOp.MUL) {
                    if (rVal != null && rVal == 1L) return b.left();
                    if (lVal != null && lVal == 1L) return b.right();
                }
            }
        }
        if (cur instanceof HirExpr.DirectBabbleCall outer && isIdempotentBabbleEligible(outer, module)) {
            HirExpr arg = HirRewriter.unwrapTransparent(outer.args().get(0));
            if (arg instanceof HirExpr.DirectBabbleCall inner
                    && sameDirectCallTarget(inner, outer)
                    && isIdempotentBabbleEligible(inner, module)) {
                return inner;
            }
        }
        if (cur instanceof HirExpr.JavaMethodCall outer && isIdempotentJavaEligible(outer, module)) {
            if (outer.target().isStatic()) {
                HirExpr arg = HirRewriter.unwrapTransparent(outer.args().get(0));
                if (arg instanceof HirExpr.JavaMethodCall inner && isIdempotentJavaEligible(inner, module)
                        && inner.target().isStatic() && inner.target().method().equals(outer.target().method())) {
                    return inner;
                }
            } else {
                HirExpr recv = HirRewriter.unwrapTransparent(outer.receiver());
                if (recv instanceof HirExpr.JavaMethodCall inner && isIdempotentJavaEligible(inner, module)
                        && !inner.target().isStatic() && inner.target().method().equals(outer.target().method())) {
                    return inner;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- constant folding & pruning

    private static HirExpr tryPruneIf(HirExpr.If i) {
        if (!(i.cond() instanceof HirExpr.Const condConst)) {
            return null;
        }
        boolean takeThen = Truth.test(condConst.value());
        HirExpr chosen = takeThen ? i.thenBranch() : i.elseBranch();
        ExprMeta ifMeta = i.meta();
        if (chosen == null) {
            if (ifMeta.kind() == PrimitiveKind.NONE && ifMeta.wideningRank() < 0) {
                return new HirExpr.Const(null, ifMeta);
            }
            return null;
        }
        HirExpr unwrapped = HirRewriter.unwrapTransparent(chosen);
        if (unwrapped instanceof HirExpr.Const c && chosen instanceof HirExpr.Block blk
                && !blk.opensNamespaceScope() && blk.declaredVars().isEmpty() && blk.innerWideningRank() < 0) {
            HirExpr.Const converted = convertConstToKind(c.value(), c.meta().kind(), ifMeta.kind(), ifMeta);
            if (converted != null) {
                return converted;
            }
        }
        if (chosen.meta().kind() == ifMeta.kind() && chosen.meta().wideningRank() < 0 && ifMeta.wideningRank() < 0) {
            return chosen;
        }
        if (chosen.meta().kind() == ifMeta.kind() && ifMeta.wideningRank() >= 0 && chosen instanceof HirExpr.Block b
                && b.innerWideningRank() < 0 && b.meta().wideningRank() < 0) {
            return new HirExpr.Block(b.statements(), b.opensNamespaceScope(), b.declaredNames(), b.declaredVars(),
                    -1, b.meta().withWideningRank(ifMeta.wideningRank()));
        }
        return null;
    }

    private static HirExpr tryFoldUnary(HirExpr.Unary u) {
        HirExpr op = HirRewriter.unwrapTransparent(u.operand());
        if (!(op instanceof HirExpr.Const c)) {
            return null;
        }
        if (u.op() == HirExpr.UnaryOp.NOT) {
            return new HirExpr.Const(!Truth.test(c.value()),
                    u.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
        }
        if (!(c.value() instanceof Number n)) {
            return null;
        }
        PrimitiveKind rk = u.meta().kind();
        Object negated = switch (rk) {
            case INT -> -n.intValue();
            case LONG -> -n.longValue();
            case FLOAT -> -n.floatValue();
            case DOUBLE -> -n.doubleValue();
            default -> null;
        };
        return negated != null ? applyWidening(negated, rk, u.meta()) : null;
    }

    private static HirExpr tryFoldBinary(HirExpr.Binary b) {
        HirExpr l = HirRewriter.unwrapTransparent(b.left());
        HirExpr r = HirRewriter.unwrapTransparent(b.right());
        if (!(l instanceof HirExpr.Const lc) || !(r instanceof HirExpr.Const rc) || b.meta().checkedType() != null) {
            return null;
        }
        if (b.op().isComparison() && HirPurity.canLowerComparison(b)) {
            PrimitiveKind lk = b.left().meta().kind();
            PrimitiveKind rk = b.right().meta().kind();
            int cmp;
            if (lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL
                    && lc.value() instanceof Boolean lb && rc.value() instanceof Boolean rb) {
                boolean eq = lb.booleanValue() == rb.booleanValue();
                boolean res = b.op() == HirExpr.BinaryOp.EQ ? eq : !eq;
                return new HirExpr.Const(res, b.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
            } else if (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR
                    && lc.value() instanceof Character lch && rc.value() instanceof Character rch) {
                cmp = Character.compare(lch, rch);
            } else if (lc.value() instanceof Number ln && rc.value() instanceof Number rn) {
                PrimitiveKind common = lk.numericRank() >= rk.numericRank() ? lk : rk;
                cmp = switch (common) {
                    case BYTE, SHORT, INT -> Integer.compare(ln.intValue(), rn.intValue());
                    case LONG -> Long.compare(ln.longValue(), rn.longValue());
                    case FLOAT -> Float.compare(ln.floatValue(), rn.floatValue());
                    case DOUBLE -> Double.compare(ln.doubleValue(), rn.doubleValue());
                    default -> throw new IllegalStateException();
                };
            } else {
                return null;
            }
            boolean res = switch (b.op()) {
                case LT -> cmp < 0;
                case LTE -> cmp <= 0;
                case EQ -> cmp == 0;
                case NEQ -> cmp != 0;
                case GTE -> cmp >= 0;
                case GT -> cmp > 0;
                default -> throw new IllegalStateException();
            };
            return new HirExpr.Const(res, b.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
        }

        if (b.op() == HirExpr.BinaryOp.ADD && b.left().meta().stringType() && b.right().meta().stringType()
                && lc.value() instanceof String ls && rc.value() instanceof String rs) {
            if (ls.length() + rs.length() <= EGraph.MAX_FOLDED_STRING_LENGTH) {
                return new HirExpr.Const(ls + rs,
                        new ExprMeta(b.meta().line(), PrimitiveKind.NONE, -1, null, b.meta().effects(), true, true));
            }
            return null;
        }

        PrimitiveKind rk = b.meta().kind();
        if (!rk.isPrimitive() || !(lc.value() instanceof Number ln) || !(rc.value() instanceof Number rn)
                || !HirPurity.isNumericKind(b.left().meta().kind()) || !HirPurity.isNumericKind(b.right().meta().kind())) {
            return null;
        }
        Object result = switch (rk) {
            case INT -> switch (b.op()) {
                case ADD -> ln.intValue() + rn.intValue();
                case SUB -> ln.intValue() - rn.intValue();
                case MUL -> ln.intValue() * rn.intValue();
                default -> null;
            };
            case LONG -> switch (b.op()) {
                case ADD -> ln.longValue() + rn.longValue();
                case SUB -> ln.longValue() - rn.longValue();
                case MUL -> ln.longValue() * rn.longValue();
                default -> null;
            };
            case FLOAT -> switch (b.op()) {
                case ADD -> ln.floatValue() + rn.floatValue();
                case SUB -> ln.floatValue() - rn.floatValue();
                case MUL -> ln.floatValue() * rn.floatValue();
                case DIV -> ln.floatValue() / rn.floatValue();
                default -> null;
            };
            case DOUBLE -> switch (b.op()) {
                case ADD -> ln.doubleValue() + rn.doubleValue();
                case SUB -> ln.doubleValue() - rn.doubleValue();
                case MUL -> ln.doubleValue() * rn.doubleValue();
                case DIV -> ln.doubleValue() / rn.doubleValue();
                default -> null;
            };
            default -> null;
        };
        return result != null ? applyWidening(result, rk, b.meta()) : null;
    }

    private static HirExpr tryFoldLogic(HirExpr.Logic l) {
        HirExpr left = HirRewriter.unwrapTransparent(l.left());
        if (!(left instanceof HirExpr.Const lc)) {
            return null;
        }
        boolean lv = Truth.test(lc.value());
        if (l.op() == HirExpr.LogicOp.AND) {
            if (!lv) {
                return new HirExpr.Const(false, l.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
            }
            return (l.right().meta().kind() == PrimitiveKind.BOOL && l.meta().wideningRank() < 0) ? l.right() : null;
        }
        if (lv) {
            return new HirExpr.Const(true, l.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
        }
        return (l.right().meta().kind() == PrimitiveKind.BOOL && l.meta().wideningRank() < 0) ? l.right() : null;
    }

    private static HirExpr tryFoldJavaCall(HirExpr.JavaMethodCall c) {
        Method m = c.target().method();
        if (c.target().isStatic() && c.pureStaticReceiver() && BuiltinAxioms.current().isFoldable(m)) {
            if (c.meta().checkedType() != null) {
                return null;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != c.args().size()) {
                return null;
            }
            Object[] jArgs = new Object[pts.length];
            for (int i = 0; i < pts.length; i++) {
                HirExpr arg = HirRewriter.unwrapTransparent(c.args().get(i));
                if (!(arg instanceof HirExpr.Const ac) || !(ac.value() instanceof Number n)) {
                    return null;
                }
                if (pts[i] == double.class) jArgs[i] = n.doubleValue();
                else if (pts[i] == float.class) jArgs[i] = n.floatValue();
                else if (pts[i] == int.class) jArgs[i] = n.intValue();
                else if (pts[i] == long.class) jArgs[i] = n.longValue();
                else return null;
            }
            try {
                Object res = m.invoke(null, jArgs);
                PrimitiveKind rk = PrimitiveKind.ofJavaClass(m.getReturnType());
                if (rk != PrimitiveKind.NONE && res != null) {
                    return applyWidening(res, rk, c.meta());
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        if (!c.target().isStatic() && BuiltinAxioms.current().isFoldable(m)
                && HirPurity.isStringCheckedType(c.meta().checkedType()) && c.args().isEmpty()) {
            HirExpr recv = HirRewriter.unwrapTransparent(c.receiver());
            if (!(recv instanceof HirExpr.Const rc) || !(rc.value() instanceof String s)) {
                return null;
            }
            try {
                Object res = m.invoke(s);
                if (res instanceof Integer iVal) {
                    return applyWidening(iVal, PrimitiveKind.INT, c.meta());
                }
                if (res instanceof Boolean bVal) {
                    return new HirExpr.Const(bVal, c.meta().withKind(PrimitiveKind.BOOL).withWideningRank(-1));
                }
                if (res instanceof String sVal) {
                    return new HirExpr.Const(sVal, new ExprMeta(c.meta().line(), PrimitiveKind.NONE,
                            -1, null, c.meta().effects(), true, true));
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long asIntegral(HirExpr expr) {
        if (expr instanceof HirExpr.Const c && c.value() instanceof Number n) {
            if (c.meta().kind() == PrimitiveKind.INT) return (long) n.intValue();
            if (c.meta().kind() == PrimitiveKind.LONG) return n.longValue();
        }
        return null;
    }

    private static HirExpr.Const coerceConstForVar(HirExpr.Const c, HirVar var, int defWideningRank) {
        PrimitiveKind vk = var.primitiveKind();
        Object val = c.value();
        PrimitiveKind ck = c.meta().kind();
        if (defWideningRank >= 0) {
            HirExpr.Const widened = applyWidening(val, ck, c.meta().withWideningRank(defWideningRank));
            if (widened == null) return null;
            val = widened.value();
            ck = widened.meta().kind();
        }
        if (vk.isPrimitive()) {
            return convertConstToKind(val, ck, vk, c.meta().withWideningRank(-1));
        }
        if (val == null || val instanceof String) {
            return new HirExpr.Const(val, c.meta().withKind(PrimitiveKind.NONE).withWideningRank(-1));
        }
        return null;
    }

    private static final PrimitiveKind[] WIDENING_KINDS = {
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT,
            PrimitiveKind.LONG, PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE
    };

    static HirExpr.Const applyWidening(Object value, PrimitiveKind currentKind, ExprMeta meta) {
        int rank = meta.wideningRank();
        if (rank < 0) {
            return new HirExpr.Const(value, meta.withKind(currentKind).withWideningRank(-1));
        }
        if (!(value instanceof Number n) || rank >= WIDENING_KINDS.length) {
            return null;
        }
        PrimitiveKind target = WIDENING_KINDS[rank];
        return new HirExpr.Const(convertNumber(n, target), meta.withKind(target).withWideningRank(-1));
    }

    private static HirExpr.Const convertConstToKind(Object val, PrimitiveKind from, PrimitiveKind to, ExprMeta meta) {
        if (from == to) {
            return applyWidening(val, to, meta);
        }
        if (to == PrimitiveKind.NONE && (val == null || val instanceof String)) {
            return new HirExpr.Const(val, meta.withKind(PrimitiveKind.NONE).withWideningRank(-1));
        }
        if (val instanceof Number n && HirPurity.isNumericKind(to)) {
            return applyWidening(convertNumber(n, to), to, meta);
        }
        return null;
    }

    private static Number convertNumber(Number n, PrimitiveKind to) {
        return switch (to) {
            case BYTE -> n.byteValue();
            case SHORT -> n.shortValue();
            case INT -> n.intValue();
            case LONG -> n.longValue();
            case FLOAT -> n.floatValue();
            default -> n.doubleValue();
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
}
