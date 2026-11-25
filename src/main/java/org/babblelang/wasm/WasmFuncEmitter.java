package org.babblelang.wasm;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct structured HIR-to-Wasm 3.0 emitter for function bodies and module initializers
 * ({@code BIP025-P1}, {@code BIP025-D3}, {@code BIP025-D13}).
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
        public final int envLocal;        // -1 if no env param
        public final int optMaskLocal;    // -1 if no optMask param
        public final int selfFnLocal;     // -1 if not allocated
        public int thisLocal = -1;        // >= 0 inside class/interface methods & constructors
        public WasmObjectSystem.ClassInfo ownerClass; // non-null inside class methods & constructors
        public int ifaceCapLocal = -1;    // >= 0 inside capturing interface default methods
        public int ifaceCapStructType = -1;
        public int staticArrayLocal = -1; // >= 0 inside static blocks
        public final Map<Integer, VarSlot> vars = new HashMap<>();
        public final List<Map<String, Integer>> nsScopes = new ArrayList<>(); // shadowed namespace-scoped locals
        public final List<String> blockStack = new ArrayList<>(); // structured Wasm block depth tracker
        public final Deque<LoopFrame> loops = new ArrayDeque<>();
        public final Deque<FinallyFrame> finallies = new ArrayDeque<>();
        public PrimitiveKind stackKind = PrimitiveKind.NONE;

        EmitCtx(WasmProgramLinker.ModuleLinkInfo modInfo, WasmProgramLinker.FuncInfo funcInfo,
                WasmModule.FuncDef fn, int paramCount, PrimitiveKind fnReturnKind,
                int envLocal, int optMaskLocal) {
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

    public record VarSlot(int localIdx, PrimitiveKind kind, boolean isCell, int cellTypeIdx,
                          boolean isCapturedField, int captureFieldIdx, int defLocal) {
        public VarSlot(int localIdx, PrimitiveKind kind, boolean isCell, int cellTypeIdx,
                       boolean isCapturedField, int captureFieldIdx) {
            this(localIdx, kind, isCell, cellTypeIdx, isCapturedField, captureFieldIdx, -1);
        }
    }

    public record LoopFrame(int breakDepth, int continueDepth, PrimitiveKind resultKind) {
    }

    public record FinallyFrame(int normalDepth, int exitKindLocal, int retValLocal, int brkValLocal,
                               PrimitiveKind brkKind, LoopFrame enclosingLoop) {
    }

    private final WasmProgramLinker linker;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmRuntimeOpsGen ops;
    private final WasmArrayOpsGen arrOps;
    private final WasmObjectSystem objects;
    private final WasmCallEmitter calls;
    private final WasmMemberEmitter members;
    private final WasmCoreEmitter core;
    private EmitCtx ctx;

    public WasmFuncEmitter(WasmProgramLinker linker, WasmCallEmitter calls) {
        this.linker = linker;
        this.ts = linker.types();
        this.rt = linker.runtime();
        this.ops = linker.ops();
        this.arrOps = linker.arrays();
        this.objects = linker.objects();
        this.calls = calls;
        this.members = new WasmMemberEmitter(linker, this, calls);
        this.core = new WasmCoreEmitter(linker);
        this.core.setFuncEmitter(this);
    }

    public EmitCtx ctx() {
        return ctx;
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
            c.globalGet(modInfo.initStateGlobalIdx).if_()
                    .refNull(ts.anyType).return_()
                    .end();
            c.i32Const(1).globalSet(modInfo.initStateGlobalIdx);

            // Pre-create $ModuleVal shell with empty exports array (so cyclic imports see a valid $ModuleVal)
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
            if (stmts.isEmpty()) {
                c.refNull(ts.anyType).localSet(resLocal);
            } else {
                for (int i = 0; i < stmts.size() - 1; i++) {
                    emitVoid(stmts.get(i));
                }
                emitBoxed(stmts.get(stmts.size() - 1));
                c.localSet(resLocal);
            }

            // Populate $ModuleVal.exports array from exported top-level globals
            for (int i = 0; i < exportNames.length; i++) {
                Integer g = modInfo.topLevelGlobals.get(exportNames[i]);
                if (g != null) {
                    c.localGet(expArrLocal).refAsNonNull().i32Const(i).globalGet(g).arraySet(ts.anyArrType);
                }
            }

            c.i32Const(2).globalSet(modInfo.initStateGlobalIdx);
            c.localGet(resLocal);
        } finally {
            this.ctx = prev;
        }
    }

    public void emitFunction(WasmProgramLinker.ModuleLinkInfo modInfo, WasmProgramLinker.FuncInfo fi) {
        HirFunction hf = fi.hir;
        int paramIdx = 0;
        int thisLocal = fi.hasThisParam ? paramIdx++ : -1;
        int envLocal = fi.hasEnvParam ? paramIdx++ : -1;
        int firstParamIdx = paramIdx;
        paramIdx += hf.params().size();
        int optMaskLocal = fi.hasOptMaskParam ? paramIdx++ : -1;

        EmitCtx prev = this.ctx;
        this.ctx = new EmitCtx(modInfo, fi, fi.directFuncDef, paramIdx, hf.returnKind(), envLocal, optMaskLocal);
        this.ctx.thisLocal = thisLocal;
        this.ctx.ownerClass = fi.ownerClass;
        try {
            WasmCodeBuilder c = ctx.code;

            // 1. Register captured variables (from $env closure, $this ownerClass, or $this.vtable.ifaceCaps)
            if (fi.ownerClass != null) {
                List<HirVar> clsCaps = fi.ownerClass.hir.capturedVars();
                for (int i = 0; i < clsCaps.size(); i++) {
                    HirVar cv = clsCaps.get(i);
                    int cellType = ts.cellTypeFor(cv.primitiveKind());
                    ctx.vars.put(cv.id(), new VarSlot(-1, cv.primitiveKind(), true, cellType, true,
                            fi.ownerClass.captureBaseFieldIdx + i));
                }
                List<HirVar> hidden = fi.ownerClass.hiddenCells;
                for (int i = 0; i < hidden.size(); i++) {
                    HirVar hv = hidden.get(i);
                    int cellType = ts.cellTypeFor(hv.primitiveKind());
                    ctx.vars.put(hv.id(), new VarSlot(-1, hv.primitiveKind(), true, cellType, true,
                            fi.ownerClass.hiddenCellBaseFieldIdx + i));
                }
            } else if (fi.isInterfaceDefault && fi.ownerIface != null && fi.ownerIface.hasCaptures()) {
                WasmObjectSystem.IfaceInfo oi = fi.ownerIface;
                int capLocal = ctx.newLocal("$ifaceCap", WasmValType.refNull(oi.capStructTypeIdx));
                int arrTmp = ctx.newLocal("$icArr", WasmValType.refNull(ts.anyArrType));
                c.localGet(thisLocal).refAsNonNull()
                        .structGet(ts.objectType, 0)
                        .structGet(ts.vtableType, 13)
                        .localTee(arrTmp)
                        .refIsNull().if_(WasmValType.refNull(oi.capStructTypeIdx))
                        .globalGet(oi.latestCapEnvGlobalIdx)
                        .else_()
                        .localGet(arrTmp).refAsNonNull()
                        .i32Const(oi.capSlotIdx).arrayGet(ts.anyArrType)
                        .refCast(oi.capStructTypeIdx)
                        .end()
                        .localSet(capLocal);
                ctx.ifaceCapLocal = capLocal;
                ctx.ifaceCapStructType = oi.capStructTypeIdx;
                for (int i = 0; i < oi.capturedVars.size(); i++) {
                    HirVar cv = oi.capturedVars.get(i);
                    int cellType = ts.cellTypeFor(cv.primitiveKind());
                    ctx.vars.put(cv.id(), new VarSlot(-1, cv.primitiveKind(), true, cellType, true, i));
                }
            } else {
                for (int i = 0; i < hf.capturedVars().size(); i++) {
                    HirVar cv = hf.capturedVars().get(i);
                    int cellType = ts.cellTypeFor(cv.primitiveKind());
                    ctx.vars.put(cv.id(), new VarSlot(-1, cv.primitiveKind(), true, cellType, true, 3 + i));
                }
            }

            // 2. Evaluate optional parameter defaults (via $optMask OR boxed $G_MISSING for >32 optional params)
            int[] paramValueLocals = new int[hf.params().size()];
            for (int i = 0; i < hf.params().size(); i++) {
                paramValueLocals[i] = firstParamIdx + i;
            }
            if (fi.hasOptMaskParam) {
                int optBit = 0;
                for (int i = 0; i < hf.params().size(); i++) {
                    HirParam p = hf.params().get(i);
                    if (p.defaultValue() != null) {
                        int pLocal = firstParamIdx + i;
                        c.localGet(optMaskLocal).i32Const(1 << optBit).op(WasmInstr.SimpleOp.I32_AND).if_();
                        ctx.pushBlock("opt_def");
                        bindParamSlotIfUnbound(p, pLocal, false);
                        emitAndWiden(p.defaultValue());
                        widenStackRank(p.wideningRank(), hf.line());
                        convertStack(ctx.stackKind, p.kind(), hf.line());
                        c.localSet(pLocal);
                        ctx.popBlock();
                        c.end();
                        optBit++;
                    }
                    bindParamSlotIfUnbound(p, firstParamIdx + i, false);
                }
            } else if (fi.hasBoxedMissingOptParams) {
                for (int i = 0; i < hf.params().size(); i++) {
                    HirParam p = hf.params().get(i);
                    int rawLocal = firstParamIdx + i;
                    if (p.defaultValue() != null) {
                        int typedLocal = p.kind().isPrimitive()
                                ? ctx.newLocal("opt_" + p.name(), ts.valTypeFor(p.kind()))
                                : rawLocal;
                        paramValueLocals[i] = typedLocal;
                        c.localGet(rawLocal).globalGet(rt.gMissing).refEq().if_();
                        ctx.pushBlock("opt_def_box");
                        bindParamSlotIfUnbound(p, typedLocal, false);
                        emitAndWiden(p.defaultValue());
                        widenStackRank(p.wideningRank(), hf.line());
                        convertStack(ctx.stackKind, p.kind(), hf.line());
                        c.localSet(typedLocal);
                        c.else_();
                        if (p.kind().isPrimitive()) {
                            c.localGet(rawLocal).i32Const(hf.line()).call(rt.unboxFuncFor(p.kind())).localSet(typedLocal);
                        }
                        ctx.popBlock();
                        c.end();
                        bindParamSlotIfUnbound(p, typedLocal, false);
                    } else {
                        bindParamSlotIfUnbound(p, rawLocal, false);
                    }
                }
            } else {
                for (int i = 0; i < hf.params().size(); i++) {
                    bindParamSlotIfUnbound(hf.params().get(i), firstParamIdx + i, false);
                }
            }

            // 3. Wrap any cell-captured parameters into capture cells
            for (int i = 0; i < hf.params().size(); i++) {
                HirParam p = hf.params().get(i);
                HirVar pv = p.var();
                if (pv != null && pv.cell()) {
                    int cellType = ts.cellTypeFor(pv.primitiveKind());
                    int cellLocal = ctx.newLocal("cell_" + pv.name(), WasmValType.ref(cellType));
                    c.localGet(paramValueLocals[i]);
                    convertRawValue(p.kind(), pv.primitiveKind(), hf.line());
                    if (pv.primitiveKind().isPrimitive()) {
                        c.i32Const(1);
                    }
                    c.structNew(cellType).localSet(cellLocal);
                    ctx.vars.put(pv.id(), new VarSlot(cellLocal, pv.primitiveKind(), true, cellType, false, -1));
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
                if (thisLocal >= 0 && (v.kind() == org.babblelang.analysis.Resolution.Variable.Kind.THIS || "this".equals(v.name()))) {
                    if (v.cell()) {
                        int cellLocal = ctx.newLocal("cell_this", WasmValType.ref(ts.refCellType));
                        c.localGet(thisLocal).structNew(ts.refCellType).localSet(cellLocal);
                        ctx.vars.put(v.id(), new VarSlot(cellLocal, PrimitiveKind.NONE, true, ts.refCellType, false, -1));
                    } else {
                        ctx.vars.put(v.id(), new VarSlot(thisLocal, PrimitiveKind.NONE, false, -1, false, -1));
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
        if (v == null || ctx.vars.containsKey(v.id())) {
            return;
        }
        WasmCodeBuilder c = ctx.code;
        if (v.cell()) {
            int cellType = ts.cellTypeFor(v.primitiveKind());
            int cellLocal = ctx.newLocal("cell_" + v.name(), WasmValType.refNull(cellType));
            if (v.primitiveKind() == PrimitiveKind.NONE) {
                c.globalGet(rt.gUndefined);
            } else {
                ts.emitDefaultValue(c, ts.valTypeFor(v.primitiveKind()));
                c.i32Const(v.mayBeUndefined() ? 0 : 1);
            }
            c.structNew(cellType).localSet(cellLocal);
            ctx.vars.put(v.id(), new VarSlot(cellLocal, v.primitiveKind(), true, cellType, false, -1));
        } else {
            WasmValType vt = ts.valTypeFor(v.primitiveKind());
            int local = ctx.newLocal("v_" + v.name(), vt);
            int defLocal = -1;
            if (v.primitiveKind() == PrimitiveKind.NONE && v.mayBeUndefined()) {
                c.globalGet(rt.gUndefined).localSet(local);
            } else if (v.primitiveKind().isPrimitive() && v.mayBeUndefined()) {
                defLocal = ctx.newLocal("def_" + v.name(), WasmValType.I32);
                c.i32Const(0).localSet(defLocal);
            }
            ctx.vars.put(v.id(), new VarSlot(local, v.primitiveKind(), false, -1, false, -1, defLocal));
        }
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
            case HirExpr.VarRead v -> emitVarRead(v.var(), v.meta().line());
            case HirExpr.NameRead n -> emitNameRead(n.name(), n.meta().line());
            case HirExpr.ThisRef t -> emitThisRef(t);
            case HirExpr.RecurseRef r -> emitRecurseRef(r.meta().line());
            case HirExpr.Def d -> emitDef(d);
            case HirExpr.VarAssign a -> emitVarAssign(a);
            case HirExpr.NameAssign a -> emitNameAssign(a);
            case HirExpr.ThisFieldGet g -> members.emitThisFieldGet(g);
            case HirExpr.ThisFieldAssign a -> members.emitThisFieldAssign(a);
            case HirExpr.DynamicFieldAssign a -> members.emitDynamicFieldAssign(a);
            case HirExpr.ArrayCreate ac -> arrOps.emitArrayCreate(this, ac);
            case HirExpr.ArrayOp ao -> arrOps.emitArrayOp(this, ao);
            case HirExpr.ModuleExportGet g -> members.emitModuleExportGet(g);
            case HirExpr.DynamicSelect s -> members.emitDynamicSelect(s);
            case HirExpr.JavaFieldGet fg -> core.emitJavaFieldGet(fg);
            case HirExpr.Unary u -> emitUnary(u);
            case HirExpr.Binary b -> emitBinary(b);
            case HirExpr.Logic l -> emitLogic(l);
            case HirExpr.Is i -> emitIs(i);
            case HirExpr.As a -> emitAsExpr(a);
            case HirExpr.NonNull n -> emitNonNull(n);
            case HirExpr.Block b -> emitBlock(b);
            case HirExpr.If i -> emitIf(i);
            case HirExpr.While w -> emitWhile(w);
            case HirExpr.For f -> core.emitFor(f);
            case HirExpr.Try t -> emitTry(t);
            case HirExpr.Break b -> emitBreak(b);
            case HirExpr.Continue c -> emitContinue();
            case HirExpr.Return r -> emitReturn(r);
            case HirExpr.Throw t -> emitThrow(t);
            case HirExpr.FunctionRef f -> emitFunctionRef(f);
            case HirExpr.ClassExpr c -> members.emitClassExpr(c);
            case HirExpr.InterfaceExpr i -> members.emitInterfaceExpr(i);
            case HirExpr.RecordNew r -> members.emitRecordNew(r);
            case HirExpr.TypeApply t -> emitBoxed(t.inner());
            case HirExpr.Move m -> emitAndWiden(m.operand());
            case HirExpr.Freeze f -> emitAndWiden(f.operand());
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
                rt.emitStringLit(ctx.code, "Not yet implemented in Wasm backend : " + expr.getClass().getSimpleName());
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

    public static boolean emitPrimitiveWidening(WasmCodeBuilder c, PrimitiveKind from, PrimitiveKind to) {
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

    private void widenStackRank(int rank, int line) {
        if (rank < 0) {
            return;
        }
        PrimitiveKind target = switch (rank) {
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
        Object v = c.value();
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
            b.i32Const(c.meta().line()).call(rt.fnThrowMsg).unreachable();
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

    // ---------------------------------------------------------------- variables, defs, assignments

    public void emitVarRead(HirVar v, int line) {
        VarSlot slot = ctx.vars.get(v.id());
        if (slot == null) {
            // Fallback: check top-level module global by name
            emitNameRead(v.name(), line);
            return;
        }
        WasmCodeBuilder c = ctx.code;
        if (slot.isCapturedField()) {
            if (ctx.ownerClass != null) {
                if (slot.kind().isPrimitive() && v.mayBeUndefined()) {
                    c.localGet(ctx.thisLocal).refAsNonNull().structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                            .refAsNonNull().structGet(slot.cellTypeIdx(), 1).op(WasmInstr.SimpleOp.I32_EQZ).if_();
                    rt.emitStringLit(c, "Name used before definition : " + v.name());
                    c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
                }
                c.localGet(ctx.thisLocal).refAsNonNull().structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                        .refAsNonNull().structGet(slot.cellTypeIdx(), 0);
            } else if (ctx.ifaceCapLocal >= 0) {
                if (slot.kind().isPrimitive() && v.mayBeUndefined()) {
                    c.localGet(ctx.ifaceCapLocal).refAsNonNull().structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 1).op(WasmInstr.SimpleOp.I32_EQZ).if_();
                    rt.emitStringLit(c, "Name used before definition : " + v.name());
                    c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
                }
                c.localGet(ctx.ifaceCapLocal).refAsNonNull().structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                        .structGet(slot.cellTypeIdx(), 0);
            } else {
                if (slot.kind().isPrimitive() && v.mayBeUndefined()) {
                    c.localGet(ctx.envLocal).structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 1).op(WasmInstr.SimpleOp.I32_EQZ).if_();
                    rt.emitStringLit(c, "Name used before definition : " + v.name());
                    c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
                }
                c.localGet(ctx.envLocal).structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                        .structGet(slot.cellTypeIdx(), 0);
            }
        } else if (slot.isCell()) {
            if (slot.kind().isPrimitive() && v.mayBeUndefined()) {
                c.localGet(slot.localIdx()).structGet(slot.cellTypeIdx(), 1).op(WasmInstr.SimpleOp.I32_EQZ).if_();
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
        if (slot.kind() == PrimitiveKind.NONE && v.mayBeUndefined()) {
            emitUndefinedCheck(v.name(), line);
        }
        ctx.stackKind = slot.kind();
    }

    private void emitThisRef(HirExpr.ThisRef t) {
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
        WasmCodeBuilder c = ctx.code;
        int tmp = ctx.newLocal("$undefChk", ts.anyRef());
        c.localTee(tmp).globalGet(rt.gUndefined).refEq().if_();
        rt.emitStringLit(c, "Name used before definition : " + name);
        c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(tmp);
    }

    private void emitNameRead(String name, int line) {
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
        // 2. Check module top-level globals
        Integer g = ctx.modInfo.topLevelGlobals.get(name);
        if (g != null) {
            c.globalGet(g);
            emitUndefinedCheck(name, line);
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
        if (isBuiltinName(name)) {
            int bg = ops.builtinCallableGlobal(name);
            c.globalGet(bg).refAsNonNull();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        rt.emitStringLit(c, "No such name : " + name);
        c.i32Const(line).call(rt.fnThrowMsg).unreachable();
        ctx.stackKind = PrimitiveKind.NONE;
    }

    public static boolean isBuiltinName(String name) {
        return switch (name) {
            case "print", "println", "affiche", "afficherc", "assert", "suppose",
                 "same", "classof", "move", "freeze", "discard",
                 "byte", "short", "int", "long", "float", "double", "bool", "char", "string", "exception" -> true;
            default -> false;
        };
    }

    private void emitRecurseRef(int line) {
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

    private void emitDef(HirExpr.Def d) {
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
                    if (ci == null || mIdx < 0 || mIdx >= ci.memberToStructField.length || ci.memberToStructField[mIdx] < 0) {
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
                        emitAndWiden(d.value());
                        widenStackRank(d.meta().wideningRank(), d.meta().line());
                        convertStack(ctx.stackKind, fk, d.meta().line());
                    } else {
                        ts.emitDefaultValue(c, ts.valTypeFor(fk));
                    }
                    int valTmp = ctx.newLocal("$mDefVal", ts.valTypeFor(fk));
                    c.localTee(valTmp);
                    c.localGet(ctx.thisLocal).refAsNonNull().localGet(valTmp).structSet(ci.structTypeIdx, fi);
                    if (ci.defMaskFieldIdx >= 0 && mIdx < ci.hir.primDefBits().length && ci.hir.primDefBits()[mIdx] >= 0) {
                        int bit = 1 << ci.hir.primDefBits()[mIdx];
                        c.localGet(ctx.thisLocal).refAsNonNull()
                                .localGet(ctx.thisLocal).refAsNonNull().structGet(ci.structTypeIdx, ci.defMaskFieldIdx)
                                .i32Const(bit).op(WasmInstr.SimpleOp.I32_OR)
                                .structSet(ci.structTypeIdx, ci.defMaskFieldIdx);
                    }
                    ctx.stackKind = fk;
                    return;
                }
                case STATIC -> {
                    if (d.value() != null) {
                        emitBoxed(d.value());
                        widenStackRank(d.meta().wideningRank(), d.meta().line());
                    } else {
                        c.refNull(ts.anyType);
                    }
                    int stTmp = ctx.newLocal("$stDefVal", ts.anyRef());
                    c.localTee(stTmp);
                    if (ctx.staticArrayLocal >= 0 && d.target().index() >= 0) {
                        c.localGet(ctx.staticArrayLocal).refAsNonNull()
                                .i32Const(d.target().index()).localGet(stTmp).arraySet(ts.anyArrType);
                    }
                    ctx.stackKind = PrimitiveKind.NONE;
                    return;
                }
                case LEXICAL -> { /* fall through below */ }
            }
        }
        if (d.var() != null) {
            allocateLocalVar(d.var());
            VarSlot slot = ctx.vars.get(d.var().id());
            if (d.value() != null) {
                emitAndWiden(d.value());
                widenStackRank(d.meta().wideningRank(), d.meta().line());
                convertStack(ctx.stackKind, slot.kind(), d.meta().line());
            } else {
                if (slot.kind() == PrimitiveKind.NONE) {
                    c.refNull(ts.anyType);
                } else {
                    ts.emitDefaultValue(c, ts.valTypeFor(slot.kind()));
                }
            }
            storeVarSlotAndKeep(slot, true);
            ctx.stackKind = slot.kind();
            return;
        }
        // Namespace-scoped def (top-level global or nested namespace block local)
        if (d.value() != null) {
            emitBoxed(d.value());
            widenStackRank(d.meta().wideningRank(), d.meta().line());
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

    public void storeVarSlotAndKeep(VarSlot slot) {
        storeVarSlotAndKeep(slot, false);
    }

    public void storeVarSlotAndKeep(VarSlot slot, boolean markDefined) {
        WasmCodeBuilder c = ctx.code;
        int tmp = ctx.newLocal("$stTmp", ts.valTypeFor(slot.kind()));
        if (slot.isCapturedField()) {
            if (ctx.ownerClass != null) {
                c.localSet(tmp)
                        .localGet(ctx.thisLocal).refAsNonNull().structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                        .refAsNonNull().localGet(tmp).structSet(slot.cellTypeIdx(), 0);
                if (markDefined && slot.kind().isPrimitive()) {
                    c.localGet(ctx.thisLocal).refAsNonNull().structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                            .refAsNonNull().i32Const(1).structSet(slot.cellTypeIdx(), 1);
                }
                c.localGet(tmp);
            } else if (ctx.ifaceCapLocal >= 0) {
                c.localSet(tmp)
                        .localGet(ctx.ifaceCapLocal).refAsNonNull().structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                        .localGet(tmp).structSet(slot.cellTypeIdx(), 0);
                if (markDefined && slot.kind().isPrimitive()) {
                    c.localGet(ctx.ifaceCapLocal).refAsNonNull().structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                            .i32Const(1).structSet(slot.cellTypeIdx(), 1);
                }
                c.localGet(tmp);
            } else {
                c.localSet(tmp)
                        .localGet(ctx.envLocal).structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                        .localGet(tmp).structSet(slot.cellTypeIdx(), 0);
                if (markDefined && slot.kind().isPrimitive()) {
                    c.localGet(ctx.envLocal).structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                            .i32Const(1).structSet(slot.cellTypeIdx(), 1);
                }
                c.localGet(tmp);
            }
        } else if (slot.isCell()) {
            c.localSet(tmp)
                    .localGet(slot.localIdx()).localGet(tmp).structSet(slot.cellTypeIdx(), 0);
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

    private void emitCheckSlotDefinedForAssign(HirVar v, VarSlot slot, int line) {
        if (!v.mayBeUndefined()) {
            return;
        }
        WasmCodeBuilder c = ctx.code;
        if (slot.isCapturedField()) {
            if (ctx.ownerClass != null) {
                if (slot.kind().isPrimitive()) {
                    c.localGet(ctx.thisLocal).refAsNonNull().structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                            .refAsNonNull().structGet(slot.cellTypeIdx(), 1).op(WasmInstr.SimpleOp.I32_EQZ).if_();
                } else {
                    c.localGet(ctx.thisLocal).refAsNonNull().structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx())
                            .refAsNonNull().structGet(slot.cellTypeIdx(), 0).globalGet(rt.gUndefined).refEq().if_();
                }
            } else if (ctx.ifaceCapLocal >= 0) {
                if (slot.kind().isPrimitive()) {
                    c.localGet(ctx.ifaceCapLocal).refAsNonNull().structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 1).op(WasmInstr.SimpleOp.I32_EQZ).if_();
                } else {
                    c.localGet(ctx.ifaceCapLocal).refAsNonNull().structGet(ctx.ifaceCapStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 0).globalGet(rt.gUndefined).refEq().if_();
                }
            } else {
                if (slot.kind().isPrimitive()) {
                    c.localGet(ctx.envLocal).structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 1).op(WasmInstr.SimpleOp.I32_EQZ).if_();
                } else {
                    c.localGet(ctx.envLocal).structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx())
                            .structGet(slot.cellTypeIdx(), 0).globalGet(rt.gUndefined).refEq().if_();
                }
            }
            rt.emitStringLit(c, "Name used before definition : " + v.name());
            c.i32Const(line).call(rt.fnThrowMsg).unreachable().end();
        } else if (slot.isCell()) {
            if (slot.kind().isPrimitive()) {
                c.localGet(slot.localIdx()).structGet(slot.cellTypeIdx(), 1).op(WasmInstr.SimpleOp.I32_EQZ).if_();
            } else {
                c.localGet(slot.localIdx()).structGet(slot.cellTypeIdx(), 0).globalGet(rt.gUndefined).refEq().if_();
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

    private void emitVarAssign(HirExpr.VarAssign a) {
        VarSlot slot = ctx.vars.get(a.var().id());
        if (slot == null) {
            emitNameAssign(new HirExpr.NameAssign(a.var().name(), a.operator(), a.value(), a.targetText(), a.valueText(), a.meta()));
            return;
        }
        if (a.operator() == null || "=".equals(a.operator())) {
            emitAs(a.value(), slot.kind());
            emitCheckSlotDefinedForAssign(a.var(), slot, a.meta().line());
            storeVarSlotAndKeep(slot, false);
            ctx.stackKind = slot.kind();
            return;
        }
        // Compound assignment: +, -, *, / (or +=, -=, *=, /=)
        emitCompoundValue(slot.kind(), () -> emitVarRead(a.var(), a.meta().line()), a.operator(), a.value(),
                a.targetText(), a.valueText(), a.meta().line());
        convertStack(ctx.stackKind, slot.kind(), a.meta().line());
        storeVarSlotAndKeep(slot, false);
        ctx.stackKind = slot.kind();
    }

    private void emitNameAssign(HirExpr.NameAssign a) {
        WasmCodeBuilder c = ctx.code;
        if (a.operator() == null || "=".equals(a.operator())) {
            emitBoxed(a.value());
        } else {
            emitCompoundValue(PrimitiveKind.NONE, () -> emitNameRead(a.name(), a.meta().line()), a.operator(), a.value(),
                    a.targetText(), a.valueText(), a.meta().line());
            convertStack(ctx.stackKind, PrimitiveKind.NONE, a.meta().line());
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

    public void emitCompoundValue(PrimitiveKind lhsKind, Runnable emitLhs, String assignOp, HirExpr rhs,
                                  String lhsText, String rhsText, int line) {
        HirExpr.BinaryOp binOp = switch (assignOp) {
            case "+", "+=" -> HirExpr.BinaryOp.ADD;
            case "-", "-=" -> HirExpr.BinaryOp.SUB;
            case "*", "*=" -> HirExpr.BinaryOp.MUL;
            case "/", "/=" -> HirExpr.BinaryOp.DIV;
            default -> throw new IllegalArgumentException("Unknown compound operator : " + assignOp);
        };
        PrimitiveKind rk = rhs.meta().kind();
        if (lhsKind.numericRank() >= 0 && rk.numericRank() >= 0) {
            PrimitiveKind target = binOp == HirExpr.BinaryOp.DIV
                    ? ((lhsKind == PrimitiveKind.FLOAT || rk == PrimitiveKind.FLOAT)
                        && lhsKind != PrimitiveKind.DOUBLE && rk != PrimitiveKind.DOUBLE
                        && lhsKind != PrimitiveKind.LONG && rk != PrimitiveKind.LONG
                        ? PrimitiveKind.FLOAT : PrimitiveKind.DOUBLE)
                    : (lhsKind.numericRank() >= rk.numericRank() ? lhsKind : rk);
            if (binOp != HirExpr.BinaryOp.DIV && target.numericRank() < 2) {
                target = PrimitiveKind.INT;
            }
            emitLhs.run();
            convertStack(ctx.stackKind, target, line);
            emitAs(rhs, target);
            emitPrimArithmeticOp(binOp, target);
            ctx.stackKind = target;
            return;
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
                emitAs(b.right(), target);
                emitPrimArithmeticOp(b.op(), target);
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

    private void emitPrimArithmeticOp(HirExpr.BinaryOp op, PrimitiveKind target) {
        WasmCodeBuilder c = ctx.code;
        switch (target) {
            case BYTE, SHORT, INT -> c.op(switch (op) {
                case ADD -> WasmInstr.SimpleOp.I32_ADD;
                case SUB -> WasmInstr.SimpleOp.I32_SUB;
                case MUL -> WasmInstr.SimpleOp.I32_MUL;
                default -> throw new IllegalStateException("Int op : " + op);
            });
            case LONG -> c.op(switch (op) {
                case ADD -> WasmInstr.SimpleOp.I64_ADD;
                case SUB -> WasmInstr.SimpleOp.I64_SUB;
                case MUL -> WasmInstr.SimpleOp.I64_MUL;
                default -> throw new IllegalStateException("Long op : " + op);
            });
            case FLOAT -> c.op(switch (op) {
                case ADD -> WasmInstr.SimpleOp.F32_ADD;
                case SUB -> WasmInstr.SimpleOp.F32_SUB;
                case MUL -> WasmInstr.SimpleOp.F32_MUL;
                case DIV -> WasmInstr.SimpleOp.F32_DIV;
                default -> throw new IllegalStateException("Float op : " + op);
            });
            case DOUBLE -> c.op(switch (op) {
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
            case BOOL -> { /* already 0/1 i32 */ }
            case BYTE, SHORT, INT, CHAR -> c.i32Const(0).op(WasmInstr.SimpleOp.I32_NE);
            case LONG -> c.i64Const(0L).op(WasmInstr.SimpleOp.I64_NE);
            case FLOAT -> c.f32Const(0.0f).op(WasmInstr.SimpleOp.F32_NE);
            case DOUBLE -> c.f64Const(0.0).op(WasmInstr.SimpleOp.F64_NE);
            case NONE -> c.call(rt.fnTruth);
        }
    }

    // ---------------------------------------------------------------- is, as, non-null

    private void emitIs(HirExpr.Is i) {
        WasmCodeBuilder c = ctx.code;
        emitBoxed(i.left());
        int valLocal = ctx.newLocal("$isVal", ts.anyRef());
        c.localSet(valLocal);
        if (i.descriptor() != null && !i.descriptor().isEmpty()) {
            emitDescCheck(c, valLocal, WasmTypeDesc.parse(i.descriptor()), false);
            ctx.stackKind = PrimitiveKind.BOOL;
            return;
        }
        if (objects.emitNominalIsCheck(c, valLocal, i.target(), i.intersectionTargets())) {
            ctx.stackKind = PrimitiveKind.BOOL;
            return;
        }
        if (i.builtin() != null) {
            int bid = WasmRuntimeOpsGen.builtinId(i.builtin());
            if (bid >= 0) {
                c.localGet(valLocal).i32Const(bid).call(ops.fnIsBuiltin);
                ctx.stackKind = PrimitiveKind.BOOL;
                return;
            }
        }
        // Null descriptor with no nominal/builtin target means `is any?` (Types.matches returns true)
        c.i32Const(1);
        ctx.stackKind = PrimitiveKind.BOOL;
    }

    /**
     * Emits an i32 (0/1) type check for {@code valLocal} against a parsed {@link WasmTypeDesc}.
     * When {@code forAsCast} is true, numeric widening (e.g. {@code int} to {@code double}) is accepted
     * to match {@code org.babblelang.runtime.Types.Builtin#accepts}.
     */
    public void emitDescCheck(WasmCodeBuilder c, int valLocal, WasmTypeDesc desc, boolean forAsCast) {
        if (desc == null) {
            c.i32Const(1);
            return;
        }
        switch (desc.kind()) {
            case "null" -> c.localGet(valLocal).refIsNull();
            case "any" -> c.localGet(valLocal).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ);
            case "fn" -> {
                c.localGet(valLocal).refTest(ts.fnBaseType)
                        .localGet(valLocal).refTest(ts.classValType).op(WasmInstr.SimpleOp.I32_OR)
                        .localGet(valLocal).refTest(ts.overloadSetType).op(WasmInstr.SimpleOp.I32_OR);
            }
            case "babble" -> {
                if (!objects.emitNominalIsCheckById(c, valLocal, desc.payload())) {
                    c.i32Const(0);
                }
            }
            case "union" -> {
                List<WasmTypeDesc> ms = desc.members();
                if (ms.isEmpty()) {
                    c.i32Const(0);
                    return;
                }
                for (int k = 0; k < ms.size(); k++) {
                    emitDescCheck(c, valLocal, ms.get(k), forAsCast);
                    if (k > 0) c.op(WasmInstr.SimpleOp.I32_OR);
                }
            }
            case "inter" -> {
                List<WasmTypeDesc> ms = desc.members();
                if (ms.isEmpty()) {
                    c.i32Const(1);
                    return;
                }
                List<LoweringTargets.IsTarget> targets = new ArrayList<>();
                boolean allNominal = true;
                for (WasmTypeDesc m : ms) {
                    if ("babble".equals(m.kind())) {
                        targets.add(new LoweringTargets.IsTarget(m.payload(), org.babblelang.runtime.ClassShape.Kind.INTERFACE, null));
                    } else {
                        allNominal = false;
                        break;
                    }
                }
                if (allNominal && objects.emitNominalIsCheck(c, valLocal, null, targets)) {
                    return;
                }
                for (int k = 0; k < ms.size(); k++) {
                    emitDescCheck(c, valLocal, ms.get(k), forAsCast);
                    if (k > 0) c.op(WasmInstr.SimpleOp.I32_AND);
                }
            }
            case "java" -> {
                String fqcn = desc.payload();
                switch (fqcn) {
                    case "java.lang.String" -> c.localGet(valLocal).refTest(ts.stringType);
                    case "java.lang.Object" -> c.localGet(valLocal).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ);
                    case "java.util.List" -> c.localGet(valLocal).refTest(ts.listType);
                    case "java.util.Set" -> c.localGet(valLocal).refTest(ts.setType);
                    case "java.util.SortedSet" -> c.localGet(valLocal).refTest(ts.setType).if_(WasmValType.I32)
                            .localGet(valLocal).refCast(ts.setType).structGet(ts.setType, 2)
                            .else_().i32Const(0).end();
                    case "java.util.Map" -> c.localGet(valLocal).refTest(ts.mapType);
                    case "java.util.SortedMap" -> c.localGet(valLocal).refTest(ts.mapType).if_(WasmValType.I32)
                            .localGet(valLocal).refCast(ts.mapType).structGet(ts.mapType, 3)
                            .else_().i32Const(0).end();
                    case "java.util.Map$Entry" -> c.localGet(valLocal).refTest(ts.entryType);
                    case "org.babblelang.runtime.BabbleException" -> c.localGet(valLocal).refTest(ts.babbleExnType);
                    case "java.lang.Iterable" -> {
                        c.localGet(valLocal).refTest(ts.listType)
                                .localGet(valLocal).refTest(ts.setType).op(WasmInstr.SimpleOp.I32_OR)
                                .localGet(valLocal).refTest(ts.arrayBaseType).op(WasmInstr.SimpleOp.I32_OR);
                    }
                    case "java.util.Iterator" -> c.localGet(valLocal).refTest(ts.collectionIterType);
                    case "java.lang.Appendable" -> {
                        c.localGet(valLocal).refTest(ts.hostStreamType)
                                .localGet(valLocal).refTest(ts.objectType).op(WasmInstr.SimpleOp.I32_OR);
                    }
                    default -> {
                        if (fqcn.startsWith("[")) {
                            c.localGet(valLocal).refTest(ts.arrayBaseType);
                        } else {
                            c.i32Const(0);
                        }
                    }
                }
            }
            default -> {
                if (forAsCast && isNumericBuiltinKind(desc.kind())) {
                    int targetRank = numericRankOf(desc.kind());
                    // Accept any numeric type with rank <= targetRank (matches JavaTypes.isWidening)
                    int rLocal = ctx.newLocal("$asRank", WasmValType.I32);
                    c.localGet(valLocal).call(rt.fnNumRank).localTee(rLocal)
                            .i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S)
                            .localGet(rLocal).i32Const(targetRank).op(WasmInstr.SimpleOp.I32_LE_S)
                            .op(WasmInstr.SimpleOp.I32_AND);
                    return;
                }
                int bid = WasmRuntimeOpsGen.builtinId(desc.kind());
                if (bid >= 0) {
                    c.localGet(valLocal).i32Const(bid).call(ops.fnIsBuiltin);
                } else if (desc.kind().startsWith("array")) {
                    c.localGet(valLocal).refTest(ts.arrayBaseType);
                } else {
                    c.i32Const(0);
                }
            }
        }
    }

    private static boolean isNumericBuiltinKind(String k) {
        return switch (k) {
            case "byte", "short", "int", "long", "float", "double" -> true;
            default -> false;
        };
    }

    private static int numericRankOf(String k) {
        return switch (k) {
            case "byte" -> 0;
            case "short" -> 1;
            case "int" -> 2;
            case "long" -> 3;
            case "float" -> 4;
            case "double" -> 5;
            default -> -1;
        };
    }

    private void emitAsExpr(HirExpr.As a) {
        WasmCodeBuilder c = ctx.code;
        String descStr = a.targetDescriptor();
        WasmTypeDesc desc = descStr != null ? WasmTypeDesc.parse(descStr) : null;
        if (desc == null && a.target() != null) {
            desc = new WasmTypeDesc("babble", a.target().typeId(), a.target().typeId(), List.of());
        }
        if (desc == null) {
            emitAs(a.left(), a.meta().kind());
            return;
        }
        emitBoxed(a.left());
        int tmp = ctx.newLocal("$asTmp", ts.anyRef());
        c.localSet(tmp);
        emitDescCheck(c, tmp, desc, true);
        c.op(WasmInstr.SimpleOp.I32_EQZ).if_();
        if ("babble".equals(desc.kind()) && desc.payload() != null && !desc.payload().isEmpty()) {
            int gotStr = ctx.newLocal("$asGot", WasmValType.refNull(ts.stringType));
            c.localGet(tmp).call(ops.fnDescribeType).localTee(gotStr).refAsNonNull();
            rt.emitStringLit(c, desc.display());
            c.call(rt.fnStrEq)
                    .localGet(tmp).refTest(ts.objectType).op(WasmInstr.SimpleOp.I32_AND).if_();
            rt.emitStringLit(c, "Type error : expected " + desc.display() + ", got ");
            c.localGet(gotStr).refAsNonNull().call(rt.fnStrConcat);
            rt.emitStringLit(c, " (expected " + desc.payload() + ", got ");
            c.call(rt.fnStrConcat)
                    .localGet(tmp).refCast(ts.objectType).structGet(ts.objectType, 0).structGet(ts.vtableType, 12)
                    .call(rt.fnStrConcat);
            rt.emitStringLit(c, ")");
            c.call(rt.fnStrConcat).i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable().end();
            rt.emitStringLit(c, "Type error : expected " + desc.display() + ", got ");
            c.localGet(gotStr).refAsNonNull().call(rt.fnStrConcat)
                    .i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable();
        } else {
            rt.emitStringLit(c, "Type error : expected " + desc.display() + ", got ");
            c.localGet(tmp).call(ops.fnDescribeType).call(rt.fnStrConcat);
            c.i32Const(a.meta().line()).call(rt.fnThrowMsg).unreachable();
        }
        c.end();

        // Numeric widening conversion when target is a numeric type (e.g. int -> double)
        String numKind = numericTargetKind(desc);
        if (numKind != null && !"byte".equals(numKind)) {
            int rank = numericRankOf(numKind);
            c.localGet(tmp).refIsNull().if_(ts.anyRef())
                    .refNull(ts.anyType)
                    .else_()
                    .localGet(tmp).i32Const(rank).call(rt.fnWidenNumeric)
                    .end();
        } else {
            c.localGet(tmp);
        }
        ctx.stackKind = PrimitiveKind.NONE;
        if (a.meta().kind().isPrimitive()) {
            convertStack(PrimitiveKind.NONE, a.meta().kind(), a.meta().line());
        }
    }

    private static String numericTargetKind(WasmTypeDesc desc) {
        if (isNumericBuiltinKind(desc.kind())) {
            return desc.kind();
        }
        if ("union".equals(desc.kind())) {
            String found = null;
            for (WasmTypeDesc m : desc.members()) {
                if ("null".equals(m.kind())) continue;
                if (isNumericBuiltinKind(m.kind()) && found == null) {
                    found = m.kind();
                } else {
                    return null;
                }
            }
            return found;
        }
        return null;
    }

    private void emitNonNull(HirExpr.NonNull n) {
        WasmCodeBuilder c = ctx.code;
        emitBoxed(n.operand());
        int tmp = ctx.newLocal("$nnTmp", ts.anyRef());
        c.localTee(tmp).refIsNull().if_();
        rt.emitStringLit(c, "Type error : expected " + (n.expectedType() != null ? n.expectedType() : "any") + ", got null");
        c.i32Const(n.meta().line()).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(tmp);
        ctx.stackKind = PrimitiveKind.NONE;
        if (n.meta().kind().isPrimitive()) {
            convertStack(PrimitiveKind.NONE, n.meta().kind(), n.meta().line());
        }
    }

    // ---------------------------------------------------------------- control flow: block, if, while, break, continue, return

    private void emitBlock(HirExpr.Block b) {
        if (b.opensNamespaceScope()) {
            Map<String, Integer> scope = new HashMap<>();
            for (String name : b.declaredNames()) {
                int local = ctx.newLocal("ns_" + name, ts.anyRef());
                ctx.code.globalGet(rt.gUndefined).localSet(local);
                scope.put(name, local);
            }
            ctx.nsScopes.add(scope);
        }
        for (HirVar v : b.declaredVars()) {
            allocateLocalVar(v);
        }
        try {
            emitStmtList(b.statements(), b.meta().kind());
            widenStackRank(b.innerWideningRank(), b.meta().line());
        } finally {
            if (b.opensNamespaceScope()) {
                ctx.nsScopes.remove(ctx.nsScopes.size() - 1);
            }
        }
    }

    private void emitIf(HirExpr.If i) {
        WasmCodeBuilder c = ctx.code;
        PrimitiveKind resKind = i.meta().kind();
        emitTruthValue(i.cond());
        c.if_(ts.valTypeFor(resKind));
        ctx.pushBlock("if");
        emitAs(i.thenBranch(), resKind);
        c.else_();
        if (i.elseBranch() != null) {
            emitAs(i.elseBranch(), resKind);
        } else {
            ts.emitDefaultValue(c, ts.valTypeFor(resKind));
        }
        ctx.popBlock();
        c.end();
        ctx.stackKind = resKind;
    }

    private void emitWhile(HirExpr.While w) {
        WasmCodeBuilder c = ctx.code;
        PrimitiveKind resKind = w.meta().kind();
        int resLocal = ctx.newLocal("$whileRes", ts.valTypeFor(resKind));
        ts.emitDefaultValue(c, ts.valTypeFor(resKind));
        c.localSet(resLocal);
        int breakDepth = ctx.pushBlock("while_brk");
        c.block(ts.valTypeFor(resKind));
        int contDepth = ctx.pushBlock("while_loop");
        c.loop();
        LoopFrame loop = new LoopFrame(breakDepth, contDepth, resKind);
        ctx.loops.push(loop);
        try {
            emitTruthValue(w.cond());
            c.if_();
            ctx.pushBlock("while_if");
            emitAs(w.body(), resKind);
            c.localSet(resLocal);
            c.br(ctx.relDepth(contDepth));
            ctx.popBlock();
            c.end();
        } finally {
            ctx.loops.pop();
        }
        ctx.popBlock();
        c.end(); // end loop
        c.localGet(resLocal);
        ctx.popBlock();
        c.end(); // end block
        ctx.stackKind = resKind;
    }

    private void emitBreak(HirExpr.Break b) {
        WasmCodeBuilder c = ctx.code;
        LoopFrame targetLoop = ctx.loops.peek();
        if (targetLoop == null) {
            rt.emitStringLit(c, "break outside of loop");
            c.i32Const(b.meta().line()).call(rt.fnThrowMsg).unreachable();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        if (b.value() != null) {
            emitAs(b.value(), targetLoop.resultKind());
        } else {
            ts.emitDefaultValue(c, ts.valTypeFor(targetLoop.resultKind()));
        }
        FinallyFrame innerFin = innermostFinallyInside(targetLoop);
        if (innerFin != null) {
            convertRawValue(targetLoop.resultKind(), innerFin.brkKind(), b.meta().line());
            c.localSet(innerFin.brkValLocal())
                    .i32Const(2).localSet(innerFin.exitKindLocal())
                    .br(ctx.relDepth(innerFin.normalDepth()));
        } else {
            c.br(ctx.relDepth(targetLoop.breakDepth()));
        }
        ctx.stackKind = b.meta().kind();
    }

    private void emitContinue() {
        WasmCodeBuilder c = ctx.code;
        LoopFrame targetLoop = ctx.loops.peek();
        if (targetLoop == null) {
            rt.emitStringLit(c, "continue outside of loop");
            c.i32Const(0).call(rt.fnThrowMsg).unreachable();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        FinallyFrame innerFin = innermostFinallyInside(targetLoop);
        if (innerFin != null) {
            c.i32Const(3).localSet(innerFin.exitKindLocal())
                    .br(ctx.relDepth(innerFin.normalDepth()));
        } else {
            c.br(ctx.relDepth(targetLoop.continueDepth()));
        }
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitReturn(HirExpr.Return r) {
        WasmCodeBuilder c = ctx.code;
        if (r.value() != null) {
            emitAs(r.value(), ctx.fnReturnKind);
        } else {
            ts.emitDefaultValue(c, ts.valTypeFor(ctx.fnReturnKind));
        }
        FinallyFrame innerFin = ctx.finallies.peek();
        if (innerFin != null) {
            c.localSet(innerFin.retValLocal())
                    .i32Const(1).localSet(innerFin.exitKindLocal())
                    .br(ctx.relDepth(innerFin.normalDepth()));
        } else {
            c.return_();
        }
        ctx.stackKind = r.meta().kind();
    }

    private FinallyFrame innermostFinallyInside(LoopFrame loop) {
        FinallyFrame top = ctx.finallies.peek();
        if (top != null && top.enclosingLoop() == loop) {
            return top;
        }
        return null;
    }

    // ---------------------------------------------------------------- BIP028 try / catch / 2-copy finally / throw (BIP025-D13)

    private void emitThrow(HirExpr.Throw t) {
        WasmCodeBuilder c = ctx.code;
        emitBoxed(t.value());
        int tmp = ctx.newLocal("$throwVal", ts.anyRef());
        c.localTee(tmp).refIsNull().if_();
        rt.emitStringLit(c, "Cannot throw null");
        c.i32Const(t.meta().line()).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(tmp).refAsNonNull()
                .i32Const(t.meta().line())
                .throw_(rt.babbleExnTag);
        ctx.stackKind = t.meta().kind();
    }

    private void emitTry(HirExpr.Try t) {
        boolean hasNonEmptyFinally = t.hasFinally() && !t.finallyBody().isEmpty();
        if (!hasNonEmptyFinally) {
            emitTryCatchOnly(t, t.meta().kind());
            return;
        }
        WasmCodeBuilder c = ctx.code;
        PrimitiveKind resKind = t.meta().kind();
        int resLocal = ctx.newLocal("$tryRes", ts.valTypeFor(resKind));
        int exitKindLocal = ctx.newLocal("$exitKind", WasmValType.I32);
        int retValLocal = ctx.newLocal("$finRet", ts.valTypeFor(ctx.fnReturnKind));
        LoopFrame enclosingLoop = ctx.loops.peek();
        PrimitiveKind brkKind = enclosingLoop != null ? enclosingLoop.resultKind() : PrimitiveKind.NONE;
        int brkValLocal = ctx.newLocal("$finBrk", ts.valTypeFor(brkKind));

        int exnValLocal = ctx.newLocal("$finExnVal", ts.anyRef());
        int exnLineLocal = ctx.newLocal("$finExnLine", WasmValType.I32);
        int exnRefLocal = ctx.newLocal("$finExnRef", WasmValType.EXNREF);

        c.i32Const(0).localSet(exitKindLocal);

        // Structured 2-copy finally (BIP025-D13):
        //   block $done (result R)
        //     block $unwind (result (ref $Any) i32 (ref exn))
        //       block $normal
        //         try_table (catch_ref $babble_exn $unwind)
        //           <try + catches -> resLocal>
        //         end
        //       end ;; $normal (normal completion OR intercepted return/break/continue!)
        //       <Copy 1: finallyBody>
        //       <dispatch exitKindLocal: 1=return, 2=break, 3=continue, 0=br $done with resLocal>
        //     end ;; $unwind -> stack: (ref $Any) i32 (ref exn)
        //     local.set $exnRef; local.set $exnLine; local.set $exnVal
        //     <Copy 2: finallyBody (with try_table addSuppressed if !finallyNothrow)>
        //     local.get $exnRef; throw_ref
        //   end ;; $done
        int doneDepth = ctx.pushBlock("try_done");
        c.block(ts.valTypeFor(resKind));

        int unwindDepth = ctx.pushBlock("try_unwind");
        c.blockType(ts.exnCatchRefTripleType);

        int normalDepth = ctx.pushBlock("try_normal");
        c.block();

        FinallyFrame finFrame = new FinallyFrame(normalDepth, exitKindLocal, retValLocal, brkValLocal, brkKind, enclosingLoop);
        ctx.finallies.push(finFrame);
        try {
            int unwindRel = ctx.relDepth(unwindDepth);
            ctx.pushBlock("try_fin_tbl");
            c.tryTable(List.of(WasmInstr.CatchClause.catchRefTag(rt.babbleExnTag, unwindRel)));
            if (t.catches().isEmpty()) {
                emitTryBodyScoped(t, resKind);
            } else {
                emitTryCatchOnly(t, resKind);
            }
            c.localSet(resLocal);
            ctx.popBlock();
            c.end(); // end try_table
        } finally {
            ctx.finallies.pop();
        }

        ctx.popBlock();
        c.end(); // end $normal

        // Copy 1: finally body on normal & control-transfer paths
        emitFinallyBodyScoped(t);

        // Dispatch exitKindLocal: 1=return, 2=break, 3=continue, 0=normal
        c.localGet(exitKindLocal).i32Const(1).op(WasmInstr.SimpleOp.I32_EQ).if_();
        ctx.pushBlock("fin_ret");
        FinallyFrame outerFin = ctx.finallies.peek();
        if (outerFin != null) {
            c.localGet(retValLocal).localSet(outerFin.retValLocal())
                    .i32Const(1).localSet(outerFin.exitKindLocal())
                    .br(ctx.relDepth(outerFin.normalDepth()));
        } else {
            c.localGet(retValLocal).return_();
        }
        ctx.popBlock();
        c.end();

        if (enclosingLoop != null) {
            c.localGet(exitKindLocal).i32Const(2).op(WasmInstr.SimpleOp.I32_EQ).if_();
            ctx.pushBlock("fin_brk");
            FinallyFrame outerLoopFin = innermostFinallyInside(enclosingLoop);
            if (outerLoopFin != null) {
                c.localGet(brkValLocal)
                        .localSet(outerLoopFin.brkValLocal())
                        .i32Const(2).localSet(outerLoopFin.exitKindLocal())
                        .br(ctx.relDepth(outerLoopFin.normalDepth()));
            } else {
                c.localGet(brkValLocal).br(ctx.relDepth(enclosingLoop.breakDepth()));
            }
            ctx.popBlock();
            c.end();

            c.localGet(exitKindLocal).i32Const(3).op(WasmInstr.SimpleOp.I32_EQ).if_();
            ctx.pushBlock("fin_cont");
            if (outerLoopFin != null) {
                c.i32Const(3).localSet(outerLoopFin.exitKindLocal())
                        .br(ctx.relDepth(outerLoopFin.normalDepth()));
            } else {
                c.br(ctx.relDepth(enclosingLoop.continueDepth()));
            }
            ctx.popBlock();
            c.end();
        }

        // Normal completion: branch to $done with resLocal
        c.localGet(resLocal).br(ctx.relDepth(doneDepth));

        ctx.popBlock();
        c.end(); // end $unwind -> stack: (ref $Any) i32 (ref exn)

        c.localSet(exnRefLocal)
                .localSet(exnLineLocal)
                .localSet(exnValLocal);

        // Copy 2: finally body on exception unwind path
        if (t.finallyNothrow()) {
            // BIP028-D5: statically nothrow finally elides inner try_table & addSuppressed!
            emitFinallyBodyScoped(t);
        } else {
            int secVal = ctx.newLocal("$secExnVal", ts.anyRef());
            int secLine = ctx.newLocal("$secExnLine", WasmValType.I32);
            int suppDone = ctx.pushBlock("supp_done");
            c.block();
            int suppCatch = ctx.pushBlock("supp_catch");
            c.blockType(ts.exnCatchPairType);
            int suppRel = ctx.relDepth(suppCatch);
            ctx.pushBlock("supp_tbl");
            c.tryTable(List.of(WasmInstr.CatchClause.catchTag(rt.babbleExnTag, suppRel)));
            emitFinallyBodyScoped(t);
            ctx.popBlock();
            c.end(); // end try_table
            c.br(ctx.relDepth(suppDone));
            ctx.popBlock();
            c.end(); // end $supp_catch -> stack: (ref $Any) i32
            c.localSet(secLine).localSet(secVal);
            c.localGet(exnValLocal).refAsNonNull()
                    .localGet(secVal).refAsNonNull()
                    .call(rt.fnAddSuppressed);
            ctx.popBlock();
            c.end(); // end $supp_done
        }
        c.localGet(exnRefLocal).refAsNonNull().throwRef();

        ctx.popBlock();
        c.end(); // end $done
        ctx.stackKind = resKind;
    }

    private void emitTryCatchOnly(HirExpr.Try t, PrimitiveKind resKind) {
        if (t.catches().isEmpty()) {
            emitTryBodyScoped(t, resKind);
            return;
        }
        WasmCodeBuilder c = ctx.code;
        int exnValLocal = ctx.newLocal("$catchVal", ts.anyRef());
        int exnLineLocal = ctx.newLocal("$catchLine", WasmValType.I32);

        int doneDepth = ctx.pushBlock("tc_done");
        c.block(ts.valTypeFor(resKind));

        int catchDepth = ctx.pushBlock("tc_catch");
        c.blockType(ts.exnCatchPairType);

        int tryValLocal = ctx.newLocal("$tcVal", ts.valTypeFor(resKind));
        int catchRel = ctx.relDepth(catchDepth);
        ctx.pushBlock("tc_tbl");
        // Always use 1-byte empty blocktype (0x40) on try_table: V8 Liftoff in Node 22 miscomputes
        // instruction length for 2-byte blocktypes (e.g. (ref null $t) or s33 >= 64) on try_table.
        c.tryTable(List.of(WasmInstr.CatchClause.catchTag(rt.babbleExnTag, catchRel)));
        emitTryBodyScoped(t, resKind);
        c.localSet(tryValLocal);
        ctx.popBlock();
        c.end(); // end try_table
        c.localGet(tryValLocal).br(ctx.relDepth(doneDepth));

        ctx.popBlock();
        c.end(); // end $tc_catch -> stack: (ref $Any) i32
        c.localSet(exnLineLocal).localSet(exnValLocal);

        // Dispatch catches sequentially
        for (HirExpr.HirCatch hc : t.catches()) {
            emitCatchMatchTest(exnValLocal, hc.target());
            c.if_();
            ctx.pushBlock("catch_body");
            emitCatchBodyScoped(hc, exnValLocal, resKind);
            c.br(ctx.relDepth(doneDepth));
            ctx.popBlock();
            c.end();
        }
        // No catch matched: rethrow!
        c.localGet(exnValLocal).refAsNonNull()
                .localGet(exnLineLocal)
                .throw_(rt.babbleExnTag);

        ctx.popBlock();
        c.end(); // end $tc_done
        ctx.stackKind = resKind;
    }

    private void emitCatchMatchTest(int exnValLocal, LoweringTargets.CatchTarget target) {
        WasmCodeBuilder c = ctx.code;
        if (target == null || target.catchesNonErrorAny() || target.catchesJavaThrowable()) {
            c.i32Const(1);
            return;
        }
        if (target.typeDescriptor() != null && !target.typeDescriptor().isEmpty()) {
            emitDescCheck(c, exnValLocal, WasmTypeDesc.parse(target.typeDescriptor()), false);
            return;
        }
        boolean hasTargets = target.babbleTargets() != null && !target.babbleTargets().isEmpty();
        if (hasTargets) {
            boolean first = true;
            for (LoweringTargets.IsTarget bt : target.babbleTargets()) {
                objects.emitNominalIsCheck(c, exnValLocal, bt, List.of());
                if (!first) {
                    c.op(WasmInstr.SimpleOp.I32_OR);
                }
                first = false;
            }
            return;
        }
        if (target.catchesBabble()) {
            c.localGet(exnValLocal).refTest(ts.babbleExnType);
            return;
        }
        c.i32Const(1);
    }

    private void emitTryBodyScoped(HirExpr.Try t, PrimitiveKind resKind) {
        if (t.tryOpensNamespaceScope()) {
            pushNsScope(t.tryDeclaredNames());
        }
        for (HirVar v : t.tryDeclaredVars()) {
            allocateLocalVar(v);
        }
        try {
            emitStmtList(t.tryBody(), resKind);
            widenStackRank(t.tryWideningRank(), t.meta().line());
            convertStack(ctx.stackKind, resKind, t.meta().line());
        } finally {
            if (t.tryOpensNamespaceScope()) {
                ctx.nsScopes.remove(ctx.nsScopes.size() - 1);
            }
        }
    }

    private void emitCatchBodyScoped(HirExpr.HirCatch hc, int exnValLocal, PrimitiveKind resKind) {
        if (hc.opensNamespaceScope()) {
            pushNsScope(hc.declaredNames());
        }
        for (HirVar v : hc.declaredVars()) {
            allocateLocalVar(v);
        }
        if (hc.var() != null) {
            allocateLocalVar(hc.var());
            VarSlot slot = ctx.vars.get(hc.var().id());
            ctx.code.localGet(exnValLocal);
            convertRawValue(PrimitiveKind.NONE, slot.kind(), hc.line());
            storeVarSlotAndKeep(slot, true);
            ctx.code.drop();
        }
        try {
            emitStmtList(hc.body(), resKind);
            widenStackRank(hc.wideningRank(), hc.line());
            convertStack(ctx.stackKind, resKind, hc.line());
        } finally {
            if (hc.opensNamespaceScope()) {
                ctx.nsScopes.remove(ctx.nsScopes.size() - 1);
            }
        }
    }

    private void emitFinallyBodyScoped(HirExpr.Try t) {
        if (t.finallyOpensNamespaceScope()) {
            pushNsScope(t.finallyDeclaredNames());
        }
        for (HirVar v : t.finallyDeclaredVars()) {
            allocateLocalVar(v);
        }
        try {
            for (HirExpr stmt : t.finallyBody()) {
                emitVoid(stmt);
            }
        } finally {
            if (t.finallyOpensNamespaceScope()) {
                ctx.nsScopes.remove(ctx.nsScopes.size() - 1);
            }
        }
    }

    private void pushNsScope(String[] declaredNames) {
        Map<String, Integer> scope = new HashMap<>();
        for (String name : declaredNames) {
            int local = ctx.newLocal("ns_" + name, ts.anyRef());
            ctx.code.globalGet(rt.gUndefined).localSet(local);
            scope.put(name, local);
        }
        ctx.nsScopes.add(scope);
    }

    // ---------------------------------------------------------------- closures (FunctionRef)

    private void emitFunctionRef(HirExpr.FunctionRef f) {
        WasmProgramLinker.FuncInfo fi = ctx.modInfo.functions.get(f.functionId());
        WasmCodeBuilder c = ctx.code;
        if (!fi.hasEnvParam) {
            c.globalGet(fi.singletonGlobalIdx).refAsNonNull();
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        // Allocate closure struct capturing outer cells
        c.refFunc(fi.genericFuncIdx);
        rt.emitStringLit(c, fi.hir.name() != null && !fi.hir.name().isEmpty() ? fi.hir.name() : "<fn>");
        c.i32Const(fi.hir.id());
        for (HirVar cv : fi.hir.capturedVars()) {
            VarSlot slot = ctx.vars.get(cv.id());
            if (slot == null) {
                throw new IllegalStateException("Missing captured variable slot : " + cv);
            }
            if (slot.isCapturedField()) {
                if (ctx.ownerClass != null) {
                    c.localGet(ctx.thisLocal).refAsNonNull().structGet(ctx.ownerClass.structTypeIdx, slot.captureFieldIdx()).refAsNonNull();
                } else if (ctx.ifaceCapLocal >= 0) {
                    c.localGet(ctx.ifaceCapLocal).refAsNonNull().structGet(ctx.ifaceCapStructType, slot.captureFieldIdx());
                } else {
                    c.localGet(ctx.envLocal).structGet(ctx.funcInfo.closureStructType, slot.captureFieldIdx());
                }
            } else if (slot.isCell()) {
                c.localGet(slot.localIdx()).refAsNonNull();
            } else {
                // Non-cell captured read-only local: wrap in fresh cell
                int cellType = ts.cellTypeFor(cv.primitiveKind());
                c.localGet(slot.localIdx());
                convertRawValue(slot.kind(), cv.primitiveKind(), f.meta().line());
                if (cv.primitiveKind().isPrimitive()) {
                    c.i32Const(1);
                }
                c.structNew(cellType);
            }
        }
        c.structNew(fi.closureStructType);
        ctx.stackKind = PrimitiveKind.NONE;
    }

    private void emitSpreadAdapt(HirExpr.SpreadAdapt s) {
        WasmCodeBuilder c = ctx.code;
        int line = s.meta().line();
        LoweringTargets.SpreadAdapt adapt = s.adapt();
        if (adapt == null || adapt.kind() == LoweringTargets.SpreadKind.NONE
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
        if (adapt == null || adapt.kind() == LoweringTargets.SpreadKind.NONE
                || adapt.kind() == LoweringTargets.SpreadKind.AS_IS
                || adapt.kind() == LoweringTargets.SpreadKind.TO_ITERABLE) {
            c.localGet(src);
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        switch (adapt.kind()) {
            case TO_LIST_COPY -> {
                int l = ctx.newLocal("$spList", WasmValType.ref(ts.listType));
                c.i32Const(4).call(linker.collections().fnListNew).localTee(l)
                        .localGet(src).i32Const(line).call(linker.collections().fnListAddAll).drop()
                        .localGet(l);
            }
            case TO_LIST_BORROWED -> {
                c.localGet(src).refTest(ts.listType).if_(ts.anyRef())
                        .localGet(src)
                        .else_();
                int l = ctx.newLocal("$spListB", WasmValType.ref(ts.listType));
                c.i32Const(4).call(linker.collections().fnListNew).localTee(l)
                        .localGet(src).i32Const(line).call(linker.collections().fnListAddAll).drop()
                        .localGet(l)
                        .end();
            }
            case TO_ARRAY -> {
                int l = ctx.newLocal("$spArrL", WasmValType.ref(ts.listType));
                c.i32Const(4).call(linker.collections().fnListNew).localTee(l)
                        .localGet(src).i32Const(line).call(linker.collections().fnListAddAll).drop();
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
