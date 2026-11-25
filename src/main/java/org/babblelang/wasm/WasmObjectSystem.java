package org.babblelang.wasm;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.runtime.ClassShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closed-world WebAssembly 3.0 object model for Babble {@code class}, strictly immutable
 * {@code record} ({@code BIP025-D11}), {@code object} singletons, {@code interface}s,
 * {@code i64}/word-array interface bitsets ({@code is I} and single-mask {@code BIP022}
 * {@code is (A & B)}), typed closed-world {@code $ITable_I} structs, and single-implementer
 * devirtualization ({@code BIP025-P2}, {@code BIP025-D7}).
 */
public final class WasmObjectSystem {
    public record ITableSlot(
            int slotIdx,
            String memberName,
            LoweringTargets.FunctionSignature sig,
            int wasmSigTypeIdx,
            boolean hasOptMask
    ) {
    }

    public static final class IfaceInfo {
        public final int ifaceIdx;
        public final WasmProgramLinker.ModuleLinkInfo modInfo;
        public final HirInterface hir;
        public final List<ITableSlot> slots = new ArrayList<>();
        public final Map<String, ITableSlot> slotByName = new LinkedHashMap<>();
        public final List<ClassInfo> implementers = new ArrayList<>();
        public int itableStructType = -1;
        public int ifaceValGlobalIdx = -1;
        public int staticInitDoneGlobalIdx = -1;

        IfaceInfo(int ifaceIdx, WasmProgramLinker.ModuleLinkInfo modInfo, HirInterface hir) {
            this.ifaceIdx = ifaceIdx;
            this.modInfo = modInfo;
            this.hir = hir;
        }
    }

    public static final class ClassInfo {
        public final int classId;
        public final WasmProgramLinker.ModuleLinkInfo modInfo;
        public final HirClass hir;
        public final ClassShape shape;
        public final int structTypeIdx;
        public final int[] memberToStructField; // -1 if method
        public final PrimitiveKind[] memberKinds;
        public final boolean[] fieldMutable;
        public final int defMaskFieldIdx; // -1 if none
        public final int captureBaseFieldIdx;
        public final boolean hasCaptures;
        public final long ifaceBits;
        public final long[] ifaceWords; // null if <= 64 interfaces
        public final Set<IfaceInfo> implementedIfaces = new LinkedHashSet<>();

        public int vtableGlobalIdx;
        public int classValGlobalIdx;
        public int classValStructType;
        public int singletonObjGlobalIdx = -1; // >= 0 for `object`
        public int staticInitDoneGlobalIdx = -1;

        public WasmModule.FuncDef directCtorDef;
        public int directCtorIdx;
        public WasmModule.FuncDef genericCtorDef;
        public int genericCtorIdx;
        public boolean ctorHasOptMask;

        public WasmModule.FuncDef equalsDef;
        public WasmModule.FuncDef hashDef;
        public WasmModule.FuncDef toStringDef;
        public WasmModule.FuncDef getMemberDef;
        public WasmModule.FuncDef setMemberDef;
        public WasmModule.FuncDef opDef;

        public final Map<String, Integer> boundMethodGenericFns = new LinkedHashMap<>();

        ClassInfo(int classId, WasmProgramLinker.ModuleLinkInfo modInfo, HirClass hir, int structTypeIdx,
                  int[] memberToStructField, PrimitiveKind[] memberKinds, boolean[] fieldMutable,
                  int defMaskFieldIdx, int captureBaseFieldIdx, long ifaceBits, long[] ifaceWords) {
            this.classId = classId;
            this.modInfo = modInfo;
            this.hir = hir;
            this.shape = hir.shape();
            this.structTypeIdx = structTypeIdx;
            this.memberToStructField = memberToStructField;
            this.memberKinds = memberKinds;
            this.fieldMutable = fieldMutable;
            this.defMaskFieldIdx = defMaskFieldIdx;
            this.captureBaseFieldIdx = captureBaseFieldIdx;
            this.hasCaptures = !hir.capturedVars().isEmpty();
            this.ifaceBits = ifaceBits;
            this.ifaceWords = ifaceWords;
        }
    }

    private final WasmProgramLinker linker;
    private final WasmModule mod;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmRuntimeOpsGen ops;

    private final List<IfaceInfo> allIfaces = new ArrayList<>();
    private final Map<String, IfaceInfo> ifacesByTypeId = new LinkedHashMap<>();
    private final Map<String, IfaceInfo> ifacesByJvmName = new LinkedHashMap<>();
    private final Map<Long, IfaceInfo> ifacesByLocal = new LinkedHashMap<>();

    private final List<ClassInfo> allClasses = new ArrayList<>();
    private final Map<String, ClassInfo> classesByTypeId = new LinkedHashMap<>();
    private final Map<String, ClassInfo> classesByJvmName = new LinkedHashMap<>();
    private final Map<Long, ClassInfo> classesByLocal = new LinkedHashMap<>();

    public final int gClassVals;
    private final WasmModule.FuncDef fnInitObjectsDef;
    public final int fnInitObjects;

    public WasmObjectSystem(WasmProgramLinker linker) {
        this.linker = linker;
        this.mod = linker.module();
        this.ts = linker.types();
        this.rt = linker.runtime();
        this.ops = linker.ops();
        this.gClassVals = mod.addGlobal("G_CLASS_VALS", WasmValType.refNull(ts.anyArrType), true,
                ts.defaultConstExpr(WasmValType.refNull(ts.anyArrType)));
        this.fnInitObjectsDef = mod.addFunction("init_objects", ts.internFuncType(List.of(), List.of()));
        this.fnInitObjects = fnInitObjectsDef.funcIndex();
    }

    public static long localKey(int modIdx, int id) {
        return (((long) modIdx) << 32) | (id & 0xFFFF_FFFFL);
    }

    public ClassInfo classByLocal(int modIdx, int classId) {
        return classesByLocal.get(localKey(modIdx, classId));
    }

    public ClassInfo classByTypeId(String typeId) {
        return typeId != null ? classesByTypeId.get(typeId) : null;
    }

