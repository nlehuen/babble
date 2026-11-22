package org.babblelang.opt;

import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirStaticBlock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Copy-on-write structural rewriter over immutable {@link HirExpr} and {@link HirModule} trees
 * (BIP014-P2).
 *
 * <p>Only allocates new record instances along rewritten paths; unchanged subtrees are shared by
 * reference.
 */
public final class HirRewriter {
    private HirRewriter() {
    }

    /** Rewrites all children of {@code expr} using {@code transform}, returning {@code expr} itself if none changed. */
    public static HirExpr rewriteChildren(HirExpr expr, UnaryOperator<HirExpr> transform) {
        if (expr == null) {
            return null;
        }
        return switch (expr) {
            case HirExpr.Const c -> c;
            case HirExpr.IntParseFallback f -> f;
            case HirExpr.CoreCollectionRef r -> r;
            case HirExpr.VarRead v -> v;
            case HirExpr.NameRead n -> n;
            case HirExpr.ThisRef t -> t;
            case HirExpr.RecurseRef r -> r;
            case HirExpr.Continue c -> c;
            case HirExpr.FunctionRef f -> f;
            case HirExpr.ClassExpr c -> c;
            case HirExpr.InterfaceExpr i -> i;
            case HirExpr.Def d -> {
                HirExpr nv = d.value() != null ? transform.apply(d.value()) : null;
                yield nv == d.value() ? d : new HirExpr.Def(d.name(), d.target(), d.var(), nv, d.meta());
            }
            case HirExpr.VarAssign a -> {
                HirExpr nv = transform.apply(a.value());
                yield nv == a.value() ? a : new HirExpr.VarAssign(a.var(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
            }
            case HirExpr.NameAssign a -> {
                HirExpr nv = transform.apply(a.value());
                yield nv == a.value() ? a : new HirExpr.NameAssign(a.name(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
            }
            case HirExpr.ThisFieldAssign a -> {
                HirExpr nr = transform.apply(a.receiver());
                HirExpr nv = transform.apply(a.value());
                yield (nr == a.receiver() && nv == a.value()) ? a
                        : new HirExpr.ThisFieldAssign(nr, a.classId(), a.memberIndex(), a.memberName(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
            }
            case HirExpr.JavaFieldAssign a -> {
                HirExpr nr = transform.apply(a.receiver());
                HirExpr nv = transform.apply(a.value());
                yield (nr == a.receiver() && nv == a.value()) ? a
                        : new HirExpr.JavaFieldAssign(nr, a.receiverText(), a.pureStaticReceiver(), a.target(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
            }
            case HirExpr.DynamicFieldAssign a -> {
                HirExpr nr = transform.apply(a.receiver());
                HirExpr nv = transform.apply(a.value());
                yield (nr == a.receiver() && nv == a.value()) ? a
                        : new HirExpr.DynamicFieldAssign(nr, a.receiverText(), a.memberName(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
            }
            case HirExpr.ThisFieldGet g -> {
                HirExpr nr = transform.apply(g.receiver());
                yield nr == g.receiver() ? g : new HirExpr.ThisFieldGet(nr, g.classId(), g.memberIndex(), g.memberName(), g.meta());
            }
            case HirExpr.JavaFieldGet g -> {
                HirExpr nr = transform.apply(g.receiver());
                yield nr == g.receiver() ? g : new HirExpr.JavaFieldGet(nr, g.receiverText(), g.pureStaticReceiver(), g.target(), g.meta());
            }
            case HirExpr.ArrayCreate c -> {
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield na == c.args() ? c : new HirExpr.ArrayCreate(
                        c.kind(), c.elemKind(), c.componentInternalName(), c.componentIsJavaInterface(),
                        c.componentClass(), na, c.meta());
            }
            case HirExpr.ArrayOp op -> {
                HirExpr nr = transform.apply(op.receiver());
                List<HirExpr> na = rewriteList(op.args(), transform);
                yield (nr == op.receiver() && na == op.args()) ? op
                        : new HirExpr.ArrayOp(op.kind(), nr, op.receiverText(), op.elemKind(),
                                op.arrayClass(), op.componentClass(), na, op.meta());
            }
            case HirExpr.ModuleExportGet g -> {
                HirExpr nr = transform.apply(g.receiver());
                yield nr == g.receiver() ? g : new HirExpr.ModuleExportGet(nr, g.target(), g.meta());
            }
            case HirExpr.DynamicSelect s -> {
                HirExpr nr = transform.apply(s.receiver());
                yield nr == s.receiver() ? s : new HirExpr.DynamicSelect(nr, s.receiverText(), s.memberName(), s.safe(), s.meta());
            }
            case HirExpr.Unary u -> {
                HirExpr no = transform.apply(u.operand());
                yield no == u.operand() ? u : new HirExpr.Unary(u.op(), no, u.operandText(), u.meta());
            }
            case HirExpr.Binary b -> {
                HirExpr nl = transform.apply(b.left());
                HirExpr nr = transform.apply(b.right());
                yield (nl == b.left() && nr == b.right()) ? b
                        : new HirExpr.Binary(b.op(), nl, nr, b.leftText(), b.rightText(), b.meta());
            }
            case HirExpr.Logic l -> {
                HirExpr nl = transform.apply(l.left());
                HirExpr nr = transform.apply(l.right());
                yield (nl == l.left() && nr == l.right()) ? l : new HirExpr.Logic(l.op(), nl, nr, l.meta());
            }
            case HirExpr.Is i -> {
                HirExpr nl = transform.apply(i.left());
                yield nl == i.left() ? i : new HirExpr.Is(nl, i.builtin(), i.target(), i.descriptor(), i.meta());
            }
            case HirExpr.As a -> {
                HirExpr nl = transform.apply(a.left());
                yield nl == a.left() ? a : new HirExpr.As(nl, a.target(), a.targetDescriptor(), a.meta());
            }
            case HirExpr.NonNull n -> {
                HirExpr no = transform.apply(n.operand());
                yield no == n.operand() ? n : new HirExpr.NonNull(no, n.expectedType(), n.meta());
            }
            case HirExpr.SpreadAdapt s -> {
                HirExpr nv = transform.apply(s.value());
                yield nv == s.value() ? s : new HirExpr.SpreadAdapt(nv, s.adapt(), s.meta());
            }
            case HirExpr.TypeApply t -> {
                HirExpr ni = transform.apply(t.inner());
                yield ni == t.inner() ? t : new HirExpr.TypeApply(ni, t.meta());
            }
            case HirExpr.Block b -> {
                List<HirExpr> ns = rewriteList(b.statements(), transform);
                yield ns == b.statements() ? b
                        : new HirExpr.Block(ns, b.opensNamespaceScope(), b.declaredNames(), b.declaredVars(), b.innerWideningRank(), b.meta());
            }
            case HirExpr.If i -> {
                HirExpr nc = transform.apply(i.cond());
                HirExpr nt = transform.apply(i.thenBranch());
                HirExpr ne = i.elseBranch() != null ? transform.apply(i.elseBranch()) : null;
                yield (nc == i.cond() && nt == i.thenBranch() && ne == i.elseBranch()) ? i
                        : new HirExpr.If(nc, nt, ne, i.meta());
            }
            case HirExpr.While w -> {
                HirExpr nc = transform.apply(w.cond());
                HirExpr nb = transform.apply(w.body());
                yield (nc == w.cond() && nb == w.body()) ? w : new HirExpr.While(nc, nb, w.meta());
            }
            case HirExpr.For f -> {
                HirExpr ni = transform.apply(f.iterable());
                List<HirExpr> nb = rewriteList(f.body(), transform);
                yield (ni == f.iterable() && nb == f.body()) ? f
                        : new HirExpr.For(ni, f.target(), f.loopVar(), f.bodyDeclaredVars(), nb, f.bodyWideningRank(), f.meta());
            }
            case HirExpr.Break b -> {
                HirExpr nv = b.value() != null ? transform.apply(b.value()) : null;
                yield nv == b.value() ? b : new HirExpr.Break(nv, b.meta());
            }
            case HirExpr.Return r -> {
                HirExpr nv = transform.apply(r.value());
                yield nv == r.value() ? r : new HirExpr.Return(nv, r.meta());
            }
            case HirExpr.CoreCollectionCall c -> {
                List<HirCallArg> na = rewriteCallArgs(c.args(), transform);
                yield na == c.args() ? c : new HirExpr.CoreCollectionCall(c.collectionKind(), na, c.meta());
            }
            case HirExpr.SuperCall c -> {
                List<HirCallArg> na = rewriteCallArgs(c.args(), transform);
                yield na == c.args() ? c
                        : new HirExpr.SuperCall(c.thisVar(), c.ownerClassId(), c.memberName(), c.targetInfo(), c.superTypeRef(), na, c.binding(), c.callSite(), c.meta());
            }
            case HirExpr.SelfRecurseCall c -> {
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield na == c.args() ? c : new HirExpr.SelfRecurseCall(c.functionId(), na, c.binding(), c.meta());
            }
            case HirExpr.DirectBabbleCall c -> {
                HirExpr nc = transform.apply(c.callee());
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield (nc == c.callee() && na == c.args()) ? c
                        : new HirExpr.DirectBabbleCall(nc, c.targetFunctionId(), na, c.binding(), c.meta());
            }
            case HirExpr.DirectThisMethodCall c -> {
                HirExpr nr = transform.apply(c.receiver());
                List<HirCallArg> na = rewriteCallArgs(c.args(), transform);
                yield (nr == c.receiver() && na == c.args()) ? c
                        : new HirExpr.DirectThisMethodCall(nr, c.classId(), c.memberName(), c.targetFunctionId(), na, c.binding(), c.callSite(), c.meta());
            }
            case HirExpr.InterfaceCall c -> {
                HirExpr nr = transform.apply(c.receiver());
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield (nr == c.receiver() && na == c.args()) ? c
                        : new HirExpr.InterfaceCall(nr, c.receiverText(), c.selectorLine(), c.interfaceTypeId(), c.memberName(), c.signature(), na, c.binding(), c.meta());
            }
            case HirExpr.JavaConstructorCall c -> {
                HirExpr nc = transform.apply(c.callee());
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield (nc == c.callee() && na == c.args()) ? c
                        : new HirExpr.JavaConstructorCall(nc, c.calleeText(), c.pureStaticCallee(), c.target(), na, c.meta());
            }
            case HirExpr.JavaMethodCall c -> {
                HirExpr nr = transform.apply(c.receiver());
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield (nr == c.receiver() && na == c.args()) ? c
                        : new HirExpr.JavaMethodCall(nr, c.receiverText(), c.selectorLine(), c.pureStaticReceiver(), c.target(), na, c.meta());
            }
            case HirExpr.SafeCall c -> {
                HirExpr nr = transform.apply(c.receiver());
                List<HirCallArg> na = rewriteCallArgs(c.args(), transform);
                yield (nr == c.receiver() && na == c.args()) ? c
                        : new HirExpr.SafeCall(nr, c.receiverText(), c.memberName(), c.selectorLine(), c.selectorCheckedType(), na, c.binding(), c.babbleSig(), c.callSite(), c.meta());
            }
            case HirExpr.DynamicCall c -> {
                HirExpr nc = transform.apply(c.callee());
                List<HirCallArg> na = rewriteCallArgs(c.args(), transform);
                yield (nc == c.callee() && na == c.args()) ? c
                        : new HirExpr.DynamicCall(nc, na, c.binding(), c.babbleSig(), c.callSite(), c.meta());
            }
            case HirExpr.MakeOverloadSet m -> {
                List<HirExpr> no = rewriteList(m.operands(), transform);
                yield no == m.operands() ? m : new HirExpr.MakeOverloadSet(no, m.metas(), m.meta());
            }
            case HirExpr.OverloadSelect s -> {
                HirExpr ns = transform.apply(s.set());
                yield ns == s.set() ? s : new HirExpr.OverloadSelect(ns, s.index(), s.signatureKey(), s.exactSet(), s.meta());
            }
            case HirExpr.OverloadDispatch d -> {
                HirExpr nc = transform.apply(d.callee());
                List<HirExpr> na = rewriteList(d.args(), transform);
                yield (nc == d.callee() && na == d.args()) ? d
                        : new HirExpr.OverloadDispatch(nc, d.calleeText(), d.callSite(), na, d.branches(), d.meta());
            }
        };
    }

    public static List<HirExpr> rewriteList(List<HirExpr> list, UnaryOperator<HirExpr> transform) {
        List<HirExpr> copy = null;
        for (int i = 0; i < list.size(); i++) {
            HirExpr old = list.get(i);
            HirExpr nw = transform.apply(old);
            if (nw != old) {
                if (copy == null) {
                    copy = new ArrayList<>(list);
                }
                copy.set(i, nw);
            }
        }
        return copy != null ? copy : list;
    }

    public static List<HirCallArg> rewriteCallArgs(List<HirCallArg> list, UnaryOperator<HirExpr> transform) {
        List<HirCallArg> copy = null;
        for (int i = 0; i < list.size(); i++) {
            HirCallArg old = list.get(i);
            HirExpr nw = transform.apply(old.value());
            if (nw != old.value()) {
                if (copy == null) {
                    copy = new ArrayList<>(list);
                }
                copy.set(i, old.withValue(nw));
            }
        }
        return copy != null ? copy : list;
    }

    public static List<HirParam> rewriteParams(List<HirParam> params, UnaryOperator<HirExpr> transform) {
        List<HirParam> copy = null;
        for (int i = 0; i < params.size(); i++) {
            HirParam old = params.get(i);
            if (old.defaultValue() != null) {
                HirExpr nw = transform.apply(old.defaultValue());
                if (nw != old.defaultValue()) {
                    if (copy == null) {
                        copy = new ArrayList<>(params);
                    }
                    copy.set(i, old.withDefaultValue(nw));
                }
            }
        }
        return copy != null ? copy : params;
    }

    /** Applies a statement-list rewriter across every body in {@code module}. */
    public static HirModule rewriteModuleBodies(HirModule module, UnaryOperator<List<HirExpr>> stmtListRewriter) {
        List<HirExpr> newTop = stmtListRewriter.apply(module.topLevelStatements());
        boolean changed = newTop != module.topLevelStatements();

        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            List<HirParam> np = rewriteParams(fn.params(), ex -> {
                List<HirExpr> r = stmtListRewriter.apply(List.of(ex));
                return r.size() == 1 ? r.get(0) : ex;
            });
            List<HirExpr> nb = stmtListRewriter.apply(fn.body());
            if (np != fn.params() || nb != fn.body()) {
                changed = true;
                fn = fn.withParamsAndBody(np, nb);
            }
            newFns.put(e.getKey(), fn);
        }

        Map<Integer, HirClass> newClasses = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirClass> e : module.classes().entrySet()) {
            HirClass cls = e.getValue();
            List<HirParam> np = rewriteParams(cls.params(), ex -> {
                List<HirExpr> r = stmtListRewriter.apply(List.of(ex));
                return r.size() == 1 ? r.get(0) : ex;
            });
            List<HirExpr> nb = stmtListRewriter.apply(cls.body());
            HirStaticBlock ns = cls.staticBlock();
            if (ns != null) {
                List<HirExpr> nsb = stmtListRewriter.apply(ns.body());
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
            List<HirExpr> nb = stmtListRewriter.apply(iface.body());
            HirStaticBlock ns = iface.staticBlock();
            if (ns != null) {
                List<HirExpr> nsb = stmtListRewriter.apply(ns.body());
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
            return module;
        }
        return module.withUpdates(newTop, newFns, newClasses, newIfaces, module.nextVarId(), module.nextOwnerId());
    }
}
