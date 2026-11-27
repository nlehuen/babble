package org.babblelang.ir.hir;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.PrimitiveKind;

/**
 * Pass 5 of {@link HirBuilder} ({@code BIP039-D4}): materializes explicit {@link HirExpr.Widen} and
 * {@link HirExpr.Convert} nodes at all optimization levels ({@code -O0}, {@code -O1}, {@code -O2}).
 */
final class HirConvertMaterializer {
    private final Map<Integer, HirFunction> functions;
    private HirFunction currentFn;

    private HirConvertMaterializer(Map<Integer, HirFunction> functions) {
        this.functions = functions;
    }

    static void materialize(
            List<HirExpr> topLevel,
            Map<Integer, HirFunction> functions,
            Map<Integer, HirClass> classes,
            Map<Integer, HirInterface> interfaces) {
        HirConvertMaterializer m = new HirConvertMaterializer(functions);
        m.materializeListInPlace(topLevel);
        for (Map.Entry<Integer, HirFunction> entry : functions.entrySet()) {
            HirFunction fn = entry.getValue();
            m.currentFn = fn;
            List<HirParam> newParams = m.materializeParams(fn.params());
            List<HirExpr> newBody =
                    m.materializeStmtList(fn.body(), fn.bodyWideningRank(), fn.returnKind());
            int newBodyWiden = newBody.isEmpty() ? fn.bodyWideningRank() : -1;
            if (newParams != fn.params()
                    || newBody != fn.body()
                    || newBodyWiden != fn.bodyWideningRank()) {
                entry.setValue(fn.withParamsBodyAndWideningRank(newParams, newBody, newBodyWiden));
            }
            m.currentFn = null;
        }
        for (Map.Entry<Integer, HirClass> entry : classes.entrySet()) {
            HirClass cls = entry.getValue();
            List<HirParam> newParams = m.materializeParams(cls.params());
            List<HirExpr> newBody = m.materializeList(cls.body(), true);
            List<HirConstructor> newCtors = m.materializeConstructors(cls.constructors());
            HirStaticBlock newStatic = m.materializeStaticBlock(cls.staticBlock());
            if (newParams != cls.params()
                    || newBody != cls.body()
                    || newCtors != cls.constructors()
                    || newStatic != cls.staticBlock()) {
                entry.setValue(cls.withBodyAndStatic(newParams, newBody, newCtors, newStatic));
            }
        }
        for (Map.Entry<Integer, HirInterface> entry : interfaces.entrySet()) {
            HirInterface iface = entry.getValue();
            List<HirExpr> newBody = m.materializeList(iface.body(), true);
            HirStaticBlock newStatic = m.materializeStaticBlock(iface.staticBlock());
            if (newBody != iface.body() || newStatic != iface.staticBlock()) {
                entry.setValue(iface.withBodyAndStatic(newBody, newStatic));
            }
        }
    }

    private List<HirParam> materializeParams(List<HirParam> params) {
        List<HirParam> copy = null;
        for (int i = 0; i < params.size(); i++) {
            HirParam p = params.get(i);
            if (p.defaultValue() != null) {
                HirExpr nd = materializeExpr(p.defaultValue());
                if (nd != p.defaultValue()) {
                    if (copy == null) {
                        copy = new ArrayList<>(params);
                    }
                    copy.set(i, p.withDefaultValue(nd));
                }
            }
        }
        return copy != null ? copy : params;
    }

    private List<HirConstructor> materializeConstructors(List<HirConstructor> ctors) {
        if (ctors.isEmpty()) {
            return ctors;
        }
        List<HirConstructor> copy = null;
        for (int i = 0; i < ctors.size(); i++) {
            HirConstructor c = ctors.get(i);
            List<HirParam> np = materializeParams(c.params());
            List<HirExpr> nPre = materializeList(c.preDelegateBody(), true);
            List<HirCallArg> nDel = c.delegateArgs();
            if (!nDel.isEmpty()) {
                List<HirCallArg> delCopy = null;
                for (int di = 0; di < nDel.size(); di++) {
                    HirCallArg da = nDel.get(di);
                    HirExpr nv = materializeExpr(da.value());
                    if (nv != da.value()) {
                        if (delCopy == null) {
                            delCopy = new ArrayList<>(nDel);
                        }
                        delCopy.set(di, new HirCallArg(da.name(), nv));
                    }
                }
                if (delCopy != null) {
                    nDel = delCopy;
                }
            }
            List<HirExpr> nBody = materializeList(c.body(), true);
            if (np != c.params()
                    || nPre != c.preDelegateBody()
                    || nDel != c.delegateArgs()
                    || nBody != c.body()) {
                if (copy == null) {
                    copy = new ArrayList<>(ctors);
                }
                copy.set(i, c.withBodies(np, nPre, nDel, nBody));
            }
        }
        return copy != null ? copy : ctors;
    }

