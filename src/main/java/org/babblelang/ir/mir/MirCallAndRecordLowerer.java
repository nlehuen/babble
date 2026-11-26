package org.babblelang.ir.mir;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.babblelang.analysis.BuiltinAxioms;
import org.babblelang.analysis.EffectSet;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.effects.Effect;
import org.babblelang.ir.hir.ExprMeta;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.mir.MirSsaBuilder.BlockBuilder;

/**
 * Lowers call expressions (Babble direct/self/interface calls, Java method/constructor calls, safe
 * calls), record allocation/reuse, opaque HIR templates, and MIR effect classification.
 */
final class MirCallAndRecordLowerer {
    private final MirLowerer.FunctionLowerer parent;
    private final MirSsaBuilder ssa;

    MirCallAndRecordLowerer(MirLowerer.FunctionLowerer parent, MirSsaBuilder ssa) {
        this.parent = parent;
        this.ssa = ssa;
    }

    // ------------------------------------------------------------------ calls & records

    MirVal lowerSelfTailCall(HirExpr.SelfTailCall c) {
        int ln = c.meta().line();
        List<MirVal> args = new ArrayList<>(c.args().size());
        List<String> argNames = new ArrayList<>(c.args().size());
        for (HirCallArg a : c.args()) {
            args.add(parent.lowerAndWiden(a.value()));
            if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
            argNames.add(a.name());
        }
        ssa.terminateFixed(
                new MirTerminator.TailCall(c.functionId(), args, argNames, c.binding(), ln));
        return ssa.emitConst(null, PrimitiveKind.NONE, ln);
    }

    MirVal lowerSelfRecurseCall(HirExpr.SelfRecurseCall c) {
        int ln = c.meta().line();
        HirFunction target = parent.module.function(c.functionId());
        List<MirVal> args =
                lowerCallExprList(
                        c.args(), target != null ? paramKinds(target) : null, c.binding());
        if (ssa.cur.isTerminated()) return ssa.emitConst(null, PrimitiveKind.NONE, ln);
        PrimitiveKind retKind =
                (target != null && target.hasSpec()) ? target.returnKind() : PrimitiveKind.NONE;
        MirEffect fx = callEffect(c.meta(), target);
        boolean comm =
                c.meta().isCommutative()
                        || (target != null && target.effects().has(Effect.COMMUTATIVE));
        boolean idem =
                c.meta().isIdempotent()
                        || (target != null && target.effects().has(Effect.IDEMPOTENT));
        MirVal res = ssa.newVal(retKind, null);
        ssa.emit(
                new MirInst.CallBabble(
                        res, null, true, c.functionId(), args, c.binding(), comm, idem, ln, fx));
        return ssa.applyCheckedType(res, c.meta().checkedType(), ln);
    }

    MirVal lowerModuleExportGet(HirExpr.ModuleExportGet g) {
        int ln = g.meta().line();
        MirVal mod = parent.lowerBoxed(g.receiver());
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
        MirVal res = ssa.newVal(PrimitiveKind.NONE, null);
        ssa.emit(new MirInst.LoadExport(res, mod, g.target(), ln, fx));
        return ssa.applyCheckedType(res, g.meta().checkedType(), ln);
    }

