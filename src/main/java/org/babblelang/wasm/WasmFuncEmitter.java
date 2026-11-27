package org.babblelang.wasm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;

/**
 * Direct structured HIR-to-Wasm 3.0 emitter for function bodies and module initializers ({@code
 * BIP025-I1}, {@code BIP025-D3}, {@code BIP025-D13}).
 */
public final class WasmFuncEmitter {
    /** Per-function emission state (locals, control stack, current stack PrimitiveKind). */
    public static final class EmitCtx {
        public final WasmProgramLinker.ModuleLinkInfo modInfo;
        public final WasmProgramLinker.FuncInfo funcInfo; // null for module init
        public final WasmModule.FuncDef fn;
        public final WasmCodeBuilder code;
        public final int paramCount;
        public final PrimitiveKind fnReturnKind;
        public final int envLocal; // -1 if no env param
        public final int optMaskLocal; // -1 if no optMask param
        public final int selfFnLocal; // -1 if not allocated
        public int thisLocal = -1; // >= 0 inside class/interface methods & constructors
        public WasmObjectSystem.ClassInfo
                ownerClass; // non-null inside class methods & constructors
        public int ifaceCapLocal = -1; // >= 0 inside capturing interface default methods
        public int ifaceCapStructType = -1;
        public int staticArrayLocal = -1; // >= 0 inside static blocks
        public WasmObjectSystem.ClassInfo staticOwnerClass; // non-null inside class static blocks
        public int currentTopLevelStmtIdx =
                -1; // statement index in module top-level (for sound direct static dispatch)
        public final Map<Integer, VarSlot> vars = new HashMap<>();
        public final List<Map<String, Integer>> nsScopes =
                new ArrayList<>(); // shadowed namespace-scoped locals
        public final List<String> blockStack =
                new ArrayList<>(); // structured Wasm block depth tracker
        public final Deque<LoopFrame> loops = new ArrayDeque<>();
        public final Deque<FinallyFrame> finallies = new ArrayDeque<>();
        public PrimitiveKind stackKind = PrimitiveKind.NONE;

        EmitCtx(
                WasmProgramLinker.ModuleLinkInfo modInfo,
                WasmProgramLinker.FuncInfo funcInfo,
                WasmModule.FuncDef fn,
                int paramCount,
                PrimitiveKind fnReturnKind,
                int envLocal,
                int optMaskLocal) {
            this.modInfo = modInfo;
            this.funcInfo = funcInfo;
            this.fn = fn;
            this.code = fn.code();
            this.paramCount = paramCount;
            this.fnReturnKind = fnReturnKind;
            this.envLocal = envLocal;
            this.optMaskLocal = optMaskLocal;
            this.selfFnLocal = -1;
        }

        public int newLocal(String name, WasmValType type) {
            return fn.addLocal(name, type, paramCount);
        }

        public int pushBlock(String tag) {
            int depth = blockStack.size();
            blockStack.add(tag);
            return depth;
        }

        public void popBlock() {
            blockStack.remove(blockStack.size() - 1);
        }

        public int relDepth(int targetDepth) {
            return blockStack.size() - 1 - targetDepth;
        }
    }

    public record VarSlot(
            int localIdx,
            PrimitiveKind kind,
            boolean isCell,
            int cellTypeIdx,
            boolean isCapturedField,
            int captureFieldIdx,
            int defLocal) {
        public VarSlot(
                int localIdx,
                PrimitiveKind kind,
                boolean isCell,
                int cellTypeIdx,
                boolean isCapturedField,
                int captureFieldIdx) {
            this(localIdx, kind, isCell, cellTypeIdx, isCapturedField, captureFieldIdx, -1);
        }
    }

    public record LoopFrame(int breakDepth, int continueDepth, PrimitiveKind resultKind) {}

    public record FinallyFrame(
            int normalDepth,
            int exitKindLocal,
            int retValLocal,
            int brkValLocal,
            PrimitiveKind brkKind,
            LoopFrame enclosingLoop) {}

    private final WasmProgramLinker linker;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmRuntimeOpsGen ops;
    private final WasmArrayOpsGen arrOps;
    private final WasmCallEmitter calls;
    private final WasmMemberEmitter members;
    private final WasmCoreEmitter core;
    private final WasmMatchEmitter matchEmitter;
    private final WasmVarAndDefEmitter varDefs;
    private final WasmControlAndTypeEmitter controlTypes;
    private EmitCtx ctx;

    public WasmFuncEmitter(WasmProgramLinker linker, WasmCallEmitter calls) {
        this.linker = linker;
        this.ts = linker.types();
        this.rt = linker.runtime();
        this.ops = linker.ops();
        this.arrOps = linker.arrays();
        this.calls = calls;
        this.members = new WasmMemberEmitter(linker, this, calls);
        this.core = new WasmCoreEmitter(linker);
        this.core.setFuncEmitter(this);
        this.matchEmitter = new WasmMatchEmitter(linker, this);
        this.varDefs = new WasmVarAndDefEmitter(linker, this);
        this.controlTypes = new WasmControlAndTypeEmitter(linker, this);
    }

    public EmitCtx ctx() {
        return ctx;
    }

    public boolean isShadowedInNsScope(String name) {
        for (int i = ctx.nsScopes.size() - 1; i >= 0; i--) {
            if (ctx.nsScopes.get(i).containsKey(name)) {
                return true;
            }
        }
        return false;
    }

    public WasmProgramLinker linker() {
        return linker;
    }

    public void setCtx(EmitCtx ctx) {
        this.ctx = ctx;
    }

    public WasmMemberEmitter members() {
        return members;
    }

    public WasmCoreEmitter core() {
        return core;
    }

    public void emitClassConstructorBody(WasmObjectSystem.ClassInfo ci) {
        members.emitClassConstructorBody(ci);
    }

    // ---------------------------------------------------------------- entry points