    private HirStaticBlock materializeStaticBlock(HirStaticBlock sb) {
        if (sb == null) {
            return null;
        }
        List<HirExpr> nb = materializeList(sb.body(), true);
        return nb == sb.body() ? sb : sb.withBody(nb);
    }

    private void materializeListInPlace(List<HirExpr> stmts) {
        int last = stmts.size() - 1;
        for (int i = 0; i < stmts.size(); i++) {
            HirExpr old = stmts.get(i);
            HirExpr nw = i < last ? materializeStmt(old) : materializeExpr(old);
            if (nw != old) {
                stmts.set(i, nw);
            }
        }
    }

    private List<HirExpr> materializeList(List<HirExpr> stmts, boolean widenLastOnly) {
        List<HirExpr> copy = null;
        int last = stmts.size() - 1;
        for (int i = 0; i < stmts.size(); i++) {
            HirExpr old = stmts.get(i);
            HirExpr nw = (widenLastOnly && i < last) ? materializeStmt(old) : materializeExpr(old);
            if (nw != old) {
                if (copy == null) {
                    copy = new ArrayList<>(stmts);
                }
                copy.set(i, nw);
            }
        }
        return copy != null ? copy : stmts;
    }

    private List<HirExpr> materializeStmtList(
            List<HirExpr> stmts, int trailingWidenRank, PrimitiveKind trailingTargetKind) {
        List<HirExpr> list = materializeList(stmts, true);
        if (list.isEmpty()) {
            return list;
        }
        int last = list.size() - 1;
        HirExpr tail = list.get(last);
        HirExpr updatedTail = tail;
        if (trailingWidenRank >= 0 && !alwaysExits(updatedTail)) {
            updatedTail = wrapWiden(updatedTail, trailingWidenRank);
        }
        if (trailingTargetKind != null && !alwaysExits(updatedTail)) {
            updatedTail = wrapConvert(updatedTail, trailingTargetKind);
        }
        if (updatedTail != tail) {
            List<HirExpr> copy = list == stmts ? new ArrayList<>(stmts) : list;
            copy.set(last, updatedTail);
            return copy;
        }
        return list;
    }

    private HirExpr materializeStmt(HirExpr expr) {
        if (expr == null) {
            return null;
        }
        HirExpr inner = materializeCore(expr);
        if (isSelfWideningConstruct(inner)) {
            return inner;
        }
        if (inner.meta().wideningRank() >= 0) {
            return HirTreeRewrite.withMeta(inner, inner.meta().withWideningRank(-1));
        }
        return inner;
    }

    private HirExpr materializeExpr(HirExpr expr) {
        if (expr == null) {
            return null;
        }
        HirExpr inner = materializeCore(expr);
        if (isSelfWideningConstruct(inner)) {
            return inner;
        }
        int rank = inner.meta().wideningRank();
        if (rank >= 0) {
            HirExpr stripped = HirTreeRewrite.withMeta(inner, inner.meta().withWideningRank(-1));
            return wrapWiden(stripped, rank);
        }
        return inner;
    }

    private static boolean isSelfWideningConstruct(HirExpr expr) {
        return expr instanceof HirExpr.Def
                || expr instanceof HirExpr.VarAssign
                || expr instanceof HirExpr.NameAssign
                || expr instanceof HirExpr.ThisFieldAssign
                || expr instanceof HirExpr.JavaFieldAssign
                || expr instanceof HirExpr.DynamicFieldAssign
                || expr instanceof HirExpr.For
                || expr instanceof HirExpr.Return;
    }

