package org.babblelang.opt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirPattern;
import org.babblelang.ir.hir.HirStaticBlock;
import org.babblelang.ir.hir.HirVar;

/**
 * Copy-on-write structural rewriter over immutable {@link HirExpr} and {@link HirModule} trees
 * (BIP014-I2).
 *
 * <p>Only allocates new record instances along rewritten paths; unchanged subtrees are shared by
 * reference.
 */
public final class HirRewriter {
    private HirRewriter() {}

    /**
     * Rewrites all children of {@code expr} using {@code transform}, returning {@code expr} itself
     * if none changed.
     */
    public static HirExpr rewriteChildren(HirExpr expr, UnaryOperator<HirExpr> transform) {
        if (expr == null) {
            return null;
        }
        return switch (expr) {
            case HirExpr.Const c -> c;
            case HirExpr.IntParseFallback f -> f;
            case HirExpr.CoreCollectionRef r -> r;
            case HirExpr.CoreExceptionRef r -> r;
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
                yield nv == d.value()
                        ? d
                        : new HirExpr.Def(d.name(), d.target(), d.var(), nv, d.meta());
            }
            case HirExpr.VarAssign a -> {
                HirExpr nv = transform.apply(a.value());
                yield nv == a.value()
                        ? a
                        : new HirExpr.VarAssign(
                                a.var(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
            }
            case HirExpr.NameAssign a -> {
                HirExpr nv = transform.apply(a.value());
                yield nv == a.value()
                        ? a
                        : new HirExpr.NameAssign(
                                a.name(),
                                a.operator(),
                                nv,
                                a.targetText(),
                                a.valueText(),
                                a.meta());
            }
            case HirExpr.ThisFieldAssign a -> {
                HirExpr nr = transform.apply(a.receiver());
                HirExpr nv = transform.apply(a.value());
                yield (nr == a.receiver() && nv == a.value())
                        ? a
                        : new HirExpr.ThisFieldAssign(
                                nr,
                                a.classId(),
                                a.memberIndex(),
                                a.memberName(),
                                a.operator(),
                                nv,
                                a.targetText(),
                                a.valueText(),
                                a.meta());
            }
            case HirExpr.JavaFieldAssign a -> {
                HirExpr nr = transform.apply(a.receiver());
                HirExpr nv = transform.apply(a.value());
                yield (nr == a.receiver() && nv == a.value())
                        ? a
                        : new HirExpr.JavaFieldAssign(
                                nr,
                                a.receiverText(),
                                a.pureStaticReceiver(),
                                a.target(),
                                a.operator(),
                                nv,
                                a.targetText(),
                                a.valueText(),
                                a.meta());
            }
            case HirExpr.DynamicFieldAssign a -> {
                HirExpr nr = transform.apply(a.receiver());
                HirExpr nv = transform.apply(a.value());
                yield (nr == a.receiver() && nv == a.value())
                        ? a
                        : new HirExpr.DynamicFieldAssign(
                                nr,
                                a.receiverText(),
                                a.memberName(),
                                a.operator(),
                                nv,
                                a.targetText(),
                                a.valueText(),
                                a.meta());
            }
            case HirExpr.ThisFieldGet g -> {
                HirExpr nr = transform.apply(g.receiver());
                yield nr == g.receiver()
                        ? g
                        : new HirExpr.ThisFieldGet(
                                nr,
                                g.receiverText(),
                                g.classId(),
                                g.externalClassJvmName(),
                                g.memberIndex(),
                                g.memberName(),
                                g.fieldKind(),
                                g.immutableField(),
                                g.definitelyDefined(),
                                g.meta());
            }
            case HirExpr.JavaFieldGet g -> {
                HirExpr nr = transform.apply(g.receiver());
                yield nr == g.receiver()
                        ? g
                        : new HirExpr.JavaFieldGet(
                                nr, g.receiverText(), g.pureStaticReceiver(), g.target(), g.meta());
            }
            case HirExpr.ArrayCreate c -> {
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield na == c.args()
                        ? c
                        : new HirExpr.ArrayCreate(
                                c.kind(),
                                c.elemKind(),
                                c.elementType(),
                                c.componentInternalName(),
                                c.componentIsJavaInterface(),
                                c.componentClass(),
                                na,
                                c.meta());
            }
            case HirExpr.ArrayOp op -> {
                HirExpr nr = transform.apply(op.receiver());
                List<HirExpr> na = rewriteList(op.args(), transform);
                yield (nr == op.receiver() && na == op.args())
                        ? op
                        : new HirExpr.ArrayOp(
                                op.kind(),
                                nr,
                                op.receiverText(),
                                op.elemKind(),
                                op.elementType(),
                                op.arrayClass(),
                                op.componentClass(),
                                na,
                                op.meta());
            }
            case HirExpr.ModuleExportGet g -> {
                HirExpr nr = transform.apply(g.receiver());
                yield nr == g.receiver()
                        ? g
                        : new HirExpr.ModuleExportGet(nr, g.target(), g.meta());
            }
            case HirExpr.DynamicSelect s -> {
                HirExpr nr = transform.apply(s.receiver());
                yield nr == s.receiver()
                        ? s
                        : new HirExpr.DynamicSelect(
                                nr, s.receiverText(), s.memberName(), s.safe(), s.meta());
            }
            case HirExpr.Unary u -> {
                HirExpr no = transform.apply(u.operand());
                yield no == u.operand()
                        ? u
                        : new HirExpr.Unary(u.op(), no, u.operandText(), u.meta());
            }
            case HirExpr.Binary b -> {
                HirExpr nl = transform.apply(b.left());
                HirExpr nr = transform.apply(b.right());
                yield (nl == b.left() && nr == b.right())
                        ? b
                        : new HirExpr.Binary(b.op(), nl, nr, b.leftText(), b.rightText(), b.meta());
            }
            case HirExpr.Range r -> {
                HirExpr ns = transform.apply(r.start());
                HirExpr ne = transform.apply(r.end());
                yield (ns == r.start() && ne == r.end())
                        ? r
                        : new HirExpr.Range(
                                ns, ne, r.inclusive(), r.elemKind(), r.stringElement(), r.meta());
            }
            case HirExpr.Logic l -> {
                HirExpr nl = transform.apply(l.left());
                HirExpr nr = transform.apply(l.right());
                yield (nl == l.left() && nr == l.right())
                        ? l
                        : new HirExpr.Logic(l.op(), nl, nr, l.meta());
            }
            case HirExpr.Is i -> {
                HirExpr nl = transform.apply(i.left());
                yield nl == i.left()
                        ? i
                        : new HirExpr.Is(
                                nl,
                                i.builtin(),
                                i.target(),
                                i.intersectionTargets(),
                                i.descriptor(),
                                i.meta());
            }
            case HirExpr.As a -> {
                HirExpr nl = transform.apply(a.left());
                yield nl == a.left()
                        ? a
                        : new HirExpr.As(
                                nl, a.target(), a.samTarget(), a.targetDescriptor(), a.meta());
            }
            case HirExpr.NonNull n -> {
                HirExpr no = transform.apply(n.operand());
                yield no == n.operand() ? n : new HirExpr.NonNull(no, n.expectedType(), n.meta());
            }
            case HirExpr.SpreadAdapt s -> {
                HirExpr nv = transform.apply(s.value());
                yield nv == s.value() ? s : new HirExpr.SpreadAdapt(nv, s.adapt(), s.meta());
            }
            case HirExpr.JavaSamAdapt j -> {
                HirExpr nv = transform.apply(j.value());
                yield nv == j.value() ? j : new HirExpr.JavaSamAdapt(nv, j.target(), j.meta());
            }
            case HirExpr.TypeApply t -> {
                HirExpr ni = transform.apply(t.inner());
                yield ni == t.inner() ? t : new HirExpr.TypeApply(ni, t.meta());
            }
            case HirExpr.Block b -> {
                List<HirExpr> ns = rewriteList(b.statements(), transform);
                yield ns == b.statements()
                        ? b
                        : new HirExpr.Block(
                                ns,
                                b.opensNamespaceScope(),
                                b.declaredNames(),
                                b.declaredVars(),
                                b.innerWideningRank(),
                                b.meta());
            }
            case HirExpr.If i -> {
                HirExpr nc = transform.apply(i.cond());
                HirExpr nt = transform.apply(i.thenBranch());
                HirExpr ne = i.elseBranch() != null ? transform.apply(i.elseBranch()) : null;
                yield (nc == i.cond() && nt == i.thenBranch() && ne == i.elseBranch())
                        ? i
                        : new HirExpr.If(nc, nt, ne, i.meta());
            }
            case HirExpr.Match m -> {
                HirExpr ns = transform.apply(m.scrutinee());
                List<HirExpr.MatchArm> narms = null;
                for (int idx = 0; idx < m.arms().size(); idx++) {
                    HirExpr.MatchArm arm = m.arms().get(idx);
                    HirPattern np = rewritePattern(arm.pattern(), transform);
                    HirExpr ng = arm.guard() != null ? transform.apply(arm.guard()) : null;
                    HirExpr nb = transform.apply(arm.body());
                    if (np != arm.pattern() || ng != arm.guard() || nb != arm.body()) {
                        if (narms == null) {
                            narms = new ArrayList<>(m.arms());
                        }
                        narms.set(
                                idx,
                                new HirExpr.MatchArm(
                                        np,
                                        arm.boundVars(),
                                        ng,
                                        nb,
                                        arm.bodyWideningRank(),
                                        arm.line()));
                    }
                }
                HirExpr.MatchElse ne = m.elseArm();
                if (ne != null) {
                    HirExpr neb = transform.apply(ne.body());
                    if (neb != ne.body()) {
                        ne = new HirExpr.MatchElse(ne.var(), neb, ne.bodyWideningRank(), ne.line());
                    }
                }
                yield (ns == m.scrutinee() && narms == null && ne == m.elseArm())
                        ? m
                        : new HirExpr.Match(
                                ns,
                                m.scrutineeVar(),
                                narms != null ? narms : m.arms(),
                                ne,
                                m.exhaustive(),
                                m.meta());
            }
            case HirExpr.While w -> {
                HirExpr nc = transform.apply(w.cond());
                HirExpr nb = transform.apply(w.body());
                yield (nc == w.cond() && nb == w.body()) ? w : new HirExpr.While(nc, nb, w.meta());
            }
            case HirExpr.For f -> {
                HirExpr ni = f.iterable() != null ? transform.apply(f.iterable()) : null;
                HirExpr.CountedRangeLoop cr = f.countedRange();
                HirExpr.CountedRangeLoop ncr = cr;
                if (cr != null) {
                    HirExpr ns = transform.apply(cr.start());
                    HirExpr ne = transform.apply(cr.end());
                    HirExpr nk = cr.step() != null ? transform.apply(cr.step()) : null;
                    if (ns != cr.start() || ne != cr.end() || nk != cr.step()) {
                        ncr =
                                new HirExpr.CountedRangeLoop(
                                        ns, ne, nk, cr.inclusive(), cr.descending(), cr.elemKind());
                    }
                }
                List<HirExpr> nb = rewriteList(f.body(), transform);
                yield (ni == f.iterable() && ncr == cr && nb == f.body())
                        ? f
                        : new HirExpr.For(
                                ni,
                                f.target(),
                                f.loopVar(),
                                f.bodyDeclaredVars(),
                                nb,
                                f.bodyWideningRank(),
                                f.elemSamTarget(),
                                ncr,
                                f.meta());
            }
            case HirExpr.Try t -> {
                List<HirExpr> ntb = rewriteList(t.tryBody(), transform);
                List<HirExpr.HirCatch> ncs = null;
                for (int idx = 0; idx < t.catches().size(); idx++) {
                    HirExpr.HirCatch cc = t.catches().get(idx);
                    List<HirExpr> ncb = rewriteList(cc.body(), transform);
                    if (ncb != cc.body()) {
                        if (ncs == null) {
                            ncs = new ArrayList<>(t.catches());
                        }
                        ncs.set(
                                idx,
                                new HirExpr.HirCatch(
                                        cc.var(),
                                        cc.target(),
                                        ncb,
                                        cc.declaredVars(),
                                        cc.opensNamespaceScope(),
                                        cc.declaredNames(),
                                        cc.wideningRank(),
                                        cc.line()));
                    }
                }
                List<HirExpr> nfb = rewriteList(t.finallyBody(), transform);
                yield (ntb == t.tryBody() && ncs == null && nfb == t.finallyBody())
                        ? t
                        : new HirExpr.Try(
                                ntb,
                                t.tryDeclaredVars(),
                                t.tryOpensNamespaceScope(),
                                t.tryDeclaredNames(),
                                t.tryWideningRank(),
                                ncs != null ? ncs : t.catches(),
                                nfb,
                                t.finallyDeclaredVars(),
                                t.finallyOpensNamespaceScope(),
                                t.finallyDeclaredNames(),
                                t.finallyNothrow(),
                                t.hasFinally(),
                                t.meta());
            }
            case HirExpr.Break b -> {
                HirExpr nv = b.value() != null ? transform.apply(b.value()) : null;
                yield nv == b.value() ? b : new HirExpr.Break(nv, b.meta());
            }
            case HirExpr.Return r -> {
                HirExpr nv = transform.apply(r.value());
                yield nv == r.value() ? r : new HirExpr.Return(nv, r.meta());
            }
            case HirExpr.Throw th -> {
                HirExpr nv = transform.apply(th.value());
                yield nv == th.value()
                        ? th
                        : new HirExpr.Throw(nv, th.isJavaThrowable(), th.meta());
            }
            case HirExpr.CoreCollectionCall c -> {
                List<HirCallArg> na = rewriteCallArgs(c.args(), transform);
                yield na == c.args()
                        ? c
                        : new HirExpr.CoreCollectionCall(c.collectionKind(), na, c.meta());
            }
            case HirExpr.SuperCall c -> {
                List<HirCallArg> na = rewriteCallArgs(c.args(), transform);
                yield na == c.args()
                        ? c
                        : new HirExpr.SuperCall(
                                c.thisVar(),
                                c.ownerClassId(),
                                c.memberName(),
                                c.targetInfo(),
                                c.superTypeRef(),
                                na,
                                c.binding(),
                                c.callSite(),
                                c.meta());
            }
            case HirExpr.SelfRecurseCall c -> {
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield na == c.args()
                        ? c
                        : new HirExpr.SelfRecurseCall(c.functionId(), na, c.binding(), c.meta());
            }
            case HirExpr.SelfTailCall c -> {
                List<HirCallArg> na = rewriteCallArgs(c.args(), transform);
                yield na == c.args()
                        ? c
                        : new HirExpr.SelfTailCall(c.functionId(), na, c.binding(), c.meta());
            }
            case HirExpr.DirectBabbleCall c -> {
                HirExpr nc = transform.apply(c.callee());
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield (nc == c.callee() && na == c.args())
                        ? c
                        : new HirExpr.DirectBabbleCall(
                                nc,
                                c.targetFunctionId(),
                                c.externalTarget(),
                                na,
                                c.binding(),
                                c.meta());
            }
            case HirExpr.DirectThisMethodCall c -> {
                HirExpr nr = transform.apply(c.receiver());
                List<HirCallArg> na = rewriteCallArgs(c.args(), transform);
                yield (nr == c.receiver() && na == c.args())
                        ? c
                        : new HirExpr.DirectThisMethodCall(
                                nr,
                                c.receiverText(),
                                c.classId(),
                                c.externalClassJvmName(),
                                c.classTypeId(),
                                c.memberName(),
                                c.overloadAltIndex(),
                                c.targetFunctionId(),
                                c.externalSignature(),
                                na,
                                c.binding(),
                                c.callSite(),
                                c.meta());
            }
            case HirExpr.InterfaceCall c -> {
                HirExpr nr = transform.apply(c.receiver());
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield (nr == c.receiver() && na == c.args())
                        ? c
                        : new HirExpr.InterfaceCall(
                                nr,
                                c.receiverText(),
                                c.selectorLine(),
                                c.interfaceTypeId(),
                                c.memberName(),
                                c.signature(),
                                na,
                                c.binding(),
                                c.meta());
            }
            case HirExpr.JavaConstructorCall c -> {
                HirExpr nc = transform.apply(c.callee());
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield (nc == c.callee() && na == c.args())
                        ? c
                        : new HirExpr.JavaConstructorCall(
                                nc, c.calleeText(), c.pureStaticCallee(), c.target(), na, c.meta());
            }
            case HirExpr.JavaMethodCall c -> {
                HirExpr nr = transform.apply(c.receiver());
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield (nr == c.receiver() && na == c.args())
                        ? c
                        : new HirExpr.JavaMethodCall(
                                nr,
                                c.receiverText(),
                                c.selectorLine(),
                                c.pureStaticReceiver(),
                                c.target(),
                                na,
                                c.meta());
            }
            case HirExpr.CoreMethodCall c -> {
                HirExpr nr = transform.apply(c.receiver());
                List<HirExpr> na = rewriteList(c.args(), transform);
                yield (nr == c.receiver() && na == c.args())
                        ? c
                        : new HirExpr.CoreMethodCall(
                                nr,
                                c.receiverText(),
                                c.selectorLine(),
                                c.coreKind(),
                                c.memberName(),
                                na,
                                c.meta());
            }
            case HirExpr.SafeCall c -> {
                HirExpr nr = transform.apply(c.receiver());
                List<HirCallArg> na = rewriteCallArgs(c.args(), transform);
                yield (nr == c.receiver() && na == c.args())
                        ? c
                        : new HirExpr.SafeCall(
                                nr,
                                c.receiverText(),
                                c.memberName(),
                                c.selectorLine(),
                                c.selectorCheckedType(),
                                na,
                                c.binding(),
                                c.babbleSig(),
                                c.callSite(),
                                c.meta());
            }
            case HirExpr.DynamicCall c -> {
                HirExpr nc = transform.apply(c.callee());
                List<HirCallArg> na = rewriteCallArgs(c.args(), transform);
                yield (nc == c.callee() && na == c.args())
                        ? c
                        : new HirExpr.DynamicCall(
                                nc, na, c.binding(), c.babbleSig(), c.callSite(), c.meta());
            }
            case HirExpr.MakeOverloadSet m -> {
                List<HirExpr> no = rewriteList(m.operands(), transform);
                yield no == m.operands() ? m : new HirExpr.MakeOverloadSet(no, m.metas(), m.meta());
            }
            case HirExpr.OverloadSelect s -> {
                HirExpr ns = transform.apply(s.set());
                yield ns == s.set()
                        ? s
                        : new HirExpr.OverloadSelect(
                                ns, s.index(), s.signatureKey(), s.exactSet(), s.meta());
            }
            case HirExpr.OverloadDispatch d -> {
                HirExpr nc = transform.apply(d.callee());
                List<HirExpr> na = rewriteList(d.args(), transform);
                yield (nc == d.callee() && na == d.args())
                        ? d
                        : new HirExpr.OverloadDispatch(
                                nc, d.calleeText(), d.callSite(), na, d.branches(), d.meta());
            }
            case HirExpr.RecordNew r -> {
                HirExpr ncr = transform.apply(r.classRef());
                List<HirExpr> na = rewriteList(r.args(), transform);
                yield (ncr == r.classRef() && na == r.args())
                        ? r
                        : new HirExpr.RecordNew(
                                r.classId(),
                                r.classTypeId(),
                                r.classJvmName(),
                                ncr,
                                r.classRefIsInstance(),
                                r.paramKinds(),
                                na,
                                r.binding(),
                                r.meta());
            }
            case HirExpr.RecordReuse r -> {
                HirExpr nt = transform.apply(r.target());
                List<HirExpr> na = rewriteList(r.args(), transform);
                yield (nt == r.target() && na == r.args())
                        ? r
                        : new HirExpr.RecordReuse(
                                nt,
                                r.classId(),
                                r.classTypeId(),
                                r.classJvmName(),
                                r.paramKinds(),
                                na,
                                r.meta());
            }
            case HirExpr.Move m -> {
                HirExpr no = transform.apply(m.operand());
                HirVar nv = no instanceof HirExpr.VarRead vr ? vr.var() : m.var();
                yield (no == m.operand() && nv == m.var()) ? m : new HirExpr.Move(nv, no, m.meta());
            }
            case HirExpr.Freeze f -> {
                HirExpr no = transform.apply(f.operand());
                yield no == f.operand() ? f : new HirExpr.Freeze(no, f.meta());
            }
            case HirExpr.Narrow n -> {
                HirExpr no = transform.apply(n.operand());
                yield no == n.operand()
                        ? n
                        : new HirExpr.Narrow(
                                no, n.narrowKind(), n.guardId(), n.targetDesc(), n.meta());
            }
            case HirExpr.Convert cv -> {
                HirExpr no = transform.apply(cv.operand());
                yield no == cv.operand() ? cv : new HirExpr.Convert(no, cv.targetKind(), cv.meta());
            }
            case HirExpr.Widen w -> {
                HirExpr no = transform.apply(w.operand());
                yield no == w.operand() ? w : new HirExpr.Widen(no, w.wideningRank(), w.meta());
            }
        };
    }

    public static HirPattern rewritePattern(HirPattern pattern, UnaryOperator<HirExpr> transform) {
        return switch (pattern) {
            case HirPattern.Const c -> c;
            case HirPattern.Wildcard w -> w;
            case HirPattern.TypeTest t -> t;
            case HirPattern.ValueExpr v -> {
                HirExpr ne = transform.apply(v.expr());
                yield ne == v.expr() ? v : new HirPattern.ValueExpr(ne, v.line());
            }
            case HirPattern.Bind b -> {
                HirPattern ni = b.inner() != null ? rewritePattern(b.inner(), transform) : null;
                yield ni == b.inner() ? b : new HirPattern.Bind(b.var(), ni, b.line());
            }
            case HirPattern.Or o -> {
                List<HirPattern> nalts = null;
                for (int i = 0; i < o.alternatives().size(); i++) {
                    HirPattern orig = o.alternatives().get(i);
                    HirPattern next = rewritePattern(orig, transform);
                    if (next != orig) {
                        if (nalts == null) {
                            nalts = new ArrayList<>(o.alternatives());
                        }
                        nalts.set(i, next);
                    }
                }
                yield nalts == null ? o : new HirPattern.Or(nalts, o.line());
            }
            case HirPattern.Relational r -> {
                HirExpr nr = transform.apply(r.rhs());
                yield nr == r.rhs()
                        ? r
                        : new HirPattern.Relational(r.op(), nr, r.scrutineeKind(), r.line());
            }
            case HirPattern.RangeTest r -> {
                HirExpr nlo = transform.apply(r.lo());
                HirExpr nhi = transform.apply(r.hi());
                HirExpr ns = r.step() != null ? transform.apply(r.step()) : null;
                yield (nlo == r.lo() && nhi == r.hi() && ns == r.step())
                        ? r
                        : new HirPattern.RangeTest(
                                nlo,
                                nhi,
                                ns,
                                r.inclusive(),
                                r.elemKind(),
                                r.stringElement(),
                                r.line());
            }
            case HirPattern.Membership m -> {
                HirExpr nc = transform.apply(m.container());
                yield nc == m.container()
                        ? m
                        : new HirPattern.Membership(nc, m.kind(), m.elemKind(), m.line());
            }
            case HirPattern.RecordDestructure rd -> {
                List<HirPattern> nfps = rewritePatternList(rd.fieldPatterns(), transform);
                yield nfps == rd.fieldPatterns()
                        ? rd
                        : new HirPattern.RecordDestructure(
                                rd.typeTest(),
                                rd.isJavaRecord(),
                                rd.babbleClassId(),
                                rd.babbleTypeId(),
                                rd.babbleJvmName(),
                                rd.javaRecordClass(),
                                rd.fieldNames(),
                                rd.babbleMemberIndices(),
                                rd.fieldKinds(),
                                rd.javaAccessors(),
                                nfps,
                                rd.line());
            }
            case HirPattern.SequenceDestructure sd -> {
                List<HirPattern> neps = rewritePatternList(sd.elemPatterns(), transform);
                yield neps == sd.elemPatterns()
                        ? sd
                        : new HirPattern.SequenceDestructure(
                                sd.typeTest(),
                                sd.isArray(),
                                sd.arrayClass(),
                                sd.elemKind(),
                                neps,
                                sd.hasRest(),
                                sd.restVar(),
                                sd.line());
            }
        };
    }

    private static List<HirPattern> rewritePatternList(
            List<HirPattern> list, UnaryOperator<HirExpr> transform) {
        List<HirPattern> copy = null;
        for (int i = 0; i < list.size(); i++) {
            HirPattern orig = list.get(i);
            HirPattern next = rewritePattern(orig, transform);
            if (next != orig) {
                if (copy == null) {
                    copy = new ArrayList<>(list);
                }
                copy.set(i, next);
            }
        }
        return copy != null ? copy : list;
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

    public static List<HirCallArg> rewriteCallArgs(
            List<HirCallArg> list, UnaryOperator<HirExpr> transform) {
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

    public static List<HirParam> rewriteParams(
            List<HirParam> params, UnaryOperator<HirExpr> transform) {
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

    /** Unwraps single-expression transparent blocks (no scope, no locals, no inner widening). */
    public static HirExpr unwrapTransparent(HirExpr expr) {
        while (expr instanceof HirExpr.Block b && b.isTransparentSingleExpr()) {
            expr = b.statements().get(0);
        }
        return expr;
    }

    /** Rewriter callback invoked for each statement list in a {@link HirModule}. */
    @FunctionalInterface
    public interface BodyRewriter {
        /**
         * @param ownerMethodId HIR owner id ({@code 0} for top-level, {@code fn.id()} for
         *     functions, {@code cls.id()} / {@code iface.id()} for types)
         * @param currentFnId {@code fn.id()} inside a function body, {@code -1} elsewhere
         * @param statements statement list to rewrite
         */
        List<HirExpr> rewrite(int ownerMethodId, int currentFnId, List<HirExpr> statements);
    }

    /**
     * Applies a statement-list rewriter across every body and parameter default in {@code module}.
     */
    public static HirModule rewriteModuleBodies(
            HirModule module, UnaryOperator<List<HirExpr>> stmtListRewriter) {
        return rewriteModule(
                module,
                (ownerId, fnId, stmts) -> stmtListRewriter.apply(stmts),
                ex -> {
                    List<HirExpr> r = stmtListRewriter.apply(List.of(ex));
                    return r.size() == 1 ? r.get(0) : ex;
                },
                module::nextVarId);
    }

    /**
     * General copy-on-write module rewriter used across HIR optimization passes (BIP027-I2),
     * avoiding duplicated traversal of top-level statements, functions, classes, interfaces, and
     * static blocks.
     */
    public static HirModule rewriteModule(
            HirModule module,
            BodyRewriter bodyRewriter,
            UnaryOperator<HirExpr> paramDefaultRewriter,
            java.util.function.IntSupplier nextVarIdSupplier) {
        List<HirExpr> newTop = bodyRewriter.rewrite(0, -1, module.topLevelStatements());
        boolean changed = newTop != module.topLevelStatements();

        Map<Integer, HirFunction> newFns = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirFunction> e : module.functions().entrySet()) {
            HirFunction fn = e.getValue();
            List<HirParam> np =
                    paramDefaultRewriter != null
                            ? rewriteParams(fn.params(), paramDefaultRewriter)
                            : fn.params();
            List<HirExpr> nb = bodyRewriter.rewrite(fn.id(), fn.id(), fn.body());
            if (np != fn.params() || nb != fn.body()) {
                changed = true;
                fn = fn.withParamsAndBody(np, nb);
            }
            newFns.put(e.getKey(), fn);
        }

        Map<Integer, HirClass> newClasses = new LinkedHashMap<>();
        for (Map.Entry<Integer, HirClass> e : module.classes().entrySet()) {
            HirClass cls = e.getValue();
            List<HirParam> np =
                    paramDefaultRewriter != null
                            ? rewriteParams(cls.params(), paramDefaultRewriter)
                            : cls.params();
            List<HirExpr> nb = bodyRewriter.rewrite(cls.id(), -1, cls.body());
            HirStaticBlock ns = cls.staticBlock();
            if (ns != null) {
                List<HirExpr> nsb = bodyRewriter.rewrite(cls.id(), -1, ns.body());
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
            List<HirExpr> nb = bodyRewriter.rewrite(iface.id(), -1, iface.body());
            HirStaticBlock ns = iface.staticBlock();
            if (ns != null) {
                List<HirExpr> nsb = bodyRewriter.rewrite(iface.id(), -1, ns.body());
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
        return module.withUpdates(
                newTop,
                newFns,
                newClasses,
                newIfaces,
                nextVarIdSupplier.getAsInt(),
                module.nextOwnerId());
    }
}
