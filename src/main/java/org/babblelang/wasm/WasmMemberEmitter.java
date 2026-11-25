package org.babblelang.wasm;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirStaticBlock;
import org.babblelang.ir.hir.HirVar;

import java.util.List;

/**
 * Emits HIR object-model expressions into WebAssembly 3.0 WasmGC: constructors, {@code ClassExpr},
 * {@code InterfaceExpr}, {@code ThisFieldGet}/{@code Assign}, {@code RecordNew},
 * {@code DirectThisMethodCall}, {@code InterfaceCall} (with single-implementer devirtualization),
 * {@code SuperCall}, {@code ModuleExportGet}, {@code DynamicSelect}/{@code FieldAssign}, and
 * {@code SafeCall} ({@code BIP025-I2}, {@code BIP025-D7}, {@code BIP025-D11}).
 */
public final class WasmMemberEmitter {
    private final WasmProgramLinker linker;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmObjectSystem objects;
    private final WasmFuncEmitter fe;
    private final WasmCallEmitter calls;

    public WasmMemberEmitter(WasmProgramLinker linker, WasmFuncEmitter fe, WasmCallEmitter calls) {
        this.linker = linker;
        this.ts = linker.types();
        this.rt = linker.runtime();
        this.objects = linker.objects();
        this.fe = fe;
        this.calls = calls;
    }

    // ---------------------------------------------------------------- Class / Record / Object Constructor Body

    public void emitClassConstructorBody(WasmObjectSystem.ClassInfo ci) {
        WasmModule.FuncDef fn = ci.directCtorDef;
        int paramIdx = 0;
        int vtableParamIdx = ci.hasCapturingIfaces ? paramIdx++ : -1;
        int capStart = paramIdx;
        paramIdx += ci.hir.capturedVars().size();
        int firstParamIdx = paramIdx;
        paramIdx += ci.hir.params().size();
        int optMaskLocal = ci.ctorHasOptMask ? paramIdx++ : -1;

        WasmFuncEmitter.EmitCtx prev = fe.ctx();
        WasmFuncEmitter.EmitCtx cctx = new WasmFuncEmitter.EmitCtx(
                ci.modInfo, null, fn, paramIdx, PrimitiveKind.NONE, -1, optMaskLocal);
        cctx.ownerClass = ci;
        cctx.currentTopLevelStmtIdx = ci.modInfo.classOwnerTopLevelStmtIdx.getOrDefault(ci.hir.id(), -1);
        fe.setCtx(cctx);
        try {
            WasmCodeBuilder c = cctx.code;

            // 1. Register captured variables (available via constructor's $cap params)
            for (int i = 0; i < ci.hir.capturedVars().size(); i++) {
                HirVar cv = ci.hir.capturedVars().get(i);
                int cellType = ts.cellTypeFor(cv.primitiveKind());
                cctx.vars.put(cv.id(), new WasmFuncEmitter.VarSlot(capStart + i, cv.primitiveKind(), true, cellType, false, -1));
            }

            // 2. Evaluate optional parameter defaults if $optMask bit is set
            if (ci.ctorHasOptMask) {
                int optBit = 0;
                for (int i = 0; i < ci.hir.params().size(); i++) {
                    HirParam p = ci.hir.params().get(i);
                    if (p.defaultValue() != null) {
                        int pLocal = firstParamIdx + i;
                        c.localGet(optMaskLocal).i32Const(1 << optBit).op(WasmInstr.SimpleOp.I32_AND).if_();
                        cctx.pushBlock("ctor_opt");
                        bindCtorParam(cctx, p, pLocal);
                        fe.emitAs(p.defaultValue(), p.kind());
                        c.localSet(pLocal);
                        cctx.popBlock();
                        c.end();
                        optBit++;
                    }
                    bindCtorParam(cctx, p, firstParamIdx + i);
                }
            } else {
                for (int i = 0; i < ci.hir.params().size(); i++) {
                    bindCtorParam(cctx, ci.hir.params().get(i), firstParamIdx + i);
                }
            }

            // 3. Wrap any cell-captured constructor parameters
            for (int i = 0; i < ci.hir.params().size(); i++) {
                HirParam p = ci.hir.params().get(i);
                HirVar pv = p.var();
                if (pv != null && pv.cell()) {
                    int cellType = ts.cellTypeFor(pv.primitiveKind());
                    int cellLocal = cctx.newLocal("cell_" + pv.name(), WasmValType.ref(cellType));
                    c.localGet(firstParamIdx + i);
                    fe.convertRawValue(p.kind(), pv.primitiveKind(), ci.hir.line());
                    if (pv.primitiveKind().isPrimitive()) {
                        c.i32Const(1);
                    }
                    c.structNew(cellType).localSet(cellLocal);
                    cctx.vars.put(pv.id(), new WasmFuncEmitter.VarSlot(cellLocal, pv.primitiveKind(), true, cellType, false, -1));
                }
            }

            // 4. Allocate the WasmGC struct instance (records have immutable param fields filled directly!)
            int[] memberParamIdx = new int[ci.shape.memberCount()];
            java.util.Arrays.fill(memberParamIdx, -1);
            for (int p = 0; p < ci.shape.parameters().length; p++) {
                int mi = ci.shape.parameterMember(p);
                if (mi >= 0 && mi < memberParamIdx.length) {
                    memberParamIdx[mi] = p;
                }
            }

            int thisLocal = cctx.newLocal("$this", WasmValType.refNull(ci.structTypeIdx));
            cctx.thisLocal = thisLocal;

            if (vtableParamIdx >= 0) {
                c.localGet(vtableParamIdx);
            } else {
                c.globalGet(ci.vtableGlobalIdx).refAsNonNull();
            }
            for (int i = 0; i < ci.shape.memberCount(); i++) {
                if (ci.memberToStructField[i] < 0) continue;
                int pi = memberParamIdx[i];
                if (pi >= 0) {
                    HirParam hp = ci.hir.params().get(pi);
                    c.localGet(firstParamIdx + pi);
                    fe.convertRawValue(hp.kind(), ci.memberKinds[i], ci.hir.line());
                } else if (ci.memberKinds[i] == PrimitiveKind.NONE) {
                    c.globalGet(rt.gUndefined);
                } else {
                    ts.emitDefaultValue(c, ts.valTypeFor(ci.memberKinds[i]));
                }
            }
            if (ci.defMaskFieldIdx >= 0) {
                int initMask = 0;
                for (int i = 0; i < ci.shape.memberCount(); i++) {
                    if (memberParamIdx[i] >= 0 && i < ci.hir.primDefBits().length && ci.hir.primDefBits()[i] >= 0) {
                        initMask |= (1 << ci.hir.primDefBits()[i]);
                    }
                }
                c.i32Const(initMask);
            }
            for (int i = 0; i < ci.hir.capturedVars().size(); i++) {
                c.localGet(capStart + i);
            }
            for (int i = 0; i < ci.hiddenCells.size(); i++) {
                c.refNull(ts.cellTypeFor(ci.hiddenCells.get(i).primitiveKind()));
            }
            c.structNew(ci.structTypeIdx).localSet(thisLocal);

            // 5. Allocate local vars declared in constructor body and run constructor body
            for (HirVar v : ci.hir.declaredVars()) {
                fe.allocateLocalVar(v);
                if (v.kind() == org.babblelang.analysis.Resolution.Variable.Kind.THIS || "this".equals(v.name())) {
                    WasmFuncEmitter.VarSlot slot = cctx.vars.get(v.id());
                    if (slot != null) {
                        c.localGet(thisLocal);
                        fe.storeVarSlotAndKeep(slot);
                        c.drop();
                    }
                }
            }
            for (int i = 0; i < ci.hiddenCells.size(); i++) {
                WasmFuncEmitter.VarSlot slot = cctx.vars.get(ci.hiddenCells.get(i).id());
                if (slot != null && slot.isCell()) {
                    c.localGet(thisLocal).refAsNonNull()
                            .localGet(slot.localIdx())
                            .structSet(ci.structTypeIdx, ci.hiddenCellBaseFieldIdx + i);
                }
            }
            for (HirExpr stmt : ci.hir.body()) {
                fe.emitVoid(stmt);
            }

            c.localGet(thisLocal).refAsNonNull();
        } finally {
            fe.setCtx(prev);
        }
    }

