package org.babblelang.wasm;

import java.util.List;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;

/**
 * Emits Babble function calls, {@code BIP024} tail calls ({@code return_call}), {@code BIP021}
 * optional/named/reordered/overloaded argument binding, generic call trampolines, and portable core
 * method calls ({@code BIP025-I1}).
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

    // ---------------------------------------------------------------- DirectBabbleCall,
    // SelfRecurseCall, SelfTailCall

    public void emitDirectBabbleCall(HirExpr.DirectBabbleCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmProgramLinker.FuncInfo fi = null;
        if (call.externalTarget() != null) {
            LoweringTargets.ExportedFunctionTarget ext = call.externalTarget();
            WasmProgramLinker.ModuleLinkInfo extMod =
                    ext.moduleKey() != null ? linker.moduleByKey(ext.moduleKey()) : null;
            if (extMod == null && ext.ownerJvmName() != null) {
                extMod = linker.moduleByKey(ext.ownerJvmName());
            }
            if (extMod != null) {
                fi = extMod.immutableTopLevelFuncs.get(ext.exportName());
            }
        } else {
            fi = ctx.modInfo.functions.get(call.targetFunctionId());
        }
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
        } else if (call.callee() != null && !isGuaranteedDefinedPureRef(call.callee())) {
            fe.emitVoid(call.callee());
        }
        emitBoundDirectArgs(fi, envTemp, call.args(), null, call.binding(), call.meta().line());
        c.call(fi.directFuncIdx);
        ctx.stackKind = fi.hir.returnKind();
    }

    public boolean isGuaranteedDefinedPureRef(HirExpr expr) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        if (expr instanceof HirExpr.FunctionRef) {
            return true;
        }
        if (expr instanceof HirExpr.VarRead vr) {
            return vr.var() != null && (!vr.var().mayBeUndefined() || vr.var().definitelyDefined());
        }
        if (expr instanceof HirExpr.NameRead nr) {
            if (fe.isShadowedInNsScope(nr.name())) {
                return false;
            }
            if (nr.definitelyDefined()) {
                return true;
            }
            Integer defIdx = ctx.modInfo.topLevelDefIndex.get(nr.name());
            return defIdx != null
                    && ctx.currentTopLevelStmtIdx >= 0
                    && defIdx < ctx.currentTopLevelStmtIdx;
        }
        if (expr instanceof HirExpr.ModuleExportGet meg) {
            return meg.target().definitelyInitialized()
                    && isGuaranteedDefinedPureRef(meg.receiver());
        }
        return false;
    }

    public void emitSelfRecurseCall(HirExpr.SelfRecurseCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmProgramLinker.FuncInfo fi = ctx.modInfo.functions.get(call.functionId());
        emitBoundDirectArgs(
                fi, ctx.envLocal, call.args(), null, call.binding(), call.meta().line());
        ctx.code.call(fi.directFuncIdx);
        ctx.stackKind = fi.hir.returnKind();
    }

    /**
     * Emits a {@code BIP024} self tail call using Wasm 3.0 {@code return_call} ($O(1)$ stack
     * space).
     */
    public void emitSelfTailCall(HirExpr.SelfTailCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmProgramLinker.FuncInfo fi = ctx.modInfo.functions.get(call.functionId());
        emitBoundDirectArgs(
                fi, ctx.envLocal, null, call.args(), call.binding(), call.meta().line());
        ctx.code.returnCall(fi.directFuncIdx);
        ctx.stackKind = fi.hir.returnKind();
    }

    /**
     * Evaluates arguments in strict left-to-right source order, reorders into callee parameter
     * order per {@link LoweringTargets.ArgumentBinding}, and pushes {@code [$env,] p0..pn [,
     * $optMask]}.
     */
    private void emitBoundDirectArgs(
            WasmProgramLinker.FuncInfo fi,
            int envLocal,
            List<HirExpr> plainArgs,
            List<HirCallArg> callArgs,
            LoweringTargets.ArgumentBinding binding,
            int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        HirFunction hf = fi.hir;
        int paramCount = hf.params().size();
        int srcCount = plainArgs != null ? plainArgs.size() : callArgs.size();

        // Fast path: identity positional args, no reordering, no omitted, no rest
        if (binding == null || binding.isIdentityPositional()) {
            if (fi.ownerClass != null || fi.isInterfaceDefault) {
                c.localGet(ctx.thisLocal).refAsNonNull();
            } else if (fi.hasEnvParam) {
                c.localGet(envLocal).refAsNonNull();
            }
            for (int p = 0; p < paramCount; p++) {
                HirParam hp = hf.params().get(p);
                boolean boxedOpt = fi.hasBoxedMissingOptParams && hp.defaultValue() != null;
                if (p < srcCount) {
                    if (boxedOpt) {
                        fe.emitBoxed(argAt(plainArgs, callArgs, p));
                    } else {
                        fe.emitAs(argAt(plainArgs, callArgs, p), hp.kind());
                    }
                } else if (boxedOpt) {
                    c.globalGet(rt.gMissing);
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
                    HirParam hp = hf.params().get(p);
                    targetKind =
                            (fi.hasBoxedMissingOptParams && hp.defaultValue() != null)
                                    ? PrimitiveKind.NONE
                                    : hp.kind();
                    break;
                }
            }
            fe.emitAs(argExpr, targetKind);
            argKinds[a] = targetKind;
            argLocals[a] = ctx.newLocal("$arg" + a, ts.valTypeFor(targetKind));
            c.localSet(argLocals[a]);
        }

        if (fi.ownerClass != null || fi.isInterfaceDefault) {
            c.localGet(ctx.thisLocal).refAsNonNull();
        } else if (fi.hasEnvParam) {
            c.localGet(envLocal).refAsNonNull();
        }
        for (int p = 0; p < paramCount; p++) {
            HirParam hp = hf.params().get(p);
            boolean boxedOpt = fi.hasBoxedMissingOptParams && hp.defaultValue() != null;
            if (binding.hasRest() && p == binding.restParam()) {
                // Pack restArgs (expanding spread arguments via fnListAddAll) into $List
                int[] restIdxs = binding.restArgs();
                boolean[] isSpreads = binding.restArgIsSpread();
                int restList = ctx.newLocal("$restList", WasmValType.ref(ts.listType));
                c.i32Const(Math.max(4, restIdxs.length))
                        .call(linker.collections().fnListNew)
                        .localSet(restList);
                for (int k = 0; k < restIdxs.length; k++) {
                    int rIdx = restIdxs[k];
                    boolean isSpread = k < isSpreads.length && isSpreads[k];
                    c.localGet(restList);
                    c.localGet(argLocals[rIdx]);
                    fe.convertRawValue(argKinds[rIdx], PrimitiveKind.NONE, line);
                    if (isSpread) {
                        c.i32Const(line).call(linker.collections().fnListAddAll).drop();
                    } else {
                        c.call(linker.collections().fnListAdd).drop();
                    }
                }
                c.localGet(restList);
            } else {
                int srcIdx = p < binding.paramToArg().length ? binding.paramToArg()[p] : -1;
                if (srcIdx >= 0) {
                    c.localGet(argLocals[srcIdx]);
                    fe.convertRawValue(
                            argKinds[srcIdx], boxedOpt ? PrimitiveKind.NONE : hp.kind(), line);
                } else if (boxedOpt) {
                    c.globalGet(rt.gMissing);
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

    // ---------------------------------------------------------------- DynamicCall & Direct
    // Built-ins

    public void emitDynamicCall(HirExpr.DynamicCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        // 0. Static module import("path") / importe("path") (BIP025-I2)
        if (call.callee() instanceof HirExpr.NameRead nr
                && ("import".equals(nr.name()) || "importe".equals(nr.name()))
                && !isShadowedLocalOrGlobal(nr.name())
                && call.args().size() == 1
                && call.args().get(0).value() instanceof HirExpr.Const cst
                && cst.value() instanceof String importPath) {
            String targetKey = ctx.modInfo.input.staticImports().get(importPath);
            WasmProgramLinker.ModuleLinkInfo targetMod =
                    targetKey != null ? linker.moduleByKey(targetKey) : null;
            if (targetMod != null) {
                ctx.code
                        .call(targetMod.initFuncIdx)
                        .drop()
                        .globalGet(targetMod.modValGlobalIdx)
                        .refAsNonNull();
                ctx.stackKind = PrimitiveKind.NONE;
                return;
            }
        }
        // 0b. Direct Wasm host import call: wasm.<mod>.<name>(...) (BIP025-I4, BIP025-D9)
        if (call.callee() instanceof HirExpr.DynamicSelect fnSel
                && !fnSel.safe()
                && fnSel.receiver() instanceof HirExpr.DynamicSelect modSel
                && !modSel.safe()
                && modSel.receiver() instanceof HirExpr.NameRead wasmNr
                && "wasm".equals(wasmNr.name())
                && !isShadowedLocalOrGlobal("wasm")) {
            WasmProgramLinker.WasmImportBinding binding =
                    linker.wasmImport(modSel.memberName(), fnSel.memberName());
            if (binding != null) {
                emitWasmHostImportCall(binding, call.args(), call.meta().line());
                return;
            }
        }
        // 1. Direct built-in recognition (print, println, assert, conversions, same, classof,
        // exception)
        if (call.callee() instanceof HirExpr.NameRead nr
                && WasmFuncEmitter.isBuiltinName(nr.name())
                && !isShadowedLocalOrGlobal(nr.name())) {
            if (emitDirectBuiltinCall(nr.name(), call)) {
                return;
            }
        }
        if (call.callee() instanceof HirExpr.CoreExceptionRef) {
            emitExceptionConstructorCall(call.args(), call.meta().line());
            return;
        }
        // 1a. Core collection constructors via TypeApply(CoreCollectionRef): e.g. (set of
        // int)(capacity: 4)
        {
            HirExpr unwrapped = call.callee();
            while (unwrapped instanceof HirExpr.TypeApply ta) {
                unwrapped = ta.inner();
            }
            if (unwrapped instanceof HirExpr.CoreCollectionRef ccr) {
                fe.core()
                        .emitCoreCollectionCall(
                                new HirExpr.CoreCollectionCall(
                                        ccr.collectionKind(), call.args(), call.meta()));
                return;
            }
        }
        // 1a2. Core collection static factories: list.of(...), (list of T).of(...),
        // set.copyOf(...), etc.
        if (call.callee() instanceof HirExpr.DynamicSelect ds
                && !ds.safe()
                && ("of".equals(ds.memberName()) || "copyOf".equals(ds.memberName()))) {
            HirExpr recv = ds.receiver();
            while (recv instanceof HirExpr.TypeApply ta) {
                recv = ta.inner();
            }
            if (recv instanceof HirExpr.CoreCollectionRef ccr) {
                fe.core()
                        .emitCollectionFactoryCall(
                                ccr.collectionKind(),
                                ds.memberName(),
                                call.args(),
                                call.meta().line());
                return;
            }
        }
        // 1a3. Dynamic array protocol calls (e.g. a.set(0, value: 9), a.get(index: 0), a.size())
        if (call.callee() instanceof HirExpr.DynamicSelect ds
                && !ds.safe()
                && ("set".equals(ds.memberName())
                        || "get".equals(ds.memberName())
                        || "size".equals(ds.memberName()))) {
            WasmCodeBuilder c = ctx.code;
            int line = call.meta().line();
            fe.emitBoxed(ds.receiver());
            int r = ctx.newLocal("$dynArrR", ts.anyRef());
            c.localTee(r).refTest(ts.arrayBaseType).if_(ts.anyRef());
            ctx.pushBlock("dyn_arr_call");
            if ("size".equals(ds.memberName()) && call.args().isEmpty()) {
                c.localGet(r)
                        .refCast(ts.arrayBaseType)
                        .call(linker.arrays().fnArraySize)
                        .call(rt.fnBoxInt);
            } else if ("get".equals(ds.memberName()) && call.args().size() == 1) {
                c.localGet(r).refCast(ts.arrayBaseType);
                fe.emitAs(call.args().get(0).value(), PrimitiveKind.INT);
                c.i32Const(line).call(linker.arrays().fnArrayGetBoxed);
            } else if ("set".equals(ds.memberName()) && call.args().size() == 2) {
                int idxL = ctx.newLocal("$daIdx", WasmValType.I32);
                int valL = ctx.newLocal("$daVal", ts.anyRef());
                HirCallArg a0 = call.args().get(0);
                HirCallArg a1 = call.args().get(1);
                if ("value".equals(a0.name())) {
                    fe.emitBoxed(a0.value());
                    c.localSet(valL);
                    fe.emitAs(a1.value(), PrimitiveKind.INT);
                    c.localSet(idxL);
                } else {
                    fe.emitAs(a0.value(), PrimitiveKind.INT);
                    c.localSet(idxL);
                    fe.emitBoxed(a1.value());
                    c.localSet(valL);
                }
                c.localGet(r)
                        .refCast(ts.arrayBaseType)
                        .localGet(idxL)
                        .localGet(valL)
                        .i32Const(line)
                        .call(linker.arrays().fnArraySetBoxed);
            } else {
                rt.emitStringLit(c, "No such member : " + ds.memberName());
                c.i32Const(line).call(rt.fnThrowMsg).unreachable();
            }
            c.else_();
            // Fallback for non-array receiver (Babble object/collection)
            emitDynamicMethodOnReceiverLocal(r, ds.memberName(), call.args(), call.binding(), line);
            ctx.popBlock();
            c.end();
            ctx.stackKind = PrimitiveKind.NONE;
            if (call.meta().kind().isPrimitive()) {
                fe.convertStack(PrimitiveKind.NONE, call.meta().kind(), line);
            }
            return;
        }
        // 1b. Overloaded portable String methods lowered as DynamicCall(DynamicSelect(recv, name),
        // args)
        if (call.callee() instanceof HirExpr.DynamicSelect ds
                && !ds.safe()
                && "replace".equals(ds.memberName())
                && call.args().size() == 2) {
            WasmCodeBuilder c = ctx.code;
            int line = call.meta().line();
            fe.emitBoxed(ds.receiver());
            int r = ctx.newLocal("$strRepR", ts.anyRef());
            c.localTee(r).refTest(ts.stringType).if_(ts.anyRef());
            ctx.pushBlock("str_rep");
            fe.emitBoxed(call.args().get(0).value());
            int a0 = ctx.newLocal("$strRepA0", ts.anyRef());
            c.localSet(a0);
            fe.emitBoxed(call.args().get(1).value());
            int a1 = ctx.newLocal("$strRepA1", ts.anyRef());
            c.localSet(a1);
            c.localGet(a0)
                    .refTest(ts.boxCharType)
                    .localGet(a1)
                    .refTest(ts.boxCharType)
                    .op(WasmInstr.SimpleOp.I32_AND)
                    .if_(ts.anyRef())
                    .localGet(r)
                    .refCast(ts.stringType)
                    .localGet(a0)
                    .refCast(ts.boxCharType)
                    .structGet(ts.boxCharType, 0)
                    .localGet(a1)
                    .refCast(ts.boxCharType)
                    .structGet(ts.boxCharType, 0)
                    .call(linker.strings().fnReplaceChar)
                    .else_()
                    .localGet(a0)
                    .refTest(ts.stringType)
                    .localGet(a1)
                    .refTest(ts.stringType)
                    .op(WasmInstr.SimpleOp.I32_AND)
                    .if_(ts.anyRef())
                    .localGet(r)
                    .refCast(ts.stringType)
                    .localGet(a0)
                    .refCast(ts.stringType)
                    .localGet(a1)
                    .refCast(ts.stringType)
                    .call(linker.strings().fnReplaceStr)
                    .else_();
            rt.emitStringLit(
                    c, "Type error : String.replace expects (char, char) or (string, string)");
            c.i32Const(line).call(rt.fnThrowMsg).unreachable().end().end().else_();
            emitDynamicMethodOnReceiverLocal(r, "replace", call.args(), call.binding(), line);
            ctx.popBlock();
            c.end();
            ctx.stackKind = PrimitiveKind.NONE;
            if (call.meta().kind().isPrimitive()) {
                fe.convertStack(PrimitiveKind.NONE, call.meta().kind(), line);
            }
            return;
        }
        // 1b-bis. Overloaded collection .remove(arg) lowered as DynamicCall (e.g.
        // list.remove(byte(0)) vs list.remove(obj))
        if (call.callee() instanceof HirExpr.DynamicSelect ds
                && !ds.safe()
                && "remove".equals(ds.memberName())
                && call.args().size() == 1
                && call.args().get(0).name() == null) {
            WasmCodeBuilder c = ctx.code;
            int line = call.meta().line();
            WasmCollectionOpsGen cols = linker.collections();
            fe.emitBoxed(ds.receiver());
            int r = ctx.newLocal("$remR", ts.anyRef());
            c.localTee(r).refTest(ts.listType).if_(ts.anyRef());
            ctx.pushBlock("rem_list");
            fe.emitBoxed(call.args().get(0).value());
            int a0 = ctx.newLocal("$remA0", ts.anyRef());
            c.localTee(a0)
                    .refTest(ts.boxIntType)
                    .if_(ts.anyRef())
                    .localGet(r)
                    .refCast(ts.listType)
                    .localGet(a0)
                    .refCast(ts.boxIntType)
                    .structGet(ts.boxIntType, 0)
                    .i32Const(line)
                    .call(cols.fnListRemoveAt)
                    .else_()
                    .localGet(a0)
                    .refTest(ts.boxByteType)
                    .if_(ts.anyRef())
                    .localGet(r)
                    .refCast(ts.listType)
                    .localGet(a0)
                    .refCast(ts.boxByteType)
                    .structGet(ts.boxByteType, 0)
                    .i32Const(line)
                    .call(cols.fnListRemoveAt)
                    .else_()
                    .localGet(a0)
                    .refTest(ts.boxShortType)
                    .if_(ts.anyRef())
                    .localGet(r)
                    .refCast(ts.listType)
                    .localGet(a0)
                    .refCast(ts.boxShortType)
                    .structGet(ts.boxShortType, 0)
                    .i32Const(line)
                    .call(cols.fnListRemoveAt)
                    .else_()
                    .localGet(r)
                    .refCast(ts.listType)
                    .localGet(a0)
                    .call(cols.fnListRemoveVal)
                    .call(rt.fnBoxBool)
                    .end()
                    .end()
                    .end()
                    .else_()
                    .localGet(r)
                    .refTest(ts.setType)
                    .if_(ts.anyRef());
            ctx.pushBlock("rem_set");
            fe.emitBoxed(call.args().get(0).value());
            int s0 = ctx.newLocal("$remS0", ts.anyRef());
            c.localSet(s0)
                    .localGet(r)
                    .refCast(ts.setType)
                    .localGet(s0)
                    .call(cols.fnSetRemove)
                    .call(rt.fnBoxBool)
                    .else_()
                    .localGet(r)
                    .refTest(ts.mapType)
                    .if_(ts.anyRef());
            ctx.pushBlock("rem_map");
            fe.emitBoxed(call.args().get(0).value());
            int m0 = ctx.newLocal("$remM0", ts.anyRef());
            c.localSet(m0)
                    .localGet(r)
                    .refCast(ts.mapType)
                    .localGet(m0)
                    .call(cols.fnMapRemove)
                    .else_();
            emitDynamicMethodOnReceiverLocal(r, "remove", call.args(), call.binding(), line);
            ctx.popBlock();
            c.end();
            ctx.popBlock();
            c.end();
            ctx.popBlock();
            c.end();
            ctx.stackKind = PrimitiveKind.NONE;
            if (call.meta().kind().isPrimitive()) {
                fe.convertStack(PrimitiveKind.NONE, call.meta().kind(), line);
            }
            return;
        }
        // 1c. Direct static dispatch for immutable cross-module functions (`mod.fn(...)`),
        //     immutable class static methods (`Cls.create(...)`), and imported top-level functions.
        WasmProgramLinker.FuncInfo staticTarget = resolveDirectStaticCallee(call);
        if (staticTarget != null && canDirectStaticDispatch(staticTarget, call)) {
            emitBoundDirectArgs(
                    staticTarget, -1, null, call.args(), call.binding(), call.meta().line());
            ctx.code.call(staticTarget.directFuncIdx);
            ctx.stackKind = staticTarget.hir.returnKind();
            return;
        }
        // 1d. Direct typed constructor call for immutable non-capturing classes (`RangeCursor(a,
        // b)`, `Sphere(...)`).
        WasmObjectSystem.ClassInfo directCtorCls =
                fe.members().resolveDirectImmutableClass(call.callee());
        if (directCtorCls != null && canDirectCtorDispatch(directCtorCls, call)) {
            fe.members()
                    .emitBoundParams(
                            directCtorCls.hir.params(),
                            directCtorCls.ctorHasOptMask,
                            false,
                            null,
                            call.args(),
                            call.binding(),
                            call.meta().line());
            ctx.code.call(directCtorCls.directCtorIdx);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        // 2. General indirect call via $FnBase.genericCall (honoring static ArgumentBinding if
        // present!)
        emitGenericCallWithCallArgs(
                call.callee(), call.args(), call.binding(), call.meta().kind(), call.meta().line());
    }

    private WasmProgramLinker.FuncInfo resolveDirectStaticCallee(HirExpr.DynamicCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        int curStmt = ctx.currentTopLevelStmtIdx;
        HirExpr callee = call.callee();
        while (callee instanceof HirExpr.TypeApply ta) {
            callee = ta.inner();
        }
        if (callee instanceof HirExpr.ModuleExportGet meg
                && meg.receiver() instanceof HirExpr.NameRead modNr) {
            if (isShadowedInNsScopes(modNr.name())
                    || (!modNr.definitelyDefined()
                            && !isDefinedBeforeStmt(ctx.modInfo, modNr.name(), curStmt))) {
                return null;
            }
            WasmProgramLinker.ModuleLinkInfo targetMod =
                    ctx.modInfo.immutableModuleImports.get(modNr.name());
            return targetMod != null
                    ? targetMod.immutableTopLevelFuncs.get(meg.target().name())
                    : null;
        }
        if (callee instanceof HirExpr.DynamicSelect ds && !ds.safe()) {
            WasmObjectSystem.ClassInfo ci = fe.members().resolveDirectImmutableClass(ds.receiver());
            return ci != null ? ci.immutableStaticMethods.get(ds.memberName()) : null;
        }
        if (callee instanceof HirExpr.NameRead fnNr) {
            if (isShadowedInNsScopes(fnNr.name())
                    || (!fnNr.definitelyDefined()
                            && !isDefinedBeforeStmt(ctx.modInfo, fnNr.name(), curStmt))) {
                return null;
            }
            return ctx.modInfo.immutableTopLevelFuncs.get(fnNr.name());
        }
        return null;
    }

    private static boolean isDefinedBeforeStmt(
            WasmProgramLinker.ModuleLinkInfo modInfo, String name, int curStmt) {
        if (curStmt < 0) {
            return false;
        }
        Integer defIdx = modInfo.topLevelDefIndex.get(name);
        return defIdx != null && defIdx < curStmt;
    }

    private boolean isShadowedInNsScopes(String name) {
        return fe.isShadowedInNsScope(name);
    }

    private static boolean canDirectCtorDispatch(
            WasmObjectSystem.ClassInfo ci, HirExpr.DynamicCall call) {
        if (ci.hir.isObject() || ci.hasCaptures || ci.hasCapturingIfaces) {
            return false;
        }
        List<HirParam> params = ci.hir.params();
        int optCount = 0;
        for (HirParam p : params) {
            if (p.defaultValue() != null) optCount++;
        }
        LoweringTargets.ArgumentBinding binding = call.binding();
        if (binding != null) {
            if (binding.hasRest() || binding.paramCount() != params.size()) {
                return false;
            }
            if (optCount > 0 && !ci.ctorHasOptMask) {
                for (int p = 0; p < params.size(); p++) {
                    if (p >= binding.paramToArg().length || binding.paramToArg()[p] < 0) {
                        return false;
                    }
                }
            }
            return true;
        }
        List<HirCallArg> args = call.args();
        if (args.size() > params.size()) {
            return false;
        }
        if (optCount > 0 && !ci.ctorHasOptMask && args.size() < params.size()) {
            return false;
        }
        for (HirCallArg a : args) {
            if (a.name() != null || a.value() instanceof HirExpr.SpreadAdapt) {
                return false;
            }
        }
        for (int p = args.size(); p < params.size(); p++) {
            if (params.get(p).defaultValue() == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean canDirectStaticDispatch(
            WasmProgramLinker.FuncInfo fi, HirExpr.DynamicCall call) {
        if (fi.hasEnvParam || fi.hasThisParam) {
            return false;
        }
        HirFunction hf = fi.hir;
        LoweringTargets.ArgumentBinding binding = call.binding();
        if (binding != null) {
            return binding.paramCount() == hf.params().size();
        }
        if (hf.restIndex() >= 0) {
            return false;
        }
        List<HirCallArg> args = call.args();
        if (args.size() > hf.params().size()) {
            return false;
        }
        for (HirCallArg a : args) {
            if (a.name() != null || a.value() instanceof HirExpr.SpreadAdapt) {
                return false;
            }
        }
        for (int p = args.size(); p < hf.params().size(); p++) {
            if (hf.params().get(p).defaultValue() == null) {
                return false;
            }
        }
        return true;
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
                // Separate positional values from optional named `to:` arg, evaluating strictly
                // left-to-right!
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
                String exprText =
                        call.callSite() != null && call.callSite().argTexts().length > 0
                                ? call.callSite().argTexts()[0]
                                : "assert";
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
            case "byte" -> {
                return emitDirectConv(args, call, ops.fnConvByte, PrimitiveKind.BYTE);
            }
            case "short" -> {
                return emitDirectConv(args, call, ops.fnConvShort, PrimitiveKind.SHORT);
            }
            case "int" -> {
                return emitDirectConv(args, call, ops.fnConvInt, PrimitiveKind.INT);
            }
            case "long" -> {
                return emitDirectConv(args, call, ops.fnConvLong, PrimitiveKind.LONG);
            }
            case "float" -> {
                return emitDirectConv(args, call, ops.fnConvFloat, PrimitiveKind.FLOAT);
            }
            case "double" -> {
                return emitDirectConv(args, call, ops.fnConvDouble, PrimitiveKind.DOUBLE);
            }
            case "bool" -> {
                return emitDirectConv(args, call, ops.fnConvBool, PrimitiveKind.BOOL);
            }
            case "char" -> {
                return emitDirectConv(args, call, ops.fnConvChar, PrimitiveKind.CHAR);
            }
            case "exception" -> {
                emitExceptionConstructorCall(args, line);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean emitDirectConv(
            List<HirCallArg> args, HirExpr.DynamicCall call, int convFn, PrimitiveKind outKind) {
        if (args.size() != 1) {
            return false;
        }
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        String argText =
                call.callSite() != null && call.callSite().argTexts().length > 0
                        ? call.callSite().argTexts()[0]
                        : "";
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
                c.localTee(tmp)
                        .refIsNull()
                        .if_(WasmValType.refNull(ts.stringType))
                        .refNull(ts.stringType)
                        .else_()
                        .localGet(tmp)
                        .call(rt.fnFormat)
                        .end()
                        .localSet(msgLocal);
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

    private void emitGenericCallExpr(
            HirExpr callee, List<HirExpr> args, PrimitiveKind expectedKind, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(callee);
        int fnLocal = ctx.newLocal("$callee", WasmValType.refNull(ts.fnBaseType));
        int tmp = ctx.newLocal("$calleeAny", ts.anyRef());
        c.localTee(tmp).refTest(ts.fnBaseType).op(WasmInstr.SimpleOp.I32_EQZ).if_();
        rt.emitStringLit(c, "Not callable");
        c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(tmp).refCast(ts.fnBaseType).localSet(fnLocal);
        for (HirExpr a : args) {
            fe.emitBoxed(a);
        }
        int arrLocal = ctx.newLocal("$callArgs", WasmValType.ref(ts.anyArrType));
        c.arrayNewFixed(ts.anyArrType, args.size()).localSet(arrLocal);
        c.localGet(fnLocal)
                .refAsNonNull()
                .localGet(arrLocal)
                .refNull(ts.anyArrType)
                .localGet(fnLocal)
                .refAsNonNull()
                .structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType);
        ctx.stackKind = PrimitiveKind.NONE;
        if (expectedKind.isPrimitive()) {
            fe.convertStack(PrimitiveKind.NONE, expectedKind, line);
        }
    }

    public void emitDynamicMethodOnReceiverLocal(
            int recvLocal,
            String memberName,
            List<HirCallArg> args,
            LoweringTargets.ArgumentBinding binding,
            int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.members().emitDynamicMemberRead(recvLocal, memberName, line);
        int fnLocal = ctx.newLocal("$dmrFn", WasmValType.refNull(ts.fnBaseType));
        int tmp = ctx.newLocal("$dmrAny", ts.anyRef());
        c.localTee(tmp).refTest(ts.fnBaseType).op(WasmInstr.SimpleOp.I32_EQZ).if_();
        rt.emitStringLit(c, "Not callable");
        c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(tmp).refCast(ts.fnBaseType).localSet(fnLocal);
        emitGenericInvokeOnFnLocal(fnLocal, args, binding, line);
    }

    private void emitGenericCallWithCallArgs(
            HirExpr callee,
            List<HirCallArg> args,
            LoweringTargets.ArgumentBinding binding,
            PrimitiveKind expectedKind,
            int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(callee);
        int fnLocal = ctx.newLocal("$callee", WasmValType.refNull(ts.fnBaseType));
        int tmp = ctx.newLocal("$calleeAny", ts.anyRef());
        c.localTee(tmp).refTest(ts.fnBaseType).op(WasmInstr.SimpleOp.I32_EQZ).if_();
        rt.emitStringLit(c, "Not callable");
        c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(tmp).refCast(ts.fnBaseType).localSet(fnLocal);

        emitGenericInvokeOnFnLocal(fnLocal, args, binding, line);
        ctx.stackKind = PrimitiveKind.NONE;
        if (expectedKind.isPrimitive()) {
            fe.convertStack(PrimitiveKind.NONE, expectedKind, line);
        }
    }

    public void emitGenericInvokeOnFnLocal(
            int fnLocal, List<HirCallArg> args, LoweringTargets.ArgumentBinding binding, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        if (binding != null) {
            // Static function type already resolved parameter names/reordering/defaults (BIP021
            // §2.2 (d)):
            // evaluate args left-to-right, pack into positional slots with $G_MISSING for omitted
            // params, pass names=null!
            int srcCount = args.size();
            int[] argLocals = new int[srcCount];
            for (int a = 0; a < srcCount; a++) {
                fe.emitBoxed(args.get(a).value());
                argLocals[a] = ctx.newLocal("$gcArg" + a, ts.anyRef());
                c.localSet(argLocals[a]);
            }
            int pCount = binding.paramToArg().length;
            int arrLocal;
            int namesLocal = -1;
            if (binding.hasRest()) {
                boolean hasKwOnly = false;
                for (int p = binding.restParam() + 1; p < pCount; p++) {
                    int srcIdx = binding.paramToArg()[p];
                    if (srcIdx >= 0 && srcIdx < srcCount && args.get(srcIdx).name() != null) {
                        hasKwOnly = true;
                        break;
                    }
                }
                int argList = ctx.newLocal("$gcArgL", WasmValType.ref(ts.listType));
                c.i32Const(Math.max(4, pCount + srcCount))
                        .call(linker.collections().fnListNew)
                        .localSet(argList);
                for (int p = 0; p <= binding.restParam(); p++) {
                    if (p == binding.restParam()) {
                        int[] rArgs = binding.restArgs();
                        boolean[] rSpreads = binding.restArgIsSpread();
                        for (int ri = 0; ri < rArgs.length; ri++) {
                            c.localGet(argList).localGet(argLocals[rArgs[ri]]);
                            if (ri < rSpreads.length && rSpreads[ri]) {
                                c.i32Const(line).call(linker.collections().fnListAddAll).drop();
                            } else {
                                c.call(linker.collections().fnListAdd).drop();
                            }
                        }
                    } else {
                        int srcIdx = binding.paramToArg()[p];
                        c.localGet(argList);
                        if (srcIdx >= 0 && srcIdx < srcCount) {
                            fe.emitSpreadAdaptValue(
                                    argLocals[srcIdx], binding.adaptForArg(srcIdx), line);
                        } else {
                            c.globalGet(rt.gMissing);
                        }
                        c.call(linker.collections().fnListAdd).drop();
                    }
                }
                int posLen = ctx.newLocal("$gcPosLen", WasmValType.I32);
                c.localGet(argList).structGet(ts.listType, 1).localSet(posLen);
                List<String> kwNames = new java.util.ArrayList<>();
                for (int p = binding.restParam() + 1; p < pCount; p++) {
                    int srcIdx = binding.paramToArg()[p];
                    if (srcIdx >= 0 && srcIdx < srcCount) {
                        c.localGet(argList);
                        fe.emitSpreadAdaptValue(
                                argLocals[srcIdx], binding.adaptForArg(srcIdx), line);
                        c.call(linker.collections().fnListAdd).drop();
                        kwNames.add(args.get(srcIdx).name());
                    }
                }
                int alLen = ctx.newLocal("$gcAlLen", WasmValType.I32);
                arrLocal = ctx.newLocal("$callArgs", WasmValType.ref(ts.anyArrType));
                int alI = ctx.newLocal("$gcAlI", WasmValType.I32);
                c.localGet(argList)
                        .structGet(ts.listType, 1)
                        .localTee(alLen)
                        .arrayNewDefault(ts.anyArrType)
                        .localSet(arrLocal);
                c.i32Const(0).localSet(alI);
                int bD = ctx.pushBlock("gc_al_brk");
                c.block();
                int lD = ctx.pushBlock("gc_al_loop");
                c.loop();
                c.localGet(alI)
                        .localGet(alLen)
                        .op(WasmInstr.SimpleOp.I32_GE_S)
                        .brIf(ctx.relDepth(bD));
                c.localGet(arrLocal)
                        .localGet(alI)
                        .localGet(argList)
                        .structGet(ts.listType, 0)
                        .localGet(alI)
                        .arrayGet(ts.anyArrType)
                        .arraySet(ts.anyArrType);
                c.localGet(alI)
                        .i32Const(1)
                        .op(WasmInstr.SimpleOp.I32_ADD)
                        .localSet(alI)
                        .br(ctx.relDepth(lD));
                ctx.popBlock();
                c.end();
                ctx.popBlock();
                c.end();
                if (hasKwOnly) {
                    namesLocal = ctx.newLocal("$callNames", WasmValType.refNull(ts.anyArrType));
                    c.localGet(alLen).arrayNewDefault(ts.anyArrType).localSet(namesLocal);
                    for (int ki = 0; ki < kwNames.size(); ki++) {
                        String kn = kwNames.get(ki);
                        if (kn != null) {
                            c.localGet(namesLocal)
                                    .refAsNonNull()
                                    .localGet(posLen)
                                    .i32Const(ki)
                                    .op(WasmInstr.SimpleOp.I32_ADD);
                            rt.emitStringLit(c, kn);
                            c.arraySet(ts.anyArrType);
                        }
                    }
                }
            } else {
                for (int p = 0; p < pCount; p++) {
                    int srcIdx = binding.paramToArg()[p];
                    if (srcIdx >= 0 && srcIdx < srcCount) {
                        fe.emitSpreadAdaptValue(
                                argLocals[srcIdx], binding.adaptForArg(srcIdx), line);
                    } else {
                        c.globalGet(rt.gMissing);
                    }
                }
                arrLocal = ctx.newLocal("$callArgs", WasmValType.ref(ts.anyArrType));
                c.arrayNewFixed(ts.anyArrType, pCount).localSet(arrLocal);
            }
            c.localGet(fnLocal).refAsNonNull().localGet(arrLocal);
            if (namesLocal >= 0) {
                c.localGet(namesLocal);
            } else {
                c.refNull(ts.anyArrType);
            }
            c.localGet(fnLocal)
                    .refAsNonNull()
                    .structGet(ts.fnBaseType, 0)
                    .callRef(ts.genericCallSigType);
            return;
        }

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

        c.localGet(fnLocal).refAsNonNull().localGet(arrLocal);
        if (hasNames) {
            c.localGet(namesLocal);
        } else {
            c.refNull(ts.anyArrType);
        }
        c.localGet(fnLocal)
                .refAsNonNull()
                .structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType);
    }

    // ---------------------------------------------------------------- Generic Call Trampoline
    // ($generic_F)

    /**
     * Emits {@code $generic_F(self: (ref $FnBase), args: (ref $AnyArr), names: (ref null $AnyArr))
     * -> any?}. Binds positional + named + optional arguments dynamically, unboxes primitives,
     * invokes {@code $fn_F}, and boxes the return value if primitive.
     */
    public void emitGenericTrampoline(
            WasmProgramLinker.ModuleLinkInfo modInfo, WasmProgramLinker.FuncInfo fi) {
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

        int restIdx = hf.restIndex();
        int restListLocal = -1;
        if (restIdx >= 0) {
            restListLocal = fn.addLocal("restL", WasmValType.ref(ts.listType), 3);
            c.i32Const(4).call(linker.collections().fnListNew).localSet(restListLocal);
            c.localGet(restListLocal).localSet(pLocals[restIdx]);
        }

        // Bind args: if names == null, pure positional; else match named args by param name
        int idx = fn.addLocal("idx", WasmValType.I32, 3);
        int nameAny = fn.addLocal("nameAny", ts.anyRef(), 3);
        int valAny = fn.addLocal("valAny", ts.anyRef(), 3);

        c.localGet(2).refIsNull().if_();
        // Pure positional binding
        if (restIdx >= 0) {
            for (int p = 0; p < restIdx; p++) {
                c.i32Const(p)
                        .localGet(argLen)
                        .op(WasmInstr.SimpleOp.I32_LT_S)
                        .if_()
                        .localGet(1)
                        .i32Const(p)
                        .arrayGet(ts.anyArrType)
                        .localSet(pLocals[p])
                        .end();
            }
            c.i32Const(restIdx).localSet(idx);
            c.block()
                    .loop()
                    .localGet(idx)
                    .localGet(argLen)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .brIf(1)
                    .localGet(1)
                    .localGet(idx)
                    .arrayGet(ts.anyArrType)
                    .localTee(valAny)
                    .globalGet(rt.gMissing)
                    .refEq()
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .localGet(restListLocal)
                    .localGet(valAny)
                    .call(linker.collections().fnListAdd)
                    .drop()
                    .end()
                    .localGet(idx)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(idx)
                    .br(0)
                    .end()
                    .end();
        } else {
            for (int p = 0; p < paramCount; p++) {
                c.i32Const(p)
                        .localGet(argLen)
                        .op(WasmInstr.SimpleOp.I32_LT_S)
                        .if_()
                        .localGet(1)
                        .i32Const(p)
                        .arrayGet(ts.anyArrType)
                        .localSet(pLocals[p])
                        .end();
            }
        }
        c.else_();
        // Mixed positional + named binding loop
        c.i32Const(0).localSet(idx);
        c.loop()
                .localGet(idx)
                .localGet(argLen)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(1)
                .localGet(idx)
                .arrayGet(ts.anyArrType)
                .localSet(valAny)
                .localGet(2)
                .refAsNonNull()
                .localGet(idx)
                .arrayGet(ts.anyArrType)
                .localTee(nameAny)
                .refIsNull()
                .if_();
        // Positional arg at idx
        if (restIdx >= 0) {
            for (int p = 0; p < restIdx; p++) {
                c.localGet(idx)
                        .i32Const(p)
                        .op(WasmInstr.SimpleOp.I32_EQ)
                        .if_()
                        .localGet(valAny)
                        .localSet(pLocals[p])
                        .end();
            }
            c.localGet(idx)
                    .i32Const(restIdx)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .if_()
                    .localGet(valAny)
                    .globalGet(rt.gMissing)
                    .refEq()
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .localGet(restListLocal)
                    .localGet(valAny)
                    .call(linker.collections().fnListAdd)
                    .drop()
                    .end()
                    .end();
        } else {
            for (int p = 0; p < paramCount; p++) {
                c.localGet(idx)
                        .i32Const(p)
                        .op(WasmInstr.SimpleOp.I32_EQ)
                        .if_()
                        .localGet(valAny)
                        .localSet(pLocals[p])
                        .end();
            }
        }
        c.else_();
        // Named arg: compare nameAny against each parameter name
        for (int p = 0; p < paramCount; p++) {
            c.localGet(nameAny).refCast(ts.stringType);
            rt.emitStringLit(c, hf.params().get(p).name());
            c.call(rt.fnStrEq).if_().localGet(valAny).localSet(pLocals[p]).end();
        }
        c.end()
                .localGet(idx)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(idx)
                .br(1)
                .end()
                .end();
        c.end();

        // Push $env (if capturing)
        if (fi.hasEnvParam) {
            c.localGet(0).refCast(fi.closureStructType);
        }
        // Push each parameter (unboxed, or raw boxed/G_MISSING when hasBoxedMissingOptParams)
        int optBit = 0;
        for (int p = 0; p < paramCount; p++) {
            HirParam hp = hf.params().get(p);
            if (fi.hasBoxedMissingOptParams && hp.defaultValue() != null) {
                c.localGet(pLocals[p]);
            } else if (hp.defaultValue() != null) {
                c.localGet(pLocals[p])
                        .globalGet(rt.gMissing)
                        .refEq()
                        .if_(ts.valTypeFor(hp.kind()))
                        .localGet(optMask)
                        .i32Const(1 << optBit)
                        .op(WasmInstr.SimpleOp.I32_OR)
                        .localSet(optMask);
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

    /**
     * Emits a bound-method generic trampoline {@code (self: (ref $FnBase), args: (ref $AnyArr),
     * names: (ref null $AnyArr)) -> any?} that unwraps {@code $BoundMethod.receiver} as {@code
     * $this : (ref $Struct_C)} and invokes {@code mfi.directFuncIdx}.
     */
    public void emitBoundMethodTrampoline(
            WasmModule.FuncDef fn, WasmObjectSystem.ClassInfo ci, WasmProgramLinker.FuncInfo mfi) {
        WasmCodeBuilder c = fn.code();
        HirFunction hf = mfi.hir;
        int paramCount = hf.params().size();

        int argLen = fn.addLocal("argLen", WasmValType.I32, 3);
        int optMask = fn.addLocal("optMask", WasmValType.I32, 3);
        c.localGet(1).arrayLen().localSet(argLen);

        int[] pLocals = new int[paramCount];
        for (int p = 0; p < paramCount; p++) {
            pLocals[p] = fn.addLocal("p" + p, ts.anyRef(), 3);
            c.globalGet(rt.gMissing).localSet(pLocals[p]);
        }

        int restIdx = hf.restIndex();
        int restListLocal = -1;
        if (restIdx >= 0) {
            restListLocal = fn.addLocal("restL", WasmValType.ref(ts.listType), 3);
            c.i32Const(4).call(linker.collections().fnListNew).localSet(restListLocal);
            c.localGet(restListLocal).localSet(pLocals[restIdx]);
        }

        int idx = fn.addLocal("idx", WasmValType.I32, 3);
        int nameAny = fn.addLocal("nameAny", ts.anyRef(), 3);
        int valAny = fn.addLocal("valAny", ts.anyRef(), 3);

        c.localGet(2).refIsNull().if_();
        if (restIdx >= 0) {
            for (int p = 0; p < restIdx; p++) {
                c.i32Const(p)
                        .localGet(argLen)
                        .op(WasmInstr.SimpleOp.I32_LT_S)
                        .if_()
                        .localGet(1)
                        .i32Const(p)
                        .arrayGet(ts.anyArrType)
                        .localSet(pLocals[p])
                        .end();
            }
            c.i32Const(restIdx).localSet(idx);
            c.block()
                    .loop()
                    .localGet(idx)
                    .localGet(argLen)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .brIf(1)
                    .localGet(1)
                    .localGet(idx)
                    .arrayGet(ts.anyArrType)
                    .localTee(valAny)
                    .globalGet(rt.gMissing)
                    .refEq()
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .localGet(restListLocal)
                    .localGet(valAny)
                    .call(linker.collections().fnListAdd)
                    .drop()
                    .end()
                    .localGet(idx)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(idx)
                    .br(0)
                    .end()
                    .end();
        } else {
            for (int p = 0; p < paramCount; p++) {
                c.i32Const(p)
                        .localGet(argLen)
                        .op(WasmInstr.SimpleOp.I32_LT_S)
                        .if_()
                        .localGet(1)
                        .i32Const(p)
                        .arrayGet(ts.anyArrType)
                        .localSet(pLocals[p])
                        .end();
            }
        }
        c.else_();
        c.i32Const(0).localSet(idx);
        c.loop()
                .localGet(idx)
                .localGet(argLen)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(1)
                .localGet(idx)
                .arrayGet(ts.anyArrType)
                .localSet(valAny)
                .localGet(2)
                .refAsNonNull()
                .localGet(idx)
                .arrayGet(ts.anyArrType)
                .localTee(nameAny)
                .refIsNull()
                .if_();
        if (restIdx >= 0) {
            for (int p = 0; p < restIdx; p++) {
                c.localGet(idx)
                        .i32Const(p)
                        .op(WasmInstr.SimpleOp.I32_EQ)
                        .if_()
                        .localGet(valAny)
                        .localSet(pLocals[p])
                        .end();
            }
            c.localGet(idx)
                    .i32Const(restIdx)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .if_()
                    .localGet(valAny)
                    .globalGet(rt.gMissing)
                    .refEq()
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .localGet(restListLocal)
                    .localGet(valAny)
                    .call(linker.collections().fnListAdd)
                    .drop()
                    .end()
                    .end();
        } else {
            for (int p = 0; p < paramCount; p++) {
                c.localGet(idx)
                        .i32Const(p)
                        .op(WasmInstr.SimpleOp.I32_EQ)
                        .if_()
                        .localGet(valAny)
                        .localSet(pLocals[p])
                        .end();
            }
        }
        c.else_();
        for (int p = 0; p < paramCount; p++) {
            c.localGet(nameAny).refCast(ts.stringType);
            rt.emitStringLit(c, hf.params().get(p).name());
            c.call(rt.fnStrEq).if_().localGet(valAny).localSet(pLocals[p]).end();
        }
        c.end()
                .localGet(idx)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(idx)
                .br(1)
                .end()
                .end();
        c.end();

        // Push $this receiver extracted from $BoundMethod.receiver (field 3)
        c.localGet(0)
                .refCast(ts.boundMethodType)
                .structGet(ts.boundMethodType, 3)
                .refCast(mfi.ownerClass != null ? ci.structTypeIdx : ts.objectType);

        int optBit = 0;
        for (int p = 0; p < paramCount; p++) {
            HirParam hp = hf.params().get(p);
            if (mfi.hasBoxedMissingOptParams && hp.defaultValue() != null) {
                c.localGet(pLocals[p]);
            } else if (hp.defaultValue() != null) {
                c.localGet(pLocals[p])
                        .globalGet(rt.gMissing)
                        .refEq()
                        .if_(ts.valTypeFor(hp.kind()))
                        .localGet(optMask)
                        .i32Const(1 << optBit)
                        .op(WasmInstr.SimpleOp.I32_OR)
                        .localSet(optMask);
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
        if (mfi.hasOptMaskParam) {
            c.localGet(optMask);
        }
        c.call(mfi.directFuncIdx);
        if (hf.returnKind().isPrimitive()) {
            c.call(rt.boxFuncFor(hf.returnKind()));
        }
    }

    // ---------------------------------------------------------------- BIP021-I5 Overloads

    public void emitMakeOverloadSet(HirExpr.MakeOverloadSet m) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int metaCount = m.metas().size();

        // Pre-build static key array from flattened metas
        for (int i = 0; i < metaCount; i++) {
            String key = m.metas().get(i).signatureKey();
            rt.emitStringLit(c, key != null ? key : "");
        }
        int origKeysLocal = ctx.newLocal("$ovOrigK", WasmValType.ref(ts.anyArrType));
        c.arrayNewFixed(ts.anyArrType, metaCount).localSet(origKeysLocal);

        // Flatten operands (unwrapping any nested $OverloadSet operands) into $List buffers
        int flatAlts = ctx.newLocal("$ovFlatA", WasmValType.ref(ts.listType));
        int flatKeys = ctx.newLocal("$ovFlatK", WasmValType.ref(ts.listType));
        int metaIdx = ctx.newLocal("$ovMIdx", WasmValType.I32);
        c.i32Const(Math.max(4, metaCount)).call(linker.collections().fnListNew).localSet(flatAlts);
        c.i32Const(Math.max(4, metaCount)).call(linker.collections().fnListNew).localSet(flatKeys);
        c.i32Const(0).localSet(metaIdx);

        int opVal = ctx.newLocal("$ovOp", ts.anyRef());
        int nestedOv = ctx.newLocal("$ovNest", WasmValType.refNull(ts.overloadSetType));
        int nestedAlts = ctx.newLocal("$ovNestA", WasmValType.refNull(ts.anyArrType));
        int nestedKeys = ctx.newLocal("$ovNestK", WasmValType.refNull(ts.anyArrType));
        int j = ctx.newLocal("$ovJ", WasmValType.I32);
        int nLen = ctx.newLocal("$ovNLen", WasmValType.I32);

        for (HirExpr op : m.operands()) {
            fe.emitBoxed(op);
            c.localTee(opVal).refTest(ts.overloadSetType).if_();
            ctx.pushBlock("ov_flat_nest");
            c.localGet(opVal)
                    .refCast(ts.overloadSetType)
                    .localTee(nestedOv)
                    .structGet(ts.overloadSetType, 3)
                    .localTee(nestedAlts)
                    .arrayLen()
                    .localSet(nLen);
            c.localGet(nestedOv).structGet(ts.overloadSetType, 4).localSet(nestedKeys);
            c.i32Const(0).localSet(j);
            int brkD = ctx.pushBlock("ov_flat_brk");
            c.block();
            int loopD = ctx.pushBlock("ov_flat_loop");
            c.loop();
            c.localGet(j).localGet(nLen).op(WasmInstr.SimpleOp.I32_GE_S).brIf(ctx.relDepth(brkD));
            c.localGet(flatAlts)
                    .localGet(nestedAlts)
                    .refAsNonNull()
                    .localGet(j)
                    .arrayGet(ts.anyArrType)
                    .call(linker.collections().fnListAdd)
                    .drop();
            c.localGet(flatKeys)
                    .localGet(nestedKeys)
                    .refAsNonNull()
                    .localGet(j)
                    .arrayGet(ts.anyArrType)
                    .call(linker.collections().fnListAdd)
                    .drop();
            c.localGet(j).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(j);
            c.localGet(metaIdx).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(metaIdx);
            c.br(ctx.relDepth(loopD));
            ctx.popBlock();
            c.end();
            ctx.popBlock();
            c.end();
            c.else_();
            c.localGet(flatAlts).localGet(opVal).call(linker.collections().fnListAdd).drop();
            c.localGet(flatKeys);
            c.localGet(metaIdx)
                    .i32Const(metaCount)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_(ts.anyRef())
                    .localGet(origKeysLocal)
                    .localGet(metaIdx)
                    .arrayGet(ts.anyArrType)
                    .else_();
            rt.emitStringLit(c, "");
            c.end();
            c.call(linker.collections().fnListAdd).drop();
            c.localGet(metaIdx).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(metaIdx);
            ctx.popBlock();
            c.end();
        }

        // Copy flatAlts & flatKeys into exact-sized $AnyArr arrays
        int finalLen = ctx.newLocal("$ovFLen", WasmValType.I32);
        int altsLocal = ctx.newLocal("$ovAlts", WasmValType.ref(ts.anyArrType));
        int keysLocal = ctx.newLocal("$ovKeys", WasmValType.ref(ts.anyArrType));
        c.localGet(flatAlts)
                .structGet(ts.listType, 1)
                .localTee(finalLen)
                .arrayNewDefault(ts.anyArrType)
                .localSet(altsLocal);
        c.localGet(finalLen).arrayNewDefault(ts.anyArrType).localSet(keysLocal);
        c.i32Const(0).localSet(j);
        int cpBrk = ctx.pushBlock("ov_cp_brk");
        c.block();
        int cpLoop = ctx.pushBlock("ov_cp_loop");
        c.loop();
        c.localGet(j).localGet(finalLen).op(WasmInstr.SimpleOp.I32_GE_S).brIf(ctx.relDepth(cpBrk));
        c.localGet(altsLocal)
                .localGet(j)
                .localGet(flatAlts)
                .structGet(ts.listType, 0)
                .localGet(j)
                .arrayGet(ts.anyArrType)
                .arraySet(ts.anyArrType);
        c.localGet(keysLocal)
                .localGet(j)
                .localGet(flatKeys)
                .structGet(ts.listType, 0)
                .localGet(j)
                .arrayGet(ts.anyArrType)
                .arraySet(ts.anyArrType);
        c.localGet(j).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(j);
        c.br(ctx.relDepth(cpLoop));
        ctx.popBlock();
        c.end();
        ctx.popBlock();
        c.end();

        // Format display string matching OverloadSet.toString()
        StringBuilder disp = new StringBuilder("overload (");
        for (int i = 0; i < metaCount; i++) {
            if (i > 0) disp.append(", ");
            disp.append(m.metas().get(i).display());
        }
        disp.append(")");

        int ovCallFn = getOrCreateOverloadGenericCall();
        c.refFunc(ovCallFn);
        rt.emitStringLit(c, disp.toString());
        c.i32Const(-2).localGet(altsLocal).localGet(keysLocal).structNew(ts.overloadSetType);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private int overloadGenericCallIdx = -1;

    public int getOrCreateOverloadGenericCall() {
        if (overloadGenericCallIdx >= 0) {
            return overloadGenericCallIdx;
        }
        WasmModule.FuncDef fn =
                linker.module()
                        .addFunction(
                                "overload_generic_call",
                                ts.genericCallSigType,
                                List.of("self", "args", "names"));
        linker.module().declareFuncRef(fn.funcIndex());
        overloadGenericCallIdx = fn.funcIndex();

        // Save/restore emitter context while emitting overload_generic_call so fe.emitDescCheck can
        // be used
        WasmFuncEmitter.EmitCtx prevCtx = fe.ctx();
        WasmFuncEmitter.EmitCtx ovCtx =
                new WasmFuncEmitter.EmitCtx(
                        prevCtx != null ? prevCtx.modInfo : linker.linkedModules().get(0),
                        null,
                        fn,
                        3,
                        PrimitiveKind.NONE,
                        -1,
                        -1);
        fe.setCtx(ovCtx);
        try {
            WasmCodeBuilder c = ovCtx.code;
            int alts = ovCtx.newLocal("alts", WasmValType.ref(ts.anyArrType));
            int keys = ovCtx.newLocal("keys", WasmValType.ref(ts.anyArrType));
            int altsLen = ovCtx.newLocal("altsLen", WasmValType.I32);
            int argLen = ovCtx.newLocal("argLen", WasmValType.I32);
            int scanI = ovCtx.newLocal("scanI", WasmValType.I32);
            int matchIdx = ovCtx.newLocal("matchIdx", WasmValType.I32);
            int argVal = ovCtx.newLocal("argVal", ts.anyRef());
            int argIdx = ovCtx.newLocal("argIdx", WasmValType.I32);
            int ok = ovCtx.newLocal("ok", WasmValType.I32);
            int chosen = ovCtx.newLocal("chosen", WasmValType.refNull(ts.fnBaseType));

            c.localGet(0)
                    .refCast(ts.overloadSetType)
                    .structGet(ts.overloadSetType, 3)
                    .localTee(alts)
                    .arrayLen()
                    .localSet(altsLen);
            c.localGet(0)
                    .refCast(ts.overloadSetType)
                    .structGet(ts.overloadSetType, 4)
                    .localSet(keys);
            c.localGet(1).arrayLen().localSet(argLen);

            // Deduplicate closed-world AltMetas by signatureKey and order most-specific first
            java.util.LinkedHashMap<String, org.babblelang.runtime.OverloadSet.AltMeta> unique =
                    new java.util.LinkedHashMap<>();
            for (org.babblelang.runtime.OverloadSet.AltMeta m : linker.allOverloadAltMetas()) {
                if (m.signatureKey != null && !m.signatureKey.isEmpty()) {
                    unique.putIfAbsent(m.signatureKey, m);
                }
            }
            List<org.babblelang.runtime.OverloadSet.AltMeta> ordered =
                    new java.util.ArrayList<>(unique.values());
            ordered.sort(
                    (a, b) -> Integer.compare(metaSpecificityScore(b), metaSpecificityScore(a)));

            for (org.babblelang.runtime.OverloadSet.AltMeta m : ordered) {
                // 1. Arity check
                c.localGet(argLen).i32Const(m.minArity).op(WasmInstr.SimpleOp.I32_GE_S);
                if (m.maxArity != Integer.MAX_VALUE) {
                    c.localGet(argLen)
                            .i32Const(m.maxArity)
                            .op(WasmInstr.SimpleOp.I32_LE_S)
                            .op(WasmInstr.SimpleOp.I32_AND);
                }
                c.if_();
                ovCtx.pushBlock("ov_arity");

                // 2. Check if this OverloadSet contains m.signatureKey
                c.i32Const(-1).localSet(matchIdx);
                c.i32Const(0).localSet(scanI);
                int kBrk = ovCtx.pushBlock("ov_kbrk");
                c.block();
                int kLoop = ovCtx.pushBlock("ov_kloop");
                c.loop();
                c.localGet(scanI)
                        .localGet(altsLen)
                        .op(WasmInstr.SimpleOp.I32_GE_S)
                        .brIf(ovCtx.relDepth(kBrk));
                c.localGet(keys).localGet(scanI).arrayGet(ts.anyArrType).refCast(ts.stringType);
                rt.emitStringLit(c, m.signatureKey);
                c.call(rt.fnStrEq).if_();
                c.localGet(scanI).localSet(matchIdx).br(ovCtx.relDepth(kBrk) + 1);
                c.end();
                c.localGet(scanI).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(scanI);
                c.br(ovCtx.relDepth(kLoop));
                ovCtx.popBlock();
                c.end();
                ovCtx.popBlock();
                c.end();

                // 3. If key present, check runtime arg types against m.paramDescriptors
                c.localGet(matchIdx).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_();
                ovCtx.pushBlock("ov_types");
                c.i32Const(1).localSet(ok);
                int fixedCount =
                        m.restIndex >= 0
                                ? Math.min(m.restIndex, m.paramDescriptors.length)
                                : m.paramDescriptors.length;
                for (int p = 0; p < fixedCount; p++) {
                    String pdesc = m.paramDescriptors[p];
                    if (pdesc == null || pdesc.isEmpty()) continue;
                    WasmTypeDesc parsed = WasmTypeDesc.parse(pdesc);
                    if (parsed == null || "any".equals(parsed.kind())) continue;
                    c.localGet(ok)
                            .i32Const(p)
                            .localGet(argLen)
                            .op(WasmInstr.SimpleOp.I32_LT_S)
                            .op(WasmInstr.SimpleOp.I32_AND)
                            .if_();
                    ovCtx.pushBlock("ov_pchk");
                    c.localGet(1).i32Const(p).arrayGet(ts.anyArrType).localSet(argVal);
                    fe.emitDescCheck(c, argVal, parsed, true);
                    c.localSet(ok);
                    ovCtx.popBlock();
                    c.end();
                }
                if (m.restIndex >= 0 && m.restIndex < m.paramDescriptors.length) {
                    String rdesc = m.paramDescriptors[m.restIndex];
                    WasmTypeDesc parsedR =
                            (rdesc != null && !rdesc.isEmpty()) ? WasmTypeDesc.parse(rdesc) : null;
                    if (parsedR != null && !"any".equals(parsedR.kind())) {
                        c.i32Const(m.restIndex).localSet(argIdx);
                        int rBrk = ovCtx.pushBlock("ov_rbrk");
                        c.block();
                        int rLoop = ovCtx.pushBlock("ov_rloop");
                        c.loop();
                        c.localGet(ok)
                                .op(WasmInstr.SimpleOp.I32_EQZ)
                                .localGet(argIdx)
                                .localGet(argLen)
                                .op(WasmInstr.SimpleOp.I32_GE_S)
                                .op(WasmInstr.SimpleOp.I32_OR)
                                .brIf(ovCtx.relDepth(rBrk));
                        c.localGet(1).localGet(argIdx).arrayGet(ts.anyArrType).localSet(argVal);
                        fe.emitDescCheck(c, argVal, parsedR, true);
                        c.localSet(ok);
                        c.localGet(argIdx)
                                .i32Const(1)
                                .op(WasmInstr.SimpleOp.I32_ADD)
                                .localSet(argIdx);
                        c.br(ovCtx.relDepth(rLoop));
                        ovCtx.popBlock();
                        c.end();
                        ovCtx.popBlock();
                        c.end();
                    }
                }
                // 4. If all matched, invoke alts[matchIdx] and return!
                c.localGet(ok).if_();
                c.localGet(alts)
                        .localGet(matchIdx)
                        .arrayGet(ts.anyArrType)
                        .refCast(ts.fnBaseType)
                        .localTee(chosen)
                        .refAsNonNull()
                        .localGet(1)
                        .localGet(2)
                        .localGet(chosen)
                        .refAsNonNull()
                        .structGet(ts.fnBaseType, 0)
                        .callRef(ts.genericCallSigType)
                        .return_();
                c.end();
                ovCtx.popBlock();
                c.end();
                ovCtx.popBlock();
                c.end();
            }

            rt.emitStringLit(c, "No applicable overload");
            c.i32Const(0).call(rt.fnThrowMsg).unreachable();
        } finally {
            fe.setCtx(prevCtx);
        }
        return overloadGenericCallIdx;
    }

    private static int metaSpecificityScore(org.babblelang.runtime.OverloadSet.AltMeta m) {
        int score = m.restIndex < 0 ? 1000 : 0;
        for (String d : m.paramDescriptors) {
            if (d == null || d.isEmpty()) continue;
            WasmTypeDesc td = WasmTypeDesc.parse(d);
            if (td == null) continue;
            score +=
                    switch (td.kind()) {
                        case "any" -> 0;
                        case "union" -> 10;
                        case "double" -> 20;
                        case "float" -> 25;
                        case "long" -> 30;
                        case "int" -> 35;
                        case "short" -> 40;
                        case "byte" -> 45;
                        case "bool", "char", "string", "java" -> 50;
                        case "babble" -> 60;
                        case "inter" -> 70;
                        default -> 15;
                    };
        }
        return score;
    }

    public void emitOverloadSelect(HirExpr.OverloadSelect s) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(s.set());
        int target = ctx.newLocal("$ovTgt", ts.anyRef());
        c.localTee(target).refTest(ts.overloadSetType).if_(ts.anyRef());
        ctx.pushBlock("ov_sel_is");
        if (s.exactSet() && s.index() >= 0) {
            c.localGet(target)
                    .refCast(ts.overloadSetType)
                    .structGet(ts.overloadSetType, 3)
                    .i32Const(s.index())
                    .arrayGet(ts.anyArrType);
        } else {
            // Search by specificity-ordered matching keys from the closed-world program (handles
            // exact + contravariant selection!)
            String sigKey = s.signatureKey() != null ? s.signatureKey() : "";
            List<String> rankedKeys =
                    org.babblelang.runtime.OverloadSet.matchingKeysBySpecificity(
                            linker.allOverloadAltMetas(), sigKey);
            if (rankedKeys.isEmpty()) {
                rankedKeys = List.of(sigKey);
            }
            int ov = ctx.newLocal("$ovSet", WasmValType.refNull(ts.overloadSetType));
            int keys = ctx.newLocal("$ovK", WasmValType.refNull(ts.anyArrType));
            int i = ctx.newLocal("$ovI", WasmValType.I32);
            int len = ctx.newLocal("$ovLen", WasmValType.I32);
            int res = ctx.newLocal("$ovRes", ts.anyRef());
            c.localGet(target)
                    .refCast(ts.overloadSetType)
                    .localTee(ov)
                    .structGet(ts.overloadSetType, 4)
                    .localTee(keys)
                    .arrayLen()
                    .localSet(len);
            c.localGet(ov)
                    .structGet(ts.overloadSetType, 3)
                    .i32Const(0)
                    .arrayGet(ts.anyArrType)
                    .localSet(res);
            int done = ctx.pushBlock("ov_sel_done");
            c.block();
            for (String candKey : rankedKeys) {
                c.i32Const(0).localSet(i);
                int loop = ctx.pushBlock("ov_sel_loop");
                c.loop();
                c.localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S).if_();
                ctx.pushBlock("ov_sel_if");
                c.localGet(keys).localGet(i).arrayGet(ts.anyArrType).refCast(ts.stringType);
                rt.emitStringLit(c, candKey);
                c.call(rt.fnStrEq).if_();
                ctx.pushBlock("ov_sel_hit");
                c.localGet(ov)
                        .structGet(ts.overloadSetType, 3)
                        .localGet(i)
                        .arrayGet(ts.anyArrType)
                        .localSet(res)
                        .br(ctx.relDepth(done));
                ctx.popBlock();
                c.end();
                c.localGet(i)
                        .i32Const(1)
                        .op(WasmInstr.SimpleOp.I32_ADD)
                        .localSet(i)
                        .br(ctx.relDepth(loop));
                ctx.popBlock();
                c.end();
                ctx.popBlock();
                c.end();
            }
            ctx.popBlock();
            c.end();
            c.localGet(res);
        }
        c.else_();
        // Single callable satisfying an overload-set type (BIP021-D12): return directly
        c.localGet(target);
        ctx.popBlock();
        c.end();
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

            LoweringTargets.ArgumentBinding binding = br.binding();
            // Invoke branch target
            if (br.directFunctionId() >= 0
                    && ctx.modInfo.functions.containsKey(br.directFunctionId())) {
                WasmProgramLinker.FuncInfo fi = ctx.modInfo.functions.get(br.directFunctionId());
                if (fi.hasEnvParam) {
                    c.localGet(calleeLocal)
                            .refTest(ts.overloadSetType)
                            .if_(WasmValType.refNull(fi.closureStructType))
                            .localGet(calleeLocal)
                            .refCast(ts.overloadSetType)
                            .structGet(ts.overloadSetType, 3)
                            .i32Const(br.altIndex())
                            .arrayGet(ts.anyArrType)
                            .refCast(fi.closureStructType)
                            .else_()
                            .localGet(calleeLocal)
                            .refCast(fi.closureStructType)
                            .end()
                            .refAsNonNull();
                }
                for (int p = 0; p < fi.hir.params().size(); p++) {
                    HirParam hp = fi.hir.params().get(p);
                    boolean boxedOpt = fi.hasBoxedMissingOptParams && hp.defaultValue() != null;
                    if (binding != null && p == binding.restParam()) {
                        emitPackedRestFromLocals(binding, argLocals, line);
                    } else {
                        int srcIdx =
                                binding != null && p < binding.paramToArg().length
                                        ? binding.paramToArg()[p]
                                        : p;
                        if (srcIdx >= 0 && srcIdx < n) {
                            LoweringTargets.SpreadAdapt adapt =
                                    binding != null ? binding.adaptForArg(srcIdx) : null;
                            fe.emitSpreadAdaptValue(argLocals[srcIdx], adapt, line);
                            if (!boxedOpt) {
                                fe.convertRawValue(PrimitiveKind.NONE, hp.kind(), line);
                            }
                        } else if (boxedOpt) {
                            c.globalGet(rt.gMissing);
                        } else {
                            ts.emitDefaultValue(c, ts.valTypeFor(hp.kind()));
                        }
                    }
                }
                if (fi.hasOptMaskParam) {
                    int mask =
                            binding != null
                                    ? binding.specMaskFor(
                                            fi.hir.optionalParamBits(), fi.hir.params().size())
                                    : 0;
                    c.i32Const(mask);
                }
                c.call(fi.directFuncIdx);
                fe.convertRawValue(fi.hir.returnKind(), resKind, line);
            } else {
                // Indirect call via selected overload alternative (or single callable if not an
                // $OverloadSet)
                int altFn = ctx.newLocal("$ovAltFn", WasmValType.refNull(ts.fnBaseType));
                c.localGet(calleeLocal)
                        .refTest(ts.overloadSetType)
                        .if_(WasmValType.refNull(ts.fnBaseType))
                        .localGet(calleeLocal)
                        .refCast(ts.overloadSetType)
                        .structGet(ts.overloadSetType, 3)
                        .i32Const(Math.max(0, br.altIndex()))
                        .arrayGet(ts.anyArrType)
                        .refCast(ts.fnBaseType)
                        .else_()
                        .localGet(calleeLocal)
                        .refCast(ts.fnBaseType)
                        .end()
                        .localTee(altFn)
                        .refAsNonNull();
                if (binding != null
                        && (binding.hasRest() || binding.hasSpreads() || binding.reordered())) {
                    int argList = ctx.newLocal("$ovArgsL", WasmValType.ref(ts.listType));
                    c.i32Const(Math.max(4, n))
                            .call(linker.collections().fnListNew)
                            .localSet(argList);
                    int pCount = binding.paramToArg().length;
                    for (int p = 0; p < pCount; p++) {
                        if (p == binding.restParam()) {
                            int[] rArgs = binding.restArgs();
                            boolean[] rSpreads = binding.restArgIsSpread();
                            for (int ri = 0; ri < rArgs.length; ri++) {
                                if (ri < rSpreads.length && rSpreads[ri]) {
                                    c.localGet(argList)
                                            .localGet(argLocals[rArgs[ri]])
                                            .i32Const(line)
                                            .call(linker.collections().fnListAddAll)
                                            .drop();
                                } else {
                                    c.localGet(argList)
                                            .localGet(argLocals[rArgs[ri]])
                                            .call(linker.collections().fnListAdd)
                                            .drop();
                                }
                            }
                        } else {
                            int srcIdx = binding.paramToArg()[p];
                            c.localGet(argList);
                            if (srcIdx >= 0 && srcIdx < n) {
                                fe.emitSpreadAdaptValue(
                                        argLocals[srcIdx], binding.adaptForArg(srcIdx), line);
                            } else {
                                c.globalGet(rt.gMissing);
                            }
                            c.call(linker.collections().fnListAdd).drop();
                        }
                    }
                    // Copy argList to exact-sized $AnyArr
                    int alLen = ctx.newLocal("$ovAlLen", WasmValType.I32);
                    int alArr = ctx.newLocal("$ovAlArr", WasmValType.ref(ts.anyArrType));
                    int alI = ctx.newLocal("$ovAlI", WasmValType.I32);
                    c.localGet(argList)
                            .structGet(ts.listType, 1)
                            .localTee(alLen)
                            .arrayNewDefault(ts.anyArrType)
                            .localSet(alArr);
                    c.i32Const(0).localSet(alI);
                    int bD = ctx.pushBlock("ov_al_brk");
                    c.block();
                    int lD = ctx.pushBlock("ov_al_loop");
                    c.loop();
                    c.localGet(alI)
                            .localGet(alLen)
                            .op(WasmInstr.SimpleOp.I32_GE_S)
                            .brIf(ctx.relDepth(bD));
                    c.localGet(alArr)
                            .localGet(alI)
                            .localGet(argList)
                            .structGet(ts.listType, 0)
                            .localGet(alI)
                            .arrayGet(ts.anyArrType)
                            .arraySet(ts.anyArrType);
                    c.localGet(alI)
                            .i32Const(1)
                            .op(WasmInstr.SimpleOp.I32_ADD)
                            .localSet(alI)
                            .br(ctx.relDepth(lD));
                    ctx.popBlock();
                    c.end();
                    ctx.popBlock();
                    c.end();
                    c.localGet(alArr);
                } else {
                    for (int i = 0; i < n; i++) {
                        c.localGet(argLocals[i]);
                    }
                    c.arrayNewFixed(ts.anyArrType, n);
                }
                c.refNull(ts.anyArrType)
                        .localGet(altFn)
                        .refAsNonNull()
                        .structGet(ts.fnBaseType, 0)
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

    private void emitPackedRestFromLocals(
            LoweringTargets.ArgumentBinding binding, int[] argLocals, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int[] rArgs = binding.restArgs();
        boolean[] rSpreads = binding.restArgIsSpread();
        int restL = ctx.newLocal("$ovRestL", WasmValType.ref(ts.listType));
        c.i32Const(Math.max(4, rArgs.length)).call(linker.collections().fnListNew).localSet(restL);
        for (int i = 0; i < rArgs.length; i++) {
            c.localGet(restL).localGet(argLocals[rArgs[i]]);
            if (i < rSpreads.length && rSpreads[i]) {
                c.i32Const(line).call(linker.collections().fnListAddAll).drop();
            } else {
                c.call(linker.collections().fnListAdd).drop();
            }
        }
        c.localGet(restL);
    }

    private void emitSplitCheck(int argLocal, LoweringTargets.OverloadSplitCheck chk) {
        WasmCodeBuilder c = fe.ctx().code;
        if (chk.isNull()) {
            c.localGet(argLocal).refIsNull();
            return;
        }
        boolean first = true;
        for (String desc : chk.descriptors()) {
            fe.emitDescCheck(c, argLocal, WasmTypeDesc.parse(desc), false);
            if (!first) {
                c.op(WasmInstr.SimpleOp.I32_AND);
            }
            first = false;
        }
        if (first) {
            c.i32Const(1);
        }
    }

    // ---------------------------------------------------------------- Portable core
    // JavaConstructorCall lowering

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
                c.localTee(tmp)
                        .refIsNull()
                        .if_(WasmValType.refNull(ts.stringType))
                        .refNull(ts.stringType)
                        .else_()
                        .localGet(tmp)
                        .call(rt.fnFormat)
                        .end()
                        .localSet(msgLocal);
            }
            if (call.args().size() >= 2) {
                fe.emitBoxed(call.args().get(1));
                c.localSet(causeLocal);
            }
            c.localGet(msgLocal).localGet(causeLocal).i32Const(line).call(rt.fnMakeExn);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        if ("java.lang.ArithmeticException".equals(owner)
                && call.pureStaticCallee()
                && call.callee() == null) {
            rt.emitStringLit(c, "java.lang.ArithmeticException : ");
            if (call.args().size() >= 1) {
                fe.emitBoxed(call.args().get(0));
                c.call(rt.fnFormat).call(rt.fnStrConcat);
            }
            c.refNull(ts.anyType).i32Const(line).call(rt.fnMakeExn);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        rt.emitStringLit(c, "Unsupported Java constructor on Wasm : " + owner);
        c.i32Const(line).call(rt.fnThrowMsg).unreachable();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    /**
     * Emits a direct Wasm host import call declared via {@code axiom wasm.<mod>.<name>: fn ...}
     * ({@code BIP025-D9}).
     */
    private void emitWasmHostImportCall(
            WasmProgramLinker.WasmImportBinding binding, List<HirCallArg> args, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        LoweringTargets.WasmImportSpec spec = binding.spec();
        int n = spec.paramKinds().length;
        for (int i = 0; i < n; i++) {
            HirExpr arg = i < args.size() ? args.get(i).value() : null;
            if (spec.paramIsString()[i]) {
                if (arg == null) {
                    c.refNull(WasmValType.HeapType.Abstract.EXTERN);
                } else {
                    fe.emitBoxed(arg);
                    int tmp = ctx.newLocal("$wStrArg", ts.anyRef());
                    c.localTee(tmp)
                            .refIsNull()
                            .if_(WasmValType.EXTERNREF)
                            .refNull(WasmValType.HeapType.Abstract.EXTERN)
                            .else_()
                            .localGet(tmp)
                            .refCast(ts.stringType)
                            .call(rt.fnToExternStr)
                            .end();
                }
            } else if (spec.paramKinds()[i] != PrimitiveKind.NONE) {
                if (arg == null) {
                    ts.emitDefaultValue(c, ts.valTypeFor(spec.paramKinds()[i]));
                } else {
                    fe.emitAs(arg, spec.paramKinds()[i]);
                }
            } else {
                if (arg == null) {
                    c.refNull(WasmValType.HeapType.Abstract.EXTERN);
                } else {
                    fe.emitBoxed(arg);
                    int tmp = ctx.newLocal("$wAnyArg", ts.anyRef());
                    c.localTee(tmp)
                            .refIsNull()
                            .if_(WasmValType.EXTERNREF)
                            .refNull(WasmValType.HeapType.Abstract.EXTERN)
                            .else_()
                            .localGet(tmp)
                            .call(rt.fnFormat)
                            .call(rt.fnToExternStr)
                            .end();
                }
            }
        }
        c.call(binding.funcIdx());
        if (spec.returnIsVoid()) {
            c.refNull(ts.anyType);
            ctx.stackKind = PrimitiveKind.NONE;
        } else if (spec.returnIsString() || spec.returnKind() == PrimitiveKind.NONE) {
            int extTmp = ctx.newLocal("$wRetExt", WasmValType.EXTERNREF);
            c.localTee(extTmp)
                    .refIsNull()
                    .if_(ts.anyRef())
                    .refNull(ts.anyType)
                    .else_()
                    .localGet(extTmp)
                    .call(rt.fnFromExternStr)
                    .end();
            ctx.stackKind = PrimitiveKind.NONE;
        } else {
            ctx.stackKind = spec.returnKind();
        }
    }
}
