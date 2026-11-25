package org.babblelang.wasm;

import org.babblelang.analysis.CompilationTarget;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirExpr;
import org.babblelang.ir.hir.HirFunction;
import org.babblelang.ir.hir.HirModule;
import org.babblelang.ir.hir.HirParam;
import org.babblelang.ir.hir.HirVar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-program closed-world linker from {@link HirModule}s to a single WebAssembly 3.0
 * {@link WasmModule} ({@code BIP025-P1}, {@code BIP025-D3}).
 *
 * <p>Resolves module globals, function declarations, closure struct layouts, generic call
 * trampolines, module initialization guards, and entry-point exports.
 */
public final class WasmProgramLinker {
    public record ModuleInput(String key, String displayName, HirModule hir) {
    }

    /** Metadata for one compiled Babble function in the linked Wasm module. */
    public static final class FuncInfo {
        public final int moduleIndex;
        public final HirFunction hir;
        public final int directFuncIdx;
        public final WasmModule.FuncDef directFuncDef;
        public final int genericFuncIdx;
        public final WasmModule.FuncDef genericFuncDef;
        public final int closureStructType; // -1 if capture-free (uses $FnBase)
        public final int singletonGlobalIdx; // >= 0 if capture-free (zero-alloc FunctionRef)
        public final List<WasmValType> captureFieldTypes;
        public final boolean hasEnvParam;
        public final boolean hasOptMaskParam;

        FuncInfo(int moduleIndex, HirFunction hir, WasmModule.FuncDef directFuncDef,
                 WasmModule.FuncDef genericFuncDef, int closureStructType, int singletonGlobalIdx,
                 List<WasmValType> captureFieldTypes, boolean hasEnvParam, boolean hasOptMaskParam) {
            this.moduleIndex = moduleIndex;
            this.hir = hir;
            this.directFuncIdx = directFuncDef.funcIndex();
            this.directFuncDef = directFuncDef;
            this.genericFuncIdx = genericFuncDef.funcIndex();
            this.genericFuncDef = genericFuncDef;
            this.closureStructType = closureStructType;
            this.singletonGlobalIdx = singletonGlobalIdx;
            this.captureFieldTypes = List.copyOf(captureFieldTypes);
            this.hasEnvParam = hasEnvParam;
            this.hasOptMaskParam = hasOptMaskParam;
        }
    }

    /** Per-module linked state: top-level globals, init function, and function table. */
    public static final class ModuleLinkInfo {
        public final int index;
        public final ModuleInput input;
        public final Map<String, Integer> topLevelGlobals = new LinkedHashMap<>();
        public final Map<Integer, FuncInfo> functions = new LinkedHashMap<>();
        public int initStateGlobalIdx; // 0 = uninit, 1 = initing, 2 = done
        public int initFuncIdx;
        public WasmModule.FuncDef initFuncDef;

        ModuleLinkInfo(int index, ModuleInput input) {
            this.index = index;
            this.input = input;
        }
    }

    private final List<ModuleInput> inputs;
    private final CompilationTarget target;
    private final WasmModule mod;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmRuntimeOpsGen ops;
    private final List<ModuleLinkInfo> linkedModules = new ArrayList<>();
    private final Map<String, ModuleLinkInfo> modulesByKey = new LinkedHashMap<>();
    private final WasmModule.FuncDef fnInitSingletonsDef;

    private WasmProgramLinker(List<ModuleInput> inputs, CompilationTarget target) {
        this.inputs = List.copyOf(inputs);
        this.target = target;
        String modName = inputs.isEmpty() ? "babble" : sanitizeName(inputs.get(0).displayName());
        this.mod = new WasmModule(modName);
        this.ts = new WasmTypeSystem(mod, target);
        this.rt = new WasmRuntimeGen(mod, ts, target);
        this.ops = new WasmRuntimeOpsGen(mod, ts, rt, target);
        this.fnInitSingletonsDef = mod.addFunction("init_fn_singletons", ts.internFuncType(List.of(), List.of()));
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
        // Phase 1: Declare module globals, closure struct types, and Wasm function signatures for all modules
        for (int m = 0; m < inputs.size(); m++) {
            ModuleInput in = inputs.get(m);
            ModuleLinkInfo info = new ModuleLinkInfo(m, in);
            linkedModules.add(info);
            modulesByKey.put(in.key(), info);
            modulesByKey.put(in.hir().moduleId(), info);

            info.initStateGlobalIdx = mod.addGlobal("M" + m + "_state", WasmValType.I32, true,
                    List.of(new WasmInstr.I32Const(0)));

            // Declare globals for all top-level names (initialized to $G_UNDEFINED at module init)
            for (String name : in.hir().topLevelDeclaredNames()) {
                int g = mod.addGlobal("M" + m + "_g_" + sanitizeName(name), ts.anyRef(), true,
                        ts.defaultConstExpr(ts.anyRef()));
                info.topLevelGlobals.put(name, g);
            }
            // Also scan top-level statements for any Def whose name isn't in topLevelDeclaredNames
            for (HirExpr stmt : in.hir().topLevelStatements()) {
                if (stmt instanceof HirExpr.Def d && d.var() == null && !info.topLevelGlobals.containsKey(d.name())) {
                    int g = mod.addGlobal("M" + m + "_g_" + sanitizeName(d.name()), ts.anyRef(), true,
                            ts.defaultConstExpr(ts.anyRef()));
                    info.topLevelGlobals.put(d.name(), g);
                }
            }

            // Declare module init function: () -> (ref null $Any) (returns value of last top-level expression)
            int initSig = ts.internFuncType(List.of(), List.of(ts.anyRef()));
            info.initFuncDef = mod.addFunction("M" + m + "_init", initSig);
            info.initFuncIdx = info.initFuncDef.funcIndex();

            // Declare all HirFunctions in this module
            for (HirFunction fn : in.hir().functions().values()) {
                declareFunction(info, fn);
            }
        }

        // Phase 2: Emit all function bodies and generic trampolines
        WasmCallEmitter callEmitter = new WasmCallEmitter(this);
        WasmFuncEmitter funcEmitter = new WasmFuncEmitter(this, callEmitter);
        callEmitter.setFuncEmitter(funcEmitter);

        for (ModuleLinkInfo info : linkedModules) {
            for (FuncInfo fi : info.functions.values()) {
                funcEmitter.emitFunction(info, fi);
                callEmitter.emitGenericTrampoline(info, fi);
            }
            funcEmitter.emitModuleInit(info);
        }

        // Phase 3: Emit entry-point exports (_start, _babble_run, and bare-compatible result/error readers)
        emitEntryPoints();

        // Phase 4: Finalize passive UTF-16 string literal segment
        rt.finalizeStrings();
    }

