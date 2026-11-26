package org.babblelang.wasm;

import org.babblelang.analysis.CompilationTarget;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whole-program closed-world linker from {@link HirModule}s to a single WebAssembly 3.0
 * {@link WasmModule} ({@code BIP025-I1}, {@code BIP025-I2}, {@code BIP025-D3}).
 *
 * <p>Resolves module globals, {@code $ModuleVal} exports, classes/records/objects, interfaces
 * &amp; typed {@code $ITable_I}s, functions &amp; closures, generic call trampolines, module
 * initialization guards, and entry-point exports.
 */
public final class WasmProgramLinker {
    public record ModuleInput(String key, String displayName, HirModule hir, Map<String, String> staticImports) {
        public ModuleInput {
            staticImports = staticImports != null ? Map.copyOf(staticImports) : Map.of();
        }

        public ModuleInput(String key, String displayName, HirModule hir) {
            this(key, displayName, hir, Map.of());
        }
    }

    /** Metadata for one compiled Babble function, class method, or interface default method. */
    public static final class FuncInfo {
        public final int moduleIndex;
        public final HirFunction hir;
        public final int directFuncIdx;
        public final WasmModule.FuncDef directFuncDef;
        public final int genericFuncIdx;
        public final WasmModule.FuncDef genericFuncDef;
        public final int closureStructType; // -1 if capture-free (uses $FnBase) or class/interface method
        public final int singletonGlobalIdx; // >= 0 if capture-free standalone function
        public final List<WasmValType> captureFieldTypes;
        public final boolean hasEnvParam;
        public final boolean hasThisParam;
        public final WasmObjectSystem.ClassInfo ownerClass; // non-null for class/record/object methods
        public final WasmObjectSystem.IfaceInfo ownerIface; // non-null for interface default methods
        public final boolean isInterfaceDefault;
        public final boolean hasOptMaskParam;
        public final boolean hasBoxedMissingOptParams;

        FuncInfo(int moduleIndex, HirFunction hir, WasmModule.FuncDef directFuncDef,
                 WasmModule.FuncDef genericFuncDef, int closureStructType, int singletonGlobalIdx,
                 List<WasmValType> captureFieldTypes, boolean hasEnvParam, boolean hasThisParam,
                 WasmObjectSystem.ClassInfo ownerClass, WasmObjectSystem.IfaceInfo ownerIface,
                 boolean isInterfaceDefault, boolean hasOptMaskParam, boolean hasBoxedMissingOptParams) {
            this.moduleIndex = moduleIndex;
            this.hir = hir;
            this.directFuncIdx = directFuncDef.funcIndex();
            this.directFuncDef = directFuncDef;
            this.genericFuncIdx = genericFuncDef != null ? genericFuncDef.funcIndex() : -1;
            this.genericFuncDef = genericFuncDef;
            this.closureStructType = closureStructType;
            this.singletonGlobalIdx = singletonGlobalIdx;
            this.captureFieldTypes = List.copyOf(captureFieldTypes);
            this.hasEnvParam = hasEnvParam;
            this.hasThisParam = hasThisParam;
            this.ownerClass = ownerClass;
            this.ownerIface = ownerIface;
            this.isInterfaceDefault = isInterfaceDefault;
            this.hasOptMaskParam = hasOptMaskParam;
            this.hasBoxedMissingOptParams = hasBoxedMissingOptParams;
        }

        public WasmObjectSystem.ClassInfo staticOwnerClass;
        public WasmObjectSystem.IfaceInfo staticOwnerIface;
    }

    /** Whether {@code cv} is a {@code BIP034-I5} value-captured immutable {@code def} (no {@code $Cell}). */
    public static boolean isValueCapture(HirVar cv) {
        return cv != null && cv.capturedByValue() && !cv.cell();
    }

    /** Unboxed primitive Wasm global backing an immutable top-level primitive {@code def}. */
    public record PrimitiveGlobal(int ownerModIndex, int globalIdx, org.babblelang.analysis.PrimitiveKind kind) {
    }

    /** Per-module linked state: top-level globals, ModuleVal, init function, and function table. */
    public static final class ModuleLinkInfo {
        public final int index;
        public final ModuleInput input;
        public final Map<String, Integer> topLevelGlobals = new LinkedHashMap<>();
        public final Map<String, Integer> topLevelDefIndex = new LinkedHashMap<>();
        public final Set<String> immutableTopLevelNames = new HashSet<>();
        public final Map<String, Object> immutableTopLevelConsts = new LinkedHashMap<>();
        public final Map<String, PrimitiveGlobal> immutableTopLevelPrimGlobals = new LinkedHashMap<>();
        public final Map<String, ModuleLinkInfo> immutableModuleImports = new LinkedHashMap<>();
        public final Map<String, FuncInfo> immutableTopLevelFuncs = new LinkedHashMap<>();
        public final Map<String, WasmObjectSystem.ClassInfo> immutableTopLevelClasses = new LinkedHashMap<>();
        public final Map<Integer, Integer> funcOwnerTopLevelStmtIdx = new HashMap<>();
        public final Map<Integer, Integer> classOwnerTopLevelStmtIdx = new HashMap<>();
        public final Map<Integer, FuncInfo> functions = new LinkedHashMap<>();
        public final Set<Integer> interfaceDefaultFuncIds = new HashSet<>();
        public final Map<Integer, Integer> interfaceDefaultOwnerIfaceId = new LinkedHashMap<>();
        public int initStateGlobalIdx; // 0 = uninit, 1 = initing, 2 = done
        public int modValGlobalIdx;    // (mut (ref null $ModuleVal))
        public int initFuncIdx;
        public WasmModule.FuncDef initFuncDef;

        ModuleLinkInfo(int index, ModuleInput input) {
            this.index = index;
            this.input = input;
            for (HirInterface hi : input.hir().interfaces().values()) {
                for (int id : hi.defaultFunctionIds().values()) {
                    interfaceDefaultFuncIds.add(id);
                    interfaceDefaultOwnerIfaceId.put(id, hi.id());
                }
                for (List<Integer> ids : hi.defaultOverloadFunctionIds().values()) {
                    for (int id : ids) {
                        if (id >= 0) {
                            interfaceDefaultFuncIds.add(id);
                            interfaceDefaultOwnerIfaceId.put(id, hi.id());
                        }
                    }
                }
            }
        }
    }

    /** Bound Wasm host import declared via {@code axiom wasm.<mod>.<name>: fn ...} ({@code BIP025-D9}). */
    public record WasmImportBinding(org.babblelang.analysis.LoweringTargets.WasmImportSpec spec, int funcIdx) {
    }

    private final List<ModuleInput> inputs;
    private final CompilationTarget target;
    private final WasmModule mod;
    private final WasmTypeSystem ts;
    private final Map<String, WasmImportBinding> wasmImports = new LinkedHashMap<>();
    private final WasmRuntimeGen rt;
    private final WasmRuntimeOpsGen ops;
    private final WasmArrayOpsGen arrOps;
    private final WasmStringOpsGen strOps;
    private final WasmCollectionOpsGen colOps;
    private final WasmRangeOpsGen rangeOps;
    private final WasmObjectSystem objects;
    private final List<ModuleLinkInfo> linkedModules = new ArrayList<>();
    private final Map<String, ModuleLinkInfo> modulesByKey = new LinkedHashMap<>();
    private final WasmModule.FuncDef fnInitSingletonsDef;