    public ClassInfo classByJvmName(String jvmName) {
        return jvmName != null ? classesByJvmName.get(jvmName) : null;
    }

    public ClassInfo resolveClass(int modIdx, int classId, String typeId, String jvmName) {
        if (classId >= 0) {
            ClassInfo ci = classByLocal(modIdx, classId);
            if (ci != null) return ci;
        }
        if (typeId != null) {
            ClassInfo ci = classByTypeId(typeId);
            if (ci != null) return ci;
        }
        return classByJvmName(jvmName);
    }

    public IfaceInfo ifaceByLocal(int modIdx, int ifaceId) {
        return ifacesByLocal.get(localKey(modIdx, ifaceId));
    }

    public IfaceInfo ifaceByTypeId(String typeId) {
        return typeId != null ? ifacesByTypeId.get(typeId) : null;
    }

    public IfaceInfo ifaceByJvmName(String jvmName) {
        return jvmName != null ? ifacesByJvmName.get(jvmName) : null;
    }

    public List<ClassInfo> allClasses() {
        return allClasses;
    }

    public List<IfaceInfo> allIfaces() {
        return allIfaces;
    }

    // ---------------------------------------------------------------- Phase 1: Declare all interfaces & classes

    public void declareAll(List<WasmProgramLinker.ModuleLinkInfo> modules) {
        // Step 1: Assign dense ifaceIdx to every HirInterface across all modules
        for (WasmProgramLinker.ModuleLinkInfo m : modules) {
            for (HirInterface hi : m.input.hir().interfaces().values()) {
                int idx = allIfaces.size();
                IfaceInfo ii = new IfaceInfo(idx, m, hi);
                allIfaces.add(ii);
                if (hi.typeId() != null) ifacesByTypeId.put(hi.typeId(), ii);
                if (hi.jvmName() != null) ifacesByJvmName.put(hi.jvmName(), ii);
                ifacesByLocal.put(localKey(m.index, hi.id()), ii);
            }
        }

        // Step 2: Build $ITable_I structs for each interface
        for (IfaceInfo ii : allIfaces) {
            LoweringTargets.TypeHierarchyInfo thi = findHierarchy(modules, ii.hir.typeId());
            List<WasmTypeDef.Field> itableFields = new ArrayList<>();
            if (thi != null && thi.interfaceMembers() != null) {
                for (Map.Entry<String, LoweringTargets.MemberSpec> e : thi.interfaceMembers().entrySet()) {
                    LoweringTargets.MemberSpec ms = e.getValue();
                    if (!ms.callable() || ms.declaredSignature() == null) {
                        continue;
                    }
                    LoweringTargets.FunctionSignature sig = ms.declaredSignature();
                    int slotIdx = ii.slots.size();
                    int wasmSig = itableMethodSig(sig);
                    ITableSlot slot = new ITableSlot(slotIdx, e.getKey(), sig, wasmSig, sig.hasOptionalMask());
                    ii.slots.add(slot);
                    ii.slotByName.put(e.getKey(), slot);
                    itableFields.add(WasmTypeSystem.field("m_" + e.getKey(), WasmValType.ref(wasmSig), false));
                }
            }
            ii.itableStructType = mod.addStructType("ITable_" + ii.ifaceIdx + "_" + WasmProgramLinker.sanitizeName(ii.hir.shape().name()),
                    true, ts.itableBaseType, itableFields);
            ii.ifaceValGlobalIdx = mod.addGlobal("G_IFACE_" + ii.ifaceIdx, WasmValType.refNull(ts.ifaceValType), true,
                    ts.defaultConstExpr(WasmValType.refNull(ts.ifaceValType)));
            ii.staticInitDoneGlobalIdx = mod.addGlobal("G_IFACE_INIT_" + ii.ifaceIdx, WasmValType.I32, true,
                    List.of(new WasmInstr.I32Const(0)));
        }

        // Step 3: Declare every HirClass (struct type, bitset, vtable/classVal globals, ctor & vtable hook signatures)
        for (WasmProgramLinker.ModuleLinkInfo m : modules) {
            for (HirClass hc : m.input.hir().classes().values()) {
                declareClass(modules, m, hc);
            }
        }
    }

    private LoweringTargets.TypeHierarchyInfo findHierarchy(List<WasmProgramLinker.ModuleLinkInfo> modules, String typeId) {
        if (typeId == null) return null;
        for (WasmProgramLinker.ModuleLinkInfo m : modules) {
            LoweringTargets.TypeHierarchyInfo info = m.input.hir().typeHierarchy(typeId);
            if (info != null) return info;
        }
        return null;
    }

    private int itableMethodSig(LoweringTargets.FunctionSignature sig) {
        List<WasmValType> params = new ArrayList<>();
        params.add(WasmValType.ref(ts.objectType));
        for (PrimitiveKind pk : sig.parameterKinds()) {
            params.add(ts.valTypeFor(pk));
        }
        if (sig.hasOptionalMask()) {
            params.add(WasmValType.I32);
        }
        List<WasmValType> results = List.of(ts.valTypeFor(sig.returnKind()));
        return ts.internFuncType(params, results);
    }