    private void declareFunction(ModuleLinkInfo modInfo, HirFunction fn) {
        List<WasmValType> capTypes = new ArrayList<>();
        for (HirVar cv : fn.capturedVars()) {
            capTypes.add(WasmValType.ref(ts.cellTypeFor(cv.primitiveKind())));
        }
        boolean hasEnv = !capTypes.isEmpty();
        int closureType = -1;
        if (hasEnv) {
            closureType = ts.addClosureType("M" + modInfo.index + "_Clos_" + fn.id(), capTypes);
        }

        boolean hasOptMask = fn.hasOptionalMask();
        List<WasmValType> params = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();
        if (hasEnv) {
            params.add(WasmValType.ref(closureType));
            paramNames.add("$env");
        }
        for (HirParam p : fn.params()) {
            params.add(ts.valTypeFor(p.kind()));
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
        WasmModule.FuncDef genericDef = mod.addFunction(fnLabel + "$generic", ts.genericCallSigType,
                List.of("self", "args", "names"));
        mod.declareFuncRef(genericDef.funcIndex());

        int singletonGlobal = -1;
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

        FuncInfo fi = new FuncInfo(modInfo.index, fn, directDef, genericDef, closureType, singletonGlobal,
                capTypes, hasEnv, hasOptMask);
        modInfo.functions.put(fn.id(), fi);
    }

    private void emitEntryPoints() {
        int tVoid = ts.internFuncType(List.of(), List.of());
        int tI32 = ts.internFuncType(List.of(), List.of(WasmValType.I32));
        int tI32ToI32 = ts.internFuncType(List.of(WasmValType.I32), List.of(WasmValType.I32));

        // 1. Exported _start(): initializes strings/builtins/singletons and executes entry module (uncaught exn propagates!)
        WasmModule.FuncDef startFn = mod.addFunction("_start", tVoid);
        WasmCodeBuilder sc = startFn.code();
        sc.call(rt.fnInitStrings)
                .call(ops.fnInitBuiltins)
                .call(fnInitSingletonsDef.funcIndex());
        if (!linkedModules.isEmpty()) {
            sc.call(linkedModules.get(0).initFuncIdx).globalSet(rt.gLastResult);
        }
        mod.addExport("_start", WasmModule.ExportKind.FUNC, startFn.funcIndex());

        // 2. Exported _babble_run() -> i32: runs _start catching $babble_exn (returns 0 on ok, 1 on uncaught Babble exception).
        // Stores formatted error in G_LAST_ERROR so both wasm-js and zero-import wasm-bare runners can inspect it cleanly!
        WasmModule.FuncDef runFn = mod.addFunction("_babble_run", tI32);
        int exnVal = runFn.addLocal("exnVal", ts.anyRef(), 0);
        int exnLine = runFn.addLocal("exnLine", WasmValType.I32, 0);
        WasmCodeBuilder rc = runFn.code();
        rc.refNull(ts.stringType).globalSet(rt.gLastError);
        rc.block(WasmValType.I32) // $done -> i32
                .blockType(ts.exnCatchPairType) // $catch -> ((ref $Any), i32)
                .tryTable(List.of(WasmInstr.CatchClause.catchTag(rt.babbleExnTag, 0)))
                .call(startFn.funcIndex())
                .end()
                .i32Const(0)
                .br(1) // br $done with 0 (OK)
                .end() // $catch
                .localSet(exnLine)
                .localSet(exnVal)
                .localGet(exnVal).call(rt.fnFormat).globalSet(rt.gLastError)
                .i32Const(1)
                .end(); // $done
        mod.addExport("_babble_run", WasmModule.ExportKind.FUNC, runFn.funcIndex());

        // 3. Bare-compatible string readers for formatted result & last error (zero host imports!)
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

        // 4. On wasm-js, also export formatted result/error as externref JS strings for zero-loop interop
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