    private WasmProgramLinker(List<ModuleInput> inputs, CompilationTarget target) {
        this.inputs = List.copyOf(inputs);
        this.target = target;
        String modName = inputs.isEmpty() ? "babble" : sanitizeName(inputs.get(0).displayName());
        this.mod = new WasmModule(modName);
        this.ts = new WasmTypeSystem(mod, target);
        declareUserWasmImports();
        this.rt = new WasmRuntimeGen(mod, ts, target);
        this.ops = new WasmRuntimeOpsGen(mod, ts, rt, target);
        this.arrOps = new WasmArrayOpsGen(mod, ts, rt, ops);
        this.strOps = new WasmStringOpsGen(mod, ts, rt, arrOps);
        this.colOps = new WasmCollectionOpsGen(mod, ts, rt);
        this.rangeOps = new WasmRangeOpsGen(mod, ts, rt);
        this.ops.setCollections(colOps);
        this.rt.emitPolymorphicHelpers(
                arrOps.fnArrayDeepEquals, arrOps.fnArrayDeepHash, arrOps.fnArrayFormat,
                colOps.fnColEq, colOps.fnColHash, colOps.fnColFmt,
                rangeOps.fnRangeEq, rangeOps.fnRangeHash, rangeOps.fnRangeFmt);
        this.objects = new WasmObjectSystem(this);
        this.arrOps.emitTdSubtypeBody(objects);
        this.ops.emitClassofWithTable(objects.gClassVals);
        this.fnInitSingletonsDef = mod.addFunction("init_fn_singletons", ts.internFuncType(List.of(), List.of()));
    }

    private void declareUserWasmImports() {
        if (target != CompilationTarget.WASM_JS) {
            return;
        }
        for (ModuleInput in : inputs) {
            for (var spec : in.hir().wasmImports()) {
                String key = spec.module() + "." + spec.name();
                if (wasmImports.containsKey(key)) {
                    continue;
                }
                List<WasmValType> params = new ArrayList<>(spec.paramKinds().length);
                for (int i = 0; i < spec.paramKinds().length; i++) {
                    if (spec.paramIsString()[i]) {
                        params.add(WasmValType.EXTERNREF);
                    } else if (spec.paramKinds()[i] != org.babblelang.analysis.PrimitiveKind.NONE) {
                        params.add(ts.valTypeFor(spec.paramKinds()[i]));
                    } else {
                        params.add(WasmValType.EXTERNREF);
                    }
                }
                List<WasmValType> results;
                if (spec.returnIsVoid()) {
                    results = List.of();
                } else if (spec.returnIsString()) {
                    results = List.of(WasmValType.EXTERNREF);
                } else if (spec.returnKind() != org.babblelang.analysis.PrimitiveKind.NONE) {
                    results = List.of(ts.valTypeFor(spec.returnKind()));
                } else {
                    results = List.of(WasmValType.EXTERNREF);
                }
                int sigIdx = ts.internFuncType(params, results);
                int fidx = mod.addFuncImport(spec.module(), spec.name(), sigIdx,
                        "wasm_" + sanitizeName(spec.module()) + "_" + sanitizeName(spec.name()));
                wasmImports.put(key, new WasmImportBinding(spec, fidx));
            }
        }
    }

    public WasmImportBinding wasmImport(String module, String name) {
        return wasmImports.get(module + "." + name);
    }

    public static WasmModule link(List<ModuleInput> inputs, CompilationTarget target) {
        WasmProgramLinker linker = new WasmProgramLinker(inputs, target);
        linker.build();
        return linker.mod;
    }

    public WasmModule module() {
        return mod;
    }

    public List<ModuleLinkInfo> linkedModules() {
        return java.util.Collections.unmodifiableList(linkedModules);
    }

    public WasmTypeSystem types() {
        return ts;
    }

    public WasmRuntimeGen runtime() {
        return rt;
    }

    public WasmRuntimeOpsGen ops() {
        return ops;
    }

    public WasmArrayOpsGen arrays() {
        return arrOps;
    }

    public WasmStringOpsGen strings() {
        return strOps;
    }

    public WasmCollectionOpsGen collections() {
        return colOps;
    }

    public WasmRangeOpsGen ranges() {
        return rangeOps;
    }

    public WasmObjectSystem objects() {
        return objects;
    }

    public CompilationTarget target() {
        return target;
    }

    public ModuleLinkInfo moduleInfo(int index) {
        return linkedModules.get(index);
    }

    public ModuleLinkInfo moduleByKey(String key) {
        return modulesByKey.get(key);
    }

    private void build() {
        // Phase 1a: Create ModuleLinkInfo shells and top-level globals for every module
        for (int m = 0; m < inputs.size(); m++) {
            ModuleInput in = inputs.get(m);
            ModuleLinkInfo info = new ModuleLinkInfo(m, in);
            linkedModules.add(info);
            modulesByKey.put(in.key(), info);
            modulesByKey.put(in.hir().moduleId(), info);

            info.initStateGlobalIdx = mod.addGlobal("M" + m + "_state", WasmValType.I32, true,
                    List.of(new WasmInstr.I32Const(0)));
            info.modValGlobalIdx = mod.addGlobal("M" + m + "_mod", WasmValType.refNull(ts.moduleValType), true,
                    ts.defaultConstExpr(WasmValType.refNull(ts.moduleValType)));

            for (String name : in.hir().topLevelDeclaredNames()) {
                int g = mod.addGlobal("M" + m + "_g_" + sanitizeName(name), ts.anyRef(), true,
                        ts.defaultConstExpr(ts.anyRef()));
                info.topLevelGlobals.put(name, g);
            }
            for (String exp : in.hir().exports()) {
                if (!info.topLevelGlobals.containsKey(exp)) {
                    int g = mod.addGlobal("M" + m + "_g_" + sanitizeName(exp), ts.anyRef(), true,
                            ts.defaultConstExpr(ts.anyRef()));
                    info.topLevelGlobals.put(exp, g);
                }
            }
            for (HirExpr stmt : in.hir().topLevelStatements()) {
                if (stmt instanceof HirExpr.Def d && d.var() == null && !info.topLevelGlobals.containsKey(d.name())) {
                    int g = mod.addGlobal("M" + m + "_g_" + sanitizeName(d.name()), ts.anyRef(), true,
                            ts.defaultConstExpr(ts.anyRef()));
                    info.topLevelGlobals.put(d.name(), g);
                }
            }

            int initSig = ts.internFuncType(List.of(), List.of(ts.anyRef()));
            info.initFuncDef = mod.addFunction("M" + m + "_init", initSig);
            info.initFuncIdx = info.initFuncDef.funcIndex();
        }

        // Phase 1b: Declare all interfaces, classes, records, objects, bitsets & itables globally
        objects.declareAll(linkedModules);

        // Phase 1c: Declare all functions, class methods, and interface default methods
        for (ModuleLinkInfo info : linkedModules) {
            for (HirFunction fn : info.input.hir().functions().values()) {
                declareFunction(info, fn);
            }
            for (HirClass hc : info.input.hir().classes().values()) {
                if (hc.staticBlock() != null) {
                    WasmObjectSystem.ClassInfo ci = objects.classByLocal(info.index, hc.id());
                    markStaticMethods(info, hc.staticBlock().body(), ci, null);
                }
            }
            for (HirInterface hi : info.input.hir().interfaces().values()) {
                if (hi.staticBlock() != null) {
                    WasmObjectSystem.IfaceInfo ii = objects.ifaceByLocal(info.index, hi.id());
                    markStaticMethods(info, hi.staticBlock().body(), null, ii);
                }
            }
        }
        indexImmutableStaticBindings();

        // Phase 2: Emit all function/method bodies, constructors, vtable hooks, itables, and module inits
        WasmCallEmitter callEmitter = new WasmCallEmitter(this);
        WasmFuncEmitter funcEmitter = new WasmFuncEmitter(this, callEmitter);
        callEmitter.setFuncEmitter(funcEmitter);

        for (ModuleLinkInfo info : linkedModules) {
            for (FuncInfo fi : info.functions.values()) {
                funcEmitter.emitFunction(info, fi);
                if (fi.genericFuncDef != null) {
                    callEmitter.emitGenericTrampoline(info, fi);
                }
            }
        }
        objects.emitAll(funcEmitter, callEmitter);

        for (ModuleLinkInfo info : linkedModules) {
            funcEmitter.emitModuleInit(info);
        }

        // Phase 3: Emit entry-point exports (_start, _babble_run, and result/error readers)
        emitEntryPoints();

        // Phase 4: Finalize passive UTF-16 string literal segment
        rt.finalizeStrings();
    }

