package org.babblelang.ir.hir;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Package-private structural child/pattern/metadata rewriters for {@code org.babblelang.ir.hir}
 * passes ({@link HirNarrowAnnotator}, {@link HirConvertMaterializer}).
 */
final class HirTreeRewrite {
    private HirTreeRewrite() {}

    static List<HirExpr> rewriteList(List<HirExpr> list, UnaryOperator<HirExpr> fn) {
        List<HirExpr> copy = null;
        for (int i = 0; i < list.size(); i++) {
            HirExpr old = list.get(i);
            HirExpr nw = fn.apply(old);
            if (nw != old) {
                if (copy == null) {
                    copy = new ArrayList<>(list);
                }
                copy.set(i, nw);
            }
        }
        return copy != null ? copy : list;
    }

    static List<HirCallArg> rewriteCallArgs(List<HirCallArg> args, UnaryOperator<HirExpr> fn) {
        List<HirCallArg> copy = null;
        for (int i = 0; i < args.size(); i++) {
            HirCallArg old = args.get(i);
            HirCallArg nw = old.withValue(fn.apply(old.value()));
            if (nw != old) {
                if (copy == null) {
                    copy = new ArrayList<>(args);
                }
                copy.set(i, nw);
            }
        }
        return copy != null ? copy : args;
    }

