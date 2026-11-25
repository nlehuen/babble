package org.babblelang.wasm;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;

import java.util.List;

/**
 * Emits Babble function calls, {@code BIP024} tail calls ({@code return_call}), {@code BIP021}
 * optional/named/reordered/overloaded argument binding, generic call trampolines, and portable
 * core method calls ({@code BIP025-P1}).
 */
public final class WasmCallEmitter {
    private final WasmProgramLinker linker;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmRuntimeOpsGen ops;
    private WasmFuncEmitter fe;

    public WasmCallEmitter(WasmProgramLinker linker) {
        this.linker = linker;
        this.ts = linker.types();
        this.rt = linker.runtime();
        this.ops = linker.ops();
    }

    public void setFuncEmitter(WasmFuncEmitter fe) {
        this.fe = fe;
    }

    // ---------------------------------------------------------------- DirectBabbleCall, SelfRecurseCall, SelfTailCall

    public void emitDirectBabbleCall(HirExpr.DirectBabbleCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmProgramLinker.FuncInfo fi = ctx.modInfo.functions.get(call.targetFunctionId());
        if (fi == null) {
            // Fallback to dynamic call if target function is not in this module
            emitGenericCallExpr(call.callee(), call.args(), call.meta().kind(), call.meta().line());
            return;
        }
        int envTemp = -1;
        if (fi.hasEnvParam) {
            fe.emitBoxed(call.callee());
            envTemp = ctx.newLocal("$callEnv", WasmValType.refNull(fi.closureStructType));
            c.refCast(fi.closureStructType).localSet(envTemp);
        } else if (call.callee() != null && !(call.callee() instanceof HirExpr.VarRead || call.callee() instanceof HirExpr.NameRead || call.callee() instanceof HirExpr.FunctionRef)) {
            fe.emitVoid(call.callee());
        }
        emitBoundDirectArgs(fi, envTemp, call.args(), null, call.binding(), call.meta().line());
        c.call(fi.directFuncIdx);
        ctx.stackKind = fi.hir.returnKind();
    }

    public void emitSelfRecurseCall(HirExpr.SelfRecurseCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmProgramLinker.FuncInfo fi = ctx.modInfo.functions.get(call.functionId());
        emitBoundDirectArgs(fi, ctx.envLocal, call.args(), null, call.binding(), call.meta().line());
        ctx.code.call(fi.directFuncIdx);
        ctx.stackKind = fi.hir.returnKind();
    }

    /** Emits a {@code BIP024} self tail call using Wasm 3.0 {@code return_call} ($O(1)$ stack space). */
    public void emitSelfTailCall(HirExpr.SelfTailCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmProgramLinker.FuncInfo fi = ctx.modInfo.functions.get(call.functionId());
        emitBoundDirectArgs(fi, ctx.envLocal, null, call.args(), call.binding(), call.meta().line());
        ctx.code.returnCall(fi.directFuncIdx);
        ctx.stackKind = fi.hir.returnKind();
    }

    /**
     * Evaluates arguments in strict left-to-right source order, reorders into callee parameter order
     * per {@link LoweringTargets.ArgumentBinding}, and pushes {@code [$env,] p0..pn [, $optMask]}.
     */
    private void emitBoundDirectArgs(WasmProgramLinker.FuncInfo fi, int envLocal,
                                     List<HirExpr> plainArgs, List<HirCallArg> callArgs,
                                     LoweringTargets.ArgumentBinding binding, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        HirFunction hf = fi.hir;
        int paramCount = hf.params().size();
        int srcCount = plainArgs != null ? plainArgs.size() : callArgs.size();

        // Fast path: identity positional args, no reordering, no omitted, no rest
        if (binding == null || binding.isIdentityPositional()) {
            if (fi.hasEnvParam) {
                c.localGet(envLocal).refAsNonNull();
            }
            for (int p = 0; p < paramCount; p++) {
                HirParam hp = hf.params().get(p);
                if (p < srcCount) {
                    fe.emitAs(argAt(plainArgs, callArgs, p), hp.kind());
                } else {
                    ts.emitDefaultValue(c, ts.valTypeFor(hp.kind()));
                }
            }
            if (fi.hasOptMaskParam) {
                int mask = 0;
                int optBit = 0;
                for (int p = 0; p < paramCount; p++) {
                    if (hf.params().get(p).defaultValue() != null) {
                        if (p >= srcCount) {
                            mask |= (1 << optBit);
                        }
                        optBit++;
                    }
                }
                c.i32Const(mask);
            }
            return;
        }

        // General path: evaluate all source args strictly left-to-right into temps first!
        int[] argLocals = new int[srcCount];
        PrimitiveKind[] argKinds = new PrimitiveKind[srcCount];
        for (int a = 0; a < srcCount; a++) {
            HirExpr argExpr = argAt(plainArgs, callArgs, a);
            // Determine target param kind if this arg feeds a single non-rest param
            PrimitiveKind targetKind = PrimitiveKind.NONE;
            for (int p = 0; p < binding.paramToArg().length && p < paramCount; p++) {
                if (p != binding.restParam() && binding.paramToArg()[p] == a) {
                    targetKind = hf.params().get(p).kind();
                    break;
                }
            }
            fe.emitAs(argExpr, targetKind);
            argKinds[a] = targetKind;
            argLocals[a] = ctx.newLocal("$arg" + a, ts.valTypeFor(targetKind));
            c.localSet(argLocals[a]);
        }

        if (fi.hasEnvParam) {
            c.localGet(envLocal).refAsNonNull();
        }
        for (int p = 0; p < paramCount; p++) {
            HirParam hp = hf.params().get(p);
            if (binding.hasRest() && p == binding.restParam()) {
                // Pack restArgs into RefArray ($AnyArr wrapped in RefArray)
                int[] restIdxs = binding.restArgs();
                for (int rIdx : restIdxs) {
                    c.localGet(argLocals[rIdx]);
                    fe.convertRawValue(argKinds[rIdx], PrimitiveKind.NONE, line);
                }
                c.arrayNewFixed(ts.anyArrType, restIdxs.length);
                // Wrap in $RefArray with "any" TypeDesc (for P1; P2/P3 refines element TypeDesc)
                int arrTmp = ctx.newLocal("$restArr", WasmValType.ref(ts.anyArrType));
                c.localSet(arrTmp);
                c.i32Const(0).i32Const(0).refNull(ts.typeDescType);
                rt.emitStringLit(c, "any");
                c.structNew(ts.typeDescType)
                        .localGet(arrTmp)
                        .structNew(ts.refArrayType);
            } else {
                int srcIdx = p < binding.paramToArg().length ? binding.paramToArg()[p] : -1;
                if (srcIdx >= 0) {
                    c.localGet(argLocals[srcIdx]);
                    fe.convertRawValue(argKinds[srcIdx], hp.kind(), line);
                } else {
                    ts.emitDefaultValue(c, ts.valTypeFor(hp.kind()));
                }
            }
        }
        if (fi.hasOptMaskParam) {
            int mask = binding.specMaskFor(hf.optionalParamBits(), paramCount);
            c.i32Const(mask);
        }
    }