    private static void markStaticMethods(ModuleLinkInfo info, List<HirExpr> stmts,
                                          WasmObjectSystem.ClassInfo ci, WasmObjectSystem.IfaceInfo ii) {
        for (HirExpr e : stmts) {
            if (e instanceof HirExpr.Def d && d.value() != null) {
                markStaticFnExpr(info, d.value(), ci, ii);
            }
        }
    }

    private static void markStaticFnExpr(ModuleLinkInfo info, HirExpr expr,
                                         WasmObjectSystem.ClassInfo ci, WasmObjectSystem.IfaceInfo ii) {
        if (expr instanceof HirExpr.FunctionRef fr) {
            FuncInfo fi = info.functions.get(fr.functionId());
            if (fi != null) {
                fi.staticOwnerClass = ci;
                fi.staticOwnerIface = ii;
            }
        } else if (expr instanceof HirExpr.MakeOverloadSet ov) {
            for (HirExpr op : ov.operands()) {
                markStaticFnExpr(info, op, ci, ii);
            }
        }
    }

    /**
     * Indexes immutable top-level module imports, functions, classes, and class static methods
     * across the closed-world module DAG so {@link WasmCallEmitter} can emit direct typed Wasm
     * {@code call} instructions instead of runtime {@code $ModuleVal}/{@code $ClassVal} lookups
     * and {@code $generic} boxing arrays.
     */
    private void indexImmutableStaticBindings() {
        Set<String> globallyAssignedMembers = new HashSet<>();
        Map<Integer, Set<String>> moduleReassignedNames = new HashMap<>();
        for (ModuleLinkInfo info : linkedModules) {
            Set<String> reassigned = new HashSet<>();
            walkModuleExprs(info.input.hir(), e -> {
                if (e instanceof HirExpr.NameAssign na) {
                    reassigned.add(na.name());
                } else if (e instanceof HirExpr.DynamicFieldAssign dfa) {
                    globallyAssignedMembers.add(dfa.memberName());
                }
            });
            moduleReassignedNames.put(info.index, reassigned);
        }

        // Pass 1: record top-level statement ownership + direct immutable bindings within each module
        for (ModuleLinkInfo info : linkedModules) {
            HirModule hm = info.input.hir();
            String[] declNames = hm.topLevelDeclaredNames();
            boolean[] declFinals = hm.topLevelDeclaredFinals();
            for (int fi = 0; fi < Math.min(declNames.length, declFinals.length); fi++) {
                if (declFinals[fi]) {
                    info.immutableTopLevelNames.add(declNames[fi]);
                }
            }
            Set<String> reassigned = moduleReassignedNames.getOrDefault(info.index, Set.of());
            Map<String, Integer> defCounts = new HashMap<>();
            List<HirExpr> stmts = hm.topLevelStatements();
            for (HirExpr stmt : stmts) {
                if (stmt instanceof HirExpr.Def d && d.var() == null) {
                    defCounts.merge(d.name(), 1, Integer::sum);
                }
            }
            for (int i = 0; i < stmts.size(); i++) {
                final int stmtIdx = i;
                HirExpr stmt = stmts.get(i);
                markOwnedByTopLevelStmt(info, stmt, stmtIdx);
                if (!(stmt instanceof HirExpr.Def d) || d.var() != null || d.value() == null) {
                    continue;
                }
                if (defCounts.getOrDefault(d.name(), 0) != 1 || reassigned.contains(d.name())) {
                    continue;
                }
                info.topLevelDefIndex.put(d.name(), i);
                info.immutableTopLevelNames.add(d.name());
                HirExpr val = unwrapTypeApply(d.value());
                ModuleLinkInfo imported = resolveStaticImportExpr(info, val);
                if (imported != null && isAcyclicDependency(info, imported)) {
                    info.immutableModuleImports.put(d.name(), imported);
                } else if (val instanceof HirExpr.FunctionRef fr) {
                    FuncInfo fi = info.functions.get(fr.functionId());
                    if (fi != null && !fi.hasEnvParam && !fi.hasThisParam) {
                        info.immutableTopLevelFuncs.put(d.name(), fi);
                    }
                } else if (val instanceof HirExpr.ClassExpr ce) {
                    WasmObjectSystem.ClassInfo ci = objects.classByLocal(info.index, ce.classId());
                    if (ci != null && !ci.hasCaptures) {
                        info.immutableTopLevelClasses.put(d.name(), ci);
                    }
                } else if (val instanceof HirExpr.Const cst && isFoldableConst(cst.value())
                        && d.meta().wideningRank() < 0) {
                    info.immutableTopLevelConsts.put(d.name(), cst.value());
                } else if (d.value().meta().kind().isPrimitive()
                        && d.meta().wideningRank() < 0) {
                    org.babblelang.analysis.PrimitiveKind pk = d.value().meta().kind();
                    int pg = mod.addGlobal("M" + info.index + "_gp_" + sanitizeName(d.name()),
                            ts.valTypeFor(pk), true, ts.defaultConstExpr(ts.valTypeFor(pk)));
                    info.immutableTopLevelPrimGlobals.put(d.name(), new PrimitiveGlobal(info.index, pg, pk));
                }
            }
        }

        // Pass 2: propagate immutable imported aliases (`def X = import("...").Y` or `def X = mod.Y`)
        for (int pass = 0; pass < linkedModules.size(); pass++) {
            boolean changed = false;
            for (ModuleLinkInfo info : linkedModules) {
                List<HirExpr> stmts = info.input.hir().topLevelStatements();
                for (int i = 0; i < stmts.size(); i++) {
                    if (!(stmts.get(i) instanceof HirExpr.Def d) || d.var() != null || d.value() == null) {
                        continue;
                    }
                    if (!info.topLevelDefIndex.containsKey(d.name())) {
                        continue;
                    }
                    HirExpr val = unwrapTypeApply(d.value());
                    if (val instanceof HirExpr.ModuleExportGet meg) {
                        ModuleLinkInfo targetMod = resolveStaticImportExpr(info, unwrapTypeApply(meg.receiver()));
                        if (targetMod == null && meg.receiver() instanceof HirExpr.NameRead nr) {
                            Integer modDefIdx = info.topLevelDefIndex.get(nr.name());
                            if (modDefIdx != null && modDefIdx < i) {
                                targetMod = info.immutableModuleImports.get(nr.name());
                            }
                        }
                        if (targetMod != null && isAcyclicDependency(info, targetMod)) {
                            String exportName = meg.target().name();
                            if (!info.immutableTopLevelFuncs.containsKey(d.name())) {
                                FuncInfo tfi = targetMod.immutableTopLevelFuncs.get(exportName);
                                if (tfi != null) {
                                    info.immutableTopLevelFuncs.put(d.name(), tfi);
                                    changed = true;
                                }
                            }
                            if (!info.immutableTopLevelClasses.containsKey(d.name())) {
                                WasmObjectSystem.ClassInfo tci = targetMod.immutableTopLevelClasses.get(exportName);
                                if (tci != null) {
                                    info.immutableTopLevelClasses.put(d.name(), tci);
                                    changed = true;
                                }
                            }
                            if (!info.immutableTopLevelConsts.containsKey(d.name())
                                    && d.meta().wideningRank() < 0) {
                                Object tcst = targetMod.immutableTopLevelConsts.get(exportName);
                                if (tcst != null) {
                                    info.immutableTopLevelConsts.put(d.name(), tcst);
                                    changed = true;
                                }
                            }
                            if (!info.immutableTopLevelPrimGlobals.containsKey(d.name())
                                    && d.meta().wideningRank() < 0) {
                                PrimitiveGlobal tpg = targetMod.immutableTopLevelPrimGlobals.get(exportName);
                                if (tpg != null) {
                                    info.immutableTopLevelPrimGlobals.put(d.name(), tpg);
                                    changed = true;
                                }
                            }
                        }
                    }
                }
            }
            if (!changed) break;
        }

        // Pass 3: index immutable static methods & fields on classes (e.g. `HitRecord.create`, `Vec3.ZERO`)
        for (ModuleLinkInfo info : linkedModules) {
            for (HirClass hc : info.input.hir().classes().values()) {
                if (hc.staticBlock() == null) continue;
                WasmObjectSystem.ClassInfo ci = objects.classByLocal(info.index, hc.id());
                if (ci == null) continue;
                List<HirExpr> body = hc.staticBlock().body();
                Map<String, Integer> stDefCounts = new HashMap<>();
                Set<String> stReassigned = new HashSet<>();
                Deque<HirExpr> work = new ArrayDeque<>(body);
                Set<Integer> seenFns = new HashSet<>();
                for (HirExpr e : body) {
                    if (e instanceof HirExpr.Def d && d.var() == null) {
                        stDefCounts.merge(d.name(), 1, Integer::sum);
                    }
                }
                while (!work.isEmpty()) {
                    walkExpr(work.poll(), x -> {
                        if (x instanceof HirExpr.NameAssign na) {
                            stReassigned.add(na.name());
                        } else if (x instanceof HirExpr.FunctionRef fr && seenFns.add(fr.functionId())) {
                            HirFunction hf = info.input.hir().functions().get(fr.functionId());
                            if (hf != null) {
                                for (HirParam p : hf.params()) {
                                    if (p.defaultValue() != null) work.add(p.defaultValue());
                                }
                                work.addAll(hf.body());
                            }
                        }
                    });
                }
                for (HirExpr e : body) {
                    if (!(e instanceof HirExpr.Def d) || d.var() != null || d.value() == null) continue;
                    if (stDefCounts.getOrDefault(d.name(), 0) != 1 || stReassigned.contains(d.name())) continue;
                    int sIdx = ci.shape.staticIndex(d.name());
                    boolean isImmutableStaticDef = sIdx >= 0 && !ci.shape.isStaticMutable(sIdx);
                    if (!isImmutableStaticDef && globallyAssignedMembers.contains(d.name())) continue;
                    if (isImmutableStaticDef) {
                        ci.immutableStaticFields.add(d.name());
                    }
                    HirExpr val = unwrapTypeApply(d.value());
                    if (val instanceof HirExpr.FunctionRef fr) {
                        FuncInfo fi = info.functions.get(fr.functionId());
                        if (fi != null && !fi.hasEnvParam && !fi.hasThisParam) {
                            ci.immutableStaticMethods.put(d.name(), fi);
                        }
                    } else if (isImmutableStaticDef
                            && val instanceof HirExpr.Const cst && isFoldableConst(cst.value())
                            && d.meta().wideningRank() < 0) {
                        ci.immutableStaticConsts.put(d.name(), cst.value());
                    }
                }
            }
        }
    }