    private void declareClass(List<WasmProgramLinker.ModuleLinkInfo> modules, WasmProgramLinker.ModuleLinkInfo m, HirClass hc) {
        int classId = allClasses.size();
        ClassShape shape = hc.shape();
        boolean isRecord = shape.isValue();

        // Determine which members are parameter-backed (strictly immutable on records!)
        boolean[] isParamMember = new boolean[shape.memberCount()];
        for (int p = 0; p < shape.parameters().length; p++) {
            int mi = shape.parameterMember(p);
            if (mi >= 0 && mi < isParamMember.length) {
                isParamMember[mi] = true;
            }
        }

        List<WasmTypeDef.Field> structFields = new ArrayList<>();
        structFields.add(WasmTypeSystem.field("vtable", WasmValType.ref(ts.vtableType), false));

        int[] memberToField = new int[shape.memberCount()];
        PrimitiveKind[] memberKinds = new PrimitiveKind[shape.memberCount()];
        boolean[] fieldMutable = new boolean[shape.memberCount()];
        for (int i = 0; i < shape.memberCount(); i++) {
            PrimitiveKind mk = i < hc.memberKinds().length && hc.memberKinds()[i] != null
                    ? hc.memberKinds()[i] : PrimitiveKind.NONE;
            memberKinds[i] = mk;
            if (shape.isMethod(i)) {
                memberToField[i] = -1;
                fieldMutable[i] = false;
            } else {
                memberToField[i] = structFields.size();
                // BIP025-D11: ALL record parameter fields (and fastRecord fields) are strictly immutable!
                boolean mut = !isRecord || (!isParamMember[i] && !hc.fastRecord());
                fieldMutable[i] = mut;
                structFields.add(WasmTypeSystem.field("f_" + shape.member(i), ts.valTypeFor(mk), mut));
            }
        }

        int defMaskField = -1;
        if (!isRecord && hc.primDefCount() > 0) {
            defMaskField = structFields.size();
            structFields.add(WasmTypeSystem.field("$defMask", WasmValType.I32, true));
        }

        int capBase = structFields.size();
        for (int i = 0; i < hc.capturedVars().size(); i++) {
            HirVar cv = hc.capturedVars().get(i);
            structFields.add(WasmTypeSystem.field("$cap" + i, WasmValType.ref(ts.cellTypeFor(cv.primitiveKind())), false));
        }

        String cname = "C" + classId + "_" + WasmProgramLinker.sanitizeName(shape.name());
        int structTypeIdx = mod.addStructType(cname, true, ts.objectType, structFields);

        // Compute transitive interface bitset (BIP025-D7)
        long bits = 0L;
        int wordCount = allIfaces.size() > 64 ? (allIfaces.size() + 63) / 64 : 0;
        long[] words = wordCount > 0 ? new long[wordCount] : null;
        Set<IfaceInfo> implSet = new LinkedHashSet<>();
        LoweringTargets.TypeHierarchyInfo thi = findHierarchy(modules, hc.typeId());
        if (thi != null && thi.allBabbleInterfaceIds() != null) {
            for (String iid : thi.allBabbleInterfaceIds()) {
                IfaceInfo ii = ifacesByTypeId.get(iid);
                if (ii != null) {
                    implSet.add(ii);
                    if (ii.ifaceIdx < 64) {
                        bits |= (1L << ii.ifaceIdx);
                    }
                    if (words != null) {
                        words[ii.ifaceIdx / 64] |= (1L << (ii.ifaceIdx % 64));
                    }
                }
            }
        }

        ClassInfo ci = new ClassInfo(classId, m, hc, structTypeIdx, memberToField, memberKinds, fieldMutable,
                defMaskField, capBase, bits, words);
        ci.implementedIfaces.addAll(implSet);
        for (IfaceInfo ii : implSet) {
            ii.implementers.add(ci);
        }

        ci.vtableGlobalIdx = mod.addGlobal("G_VT_" + cname, WasmValType.refNull(ts.vtableType), true,
                ts.defaultConstExpr(WasmValType.refNull(ts.vtableType)));
        ci.classValGlobalIdx = mod.addGlobal("G_CLS_" + cname, WasmValType.refNull(ts.classValType), true,
                ts.defaultConstExpr(WasmValType.refNull(ts.classValType)));
        if (ci.hasCaptures && !hc.isObject()) {
            List<WasmTypeDef.Field> cvFields = new ArrayList<>();
            cvFields.add(WasmTypeSystem.field("genericCall", WasmValType.ref(ts.genericCallSigType), false));
            cvFields.add(WasmTypeSystem.field("name", WasmValType.ref(ts.stringType), false));
            cvFields.add(WasmTypeSystem.field("fnId", WasmValType.I32, false));
            cvFields.add(WasmTypeSystem.field("classId", WasmValType.I32, false));
            cvFields.add(WasmTypeSystem.field("vtable", WasmValType.ref(ts.vtableType), false));
            cvFields.add(WasmTypeSystem.field("statics", WasmValType.refNull(ts.anyArrType), false));
            for (int i = 0; i < hc.capturedVars().size(); i++) {
                HirVar cv = hc.capturedVars().get(i);
                cvFields.add(WasmTypeSystem.field("$cap" + i, WasmValType.ref(ts.cellTypeFor(cv.primitiveKind())), false));
            }
            ci.classValStructType = mod.addStructType(cname + "_ClassVal", true, ts.classValType, cvFields);
        } else {
            ci.classValStructType = ts.classValType;
        }
        ci.staticInitDoneGlobalIdx = mod.addGlobal("G_CLS_INIT_" + cname, WasmValType.I32, true,
                List.of(new WasmInstr.I32Const(0)));
        if (hc.isObject()) {
            ci.singletonObjGlobalIdx = mod.addGlobal("G_OBJ_" + cname, WasmValType.refNull(structTypeIdx), true,
                    ts.defaultConstExpr(WasmValType.refNull(structTypeIdx)));
        }

        // Constructor signatures
        int optCount = 0;
        for (HirParam p : hc.params()) {
            if (p.defaultValue() != null) optCount++;
        }
        ci.ctorHasOptMask = optCount > 0 && optCount <= 32 && hc.params().size() <= 32;
        List<WasmValType> ctorParams = new ArrayList<>();
        List<String> ctorParamNames = new ArrayList<>();
        for (int i = 0; i < hc.capturedVars().size(); i++) {
            HirVar cv = hc.capturedVars().get(i);
            ctorParams.add(WasmValType.ref(ts.cellTypeFor(cv.primitiveKind())));
            ctorParamNames.add("$cap" + i);
        }
        for (HirParam p : hc.params()) {
            ctorParams.add(ts.valTypeFor(p.kind()));
            ctorParamNames.add(p.name());
        }
        if (ci.ctorHasOptMask) {
            ctorParams.add(WasmValType.I32);
            ctorParamNames.add("$optMask");
        }
        int ctorSig = ts.internFuncType(ctorParams, List.of(WasmValType.ref(structTypeIdx)));
        ci.directCtorDef = mod.addFunction(cname + "$new", ctorSig, ctorParamNames);
        ci.directCtorIdx = ci.directCtorDef.funcIndex();

        ci.genericCtorDef = mod.addFunction(cname + "$ctor_generic", ts.genericCallSigType, List.of("self", "args", "names"));
        ci.genericCtorIdx = ci.genericCtorDef.funcIndex();
        mod.declareFuncRef(ci.genericCtorIdx);

        // VTable virtual hooks
        ci.equalsDef = mod.addFunction(cname + "$equals", ts.eqFnSigType, List.of("self", "other"));
        ci.hashDef = mod.addFunction(cname + "$hashCode", ts.hashFnSigType, List.of("self"));
        ci.toStringDef = mod.addFunction(cname + "$toString", ts.toStrFnSigType, List.of("self"));
        ci.getMemberDef = mod.addFunction(cname + "$getMember", ts.getMemFnSigType, List.of("self", "name"));
        ci.setMemberDef = mod.addFunction(cname + "$setMember", ts.setMemFnSigType, List.of("self", "name", "val"));
        ci.opDef = mod.addFunction(cname + "$op", ts.opFnSigType, List.of("self", "op", "rhs"));
        mod.declareFuncRef(ci.equalsDef.funcIndex());
        mod.declareFuncRef(ci.hashDef.funcIndex());
        mod.declareFuncRef(ci.toStringDef.funcIndex());
        mod.declareFuncRef(ci.getMemberDef.funcIndex());
        mod.declareFuncRef(ci.setMemberDef.funcIndex());
        mod.declareFuncRef(ci.opDef.funcIndex());

        allClasses.add(ci);
        if (hc.typeId() != null) classesByTypeId.put(hc.typeId(), ci);
        if (hc.jvmName() != null) classesByJvmName.put(hc.jvmName(), ci);
        classesByLocal.put(localKey(m.index, hc.id()), ci);
    }

