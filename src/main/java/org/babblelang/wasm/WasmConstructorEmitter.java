package org.babblelang.wasm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.analysis.Resolution;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirConstructor;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.runtime.ClassShape;

/**
 * Emits {@code BIP040} class/record/object constructors ({@code init}), delegation chains ({@code
 * this(...)}), local-staged immutable WasmGC {@code struct.new}, direct typed constructor calls,
 * and dynamic multi-constructor {@code $ClassVal.genericCall} trampolines ({@code BIP040-D10}).
 */
public final class WasmConstructorEmitter {
    /**
     * Metadata for one lowered Wasm constructor function of a {@link WasmObjectSystem.ClassInfo}.
     */
    public static final class CtorInfo {
        public final int index;
        public final HirConstructor hir; // null only if HirClass had no constructors list
        public final List<HirParam> params;
        public final WasmModule.FuncDef directDef;
        public final int directFuncIdx;
        public final boolean hasOptMask;

        CtorInfo(
                int index,
                HirConstructor hir,
                List<HirParam> params,
                WasmModule.FuncDef directDef,
                boolean hasOptMask) {
            this.index = index;
            this.hir = hir;
            this.params = params;
            this.directDef = directDef;
            this.directFuncIdx = directDef.funcIndex();
            this.hasOptMask = hasOptMask;
        }
    }

    private final WasmProgramLinker linker;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmObjectSystem objects;
    private final WasmFuncEmitter fe;

    public WasmConstructorEmitter(WasmProgramLinker linker, WasmFuncEmitter fe) {
        this.linker = linker;
        this.ts = linker.types();
        this.rt = linker.runtime();
        this.objects = linker.objects();
        this.fe = fe;
    }

    // ---------------------------------------------------------------- Local-staged immutability