    private static boolean isFoldableConst(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double
                || value instanceof Boolean || value instanceof Character || value instanceof String;
    }

    private void markOwnedByTopLevelStmt(ModuleLinkInfo info, HirExpr root, int stmtIdx) {
        HirModule hm = info.input.hir();
        Deque<HirExpr> work = new ArrayDeque<>();
        Set<Integer> seenFns = new HashSet<>();
        Set<Integer> seenCls = new HashSet<>();
        Set<Integer> seenIfaces = new HashSet<>();
        if (root != null) work.add(root);
        while (!work.isEmpty()) {
            HirExpr cur = work.poll();
            walkExpr(cur, e -> {
                if (e instanceof HirExpr.FunctionRef fr && seenFns.add(fr.functionId())) {
                    info.funcOwnerTopLevelStmtIdx.putIfAbsent(fr.functionId(), stmtIdx);
                    HirFunction hf = hm.functions().get(fr.functionId());
                    if (hf != null) {
                        for (HirParam p : hf.params()) if (p.defaultValue() != null) work.add(p.defaultValue());
                        work.addAll(hf.body());
                    }
                } else if (e instanceof HirExpr.ClassExpr ce && seenCls.add(ce.classId())) {
                    info.classOwnerTopLevelStmtIdx.putIfAbsent(ce.classId(), stmtIdx);
                    HirClass hc = hm.classes().get(ce.classId());
                    if (hc != null) {
                        for (HirParam p : hc.params()) if (p.defaultValue() != null) work.add(p.defaultValue());
                        work.addAll(hc.body());
                        if (hc.staticBlock() != null) work.addAll(hc.staticBlock().body());
                    }
                } else if (e instanceof HirExpr.InterfaceExpr ie && seenIfaces.add(ie.interfaceId())) {
                    HirInterface hi = hm.interfaces().get(ie.interfaceId());
                    if (hi != null) {
                        work.addAll(hi.body());
                        if (hi.staticBlock() != null) work.addAll(hi.staticBlock().body());
                    }
                }
            });
        }
    }

    private static HirExpr unwrapTypeApply(HirExpr e) {
        while (e instanceof HirExpr.TypeApply ta) {
            e = ta.inner();
        }
        return e;
    }