    // ---------------------------------------------------------------- Phase 2: Emit constructors, vtable hooks, itables, init_objects

    public void emitAll(WasmFuncEmitter fe, WasmCallEmitter ce) {
        for (ClassInfo ci : allClasses) {
            emitClassBoundMethods(ci, ce);
            emitClassConstructor(ci, fe);
            emitGenericCtorTrampoline(ci);
            emitEqualsHook(ci);
            emitHashCodeHook(ci);
            emitToStringHook(ci);
            emitGetMemberHook(ci);
            emitSetMemberHook(ci);
            emitOpHook(ci);
        }
        emitInitObjects();
    }

    private void emitClassBoundMethods(ClassInfo ci, WasmCallEmitter ce) {
        for (Map.Entry<String, Integer> e : ci.hir.methodFunctionIds().entrySet()) {
            WasmProgramLinker.FuncInfo mfi = ci.modInfo.functions.get(e.getValue());
            if (mfi == null) continue;
            WasmModule.FuncDef bm = mod.addFunction(
                    "C" + ci.classId + "_bm_" + WasmProgramLinker.sanitizeName(e.getKey()),
                    ts.genericCallSigType, List.of("self", "args", "names"));
            mod.declareFuncRef(bm.funcIndex());
            ce.emitBoundMethodTrampoline(bm, ci, mfi);
            ci.boundMethodGenericFns.put(e.getKey(), bm.funcIndex());
        }
        for (Map.Entry<String, List<Integer>> e : ci.hir.overloadMethodFunctionIds().entrySet()) {
            if (ci.boundMethodGenericFns.containsKey(e.getKey()) || e.getValue().isEmpty()) continue;
            // Pick 1-arg reference overload if present (e.g. Appendable.append(CharSequence)), else alt 0
            WasmProgramLinker.FuncInfo mfi = ci.modInfo.functions.get(e.getValue().get(0));
            for (int id : e.getValue()) {
                WasmProgramLinker.FuncInfo cand = ci.modInfo.functions.get(id);
                if (cand != null && cand.hir.params().size() == 1 && !cand.hir.params().get(0).kind().isPrimitive()) {
                    mfi = cand;
                    break;
                }
            }
            if (mfi == null) continue;
            WasmModule.FuncDef bm = mod.addFunction(
                    "C" + ci.classId + "_bm_" + WasmProgramLinker.sanitizeName(e.getKey()),
                    ts.genericCallSigType, List.of("self", "args", "names"));
            mod.declareFuncRef(bm.funcIndex());
            ce.emitBoundMethodTrampoline(bm, ci, mfi);
            ci.boundMethodGenericFns.put(e.getKey(), bm.funcIndex());
        }
    }

    private void emitClassConstructor(ClassInfo ci, WasmFuncEmitter fe) {
        fe.emitClassConstructorBody(ci);
    }

