package org.babblelang.opt.hir;

import java.util.ArrayList;
import java.util.List;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.opt.HirRewriter;

/**
 * Mandatory pre-emission normalization pass enforcing the JVM empty-operand-stack invariant for
 * {@link HirExpr.Try} ({@code BIP028-D9}, §7.2.1).
 *
 * <p>On the JVM, entering an exception handler clears the operand stack. Whenever a {@link
 * HirExpr.Try} appears inside a compound expression that would otherwise leave values on the
 * operand stack (binary RHS, call/array/overload arguments, member/namespace/field assignments),
 * this pass hoists preceding sibling expressions and the {@code Try}-bearing operand(s) into
 * sequential synthetic temp {@link HirVar}s in a wrapping {@link HirExpr.Block}, preserving strict
 * left-to-right evaluation order, unboxed primitive kinds, and source lines.
 */
public final class HirTryNormalize {
    private HirTryNormalize() {}

    public static HirModule normalize(HirModule module) {
        int[] nextVarId = {module.nextVarId()};
        return HirRewriter.rewriteModule(
                module,
                (ownerMethodId, currentFnId, stmts) ->
                        HirRewriter.rewriteList(
                                stmts, s -> normalizeExpr(s, ownerMethodId, nextVarId)),
                defaultExpr -> normalizeExpr(defaultExpr, 0, nextVarId),
                () -> nextVarId[0]);
    }

    private static HirExpr normalizeExpr(HirExpr expr, int ownerId, int[] nextVarId) {
        if (expr == null || !containsTry(expr)) {
            return expr;
        }
        // Bottom-up: normalize children first so inner compound expressions inside Try/If/etc. are
        // normalized.
        HirExpr cur =
                HirRewriter.rewriteChildren(
                        expr, child -> normalizeExpr(child, ownerId, nextVarId));
        return spillIfNeeded(cur, ownerId, nextVarId);
    }

