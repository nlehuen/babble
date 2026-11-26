package org.babblelang.wasm;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.babblelang.analysis.CompilationTarget;
import org.babblelang.analysis.PrimitiveKind;

/**
 * Generates core WebAssembly 3.0 runtime globals, host imports, string literal pool, and
 * foundational pure-Wasm helper functions ({@code BIP025-I1}, {@code BIP025-D4}, {@code BIP025-D5},
 * {@code BIP025-D13}).
 */
public final class WasmRuntimeGen {
    final WasmModule mod;
    private final WasmNumericRuntimeGen numericGen;
    private final WasmValueRuntimeGen valueGen;
    private final WasmTypeSystem ts;
    final CompilationTarget target;

    // ---- Imports (only populated on WASM_JS; -1 on WASM_BARE) ----
    public final int impJsStrFromArr;
    public final int impJsStrIntoArr;
    public final int impJsStrLen;
    public final int impWriteStdout;
    public final int impWriteStderr;
    public final int impFormatF64;
    public final int impFormatF32;
    public final int impParseF64;
    public final int impParseF32;
    public final int impArgsCount;
    public final int impArgsGet;

    // ---- Tag & Core Globals ----
    public final int babbleExnTag;
    public final int gUndefined;
    public final int gMissing;
    public final int gTrue;
    public final int gFalse;
    public final int gEmptyStr;
    public final int gEmptyArgs;
    public final int gStdout;
    public final int gStderr;
    public final int gArgs;
    public final int gLastResult;
    public final int gLastError;

    // ---- Interned String Pool ----
    private record StringEntry(
            String value, int globalIdx, int byteOffset, int charLen, int javaHash) {}

    private final Map<String, StringEntry> stringPool = new LinkedHashMap<>();
    private final ByteArrayOutputStream stringDataBytes = new ByteArrayOutputStream();
    private final WasmModule.FuncDef fnInitStringsDef;
    public final int fnInitStrings;
    public final int fnInitArgs;

    // ---- Core Runtime Function Indices ----
    public final int fnFromExternStr; // -1 on wasm-bare
    public final int fnToExternStr; // -1 on wasm-bare
    public final int fnStrEq;
    public final int fnStrCmp;
    public final int fnStrConcat;
    public final int fnStrHash;
    public final int fnStrStrip;
    public final int fnIntToStr;
    public final int fnLongToStr;
    public final int fnDoubleToStr;
    public final int fnFloatToStr;
    public final int fnCharToStr;
    public final int fnParseLong;

    public final int fnBoxByte;
    public final int fnBoxShort;
    public final int fnBoxInt;
    public final int fnBoxLong;
    public final int fnBoxFloat;
    public final int fnBoxDouble;
    public final int fnBoxBool;
    public final int fnBoxChar;

    public final int fnUnboxByte;
    public final int fnUnboxShort;
    public final int fnUnboxInt;
    public final int fnUnboxLong;
    public final int fnUnboxFloat;
    public final int fnUnboxDouble;
    public final int fnUnboxBool;
    public final int fnUnboxChar;

    public final int fnF64Compare;
    public final int fnF32Compare;
    public final int fnTruth;
    public final int fnNumRank;
    public final int fnNumAsLong;
    public final int fnNumAsDouble;
    public final int fnWidenNumeric;
    public final int fnEquals;
    public final int fnSame;
    public final int fnHashCode;
    public final int fnCompare;
    public final int fnFormat;

    public final int fnMakeExn;
    public final int fnThrowMsg;
    public final int fnAddSuppressed;
    public final int fnAssert;
    public final int fnPrint;

    private final WasmModule.FuncDef dEqualsDef;
    private final WasmModule.FuncDef dHashCodeDef;
    private final WasmModule.FuncDef dFormatDef;

    // Common interned strings
    public final int sNull;
    public final int sTrue;
    public final int sFalse;
    public final int sNaN;
    public final int sInfinity;
    public final int sNegInfinity;
    public final int sNegZero;
    public final int sNewline;