    private void emitGenericCtorTrampoline(ClassInfo ci) {
        WasmModule.FuncDef fn = ci.genericCtorDef;
        WasmCodeBuilder c = fn.code();
        if (ci.hir.isObject()) {
            rt.emitStringLit(c, "Cannot instantiate object : " + ci.shape.name());
            c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable();
            return;
        }
        int paramCount = ci.hir.params().size();
        int argLen = fn.addLocal("argLen", WasmValType.I32, 3);
        int optMask = fn.addLocal("optMask", WasmValType.I32, 3);
        c.localGet(1).arrayLen().localSet(argLen);

        int[] pLocals = new int[paramCount];
        for (int p = 0; p < paramCount; p++) {
            pLocals[p] = fn.addLocal("p" + p, ts.anyRef(), 3);
            c.globalGet(rt.gMissing).localSet(pLocals[p]);
        }

        int idx = fn.addLocal("idx", WasmValType.I32, 3);
        int nameAny = fn.addLocal("nameAny", ts.anyRef(), 3);
        int valAny = fn.addLocal("valAny", ts.anyRef(), 3);
        c.localGet(2).refIsNull().if_();
        for (int p = 0; p < paramCount; p++) {
            c.i32Const(p).localGet(argLen).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(1).i32Const(p).arrayGet(ts.anyArrType).localSet(pLocals[p])
                    .end();
        }
        c.else_();
        c.i32Const(0).localSet(idx);
        c.loop()
                .localGet(idx).localGet(argLen).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(1).localGet(idx).arrayGet(ts.anyArrType).localSet(valAny)
                .localGet(2).refAsNonNull().localGet(idx).arrayGet(ts.anyArrType).localTee(nameAny)
                .refIsNull().if_();
        for (int p = 0; p < paramCount; p++) {
            c.localGet(idx).i32Const(p).op(WasmInstr.SimpleOp.I32_EQ).if_()
                    .localGet(valAny).localSet(pLocals[p]).end();
        }
        c.else_();
        for (int p = 0; p < paramCount; p++) {
            c.localGet(nameAny).refCast(ts.stringType);
            rt.emitStringLit(c, ci.hir.params().get(p).name());
            c.call(rt.fnStrEq).if_().localGet(valAny).localSet(pLocals[p]).end();
        }
        c.end()
                .localGet(idx).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(idx)
                .br(1).end().end();
        c.end();

        if (ci.hasCaptures) {
            int cvSelf = fn.addLocal("cvSelf", WasmValType.refNull(ci.classValStructType), 3);
            c.localGet(0).refCast(ci.classValStructType).localSet(cvSelf);
            for (int i = 0; i < ci.hir.capturedVars().size(); i++) {
                c.localGet(cvSelf).refAsNonNull().structGet(ci.classValStructType, 6 + i);
            }
        }

        int optBit = 0;
        for (int p = 0; p < paramCount; p++) {
            HirParam hp = ci.hir.params().get(p);
            if (hp.defaultValue() != null) {
                c.localGet(pLocals[p]).globalGet(rt.gMissing).refEq().if_(ts.valTypeFor(hp.kind()))
                        .localGet(optMask).i32Const(1 << optBit).op(WasmInstr.SimpleOp.I32_OR).localSet(optMask);
                ts.emitDefaultValue(c, ts.valTypeFor(hp.kind()));
                c.else_();
                c.localGet(pLocals[p]);
                if (hp.kind().isPrimitive()) c.i32Const(ci.hir.line()).call(rt.unboxFuncFor(hp.kind()));
                c.end();
                optBit++;
            } else {
                c.localGet(pLocals[p]);
                if (hp.kind().isPrimitive()) c.i32Const(ci.hir.line()).call(rt.unboxFuncFor(hp.kind()));
            }
        }
        if (ci.ctorHasOptMask) {
            c.localGet(optMask);
        }
        c.call(ci.directCtorIdx);
    }

    private void emitEqualsHook(ClassInfo ci) {
        WasmCodeBuilder c = ci.equalsDef.code();
        // Custom equals(other) method?
        Integer eqMethodId = ci.hir.methodFunctionIds().get("equals");
        if (eqMethodId != null) {
            WasmProgramLinker.FuncInfo mfi = ci.modInfo.functions.get(eqMethodId);
            if (mfi != null && mfi.hir.params().size() == 1) {
                c.localGet(0).refCast(ci.structTypeIdx);
                c.localGet(1);
                if (mfi.hir.params().get(0).kind().isPrimitive()) {
                    c.i32Const(ci.hir.line()).call(rt.unboxFuncFor(mfi.hir.params().get(0).kind()));
                }
                if (mfi.hasOptMaskParam) c.i32Const(0);
                c.call(mfi.directFuncIdx);
                if (mfi.hir.returnKind() == PrimitiveKind.NONE) {
                    c.call(rt.fnTruth);
                }
                return;
            }
        }
        c.localGet(0).localGet(1).refEq().if_().i32Const(1).return_().end();
        if (!ci.shape.isValue()) {
            c.i32Const(0);
            return;
        }
        // Structural record equality
        c.localGet(1).refTest(ci.structTypeIdx).op(WasmInstr.SimpleOp.I32_EQZ).if_().i32Const(0).return_().end();
        int a = ci.equalsDef.addLocal("a", WasmValType.refNull(ci.structTypeIdx), 2);
        int b = ci.equalsDef.addLocal("b", WasmValType.refNull(ci.structTypeIdx), 2);
        c.localGet(0).refCast(ci.structTypeIdx).localSet(a);
        c.localGet(1).refCast(ci.structTypeIdx).localSet(b);
        for (int i = 0; i < ci.shape.memberCount(); i++) {
            int fi = ci.memberToStructField[i];
            if (fi < 0) continue;
            PrimitiveKind mk = ci.memberKinds[i];
            c.localGet(a).refAsNonNull().structGet(ci.structTypeIdx, fi)
                    .localGet(b).refAsNonNull().structGet(ci.structTypeIdx, fi);
            switch (mk) {
                case BYTE, SHORT, INT, BOOL, CHAR -> c.op(WasmInstr.SimpleOp.I32_NE);
                case LONG -> c.op(WasmInstr.SimpleOp.I64_NE);
                case FLOAT -> c.call(rt.fnF32Compare).i32Const(0).op(WasmInstr.SimpleOp.I32_NE);
                case DOUBLE -> c.call(rt.fnF64Compare).i32Const(0).op(WasmInstr.SimpleOp.I32_NE);
                case NONE -> c.call(rt.fnEquals).op(WasmInstr.SimpleOp.I32_EQZ);
            }
            c.if_().i32Const(0).return_().end();
        }
        c.i32Const(1);
    }