    private static HirExpr argAt(List<HirExpr> plain, List<HirCallArg> callArgs, int idx) {
        return plain != null ? plain.get(idx) : callArgs.get(idx).value();
    }

    // ---------------------------------------------------------------- DynamicCall & Direct Built-ins

    public void emitDynamicCall(HirExpr.DynamicCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        // 1. Direct built-in recognition (print, println, assert, conversions, same, classof, exception)
        if (call.callee() instanceof HirExpr.NameRead nr && WasmFuncEmitter.isBuiltinName(nr.name())
                && !isShadowedLocalOrGlobal(nr.name())) {
            if (emitDirectBuiltinCall(nr.name(), call)) {
                return;
            }
        }
        if (call.callee() instanceof HirExpr.CoreExceptionRef) {
            emitExceptionConstructorCall(call.args(), call.meta().line());
            return;
        }
        // 2. General indirect call via $FnBase.genericCall
        emitGenericCallWithCallArgs(call.callee(), call.args(), call.meta().kind(), call.meta().line());
    }

    private boolean isShadowedLocalOrGlobal(String name) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        for (var scope : ctx.nsScopes) {
            if (scope.containsKey(name)) {
                return true;
            }
        }
        return ctx.modInfo.topLevelGlobals.containsKey(name);
    }

    private boolean emitDirectBuiltinCall(String name, HirExpr.DynamicCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int line = call.meta().line();
        List<HirCallArg> args = call.args();

        switch (name) {
            case "print", "affiche", "println", "afficherc" -> {
                boolean nl = "println".equals(name) || "afficherc".equals(name);
                // Separate positional values from optional named `to:` arg, evaluating strictly left-to-right!
                int[] valLocals = new int[args.size()];
                int valCount = 0;
                int toLocal = ctx.newLocal("$printTo", ts.anyRef());
                c.refNull(ts.anyType).localSet(toLocal);
                for (HirCallArg a : args) {
                    if ("to".equals(a.name())) {
                        fe.emitBoxed(a.value());
                        c.localSet(toLocal);
                    } else {
                        fe.emitBoxed(a.value());
                        int vl = ctx.newLocal("$pval", ts.anyRef());
                        c.localSet(vl);
                        valLocals[valCount++] = vl;
                    }
                }
                for (int i = 0; i < valCount; i++) {
                    c.localGet(valLocals[i]);
                }
                c.arrayNewFixed(ts.anyArrType, valCount)
                        .localGet(toLocal)
                        .i32Const(nl ? 1 : 0)
                        .i32Const(line)
                        .call(rt.fnPrint);
                c.refNull(ts.anyType);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "assert", "suppose" -> {
                if (args.isEmpty()) {
                    rt.emitStringLit(c, "assert() called without parameters");
                    c.i32Const(line).call(rt.fnThrowMsg).unreachable();
                    ctx.stackKind = PrimitiveKind.BOOL;
                    return true;
                }
                String exprText = call.callSite() != null && call.callSite().argTexts().length > 0
                        ? call.callSite().argTexts()[0] : "assert";
                fe.emitBoxed(args.get(0).value());
                if (args.size() > 1) {
                    fe.emitBoxed(args.get(1).value());
                } else {
                    c.refNull(ts.anyType);
                }
                rt.emitStringLit(c, exprText);
                c.i32Const(line).call(rt.fnAssert);
                ctx.stackKind = PrimitiveKind.BOOL;
                return true;
            }
            case "same" -> {
                if (args.size() == 2) {
                    fe.emitBoxed(args.get(0).value());
                    fe.emitBoxed(args.get(1).value());
                    c.call(rt.fnSame);
                    ctx.stackKind = PrimitiveKind.BOOL;
                    return true;
                }
                return false;
            }
            case "classof" -> {
                if (args.size() == 1) {
                    fe.emitBoxed(args.get(0).value());
                    c.call(ops.fnClassof);
                    ctx.stackKind = PrimitiveKind.NONE;
                    return true;
                }
                return false;
            }
            case "move", "freeze" -> {
                if (args.size() == 1) {
                    fe.emitAndWiden(args.get(0).value());
                    return true;
                }
                return false;
            }
            case "discard" -> {
                for (HirCallArg a : args) {
                    fe.emitVoid(a.value());
                }
                c.refNull(ts.anyType);
                ctx.stackKind = PrimitiveKind.NONE;
                return true;
            }
            case "string" -> {
                if (args.size() == 1) {
                    fe.emitBoxed(args.get(0).value());
                    c.call(rt.fnFormat);
                    ctx.stackKind = PrimitiveKind.NONE;
                    return true;
                }
                return false;
            }
            case "byte" -> { return emitDirectConv(args, call, ops.fnConvByte, PrimitiveKind.BYTE); }
            case "short" -> { return emitDirectConv(args, call, ops.fnConvShort, PrimitiveKind.SHORT); }
            case "int" -> { return emitDirectConv(args, call, ops.fnConvInt, PrimitiveKind.INT); }
            case "long" -> { return emitDirectConv(args, call, ops.fnConvLong, PrimitiveKind.LONG); }
            case "float" -> { return emitDirectConv(args, call, ops.fnConvFloat, PrimitiveKind.FLOAT); }
            case "double" -> { return emitDirectConv(args, call, ops.fnConvDouble, PrimitiveKind.DOUBLE); }
            case "bool" -> { return emitDirectConv(args, call, ops.fnConvBool, PrimitiveKind.BOOL); }
            case "char" -> { return emitDirectConv(args, call, ops.fnConvChar, PrimitiveKind.CHAR); }
            case "exception" -> {
                emitExceptionConstructorCall(args, line);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean emitDirectConv(List<HirCallArg> args, HirExpr.DynamicCall call, int convFn, PrimitiveKind outKind) {
        if (args.size() != 1) {
            return false;
        }
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        String argText = call.callSite() != null && call.callSite().argTexts().length > 0
                ? call.callSite().argTexts()[0] : "";
        fe.emitBoxed(args.get(0).value());
        rt.emitStringLit(c, argText);
        c.i32Const(call.meta().line()).call(convFn);
        ctx.stackKind = outKind;
        return true;
    }

    private void emitExceptionConstructorCall(List<HirCallArg> args, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int msgLocal = ctx.newLocal("$exMsg", WasmValType.refNull(ts.stringType));
        int causeLocal = ctx.newLocal("$exCause", ts.anyRef());
        int pos = 0;
        for (HirCallArg a : args) {
            if ("cause".equals(a.name())) {
                fe.emitBoxed(a.value());
                c.localSet(causeLocal);
            } else if ("message".equals(a.name()) || pos == 0) {
                fe.emitBoxed(a.value());
                int tmp = ctx.newLocal("$mTmp", ts.anyRef());
                c.localTee(tmp).refIsNull().if_(WasmValType.refNull(ts.stringType))
                        .refNull(ts.stringType)
                        .else_()
                        .localGet(tmp).call(rt.fnFormat)
                        .end().localSet(msgLocal);
                if (a.name() == null) pos++;
            } else if (pos == 1) {
                fe.emitBoxed(a.value());
                c.localSet(causeLocal);
                pos++;
            } else {
                fe.emitVoid(a.value());
            }
        }
        c.localGet(msgLocal).localGet(causeLocal).i32Const(line).call(rt.fnMakeExn);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitGenericCallExpr(HirExpr callee, List<HirExpr> args, PrimitiveKind expectedKind, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(callee);
        int fnLocal = ctx.newLocal("$callee", WasmValType.refNull(ts.fnBaseType));
        c.refCast(ts.fnBaseType).localSet(fnLocal);
        for (HirExpr a : args) {
            fe.emitBoxed(a);
        }
        int arrLocal = ctx.newLocal("$callArgs", WasmValType.ref(ts.anyArrType));
        c.arrayNewFixed(ts.anyArrType, args.size()).localSet(arrLocal);
        c.localGet(fnLocal).refAsNonNull()
                .localGet(arrLocal)
                .refNull(ts.anyArrType)
                .localGet(fnLocal).refAsNonNull().structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType);
        ctx.stackKind = PrimitiveKind.NONE;
        if (expectedKind.isPrimitive()) {
            fe.convertStack(PrimitiveKind.NONE, expectedKind, line);
        }
    }

    private void emitGenericCallWithCallArgs(HirExpr callee, List<HirCallArg> args, PrimitiveKind expectedKind, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(callee);
        int fnLocal = ctx.newLocal("$callee", WasmValType.refNull(ts.fnBaseType));
        int tmp = ctx.newLocal("$calleeAny", ts.anyRef());
        c.localTee(tmp).refTest(ts.fnBaseType).op(WasmInstr.SimpleOp.I32_EQZ).if_();
        rt.emitStringLit(c, "Not callable");
        c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(tmp).refCast(ts.fnBaseType).localSet(fnLocal);

        boolean hasNames = false;
        for (HirCallArg a : args) {
            if (a.name() != null) {
                hasNames = true;
                break;
            }
        }
        for (HirCallArg a : args) {
            fe.emitBoxed(a.value());
        }
        int arrLocal = ctx.newLocal("$callArgs", WasmValType.ref(ts.anyArrType));
        c.arrayNewFixed(ts.anyArrType, args.size()).localSet(arrLocal);

        int namesLocal = -1;
        if (hasNames) {
            for (HirCallArg a : args) {
                if (a.name() != null) {
                    rt.emitStringLit(c, a.name());
                } else {
                    c.refNull(ts.anyType);
                }
            }
            namesLocal = ctx.newLocal("$callNames", WasmValType.refNull(ts.anyArrType));
            c.arrayNewFixed(ts.anyArrType, args.size()).localSet(namesLocal);
        }

        c.localGet(fnLocal).refAsNonNull()
                .localGet(arrLocal);
        if (hasNames) {
            c.localGet(namesLocal);
        } else {
            c.refNull(ts.anyArrType);
        }
        c.localGet(fnLocal).refAsNonNull().structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType);
        ctx.stackKind = PrimitiveKind.NONE;
        if (expectedKind.isPrimitive()) {
            fe.convertStack(PrimitiveKind.NONE, expectedKind, line);
        }
    }

    // ---------------------------------------------------------------- Generic Call Trampoline ($generic_F)

    /**
     * Emits {@code $generic_F(self: (ref $FnBase), args: (ref $AnyArr), names: (ref null $AnyArr)) -> any?}.
     * Binds positional + named + optional arguments dynamically, unboxes primitives, invokes
     * {@code $fn_F}, and boxes the return value if primitive.
     */
    public void emitGenericTrampoline(WasmProgramLinker.ModuleLinkInfo modInfo, WasmProgramLinker.FuncInfo fi) {
        WasmModule.FuncDef fn = fi.genericFuncDef;
        WasmCodeBuilder c = fn.code();
        HirFunction hf = fi.hir;
        int paramCount = hf.params().size();

        int argLen = fn.addLocal("argLen", WasmValType.I32, 3);
        int optMask = fn.addLocal("optMask", WasmValType.I32, 3);
        c.localGet(1).arrayLen().localSet(argLen);

        // Allocate boxed slot per parameter initialized to $G_MISSING
        int[] pLocals = new int[paramCount];
        for (int p = 0; p < paramCount; p++) {
            pLocals[p] = fn.addLocal("p" + p, ts.anyRef(), 3);
            c.globalGet(rt.gMissing).localSet(pLocals[p]);
        }

        // Bind args: if names == null, pure positional; else match named args by param name
        int idx = fn.addLocal("idx", WasmValType.I32, 3);
        int nameAny = fn.addLocal("nameAny", ts.anyRef(), 3);
        int valAny = fn.addLocal("valAny", ts.anyRef(), 3);

        c.localGet(2).refIsNull().if_();
        // Pure positional binding
        for (int p = 0; p < paramCount; p++) {
            c.i32Const(p).localGet(argLen).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(1).i32Const(p).arrayGet(ts.anyArrType).localSet(pLocals[p])
                    .end();
        }
        c.else_();
        // Mixed positional + named binding loop
        c.i32Const(0).localSet(idx);
        c.loop()
                .localGet(idx).localGet(argLen).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(1).localGet(idx).arrayGet(ts.anyArrType).localSet(valAny)
                .localGet(2).refAsNonNull().localGet(idx).arrayGet(ts.anyArrType).localTee(nameAny)
                .refIsNull().if_();
        // Positional arg at idx
        for (int p = 0; p < paramCount; p++) {
            c.localGet(idx).i32Const(p).op(WasmInstr.SimpleOp.I32_EQ).if_()
                    .localGet(valAny).localSet(pLocals[p])
                    .end();
        }
        c.else_();
        // Named arg: compare nameAny against each parameter name
        for (int p = 0; p < paramCount; p++) {
            c.localGet(nameAny).refCast(ts.stringType);
            rt.emitStringLit(c, hf.params().get(p).name());
            c.call(rt.fnStrEq).if_()
                    .localGet(valAny).localSet(pLocals[p])
                    .end();
        }
        c.end()
                .localGet(idx).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(idx)
                .br(1)
                .end()
                .end();
        c.end();

        // Push $env (if capturing)
        if (fi.hasEnvParam) {
            c.localGet(0).refCast(fi.closureStructType);
        }
        // Push each parameter (unboxed) and build optMask for missing optional params
        int optBit = 0;
        for (int p = 0; p < paramCount; p++) {
            HirParam hp = hf.params().get(p);
            if (hp.defaultValue() != null) {
                c.localGet(pLocals[p]).globalGet(rt.gMissing).refEq().if_(ts.valTypeFor(hp.kind()))
                        .localGet(optMask).i32Const(1 << optBit).op(WasmInstr.SimpleOp.I32_OR).localSet(optMask);
                ts.emitDefaultValue(c, ts.valTypeFor(hp.kind()));
                c.else_();
                c.localGet(pLocals[p]);
                if (hp.kind().isPrimitive()) {
                    c.i32Const(hf.line()).call(rt.unboxFuncFor(hp.kind()));
                }
                c.end();
                optBit++;
            } else {
                c.localGet(pLocals[p]);
                if (hp.kind().isPrimitive()) {
                    c.i32Const(hf.line()).call(rt.unboxFuncFor(hp.kind()));
                }
            }
        }
        if (fi.hasOptMaskParam) {
            c.localGet(optMask);
        }
        c.call(fi.directFuncIdx);
        if (hf.returnKind().isPrimitive()) {
            c.call(rt.boxFuncFor(hf.returnKind()));
        }
    }

    // ---------------------------------------------------------------- BIP021-I5 Overloads

    public void emitMakeOverloadSet(HirExpr.MakeOverloadSet m) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int n = m.operands().size();
        for (HirExpr op : m.operands()) {
            fe.emitBoxed(op);
        }
        int altsLocal = ctx.newLocal("$ovAlts", WasmValType.ref(ts.anyArrType));
        c.arrayNewFixed(ts.anyArrType, n).localSet(altsLocal);

        for (int i = 0; i < n; i++) {
            String key = i < m.metas().size() ? m.metas().get(i).signatureKey() : "";
            rt.emitStringLit(c, key);
        }
        int keysLocal = ctx.newLocal("$ovKeys", WasmValType.ref(ts.anyArrType));
        c.arrayNewFixed(ts.anyArrType, n).localSet(keysLocal);

        // Create generic call dispatcher that delegates to alt 0 by default (typed dispatch uses OverloadDispatch/OverloadSelect)
        int ovCallFn = getOrCreateOverloadGenericCall();
        c.refFunc(ovCallFn);
        rt.emitStringLit(c, "<overload>");
        c.i32Const(-2)
                .localGet(altsLocal)
                .localGet(keysLocal)
                .structNew(ts.overloadSetType);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private int overloadGenericCallIdx = -1;

    private int getOrCreateOverloadGenericCall() {
        if (overloadGenericCallIdx >= 0) {
            return overloadGenericCallIdx;
        }
        WasmModule.FuncDef fn = linker.module().addFunction("overload_generic_call", ts.genericCallSigType,
                List.of("self", "args", "names"));
        WasmCodeBuilder c = fn.code();
        int alt0 = fn.addLocal("alt0", WasmValType.refNull(ts.fnBaseType), 3);
        c.localGet(0).refCast(ts.overloadSetType).structGet(ts.overloadSetType, 3)
                .i32Const(0).arrayGet(ts.anyArrType).refCast(ts.fnBaseType).localTee(alt0).refAsNonNull()
                .localGet(1).localGet(2)
                .localGet(alt0).refAsNonNull().structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType);
        linker.module().declareFuncRef(fn.funcIndex());
        overloadGenericCallIdx = fn.funcIndex();
        return overloadGenericCallIdx;
    }

    public void emitOverloadSelect(HirExpr.OverloadSelect s) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(s.set());
        if (s.exactSet() && s.index() >= 0) {
            c.refCast(ts.overloadSetType).structGet(ts.overloadSetType, 3)
                    .i32Const(s.index()).arrayGet(ts.anyArrType);
        } else {
            // Search by signatureKey
            int ov = ctx.newLocal("$ovSet", WasmValType.refNull(ts.overloadSetType));
            int keys = ctx.newLocal("$ovK", WasmValType.refNull(ts.anyArrType));
            int i = ctx.newLocal("$ovI", WasmValType.I32);
            int len = ctx.newLocal("$ovLen", WasmValType.I32);
            int res = ctx.newLocal("$ovRes", ts.anyRef());
            c.refCast(ts.overloadSetType).localTee(ov)
                    .structGet(ts.overloadSetType, 4).localTee(keys).arrayLen().localSet(len);
            c.i32Const(0).localSet(i);
            int done = ctx.pushBlock("ov_sel_done");
            c.block();
            int loop = ctx.pushBlock("ov_sel_loop");
            c.loop();
            c.localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S).if_();
            ctx.pushBlock("ov_sel_if");
            c.localGet(keys).localGet(i).arrayGet(ts.anyArrType).refCast(ts.stringType);
            rt.emitStringLit(c, s.signatureKey() != null ? s.signatureKey() : "");
            c.call(rt.fnStrEq).if_();
            ctx.pushBlock("ov_sel_hit");
            c.localGet(ov).structGet(ts.overloadSetType, 3).localGet(i).arrayGet(ts.anyArrType)
                    .localSet(res).br(ctx.relDepth(done));
            ctx.popBlock();
            c.end();
            c.localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(ctx.relDepth(loop));
            ctx.popBlock();
            c.end();
            ctx.popBlock();
            c.end();
            ctx.popBlock();
            c.end();
            c.localGet(res);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    public void emitOverloadDispatch(HirExpr.OverloadDispatch d) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int line = d.meta().line();
        PrimitiveKind resKind = d.meta().kind();

        // Evaluate callee and all args once
        int calleeLocal = -1;
        if (d.callee() != null) {
            fe.emitBoxed(d.callee());
            calleeLocal = ctx.newLocal("$ovCallee", ts.anyRef());
            c.localSet(calleeLocal);
        }
        int n = d.args().size();
        int[] argLocals = new int[n];
        for (int i = 0; i < n; i++) {
            fe.emitBoxed(d.args().get(i));
            argLocals[i] = ctx.newLocal("$ovArg" + i, ts.anyRef());
            c.localSet(argLocals[i]);
        }

        int doneDepth = ctx.pushBlock("ov_disp_done");
        c.block(ts.valTypeFor(resKind));

        for (HirExpr.OverloadBranch br : d.branches()) {
            // Check all union-split runtime checks for this branch
            if (!br.checks().isEmpty()) {
                for (int ci = 0; ci < br.checks().size(); ci++) {
                    LoweringTargets.OverloadSplitCheck chk = br.checks().get(ci);
                    emitSplitCheck(argLocals[chk.argIndex()], chk);
                    if (ci > 0) {
                        c.op(WasmInstr.SimpleOp.I32_AND);
                    }
                }
                c.if_();
                ctx.pushBlock("ov_br");
            }

            // Invoke branch target
            if (br.directFunctionId() >= 0 && ctx.modInfo.functions.containsKey(br.directFunctionId())) {
                WasmProgramLinker.FuncInfo fi = ctx.modInfo.functions.get(br.directFunctionId());
                if (fi.hasEnvParam) {
                    c.localGet(calleeLocal).refCast(ts.overloadSetType).structGet(ts.overloadSetType, 3)
                            .i32Const(br.altIndex()).arrayGet(ts.anyArrType).refCast(fi.closureStructType);
                }
                for (int p = 0; p < fi.hir.params().size(); p++) {
                    HirParam hp = fi.hir.params().get(p);
                    int srcIdx = br.binding() != null && p < br.binding().paramToArg().length
                            ? br.binding().paramToArg()[p] : p;
                    if (srcIdx >= 0 && srcIdx < n) {
                        c.localGet(argLocals[srcIdx]);
                        fe.convertRawValue(PrimitiveKind.NONE, hp.kind(), line);
                    } else {
                        ts.emitDefaultValue(c, ts.valTypeFor(hp.kind()));
                    }
                }
                if (fi.hasOptMaskParam) {
                    int mask = br.binding() != null
                            ? br.binding().specMaskFor(fi.hir.optionalParamBits(), fi.hir.params().size()) : 0;
                    c.i32Const(mask);
                }
                c.call(fi.directFuncIdx);
                fe.convertRawValue(fi.hir.returnKind(), resKind, line);
            } else {
                // Indirect call via selected overload alternative
                int altFn = ctx.newLocal("$ovAltFn", WasmValType.refNull(ts.fnBaseType));
                c.localGet(calleeLocal).refCast(ts.overloadSetType).structGet(ts.overloadSetType, 3)
                        .i32Const(Math.max(0, br.altIndex())).arrayGet(ts.anyArrType)
                        .refCast(ts.fnBaseType).localTee(altFn).refAsNonNull();
                for (int i = 0; i < n; i++) {
                    c.localGet(argLocals[i]);
                }
                c.arrayNewFixed(ts.anyArrType, n)
                        .refNull(ts.anyArrType)
                        .localGet(altFn).refAsNonNull().structGet(ts.fnBaseType, 0)
                        .callRef(ts.genericCallSigType);
                fe.convertRawValue(PrimitiveKind.NONE, resKind, line);
            }
            c.br(ctx.relDepth(doneDepth));

            if (!br.checks().isEmpty()) {
                ctx.popBlock();
                c.end();
            }
        }

        rt.emitStringLit(c, "No matching overload");
        c.i32Const(line).call(rt.fnThrowMsg).unreachable();

        ctx.popBlock();
        c.end();
        ctx.stackKind = resKind;
    }

    private void emitSplitCheck(int argLocal, LoweringTargets.OverloadSplitCheck chk) {
        WasmCodeBuilder c = fe.ctx().code;
        if (chk.isNull()) {
            c.localGet(argLocal).refIsNull();
            return;
        }
        boolean first = true;
        for (String desc : chk.descriptors()) {
            int bid = WasmRuntimeOpsGen.builtinId(desc);
            if (bid >= 0) {
                c.localGet(argLocal).i32Const(bid).call(ops.fnIsBuiltin);
            } else {
                c.i32Const(0);
            }
            if (!first) {
                c.op(WasmInstr.SimpleOp.I32_AND);
            }
            first = false;
        }
        if (first) {
            c.i32Const(1);
        }
    }

    // ---------------------------------------------------------------- Portable core JavaMethodCall lowering

    public void emitPortableJavaMethodCall(HirExpr.JavaMethodCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        String owner = call.target().method().getDeclaringClass().getName();
        String name = call.target().method().getName();
        int line = call.meta().line();

        // 1. org.babblelang.runtime.BabbleException / java.lang.Throwable
        if ("org.babblelang.runtime.BabbleException".equals(owner) || "java.lang.Throwable".equals(owner)) {
            fe.emitBoxed(call.receiver());
            c.refCast(ts.babbleExnType);
            if ("getMessage".equals(name)) {
                c.structGet(ts.babbleExnType, 0);
                ctx.stackKind = PrimitiveKind.NONE;
                return;
            }
            if ("getCause".equals(name)) {
                c.structGet(ts.babbleExnType, 1);
                ctx.stackKind = PrimitiveKind.NONE;
                return;
            }
            if ("addSuppressed".equals(name) && call.args().size() == 1) {
                fe.emitBoxed(call.args().get(0));
                c.refAsNonNull().call(rt.fnAddSuppressed).refNull(ts.anyType);
                ctx.stackKind = PrimitiveKind.NONE;
                return;
            }
            if ("getSuppressed".equals(name)) {
                int exn = ctx.newLocal("$exn", WasmValType.refNull(ts.babbleExnType));
                int cnt = ctx.newLocal("$scnt", WasmValType.I32);
                int out = ctx.newLocal("$sout", WasmValType.refNull(ts.anyArrType));
                c.localTee(exn).structGet(ts.babbleExnType, 3).localTee(cnt)
                        .arrayNewDefault(ts.anyArrType).localSet(out);
                c.localGet(cnt).i32Const(0).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                        .localGet(out).i32Const(0)
                        .localGet(exn).structGet(ts.babbleExnType, 2).refAsNonNull()
                        .i32Const(0).localGet(cnt)
                        .arrayCopy(ts.anyArrType, ts.anyArrType)
                        .end();
                c.i32Const(0).i32Const(0).refNull(ts.typeDescType);
                rt.emitStringLit(c, "exception");
                c.structNew(ts.typeDescType)
                        .localGet(out).refAsNonNull()
                        .structNew(ts.refArrayType);
                ctx.stackKind = PrimitiveKind.NONE;
                return;
            }
            if ("toString".equals(name)) {
                c.call(rt.fnFormat);
                ctx.stackKind = PrimitiveKind.NONE;
                return;
            }
        }

        // 2. java.lang.String basic methods
        if ("java.lang.String".equals(owner) || "java.lang.CharSequence".equals(owner)) {
            fe.emitBoxed(call.receiver());
            c.refCast(ts.stringType);
            if ("length".equals(name) && call.args().isEmpty()) {
                c.structGet(ts.stringType, 0).arrayLen();
                ctx.stackKind = PrimitiveKind.INT;
                return;
            }
            if ("isEmpty".equals(name) && call.args().isEmpty()) {
                c.structGet(ts.stringType, 0).arrayLen().op(WasmInstr.SimpleOp.I32_EQZ);
                ctx.stackKind = PrimitiveKind.BOOL;
                return;
            }
            if ("charAt".equals(name) && call.args().size() == 1) {
                c.structGet(ts.stringType, 0);
                fe.emitAs(call.args().get(0), PrimitiveKind.INT);
                c.arrayGetU(ts.charArrType);
                ctx.stackKind = PrimitiveKind.CHAR;
                return;
            }
            if ("equals".equals(name) && call.args().size() == 1) {
                fe.emitBoxed(call.args().get(0));
                c.call(rt.fnEquals);
                ctx.stackKind = PrimitiveKind.BOOL;
                return;
            }
            if ("hashCode".equals(name) && call.args().isEmpty()) {
                c.call(rt.fnStrHash);
                ctx.stackKind = PrimitiveKind.INT;
                return;
            }
            if ("strip".equals(name) && call.args().isEmpty()) {
                c.call(rt.fnStrStrip);
                ctx.stackKind = PrimitiveKind.NONE;
                return;
            }
        }

        // 3. java.lang.Math hardware Wasm intrinsics (zero host calls!)
        if ("java.lang.Math".equals(owner) || "java.lang.StrictMath".equals(owner)) {
            if (!call.pureStaticReceiver() && call.receiver() != null) {
                fe.emitVoid(call.receiver());
            }
            if ("sqrt".equals(name) && call.args().size() == 1) {
                fe.emitAs(call.args().get(0), PrimitiveKind.DOUBLE);
                c.op(WasmInstr.SimpleOp.F64_SQRT);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return;
            }
            if ("abs".equals(name) && call.args().size() == 1) {
                PrimitiveKind rk = call.meta().kind();
                if (rk == PrimitiveKind.DOUBLE) {
                    fe.emitAs(call.args().get(0), PrimitiveKind.DOUBLE);
                    c.op(WasmInstr.SimpleOp.F64_ABS);
                    ctx.stackKind = PrimitiveKind.DOUBLE;
                    return;
                }
                if (rk == PrimitiveKind.FLOAT) {
                    fe.emitAs(call.args().get(0), PrimitiveKind.FLOAT);
                    c.op(WasmInstr.SimpleOp.F32_ABS);
                    ctx.stackKind = PrimitiveKind.FLOAT;
                    return;
                }
                if (rk == PrimitiveKind.INT) {
                    int x = ctx.newLocal("$absI", WasmValType.I32);
                    fe.emitAs(call.args().get(0), PrimitiveKind.INT);
                    c.localTee(x).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_(WasmValType.I32)
                            .i32Const(0).localGet(x).op(WasmInstr.SimpleOp.I32_SUB)
                            .else_().localGet(x).end();
                    ctx.stackKind = PrimitiveKind.INT;
                    return;
                }
                if (rk == PrimitiveKind.LONG) {
                    int x = ctx.newLocal("$absL", WasmValType.I64);
                    fe.emitAs(call.args().get(0), PrimitiveKind.LONG);
                    c.localTee(x).i64Const(0L).op(WasmInstr.SimpleOp.I64_LT_S).if_(WasmValType.I64)
                            .i64Const(0L).localGet(x).op(WasmInstr.SimpleOp.I64_SUB)
                            .else_().localGet(x).end();
                    ctx.stackKind = PrimitiveKind.LONG;
                    return;
                }
            }
            if ("floor".equals(name) && call.args().size() == 1) {
                fe.emitAs(call.args().get(0), PrimitiveKind.DOUBLE);
                c.op(WasmInstr.SimpleOp.F64_FLOOR);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return;
            }
            if ("ceil".equals(name) && call.args().size() == 1) {
                fe.emitAs(call.args().get(0), PrimitiveKind.DOUBLE);
                c.op(WasmInstr.SimpleOp.F64_CEIL);
                ctx.stackKind = PrimitiveKind.DOUBLE;
                return;
            }
            if (("min".equals(name) || "max".equals(name)) && call.args().size() == 2) {
                PrimitiveKind rk = call.meta().kind();
                boolean isMin = "min".equals(name);
                if (rk == PrimitiveKind.DOUBLE) {
                    fe.emitAs(call.args().get(0), PrimitiveKind.DOUBLE);
                    fe.emitAs(call.args().get(1), PrimitiveKind.DOUBLE);
                    c.op(isMin ? WasmInstr.SimpleOp.F64_MIN : WasmInstr.SimpleOp.F64_MAX);
                    ctx.stackKind = PrimitiveKind.DOUBLE;
                    return;
                }
                if (rk == PrimitiveKind.INT) {
                    int a = ctx.newLocal("$mmA", WasmValType.I32);
                    int b = ctx.newLocal("$mmB", WasmValType.I32);
                    fe.emitAs(call.args().get(0), PrimitiveKind.INT);
                    c.localSet(a);
                    fe.emitAs(call.args().get(1), PrimitiveKind.INT);
                    c.localSet(b);
                    c.localGet(a).localGet(b)
                            .localGet(a).localGet(b)
                            .op(isMin ? WasmInstr.SimpleOp.I32_LT_S : WasmInstr.SimpleOp.I32_GT_S)
                            .select();
                    ctx.stackKind = PrimitiveKind.INT;
                    return;
                }
            }
        }

        // 4. java.lang.Object universal methods (equals, hashCode, toString)
        if ("java.lang.Object".equals(owner)) {
            if ("equals".equals(name) && call.args().size() == 1) {
                fe.emitBoxed(call.receiver());
                fe.emitBoxed(call.args().get(0));
                c.call(rt.fnEquals);
                ctx.stackKind = PrimitiveKind.BOOL;
                return;
            }
            if ("hashCode".equals(name) && call.args().isEmpty()) {
                fe.emitBoxed(call.receiver());
                c.call(rt.fnHashCode);
                ctx.stackKind = PrimitiveKind.INT;
                return;
            }
            if ("toString".equals(name) && call.args().isEmpty()) {
                fe.emitBoxed(call.receiver());
                c.call(rt.fnFormat);
                ctx.stackKind = PrimitiveKind.NONE;
                return;
            }
        }

        rt.emitStringLit(c, "Unsupported Java method on Wasm : " + owner + "." + name);
        c.i32Const(line).call(rt.fnThrowMsg).unreachable();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    public void emitPortableJavaConstructorCall(HirExpr.JavaConstructorCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        String owner = call.target().constructor().getDeclaringClass().getName();
        int line = call.meta().line();
        if ("org.babblelang.runtime.BabbleException".equals(owner)) {
            if (!call.pureStaticCallee() && call.callee() != null) {
                fe.emitVoid(call.callee());
            }
            int msgLocal = ctx.newLocal("$exMsg", WasmValType.refNull(ts.stringType));
            int causeLocal = ctx.newLocal("$exCause", ts.anyRef());
            if (call.args().size() >= 1) {
                fe.emitBoxed(call.args().get(0));
                int tmp = ctx.newLocal("$mTmp", ts.anyRef());
                c.localTee(tmp).refIsNull().if_(WasmValType.refNull(ts.stringType))
                        .refNull(ts.stringType)
                        .else_()
                        .localGet(tmp).call(rt.fnFormat)
                        .end().localSet(msgLocal);
            }
            if (call.args().size() >= 2) {
                fe.emitBoxed(call.args().get(1));
                c.localSet(causeLocal);
            }
            c.localGet(msgLocal).localGet(causeLocal).i32Const(line).call(rt.fnMakeExn);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        rt.emitStringLit(c, "Unsupported Java constructor on Wasm : " + owner);
        c.i32Const(line).call(rt.fnThrowMsg).unreachable();
        ctx.stackKind = PrimitiveKind.NONE;
    }
}
