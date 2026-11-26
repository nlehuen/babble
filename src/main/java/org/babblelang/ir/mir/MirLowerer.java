package org.babblelang.ir.mir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.BuiltinAxioms;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.effects.Effect;
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

        FunctionLowerer(HirModule module, HirFunction fn) {
            this.module = module;
            this.fn = fn;
            this.ssa = new MirSsaBuilder(fn, MirVarPinningScanner.scan(fn));
            this.matchLowerer = new MirMatchLowerer(this, this.ssa);
            this.controlFlow = new MirControlFlowLowerer(this, this.ssa);
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

        // ------------------------------------------------------------------ SSA delegators

        private MirVal newVal(PrimitiveKind kind, String hint) {
            return ssa.newVal(kind, hint);
        }

        private BlockBuilder newBlock(
                List<MirVal> explicitParams, boolean handlerEntry, boolean sealed) {
            return ssa.newBlock(explicitParams, handlerEntry, sealed);
        }

        private BlockBuilder newBlockWithHandlers(
                List<MirVal> explicitParams,
                List<MirHandler> handlers,
                boolean handlerEntry,
                boolean sealed) {
            return ssa.newBlockWithHandlers(explicitParams, handlers, handlerEntry, sealed);
        }

        private BlockBuilder newBlockWithHandlers(
                List<MirVal> explicitParams,
                List<MirHandler> handlers,
                boolean handlerEntry,
                BlockBuilder handlerDominator,
                boolean sealed) {
            return ssa.newBlockWithHandlers(
                    explicitParams, handlers, handlerEntry, handlerDominator, sealed);
        }

        private void emit(MirInst inst) {
            ssa.emit(inst);
        }

        private void terminateBr(BlockBuilder target, List<MirVal> explicitArgs, int line) {
            ssa.terminateBr(target, explicitArgs, line);
        }

        private void terminateBrIf(
                MirVal cond,
                BlockBuilder thenTarget,
                List<MirVal> thenArgs,
                BlockBuilder elseTarget,
                List<MirVal> elseArgs,
                int line) {
            ssa.terminateBrIf(cond, thenTarget, thenArgs, elseTarget, elseArgs, line);
        }

        private void terminateSwitch(
                MirVal key,
                int[] keys,
                BlockBuilder[] targets,
                BlockBuilder defaultTarget,
                int line) {
            ssa.terminateSwitch(key, keys, targets, defaultTarget, line);
        }

        private void terminateFixed(MirTerminator term) {
            ssa.terminateFixed(term);
        }

        private void writeVar(HirVar v, MirVal val, int line) {
            ssa.writeVar(v, val, line);
        }

        private void defVar(HirVar v, MirVal val, int line) {
            ssa.defVar(v, val, line);
        }

        private MirVal readVar(HirVar v, int line) {
            return ssa.readVar(v, line);
        }

        private void sealBlock(BlockBuilder blk) {
            ssa.sealBlock(blk);
        }

        private MirVal emitConst(Object value, PrimitiveKind kind, int line) {
            return ssa.emitConst(value, kind, line);
        }

        private MirVal convertVal(MirVal val, PrimitiveKind target, int line) {
            return ssa.convertVal(val, target, line);
        }

        private MirVal applyWideningRank(MirVal val, int rank, int line) {
            return ssa.applyWideningRank(val, rank, line);
        }

        private MirVal applyCheckedType(MirVal val, String checkedType, int line) {
            return ssa.applyCheckedType(val, checkedType, line);
        }

        private void propagateAlias(MirVal src, MirVal dst) {
            ssa.propagateAlias(src, dst);
        }

        private void recordExprAlias(MirVal val, ExprMeta meta) {
            ssa.recordExprAlias(val, meta);
        }

        private MirEffect.AliasClass aliasOf(MirVal val, ExprMeta exprMeta) {
            return ssa.aliasOf(val, exprMeta);
        }

        // ------------------------------------------------------------------ statement & expression
        // lowering

        MirVal lowerStatements(List<HirExpr> stmts, int fallbackLine) {
            if (stmts.isEmpty()) {
                return emitConst(null, PrimitiveKind.NONE, fallbackLine);
            }
            int last = stmts.size() - 1;
            for (int i = 0; i < last; i++) {
                if (ssa.cur.isTerminated()) {
                    return emitConst(null, PrimitiveKind.NONE, stmts.get(i).meta().line());
                }
                lowerExpr(stmts.get(i));
            }
            if (ssa.cur.isTerminated()) {
                return emitConst(null, PrimitiveKind.NONE, stmts.get(last).meta().line());
            }
            return lowerAndWiden(stmts.get(last));
        }

        MirVal lowerAndWiden(HirExpr expr) {
            MirVal v = lowerExpr(expr);
            if (ssa.cur.isTerminated()) {
                return v;
            }
            v = applyWideningRank(v, expr.meta().wideningRank(), expr.meta().line());
            recordExprAlias(v, expr.meta());
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
                    v = convertVal(v, exprKind, expr.meta().line());
                }
            }
            return convertVal(v, desired, expr.meta().line());
        }

        MirVal lowerBoxed(HirExpr expr) {
            return lowerAs(expr, PrimitiveKind.NONE);
        }

        MirVal lowerExpr(HirExpr expr) {
            int ln = expr.meta().line();
            MirVal res =
                    switch (expr) {
                        case HirExpr.Const c -> emitConst(c.value(), constKind(c.value()), ln);
                        case HirExpr.VarRead v -> readVar(v.var(), ln);
                        case HirExpr.NameRead nr -> lowerNameRead(nr);
                        case HirExpr.ThisRef t ->
                                t.var() != null ? readVar(t.var(), ln) : lowerOpaque(expr);
                        case HirExpr.RecurseRef r ->
                                r.var() != null ? readVar(r.var(), ln) : lowerOpaque(expr);
                        case HirExpr.Def d -> lowerDef(d);
                        case HirExpr.VarAssign a -> lowerVarAssign(a);
                        case HirExpr.NameAssign a -> lowerNameAssign(a);
                        case HirExpr.ThisFieldGet g -> lowerThisFieldGet(g);
                        case HirExpr.ThisFieldAssign a -> lowerThisFieldAssign(a);
                        case HirExpr.JavaFieldGet g -> lowerJavaFieldGet(g);
                        case HirExpr.JavaFieldAssign a -> lowerJavaFieldAssign(a);
                        case HirExpr.ModuleExportGet g -> lowerModuleExportGet(g);
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
                        case HirExpr.SelfTailCall c -> lowerSelfTailCall(c);
                        case HirExpr.SelfRecurseCall c -> lowerSelfRecurseCall(c);
                        case HirExpr.DirectBabbleCall c -> lowerDirectBabbleCall(c);
                        case HirExpr.DirectThisMethodCall c -> lowerDirectThisMethodCall(c);
                        case HirExpr.InterfaceCall c -> lowerInterfaceCall(c);
                        case HirExpr.JavaMethodCall c -> lowerJavaMethodCall(c);
                        case HirExpr.JavaConstructorCall c -> lowerJavaConstructorCall(c);
                        case HirExpr.SafeCall c -> lowerSafeCall(c);
                        case HirExpr.RecordNew r -> lowerRecordNew(r);
                        case HirExpr.RecordReuse r -> lowerRecordReuse(r);
                        default -> lowerOpaque(expr);
                    };
            recordExprAlias(res, expr.meta());
            return res;
        }

        // ------------------------------------------------------------------ defs & assignments

        private MirVal lowerDef(HirExpr.Def d) {
            int ln = d.meta().line();
            if (d.target().kind() == LoweringTargets.DefKind.REQUIRED) {
                return emitConst(null, PrimitiveKind.NONE, ln);
            }
            if (d.var() == null) {
                return lowerOpaque(d);
            }
            MirVal v =
                    d.value() != null
                            ? lowerExpr(d.value())
                            : emitConst(null, PrimitiveKind.NONE, ln);
            if (ssa.cur.isTerminated()) {
                return v;
            }
            v = applyWideningRank(v, d.meta().wideningRank(), ln);
            MirVal conv = convertVal(v, d.var().primitiveKind(), ln);
            defVar(d.var(), conv, ln);
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
                MirVal res = newVal(targetKind, null);
                MirEffect bopFx =
                        (bop == HirExpr.BinaryOp.FLOOR_DIV
                                        && !HirLoweringRules.isNonZeroIntegerConst(rhsExpr))
                                ? MirEffect.PURE_MAY_THROW
                                : MirEffect.PURE_NOTHROW;
                emit(
                        new MirInst.Binary(
                                res, bop, oldVal, rhs, targetText, valueText, false, ln, bopFx));
                return res;
            }
            MirVal oldBox = convertVal(oldVal, PrimitiveKind.NONE, ln);
            MirVal rhsBox = lowerBoxed(rhsExpr);
            if (ssa.cur.isTerminated()) return rhsBox;
            MirVal raw = newVal(PrimitiveKind.NONE, null);
            emit(
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
            raw = applyWideningRank(raw, meta.wideningRank(), ln);
            return convertDynamicResult ? convertVal(raw, targetKind, ln) : raw;
        }

        private MirVal lowerVarAssign(HirExpr.VarAssign a) {
            int ln = a.meta().line();
            HirVar v = a.var();
            PrimitiveKind vk = v.primitiveKind();
            if (a.operator() == null) {
                MirVal val = lowerAndWiden(a.value());
                if (ssa.cur.isTerminated()) return val;
                val = applyWideningRank(val, a.meta().wideningRank(), ln);
                MirVal conv = convertVal(val, vk, ln);
                writeVar(v, conv, ln);
                return conv;
            }
            // Compound assignment: evaluate current value, then rhs, apply binary op, then store.
            MirVal curVal = readVar(v, ln);
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
            writeVar(v, nextVal, ln);
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
            MirVal res = newVal(PrimitiveKind.NONE, nr.name());
            emit(new MirInst.NameRead(res, nr.name(), nr.finalBinding(), ln, fx));
            return applyCheckedType(res, nr.meta().checkedType(), ln);
        }

        private MirVal lowerNameAssign(HirExpr.NameAssign a) {
            int ln = a.meta().line();
            MirVal val = lowerAndWiden(a.value());
            if (ssa.cur.isTerminated()) return val;
            if (a.operator() == null) {
                val = applyWideningRank(val, a.meta().wideningRank(), ln);
            }
            MirVal boxed = convertVal(val, PrimitiveKind.NONE, ln);
            MirVal res = newVal(PrimitiveKind.NONE, a.name());
            MirEffect fx =
                    a.operator() == null ? MirEffect.WRITE_SHARED_THROW : MirEffect.IMPURE_THROW;
            emit(
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
            return applyCheckedType(res, a.meta().checkedType(), ln);
        }

        // ------------------------------------------------------------------ fields & arrays

        private MirVal lowerThisFieldGet(HirExpr.ThisFieldGet g) {
            int ln = g.meta().line();
            MirVal recv = lowerBoxed(g.receiver());
            if (ssa.cur.isTerminated()) return recv;
            PrimitiveKind outKind = resolveThisFieldKind(g);
            MirEffect fx = thisFieldReadEffect(g, recv);
            MirVal res = newVal(outKind, g.memberName());
            emit(
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
            return applyCheckedType(res, g.meta().checkedType(), ln);
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
            MirEffect.AliasClass alias = aliasOf(recv, g.receiver().meta());
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
                                        || g.receiver() instanceof HirExpr.VarRead);
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
            MirEffect.AliasClass alias = aliasOf(recv, a.receiver().meta());
            if (a.operator() == null) {
                MirVal val = lowerAndWiden(a.value());
                if (ssa.cur.isTerminated()) return val;
                val = applyWideningRank(val, a.meta().wideningRank(), ln);
                if (mk.isPrimitive()
                        && val.kind() == PrimitiveKind.NONE
                        && a.value().meta().kind() == PrimitiveKind.INT
                        && mk == PrimitiveKind.DOUBLE
                        && a.value().meta().wideningRank() < 0
                        && a.meta().wideningRank() < 0) {
                    val = convertVal(val, PrimitiveKind.INT, ln);
                }
                MirVal stored = convertVal(val, mk, ln);
                emit(
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
            MirVal oldVal = newVal(mk, a.memberName());
            emit(
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
            emit(
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
            PrimitiveKind outKind = javaPrimitiveKind(field.getType());
            boolean isFinal = Modifier.isFinal(field.getModifiers());
            MirEffect.AliasClass alias =
                    isFinal
                            ? MirEffect.AliasClass.FROZEN
                            : (recv != null
                                    ? aliasOf(recv, g.receiver().meta())
                                    : MirEffect.AliasClass.SHARED);
            boolean nothrow = target.isStatic() && g.pureStaticReceiver();
            MirEffect fx = MirEffect.read(alias, nothrow, true, true);
            MirVal res = newVal(outKind, field.getName());
            emit(
                    new MirInst.LoadJavaField(
                            res, recv, g.receiverText(), g.pureStaticReceiver(), target, ln, fx));
            return applyCheckedType(res, g.meta().checkedType(), ln);
        }

        private MirVal lowerJavaFieldAssign(HirExpr.JavaFieldAssign a) {
            int ln = a.meta().line();
            LoweringTargets.JavaFieldTarget target = a.target();
            Field field = target.field();
            PrimitiveKind fk = javaPrimitiveKind(field.getType());
            MirVal recv = null;
            if (!target.isStatic() || !a.pureStaticReceiver()) {
                recv = lowerBoxed(a.receiver());
                if (ssa.cur.isTerminated()) return recv;
            }
            MirEffect.AliasClass alias =
                    recv != null ? aliasOf(recv, a.receiver().meta()) : MirEffect.AliasClass.SHARED;
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
                emit(
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
            MirVal oldVal = newVal(fk, field.getName());
            emit(
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
            emit(
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
                    MirVal res = newVal(PrimitiveKind.INT, "len");
                    // Array length is immutable once allocated (only null-check may throw).
                    MirEffect fx = MirEffect.read(MirEffect.AliasClass.FROZEN, false, true, true);
                    emit(
                            new MirInst.ArrayLen(
                                    res,
                                    arr,
                                    op.receiverText(),
                                    op.elemKind(),
                                    op.arrayClass(),
                                    op.componentClass(),
                                    ln,
                                    fx));
                    yield applyCheckedType(res, op.meta().checkedType(), ln);
                }
                case GET -> {
                    MirVal arr = lowerBoxed(op.receiver());
                    if (ssa.cur.isTerminated()) yield arr;
                    MirVal idx = lowerAs(op.args().get(0), PrimitiveKind.INT);
                    if (ssa.cur.isTerminated()) yield idx;
                    PrimitiveKind outKind =
                            op.elemKind().isPrimitive() ? op.elemKind() : PrimitiveKind.NONE;
                    MirVal res = newVal(outKind, null);
                    MirEffect fx =
                            MirEffect.read(aliasOf(arr, op.receiver().meta()), false, true, true);
                    emit(
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
                    yield applyCheckedType(res, op.meta().checkedType(), ln);
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
                    MirEffect fx = MirEffect.write(aliasOf(arr, op.receiver().meta()), false);
                    emit(
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
                    MirVal nullRes = emitConst(null, PrimitiveKind.NONE, ln);
                    yield applyCheckedType(nullRes, op.meta().checkedType(), ln);
                }
                case EQUALS, HASH_CODE, TO_STRING -> lowerOpaque(op);
            };
        }

        // ------------------------------------------------------------------ unary / binary / logic
        // / types

        private MirVal lowerUnary(HirExpr.Unary u) {
            int ln = u.meta().line();
            if (u.op() == HirExpr.UnaryOp.NOT) {
                MirVal operand = lowerExpr(u.operand());
                if (ssa.cur.isTerminated()) return operand;
                MirVal res = newVal(PrimitiveKind.BOOL, null);
                emit(
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
                MirVal res = newVal(PrimitiveKind.INT, null);
                emit(
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
                MirVal res = newVal(PrimitiveKind.LONG, null);
                emit(
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
                MirVal res = newVal(PrimitiveKind.FLOAT, null);
                emit(
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
                MirVal res = newVal(PrimitiveKind.DOUBLE, null);
                emit(
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
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(
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
            MirVal res = newVal(resKind, null);
            emit(
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
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(
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
            MirVal res = newVal(PrimitiveKind.NONE, null);
            MirEffect fx = ek.isPrimitive() ? MirEffect.PURE_NOTHROW : MirEffect.PURE_MAY_THROW;
            emit(new MirInst.RangeNew(res, s, e, r.inclusive(), ek, ln, fx));
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
            MirVal mergeParam = newVal(PrimitiveKind.BOOL, null);
            BlockBuilder bbRight = newBlock(List.of(), false, false);
            BlockBuilder bbMerge = newBlock(List.of(mergeParam), false, false);
            if (l.op() == HirExpr.LogicOp.AND) {
                MirVal falseVal = emitConst(Boolean.FALSE, PrimitiveKind.BOOL, ln);
                terminateBrIf(lhs, bbRight, List.of(), bbMerge, List.of(falseVal), ln);
            } else {
                MirVal trueVal = emitConst(Boolean.TRUE, PrimitiveKind.BOOL, ln);
                terminateBrIf(lhs, bbMerge, List.of(trueVal), bbRight, List.of(), ln);
            }
            sealBlock(bbRight);
            ssa.cur = bbRight;
            if (hint != null
                    && ((l.op() == HirExpr.LogicOp.AND && hint.onTrueBranch())
                            || (l.op() == HirExpr.LogicOp.OR && !hint.onTrueBranch()))) {
                controlFlow.emitNarrowAnchor(hint, ln);
            }
            MirVal rhs = lowerConditionBool(l.right(), ln);
            if (!ssa.cur.isTerminated()) {
                terminateBr(bbMerge, List.of(rhs), ln);
            }
            sealBlock(bbMerge);
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
                    MirVal zero = emitConst(0, PrimitiveKind.INT, ln);
                    MirVal iv = convertVal(v, PrimitiveKind.INT, ln);
                    MirVal b = newVal(PrimitiveKind.BOOL, null);
                    emit(
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
                    MirVal zero = emitConst(0L, PrimitiveKind.LONG, ln);
                    MirVal b = newVal(PrimitiveKind.BOOL, null);
                    emit(
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
                    MirVal zero = emitConst(0.0f, PrimitiveKind.FLOAT, ln);
                    MirVal b = newVal(PrimitiveKind.BOOL, null);
                    emit(
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
                    MirVal zero = emitConst(0.0d, PrimitiveKind.DOUBLE, ln);
                    MirVal b = newVal(PrimitiveKind.BOOL, null);
                    emit(
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
                    MirVal neg = newVal(PrimitiveKind.BOOL, null);
                    emit(
                            new MirInst.Unary(
                                    neg, HirExpr.UnaryOp.NOT, v, null, ln, MirEffect.PURE_NOTHROW));
                    MirVal pos = newVal(PrimitiveKind.BOOL, null);
                    emit(
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
            MirVal res = newVal(outKind, null);
            emit(
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
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(
                    new MirInst.CheckCast(
                            res, operand, a.target(), a.samTarget(), a.targetDescriptor(), ln));
            propagateAlias(operand, res);
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
            MirVal res = newVal(targetKind, null);
            emit(new MirInst.NonNull(res, operand, n.expectedType(), ln));
            propagateAlias(operand, res);
            return res;
        }

        private MirVal lowerMove(HirExpr.Move m) {
            int ln = m.meta().line();
            MirVal op = lowerAndWiden(m.operand());
            if (ssa.cur.isTerminated()) return op;
            MirVal res = newVal(op.kind(), m.var() != null ? m.var().name() : null);
            emit(new MirInst.Move(res, m.var(), op, ln));
            propagateAlias(op, res);
            return res;
        }

        private MirVal lowerFreeze(HirExpr.Freeze f) {
            int ln = f.meta().line();
            MirVal op = lowerAndWiden(f.operand());
            if (ssa.cur.isTerminated()) return op;
            MirVal res = newVal(op.kind(), null);
            emit(new MirInst.Freeze(res, op, ln));
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

        // ------------------------------------------------------------------ calls & records

        private MirVal lowerSelfTailCall(HirExpr.SelfTailCall c) {
            int ln = c.meta().line();
            List<MirVal> args = new ArrayList<>(c.args().size());
            List<String> argNames = new ArrayList<>(c.args().size());
            for (HirCallArg a : c.args()) {
                args.add(lowerAndWiden(a.value()));
                if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
                argNames.add(a.name());
            }
            terminateFixed(
                    new MirTerminator.TailCall(c.functionId(), args, argNames, c.binding(), ln));
            return emitConst(null, PrimitiveKind.NONE, ln);
        }

        private MirVal lowerSelfRecurseCall(HirExpr.SelfRecurseCall c) {
            int ln = c.meta().line();
            HirFunction target = module.function(c.functionId());
            List<MirVal> args =
                    lowerCallExprList(
                            c.args(), target != null ? paramKinds(target) : null, c.binding());
            if (ssa.cur.isTerminated()) return emitConst(null, PrimitiveKind.NONE, ln);
            PrimitiveKind retKind =
                    (target != null && target.hasSpec()) ? target.returnKind() : PrimitiveKind.NONE;
            MirEffect fx = callEffect(c.meta(), target);
            boolean comm =
                    c.meta().isCommutative()
                            || (target != null && target.effects().has(Effect.COMMUTATIVE));
            boolean idem =
                    c.meta().isIdempotent()
                            || (target != null && target.effects().has(Effect.IDEMPOTENT));
            MirVal res = newVal(retKind, null);
            emit(
                    new MirInst.CallBabble(
                            res,
                            null,
                            true,
                            c.functionId(),
                            args,
                            c.binding(),
                            comm,
                            idem,
                            ln,
                            fx));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerModuleExportGet(HirExpr.ModuleExportGet g) {
            int ln = g.meta().line();
            MirVal mod = lowerBoxed(g.receiver());
            if (ssa.cur.isTerminated()) return mod;
            MirEffect fx =
                    (g.target().definitelyInitialized() && g.meta().checkedType() == null)
                            ? MirEffect.PURE_NOTHROW
                            : new MirEffect(
                                    MirEffect.Kind.READ,
                                    MirEffect.AliasClass.SHARED,
                                    false,
                                    true,
                                    true);
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(new MirInst.LoadExport(res, mod, g.target(), ln, fx));
            return applyCheckedType(res, g.meta().checkedType(), ln);
        }

        private MirVal lowerDirectBabbleCall(HirExpr.DirectBabbleCall c) {
            int ln = c.meta().line();
            LoweringTargets.ExportedFunctionTarget ext = c.externalTarget();
            HirFunction target = ext == null ? module.function(c.targetFunctionId()) : null;
            MirVal callee = lowerBoxed(c.callee());
            if (ssa.cur.isTerminated()) return callee;
            PrimitiveKind[] pKinds =
                    ext != null
                            ? ext.signature().parameterKinds()
                            : (target != null ? paramKinds(target) : null);
            List<MirVal> args = lowerCallExprList(c.args(), pKinds, c.binding());
            if (ssa.cur.isTerminated()) return emitConst(null, PrimitiveKind.NONE, ln);
            PrimitiveKind retKind;
            MirEffect fx;
            boolean comm;
            boolean idem;
            if (ext != null) {
                retKind =
                        ext.signature().hasSpec()
                                ? ext.signature().returnKind()
                                : PrimitiveKind.NONE;
                fx =
                        ext.hasMutParam()
                                ? MirEffect.IMPURE_THROW
                                : callEffect(c.meta(), ext.effects());
                comm = c.meta().isCommutative() || ext.effects().has(Effect.COMMUTATIVE);
                idem = c.meta().isIdempotent() || ext.effects().has(Effect.IDEMPOTENT);
            } else {
                retKind =
                        (target != null && target.hasSpec())
                                ? target.returnKind()
                                : PrimitiveKind.NONE;
                fx = callEffect(c.meta(), target);
                comm =
                        c.meta().isCommutative()
                                || (target != null && target.effects().has(Effect.COMMUTATIVE));
                idem =
                        c.meta().isIdempotent()
                                || (target != null && target.effects().has(Effect.IDEMPOTENT));
            }
            MirVal res = newVal(retKind, null);
            emit(
                    new MirInst.CallBabble(
                            res,
                            callee,
                            false,
                            c.targetFunctionId(),
                            ext,
                            args,
                            c.binding(),
                            comm,
                            idem,
                            ln,
                            fx));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerDirectThisMethodCall(HirExpr.DirectThisMethodCall c) {
            int ln = c.meta().line();
            MirVal recv = lowerBoxed(c.receiver());
            if (ssa.cur.isTerminated()) return recv;
            List<MirVal> args = new ArrayList<>(c.args().size());
            List<String> argNames = new ArrayList<>(c.args().size());
            for (HirCallArg a : c.args()) {
                args.add(lowerAndWiden(a.value()));
                if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
                argNames.add(a.name());
            }
            HirFunction target =
                    c.targetFunctionId() >= 0 ? module.function(c.targetFunctionId()) : null;
            PrimitiveKind retKind = PrimitiveKind.NONE;
            if (target != null && target.hasSpec()) {
                retKind = target.returnKind();
            } else if (c.externalSignature() != null) {
                retKind = c.externalSignature().returnKind();
            } else if (c.meta().kind().isPrimitive()) {
                retKind = c.meta().kind();
            }
            MirEffect fx = callEffect(c.meta(), target);
            boolean comm =
                    c.meta().isCommutative()
                            || (target != null && target.effects().has(Effect.COMMUTATIVE));
            boolean idem =
                    c.meta().isIdempotent()
                            || (target != null && target.effects().has(Effect.IDEMPOTENT));
            MirVal res = newVal(retKind, null);
            emit(
                    new MirInst.CallMethod(
                            res,
                            recv,
                            c.receiverText(),
                            c.classId(),
                            c.externalClassJvmName(),
                            c.classTypeId(),
                            c.memberName(),
                            c.overloadAltIndex(),
                            c.targetFunctionId(),
                            c.externalSignature(),
                            args,
                            argNames,
                            c.binding(),
                            c.callSite(),
                            comm,
                            idem,
                            ln,
                            fx));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerInterfaceCall(HirExpr.InterfaceCall c) {
            int ln = c.meta().line();
            MirVal recv = lowerBoxed(c.receiver());
            if (ssa.cur.isTerminated()) return recv;
            List<MirVal> args = new ArrayList<>(c.args().size());
            for (HirExpr a : c.args()) {
                args.add(lowerAndWiden(a));
                if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
            }
            PrimitiveKind retKind =
                    c.signature() != null ? c.signature().returnKind() : PrimitiveKind.NONE;
            MirEffect fx = callEffect(c.meta(), EffectSet.EMPTY);
            MirVal res = newVal(retKind, null);
            emit(
                    new MirInst.CallInterface(
                            res,
                            recv,
                            c.receiverText(),
                            c.selectorLine(),
                            c.interfaceTypeId(),
                            c.memberName(),
                            c.signature(),
                            args,
                            c.binding(),
                            ln,
                            fx));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerJavaMethodCall(HirExpr.JavaMethodCall c) {
            int ln = c.meta().line();
            LoweringTargets.JavaCallTarget target = c.target();
            MirVal recv = null;
            if (!target.isStatic() || !c.pureStaticReceiver()) {
                recv = lowerBoxed(c.receiver());
                if (ssa.cur.isTerminated()) return recv;
            }
            Method m = target.method();
            Class<?>[] pTypes = m.getParameterTypes();
            List<MirVal> args = new ArrayList<>(c.args().size());
            for (int i = 0; i < c.args().size(); i++) {
                HirExpr argExpr = c.args().get(i);
                PrimitiveKind desired =
                        (i < pTypes.length) ? javaArgDesiredKind(pTypes[i]) : PrimitiveKind.NONE;
                if (desired == PrimitiveKind.INT
                        || desired == PrimitiveKind.BOOL
                        || desired == PrimitiveKind.DOUBLE) {
                    args.add(lowerAs(argExpr, desired));
                } else {
                    args.add(lowerAndWiden(argExpr));
                }
                if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
            }
            PrimitiveKind retKind = javaPrimitiveKind(m.getReturnType());
            boolean foldable = BuiltinAxioms.current().isFoldable(m);
            boolean idempotent = BuiltinAxioms.current().isIdempotent(m) || c.meta().isIdempotent();
            boolean commutative = c.meta().isCommutative();
            MirEffect fx;
            if (foldable && target.isStatic() && c.pureStaticReceiver()) {
                fx = MirEffect.PURE_NOTHROW;
            } else {
                fx = callEffect(c.meta(), EffectSet.EMPTY);
                if (!target.isStatic()
                        && fx.nothrow()
                        && !(c.receiver() instanceof HirExpr.Const rc && rc.value() != null)) {
                    fx =
                            new MirEffect(
                                    fx.kind(),
                                    fx.alias(),
                                    false,
                                    fx.terminating(),
                                    fx.deterministic());
                }
            }
            MirVal res = newVal(retKind, null);
            emit(
                    new MirInst.CallJava(
                            res,
                            recv,
                            c.receiverText(),
                            c.selectorLine(),
                            c.pureStaticReceiver(),
                            target,
                            args,
                            commutative,
                            idempotent,
                            ln,
                            fx));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerJavaConstructorCall(HirExpr.JavaConstructorCall c) {
            int ln = c.meta().line();
            MirVal callee = null;
            if (!c.pureStaticCallee()) {
                callee = lowerBoxed(c.callee());
                if (ssa.cur.isTerminated()) return callee;
            }
            List<MirVal> args = new ArrayList<>(c.args().size());
            for (HirExpr a : c.args()) {
                args.add(lowerAndWiden(a));
                if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
            }
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(
                    new MirInst.CallJavaCtor(
                            res,
                            callee,
                            c.calleeText(),
                            c.pureStaticCallee(),
                            c.target(),
                            args,
                            ln,
                            callEffect(c.meta(), EffectSet.EMPTY)));
            return applyCheckedType(res, c.meta().checkedType(), ln);
        }

        private MirVal lowerSafeCall(HirExpr.SafeCall c) {
            // SafeCall evaluates args ONLY when receiver != null: lower as a CFG diamond!
            int ln = c.meta().line();
            MirVal recv = lowerBoxed(c.receiver());
            if (ssa.cur.isTerminated()) return recv;
            MirVal nullConst = emitConst(null, PrimitiveKind.NONE, ln);
            MirVal isNonNull = newVal(PrimitiveKind.BOOL, null);
            emit(
                    new MirInst.Binary(
                            isNonNull,
                            HirExpr.BinaryOp.NEQ,
                            recv,
                            nullConst,
                            c.receiverText(),
                            "null",
                            false,
                            ln,
                            MirEffect.PURE_NOTHROW));

            BlockBuilder bbNonNull = newBlock(List.of(), false, false);
            MirVal mergeParam = newVal(PrimitiveKind.NONE, null);
            BlockBuilder bbMerge = newBlock(List.of(mergeParam), false, false);
            terminateBrIf(isNonNull, bbNonNull, List.of(), bbMerge, List.of(nullConst), ln);
            sealBlock(bbNonNull);

            ssa.cur = bbNonNull;
            MirVal narrowed = newVal(PrimitiveKind.NONE, null);
            emit(new MirInst.Narrow(narrowed, MirInst.NarrowKind.NON_NULL, recv, "non-null", ln));
            List<MirVal> ops = new ArrayList<>(1 + c.args().size());
            ops.add(narrowed);
            for (HirCallArg a : c.args()) {
                ops.add(lowerAndWiden(a.value()));
                if (ssa.cur.isTerminated()) break;
            }
            if (!ssa.cur.isTerminated()) {
                MirVal callRes = newVal(PrimitiveKind.NONE, null);
                emit(new MirInst.OpaqueExpr(callRes, c, ops, ln, MirEffect.IMPURE_THROW));
                terminateBr(bbMerge, List.of(callRes), ln);
            }
            sealBlock(bbMerge);
            ssa.cur = bbMerge;
            return mergeParam;
        }

        private MirVal lowerRecordNew(HirExpr.RecordNew r) {
            int ln = r.meta().line();
            MirVal classRef = lowerBoxed(r.classRef());
            if (ssa.cur.isTerminated()) return classRef;
            List<MirVal> args = new ArrayList<>(r.args().size());
            for (HirExpr a : r.args()) {
                args.add(lowerAndWiden(a));
                if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
            }
            MirVal res = newVal(PrimitiveKind.NONE, null);
            emit(
                    new MirInst.RecordNew(
                            res,
                            r.classId(),
                            r.classTypeId(),
                            r.classJvmName(),
                            classRef,
                            r.classRefIsInstance(),
                            r.paramKinds(),
                            args,
                            r.binding(),
                            ln,
                            MirEffect.PURE_NOTHROW));
            if (r.meta().frozen() || r.meta().deeplyImmutable()) {
                ssa.valAliases.put(res.id(), MirEffect.AliasClass.FROZEN);
            }
            return applyCheckedType(res, r.meta().checkedType(), ln);
        }

        private MirVal lowerRecordReuse(HirExpr.RecordReuse r) {
            int ln = r.meta().line();
            MirVal target = lowerBoxed(r.target());
            if (ssa.cur.isTerminated()) return target;
            List<MirVal> args = new ArrayList<>(r.args().size());
            for (HirExpr a : r.args()) {
                args.add(lowerAndWiden(a));
                if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
            }
            MirVal res = newVal(PrimitiveKind.NONE, null);
            MirEffect.AliasClass alias = aliasOf(target, r.target().meta());
            emit(
                    new MirInst.RecordReuse(
                            res,
                            target,
                            r.classId(),
                            r.classTypeId(),
                            r.classJvmName(),
                            r.paramKinds(),
                            args,
                            ln,
                            MirEffect.write(alias, true)));
            propagateAlias(target, res);
            return applyCheckedType(res, r.meta().checkedType(), ln);
        }

        private MirVal lowerOpaque(HirExpr expr) {
            int ln = expr.meta().line();
            List<HirExpr> children = opaqueChildExprs(expr);
            List<MirVal> operands = new ArrayList<>(children.size());
            for (HirExpr ch : children) {
                operands.add(lowerAndWiden(ch));
                if (ssa.cur.isTerminated()) return operands.get(operands.size() - 1);
            }
            PrimitiveKind outKind = opaqueResultKind(expr);
            MirEffect fx = opaqueEffect(expr, operands);
            MirVal res = newVal(outKind, null);
            emit(new MirInst.OpaqueExpr(res, expr, operands, ln, fx));
            return res;
        }

        private PrimitiveKind opaqueResultKind(HirExpr expr) {
            return switch (expr) {
                case HirExpr.IntParseFallback ignored -> PrimitiveKind.INT;
                case HirExpr.ArrayOp op ->
                        switch (op.kind()) {
                            case EQUALS -> PrimitiveKind.BOOL;
                            case HASH_CODE -> PrimitiveKind.INT;
                            default -> PrimitiveKind.NONE;
                        };
                case HirExpr.DynamicCall c ->
                        c.meta().checkedType() == null
                                ? dynamicCallResultKind(c)
                                : PrimitiveKind.NONE;
                case HirExpr.OverloadDispatch d ->
                        d.meta().checkedType() == null ? d.meta().kind() : PrimitiveKind.NONE;
                default -> PrimitiveKind.NONE;
            };
        }

        private static PrimitiveKind dynamicCallResultKind(HirExpr.DynamicCall c) {
            LoweringTargets.FunctionSignature sig = c.babbleSig();
            if (sig == null) {
                return PrimitiveKind.NONE;
            }
            LoweringTargets.ArgumentBinding binding = c.binding();
            if (binding != null) {
                return binding.paramCount() == sig.parameterKinds().length
                        ? sig.returnKind()
                        : PrimitiveKind.NONE;
            }
            for (HirCallArg arg : c.args()) {
                if (arg.name() != null) {
                    return PrimitiveKind.NONE;
                }
            }
            return c.args().size() == sig.parameterKinds().length
                    ? sig.returnKind()
                    : PrimitiveKind.NONE;
        }

        private MirEffect opaqueEffect(HirExpr expr, List<MirVal> operands) {
            return switch (expr) {
                case HirExpr.CoreCollectionRef ignored -> MirEffect.PURE_NOTHROW;
                case HirExpr.CoreExceptionRef ignored -> MirEffect.PURE_NOTHROW;
                case HirExpr.FunctionRef fr -> {
                    HirFunction target = module.function(fr.functionId());
                    yield (target != null && !target.capturedVars().isEmpty())
                            ? MirEffect.READ_SHARED
                            : MirEffect.PURE_NOTHROW;
                }
                case HirExpr.MakeOverloadSet ignored -> MirEffect.PURE_NOTHROW;
                case HirExpr.OverloadSelect s ->
                        s.exactSet() ? MirEffect.PURE_NOTHROW : MirEffect.PURE_MAY_THROW;
                case HirExpr.ArrayOp ignored -> MirEffect.READ_SHARED_THROW;
                case HirExpr.DynamicFieldAssign a ->
                        (a.operator() == null && !operands.isEmpty())
                                ? MirEffect.write(
                                        aliasOf(operands.get(0), a.receiver().meta()), false)
                                : MirEffect.IMPURE_THROW;
                default -> MirEffect.IMPURE_THROW;
            };
        }

        private List<MirVal> lowerCallExprList(
                List<HirExpr> exprs,
                PrimitiveKind[] paramKinds,
                LoweringTargets.ArgumentBinding binding) {
            List<MirVal> out = new ArrayList<>(exprs.size());
            for (int i = 0; i < exprs.size(); i++) {
                out.add(lowerAndWiden(exprs.get(i)));
                if (ssa.cur.isTerminated()) break;
            }
            return out;
        }

        // ------------------------------------------------------------------ effect & type helpers

        private MirEffect callEffect(ExprMeta meta, HirFunction target) {
            if (target != null) {
                if (!target.capturedVars().isEmpty()) {
                    return MirEffect.IMPURE_THROW;
                }
                for (HirParam p : target.params()) {
                    if (p.isMut()) {
                        return MirEffect.IMPURE_THROW;
                    }
                }
            }
            return callEffect(meta, target != null ? target.effects() : EffectSet.EMPTY);
        }

        private MirEffect callEffect(ExprMeta meta, EffectSet calleeEffects) {
            EffectSet fx = meta.effects();
            if (fx.isIO() || calleeEffects.isIO() || fx.mutThis() || calleeEffects.mutThis()) {
                return MirEffect.IMPURE_THROW;
            }
            boolean pure =
                    fx.isPure() && (calleeEffects == EffectSet.EMPTY || calleeEffects.isPure());
            boolean nothrow = fx.isNothrow() || calleeEffects.isNothrow();
            boolean terminating = fx.terminating() || calleeEffects.terminating();
            boolean deterministic =
                    fx.isDeterministic()
                            && (calleeEffects == EffectSet.EMPTY
                                    || calleeEffects.isDeterministic());
            if (pure && meta.checkedType() == null) {
                return new MirEffect(
                        MirEffect.Kind.PURE,
                        MirEffect.AliasClass.NONE,
                        nothrow,
                        terminating,
                        deterministic);
            }
            return MirEffect.IMPURE_THROW;
        }

        MirEffect effectFromMeta(ExprMeta meta, boolean defaultNothrow) {
            EffectSet fx = meta.effects();
            if (fx.isPure() && meta.checkedType() == null) {
                return new MirEffect(
                        MirEffect.Kind.PURE,
                        MirEffect.AliasClass.NONE,
                        fx.isNothrow() || defaultNothrow,
                        fx.terminating(),
                        fx.isDeterministic());
            }
            return MirEffect.IMPURE_THROW;
        }

        private static PrimitiveKind constKind(Object v) {
            if (v == null || v instanceof String) return PrimitiveKind.NONE;
            if (v instanceof Boolean) return PrimitiveKind.BOOL;
            if (v instanceof Byte) return PrimitiveKind.BYTE;
            if (v instanceof Short) return PrimitiveKind.SHORT;
            if (v instanceof Integer) return PrimitiveKind.INT;
            if (v instanceof Long) return PrimitiveKind.LONG;
            if (v instanceof Float) return PrimitiveKind.FLOAT;
            if (v instanceof Double) return PrimitiveKind.DOUBLE;
            if (v instanceof Character) return PrimitiveKind.CHAR;
            return PrimitiveKind.NONE;
        }

        private static PrimitiveKind javaPrimitiveKind(Class<?> c) {
            return PrimitiveKind.ofJavaClass(c);
        }

        private static PrimitiveKind javaArgDesiredKind(Class<?> c) {
            if (c == int.class) return PrimitiveKind.INT;
            if (c == boolean.class) return PrimitiveKind.BOOL;
            if (c == double.class) return PrimitiveKind.DOUBLE;
            return PrimitiveKind.NONE;
        }

        private static PrimitiveKind[] paramKinds(HirFunction f) {
            PrimitiveKind[] out = new PrimitiveKind[f.params().size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = f.params().get(i).kind();
            }
            return out;
        }
    }
}