    private void emitHashCodeHook(ClassInfo ci) {
        WasmCodeBuilder c = ci.hashDef.code();
        Integer hMethodId = ci.hir.methodFunctionIds().get("hashCode");
        if (hMethodId != null) {
            WasmProgramLinker.FuncInfo mfi = ci.modInfo.functions.get(hMethodId);
            if (mfi != null && mfi.hir.params().isEmpty()) {
                c.localGet(0).refCast(ci.structTypeIdx);
                if (mfi.hasOptMaskParam) c.i32Const(0);
                c.call(mfi.directFuncIdx);
                if (mfi.hir.returnKind() == PrimitiveKind.NONE) {
                    c.i32Const(ci.hir.line()).call(rt.fnUnboxInt);
                }
                return;
            }
        }
        if (!ci.shape.isValue()) {
            c.i32Const(ci.classId * 31 + 17);
            return;
        }
        int self = ci.hashDef.addLocal("s", WasmValType.refNull(ci.structTypeIdx), 1);
        int h = ci.hashDef.addLocal("h", WasmValType.I32, 1);
        c.localGet(0).refCast(ci.structTypeIdx).localSet(self);
        c.i32Const(ci.classId + 1).localSet(h);
        for (int i = 0; i < ci.shape.memberCount(); i++) {
            int fi = ci.memberToStructField[i];
            if (fi < 0) continue;
            PrimitiveKind mk = ci.memberKinds[i];
            c.localGet(h).i32Const(31).op(WasmInstr.SimpleOp.I32_MUL);
            c.localGet(self).refAsNonNull().structGet(ci.structTypeIdx, fi);
            if (mk.isPrimitive()) {
                c.call(rt.boxFuncFor(mk));
            }
            c.call(rt.fnHashCode).op(WasmInstr.SimpleOp.I32_ADD).localSet(h);
        }
        c.localGet(h);
    }

    private void emitToStringHook(ClassInfo ci) {
        WasmCodeBuilder c = ci.toStringDef.code();
        Integer tsMethodId = ci.hir.methodFunctionIds().get("toString");
        if (tsMethodId != null) {
            WasmProgramLinker.FuncInfo mfi = ci.modInfo.functions.get(tsMethodId);
            if (mfi != null && mfi.hir.params().isEmpty()) {
                c.localGet(0).refCast(ci.structTypeIdx);
                if (mfi.hasOptMaskParam) c.i32Const(0);
                c.call(mfi.directFuncIdx);
                if (mfi.hir.returnKind().isPrimitive()) {
                    c.call(rt.boxFuncFor(mfi.hir.returnKind()));
                }
                c.call(rt.fnFormat);
                return;
            }
        }
        if (!ci.shape.isValue()) {
            rt.emitStringLit(c, ci.shape.name());
            return;
        }
        // Record format matching BabbleObjects.toString: Name(f0: v0, f1: v1)
        int self = ci.toStringDef.addLocal("s", WasmValType.refNull(ci.structTypeIdx), 1);
        int acc = ci.toStringDef.addLocal("acc", WasmValType.refNull(ts.stringType), 1);
        c.localGet(0).refCast(ci.structTypeIdx).localSet(self);
        rt.emitStringLit(c, ci.shape.name() + "(");
        c.localSet(acc);
        boolean first = true;
        for (int i = 0; i < ci.shape.memberCount(); i++) {
            int fi = ci.memberToStructField[i];
            if (fi < 0 || ci.shape.isPrivate(i)) continue;
            c.localGet(acc).refAsNonNull();
            rt.emitStringLit(c, (first ? "" : ", ") + ci.shape.member(i) + ": ");
            c.call(rt.fnStrConcat);
            c.localGet(self).refAsNonNull().structGet(ci.structTypeIdx, fi);
            PrimitiveKind mk = ci.memberKinds[i];
            if (mk.isPrimitive()) {
                c.call(rt.boxFuncFor(mk));
            }
            c.call(rt.fnFormat).call(rt.fnStrConcat).localSet(acc);
            first = false;
        }
        c.localGet(acc).refAsNonNull();
        rt.emitStringLit(c, ")");
        c.call(rt.fnStrConcat);
    }

    private void emitGetMemberHook(ClassInfo ci) {
        WasmCodeBuilder c = ci.getMemberDef.code();
        int self = ci.getMemberDef.addLocal("s", WasmValType.refNull(ci.structTypeIdx), 2);
        c.localGet(0).refCast(ci.structTypeIdx).localSet(self);
        for (int i = 0; i < ci.shape.memberCount(); i++) {
            String mname = ci.shape.member(i);
            c.localGet(1);
            rt.emitStringLit(c, mname);
            c.call(rt.fnStrEq).if_();
            int fi = ci.memberToStructField[i];
            if (fi >= 0) {
                c.localGet(self).refAsNonNull().structGet(ci.structTypeIdx, fi);
                if (ci.memberKinds[i].isPrimitive()) {
                    c.call(rt.boxFuncFor(ci.memberKinds[i]));
                }
                c.return_();
            } else {
                Integer bmFn = ci.boundMethodGenericFns.get(mname);
                if (bmFn != null) {
                    c.refFunc(bmFn);
                    rt.emitStringLit(c, mname);
                    c.i32Const(i)
                            .localGet(0)
                            .structNew(ts.boundMethodType)
                            .return_();
                }
            }
            c.end();
        }
        rt.emitStringLit(c, "No such member in " + ci.shape.name() + " : ");
        c.localGet(1).call(rt.fnStrConcat).i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable();
    }

    private void emitSetMemberHook(ClassInfo ci) {
        WasmCodeBuilder c = ci.setMemberDef.code();
        int self = ci.setMemberDef.addLocal("s", WasmValType.refNull(ci.structTypeIdx), 3);
        c.localGet(0).refCast(ci.structTypeIdx).localSet(self);
        for (int i = 0; i < ci.shape.memberCount(); i++) {
            int fi = ci.memberToStructField[i];
            if (fi < 0) continue;
            c.localGet(1);
            rt.emitStringLit(c, ci.shape.member(i));
            c.call(rt.fnStrEq).if_();
            if (!ci.fieldMutable[i]) {
                rt.emitStringLit(c, "Cannot reassign field of record " + ci.shape.name() + " : " + ci.shape.member(i));
                c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable();
            } else {
                c.localGet(self).refAsNonNull().localGet(2);
                if (ci.memberKinds[i].isPrimitive()) {
                    c.i32Const(ci.hir.line()).call(rt.unboxFuncFor(ci.memberKinds[i]));
                }
                c.structSet(ci.structTypeIdx, fi);
                c.localGet(2).return_();
            }
            c.end();
        }
        rt.emitStringLit(c, "No such member in " + ci.shape.name() + " : ");
        c.localGet(1).call(rt.fnStrConcat).i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable();
    }

