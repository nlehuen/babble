package org.babblelang.wasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirCallArg;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;
import org.babblelang.runtime.ClassShape;

/**
 * Closed-world WebAssembly 3.0 object model for Babble {@code class}, strictly immutable {@code
 * record} ({@code BIP025-D11}), {@code object} singletons, {@code interface}s, {@code
 * i64}/word-array interface bitsets ({@code is I} and single-mask {@code BIP022} {@code is (A &
 * B)}), typed closed-world {@code $ITable_I} structs, and single-implementer devirtualization
 * ({@code BIP025-I2}, {@code BIP025-D7}).
 */
public final class WasmObjectSystem {
    public record ITableSlot(
            int slotIdx,
            String slotKey,
            String memberName,
            int overloadAltIndex,
            LoweringTargets.FunctionSignature sig,
            int wasmSigTypeIdx,
            boolean hasOptMask) {}

    public static final class IfaceInfo {
        public final int ifaceIdx;
        public final WasmProgramLinker.ModuleLinkInfo modInfo;
        public final HirInterface hir;
        public final List<ITableSlot> slots = new ArrayList<>();
        public final Map<String, ITableSlot> slotByName = new LinkedHashMap<>();
        public final List<ClassInfo> implementers = new ArrayList<>();
        public final Set<String> allSuperIfaceIds = new LinkedHashSet<>();
        public final List<HirVar> capturedVars = new ArrayList<>();
        public int capStructTypeIdx = -1;
        public int capSlotIdx = -1;
        public int latestCapEnvGlobalIdx = -1;
        public int itableStructType = -1;
        public int ifaceValGlobalIdx = -1;
        public int staticInitDoneGlobalIdx = -1;

        IfaceInfo(int ifaceIdx, WasmProgramLinker.ModuleLinkInfo modInfo, HirInterface hir) {
            this.ifaceIdx = ifaceIdx;
            this.modInfo = modInfo;
            this.hir = hir;
        }