    private static HirExpr spillIfNeeded(HirExpr expr, int ownerId, int[] nextVarId) {
        return switch (expr) {
            case HirExpr.Def d -> {
                if (d.var() == null && d.value() != null && containsTry(d.value())) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr valRef = s.hoist(d.value());
                    yield s.wrap(
                            new HirExpr.Def(d.name(), d.target(), null, valRef, d.meta()),
                            d.meta());
                }
                yield d;
            }
            case HirExpr.NameAssign a -> {
                if (a.operator() == null && containsTry(a.value())) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr valRef = s.hoist(a.value());
                    yield s.wrap(
                            new HirExpr.NameAssign(
                                    a.name(),
                                    null,
                                    valRef,
                                    a.targetText(),
                                    a.valueText(),
                                    a.meta()),
                            a.meta());
                }
                yield a;
            }
            case HirExpr.ThisFieldInit a -> {
                if (containsTry(a.value())) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr valRef = s.hoist(a.value());
                    yield s.wrap(
                            new HirExpr.ThisFieldInit(
                                    a.classId(), a.memberIndex(), a.memberName(), valRef, a.meta()),
                            a.meta());
                }
                yield a;
            }
            case HirExpr.ThisFieldAssign a -> {
                if (a.operator() == null && containsTry(a.value())) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr recvRef = s.hoist(a.receiver());
                    HirExpr valRef = s.hoist(a.value());
                    yield s.wrap(
                            new HirExpr.ThisFieldAssign(
                                    recvRef,
                                    a.classId(),
                                    a.memberIndex(),
                                    a.memberName(),
                                    null,
                                    valRef,
                                    a.targetText(),
                                    a.valueText(),
                                    a.meta()),
                            a.meta());
                }
                yield a;
            }
            case HirExpr.JavaFieldAssign a -> {
                if (a.operator() == null && containsTry(a.value())) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr recvRef = a.pureStaticReceiver() ? a.receiver() : s.hoist(a.receiver());
                    HirExpr valRef = s.hoist(a.value());
                    yield s.wrap(
                            new HirExpr.JavaFieldAssign(
                                    recvRef,
                                    a.receiverText(),
                                    a.pureStaticReceiver(),
                                    a.target(),
                                    null,
                                    valRef,
                                    a.targetText(),
                                    a.valueText(),
                                    a.meta()),
                            a.meta());
                }
                yield a;
            }
            case HirExpr.DynamicFieldAssign a -> {
                if (a.operator() == null && containsTry(a.value())) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr recvRef = s.hoist(a.receiver());
                    HirExpr valRef = s.hoist(a.value());
                    yield s.wrap(
                            new HirExpr.DynamicFieldAssign(
                                    recvRef,
                                    a.receiverText(),
                                    a.memberName(),
                                    null,
                                    valRef,
                                    a.targetText(),
                                    a.valueText(),
                                    a.meta()),
                            a.meta());
                }
                yield a;
            }
            case HirExpr.Binary b -> {
                if (containsTry(b.right())) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr lRef = s.hoist(b.left());
                    HirExpr rRef = s.hoist(b.right());
                    yield s.wrap(
                            new HirExpr.Binary(
                                    b.op(), lRef, rRef, b.leftText(), b.rightText(), b.meta()),
                            b.meta());
                }
                yield b;
            }
            case HirExpr.Range r -> {
                if (containsTry(r.end())) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr sRef = s.hoist(r.start());
                    HirExpr eRef = s.hoist(r.end());
                    yield s.wrap(
                            new HirExpr.Range(
                                    sRef,
                                    eRef,
                                    r.inclusive(),
                                    r.elemKind(),
                                    r.stringElement(),
                                    r.meta()),
                            r.meta());
                }
                yield r;
            }
            case HirExpr.ArrayCreate c -> {
                int lastTry = lastTryIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    List<HirExpr> na = s.hoistPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.ArrayCreate(
                                    c.kind(),
                                    c.elemKind(),
                                    c.componentInternalName(),
                                    c.componentIsJavaInterface(),
                                    c.componentClass(),
                                    na,
                                    c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.ArrayOp op -> {
                int lastTry = lastTryIndex(op.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr nr = s.hoist(op.receiver());
                    List<HirExpr> na = s.hoistPrefix(op.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.ArrayOp(
                                    op.kind(),
                                    nr,
                                    op.receiverText(),
                                    op.elemKind(),
                                    op.arrayClass(),
                                    op.componentClass(),
                                    na,
                                    op.meta()),
                            op.meta());
                }
                yield op;
            }
            case HirExpr.CoreCollectionCall c -> {
                int lastTry = lastTryCallArgIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    List<HirCallArg> na = s.hoistCallArgPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.CoreCollectionCall(c.collectionKind(), na, c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.SuperCall c -> {
                int lastTry = lastTryCallArgIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    List<HirCallArg> na = s.hoistCallArgPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.SuperCall(
                                    c.thisVar(),
                                    c.ownerClassId(),
                                    c.memberName(),
                                    c.targetInfo(),
                                    c.superTypeRef(),
                                    na,
                                    c.binding(),
                                    c.callSite(),
                                    c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.SelfRecurseCall c -> {
                int lastTry = lastTryIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    List<HirExpr> na = s.hoistPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.SelfRecurseCall(c.functionId(), na, c.binding(), c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.SelfTailCall c -> {
                int lastTry = lastTryCallArgIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    List<HirCallArg> na = s.hoistCallArgPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.SelfTailCall(c.functionId(), na, c.binding(), c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.DirectBabbleCall c -> {
                int lastTry = lastTryIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr nc = s.hoist(c.callee());
                    List<HirExpr> na = s.hoistPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.DirectBabbleCall(
                                    nc, c.targetFunctionId(), na, c.binding(), c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.DirectThisMethodCall c -> {
                int lastTry = lastTryCallArgIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr nr = s.hoist(c.receiver());
                    List<HirCallArg> na = s.hoistCallArgPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.DirectThisMethodCall(
                                    nr,
                                    c.receiverText(),
                                    c.classId(),
                                    c.memberName(),
                                    c.overloadAltIndex(),
                                    c.targetFunctionId(),
                                    na,
                                    c.binding(),
                                    c.callSite(),
                                    c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.InterfaceCall c -> {
                int lastTry = lastTryIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr nr = s.hoist(c.receiver());
                    List<HirExpr> na = s.hoistPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.InterfaceCall(
                                    nr,
                                    c.receiverText(),
                                    c.selectorLine(),
                                    c.interfaceTypeId(),
                                    c.memberName(),
                                    c.signature(),
                                    na,
                                    c.binding(),
                                    c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.JavaConstructorCall c -> {
                int lastTry = lastTryIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr nc = c.pureStaticCallee() ? c.callee() : s.hoist(c.callee());
                    List<HirExpr> na = s.hoistPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.JavaConstructorCall(
                                    nc,
                                    c.calleeText(),
                                    c.pureStaticCallee(),
                                    c.target(),
                                    na,
                                    c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.JavaMethodCall c -> {
                int lastTry = lastTryIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr nr = c.pureStaticReceiver() ? c.receiver() : s.hoist(c.receiver());
                    List<HirExpr> na = s.hoistPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.JavaMethodCall(
                                    nr,
                                    c.receiverText(),
                                    c.selectorLine(),
                                    c.pureStaticReceiver(),
                                    c.target(),
                                    na,
                                    c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.CoreMethodCall c -> {
                int lastTry = lastTryIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr nr = s.hoist(c.receiver());
                    List<HirExpr> na = s.hoistPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.CoreMethodCall(
                                    nr,
                                    c.receiverText(),
                                    c.selectorLine(),
                                    c.coreKind(),
                                    c.memberName(),
                                    na,
                                    c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.DynamicCall c -> {
                int lastTry = lastTryCallArgIndex(c.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr nc = s.hoist(c.callee());
                    List<HirCallArg> na = s.hoistCallArgPrefix(c.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.DynamicCall(
                                    nc, na, c.binding(), c.babbleSig(), c.callSite(), c.meta()),
                            c.meta());
                }
                yield c;
            }
            case HirExpr.SafeCall c -> {
                int lastTry = lastTryCallArgIndex(c.args());
                if (lastTry >= 0) {
                    Spill outerSpill = new Spill(ownerId, nextVarId);
                    HirExpr recvRef = outerSpill.hoist(c.receiver());
                    Spill innerSpill = new Spill(ownerId, nextVarId);
                    List<HirCallArg> na = innerSpill.hoistCallArgPrefix(c.args(), lastTry);
                    HirExpr safeCall =
                            new HirExpr.SafeCall(
                                    recvRef,
                                    c.receiverText(),
                                    c.memberName(),
                                    c.selectorLine(),
                                    c.selectorCheckedType(),
                                    na,
                                    c.binding(),
                                    c.babbleSig(),
                                    c.callSite(),
                                    c.meta());
                    HirExpr nonNullBranch = innerSpill.wrap(safeCall, c.meta());
                    ExprMeta condMeta =
                            new ExprMeta(
                                    c.meta().line(),
                                    PrimitiveKind.BOOL,
                                    -1,
                                    null,
                                    EffectSet.EMPTY,
                                    true,
                                    false);
                    ExprMeta nullMeta =
                            new ExprMeta(
                                    c.meta().line(),
                                    PrimitiveKind.NONE,
                                    -1,
                                    null,
                                    EffectSet.EMPTY,
                                    true,
                                    false);
                    HirExpr isNull =
                            new HirExpr.Binary(
                                    HirExpr.BinaryOp.EQ,
                                    recvRef,
                                    new HirExpr.Const(null, nullMeta),
                                    c.receiverText(),
                                    "null",
                                    condMeta);
                    HirExpr guard =
                            new HirExpr.If(
                                    isNull,
                                    new HirExpr.Const(null, nullMeta),
                                    nonNullBranch,
                                    c.meta());
                    yield outerSpill.wrap(guard, c.meta());
                }
                yield c;
            }
            case HirExpr.MakeOverloadSet m -> {
                int lastTry = lastTryIndex(m.operands());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    List<HirExpr> no = s.hoistPrefix(m.operands(), lastTry);
                    yield s.wrap(new HirExpr.MakeOverloadSet(no, m.metas(), m.meta()), m.meta());
                }
                yield m;
            }
            case HirExpr.OverloadDispatch d -> {
                int lastTry = lastTryIndex(d.args());
                if (lastTry >= 0) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr nc = s.hoist(d.callee());
                    List<HirExpr> na = s.hoistPrefix(d.args(), lastTry);
                    yield s.wrap(
                            new HirExpr.OverloadDispatch(
                                    nc, d.calleeText(), d.callSite(), na, d.branches(), d.meta()),
                            d.meta());
                }
                yield d;
            }
            case HirExpr.RecordNew r -> {
                int lastTry = lastTryIndex(r.args());
                if (lastTry >= 0 || containsTry(r.classRef())) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr ncr = s.hoist(r.classRef());
                    List<HirExpr> na = lastTry >= 0 ? s.hoistPrefix(r.args(), lastTry) : r.args();
                    yield s.wrap(
                            new HirExpr.RecordNew(
                                    r.classId(),
                                    r.classTypeId(),
                                    r.classJvmName(),
                                    ncr,
                                    r.classRefIsInstance(),
                                    r.paramKinds(),
                                    na,
                                    r.binding(),
                                    r.meta()),
                            r.meta());
                }
                yield r;
            }
            case HirExpr.RecordReuse r -> {
                int lastTry = lastTryIndex(r.args());
                if (lastTry >= 0 || containsTry(r.target())) {
                    Spill s = new Spill(ownerId, nextVarId);
                    HirExpr nt = s.hoist(r.target());
                    List<HirExpr> na = lastTry >= 0 ? s.hoistPrefix(r.args(), lastTry) : r.args();
                    yield s.wrap(
                            new HirExpr.RecordReuse(
                                    nt,
                                    r.classId(),
                                    r.classTypeId(),
                                    r.classJvmName(),
                                    r.paramKinds(),
                                    na,
                                    r.meta()),
                            r.meta());
                }
                yield r;
            }
            default -> expr;
        };
    }

    private static final class Spill {
        final int ownerId;
        final int[] nextVarId;
        final List<HirVar> vars = new ArrayList<>();
        final List<HirExpr> defs = new ArrayList<>();

        Spill(int ownerId, int[] nextVarId) {
            this.ownerId = ownerId;
            this.nextVarId = nextVarId;
        }

        HirExpr hoist(HirExpr expr) {
            PrimitiveKind vk = effectiveKind(expr.meta());
            int id = nextVarId[0]++;
            String name = "$try" + id;
            HirVar var =
                    new HirVar(
                            id,
                            name,
                            Resolution.Variable.Kind.DEFINITION,
                            ownerId,
                            vk,
                            false,
                            true,
                            false,
                            true);
            vars.add(var);
            ExprMeta defMeta =
                    new ExprMeta(
                            expr.meta().line(),
                            vk,
                            -1,
                            null,
                            EffectSet.EMPTY,
                            expr.meta().deeplyImmutable(),
                            expr.meta().stringType());
            defs.add(
                    new HirExpr.Def(
                            name,
                            new LoweringTargets.DefTarget(LoweringTargets.DefKind.LEXICAL, -1),
                            var,
                            expr,
                            defMeta));
            ExprMeta readMeta =
                    new ExprMeta(
                            expr.meta().line(),
                            vk,
                            -1,
                            null,
                            EffectSet.EMPTY,
                            expr.meta().deeplyImmutable(),
                            expr.meta().stringType());
            return new HirExpr.VarRead(var, readMeta);
        }

        List<HirExpr> hoistPrefix(List<HirExpr> exprs, int lastIdx) {
            List<HirExpr> out = new ArrayList<>(exprs.size());
            for (int i = 0; i < exprs.size(); i++) {
                out.add(i <= lastIdx ? hoist(exprs.get(i)) : exprs.get(i));
            }
            return out;
        }

        List<HirCallArg> hoistCallArgPrefix(List<HirCallArg> args, int lastIdx) {
            List<HirCallArg> out = new ArrayList<>(args.size());
            for (int i = 0; i < args.size(); i++) {
                HirCallArg a = args.get(i);
                out.add(i <= lastIdx ? a.withValue(hoist(a.value())) : a);
            }
            return out;
        }

        HirExpr wrap(HirExpr tail, ExprMeta meta) {
            List<HirExpr> stmts = new ArrayList<>(defs.size() + 1);
            stmts.addAll(defs);
            stmts.add(tail);
            return new HirExpr.Block(stmts, false, new String[0], vars, -1, meta);
        }
    }

    private static PrimitiveKind effectiveKind(ExprMeta meta) {
        if (!meta.kind().isPrimitive()) {
            return PrimitiveKind.NONE;
        }
        int rank = meta.wideningRank();
        if (rank >= 1 && rank <= 5) {
            return switch (rank) {
                case 1 -> PrimitiveKind.SHORT;
                case 2 -> PrimitiveKind.INT;
                case 3 -> PrimitiveKind.LONG;
                case 4 -> PrimitiveKind.FLOAT;
                case 5 -> PrimitiveKind.DOUBLE;
                default -> meta.kind();
            };
        }
        return meta.kind();
    }

    private static int lastTryIndex(List<HirExpr> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (containsTry(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int lastTryCallArgIndex(List<HirCallArg> list) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (containsTry(list.get(i).value())) {
                return i;
            }
        }
        return -1;
    }

    /** Whether {@code expr} contains a {@link HirExpr.Try} in the same method body. */
    public static boolean containsTry(HirExpr expr) {
        if (expr == null) {
            return false;
        }
        return switch (expr) {
            case HirExpr.Try t -> true;
            case HirExpr.Const c -> false;
            case HirExpr.IntParseFallback f -> false;
            case HirExpr.CoreCollectionRef cr -> false;
            case HirExpr.CoreExceptionRef ce -> false;
            case HirExpr.VarRead vr -> false;
            case HirExpr.NameRead nr -> false;
            case HirExpr.ThisRef tr -> false;
            case HirExpr.RecurseRef rr -> false;
            case HirExpr.Continue ct -> false;
            case HirExpr.FunctionRef fr -> false;
            case HirExpr.ClassExpr cls -> false;
            case HirExpr.InterfaceExpr iface -> false;
            case HirExpr.Def d -> containsTry(d.value());
            case HirExpr.VarAssign a -> containsTry(a.value());
            case HirExpr.NameAssign a -> containsTry(a.value());
            case HirExpr.ThisFieldInit a -> containsTry(a.value());
            case HirExpr.ThisFieldAssign a -> containsTry(a.receiver()) || containsTry(a.value());
            case HirExpr.JavaFieldAssign a -> containsTry(a.receiver()) || containsTry(a.value());
            case HirExpr.DynamicFieldAssign a ->
                    containsTry(a.receiver()) || containsTry(a.value());
            case HirExpr.ThisFieldGet g -> containsTry(g.receiver());
            case HirExpr.JavaFieldGet g -> containsTry(g.receiver());
            case HirExpr.ModuleExportGet g -> containsTry(g.receiver());
            case HirExpr.DynamicSelect s -> containsTry(s.receiver());
            case HirExpr.Unary u -> containsTry(u.operand());
            case HirExpr.Binary b -> containsTry(b.left()) || containsTry(b.right());
            case HirExpr.Range r -> containsTry(r.start()) || containsTry(r.end());
            case HirExpr.Logic l -> containsTry(l.left()) || containsTry(l.right());
            case HirExpr.Is i -> containsTry(i.left());
            case HirExpr.As a -> containsTry(a.left());
            case HirExpr.NonNull n -> containsTry(n.operand());
            case HirExpr.SpreadAdapt s -> containsTry(s.value());
            case HirExpr.JavaSamAdapt j -> containsTry(j.value());
            case HirExpr.TypeApply t -> containsTry(t.inner());
            case HirExpr.Block b -> anyContainsTry(b.statements());
            case HirExpr.If i ->
                    containsTry(i.cond())
                            || containsTry(i.thenBranch())
                            || containsTry(i.elseBranch());
            case HirExpr.Match m -> matchContainsTry(m);
            case HirExpr.While w -> containsTry(w.cond()) || containsTry(w.body());
            case HirExpr.For f ->
                    (f.countedRange() != null
                                    ? (containsTry(f.countedRange().start())
                                            || containsTry(f.countedRange().end())
                                            || containsTry(f.countedRange().step()))
                                    : containsTry(f.iterable()))
                            || anyContainsTry(f.body());
            case HirExpr.Break b -> containsTry(b.value());
            case HirExpr.Return r -> containsTry(r.value());
            case HirExpr.Throw th -> containsTry(th.value());
            case HirExpr.ArrayCreate c -> anyContainsTry(c.args());
            case HirExpr.ArrayOp op -> containsTry(op.receiver()) || anyContainsTry(op.args());
            case HirExpr.CoreCollectionCall c -> anyCallArgContainsTry(c.args());
            case HirExpr.SuperCall c -> anyCallArgContainsTry(c.args());
            case HirExpr.SelfRecurseCall c -> anyContainsTry(c.args());
            case HirExpr.SelfTailCall c -> anyCallArgContainsTry(c.args());
            case HirExpr.DirectBabbleCall c -> containsTry(c.callee()) || anyContainsTry(c.args());
            case HirExpr.DirectThisMethodCall c ->
                    containsTry(c.receiver()) || anyCallArgContainsTry(c.args());
            case HirExpr.InterfaceCall c -> containsTry(c.receiver()) || anyContainsTry(c.args());
            case HirExpr.JavaConstructorCall c ->
                    (!c.pureStaticCallee() && containsTry(c.callee())) || anyContainsTry(c.args());
            case HirExpr.JavaMethodCall c ->
                    (!c.pureStaticReceiver() && containsTry(c.receiver()))
                            || anyContainsTry(c.args());
            case HirExpr.CoreMethodCall c -> containsTry(c.receiver()) || anyContainsTry(c.args());
            case HirExpr.SafeCall c -> containsTry(c.receiver()) || anyCallArgContainsTry(c.args());
            case HirExpr.DynamicCall c ->
                    containsTry(c.callee()) || anyCallArgContainsTry(c.args());
            case HirExpr.MakeOverloadSet m -> anyContainsTry(m.operands());
            case HirExpr.OverloadSelect s -> containsTry(s.set());
            case HirExpr.OverloadDispatch d -> containsTry(d.callee()) || anyContainsTry(d.args());
            case HirExpr.RecordNew r -> containsTry(r.classRef()) || anyContainsTry(r.args());
            case HirExpr.RecordReuse r -> containsTry(r.target()) || anyContainsTry(r.args());
            case HirExpr.Move m -> containsTry(m.operand());
            case HirExpr.Freeze f -> containsTry(f.operand());
            case HirExpr.Narrow n -> containsTry(n.operand());
            case HirExpr.Convert cv -> containsTry(cv.operand());
            case HirExpr.Widen w -> containsTry(w.operand());
        };
    }

    private static boolean matchContainsTry(HirExpr.Match m) {
        if (containsTry(m.scrutinee())) {
            return true;
        }
        for (HirExpr.MatchArm arm : m.arms()) {
            if (patternContainsTry(arm.pattern())
                    || containsTry(arm.guard())
                    || containsTry(arm.body())) {
                return true;
            }
        }
        return m.elseArm() != null && containsTry(m.elseArm().body());
    }

    private static boolean patternContainsTry(org.babblelang.ir.hir.HirPattern p) {
        if (p == null) {
            return false;
        }
        return switch (p) {
            case org.babblelang.ir.hir.HirPattern.Const ignored -> false;
            case org.babblelang.ir.hir.HirPattern.Wildcard ignored -> false;
            case org.babblelang.ir.hir.HirPattern.TypeTest ignored -> false;
            case org.babblelang.ir.hir.HirPattern.ValueExpr v -> containsTry(v.expr());
            case org.babblelang.ir.hir.HirPattern.Bind b -> patternContainsTry(b.inner());
            case org.babblelang.ir.hir.HirPattern.Or o -> {
                for (org.babblelang.ir.hir.HirPattern alt : o.alternatives()) {
                    if (patternContainsTry(alt)) {
                        yield true;
                    }
                }
                yield false;
            }
            case org.babblelang.ir.hir.HirPattern.Relational r -> containsTry(r.rhs());
            case org.babblelang.ir.hir.HirPattern.RangeTest r ->
                    containsTry(r.lo()) || containsTry(r.hi()) || containsTry(r.step());
            case org.babblelang.ir.hir.HirPattern.Membership m -> containsTry(m.container());
            case org.babblelang.ir.hir.HirPattern.RecordDestructure rd -> {
                for (org.babblelang.ir.hir.HirPattern fp : rd.fieldPatterns()) {
                    if (patternContainsTry(fp)) {
                        yield true;
                    }
                }
                yield false;
            }
            case org.babblelang.ir.hir.HirPattern.SequenceDestructure sd -> {
                for (org.babblelang.ir.hir.HirPattern ep : sd.elemPatterns()) {
                    if (patternContainsTry(ep)) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }

    private static boolean anyContainsTry(List<HirExpr> list) {
        for (HirExpr e : list) {
            if (containsTry(e)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyCallArgContainsTry(List<HirCallArg> list) {
        for (HirCallArg a : list) {
            if (containsTry(a.value())) {
                return true;
            }
        }
        return false;
    }
}