    private void emitOpHook(ClassInfo ci) {
        WasmCodeBuilder c = ci.opDef.code();
        String[] opsList = {"plus", "minus", "times", "div", "neg"};
        for (String opName : opsList) {
            Integer mId = ci.hir.methodFunctionIds().get(opName);
            if (mId == null) continue;
            WasmProgramLinker.FuncInfo mfi = ci.modInfo.functions.get(mId);
            if (mfi == null) continue;
            c.localGet(1);
            rt.emitStringLit(c, opName);
            c.call(rt.fnStrEq).if_();
            c.localGet(0).refCast(ci.structTypeIdx);
            if (!"neg".equals(opName) && !mfi.hir.params().isEmpty()) {
                c.localGet(2);
                PrimitiveKind pk = mfi.hir.params().get(0).kind();
                if (pk.isPrimitive()) {
                    c.i32Const(ci.hir.line()).call(rt.unboxFuncFor(pk));
                }
            }
            if (mfi.hasOptMaskParam) c.i32Const(0);
            c.call(mfi.directFuncIdx);
            if (mfi.hir.returnKind().isPrimitive()) {
                c.call(rt.boxFuncFor(mfi.hir.returnKind()));
            }
            c.return_().end();
        }
        rt.emitStringLit(c, "Operator method not defined on " + ci.shape.name() + " : ");
        c.localGet(1).call(rt.fnStrConcat).i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable();
    }

    /** Builds `$ITable_I` thunk function adapting `(ref $Object)` -> `(ref $Struct_C)` and invoking the implementation. */
    private int emitITableThunk(ClassInfo ci, IfaceInfo ii, ITableSlot slot) {
        // Find implementing method: either C's own method or default method from ii / transitive interfaces
        WasmProgramLinker.FuncInfo impl = findClassOrDefaultImpl(ci, ii, slot.memberName());
        WasmModule.FuncDef thunk = mod.addFunction(
                "thunk_C" + ci.classId + "_I" + ii.ifaceIdx + "_" + WasmProgramLinker.sanitizeName(slot.memberName()),
                slot.wasmSigTypeIdx());
        mod.declareFuncRef(thunk.funcIndex());
        WasmCodeBuilder c = thunk.code();
        if (impl == null) {
            rt.emitStringLit(c, "Unimplemented interface method : " + ii.hir.shape().name() + "." + slot.memberName());
            c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable();
            return thunk.funcIndex();
        }

        // Receiver: class method expects (ref $Struct_C); interface default expects (ref $Object)
        if (impl.hir.ownerClassId() >= 0) {
            c.localGet(0).refCast(ci.structTypeIdx);
        } else {
            c.localGet(0);
        }
        PrimitiveKind[] slotParams = slot.sig().parameterKinds();
        for (int p = 0; p < impl.hir.params().size(); p++) {
            PrimitiveKind implPk = impl.hir.params().get(p).kind();
            if (p < slotParams.length) {
                c.localGet(1 + p);
                emitConvertValue(c, slotParams[p], implPk, ci.hir.line());
            } else {
                ts.emitDefaultValue(c, ts.valTypeFor(implPk));
            }
        }
        if (impl.hasOptMaskParam) {
            if (slot.hasOptMask()) {
                c.localGet(1 + slotParams.length);
            } else {
                c.i32Const(0);
            }
        }
        c.call(impl.directFuncIdx);
        emitConvertValue(c, impl.hir.returnKind(), slot.sig().returnKind(), ci.hir.line());
        return thunk.funcIndex();
    }

    private void emitConvertValue(WasmCodeBuilder c, PrimitiveKind from, PrimitiveKind to, int line) {
        if (from == to) return;
        if (to == PrimitiveKind.NONE) {
            c.call(rt.boxFuncFor(from));
        } else if (from == PrimitiveKind.NONE) {
            c.i32Const(line).call(rt.unboxFuncFor(to));
        } else if (!WasmFuncEmitter.emitPrimitiveWidening(c, from, to)) {
            c.call(rt.boxFuncFor(from)).i32Const(line).call(rt.unboxFuncFor(to));
        }
    }

    public WasmProgramLinker.FuncInfo findClassOrDefaultImpl(ClassInfo ci, IfaceInfo ii, String memberName) {
        Integer mId = ci.hir.methodFunctionIds().get(memberName);
        if (mId != null) {
            return ci.modInfo.functions.get(mId);
        }
        Integer dId = ii.hir.defaultFunctionIds().get(memberName);
        if (dId != null) {
            return ii.modInfo.functions.get(dId);
        }
        for (IfaceInfo imp : ci.implementedIfaces) {
            Integer id2 = imp.hir.defaultFunctionIds().get(memberName);
            if (id2 != null) {
                return imp.modInfo.functions.get(id2);
            }
        }
        return null;
    }