    public void emitModuleInit(WasmProgramLinker.ModuleLinkInfo modInfo) {
        WasmModule.FuncDef fn = modInfo.initFuncDef;
        EmitCtx prev = this.ctx;
        this.ctx = new EmitCtx(modInfo, null, fn, 0, PrimitiveKind.NONE, -1, -1);
        try {
            WasmCodeBuilder c = ctx.code;
            // Guard: if state != 0 return null (prevents re-running on static re-import / cycles)
            c.globalGet(modInfo.initStateGlobalIdx).if_().refNull(ts.anyType).return_().end();
            c.i32Const(1).globalSet(modInfo.initStateGlobalIdx);

            // Pre-create $ModuleVal shell with empty exports array (so cyclic imports see a valid
            // $ModuleVal)
            String[] exportNames = modInfo.input.hir().exports();
            int expArrLocal = ctx.newLocal("$modExp", WasmValType.refNull(ts.anyArrType));
            c.i32Const(exportNames.length).arrayNewDefault(ts.anyArrType).localSet(expArrLocal);
            c.i32Const(modInfo.index);
            rt.emitStringLit(c, modInfo.input.displayName());
            c.localGet(expArrLocal).refAsNonNull();
            for (String en : exportNames) {
                rt.emitStringLit(c, en);
            }
            c.arrayNewFixed(ts.anyArrType, exportNames.length)
                    .structNew(ts.moduleValType)
                    .globalSet(modInfo.modValGlobalIdx);

            // Initialize all top-level module globals to $G_UNDEFINED
            for (int g : modInfo.topLevelGlobals.values()) {
                c.globalGet(rt.gUndefined).globalSet(g);
            }

            List<HirExpr> stmts = modInfo.input.hir().topLevelStatements();
            int resLocal = ctx.newLocal("$modRes", ts.anyRef());
            int exnLocal = ctx.newLocal("$modExn", WasmValType.EXNREF);
            int doneD = ctx.pushBlock("mod_init_done");
            c.block();
            int catchD = ctx.pushBlock("mod_init_catch");
            c.block(WasmValType.EXNREF);
            int catchRel = ctx.relDepth(catchD);
            ctx.pushBlock("mod_init_tbl");
            c.tryTable(List.of(WasmInstr.CatchClause.catchAllRef(catchRel)));
            if (stmts.isEmpty()) {
                c.refNull(ts.anyType).localSet(resLocal);
            } else {
                for (int i = 0; i < stmts.size() - 1; i++) {
                    ctx.currentTopLevelStmtIdx = i;
                    emitVoid(stmts.get(i));
                }
                ctx.currentTopLevelStmtIdx = stmts.size() - 1;
                emitBoxed(stmts.get(stmts.size() - 1));
                c.localSet(resLocal);
            }
            ctx.popBlock();
            c.end(); // end try_table
            c.br(ctx.relDepth(doneD));
            ctx.popBlock();
            c.end(); // end mod_init_catch -> exnref
            c.localSet(exnLocal);
            c.i32Const(0).globalSet(modInfo.initStateGlobalIdx);
            c.localGet(exnLocal).refAsNonNull().throwRef();
            ctx.popBlock();
            c.end(); // end mod_init_done

            // Populate $ModuleVal.exports array from exported top-level globals
            for (int i = 0; i < exportNames.length; i++) {
                Integer g = modInfo.topLevelGlobals.get(exportNames[i]);
                if (g != null) {
                    c.localGet(expArrLocal)
                            .refAsNonNull()
                            .i32Const(i)
                            .globalGet(g)
                            .arraySet(ts.anyArrType);
                }
            }

            c.i32Const(2).globalSet(modInfo.initStateGlobalIdx);
            c.localGet(resLocal);
        } finally {
            this.ctx = prev;
        }
    }