    private ModuleLinkInfo resolveStaticImportExpr(ModuleLinkInfo fromMod, HirExpr expr) {
        if (expr instanceof HirExpr.DynamicCall dc
                && dc.callee() instanceof HirExpr.NameRead nr
                && ("import".equals(nr.name()) || "importe".equals(nr.name()))
                && dc.args().size() == 1
                && dc.args().get(0).value() instanceof HirExpr.Const cst
                && cst.value() instanceof String path) {
            String key = fromMod.input.staticImports().get(path);
            return key != null ? moduleByKey(key) : null;
        }
        return null;
    }

    /** True iff {@code targetMod} does not transitively import {@code fromMod} (no init cycle). */
    private boolean isAcyclicDependency(ModuleLinkInfo fromMod, ModuleLinkInfo targetMod) {
        if (fromMod.index == targetMod.index) return false;
        Set<Integer> visited = new HashSet<>();
        Deque<ModuleLinkInfo> stack = new ArrayDeque<>();
        stack.push(targetMod);
        visited.add(targetMod.index);
        while (!stack.isEmpty()) {
            ModuleLinkInfo cur = stack.pop();
            for (String depKey : cur.input.staticImports().values()) {
                ModuleLinkInfo dep = moduleByKey(depKey);
                if (dep == null) continue;
                if (dep.index == fromMod.index) return false;
                if (visited.add(dep.index)) stack.push(dep);
            }
        }
        return true;
    }

    private static void walkModuleExprs(HirModule hm, java.util.function.Consumer<HirExpr> visitor) {
        for (HirExpr e : hm.topLevelStatements()) walkExpr(e, visitor);
        for (HirFunction f : hm.functions().values()) {
            for (HirParam p : f.params()) walkExpr(p.defaultValue(), visitor);
            for (HirExpr e : f.body()) walkExpr(e, visitor);
        }
        for (HirClass c : hm.classes().values()) {
            for (HirParam p : c.params()) walkExpr(p.defaultValue(), visitor);
            for (HirExpr e : c.body()) walkExpr(e, visitor);
            if (c.staticBlock() != null) {
                for (HirExpr e : c.staticBlock().body()) walkExpr(e, visitor);
            }
        }
        for (HirInterface hi : hm.interfaces().values()) {
            for (HirExpr e : hi.body()) walkExpr(e, visitor);
            if (hi.staticBlock() != null) {
                for (HirExpr e : hi.staticBlock().body()) walkExpr(e, visitor);
            }
        }
    }

    private static void walkExpr(HirExpr e, java.util.function.Consumer<HirExpr> visitor) {
        if (e == null) return;
        visitor.accept(e);
        switch (e) {
            case HirExpr.Block b -> { for (HirExpr s : b.statements()) walkExpr(s, visitor); }
            case HirExpr.Def d -> walkExpr(d.value(), visitor);
            case HirExpr.VarAssign a -> walkExpr(a.value(), visitor);
            case HirExpr.NameAssign a -> walkExpr(a.value(), visitor);
            case HirExpr.ThisFieldAssign a -> { walkExpr(a.receiver(), visitor); walkExpr(a.value(), visitor); }
            case HirExpr.DynamicFieldAssign a -> { walkExpr(a.receiver(), visitor); walkExpr(a.value(), visitor); }
            case HirExpr.ThisFieldGet g -> walkExpr(g.receiver(), visitor);
            case HirExpr.ModuleExportGet g -> walkExpr(g.receiver(), visitor);
            case HirExpr.DynamicSelect s -> walkExpr(s.receiver(), visitor);
            case HirExpr.Unary u -> walkExpr(u.operand(), visitor);
            case HirExpr.Binary b -> { walkExpr(b.left(), visitor); walkExpr(b.right(), visitor); }
            case HirExpr.Range r -> { walkExpr(r.start(), visitor); walkExpr(r.end(), visitor); }
            case HirExpr.Logic l -> { walkExpr(l.left(), visitor); walkExpr(l.right(), visitor); }
            case HirExpr.If i -> { walkExpr(i.cond(), visitor); walkExpr(i.thenBranch(), visitor); walkExpr(i.elseBranch(), visitor); }
            case HirExpr.While w -> { walkExpr(w.cond(), visitor); walkExpr(w.body(), visitor); }
            case HirExpr.For f -> {
                if (f.countedRange() != null) {
                    walkExpr(f.countedRange().start(), visitor);
                    walkExpr(f.countedRange().end(), visitor);
                    walkExpr(f.countedRange().step(), visitor);
                } else {
                    walkExpr(f.iterable(), visitor);
                }
                for (HirExpr s : f.body()) walkExpr(s, visitor);
            }
            case HirExpr.Return r -> walkExpr(r.value(), visitor);
            case HirExpr.Break b -> walkExpr(b.value(), visitor);
            case HirExpr.Throw t -> walkExpr(t.value(), visitor);
            case HirExpr.Try t -> {
                for (HirExpr s : t.tryBody()) walkExpr(s, visitor);
                for (HirExpr.HirCatch hc : t.catches()) for (HirExpr s : hc.body()) walkExpr(s, visitor);
                for (HirExpr s : t.finallyBody()) walkExpr(s, visitor);
            }
            case HirExpr.DynamicCall c -> { walkExpr(c.callee(), visitor); for (var a : c.args()) walkExpr(a.value(), visitor); }
            case HirExpr.DirectBabbleCall c -> { walkExpr(c.callee(), visitor); for (HirExpr a : c.args()) walkExpr(a, visitor); }
            case HirExpr.DirectThisMethodCall c -> { walkExpr(c.receiver(), visitor); for (var a : c.args()) walkExpr(a.value(), visitor); }
            case HirExpr.InterfaceCall c -> { walkExpr(c.receiver(), visitor); for (HirExpr a : c.args()) walkExpr(a, visitor); }
            case HirExpr.SafeCall c -> { walkExpr(c.receiver(), visitor); for (var a : c.args()) walkExpr(a.value(), visitor); }
            case HirExpr.SelfRecurseCall c -> { for (HirExpr a : c.args()) walkExpr(a, visitor); }
            case HirExpr.SelfTailCall c -> { for (var a : c.args()) walkExpr(a.value(), visitor); }
            case HirExpr.CoreMethodCall c -> { walkExpr(c.receiver(), visitor); for (HirExpr a : c.args()) walkExpr(a, visitor); }
            case HirExpr.SuperCall c -> { for (var a : c.args()) walkExpr(a.value(), visitor); }
            case HirExpr.RecordNew r -> { walkExpr(r.classRef(), visitor); for (HirExpr a : r.args()) walkExpr(a, visitor); }
            case HirExpr.RecordReuse r -> { walkExpr(r.target(), visitor); for (HirExpr a : r.args()) walkExpr(a, visitor); }
            case HirExpr.MakeOverloadSet m -> { for (HirExpr op : m.operands()) walkExpr(op, visitor); }
            case HirExpr.OverloadSelect s -> walkExpr(s.set(), visitor);
            case HirExpr.OverloadDispatch d -> { walkExpr(d.callee(), visitor); for (HirExpr a : d.args()) walkExpr(a, visitor); }
            case HirExpr.CoreCollectionCall c -> { for (var a : c.args()) walkExpr(a.value(), visitor); }
            case HirExpr.ArrayCreate a -> { for (HirExpr x : a.args()) walkExpr(x, visitor); }
            case HirExpr.ArrayOp a -> { walkExpr(a.receiver(), visitor); for (HirExpr x : a.args()) walkExpr(x, visitor); }
            case HirExpr.SpreadAdapt s -> walkExpr(s.value(), visitor);
            case HirExpr.TypeApply t -> walkExpr(t.inner(), visitor);
            case HirExpr.Is i -> walkExpr(i.left(), visitor);
            case HirExpr.As a -> walkExpr(a.left(), visitor);
            case HirExpr.NonNull n -> walkExpr(n.operand(), visitor);
            case HirExpr.Move m -> walkExpr(m.operand(), visitor);
            case HirExpr.Freeze f -> walkExpr(f.operand(), visitor);
            case HirExpr.JavaMethodCall c -> { walkExpr(c.receiver(), visitor); for (HirExpr a : c.args()) walkExpr(a, visitor); }
            case HirExpr.JavaConstructorCall c -> { for (HirExpr a : c.args()) walkExpr(a, visitor); }
            case HirExpr.Match m -> {
                walkExpr(m.scrutinee(), visitor);
                for (HirExpr.MatchArm arm : m.arms()) {
                    walkPattern(arm.pattern(), visitor);
                    walkExpr(arm.guard(), visitor);
                    walkExpr(arm.body(), visitor);
                }
                if (m.elseArm() != null) {
                    walkExpr(m.elseArm().body(), visitor);
                }
            }
            default -> {}
        }
    }