    private void emitInitObjects() {
        WasmCodeBuilder c = fnInitObjectsDef.code();

        // 1. Initialize $IfaceVal globals
        for (IfaceInfo ii : allIfaces) {
            c.i32Const(ii.ifaceIdx);
            rt.emitStringLit(c, ii.hir.shape().name());
            if (ii.hir.shape().staticCount() > 0) {
                c.i32Const(ii.hir.shape().staticCount()).arrayNewDefault(ts.anyArrType);
            } else {
                c.refNull(ts.anyArrType);
            }
            c.structNew(ts.ifaceValType).globalSet(ii.ifaceValGlobalIdx);
        }

        // 2. Initialize $VTable & $ClassVal globals for all classes
        int ifaceCount = allIfaces.size();
        for (ClassInfo ci : allClasses) {
            c.i32Const(ci.classId);
            rt.emitStringLit(c, ci.shape.name());
            c.i64Const(ci.ifaceBits);
            if (ci.ifaceWords != null) {
                for (long w : ci.ifaceWords) c.i64Const(w);
                c.arrayNewFixed(ts.i64ConstArrType, ci.ifaceWords.length);
            } else {
                c.refNull(ts.i64ConstArrType);
            }
            if (ifaceCount > 0 && !ci.implementedIfaces.isEmpty()) {
                for (int idx = 0; idx < ifaceCount; idx++) {
                    IfaceInfo ii = allIfaces.get(idx);
                    if (ci.implementedIfaces.contains(ii)) {
                        for (ITableSlot slot : ii.slots) {
                            int thunkIdx = emitITableThunk(ci, ii, slot);
                            c.refFunc(thunkIdx);
                        }
                        c.structNew(ii.itableStructType);
                    } else {
                        c.refNull(ts.itableBaseType);
                    }
                }
                c.arrayNewFixed(ts.itableArrType, ifaceCount);
            } else {
                c.refNull(ts.itableArrType);
            }
            c.i32Const(ci.shape.isValue() ? 1 : 0)
                    .refFunc(ci.equalsDef.funcIndex())
                    .refFunc(ci.hashDef.funcIndex())
                    .refFunc(ci.toStringDef.funcIndex())
                    .refFunc(ci.getMemberDef.funcIndex())
                    .refFunc(ci.setMemberDef.funcIndex())
                    .refFunc(ci.opDef.funcIndex())
                    .structNew(ts.vtableType)
                    .globalSet(ci.vtableGlobalIdx);

            // $ClassVal
            c.refFunc(ci.genericCtorIdx);
            rt.emitStringLit(c, ci.shape.name());
            c.i32Const(ci.classId)
                    .i32Const(ci.classId)
                    .globalGet(ci.vtableGlobalIdx).refAsNonNull();
            if (ci.shape.staticCount() > 0) {
                c.i32Const(ci.shape.staticCount()).arrayNewDefault(ts.anyArrType);
            } else {
                c.refNull(ts.anyArrType);
            }
            c.structNew(ts.classValType).globalSet(ci.classValGlobalIdx);
        }

        // 3. Populate G_CLASS_VALS for O(1) classof(x)
        if (!allClasses.isEmpty()) {
            for (ClassInfo ci : allClasses) {
                c.globalGet(ci.classValGlobalIdx);
            }
            c.arrayNewFixed(ts.anyArrType, allClasses.size()).globalSet(gClassVals);
        }
    }

    // ---------------------------------------------------------------- Interface bitset `is` & intersection `A & B` (BIP025-D7)

    /**
     * Emits {@code x is target} or single-mask {@code x is (A & B & ...)} leaving {@code i32} (0/1) on stack.
     * Consumes the boxed {@code (ref null $Any)} currently stored in {@code valLocal}.
     */
    public boolean emitNominalIsCheck(WasmCodeBuilder c, int valLocal, LoweringTargets.IsTarget target,
                                      List<LoweringTargets.IsTarget> intersectionTargets) {
        if (intersectionTargets != null && !intersectionTargets.isEmpty()) {
            // Fast single-mask i64.and + i64.eq when all members are interfaces with idx < 64 (BIP025-D7)!
            long mask = 0L;
            boolean allSmallIfaces = true;
            for (LoweringTargets.IsTarget t : intersectionTargets) {
                IfaceInfo ii = ifaceByTypeId(t.typeId());
                if (ii == null || ii.ifaceIdx >= 64) {
                    allSmallIfaces = false;
                    break;
                }
                mask |= (1L << ii.ifaceIdx);
            }
            if (allSmallIfaces && mask != 0L) {
                c.localGet(valLocal).refTest(ts.objectType).if_(WasmValType.I32)
                        .localGet(valLocal).refCast(ts.objectType)
                        .structGet(ts.objectType, 0)
                        .structGet(ts.vtableType, 2)
                        .i64Const(mask).op(WasmInstr.SimpleOp.I64_AND)
                        .i64Const(mask).op(WasmInstr.SimpleOp.I64_EQ)
                        .else_().i32Const(0).end();
                return true;
            }
            // General intersection: AND each member check
            for (int i = 0; i < intersectionTargets.size(); i++) {
                emitSingleTargetCheck(c, valLocal, intersectionTargets.get(i));
                if (i > 0) c.op(WasmInstr.SimpleOp.I32_AND);
            }
            return true;
        }
        if (target != null) {
            return emitSingleTargetCheck(c, valLocal, target);
        }
        return false;
    }

    private boolean emitSingleTargetCheck(WasmCodeBuilder c, int valLocal, LoweringTargets.IsTarget target) {
        if (target.kind() == ClassShape.Kind.INTERFACE) {
            IfaceInfo ii = ifaceByTypeId(target.typeId());
            if (ii == null && target.jvmInternalName() != null) ii = ifaceByJvmName(target.jvmInternalName());
            if (ii == null) {
                c.i32Const(0);
                return true;
            }
            c.localGet(valLocal).refTest(ts.objectType).if_(WasmValType.I32);
            if (ii.ifaceIdx < 64) {
                c.localGet(valLocal).refCast(ts.objectType)
                        .structGet(ts.objectType, 0)
                        .structGet(ts.vtableType, 2)
                        .i64Const(1L << ii.ifaceIdx).op(WasmInstr.SimpleOp.I64_AND)
                        .i64Const(0L).op(WasmInstr.SimpleOp.I64_NE);
            } else {
                c.localGet(valLocal).refCast(ts.objectType)
                        .structGet(ts.objectType, 0)
                        .structGet(ts.vtableType, 3).refAsNonNull()
                        .i32Const(ii.ifaceIdx / 64).arrayGet(ts.i64ConstArrType)
                        .i64Const(1L << (ii.ifaceIdx % 64)).op(WasmInstr.SimpleOp.I64_AND)
                        .i64Const(0L).op(WasmInstr.SimpleOp.I64_NE);
            }
            c.else_().i32Const(0).end();
            return true;
        }
        ClassInfo ci = classByTypeId(target.typeId());
        if (ci == null && target.jvmInternalName() != null) ci = classByJvmName(target.jvmInternalName());
        if (ci == null) {
            c.i32Const(0);
            return true;
        }
        c.localGet(valLocal).refTest(ci.structTypeIdx);
        return true;
    }
}
