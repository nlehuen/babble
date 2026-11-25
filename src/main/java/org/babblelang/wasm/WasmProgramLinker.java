package org.babblelang.wasm;

import org.babblelang.analysis.CompilationTarget;
import org.babblelang.ir.hir.HirClass;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirInterface;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;

import java.util.ArrayList;
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

    /** Per-module linked state: top-level globals, ModuleVal, init function, and function table. */
    public static final class ModuleLinkInfo {
        public final int index;
        public final ModuleInput input;
        public final Map<String, Integer> topLevelGlobals = new LinkedHashMap<>();
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
        this.ops.setCollections(colOps);
        this.rt.emitPolymorphicHelpers(
                arrOps.fnArrayDeepEquals, arrOps.fnArrayDeepHash, arrOps.fnArrayFormat,
                colOps.fnColEq, colOps.fnColHash, colOps.fnColFmt);
        this.objects = new WasmObjectSystem(this);
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
                capTypes.add(WasmValType.ref(ts.cellTypeFor(cv.primitiveKind())));
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
            case HirExpr.Logic l -> { collectOverloadMetas(l.left(), out); collectOverloadMetas(l.right(), out); }
            case HirExpr.If i -> { collectOverloadMetas(i.cond(), out); collectOverloadMetas(i.thenBranch(), out); collectOverloadMetas(i.elseBranch(), out); }
            case HirExpr.While w -> { collectOverloadMetas(w.cond(), out); collectOverloadMetas(w.body(), out); }
            case HirExpr.For f -> { collectOverloadMetas(f.iterable(), out); for (HirExpr s : f.body()) collectOverloadMetas(s, out); }
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
            default -> {}
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
