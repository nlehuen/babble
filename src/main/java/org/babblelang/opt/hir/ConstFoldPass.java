package org.babblelang.opt.hir;

import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.ir.hir.ExprMeta;
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
import org.babblelang.runtime.Truth;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code O1} — Constant Folding, Intra-Function Constant Propagation, Constant Branch Pruning,
 * and Whitelisted Pure-JDK Call Folding (BIP014-I2, BIP014-D4).
 */
public final class ConstFoldPass implements HirPass {
    private static final int MAX_FOLDED_STRING_LENGTH = 4096;

    @Override
    public String name() {
        return "ConstFold";
    }

    @Override
    public Result run(HirModule module, PassContext context) {
        int[] rewrites = {0};

        List<HirExpr> newTop = foldBody(module.topLevelStatements(), rewrites);
        boolean changed = newTop != module.topLevelStatements();

        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            List<HirParam> np = foldParams(fn.params(), rewrites);
            List<HirExpr> nb = foldBody(fn.body(), rewrites);
            if (np != fn.params() || nb != fn.body()) {
                changed = true;
                fn = fn.withParamsAndBody(np, nb);
            }
            newFns.put(e.getKey(), fn);
        }

        Map<Integer, HirClass> newClasses = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirClass> e : module.classes().entrySet()) {
            HirClass cls = e.getValue();
            List<HirParam> np = foldParams(cls.params(), rewrites);
            List<HirExpr> nb = foldBody(cls.body(), rewrites);
            HirStaticBlock ns = cls.staticBlock();
            if (ns != null) {
                List<HirExpr> nsb = foldBody(ns.body(), rewrites);
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
            List<HirExpr> nb = foldBody(iface.body(), rewrites);
            HirStaticBlock ns = iface.staticBlock();
            if (ns != null) {
                List<HirExpr> nsb = foldBody(ns.body(), rewrites);
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

    private List<HirParam> foldParams(List<HirParam> params, int[] rewrites) {
        Set<Integer> emptyAssigned = Set.of();
        return HirRewriter.rewriteParams(params, expr -> foldExpr(expr, Map.of(), emptyAssigned, rewrites));
    }

    private List<HirExpr> foldBody(List<HirExpr> stmts, int[] rewrites) {
        Set<Integer> assigned = new HashSet<>();
        for (HirExpr s : stmts) {
            collectAssignedVars(s, assigned);
        }
        return foldStatements(stmts, new HashMap<>(), assigned, rewrites);
    }

    private List<HirExpr> foldStatements(List<HirExpr> stmts, Map<Integer, HirExpr.Const> env,
                                         Set<Integer> assigned, int[] rewrites) {
        List<HirExpr> copy = null;
        for (int i = 0; i < stmts.size(); i++) {
            HirExpr old = stmts.get(i);
            HirExpr nw = foldExpr(old, env, assigned, rewrites);
            if (nw != old) {
                if (copy == null) {
                    copy = new ArrayList<>(stmts);
                }
                copy.set(i, nw);
            }
            if (nw instanceof HirExpr.Def d && d.var() != null && !d.var().cell()
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

    private HirExpr foldExpr(HirExpr expr, Map<Integer, HirExpr.Const> env, Set<Integer> assigned, int[] rewrites) {
        if (expr == null) {
            return null;
        }
        // Scope-aware recursion for Block, If, While, For
        if (expr instanceof HirExpr.Block b) {
            Map<Integer, HirExpr.Const> innerEnv = new HashMap<>(env);
            List<HirExpr> ns = foldStatements(b.statements(), innerEnv, assigned, rewrites);
            HirExpr.Block nb = ns == b.statements() ? b
                    : new HirExpr.Block(ns, b.opensNamespaceScope(), b.declaredNames(), b.declaredVars(), b.innerWideningRank(), b.meta());
            if (nb.isTransparentSingleExpr() && nb.statements().get(0) instanceof HirExpr.Const c) {
                rewrites[0]++;
                return c;
            }
            return nb;
        }
        if (expr instanceof HirExpr.If i) {
            HirExpr nc = foldExpr(i.cond(), env, assigned, rewrites);
            HirExpr nt = foldExpr(i.thenBranch(), new HashMap<>(env), assigned, rewrites);
            HirExpr ne = i.elseBranch() != null ? foldExpr(i.elseBranch(), new HashMap<>(env), assigned, rewrites) : null;
            HirExpr.If rebuilt = (nc == i.cond() && nt == i.thenBranch() && ne == i.elseBranch()) ? i
                    : new HirExpr.If(nc, nt, ne, i.meta());
            HirExpr pruned = tryPruneIf(rebuilt);
            if (pruned != null) {
                rewrites[0]++;
                return pruned;
            }
            return rebuilt;
        }
        if (expr instanceof HirExpr.While w) {
            HirExpr nc = foldExpr(w.cond(), env, assigned, rewrites);
            if (nc instanceof HirExpr.Const c && !Truth.test(c.value())) {
                rewrites[0]++;
                return new HirExpr.Const(null, w.meta().withKind(ScopeAnalysis.PrimitiveKind.NONE).withWideningRank(-1));
            }
            HirExpr nb = foldExpr(w.body(), new HashMap<>(env), assigned, rewrites);
            return (nc == w.cond() && nb == w.body()) ? w : new HirExpr.While(nc, nb, w.meta());
        }
        if (expr instanceof HirExpr.For f) {
            HirExpr ni = foldExpr(f.iterable(), env, assigned, rewrites);
            List<HirExpr> nb = foldStatements(f.body(), new HashMap<>(env), assigned, rewrites);
            return (ni == f.iterable() && nb == f.body()) ? f
                    : new HirExpr.For(ni, f.target(), f.loopVar(), f.bodyDeclaredVars(), nb, f.bodyWideningRank(), f.meta());
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
        HirExpr cur = HirRewriter.rewriteChildren(expr, child -> foldExpr(child, env, assigned, rewrites));

        // Canonicalize Const with pending wideningRank
        if (cur instanceof HirExpr.Const c && c.meta().wideningRank() >= 0) {
            HirExpr.Const widened = applyWidening(c.value(), c.meta().kind(), c.meta());
            if (widened != null) {
                rewrites[0]++;
                return widened;
            }
        }

        // Fold Unary
        if (cur instanceof HirExpr.Unary u) {
            HirExpr folded = tryFoldUnary(u);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }

        // Fold Binary
        if (cur instanceof HirExpr.Binary b) {
            HirExpr folded = tryFoldBinary(b);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }

        // Fold Logic (short-circuit)
        if (cur instanceof HirExpr.Logic l) {
            HirExpr folded = tryFoldLogic(l);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }

        // Fold whitelisted pure deterministic JDK calls (Math.*, String.*)
        if (cur instanceof HirExpr.JavaMethodCall jm) {
            HirExpr folded = tryFoldJavaCall(jm);
            if (folded != null) {
                rewrites[0]++;
                return folded;
            }
        }

        return cur;
    }

    private static HirExpr tryPruneIf(HirExpr.If i) {
        if (!(i.cond() instanceof HirExpr.Const condConst)) {
            return null;
        }
        boolean takeThen = Truth.test(condConst.value());
        HirExpr chosen = takeThen ? i.thenBranch() : i.elseBranch();
        ExprMeta ifMeta = i.meta();
        if (chosen == null) {
            if (ifMeta.kind() == ScopeAnalysis.PrimitiveKind.NONE && ifMeta.wideningRank() < 0) {
                return new HirExpr.Const(null, ifMeta);
            }
            return null;
        }
        // Unwrap single-expression non-scoping block if it yields a constant
        HirExpr unwrapped = unwrapTransparent(chosen);
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
        HirExpr op = unwrapTransparent(u.operand());
        if (!(op instanceof HirExpr.Const c)) {
            return null;
        }
        if (u.op() == HirExpr.UnaryOp.NOT) {
            return new HirExpr.Const(!Truth.test(c.value()),
                    u.meta().withKind(ScopeAnalysis.PrimitiveKind.BOOL).withWideningRank(-1));
        }
        // NEGATE
        if (!(c.value() instanceof Number n)) {
            return null;
        }
        ScopeAnalysis.PrimitiveKind rk = u.meta().kind();
        Object negated = switch (rk) {
            case INT -> -n.intValue();
            case LONG -> -n.longValue();
            case FLOAT -> -n.floatValue();
            case DOUBLE -> -n.doubleValue();
            default -> null;
        };
        if (negated == null) {
            return null;
        }
        return applyWidening(negated, rk, u.meta());
    }

    private static HirExpr tryFoldBinary(HirExpr.Binary b) {
        HirExpr l = unwrapTransparent(b.left());
        HirExpr r = unwrapTransparent(b.right());
        if (!(l instanceof HirExpr.Const lc) || !(r instanceof HirExpr.Const rc)) {
            return null;
        }
        if (b.meta().checkedType() != null) {
            return null;
        }
        if (b.op().isComparison() && HirPurity.canLowerComparison(b)) {
            ScopeAnalysis.PrimitiveKind lk = b.left().meta().kind();
            ScopeAnalysis.PrimitiveKind rk = b.right().meta().kind();
            int cmp;
            if (lk == ScopeAnalysis.PrimitiveKind.BOOL && rk == ScopeAnalysis.PrimitiveKind.BOOL
                    && lc.value() instanceof Boolean lb && rc.value() instanceof Boolean rb) {
                boolean eq = lb.booleanValue() == rb.booleanValue();
                boolean res = b.op() == HirExpr.BinaryOp.EQ ? eq : !eq;
                return new HirExpr.Const(res, b.meta().withKind(ScopeAnalysis.PrimitiveKind.BOOL).withWideningRank(-1));
            } else if (lk == ScopeAnalysis.PrimitiveKind.CHAR && rk == ScopeAnalysis.PrimitiveKind.CHAR
                    && lc.value() instanceof Character lch && rc.value() instanceof Character rch) {
                cmp = Character.compare(lch, rch);
            } else if (lc.value() instanceof Number ln && rc.value() instanceof Number rn) {
                ScopeAnalysis.PrimitiveKind common = numericOrder(lk) >= numericOrder(rk) ? lk : rk;
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
            return new HirExpr.Const(res, b.meta().withKind(ScopeAnalysis.PrimitiveKind.BOOL).withWideningRank(-1));
        }

        // String concatenation
        if (b.op() == HirExpr.BinaryOp.ADD && b.left().meta().stringType() && b.right().meta().stringType()
                && lc.value() instanceof String ls && rc.value() instanceof String rs) {
            if (ls.length() + rs.length() <= MAX_FOLDED_STRING_LENGTH) {
                return new HirExpr.Const(ls + rs,
                        new ExprMeta(b.meta().line(), ScopeAnalysis.PrimitiveKind.NONE, -1, null,
                                b.meta().effects(), true, true));
            }
            return null;
        }

        // Numeric primitive arithmetic
        ScopeAnalysis.PrimitiveKind rk = b.meta().kind();
        if (!rk.isPrimitive() || !(lc.value() instanceof Number ln) || !(rc.value() instanceof Number rn)) {
            return null;
        }
        if (!HirPurity.isNumericKind(b.left().meta().kind()) || !HirPurity.isNumericKind(b.right().meta().kind())) {
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
        if (result == null) {
            return null;
        }
        return applyWidening(result, rk, b.meta());
    }

    private static HirExpr tryFoldLogic(HirExpr.Logic l) {
        HirExpr left = unwrapTransparent(l.left());
        if (!(left instanceof HirExpr.Const lc)) {
            return null;
        }
        boolean lv = Truth.test(lc.value());
        if (l.op() == HirExpr.LogicOp.AND) {
            if (!lv) {
                return new HirExpr.Const(false, l.meta().withKind(ScopeAnalysis.PrimitiveKind.BOOL).withWideningRank(-1));
            }
            if (l.right().meta().kind() == ScopeAnalysis.PrimitiveKind.BOOL && l.meta().wideningRank() < 0) {
                return l.right();
            }
            return null;
        }
        // OR
        if (lv) {
            return new HirExpr.Const(true, l.meta().withKind(ScopeAnalysis.PrimitiveKind.BOOL).withWideningRank(-1));
        }
        if (l.right().meta().kind() == ScopeAnalysis.PrimitiveKind.BOOL && l.meta().wideningRank() < 0) {
            return l.right();
        }
        return null;
    }

    private static HirExpr tryFoldJavaCall(HirExpr.JavaMethodCall c) {
        Method m = c.target().method();
        if (c.target().isStatic() && c.pureStaticReceiver()
                && org.babblelang.analysis.BuiltinAxioms.current().isFoldable(m)) {
            if (c.meta().checkedType() != null) {
                return null;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != c.args().size()) {
                return null;
            }
            Object[] jArgs = new Object[pts.length];
            for (int i = 0; i < pts.length; i++) {
                HirExpr arg = unwrapTransparent(c.args().get(i));
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
                ScopeAnalysis.PrimitiveKind rk = javaRetKind(m.getReturnType());
                if (rk != ScopeAnalysis.PrimitiveKind.NONE && res != null) {
                    return applyWidening(res, rk, c.meta());
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        if (!c.target().isStatic()
                && org.babblelang.analysis.BuiltinAxioms.current().isFoldable(m)) {
            if (!HirPurity.isStringCheckedType(c.meta().checkedType())) {
                return null;
            }
            HirExpr recv = unwrapTransparent(c.receiver());
            if (!(recv instanceof HirExpr.Const rc) || !(rc.value() instanceof String s)) {
                return null;
            }
            if (!c.args().isEmpty()) {
                return null;
            }
            try {
                Object res = m.invoke(s);
                if (res instanceof Integer iVal) {
                    return applyWidening(iVal, ScopeAnalysis.PrimitiveKind.INT, c.meta());
                }
                if (res instanceof Boolean bVal) {
                    return new HirExpr.Const(bVal, c.meta().withKind(ScopeAnalysis.PrimitiveKind.BOOL).withWideningRank(-1));
                }
                if (res instanceof String sVal) {
                    return new HirExpr.Const(sVal, new ExprMeta(c.meta().line(), ScopeAnalysis.PrimitiveKind.NONE,
                            -1, null, c.meta().effects(), true, true));
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static ScopeAnalysis.PrimitiveKind javaRetKind(Class<?> ret) {
        if (ret == int.class) return ScopeAnalysis.PrimitiveKind.INT;
        if (ret == long.class) return ScopeAnalysis.PrimitiveKind.LONG;
        if (ret == float.class) return ScopeAnalysis.PrimitiveKind.FLOAT;
        if (ret == double.class) return ScopeAnalysis.PrimitiveKind.DOUBLE;
        if (ret == boolean.class) return ScopeAnalysis.PrimitiveKind.BOOL;
        if (ret == byte.class) return ScopeAnalysis.PrimitiveKind.BYTE;
        if (ret == short.class) return ScopeAnalysis.PrimitiveKind.SHORT;
        if (ret == char.class) return ScopeAnalysis.PrimitiveKind.CHAR;
        return ScopeAnalysis.PrimitiveKind.NONE;
    }

    private static HirExpr.Const coerceConstForVar(HirExpr.Const c, HirVar var, int defWideningRank) {
        ScopeAnalysis.PrimitiveKind vk = var.primitiveKind();
        Object val = c.value();
        ScopeAnalysis.PrimitiveKind ck = c.meta().kind();
        if (defWideningRank >= 0) {
            HirExpr.Const widened = applyWidening(val, ck, c.meta().withWideningRank(defWideningRank));
            if (widened == null) return null;
            val = widened.value();
            ck = widened.meta().kind();
        }
        if (vk.isPrimitive()) {
            return convertConstToKind(val, ck, vk, c.meta().withWideningRank(-1));
        }
        // Only propagate NONE-kind constants (String, null) to NONE-kind locals so primitive boxing is never altered.
        if (val == null || val instanceof String) {
            return new HirExpr.Const(val, c.meta().withKind(ScopeAnalysis.PrimitiveKind.NONE).withWideningRank(-1));
        }
        return null;
    }

    static HirExpr.Const applyWidening(Object value, ScopeAnalysis.PrimitiveKind currentKind, ExprMeta meta) {
        int rank = meta.wideningRank();
        if (rank < 0) {
            return new HirExpr.Const(value, meta.withKind(currentKind).withWideningRank(-1));
        }
        if (!(value instanceof Number n)) {
            return null;
        }
        return switch (rank) {
            case 1 -> new HirExpr.Const(n.shortValue(), meta.withKind(ScopeAnalysis.PrimitiveKind.SHORT).withWideningRank(-1));
            case 2 -> new HirExpr.Const(n.intValue(), meta.withKind(ScopeAnalysis.PrimitiveKind.INT).withWideningRank(-1));
            case 3 -> new HirExpr.Const(n.longValue(), meta.withKind(ScopeAnalysis.PrimitiveKind.LONG).withWideningRank(-1));
            case 4 -> new HirExpr.Const(n.floatValue(), meta.withKind(ScopeAnalysis.PrimitiveKind.FLOAT).withWideningRank(-1));
            case 5 -> new HirExpr.Const(n.doubleValue(), meta.withKind(ScopeAnalysis.PrimitiveKind.DOUBLE).withWideningRank(-1));
            default -> null;
        };
    }

    private static HirExpr.Const convertConstToKind(Object val, ScopeAnalysis.PrimitiveKind from,
                                                    ScopeAnalysis.PrimitiveKind to, ExprMeta meta) {
        if (from == to) {
            return applyWidening(val, to, meta);
        }
        if (to == ScopeAnalysis.PrimitiveKind.NONE && (val == null || val instanceof String)) {
            return new HirExpr.Const(val, meta.withKind(ScopeAnalysis.PrimitiveKind.NONE).withWideningRank(-1));
        }
        if (val instanceof Number n && HirPurity.isNumericKind(to)) {
            Object conv = switch (to) {
                case BYTE -> n.byteValue();
                case SHORT -> n.shortValue();
                case INT -> n.intValue();
                case LONG -> n.longValue();
                case FLOAT -> n.floatValue();
                case DOUBLE -> n.doubleValue();
                default -> null;
            };
            if (conv != null) {
                return applyWidening(conv, to, meta);
            }
        }
        return null;
    }

    static HirExpr unwrapTransparent(HirExpr expr) {
        while (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
            expr = b.statements().get(0);
        }
        return expr;
    }

    private static int numericOrder(ScopeAnalysis.PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> 0;
            case SHORT -> 1;
            case INT -> 2;
            case LONG -> 3;
            case FLOAT -> 4;
            case DOUBLE -> 5;
            default -> -1;
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