        public boolean hasCaptures() {
            return !capturedVars.isEmpty();
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
        public final boolean[] guaranteedDefinedAfterCtor;
        public final int defMaskFieldIdx; // -1 if none
        public final int captureBaseFieldIdx;
        public final int hiddenCellBaseFieldIdx;
        public final List<HirVar> hiddenCells;
        public final Map<Integer, Integer> valueHiddenVarToField = new LinkedHashMap<>();
        public final boolean hasCaptures;
        public final boolean hasCapturingIfaces;
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
        public int[] staticFieldGlobals = new int[0];
        public final Map<String, WasmProgramLinker.FuncInfo> immutableStaticMethods =
                new LinkedHashMap<>();
        public final Set<String> immutableStaticFields = new LinkedHashSet<>();
        public final Map<String, Object> immutableStaticConsts = new LinkedHashMap<>();

        public final Map<String, Integer> boundMethodGenericFns = new LinkedHashMap<>();

        ClassInfo(
                int classId,
                WasmProgramLinker.ModuleLinkInfo modInfo,
                HirClass hir,
                int structTypeIdx,
                int[] memberToStructField,
                PrimitiveKind[] memberKinds,
                boolean[] fieldMutable,
                boolean[] guaranteedDefinedAfterCtor,
                int defMaskFieldIdx,
                int captureBaseFieldIdx,
                int hiddenCellBaseFieldIdx,
                List<HirVar> hiddenCells,
                boolean hasCapturingIfaces,
                long ifaceBits,
                long[] ifaceWords) {
            this.classId = classId;
            this.modInfo = modInfo;
            this.hir = hir;
            this.shape = hir.shape();
            this.structTypeIdx = structTypeIdx;
            this.memberToStructField = memberToStructField;
            this.memberKinds = memberKinds;
            this.fieldMutable = fieldMutable;
            this.guaranteedDefinedAfterCtor = guaranteedDefinedAfterCtor;
            this.defMaskFieldIdx = defMaskFieldIdx;
            this.captureBaseFieldIdx = captureBaseFieldIdx;
            this.hiddenCellBaseFieldIdx = hiddenCellBaseFieldIdx;
            this.hiddenCells = List.copyOf(hiddenCells);
            for (int i = 0; i < this.hiddenCells.size(); i++) {
                HirVar hv = this.hiddenCells.get(i);
                if (WasmProgramLinker.isValueCapture(hv)) {
                    this.valueHiddenVarToField.put(hv.id(), hiddenCellBaseFieldIdx + i);
                }
            }
            this.hasCaptures = !hir.capturedVars().isEmpty();
            this.hasCapturingIfaces = hasCapturingIfaces;
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
    private int capturingIfaceCount = 0;

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
        this.gClassVals =
                mod.addGlobal(
                        "G_CLASS_VALS",
                        WasmValType.refNull(ts.anyArrType),
                        true,
                        ts.defaultConstExpr(WasmValType.refNull(ts.anyArrType)));
        this.fnInitObjectsDef =
                mod.addFunction("init_objects", ts.internFuncType(List.of(), List.of()));
        this.fnInitObjects = fnInitObjectsDef.funcIndex();
    }

    public int capturingIfaceCount() {
        return capturingIfaceCount;
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

    // ---------------------------------------------------------------- Phase 1: Declare all
    // interfaces & classes

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

        // Step 2: Build $ITable_I structs, super-interface sets, and capture structs for each
        // interface
        for (IfaceInfo ii : allIfaces) {
            LoweringTargets.TypeHierarchyInfo thi = findHierarchy(modules, ii.hir.typeId());
            if (thi != null && thi.allBabbleInterfaceIds() != null) {
                ii.allSuperIfaceIds.addAll(thi.allBabbleInterfaceIds());
            }
            List<WasmTypeDef.Field> itableFields = new ArrayList<>();
            if (thi != null && thi.interfaceMembers() != null) {
                for (Map.Entry<String, LoweringTargets.MemberSpec> e :
                        thi.interfaceMembers().entrySet()) {
                    LoweringTargets.MemberSpec ms = e.getValue();
                    if (ms.callable() && ms.declaredSignature() != null) {
                        LoweringTargets.FunctionSignature sig = ms.declaredSignature();
                        int slotIdx = ii.slots.size();
                        int wasmSig = itableMethodSig(sig);
                        ITableSlot slot =
                                new ITableSlot(
                                        slotIdx,
                                        e.getKey(),
                                        e.getKey(),
                                        -1,
                                        sig,
                                        wasmSig,
                                        sig.hasOptionalMask());
                        ii.slots.add(slot);
                        ii.slotByName.put(e.getKey(), slot);
                        itableFields.add(
                                WasmTypeSystem.field(
                                        "m_" + e.getKey(), WasmValType.ref(wasmSig), false));
                    } else if (ms.isOverloaded()) {
                        List<LoweringTargets.FunctionSignature> sigs = ms.overloadSignatures();
                        for (int k = 0; k < sigs.size(); k++) {
                            String slotKey = e.getKey() + "$" + k;
                            LoweringTargets.FunctionSignature sig = sigs.get(k);
                            int slotIdx = ii.slots.size();
                            int wasmSig = itableMethodSig(sig);
                            ITableSlot slot =
                                    new ITableSlot(
                                            slotIdx,
                                            slotKey,
                                            e.getKey(),
                                            k,
                                            sig,
                                            wasmSig,
                                            sig.hasOptionalMask());
                            ii.slots.add(slot);
                            ii.slotByName.put(slotKey, slot);
                            itableFields.add(
                                    WasmTypeSystem.field(
                                            "m_" + WasmProgramLinker.sanitizeName(slotKey),
                                            WasmValType.ref(wasmSig),
                                            false));
                        }
                    }
                }
            }
            // Collect captured variables across all default methods of this interface
            Set<Integer> seenCapIds = new LinkedHashSet<>();
            for (int fnId : ii.hir.defaultFunctionIds().values()) {
                HirFunction dfn = ii.modInfo.input.hir().functions().get(fnId);
                if (dfn != null) {
                    for (HirVar cv : dfn.capturedVars()) {
                        if (seenCapIds.add(cv.id())) ii.capturedVars.add(cv);
                    }
                }
            }
            for (List<Integer> fnIds : ii.hir.defaultOverloadFunctionIds().values()) {
                for (int fnId : fnIds) {
                    HirFunction dfn = ii.modInfo.input.hir().functions().get(fnId);
                    if (dfn != null) {
                        for (HirVar cv : dfn.capturedVars()) {
                            if (seenCapIds.add(cv.id())) ii.capturedVars.add(cv);
                        }
                    }
                }
            }
            if (!ii.capturedVars.isEmpty()) {
                ii.capSlotIdx = capturingIfaceCount++;
                List<WasmTypeDef.Field> capFields = new ArrayList<>();
                for (int ci = 0; ci < ii.capturedVars.size(); ci++) {
                    HirVar cv = ii.capturedVars.get(ci);
                    WasmValType capType =
                            WasmProgramLinker.isValueCapture(cv)
                                    ? ts.valTypeFor(cv.primitiveKind())
                                    : WasmValType.ref(ts.cellTypeFor(cv.primitiveKind()));
                    capFields.add(WasmTypeSystem.field("$icap" + ci, capType, false));
                }
                ii.capStructTypeIdx =
                        mod.addStructType("IfaceCap_" + ii.ifaceIdx, true, ts.anyType, capFields);
                ii.latestCapEnvGlobalIdx =
                        mod.addGlobal(
                                "G_IFACE_CAP_" + ii.ifaceIdx,
                                WasmValType.refNull(ii.capStructTypeIdx),
                                true,
                                ts.defaultConstExpr(WasmValType.refNull(ii.capStructTypeIdx)));
            }

            ii.itableStructType =
                    mod.addStructType(
                            "ITable_"
                                    + ii.ifaceIdx
                                    + "_"
                                    + WasmProgramLinker.sanitizeName(ii.hir.shape().name()),
                            true,
                            ts.itableBaseType,
                            itableFields);
            ii.ifaceValGlobalIdx =
                    mod.addGlobal(
                            "G_IFACE_" + ii.ifaceIdx,
                            WasmValType.refNull(ts.ifaceValType),
                            true,
                            ts.defaultConstExpr(WasmValType.refNull(ts.ifaceValType)));
            ii.staticInitDoneGlobalIdx =
                    mod.addGlobal(
                            "G_IFACE_INIT_" + ii.ifaceIdx,
                            WasmValType.I32,
                            true,
                            List.of(new WasmInstr.I32Const(0)));
        }

        // Step 3: Declare every HirClass struct inside ONE WasmGC rec group so classes with
        // identical field layouts (e.g. Circle(r: double) vs Square(s: double), or empty error
        // classes)
        // remain 100% nominally distinct under WasmGC iso-recursive type canonicalization!
        List<WasmTypeDef.SubType> classRecGroup = new ArrayList<>();
        record PendingClass(
                WasmProgramLinker.ModuleLinkInfo modInfo,
                HirClass hc,
                int classId,
                int structTypeIdx,
                int[] memberToField,
                PrimitiveKind[] memberKinds,
                boolean[] fieldMutable,
                int defMaskField,
                int capBase,
                int hiddenBase,
                List<HirVar> hiddenCells,
                String cname) {}
        List<PendingClass> pending = new ArrayList<>();
        for (WasmProgramLinker.ModuleLinkInfo m : modules) {
            for (HirClass hc : m.input.hir().classes().values()) {
                int classId = pending.size();
                ClassShape shape = hc.shape();
                boolean isRecord = shape.isValue();

                boolean[] isParamMember = new boolean[shape.memberCount()];
                for (int p = 0; p < shape.parameters().length; p++) {
                    int mi = shape.parameterMember(p);
                    if (mi >= 0 && mi < isParamMember.length) {
                        isParamMember[mi] = true;
                    }
                }

                List<WasmTypeDef.Field> structFields = new ArrayList<>();
                structFields.add(
                        WasmTypeSystem.field("vtable", WasmValType.ref(ts.vtableType), false));

                int[] memberToField = new int[shape.memberCount()];
                PrimitiveKind[] memberKinds = new PrimitiveKind[shape.memberCount()];
                boolean[] fieldMutable = new boolean[shape.memberCount()];
                for (int i = 0; i < shape.memberCount(); i++) {
                    PrimitiveKind mk =
                            i < hc.memberKinds().length && hc.memberKinds()[i] != null
                                    ? hc.memberKinds()[i]
                                    : PrimitiveKind.NONE;
                    memberKinds[i] = mk;
                    String mname = shape.member(i);
                    List<Integer> ovIds = hc.overloadMethodFunctionIds().get(mname);
                    boolean isCompiledMethod =
                            shape.isMethod(i)
                                    && (hc.methodFunctionIds().containsKey(mname)
                                            || (ovIds != null
                                                    && !ovIds.isEmpty()
                                                    && ovIds.stream().allMatch(id -> id >= 0)));
                    if (isCompiledMethod) {
                        memberToField[i] = -1;
                        fieldMutable[i] = false;
                    } else {
                        memberToField[i] = structFields.size();
                        // BIP025-D11 + BIP034-I5: record fields AND header `def` parameter fields
                        // on regular
                        // classes are initialized directly in struct.new and strictly immutable in
                        // WasmGC!
                        boolean immutableHeaderParam = isParamMember[i] && !shape.isMutable(i);
                        boolean mut = !immutableHeaderParam && !(isRecord && hc.fastRecord());
                        fieldMutable[i] = mut;
                        structFields.add(
                                WasmTypeSystem.field("f_" + mname, ts.valTypeFor(mk), mut));
                    }
                }

                int defMaskField = -1;
                if (!isRecord && hc.primDefCount() > 0) {
                    boolean needsDefMask = false;
                    for (int i = 0; i < shape.memberCount(); i++) {
                        if (fieldMutable[i]
                                && i < hc.primDefBits().length
                                && hc.primDefBits()[i] >= 0) {
                            needsDefMask = true;
                            break;
                        }
                    }
                    if (needsDefMask) {
                        defMaskField = structFields.size();
                        structFields.add(WasmTypeSystem.field("$defMask", WasmValType.I32, true));
                    }
                }

                int capBase = structFields.size();
                for (int i = 0; i < hc.capturedVars().size(); i++) {
                    HirVar cv = hc.capturedVars().get(i);
                    WasmValType capType =
                            WasmProgramLinker.isValueCapture(cv)
                                    ? ts.valTypeFor(cv.primitiveKind())
                                    : WasmValType.ref(ts.cellTypeFor(cv.primitiveKind()));
                    structFields.add(WasmTypeSystem.field("$cap" + i, capType, false));
                }

                int hiddenBase = structFields.size();
                List<HirVar> hiddenCells = new ArrayList<>();
                Set<Integer> seenHidden = new LinkedHashSet<>();
                for (HirParam p : hc.params()) {
                    if (p.var() != null
                            && (p.var().cell() || p.var().capturedByValue())
                            && seenHidden.add(p.var().id())) {
                        hiddenCells.add(p.var());
                    }
                }
                for (HirVar dv : hc.declaredVars()) {
                    if ((dv.cell() || dv.capturedByValue()) && seenHidden.add(dv.id())) {
                        hiddenCells.add(dv);
                    }
                }
                for (int i = 0; i < hiddenCells.size(); i++) {
                    HirVar hv = hiddenCells.get(i);
                    WasmValType hvt =
                            WasmProgramLinker.isValueCapture(hv)
                                    ? ts.valTypeFor(hv.primitiveKind())
                                    : WasmValType.refNull(ts.cellTypeFor(hv.primitiveKind()));
                    structFields.add(WasmTypeSystem.field("$hc" + i, hvt, true));
                }

                String cname = "C" + classId + "_" + WasmProgramLinker.sanitizeName(shape.name());
                int structTypeIdx = mod.reserveTypeIndex();
                classRecGroup.add(
                        new WasmTypeDef.SubType(
                                structTypeIdx,
                                cname,
                                true,
                                ts.objectType,
                                new WasmTypeDef.StructType(structFields)));
                pending.add(
                        new PendingClass(
                                m,
                                hc,
                                classId,
                                structTypeIdx,
                                memberToField,
                                memberKinds,
                                fieldMutable,
                                defMaskField,
                                capBase,
                                hiddenBase,
                                hiddenCells,
                                cname));
            }
        }
        if (!classRecGroup.isEmpty()) {
            mod.commitRecGroup(classRecGroup);
        }
        for (PendingClass pc : pending) {
            finishDeclareClass(
                    modules,
                    pc.modInfo,
                    pc.hc,
                    pc.classId,
                    pc.structTypeIdx,
                    pc.memberToField,
                    pc.memberKinds,
                    pc.fieldMutable,
                    pc.defMaskField,
                    pc.capBase,
                    pc.hiddenBase,
                    pc.hiddenCells,
                    pc.cname);
        }
    }

    private LoweringTargets.TypeHierarchyInfo findHierarchy(
            List<WasmProgramLinker.ModuleLinkInfo> modules, String typeId) {
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

    private void finishDeclareClass(
            List<WasmProgramLinker.ModuleLinkInfo> modules,
            WasmProgramLinker.ModuleLinkInfo m,
            HirClass hc,
            int classId,
            int structTypeIdx,
            int[] memberToField,
            PrimitiveKind[] memberKinds,
            boolean[] fieldMutable,
            int defMaskField,
            int capBase,
            int hiddenBase,
            List<HirVar> hiddenCells,
            String cname) {
        ClassShape shape = hc.shape();

        // Compute transitive interface bitset (BIP025-D7)
        long bits = 0L;
        int wordCount = allIfaces.size() > 64 ? (allIfaces.size() + 63) / 64 : 0;
        long[] words = wordCount > 0 ? new long[wordCount] : null;
        Set<IfaceInfo> implSet = new LinkedHashSet<>();
        boolean hasCapIfaces = false;
        LoweringTargets.TypeHierarchyInfo thi = findHierarchy(modules, hc.typeId());
        if (thi != null && thi.allBabbleInterfaceIds() != null) {
            for (String iid : thi.allBabbleInterfaceIds()) {
                IfaceInfo ii = ifacesByTypeId.get(iid);
                if (ii != null) {
                    implSet.add(ii);
                    if (ii.hasCaptures()) hasCapIfaces = true;
                    if (ii.ifaceIdx < 64) {
                        bits |= (1L << ii.ifaceIdx);
                    }
                    if (words != null) {
                        words[ii.ifaceIdx / 64] |= (1L << (ii.ifaceIdx % 64));
                    }
                }
            }
        }

        boolean[] guaranteedDefinedAfterCtor =
                computeGuaranteedDefinedAfterCtor(hc, memberToField, fieldMutable);

        ClassInfo ci =
                new ClassInfo(
                        classId,
                        m,
                        hc,
                        structTypeIdx,
                        memberToField,
                        memberKinds,
                        fieldMutable,
                        guaranteedDefinedAfterCtor,
                        defMaskField,
                        capBase,
                        hiddenBase,
                        hiddenCells,
                        hasCapIfaces,
                        bits,
                        words);
        ci.implementedIfaces.addAll(implSet);
        for (IfaceInfo ii : implSet) {
            ii.implementers.add(ci);
        }

        ci.vtableGlobalIdx =
                mod.addGlobal(
                        "G_VT_" + cname,
                        WasmValType.refNull(ts.vtableType),
                        true,
                        ts.defaultConstExpr(WasmValType.refNull(ts.vtableType)));
        ci.classValGlobalIdx =
                mod.addGlobal(
                        "G_CLS_" + cname,
                        WasmValType.refNull(ts.classValType),
                        true,
                        ts.defaultConstExpr(WasmValType.refNull(ts.classValType)));
        if (ci.hasCaptures && !hc.isObject()) {
            List<WasmTypeDef.Field> cvFields = new ArrayList<>();
            cvFields.add(
                    WasmTypeSystem.field(
                            "genericCall", WasmValType.ref(ts.genericCallSigType), false));
            cvFields.add(WasmTypeSystem.field("name", WasmValType.ref(ts.stringType), false));
            cvFields.add(WasmTypeSystem.field("fnId", WasmValType.I32, false));
            cvFields.add(WasmTypeSystem.field("classId", WasmValType.I32, false));
            cvFields.add(WasmTypeSystem.field("vtable", WasmValType.ref(ts.vtableType), false));
            cvFields.add(
                    WasmTypeSystem.field("statics", WasmValType.refNull(ts.anyArrType), false));
            for (int i = 0; i < hc.capturedVars().size(); i++) {
                HirVar cv = hc.capturedVars().get(i);
                WasmValType capVt =
                        WasmProgramLinker.isValueCapture(cv)
                                ? ts.valTypeFor(cv.primitiveKind())
                                : WasmValType.ref(ts.cellTypeFor(cv.primitiveKind()));
                cvFields.add(WasmTypeSystem.field("$cap" + i, capVt, false));
            }
            ci.classValStructType =
                    mod.addStructType(cname + "_ClassVal", true, ts.classValType, cvFields);
        } else {
            ci.classValStructType = ts.classValType;
        }
        ci.staticInitDoneGlobalIdx =
                mod.addGlobal(
                        "G_CLS_INIT_" + cname,
                        WasmValType.I32,
                        true,
                        List.of(new WasmInstr.I32Const(0)));
        int staticCount = shape.staticCount();
        if (staticCount > 0) {
            ci.staticFieldGlobals = new int[staticCount];
            for (int s = 0; s < staticCount; s++) {
                ci.staticFieldGlobals[s] =
                        mod.addGlobal(
                                "G_ST_"
                                        + cname
                                        + "_"
                                        + WasmProgramLinker.sanitizeName(shape.staticMember(s)),
                                ts.anyRef(),
                                true,
                                ts.defaultConstExpr(ts.anyRef()));
            }
        }
        if (hc.isObject()) {
            ci.singletonObjGlobalIdx =
                    mod.addGlobal(
                            "G_OBJ_" + cname,
                            WasmValType.refNull(structTypeIdx),
                            true,
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

        ci.genericCtorDef =
                mod.addFunction(
                        cname + "$ctor_generic",
                        ts.genericCallSigType,
                        List.of("self", "args", "names"));
        ci.genericCtorIdx = ci.genericCtorDef.funcIndex();
        mod.declareFuncRef(ci.genericCtorIdx);

        // VTable virtual hooks
        ci.equalsDef = mod.addFunction(cname + "$equals", ts.eqFnSigType, List.of("self", "other"));
        ci.hashDef = mod.addFunction(cname + "$hashCode", ts.hashFnSigType, List.of("self"));
        ci.toStringDef = mod.addFunction(cname + "$toString", ts.toStrFnSigType, List.of("self"));
        ci.getMemberDef =
                mod.addFunction(cname + "$getMember", ts.getMemFnSigType, List.of("self", "name"));
        ci.setMemberDef =
                mod.addFunction(
                        cname + "$setMember", ts.setMemFnSigType, List.of("self", "name", "val"));
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

    private static boolean[] computeGuaranteedDefinedAfterCtor(
            HirClass hc, int[] memberToField, boolean[] fieldMutable) {
        ClassShape shape = hc.shape();
        boolean[] guaranteed = new boolean[shape.memberCount()];
        for (int i = 0; i < shape.memberCount(); i++) {
            if (memberToField[i] >= 0 && (!fieldMutable[i] || shape.isHeaderParameterMember(i))) {
                guaranteed[i] = true;
            }
        }
        boolean escapesThis = false;
        for (HirExpr stmt : hc.body()) {
            if (stmt instanceof HirExpr.Def d
                    && d.target() != null
                    && d.target().kind() == LoweringTargets.DefKind.MEMBER
                    && d.target().index() >= 0
                    && d.target().index() < memberToField.length
                    && memberToField[d.target().index()] < 0) {
                continue; // compiled method declaration, not evaluated during construction
            }
            if (exprEscapesThis(stmt)) {
                escapesThis = true;
                break;
            }
        }
        if (!escapesThis) {
            for (HirExpr stmt : hc.body()) {
                if (stmt instanceof HirExpr.Def d
                        && d.target() != null
                        && d.target().kind() == LoweringTargets.DefKind.MEMBER
                        && d.value() != null) {
                    int mIdx = d.target().index();
                    if (mIdx >= 0 && mIdx < guaranteed.length && memberToField[mIdx] >= 0) {
                        guaranteed[mIdx] = true;
                    }
                }
            }
        }
        return guaranteed;
    }

    private static boolean exprEscapesThis(HirExpr e) {
        if (e == null) return false;
        return switch (e) {
            case HirExpr.Const ignored -> false;
            case HirExpr.IntParseFallback ignored -> false;
            case HirExpr.CoreExceptionRef ignored -> false;
            case HirExpr.CoreCollectionRef ignored -> false;
            case HirExpr.NameRead ignored -> false;
            case HirExpr.RecurseRef ignored -> false;
            case HirExpr.VarRead v ->
                    v.var() != null
                            && (v.var().kind()
                                            == org.babblelang.analysis.Resolution.Variable.Kind.THIS
                                    || "this".equals(v.var().name()));
            case HirExpr.ThisRef ignored -> true;
            case HirExpr.ThisFieldGet g ->
                    !(g.receiver() instanceof HirExpr.ThisRef) && exprEscapesThis(g.receiver());
            case HirExpr.Def d -> exprEscapesThis(d.value());
            case HirExpr.Unary u -> exprEscapesThis(u.operand());
            case HirExpr.Binary b -> exprEscapesThis(b.left()) || exprEscapesThis(b.right());
            case HirExpr.Range r -> exprEscapesThis(r.start()) || exprEscapesThis(r.end());
            case HirExpr.Logic l -> exprEscapesThis(l.left()) || exprEscapesThis(l.right());
            case HirExpr.ModuleExportGet g -> exprEscapesThis(g.receiver());
            case HirExpr.DynamicSelect s -> exprEscapesThis(s.receiver());
            case HirExpr.RecordNew r -> exprEscapesThis(r.classRef()) || anyEscapesThis(r.args());
            case HirExpr.DynamicCall c ->
                    exprEscapesThis(c.callee()) || anyCallArgsEscapeThis(c.args());
            case HirExpr.DirectBabbleCall c ->
                    exprEscapesThis(c.callee()) || anyEscapesThis(c.args());
            case HirExpr.DirectThisMethodCall c ->
                    exprEscapesThis(c.receiver()) || anyCallArgsEscapeThis(c.args());
            case HirExpr.CoreMethodCall c ->
                    exprEscapesThis(c.receiver()) || anyEscapesThis(c.args());
            case HirExpr.CoreCollectionCall c -> anyCallArgsEscapeThis(c.args());
            case HirExpr.ArrayCreate a -> anyEscapesThis(a.args());
            case HirExpr.ArrayOp a -> exprEscapesThis(a.receiver()) || anyEscapesThis(a.args());
            case HirExpr.If i ->
                    exprEscapesThis(i.cond())
                            || exprEscapesThis(i.thenBranch())
                            || exprEscapesThis(i.elseBranch());
            case HirExpr.Block b -> anyEscapesThis(b.statements());
            case HirExpr.TypeApply t -> exprEscapesThis(t.inner());
            case HirExpr.Is i -> exprEscapesThis(i.left());
            case HirExpr.As a -> exprEscapesThis(a.left());
            case HirExpr.NonNull n -> exprEscapesThis(n.operand());
            case HirExpr.Move m -> exprEscapesThis(m.operand());
            case HirExpr.Freeze f -> exprEscapesThis(f.operand());
            case HirExpr.SpreadAdapt s -> exprEscapesThis(s.value());
            case HirExpr.JavaMethodCall c ->
                    exprEscapesThis(c.receiver()) || anyEscapesThis(c.args());
            case HirExpr.JavaConstructorCall c -> anyEscapesThis(c.args());
            case HirExpr.JavaFieldGet f -> exprEscapesThis(f.receiver());
            case HirExpr.Match m -> {
                if (exprEscapesThis(m.scrutinee())) yield true;
                for (HirExpr.MatchArm arm : m.arms()) {
                    if (patternEscapesThis(arm.pattern())
                            || exprEscapesThis(arm.guard())
                            || exprEscapesThis(arm.body())) {
                        yield true;
                    }
                }
                yield m.elseArm() != null && exprEscapesThis(m.elseArm().body());
            }
            default -> true;
        };
    }

    private static boolean patternEscapesThis(org.babblelang.ir.hir.HirPattern p) {
        if (p == null) return false;
        return switch (p) {
            case org.babblelang.ir.hir.HirPattern.Const ignored -> false;
            case org.babblelang.ir.hir.HirPattern.TypeTest ignored -> false;
            case org.babblelang.ir.hir.HirPattern.Wildcard ignored -> false;
            case org.babblelang.ir.hir.HirPattern.Bind b -> patternEscapesThis(b.inner());
            case org.babblelang.ir.hir.HirPattern.ValueExpr v -> exprEscapesThis(v.expr());
            case org.babblelang.ir.hir.HirPattern.Or o -> {
                for (var alt : o.alternatives()) if (patternEscapesThis(alt)) yield true;
                yield false;
            }
            case org.babblelang.ir.hir.HirPattern.Relational r -> exprEscapesThis(r.rhs());
            case org.babblelang.ir.hir.HirPattern.RangeTest rt ->
                    exprEscapesThis(rt.lo())
                            || exprEscapesThis(rt.hi())
                            || exprEscapesThis(rt.step());
            case org.babblelang.ir.hir.HirPattern.Membership m -> exprEscapesThis(m.container());
            case org.babblelang.ir.hir.HirPattern.RecordDestructure rd -> {
                for (var fp : rd.fieldPatterns()) if (patternEscapesThis(fp)) yield true;
                yield false;
            }
            case org.babblelang.ir.hir.HirPattern.SequenceDestructure sd -> {
                for (var ep : sd.elemPatterns()) if (patternEscapesThis(ep)) yield true;
                yield false;
            }
        };
    }

    private static boolean anyEscapesThis(List<HirExpr> exprs) {
        for (HirExpr x : exprs) {
            if (exprEscapesThis(x)) return true;
        }
        return false;
    }

    private static boolean anyCallArgsEscapeThis(List<HirCallArg> args) {
        for (HirCallArg a : args) {
            if (exprEscapesThis(a.value())) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Phase 2: Emit constructors,
    // vtable hooks, itables, init_objects

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
            WasmModule.FuncDef bm =
                    mod.addFunction(
                            "C" + ci.classId + "_bm_" + WasmProgramLinker.sanitizeName(e.getKey()),
                            ts.genericCallSigType,
                            List.of("self", "args", "names"));
            mod.declareFuncRef(bm.funcIndex());
            ce.emitBoundMethodTrampoline(bm, ci, mfi);
            ci.boundMethodGenericFns.put(e.getKey(), bm.funcIndex());
        }
        for (Map.Entry<String, List<Integer>> e : ci.hir.overloadMethodFunctionIds().entrySet()) {
            if (ci.boundMethodGenericFns.containsKey(e.getKey()) || e.getValue().isEmpty())
                continue;
            // Pick 1-arg reference overload if present (e.g. Appendable.append(CharSequence)), else
            // alt 0
            WasmProgramLinker.FuncInfo mfi = ci.modInfo.functions.get(e.getValue().get(0));
            for (int id : e.getValue()) {
                WasmProgramLinker.FuncInfo cand = ci.modInfo.functions.get(id);
                if (cand != null
                        && cand.hir.params().size() == 1
                        && !cand.hir.params().get(0).kind().isPrimitive()) {
                    mfi = cand;
                    break;
                }
            }
            if (mfi == null) continue;
            WasmModule.FuncDef bm =
                    mod.addFunction(
                            "C" + ci.classId + "_bm_" + WasmProgramLinker.sanitizeName(e.getKey()),
                            ts.genericCallSigType,
                            List.of("self", "args", "names"));
            mod.declareFuncRef(bm.funcIndex());
            ce.emitBoundMethodTrampoline(bm, ci, mfi);
            ci.boundMethodGenericFns.put(e.getKey(), bm.funcIndex());
        }
        // Inherited interface default methods (accessible via this.m / obj.m)
        for (IfaceInfo ii : ci.implementedIfaces) {
            for (String defName : ii.hir.defaultFunctionIds().keySet()) {
                if (ci.boundMethodGenericFns.containsKey(defName)
                        || hasShapeMember(ci.shape, defName)) {
                    continue;
                }
                WasmProgramLinker.FuncInfo dfi =
                        findClassOrDefaultImpl(ci, null, defName, -1, null);
                if (dfi == null) continue;
                WasmModule.FuncDef bm =
                        mod.addFunction(
                                "C"
                                        + ci.classId
                                        + "_bm_def_"
                                        + WasmProgramLinker.sanitizeName(defName),
                                ts.genericCallSigType,
                                List.of("self", "args", "names"));
                mod.declareFuncRef(bm.funcIndex());
                ce.emitBoundMethodTrampoline(bm, ci, dfi);
                ci.boundMethodGenericFns.put(defName, bm.funcIndex());
            }
        }
    }

    private static boolean hasShapeMember(ClassShape shape, String name) {
        return shapeIndexOf(shape, name) >= 0;
    }

    private static int shapeIndexOf(ClassShape shape, String name) {
        for (int i = 0; i < shape.memberCount(); i++) {
            if (name.equals(shape.member(i))) return i;
        }
        return -1;
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
        for (int p = 0; p < paramCount; p++) {
            c.localGet(idx)
                    .i32Const(p)
                    .op(WasmInstr.SimpleOp.I32_EQ)
                    .if_()
                    .localGet(valAny)
                    .localSet(pLocals[p])
                    .end();
        }
        c.else_();
        for (int p = 0; p < paramCount; p++) {
            c.localGet(nameAny).refCast(ts.stringType);
            rt.emitStringLit(c, ci.hir.params().get(p).name());
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

        if (ci.hasCapturingIfaces) {
            c.localGet(0).refCast(ts.classValType).structGet(ts.classValType, 4);
        }
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
                if (hp.kind().isPrimitive())
                    c.i32Const(ci.hir.line()).call(rt.unboxFuncFor(hp.kind()));
                c.end();
                optBit++;
            } else {
                c.localGet(pLocals[p]);
                if (hp.kind().isPrimitive())
                    c.i32Const(ci.hir.line()).call(rt.unboxFuncFor(hp.kind()));
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
        c.localGet(1)
                .refTest(ci.structTypeIdx)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .i32Const(0)
                .return_()
                .end();
        int a = ci.equalsDef.addLocal("a", WasmValType.refNull(ci.structTypeIdx), 2);
        int b = ci.equalsDef.addLocal("b", WasmValType.refNull(ci.structTypeIdx), 2);
        c.localGet(0).refCast(ci.structTypeIdx).localSet(a);
        c.localGet(1).refCast(ci.structTypeIdx).localSet(b);
        for (int i = 0; i < ci.shape.memberCount(); i++) {
            int fi = ci.memberToStructField[i];
            if (fi < 0) continue;
            PrimitiveKind mk = ci.memberKinds[i];
            c.localGet(a)
                    .refAsNonNull()
                    .structGet(ci.structTypeIdx, fi)
                    .localGet(b)
                    .refAsNonNull()
                    .structGet(ci.structTypeIdx, fi);
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
        int tmp = ci.getMemberDef.addLocal("v", ts.anyRef(), 2);
        c.localGet(0).refCast(ci.structTypeIdx).localSet(self);
        for (int i = 0; i < ci.shape.memberCount(); i++) {
            String mname = ci.shape.member(i);
            c.localGet(1);
            rt.emitStringLit(c, mname);
            c.call(rt.fnStrEq).if_();
            int fi = ci.memberToStructField[i];
            if (fi >= 0) {
                boolean needsCheck = ci.fieldMutable[i] && !ci.guaranteedDefinedAfterCtor[i];
                if (needsCheck
                        && ci.defMaskFieldIdx >= 0
                        && i < ci.hir.primDefBits().length
                        && ci.hir.primDefBits()[i] >= 0) {
                    int bit = 1 << ci.hir.primDefBits()[i];
                    c.localGet(self)
                            .refAsNonNull()
                            .structGet(ci.structTypeIdx, ci.defMaskFieldIdx)
                            .i32Const(bit)
                            .op(WasmInstr.SimpleOp.I32_AND)
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .if_();
                    rt.emitStringLit(c, "Member used before definition : " + mname);
                    c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable().end();
                }
                c.localGet(self).refAsNonNull().structGet(ci.structTypeIdx, fi);
                if (ci.memberKinds[i].isPrimitive()) {
                    c.call(rt.boxFuncFor(ci.memberKinds[i]));
                } else if (needsCheck) {
                    c.localTee(tmp).globalGet(rt.gUndefined).refEq().if_();
                    rt.emitStringLit(c, "Member used before definition : " + mname);
                    c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable().end();
                    c.localGet(tmp);
                }
                c.return_();
            } else {
                Integer bmFn = ci.boundMethodGenericFns.get(mname);
                if (bmFn != null) {
                    c.refFunc(bmFn);
                    rt.emitStringLit(c, mname);
                    c.i32Const(i).localGet(0).structNew(ts.boundMethodType).return_();
                }
            }
            c.end();
        }
        // Check inherited interface default methods not declared directly on the class
        for (Map.Entry<String, Integer> e : ci.boundMethodGenericFns.entrySet()) {
            if (hasShapeMember(ci.shape, e.getKey())) continue;
            c.localGet(1);
            rt.emitStringLit(c, e.getKey());
            c.call(rt.fnStrEq).if_();
            c.refFunc(e.getValue());
            rt.emitStringLit(c, e.getKey());
            c.i32Const(-1).localGet(0).structNew(ts.boundMethodType).return_();
            c.end();
        }
        rt.emitStringLit(c, "No such member in " + ci.shape.name() + " : ");
        c.localGet(1)
                .call(rt.fnStrConcat)
                .i32Const(ci.hir.line())
                .call(rt.fnThrowMsg)
                .unreachable();
    }

    private void emitSetMemberHook(ClassInfo ci) {
        WasmCodeBuilder c = ci.setMemberDef.code();
        int self = ci.setMemberDef.addLocal("s", WasmValType.refNull(ci.structTypeIdx), 3);
        c.localGet(0).refCast(ci.structTypeIdx).localSet(self);
        for (int i = 0; i < ci.shape.memberCount(); i++) {
            String mname = ci.shape.member(i);
            int fi = ci.memberToStructField[i];
            c.localGet(1);
            rt.emitStringLit(c, mname);
            c.call(rt.fnStrEq).if_();
            if (fi < 0) {
                rt.emitStringLit(c, "Cannot assign method : " + mname);
                c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable();
            } else if (!ci.fieldMutable[i]) {
                String msg =
                        ci.shape.isValue()
                                ? "Cannot reassign field of record "
                                        + ci.shape.name()
                                        + " : "
                                        + mname
                                : "Cannot assign to def member : " + mname;
                rt.emitStringLit(c, msg);
                c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable();
            } else {
                // Reject writes to fields that have not been defined yet during construction
                if (ci.defMaskFieldIdx >= 0
                        && i < ci.hir.primDefBits().length
                        && ci.hir.primDefBits()[i] >= 0) {
                    int bit = 1 << ci.hir.primDefBits()[i];
                    c.localGet(self)
                            .refAsNonNull()
                            .structGet(ci.structTypeIdx, ci.defMaskFieldIdx)
                            .i32Const(bit)
                            .op(WasmInstr.SimpleOp.I32_AND)
                            .op(WasmInstr.SimpleOp.I32_EQZ)
                            .if_();
                    rt.emitStringLit(c, "Member used before definition : " + mname);
                    c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable().end();
                } else if (ci.memberKinds[i] == PrimitiveKind.NONE) {
                    c.localGet(self)
                            .refAsNonNull()
                            .structGet(ci.structTypeIdx, fi)
                            .globalGet(rt.gUndefined)
                            .refEq()
                            .if_();
                    rt.emitStringLit(c, "Member used before definition : " + mname);
                    c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable().end();
                }
                c.localGet(self).refAsNonNull().localGet(2);
                if (ci.memberKinds[i].isPrimitive()) {
                    c.i32Const(ci.hir.line()).call(rt.unboxFuncFor(ci.memberKinds[i]));
                }
                c.structSet(ci.structTypeIdx, fi);
                c.localGet(2).return_();
            }
            c.end();
        }
        for (String defName : ci.boundMethodGenericFns.keySet()) {
            if (hasShapeMember(ci.shape, defName)) continue;
            c.localGet(1);
            rt.emitStringLit(c, defName);
            c.call(rt.fnStrEq).if_();
            rt.emitStringLit(c, "Cannot assign method : " + defName);
            c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable().end();
        }
        rt.emitStringLit(c, "No such member in " + ci.shape.name() + " : ");
        c.localGet(1)
                .call(rt.fnStrConcat)
                .i32Const(ci.hir.line())
                .call(rt.fnThrowMsg)
                .unreachable();
    }

    private void emitOpHook(ClassInfo ci) {
        WasmCodeBuilder c = ci.opDef.code();
        String[] opsList = {"plus", "minus", "times", "div", "floorDiv", "mod", "neg"};
        for (String opName : opsList) {
            Integer mId = ci.hir.methodFunctionIds().get(opName);
            List<Integer> ovIds = ci.hir.overloadMethodFunctionIds().get(opName);
            if (mId == null && (ovIds == null || ovIds.isEmpty())) continue;
            c.localGet(1);
            rt.emitStringLit(c, opName);
            c.call(rt.fnStrEq).if_();
            if (mId != null) {
                WasmProgramLinker.FuncInfo mfi = ci.modInfo.functions.get(mId);
                if (mfi != null) {
                    emitOpDirectCall(c, ci, opName, mfi);
                    c.return_();
                }
            } else {
                // Overloaded operator methods: pick first matching alt by runtime argument type (or
                // exact arity for neg)
                for (int altId : ovIds) {
                    WasmProgramLinker.FuncInfo mfi = ci.modInfo.functions.get(altId);
                    if (mfi == null) continue;
                    if ("neg".equals(opName)) {
                        if (mfi.hir.params().isEmpty()) {
                            emitOpDirectCall(c, ci, opName, mfi);
                            c.return_();
                        }
                    } else if (!mfi.hir.params().isEmpty()) {
                        PrimitiveKind pk = mfi.hir.params().get(0).kind();
                        if (pk.isPrimitive()) {
                            emitPrimMatchCheck(c, 2, pk);
                            c.if_();
                            emitOpDirectCall(c, ci, opName, mfi);
                            c.return_().end();
                        }
                    }
                }
                // Fallback to non-primitive / widest alt
                for (int altId : ovIds) {
                    WasmProgramLinker.FuncInfo mfi = ci.modInfo.functions.get(altId);
                    if (mfi != null && ("neg".equals(opName) || !mfi.hir.params().isEmpty())) {
                        emitOpDirectCall(c, ci, opName, mfi);
                        c.return_();
                        break;
                    }
                }
            }
            c.end();
        }
        rt.emitStringLit(c, "Operator method not defined on " + ci.shape.name() + " : ");
        c.localGet(1)
                .call(rt.fnStrConcat)
                .i32Const(ci.hir.line())
                .call(rt.fnThrowMsg)
                .unreachable();
    }

    private void emitOpDirectCall(
            WasmCodeBuilder c, ClassInfo ci, String opName, WasmProgramLinker.FuncInfo mfi) {
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
    }

    private void emitPrimMatchCheck(WasmCodeBuilder c, int localIdx, PrimitiveKind pk) {
        switch (pk) {
            case BYTE -> c.localGet(localIdx).refTest(ts.boxByteType);
            case SHORT ->
                    c.localGet(localIdx)
                            .refTest(ts.boxShortType)
                            .localGet(localIdx)
                            .refTest(ts.boxByteType)
                            .op(WasmInstr.SimpleOp.I32_OR);
            case INT ->
                    c.localGet(localIdx)
                            .refTest(ts.boxIntType)
                            .localGet(localIdx)
                            .refTest(ts.boxShortType)
                            .op(WasmInstr.SimpleOp.I32_OR)
                            .localGet(localIdx)
                            .refTest(ts.boxByteType)
                            .op(WasmInstr.SimpleOp.I32_OR);
            case LONG ->
                    c.localGet(localIdx)
                            .call(rt.fnNumRank)
                            .i32Const(0)
                            .op(WasmInstr.SimpleOp.I32_GE_S)
                            .localGet(localIdx)
                            .call(rt.fnNumRank)
                            .i32Const(3)
                            .op(WasmInstr.SimpleOp.I32_LE_S)
                            .op(WasmInstr.SimpleOp.I32_AND);
            case FLOAT ->
                    c.localGet(localIdx)
                            .call(rt.fnNumRank)
                            .i32Const(0)
                            .op(WasmInstr.SimpleOp.I32_GE_S)
                            .localGet(localIdx)
                            .call(rt.fnNumRank)
                            .i32Const(4)
                            .op(WasmInstr.SimpleOp.I32_LE_S)
                            .op(WasmInstr.SimpleOp.I32_AND);
            case DOUBLE ->
                    c.localGet(localIdx)
                            .call(rt.fnNumRank)
                            .i32Const(0)
                            .op(WasmInstr.SimpleOp.I32_GE_S);
            case BOOL -> c.localGet(localIdx).refTest(ts.boxBoolType);
            case CHAR -> c.localGet(localIdx).refTest(ts.boxCharType);
            default -> c.i32Const(1);
        }
    }

    /**
     * Builds `$ITable_I` thunk function adapting `(ref $Object)` -> `(ref $Struct_C)` and invoking
     * the implementation.
     */
    private int emitITableThunk(ClassInfo ci, IfaceInfo ii, ITableSlot slot) {
        // Find implementing method: either C's own method/overload or most-specific default method
        WasmProgramLinker.FuncInfo impl =
                findClassOrDefaultImpl(
                        ci, ii, slot.memberName(), slot.overloadAltIndex(), slot.sig());
        WasmModule.FuncDef thunk =
                mod.addFunction(
                        "thunk_C"
                                + ci.classId
                                + "_I"
                                + ii.ifaceIdx
                                + "_"
                                + WasmProgramLinker.sanitizeName(slot.slotKey()),
                        slot.wasmSigTypeIdx());
        mod.declareFuncRef(thunk.funcIndex());
        WasmCodeBuilder c = thunk.code();
        PrimitiveKind[] slotParams = slot.sig().parameterKinds();
        if (impl == null) {
            // Check field-holding-function implementation (e.g. `def run = func`)
            int mIdx = shapeIndexOf(ci.shape, slot.memberName());
            if (mIdx >= 0 && ci.memberToStructField[mIdx] >= 0) {
                int fi = ci.memberToStructField[mIdx];
                int paramCount = 1 + slotParams.length + (slot.hasOptMask() ? 1 : 0);
                int fnLocal =
                        thunk.addLocal("fldFn", WasmValType.refNull(ts.fnBaseType), paramCount);
                c.localGet(0)
                        .refCast(ci.structTypeIdx)
                        .structGet(ci.structTypeIdx, fi)
                        .refCast(ts.fnBaseType)
                        .localTee(fnLocal)
                        .refAsNonNull();
                int slotOptBit = 0;
                for (int p = 0; p < slotParams.length; p++) {
                    boolean slotOpt = slot.hasOptMask() && slot.sig().isOptional(p);
                    if (slotOpt) {
                        c.localGet(1 + slotParams.length)
                                .i32Const(1 << slotOptBit)
                                .op(WasmInstr.SimpleOp.I32_AND)
                                .if_(ts.anyRef())
                                .globalGet(rt.gMissing)
                                .else_()
                                .localGet(1 + p);
                        emitConvertValue(c, slotParams[p], PrimitiveKind.NONE, ci.hir.line());
                        c.end();
                        slotOptBit++;
                    } else {
                        c.localGet(1 + p);
                        emitConvertValue(c, slotParams[p], PrimitiveKind.NONE, ci.hir.line());
                    }
                }
                c.arrayNewFixed(ts.anyArrType, slotParams.length)
                        .refNull(ts.anyArrType)
                        .localGet(fnLocal)
                        .refAsNonNull()
                        .structGet(ts.fnBaseType, 0)
                        .callRef(ts.genericCallSigType);
                emitConvertValue(c, PrimitiveKind.NONE, slot.sig().returnKind(), ci.hir.line());
                return thunk.funcIndex();
            }
            rt.emitStringLit(
                    c,
                    "Unimplemented interface method : "
                            + ii.hir.shape().name()
                            + "."
                            + slot.memberName());
            c.i32Const(ci.hir.line()).call(rt.fnThrowMsg).unreachable();
            return thunk.funcIndex();
        }

        // Receiver: class method expects (ref $Struct_C); interface default expects (ref $Object)
        if (impl.hir.ownerClassId() >= 0) {
            c.localGet(0).refCast(ci.structTypeIdx);
        } else {
            c.localGet(0);
        }
        int implRest = impl.hir.restIndex();
        int slotRest = slot.sig().restIndex();
        if (implRest >= 0 && slotRest < 0) {
            int restLocal =
                    thunk.addLocal(
                            "restL",
                            WasmValType.ref(ts.listType),
                            1 + slotParams.length + (slot.hasOptMask() ? 1 : 0));
            int slotOptBit = 0;
            for (int p = 0; p < impl.hir.params().size(); p++) {
                HirParam hp = impl.hir.params().get(p);
                PrimitiveKind implPk = hp.kind();
                boolean slotOpt =
                        p < slotParams.length && slot.hasOptMask() && slot.sig().isOptional(p);
                if (p == implRest) {
                    c.i32Const(Math.max(4, slotParams.length - implRest))
                            .call(linker.collections().fnListNew)
                            .localTee(restLocal);
                    for (int k = implRest; k < slotParams.length; k++) {
                        c.localGet(restLocal).localGet(1 + k);
                        emitConvertValue(c, slotParams[k], PrimitiveKind.NONE, ci.hir.line());
                        c.call(linker.collections().fnListAdd).drop();
                    }
                } else if (p < slotParams.length) {
                    boolean boxedOpt = impl.hasBoxedMissingOptParams && hp.defaultValue() != null;
                    if (boxedOpt && slotOpt) {
                        c.localGet(1 + slotParams.length)
                                .i32Const(1 << slotOptBit)
                                .op(WasmInstr.SimpleOp.I32_AND)
                                .if_(ts.anyRef())
                                .globalGet(rt.gMissing)
                                .else_()
                                .localGet(1 + p);
                        emitConvertValue(c, slotParams[p], PrimitiveKind.NONE, ci.hir.line());
                        c.end();
                    } else {
                        c.localGet(1 + p);
                        emitConvertValue(
                                c,
                                slotParams[p],
                                boxedOpt ? PrimitiveKind.NONE : implPk,
                                ci.hir.line());
                    }
                } else if (impl.hasBoxedMissingOptParams && hp.defaultValue() != null) {
                    c.globalGet(rt.gMissing);
                } else {
                    ts.emitDefaultValue(c, ts.valTypeFor(implPk));
                }
                if (slotOpt) slotOptBit++;
            }
        } else {
            int slotOptBit = 0;
            for (int p = 0; p < impl.hir.params().size(); p++) {
                HirParam hp = impl.hir.params().get(p);
                PrimitiveKind implPk = hp.kind();
                boolean slotOpt =
                        p < slotParams.length && slot.hasOptMask() && slot.sig().isOptional(p);
                if (p < slotParams.length) {
                    boolean boxedOpt = impl.hasBoxedMissingOptParams && hp.defaultValue() != null;
                    if (boxedOpt && slotOpt) {
                        c.localGet(1 + slotParams.length)
                                .i32Const(1 << slotOptBit)
                                .op(WasmInstr.SimpleOp.I32_AND)
                                .if_(ts.anyRef())
                                .globalGet(rt.gMissing)
                                .else_()
                                .localGet(1 + p);
                        emitConvertValue(c, slotParams[p], PrimitiveKind.NONE, ci.hir.line());
                        c.end();
                    } else {
                        c.localGet(1 + p);
                        emitConvertValue(
                                c,
                                slotParams[p],
                                boxedOpt ? PrimitiveKind.NONE : implPk,
                                ci.hir.line());
                    }
                } else if (impl.hasBoxedMissingOptParams && hp.defaultValue() != null) {
                    c.globalGet(rt.gMissing);
                } else {
                    ts.emitDefaultValue(c, ts.valTypeFor(implPk));
                }
                if (slotOpt) slotOptBit++;
            }
        }
        if (impl.hasOptMaskParam) {
            int implParamCount = impl.hir.params().size();
            int extraMask = 0;
            int optBit = 0;
            for (int p = 0; p < implParamCount; p++) {
                if (impl.hir.params().get(p).defaultValue() != null) {
                    if (p >= slotParams.length) {
                        extraMask |= (1 << optBit);
                    }
                    optBit++;
                }
            }
            if (slot.hasOptMask()) {
                int outMask = thunk.addLocal("outMask", WasmValType.I32, 1 + slotParams.length + 1);
                c.i32Const(extraMask).localSet(outMask);
                int slotOptBit = 0;
                int implOptBit = 0;
                for (int p = 0; p < slotParams.length && p < implParamCount; p++) {
                    boolean slotOpt = slot.sig().isOptional(p);
                    boolean implOpt = impl.hir.params().get(p).defaultValue() != null;
                    if (slotOpt && implOpt) {
                        c.localGet(1 + slotParams.length)
                                .i32Const(1 << slotOptBit)
                                .op(WasmInstr.SimpleOp.I32_AND)
                                .if_()
                                .localGet(outMask)
                                .i32Const(1 << implOptBit)
                                .op(WasmInstr.SimpleOp.I32_OR)
                                .localSet(outMask)
                                .end();
                    }
                    if (slotOpt) slotOptBit++;
                    if (implOpt) implOptBit++;
                }
                c.localGet(outMask);
            } else {
                c.i32Const(extraMask);
            }
        }
        c.call(impl.directFuncIdx);
        emitConvertValue(c, impl.hir.returnKind(), slot.sig().returnKind(), ci.hir.line());
        return thunk.funcIndex();
    }

    private void emitConvertValue(
            WasmCodeBuilder c, PrimitiveKind from, PrimitiveKind to, int line) {
        if (from == to) return;
        if (to == PrimitiveKind.NONE) {
            c.call(rt.boxFuncFor(from));
        } else if (from == PrimitiveKind.NONE) {
            c.i32Const(line).call(rt.unboxFuncFor(to));
        } else if (!WasmFuncEmitter.emitPrimitiveWidening(c, from, to)) {
            c.call(rt.boxFuncFor(from)).i32Const(line).call(rt.unboxFuncFor(to));
        }
    }

    public WasmProgramLinker.FuncInfo findClassOrDefaultImpl(
            ClassInfo ci, IfaceInfo ii, String memberName) {
        return findClassOrDefaultImpl(ci, ii, memberName, -1, null);
    }

    public WasmProgramLinker.FuncInfo findClassOrDefaultImpl(
            ClassInfo ci,
            IfaceInfo ii,
            String memberName,
            int overloadAltIndex,
            LoweringTargets.FunctionSignature targetSig) {
        // 1. Class's own single method
        if (overloadAltIndex < 0) {
            Integer mId = ci.hir.methodFunctionIds().get(memberName);
            if (mId != null) {
                return ci.modInfo.functions.get(mId);
            }
        }
        // 2. Class's own overloaded method (matching overloadAltIndex or targetSig)
        List<Integer> clsOvIds = ci.hir.overloadMethodFunctionIds().get(memberName);
        if (clsOvIds != null && !clsOvIds.isEmpty()) {
            WasmProgramLinker.FuncInfo matched =
                    matchOverloadCandidate(ci.modInfo, clsOvIds, overloadAltIndex, targetSig);
            if (matched != null) return matched;
        }
        // 3. Most-specific inherited interface default method (sub-interface overrides
        // super-interface!)
        List<IfaceInfo> cands = new ArrayList<>();
        for (IfaceInfo imp : ci.implementedIfaces) {
            if (!ifaceProvidesDefault(imp, memberName, overloadAltIndex)) continue;
            if (ii == null
                    || imp == ii
                    || (ii.hir.typeId() != null
                            && imp.allSuperIfaceIds.contains(ii.hir.typeId()))) {
                cands.add(imp);
            }
        }
        if (cands.isEmpty()) {
            for (IfaceInfo imp : ci.implementedIfaces) {
                if (ifaceProvidesDefault(imp, memberName, overloadAltIndex)) {
                    cands.add(imp);
                }
            }
        }
        IfaceInfo winner = pickMostSpecificIface(cands);
        if (winner != null) {
            if (overloadAltIndex < 0) {
                Integer dId = winner.hir.defaultFunctionIds().get(memberName);
                if (dId != null) {
                    return winner.modInfo.functions.get(dId);
                }
            }
            List<Integer> defOvIds = winner.hir.defaultOverloadFunctionIds().get(memberName);
            if (defOvIds != null && !defOvIds.isEmpty()) {
                WasmProgramLinker.FuncInfo matched =
                        matchOverloadCandidate(
                                winner.modInfo, defOvIds, overloadAltIndex, targetSig);
                if (matched != null) return matched;
            }
        }
        return null;
    }

    private static boolean ifaceProvidesDefault(
            IfaceInfo imp, String memberName, int overloadAltIndex) {
        if (overloadAltIndex < 0 && imp.hir.defaultFunctionIds().containsKey(memberName)) {
            return true;
        }
        List<Integer> ovIds = imp.hir.defaultOverloadFunctionIds().get(memberName);
        return ovIds != null && !ovIds.isEmpty();
    }

    private static IfaceInfo pickMostSpecificIface(List<IfaceInfo> cands) {
        if (cands.isEmpty()) return null;
        if (cands.size() == 1) return cands.get(0);
        for (IfaceInfo cand : cands) {
            String candId = cand.hir.typeId();
            boolean overridden = false;
            if (candId != null) {
                for (IfaceInfo other : cands) {
                    if (other != cand && other.allSuperIfaceIds.contains(candId)) {
                        overridden = true;
                        break;
                    }
                }
            }
            if (!overridden) return cand;
        }
        return cands.get(0);
    }

    private static WasmProgramLinker.FuncInfo matchOverloadCandidate(
            WasmProgramLinker.ModuleLinkInfo modInfo,
            List<Integer> fnIds,
            int overloadAltIndex,
            LoweringTargets.FunctionSignature targetSig) {
        if (overloadAltIndex >= 0 && overloadAltIndex < fnIds.size()) {
            int id = fnIds.get(overloadAltIndex);
            if (id >= 0) {
                WasmProgramLinker.FuncInfo fi = modInfo.functions.get(id);
                if (fi != null) return fi;
            }
        }
        if (targetSig != null) {
            PrimitiveKind[] tParams = targetSig.parameterKinds();
            // Exact param count & primitive kind match first
            for (int id : fnIds) {
                if (id < 0) continue;
                WasmProgramLinker.FuncInfo fi = modInfo.functions.get(id);
                if (fi == null || fi.hir.params().size() != tParams.length) continue;
                boolean exact = true;
                for (int i = 0; i < tParams.length; i++) {
                    if (fi.hir.params().get(i).kind() != tParams[i]) {
                        exact = false;
                        break;
                    }
                }
                if (exact) return fi;
            }
            // Compatible arity (with optional/rest params)
            for (int id : fnIds) {
                if (id < 0) continue;
                WasmProgramLinker.FuncInfo fi = modInfo.functions.get(id);
                if (fi == null) continue;
                int req = fi.hir.requiredParams();
                int max = fi.hir.restIndex() >= 0 ? Integer.MAX_VALUE : fi.hir.params().size();
                if (tParams.length >= req && tParams.length <= max) {
                    return fi;
                }
            }
        }
        for (int id : fnIds) {
            if (id >= 0) {
                WasmProgramLinker.FuncInfo fi = modInfo.functions.get(id);
                if (fi != null) return fi;
            }
        }
        return null;
    }

    private void emitInitObjects() {
        WasmCodeBuilder c = fnInitObjectsDef.code();

        // 1. Initialize $IfaceVal globals (statics slots initialized to $G_UNDEFINED)
        for (IfaceInfo ii : allIfaces) {
            c.i32Const(ii.ifaceIdx);
            rt.emitStringLit(c, ii.hir.shape().name());
            int sc = ii.hir.shape().staticCount();
            if (sc > 0) {
                for (int s = 0; s < sc; s++) {
                    c.globalGet(rt.gUndefined);
                }
                c.arrayNewFixed(ts.anyArrType, sc);
            } else {
                c.refNull(ts.anyArrType);
            }
            c.refNull(ts.anyType); // capEnv (field 3)
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
                    .refFunc(ci.opDef.funcIndex());
            rt.emitStringLit(c, ci.shape.id() != null ? ci.shape.id() : ci.shape.name());
            c.refNull(ts.anyArrType); // ifaceCaps (field 13)
            c.structNew(ts.vtableType).globalSet(ci.vtableGlobalIdx);

            // $ClassVal (statics slots initialized to $G_UNDEFINED)
            c.refFunc(ci.genericCtorIdx);
            rt.emitStringLit(c, ci.shape.name());
            c.i32Const(ci.classId)
                    .i32Const(ci.classId)
                    .globalGet(ci.vtableGlobalIdx)
                    .refAsNonNull();
            int csc = ci.shape.staticCount();
            if (csc > 0) {
                for (int s = 0; s < csc; s++) {
                    c.globalGet(rt.gUndefined).globalSet(ci.staticFieldGlobals[s]);
                }
                for (int s = 0; s < csc; s++) {
                    c.globalGet(rt.gUndefined);
                }
                c.arrayNewFixed(ts.anyArrType, csc);
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

    // ---------------------------------------------------------------- Interface bitset `is` &
    // intersection `A & B` (BIP025-D7)

    /**
     * Emits {@code x is target} or single-mask {@code x is (A & B & ...)} leaving {@code i32} (0/1)
     * on stack. Consumes the boxed {@code (ref null $Any)} currently stored in {@code valLocal}.
     */
    public boolean emitNominalIsCheck(
            WasmCodeBuilder c,
            int valLocal,
            LoweringTargets.IsTarget target,
            List<LoweringTargets.IsTarget> intersectionTargets) {
        if (intersectionTargets != null && !intersectionTargets.isEmpty()) {
            // Fast single-mask i64.and + i64.eq when all members are interfaces with idx < 64
            // (BIP025-D7)!
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
                c.localGet(valLocal)
                        .refTest(ts.objectType)
                        .if_(WasmValType.I32)
                        .localGet(valLocal)
                        .refCast(ts.objectType)
                        .structGet(ts.objectType, 0)
                        .structGet(ts.vtableType, 2)
                        .i64Const(mask)
                        .op(WasmInstr.SimpleOp.I64_AND)
                        .i64Const(mask)
                        .op(WasmInstr.SimpleOp.I64_EQ)
                        .else_()
                        .i32Const(0)
                        .end();
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

    /**
     * Emits a nominal class/interface {@code is} check by {@code typeId} (for descriptor-driven
     * {@code is}/{@code as}).
     */
    public boolean emitNominalIsCheckById(WasmCodeBuilder c, int valLocal, String typeId) {
        if (typeId == null) {
            return false;
        }
        IfaceInfo ii = ifaceByTypeId(typeId);
        if (ii != null) {
            return emitSingleTargetCheck(
                    c,
                    valLocal,
                    new LoweringTargets.IsTarget(typeId, ClassShape.Kind.INTERFACE, null));
        }
        ClassInfo ci = classByTypeId(typeId);
        if (ci != null) {
            return emitSingleTargetCheck(
                    c, valLocal, new LoweringTargets.IsTarget(typeId, ClassShape.Kind.CLASS, null));
        }
        return false;
    }

    private boolean emitSingleTargetCheck(
            WasmCodeBuilder c, int valLocal, LoweringTargets.IsTarget target) {
        if (target.kind() == ClassShape.Kind.INTERFACE) {
            IfaceInfo ii = ifaceByTypeId(target.typeId());
            if (ii == null && target.jvmInternalName() != null)
                ii = ifaceByJvmName(target.jvmInternalName());
            if (ii == null) {
                c.i32Const(0);
                return true;
            }
            c.localGet(valLocal).refTest(ts.objectType).if_(WasmValType.I32);
            if (ii.ifaceIdx < 64) {
                c.localGet(valLocal)
                        .refCast(ts.objectType)
                        .structGet(ts.objectType, 0)
                        .structGet(ts.vtableType, 2)
                        .i64Const(1L << ii.ifaceIdx)
                        .op(WasmInstr.SimpleOp.I64_AND)
                        .i64Const(0L)
                        .op(WasmInstr.SimpleOp.I64_NE);
            } else {
                c.localGet(valLocal)
                        .refCast(ts.objectType)
                        .structGet(ts.objectType, 0)
                        .structGet(ts.vtableType, 3)
                        .refAsNonNull()
                        .i32Const(ii.ifaceIdx / 64)
                        .arrayGet(ts.i64ConstArrType)
                        .i64Const(1L << (ii.ifaceIdx % 64))
                        .op(WasmInstr.SimpleOp.I64_AND)
                        .i64Const(0L)
                        .op(WasmInstr.SimpleOp.I64_NE);
            }
            c.else_().i32Const(0).end();
            return true;
        }
        ClassInfo ci = classByTypeId(target.typeId());
        if (ci == null && target.jvmInternalName() != null)
            ci = classByJvmName(target.jvmInternalName());
        if (ci == null) {
            c.i32Const(0);
            return true;
        }
        c.localGet(valLocal).refTest(ci.structTypeIdx);
        return true;
    }
}