    private void bindCtorParam(WasmFuncEmitter.EmitCtx cctx, HirParam p, int pLocal) {
        HirVar pv = p.var();
        if (pv != null && !cctx.vars.containsKey(pv.id())) {
            cctx.vars.put(pv.id(), new WasmFuncEmitter.VarSlot(pLocal, p.kind(), false, -1, false, -1));
        }
    }

    // ---------------------------------------------------------------- ClassExpr & InterfaceExpr & Static Blocks

    public void emitClassExpr(HirExpr.ClassExpr ce) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmObjectSystem.ClassInfo ci = objects.classByLocal(ctx.modInfo.index, ce.classId());

        // Run static block once if present
        if (ci.hir.staticBlock() != null && !ci.hir.staticBlock().body().isEmpty()) {
            c.globalGet(ci.staticInitDoneGlobalIdx).op(WasmInstr.SimpleOp.I32_EQZ).if_();
            ctx.pushBlock("cls_sinit");
            c.i32Const(1).globalSet(ci.staticInitDoneGlobalIdx);
            int clsVal = ctx.newLocal("$clsStaticThis", ts.anyRef());
            int stArr = ctx.newLocal("$stArr", WasmValType.refNull(ts.anyArrType));
            c.globalGet(ci.classValGlobalIdx).refAsNonNull().localTee(clsVal)
                    .refCast(ts.classValType).structGet(ts.classValType, 5).localSet(stArr);
            emitStaticBlockBody(ci.hir.staticBlock(), stArr, clsVal);
            ctx.popBlock();
            c.end();
        }

        if (ci.hir.isObject()) {
            if (ci.hasCaptures || ci.hasCapturingIfaces) {
                // Capturing object inside a function/closure: allocate a fresh instance per evaluation!
                if (ci.hasCapturingIfaces) {
                    emitPerEvalVTable(ci, ce.meta().line());
                }
                for (HirVar cv : ci.hir.capturedVars()) {
                    emitCaptureCellPush(cv, ce.meta().line());
                }
                if (ci.ctorHasOptMask) c.i32Const(0);
                c.call(ci.directCtorIdx);
                ctx.stackKind = PrimitiveKind.NONE;
                return;
            }
            // Non-capturing object singleton: instantiate once on first evaluation
            c.globalGet(ci.singletonObjGlobalIdx).refIsNull().if_();
            ctx.pushBlock("obj_init");
            if (ci.ctorHasOptMask) c.i32Const(0);
            c.call(ci.directCtorIdx).globalSet(ci.singletonObjGlobalIdx);
            ctx.popBlock();
            c.end();
            c.globalGet(ci.singletonObjGlobalIdx).refAsNonNull();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }

        if (ci.hasCaptures || ci.hasCapturingIfaces) {
            c.refFunc(ci.genericCtorIdx);
            rt.emitStringLit(c, ci.shape.name());
            c.i32Const(ci.classId)
                    .i32Const(ci.classId);
            if (ci.hasCapturingIfaces) {
                emitPerEvalVTable(ci, ce.meta().line());
            } else {
                c.globalGet(ci.vtableGlobalIdx).refAsNonNull();
            }
            c.globalGet(ci.classValGlobalIdx).refAsNonNull().structGet(ts.classValType, 5);
            if (ci.hasCaptures) {
                for (HirVar cv : ci.hir.capturedVars()) {
                    emitCaptureCellPush(cv, ce.meta().line());
                }
                c.structNew(ci.classValStructType);
            } else {
                c.structNew(ts.classValType);
            }
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }

        c.globalGet(ci.classValGlobalIdx).refAsNonNull();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    /** Clones {@code G_VT_C} with a fresh {@code ifaceCaps} array capturing live interface envs. */
    private void emitPerEvalVTable(WasmObjectSystem.ClassInfo ci, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int capArr = ctx.newLocal("$vtCapArr", WasmValType.refNull(ts.anyArrType));
        int capCnt = objects.capturingIfaceCount();
        c.i32Const(capCnt).arrayNewDefault(ts.anyArrType).localSet(capArr);
        for (WasmObjectSystem.IfaceInfo ii : ci.implementedIfaces) {
            if (!ii.hasCaptures()) continue;
            c.localGet(capArr).refAsNonNull().i32Const(ii.capSlotIdx)
                    .globalGet(ii.latestCapEnvGlobalIdx)
                    .arraySet(ts.anyArrType);
        }
        // Also check direct implementedTypes rootVars (in case an $IfaceVal was passed via local variable)
        for (org.babblelang.ir.hir.HirTypeRef tr : ci.hir.implementedTypes()) {
            if (tr.rootVar() != null && tr.path().size() == 1 && ctx.vars.containsKey(tr.rootVar().id())) {
                int ivTmp = ctx.newLocal("$trIv", ts.anyRef());
                fe.emitVarRead(tr.rootVar(), line);
                c.localTee(ivTmp).refTest(ts.ifaceValType).if_();
                int ivCast = ctx.newLocal("$trIvC", WasmValType.refNull(ts.ifaceValType));
                c.localGet(ivTmp).refCast(ts.ifaceValType).localSet(ivCast);
                for (WasmObjectSystem.IfaceInfo ii : ci.implementedIfaces) {
                    if (!ii.hasCaptures()) continue;
                    c.localGet(ivCast).refAsNonNull().structGet(ts.ifaceValType, 0)
                            .i32Const(ii.ifaceIdx).op(WasmInstr.SimpleOp.I32_EQ).if_()
                            .localGet(capArr).refAsNonNull().i32Const(ii.capSlotIdx)
                            .localGet(ivCast).refAsNonNull().structGet(ts.ifaceValType, 3)
                            .arraySet(ts.anyArrType)
                            .end();
                }
                c.end();
            }
        }
        int baseVt = ctx.newLocal("$baseVt", WasmValType.refNull(ts.vtableType));
        c.globalGet(ci.vtableGlobalIdx).refAsNonNull().localTee(baseVt)
                .structGet(ts.vtableType, 0);
        for (int f = 1; f <= 12; f++) {
            c.localGet(baseVt).refAsNonNull().structGet(ts.vtableType, f);
        }
        c.localGet(capArr).structNew(ts.vtableType);
    }

    public void emitInterfaceExpr(HirExpr.InterfaceExpr ie) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmObjectSystem.IfaceInfo ii = objects.ifaceByLocal(ctx.modInfo.index, ie.interfaceId());

        if (ii.hir.staticBlock() != null && !ii.hir.staticBlock().body().isEmpty()) {
            c.globalGet(ii.staticInitDoneGlobalIdx).op(WasmInstr.SimpleOp.I32_EQZ).if_();
            ctx.pushBlock("if_sinit");
            c.i32Const(1).globalSet(ii.staticInitDoneGlobalIdx);
            int ifVal = ctx.newLocal("$ifStaticThis", ts.anyRef());
            int stArr = ctx.newLocal("$ifStArr", WasmValType.refNull(ts.anyArrType));
            c.globalGet(ii.ifaceValGlobalIdx).refAsNonNull().localTee(ifVal)
                    .refCast(ts.ifaceValType).structGet(ts.ifaceValType, 2).localSet(stArr);
            emitStaticBlockBody(ii.hir.staticBlock(), stArr, ifVal);
            ctx.popBlock();
            c.end();
        }
        if (ii.hasCaptures()) {
            int capLocal = ctx.newLocal("$ifCap", WasmValType.refNull(ii.capStructTypeIdx));
            for (HirVar cv : ii.capturedVars) {
                emitCaptureCellPush(cv, ie.meta().line());
            }
            c.structNew(ii.capStructTypeIdx).localTee(capLocal).globalSet(ii.latestCapEnvGlobalIdx);
            c.i32Const(ii.ifaceIdx);
            rt.emitStringLit(c, ii.hir.shape().name());
            c.globalGet(ii.ifaceValGlobalIdx).refAsNonNull().structGet(ts.ifaceValType, 2)
                    .localGet(capLocal)
                    .structNew(ts.ifaceValType);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        c.globalGet(ii.ifaceValGlobalIdx).refAsNonNull();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitStaticBlockBody(HirStaticBlock sb, int staticArrLocal, int typeValLocal) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        int prevSt = ctx.staticArrayLocal;
        int prevThis = ctx.thisLocal;
        ctx.staticArrayLocal = staticArrLocal;
        ctx.thisLocal = typeValLocal;
        for (HirVar v : sb.declaredVars()) {
            fe.allocateLocalVar(v);
            if (v.kind() == org.babblelang.analysis.Resolution.Variable.Kind.THIS) {
                WasmFuncEmitter.VarSlot slot = ctx.vars.get(v.id());
                if (slot != null) {
                    ctx.code.localGet(typeValLocal);
                    fe.storeVarSlotAndKeep(slot);
                    ctx.code.drop();
                }
            }
        }
        try {
            for (HirExpr stmt : sb.body()) {
                fe.emitVoid(stmt);
            }
        } finally {
            ctx.staticArrayLocal = prevSt;
            ctx.thisLocal = prevThis;
        }
    }