    private static void walkPattern(org.babblelang.ir.hir.HirPattern pat, java.util.function.Consumer<HirExpr> visitor) {
        if (pat == null) return;
        switch (pat) {
            case org.babblelang.ir.hir.HirPattern.Const ignored -> {}
            case org.babblelang.ir.hir.HirPattern.TypeTest ignored -> {}
            case org.babblelang.ir.hir.HirPattern.Wildcard ignored -> {}
            case org.babblelang.ir.hir.HirPattern.Bind b -> walkPattern(b.inner(), visitor);
            case org.babblelang.ir.hir.HirPattern.ValueExpr v -> walkExpr(v.expr(), visitor);
            case org.babblelang.ir.hir.HirPattern.Or o -> {
                for (var alt : o.alternatives()) walkPattern(alt, visitor);
            }
            case org.babblelang.ir.hir.HirPattern.Relational r -> walkExpr(r.rhs(), visitor);
            case org.babblelang.ir.hir.HirPattern.RangeTest rt -> {
                walkExpr(rt.lo(), visitor);
                walkExpr(rt.hi(), visitor);
                walkExpr(rt.step(), visitor);
            }
            case org.babblelang.ir.hir.HirPattern.Membership m -> walkExpr(m.container(), visitor);
        }
    }

    private void declareFunction(ModuleLinkInfo modInfo, HirFunction fn) {
        WasmObjectSystem.ClassInfo ownerClass = fn.ownerClassId() >= 0
                ? objects.classByLocal(modInfo.index, fn.ownerClassId()) : null;
        boolean isIfaceDefault = ownerClass == null && modInfo.interfaceDefaultFuncIds.contains(fn.id());
        Integer ownerIfaceId = isIfaceDefault ? modInfo.interfaceDefaultOwnerIfaceId.get(fn.id()) : null;
        WasmObjectSystem.IfaceInfo ownerIface = ownerIfaceId != null
                ? objects.ifaceByLocal(modInfo.index, ownerIfaceId) : null;
        boolean hasThis = ownerClass != null || isIfaceDefault;

        List<WasmValType> capTypes = new ArrayList<>();
        if (!hasThis) {
            for (HirVar cv : fn.capturedVars()) {
                capTypes.add(isValueCapture(cv)
                        ? ts.valTypeFor(cv.primitiveKind())
                        : WasmValType.ref(ts.cellTypeFor(cv.primitiveKind())));
            }
        }
        boolean hasEnv = !hasThis && !capTypes.isEmpty();
        int closureType = -1;
        if (hasEnv) {
            closureType = ts.addClosureType("M" + modInfo.index + "_Clos_" + fn.id(), capTypes);
        }

        boolean hasOptMask = fn.hasOptionalMask();
        int optCount = 0;
        for (HirParam p : fn.params()) {
            if (p.defaultValue() != null) optCount++;
        }
        boolean hasBoxedMissingOpt = optCount > 0 && !hasOptMask;

        List<WasmValType> params = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();
        if (ownerClass != null) {
            params.add(WasmValType.ref(ownerClass.structTypeIdx));
            paramNames.add("$this");
        } else if (isIfaceDefault) {
            params.add(WasmValType.ref(ts.objectType));
            paramNames.add("$this");
        } else if (hasEnv) {
            params.add(WasmValType.ref(closureType));
            paramNames.add("$env");
        }
        for (HirParam p : fn.params()) {
            if (hasBoxedMissingOpt && p.defaultValue() != null) {
                params.add(ts.anyRef());
            } else {
                params.add(ts.valTypeFor(p.kind()));
            }
            paramNames.add(p.name());
        }
        if (hasOptMask) {
            params.add(WasmValType.I32);
            paramNames.add("$optMask");
        }
        List<WasmValType> results = List.of(ts.valTypeFor(fn.returnKind()));
        int directSig = ts.internFuncType(params, results);

        String fnLabel = "M" + modInfo.index + "_fn" + fn.id()
                + (fn.name() != null && !fn.name().isEmpty() ? "_" + sanitizeName(fn.name()) : "");
        WasmModule.FuncDef directDef = mod.addFunction(fnLabel, directSig, paramNames);

        WasmModule.FuncDef genericDef = null;
        int singletonGlobal = -1;
        if (!hasThis) {
            genericDef = mod.addFunction(fnLabel + "$generic", ts.genericCallSigType,
                    List.of("self", "args", "names"));
            mod.declareFuncRef(genericDef.funcIndex());
            if (!hasEnv) {
                singletonGlobal = mod.addGlobal("G_" + fnLabel, WasmValType.refNull(ts.fnBaseType), true,
                        ts.defaultConstExpr(WasmValType.refNull(ts.fnBaseType)));
                WasmCodeBuilder init = fnInitSingletonsDef.code();
                init.refFunc(genericDef.funcIndex());
                rt.emitStringLit(init, fn.name() != null && !fn.name().isEmpty() ? fn.name() : "<fn>");
                init.i32Const(fn.id())
                        .structNew(ts.fnBaseType)
                        .globalSet(singletonGlobal);
            }
        }

        FuncInfo fi = new FuncInfo(modInfo.index, fn, directDef, genericDef, closureType, singletonGlobal,
                capTypes, hasEnv, hasThis, ownerClass, ownerIface, isIfaceDefault, hasOptMask, hasBoxedMissingOpt);
        modInfo.functions.put(fn.id(), fi);
    }