    MirVal lowerDirectBabbleCall(HirExpr.DirectBabbleCall c) {
        int ln = c.meta().line();
        LoweringTargets.ExportedFunctionTarget ext = c.externalTarget();
        HirFunction target = ext == null ? parent.module.function(c.targetFunctionId()) : null;
        MirVal callee = parent.lowerBoxed(c.callee());
        if (ssa.cur.isTerminated()) return callee;
        PrimitiveKind[] pKinds =
                ext != null
                        ? ext.signature().parameterKinds()
                        : (target != null ? paramKinds(target) : null);
        List<MirVal> args = lowerCallExprList(c.args(), pKinds, c.binding());
        if (ssa.cur.isTerminated()) return ssa.emitConst(null, PrimitiveKind.NONE, ln);
        PrimitiveKind retKind;
        MirEffect fx;
        boolean comm;
        boolean idem;
        if (ext != null) {
            retKind = ext.signature().hasSpec() ? ext.signature().returnKind() : PrimitiveKind.NONE;
            fx = ext.hasMutParam() ? MirEffect.IMPURE_THROW : callEffect(c.meta(), ext.effects());
            comm = c.meta().isCommutative() || ext.effects().has(Effect.COMMUTATIVE);
            idem = c.meta().isIdempotent() || ext.effects().has(Effect.IDEMPOTENT);
        } else {
            retKind =
                    (target != null && target.hasSpec()) ? target.returnKind() : PrimitiveKind.NONE;
            fx = callEffect(c.meta(), target);
            comm =
                    c.meta().isCommutative()
                            || (target != null && target.effects().has(Effect.COMMUTATIVE));
            idem =
                    c.meta().isIdempotent()
                            || (target != null && target.effects().has(Effect.IDEMPOTENT));
        }
        MirVal res = ssa.newVal(retKind, null);
        ssa.emit(
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
        return ssa.applyCheckedType(res, c.meta().checkedType(), ln);
    }

    MirVal lowerDirectThisMethodCall(HirExpr.DirectThisMethodCall c) {
        int ln = c.meta().line();
        MirVal recv = parent.lowerBoxed(c.receiver());
        if (ssa.cur.isTerminated()) return recv;
        List<MirVal> args = new ArrayList<>(c.args().size());
        List<String> argNames = new ArrayList<>(c.args().size());
        for (HirCallArg a : c.args()) {
            args.add(parent.lowerAndWiden(a.value()));
            if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
            argNames.add(a.name());
        }
        HirFunction target =
                c.targetFunctionId() >= 0 ? parent.module.function(c.targetFunctionId()) : null;
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
        MirVal res = ssa.newVal(retKind, null);
        ssa.emit(
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
        return ssa.applyCheckedType(res, c.meta().checkedType(), ln);
    }

    MirVal lowerInterfaceCall(HirExpr.InterfaceCall c) {
        int ln = c.meta().line();
        MirVal recv = parent.lowerBoxed(c.receiver());
        if (ssa.cur.isTerminated()) return recv;
        List<MirVal> args = new ArrayList<>(c.args().size());
        for (HirExpr a : c.args()) {
            args.add(parent.lowerAndWiden(a));
            if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
        }
        PrimitiveKind retKind =
                c.signature() != null ? c.signature().returnKind() : PrimitiveKind.NONE;
        MirEffect fx = callEffect(c.meta(), EffectSet.EMPTY);
        MirVal res = ssa.newVal(retKind, null);
        ssa.emit(
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
        return ssa.applyCheckedType(res, c.meta().checkedType(), ln);
    }

    MirVal lowerJavaMethodCall(HirExpr.JavaMethodCall c) {
        int ln = c.meta().line();
        LoweringTargets.JavaCallTarget target = c.target();
        MirVal recv = null;
        if (!target.isStatic() || !c.pureStaticReceiver()) {
            recv = parent.lowerBoxed(c.receiver());
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
                args.add(parent.lowerAs(argExpr, desired));
            } else {
                args.add(parent.lowerAndWiden(argExpr));
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
                                fx.kind(), fx.alias(), false, fx.terminating(), fx.deterministic());
            }
        }
        MirVal res = ssa.newVal(retKind, null);
        ssa.emit(
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
        return ssa.applyCheckedType(res, c.meta().checkedType(), ln);
    }

    MirVal lowerJavaConstructorCall(HirExpr.JavaConstructorCall c) {
        int ln = c.meta().line();
        MirVal callee = null;
        if (!c.pureStaticCallee()) {
            callee = parent.lowerBoxed(c.callee());
            if (ssa.cur.isTerminated()) return callee;
        }
        List<MirVal> args = new ArrayList<>(c.args().size());
        for (HirExpr a : c.args()) {
            args.add(parent.lowerAndWiden(a));
            if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
        }
        MirVal res = ssa.newVal(PrimitiveKind.NONE, null);
        ssa.emit(
                new MirInst.CallJavaCtor(
                        res,
                        callee,
                        c.calleeText(),
                        c.pureStaticCallee(),
                        c.target(),
                        args,
                        ln,
                        callEffect(c.meta(), EffectSet.EMPTY)));
        return ssa.applyCheckedType(res, c.meta().checkedType(), ln);
    }

    MirVal lowerSafeCall(HirExpr.SafeCall c) {
        // SafeCall evaluates args ONLY when receiver != null: lower as a CFG diamond!
        int ln = c.meta().line();
        MirVal recv = parent.lowerBoxed(c.receiver());
        if (ssa.cur.isTerminated()) return recv;
        MirVal nullConst = ssa.emitConst(null, PrimitiveKind.NONE, ln);
        MirVal isNonNull = ssa.newVal(PrimitiveKind.BOOL, null);
        ssa.emit(
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

        BlockBuilder bbNonNull = ssa.newBlock(List.of(), false, false);
        MirVal mergeParam = ssa.newVal(PrimitiveKind.NONE, null);
        BlockBuilder bbMerge = ssa.newBlock(List.of(mergeParam), false, false);
        ssa.terminateBrIf(isNonNull, bbNonNull, List.of(), bbMerge, List.of(nullConst), ln);
        ssa.sealBlock(bbNonNull);

        ssa.cur = bbNonNull;
        MirVal narrowed = ssa.newVal(PrimitiveKind.NONE, null);
        ssa.emit(new MirInst.Narrow(narrowed, MirInst.NarrowKind.NON_NULL, recv, "non-null", ln));
        List<MirVal> ops = new ArrayList<>(1 + c.args().size());
        ops.add(narrowed);
        for (HirCallArg a : c.args()) {
            ops.add(parent.lowerAndWiden(a.value()));
            if (ssa.cur.isTerminated()) break;
        }
        if (!ssa.cur.isTerminated()) {
            MirVal callRes = ssa.newVal(PrimitiveKind.NONE, null);
            ssa.emit(new MirInst.OpaqueExpr(callRes, c, ops, ln, MirEffect.IMPURE_THROW));
            ssa.terminateBr(bbMerge, List.of(callRes), ln);
        }
        ssa.sealBlock(bbMerge);
        ssa.cur = bbMerge;
        return mergeParam;
    }

    MirVal lowerRecordNew(HirExpr.RecordNew r) {
        int ln = r.meta().line();
        MirVal classRef = parent.lowerBoxed(r.classRef());
        if (ssa.cur.isTerminated()) return classRef;
        List<MirVal> args = new ArrayList<>(r.args().size());
        for (HirExpr a : r.args()) {
            args.add(parent.lowerAndWiden(a));
            if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
        }
        MirVal res = ssa.newVal(PrimitiveKind.NONE, null);
        ssa.emit(
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
        return ssa.applyCheckedType(res, r.meta().checkedType(), ln);
    }

    MirVal lowerRecordReuse(HirExpr.RecordReuse r) {
        int ln = r.meta().line();
        MirVal target = parent.lowerBoxed(r.target());
        if (ssa.cur.isTerminated()) return target;
        List<MirVal> args = new ArrayList<>(r.args().size());
        for (HirExpr a : r.args()) {
            args.add(parent.lowerAndWiden(a));
            if (ssa.cur.isTerminated()) return args.get(args.size() - 1);
        }
        MirVal res = ssa.newVal(PrimitiveKind.NONE, null);
        MirEffect.AliasClass alias = ssa.aliasOf(target, r.target().meta());
        ssa.emit(
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
        ssa.propagateAlias(target, res);
        return ssa.applyCheckedType(res, r.meta().checkedType(), ln);
    }

    MirVal lowerOpaque(HirExpr expr) {
        int ln = expr.meta().line();
        List<HirExpr> children = MirLowerer.opaqueChildExprs(expr);
        List<MirVal> operands = new ArrayList<>(children.size());
        for (HirExpr ch : children) {
            operands.add(parent.lowerAndWiden(ch));
            if (ssa.cur.isTerminated()) return operands.get(operands.size() - 1);
        }
        PrimitiveKind outKind = opaqueResultKind(expr);
        MirEffect fx = opaqueEffect(expr, operands);
        MirVal res = ssa.newVal(outKind, null);
        ssa.emit(new MirInst.OpaqueExpr(res, expr, operands, ln, fx));
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
                    c.meta().checkedType() == null ? dynamicCallResultKind(c) : PrimitiveKind.NONE;
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
                HirFunction target = parent.module.function(fr.functionId());
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
                                    ssa.aliasOf(operands.get(0), a.receiver().meta()), false)
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
            out.add(parent.lowerAndWiden(exprs.get(i)));
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
        boolean pure = fx.isPure() && (calleeEffects == EffectSet.EMPTY || calleeEffects.isPure());
        boolean nothrow = fx.isNothrow() || calleeEffects.isNothrow();
        boolean terminating = fx.terminating() || calleeEffects.terminating();
        boolean deterministic =
                fx.isDeterministic()
                        && (calleeEffects == EffectSet.EMPTY || calleeEffects.isDeterministic());
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

    static PrimitiveKind constKind(Object v) {
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