    private void emitCaptureCellPush(HirVar cv, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmFuncEmitter.VarSlot slot = ctx.vars.get(cv.id());
        if (slot == null) {
            throw new IllegalStateException("Missing captured variable for object/class : " + cv);
        }
        if (slot.isCapturedField()) {
            if (ctx.ownerClass != null) {
                c.localGet(ctx.thisLocal).refAsNonNull().structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx());
            } else if (ctx.ifaceCapLocal >= 0) {
                c.localGet(ctx.ifaceCapLocal).refAsNonNull().structGet(ctx.ifaceCapStructType, slot.captureFieldIdx());
            } else {
                c.localGet(ctx.envLocal).structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx());
            }
        } else if (slot.isCell()) {
            c.localGet(slot.localIdx()).refAsNonNull();
        } else {
            int cellType = ts.cellTypeFor(cv.primitiveKind());
            c.localGet(slot.localIdx());
            fe.convertRawValue(slot.kind(), cv.primitiveKind(), line);
            if (cv.primitiveKind().isPrimitive()) {
                c.i32Const(1);
            }
            c.structNew(cellType);
        }
    }

    // ---------------------------------------------------------------- RecordNew

    public void emitRecordNew(HirExpr.RecordNew rn) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmObjectSystem.ClassInfo ci = objects.resolveClass(ctx.modInfo.index, rn.classId(), rn.classTypeId(), rn.classJvmName());
        if (ci == null) {
            rt.emitStringLit(c, "Unknown record class : " + rn.classTypeId());
            c.i32Const(rn.meta().line()).call(rt.fnThrowMsg).unreachable();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        if (ci.hasCapturingIfaces) {
            if (rn.classRef() != null) {
                fe.emitBoxed(rn.classRef());
                c.refCast(ts.classValType).structGet(ts.classValType, 4);
            } else {
                emitPerEvalVTable(ci, rn.meta().line());
            }
        } else if (rn.classRef() != null && !(rn.classRef() instanceof HirExpr.VarRead || rn.classRef() instanceof HirExpr.NameRead)) {
            fe.emitVoid(rn.classRef());
        }
        for (HirVar cv : ci.hir.capturedVars()) {
            emitCaptureCellPush(cv, rn.meta().line());
        }
        emitBoundParams(ci.hir.params(), ci.ctorHasOptMask, false, rn.args(), null, rn.binding(), rn.meta().line());
        c.call(ci.directCtorIdx);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- ThisFieldGet & ThisFieldAssign

    private boolean isDirectOwnerThis(HirExpr receiver, WasmObjectSystem.ClassInfo ci) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        if (ctx.thisLocal < 0 || ctx.ownerClass == null || ctx.ownerClass.structTypeIdx != ci.structTypeIdx) {
            return false;
        }
        if (!(receiver instanceof HirExpr.ThisRef t)) {
            return false;
        }
        if (t.var() == null) {
            return true;
        }
        WasmFuncEmitter.VarSlot slot = ctx.vars.get(t.var().id());
        return slot == null || (!slot.isCell() && !slot.isCapturedField() && slot.localIdx() == ctx.thisLocal);
    }

    public void emitThisFieldGet(HirExpr.ThisFieldGet g) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmObjectSystem.ClassInfo ci = objects.resolveClass(ctx.modInfo.index, g.classId(), null, g.externalClassJvmName());
        if (ci == null) {
            emitDynamicSelect(new HirExpr.DynamicSelect(g.receiver(), g.receiverText(), g.memberName(), false, g.meta()));
            return;
        }
        int fi = g.memberIndex() >= 0 && g.memberIndex() < ci.memberToStructField.length
                ? ci.memberToStructField[g.memberIndex()] : -1;
        if (fi < 0) {
            // Method selected as value
            fe.emitBoxed(g.receiver());
            c.refCast(ts.objectType);
            rt.emitStringLit(c, g.memberName());
            c.call(ci.getMemberDef.funcIndex());
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        boolean directThis = isDirectOwnerThis(g.receiver(), ci);
        if (directThis) {
            c.localGet(ctx.thisLocal);
        } else {
            fe.emitBoxed(g.receiver());
            c.refCast(ci.structTypeIdx);
        }
        PrimitiveKind mk = ci.memberKinds[g.memberIndex()];
        boolean needsCheck = !g.definitelyDefined();
        if (needsCheck && ci.defMaskFieldIdx >= 0 && g.memberIndex() < ci.hir.primDefBits().length && ci.hir.primDefBits()[g.memberIndex()] >= 0) {
            int recv = ctx.newLocal("$tfGetR", WasmValType.refNull(ci.structTypeIdx));
            int bit = 1 << ci.hir.primDefBits()[g.memberIndex()];
            c.localTee(recv)
                    .structGet(ci.structTypeIdx, ci.defMaskFieldIdx)
                    .i32Const(bit).op(WasmInstr.SimpleOp.I32_AND).op(WasmInstr.SimpleOp.I32_EQZ).if_();
            rt.emitStringLit(c, "Member used before definition : " + g.memberName());
            c.i32Const(g.meta().line()).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(recv).refAsNonNull().structGet(ci.structTypeIdx, fi);
        } else if (needsCheck && mk == PrimitiveKind.NONE) {
            int tmp = ctx.newLocal("$tfGetV", ts.anyRef());
            c.structGet(ci.structTypeIdx, fi)
                    .localTee(tmp).globalGet(rt.gUndefined).refEq().if_();
            rt.emitStringLit(c, "Member used before definition : " + g.memberName());
            c.i32Const(g.meta().line()).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(tmp);
        } else {
            c.structGet(ci.structTypeIdx, fi);
        }
        ctx.stackKind = mk;
    }

    public void emitThisFieldAssign(HirExpr.ThisFieldAssign a) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmObjectSystem.ClassInfo ci = objects.resolveClass(ctx.modInfo.index, a.classId(), null, null);
        if (ci == null) {
            emitDynamicFieldAssign(new HirExpr.DynamicFieldAssign(a.receiver(), null, a.memberName(),
                    a.operator(), a.value(), a.targetText(), a.valueText(), a.meta()));
            return;
        }
        int fi = ci.memberToStructField[a.memberIndex()];
        PrimitiveKind fk = ci.memberKinds[a.memberIndex()];
        int recv;
        if (isDirectOwnerThis(a.receiver(), ci)) {
            recv = ctx.thisLocal;
        } else {
            fe.emitBoxed(a.receiver());
            recv = ctx.newLocal("$tfRecv", WasmValType.refNull(ci.structTypeIdx));
            c.refCast(ci.structTypeIdx).localSet(recv);
        }

        // Reject assignment to a field before its `def` has run during construction
        if (ci.defMaskFieldIdx >= 0 && a.memberIndex() < ci.hir.primDefBits().length && ci.hir.primDefBits()[a.memberIndex()] >= 0) {
            int bit = 1 << ci.hir.primDefBits()[a.memberIndex()];
            c.localGet(recv).refAsNonNull().structGet(ci.structTypeIdx, ci.defMaskFieldIdx)
                    .i32Const(bit).op(WasmInstr.SimpleOp.I32_AND).op(WasmInstr.SimpleOp.I32_EQZ).if_();
            rt.emitStringLit(c, "Member used before definition : " + a.memberName());
            c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable().end();
        } else if (fk == PrimitiveKind.NONE) {
            c.localGet(recv).refAsNonNull().structGet(ci.structTypeIdx, fi)
                    .globalGet(rt.gUndefined).refEq().if_();
            rt.emitStringLit(c, "Member used before definition : " + a.memberName());
            c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable().end();
        }

        if (a.operator() == null || "=".equals(a.operator())) {
            fe.emitAs(a.value(), fk);
        } else {
            fe.emitCompoundValue(fk, () -> {
                c.localGet(recv).refAsNonNull().structGet(ci.structTypeIdx, fi);
                ctx.stackKind = fk;
            }, a.operator(), a.value(), a.targetText(), a.valueText(), a.meta().line());
            fe.convertStack(ctx.stackKind, fk, a.meta().line());
        }
        int valTmp = ctx.newLocal("$tfVal", ts.valTypeFor(fk));
        c.localTee(valTmp);
        c.localGet(recv).refAsNonNull().localGet(valTmp).structSet(ci.structTypeIdx, fi);
        // Mark defined bit if primitive field uses defMask
        if (ci.defMaskFieldIdx >= 0 && a.memberIndex() < ci.hir.primDefBits().length && ci.hir.primDefBits()[a.memberIndex()] >= 0) {
            int bit = 1 << ci.hir.primDefBits()[a.memberIndex()];
            c.localGet(recv).refAsNonNull()
                    .localGet(recv).refAsNonNull().structGet(ci.structTypeIdx, ci.defMaskFieldIdx)
                    .i32Const(bit).op(WasmInstr.SimpleOp.I32_OR)
                    .structSet(ci.structTypeIdx, ci.defMaskFieldIdx);
        }
        ctx.stackKind = fk;
    }

    // ---------------------------------------------------------------- DirectThisMethodCall, InterfaceCall, SuperCall

    public void emitDirectThisMethodCall(HirExpr.DirectThisMethodCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmObjectSystem.ClassInfo ci = objects.resolveClass(ctx.modInfo.index, call.classId(),
                call.classTypeId(), call.externalClassJvmName());
        if (ci == null) {
            emitDynamicMethodCall(call.receiver(), call.memberName(), call.args(), call.meta().kind(), call.meta().line());
            return;
        }
        int fnId = call.targetFunctionId();
        WasmProgramLinker.FuncInfo mfi = null;
        if (fnId >= 0) {
            mfi = ci.modInfo.functions.get(fnId);
        }
        if (mfi == null && call.overloadAltIndex() >= 0) {
            List<Integer> ovIds = ci.hir.overloadMethodFunctionIds().get(call.memberName());
            if (ovIds != null && call.overloadAltIndex() < ovIds.size()) {
                mfi = ci.modInfo.functions.get(ovIds.get(call.overloadAltIndex()));
            }
        }
        if (mfi == null) {
            Integer mId = ci.hir.methodFunctionIds().get(call.memberName());
            if (mId != null) {
                mfi = ci.modInfo.functions.get(mId);
            }
        }
        if (mfi == null) {
            // Check most-specific inherited interface default method
            mfi = objects.findClassOrDefaultImpl(ci, null, call.memberName(), call.overloadAltIndex(), call.externalSignature());
        }
        if (mfi == null) {
            emitDynamicMethodCall(call.receiver(), call.memberName(), call.args(), call.meta().kind(), call.meta().line());
            return;
        }

        fe.emitBoxed(call.receiver());
        int recvLocal = ctx.newLocal("$mRecv", WasmValType.refNull(mfi.ownerClass != null ? ci.structTypeIdx : ts.objectType));
        if (mfi.ownerClass != null) {
            c.refCast(ci.structTypeIdx).localSet(recvLocal);
        } else {
            c.refCast(ts.objectType).localSet(recvLocal);
        }

        c.localGet(recvLocal).refAsNonNull();
        emitBoundParams(mfi.hir.params(), mfi.hasOptMaskParam, mfi.hasBoxedMissingOptParams, null, call.args(), call.binding(), call.meta().line());
        c.call(mfi.directFuncIdx);
        ctx.stackKind = mfi.hir.returnKind();
    }

    /**
     * Emits {@link HirExpr.InterfaceCall}: single-implementer devirtualization ({@code BIP025-D7})
     * when the closed-world interface has exactly one concrete implementer, otherwise typed
     * {@code $ITable_I} {@code call_ref}.
     */
    public void emitInterfaceCall(HirExpr.InterfaceCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmObjectSystem.IfaceInfo ii = objects.ifaceByTypeId(call.interfaceTypeId());
        if (ii == null) {
            emitDynamicMethodCallExprs(call.receiver(), call.memberName(), call.args(), call.meta().kind(), call.meta().line());
            return;
        }
        WasmObjectSystem.ITableSlot slot = ii.slotByName.get(call.memberName());
        if (slot == null) {
            // Inherited interface method declared on a super-interface (e.g. OvSub inheriting OvBase.pick$0)
            for (String supId : ii.allSuperIfaceIds) {
                WasmObjectSystem.IfaceInfo sup = objects.ifaceByTypeId(supId);
                if (sup != null && sup.slotByName.containsKey(call.memberName())) {
                    ii = sup;
                    slot = sup.slotByName.get(call.memberName());
                    break;
                }
            }
        }
        if (slot == null) {
            emitDynamicMethodCallExprs(call.receiver(), call.memberName(), call.args(), call.meta().kind(), call.meta().line());
            return;
        }

        // BIP025-D7: Single-implementer devirtualization -> direct Wasm `call` (zero itable lookup!)
        if (ii.implementers.size() == 1) {
            WasmObjectSystem.ClassInfo onlyImpl = ii.implementers.get(0);
            WasmProgramLinker.FuncInfo mfi = objects.findClassOrDefaultImpl(
                    onlyImpl, ii, slot.memberName(), slot.overloadAltIndex(), slot.sig());
            if (mfi != null && mfi.hir.restIndex() == slot.sig().restIndex()) {
                fe.emitBoxed(call.receiver());
                int recvLocal = ctx.newLocal("$devRecv", WasmValType.refNull(mfi.ownerClass != null ? onlyImpl.structTypeIdx : ts.objectType));
                c.refCast(mfi.ownerClass != null ? onlyImpl.structTypeIdx : ts.objectType).localSet(recvLocal);
                c.localGet(recvLocal).refAsNonNull();
                emitBoundParams(mfi.hir.params(), mfi.hasOptMaskParam, mfi.hasBoxedMissingOptParams, call.args(), null, call.binding(), call.meta().line());
                c.call(mfi.directFuncIdx);
                ctx.stackKind = mfi.hir.returnKind();
                return;
            }
        }

        // General typed $ITable_I indirect call via call_ref
        fe.emitBoxed(call.receiver());
        int recvLocal = ctx.newLocal("$ifRecv", WasmValType.refNull(ts.objectType));
        c.refCast(ts.objectType).localSet(recvLocal);

        // Evaluate arguments into param order matching slot.sig()
        PrimitiveKind[] pKinds = slot.sig().parameterKinds();
        int srcCount = call.args().size();
        int[] argLocals = new int[srcCount];
        PrimitiveKind[] argKinds = new PrimitiveKind[srcCount];
        LoweringTargets.ArgumentBinding binding = call.binding();
        for (int a = 0; a < srcCount; a++) {
            PrimitiveKind tk = PrimitiveKind.NONE;
            if (binding == null || (binding.isIdentityPositional() && !binding.hasRest())) {
                tk = a < pKinds.length ? pKinds[a] : PrimitiveKind.NONE;
            } else {
                for (int p = 0; p < binding.paramToArg().length && p < pKinds.length; p++) {
                    if (p != binding.restParam() && binding.paramToArg()[p] == a) {
                        tk = pKinds[p];
                        break;
                    }
                }
            }
            fe.emitAs(call.args().get(a), tk);
            argKinds[a] = tk;
            argLocals[a] = ctx.newLocal("$ifArg" + a, ts.valTypeFor(tk));
            c.localSet(argLocals[a]);
        }

        c.localGet(recvLocal).refAsNonNull();
        for (int p = 0; p < pKinds.length; p++) {
            if (binding != null && p == binding.restParam()) {
                emitRestListFromLocals(binding, argLocals, argKinds, call.meta().line());
                continue;
            }
            int srcIdx = (binding == null || (binding.isIdentityPositional() && !binding.hasRest()))
                    ? (p < srcCount ? p : -1)
                    : (p < binding.paramToArg().length ? binding.paramToArg()[p] : -1);
            if (srcIdx >= 0 && srcIdx < srcCount) {
                c.localGet(argLocals[srcIdx]);
                fe.convertRawValue(argKinds[srcIdx], pKinds[p], call.meta().line());
            } else {
                ts.emitDefaultValue(c, ts.valTypeFor(pKinds[p]));
            }
        }
        if (slot.hasOptMask()) {
            int mask = binding != null ? binding.specMaskFor(slot.sig().optionalParamBits(), pKinds.length) : 0;
            c.i32Const(mask);
        }
        // Fetch funcref from recv.vtable.itables[ii.ifaceIdx].slotIdx
        c.localGet(recvLocal).refAsNonNull()
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 4).refAsNonNull()
                .i32Const(ii.ifaceIdx).arrayGet(ts.itableArrType)
                .refCast(ii.itableStructType)
                .structGet(ii.itableStructType, slot.slotIdx())
                .callRef(slot.wasmSigTypeIdx());
        ctx.stackKind = slot.sig().returnKind();
    }

    public void emitSuperCall(HirExpr.SuperCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int line = call.meta().line();
        String provId = call.targetInfo() != null ? call.targetInfo().providerBabbleTypeId() : null;
        if (provId == null && call.targetInfo() != null) {
            provId = call.targetInfo().directBabbleTypeId();
        }
        WasmObjectSystem.IfaceInfo ii = objects.ifaceByTypeId(provId);
        if (ii == null) {
            // Overloaded super calls may have targetInfo == null: resolve from enclosing class/interface hierarchy
            String targetRefName = call.superTypeRef() != null ? call.superTypeRef().text() : null;
            WasmObjectSystem.ClassInfo oc = ctx.ownerClass;
            if (oc == null && call.ownerClassId() >= 0) {
                oc = objects.classByLocal(ctx.modInfo.index, call.ownerClassId());
            }
            List<WasmObjectSystem.IfaceInfo> cands = new java.util.ArrayList<>();
            if (oc != null) {
                for (WasmObjectSystem.IfaceInfo imp : oc.implementedIfaces) {
                    if (targetRefName != null && !targetRefName.equals(imp.hir.shape().name())) continue;
                    if (imp.hir.defaultFunctionIds().containsKey(call.memberName())
                            || imp.hir.defaultOverloadFunctionIds().containsKey(call.memberName())) {
                        cands.add(imp);
                    }
                }
            } else if (ctx.funcInfo != null && ctx.funcInfo.ownerIface != null) {
                WasmObjectSystem.IfaceInfo oi = ctx.funcInfo.ownerIface;
                for (String supId : oi.allSuperIfaceIds) {
                    if (supId.equals(oi.hir.typeId())) continue;
                    WasmObjectSystem.IfaceInfo sup = objects.ifaceByTypeId(supId);
                    if (sup == null) continue;
                    if (targetRefName != null && !targetRefName.equals(sup.hir.shape().name())) continue;
                    if (sup.hir.defaultFunctionIds().containsKey(call.memberName())
                            || sup.hir.defaultOverloadFunctionIds().containsKey(call.memberName())) {
                        cands.add(sup);
                    }
                }
            }
            if (!cands.isEmpty()) {
                ii = cands.get(0);
                for (WasmObjectSystem.IfaceInfo cand : cands) {
                    String cid = cand.hir.typeId();
                    boolean overridden = false;
                    if (cid != null) {
                        for (WasmObjectSystem.IfaceInfo other : cands) {
                            if (other != cand && other.allSuperIfaceIds.contains(cid)) {
                                overridden = true;
                                break;
                            }
                        }
                    }
                    if (!overridden) {
                        ii = cand;
                        break;
                    }
                }
            }
        }
        if (ii != null) {
            Integer defFnId = ii.hir.defaultFunctionIds().get(call.memberName());
            if (defFnId != null) {
                WasmProgramLinker.FuncInfo dfi = ii.modInfo.functions.get(defFnId);
                if (dfi != null) {
                    emitSuperThisPush(call);
                    emitBoundParams(dfi.hir.params(), dfi.hasOptMaskParam, dfi.hasBoxedMissingOptParams, null, call.args(), call.binding(), line);
                    c.call(dfi.directFuncIdx);
                    ctx.stackKind = dfi.hir.returnKind();
                    return;
                }
            }
            // Overloaded interface default method (e.g. `super.calc(u)` or `super.calc(x: u)`)
            List<Integer> ovIds = ii.hir.defaultOverloadFunctionIds().get(call.memberName());
            if (ovIds != null && !ovIds.isEmpty()) {
                emitOverloadedSuperCall(ii, ovIds, call);
                return;
            }
        }
        rt.emitStringLit(c, "Cannot resolve super method : " + call.memberName());
        c.i32Const(line).call(rt.fnThrowMsg).unreachable();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitSuperThisPush(HirExpr.SuperCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        if (call.thisVar() != null && ctx.vars.containsKey(call.thisVar().id())) {
            fe.emitVarRead(call.thisVar(), call.meta().line());
            c.refCast(ts.objectType);
        } else {
            c.localGet(ctx.thisLocal).refAsNonNull();
        }
    }

    private void emitOverloadedSuperCall(WasmObjectSystem.IfaceInfo ii, List<Integer> ovIds, HirExpr.SuperCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int line = call.meta().line();
        PrimitiveKind resKind = call.meta().kind();

        // Find matching MakeOverloadSet metas in ii.hir.body() for precise runtime param descriptors
        List<LoweringTargets.OverloadAltMetaInfo> metas = null;
        for (HirExpr stmt : ii.hir.body()) {
            if (stmt instanceof HirExpr.Def d && call.memberName().equals(d.name())
                    && d.value() instanceof HirExpr.MakeOverloadSet mos) {
                metas = mos.metas();
                break;
            }
        }

        // Evaluate $this and all source args once
        int thisL = ctx.newLocal("$supThis", WasmValType.refNull(ts.objectType));
        emitSuperThisPush(call);
        c.localSet(thisL);

        int n = call.args().size();
        int[] argLocals = new int[n];
        for (int i = 0; i < n; i++) {
            fe.emitBoxed(call.args().get(i).value());
            argLocals[i] = ctx.newLocal("$supArg" + i, ts.anyRef());
            c.localSet(argLocals[i]);
        }

        int doneDepth = ctx.pushBlock("sup_ov_done");
        c.block(ts.valTypeFor(resKind));

        for (int k = 0; k < ovIds.size(); k++) {
            int fnId = ovIds.get(k);
            if (fnId < 0) continue;
            WasmProgramLinker.FuncInfo altFi = ii.modInfo.functions.get(fnId);
            if (altFi == null) continue;
            List<HirParam> params = altFi.hir.params();
            int pCount = params.size();
            // Statically bind call.args() (positional + named) to altFi's params
            int[] boundIdx = new int[pCount];
            java.util.Arrays.fill(boundIdx, -1);
            boolean namesMatch = true;
            for (int a = 0; a < n; a++) {
                String argName = call.args().get(a).name();
                if (argName == null) {
                    if (a >= pCount || boundIdx[a] >= 0) {
                        namesMatch = false;
                        break;
                    }
                    boundIdx[a] = a;
                } else {
                    int found = -1;
                    for (int p = 0; p < pCount; p++) {
                        if (argName.equals(params.get(p).name())) {
                            found = p;
                            break;
                        }
                    }
                    if (found < 0 || boundIdx[found] >= 0) {
                        namesMatch = false;
                        break;
                    }
                    boundIdx[found] = a;
                }
            }
            if (!namesMatch) continue;
            for (int p = 0; p < pCount; p++) {
                if (boundIdx[p] < 0 && params.get(p).defaultValue() == null) {
                    namesMatch = false;
                    break;
                }
            }
            if (!namesMatch) continue;

            // Emit runtime type checks on bound args
            String[] paramDescs = (metas != null && k < metas.size()) ? metas.get(k).paramDescriptors() : null;
            boolean hasCheck = false;
            for (int p = 0; p < pCount; p++) {
                if (boundIdx[p] < 0) continue;
                String desc = (paramDescs != null && p < paramDescs.length) ? paramDescs[p] : null;
                if (desc != null && !desc.isEmpty()) {
                    fe.emitDescCheck(c, argLocals[boundIdx[p]], WasmTypeDesc.parse(desc), false);
                } else if (params.get(p).kind().isPrimitive()) {
                    c.localGet(argLocals[boundIdx[p]]).refTest(ts.boxTypeFor(params.get(p).kind()));
                } else {
                    continue;
                }
                if (hasCheck) {
                    c.op(WasmInstr.SimpleOp.I32_AND);
                }
                hasCheck = true;
            }
            if (hasCheck) {
                c.if_();
                ctx.pushBlock("sup_ov_br");
            }
            c.localGet(thisL).refAsNonNull();
            int optMask = 0;
            int optBit = 0;
            for (int p = 0; p < pCount; p++) {
                HirParam hp = params.get(p);
                boolean boxedOpt = altFi.hasBoxedMissingOptParams && hp.defaultValue() != null;
                if (boundIdx[p] >= 0) {
                    c.localGet(argLocals[boundIdx[p]]);
                    fe.convertRawValue(PrimitiveKind.NONE, boxedOpt ? PrimitiveKind.NONE : hp.kind(), line);
                } else if (boxedOpt) {
                    c.globalGet(rt.gMissing);
                } else {
                    ts.emitDefaultValue(c, ts.valTypeFor(hp.kind()));
                    if (hp.defaultValue() != null) optMask |= (1 << optBit);
                }
                if (hp.defaultValue() != null) optBit++;
            }
            if (altFi.hasOptMaskParam) {
                c.i32Const(optMask);
            }
            c.call(altFi.directFuncIdx);
            fe.convertRawValue(altFi.hir.returnKind(), resKind, line);
            c.br(ctx.relDepth(doneDepth));
            if (hasCheck) {
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

    // ---------------------------------------------------------------- ModuleExportGet, DynamicSelect, DynamicFieldAssign, SafeCall

    public void emitModuleExportGet(HirExpr.ModuleExportGet g) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(g.receiver());
        c.refCast(ts.moduleValType)
                .structGet(ts.moduleValType, 2)
                .i32Const(g.target().index())
                .arrayGet(ts.anyArrType);
        ctx.stackKind = PrimitiveKind.NONE;
        if (g.meta().kind().isPrimitive()) {
            fe.convertStack(PrimitiveKind.NONE, g.meta().kind(), g.meta().line());
        }
    }

    public void emitDynamicSelect(HirExpr.DynamicSelect s) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(s.receiver());
        int recv = ctx.newLocal("$selRecv", ts.anyRef());
        c.localTee(recv);
        if (s.safe()) {
            c.refIsNull().if_(ts.anyRef()).refNull(ts.anyType).else_();
            ctx.pushBlock("safe_sel");
        } else {
            c.refIsNull().if_();
            rt.emitStringLit(c, "Null receiver for ." + s.memberName());
            c.i32Const(s.meta().line()).call(rt.fnThrowMsg).unreachable().end();
        }
        emitDynamicMemberRead(recv, s.memberName(), s.meta().line());
        if (s.safe()) {
            ctx.popBlock();
            c.end();
        }
        ctx.stackKind = PrimitiveKind.NONE;
        if (!s.safe() && s.meta().kind().isPrimitive()) {
            fe.convertStack(PrimitiveKind.NONE, s.meta().kind(), s.meta().line());
        }
    }

    public void emitDynamicMemberRead(int recvLocal, String memberName, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int done = ctx.pushBlock("sel_done");
        c.block(ts.anyRef());

        // 1. Babble $Object -> vtable.getMemberFn
        c.localGet(recvLocal).refTest(ts.objectType).if_();
        ctx.pushBlock("sel_obj");
        c.localGet(recvLocal).refCast(ts.objectType);
        rt.emitStringLit(c, memberName);
        c.localGet(recvLocal).refCast(ts.objectType)
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 9).refAsNonNull()
                .callRef(ts.getMemFnSigType)
                .br(ctx.relDepth(done));
        ctx.popBlock();
        c.end();

        // 2. $ModuleVal -> lookup export by name
        c.localGet(recvLocal).refTest(ts.moduleValType).if_();
        ctx.pushBlock("sel_mod");
        int mv = ctx.newLocal("$mv", WasmValType.refNull(ts.moduleValType));
        int mnames = ctx.newLocal("$mnames", WasmValType.refNull(ts.anyArrType));
        int mlen = ctx.newLocal("$mlen", WasmValType.I32);
        int mi = ctx.newLocal("$mi", WasmValType.I32);
        c.localGet(recvLocal).refCast(ts.moduleValType).localTee(mv)
                .structGet(ts.moduleValType, 3).localTee(mnames).arrayLen().localSet(mlen);
        c.i32Const(0).localSet(mi);
        int mloop = ctx.pushBlock("sel_mloop");
        c.loop();
        c.localGet(mi).localGet(mlen).op(WasmInstr.SimpleOp.I32_LT_S).if_();
        ctx.pushBlock("sel_mif");
        c.localGet(mnames).refAsNonNull().localGet(mi).arrayGet(ts.anyArrType).refCast(ts.stringType);
        rt.emitStringLit(c, memberName);
        c.call(rt.fnStrEq).if_();
        ctx.pushBlock("sel_mhit");
        c.localGet(mv).refAsNonNull().structGet(ts.moduleValType, 2)
                .localGet(mi).arrayGet(ts.anyArrType)
                .br(ctx.relDepth(done));
        ctx.popBlock();
        c.end();
        c.localGet(mi).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(mi)
                .br(ctx.relDepth(mloop));
        ctx.popBlock();
        c.end();
        ctx.popBlock();
        c.end();
        ctx.popBlock();
        c.end();

        // 3. $ClassVal statics lookup
        c.localGet(recvLocal).refTest(ts.classValType).if_();
        ctx.pushBlock("sel_cls");
        for (WasmObjectSystem.ClassInfo ci : objects.allClasses()) {
            int sIdx = ci.shape.staticIndex(memberName);
            if (sIdx < 0) continue;
            c.localGet(recvLocal).refCast(ts.classValType).structGet(ts.classValType, 3)
                    .i32Const(ci.classId).op(WasmInstr.SimpleOp.I32_EQ).if_();
            ctx.pushBlock("sel_cls_hit");
            int stVal = ctx.newLocal("$clsStV", ts.anyRef());
            c.localGet(recvLocal).refCast(ts.classValType).structGet(ts.classValType, 5).refAsNonNull()
                    .i32Const(sIdx).arrayGet(ts.anyArrType).localTee(stVal)
                    .globalGet(rt.gUndefined).refEq().if_();
            rt.emitStringLit(c, "Member used before definition : " + memberName);
            c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(stVal).br(ctx.relDepth(done));
            ctx.popBlock();
            c.end();
        }
        ctx.popBlock();
        c.end();

        // 4. $IfaceVal statics lookup
        c.localGet(recvLocal).refTest(ts.ifaceValType).if_();
        ctx.pushBlock("sel_iface");
        for (WasmObjectSystem.IfaceInfo ii : objects.allIfaces()) {
            int sIdx = ii.hir.shape().staticIndex(memberName);
            if (sIdx < 0) continue;
            c.localGet(recvLocal).refCast(ts.ifaceValType).structGet(ts.ifaceValType, 0)
                    .i32Const(ii.ifaceIdx).op(WasmInstr.SimpleOp.I32_EQ).if_();
            ctx.pushBlock("sel_if_hit");
            int stVal = ctx.newLocal("$ifStV", ts.anyRef());
            c.localGet(recvLocal).refCast(ts.ifaceValType).structGet(ts.ifaceValType, 2).refAsNonNull()
                    .i32Const(sIdx).arrayGet(ts.anyArrType).localTee(stVal)
                    .globalGet(rt.gUndefined).refEq().if_();
            rt.emitStringLit(c, "Member used before definition : " + memberName);
            c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(stVal).br(ctx.relDepth(done));
            ctx.popBlock();
            c.end();
        }
        ctx.popBlock();
        c.end();

        // 5. $BabbleException message/cause
        if ("message".equals(memberName) || "cause".equals(memberName)) {
            c.localGet(recvLocal).refTest(ts.babbleExnType).if_();
            ctx.pushBlock("sel_exn");
            c.localGet(recvLocal).refCast(ts.babbleExnType)
                    .structGet(ts.babbleExnType, "message".equals(memberName) ? 0 : 1)
                    .br(ctx.relDepth(done));
            ctx.popBlock();
            c.end();
        }

        rt.emitStringLit(c, "No such member : " + memberName);
        c.i32Const(line).call(rt.fnThrowMsg).unreachable();
        ctx.popBlock();
        c.end();
    }

    public void emitDynamicFieldAssign(HirExpr.DynamicFieldAssign a) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(a.receiver());
        int recv = ctx.newLocal("$dfRecv", ts.anyRef());
        c.localSet(recv);

        if (a.operator() == null || "=".equals(a.operator())) {
            fe.emitBoxed(a.value());
        } else {
            fe.emitCompoundValue(PrimitiveKind.NONE, () -> {
                emitDynamicMemberRead(recv, a.memberName(), a.meta().line());
                ctx.stackKind = PrimitiveKind.NONE;
            }, a.operator(), a.value(), a.targetText(), a.valueText(), a.meta().line());
            fe.convertStack(ctx.stackKind, PrimitiveKind.NONE, a.meta().line());
        }
        int val = ctx.newLocal("$dfVal", ts.anyRef());
        c.localSet(val);

        c.localGet(recv).refTest(ts.objectType).if_();
        c.localGet(recv).refCast(ts.objectType);
        rt.emitStringLit(c, a.memberName());
        c.localGet(val)
                .localGet(recv).refCast(ts.objectType)
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 10).refAsNonNull()
                .callRef(ts.setMemFnSigType).drop();
        c.else_();
        // Check $ClassVal static assignment
        c.localGet(recv).refTest(ts.classValType).if_();
        for (WasmObjectSystem.ClassInfo ci : objects.allClasses()) {
            int sIdx = ci.shape.staticIndex(a.memberName());
            if (sIdx < 0) continue;
            c.localGet(recv).refCast(ts.classValType).structGet(ts.classValType, 3)
                    .i32Const(ci.classId).op(WasmInstr.SimpleOp.I32_EQ).if_();
            if (ci.shape.isStaticMethod(sIdx)) {
                rt.emitStringLit(c, "Cannot assign method : " + a.memberName());
                c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable();
            } else {
                c.localGet(recv).refCast(ts.classValType).structGet(ts.classValType, 5).refAsNonNull()
                        .i32Const(sIdx).arrayGet(ts.anyArrType).globalGet(rt.gUndefined).refEq().if_();
                rt.emitStringLit(c, "Member used before definition : " + a.memberName());
                c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable().end();
                c.localGet(recv).refCast(ts.classValType).structGet(ts.classValType, 5).refAsNonNull()
                        .i32Const(sIdx).localGet(val).arraySet(ts.anyArrType);
            }
            c.end();
        }
        c.else_();
        // Check $IfaceVal static assignment
        c.localGet(recv).refTest(ts.ifaceValType).if_();
        for (WasmObjectSystem.IfaceInfo ii : objects.allIfaces()) {
            int sIdx = ii.hir.shape().staticIndex(a.memberName());
            if (sIdx < 0) continue;
            c.localGet(recv).refCast(ts.ifaceValType).structGet(ts.ifaceValType, 0)
                    .i32Const(ii.ifaceIdx).op(WasmInstr.SimpleOp.I32_EQ).if_();
            if (ii.hir.shape().isStaticMethod(sIdx)) {
                rt.emitStringLit(c, "Cannot assign method : " + a.memberName());
                c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable();
            } else {
                c.localGet(recv).refCast(ts.ifaceValType).structGet(ts.ifaceValType, 2).refAsNonNull()
                        .i32Const(sIdx).arrayGet(ts.anyArrType).globalGet(rt.gUndefined).refEq().if_();
                rt.emitStringLit(c, "Member used before definition : " + a.memberName());
                c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable().end();
                c.localGet(recv).refCast(ts.ifaceValType).structGet(ts.ifaceValType, 2).refAsNonNull()
                        .i32Const(sIdx).localGet(val).arraySet(ts.anyArrType);
            }
            c.end();
        }
        c.else_();
        // Check $ModuleVal assignment -> exact Module.setMember diagnostics
        c.localGet(recv).refTest(ts.moduleValType).if_();
        int mv = ctx.newLocal("$dfMv", WasmValType.refNull(ts.moduleValType));
        int mnames = ctx.newLocal("$dfMNames", WasmValType.refNull(ts.anyArrType));
        int mlen = ctx.newLocal("$dfMLen", WasmValType.I32);
        int mi = ctx.newLocal("$dfMI", WasmValType.I32);
        c.localGet(recv).refCast(ts.moduleValType).localTee(mv)
                .structGet(ts.moduleValType, 3).localTee(mnames).arrayLen().localSet(mlen);
        c.i32Const(0).localSet(mi);
        c.block().loop()
                .localGet(mi).localGet(mlen).op(WasmInstr.SimpleOp.I32_GE_S).brIf(1)
                .localGet(mnames).refAsNonNull().localGet(mi).arrayGet(ts.anyArrType).refCast(ts.stringType);
        rt.emitStringLit(c, a.memberName());
        c.call(rt.fnStrEq).if_();
        rt.emitStringLit(c, "Cannot assign to an export : " + a.memberName());
        c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable().end()
                .localGet(mi).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(mi).br(0)
                .end().end();
        rt.emitStringLit(c, "No such export : " + a.memberName() + " in ");
        c.localGet(mv).refAsNonNull().structGet(ts.moduleValType, 1).call(rt.fnStrConcat)
                .i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable();
        c.else_();
        rt.emitStringLit(c, "Cannot assign member : " + a.memberName());
        c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable();
        c.end().end().end().end();

        c.localGet(val);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    public void emitSafeCall(HirExpr.SafeCall sc) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int line = sc.meta().line();
        fe.emitBoxed(sc.receiver());
        int recv = ctx.newLocal("$scRecv", ts.anyRef());
        c.localTee(recv).refIsNull().if_(ts.anyRef())
                .refNull(ts.anyType)
                .else_();
        ctx.pushBlock("safe_call");
        String m = sc.memberName();
        boolean handledCore = false;
        if ("length".equals(m) && sc.args().isEmpty()) {
            c.localGet(recv).refTest(ts.stringType).if_(ts.anyRef())
                    .localGet(recv).refCast(ts.stringType).structGet(ts.stringType, 0).arrayLen().call(rt.fnBoxInt)
                    .else_();
            ctx.pushBlock("sc_str_len");
            handledCore = true;
        } else if ("substring".equals(m) && (sc.args().size() == 1 || sc.args().size() == 2)) {
            c.localGet(recv).refTest(ts.stringType).if_(ts.anyRef())
                    .localGet(recv).refCast(ts.stringType);
            fe.emitAs(sc.args().get(0).value(), PrimitiveKind.INT);
            if (sc.args().size() == 1) {
                c.localGet(recv).refCast(ts.stringType).structGet(ts.stringType, 0).arrayLen();
            } else {
                fe.emitAs(sc.args().get(1).value(), PrimitiveKind.INT);
            }
            c.i32Const(line).call(linker.strings().fnSubstring)
                    .else_();
            ctx.pushBlock("sc_str_sub");
            handledCore = true;
        } else if ("isEmpty".equals(m) && sc.args().isEmpty()) {
            c.localGet(recv).refTest(ts.stringType).if_(ts.anyRef())
                    .localGet(recv).refCast(ts.stringType).structGet(ts.stringType, 0).arrayLen()
                    .op(WasmInstr.SimpleOp.I32_EQZ).call(rt.fnBoxBool)
                    .else_();
            ctx.pushBlock("sc_str_emp");
            handledCore = true;
        } else if ("size".equals(m) && sc.args().isEmpty()) {
            c.localGet(recv).refTest(ts.arrayBaseType).if_(ts.anyRef())
                    .localGet(recv).refCast(ts.arrayBaseType).call(linker.arrays().fnArraySize).call(rt.fnBoxInt)
                    .else_()
                    .localGet(recv).refTest(ts.listType).if_(ts.anyRef())
                    .localGet(recv).refCast(ts.listType).structGet(ts.listType, 1).call(rt.fnBoxInt)
                    .else_();
            ctx.pushBlock("sc_sz1");
            ctx.pushBlock("sc_sz2");
            handledCore = true;
        } else if ("toString".equals(m) && sc.args().isEmpty()) {
            c.localGet(recv).call(rt.fnFormat);
            ctx.popBlock();
            c.end();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }

        emitDynamicMemberRead(recv, sc.memberName(), line);
        int callee = ctx.newLocal("$scFn", WasmValType.refNull(ts.fnBaseType));
        c.refCast(ts.fnBaseType).localSet(callee);
        for (HirCallArg a : sc.args()) {
            fe.emitBoxed(a.value());
        }
        int arr = ctx.newLocal("$scArgs", WasmValType.ref(ts.anyArrType));
        c.arrayNewFixed(ts.anyArrType, sc.args().size()).localSet(arr);
        c.localGet(callee).refAsNonNull().localGet(arr).refNull(ts.anyArrType)
                .localGet(callee).refAsNonNull().structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType);

        if (handledCore) {
            if ("size".equals(m)) {
                ctx.popBlock(); c.end();
                ctx.popBlock(); c.end();
            } else {
                ctx.popBlock(); c.end();
            }
        }
        ctx.popBlock();
        c.end();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitDynamicMethodCall(HirExpr receiver, String memberName, List<HirCallArg> args,
                                       PrimitiveKind expectedKind, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(receiver);
        int recv = ctx.newLocal("$dmRecv", ts.anyRef());
        c.localSet(recv);
        emitDynamicMemberRead(recv, memberName, line);
        int fn = ctx.newLocal("$dmFn", WasmValType.refNull(ts.fnBaseType));
        c.refCast(ts.fnBaseType).localSet(fn);
        for (HirCallArg a : args) fe.emitBoxed(a.value());
        int arr = ctx.newLocal("$dmArgs", WasmValType.ref(ts.anyArrType));
        c.arrayNewFixed(ts.anyArrType, args.size()).localSet(arr);
        c.localGet(fn).refAsNonNull().localGet(arr).refNull(ts.anyArrType)
                .localGet(fn).refAsNonNull().structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType);
        ctx.stackKind = PrimitiveKind.NONE;
        if (expectedKind.isPrimitive()) fe.convertStack(PrimitiveKind.NONE, expectedKind, line);
    }

    private void emitDynamicMethodCallExprs(HirExpr receiver, String memberName, List<HirExpr> args,
                                            PrimitiveKind expectedKind, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        fe.emitBoxed(receiver);
        int recv = ctx.newLocal("$dmRecv", ts.anyRef());
        c.localSet(recv);
        emitDynamicMemberRead(recv, memberName, line);
        int fn = ctx.newLocal("$dmFn", WasmValType.refNull(ts.fnBaseType));
        c.refCast(ts.fnBaseType).localSet(fn);
        for (HirExpr a : args) fe.emitBoxed(a);
        int arr = ctx.newLocal("$dmArgs", WasmValType.ref(ts.anyArrType));
        c.arrayNewFixed(ts.anyArrType, args.size()).localSet(arr);
        c.localGet(fn).refAsNonNull().localGet(arr).refNull(ts.anyArrType)
                .localGet(fn).refAsNonNull().structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType);
        ctx.stackKind = PrimitiveKind.NONE;
        if (expectedKind.isPrimitive()) fe.convertStack(PrimitiveKind.NONE, expectedKind, line);
    }

    /** Shared helper: binds positional/named/optional arguments for a parameter list. */
    private void emitBoundParams(List<HirParam> params, boolean hasOptMask, boolean hasBoxedMissingOpt,
                                 List<HirExpr> plainArgs, List<HirCallArg> callArgs,
                                 LoweringTargets.ArgumentBinding binding, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int paramCount = params.size();
        int srcCount = plainArgs != null ? plainArgs.size() : callArgs.size();

        int optBits = 0;
        for (int p = 0; p < paramCount && p < 32; p++) {
            if (params.get(p).defaultValue() != null) optBits |= (1 << p);
        }

        if (binding == null || (binding.isIdentityPositional() && !binding.hasRest())) {
            for (int p = 0; p < paramCount; p++) {
                HirParam hp = params.get(p);
                boolean boxedOpt = hasBoxedMissingOpt && hp.defaultValue() != null;
                if (p < srcCount) {
                    if (boxedOpt) {
                        fe.emitBoxed(plainArgs != null ? plainArgs.get(p) : callArgs.get(p).value());
                    } else {
                        fe.emitAs(plainArgs != null ? plainArgs.get(p) : callArgs.get(p).value(), hp.kind());
                    }
                } else if (boxedOpt) {
                    c.globalGet(rt.gMissing);
                } else {
                    ts.emitDefaultValue(c, ts.valTypeFor(hp.kind()));
                }
            }
            if (hasOptMask) {
                int mask = 0;
                int optBit = 0;
                for (int p = 0; p < paramCount; p++) {
                    if (params.get(p).defaultValue() != null) {
                        if (p >= srcCount) mask |= (1 << optBit);
                        optBit++;
                    }
                }
                c.i32Const(mask);
            }
            return;
        }

        int[] argLocals = new int[srcCount];
        PrimitiveKind[] argKinds = new PrimitiveKind[srcCount];
        for (int a = 0; a < srcCount; a++) {
            HirExpr argExpr = plainArgs != null ? plainArgs.get(a) : callArgs.get(a).value();
            PrimitiveKind tk = PrimitiveKind.NONE;
            for (int p = 0; p < binding.paramToArg().length && p < paramCount; p++) {
                if (p != binding.restParam() && binding.paramToArg()[p] == a) {
                    HirParam hp = params.get(p);
                    tk = (hasBoxedMissingOpt && hp.defaultValue() != null) ? PrimitiveKind.NONE : hp.kind();
                    break;
                }
            }
            fe.emitAs(argExpr, tk);
            argKinds[a] = tk;
            argLocals[a] = ctx.newLocal("$bp" + a, ts.valTypeFor(tk));
            c.localSet(argLocals[a]);
        }
        for (int p = 0; p < paramCount; p++) {
            HirParam hp = params.get(p);
            boolean boxedOpt = hasBoxedMissingOpt && hp.defaultValue() != null;
            if (p == binding.restParam()) {
                emitRestListFromLocals(binding, argLocals, argKinds, line);
                continue;
            }
            int srcIdx = p < binding.paramToArg().length ? binding.paramToArg()[p] : -1;
            if (srcIdx >= 0 && srcIdx < srcCount) {
                c.localGet(argLocals[srcIdx]);
                fe.convertRawValue(argKinds[srcIdx], boxedOpt ? PrimitiveKind.NONE : hp.kind(), line);
            } else if (boxedOpt) {
                c.globalGet(rt.gMissing);
            } else {
                ts.emitDefaultValue(c, ts.valTypeFor(hp.kind()));
            }
        }
        if (hasOptMask) {
            c.i32Const(binding.specMaskFor(optBits, paramCount));
        }
    }

    private void emitRestListFromLocals(LoweringTargets.ArgumentBinding binding,
                                        int[] argLocals, PrimitiveKind[] argKinds, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int[] rArgs = binding.restArgs();
        boolean[] rSpreads = binding.restArgIsSpread();
        int restL = ctx.newLocal("$bpRestL", WasmValType.ref(ts.listType));
        c.i32Const(Math.max(4, rArgs.length)).call(linker.collections().fnListNew).localSet(restL);
        for (int i = 0; i < rArgs.length; i++) {
            int ai = rArgs[i];
            c.localGet(restL).localGet(argLocals[ai]);
            fe.convertRawValue(argKinds[ai], PrimitiveKind.NONE, line);
            if (i < rSpreads.length && rSpreads[i]) {
                c.i32Const(line).call(linker.collections().fnListAddAll).drop();
            } else {
                c.call(linker.collections().fnListAdd).drop();
            }
        }
        c.localGet(restL);
    }
}
