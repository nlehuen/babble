package org.babblelang.ir.mir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirLoweringRules;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.ir.mir.MirSsaBuilder.BlockBuilder;

/**
 * Lowers an ANTLR-free {@link HirModule} into per-function SSA {@link MirModule} ({@code BIP014-I3}
 * §6.4).
 *
 * <p>Every non-namespace-scoped {@link HirFunction} (including methods and functions containing
 * {@code BIP028} {@code try}/{@code catch}/{@code finally}/{@code throw}) is lowered into a flat
 * basic-block SSA CFG using Braun-style block-parameter construction. Namespace-scoped functions
 * are explicitly recorded in {@link MirModule#fallbacks()} with reason {@code "namespace-scoped"}
 * so fallback decisions remain visible in {@link MirCanonicalPrinter}.
 */
public final class MirLowerer {
    private MirLowerer() {}

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
     * Deterministic child-expression extractor for {@link MirInst.OpaqueExpr} templates. Shared by
     * {@link MirLowerer} and {@code MirEmitter} so SSA operands align 1-to-1 with the child
     * expressions of any opaque HIR node.
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
            default ->
                    throw new IllegalStateException(
                            "Unsupported opaque HIR template : " + expr.getClass().getSimpleName());
        };
    }

    private static List<HirExpr> extractCallArgs(List<HirCallArg> args) {
        List<HirExpr> out = new ArrayList<>(args.size());
        for (HirCallArg a : args) {
            out.add(a.value());
        }
        return out;
    }

    // ========================================================================= per-function
    // lowerer

    static final class FunctionLowerer {
        final HirModule module;
        final HirFunction fn;
        final MirSsaBuilder ssa;
        final MirMatchLowerer matchLowerer;
        final MirControlFlowLowerer controlFlow;
        final MirCallAndRecordLowerer callLowerer;

        FunctionLowerer(HirModule module, HirFunction fn) {
            this.module = module;
            this.fn = fn;
            this.ssa = new MirSsaBuilder(fn, MirVarPinningScanner.scan(fn));
            this.matchLowerer = new MirMatchLowerer(this, this.ssa);
            this.controlFlow = new MirControlFlowLowerer(this, this.ssa);
            this.callLowerer = new MirCallAndRecordLowerer(this, this.ssa);
        }

        MirFunction lower() {
            // Create entry block (sealed immediately: no predecessors).
            BlockBuilder entry = ssa.newBlock(List.of(), false, true);
            ssa.cur = entry;

            // Bind function parameters as SSA values (for non-pinned params) or pinned slots.
            List<MirVal> mirParams = new ArrayList<>(fn.params().size());
            List<HirVar> paramVars = new ArrayList<>(fn.params().size());
            for (int i = 0; i < fn.params().size(); i++) {
                HirParam p = fn.params().get(i);
                HirVar v = p.var();
                if (v != null) {
                    ssa.varById.put(v.id(), v);
                    paramVars.add(v);
                    if (!ssa.pinnedVarIds.contains(v.id())) {
                        MirVal pv = ssa.newVal(v.primitiveKind(), v.name());
                        mirParams.add(pv);
                        entry.currentDef.put(v.id(), pv);
                        if (p.isUnique()) {
                            ssa.valAliases.put(pv.id(), MirEffect.AliasClass.uniqueParam(i));
                        }
                    } else {
                        // MirFunction.params() holds SSA params matching paramVars for non-pinned
                        // params only, so MirEmitter knows which params are read via SSA vs pinned
                        // slot.
                        paramVars.remove(paramVars.size() - 1);
                    }
                }
            }

            // Lower function body statements.
            MirVal bodyVal = lowerStatements(fn.body(), fn.line());
            if (!ssa.cur.isTerminated()) {
                bodyVal = ssa.applyWideningRank(bodyVal, fn.bodyWideningRank(), fn.line());
                bodyVal = ssa.convertVal(bodyVal, fn.returnKind(), fn.line());
                ssa.terminateFixed(new MirTerminator.Ret(bodyVal, fn.returnKind(), -1, fn.line()));
            }

            List<MirBlock> built = ssa.buildAndFinalizeBlocks();

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
                    ssa.nextValId(),
                    ssa.nextBlockId(),
                    List.copyOf(ssa.pinnedVarsById.values()),
                    ssa.valAliases);
        }

        // ------------------------------------------------------------------ statement & expression
        // lowering

        MirVal lowerStatements(List<HirExpr> stmts, int fallbackLine) {
            if (stmts.isEmpty()) {
                return ssa.emitConst(null, PrimitiveKind.NONE, fallbackLine);
            }
            int last = stmts.size() - 1;
            for (int i = 0; i < last; i++) {
                if (ssa.cur.isTerminated()) {
                    return ssa.emitConst(null, PrimitiveKind.NONE, stmts.get(i).meta().line());
                }
                lowerExpr(stmts.get(i));
            }
            if (ssa.cur.isTerminated()) {
                return ssa.emitConst(null, PrimitiveKind.NONE, stmts.get(last).meta().line());
            }
            return lowerAndWiden(stmts.get(last));
        }

        MirVal lowerAndWiden(HirExpr expr) {
            MirVal v = lowerExpr(expr);
            if (ssa.cur.isTerminated()) {
                return v;
            }
            v = ssa.applyWideningRank(v, expr.meta().wideningRank(), expr.meta().line());
            ssa.recordExprAlias(v, expr.meta());
            return v;
        }

        MirVal lowerAs(HirExpr expr, PrimitiveKind desired) {
            MirVal v = lowerAndWiden(expr);
            if (ssa.cur.isTerminated()) {
                return v;
            }
            if (v.kind() == PrimitiveKind.NONE && desired.isPrimitive()) {
                PrimitiveKind exprKind = expr.meta().kind();
                if (exprKind.isPrimitive()
                        && expr.meta().wideningRank() < 0
                        && exprKind != desired) {
                    v = ssa.convertVal(v, exprKind, expr.meta().line());
                }
            }
            return ssa.convertVal(v, desired, expr.meta().line());
        }

        MirVal lowerBoxed(HirExpr expr) {
            return lowerAs(expr, PrimitiveKind.NONE);
        }

        MirVal lowerExpr(HirExpr expr) {
            int ln = expr.meta().line();
            MirVal res =
                    switch (expr) {
                        case HirExpr.Const c ->
                                ssa.emitConst(
                                        c.value(),
                                        MirCallAndRecordLowerer.constKind(c.value()),
                                        ln);
                        case HirExpr.VarRead v -> ssa.readVar(v.var(), ln);
                        case HirExpr.NameRead nr -> lowerNameRead(nr);
                        case HirExpr.ThisRef t ->
                                t.var() != null
                                        ? ssa.readVar(t.var(), ln)
                                        : callLowerer.lowerOpaque(expr);
                        case HirExpr.RecurseRef r ->
                                r.var() != null
                                        ? ssa.readVar(r.var(), ln)
                                        : callLowerer.lowerOpaque(expr);
                        case HirExpr.Def d -> lowerDef(d);
                        case HirExpr.VarAssign a -> lowerVarAssign(a);
                        case HirExpr.NameAssign a -> lowerNameAssign(a);
                        case HirExpr.ThisFieldGet g -> lowerThisFieldGet(g);
                        case HirExpr.ThisFieldAssign a -> lowerThisFieldAssign(a);
                        case HirExpr.JavaFieldGet g -> lowerJavaFieldGet(g);
                        case HirExpr.JavaFieldAssign a -> lowerJavaFieldAssign(a);
                        case HirExpr.ModuleExportGet g -> callLowerer.lowerModuleExportGet(g);
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
                        case HirExpr.Narrow n -> lowerAndWiden(n.operand());
                        case HirExpr.Convert cv -> lowerAs(cv.operand(), cv.targetKind());
                        case HirExpr.Widen w -> {
                            MirVal v = lowerAndWiden(w.operand());
                            if (ssa.cur.isTerminated()) yield v;
                            yield ssa.applyWideningRank(v, w.wideningRank(), ln);
                        }
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
                        case HirExpr.SelfTailCall c -> callLowerer.lowerSelfTailCall(c);
                        case HirExpr.SelfRecurseCall c -> callLowerer.lowerSelfRecurseCall(c);
                        case HirExpr.DirectBabbleCall c -> callLowerer.lowerDirectBabbleCall(c);
                        case HirExpr.DirectThisMethodCall c ->
                                callLowerer.lowerDirectThisMethodCall(c);
                        case HirExpr.InterfaceCall c -> callLowerer.lowerInterfaceCall(c);
                        case HirExpr.JavaMethodCall c -> callLowerer.lowerJavaMethodCall(c);
                        case HirExpr.JavaConstructorCall c ->
                                callLowerer.lowerJavaConstructorCall(c);
                        case HirExpr.SafeCall c -> callLowerer.lowerSafeCall(c);
                        case HirExpr.RecordNew r -> callLowerer.lowerRecordNew(r);
                        case HirExpr.RecordReuse r -> callLowerer.lowerRecordReuse(r);
                        default -> callLowerer.lowerOpaque(expr);
                    };
            ssa.recordExprAlias(res, expr.meta());
            return res;
        }

        // ------------------------------------------------------------------ defs & assignments

        private MirVal lowerDef(HirExpr.Def d) {
            int ln = d.meta().line();
            if (d.target().kind() == LoweringTargets.DefKind.REQUIRED) {
                return ssa.emitConst(null, PrimitiveKind.NONE, ln);
            }
            if (d.var() == null) {
                return callLowerer.lowerOpaque(d);
            }
            MirVal v =
                    d.value() != null
                            ? lowerExpr(d.value())
                            : ssa.emitConst(null, PrimitiveKind.NONE, ln);
            if (ssa.cur.isTerminated()) {
                return v;
            }
            v = ssa.applyWideningRank(v, d.meta().wideningRank(), ln);
            MirVal conv = ssa.convertVal(v, d.var().primitiveKind(), ln);
            ssa.defVar(d.var(), conv, ln);
            return conv;
        }

        private MirVal lowerCompoundRhs(
                MirVal oldVal,
                PrimitiveKind targetKind,
                String operator,
                HirExpr rhsExpr,
                String targetText,
                String valueText,
                ExprMeta meta,
                boolean convertDynamicResult) {
            int ln = meta.line();
            HirExpr.BinaryOp bop = HirLoweringRules.compoundOp(operator);
            if (HirLoweringRules.canLowerPrimitiveCompound(
                    targetKind, rhsExpr.meta().kind(), operator)) {
                MirVal rhs = lowerAs(rhsExpr, targetKind);
                if (ssa.cur.isTerminated()) return rhs;
                MirVal res = ssa.newVal(targetKind, null);
                MirEffect bopFx =
                        (bop == HirExpr.BinaryOp.FLOOR_DIV
                                        && !HirLoweringRules.isNonZeroIntegerConst(rhsExpr))
                                ? MirEffect.PURE_MAY_THROW
                                : MirEffect.PURE_NOTHROW;
                ssa.emit(
                        new MirInst.Binary(
                                res, bop, oldVal, rhs, targetText, valueText, false, ln, bopFx));
                return res;
            }
            MirVal oldBox = ssa.convertVal(oldVal, PrimitiveKind.NONE, ln);
            MirVal rhsBox = lowerBoxed(rhsExpr);
            if (ssa.cur.isTerminated()) return rhsBox;
            MirVal raw = ssa.newVal(PrimitiveKind.NONE, null);
            ssa.emit(
                    new MirInst.Binary(
                            raw,
                            bop,
                            oldBox,
                            rhsBox,
                            targetText,
                            valueText,
                            false,
                            ln,
                            dynamicBinaryEffect(bop, meta)));
            raw = ssa.applyWideningRank(raw, meta.wideningRank(), ln);
            return convertDynamicResult ? ssa.convertVal(raw, targetKind, ln) : raw;
        }

        private MirVal lowerVarAssign(HirExpr.VarAssign a) {
            int ln = a.meta().line();
            HirVar v = a.var();
            PrimitiveKind vk = v.primitiveKind();
            if (a.operator() == null) {
                MirVal val = lowerAndWiden(a.value());
                if (ssa.cur.isTerminated()) return val;
                val = ssa.applyWideningRank(val, a.meta().wideningRank(), ln);
                MirVal conv = ssa.convertVal(val, vk, ln);
                ssa.writeVar(v, conv, ln);
                return conv;
            }
            // Compound assignment: evaluate current value, then rhs, apply binary op, then store.
            MirVal curVal = ssa.readVar(v, ln);
            MirVal nextVal =
                    lowerCompoundRhs(
                            curVal,
                            vk,
                            a.operator(),
                            a.value(),
                            a.targetText(),
                            a.valueText(),
                            a.meta(),
                            true);
            if (ssa.cur.isTerminated()) return nextVal;
            ssa.writeVar(v, nextVal, ln);
            return nextVal;
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
            MirVal res = ssa.newVal(PrimitiveKind.NONE, nr.name());
            ssa.emit(new MirInst.NameRead(res, nr.name(), nr.finalBinding(), ln, fx));
            return ssa.applyCheckedType(res, nr.meta().checkedType(), ln);
        }

        private MirVal lowerNameAssign(HirExpr.NameAssign a) {
            int ln = a.meta().line();
            MirVal val = lowerAndWiden(a.value());
            if (ssa.cur.isTerminated()) return val;
            if (a.operator() == null) {
                val = ssa.applyWideningRank(val, a.meta().wideningRank(), ln);
            }
            MirVal boxed = ssa.convertVal(val, PrimitiveKind.NONE, ln);
            MirVal res = ssa.newVal(PrimitiveKind.NONE, a.name());
            MirEffect fx =
                    a.operator() == null ? MirEffect.WRITE_SHARED_THROW : MirEffect.IMPURE_THROW;
            ssa.emit(
                    new MirInst.NameAssign(
                            res,
                            a.name(),
                            a.operator(),
                            boxed,
                            a.targetText(),
                            a.valueText(),
                            a.operator() != null ? a.meta().wideningRank() : -1,
                            ln,
                            fx));
            return ssa.applyCheckedType(res, a.meta().checkedType(), ln);
        }

        // ------------------------------------------------------------------ fields & arrays

        private MirVal lowerThisFieldGet(HirExpr.ThisFieldGet g) {
            int ln = g.meta().line();
            MirVal recv = lowerBoxed(g.receiver());
            if (ssa.cur.isTerminated()) return recv;
            PrimitiveKind outKind = resolveThisFieldKind(g);
            MirEffect fx = thisFieldReadEffect(g, recv);
            MirVal res = ssa.newVal(outKind, g.memberName());
            ssa.emit(
                    new MirInst.LoadField(
                            res,
                            recv,
                            g.receiverText(),
                            g.classId(),
                            g.externalClassJvmName(),
                            g.memberIndex(),
                            g.memberName(),
                            g.fieldKind(),
                            g.immutableField(),
                            g.definitelyDefined(),
                            ln,
                            fx));
            return ssa.applyCheckedType(res, g.meta().checkedType(), ln);
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
            MirEffect.AliasClass alias = ssa.aliasOf(recv, g.receiver().meta());
            if (g.immutableField()) {
                alias = MirEffect.AliasClass.FROZEN;
            }
            boolean nothrow;
            if (g.receiverText() == null && g.classId() >= 0 && g.memberIndex() >= 0) {
                HirClass cls = module.clazz(g.classId());
                nothrow =
                        cls != null
                                && g.immutableField()
                                && g.definitelyDefined()
                                && (g.memberIndex() >= cls.primDefBits().length
                                        || cls.primDefBits()[g.memberIndex()] < 0);
            } else {
                nothrow =
                        g.immutableField()
                                && g.definitelyDefined()
                                && (g.receiver() instanceof HirExpr.ThisRef
                                        || g.receiver() instanceof HirExpr.RecordNew
                                        || g.receiver() instanceof HirExpr.VarRead
                                        || g.receiver() instanceof HirExpr.Narrow);
            }
            if (g.immutableField() && nothrow) {
                return MirEffect.READ_FROZEN;
            }
            return MirEffect.read(alias, nothrow, true, true);
        }

        private MirVal lowerThisFieldAssign(HirExpr.ThisFieldAssign a) {
            int ln = a.meta().line();
            HirClass cls = a.classId() >= 0 ? module.clazz(a.classId()) : null;
            PrimitiveKind mk =
                    (cls != null
                                    && a.memberIndex() >= 0
                                    && a.memberIndex() < cls.memberKinds().length)
                            ? cls.memberKinds()[a.memberIndex()]
                            : PrimitiveKind.NONE;
            MirVal recv = lowerBoxed(a.receiver());
            if (ssa.cur.isTerminated()) return recv;
            MirEffect.AliasClass alias = ssa.aliasOf(recv, a.receiver().meta());
            if (a.operator() == null) {
                MirVal val = lowerAndWiden(a.value());
                if (ssa.cur.isTerminated()) return val;
                val = ssa.applyWideningRank(val, a.meta().wideningRank(), ln);
                if (mk.isPrimitive()
                        && val.kind() == PrimitiveKind.NONE
                        && a.value().meta().kind() == PrimitiveKind.INT
                        && mk == PrimitiveKind.DOUBLE
                        && a.value().meta().wideningRank() < 0
                        && a.meta().wideningRank() < 0) {
                    val = ssa.convertVal(val, PrimitiveKind.INT, ln);
                }
                MirVal stored = ssa.convertVal(val, mk, ln);
                ssa.emit(
                        new MirInst.StoreField(
                                recv,
                                a.classId(),
                                a.memberIndex(),
                                a.memberName(),
                                stored,
                                a.targetText(),
                                a.valueText(),
                                ln,
                                MirEffect.write(alias, false)));
                return stored;
            }
            // Compound this.field op= rhs
            MirVal oldVal = ssa.newVal(mk, a.memberName());
            ssa.emit(
                    new MirInst.LoadField(
                            oldVal,
                            recv,
                            null,
                            a.classId(),
                            null,
                            a.memberIndex(),
                            a.memberName(),
                            mk,
                            false,
                            false,
                            ln,
                            MirEffect.read(alias, false, true, true)));
            MirVal nextVal =
                    lowerCompoundRhs(
                            oldVal,
                            mk,
                            a.operator(),
                            a.value(),
                            a.targetText(),
                            a.valueText(),
                            a.meta(),
                            true);
            if (ssa.cur.isTerminated()) return nextVal;
            ssa.emit(
                    new MirInst.StoreField(
                            recv,
                            a.classId(),
                            a.memberIndex(),
                            a.memberName(),
                            nextVal,
                            a.targetText(),
                            a.valueText(),
                            ln,
                            MirEffect.write(alias, false)));
            return nextVal;
        }

        private MirVal lowerJavaFieldGet(HirExpr.JavaFieldGet g) {
            int ln = g.meta().line();
            LoweringTargets.JavaFieldTarget target = g.target();
            MirVal recv = null;
            if (!target.isStatic() || !g.pureStaticReceiver()) {
                recv = lowerBoxed(g.receiver());
                if (ssa.cur.isTerminated()) return recv;
            }
            Field field = target.field();
            PrimitiveKind outKind = PrimitiveKind.ofJavaClass(field.getType());
            boolean isFinal = Modifier.isFinal(field.getModifiers());
            MirEffect.AliasClass alias =
                    isFinal
                            ? MirEffect.AliasClass.FROZEN
                            : (recv != null
                                    ? ssa.aliasOf(recv, g.receiver().meta())
                                    : MirEffect.AliasClass.SHARED);
            boolean nothrow = target.isStatic() && g.pureStaticReceiver();
            MirEffect fx = MirEffect.read(alias, nothrow, true, true);
            MirVal res = ssa.newVal(outKind, field.getName());
            ssa.emit(
                    new MirInst.LoadJavaField(
                            res, recv, g.receiverText(), g.pureStaticReceiver(), target, ln, fx));
            return ssa.applyCheckedType(res, g.meta().checkedType(), ln);
        }

        private MirVal lowerJavaFieldAssign(HirExpr.JavaFieldAssign a) {
            int ln = a.meta().line();
            LoweringTargets.JavaFieldTarget target = a.target();
            Field field = target.field();
            PrimitiveKind fk = PrimitiveKind.ofJavaClass(field.getType());
            MirVal recv = null;
            if (!target.isStatic() || !a.pureStaticReceiver()) {
                recv = lowerBoxed(a.receiver());
                if (ssa.cur.isTerminated()) return recv;
            }
            MirEffect.AliasClass alias =
                    recv != null
                            ? ssa.aliasOf(recv, a.receiver().meta())
                            : MirEffect.AliasClass.SHARED;
            if (a.operator() == null) {
                MirVal val;
                if (fk == PrimitiveKind.INT
                        || fk == PrimitiveKind.BOOL
                        || fk == PrimitiveKind.DOUBLE) {
                    val = lowerAs(a.value(), fk);
                } else if (fk.isPrimitive()) {
                    val = lowerAs(a.value(), fk);
                } else {
                    val = lowerBoxed(a.value());
                }
                if (ssa.cur.isTerminated()) return val;
                ssa.emit(
                        new MirInst.StoreJavaField(
                                recv,
                                a.receiverText(),
                                a.pureStaticReceiver(),
                                target,
                                val,
                                a.targetText(),
                                a.valueText(),
                                ln,
                                MirEffect.write(alias, false)));
                return val;
            }
            MirVal oldVal = ssa.newVal(fk, field.getName());
            ssa.emit(
                    new MirInst.LoadJavaField(
                            oldVal,
                            recv,
                            a.receiverText(),
                            a.pureStaticReceiver(),
                            target,
                            ln,
                            MirEffect.read(alias, false, true, true)));
            MirVal nextVal =
                    lowerCompoundRhs(
                            oldVal,
                            fk,
                            a.operator(),
                            a.value(),
                            a.targetText(),
                            a.valueText(),
                            a.meta(),
                            false);
            if (ssa.cur.isTerminated()) return nextVal;
            ssa.emit(
                    new MirInst.StoreJavaField(
                            recv,
                            a.receiverText(),
                            a.pureStaticReceiver(),
                            target,
                            nextVal,
                            a.targetText(),
                            a.valueText(),
                            ln,
                            MirEffect.write(alias, false)));
            return nextVal;
        }

        private MirVal lowerArrayOp(HirExpr.ArrayOp op) {
            int ln = op.meta().line();
            return switch (op.kind()) {
                case SIZE -> {
                    MirVal arr = lowerBoxed(op.receiver());
                    if (ssa.cur.isTerminated()) yield arr;
                    MirVal res = ssa.newVal(PrimitiveKind.INT, "len");
                    // Array length is immutable once allocated (only null-check may throw).
                    MirEffect fx = MirEffect.read(MirEffect.AliasClass.FROZEN, false, true, true);
                    ssa.emit(
                            new MirInst.ArrayLen(
                                    res,
                                    arr,
                                    op.receiverText(),
                                    op.elemKind(),
                                    op.arrayClass(),
                                    op.componentClass(),
                                    ln,
                                    fx));
                    yield ssa.applyCheckedType(res, op.meta().checkedType(), ln);
                }
                case GET -> {
                    MirVal arr = lowerBoxed(op.receiver());
                    if (ssa.cur.isTerminated()) yield arr;
                    MirVal idx = lowerAs(op.args().get(0), PrimitiveKind.INT);
                    if (ssa.cur.isTerminated()) yield idx;
                    PrimitiveKind outKind =
                            op.elemKind().isPrimitive() ? op.elemKind() : PrimitiveKind.NONE;
                    MirVal res = ssa.newVal(outKind, null);
                    MirEffect fx =
                            MirEffect.read(
                                    ssa.aliasOf(arr, op.receiver().meta()), false, true, true);
                    ssa.emit(
                            new MirInst.ArrayGet(
                                    res,
                                    arr,
                                    idx,
                                    op.receiverText(),
                                    op.elemKind(),
                                    op.arrayClass(),
                                    op.componentClass(),
                                    ln,
                                    fx));
                    yield ssa.applyCheckedType(res, op.meta().checkedType(), ln);
                }
                case SET -> {
                    MirVal arr = lowerBoxed(op.receiver());
                    if (ssa.cur.isTerminated()) yield arr;
                    MirVal idx = lowerAs(op.args().get(0), PrimitiveKind.INT);
                    if (ssa.cur.isTerminated()) yield idx;
                    MirVal val =
                            op.elemKind().isPrimitive()
                                    ? lowerAs(op.args().get(1), op.elemKind())
                                    : lowerBoxed(op.args().get(1));
                    if (ssa.cur.isTerminated()) yield val;
                    MirEffect fx = MirEffect.write(ssa.aliasOf(arr, op.receiver().meta()), false);
                    ssa.emit(
                            new MirInst.ArraySet(
                                    arr,
                                    idx,
                                    val,
                                    op.receiverText(),
                                    op.elemKind(),
                                    op.arrayClass(),
                                    op.componentClass(),
                                    ln,
                                    fx));
                    MirVal nullRes = ssa.emitConst(null, PrimitiveKind.NONE, ln);
                    yield ssa.applyCheckedType(nullRes, op.meta().checkedType(), ln);
                }
                case EQUALS, HASH_CODE, TO_STRING -> callLowerer.lowerOpaque(op);
            };
        }

        // ------------------------------------------------------------------ unary / binary / logic
        // / types

        private MirVal lowerUnary(HirExpr.Unary u) {
            int ln = u.meta().line();
            if (u.op() == HirExpr.UnaryOp.NOT) {
                MirVal operand = lowerExpr(u.operand());
                if (ssa.cur.isTerminated()) return operand;
                MirVal res = ssa.newVal(PrimitiveKind.BOOL, null);
                ssa.emit(
                        new MirInst.Unary(
                                res,
                                HirExpr.UnaryOp.NOT,
                                operand,
                                u.operandText(),
                                ln,
                                MirEffect.PURE_NOTHROW));
                return res;
            }
            PrimitiveKind ok = u.operand().meta().kind();
            PrimitiveKind rk = u.meta().kind();
            if (rk == PrimitiveKind.INT && ok.isNumeric() && ok.numericRank() <= 2) {
                MirVal op = lowerAs(u.operand(), PrimitiveKind.INT);
                if (ssa.cur.isTerminated()) return op;
                MirVal res = ssa.newVal(PrimitiveKind.INT, null);
                ssa.emit(
                        new MirInst.Unary(
                                res,
                                HirExpr.UnaryOp.NEGATE,
                                op,
                                u.operandText(),
                                ln,
                                MirEffect.PURE_NOTHROW));
                return res;
            }
            if (rk == PrimitiveKind.LONG && ok.isNumeric() && ok.numericRank() <= 3) {
                MirVal op = lowerAs(u.operand(), PrimitiveKind.LONG);
                if (ssa.cur.isTerminated()) return op;
                MirVal res = ssa.newVal(PrimitiveKind.LONG, null);
                ssa.emit(
                        new MirInst.Unary(
                                res,
                                HirExpr.UnaryOp.NEGATE,
                                op,
                                u.operandText(),
                                ln,
                                MirEffect.PURE_NOTHROW));
                return res;
            }
            if (rk == PrimitiveKind.FLOAT && ok.isNumeric() && ok.numericRank() <= 4) {
                MirVal op = lowerAs(u.operand(), PrimitiveKind.FLOAT);
                if (ssa.cur.isTerminated()) return op;
                MirVal res = ssa.newVal(PrimitiveKind.FLOAT, null);
                ssa.emit(
                        new MirInst.Unary(
                                res,
                                HirExpr.UnaryOp.NEGATE,
                                op,
                                u.operandText(),
                                ln,
                                MirEffect.PURE_NOTHROW));
                return res;
            }
            if (rk == PrimitiveKind.DOUBLE && ok.isNumeric()) {
                MirVal op = lowerAs(u.operand(), PrimitiveKind.DOUBLE);
                if (ssa.cur.isTerminated()) return op;
                MirVal res = ssa.newVal(PrimitiveKind.DOUBLE, null);
                ssa.emit(
                        new MirInst.Unary(
                                res,
                                HirExpr.UnaryOp.NEGATE,
                                op,
                                u.operandText(),
                                ln,
                                MirEffect.PURE_NOTHROW));
                return res;
            }
            MirVal op = lowerBoxed(u.operand());
            if (ssa.cur.isTerminated()) return op;
            MirVal res = ssa.newVal(PrimitiveKind.NONE, null);
            ssa.emit(
                    new MirInst.Unary(
                            res,
                            HirExpr.UnaryOp.NEGATE,
                            op,
                            u.operandText(),
                            ln,
                            effectFromMeta(u.meta(), false)));
            return res;
        }

        private MirVal emitPrimitiveBinary(
                HirExpr.Binary b, PrimitiveKind operandKind, PrimitiveKind resKind, MirEffect fx) {
            MirVal lhs = lowerAs(b.left(), operandKind);
            if (ssa.cur.isTerminated()) return lhs;
            MirVal rhs = lowerAs(b.right(), operandKind);
            if (ssa.cur.isTerminated()) return rhs;
            MirVal res = ssa.newVal(resKind, null);
            ssa.emit(
                    new MirInst.Binary(
                            res,
                            b.op(),
                            lhs,
                            rhs,
                            b.leftText(),
                            b.rightText(),
                            false,
                            b.meta().line(),
                            fx));
            return res;
        }

        private MirVal lowerBinary(HirExpr.Binary b) {
            int ln = b.meta().line();
            PrimitiveKind lk = b.left().meta().kind();
            PrimitiveKind rk = b.right().meta().kind();
            if (b.op().isComparison() && HirLoweringRules.canLowerComparison(b.op(), lk, rk)) {
                PrimitiveKind common =
                        ((lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL)
                                        || (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR))
                                ? lk
                                : (lk.numericRank() >= rk.numericRank() ? lk : rk);
                return emitPrimitiveBinary(b, common, PrimitiveKind.BOOL, MirEffect.PURE_NOTHROW);
            }
            PrimitiveKind resultKind = b.meta().kind();
            if (lk.isNumeric() && rk.isNumeric()) {
                if ((resultKind == PrimitiveKind.INT || resultKind == PrimitiveKind.LONG)
                        && (b.op() == HirExpr.BinaryOp.ADD
                                || b.op() == HirExpr.BinaryOp.SUB
                                || b.op() == HirExpr.BinaryOp.MUL)) {
                    return emitPrimitiveBinary(b, resultKind, resultKind, MirEffect.PURE_NOTHROW);
                }
                if ((resultKind == PrimitiveKind.INT || resultKind == PrimitiveKind.LONG)
                        && (b.op() == HirExpr.BinaryOp.MOD
                                || b.op() == HirExpr.BinaryOp.FLOOR_DIV)) {
                    MirEffect fx =
                            HirLoweringRules.isNonZeroIntegerConst(b.right())
                                    ? MirEffect.PURE_NOTHROW
                                    : MirEffect.PURE_MAY_THROW;
                    return emitPrimitiveBinary(b, resultKind, resultKind, fx);
                }
                if ((resultKind == PrimitiveKind.FLOAT || resultKind == PrimitiveKind.DOUBLE)
                        && b.op().isArithmetic()) {
                    return emitPrimitiveBinary(b, resultKind, resultKind, MirEffect.PURE_NOTHROW);
                }
            }
            MirVal lhs = lowerBoxed(b.left());
            if (ssa.cur.isTerminated()) return lhs;
            MirVal rhs = lowerBoxed(b.right());
            if (ssa.cur.isTerminated()) return rhs;
            boolean stringConcat =
                    b.op() == HirExpr.BinaryOp.ADD
                            && b.left().meta().stringType()
                            && b.right().meta().stringType();
            boolean pureStringEq =
                    (b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ)
                            && b.left().meta().stringType()
                            && b.right().meta().stringType();
            MirEffect fx =
                    (stringConcat || pureStringEq)
                            ? MirEffect.PURE_NOTHROW
                            : dynamicBinaryEffect(b.op(), b.meta());
            MirVal res = ssa.newVal(PrimitiveKind.NONE, null);
            ssa.emit(
                    new MirInst.Binary(
                            res,
                            b.op(),
                            lhs,
                            rhs,
                            b.leftText(),
                            b.rightText(),
                            stringConcat,
                            ln,
                            fx));
            return res;
        }

        private MirVal lowerRange(HirExpr.Range r) {
            int ln = r.meta().line();
            PrimitiveKind ek = r.elemKind();
            MirVal s = ek.isPrimitive() ? lowerAs(r.start(), ek) : lowerBoxed(r.start());
            if (ssa.cur.isTerminated()) return s;
            MirVal e = ek.isPrimitive() ? lowerAs(r.end(), ek) : lowerBoxed(r.end());
            if (ssa.cur.isTerminated()) return e;
            MirVal res = ssa.newVal(PrimitiveKind.NONE, null);
            MirEffect fx = ek.isPrimitive() ? MirEffect.PURE_NOTHROW : MirEffect.PURE_MAY_THROW;
            ssa.emit(new MirInst.RangeNew(res, s, e, r.inclusive(), ek, ln, fx));
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
            if (ssa.cur.isTerminated()) return lhs;
            MirControlFlowLowerer.NarrowHint hint = controlFlow.extractNarrowHint(l.left());
            MirVal mergeParam = ssa.newVal(PrimitiveKind.BOOL, null);
            BlockBuilder bbRight = ssa.newBlock(List.of(), false, false);
            BlockBuilder bbMerge = ssa.newBlock(List.of(mergeParam), false, false);
            if (l.op() == HirExpr.LogicOp.AND) {
                MirVal falseVal = ssa.emitConst(Boolean.FALSE, PrimitiveKind.BOOL, ln);
                ssa.terminateBrIf(lhs, bbRight, List.of(), bbMerge, List.of(falseVal), ln);
            } else {
                MirVal trueVal = ssa.emitConst(Boolean.TRUE, PrimitiveKind.BOOL, ln);
                ssa.terminateBrIf(lhs, bbMerge, List.of(trueVal), bbRight, List.of(), ln);
            }
            ssa.sealBlock(bbRight);
            ssa.cur = bbRight;
            if (hint != null
                    && ((l.op() == HirExpr.LogicOp.AND && hint.onTrueBranch())
                            || (l.op() == HirExpr.LogicOp.OR && !hint.onTrueBranch()))) {
                controlFlow.emitNarrowAnchor(hint, ln);
            }
            MirVal rhs = lowerConditionBool(l.right(), ln);
            if (!ssa.cur.isTerminated()) {
                ssa.terminateBr(bbMerge, List.of(rhs), ln);
            }
            ssa.sealBlock(bbMerge);
            ssa.cur = bbMerge;
            return mergeParam;
        }

        MirVal lowerConditionBool(HirExpr cond, int ln) {
            MirVal v = lowerExpr(cond);
            if (ssa.cur.isTerminated()) {
                return v;
            }
            if (v.kind() == PrimitiveKind.BOOL) {
                return v;
            }
            // Convert truthiness of primitive or reference to BOOL via Unary(NOT) twice? Better:
            // Convert(BOOL) or Binary != 0.
            return switch (v.kind()) {
                case BYTE, SHORT, INT, CHAR -> {
                    MirVal zero = ssa.emitConst(0, PrimitiveKind.INT, ln);
                    MirVal iv = ssa.convertVal(v, PrimitiveKind.INT, ln);
                    MirVal b = ssa.newVal(PrimitiveKind.BOOL, null);
                    ssa.emit(
                            new MirInst.Binary(
                                    b,
                                    HirExpr.BinaryOp.NEQ,
                                    iv,
                                    zero,
                                    null,
                                    null,
                                    false,
                                    ln,
                                    MirEffect.PURE_NOTHROW));
                    yield b;
                }
                case LONG -> {
                    MirVal zero = ssa.emitConst(0L, PrimitiveKind.LONG, ln);
                    MirVal b = ssa.newVal(PrimitiveKind.BOOL, null);
                    ssa.emit(
                            new MirInst.Binary(
                                    b,
                                    HirExpr.BinaryOp.NEQ,
                                    v,
                                    zero,
                                    null,
                                    null,
                                    false,
                                    ln,
                                    MirEffect.PURE_NOTHROW));
                    yield b;
                }
                case FLOAT -> {
                    MirVal zero = ssa.emitConst(0.0f, PrimitiveKind.FLOAT, ln);
                    MirVal b = ssa.newVal(PrimitiveKind.BOOL, null);
                    ssa.emit(
                            new MirInst.Binary(
                                    b,
                                    HirExpr.BinaryOp.NEQ,
                                    v,
                                    zero,
                                    null,
                                    null,
                                    false,
                                    ln,
                                    MirEffect.PURE_NOTHROW));
                    yield b;
                }
                case DOUBLE -> {
                    MirVal zero = ssa.emitConst(0.0d, PrimitiveKind.DOUBLE, ln);
                    MirVal b = ssa.newVal(PrimitiveKind.BOOL, null);
                    ssa.emit(
                            new MirInst.Binary(
                                    b,
                                    HirExpr.BinaryOp.NEQ,
                                    v,
                                    zero,
                                    null,
                                    null,
                                    false,
                                    ln,
                                    MirEffect.PURE_NOTHROW));
                    yield b;
                }
                case NONE -> {
                    // Use Truth.test(Object) via Convert? Wait: Convert(NONE -> BOOL) unboxes
                    // Boolean, NOT Truth.test!
                    // Unary(NOT) calls Truth.test and negates; double NOT? No, let's check: in
                    // Babble strict typing,
                    // if cond is NONE (e.g. any or boxed Boolean or nullable), Truth.test(obj) is
                    // exact!
                    // Unary(NOT, x) produces !Truth.test(x); Unary(NOT, Unary(NOT, x)) produces
                    // Truth.test(x) cleanly
                    // OR we can handle BrIf(cond) directly with Truth.test when cond.kind() ==
                    // NONE!
                    // Wait: for Logic(AND/OR), mergeParam is BOOL, so we DO want a BOOL value here.
                    MirVal neg = ssa.newVal(PrimitiveKind.BOOL, null);
                    ssa.emit(
                            new MirInst.Unary(
                                    neg, HirExpr.UnaryOp.NOT, v, null, ln, MirEffect.PURE_NOTHROW));
                    MirVal pos = ssa.newVal(PrimitiveKind.BOOL, null);
                    ssa.emit(
                            new MirInst.Unary(
                                    pos,
                                    HirExpr.UnaryOp.NOT,
                                    neg,
                                    null,
                                    ln,
                                    MirEffect.PURE_NOTHROW));
                    yield pos;
                }
                case BOOL -> v;
            };
        }

        private MirVal lowerIs(HirExpr.Is i) {
            int ln = i.meta().line();
            MirVal operand = lowerBoxed(i.left());
            if (ssa.cur.isTerminated()) return operand;
            PrimitiveKind outKind = i.builtin() != null ? PrimitiveKind.NONE : PrimitiveKind.BOOL;
            MirVal res = ssa.newVal(outKind, null);
            ssa.emit(
                    new MirInst.IsCheck(
                            res,
                            operand,
                            i.builtin(),
                            i.target(),
                            i.intersectionTargets(),
                            i.descriptor(),
                            ln));
            return res;
        }

        private MirVal lowerAsExpr(HirExpr.As a) {
            int ln = a.meta().line();
            if (a.targetDescriptor() == null && a.samTarget() == null) {
                return lowerAndWiden(a.left());
            }
            MirVal operand = lowerBoxed(a.left());
            if (ssa.cur.isTerminated()) return operand;
            MirVal res = ssa.newVal(PrimitiveKind.NONE, null);
            ssa.emit(
                    new MirInst.CheckCast(
                            res, operand, a.target(), a.samTarget(), a.targetDescriptor(), ln));
            ssa.propagateAlias(operand, res);
            return res;
        }

        private MirVal lowerNonNull(HirExpr.NonNull n) {
            int ln = n.meta().line();
            if (n.expectedType() == null) {
                return lowerExpr(n.operand());
            }
            MirVal operand = lowerBoxed(n.operand());
            if (ssa.cur.isTerminated()) return operand;
            PrimitiveKind targetKind =
                    n.meta().kind().isPrimitive() ? n.meta().kind() : PrimitiveKind.NONE;
            MirVal res = ssa.newVal(targetKind, null);
            ssa.emit(new MirInst.NonNull(res, operand, n.expectedType(), ln));
            ssa.propagateAlias(operand, res);
            return res;
        }

        private MirVal lowerMove(HirExpr.Move m) {
            int ln = m.meta().line();
            MirVal op = lowerAndWiden(m.operand());
            if (ssa.cur.isTerminated()) return op;
            MirVal res = ssa.newVal(op.kind(), m.var() != null ? m.var().name() : null);
            ssa.emit(new MirInst.Move(res, m.var(), op, ln));
            ssa.propagateAlias(op, res);
            return res;
        }

        private MirVal lowerFreeze(HirExpr.Freeze f) {
            int ln = f.meta().line();
            MirVal op = lowerAndWiden(f.operand());
            if (ssa.cur.isTerminated()) return op;
            MirVal res = ssa.newVal(op.kind(), null);
            ssa.emit(new MirInst.Freeze(res, op, ln));
            ssa.valAliases.put(res.id(), MirEffect.AliasClass.FROZEN);
            return res;
        }

        // ------------------------------------------------------------------ control flow

        private MirVal lowerBlock(HirExpr.Block b) {
            return controlFlow.lowerBlock(b);
        }

        private MirVal lowerIf(HirExpr.If i) {
            return controlFlow.lowerIf(i);
        }

        private MirVal lowerMatch(HirExpr.Match m) {
            return matchLowerer.lowerMatch(m);
        }

        private MirVal lowerWhile(HirExpr.While w) {
            return controlFlow.lowerWhile(w);
        }

        private MirVal lowerFor(HirExpr.For f) {
            return controlFlow.lowerFor(f);
        }

        private MirVal lowerBreak(HirExpr.Break b) {
            return controlFlow.lowerBreak(b);
        }

        private MirVal lowerContinue(HirExpr.Continue c) {
            return controlFlow.lowerContinue(c);
        }

        private MirVal lowerReturn(HirExpr.Return r) {
            return controlFlow.lowerReturn(r);
        }

        private MirVal lowerThrow(HirExpr.Throw th) {
            return controlFlow.lowerThrow(th);
        }

        private MirVal lowerTry(HirExpr.Try t) {
            return controlFlow.lowerTry(t);
        }

        MirEffect effectFromMeta(ExprMeta meta, boolean defaultNothrow) {
            return callLowerer.effectFromMeta(meta, defaultNothrow);
        }
    }
}