    public void emitFunction(
            WasmProgramLinker.ModuleLinkInfo modInfo, WasmProgramLinker.FuncInfo fi) {
        HirFunction hf = fi.hir;
        int paramIdx = 0;
        int thisLocal = fi.hasThisParam ? paramIdx++ : -1;
        int envLocal = fi.hasEnvParam ? paramIdx++ : -1;
        int firstParamIdx = paramIdx;
        paramIdx += hf.params().size();
        int optMaskLocal = fi.hasOptMaskParam ? paramIdx++ : -1;

        EmitCtx prev = this.ctx;
        this.ctx =
                new EmitCtx(
                        modInfo,
                        fi,
                        fi.directFuncDef,
                        paramIdx,
                        hf.returnKind(),
                        envLocal,
                        optMaskLocal);
        this.ctx.thisLocal = thisLocal;
        this.ctx.ownerClass = fi.ownerClass;
        this.ctx.currentTopLevelStmtIdx =
                modInfo.funcOwnerTopLevelStmtIdx.getOrDefault(hf.id(), -1);
        try {
            WasmCodeBuilder c = ctx.code;

            // 1. Register captured variables (from $env closure, $this ownerClass, or
            // $this.vtable.ifaceCaps)
            if (fi.ownerClass != null) {
                List<HirVar> clsCaps = fi.ownerClass.hir.capturedVars();
                for (int i = 0; i < clsCaps.size(); i++) {
                    HirVar cv = clsCaps.get(i);
                    boolean valCap = WasmProgramLinker.isValueCapture(cv);
                    int cellType = valCap ? -1 : ts.cellTypeFor(cv.primitiveKind());
                    ctx.vars.put(
                            cv.id(),
                            new VarSlot(
                                    -1,
                                    cv.primitiveKind(),
                                    !valCap,
                                    cellType,
                                    true,
                                    fi.ownerClass.captureBaseFieldIdx + i));
                }
                List<HirVar> hidden = fi.ownerClass.hiddenCells;
                for (int i = 0; i < hidden.size(); i++) {
                    HirVar hv = hidden.get(i);
                    boolean valCap = WasmProgramLinker.isValueCapture(hv);
                    int cellType = valCap ? -1 : ts.cellTypeFor(hv.primitiveKind());
                    ctx.vars.put(
                            hv.id(),
                            new VarSlot(
                                    -1,
                                    hv.primitiveKind(),
                                    !valCap,
                                    cellType,
                                    true,
                                    fi.ownerClass.hiddenCellBaseFieldIdx + i));
                }
            } else if (fi.isInterfaceDefault
                    && fi.ownerIface != null
                    && fi.ownerIface.hasCaptures()) {
                WasmObjectSystem.IfaceInfo oi = fi.ownerIface;
                int capLocal = ctx.newLocal("$ifaceCap", WasmValType.refNull(oi.capStructTypeIdx));
                int arrTmp = ctx.newLocal("$icArr", WasmValType.refNull(ts.anyArrType));
                c.localGet(thisLocal)
                        .refAsNonNull()
                        .structGet(ts.objectType, 0)
                        .structGet(ts.vtableType, 13)
                        .localTee(arrTmp)
                        .refIsNull()
                        .if_(WasmValType.refNull(oi.capStructTypeIdx))
                        .globalGet(oi.latestCapEnvGlobalIdx)
                        .else_()
                        .localGet(arrTmp)
                        .refAsNonNull()
                        .i32Const(oi.capSlotIdx)
                        .arrayGet(ts.anyArrType)
                        .refCast(oi.capStructTypeIdx)
                        .end()
                        .localSet(capLocal);
                ctx.ifaceCapLocal = capLocal;
                ctx.ifaceCapStructType = oi.capStructTypeIdx;
                for (int i = 0; i < oi.capturedVars.size(); i++) {
                    HirVar cv = oi.capturedVars.get(i);
                    boolean valCap = WasmProgramLinker.isValueCapture(cv);
                    int cellType = valCap ? -1 : ts.cellTypeFor(cv.primitiveKind());
                    ctx.vars.put(
                            cv.id(),
                            new VarSlot(-1, cv.primitiveKind(), !valCap, cellType, true, i));
                }
            } else {
                for (int i = 0; i < hf.capturedVars().size(); i++) {
                    HirVar cv = hf.capturedVars().get(i);
                    boolean valCap = WasmProgramLinker.isValueCapture(cv);
                    int cellType = valCap ? -1 : ts.cellTypeFor(cv.primitiveKind());
                    ctx.vars.put(
                            cv.id(),
                            new VarSlot(-1, cv.primitiveKind(), !valCap, cellType, true, 3 + i));
                }
            }

            // 2. Evaluate optional parameter defaults and immediately wrap cell-captured parameters
            // so
            //    closures created in subsequent parameter defaults share the same capture cell.
            int optBit = 0;
            for (int i = 0; i < hf.params().size(); i++) {
                HirParam p = hf.params().get(i);
                int rawLocal = firstParamIdx + i;
                int valLocal = rawLocal;
                if (fi.hasOptMaskParam && p.defaultValue() != null) {
                    c.localGet(optMaskLocal)
                            .i32Const(1 << optBit)
                            .op(WasmInstr.SimpleOp.I32_AND)
                            .if_();
                    ctx.pushBlock("opt_def");
                    bindParamSlotIfUnbound(p, rawLocal, false);
                    emitAndWiden(p.defaultValue());
                    widenStackRank(p.wideningRank(), hf.line());
                    convertStack(ctx.stackKind, p.kind(), hf.line());
                    c.localSet(rawLocal);
                    ctx.popBlock();
                    c.end();
                    optBit++;
                } else if (fi.hasBoxedMissingOptParams && p.defaultValue() != null) {
                    int typedLocal =
                            p.kind().isPrimitive()
                                    ? ctx.newLocal("opt_" + p.name(), ts.valTypeFor(p.kind()))
                                    : rawLocal;
                    valLocal = typedLocal;
                    c.localGet(rawLocal).globalGet(rt.gMissing).refEq().if_();
                    ctx.pushBlock("opt_def_box");
                    bindParamSlotIfUnbound(p, typedLocal, false);
                    emitAndWiden(p.defaultValue());
                    widenStackRank(p.wideningRank(), hf.line());
                    convertStack(ctx.stackKind, p.kind(), hf.line());
                    c.localSet(typedLocal);
                    c.else_();
                    if (p.kind().isPrimitive()) {
                        c.localGet(rawLocal)
                                .i32Const(hf.line())
                                .call(rt.unboxFuncFor(p.kind()))
                                .localSet(typedLocal);
                    }
                    ctx.popBlock();
                    c.end();
                }
                bindParamSlotIfUnbound(p, valLocal, false);
                HirVar pv = p.var();
                if (pv != null && pv.cell()) {
                    int cellType = ts.cellTypeFor(pv.primitiveKind());
                    int cellLocal = ctx.newLocal("cell_" + pv.name(), WasmValType.ref(cellType));
                    c.localGet(valLocal);
                    convertRawValue(p.kind(), pv.primitiveKind(), hf.line());
                    if (pv.primitiveKind().isPrimitive()) {
                        c.i32Const(1);
                    }
                    c.structNew(cellType).localSet(cellLocal);
                    ctx.vars.put(
                            pv.id(),
                            new VarSlot(cellLocal, pv.primitiveKind(), true, cellType, false, -1));
                }
            }

            // 4. Allocate locals / cells for all declared variables in this function
            if (thisLocal < 0 && (fi.staticOwnerClass != null || fi.staticOwnerIface != null)) {
                int stThis = ctx.newLocal("$staticThis", ts.anyRef());
                if (fi.staticOwnerClass != null) {
                    c.globalGet(fi.staticOwnerClass.classValGlobalIdx).localSet(stThis);
                } else {
                    c.globalGet(fi.staticOwnerIface.ifaceValGlobalIdx).localSet(stThis);
                }
                thisLocal = stThis;
                ctx.thisLocal = stThis;
            }
            for (HirVar v : hf.declaredVars()) {
                if (thisLocal >= 0
                        && (v.kind() == org.babblelang.analysis.Resolution.Variable.Kind.THIS
                                || "this".equals(v.name()))) {
                    if (v.cell()) {
                        int cellLocal = ctx.newLocal("cell_this", WasmValType.ref(ts.refCellType));
                        c.localGet(thisLocal).structNew(ts.refCellType).localSet(cellLocal);
                        ctx.vars.put(
                                v.id(),
                                new VarSlot(
                                        cellLocal,
                                        PrimitiveKind.NONE,
                                        true,
                                        ts.refCellType,
                                        false,
                                        -1));
                    } else {
                        ctx.vars.put(
                                v.id(),
                                new VarSlot(thisLocal, PrimitiveKind.NONE, false, -1, false, -1));
                    }
                    continue;
                }
                allocateLocalVar(v);
            }

            // 5. Emit body producing fnReturnKind
            emitStmtList(hf.body(), hf.returnKind());
        } finally {
            this.ctx = prev;
        }
    }

    private void bindParamSlotIfUnbound(HirParam p, int pLocal, boolean force) {
        HirVar pv = p.var();
        if (pv != null && (force || !ctx.vars.containsKey(pv.id()))) {
            ctx.vars.put(pv.id(), new VarSlot(pLocal, p.kind(), false, -1, false, -1));
        }
    }

    public void allocateLocalVar(HirVar v) {
        varDefs.allocateLocalVar(v);
    }

    // ---------------------------------------------------------------- expression dispatch