    public WasmRuntimeGen(WasmModule mod, WasmTypeSystem ts, CompilationTarget target) {
        this.mod = mod;
        this.ts = ts;
        this.target = target;
        this.numericGen = new WasmNumericRuntimeGen(this, ts);
        this.valueGen = new WasmValueRuntimeGen(this, ts);

        // 1. Declare host imports FIRST (Wasm requires all imports before defined functions)
        if (target == CompilationTarget.WASM_JS) {
            int tFromArr =
                    ts.internFuncType(
                            "JsStrFromArr",
                            List.of(
                                    WasmValType.refNull(ts.charArrType),
                                    WasmValType.I32,
                                    WasmValType.I32),
                            List.of(WasmValType.ref(WasmValType.HeapType.Abstract.EXTERN)));
            int tIntoArr =
                    ts.internFuncType(
                            "JsStrIntoArr",
                            List.of(
                                    WasmValType.EXTERNREF,
                                    WasmValType.refNull(ts.charArrType),
                                    WasmValType.I32),
                            List.of(WasmValType.I32));
            int tLen =
                    ts.internFuncType(
                            "JsStrLen", List.of(WasmValType.EXTERNREF), List.of(WasmValType.I32));
            int tWrite = ts.internFuncType("EnvWrite", List.of(WasmValType.EXTERNREF), List.of());
            int tFmtF64 =
                    ts.internFuncType(
                            "EnvFmtF64", List.of(WasmValType.F64), List.of(WasmValType.EXTERNREF));
            int tFmtF32 =
                    ts.internFuncType(
                            "EnvFmtF32", List.of(WasmValType.F32), List.of(WasmValType.EXTERNREF));
            int tParseF64 =
                    ts.internFuncType(
                            "EnvParseF64",
                            List.of(WasmValType.EXTERNREF),
                            List.of(WasmValType.F64, WasmValType.I32));
            int tParseF32 =
                    ts.internFuncType(
                            "EnvParseF32",
                            List.of(WasmValType.EXTERNREF),
                            List.of(WasmValType.F32, WasmValType.I32));
            int tArgsCount = ts.internFuncType("EnvArgsCount", List.of(), List.of(WasmValType.I32));
            int tArgsGet =
                    ts.internFuncType(
                            "EnvArgAt", List.of(WasmValType.I32), List.of(WasmValType.EXTERNREF));

            this.impJsStrFromArr =
                    mod.addFuncImport(
                            "wasm:js-string", "fromCharCodeArray", tFromArr, "js_str_from_arr");
            this.impJsStrIntoArr =
                    mod.addFuncImport(
                            "wasm:js-string", "intoCharCodeArray", tIntoArr, "js_str_into_arr");
            this.impJsStrLen = mod.addFuncImport("wasm:js-string", "length", tLen, "js_str_len");
            this.impWriteStdout =
                    mod.addFuncImport("babble:env", "write_stdout", tWrite, "env_write_stdout");
            this.impWriteStderr =
                    mod.addFuncImport("babble:env", "write_stderr", tWrite, "env_write_stderr");
            this.impFormatF64 =
                    mod.addFuncImport("babble:env", "format_f64", tFmtF64, "env_format_f64");
            this.impFormatF32 =
                    mod.addFuncImport("babble:env", "format_f32", tFmtF32, "env_format_f32");
            this.impParseF64 =
                    mod.addFuncImport("babble:env", "parse_f64", tParseF64, "env_parse_f64");
            this.impParseF32 =
                    mod.addFuncImport("babble:env", "parse_f32", tParseF32, "env_parse_f32");
            this.impArgsCount =
                    mod.addFuncImport("babble:env", "args_count", tArgsCount, "env_args_count");
            this.impArgsGet = mod.addFuncImport("babble:env", "arg_at", tArgsGet, "env_arg_at");
        } else {
            this.impJsStrFromArr = -1;
            this.impJsStrIntoArr = -1;
            this.impJsStrLen = -1;
            this.impWriteStdout = -1;
            this.impWriteStderr = -1;
            this.impFormatF64 = -1;
            this.impFormatF32 = -1;
            this.impParseF64 = -1;
            this.impParseF32 = -1;
            this.impArgsCount = -1;
            this.impArgsGet = -1;
        }

        // 2. Tag & core globals
        this.babbleExnTag = mod.addTag("babble_exn", ts.exnTagType);
        this.gUndefined =
                mod.addGlobal(
                        "G_UNDEFINED",
                        WasmValType.ref(ts.sentinelType),
                        false,
                        List.of(
                                new WasmInstr.I32Const(0),
                                new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.sentinelType)));
        this.gMissing =
                mod.addGlobal(
                        "G_MISSING",
                        WasmValType.ref(ts.sentinelType),
                        false,
                        List.of(
                                new WasmInstr.I32Const(1),
                                new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.sentinelType)));
        this.gTrue =
                mod.addGlobal(
                        "G_TRUE",
                        WasmValType.ref(ts.boxBoolType),
                        false,
                        List.of(
                                new WasmInstr.I32Const(1),
                                new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.boxBoolType)));
        this.gFalse =
                mod.addGlobal(
                        "G_FALSE",
                        WasmValType.ref(ts.boxBoolType),
                        false,
                        List.of(
                                new WasmInstr.I32Const(0),
                                new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.boxBoolType)));
        this.gEmptyStr =
                mod.addGlobal(
                        "G_EMPTY_STR",
                        WasmValType.ref(ts.stringType),
                        false,
                        List.of(
                                new WasmInstr.Idx2(
                                        WasmInstr.Idx2Op.ARRAY_NEW_FIXED, ts.charArrType, 0),
                                new WasmInstr.I32Const(0),
                                new WasmInstr.I32Const(1),
                                new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.stringType)));
        this.gEmptyArgs =
                mod.addGlobal(
                        "G_EMPTY_ARGS",
                        WasmValType.ref(ts.anyArrType),
                        false,
                        List.of(
                                new WasmInstr.Idx2(
                                        WasmInstr.Idx2Op.ARRAY_NEW_FIXED, ts.anyArrType, 0)));
        this.gStdout =
                mod.addGlobal(
                        "G_STDOUT",
                        WasmValType.ref(ts.hostStreamType),
                        false,
                        List.of(
                                new WasmInstr.I32Const(0),
                                new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.hostStreamType)));
        this.gStderr =
                mod.addGlobal(
                        "G_STDERR",
                        WasmValType.ref(ts.hostStreamType),
                        false,
                        List.of(
                                new WasmInstr.I32Const(1),
                                new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.hostStreamType)));
        this.gArgs =
                mod.addGlobal(
                        "G_ARGS",
                        WasmValType.refNull(ts.listType),
                        true,
                        ts.defaultConstExpr(WasmValType.refNull(ts.listType)));
        this.gLastResult =
                mod.addGlobal("G_LAST_RESULT", ts.anyRef(), true, ts.defaultConstExpr(ts.anyRef()));
        this.gLastError =
                mod.addGlobal(
                        "G_LAST_ERROR",
                        WasmValType.refNull(ts.stringType),
                        true,
                        ts.defaultConstExpr(WasmValType.refNull(ts.stringType)));

        // Common interned strings
        this.sNull = internString("null");
        this.sTrue = internString("true");
        this.sFalse = internString("false");
        this.sNaN = internString("NaN");
        this.sInfinity = internString("Infinity");
        this.sNegInfinity = internString("-Infinity");
        this.sNegZero = internString("-0.0");
        this.sNewline = internString("\n");

        // 3. Reserve $init_strings (body emitted at finalizeStrings())
        int tVoid = ts.internFuncType("Void", List.of(), List.of());
        this.fnInitStringsDef = mod.addFunction("init_strings", tVoid);
        this.fnInitStrings = fnInitStringsDef.funcIndex();

        // 4. Pre-declare all runtime functions so mutual recursion works cleanly
        WasmValType refAnyNull = ts.anyRef();
        WasmValType refAny = ts.nonNullAnyRef();
        WasmValType refStr = WasmValType.ref(ts.stringType);
        WasmValType refStrNull = WasmValType.refNull(ts.stringType);

        if (target == CompilationTarget.WASM_JS) {
            WasmModule.FuncDef fromExt =
                    mod.addFunction(
                            "from_extern_str",
                            ts.internFuncType(List.of(WasmValType.EXTERNREF), List.of(refStr)),
                            List.of("ext"));
            WasmModule.FuncDef toExt =
                    mod.addFunction(
                            "to_extern_str",
                            ts.internFuncType(
                                    List.of(refStr),
                                    List.of(WasmValType.ref(WasmValType.HeapType.Abstract.EXTERN))),
                            List.of("s"));
            this.fnFromExternStr = fromExt.funcIndex();
            this.fnToExternStr = toExt.funcIndex();
            emitFromExternStr(fromExt);
            emitToExternStr(toExt);
        } else {
            this.fnFromExternStr = -1;
            this.fnToExternStr = -1;
        }

        WasmModule.FuncDef initArgsDef = mod.addFunction("init_args", tVoid);
        this.fnInitArgs = initArgsDef.funcIndex();
        emitInitArgs(initArgsDef);

        WasmModule.FuncDef dStrEq =
                mod.addFunction(
                        "str_eq",
                        ts.internFuncType(List.of(refStr, refStr), List.of(WasmValType.I32)),
                        List.of("a", "b"));
        WasmModule.FuncDef dStrCmp =
                mod.addFunction(
                        "str_cmp",
                        ts.internFuncType(List.of(refStr, refStr), List.of(WasmValType.I32)),
                        List.of("a", "b"));
        WasmModule.FuncDef dStrConcat =
                mod.addFunction(
                        "str_concat",
                        ts.internFuncType(List.of(refStr, refStr), List.of(refStr)),
                        List.of("a", "b"));
        WasmModule.FuncDef dStrHash =
                mod.addFunction(
                        "str_hash",
                        ts.internFuncType(List.of(refStr), List.of(WasmValType.I32)),
                        List.of("s"));
        WasmModule.FuncDef dStrStrip =
                mod.addFunction(
                        "str_strip",
                        ts.internFuncType(List.of(refStr), List.of(refStr)),
                        List.of("s"));
        WasmModule.FuncDef dIntToStr =
                mod.addFunction(
                        "int_to_str",
                        ts.internFuncType(List.of(WasmValType.I32), List.of(refStr)),
                        List.of("v"));
        WasmModule.FuncDef dLongToStr =
                mod.addFunction(
                        "long_to_str",
                        ts.internFuncType(List.of(WasmValType.I64), List.of(refStr)),
                        List.of("v"));
        WasmModule.FuncDef dDoubleToStr =
                mod.addFunction(
                        "double_to_str",
                        ts.internFuncType(List.of(WasmValType.F64), List.of(refStr)),
                        List.of("v"));
        WasmModule.FuncDef dFloatToStr =
                mod.addFunction(
                        "float_to_str",
                        ts.internFuncType(List.of(WasmValType.F32), List.of(refStr)),
                        List.of("v"));
        WasmModule.FuncDef dCharToStr =
                mod.addFunction(
                        "char_to_str",
                        ts.internFuncType(List.of(WasmValType.I32), List.of(refStr)),
                        List.of("c"));
        WasmModule.FuncDef dParseLong =
                mod.addFunction(
                        "parse_long",
                        ts.internFuncType(
                                List.of(refStr), List.of(WasmValType.I64, WasmValType.I32)),
                        List.of("s"));

        WasmModule.FuncDef dBoxByte =
                mod.addFunction(
                        "box_byte",
                        ts.internFuncType(List.of(WasmValType.I32), List.of(refAny)),
                        List.of("v"));
        WasmModule.FuncDef dBoxShort =
                mod.addFunction(
                        "box_short",
                        ts.internFuncType(List.of(WasmValType.I32), List.of(refAny)),
                        List.of("v"));
        WasmModule.FuncDef dBoxInt =
                mod.addFunction(
                        "box_int",
                        ts.internFuncType(List.of(WasmValType.I32), List.of(refAny)),
                        List.of("v"));
        WasmModule.FuncDef dBoxLong =
                mod.addFunction(
                        "box_long",
                        ts.internFuncType(List.of(WasmValType.I64), List.of(refAny)),
                        List.of("v"));
        WasmModule.FuncDef dBoxFloat =
                mod.addFunction(
                        "box_float",
                        ts.internFuncType(List.of(WasmValType.F32), List.of(refAny)),
                        List.of("v"));
        WasmModule.FuncDef dBoxDouble =
                mod.addFunction(
                        "box_double",
                        ts.internFuncType(List.of(WasmValType.F64), List.of(refAny)),
                        List.of("v"));
        WasmModule.FuncDef dBoxBool =
                mod.addFunction(
                        "box_bool",
                        ts.internFuncType(List.of(WasmValType.I32), List.of(refAny)),
                        List.of("v"));
        WasmModule.FuncDef dBoxChar =
                mod.addFunction(
                        "box_char",
                        ts.internFuncType(List.of(WasmValType.I32), List.of(refAny)),
                        List.of("v"));

        WasmModule.FuncDef dUnboxByte =
                mod.addFunction(
                        "unbox_byte",
                        ts.internFuncType(
                                List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I32)),
                        List.of("v", "line"));
        WasmModule.FuncDef dUnboxShort =
                mod.addFunction(
                        "unbox_short",
                        ts.internFuncType(
                                List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I32)),
                        List.of("v", "line"));
        WasmModule.FuncDef dUnboxInt =
                mod.addFunction(
                        "unbox_int",
                        ts.internFuncType(
                                List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I32)),
                        List.of("v", "line"));
        WasmModule.FuncDef dUnboxLong =
                mod.addFunction(
                        "unbox_long",
                        ts.internFuncType(
                                List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I64)),
                        List.of("v", "line"));
        WasmModule.FuncDef dUnboxFloat =
                mod.addFunction(
                        "unbox_float",
                        ts.internFuncType(
                                List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.F32)),
                        List.of("v", "line"));
        WasmModule.FuncDef dUnboxDouble =
                mod.addFunction(
                        "unbox_double",
                        ts.internFuncType(
                                List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.F64)),
                        List.of("v", "line"));
        WasmModule.FuncDef dUnboxBool =
                mod.addFunction(
                        "unbox_bool",
                        ts.internFuncType(
                                List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I32)),
                        List.of("v", "line"));
        WasmModule.FuncDef dUnboxChar =
                mod.addFunction(
                        "unbox_char",
                        ts.internFuncType(
                                List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I32)),
                        List.of("v", "line"));

        WasmModule.FuncDef dF64Cmp =
                mod.addFunction(
                        "f64_compare",
                        ts.internFuncType(
                                List.of(WasmValType.F64, WasmValType.F64),
                                List.of(WasmValType.I32)),
                        List.of("a", "b"));
        WasmModule.FuncDef dF32Cmp =
                mod.addFunction(
                        "f32_compare",
                        ts.internFuncType(
                                List.of(WasmValType.F32, WasmValType.F32),
                                List.of(WasmValType.I32)),
                        List.of("a", "b"));
        WasmModule.FuncDef dTruth =
                mod.addFunction(
                        "truth",
                        ts.internFuncType(List.of(refAnyNull), List.of(WasmValType.I32)),
                        List.of("v"));
        WasmModule.FuncDef dNumRank =
                mod.addFunction(
                        "num_rank",
                        ts.internFuncType(List.of(refAnyNull), List.of(WasmValType.I32)),
                        List.of("v"));
        WasmModule.FuncDef dNumAsLong =
                mod.addFunction(
                        "num_as_long",
                        ts.internFuncType(List.of(refAny), List.of(WasmValType.I64)),
                        List.of("v"));
        WasmModule.FuncDef dNumAsDouble =
                mod.addFunction(
                        "num_as_double",
                        ts.internFuncType(List.of(refAny), List.of(WasmValType.F64)),
                        List.of("v"));
        WasmModule.FuncDef dWidenNum =
                mod.addFunction(
                        "widen_numeric",
                        ts.internFuncType(
                                List.of(refAnyNull, WasmValType.I32), List.of(refAnyNull)),
                        List.of("v", "rank"));
        WasmModule.FuncDef dEquals =
                mod.addFunction(
                        "equals",
                        ts.internFuncType(
                                List.of(refAnyNull, refAnyNull), List.of(WasmValType.I32)),
                        List.of("a", "b"));
        WasmModule.FuncDef dSame =
                mod.addFunction(
                        "same",
                        ts.internFuncType(
                                List.of(refAnyNull, refAnyNull), List.of(WasmValType.I32)),
                        List.of("a", "b"));
        WasmModule.FuncDef dHashCode =
                mod.addFunction(
                        "hash_code",
                        ts.internFuncType(List.of(refAnyNull), List.of(WasmValType.I32)),
                        List.of("v"));
        WasmModule.FuncDef dCompare =
                mod.addFunction(
                        "compare",
                        ts.internFuncType(
                                List.of(refAnyNull, refAnyNull, refStr, refStr, WasmValType.I32),
                                List.of(WasmValType.I32)),
                        List.of("a", "b", "lt", "rt", "line"));
        WasmModule.FuncDef dFormat =
                mod.addFunction(
                        "format",
                        ts.internFuncType(List.of(refAnyNull), List.of(refStr)),
                        List.of("v"));

        WasmModule.FuncDef dMakeExn =
                mod.addFunction(
                        "make_exn",
                        ts.internFuncType(
                                List.of(refStrNull, refAnyNull, WasmValType.I32),
                                List.of(WasmValType.ref(ts.babbleExnType))),
                        List.of("msg", "cause", "line"));
        WasmModule.FuncDef dThrowMsg =
                mod.addFunction(
                        "throw_msg",
                        ts.internFuncType(List.of(refStr, WasmValType.I32), List.of()),
                        List.of("msg", "line"));
        WasmModule.FuncDef dAddSupp =
                mod.addFunction(
                        "add_suppressed",
                        ts.internFuncType(List.of(refAny, refAny), List.of()),
                        List.of("primary", "secondary"));
        WasmModule.FuncDef dAssert =
                mod.addFunction(
                        "assert_fn",
                        ts.internFuncType(
                                List.of(refAnyNull, refAnyNull, refStr, WasmValType.I32),
                                List.of(WasmValType.I32)),
                        List.of("cond", "msg", "expr", "line"));
        WasmModule.FuncDef dPrint =
                mod.addFunction(
                        "print_values",
                        ts.internFuncType(
                                List.of(
                                        WasmValType.ref(ts.anyArrType),
                                        refAnyNull,
                                        WasmValType.I32,
                                        WasmValType.I32),
                                List.of()),
                        List.of("vals", "to", "nl", "line"));

        this.fnStrEq = dStrEq.funcIndex();
        this.fnStrCmp = dStrCmp.funcIndex();
        this.fnStrConcat = dStrConcat.funcIndex();
        this.fnStrHash = dStrHash.funcIndex();
        this.fnStrStrip = dStrStrip.funcIndex();
        this.fnIntToStr = dIntToStr.funcIndex();
        this.fnLongToStr = dLongToStr.funcIndex();
        this.fnDoubleToStr = dDoubleToStr.funcIndex();
        this.fnFloatToStr = dFloatToStr.funcIndex();
        this.fnCharToStr = dCharToStr.funcIndex();
        this.fnParseLong = dParseLong.funcIndex();

        this.fnBoxByte = dBoxByte.funcIndex();
        this.fnBoxShort = dBoxShort.funcIndex();
        this.fnBoxInt = dBoxInt.funcIndex();
        this.fnBoxLong = dBoxLong.funcIndex();
        this.fnBoxFloat = dBoxFloat.funcIndex();
        this.fnBoxDouble = dBoxDouble.funcIndex();
        this.fnBoxBool = dBoxBool.funcIndex();
        this.fnBoxChar = dBoxChar.funcIndex();

        this.fnUnboxByte = dUnboxByte.funcIndex();
        this.fnUnboxShort = dUnboxShort.funcIndex();
        this.fnUnboxInt = dUnboxInt.funcIndex();
        this.fnUnboxLong = dUnboxLong.funcIndex();
        this.fnUnboxFloat = dUnboxFloat.funcIndex();
        this.fnUnboxDouble = dUnboxDouble.funcIndex();
        this.fnUnboxBool = dUnboxBool.funcIndex();
        this.fnUnboxChar = dUnboxChar.funcIndex();

        this.fnF64Compare = dF64Cmp.funcIndex();
        this.fnF32Compare = dF32Cmp.funcIndex();
        this.fnTruth = dTruth.funcIndex();
        this.fnNumRank = dNumRank.funcIndex();
        this.fnNumAsLong = dNumAsLong.funcIndex();
        this.fnNumAsDouble = dNumAsDouble.funcIndex();
        this.fnWidenNumeric = dWidenNum.funcIndex();
        this.fnEquals = dEquals.funcIndex();
        this.fnSame = dSame.funcIndex();
        this.fnHashCode = dHashCode.funcIndex();
        this.fnCompare = dCompare.funcIndex();
        this.fnFormat = dFormat.funcIndex();

        this.fnMakeExn = dMakeExn.funcIndex();
        this.fnThrowMsg = dThrowMsg.funcIndex();
        this.fnAddSuppressed = dAddSupp.funcIndex();
        this.fnAssert = dAssert.funcIndex();
        this.fnPrint = dPrint.funcIndex();

        // 5. Emit bodies
        emitStrEq(dStrEq);
        emitStrCmp(dStrCmp);
        emitStrConcat(dStrConcat);
        emitStrHash(dStrHash);
        emitStrStrip(dStrStrip);
        numericGen.emitIntToStr(dIntToStr);
        numericGen.emitLongToStr(dLongToStr);
        numericGen.emitDoubleToStr(dDoubleToStr);
        numericGen.emitFloatToStr(dFloatToStr);
        numericGen.emitCharToStr(dCharToStr);
        numericGen.emitParseLong(dParseLong);

        numericGen.emitSimpleBox(dBoxByte, ts.boxByteType, WasmInstr.SimpleOp.I32_EXTEND8_S);
        numericGen.emitSimpleBox(dBoxShort, ts.boxShortType, WasmInstr.SimpleOp.I32_EXTEND16_S);
        numericGen.emitSimpleBox(dBoxInt, ts.boxIntType, null);
        numericGen.emitSimpleBox(dBoxLong, ts.boxLongType, null);
        numericGen.emitSimpleBox(dBoxFloat, ts.boxFloatType, null);
        numericGen.emitSimpleBox(dBoxDouble, ts.boxDoubleType, null);
        numericGen.emitBoxBool(dBoxBool);
        numericGen.emitBoxChar(dBoxChar);

        numericGen.emitUnboxIntegral(dUnboxByte, ts.boxByteType, PrimitiveKind.BYTE);
        numericGen.emitUnboxIntegral(dUnboxShort, ts.boxShortType, PrimitiveKind.SHORT);
        numericGen.emitUnboxIntegral(dUnboxInt, ts.boxIntType, PrimitiveKind.INT);
        numericGen.emitUnboxLong(dUnboxLong);
        numericGen.emitUnboxFloat(dUnboxFloat);
        numericGen.emitUnboxDouble(dUnboxDouble);
        numericGen.emitUnboxBool(dUnboxBool);
        numericGen.emitUnboxChar(dUnboxChar);

        this.dEqualsDef = dEquals;
        this.dHashCodeDef = dHashCode;
        this.dFormatDef = dFormat;

        numericGen.emitF64Compare(dF64Cmp);
        numericGen.emitF32Compare(dF32Cmp);
        numericGen.emitTruth(dTruth);
        numericGen.emitNumRank(dNumRank);
        numericGen.emitNumAsLong(dNumAsLong);
        numericGen.emitNumAsDouble(dNumAsDouble);
        numericGen.emitWidenNumeric(dWidenNum);
        valueGen.emitSame(dSame);
        valueGen.emitCompare(dCompare);

        valueGen.emitMakeExn(dMakeExn);
        valueGen.emitThrowMsg(dThrowMsg);
        valueGen.emitAddSuppressed(dAddSupp);
        valueGen.emitAssert(dAssert);
        valueGen.emitPrint(dPrint);
    }

    /**
     * Emits {@code equals}, {@code hashCode}, and {@code format} with {@code $ArrayBase},
     * collection, and range hooks ({@code BIP025-I2}, {@code BIP025-I3}, {@code BIP031}).
     */
    public void emitPolymorphicHelpers(
            int fnArrEq,
            int fnArrHash,
            int fnArrFmt,
            int fnColEq,
            int fnColHash,
            int fnColFmt,
            int fnRangeEq,
            int fnRangeHash,
            int fnRangeFmt) {
        valueGen.emitEquals(dEqualsDef, fnArrEq, fnColEq, fnRangeEq);
        valueGen.emitHashCode(dHashCodeDef, fnArrHash, fnColHash, fnRangeHash);
        valueGen.emitFormat(dFormatDef, fnArrFmt, fnColFmt, fnRangeFmt);
    }

    private void emitInitArgs(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        if (target != CompilationTarget.WASM_JS) {
            c.i32Const(0)
                    .arrayNewDefault(ts.anyArrType)
                    .i32Const(0)
                    .refNull(ts.listType)
                    .i32Const(0)
                    .structNew(ts.listType)
                    .globalSet(gArgs);
            return;
        }
        int n = fn.addLocal("n", WasmValType.I32, 0);
        int i = fn.addLocal("i", WasmValType.I32, 0);
        int cap = fn.addLocal("cap", WasmValType.I32, 0);
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.anyArrType), 0);
        c.call(impArgsCount)
                .localTee(n)
                .i32Const(4)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_(WasmValType.I32)
                .localGet(n)
                .else_()
                .i32Const(4)
                .end()
                .localTee(cap)
                .arrayNewDefault(ts.anyArrType)
                .localSet(arr)
                .i32Const(0)
                .localSet(i)
                .block()
                .loop()
                .localGet(i)
                .localGet(n)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .brIf(1)
                .localGet(arr)
                .refAsNonNull()
                .localGet(i)
                .localGet(i)
                .call(impArgsGet)
                .call(fnFromExternStr)
                .arraySet(ts.anyArrType)
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(0)
                .end()
                .end()
                .localGet(arr)
                .refAsNonNull()
                .localGet(n)
                .refNull(ts.listType)
                .i32Const(0)
                .structNew(ts.listType)
                .globalSet(gArgs);
    }

    // ---------------------------------------------------------------- string literal pool

    /** Interns a compile-time string literal and returns its global index. */
    public int internString(String value) {
        if (value == null || value.isEmpty()) {
            return gEmptyStr;
        }
        StringEntry existing = stringPool.get(value);
        if (existing != null) {
            return existing.globalIdx();
        }
        int offset = stringDataBytes.size();
        int len = value.length();
        for (int i = 0; i < len; i++) {
            char ch = value.charAt(i);
            stringDataBytes.write(ch & 0xFF);
            stringDataBytes.write((ch >>> 8) & 0xFF);
        }
        int globalIdx =
                mod.addGlobal(
                        "S_" + stringPool.size(),
                        WasmValType.refNull(ts.stringType),
                        true,
                        ts.defaultConstExpr(WasmValType.refNull(ts.stringType)));
        StringEntry entry = new StringEntry(value, globalIdx, offset, len, value.hashCode());
        stringPool.put(value, entry);
        return globalIdx;
    }

    /** Emits code to push a non-null {@code (ref $String)} for an interned string literal. */
    public void emitStringLit(WasmCodeBuilder code, String value) {
        int g = internString(value);
        emitStringGlobal(code, g);
    }

    /** Emits code to push a non-null {@code (ref $String)} from an interned string global index. */
    public void emitStringGlobal(WasmCodeBuilder code, int globalIdx) {
        code.globalGet(globalIdx);
        if (globalIdx != gEmptyStr) {
            code.refAsNonNull();
        }
    }

    /** Must be called once after all modules & runtime functions have interned their strings. */
    public void finalizeStrings() {
        WasmCodeBuilder c = fnInitStringsDef.code();
        if (!stringPool.isEmpty()) {
            int dataIdx = mod.addPassiveData(stringDataBytes.toByteArray());
            for (StringEntry e : stringPool.values()) {
                c.i32Const(e.byteOffset())
                        .i32Const(e.charLen())
                        .arrayNewData(ts.charArrType, dataIdx)
                        .i32Const(e.javaHash())
                        .i32Const(1)
                        .structNew(ts.stringType)
                        .globalSet(e.globalIdx());
            }
        }
    }

    public int boxFuncFor(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> fnBoxByte;
            case SHORT -> fnBoxShort;
            case INT -> fnBoxInt;
            case LONG -> fnBoxLong;
            case FLOAT -> fnBoxFloat;
            case DOUBLE -> fnBoxDouble;
            case BOOL -> fnBoxBool;
            case CHAR -> fnBoxChar;
            case NONE -> throw new IllegalArgumentException("No box function for NONE");
        };
    }

    public int unboxFuncFor(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> fnUnboxByte;
            case SHORT -> fnUnboxShort;
            case INT -> fnUnboxInt;
            case LONG -> fnUnboxLong;
            case FLOAT -> fnUnboxFloat;
            case DOUBLE -> fnUnboxDouble;
            case BOOL -> fnUnboxBool;
            case CHAR -> fnUnboxChar;
            case NONE -> throw new IllegalArgumentException("No unbox function for NONE");
        };
    }

    // ---------------------------------------------------------------- js-string bridges

    private void emitFromExternStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int len = fn.addLocal("len", WasmValType.I32, 1);
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 1);
        c.localGet(0)
                .call(impJsStrLen)
                .localTee(len)
                .arrayNewDefault(ts.charArrType)
                .localTee(arr)
                .drop();
        c.localGet(0).localGet(arr).i32Const(0).call(impJsStrIntoArr).drop();
        c.localGet(arr).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    private void emitToExternStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 1);
        c.localGet(0)
                .structGet(ts.stringType, 0)
                .localTee(arr)
                .i32Const(0)
                .localGet(arr)
                .arrayLen()
                .call(impJsStrFromArr);
    }

    // ---------------------------------------------------------------- string operations

    private void emitStrEq(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ca = fn.addLocal("ca", WasmValType.refNull(ts.charArrType), 2);
        int cb = fn.addLocal("cb", WasmValType.refNull(ts.charArrType), 2);
        int len = fn.addLocal("len", WasmValType.I32, 2);
        int i = fn.addLocal("i", WasmValType.I32, 2);
        // Fast identity check
        c.localGet(0).localGet(1).refEq().if_().i32Const(1).return_().end();
        c.localGet(0).structGet(ts.stringType, 0).localSet(ca);
        c.localGet(1).structGet(ts.stringType, 0).localSet(cb);
        c.localGet(ca)
                .arrayLen()
                .localTee(len)
                .localGet(cb)
                .arrayLen()
                .op(WasmInstr.SimpleOp.I32_NE)
                .if_()
                .i32Const(0)
                .return_()
                .end();
        c.loop()
                .localGet(i)
                .localGet(len)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(ca)
                .localGet(i)
                .arrayGetU(ts.charArrType)
                .localGet(cb)
                .localGet(i)
                .arrayGetU(ts.charArrType)
                .op(WasmInstr.SimpleOp.I32_NE)
                .if_()
                .i32Const(0)
                .return_()
                .end()
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end();
        c.i32Const(1);
    }

    private void emitStrCmp(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ca = fn.addLocal("ca", WasmValType.refNull(ts.charArrType), 2);
        int cb = fn.addLocal("cb", WasmValType.refNull(ts.charArrType), 2);
        int la = fn.addLocal("la", WasmValType.I32, 2);
        int lb = fn.addLocal("lb", WasmValType.I32, 2);
        int lim = fn.addLocal("lim", WasmValType.I32, 2);
        int i = fn.addLocal("i", WasmValType.I32, 2);
        int diff = fn.addLocal("diff", WasmValType.I32, 2);

        c.localGet(0).structGet(ts.stringType, 0).localTee(ca).arrayLen().localSet(la);
        c.localGet(1).structGet(ts.stringType, 0).localTee(cb).arrayLen().localSet(lb);
        c.localGet(la)
                .localGet(lb)
                .localGet(la)
                .localGet(lb)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .select()
                .localSet(lim);
        c.loop()
                .localGet(i)
                .localGet(lim)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(ca)
                .localGet(i)
                .arrayGetU(ts.charArrType)
                .localGet(cb)
                .localGet(i)
                .arrayGetU(ts.charArrType)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .localTee(diff)
                .if_()
                .localGet(diff)
                .return_()
                .end()
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end();
        c.localGet(la).localGet(lb).op(WasmInstr.SimpleOp.I32_SUB);
    }

    private void emitStrConcat(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ca = fn.addLocal("ca", WasmValType.refNull(ts.charArrType), 2);
        int cb = fn.addLocal("cb", WasmValType.refNull(ts.charArrType), 2);
        int la = fn.addLocal("la", WasmValType.I32, 2);
        int lb = fn.addLocal("lb", WasmValType.I32, 2);
        int dst = fn.addLocal("dst", WasmValType.refNull(ts.charArrType), 2);

        c.localGet(0)
                .structGet(ts.stringType, 0)
                .localTee(ca)
                .arrayLen()
                .localTee(la)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .localGet(1)
                .return_()
                .end();
        c.localGet(1)
                .structGet(ts.stringType, 0)
                .localTee(cb)
                .arrayLen()
                .localTee(lb)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .localGet(0)
                .return_()
                .end();
        c.localGet(la)
                .localGet(lb)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .arrayNewDefault(ts.charArrType)
                .localSet(dst);
        c.localGet(dst)
                .i32Const(0)
                .localGet(ca)
                .i32Const(0)
                .localGet(la)
                .arrayCopy(ts.charArrType, ts.charArrType);
        c.localGet(dst)
                .localGet(la)
                .localGet(cb)
                .i32Const(0)
                .localGet(lb)
                .arrayCopy(ts.charArrType, ts.charArrType);
        c.localGet(dst).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    private void emitStrHash(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ca = fn.addLocal("ca", WasmValType.refNull(ts.charArrType), 1);
        int len = fn.addLocal("len", WasmValType.I32, 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        int h = fn.addLocal("h", WasmValType.I32, 1);

        c.localGet(0)
                .structGet(ts.stringType, 2)
                .if_()
                .localGet(0)
                .structGet(ts.stringType, 1)
                .return_()
                .end();
        c.localGet(0).structGet(ts.stringType, 0).localTee(ca).arrayLen().localSet(len);
        c.loop()
                .localGet(i)
                .localGet(len)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(h)
                .i32Const(31)
                .op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(ca)
                .localGet(i)
                .arrayGetU(ts.charArrType)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(h)
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end();
        c.localGet(0).localGet(h).structSet(ts.stringType, 1);
        c.localGet(0).i32Const(1).structSet(ts.stringType, 2);
        c.localGet(h);
    }

    private void emitStrStrip(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ca = fn.addLocal("ca", WasmValType.refNull(ts.charArrType), 1);
        int start = fn.addLocal("start", WasmValType.I32, 1);
        int end = fn.addLocal("end", WasmValType.I32, 1);
        int ch = fn.addLocal("ch", WasmValType.I32, 1);
        int nlen = fn.addLocal("nlen", WasmValType.I32, 1);
        int dst = fn.addLocal("dst", WasmValType.refNull(ts.charArrType), 1);

        c.localGet(0).structGet(ts.stringType, 0).localTee(ca).arrayLen().localSet(end);
        // Leading whitespace: ch <= 0x20
        c.loop()
                .localGet(start)
                .localGet(end)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(ca)
                .localGet(start)
                .arrayGetU(ts.charArrType)
                .localTee(ch)
                .i32Const(0x20)
                .op(WasmInstr.SimpleOp.I32_LE_U)
                .if_()
                .localGet(start)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(start)
                .br(2)
                .end()
                .end()
                .end();
        // Trailing whitespace
        c.loop()
                .localGet(end)
                .localGet(start)
                .op(WasmInstr.SimpleOp.I32_GT_S)
                .if_()
                .localGet(ca)
                .localGet(end)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .arrayGetU(ts.charArrType)
                .localTee(ch)
                .i32Const(0x20)
                .op(WasmInstr.SimpleOp.I32_LE_U)
                .if_()
                .localGet(end)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .localSet(end)
                .br(2)
                .end()
                .end()
                .end();
        c.localGet(end)
                .localGet(start)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .localTee(nlen)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .globalGet(gEmptyStr)
                .return_()
                .end();
        c.localGet(nlen).arrayNewDefault(ts.charArrType).localSet(dst);
        c.localGet(dst)
                .i32Const(0)
                .localGet(ca)
                .localGet(start)
                .localGet(nlen)
                .arrayCopy(ts.charArrType, ts.charArrType);
        c.localGet(dst).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    WasmModule.FuncDef defineFn(
            String name,
            List<WasmValType> params,
            List<WasmValType> results,
            List<String> paramNames) {
        return mod.addFunction(name, ts.internFuncType(params, results), paramNames);
    }
}