    private HirExpr materializeCore(HirExpr expr) {
        return switch (expr) {
            case HirExpr.Def d -> {
                HirExpr nv = d.value() != null ? materializeExpr(d.value()) : null;
                ExprMeta meta = d.meta();
                if (nv != null && meta.wideningRank() >= 0) {
                    nv = wrapWiden(nv, meta.wideningRank());
                    meta = meta.withWideningRank(-1);
                }
                if (nv != null && d.var() != null && !alwaysExits(nv)) {
                    nv = wrapConvert(nv, d.var().primitiveKind());
                }
                yield (nv == d.value() && meta == d.meta())
                        ? d
                        : new HirExpr.Def(d.name(), d.target(), d.var(), nv, meta);
            }
            case HirExpr.VarAssign a -> {
                HirExpr nv = materializeExpr(a.value());
                ExprMeta meta = a.meta();
                if (a.operator() == null) {
                    if (meta.wideningRank() >= 0) {
                        nv = wrapWiden(nv, meta.wideningRank());
                        meta = meta.withWideningRank(-1);
                    }
                    if (!alwaysExits(nv)) {
                        nv = wrapConvert(nv, a.var().primitiveKind());
                    }
                }
                yield (nv == a.value() && meta == a.meta())
                        ? a
                        : new HirExpr.VarAssign(
                                a.var(), a.operator(), nv, a.targetText(), a.valueText(), meta);
            }
            case HirExpr.NameAssign a -> {
                HirExpr nv = materializeExpr(a.value());
                ExprMeta meta = a.meta();
                if (a.operator() == null && meta.wideningRank() >= 0) {
                    nv = wrapWiden(nv, meta.wideningRank());
                    meta = meta.withWideningRank(-1);
                }
                yield (nv == a.value() && meta == a.meta())
                        ? a
                        : new HirExpr.NameAssign(
                                a.name(), a.operator(), nv, a.targetText(), a.valueText(), meta);
            }
            case HirExpr.ThisFieldInit a -> {
                HirExpr nv = materializeExpr(a.value());
                ExprMeta meta = a.meta();
                if (meta.wideningRank() >= 0) {
                    nv = wrapWiden(nv, meta.wideningRank());
                    meta = meta.withWideningRank(-1);
                }
                yield (nv == a.value() && meta == a.meta())
                        ? a
                        : new HirExpr.ThisFieldInit(
                                a.classId(), a.memberIndex(), a.memberName(), nv, meta);
            }
            case HirExpr.ThisFieldAssign a -> {
                HirExpr nr = materializeExpr(a.receiver());
                HirExpr nv = materializeExpr(a.value());
                ExprMeta meta = a.meta();
                if (a.operator() == null && meta.wideningRank() >= 0) {
                    nv = wrapWiden(nv, meta.wideningRank());
                    meta = meta.withWideningRank(-1);
                }
                yield (nr == a.receiver() && nv == a.value() && meta == a.meta())
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
                                meta);
            }
            case HirExpr.JavaFieldAssign a -> {
                HirExpr nr = materializeExpr(a.receiver());
                HirExpr nv = materializeExpr(a.value());
                ExprMeta meta = a.meta();
                if (a.operator() == null && meta.wideningRank() >= 0) {
                    nv = wrapWiden(nv, meta.wideningRank());
                    meta = meta.withWideningRank(-1);
                }
                yield (nr == a.receiver() && nv == a.value() && meta == a.meta())
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
                                meta);
            }
            case HirExpr.DynamicFieldAssign a -> {
                HirExpr nr = materializeExpr(a.receiver());
                HirExpr nv = materializeExpr(a.value());
                ExprMeta meta = a.meta();
                if (a.operator() == null && meta.wideningRank() >= 0) {
                    nv = wrapWiden(nv, meta.wideningRank());
                    meta = meta.withWideningRank(-1);
                }
                yield (nr == a.receiver() && nv == a.value() && meta == a.meta())
                        ? a
                        : new HirExpr.DynamicFieldAssign(
                                nr,
                                a.receiverText(),
                                a.memberName(),
                                a.operator(),
                                nv,
                                a.targetText(),
                                a.valueText(),
                                meta);
            }
            case HirExpr.Unary u -> {
                HirExpr no = materializeExpr(u.operand());
                if (u.op() == HirExpr.UnaryOp.NEGATE
                        && u.meta().kind().isPrimitive()
                        && no.meta().kind().isNumeric()
                        && !alwaysExits(no)) {
                    no = wrapConvert(no, u.meta().kind());
                }
                yield no == u.operand()
                        ? u
                        : new HirExpr.Unary(u.op(), no, u.operandText(), u.meta());
            }
            case HirExpr.Binary b -> {
                HirExpr nl = materializeExpr(b.left());
                HirExpr nr = materializeExpr(b.right());
                PrimitiveKind lk = nl.meta().kind();
                PrimitiveKind rk = nr.meta().kind();
                if (b.op().isComparison() && HirLoweringRules.canLowerComparison(b.op(), lk, rk)) {
                    PrimitiveKind common =
                            ((lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL)
                                            || (lk == PrimitiveKind.CHAR
                                                    && rk == PrimitiveKind.CHAR))
                                    ? lk
                                    : (lk.numericRank() >= rk.numericRank() ? lk : rk);
                    if (!alwaysExits(nl)) nl = wrapConvert(nl, common);
                    if (!alwaysExits(nr)) nr = wrapConvert(nr, common);
                } else if (b.meta().kind().isPrimitive() && lk.isNumeric() && rk.isNumeric()) {
                    PrimitiveKind target = b.meta().kind();
                    if (!alwaysExits(nl)) nl = wrapConvert(nl, target);
                    if (!alwaysExits(nr)) nr = wrapConvert(nr, target);
                }
                yield (nl == b.left() && nr == b.right())
                        ? b
                        : new HirExpr.Binary(b.op(), nl, nr, b.leftText(), b.rightText(), b.meta());
            }
            case HirExpr.Block b -> {
                List<HirExpr> ns = materializeStmtList(b.statements(), b.innerWideningRank(), null);
                int newInner = ns.isEmpty() ? b.innerWideningRank() : -1;
                yield (ns == b.statements() && newInner == b.innerWideningRank())
                        ? b
                        : new HirExpr.Block(
                                ns,
                                b.opensNamespaceScope(),
                                b.declaredNames(),
                                b.declaredVars(),
                                newInner,
                                b.meta());
            }
            case HirExpr.If i -> {
                HirExpr nc = materializeExpr(i.cond());
                HirExpr nt = materializeExpr(i.thenBranch());
                HirExpr ne = i.elseBranch() != null ? materializeExpr(i.elseBranch()) : null;
                PrimitiveKind target = i.meta().kind();
                if (!alwaysExits(nt)) {
                    nt = wrapConvert(nt, target);
                }
                if (ne != null && !alwaysExits(ne)) {
                    ne = wrapConvert(ne, target);
                }
                yield (nc == i.cond() && nt == i.thenBranch() && ne == i.elseBranch())
                        ? i
                        : new HirExpr.If(nc, nt, ne, i.meta());
            }
            case HirExpr.Match m -> materializeMatch(m);
            case HirExpr.For f -> {
                HirExpr ni = f.iterable() != null ? materializeExpr(f.iterable()) : null;
                HirExpr.CountedRangeLoop cr = f.countedRange();
                HirExpr.CountedRangeLoop ncr = cr;
                if (cr != null) {
                    HirExpr ns = materializeExpr(cr.start());
                    HirExpr ne = materializeExpr(cr.end());
                    HirExpr nk = cr.step() != null ? materializeExpr(cr.step()) : null;
                    if (ns != cr.start() || ne != cr.end() || nk != cr.step()) {
                        ncr =
                                new HirExpr.CountedRangeLoop(
                                        ns, ne, nk, cr.inclusive(), cr.descending(), cr.elemKind());
                    }
                }
                List<HirExpr> nb = materializeList(f.body(), true);
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
            case HirExpr.Try t -> materializeTry(t);
            case HirExpr.Return r -> {
                HirExpr nv = materializeExpr(r.value());
                ExprMeta meta = r.meta();
                if (meta.wideningRank() >= 0) {
                    nv = wrapWiden(nv, meta.wideningRank());
                    meta = meta.withWideningRank(-1);
                }
                if (currentFn != null && !alwaysExits(nv)) {
                    nv = wrapConvert(nv, currentFn.returnKind());
                }
                yield (nv == r.value() && meta == r.meta()) ? r : new HirExpr.Return(nv, meta);
            }
            case HirExpr.SelfRecurseCall c -> {
                List<HirExpr> na = materializeList(c.args(), false);
                if (currentFn != null
                        && (c.binding() == null || c.binding().isIdentityPositional())
                        && na.size() == currentFn.params().size()) {
                    na = convertArgsToParams(na, currentFn.params());
                }
                yield na == c.args()
                        ? c
                        : new HirExpr.SelfRecurseCall(c.functionId(), na, c.binding(), c.meta());
            }
            case HirExpr.DirectBabbleCall c -> {
                HirExpr nc = materializeExpr(c.callee());
                List<HirExpr> na = materializeList(c.args(), false);
                HirFunction targetFn =
                        c.targetFunctionId() >= 0 ? functions.get(c.targetFunctionId()) : null;
                if (targetFn != null
                        && (c.binding() == null || c.binding().isIdentityPositional())
                        && na.size() == targetFn.params().size()) {
                    na = convertArgsToParams(na, targetFn.params());
                }
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
            case HirExpr.RecordNew r -> {
                HirExpr nc = materializeExpr(r.classRef());
                List<HirExpr> na = materializeList(r.args(), false);
                if ((r.binding() == null || r.binding().isIdentityPositional())
                        && na.size() == r.paramKinds().length) {
                    na = convertArgsToKinds(na, r.paramKinds());
                }
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
                HirExpr nt = materializeExpr(r.target());
                List<HirExpr> na = materializeList(r.args(), false);
                if (na.size() == r.paramKinds().length) {
                    na = convertArgsToKinds(na, r.paramKinds());
                }
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
            default -> HirTreeRewrite.rewriteChildren(expr, this::materializeExpr);
        };
    }

    private HirExpr materializeMatch(HirExpr.Match m) {
        HirExpr ns = materializeExpr(m.scrutinee());
        List<HirExpr.MatchArm> narms = null;
        for (int idx = 0; idx < m.arms().size(); idx++) {
            HirExpr.MatchArm arm = m.arms().get(idx);
            HirPattern np = HirTreeRewrite.rewritePattern(arm.pattern(), this::materializeExpr);
            HirExpr ng = arm.guard() != null ? materializeExpr(arm.guard()) : null;
            HirExpr nb = materializeExpr(arm.body());
            int bw = arm.bodyWideningRank();
            if (bw >= 0 && !alwaysExits(nb)) {
                nb = wrapWiden(nb, bw);
                bw = -1;
            }
            if (np != arm.pattern()
                    || ng != arm.guard()
                    || nb != arm.body()
                    || bw != arm.bodyWideningRank()) {
                if (narms == null) {
                    narms = new ArrayList<>(m.arms());
                }
                narms.set(idx, new HirExpr.MatchArm(np, arm.boundVars(), ng, nb, bw, arm.line()));
            }
        }
        HirExpr.MatchElse ne = m.elseArm();
        if (ne != null) {
            HirExpr neb = materializeExpr(ne.body());
            int bw = ne.bodyWideningRank();
            if (bw >= 0 && !alwaysExits(neb)) {
                neb = wrapWiden(neb, bw);
                bw = -1;
            }
            if (neb != ne.body() || bw != ne.bodyWideningRank()) {
                ne = new HirExpr.MatchElse(ne.var(), neb, bw, ne.line());
            }
        }
        return (ns == m.scrutinee() && narms == null && ne == m.elseArm())
                ? m
                : new HirExpr.Match(
                        ns,
                        m.scrutineeVar(),
                        narms != null ? narms : m.arms(),
                        ne,
                        m.exhaustive(),
                        m.meta());
    }

    private HirExpr materializeTry(HirExpr.Try t) {
        List<HirExpr> ntb = materializeStmtList(t.tryBody(), t.tryWideningRank(), null);
        int newTryWiden = ntb.isEmpty() ? t.tryWideningRank() : -1;
        List<HirExpr.HirCatch> ncs = null;
        for (int idx = 0; idx < t.catches().size(); idx++) {
            HirExpr.HirCatch cc = t.catches().get(idx);
            List<HirExpr> ncb = materializeStmtList(cc.body(), cc.wideningRank(), null);
            int newCatchWiden = ncb.isEmpty() ? cc.wideningRank() : -1;
            if (ncb != cc.body() || newCatchWiden != cc.wideningRank()) {
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
                                newCatchWiden,
                                cc.line()));
            }
        }
        List<HirExpr> nfb = materializeList(t.finallyBody(), true);
        return (ntb == t.tryBody()
                        && newTryWiden == t.tryWideningRank()
                        && ncs == null
                        && nfb == t.finallyBody())
                ? t
                : new HirExpr.Try(
                        ntb,
                        t.tryDeclaredVars(),
                        t.tryOpensNamespaceScope(),
                        t.tryDeclaredNames(),
                        newTryWiden,
                        ncs != null ? ncs : t.catches(),
                        nfb,
                        t.finallyDeclaredVars(),
                        t.finallyOpensNamespaceScope(),
                        t.finallyDeclaredNames(),
                        t.finallyNothrow(),
                        t.hasFinally(),
                        t.meta());
    }

    private static List<HirExpr> convertArgsToParams(List<HirExpr> args, List<HirParam> params) {
        List<HirExpr> copy = null;
        for (int i = 0; i < args.size(); i++) {
            HirExpr arg = args.get(i);
            if (!alwaysExits(arg)) {
                HirExpr conv = wrapConvert(arg, params.get(i).kind());
                if (conv != arg) {
                    if (copy == null) {
                        copy = new ArrayList<>(args);
                    }
                    copy.set(i, conv);
                }
            }
        }
        return copy != null ? copy : args;
    }

    private static List<HirExpr> convertArgsToKinds(List<HirExpr> args, PrimitiveKind[] kinds) {
        List<HirExpr> copy = null;
        for (int i = 0; i < args.size(); i++) {
            HirExpr arg = args.get(i);
            if (!alwaysExits(arg)) {
                HirExpr conv = wrapConvert(arg, kinds[i]);
                if (conv != arg) {
                    if (copy == null) {
                        copy = new ArrayList<>(args);
                    }
                    copy.set(i, conv);
                }
            }
        }
        return copy != null ? copy : args;
    }

    private static HirExpr wrapWiden(HirExpr operand, int rank) {
        if (rank < 1 || rank > 5) {
            return operand;
        }
        PrimitiveKind opKind = operand.meta().kind();
        PrimitiveKind target = PrimitiveKind.ofNumericRank(rank);
        PrimitiveKind outKind =
                opKind.isPrimitive() && target != PrimitiveKind.NONE ? target : PrimitiveKind.NONE;
        if (opKind.isPrimitive() && opKind == outKind) {
            return operand;
        }
        ExprMeta meta = operand.meta().withKind(outKind).withWideningRank(-1).withCheckedType(null);
        return new HirExpr.Widen(operand, rank, meta);
    }

    private static HirExpr wrapConvert(HirExpr operand, PrimitiveKind targetKind) {
        if (operand == null || operand.meta().kind() == targetKind) {
            return operand;
        }
        ExprMeta meta =
                operand.meta().withKind(targetKind).withWideningRank(-1).withCheckedType(null);
        return new HirExpr.Convert(operand, targetKind, meta);
    }

    private static boolean alwaysExits(HirExpr expr) {
        if (expr == null) {
            return false;
        }
        return switch (expr) {
            case HirExpr.Return ignored -> true;
            case HirExpr.Throw ignored -> true;
            case HirExpr.Break ignored -> true;
            case HirExpr.Continue ignored -> true;
            case HirExpr.SelfTailCall ignored -> true;
            case HirExpr.Block b ->
                    !b.statements().isEmpty()
                            && alwaysExits(b.statements().get(b.statements().size() - 1));
            default -> false;
        };
    }
}