    static HirPattern rewritePattern(HirPattern p, UnaryOperator<HirExpr> fn) {
        return switch (p) {
            case HirPattern.Const c -> c;
            case HirPattern.Wildcard w -> w;
            case HirPattern.TypeTest t -> t;
            case HirPattern.ValueExpr v -> {
                HirExpr ne = fn.apply(v.expr());
                yield ne == v.expr() ? v : new HirPattern.ValueExpr(ne, v.line());
            }
            case HirPattern.Bind b -> {
                HirPattern ni = b.inner() != null ? rewritePattern(b.inner(), fn) : null;
                yield ni == b.inner() ? b : new HirPattern.Bind(b.var(), ni, b.line());
            }
            case HirPattern.Or o -> {
                List<HirPattern> alts = null;
                for (int i = 0; i < o.alternatives().size(); i++) {
                    HirPattern old = o.alternatives().get(i);
                    HirPattern nw = rewritePattern(old, fn);
                    if (nw != old) {
                        if (alts == null) {
                            alts = new ArrayList<>(o.alternatives());
                        }
                        alts.set(i, nw);
                    }
                }
                yield alts == null ? o : new HirPattern.Or(alts, o.line());
            }
            case HirPattern.Relational r -> {
                HirExpr nr = fn.apply(r.rhs());
                yield nr == r.rhs()
                        ? r
                        : new HirPattern.Relational(r.op(), nr, r.scrutineeKind(), r.line());
            }
            case HirPattern.RangeTest r -> {
                HirExpr nlo = fn.apply(r.lo());
                HirExpr nhi = fn.apply(r.hi());
                HirExpr nst = r.step() != null ? fn.apply(r.step()) : null;
                yield (nlo == r.lo() && nhi == r.hi() && nst == r.step())
                        ? r
                        : new HirPattern.RangeTest(
                                nlo,
                                nhi,
                                nst,
                                r.inclusive(),
                                r.elemKind(),
                                r.stringElement(),
                                r.line());
            }
            case HirPattern.Membership m -> {
                HirExpr nc = fn.apply(m.container());
                yield nc == m.container()
                        ? m
                        : new HirPattern.Membership(nc, m.kind(), m.elemKind(), m.line());
            }
            case HirPattern.RecordDestructure rd -> {
                List<HirPattern> fps = null;
                for (int i = 0; i < rd.fieldPatterns().size(); i++) {
                    HirPattern old = rd.fieldPatterns().get(i);
                    HirPattern nw = rewritePattern(old, fn);
                    if (nw != old) {
                        if (fps == null) {
                            fps = new ArrayList<>(rd.fieldPatterns());
                        }
                        fps.set(i, nw);
                    }
                }
                yield fps == null
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
                                fps,
                                rd.line());
            }
            case HirPattern.SequenceDestructure sd -> {
                List<HirPattern> eps = null;
                for (int i = 0; i < sd.elemPatterns().size(); i++) {
                    HirPattern old = sd.elemPatterns().get(i);
                    HirPattern nw = rewritePattern(old, fn);
                    if (nw != old) {
                        if (eps == null) {
                            eps = new ArrayList<>(sd.elemPatterns());
                        }
                        eps.set(i, nw);
                    }
                }
                yield eps == null
                        ? sd
                        : new HirPattern.SequenceDestructure(
                                sd.typeTest(),
                                sd.isArray(),
                                sd.arrayClass(),
                                sd.elemKind(),
                                eps,
                                sd.hasRest(),
                                sd.restVar(),
                                sd.line());
            }
        };
    }

    static HirExpr rewriteChildren(HirExpr expr, UnaryOperator<HirExpr> fn) {
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
                HirExpr nv = d.value() != null ? fn.apply(d.value()) : null;
                yield nv == d.value()
                        ? d
                        : new HirExpr.Def(d.name(), d.target(), d.var(), nv, d.meta());
            }
            case HirExpr.VarAssign a -> {
                HirExpr nv = fn.apply(a.value());
                yield nv == a.value()
                        ? a
                        : new HirExpr.VarAssign(
                                a.var(), a.operator(), nv, a.targetText(), a.valueText(), a.meta());
            }
            case HirExpr.NameAssign a -> {
                HirExpr nv = fn.apply(a.value());
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
                HirExpr nr = fn.apply(a.receiver());
                HirExpr nv = fn.apply(a.value());
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
                HirExpr nr = fn.apply(a.receiver());
                HirExpr nv = fn.apply(a.value());
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
                HirExpr nr = fn.apply(a.receiver());
                HirExpr nv = fn.apply(a.value());
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
                HirExpr nr = fn.apply(g.receiver());
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
                HirExpr nr = fn.apply(g.receiver());
                yield nr == g.receiver()
                        ? g
                        : new HirExpr.JavaFieldGet(
                                nr, g.receiverText(), g.pureStaticReceiver(), g.target(), g.meta());
            }
            case HirExpr.ArrayCreate c -> {
                List<HirExpr> na = rewriteList(c.args(), fn);
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
                HirExpr nr = fn.apply(op.receiver());
                List<HirExpr> na = rewriteList(op.args(), fn);
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
                HirExpr nr = fn.apply(g.receiver());
                yield nr == g.receiver()
                        ? g
                        : new HirExpr.ModuleExportGet(nr, g.target(), g.meta());
            }
            case HirExpr.DynamicSelect s -> {
                HirExpr nr = fn.apply(s.receiver());
                yield nr == s.receiver()
                        ? s
                        : new HirExpr.DynamicSelect(
                                nr, s.receiverText(), s.memberName(), s.safe(), s.meta());
            }
            case HirExpr.Unary u -> {
                HirExpr no = fn.apply(u.operand());
                yield no == u.operand()
                        ? u
                        : new HirExpr.Unary(u.op(), no, u.operandText(), u.meta());
            }
            case HirExpr.Binary b -> {
                HirExpr nl = fn.apply(b.left());
                HirExpr nr = fn.apply(b.right());
                yield (nl == b.left() && nr == b.right())
                        ? b
                        : new HirExpr.Binary(b.op(), nl, nr, b.leftText(), b.rightText(), b.meta());
            }
            case HirExpr.Range r -> {
                HirExpr ns = fn.apply(r.start());
                HirExpr ne = fn.apply(r.end());
                yield (ns == r.start() && ne == r.end())
                        ? r
                        : new HirExpr.Range(
                                ns, ne, r.inclusive(), r.elemKind(), r.stringElement(), r.meta());
            }
            case HirExpr.Logic l -> {
                HirExpr nl = fn.apply(l.left());
                HirExpr nr = fn.apply(l.right());
                yield (nl == l.left() && nr == l.right())
                        ? l
                        : new HirExpr.Logic(l.op(), nl, nr, l.rightNarrowed(), l.meta());
            }
            case HirExpr.Is i -> {
                HirExpr nl = fn.apply(i.left());
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
                HirExpr nl = fn.apply(a.left());
                yield nl == a.left()
                        ? a
                        : new HirExpr.As(
                                nl, a.target(), a.samTarget(), a.targetDescriptor(), a.meta());
            }
            case HirExpr.NonNull n -> {
                HirExpr no = fn.apply(n.operand());
                yield no == n.operand() ? n : new HirExpr.NonNull(no, n.expectedType(), n.meta());
            }
            case HirExpr.SpreadAdapt s -> {
                HirExpr nv = fn.apply(s.value());
                yield nv == s.value() ? s : new HirExpr.SpreadAdapt(nv, s.adapt(), s.meta());
            }
            case HirExpr.JavaSamAdapt j -> {
                HirExpr nv = fn.apply(j.value());
                yield nv == j.value() ? j : new HirExpr.JavaSamAdapt(nv, j.target(), j.meta());
            }
            case HirExpr.Block b -> {
                List<HirExpr> ns = rewriteList(b.statements(), fn);
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
                HirExpr nc = fn.apply(i.cond());
                HirExpr nt = fn.apply(i.thenBranch());
                HirExpr ne = i.elseBranch() != null ? fn.apply(i.elseBranch()) : null;
                yield (nc == i.cond() && nt == i.thenBranch() && ne == i.elseBranch())
                        ? i
                        : new HirExpr.If(nc, nt, ne, i.meta());
            }
            case HirExpr.Match m -> {
                HirExpr ns = fn.apply(m.scrutinee());
                List<HirExpr.MatchArm> narms = null;
                for (int idx = 0; idx < m.arms().size(); idx++) {
                    HirExpr.MatchArm arm = m.arms().get(idx);
                    HirPattern np = rewritePattern(arm.pattern(), fn);
                    HirExpr ng = arm.guard() != null ? fn.apply(arm.guard()) : null;
                    HirExpr nb = fn.apply(arm.body());
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
                    HirExpr neb = fn.apply(ne.body());
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
                HirExpr nc = fn.apply(w.cond());
                HirExpr nb = fn.apply(w.body());
                yield (nc == w.cond() && nb == w.body()) ? w : new HirExpr.While(nc, nb, w.meta());
            }
            case HirExpr.For f -> {
                HirExpr ni = f.iterable() != null ? fn.apply(f.iterable()) : null;
                HirExpr.CountedRangeLoop cr = f.countedRange();
                HirExpr.CountedRangeLoop ncr = cr;
                if (cr != null) {
                    HirExpr ns = fn.apply(cr.start());
                    HirExpr ne = fn.apply(cr.end());
                    HirExpr nk = cr.step() != null ? fn.apply(cr.step()) : null;
                    if (ns != cr.start() || ne != cr.end() || nk != cr.step()) {
                        ncr =
                                new HirExpr.CountedRangeLoop(
                                        ns, ne, nk, cr.inclusive(), cr.descending(), cr.elemKind());
                    }
                }
                List<HirExpr> nb = rewriteList(f.body(), fn);
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
                List<HirExpr> ntb = rewriteList(t.tryBody(), fn);
                List<HirExpr.HirCatch> ncs = null;
                for (int idx = 0; idx < t.catches().size(); idx++) {
                    HirExpr.HirCatch cc = t.catches().get(idx);
                    List<HirExpr> ncb = rewriteList(cc.body(), fn);
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
                List<HirExpr> nfb = rewriteList(t.finallyBody(), fn);
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
                HirExpr nv = b.value() != null ? fn.apply(b.value()) : null;
                yield nv == b.value() ? b : new HirExpr.Break(nv, b.meta());
            }
            case HirExpr.Return r -> {
                HirExpr nv = fn.apply(r.value());
                yield nv == r.value() ? r : new HirExpr.Return(nv, r.meta());
            }
            case HirExpr.Throw th -> {
                HirExpr nv = fn.apply(th.value());
                yield nv == th.value()
                        ? th
                        : new HirExpr.Throw(nv, th.isJavaThrowable(), th.meta());
            }
            case HirExpr.CoreCollectionCall c -> {
                List<HirCallArg> na = rewriteCallArgs(c.args(), fn);
                yield na == c.args()
                        ? c
                        : new HirExpr.CoreCollectionCall(c.collectionKind(), na, c.meta());
            }
            case HirExpr.SuperCall c -> {
                List<HirCallArg> na = rewriteCallArgs(c.args(), fn);
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
                List<HirExpr> na = rewriteList(c.args(), fn);
                yield na == c.args()
                        ? c
                        : new HirExpr.SelfRecurseCall(c.functionId(), na, c.binding(), c.meta());
            }
            case HirExpr.SelfTailCall c -> {
                List<HirCallArg> na = rewriteCallArgs(c.args(), fn);
                yield na == c.args()
                        ? c
                        : new HirExpr.SelfTailCall(c.functionId(), na, c.binding(), c.meta());
            }
            case HirExpr.DirectBabbleCall c -> {
                HirExpr nc = fn.apply(c.callee());
                List<HirExpr> na = rewriteList(c.args(), fn);
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
                HirExpr nr = fn.apply(c.receiver());
                List<HirCallArg> na = rewriteCallArgs(c.args(), fn);
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
                HirExpr nr = fn.apply(c.receiver());
                List<HirExpr> na = rewriteList(c.args(), fn);
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
                HirExpr nc = c.callee() != null ? fn.apply(c.callee()) : null;
                List<HirExpr> na = rewriteList(c.args(), fn);
                yield (nc == c.callee() && na == c.args())
                        ? c
                        : new HirExpr.JavaConstructorCall(
                                nc, c.calleeText(), c.pureStaticCallee(), c.target(), na, c.meta());
            }
            case HirExpr.JavaMethodCall c -> {
                HirExpr nr = fn.apply(c.receiver());
                List<HirExpr> na = rewriteList(c.args(), fn);
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
                HirExpr nr = fn.apply(c.receiver());
                List<HirExpr> na = rewriteList(c.args(), fn);
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
                HirExpr nr = fn.apply(c.receiver());
                List<HirCallArg> na = rewriteCallArgs(c.args(), fn);
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
                HirExpr nc = fn.apply(c.callee());
                List<HirCallArg> na = rewriteCallArgs(c.args(), fn);
                yield (nc == c.callee() && na == c.args())
                        ? c
                        : new HirExpr.DynamicCall(
                                nc, na, c.binding(), c.babbleSig(), c.callSite(), c.meta());
            }
            case HirExpr.MakeOverloadSet m -> {
                List<HirExpr> no = rewriteList(m.operands(), fn);
                yield no == m.operands() ? m : new HirExpr.MakeOverloadSet(no, m.metas(), m.meta());
            }
            case HirExpr.OverloadSelect s -> {
                HirExpr ns = fn.apply(s.set());
                yield ns == s.set()
                        ? s
                        : new HirExpr.OverloadSelect(
                                ns, s.index(), s.signatureKey(), s.exactSet(), s.meta());
            }
            case HirExpr.OverloadDispatch d -> {
                HirExpr nc = fn.apply(d.callee());
                List<HirExpr> na = rewriteList(d.args(), fn);
                yield (nc == d.callee() && na == d.args())
                        ? d
                        : new HirExpr.OverloadDispatch(
                                nc, d.calleeText(), d.callSite(), na, d.branches(), d.meta());
            }
            case HirExpr.TypeApply t -> {
                HirExpr ni = fn.apply(t.inner());
                yield ni == t.inner() ? t : new HirExpr.TypeApply(ni, t.meta());
            }
            case HirExpr.RecordNew r -> {
                HirExpr nc = fn.apply(r.classRef());
                List<HirExpr> na = rewriteList(r.args(), fn);
                yield (nc == r.classRef() && na == r.args())
                        ? r
                        : new HirExpr.RecordNew(
                                r.classId(),
                                r.classTypeId(),
                                r.classJvmName(),
                                nc,
                                r.classRefIsInstance(),
                                r.paramKinds(),
                                na,
                                r.binding(),
                                r.meta());
            }
            case HirExpr.RecordReuse r -> {
                HirExpr nt = fn.apply(r.target());
                List<HirExpr> na = rewriteList(r.args(), fn);
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
                HirExpr no = fn.apply(m.operand());
                HirVar nv = no instanceof HirExpr.VarRead vr ? vr.var() : m.var();
                yield (no == m.operand() && nv == m.var()) ? m : new HirExpr.Move(nv, no, m.meta());
            }
            case HirExpr.Freeze f -> {
                HirExpr no = fn.apply(f.operand());
                yield no == f.operand() ? f : new HirExpr.Freeze(no, f.meta());
            }
            case HirExpr.Narrow n -> {
                HirExpr no = fn.apply(n.operand());
                yield no == n.operand()
                        ? n
                        : new HirExpr.Narrow(
                                no, n.narrowKind(), n.guardId(), n.targetDesc(), n.meta());
            }
            case HirExpr.Convert cv -> {
                HirExpr no = fn.apply(cv.operand());
                yield no == cv.operand() ? cv : new HirExpr.Convert(no, cv.targetKind(), cv.meta());
            }
            case HirExpr.Widen w -> {
                HirExpr no = fn.apply(w.operand());
                yield no == w.operand() ? w : new HirExpr.Widen(no, w.wideningRank(), w.meta());
            }
        };
    }

    static HirExpr withMeta(HirExpr expr, ExprMeta meta) {
        return switch (expr) {
            case HirExpr.Const c -> new HirExpr.Const(c.value(), meta);
            case HirExpr.IntParseFallback f -> new HirExpr.IntParseFallback(f.text(), meta);
            case HirExpr.CoreCollectionRef r ->
                    new HirExpr.CoreCollectionRef(r.collectionKind(), meta);
            case HirExpr.CoreExceptionRef r -> new HirExpr.CoreExceptionRef(meta);
            case HirExpr.VarRead v -> new HirExpr.VarRead(v.var(), meta);
            case HirExpr.NameRead n ->
                    new HirExpr.NameRead(n.name(), n.finalBinding(), n.definitelyDefined(), meta);
            case HirExpr.ThisRef t -> new HirExpr.ThisRef(t.var(), t.enclosingClassId(), meta);
            case HirExpr.RecurseRef r -> new HirExpr.RecurseRef(r.var(), meta);
            case HirExpr.Def d -> new HirExpr.Def(d.name(), d.target(), d.var(), d.value(), meta);
            case HirExpr.VarAssign a ->
                    new HirExpr.VarAssign(
                            a.var(), a.operator(), a.value(), a.targetText(), a.valueText(), meta);
            case HirExpr.NameAssign a ->
                    new HirExpr.NameAssign(
                            a.name(), a.operator(), a.value(), a.targetText(), a.valueText(), meta);
            case HirExpr.ThisFieldAssign a ->
                    new HirExpr.ThisFieldAssign(
                            a.receiver(),
                            a.classId(),
                            a.memberIndex(),
                            a.memberName(),
                            a.operator(),
                            a.value(),
                            a.targetText(),
                            a.valueText(),
                            meta);
            case HirExpr.JavaFieldAssign a ->
                    new HirExpr.JavaFieldAssign(
                            a.receiver(),
                            a.receiverText(),
                            a.pureStaticReceiver(),
                            a.target(),
                            a.operator(),
                            a.value(),
                            a.targetText(),
                            a.valueText(),
                            meta);
            case HirExpr.DynamicFieldAssign a ->
                    new HirExpr.DynamicFieldAssign(
                            a.receiver(),
                            a.receiverText(),
                            a.memberName(),
                            a.operator(),
                            a.value(),
                            a.targetText(),
                            a.valueText(),
                            meta);
            case HirExpr.ThisFieldGet g ->
                    new HirExpr.ThisFieldGet(
                            g.receiver(),
                            g.receiverText(),
                            g.classId(),
                            g.externalClassJvmName(),
                            g.memberIndex(),
                            g.memberName(),
                            g.fieldKind(),
                            g.immutableField(),
                            g.definitelyDefined(),
                            meta);
            case HirExpr.JavaFieldGet g ->
                    new HirExpr.JavaFieldGet(
                            g.receiver(),
                            g.receiverText(),
                            g.pureStaticReceiver(),
                            g.target(),
                            meta);
            case HirExpr.ArrayCreate c ->
                    new HirExpr.ArrayCreate(
                            c.kind(),
                            c.elemKind(),
                            c.elementType(),
                            c.componentInternalName(),
                            c.componentIsJavaInterface(),
                            c.componentClass(),
                            c.args(),
                            meta);
            case HirExpr.ArrayOp op ->
                    new HirExpr.ArrayOp(
                            op.kind(),
                            op.receiver(),
                            op.receiverText(),
                            op.elemKind(),
                            op.elementType(),
                            op.arrayClass(),
                            op.componentClass(),
                            op.args(),
                            meta);
            case HirExpr.ModuleExportGet g ->
                    new HirExpr.ModuleExportGet(g.receiver(), g.target(), meta);
            case HirExpr.DynamicSelect s ->
                    new HirExpr.DynamicSelect(
                            s.receiver(), s.receiverText(), s.memberName(), s.safe(), meta);
            case HirExpr.Unary u -> new HirExpr.Unary(u.op(), u.operand(), u.operandText(), meta);
            case HirExpr.Binary b ->
                    new HirExpr.Binary(
                            b.op(), b.left(), b.right(), b.leftText(), b.rightText(), meta);
            case HirExpr.Range r ->
                    new HirExpr.Range(
                            r.start(),
                            r.end(),
                            r.inclusive(),
                            r.elemKind(),
                            r.stringElement(),
                            meta);
            case HirExpr.Logic l ->
                    new HirExpr.Logic(l.op(), l.left(), l.right(), l.rightNarrowed(), meta);
            case HirExpr.Is i ->
                    new HirExpr.Is(
                            i.left(),
                            i.builtin(),
                            i.target(),
                            i.intersectionTargets(),
                            i.descriptor(),
                            meta);
            case HirExpr.As a ->
                    new HirExpr.As(a.left(), a.target(), a.samTarget(), a.targetDescriptor(), meta);
            case HirExpr.NonNull n -> new HirExpr.NonNull(n.operand(), n.expectedType(), meta);
            case HirExpr.SpreadAdapt s -> new HirExpr.SpreadAdapt(s.value(), s.adapt(), meta);
            case HirExpr.JavaSamAdapt j -> new HirExpr.JavaSamAdapt(j.value(), j.target(), meta);
            case HirExpr.Block b ->
                    new HirExpr.Block(
                            b.statements(),
                            b.opensNamespaceScope(),
                            b.declaredNames(),
                            b.declaredVars(),
                            b.innerWideningRank(),
                            meta);
            case HirExpr.If i -> new HirExpr.If(i.cond(), i.thenBranch(), i.elseBranch(), meta);
            case HirExpr.Match m ->
                    new HirExpr.Match(
                            m.scrutinee(),
                            m.scrutineeVar(),
                            m.arms(),
                            m.elseArm(),
                            m.exhaustive(),
                            meta);
            case HirExpr.While w -> new HirExpr.While(w.cond(), w.body(), meta);
            case HirExpr.For f ->
                    new HirExpr.For(
                            f.iterable(),
                            f.target(),
                            f.loopVar(),
                            f.bodyDeclaredVars(),
                            f.body(),
                            f.bodyWideningRank(),
                            f.elemSamTarget(),
                            f.countedRange(),
                            meta);
            case HirExpr.Try t ->
                    new HirExpr.Try(
                            t.tryBody(),
                            t.tryDeclaredVars(),
                            t.tryOpensNamespaceScope(),
                            t.tryDeclaredNames(),
                            t.tryWideningRank(),
                            t.catches(),
                            t.finallyBody(),
                            t.finallyDeclaredVars(),
                            t.finallyOpensNamespaceScope(),
                            t.finallyDeclaredNames(),
                            t.finallyNothrow(),
                            t.hasFinally(),
                            meta);
            case HirExpr.Break b -> new HirExpr.Break(b.value(), meta);
            case HirExpr.Continue c -> new HirExpr.Continue(meta);
            case HirExpr.Return r -> new HirExpr.Return(r.value(), meta);
            case HirExpr.Throw th -> new HirExpr.Throw(th.value(), th.isJavaThrowable(), meta);
            case HirExpr.CoreCollectionCall c ->
                    new HirExpr.CoreCollectionCall(c.collectionKind(), c.args(), meta);
            case HirExpr.SuperCall c ->
                    new HirExpr.SuperCall(
                            c.thisVar(),
                            c.ownerClassId(),
                            c.memberName(),
                            c.targetInfo(),
                            c.superTypeRef(),
                            c.args(),
                            c.binding(),
                            c.callSite(),
                            meta);
            case HirExpr.SelfRecurseCall c ->
                    new HirExpr.SelfRecurseCall(c.functionId(), c.args(), c.binding(), meta);
            case HirExpr.SelfTailCall c ->
                    new HirExpr.SelfTailCall(c.functionId(), c.args(), c.binding(), meta);
            case HirExpr.DirectBabbleCall c ->
                    new HirExpr.DirectBabbleCall(
                            c.callee(),
                            c.targetFunctionId(),
                            c.externalTarget(),
                            c.args(),
                            c.binding(),
                            meta);
            case HirExpr.DirectThisMethodCall c ->
                    new HirExpr.DirectThisMethodCall(
                            c.receiver(),
                            c.receiverText(),
                            c.classId(),
                            c.externalClassJvmName(),
                            c.classTypeId(),
                            c.memberName(),
                            c.overloadAltIndex(),
                            c.targetFunctionId(),
                            c.externalSignature(),
                            c.args(),
                            c.binding(),
                            c.callSite(),
                            meta);
            case HirExpr.InterfaceCall c ->
                    new HirExpr.InterfaceCall(
                            c.receiver(),
                            c.receiverText(),
                            c.selectorLine(),
                            c.interfaceTypeId(),
                            c.memberName(),
                            c.signature(),
                            c.args(),
                            c.binding(),
                            meta);
            case HirExpr.JavaConstructorCall c ->
                    new HirExpr.JavaConstructorCall(
                            c.callee(),
                            c.calleeText(),
                            c.pureStaticCallee(),
                            c.target(),
                            c.args(),
                            meta);
            case HirExpr.JavaMethodCall c ->
                    new HirExpr.JavaMethodCall(
                            c.receiver(),
                            c.receiverText(),
                            c.selectorLine(),
                            c.pureStaticReceiver(),
                            c.target(),
                            c.args(),
                            meta);
            case HirExpr.CoreMethodCall c ->
                    new HirExpr.CoreMethodCall(
                            c.receiver(),
                            c.receiverText(),
                            c.selectorLine(),
                            c.coreKind(),
                            c.memberName(),
                            c.args(),
                            meta);
            case HirExpr.SafeCall c ->
                    new HirExpr.SafeCall(
                            c.receiver(),
                            c.receiverText(),
                            c.memberName(),
                            c.selectorLine(),
                            c.selectorCheckedType(),
                            c.args(),
                            c.binding(),
                            c.babbleSig(),
                            c.callSite(),
                            meta);
            case HirExpr.DynamicCall c ->
                    new HirExpr.DynamicCall(
                            c.callee(), c.args(), c.binding(), c.babbleSig(), c.callSite(), meta);
            case HirExpr.MakeOverloadSet m ->
                    new HirExpr.MakeOverloadSet(m.operands(), m.metas(), meta);
            case HirExpr.OverloadSelect s ->
                    new HirExpr.OverloadSelect(
                            s.set(), s.index(), s.signatureKey(), s.exactSet(), meta);
            case HirExpr.OverloadDispatch d ->
                    new HirExpr.OverloadDispatch(
                            d.callee(), d.calleeText(), d.callSite(), d.args(), d.branches(), meta);
            case HirExpr.FunctionRef f -> new HirExpr.FunctionRef(f.functionId(), meta);
            case HirExpr.ClassExpr c -> new HirExpr.ClassExpr(c.classId(), meta);
            case HirExpr.InterfaceExpr i -> new HirExpr.InterfaceExpr(i.interfaceId(), meta);
            case HirExpr.TypeApply t -> new HirExpr.TypeApply(t.inner(), meta);
            case HirExpr.RecordNew r ->
                    new HirExpr.RecordNew(
                            r.classId(),
                            r.classTypeId(),
                            r.classJvmName(),
                            r.classRef(),
                            r.classRefIsInstance(),
                            r.paramKinds(),
                            r.args(),
                            r.binding(),
                            meta);
            case HirExpr.RecordReuse r ->
                    new HirExpr.RecordReuse(
                            r.target(),
                            r.classId(),
                            r.classTypeId(),
                            r.classJvmName(),
                            r.paramKinds(),
                            r.args(),
                            meta);
            case HirExpr.Move m -> new HirExpr.Move(m.var(), m.operand(), meta);
            case HirExpr.Freeze f -> new HirExpr.Freeze(f.operand(), meta);
            case HirExpr.Narrow n ->
                    new HirExpr.Narrow(
                            n.operand(), n.narrowKind(), n.guardId(), n.targetDesc(), meta);
            case HirExpr.Convert cv -> new HirExpr.Convert(cv.operand(), cv.targetKind(), meta);
            case HirExpr.Widen w -> new HirExpr.Widen(w.operand(), w.wideningRank(), meta);
        };
    }
}
