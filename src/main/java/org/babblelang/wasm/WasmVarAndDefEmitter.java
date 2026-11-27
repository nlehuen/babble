package org.babblelang.wasm;

import java.util.Map;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirVar;

/**
 * Emits Wasm 3.0 local variables, capture cells, closure structs, lexical/member/static {@code def}
 * statements, and variable/name assignments on behalf of {@link WasmFuncEmitter}.
 */
final class WasmVarAndDefEmitter {
    private final WasmFuncEmitter fn;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmRuntimeOpsGen ops;

    WasmVarAndDefEmitter(WasmProgramLinker linker, WasmFuncEmitter fn) {
        this.fn = fn;
        this.ts = linker.types();
        this.rt = linker.runtime();
        this.ops = linker.ops();
    }

    static boolean needsDefCheck(HirVar v) {
        return v.mayBeUndefined() && !v.definitelyDefined();
    }

    void allocateLocalVar(HirVar v) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        if (v == null || ctx.vars.containsKey(v.id())) {
            return;
        }
        WasmCodeBuilder c = ctx.code;
        boolean checkDef = needsDefCheck(v);
        if (v.cell()) {
            int cellType = ts.cellTypeFor(v.primitiveKind());
            int cellLocal = ctx.newLocal("cell_" + v.name(), WasmValType.refNull(cellType));
            if (v.primitiveKind() == PrimitiveKind.NONE) {
                c.globalGet(rt.gUndefined);
            } else {
                ts.emitDefaultValue(c, ts.valTypeFor(v.primitiveKind()));
                c.i32Const(checkDef ? 0 : 1);
            }
            c.structNew(cellType).localSet(cellLocal);
            ctx.vars.put(
                    v.id(),
                    new WasmFuncEmitter.VarSlot(
                            cellLocal, v.primitiveKind(), true, cellType, false, -1));
        } else {
            WasmValType vt = ts.valTypeFor(v.primitiveKind());
            int local = ctx.newLocal("v_" + v.name(), vt);
            int defLocal = -1;
            if (v.primitiveKind() == PrimitiveKind.NONE && checkDef) {
                c.globalGet(rt.gUndefined).localSet(local);
            } else if (v.primitiveKind().isPrimitive() && checkDef) {
                defLocal = ctx.newLocal("def_" + v.name(), WasmValType.I32);
                c.i32Const(0).localSet(defLocal);
            }
            ctx.vars.put(
                    v.id(),
                    new WasmFuncEmitter.VarSlot(
                            local, v.primitiveKind(), false, -1, false, -1, defLocal));
        }
    }

    void emitVarRead(HirVar v, int line) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmFuncEmitter.VarSlot slot = ctx.vars.get(v.id());
        if (slot == null) {
            // Fallback: check top-level module global by name
            emitNameRead(v.name(), false, line);
            return;
        }
        WasmCodeBuilder c = ctx.code;
        boolean checkDef = needsDefCheck(v);
        if (slot.isCapturedField()) {
            if (!slot.isCell()) {
                // BIP034-I5 value-captured immutable variable: single struct.get, no cell
                // indirection
                if (ctx.ownerClass != null) {
                    c.localGet(ctx.thisLocal)
                            .refAsNonNull()
                            .structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx());
                } else if (ctx.ifaceCapLocal >= 0) {
                    c.localGet(ctx.ifaceCapLocal)
                            .refAsNonNull()
                            .structGet(ctx.ifaceCapStructType, slot.captureFieldIdx());
                } else {
                    c.localGet(ctx.envLocal)
                            .structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx());
                }
                checkDef = false;
            } else if (ctx.ownerClass != null) {
                if (slot.kind().isPrimitive() && checkDef) {
                    c.localGet(ctx.thisLocal)
                            .refAsNonNull()
                            .structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                            .refAsNonNull()
                            .structGet(slot.cellTypeIdx(), 1)
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .if_();
                    rt.emitStringLit(c, "Name used before definition : " + v.name());
                    c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
                }
                c.localGet(ctx.thisLocal)
                        .refAsNonNull()
                        .structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                        .refAsNonNull()
                        .structGet(slot.cellTypeIdx(), 0);
            } else if (ctx.ifaceCapLocal >= 0) {
                if (slot.kind().isPrimitive() && checkDef) {
                    c.localGet(ctx.ifaceCapLocal)
                            .refAsNonNull()
                            .structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 1)
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .if_();
                    rt.emitStringLit(c, "Name used before definition : " + v.name());
                    c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
                }
                c.localGet(ctx.ifaceCapLocal)
                        .refAsNonNull()
                        .structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                        .structGet(slot.cellTypeIdx(), 0);
            } else {
                if (slot.kind().isPrimitive() && checkDef) {
                    c.localGet(ctx.envLocal)
                            .structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 1)
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .if_();
                    rt.emitStringLit(c, "Name used before definition : " + v.name());
                    c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
                }
                c.localGet(ctx.envLocal)
                        .structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                        .structGet(slot.cellTypeIdx(), 0);
            }
        } else if (slot.isCell()) {
            if (slot.kind().isPrimitive() && checkDef) {
                c.localGet(slot.localIdx())
                        .structGet(slot.cellTypeIdx(), 1)
                        .op(WasmInstr.SimpleOp.I32_EQZ)
                        .if_();
                rt.emitStringLit(c, "Name used before definition : " + v.name());
                c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
            }
            c.localGet(slot.localIdx()).structGet(slot.cellTypeIdx(), 0);
        } else {
            if (slot.defLocal() >= 0) {
                c.localGet(slot.defLocal()).op(WasmInstr.SimpleOp.I32_EQZ).if_();
                rt.emitStringLit(c, "Name used before definition : " + v.name());
                c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
            }
            c.localGet(slot.localIdx());
        }
        if (slot.kind() == PrimitiveKind.NONE && checkDef) {
            emitUndefinedCheck(v.name(), line);
        }
        ctx.stackKind = slot.kind();
    }

    void emitThisRef(HirExpr.ThisRef t) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        if (t.var() != null && ctx.vars.containsKey(t.var().id())) {
            emitVarRead(t.var(), t.meta().line());
            return;
        }
        if (ctx.thisLocal >= 0) {
            c.localGet(ctx.thisLocal);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        rt.emitStringLit(c, "this used outside of class or interface");
        c.i32Const(t.meta().line()).call(rt.fnThrowMsg).unreachable();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitUndefinedCheck(String name, int line) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        int tmp = ctx.newLocal("$undefChk", ts.anyRef());
        c.localTee(tmp).globalGet(rt.gUndefined).refEq().if_();
        rt.emitStringLit(c, "Name used before definition : " + name);
        c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(tmp);
    }

    void emitNameRead(String name, boolean definitelyDefined, int line) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        // 1. Check shadowed namespace-scoped block locals (innermost first)
        for (int i = ctx.nsScopes.size() - 1; i >= 0; i--) {
            Integer local = ctx.nsScopes.get(i).get(name);
            if (local != null) {
                c.localGet(local);
                emitUndefinedCheck(name, line);
                ctx.stackKind = PrimitiveKind.NONE;
                return;
            }
        }
        // 2. Check module top-level globals (with BIP034 definite-init, constant folding, and
        // unboxed primitive globals)
        if (definitelyDefined) {
            if (ctx.modInfo.immutableTopLevelConsts.containsKey(name)) {
                fn.emitConstValue(ctx.modInfo.immutableTopLevelConsts.get(name), line);
                return;
            }
            WasmProgramLinker.PrimitiveGlobal pg =
                    ctx.modInfo.immutableTopLevelPrimGlobals.get(name);
            if (pg != null) {
                c.globalGet(pg.globalIdx());
                ctx.stackKind = pg.kind();
                return;
            }
        }
        Integer g = ctx.modInfo.topLevelGlobals.get(name);
        if (g != null) {
            c.globalGet(g);
            if (!definitelyDefined) {
                emitUndefinedCheck(name, line);
            }
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        // 3. Built-ins
        if ("STDOUT".equals(name)) {
            c.globalGet(rt.gStdout);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        if ("STDERR".equals(name)) {
            c.globalGet(rt.gStderr);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        if ("args".equals(name)) {
            c.globalGet(rt.gArgs).refAsNonNull();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        if (WasmFuncEmitter.isBuiltinName(name)) {
            int bg = ops.builtinCallableGlobal(name);
            c.globalGet(bg).refAsNonNull();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        rt.emitStringLit(c, "No such name : " + name);
        c.i32Const(line).call(rt.fnThrowMsg).unreachable();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void emitRecurseRef(int line) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        if (ctx.funcInfo == null) {
            rt.emitStringLit(c, "No such name : $recurse");
            c.i32Const(line).call(rt.fnThrowMsg).unreachable();
        } else if (ctx.funcInfo.hasEnvParam) {
            c.localGet(ctx.envLocal);
        } else if (ctx.funcInfo.singletonGlobalIdx >= 0) {
            c.globalGet(ctx.funcInfo.singletonGlobalIdx).refAsNonNull();
        } else {
            rt.emitStringLit(c, "No such name : $recurse");
            c.i32Const(line).call(rt.fnThrowMsg).unreachable();
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void emitDef(HirExpr.Def d) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        if (d.target() != null) {
            switch (d.target().kind()) {
                case REQUIRED, DEFAULT -> {
                    c.refNull(ts.anyType);
                    ctx.stackKind = PrimitiveKind.NONE;
                    return;
                }
                case MEMBER -> {
                    WasmObjectSystem.ClassInfo ci = ctx.ownerClass;
                    int mIdx = d.target().index();
                    if (ci == null
                            || mIdx < 0
                            || mIdx >= ci.memberToStructField.length
                            || ci.memberToStructField[mIdx] < 0) {
                        // Method declaration (already compiled as class method): no-op at runtime
                        c.refNull(ts.anyType);
                        ctx.stackKind = PrimitiveKind.NONE;
                        return;
                    }
                    int fi = ci.memberToStructField[mIdx];
                    PrimitiveKind fk = ci.memberKinds[mIdx];
                    if (!ci.fieldMutable[mIdx]) {
                        // Strictly immutable record field already initialized in struct.new
                        c.localGet(ctx.thisLocal).refAsNonNull().structGet(ci.structTypeIdx, fi);
                        ctx.stackKind = fk;
                        return;
                    }
                    if (d.value() != null) {
                        fn.emitAndWiden(d.value());
                        fn.widenStackRank(d.meta().wideningRank(), d.meta().line());
                        fn.convertStack(ctx.stackKind, fk, d.meta().line());
                    } else {
                        ts.emitDefaultValue(c, ts.valTypeFor(fk));
                    }
                    int valTmp = ctx.newLocal("$mDefVal", ts.valTypeFor(fk));
                    c.localTee(valTmp);
                    c.localGet(ctx.thisLocal)
                            .refAsNonNull()
                            .localGet(valTmp)
                            .structSet(ci.structTypeIdx, fi);
                    if (ci.defMaskFieldIdx >= 0
                            && mIdx < ci.hir.primDefBits().length
                            && ci.hir.primDefBits()[mIdx] >= 0) {
                        int bit = 1 << ci.hir.primDefBits()[mIdx];
                        c.localGet(ctx.thisLocal)
                                .refAsNonNull()
                                .localGet(ctx.thisLocal)
                                .refAsNonNull()
                                .structGet(ci.structTypeIdx, ci.defMaskFieldIdx)
                                .i32Const(bit)
                                .op(WasmInstr.SimpleOp.I32_OR)
                                .structSet(ci.structTypeIdx, ci.defMaskFieldIdx);
                    }
                    ctx.stackKind = fk;
                    return;
                }
                case STATIC -> {
                    if (d.value() != null) {
                        fn.emitBoxed(d.value());
                        fn.widenStackRank(d.meta().wideningRank(), d.meta().line());
                    } else {
                        c.refNull(ts.anyType);
                    }
                    int stTmp = ctx.newLocal("$stDefVal", ts.anyRef());
                    c.localTee(stTmp);
                    if (ctx.staticArrayLocal >= 0 && d.target().index() >= 0) {
                        c.localGet(ctx.staticArrayLocal)
                                .refAsNonNull()
                                .i32Const(d.target().index())
                                .localGet(stTmp)
                                .arraySet(ts.anyArrType);
                    }
                    if (ctx.staticOwnerClass != null
                            && d.target().index() >= 0
                            && d.target().index()
                                    < ctx.staticOwnerClass.staticFieldGlobals.length) {
                        c.localGet(stTmp)
                                .globalSet(
                                        ctx.staticOwnerClass
                                                .staticFieldGlobals[d.target().index()]);
                    }
                    ctx.stackKind = PrimitiveKind.NONE;
                    return;
                }
                case LEXICAL -> {
                    /* fall through below */
                }
            }
        }
        if (d.var() != null) {
            allocateLocalVar(d.var());
            WasmFuncEmitter.VarSlot slot = ctx.vars.get(d.var().id());
            if (d.value() != null) {
                fn.emitAndWiden(d.value());
                fn.widenStackRank(d.meta().wideningRank(), d.meta().line());
                fn.convertStack(ctx.stackKind, slot.kind(), d.meta().line());
            } else {
                if (slot.kind() == PrimitiveKind.NONE) {
                    c.refNull(ts.anyType);
                } else {
                    ts.emitDefaultValue(c, ts.valTypeFor(slot.kind()));
                }
            }
            storeVarSlotAndKeep(slot, true);
            if (ctx.ownerClass != null && ctx.funcInfo == null && ctx.thisLocal >= 0) {
                Integer hcField = ctx.ownerClass.valueHiddenVarToField.get(d.var().id());
                if (hcField != null) {
                    int hvTmp = ctx.newLocal("$hcVal", ts.valTypeFor(slot.kind()));
                    c.localTee(hvTmp);
                    c.localGet(ctx.thisLocal)
                            .refAsNonNull()
                            .localGet(hvTmp)
                            .structSet(ctx.ownerClass.structTypeIdx, hcField);
                }
            }
            ctx.stackKind = slot.kind();
            return;
        }
        // Namespace-scoped def (top-level global or nested namespace block local)
        if (ctx.nsScopes.isEmpty() && d.value() != null) {
            WasmProgramLinker.PrimitiveGlobal pg =
                    ctx.modInfo.immutableTopLevelPrimGlobals.get(d.name());
            if (pg != null && pg.ownerModIndex() == ctx.modInfo.index) {
                fn.emitAs(d.value(), pg.kind());
                int pTmp = ctx.newLocal("$defPrimTmp", ts.valTypeFor(pg.kind()));
                c.localTee(pTmp).globalSet(pg.globalIdx());
                Integer g = ctx.modInfo.topLevelGlobals.get(d.name());
                if (g != null) {
                    c.localGet(pTmp).call(rt.boxFuncFor(pg.kind())).globalSet(g);
                }
                c.localGet(pTmp);
                ctx.stackKind = pg.kind();
                return;
            }
        }
        if (d.value() != null) {
            fn.emitBoxed(d.value());
            fn.widenStackRank(d.meta().wideningRank(), d.meta().line());
        } else {
            c.refNull(ts.anyType);
        }
        int tmp = ctx.newLocal("$defTmp", ts.anyRef());
        c.localTee(tmp);
        if (!ctx.nsScopes.isEmpty()) {
            Map<String, Integer> scope = ctx.nsScopes.get(ctx.nsScopes.size() - 1);
            Integer local = scope.get(d.name());
            if (local == null) {
                local = ctx.newLocal("ns_" + d.name(), ts.anyRef());
                scope.put(d.name(), local);
            }
            c.localSet(local);
        } else {
            Integer g = ctx.modInfo.topLevelGlobals.get(d.name());
            if (g != null) {
                c.globalSet(g);
            } else {
                c.drop();
            }
        }
        c.localGet(tmp);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void storeVarSlotAndKeep(WasmFuncEmitter.VarSlot slot, boolean markDefined) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        int tmp = ctx.newLocal("$stTmp", ts.valTypeFor(slot.kind()));
        if (slot.isCapturedField()) {
            if (ctx.ownerClass != null) {
                c.localSet(tmp)
                        .localGet(ctx.thisLocal)
                        .refAsNonNull()
                        .structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                        .refAsNonNull()
                        .localGet(tmp)
                        .structSet(slot.cellTypeIdx(), 0);
                if (markDefined && slot.kind().isPrimitive()) {
                    c.localGet(ctx.thisLocal)
                            .refAsNonNull()
                            .structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                            .refAsNonNull()
                            .i32Const(1)
                            .structSet(slot.cellTypeIdx(), 1);
                }
                c.localGet(tmp);
            } else if (ctx.ifaceCapLocal >= 0) {
                c.localSet(tmp)
                        .localGet(ctx.ifaceCapLocal)
                        .refAsNonNull()
                        .structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                        .localGet(tmp)
                        .structSet(slot.cellTypeIdx(), 0);
                if (markDefined && slot.kind().isPrimitive()) {
                    c.localGet(ctx.ifaceCapLocal)
                            .refAsNonNull()
                            .structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                            .i32Const(1)
                            .structSet(slot.cellTypeIdx(), 1);
                }
                c.localGet(tmp);
            } else {
                c.localSet(tmp)
                        .localGet(ctx.envLocal)
                        .structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                        .localGet(tmp)
                        .structSet(slot.cellTypeIdx(), 0);
                if (markDefined && slot.kind().isPrimitive()) {
                    c.localGet(ctx.envLocal)
                            .structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                            .i32Const(1)
                            .structSet(slot.cellTypeIdx(), 1);
                }
                c.localGet(tmp);
            }
        } else if (slot.isCell()) {
            c.localSet(tmp)
                    .localGet(slot.localIdx())
                    .localGet(tmp)
                    .structSet(slot.cellTypeIdx(), 0);
            if (markDefined && slot.kind().isPrimitive()) {
                c.localGet(slot.localIdx()).i32Const(1).structSet(slot.cellTypeIdx(), 1);
            }
            c.localGet(tmp);
        } else {
            c.localTee(slot.localIdx());
            if (markDefined && slot.defLocal() >= 0) {
                c.i32Const(1).localSet(slot.defLocal());
            }
        }
    }

    private void emitCheckSlotDefinedForAssign(HirVar v, WasmFuncEmitter.VarSlot slot, int line) {
        if (!needsDefCheck(v)) {
            return;
        }
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        if (slot.isCapturedField()) {
            if (ctx.ownerClass != null) {
                if (slot.kind().isPrimitive()) {
                    c.localGet(ctx.thisLocal)
                            .refAsNonNull()
                            .structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                            .refAsNonNull()
                            .structGet(slot.cellTypeIdx(), 1)
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .if_();
                } else {
                    c.localGet(ctx.thisLocal)
                            .refAsNonNull()
                            .structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                            .refAsNonNull()
                            .structGet(slot.cellTypeIdx(), 0)
                            .globalGet(rt.gUndefined)
                            .refEq()
                            .if_();
                }
            } else if (ctx.ifaceCapLocal >= 0) {
                if (slot.kind().isPrimitive()) {
                    c.localGet(ctx.ifaceCapLocal)
                            .refAsNonNull()
                            .structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 1)
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .if_();
                } else {
                    c.localGet(ctx.ifaceCapLocal)
                            .refAsNonNull()
                            .structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 0)
                            .globalGet(rt.gUndefined)
                            .refEq()
                            .if_();
                }
            } else {
                if (slot.kind().isPrimitive()) {
                    c.localGet(ctx.envLocal)
                            .structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 1)
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .if_();
                } else {
                    c.localGet(ctx.envLocal)
                            .structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 0)
                            .globalGet(rt.gUndefined)
                            .refEq()
                            .if_();
                }
            }
            rt.emitStringLit(c, "Name used before definition : " + v.name());
            c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        } else if (slot.isCell()) {
            if (slot.kind().isPrimitive()) {
                c.localGet(slot.localIdx())
                        .structGet(slot.cellTypeIdx(), 1)
                        .op(WasmInstr.SimpleOp.I32_EQZ)
                        .if_();
            } else {
                c.localGet(slot.localIdx())
                        .structGet(slot.cellTypeIdx(), 0)
                        .globalGet(rt.gUndefined)
                        .refEq()
                        .if_();
            }
            rt.emitStringLit(c, "Name used before definition : " + v.name());
            c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        } else if (slot.defLocal() >= 0) {
            c.localGet(slot.defLocal()).op(WasmInstr.SimpleOp.I32_EQZ).if_();
            rt.emitStringLit(c, "Name used before definition : " + v.name());
            c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        } else if (slot.kind() == PrimitiveKind.NONE) {
            c.localGet(slot.localIdx()).globalGet(rt.gUndefined).refEq().if_();
            rt.emitStringLit(c, "Name used before definition : " + v.name());
            c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        }
    }

    void emitVarAssign(HirExpr.VarAssign a) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmFuncEmitter.VarSlot slot = ctx.vars.get(a.var().id());
        if (slot == null) {
            emitNameAssign(
                    new HirExpr.NameAssign(
                            a.var().name(),
                            a.operator(),
                            a.value(),
                            a.targetText(),
                            a.valueText(),
                            a.meta()));
            return;
        }
        if (a.operator() == null || "=".equals(a.operator())) {
            fn.emitAs(a.value(), slot.kind());
            emitCheckSlotDefinedForAssign(a.var(), slot, a.meta().line());
            storeVarSlotAndKeep(slot, false);
            ctx.stackKind = slot.kind();
            return;
        }
        // Compound assignment: +, -, *, / (or +=, -=, *=, /=)
        fn.emitCompoundValue(
                slot.kind(),
                () -> emitVarRead(a.var(), a.meta().line()),
                a.operator(),
                a.value(),
                a.targetText(),
                a.valueText(),
                a.meta().line());
        fn.convertStack(ctx.stackKind, slot.kind(), a.meta().line());
        storeVarSlotAndKeep(slot, false);
        ctx.stackKind = slot.kind();
    }

    void emitNameAssign(HirExpr.NameAssign a) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        if (a.operator() == null || "=".equals(a.operator())) {
            fn.emitBoxed(a.value());
        } else {
            fn.emitCompoundValue(
                    PrimitiveKind.NONE,
                    () -> emitNameRead(a.name(), false, a.meta().line()),
                    a.operator(),
                    a.value(),
                    a.targetText(),
                    a.valueText(),
                    a.meta().line());
            fn.convertStack(ctx.stackKind, PrimitiveKind.NONE, a.meta().line());
        }
        int tmp = ctx.newLocal("$asgnTmp", ts.anyRef());
        c.localSet(tmp);
        for (int i = ctx.nsScopes.size() - 1; i >= 0; i--) {
            Integer local = ctx.nsScopes.get(i).get(a.name());
            if (local != null) {
                c.localGet(local).globalGet(rt.gUndefined).refEq().if_();
                rt.emitStringLit(c, "Name used before definition : " + a.name());
                c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable().end();
                c.localGet(tmp).localSet(local).localGet(tmp);
                ctx.stackKind = PrimitiveKind.NONE;
                return;
            }
        }
        Integer g = ctx.modInfo.topLevelGlobals.get(a.name());
        if (g != null) {
            c.globalGet(g).globalGet(rt.gUndefined).refEq().if_();
            rt.emitStringLit(c, "Name used before definition : " + a.name());
            c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(tmp).globalSet(g).localGet(tmp);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        rt.emitStringLit(c, "Undefined variable : " + a.name());
        c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    void emitFunctionRef(HirExpr.FunctionRef f) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmProgramLinker.FuncInfo fi = ctx.modInfo.functions.get(f.functionId());
        WasmCodeBuilder c = ctx.code;
        if (!fi.hasEnvParam) {
            c.globalGet(fi.singletonGlobalIdx).refAsNonNull();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        // Allocate closure struct capturing outer cells
        c.refFunc(fi.genericFuncIdx);
        rt.emitStringLit(
                c, fi.hir.name() != null && !fi.hir.name().isEmpty() ? fi.hir.name() : "<fn>");
        c.i32Const(fi.hir.id());
        for (HirVar cv : fi.hir.capturedVars()) {
            emitCapturePush(cv, f.meta().line());
        }
        c.structNew(fi.closureStructType);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    /**
     * Pushes a captured variable onto the Wasm stack for closure / class / interface construction:
     * a raw value for {@code BIP034-I5} value-captures, or a {@code (ref $Cell_K)} otherwise.
     */
    void emitCapturePush(HirVar cv, int line) {
        WasmFuncEmitter.EmitCtx ctx = fn.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmFuncEmitter.VarSlot slot = ctx.vars.get(cv.id());
        if (slot == null) {
            throw new IllegalStateException("Missing captured variable slot : " + cv);
        }
        if (WasmProgramLinker.isValueCapture(cv)) {
            if (slot.isCapturedField()) {
                if (ctx.ownerClass != null) {
                    c.localGet(ctx.thisLocal)
                            .refAsNonNull()
                            .structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx());
                } else if (ctx.ifaceCapLocal >= 0) {
                    c.localGet(ctx.ifaceCapLocal)
                            .refAsNonNull()
                            .structGet(ctx.ifaceCapStructType, slot.captureFieldIdx());
                } else {
                    c.localGet(ctx.envLocal)
                            .structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx());
                }
                if (slot.isCell()) {
                    c.refAsNonNull().structGet(slot.cellTypeIdx(), 0);
                }
            } else if (slot.isCell()) {
                c.localGet(slot.localIdx()).refAsNonNull().structGet(slot.cellTypeIdx(), 0);
            } else {
                c.localGet(slot.localIdx());
            }
            fn.convertRawValue(slot.kind(), cv.primitiveKind(), line);
            return;
        }
        // Cell capture
        if (slot.isCapturedField()) {
            if (slot.isCell()) {
                if (ctx.ownerClass != null) {
                    c.localGet(ctx.thisLocal)
                            .refAsNonNull()
                            .structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                            .refAsNonNull();
                } else if (ctx.ifaceCapLocal >= 0) {
                    c.localGet(ctx.ifaceCapLocal)
                            .refAsNonNull()
                            .structGet(ctx.ifaceCapStructType, slot.captureFieldIdx());
                } else {
                    c.localGet(ctx.envLocal)
                            .structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx());
                }
            } else {
                if (ctx.ownerClass != null) {
                    c.localGet(ctx.thisLocal)
                            .refAsNonNull()
                            .structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx());
                } else if (ctx.ifaceCapLocal >= 0) {
                    c.localGet(ctx.ifaceCapLocal)
                            .refAsNonNull()
                            .structGet(ctx.ifaceCapStructType, slot.captureFieldIdx());
                } else {
                    c.localGet(ctx.envLocal)
                            .structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx());
                }
                fn.convertRawValue(slot.kind(), cv.primitiveKind(), line);
                if (cv.primitiveKind().isPrimitive()) {
                    c.i32Const(1);
                }
                c.structNew(ts.cellTypeFor(cv.primitiveKind()));
            }
        } else if (slot.isCell()) {
            c.localGet(slot.localIdx()).refAsNonNull();
        } else {
            int cellType = ts.cellTypeFor(cv.primitiveKind());
            c.localGet(slot.localIdx());
            fn.convertRawValue(slot.kind(), cv.primitiveKind(), line);
            if (cv.primitiveKind().isPrimitive()) {
                c.i32Const(1);
            }
            c.structNew(cellType);
        }
    }
}