    private void emitEntryPoints() {
        int tVoid = ts.internFuncType(List.of(), List.of());
        int tI32 = ts.internFuncType(List.of(), List.of(WasmValType.I32));
        int tI32ToI32 = ts.internFuncType(List.of(WasmValType.I32), List.of(WasmValType.I32));

        WasmModule.FuncDef startFn = mod.addFunction("_start", tVoid);
        WasmCodeBuilder sc = startFn.code();
        sc.call(rt.fnInitStrings)
                .call(rt.fnInitArgs)
                .call(ops.fnInitBuiltins)
                .call(arrOps.fnInitArrayTypes)
                .call(objects.fnInitObjects)
                .call(fnInitSingletonsDef.funcIndex());
        if (!linkedModules.isEmpty()) {
            sc.call(linkedModules.get(0).initFuncIdx).globalSet(rt.gLastResult);
        }
        mod.addExport("_start", WasmModule.ExportKind.FUNC, startFn.funcIndex());

        WasmModule.FuncDef runFn = mod.addFunction("_babble_run", tI32);
        int exnVal = runFn.addLocal("exnVal", ts.anyRef(), 0);
        int exnLine = runFn.addLocal("exnLine", WasmValType.I32, 0);
        WasmCodeBuilder rc = runFn.code();
        rc.refNull(ts.stringType).globalSet(rt.gLastError);
        rc.block(WasmValType.I32)
                .blockType(ts.exnCatchPairType)
                .tryTable(List.of(WasmInstr.CatchClause.catchTag(rt.babbleExnTag, 0)))
                .call(startFn.funcIndex())
                .end()
                .i32Const(0)
                .br(1)
                .end()
                .localSet(exnLine)
                .localSet(exnVal)
                .localGet(exnVal).call(rt.fnFormat).globalSet(rt.gLastError)
                .i32Const(1)
                .end();
        mod.addExport("_babble_run", WasmModule.ExportKind.FUNC, runFn.funcIndex());

        WasmModule.FuncDef resStrLen = mod.addFunction("_babble_result_len", tI32);
        resStrLen.code().globalGet(rt.gLastResult).call(rt.fnFormat)
                .structGet(ts.stringType, 0).arrayLen();
        mod.addExport("_babble_result_len", WasmModule.ExportKind.FUNC, resStrLen.funcIndex());

        WasmModule.FuncDef resStrChar = mod.addFunction("_babble_result_char", tI32ToI32, List.of("i"));
        resStrChar.code().globalGet(rt.gLastResult).call(rt.fnFormat)
                .structGet(ts.stringType, 0).localGet(0).arrayGetU(ts.charArrType);
        mod.addExport("_babble_result_char", WasmModule.ExportKind.FUNC, resStrChar.funcIndex());

        WasmModule.FuncDef errStrLen = mod.addFunction("_babble_error_len", tI32);
        errStrLen.code().globalGet(rt.gLastError).refIsNull().if_(WasmValType.I32)
                .i32Const(-1)
                .else_()
                .globalGet(rt.gLastError).refAsNonNull().structGet(ts.stringType, 0).arrayLen()
                .end();
        mod.addExport("_babble_error_len", WasmModule.ExportKind.FUNC, errStrLen.funcIndex());

        WasmModule.FuncDef errStrChar = mod.addFunction("_babble_error_char", tI32ToI32, List.of("i"));
        errStrChar.code().globalGet(rt.gLastError).refAsNonNull()
                .structGet(ts.stringType, 0).localGet(0).arrayGetU(ts.charArrType);
        mod.addExport("_babble_error_char", WasmModule.ExportKind.FUNC, errStrChar.funcIndex());

        if (target == CompilationTarget.WASM_JS) {
            int tExt = ts.internFuncType(List.of(), List.of(WasmValType.EXTERNREF));
            WasmModule.FuncDef resJs = mod.addFunction("_babble_result_js", tExt);
            resJs.code().globalGet(rt.gLastResult).call(rt.fnFormat).call(rt.fnToExternStr);
            mod.addExport("_babble_result_js", WasmModule.ExportKind.FUNC, resJs.funcIndex());

            WasmModule.FuncDef errJs = mod.addFunction("_babble_error_js", tExt);
            errJs.code().globalGet(rt.gLastError).refIsNull().if_(WasmValType.EXTERNREF)
                    .refNull(WasmValType.HeapType.Abstract.EXTERN)
                    .else_()
                    .globalGet(rt.gLastError).refAsNonNull().call(rt.fnToExternStr)
                    .end();
            mod.addExport("_babble_error_js", WasmModule.ExportKind.FUNC, errJs.funcIndex());
        }
    }

    private List<org.babblelang.runtime.OverloadSet.AltMeta> cachedOverloadMetas;

    /** Returns all {@code AltMeta} descriptors appearing in any {@code MakeOverloadSet} across linked modules. */
    public List<org.babblelang.runtime.OverloadSet.AltMeta> allOverloadAltMetas() {
        if (cachedOverloadMetas != null) {
            return cachedOverloadMetas;
        }
        List<org.babblelang.runtime.OverloadSet.AltMeta> out = new ArrayList<>();
        for (ModuleLinkInfo mi : linkedModules) {
            HirModule hm = mi.input.hir();
            for (HirExpr e : hm.topLevelStatements()) collectOverloadMetas(e, out);
            for (HirFunction f : hm.functions().values()) {
                for (HirParam p : f.params()) collectOverloadMetas(p.defaultValue(), out);
                for (HirExpr e : f.body()) collectOverloadMetas(e, out);
            }
            for (HirClass c : hm.classes().values()) {
                for (HirParam p : c.params()) collectOverloadMetas(p.defaultValue(), out);
                for (HirExpr e : c.body()) collectOverloadMetas(e, out);
                if (c.staticBlock() != null) {
                    for (HirExpr e : c.staticBlock().body()) collectOverloadMetas(e, out);
                }
            }
            for (HirInterface hi : hm.interfaces().values()) {
                for (HirExpr e : hi.body()) collectOverloadMetas(e, out);
                if (hi.staticBlock() != null) {
                    for (HirExpr e : hi.staticBlock().body()) collectOverloadMetas(e, out);
                }
            }
        }
        cachedOverloadMetas = List.copyOf(out);
        return cachedOverloadMetas;
    }