    public void emitExpr(HirExpr expr) {
        ctx.stackKind = PrimitiveKind.NONE;
        switch (expr) {
            case HirExpr.Const c -> emitConst(c);
            case HirExpr.IntParseFallback f -> emitIntParseFallback(f);
            case HirExpr.CoreExceptionRef r -> emitCoreExceptionRef();
            case HirExpr.CoreCollectionRef r -> core.emitCoreCollectionRef(r);
            case HirExpr.CoreCollectionCall c -> core.emitCoreCollectionCall(c);
            case HirExpr.VarRead v -> varDefs.emitVarRead(v.var(), v.meta().line());
            case HirExpr.NameRead n ->
                    varDefs.emitNameRead(n.name(), n.definitelyDefined(), n.meta().line());
            case HirExpr.ThisRef t -> varDefs.emitThisRef(t);
            case HirExpr.RecurseRef r -> varDefs.emitRecurseRef(r.meta().line());
            case HirExpr.Def d -> varDefs.emitDef(d);
            case HirExpr.VarAssign a -> varDefs.emitVarAssign(a);
            case HirExpr.NameAssign a -> varDefs.emitNameAssign(a);
            case HirExpr.ThisFieldGet g -> members.emitThisFieldGet(g);
            case HirExpr.ThisFieldInit a ->
                    members.emitThisFieldAssign(
                            new HirExpr.ThisFieldAssign(
                                    new HirExpr.ThisRef(null, a.classId(), a.meta()),
                                    a.classId(),
                                    a.memberIndex(),
                                    a.memberName(),
                                    null,
                                    a.value(),
                                    "this." + a.memberName(),
                                    "",
                                    a.meta()));
            case HirExpr.ThisFieldAssign a -> members.emitThisFieldAssign(a);
            case HirExpr.DynamicFieldAssign a -> members.emitDynamicFieldAssign(a);
            case HirExpr.ArrayCreate ac -> arrOps.emitArrayCreate(this, ac);
            case HirExpr.ArrayOp ao -> arrOps.emitArrayOp(this, ao);
            case HirExpr.ModuleExportGet g -> members.emitModuleExportGet(g);
            case HirExpr.DynamicSelect s -> members.emitDynamicSelect(s);
            case HirExpr.JavaFieldGet fg -> core.emitJavaFieldGet(fg);
            case HirExpr.Unary u -> emitUnary(u);
            case HirExpr.Binary b -> emitBinary(b);
            case HirExpr.Range r -> core.emitRange(r);
            case HirExpr.Logic l -> emitLogic(l);
            case HirExpr.Is i -> controlTypes.emitIs(i);
            case HirExpr.As a -> controlTypes.emitAsExpr(a);
            case HirExpr.NonNull n -> controlTypes.emitNonNull(n);
            case HirExpr.Block b -> controlTypes.emitBlock(b);
            case HirExpr.If i -> controlTypes.emitIf(i);
            case HirExpr.Match m -> matchEmitter.emitMatch(m);
            case HirExpr.While w -> controlTypes.emitWhile(w);
            case HirExpr.For f -> core.emitFor(f);
            case HirExpr.Try t -> controlTypes.emitTry(t);
            case HirExpr.Break b -> controlTypes.emitBreak(b);
            case HirExpr.Continue c -> controlTypes.emitContinue();
            case HirExpr.Return r -> controlTypes.emitReturn(r);
            case HirExpr.Throw t -> controlTypes.emitThrow(t);
            case HirExpr.FunctionRef f -> varDefs.emitFunctionRef(f);
            case HirExpr.ClassExpr c -> members.emitClassExpr(c);
            case HirExpr.InterfaceExpr i -> members.emitInterfaceExpr(i);
            case HirExpr.RecordNew r -> members.emitRecordNew(r);
            case HirExpr.TypeApply t -> emitBoxed(t.inner());
            case HirExpr.Move m -> emitAndWiden(m.operand());
            case HirExpr.Freeze f -> emitAndWiden(f.operand());
            case HirExpr.Narrow n -> emitAndWiden(n.operand());
            case HirExpr.Convert cv -> emitAs(cv.operand(), cv.targetKind());
            case HirExpr.Widen w -> {
                emitAndWiden(w.operand());
                widenStackRank(w.wideningRank(), w.meta().line());
            }
            case HirExpr.SelfRecurseCall c -> calls.emitSelfRecurseCall(c);
            case HirExpr.SelfTailCall c -> calls.emitSelfTailCall(c);
            case HirExpr.DirectBabbleCall c -> calls.emitDirectBabbleCall(c);
            case HirExpr.DirectThisMethodCall c -> members.emitDirectThisMethodCall(c);
            case HirExpr.InterfaceCall c -> members.emitInterfaceCall(c);
            case HirExpr.SuperCall c -> members.emitSuperCall(c);
            case HirExpr.SafeCall c -> members.emitSafeCall(c);
            case HirExpr.DynamicCall c -> calls.emitDynamicCall(c);
            case HirExpr.MakeOverloadSet m -> calls.emitMakeOverloadSet(m);
            case HirExpr.OverloadSelect s -> calls.emitOverloadSelect(s);
            case HirExpr.OverloadDispatch d -> calls.emitOverloadDispatch(d);
            case HirExpr.CoreMethodCall c -> core.emitCoreMethodCall(c);
            case HirExpr.JavaMethodCall j -> core.emitPortableJavaMethodCall(j);
            case HirExpr.JavaConstructorCall j -> calls.emitPortableJavaConstructorCall(j);
            case HirExpr.SpreadAdapt s -> emitSpreadAdapt(s);
            default -> {
                rt.emitStringLit(
                        ctx.code,
                        "Not yet implemented in Wasm backend : " + expr.getClass().getSimpleName());
                ctx.code.i32Const(expr.meta().line()).call(rt.fnThrowMsg).unreachable();
                ctx.stackKind = PrimitiveKind.NONE;
            }
        }
    }

    public void emitAndWiden(HirExpr expr) {
        emitExpr(expr);
        widenStackRank(expr.meta().wideningRank(), expr.meta().line());
    }

    public void emitAs(HirExpr expr, PrimitiveKind desired) {
        emitAndWiden(expr);
        convertStack(ctx.stackKind, desired, expr.meta().line());
    }

    public void emitBoxed(HirExpr expr) {
        emitAs(expr, PrimitiveKind.NONE);
    }