    /**
     * Returns {@code true} iff every root constructor of {@code hc} can stage all fields in Wasm
     * locals before a single {@code struct.new}, enabling strictly immutable WasmGC {@code (field
     * T)} for every {@code def} field (header and body) and eliminating {@code $defMask} on them
     * ({@code BIP040-D10}).
     */
    public static boolean canLocalStageClass(HirClass hc) {
        if (!hc.allDefFieldsFinalSafe()) {
            return false;
        }
        // Hidden captured cells declared inside class body (rare) require struct.set after body
        // allocation; header param hiddenCells are fine because params are allocated before body.
        for (HirVar dv : hc.declaredVars()) {
            if (dv.cell() || dv.capturedByValue()) {
                return false;
            }
        }
        // Inline body statements must not escape `this`.
        for (HirExpr stmt : hc.body()) {
            if (isCompiledMethodDef(hc, stmt)) {
                continue;
            }
            if (stmtEscapesThisOutsideFields(stmt, hc.id())) {
                return false;
            }
        }
        // Every root constructor must have a clean top-level split point where all ThisFieldInit
        // happen before splitIdx and no general ThisRef use happens before splitIdx.
        for (HirConstructor ctor : hc.constructors()) {
            if (ctor.isDelegating()) {
                continue;
            }
            if (findLocalStageSplitIndex(ctor, hc.id()) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the statement index in {@code ctor.body()} where {@code struct.new} should be emitted
     * (all statements before {@code splitIdx} run in local-staged mode, and all statements at or
     * after {@code splitIdx} run against the allocated {@code $this}), or {@code -1} if local
     * staging is not valid for this constructor.
     */
    private static int findLocalStageSplitIndex(HirConstructor ctor, int classId) {
        List<HirExpr> body = ctor.body();
        int lastFieldInit = -1;
        int firstThisEscape = body.size();
        for (int i = 0; i < body.size(); i++) {
            HirExpr stmt = body.get(i);
            if (containsThisFieldInit(stmt)) {
                lastFieldInit = i;
            }
            if (firstThisEscape == body.size() && stmtEscapesThisOutsideFields(stmt, classId)) {
                firstThisEscape = i;
            }
        }
        if (lastFieldInit >= firstThisEscape) {
            return -1;
        }
        return firstThisEscape;
    }

    private static boolean isCompiledMethodDef(HirClass hc, HirExpr stmt) {
        if (!(stmt instanceof HirExpr.Def d)
                || d.target() == null
                || d.target().kind() != LoweringTargets.DefKind.MEMBER) {
            return false;
        }
        int idx = d.target().index();
        if (idx < 0 || idx >= hc.shape().memberCount() || !hc.shape().isMethod(idx)) {
            return false;
        }
        String mname = hc.shape().member(idx);
        List<Integer> ovIds = hc.overloadMethodFunctionIds().get(mname);
        return hc.methodFunctionIds().containsKey(mname)
                || (ovIds != null && !ovIds.isEmpty() && ovIds.stream().allMatch(id -> id >= 0));
    }

    private static boolean containsThisFieldInit(HirExpr expr) {
        if (expr == null) {
            return false;
        }
        if (expr instanceof HirExpr.ThisFieldInit) {
            return true;
        }
        boolean[] found = {false};
        org.babblelang.opt.HirRewriter.rewriteChildren(
                expr,
                child -> {
                    if (!found[0] && containsThisFieldInit(child)) {
                        found[0] = true;
                    }
                    return child;
                });
        return found[0];
    }

    /**
     * True iff {@code expr} uses {@code this} (for {@code classId}) in any way other than {@code
     * ThisFieldInit}, {@code ThisFieldGet(ThisRef)}, or {@code ThisFieldAssign(ThisRef)} (e.g.
     * calling a method on {@code this}, passing {@code this} to a function, or capturing {@code
     * this} in a closure).
     */
    private static boolean stmtEscapesThisOutsideFields(HirExpr expr, int classId) {
        if (expr == null) {
            return false;
        }
        switch (expr) {
            case HirExpr.ThisRef t -> {
                return t.enclosingClassId() == classId || t.enclosingClassId() < 0;
            }
            case HirExpr.VarRead v -> {
                return v.var() != null
                        && (v.var().kind() == Resolution.Variable.Kind.THIS
                                || "this".equals(v.var().name()));
            }
            case HirExpr.ThisFieldInit a -> {
                return stmtEscapesThisOutsideFields(a.value(), classId);
            }
            case HirExpr.ThisFieldGet g -> {
                if (g.receiver() instanceof HirExpr.ThisRef t
                        && (t.enclosingClassId() == classId || t.enclosingClassId() < 0)
                        && g.classId() == classId
                        && g.memberIndex() >= 0) {
                    return false;
                }
                return stmtEscapesThisOutsideFields(g.receiver(), classId);
            }
            case HirExpr.ThisFieldAssign a -> {
                if (a.receiver() instanceof HirExpr.ThisRef t
                        && (t.enclosingClassId() == classId || t.enclosingClassId() < 0)
                        && a.classId() == classId
                        && a.memberIndex() >= 0) {
                    return stmtEscapesThisOutsideFields(a.value(), classId);
                }
                return stmtEscapesThisOutsideFields(a.receiver(), classId)
                        || stmtEscapesThisOutsideFields(a.value(), classId);
            }
            case HirExpr.FunctionRef ignored -> {
                // Conservatively treat any closure created inside init as potentially capturing
                // `this` unless verified otherwise.
                return true;
            }
            default -> {}
        }
        boolean[] escaped = {false};
        org.babblelang.opt.HirRewriter.rewriteChildren(
                expr,
                child -> {
                    if (!escaped[0] && stmtEscapesThisOutsideFields(child, classId)) {
                        escaped[0] = true;
                    }
                    return child;
                });
        return escaped[0];
    }

    // ---------------------------------------------------------------- Phase 1: Declare ctors

    public static void declareConstructors(
            WasmProgramLinker linker, WasmObjectSystem.ClassInfo ci, String cname) {
        WasmModule mod = linker.module();
        WasmTypeSystem ts = linker.types();
        HirClass hc = ci.hir;
        List<HirConstructor> ctors = hc.constructors();
        if (ctors.isEmpty()) {
            CtorInfo primary = declareSingleCtor(mod, ts, ci, cname, 0, null, hc.params());
            ci.ctors.add(primary);
        } else {
            for (int k = 0; k < ctors.size(); k++) {
                HirConstructor ctor = ctors.get(k);
                ci.ctors.add(declareSingleCtor(mod, ts, ci, cname, k, ctor, ctor.params()));
            }
        }
        CtorInfo first = ci.ctors.get(0);
        ci.directCtorDef = first.directDef;
        ci.directCtorIdx = first.directFuncIdx;
        ci.ctorHasOptMask = first.hasOptMask;

        ci.genericCtorDef =
                mod.addFunction(
                        cname + "$ctor_generic",
                        ts.genericCallSigType,
                        List.of("self", "args", "names"));
        ci.genericCtorIdx = ci.genericCtorDef.funcIndex();
        mod.declareFuncRef(ci.genericCtorIdx);
    }

    private static CtorInfo declareSingleCtor(
            WasmModule mod,
            WasmTypeSystem ts,
            WasmObjectSystem.ClassInfo ci,
            String cname,
            int index,
            HirConstructor hir,
            List<HirParam> params) {
        HirClass hc = ci.hir;
        int optCount = 0;
        for (HirParam p : params) {
            if (p.defaultValue() != null) {
                optCount++;
            }
        }
        boolean hasOptMask = optCount > 0 && optCount <= 32 && params.size() <= 32;
        List<WasmValType> ctorParams = new ArrayList<>();
        List<String> ctorParamNames = new ArrayList<>();
        if (ci.hasCapturingIfaces) {
            ctorParams.add(WasmValType.ref(ts.vtableType));
            ctorParamNames.add("$vtable");
        }
        for (int i = 0; i < hc.capturedVars().size(); i++) {
            HirVar cv = hc.capturedVars().get(i);
            ctorParams.add(
                    WasmProgramLinker.isValueCapture(cv)
                            ? ts.valTypeFor(cv.primitiveKind())
                            : WasmValType.ref(ts.cellTypeFor(cv.primitiveKind())));
            ctorParamNames.add("$cap" + i);
        }
        for (HirParam p : params) {
            ctorParams.add(ts.valTypeFor(p.kind()));
            ctorParamNames.add(p.name());
        }
        if (hasOptMask) {
            ctorParams.add(WasmValType.I32);
            ctorParamNames.add("$optMask");
        }
        int ctorSig = ts.internFuncType(ctorParams, List.of(WasmValType.ref(ci.structTypeIdx)));
        String fnName = index == 0 ? cname + "$new" : cname + "$new$" + index;
        WasmModule.FuncDef def = mod.addFunction(fnName, ctorSig, ctorParamNames);
        return new CtorInfo(index, hir, params, def, hasOptMask);
    }

    // ---------------------------------------------------------------- Phase 2: Emit ctor bodies

    public void emitClassConstructors(WasmObjectSystem.ClassInfo ci) {
        for (CtorInfo ctor : ci.ctors) {
            if (ctor.hir != null && ctor.hir.isDelegating()) {
                emitDelegatingConstructor(ci, ctor);
            } else {
                emitRootConstructor(ci, ctor);
            }
        }
    }

    private void emitRootConstructor(WasmObjectSystem.ClassInfo ci, CtorInfo ctor) {
        WasmModule.FuncDef fn = ctor.directDef;
        int paramIdx = 0;
        int vtableParamIdx = ci.hasCapturingIfaces ? paramIdx++ : -1;
        int capStart = paramIdx;
        paramIdx += ci.hir.capturedVars().size();
        int firstParamIdx = paramIdx;
        paramIdx += ctor.params.size();
        int optMaskLocal = ctor.hasOptMask ? paramIdx++ : -1;

        WasmFuncEmitter.EmitCtx prev = fe.ctx();
        WasmFuncEmitter.EmitCtx cctx =
                new WasmFuncEmitter.EmitCtx(
                        ci.modInfo, null, fn, paramIdx, PrimitiveKind.NONE, -1, optMaskLocal);
        cctx.ownerClass = ci;
        cctx.currentTopLevelStmtIdx =
                ci.modInfo.classOwnerTopLevelStmtIdx.getOrDefault(ci.hir.id(), -1);
        fe.setCtx(cctx);
        try {
            WasmCodeBuilder c = cctx.code;
            int line = ctor.hir != null ? ctor.hir.line() : ci.hir.line();

            // 1. Bind captured variables
            bindCapturedVars(cctx, ci, capStart);

            // 2. Evaluate optional defaults & wrap cell-captured params
            bindAndInitParams(
                    cctx, ctor.params, firstParamIdx, ctor.hasOptMask, optMaskLocal, line);

            // 3. Determine header param -> member mapping (only for primary constructor)
            int[] memberParamIdx = new int[ci.shape.memberCount()];
            Arrays.fill(memberParamIdx, -1);
            boolean isPrimary = ctor.hir == null || ctor.hir.isPrimary();
            if (isPrimary) {
                for (int p = 0; p < ci.shape.parameters().length; p++) {
                    int mi = ci.shape.parameterMember(p);
                    if (mi >= 0 && mi < memberParamIdx.length) {
                        memberParamIdx[mi] = p;
                    }
                }
            }

            int thisLocal = cctx.newLocal("$this", WasmValType.refNull(ci.structTypeIdx));
            cctx.thisLocal = thisLocal;

            List<HirExpr> initBody = ctor.hir != null ? ctor.hir.body() : List.of();
            if (ci.localStagedCtor) {
                int splitIdx =
                        ctor.hir != null ? findLocalStageSplitIndex(ctor.hir, ci.hir.id()) : 0;
                emitLocalStagedRootBody(
                        cctx,
                        ci,
                        ctor,
                        vtableParamIdx,
                        capStart,
                        firstParamIdx,
                        memberParamIdx,
                        thisLocal,
                        initBody,
                        splitIdx,
                        line);
            } else {
                emitEagerAllocRootBody(
                        cctx,
                        ci,
                        ctor,
                        vtableParamIdx,
                        capStart,
                        firstParamIdx,
                        memberParamIdx,
                        thisLocal,
                        initBody,
                        line);
            }

            c.localGet(thisLocal).refAsNonNull();
        } finally {
            fe.setCtx(prev);
        }
    }

    private void emitLocalStagedRootBody(
            WasmFuncEmitter.EmitCtx cctx,
            WasmObjectSystem.ClassInfo ci,
            CtorInfo ctor,
            int vtableParamIdx,
            int capStart,
            int firstParamIdx,
            int[] memberParamIdx,
            int thisLocal,
            List<HirExpr> initBody,
            int splitIdx,
            int line) {
        WasmCodeBuilder c = cctx.code;
        int memberCount = ci.shape.memberCount();
        int[] staged = new int[memberCount];
        Arrays.fill(staged, -1);
        for (int i = 0; i < memberCount; i++) {
            if (ci.memberToStructField[i] < 0) {
                continue;
            }
            PrimitiveKind mk = ci.memberKinds[i];
            int local = cctx.newLocal("$sf_" + ci.shape.member(i), ts.valTypeFor(mk));
            staged[i] = local;
            int pi = memberParamIdx[i];
            if (pi >= 0) {
                pushParamValue(cctx, ctor.params.get(pi), firstParamIdx + pi, mk, line);
                c.localSet(local);
            } else if (mk == PrimitiveKind.NONE) {
                c.globalGet(rt.gUndefined).localSet(local);
            } else {
                ts.emitDefaultValue(c, ts.valTypeFor(mk));
                c.localSet(local);
            }
        }
        cctx.stagedFieldLocals = staged;

        // Allocate non-THIS declared vars from class body & constructor
        allocateDeclaredVars(cctx, ci.hir.declaredVars(), -1);
        if (ctor.hir != null) {
            allocateDeclaredVars(cctx, ctor.hir.declaredVars(), -1);
        }

        // Run inline field definitions in class body
        for (HirExpr stmt : ci.hir.body()) {
            fe.emitVoid(stmt);
        }
        // Run pre-struct.new init statements
        for (int i = 0; i < splitIdx; i++) {
            fe.emitVoid(initBody.get(i));
        }

        // Allocate immutable WasmGC struct with all staged field values!
        if (vtableParamIdx >= 0) {
            c.localGet(vtableParamIdx);
        } else {
            c.globalGet(ci.vtableGlobalIdx).refAsNonNull();
        }
        for (int i = 0; i < memberCount; i++) {
            if (ci.memberToStructField[i] < 0) {
                continue;
            }
            c.localGet(staged[i]);
        }
        if (ci.defMaskFieldIdx >= 0) {
            int initMask = 0;
            for (int i = 0; i < memberCount; i++) {
                if (i < ci.hir.primDefBits().length && ci.hir.primDefBits()[i] >= 0) {
                    initMask |= (1 << ci.hir.primDefBits()[i]);
                }
            }
            c.i32Const(initMask);
        }
        for (int i = 0; i < ci.hir.capturedVars().size(); i++) {
            c.localGet(capStart + i);
        }
        pushHiddenCellsForStructNew(cctx, ci, line);
        c.structNew(ci.structTypeIdx).localSet(thisLocal);

        cctx.stagedFieldLocals = null;
        bindThisDeclaredVars(cctx, ci.hir.declaredVars(), thisLocal);
        if (ctor.hir != null) {
            bindThisDeclaredVars(cctx, ctor.hir.declaredVars(), thisLocal);
        }
        writeHiddenCellsToStruct(cctx, ci, thisLocal, line);

        // Run post-struct.new init statements (may call methods or pass `this`)
        for (int i = splitIdx; i < initBody.size(); i++) {
            fe.emitVoid(initBody.get(i));
        }
    }

    private void emitEagerAllocRootBody(
            WasmFuncEmitter.EmitCtx cctx,
            WasmObjectSystem.ClassInfo ci,
            CtorInfo ctor,
            int vtableParamIdx,
            int capStart,
            int firstParamIdx,
            int[] memberParamIdx,
            int thisLocal,
            List<HirExpr> initBody,
            int line) {
        WasmCodeBuilder c = cctx.code;
        if (vtableParamIdx >= 0) {
            c.localGet(vtableParamIdx);
        } else {
            c.globalGet(ci.vtableGlobalIdx).refAsNonNull();
        }
        for (int i = 0; i < ci.shape.memberCount(); i++) {
            if (ci.memberToStructField[i] < 0) {
                continue;
            }
            int pi = memberParamIdx[i];
            if (pi >= 0) {
                pushParamValue(
                        cctx, ctor.params.get(pi), firstParamIdx + pi, ci.memberKinds[i], line);
            } else if (ci.memberKinds[i] == PrimitiveKind.NONE) {
                c.globalGet(rt.gUndefined);
            } else {
                ts.emitDefaultValue(c, ts.valTypeFor(ci.memberKinds[i]));
            }
        }
        if (ci.defMaskFieldIdx >= 0) {
            int initMask = 0;
            for (int i = 0; i < ci.shape.memberCount(); i++) {
                if (memberParamIdx[i] >= 0
                        && i < ci.hir.primDefBits().length
                        && ci.hir.primDefBits()[i] >= 0) {
                    initMask |= (1 << ci.hir.primDefBits()[i]);
                }
            }
            c.i32Const(initMask);
        }
        for (int i = 0; i < ci.hir.capturedVars().size(); i++) {
            c.localGet(capStart + i);
        }
        pushHiddenCellsForStructNew(cctx, ci, line);
        c.structNew(ci.structTypeIdx).localSet(thisLocal);

        allocateDeclaredVars(cctx, ci.hir.declaredVars(), thisLocal);
        if (ctor.hir != null) {
            allocateDeclaredVars(cctx, ctor.hir.declaredVars(), thisLocal);
        }
        writeHiddenCellsToStruct(cctx, ci, thisLocal, line);

        for (HirExpr stmt : ci.hir.body()) {
            fe.emitVoid(stmt);
        }
        for (HirExpr stmt : initBody) {
            fe.emitVoid(stmt);
        }
    }

    private void emitDelegatingConstructor(WasmObjectSystem.ClassInfo ci, CtorInfo ctor) {
        HirConstructor hc = ctor.hir;
        WasmModule.FuncDef fn = ctor.directDef;
        int paramIdx = 0;
        int vtableParamIdx = ci.hasCapturingIfaces ? paramIdx++ : -1;
        int capStart = paramIdx;
        paramIdx += ci.hir.capturedVars().size();
        int firstParamIdx = paramIdx;
        paramIdx += ctor.params.size();
        int optMaskLocal = ctor.hasOptMask ? paramIdx++ : -1;

        WasmFuncEmitter.EmitCtx prev = fe.ctx();
        WasmFuncEmitter.EmitCtx cctx =
                new WasmFuncEmitter.EmitCtx(
                        ci.modInfo, null, fn, paramIdx, PrimitiveKind.NONE, -1, optMaskLocal);
        cctx.ownerClass = ci;
        cctx.currentTopLevelStmtIdx =
                ci.modInfo.classOwnerTopLevelStmtIdx.getOrDefault(ci.hir.id(), -1);
        fe.setCtx(cctx);
        try {
            WasmCodeBuilder c = cctx.code;
            int line = hc.line();

            bindCapturedVars(cctx, ci, capStart);
            bindAndInitParams(
                    cctx, ctor.params, firstParamIdx, ctor.hasOptMask, optMaskLocal, line);
            allocateDeclaredVars(cctx, hc.declaredVars(), -1);

            // 1. Pre-delegation local statements (no `this`)
            for (HirExpr stmt : hc.preDelegateBody()) {
                fe.emitVoid(stmt);
            }

            // 2. Delegate call to target constructor
            CtorInfo target = ci.ctors.get(hc.delegateIndex());
            if (vtableParamIdx >= 0) {
                c.localGet(vtableParamIdx);
            }
            for (int i = 0; i < ci.hir.capturedVars().size(); i++) {
                c.localGet(capStart + i);
            }
            fe.members()
                    .emitBoundParams(
                            target.params,
                            target.hasOptMask,
                            false,
                            null,
                            hc.delegateArgs(),
                            hc.delegateBinding(),
                            line);
            c.call(target.directFuncIdx);

            int thisLocal = cctx.newLocal("$this", WasmValType.refNull(ci.structTypeIdx));
            cctx.thisLocal = thisLocal;
            c.localSet(thisLocal);
            bindThisDeclaredVars(cctx, hc.declaredVars(), thisLocal);

            // 3. Post-delegation statements
            for (HirExpr stmt : hc.body()) {
                fe.emitVoid(stmt);
            }

            c.localGet(thisLocal).refAsNonNull();
        } finally {
            fe.setCtx(prev);
        }
    }

    // ---------------------------------------------------------------- ThisFieldInit

    public void emitThisFieldInit(HirExpr.ThisFieldInit a) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        WasmObjectSystem.ClassInfo ci =
                objects.resolveClass(ctx.modInfo.index, a.classId(), null, null);
        if (ci == null) {
            fe.emitVoid(a.value());
            ctx.stackKind = PrimitiveKind.NONE;
            return;
        }
        int mIdx = a.memberIndex();
        PrimitiveKind fk = ci.memberKinds[mIdx];
        fe.emitAs(a.value(), fk);
        int valTmp = ctx.newLocal("$tfiVal", ts.valTypeFor(fk));
        c.localTee(valTmp);
        if (ctx.stagedFieldLocals != null
                && mIdx >= 0
                && mIdx < ctx.stagedFieldLocals.length
                && ctx.stagedFieldLocals[mIdx] >= 0) {
            c.localSet(ctx.stagedFieldLocals[mIdx]);
            c.localGet(valTmp);
            ctx.stackKind = fk;
            return;
        }
        int fi = ci.memberToStructField[mIdx];
        c.localGet(ctx.thisLocal).refAsNonNull().localGet(valTmp).structSet(ci.structTypeIdx, fi);
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
    }

    // ---------------------------------------------------------------- Direct ctor call dispatch

    /**
     * Emits a direct typed constructor call {@code C(args)} or static-method {@code this(args)}
     * when the target class and constructor overload are statically resolved.
     */
    public boolean tryEmitDirectConstructorCall(HirExpr.DynamicCall call) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmObjectSystem.ClassInfo ci = fe.members().resolveDirectImmutableClass(call.callee());
        if (ci == null || ci.ctors.isEmpty()) {
            return false;
        }
        int ctorIdx = call.constructorIndex() >= 0 ? call.constructorIndex() : 0;
        if (ctorIdx >= ci.ctors.size()) {
            return false;
        }
        CtorInfo target = ci.ctors.get(ctorIdx);
        if (!canDirectCtorDispatch(ci, target, call)) {
            return false;
        }
        fe.members()
                .emitBoundParams(
                        target.params,
                        target.hasOptMask,
                        false,
                        null,
                        call.args(),
                        call.binding(),
                        call.meta().line());
        ctx.code.call(target.directFuncIdx);
        ctx.stackKind = PrimitiveKind.NONE;
        return true;
    }

    private static boolean canDirectCtorDispatch(
            WasmObjectSystem.ClassInfo ci, CtorInfo ctor, HirExpr.DynamicCall call) {
        if (ci.hir.isObject() || ci.hasCaptures || ci.hasCapturingIfaces) {
            return false;
        }
        List<HirParam> params = ctor.params;
        int optCount = 0;
        for (HirParam p : params) {
            if (p.defaultValue() != null) {
                optCount++;
            }
        }
        LoweringTargets.ArgumentBinding binding = call.binding();
        if (binding != null) {
            if (binding.paramCount() != params.size()) {
                return false;
            }
            if (optCount > 0 && !ctor.hasOptMask) {
                for (int p = 0; p < params.size(); p++) {
                    if (p != binding.restParam()
                            && (p >= binding.paramToArg().length || binding.paramToArg()[p] < 0)) {
                        return false;
                    }
                }
            }
            return true;
        }
        if (ctor.hir != null && ctor.hir.restIndex() >= 0) {
            return false;
        }
        List<HirCallArg> args = call.args();
        if (args.size() > params.size()) {
            return false;
        }
        if (optCount > 0 && !ctor.hasOptMask && args.size() < params.size()) {
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

    // ---------------------------------------------------------------- Generic ctor trampoline

    public void emitGenericCtorTrampoline(WasmObjectSystem.ClassInfo ci) {
        WasmModule.FuncDef fn = ci.genericCtorDef;
        WasmCodeBuilder c = fn.code();
        if (ci.hir.isObject()) {
            rt.emitStringLit(c, "Cannot instantiate object : " + ci.shape.name());
            c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable();
            return;
        }
        WasmFuncEmitter.EmitCtx prev = fe.ctx();
        WasmFuncEmitter.EmitCtx gctx =
                new WasmFuncEmitter.EmitCtx(ci.modInfo, null, fn, 3, PrimitiveKind.NONE, -1, -1);
        gctx.ownerClass = ci;
        fe.setCtx(gctx);
        try {
            int argLen = fn.addLocal("argLen", WasmValType.I32, 3);
            int idx = fn.addLocal("idx", WasmValType.I32, 3);
            int nameAny = fn.addLocal("nameAny", ts.anyRef(), 3);
            int valAny = fn.addLocal("valAny", ts.anyRef(), 3);
            int optMask = fn.addLocal("optMask", WasmValType.I32, 3);
            int matched = fn.addLocal("matched", WasmValType.I32, 3);
            int cvSelf =
                    ci.hasCaptures
                            ? fn.addLocal("cvSelf", WasmValType.refNull(ci.classValStructType), 3)
                            : -1;
            c.localGet(1).arrayLen().localSet(argLen);
            if (ci.hasCaptures) {
                c.localGet(0).refCast(ci.classValStructType).localSet(cvSelf);
            }

            if (ci.ctors.size() == 1) {
                CtorInfo single = ci.ctors.get(0);
                emitBindAndCallCtorInTrampoline(
                        fn, c, ci, single, false, argLen, idx, nameAny, valAny, optMask, matched,
                        cvSelf);
                return;
            }

            // Multiple constructors: try each candidate in order, checking arity, named args, and
            // runtime parameter types via ClassShape.ConstructorShape.paramTypes().
            for (CtorInfo cand : ci.ctors) {
                emitBindAndCallCtorInTrampoline(
                        fn, c, ci, cand, true, argLen, idx, nameAny, valAny, optMask, matched,
                        cvSelf);
            }
            rt.emitStringLit(c, "No matching constructor overload for : " + ci.shape.name());
            c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable();
        } finally {
            fe.setCtx(prev);
        }
    }

    private void emitBindAndCallCtorInTrampoline(
            WasmModule.FuncDef fn,
            WasmCodeBuilder c,
            WasmObjectSystem.ClassInfo ci,
            CtorInfo ctor,
            boolean checkMatch,
            int argLen,
            int idx,
            int nameAny,
            int valAny,
            int optMask,
            int matched,
            int cvSelf) {
        List<HirParam> params = ctor.params;
        int paramCount = params.size();
        int restIdx = ctor.hir != null ? ctor.hir.restIndex() : -1;
        int kwOnlyStart = ctor.hir != null ? ctor.hir.keywordOnlyStart() : paramCount;

        int[] pLocals = new int[paramCount];
        for (int p = 0; p < paramCount; p++) {
            pLocals[p] = fn.addLocal("c" + ctor.index + "_p" + p, ts.anyRef(), 3);
            c.globalGet(rt.gMissing).localSet(pLocals[p]);
        }
        int restList =
                restIdx >= 0
                        ? fn.addLocal(
                                "c" + ctor.index + "_rest", WasmValType.refNull(ts.listType), 3)
                        : -1;
        if (restList >= 0) {
            c.i32Const(4).call(linker.collections().fnListNew).localSet(restList);
        }

        c.i32Const(1).localSet(matched);
        c.i32Const(0).localSet(idx);
        c.loop()
                .localGet(idx)
                .localGet(argLen)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .localGet(matched)
                .op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(1)
                .localGet(idx)
                .arrayGet(ts.anyArrType)
                .localSet(valAny);

        // Is this argument positional (`names == null || names[idx] == null`)?
        c.localGet(2)
                .refIsNull()
                .if_(WasmValType.I32)
                .i32Const(1)
                .else_()
                .localGet(2)
                .refAsNonNull()
                .localGet(idx)
                .arrayGet(ts.anyArrType)
                .localTee(nameAny)
                .refIsNull()
                .end()
                .if_();

        // Positional argument at idx
        int assignedPos = fn.addLocal("c" + ctor.index + "_ap", WasmValType.I32, 3);
        c.i32Const(0).localSet(assignedPos);
        for (int p = 0; p < paramCount; p++) {
            if (p == restIdx || p >= kwOnlyStart) {
                continue;
            }
            c.localGet(idx)
                    .i32Const(p)
                    .op(WasmInstr.SimpleOp.I32_EQ)
                    .if_()
                    .localGet(valAny)
                    .localSet(pLocals[p])
                    .i32Const(1)
                    .localSet(assignedPos)
                    .end();
        }
        if (restList >= 0) {
            c.localGet(assignedPos)
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .localGet(idx)
                    .i32Const(restIdx)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .op(WasmInstr.SimpleOp.I32_AND)
                    .if_()
                    .localGet(restList)
                    .refAsNonNull()
                    .localGet(valAny)
                    .call(linker.collections().fnListAdd)
                    .drop()
                    .i32Const(1)
                    .localSet(assignedPos)
                    .end();
        }
        if (checkMatch) {
            c.localGet(assignedPos)
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .i32Const(0)
                    .localSet(matched)
                    .end();
        }

        c.else_();
        // Named argument with nameAny
        int assignedNamed = fn.addLocal("c" + ctor.index + "_an", WasmValType.I32, 3);
        c.i32Const(0).localSet(assignedNamed);
        for (int p = 0; p < paramCount; p++) {
            if (p == restIdx) {
                continue;
            }
            c.localGet(nameAny).refCast(ts.stringType);
            rt.emitStringLit(c, params.get(p).name());
            c.call(rt.fnStrEq)
                    .if_()
                    .localGet(pLocals[p])
                    .globalGet(rt.gMissing)
                    .refEq()
                    .if_()
                    .localGet(valAny)
                    .localSet(pLocals[p])
                    .i32Const(1)
                    .localSet(assignedNamed)
                    .else_()
                    .i32Const(0)
                    .localSet(matched)
                    .end()
                    .end();
        }
        if (checkMatch) {
            c.localGet(assignedNamed)
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .i32Const(0)
                    .localSet(matched)
                    .end();
        }
        c.end();

        c.localGet(idx).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(idx).br(1).end().end();

        if (checkMatch) {
            // Verify all required params were supplied and runtime types match
            ClassShape.ConstructorShape[] cShapes = ci.shape.constructors();
            String[] paramDescs =
                    (cShapes != null && ctor.index < cShapes.length)
                            ? cShapes[ctor.index].paramTypes()
                            : null;
            for (int p = 0; p < paramCount; p++) {
                if (p == restIdx) {
                    continue;
                }
                HirParam hp = params.get(p);
                if (hp.defaultValue() == null) {
                    c.localGet(matched)
                            .localGet(pLocals[p])
                            .globalGet(rt.gMissing)
                            .refEq()
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .op(WasmInstr.SimpleOp.I32_AND)
                            .localSet(matched);
                }
                String desc = (paramDescs != null && p < paramDescs.length) ? paramDescs[p] : null;
                if ((desc != null && !desc.isEmpty()) || hp.kind().isPrimitive()) {
                    c.localGet(matched)
                            .localGet(pLocals[p])
                            .globalGet(rt.gMissing)
                            .refEq()
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .op(WasmInstr.SimpleOp.I32_AND)
                            .if_();
                    if (desc != null && !desc.isEmpty()) {
                        fe.emitDescCheck(c, pLocals[p], WasmTypeDesc.parse(desc), false);
                    } else {
                        c.localGet(pLocals[p]).refTest(ts.boxTypeFor(hp.kind()));
                    }
                    c.localSet(matched).end();
                }
            }
            c.localGet(matched).if_();
        }

        // Dispatch to ctor.directFuncIdx
        c.i32Const(0).localSet(optMask);
        if (ci.hasCapturingIfaces) {
            c.localGet(0).refCast(ts.classValType).structGet(ts.classValType, 4);
        }
        if (ci.hasCaptures) {
            for (int i = 0; i < ci.hir.capturedVars().size(); i++) {
                c.localGet(cvSelf).refAsNonNull().structGet(ci.classValStructType, 6 + i);
            }
        }
        int optBit = 0;
        for (int p = 0; p < paramCount; p++) {
            HirParam hp = params.get(p);
            if (p == restIdx) {
                c.localGet(restList).refAsNonNull();
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
                    c.i32Const(ci.hir.line()).call(rt.unboxFuncFor(hp.kind()));
                }
                c.end();
                optBit++;
            } else {
                c.localGet(pLocals[p]);
                if (hp.kind().isPrimitive()) {
                    c.i32Const(ci.hir.line()).call(rt.unboxFuncFor(hp.kind()));
                }
            }
        }
        if (ctor.hasOptMask) {
            c.localGet(optMask);
        }
        c.call(ctor.directFuncIdx);
        if (checkMatch) {
            c.return_().end();
        }
    }

    // ---------------------------------------------------------------- Helpers

    private void bindCapturedVars(
            WasmFuncEmitter.EmitCtx cctx, WasmObjectSystem.ClassInfo ci, int capStart) {
        for (int i = 0; i < ci.hir.capturedVars().size(); i++) {
            HirVar cv = ci.hir.capturedVars().get(i);
            boolean valCap = WasmProgramLinker.isValueCapture(cv);
            int cellType = valCap ? -1 : ts.cellTypeFor(cv.primitiveKind());
            cctx.vars.put(
                    cv.id(),
                    new WasmFuncEmitter.VarSlot(
                            capStart + i, cv.primitiveKind(), !valCap, cellType, false, -1));
        }
    }

    private void bindAndInitParams(
            WasmFuncEmitter.EmitCtx cctx,
            List<HirParam> params,
            int firstParamIdx,
            boolean hasOptMask,
            int optMaskLocal,
            int line) {
        WasmCodeBuilder c = cctx.code;
        int optBit = 0;
        for (int i = 0; i < params.size(); i++) {
            HirParam p = params.get(i);
            int pLocal = firstParamIdx + i;
            if (hasOptMask && p.defaultValue() != null) {
                c.localGet(optMaskLocal).i32Const(1 << optBit).op(WasmInstr.SimpleOp.I32_AND).if_();
                cctx.pushBlock("ctor_opt");
                bindCtorParam(cctx, p, pLocal);
                fe.emitAs(p.defaultValue(), p.kind());
                c.localSet(pLocal);
                cctx.popBlock();
                c.end();
                optBit++;
            }
            bindCtorParam(cctx, p, pLocal);
            HirVar pv = p.var();
            if (pv != null && pv.cell()) {
                int cellType = ts.cellTypeFor(pv.primitiveKind());
                int cellLocal = cctx.newLocal("cell_" + pv.name(), WasmValType.ref(cellType));
                c.localGet(pLocal);
                fe.convertRawValue(p.kind(), pv.primitiveKind(), line);
                if (pv.primitiveKind().isPrimitive()) {
                    c.i32Const(1);
                }
                c.structNew(cellType).localSet(cellLocal);
                cctx.vars.put(
                        pv.id(),
                        new WasmFuncEmitter.VarSlot(
                                cellLocal, pv.primitiveKind(), true, cellType, false, -1));
            }
        }
    }

    private static void bindCtorParam(WasmFuncEmitter.EmitCtx cctx, HirParam p, int pLocal) {
        HirVar pv = p.var();
        if (pv != null && !cctx.vars.containsKey(pv.id())) {
            cctx.vars.put(
                    pv.id(), new WasmFuncEmitter.VarSlot(pLocal, p.kind(), false, -1, false, -1));
        }
    }

    private void pushParamValue(
            WasmFuncEmitter.EmitCtx cctx,
            HirParam hp,
            int paramLocal,
            PrimitiveKind targetKind,
            int line) {
        WasmCodeBuilder c = cctx.code;
        HirVar pv = hp.var();
        WasmFuncEmitter.VarSlot pvSlot = pv != null ? cctx.vars.get(pv.id()) : null;
        if (pvSlot != null && pvSlot.isCell()) {
            c.localGet(pvSlot.localIdx()).refAsNonNull().structGet(pvSlot.cellTypeIdx(), 0);
            fe.convertRawValue(pvSlot.kind(), targetKind, line);
        } else {
            c.localGet(paramLocal);
            fe.convertRawValue(hp.kind(), targetKind, line);
        }
    }

    private void pushHiddenCellsForStructNew(
            WasmFuncEmitter.EmitCtx cctx, WasmObjectSystem.ClassInfo ci, int line) {
        WasmCodeBuilder c = cctx.code;
        for (int i = 0; i < ci.hiddenCells.size(); i++) {
            HirVar hv = ci.hiddenCells.get(i);
            if (WasmProgramLinker.isValueCapture(hv)) {
                WasmFuncEmitter.VarSlot slot = cctx.vars.get(hv.id());
                if (slot != null && !slot.isCell()) {
                    c.localGet(slot.localIdx());
                    fe.convertRawValue(slot.kind(), hv.primitiveKind(), line);
                } else {
                    ts.emitDefaultValue(c, ts.valTypeFor(hv.primitiveKind()));
                }
            } else {
                c.refNull(ts.cellTypeFor(hv.primitiveKind()));
            }
        }
    }

    private void allocateDeclaredVars(
            WasmFuncEmitter.EmitCtx cctx, List<HirVar> declaredVars, int thisLocal) {
        WasmCodeBuilder c = cctx.code;
        for (HirVar v : declaredVars) {
            if (!cctx.vars.containsKey(v.id())) {
                fe.allocateLocalVar(v);
            }
            if (thisLocal >= 0
                    && (v.kind() == Resolution.Variable.Kind.THIS || "this".equals(v.name()))) {
                WasmFuncEmitter.VarSlot slot = cctx.vars.get(v.id());
                if (slot != null) {
                    c.localGet(thisLocal);
                    fe.storeVarSlotAndKeep(slot);
                    c.drop();
                }
            }
        }
    }

    private void bindThisDeclaredVars(
            WasmFuncEmitter.EmitCtx cctx, List<HirVar> declaredVars, int thisLocal) {
        WasmCodeBuilder c = cctx.code;
        for (HirVar v : declaredVars) {
            if (v.kind() == Resolution.Variable.Kind.THIS || "this".equals(v.name())) {
                WasmFuncEmitter.VarSlot slot = cctx.vars.get(v.id());
                if (slot != null) {
                    c.localGet(thisLocal);
                    fe.storeVarSlotAndKeep(slot);
                    c.drop();
                }
            }
        }
    }

    private void writeHiddenCellsToStruct(
            WasmFuncEmitter.EmitCtx cctx, WasmObjectSystem.ClassInfo ci, int thisLocal, int line) {
        WasmCodeBuilder c = cctx.code;
        for (int i = 0; i < ci.hiddenCells.size(); i++) {
            HirVar hv = ci.hiddenCells.get(i);
            WasmFuncEmitter.VarSlot slot = cctx.vars.get(hv.id());
            if (slot != null && slot.isCell()) {
                c.localGet(thisLocal)
                        .refAsNonNull()
                        .localGet(slot.localIdx())
                        .structSet(ci.structTypeIdx, ci.hiddenCellBaseFieldIdx + i);
            } else if (slot != null && WasmProgramLinker.isValueCapture(hv)) {
                c.localGet(thisLocal).refAsNonNull().localGet(slot.localIdx());
                fe.convertRawValue(slot.kind(), hv.primitiveKind(), line);
                c.structSet(ci.structTypeIdx, ci.hiddenCellBaseFieldIdx + i);
            }
        }
    }
}