    private static void collectOverloadMetas(HirExpr e, List<org.babblelang.runtime.OverloadSet.AltMeta> out) {
        if (e == null) return;
        if (e instanceof HirExpr.MakeOverloadSet m) {
            for (var info : m.metas()) {
                out.add(new org.babblelang.runtime.OverloadSet.AltMeta(
                        info.signatureKey(), info.display(), info.minArity(), info.maxArity(),
                        info.restIndex(), info.paramDescriptors()));
            }
            for (HirExpr op : m.operands()) collectOverloadMetas(op, out);
            return;
        }
        switch (e) {
            case HirExpr.Block b -> { for (HirExpr s : b.statements()) collectOverloadMetas(s, out); }
            case HirExpr.Def d -> collectOverloadMetas(d.value(), out);
            case HirExpr.VarAssign a -> collectOverloadMetas(a.value(), out);
            case HirExpr.NameAssign a -> collectOverloadMetas(a.value(), out);
            case HirExpr.ThisFieldAssign a -> { collectOverloadMetas(a.receiver(), out); collectOverloadMetas(a.value(), out); }
            case HirExpr.DynamicFieldAssign a -> { collectOverloadMetas(a.receiver(), out); collectOverloadMetas(a.value(), out); }
            case HirExpr.ThisFieldGet g -> collectOverloadMetas(g.receiver(), out);
            case HirExpr.ModuleExportGet g -> collectOverloadMetas(g.receiver(), out);
            case HirExpr.DynamicSelect s -> collectOverloadMetas(s.receiver(), out);
            case HirExpr.Unary u -> collectOverloadMetas(u.operand(), out);
            case HirExpr.Binary b -> { collectOverloadMetas(b.left(), out); collectOverloadMetas(b.right(), out); }
            case HirExpr.Range r -> { collectOverloadMetas(r.start(), out); collectOverloadMetas(r.end(), out); }
            case HirExpr.Logic l -> { collectOverloadMetas(l.left(), out); collectOverloadMetas(l.right(), out); }
            case HirExpr.If i -> { collectOverloadMetas(i.cond(), out); collectOverloadMetas(i.thenBranch(), out); collectOverloadMetas(i.elseBranch(), out); }
            case HirExpr.While w -> { collectOverloadMetas(w.cond(), out); collectOverloadMetas(w.body(), out); }
            case HirExpr.For f -> {
                if (f.countedRange() != null) {
                    collectOverloadMetas(f.countedRange().start(), out);
                    collectOverloadMetas(f.countedRange().end(), out);
                    collectOverloadMetas(f.countedRange().step(), out);
                } else {
                    collectOverloadMetas(f.iterable(), out);
                }
                for (HirExpr s : f.body()) collectOverloadMetas(s, out);
            }
            case HirExpr.Return r -> collectOverloadMetas(r.value(), out);
            case HirExpr.Break b -> collectOverloadMetas(b.value(), out);
            case HirExpr.Throw t -> collectOverloadMetas(t.value(), out);
            case HirExpr.Try t -> {
                for (HirExpr s : t.tryBody()) collectOverloadMetas(s, out);
                for (HirExpr.HirCatch hc : t.catches()) for (HirExpr s : hc.body()) collectOverloadMetas(s, out);
                for (HirExpr s : t.finallyBody()) collectOverloadMetas(s, out);
            }
            case HirExpr.DynamicCall c -> { collectOverloadMetas(c.callee(), out); for (var a : c.args()) collectOverloadMetas(a.value(), out); }
            case HirExpr.DirectBabbleCall c -> { collectOverloadMetas(c.callee(), out); for (HirExpr a : c.args()) collectOverloadMetas(a, out); }
            case HirExpr.DirectThisMethodCall c -> { collectOverloadMetas(c.receiver(), out); for (var a : c.args()) collectOverloadMetas(a.value(), out); }
            case HirExpr.InterfaceCall c -> { collectOverloadMetas(c.receiver(), out); for (HirExpr a : c.args()) collectOverloadMetas(a, out); }
            case HirExpr.SafeCall c -> { collectOverloadMetas(c.receiver(), out); for (var a : c.args()) collectOverloadMetas(a.value(), out); }
            case HirExpr.SelfRecurseCall c -> { for (HirExpr a : c.args()) collectOverloadMetas(a, out); }
            case HirExpr.SelfTailCall c -> { for (var a : c.args()) collectOverloadMetas(a.value(), out); }
            case HirExpr.CoreMethodCall c -> { collectOverloadMetas(c.receiver(), out); for (HirExpr a : c.args()) collectOverloadMetas(a, out); }
            case HirExpr.SuperCall c -> { for (var a : c.args()) collectOverloadMetas(a.value(), out); }
            case HirExpr.RecordNew r -> { collectOverloadMetas(r.classRef(), out); for (HirExpr a : r.args()) collectOverloadMetas(a, out); }
            case HirExpr.RecordReuse r -> { collectOverloadMetas(r.target(), out); for (HirExpr a : r.args()) collectOverloadMetas(a, out); }
            case HirExpr.OverloadSelect s -> collectOverloadMetas(s.set(), out);
            case HirExpr.OverloadDispatch d -> { collectOverloadMetas(d.callee(), out); for (HirExpr a : d.args()) collectOverloadMetas(a, out); }
            case HirExpr.CoreCollectionCall c -> { for (var a : c.args()) collectOverloadMetas(a.value(), out); }
            case HirExpr.ArrayCreate a -> { for (HirExpr x : a.args()) collectOverloadMetas(x, out); }
            case HirExpr.ArrayOp a -> { collectOverloadMetas(a.receiver(), out); for (HirExpr x : a.args()) collectOverloadMetas(x, out); }
            case HirExpr.SpreadAdapt s -> collectOverloadMetas(s.value(), out);
            case HirExpr.TypeApply t -> collectOverloadMetas(t.inner(), out);
            case HirExpr.Is i -> collectOverloadMetas(i.left(), out);
            case HirExpr.As a -> collectOverloadMetas(a.left(), out);
            case HirExpr.NonNull n -> collectOverloadMetas(n.operand(), out);
            case HirExpr.Move m -> collectOverloadMetas(m.operand(), out);
            case HirExpr.Freeze f -> collectOverloadMetas(f.operand(), out);
            case HirExpr.JavaMethodCall c -> { collectOverloadMetas(c.receiver(), out); for (HirExpr a : c.args()) collectOverloadMetas(a, out); }
            case HirExpr.JavaConstructorCall c -> { for (HirExpr a : c.args()) collectOverloadMetas(a, out); }
            case HirExpr.Match m -> {
                collectOverloadMetas(m.scrutinee(), out);
                for (HirExpr.MatchArm arm : m.arms()) {
                    collectPatternOverloadMetas(arm.pattern(), out);
                    collectOverloadMetas(arm.guard(), out);
                    collectOverloadMetas(arm.body(), out);
                }
                if (m.elseArm() != null) {
                    collectOverloadMetas(m.elseArm().body(), out);
                }
            }
            default -> {}
        }
    }

    private static void collectPatternOverloadMetas(org.babblelang.ir.hir.HirPattern pat,
                                                    List<org.babblelang.runtime.OverloadSet.AltMeta> out) {
        if (pat == null) return;
        switch (pat) {
            case org.babblelang.ir.hir.HirPattern.Const ignored -> {}
            case org.babblelang.ir.hir.HirPattern.TypeTest ignored -> {}
            case org.babblelang.ir.hir.HirPattern.Wildcard ignored -> {}
            case org.babblelang.ir.hir.HirPattern.Bind b -> collectPatternOverloadMetas(b.inner(), out);
            case org.babblelang.ir.hir.HirPattern.ValueExpr v -> collectOverloadMetas(v.expr(), out);
            case org.babblelang.ir.hir.HirPattern.Or o -> {
                for (var alt : o.alternatives()) collectPatternOverloadMetas(alt, out);
            }
            case org.babblelang.ir.hir.HirPattern.Relational r -> collectOverloadMetas(r.rhs(), out);
            case org.babblelang.ir.hir.HirPattern.RangeTest rt -> {
                collectOverloadMetas(rt.lo(), out);
                collectOverloadMetas(rt.hi(), out);
                collectOverloadMetas(rt.step(), out);
            }
            case org.babblelang.ir.hir.HirPattern.Membership m -> collectOverloadMetas(m.container(), out);
        }
    }

    public static String sanitizeName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "anon";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