    public void emitVoid(HirExpr expr) {
        emitExpr(expr);
        ctx.code.drop();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    public void emitStmtList(List<HirExpr> stmts, PrimitiveKind desired) {
        if (stmts.isEmpty()) {
            ts.emitDefaultValue(ctx.code, ts.valTypeFor(desired));
            ctx.stackKind = desired;
            return;
        }
        for (int i = 0; i < stmts.size() - 1; i++) {
            emitVoid(stmts.get(i));
        }
        emitAs(stmts.get(stmts.size() - 1), desired);
    }

    // ---------------------------------------------------------------- stack conversions & widening

    public void convertStack(PrimitiveKind from, PrimitiveKind to, int line) {
        if (from == to) {
            return;
        }
        convertRawValue(from, to, line);
        ctx.stackKind = to;
    }

    public void convertRawValue(PrimitiveKind from, PrimitiveKind to, int line) {
        if (from == to) {
            return;
        }
        WasmCodeBuilder c = ctx.code;
        if (to == PrimitiveKind.NONE) {
            c.call(rt.boxFuncFor(from));
            return;
        }
        if (from == PrimitiveKind.NONE) {
            c.i32Const(line).call(rt.unboxFuncFor(to));
            return;
        }
        // Primitive-to-primitive widening
        if (emitPrimitiveWidening(c, from, to)) {
            return;
        }
        // Fallback: box then unbox
        c.call(rt.boxFuncFor(from)).i32Const(line).call(rt.unboxFuncFor(to));
    }

    public static boolean emitPrimitiveWidening(
            WasmCodeBuilder c, PrimitiveKind from, PrimitiveKind to) {
        if (from == to) {
            return true;
        }
        int fr = from.numericRank();
        int tr = to.numericRank();
        if (fr < 0 || tr < 0 || fr >= tr) {
            return false;
        }
        if (tr <= 2) {
            return true; // byte/short -> short/int are all sign-extended i32
        }
        if (tr == 3) {
            c.op(WasmInstr.SimpleOp.I64_EXTEND_I32_S);
            return true;
        }
        if (tr == 4) {
            if (fr <= 2) {
                c.op(WasmInstr.SimpleOp.F32_CONVERT_I32_S);
            } else {
                c.op(WasmInstr.SimpleOp.F32_CONVERT_I64_S);
            }
            return true;
        }
        // tr == 5 (double)
        if (fr <= 2) {
            c.op(WasmInstr.SimpleOp.F64_CONVERT_I32_S);
        } else if (fr == 3) {
            c.op(WasmInstr.SimpleOp.F64_CONVERT_I64_S);
        } else {
            c.op(WasmInstr.SimpleOp.F64_PROMOTE_F32);
        }
        return true;
    }

    public void widenStackRank(int rank, int line) {
        if (rank < 0) {
            return;
        }
        PrimitiveKind target =
                switch (rank) {
                    case 1 -> PrimitiveKind.SHORT;
                    case 2 -> PrimitiveKind.INT;
                    case 3 -> PrimitiveKind.LONG;
                    case 4 -> PrimitiveKind.FLOAT;
                    case 5 -> PrimitiveKind.DOUBLE;
                    default -> PrimitiveKind.NONE;
                };
        if (ctx.stackKind.isPrimitive() && target != PrimitiveKind.NONE) {
            convertStack(ctx.stackKind, target, line);
            return;
        }
        if (ctx.stackKind == PrimitiveKind.NONE) {
            ctx.code.i32Const(rank).call(rt.fnWidenNumeric);
        }
    }

    // ---------------------------------------------------------------- literals

    private void emitConst(HirExpr.Const c) {
        emitConstValue(c.value(), c.meta().line());
    }

    public void emitConstValue(Object v, int line) {
        WasmCodeBuilder b = ctx.code;
        if (v == null) {
            b.refNull(ts.anyType);
            ctx.stackKind = PrimitiveKind.NONE;
        } else if (v instanceof Integer iv) {
            b.i32Const(iv);
            ctx.stackKind = PrimitiveKind.INT;
        } else if (v instanceof Long lv) {
            b.i64Const(lv);
            ctx.stackKind = PrimitiveKind.LONG;
        } else if (v instanceof Double dv) {
            b.f64Const(dv);
            ctx.stackKind = PrimitiveKind.DOUBLE;
        } else if (v instanceof Float fv) {
            b.f32Const(fv);
            ctx.stackKind = PrimitiveKind.FLOAT;
        } else if (v instanceof Boolean bv) {
            b.i32Const(bv ? 1 : 0);
            ctx.stackKind = PrimitiveKind.BOOL;
        } else if (v instanceof Character cv) {
            b.i32Const(cv);
            ctx.stackKind = PrimitiveKind.CHAR;
        } else if (v instanceof Byte bv) {
            b.i32Const(bv);
            ctx.stackKind = PrimitiveKind.BYTE;
        } else if (v instanceof Short sv) {
            b.i32Const(sv);
            ctx.stackKind = PrimitiveKind.SHORT;
        } else if (v instanceof String sv) {
            rt.emitStringLit(b, sv);
            ctx.stackKind = PrimitiveKind.NONE;
        } else {
            rt.emitStringLit(b, "Unsupported constant : " + v);
            b.i32Const(line).call(rt.fnThrowMsg).unreachable();
            ctx.stackKind = PrimitiveKind.NONE;
        }
    }

    private void emitIntParseFallback(HirExpr.IntParseFallback f) {
        rt.emitStringLit(ctx.code, "For input string: \"" + f.text() + "\"");
        ctx.code.i32Const(f.meta().line()).call(rt.fnThrowMsg).unreachable();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitCoreExceptionRef() {
        int g = ops.builtinCallableGlobal("exception");
        ctx.code.globalGet(g).refAsNonNull();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- variable & def forwarders

    public void emitVarRead(HirVar v, int line) {
        varDefs.emitVarRead(v, line);
    }

    public static boolean isBuiltinName(String name) {
        return switch (name) {
            case "print",
                    "println",
                    "affiche",
                    "afficherc",
                    "assert",
                    "suppose",
                    "same",
                    "classof",
                    "move",
                    "freeze",
                    "discard",
                    "byte",
                    "short",
                    "int",
                    "long",
                    "float",
                    "double",
                    "bool",
                    "char",
                    "string",
                    "exception" ->
                    true;
            default -> false;
        };
    }

    public void storeVarSlotAndKeep(VarSlot slot) {
        varDefs.storeVarSlotAndKeep(slot, false);
    }

    public void storeVarSlotAndKeep(VarSlot slot, boolean markDefined) {
        varDefs.storeVarSlotAndKeep(slot, markDefined);
    }

    public void emitCompoundValue(
            PrimitiveKind lhsKind,
            Runnable emitLhs,
            String assignOp,
            HirExpr rhs,
            String lhsText,
            String rhsText,
            int line) {
        HirExpr.BinaryOp binOp =
                switch (assignOp) {
                    case "+", "+=" -> HirExpr.BinaryOp.ADD;
                    case "-", "-=" -> HirExpr.BinaryOp.SUB;
                    case "*", "*=" -> HirExpr.BinaryOp.MUL;
                    case "/", "/=" -> HirExpr.BinaryOp.DIV;
                    case "//", "//=" -> HirExpr.BinaryOp.FLOOR_DIV;
                    default ->
                            throw new IllegalArgumentException(
                                    "Unknown compound operator : " + assignOp);
                };
        PrimitiveKind rk = rhs.meta().kind();
        if (lhsKind.numericRank() >= 0 && rk.numericRank() >= 0) {
            boolean integralDiv = binOp == HirExpr.BinaryOp.FLOOR_DIV;
            if (!integralDiv || (lhsKind.numericRank() <= 3 && rk.numericRank() <= 3)) {
                PrimitiveKind target =
                        binOp == HirExpr.BinaryOp.DIV
                                ? ((lhsKind == PrimitiveKind.FLOAT || rk == PrimitiveKind.FLOAT)
                                                && lhsKind != PrimitiveKind.DOUBLE
                                                && rk != PrimitiveKind.DOUBLE
                                                && lhsKind != PrimitiveKind.LONG
                                                && rk != PrimitiveKind.LONG
                                        ? PrimitiveKind.FLOAT
                                        : PrimitiveKind.DOUBLE)
                                : (lhsKind.numericRank() >= rk.numericRank() ? lhsKind : rk);
                if (binOp != HirExpr.BinaryOp.DIV && target.numericRank() < 2) {
                    target = PrimitiveKind.INT;
                }
                emitLhs.run();
                convertStack(ctx.stackKind, target, line);
                emitPrimBinaryRhsAndOp(binOp, target, rhs, line);
                ctx.stackKind = target;
                return;
            }
        }
        emitLhs.run();
        convertStack(ctx.stackKind, PrimitiveKind.NONE, line);
        emitBoxed(rhs);
        rt.emitStringLit(ctx.code, lhsText != null ? lhsText : "");
        rt.emitStringLit(ctx.code, rhsText != null ? rhsText : "");
        ctx.code.i32Const(line).call(dynBinOpFunc(binOp));
        ctx.stackKind = PrimitiveKind.NONE;
    }

    // ---------------------------------------------------------------- unary, binary, logic

    private void emitUnary(HirExpr.Unary u) {
        WasmCodeBuilder c = ctx.code;
        if (u.op() == HirExpr.UnaryOp.NOT) {
            emitTruthValue(u.operand());
            c.op(WasmInstr.SimpleOp.I32_EQZ);
            ctx.stackKind = PrimitiveKind.BOOL;
            return;
        }
        // NEGATE
        PrimitiveKind ok = u.operand().meta().kind();
        if (ok.numericRank() >= 0) {
            if (ok.numericRank() <= 2) {
                c.i32Const(0);
                emitAs(u.operand(), PrimitiveKind.INT);
                c.op(WasmInstr.SimpleOp.I32_SUB);
                ctx.stackKind = PrimitiveKind.INT;
            } else if (ok == PrimitiveKind.LONG) {
                c.i64Const(0L);
                emitAs(u.operand(), PrimitiveKind.LONG);
                c.op(WasmInstr.SimpleOp.I64_SUB);
                ctx.stackKind = PrimitiveKind.LONG;
            } else if (ok == PrimitiveKind.FLOAT) {
                emitAs(u.operand(), PrimitiveKind.FLOAT);
                c.op(WasmInstr.SimpleOp.F32_NEG);
                ctx.stackKind = PrimitiveKind.FLOAT;
            } else {
                emitAs(u.operand(), PrimitiveKind.DOUBLE);
                c.op(WasmInstr.SimpleOp.F64_NEG);
                ctx.stackKind = PrimitiveKind.DOUBLE;
            }
            return;
        }
        emitBoxed(u.operand());
        rt.emitStringLit(c, u.operandText() != null ? u.operandText() : "");
        c.i32Const(u.meta().line()).call(ops.fnDynNeg);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitBinary(HirExpr.Binary b) {
        WasmCodeBuilder c = ctx.code;
        PrimitiveKind lk = b.left().meta().kind();
        PrimitiveKind rk = b.right().meta().kind();

        if (b.op().isComparison()) {
            emitComparison(b, lk, rk);
            return;
        }

        // Fast primitive arithmetic
        if (lk.numericRank() >= 0 && rk.numericRank() >= 0) {
            PrimitiveKind target = b.meta().kind();
            if (target.numericRank() >= 0) {
                emitAs(b.left(), target);
                emitPrimBinaryRhsAndOp(b.op(), target, b.right(), b.meta().line());
                ctx.stackKind = target;
                return;
            }
        }

        // Fast string + anything
        if (b.op() == HirExpr.BinaryOp.ADD && b.left().meta().stringType()) {
            emitBoxed(b.left());
            c.refCast(ts.stringType);
            emitBoxed(b.right());
            c.call(rt.fnFormat).call(rt.fnStrConcat);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }

        // Dynamic boxed arithmetic (handles numbers, strings, and Babble operator methods)
        emitBoxed(b.left());
        emitBoxed(b.right());
        rt.emitStringLit(c, b.leftText() != null ? b.leftText() : "");
        rt.emitStringLit(c, b.rightText() != null ? b.rightText() : "");
        c.i32Const(b.meta().line()).call(dynBinOpFunc(b.op()));
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitPrimBinaryRhsAndOp(
            HirExpr.BinaryOp op, PrimitiveKind target, HirExpr rhs, int line) {
        WasmCodeBuilder c = ctx.code;
        if ((op == HirExpr.BinaryOp.MOD || op == HirExpr.BinaryOp.FLOOR_DIV)
                && (target == PrimitiveKind.INT || target == PrimitiveKind.LONG)) {
            if (rhs instanceof HirExpr.Const cst && cst.value() instanceof Number n) {
                long v = target == PrimitiveKind.LONG ? n.longValue() : n.intValue();
                if (v > 0L && (v & (v - 1L)) == 0L) {
                    if (op == HirExpr.BinaryOp.MOD) {
                        if (target == PrimitiveKind.LONG) {
                            c.i64Const(v - 1L).op(WasmInstr.SimpleOp.I64_AND);
                        } else {
                            c.i32Const((int) (v - 1L)).op(WasmInstr.SimpleOp.I32_AND);
                        }
                    } else {
                        int shift = Long.numberOfTrailingZeros(v);
                        if (target == PrimitiveKind.LONG) {
                            c.i64Const(shift).op(WasmInstr.SimpleOp.I64_SHR_S);
                        } else {
                            c.i32Const(shift).op(WasmInstr.SimpleOp.I32_SHR_S);
                        }
                    }
                    return;
                }
            }
            emitAs(rhs, target);
            c.i32Const(line);
            if (target == PrimitiveKind.LONG) {
                c.call(op == HirExpr.BinaryOp.MOD ? ops.fnFloorModLong : ops.fnFloorDivLong);
            } else {
                c.call(op == HirExpr.BinaryOp.MOD ? ops.fnFloorModInt : ops.fnFloorDivInt);
            }
            return;
        }
        emitAs(rhs, target);
        emitPrimArithmeticOp(op, target);
    }

    private void emitPrimArithmeticOp(HirExpr.BinaryOp op, PrimitiveKind target) {
        WasmCodeBuilder c = ctx.code;
        switch (target) {
            case BYTE, SHORT, INT ->
                    c.op(
                            switch (op) {
                                case ADD -> WasmInstr.SimpleOp.I32_ADD;
                                case SUB -> WasmInstr.SimpleOp.I32_SUB;
                                case MUL -> WasmInstr.SimpleOp.I32_MUL;
                                default -> throw new IllegalStateException("Int op : " + op);
                            });
            case LONG ->
                    c.op(
                            switch (op) {
                                case ADD -> WasmInstr.SimpleOp.I64_ADD;
                                case SUB -> WasmInstr.SimpleOp.I64_SUB;
                                case MUL -> WasmInstr.SimpleOp.I64_MUL;
                                default -> throw new IllegalStateException("Long op : " + op);
                            });
            case FLOAT ->
                    c.op(
                            switch (op) {
                                case ADD -> WasmInstr.SimpleOp.F32_ADD;
                                case SUB -> WasmInstr.SimpleOp.F32_SUB;
                                case MUL -> WasmInstr.SimpleOp.F32_MUL;
                                case DIV -> WasmInstr.SimpleOp.F32_DIV;
                                default -> throw new IllegalStateException("Float op : " + op);
                            });
            case DOUBLE ->
                    c.op(
                            switch (op) {
                                case ADD -> WasmInstr.SimpleOp.F64_ADD;
                                case SUB -> WasmInstr.SimpleOp.F64_SUB;
                                case MUL -> WasmInstr.SimpleOp.F64_MUL;
                                case DIV -> WasmInstr.SimpleOp.F64_DIV;
                                default -> throw new IllegalStateException("Double op : " + op);
                            });
            default -> throw new IllegalStateException("Non-numeric target : " + target);
        }
    }

    private int dynBinOpFunc(HirExpr.BinaryOp op) {
        return switch (op) {
            case ADD -> ops.fnDynAdd;
            case SUB -> ops.fnDynSub;
            case MUL -> ops.fnDynMul;
            case DIV -> ops.fnDynDiv;
            case FLOOR_DIV -> ops.fnDynFloorDiv;
            case MOD -> ops.fnDynMod;
            default -> throw new IllegalStateException("Not arithmetic : " + op);
        };
    }

    private void emitComparison(HirExpr.Binary b, PrimitiveKind lk, PrimitiveKind rk) {
        WasmCodeBuilder c = ctx.code;
        if ((lk == PrimitiveKind.BOOL && rk == PrimitiveKind.BOOL)
                || (lk == PrimitiveKind.CHAR && rk == PrimitiveKind.CHAR)) {
            emitAs(b.left(), lk);
            emitAs(b.right(), rk);
            c.op(i32CmpOp(b.op()));
            ctx.stackKind = PrimitiveKind.BOOL;
            return;
        }
        if (lk.numericRank() >= 0 && rk.numericRank() >= 0) {
            PrimitiveKind common = lk.numericRank() >= rk.numericRank() ? lk : rk;
            if (common.numericRank() <= 2) {
                emitAs(b.left(), PrimitiveKind.INT);
                emitAs(b.right(), PrimitiveKind.INT);
                c.op(i32CmpOp(b.op()));
            } else if (common == PrimitiveKind.LONG) {
                emitAs(b.left(), PrimitiveKind.LONG);
                emitAs(b.right(), PrimitiveKind.LONG);
                c.op(i64CmpOp(b.op()));
            } else if (common == PrimitiveKind.FLOAT) {
                emitAs(b.left(), PrimitiveKind.FLOAT);
                emitAs(b.right(), PrimitiveKind.FLOAT);
                c.call(rt.fnF32Compare).i32Const(0).op(i32CmpOp(b.op()));
            } else {
                emitAs(b.left(), PrimitiveKind.DOUBLE);
                emitAs(b.right(), PrimitiveKind.DOUBLE);
                c.call(rt.fnF64Compare).i32Const(0).op(i32CmpOp(b.op()));
            }
            ctx.stackKind = PrimitiveKind.BOOL;
            return;
        }
        // Fast path: == null / <> null uses single Wasm `ref.is_null` instruction instead of
        // calling $equals
        if (b.op() == HirExpr.BinaryOp.EQ || b.op() == HirExpr.BinaryOp.NEQ) {
            boolean leftNull = b.left() instanceof HirExpr.Const lc && lc.value() == null;
            boolean rightNull = b.right() instanceof HirExpr.Const rc && rc.value() == null;
            if (leftNull || rightNull) {
                HirExpr other = leftNull ? b.right() : b.left();
                PrimitiveKind ok = leftNull ? rk : lk;
                if (ok.isPrimitive()) {
                    emitVoid(other);
                    c.i32Const(b.op() == HirExpr.BinaryOp.EQ ? 0 : 1);
                } else {
                    emitBoxed(other);
                    c.refIsNull();
                    if (b.op() == HirExpr.BinaryOp.NEQ) {
                        c.op(WasmInstr.SimpleOp.I32_EQZ);
                    }
                }
                ctx.stackKind = PrimitiveKind.BOOL;
                return;
            }
        }
        // Boxed comparison
        emitBoxed(b.left());
        emitBoxed(b.right());
        if (b.op() == HirExpr.BinaryOp.EQ) {
            c.call(rt.fnEquals);
        } else if (b.op() == HirExpr.BinaryOp.NEQ) {
            c.call(rt.fnEquals).op(WasmInstr.SimpleOp.I32_EQZ);
        } else {
            rt.emitStringLit(c, b.leftText() != null ? b.leftText() : "");
            rt.emitStringLit(c, b.rightText() != null ? b.rightText() : "");
            c.i32Const(b.meta().line()).call(rt.fnCompare).i32Const(0).op(i32CmpOp(b.op()));
        }
        ctx.stackKind = PrimitiveKind.BOOL;
    }

    private static WasmInstr.SimpleOp i32CmpOp(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> WasmInstr.SimpleOp.I32_LT_S;
            case LTE -> WasmInstr.SimpleOp.I32_LE_S;
            case EQ -> WasmInstr.SimpleOp.I32_EQ;
            case NEQ -> WasmInstr.SimpleOp.I32_NE;
            case GTE -> WasmInstr.SimpleOp.I32_GE_S;
            case GT -> WasmInstr.SimpleOp.I32_GT_S;
            default -> throw new IllegalStateException();
        };
    }

    private static WasmInstr.SimpleOp i64CmpOp(HirExpr.BinaryOp op) {
        return switch (op) {
            case LT -> WasmInstr.SimpleOp.I64_LT_S;
            case LTE -> WasmInstr.SimpleOp.I64_LE_S;
            case EQ -> WasmInstr.SimpleOp.I64_EQ;
            case NEQ -> WasmInstr.SimpleOp.I64_NE;
            case GTE -> WasmInstr.SimpleOp.I64_GE_S;
            case GT -> WasmInstr.SimpleOp.I64_GT_S;
            default -> throw new IllegalStateException();
        };
    }

    private void emitLogic(HirExpr.Logic l) {
        WasmCodeBuilder c = ctx.code;
        emitTruthValue(l.left());
        c.if_(WasmValType.I32);
        ctx.pushBlock("logic");
        if (l.op() == HirExpr.LogicOp.AND) {
            emitTruthValue(l.right());
        } else {
            c.i32Const(1);
        }
        c.else_();
        if (l.op() == HirExpr.LogicOp.AND) {
            c.i32Const(0);
        } else {
            emitTruthValue(l.right());
        }
        ctx.popBlock();
        c.end();
        ctx.stackKind = PrimitiveKind.BOOL;
    }

    public void emitTruthValue(HirExpr expr) {
        emitAndWiden(expr);
        emitTruthFromKind(ctx.stackKind);
        ctx.stackKind = PrimitiveKind.BOOL;
    }

    private void emitTruthFromKind(PrimitiveKind kind) {
        WasmCodeBuilder c = ctx.code;
        switch (kind) {
            case BOOL -> {
                /* already 0/1 i32 */
            }
            case BYTE, SHORT, INT, CHAR -> c.i32Const(0).op(WasmInstr.SimpleOp.I32_NE);
            case LONG -> c.i64Const(0L).op(WasmInstr.SimpleOp.I64_NE);
            case FLOAT -> c.f32Const(0.0f).op(WasmInstr.SimpleOp.F32_NE);
            case DOUBLE -> c.f64Const(0.0).op(WasmInstr.SimpleOp.F64_NE);
            case NONE -> c.call(rt.fnTruth);
        }
    }

    // ---------------------------------------------------------------- type-check & capture
    // forwarders

    public void emitDescCheck(
            WasmCodeBuilder c, int valLocal, WasmTypeDesc desc, boolean forAsCast) {
        controlTypes.emitDescCheck(c, valLocal, desc, forAsCast);
    }

    public void emitCapturePush(HirVar cv, int line) {
        varDefs.emitCapturePush(cv, line);
    }

    private void emitSpreadAdapt(HirExpr.SpreadAdapt s) {
        WasmCodeBuilder c = ctx.code;
        int line = s.meta().line();
        LoweringTargets.SpreadAdapt adapt = s.adapt();
        if (adapt == null
                || adapt.kind() == LoweringTargets.SpreadKind.NONE
                || adapt.kind() == LoweringTargets.SpreadKind.AS_IS
                || adapt.kind() == LoweringTargets.SpreadKind.TO_ITERABLE) {
            emitBoxed(s.value());
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        emitBoxed(s.value());
        int src = ctx.newLocal("$spSrc", ts.anyRef());
        c.localSet(src);
        emitSpreadAdaptValue(src, adapt, line);
    }

    /** Adapts an already-evaluated boxed spread value in {@code src} according to {@code adapt}. */
    public void emitSpreadAdaptValue(int src, LoweringTargets.SpreadAdapt adapt, int line) {
        WasmCodeBuilder c = ctx.code;
        if (adapt == null
                || adapt.kind() == LoweringTargets.SpreadKind.NONE
                || adapt.kind() == LoweringTargets.SpreadKind.AS_IS
                || adapt.kind() == LoweringTargets.SpreadKind.TO_ITERABLE) {
            c.localGet(src);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        switch (adapt.kind()) {
            case TO_LIST_COPY -> {
                int l = ctx.newLocal("$spList", WasmValType.ref(ts.listType));
                c.i32Const(4)
                        .call(linker.collections().fnListNew)
                        .localTee(l)
                        .localGet(src)
                        .i32Const(line)
                        .call(linker.collections().fnListAddAll)
                        .drop()
                        .localGet(l);
            }
            case TO_LIST_BORROWED -> {
                c.localGet(src).refTest(ts.listType).if_(ts.anyRef()).localGet(src).else_();
                int l = ctx.newLocal("$spListB", WasmValType.ref(ts.listType));
                c.i32Const(4)
                        .call(linker.collections().fnListNew)
                        .localTee(l)
                        .localGet(src)
                        .i32Const(line)
                        .call(linker.collections().fnListAddAll)
                        .drop()
                        .localGet(l)
                        .end();
            }
            case TO_ARRAY -> {
                int l = ctx.newLocal("$spArrL", WasmValType.ref(ts.listType));
                c.i32Const(4)
                        .call(linker.collections().fnListNew)
                        .localTee(l)
                        .localGet(src)
                        .i32Const(line)
                        .call(linker.collections().fnListAddAll)
                        .drop();
                Class<?> comp = adapt.arrayComponentType();
                PrimitiveKind ek = PrimitiveKind.NONE;
                if (comp == int.class) ek = PrimitiveKind.INT;
                else if (comp == double.class) ek = PrimitiveKind.DOUBLE;
                else if (comp == long.class) ek = PrimitiveKind.LONG;
                else if (comp == float.class) ek = PrimitiveKind.FLOAT;
                else if (comp == byte.class) ek = PrimitiveKind.BYTE;
                else if (comp == short.class) ek = PrimitiveKind.SHORT;
                else if (comp == boolean.class) ek = PrimitiveKind.BOOL;
                else if (comp == char.class) ek = PrimitiveKind.CHAR;
                arrOps.emitArrayFromList(this, l, ek, line);
            }
            default -> c.localGet(src);
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }
}
