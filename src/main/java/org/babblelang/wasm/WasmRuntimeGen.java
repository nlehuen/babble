package org.babblelang.wasm;

import org.babblelang.analysis.CompilationTarget;
import org.babblelang.analysis.PrimitiveKind;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates core WebAssembly 3.0 runtime globals, host imports, string literal pool,
 * and foundational pure-Wasm helper functions ({@code BIP025-I1}, {@code BIP025-D4},
 * {@code BIP025-D5}, {@code BIP025-D13}).
 */
public final class WasmRuntimeGen {
    private final WasmModule mod;
    private final WasmTypeSystem ts;
    private final CompilationTarget target;

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
    private record StringEntry(String value, int globalIdx, int byteOffset, int charLen, int javaHash) {
    }

    private final Map<String, StringEntry> stringPool = new LinkedHashMap<>();
    private final ByteArrayOutputStream stringDataBytes = new ByteArrayOutputStream();
    private final WasmModule.FuncDef fnInitStringsDef;
    public final int fnInitStrings;
    public final int fnInitArgs;

    // ---- Core Runtime Function Indices ----
    public final int fnFromExternStr; // -1 on wasm-bare
    public final int fnToExternStr;   // -1 on wasm-bare
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

        // 1. Declare host imports FIRST (Wasm requires all imports before defined functions)
        if (target == CompilationTarget.WASM_JS) {
            int tFromArr = ts.internFuncType("JsStrFromArr",
                    List.of(WasmValType.refNull(ts.charArrType), WasmValType.I32, WasmValType.I32),
                    List.of(WasmValType.ref(WasmValType.HeapType.Abstract.EXTERN)));
            int tIntoArr = ts.internFuncType("JsStrIntoArr",
                    List.of(WasmValType.EXTERNREF, WasmValType.refNull(ts.charArrType), WasmValType.I32),
                    List.of(WasmValType.I32));
            int tLen = ts.internFuncType("JsStrLen", List.of(WasmValType.EXTERNREF), List.of(WasmValType.I32));
            int tWrite = ts.internFuncType("EnvWrite", List.of(WasmValType.EXTERNREF), List.of());
            int tFmtF64 = ts.internFuncType("EnvFmtF64", List.of(WasmValType.F64), List.of(WasmValType.EXTERNREF));
            int tFmtF32 = ts.internFuncType("EnvFmtF32", List.of(WasmValType.F32), List.of(WasmValType.EXTERNREF));
            int tParseF64 = ts.internFuncType("EnvParseF64", List.of(WasmValType.EXTERNREF), List.of(WasmValType.F64, WasmValType.I32));
            int tParseF32 = ts.internFuncType("EnvParseF32", List.of(WasmValType.EXTERNREF), List.of(WasmValType.F32, WasmValType.I32));
            int tArgsCount = ts.internFuncType("EnvArgsCount", List.of(), List.of(WasmValType.I32));
            int tArgsGet = ts.internFuncType("EnvArgAt", List.of(WasmValType.I32), List.of(WasmValType.EXTERNREF));

            this.impJsStrFromArr = mod.addFuncImport("wasm:js-string", "fromCharCodeArray", tFromArr, "js_str_from_arr");
            this.impJsStrIntoArr = mod.addFuncImport("wasm:js-string", "intoCharCodeArray", tIntoArr, "js_str_into_arr");
            this.impJsStrLen = mod.addFuncImport("wasm:js-string", "length", tLen, "js_str_len");
            this.impWriteStdout = mod.addFuncImport("babble:env", "write_stdout", tWrite, "env_write_stdout");
            this.impWriteStderr = mod.addFuncImport("babble:env", "write_stderr", tWrite, "env_write_stderr");
            this.impFormatF64 = mod.addFuncImport("babble:env", "format_f64", tFmtF64, "env_format_f64");
            this.impFormatF32 = mod.addFuncImport("babble:env", "format_f32", tFmtF32, "env_format_f32");
            this.impParseF64 = mod.addFuncImport("babble:env", "parse_f64", tParseF64, "env_parse_f64");
            this.impParseF32 = mod.addFuncImport("babble:env", "parse_f32", tParseF32, "env_parse_f32");
            this.impArgsCount = mod.addFuncImport("babble:env", "args_count", tArgsCount, "env_args_count");
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
        this.gUndefined = mod.addGlobal("G_UNDEFINED", WasmValType.ref(ts.sentinelType), false,
                List.of(new WasmInstr.I32Const(0), new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.sentinelType)));
        this.gMissing = mod.addGlobal("G_MISSING", WasmValType.ref(ts.sentinelType), false,
                List.of(new WasmInstr.I32Const(1), new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.sentinelType)));
        this.gTrue = mod.addGlobal("G_TRUE", WasmValType.ref(ts.boxBoolType), false,
                List.of(new WasmInstr.I32Const(1), new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.boxBoolType)));
        this.gFalse = mod.addGlobal("G_FALSE", WasmValType.ref(ts.boxBoolType), false,
                List.of(new WasmInstr.I32Const(0), new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.boxBoolType)));
        this.gEmptyStr = mod.addGlobal("G_EMPTY_STR", WasmValType.ref(ts.stringType), false, List.of(
                new WasmInstr.Idx2(WasmInstr.Idx2Op.ARRAY_NEW_FIXED, ts.charArrType, 0),
                new WasmInstr.I32Const(0),
                new WasmInstr.I32Const(1),
                new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.stringType)));
        this.gEmptyArgs = mod.addGlobal("G_EMPTY_ARGS", WasmValType.ref(ts.anyArrType), false,
                List.of(new WasmInstr.Idx2(WasmInstr.Idx2Op.ARRAY_NEW_FIXED, ts.anyArrType, 0)));
        this.gStdout = mod.addGlobal("G_STDOUT", WasmValType.ref(ts.hostStreamType), false,
                List.of(new WasmInstr.I32Const(0), new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.hostStreamType)));
        this.gStderr = mod.addGlobal("G_STDERR", WasmValType.ref(ts.hostStreamType), false,
                List.of(new WasmInstr.I32Const(1), new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, ts.hostStreamType)));
        this.gArgs = mod.addGlobal("G_ARGS", WasmValType.refNull(ts.listType), true,
                ts.defaultConstExpr(WasmValType.refNull(ts.listType)));
        this.gLastResult = mod.addGlobal("G_LAST_RESULT", ts.anyRef(), true, ts.defaultConstExpr(ts.anyRef()));
        this.gLastError = mod.addGlobal("G_LAST_ERROR", WasmValType.refNull(ts.stringType), true,
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
            WasmModule.FuncDef fromExt = mod.addFunction("from_extern_str",
                    ts.internFuncType(List.of(WasmValType.EXTERNREF), List.of(refStr)), List.of("ext"));
            WasmModule.FuncDef toExt = mod.addFunction("to_extern_str",
                    ts.internFuncType(List.of(refStr), List.of(WasmValType.ref(WasmValType.HeapType.Abstract.EXTERN))), List.of("s"));
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

        WasmModule.FuncDef dStrEq = mod.addFunction("str_eq", ts.internFuncType(List.of(refStr, refStr), List.of(WasmValType.I32)), List.of("a", "b"));
        WasmModule.FuncDef dStrCmp = mod.addFunction("str_cmp", ts.internFuncType(List.of(refStr, refStr), List.of(WasmValType.I32)), List.of("a", "b"));
        WasmModule.FuncDef dStrConcat = mod.addFunction("str_concat", ts.internFuncType(List.of(refStr, refStr), List.of(refStr)), List.of("a", "b"));
        WasmModule.FuncDef dStrHash = mod.addFunction("str_hash", ts.internFuncType(List.of(refStr), List.of(WasmValType.I32)), List.of("s"));
        WasmModule.FuncDef dStrStrip = mod.addFunction("str_strip", ts.internFuncType(List.of(refStr), List.of(refStr)), List.of("s"));
        WasmModule.FuncDef dIntToStr = mod.addFunction("int_to_str", ts.internFuncType(List.of(WasmValType.I32), List.of(refStr)), List.of("v"));
        WasmModule.FuncDef dLongToStr = mod.addFunction("long_to_str", ts.internFuncType(List.of(WasmValType.I64), List.of(refStr)), List.of("v"));
        WasmModule.FuncDef dDoubleToStr = mod.addFunction("double_to_str", ts.internFuncType(List.of(WasmValType.F64), List.of(refStr)), List.of("v"));
        WasmModule.FuncDef dFloatToStr = mod.addFunction("float_to_str", ts.internFuncType(List.of(WasmValType.F32), List.of(refStr)), List.of("v"));
        WasmModule.FuncDef dCharToStr = mod.addFunction("char_to_str", ts.internFuncType(List.of(WasmValType.I32), List.of(refStr)), List.of("c"));
        WasmModule.FuncDef dParseLong = mod.addFunction("parse_long", ts.internFuncType(List.of(refStr), List.of(WasmValType.I64, WasmValType.I32)), List.of("s"));

        WasmModule.FuncDef dBoxByte = mod.addFunction("box_byte", ts.internFuncType(List.of(WasmValType.I32), List.of(refAny)), List.of("v"));
        WasmModule.FuncDef dBoxShort = mod.addFunction("box_short", ts.internFuncType(List.of(WasmValType.I32), List.of(refAny)), List.of("v"));
        WasmModule.FuncDef dBoxInt = mod.addFunction("box_int", ts.internFuncType(List.of(WasmValType.I32), List.of(refAny)), List.of("v"));
        WasmModule.FuncDef dBoxLong = mod.addFunction("box_long", ts.internFuncType(List.of(WasmValType.I64), List.of(refAny)), List.of("v"));
        WasmModule.FuncDef dBoxFloat = mod.addFunction("box_float", ts.internFuncType(List.of(WasmValType.F32), List.of(refAny)), List.of("v"));
        WasmModule.FuncDef dBoxDouble = mod.addFunction("box_double", ts.internFuncType(List.of(WasmValType.F64), List.of(refAny)), List.of("v"));
        WasmModule.FuncDef dBoxBool = mod.addFunction("box_bool", ts.internFuncType(List.of(WasmValType.I32), List.of(refAny)), List.of("v"));
        WasmModule.FuncDef dBoxChar = mod.addFunction("box_char", ts.internFuncType(List.of(WasmValType.I32), List.of(refAny)), List.of("v"));

        WasmModule.FuncDef dUnboxByte = mod.addFunction("unbox_byte", ts.internFuncType(List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I32)), List.of("v", "line"));
        WasmModule.FuncDef dUnboxShort = mod.addFunction("unbox_short", ts.internFuncType(List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I32)), List.of("v", "line"));
        WasmModule.FuncDef dUnboxInt = mod.addFunction("unbox_int", ts.internFuncType(List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I32)), List.of("v", "line"));
        WasmModule.FuncDef dUnboxLong = mod.addFunction("unbox_long", ts.internFuncType(List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I64)), List.of("v", "line"));
        WasmModule.FuncDef dUnboxFloat = mod.addFunction("unbox_float", ts.internFuncType(List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.F32)), List.of("v", "line"));
        WasmModule.FuncDef dUnboxDouble = mod.addFunction("unbox_double", ts.internFuncType(List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.F64)), List.of("v", "line"));
        WasmModule.FuncDef dUnboxBool = mod.addFunction("unbox_bool", ts.internFuncType(List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I32)), List.of("v", "line"));
        WasmModule.FuncDef dUnboxChar = mod.addFunction("unbox_char", ts.internFuncType(List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I32)), List.of("v", "line"));

        WasmModule.FuncDef dF64Cmp = mod.addFunction("f64_compare", ts.internFuncType(List.of(WasmValType.F64, WasmValType.F64), List.of(WasmValType.I32)), List.of("a", "b"));
        WasmModule.FuncDef dF32Cmp = mod.addFunction("f32_compare", ts.internFuncType(List.of(WasmValType.F32, WasmValType.F32), List.of(WasmValType.I32)), List.of("a", "b"));
        WasmModule.FuncDef dTruth = mod.addFunction("truth", ts.internFuncType(List.of(refAnyNull), List.of(WasmValType.I32)), List.of("v"));
        WasmModule.FuncDef dNumRank = mod.addFunction("num_rank", ts.internFuncType(List.of(refAnyNull), List.of(WasmValType.I32)), List.of("v"));
        WasmModule.FuncDef dNumAsLong = mod.addFunction("num_as_long", ts.internFuncType(List.of(refAny), List.of(WasmValType.I64)), List.of("v"));
        WasmModule.FuncDef dNumAsDouble = mod.addFunction("num_as_double", ts.internFuncType(List.of(refAny), List.of(WasmValType.F64)), List.of("v"));
        WasmModule.FuncDef dWidenNum = mod.addFunction("widen_numeric", ts.internFuncType(List.of(refAnyNull, WasmValType.I32), List.of(refAnyNull)), List.of("v", "rank"));
        WasmModule.FuncDef dEquals = mod.addFunction("equals", ts.internFuncType(List.of(refAnyNull, refAnyNull), List.of(WasmValType.I32)), List.of("a", "b"));
        WasmModule.FuncDef dSame = mod.addFunction("same", ts.internFuncType(List.of(refAnyNull, refAnyNull), List.of(WasmValType.I32)), List.of("a", "b"));
        WasmModule.FuncDef dHashCode = mod.addFunction("hash_code", ts.internFuncType(List.of(refAnyNull), List.of(WasmValType.I32)), List.of("v"));
        WasmModule.FuncDef dCompare = mod.addFunction("compare", ts.internFuncType(List.of(refAnyNull, refAnyNull, refStr, refStr, WasmValType.I32), List.of(WasmValType.I32)), List.of("a", "b", "lt", "rt", "line"));
        WasmModule.FuncDef dFormat = mod.addFunction("format", ts.internFuncType(List.of(refAnyNull), List.of(refStr)), List.of("v"));

        WasmModule.FuncDef dMakeExn = mod.addFunction("make_exn", ts.internFuncType(List.of(refStrNull, refAnyNull, WasmValType.I32), List.of(WasmValType.ref(ts.babbleExnType))), List.of("msg", "cause", "line"));
        WasmModule.FuncDef dThrowMsg = mod.addFunction("throw_msg", ts.internFuncType(List.of(refStr, WasmValType.I32), List.of()), List.of("msg", "line"));
        WasmModule.FuncDef dAddSupp = mod.addFunction("add_suppressed", ts.internFuncType(List.of(refAny, refAny), List.of()), List.of("primary", "secondary"));
        WasmModule.FuncDef dAssert = mod.addFunction("assert_fn", ts.internFuncType(List.of(refAnyNull, refAnyNull, refStr, WasmValType.I32), List.of(WasmValType.I32)), List.of("cond", "msg", "expr", "line"));
        WasmModule.FuncDef dPrint = mod.addFunction("print_values", ts.internFuncType(List.of(WasmValType.ref(ts.anyArrType), refAnyNull, WasmValType.I32, WasmValType.I32), List.of()), List.of("vals", "to", "nl", "line"));

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
        emitIntToStr(dIntToStr);
        emitLongToStr(dLongToStr);
        emitDoubleToStr(dDoubleToStr);
        emitFloatToStr(dFloatToStr);
        emitCharToStr(dCharToStr);
        emitParseLong(dParseLong);

        emitSimpleBox(dBoxByte, ts.boxByteType, WasmInstr.SimpleOp.I32_EXTEND8_S);
        emitSimpleBox(dBoxShort, ts.boxShortType, WasmInstr.SimpleOp.I32_EXTEND16_S);
        emitSimpleBox(dBoxInt, ts.boxIntType, null);
        emitSimpleBox(dBoxLong, ts.boxLongType, null);
        emitSimpleBox(dBoxFloat, ts.boxFloatType, null);
        emitSimpleBox(dBoxDouble, ts.boxDoubleType, null);
        emitBoxBool(dBoxBool);
        emitBoxChar(dBoxChar);

        emitUnboxIntegral(dUnboxByte, ts.boxByteType, PrimitiveKind.BYTE);
        emitUnboxIntegral(dUnboxShort, ts.boxShortType, PrimitiveKind.SHORT);
        emitUnboxIntegral(dUnboxInt, ts.boxIntType, PrimitiveKind.INT);
        emitUnboxLong(dUnboxLong);
        emitUnboxFloat(dUnboxFloat);
        emitUnboxDouble(dUnboxDouble);
        emitUnboxBool(dUnboxBool);
        emitUnboxChar(dUnboxChar);

        this.dEqualsDef = dEquals;
        this.dHashCodeDef = dHashCode;
        this.dFormatDef = dFormat;

        emitF64Compare(dF64Cmp);
        emitF32Compare(dF32Cmp);
        emitTruth(dTruth);
        emitNumRank(dNumRank);
        emitNumAsLong(dNumAsLong);
        emitNumAsDouble(dNumAsDouble);
        emitWidenNumeric(dWidenNum);
        emitSame(dSame);
        emitCompare(dCompare);

        emitMakeExn(dMakeExn);
        emitThrowMsg(dThrowMsg);
        emitAddSuppressed(dAddSupp);
        emitAssert(dAssert);
        emitPrint(dPrint);
    }

    /** Emits {@code equals}, {@code hashCode}, and {@code format} with {@code $ArrayBase} and collection hooks ({@code BIP025-I2}, {@code BIP025-I3}). */
    public void emitPolymorphicHelpers(int fnArrEq, int fnArrHash, int fnArrFmt,
                                       int fnColEq, int fnColHash, int fnColFmt) {
        emitEquals(dEqualsDef, fnArrEq, fnColEq);
        emitHashCode(dHashCodeDef, fnArrHash, fnColHash);
        emitFormat(dFormatDef, fnArrFmt, fnColFmt);
    }

    private void emitInitArgs(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        if (target != CompilationTarget.WASM_JS) {
            c.i32Const(0).arrayNewDefault(ts.anyArrType)
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
        c.call(impArgsCount).localTee(n)
                .i32Const(4).op(WasmInstr.SimpleOp.I32_GE_S).if_(WasmValType.I32)
                .localGet(n)
                .else_()
                .i32Const(4)
                .end()
                .localTee(cap)
                .arrayNewDefault(ts.anyArrType).localSet(arr)
                .i32Const(0).localSet(i)
                .block()
                .loop()
                .localGet(i).localGet(n).op(WasmInstr.SimpleOp.I32_GE_S).brIf(1)
                .localGet(arr).refAsNonNull()
                .localGet(i)
                .localGet(i).call(impArgsGet).call(fnFromExternStr)
                .arraySet(ts.anyArrType)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(0)
                .end()
                .end()
                .localGet(arr).refAsNonNull()
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
        int globalIdx = mod.addGlobal("S_" + stringPool.size(), WasmValType.refNull(ts.stringType), true,
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
        c.localGet(0).call(impJsStrLen).localTee(len)
                .arrayNewDefault(ts.charArrType).localTee(arr)
                .drop();
        c.localGet(0).localGet(arr).i32Const(0).call(impJsStrIntoArr).drop();
        c.localGet(arr).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    private void emitToExternStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 1);
        c.localGet(0).structGet(ts.stringType, 0).localTee(arr)
                .i32Const(0)
                .localGet(arr).arrayLen()
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
        c.localGet(ca).arrayLen().localTee(len).localGet(cb).arrayLen().op(WasmInstr.SimpleOp.I32_NE)
                .if_().i32Const(0).return_().end();
        c.loop()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(ca).localGet(i).arrayGetU(ts.charArrType)
                .localGet(cb).localGet(i).arrayGetU(ts.charArrType)
                .op(WasmInstr.SimpleOp.I32_NE)
                .if_().i32Const(0).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
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
        c.localGet(la).localGet(lb).localGet(la).localGet(lb).op(WasmInstr.SimpleOp.I32_LT_S).select().localSet(lim);
        c.loop()
                .localGet(i).localGet(lim).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(ca).localGet(i).arrayGetU(ts.charArrType)
                .localGet(cb).localGet(i).arrayGetU(ts.charArrType)
                .op(WasmInstr.SimpleOp.I32_SUB).localTee(diff)
                .if_().localGet(diff).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
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

        c.localGet(0).structGet(ts.stringType, 0).localTee(ca).arrayLen().localTee(la)
                .op(WasmInstr.SimpleOp.I32_EQZ).if_().localGet(1).return_().end();
        c.localGet(1).structGet(ts.stringType, 0).localTee(cb).arrayLen().localTee(lb)
                .op(WasmInstr.SimpleOp.I32_EQZ).if_().localGet(0).return_().end();
        c.localGet(la).localGet(lb).op(WasmInstr.SimpleOp.I32_ADD).arrayNewDefault(ts.charArrType).localSet(dst);
        c.localGet(dst).i32Const(0).localGet(ca).i32Const(0).localGet(la).arrayCopy(ts.charArrType, ts.charArrType);
        c.localGet(dst).localGet(la).localGet(cb).i32Const(0).localGet(lb).arrayCopy(ts.charArrType, ts.charArrType);
        c.localGet(dst).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    private void emitStrHash(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ca = fn.addLocal("ca", WasmValType.refNull(ts.charArrType), 1);
        int len = fn.addLocal("len", WasmValType.I32, 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        int h = fn.addLocal("h", WasmValType.I32, 1);

        c.localGet(0).structGet(ts.stringType, 2).if_()
                .localGet(0).structGet(ts.stringType, 1).return_()
                .end();
        c.localGet(0).structGet(ts.stringType, 0).localTee(ca).arrayLen().localSet(len);
        c.loop()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(h).i32Const(31).op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(ca).localGet(i).arrayGetU(ts.charArrType)
                .op(WasmInstr.SimpleOp.I32_ADD).localSet(h)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
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
                .localGet(start).localGet(end).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(ca).localGet(start).arrayGetU(ts.charArrType).localTee(ch)
                .i32Const(0x20).op(WasmInstr.SimpleOp.I32_LE_U)
                .if_()
                .localGet(start).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(start)
                .br(2)
                .end()
                .end()
                .end();
        // Trailing whitespace
        c.loop()
                .localGet(end).localGet(start).op(WasmInstr.SimpleOp.I32_GT_S)
                .if_()
                .localGet(ca).localGet(end).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).arrayGetU(ts.charArrType).localTee(ch)
                .i32Const(0x20).op(WasmInstr.SimpleOp.I32_LE_U)
                .if_()
                .localGet(end).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(end)
                .br(2)
                .end()
                .end()
                .end();
        c.localGet(end).localGet(start).op(WasmInstr.SimpleOp.I32_SUB).localTee(nlen)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_().globalGet(gEmptyStr).return_().end();
        c.localGet(nlen).arrayNewDefault(ts.charArrType).localSet(dst);
        c.localGet(dst).i32Const(0).localGet(ca).localGet(start).localGet(nlen).arrayCopy(ts.charArrType, ts.charArrType);
        c.localGet(dst).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    // ---------------------------------------------------------------- number formatting & parsing

    private void emitIntToStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S).call(fnLongToStr);
    }

    private void emitLongToStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int buf = fn.addLocal("buf", WasmValType.refNull(ts.charArrType), 1);
        int pos = fn.addLocal("pos", WasmValType.I32, 1);
        int neg = fn.addLocal("neg", WasmValType.I32, 1);
        int mag = fn.addLocal("mag", WasmValType.I64, 1); // unsigned magnitude!
        int outLen = fn.addLocal("outLen", WasmValType.I32, 1);
        int out = fn.addLocal("out", WasmValType.refNull(ts.charArrType), 1);

        c.localGet(0).op(WasmInstr.SimpleOp.I64_EQZ).if_();
        emitStringLit(c, "0");
        c.return_().end();

        c.i32Const(24).arrayNewDefault(ts.charArrType).localSet(buf);
        c.i32Const(24).localSet(pos);

        c.localGet(0).i64Const(0L).op(WasmInstr.SimpleOp.I64_LT_S)
                .if_()
                .i32Const(1).localSet(neg)
                .i64Const(0L).localGet(0).op(WasmInstr.SimpleOp.I64_SUB).localSet(mag) // handles Long.MIN_VALUE as unsigned 2^63!
                .else_()
                .localGet(0).localSet(mag)
                .end();

        c.loop()
                .localGet(mag).i64Const(0L).op(WasmInstr.SimpleOp.I64_NE)
                .if_()
                .localGet(pos).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(pos)
                .localGet(buf).localGet(pos)
                .localGet(mag).i64Const(10L).op(WasmInstr.SimpleOp.I64_REM_U).op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .i32Const('0').op(WasmInstr.SimpleOp.I32_ADD)
                .arraySet(ts.charArrType)
                .localGet(mag).i64Const(10L).op(WasmInstr.SimpleOp.I64_DIV_U).localSet(mag)
                .br(1)
                .end()
                .end();

        c.localGet(neg).if_()
                .localGet(pos).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(pos)
                .localGet(buf).localGet(pos).i32Const('-').arraySet(ts.charArrType)
                .end();

        c.i32Const(24).localGet(pos).op(WasmInstr.SimpleOp.I32_SUB).localTee(outLen)
                .arrayNewDefault(ts.charArrType).localSet(out);
        c.localGet(out).i32Const(0).localGet(buf).localGet(pos).localGet(outLen).arrayCopy(ts.charArrType, ts.charArrType);
        c.localGet(out).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    private void emitDoubleToStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        if (target == CompilationTarget.WASM_JS) {
            c.localGet(0).call(impFormatF64).call(fnFromExternStr);
            return;
        }
        // Pure-Wasm fallback for wasm-bare (zero host imports)
        int bits = fn.addLocal("bits", WasmValType.I64, 1);
        int neg = fn.addLocal("neg", WasmValType.I32, 1);
        int absD = fn.addLocal("absD", WasmValType.F64, 1);
        int whole = fn.addLocal("whole", WasmValType.I64, 1);
        int frac = fn.addLocal("frac", WasmValType.I64, 1);
        int res = fn.addLocal("res", WasmValType.refNull(ts.stringType), 1);

        // NaN
        c.localGet(0).localGet(0).op(WasmInstr.SimpleOp.F64_NE).if_();
        emitStringGlobal(c, sNaN);
        c.return_().end();
        // Infinity / -Infinity
        c.localGet(0).f64Const(Double.POSITIVE_INFINITY).op(WasmInstr.SimpleOp.F64_EQ).if_();
        emitStringGlobal(c, sInfinity);
        c.return_().end();
        c.localGet(0).f64Const(Double.NEGATIVE_INFINITY).op(WasmInstr.SimpleOp.F64_EQ).if_();
        emitStringGlobal(c, sNegInfinity);
        c.return_().end();
        // -0.0 / 0.0
        c.localGet(0).op(WasmInstr.SimpleOp.I64_REINTERPRET_F64).localSet(bits);
        c.localGet(bits).i64Const(0x8000_0000_0000_0000L).op(WasmInstr.SimpleOp.I64_EQ).if_();
        emitStringGlobal(c, sNegZero);
        c.return_().end();
        c.localGet(bits).op(WasmInstr.SimpleOp.I64_EQZ).if_();
        emitStringLit(c, "0.0");
        c.return_().end();

        c.localGet(bits).i64Const(0L).op(WasmInstr.SimpleOp.I64_LT_S).localSet(neg);
        c.localGet(0).op(WasmInstr.SimpleOp.F64_ABS).localSet(absD);
        // Scale out-of-range magnitudes into scientific notation so absD never overflows Long.MAX_VALUE
        int exp = fn.addLocal("exp", WasmValType.I32, 1);
        int useSci = fn.addLocal("useSci", WasmValType.I32, 1);
        c.localGet(absD).f64Const(1.0e7).op(WasmInstr.SimpleOp.F64_GE)
                .localGet(absD).f64Const(1.0e-3).op(WasmInstr.SimpleOp.F64_LT).op(WasmInstr.SimpleOp.I32_OR)
                .if_()
                .i32Const(1).localSet(useSci)
                .loop()
                .localGet(absD).f64Const(10.0).op(WasmInstr.SimpleOp.F64_GE).if_()
                .localGet(absD).f64Const(10.0).op(WasmInstr.SimpleOp.F64_DIV).localSet(absD)
                .localGet(exp).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(exp)
                .br(1).end().end()
                .loop()
                .localGet(absD).f64Const(1.0).op(WasmInstr.SimpleOp.F64_LT).if_()
                .localGet(absD).f64Const(10.0).op(WasmInstr.SimpleOp.F64_MUL).localSet(absD)
                .localGet(exp).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(exp)
                .br(1).end().end()
                .end();
        // Fixed decimal for [1e-3, 1e7) or scaled mantissa [1.0, 10.0)
        c.localGet(absD).op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F64_S).localSet(whole);
        c.localGet(absD).localGet(whole).op(WasmInstr.SimpleOp.F64_CONVERT_I64_S).op(WasmInstr.SimpleOp.F64_SUB)
                .f64Const(1_000_000.0).op(WasmInstr.SimpleOp.F64_MUL).op(WasmInstr.SimpleOp.F64_NEAREST)
                .op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F64_S).localSet(frac);
        c.localGet(frac).i64Const(1_000_000L).op(WasmInstr.SimpleOp.I64_GE_S).if_()
                .localGet(whole).i64Const(1L).op(WasmInstr.SimpleOp.I64_ADD).localSet(whole)
                .i64Const(0L).localSet(frac)
                .end();
        c.localGet(whole).call(fnLongToStr);
        emitStringLit(c, ".");
        c.call(fnStrConcat).localSet(res);
        // Trim trailing zeros of frac (keep at least 1 digit)
        c.localGet(frac).op(WasmInstr.SimpleOp.I64_EQZ).if_()
                .localGet(res).refAsNonNull();
        emitStringLit(c, "0");
        c.call(fnStrConcat).localSet(res)
                .else_();
        // Leading zeros for frac
        int div = fn.addLocal("div", WasmValType.I64, 1);
        c.i64Const(100_000L).localSet(div);
        c.loop()
                .localGet(div).i64Const(1L).op(WasmInstr.SimpleOp.I64_GT_S)
                .if_()
                .localGet(frac).localGet(div).op(WasmInstr.SimpleOp.I64_LT_S)
                .if_()
                .localGet(res).refAsNonNull();
        emitStringLit(c, "0");
        c.call(fnStrConcat).localSet(res)
                .localGet(div).i64Const(10L).op(WasmInstr.SimpleOp.I64_DIV_S).localSet(div)
                .br(2)
                .end()
                .end()
                .end();
        c.loop()
                .localGet(frac).i64Const(10L).op(WasmInstr.SimpleOp.I64_REM_S).op(WasmInstr.SimpleOp.I64_EQZ)
                .if_()
                .localGet(frac).i64Const(10L).op(WasmInstr.SimpleOp.I64_DIV_S).localSet(frac)
                .br(1)
                .end()
                .end();
        c.localGet(res).refAsNonNull().localGet(frac).call(fnLongToStr).call(fnStrConcat).localSet(res);
        c.end();

        c.localGet(useSci).if_()
                .localGet(res).refAsNonNull();
        emitStringLit(c, "E");
        c.call(fnStrConcat).localGet(exp).call(fnIntToStr).call(fnStrConcat).localSet(res)
                .end();

        c.localGet(neg).if_();
        emitStringLit(c, "-");
        c.localGet(res).refAsNonNull().call(fnStrConcat).return_().end();
        c.localGet(res).refAsNonNull();
    }

    private void emitFloatToStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        if (target == CompilationTarget.WASM_JS) {
            c.localGet(0).call(impFormatF32).call(fnFromExternStr);
        } else {
            c.localGet(0).op(WasmInstr.SimpleOp.F64_PROMOTE_F32).call(fnDoubleToStr);
        }
    }

    private void emitCharToStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).arrayNewFixed(ts.charArrType, 1)
                .localGet(0)
                .i32Const(1)
                .structNew(ts.stringType);
    }

    /**
     * Parses a decimal signed 64-bit integer from a stripped string.
     * Returns {@code (value: i64, status: i32)} where {@code 0 = OK}, {@code 1 = NOT_INT}, {@code 2 = OVERFLOW}.
     */
    private void emitParseLong(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ca = fn.addLocal("ca", WasmValType.refNull(ts.charArrType), 1);
        int len = fn.addLocal("len", WasmValType.I32, 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        int neg = fn.addLocal("neg", WasmValType.I32, 1);
        int ch = fn.addLocal("ch", WasmValType.I32, 1);
        int acc = fn.addLocal("acc", WasmValType.I64, 1); // accumulated as NEGATIVE so Long.MIN_VALUE fits cleanly!
        int limit = fn.addLocal("limit", WasmValType.I64, 1);
        int multmin = fn.addLocal("multmin", WasmValType.I64, 1);
        int digit = fn.addLocal("digit", WasmValType.I64, 1);
        int overflow = fn.addLocal("overflow", WasmValType.I32, 1);

        c.localGet(0).call(fnStrStrip).structGet(ts.stringType, 0).localTee(ca).arrayLen().localTee(len)
                .op(WasmInstr.SimpleOp.I32_EQZ).if_().i64Const(0L).i32Const(1).return_().end();

        c.i64Const(-Long.MAX_VALUE).localSet(limit);
        c.localGet(ca).i32Const(0).arrayGetU(ts.charArrType).localTee(ch)
                .i32Const('-').op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .i32Const(1).localSet(neg)
                .i64Const(Long.MIN_VALUE).localSet(limit)
                .i32Const(1).localSet(i)
                .else_()
                .localGet(ch).i32Const('+').op(WasmInstr.SimpleOp.I32_EQ)
                .if_().i32Const(1).localSet(i).end()
                .end();

        // Must have at least 1 digit
        c.localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_GE_S)
                .if_().i64Const(0L).i32Const(1).return_().end();

        c.localGet(limit).i64Const(10L).op(WasmInstr.SimpleOp.I64_DIV_S).localSet(multmin);

        c.loop()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(ca).localGet(i).arrayGetU(ts.charArrType).localTee(ch)
                .i32Const('0').op(WasmInstr.SimpleOp.I32_SUB).localSet(ch)
                .localGet(ch).i32Const(9).op(WasmInstr.SimpleOp.I32_GT_U)
                .if_().i64Const(0L).i32Const(1).return_().end()
                .localGet(ch).op(WasmInstr.SimpleOp.I64_EXTEND_I32_U).localSet(digit)
                .localGet(acc).localGet(multmin).op(WasmInstr.SimpleOp.I64_LT_S)
                .if_().i32Const(1).localSet(overflow).end()
                .localGet(acc).i64Const(10L).op(WasmInstr.SimpleOp.I64_MUL).localSet(acc)
                .localGet(acc).localGet(limit).localGet(digit).op(WasmInstr.SimpleOp.I64_ADD).op(WasmInstr.SimpleOp.I64_LT_S)
                .if_().i32Const(1).localSet(overflow).end()
                .localGet(acc).localGet(digit).op(WasmInstr.SimpleOp.I64_SUB).localSet(acc)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1)
                .end()
                .end();

        c.localGet(overflow).if_().i64Const(0L).i32Const(2).return_().end();
        c.localGet(neg).if_()
                .localGet(acc).i32Const(0).return_()
                .end();
        c.i64Const(0L).localGet(acc).op(WasmInstr.SimpleOp.I64_SUB).i32Const(0);
    }

    // ---------------------------------------------------------------- boxing & unboxing

    private void emitSimpleBox(WasmModule.FuncDef fn, int boxType, WasmInstr.SimpleOp normOp) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0);
        if (normOp != null) {
            c.op(normOp);
        }
        c.structNew(boxType);
    }

    private void emitBoxBool(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).if_(ts.nonNullAnyRef())
                .globalGet(gTrue)
                .else_()
                .globalGet(gFalse)
                .end();
    }

    private void emitBoxChar(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).i32Const(0xFFFF).op(WasmInstr.SimpleOp.I32_AND).structNew(ts.boxCharType);
    }

    private void emitUnboxIntegral(WasmModule.FuncDef fn, int exactBox, PrimitiveKind kind) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(exactBox).if_()
                .localGet(0).refCast(exactBox).structGet(exactBox, 0).return_()
                .end();
        if (kind == PrimitiveKind.SHORT || kind == PrimitiveKind.INT) {
            c.localGet(0).refTest(ts.boxByteType).if_()
                    .localGet(0).refCast(ts.boxByteType).structGet(ts.boxByteType, 0).return_()
                    .end();
        }
        if (kind == PrimitiveKind.INT) {
            c.localGet(0).refTest(ts.boxShortType).if_()
                    .localGet(0).refCast(ts.boxShortType).structGet(ts.boxShortType, 0).return_()
                    .end();
        }
        emitStringLit(c, "Expected " + kind.name().toLowerCase());
        c.localGet(1).call(fnThrowMsg).unreachable();
    }

    private void emitUnboxLong(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(ts.boxLongType).if_()
                .localGet(0).refCast(ts.boxLongType).structGet(ts.boxLongType, 0).return_()
                .end();
        c.localGet(0).refTest(ts.boxIntType).if_()
                .localGet(0).refCast(ts.boxIntType).structGet(ts.boxIntType, 0).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S).return_()
                .end();
        c.localGet(0).refTest(ts.boxShortType).if_()
                .localGet(0).refCast(ts.boxShortType).structGet(ts.boxShortType, 0).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S).return_()
                .end();
        c.localGet(0).refTest(ts.boxByteType).if_()
                .localGet(0).refCast(ts.boxByteType).structGet(ts.boxByteType, 0).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S).return_()
                .end();
        emitStringLit(c, "Expected long");
        c.localGet(1).call(fnThrowMsg).unreachable();
    }

    private void emitUnboxFloat(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int r = fn.addLocal("r", WasmValType.I32, 2);
        c.localGet(0).refTest(ts.boxFloatType).if_()
                .localGet(0).refCast(ts.boxFloatType).structGet(ts.boxFloatType, 0).return_()
                .end();
        c.localGet(0).call(fnNumRank).localTee(r).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S)
                .localGet(r).i32Const(4).op(WasmInstr.SimpleOp.I32_LE_S).op(WasmInstr.SimpleOp.I32_AND).if_()
                .localGet(0).refAsNonNull().call(fnNumAsDouble).op(WasmInstr.SimpleOp.F32_DEMOTE_F64).return_()
                .end();
        emitStringLit(c, "Expected float");
        c.localGet(1).call(fnThrowMsg).unreachable();
    }

    private void emitUnboxDouble(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(ts.boxDoubleType).if_()
                .localGet(0).refCast(ts.boxDoubleType).structGet(ts.boxDoubleType, 0).return_()
                .end();
        c.localGet(0).call(fnNumRank).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(0).refAsNonNull().call(fnNumAsDouble).return_()
                .end();
        emitStringLit(c, "Expected double");
        c.localGet(1).call(fnThrowMsg).unreachable();
    }

    private void emitUnboxBool(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(ts.boxBoolType).if_()
                .localGet(0).refCast(ts.boxBoolType).structGet(ts.boxBoolType, 0).return_()
                .end();
        emitStringLit(c, "Expected bool");
        c.localGet(1).call(fnThrowMsg).unreachable();
    }

    private void emitUnboxChar(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(ts.boxCharType).if_()
                .localGet(0).refCast(ts.boxCharType).structGet(ts.boxCharType, 0).return_()
                .end();
        emitStringLit(c, "Expected char");
        c.localGet(1).call(fnThrowMsg).unreachable();
    }

    // ---------------------------------------------------------------- Java Double/Float.compare & Truth

    /** Exact {@code java.lang.Double.compare(a, b)}: {@code NaN == NaN}, {@code NaN > +Inf}, {@code -0.0 < +0.0}. */
    private void emitF64Compare(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ba = fn.addLocal("ba", WasmValType.I64, 2);
        int bb = fn.addLocal("bb", WasmValType.I64, 2);
        c.localGet(0).localGet(1).op(WasmInstr.SimpleOp.F64_LT).if_().i32Const(-1).return_().end();
        c.localGet(0).localGet(1).op(WasmInstr.SimpleOp.F64_GT).if_().i32Const(1).return_().end();
        // Either equal (incl. -0.0 == +0.0) or at least one is NaN
        c.localGet(0).localGet(0).op(WasmInstr.SimpleOp.F64_NE).if_()
                // a is NaN
                .localGet(1).localGet(1).op(WasmInstr.SimpleOp.F64_NE).if_().i32Const(0).return_().end()
                .i32Const(1).return_()
                .end();
        c.localGet(1).localGet(1).op(WasmInstr.SimpleOp.F64_NE).if_().i32Const(-1).return_().end();
        // Neither is NaN and a == b; distinguish -0.0 vs +0.0 via raw bits
        c.localGet(0).op(WasmInstr.SimpleOp.I64_REINTERPRET_F64).localSet(ba);
        c.localGet(1).op(WasmInstr.SimpleOp.I64_REINTERPRET_F64).localSet(bb);
        c.localGet(ba).localGet(bb).op(WasmInstr.SimpleOp.I64_EQ).if_().i32Const(0).return_().end();
        c.localGet(ba).localGet(bb).op(WasmInstr.SimpleOp.I64_LT_S).if_().i32Const(-1).return_().end();
        c.i32Const(1);
    }

    /** Exact {@code java.lang.Float.compare(a, b)}. */
    private void emitF32Compare(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ba = fn.addLocal("ba", WasmValType.I32, 2);
        int bb = fn.addLocal("bb", WasmValType.I32, 2);
        c.localGet(0).localGet(1).op(WasmInstr.SimpleOp.F32_LT).if_().i32Const(-1).return_().end();
        c.localGet(0).localGet(1).op(WasmInstr.SimpleOp.F32_GT).if_().i32Const(1).return_().end();
        c.localGet(0).localGet(0).op(WasmInstr.SimpleOp.F32_NE).if_()
                .localGet(1).localGet(1).op(WasmInstr.SimpleOp.F32_NE).if_().i32Const(0).return_().end()
                .i32Const(1).return_()
                .end();
        c.localGet(1).localGet(1).op(WasmInstr.SimpleOp.F32_NE).if_().i32Const(-1).return_().end();
        c.localGet(0).op(WasmInstr.SimpleOp.I32_REINTERPRET_F32).localSet(ba);
        c.localGet(1).op(WasmInstr.SimpleOp.I32_REINTERPRET_F32).localSet(bb);
        c.localGet(ba).localGet(bb).op(WasmInstr.SimpleOp.I32_EQ).if_().i32Const(0).return_().end();
        c.localGet(ba).localGet(bb).op(WasmInstr.SimpleOp.I32_LT_S).if_().i32Const(-1).return_().end();
        c.i32Const(1);
    }

    private void emitTruth(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refIsNull().if_().i32Const(0).return_().end();
        c.localGet(0).refTest(ts.boxBoolType).if_()
                .localGet(0).refCast(ts.boxBoolType).structGet(ts.boxBoolType, 0).return_()
                .end();
        c.localGet(0).call(fnNumRank).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(0).refAsNonNull().call(fnNumAsDouble).f64Const(0.0).op(WasmInstr.SimpleOp.F64_NE).return_()
                .end();
        c.i32Const(1);
    }

    // ---------------------------------------------------------------- numeric helpers

    /** Returns numeric rank: 0=byte, 1=short, 2=int, 3=long, 4=float, 5=double, -1=not a number. */
    private void emitNumRank(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(ts.boxIntType).if_().i32Const(2).return_().end();
        c.localGet(0).refTest(ts.boxDoubleType).if_().i32Const(5).return_().end();
        c.localGet(0).refTest(ts.boxLongType).if_().i32Const(3).return_().end();
        c.localGet(0).refTest(ts.boxFloatType).if_().i32Const(4).return_().end();
        c.localGet(0).refTest(ts.boxShortType).if_().i32Const(1).return_().end();
        c.localGet(0).refTest(ts.boxByteType).if_().i32Const(0).return_().end();
        c.i32Const(-1);
    }

    private void emitNumAsLong(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(ts.boxIntType).if_()
                .localGet(0).refCast(ts.boxIntType).structGet(ts.boxIntType, 0).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S).return_().end();
        c.localGet(0).refTest(ts.boxLongType).if_()
                .localGet(0).refCast(ts.boxLongType).structGet(ts.boxLongType, 0).return_().end();
        c.localGet(0).refTest(ts.boxShortType).if_()
                .localGet(0).refCast(ts.boxShortType).structGet(ts.boxShortType, 0).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S).return_().end();
        c.localGet(0).refTest(ts.boxByteType).if_()
                .localGet(0).refCast(ts.boxByteType).structGet(ts.boxByteType, 0).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S).return_().end();
        c.localGet(0).refTest(ts.boxDoubleType).if_()
                .localGet(0).refCast(ts.boxDoubleType).structGet(ts.boxDoubleType, 0).op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F64_S).return_().end();
        c.localGet(0).refCast(ts.boxFloatType).structGet(ts.boxFloatType, 0).op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F32_S);
    }

    private void emitNumAsDouble(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(ts.boxDoubleType).if_()
                .localGet(0).refCast(ts.boxDoubleType).structGet(ts.boxDoubleType, 0).return_().end();
        c.localGet(0).refTest(ts.boxIntType).if_()
                .localGet(0).refCast(ts.boxIntType).structGet(ts.boxIntType, 0).op(WasmInstr.SimpleOp.F64_CONVERT_I32_S).return_().end();
        c.localGet(0).refTest(ts.boxLongType).if_()
                .localGet(0).refCast(ts.boxLongType).structGet(ts.boxLongType, 0).op(WasmInstr.SimpleOp.F64_CONVERT_I64_S).return_().end();
        c.localGet(0).refTest(ts.boxFloatType).if_()
                .localGet(0).refCast(ts.boxFloatType).structGet(ts.boxFloatType, 0).op(WasmInstr.SimpleOp.F64_PROMOTE_F32).return_().end();
        c.localGet(0).refTest(ts.boxShortType).if_()
                .localGet(0).refCast(ts.boxShortType).structGet(ts.boxShortType, 0).op(WasmInstr.SimpleOp.F64_CONVERT_I32_S).return_().end();
        c.localGet(0).refCast(ts.boxByteType).structGet(ts.boxByteType, 0).op(WasmInstr.SimpleOp.F64_CONVERT_I32_S);
    }

    private void emitWidenNumeric(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int r = fn.addLocal("r", WasmValType.I32, 2);
        c.localGet(0).call(fnNumRank).localTee(r).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_().localGet(0).return_().end();
        c.localGet(r).localGet(1).op(WasmInstr.SimpleOp.I32_EQ).if_().localGet(0).return_().end();
        c.localGet(1).i32Const(5).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(0).refAsNonNull().call(fnNumAsDouble).call(fnBoxDouble).return_().end();
        c.localGet(1).i32Const(4).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(0).refAsNonNull().call(fnNumAsDouble).op(WasmInstr.SimpleOp.F32_DEMOTE_F64).call(fnBoxFloat).return_().end();
        c.localGet(1).i32Const(3).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(0).refAsNonNull().call(fnNumAsLong).call(fnBoxLong).return_().end();
        c.localGet(1).i32Const(2).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(0).refAsNonNull().call(fnNumAsLong).op(WasmInstr.SimpleOp.I32_WRAP_I64).call(fnBoxInt).return_().end();
        c.localGet(1).i32Const(1).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(0).refAsNonNull().call(fnNumAsLong).op(WasmInstr.SimpleOp.I32_WRAP_I64).call(fnBoxShort).return_().end();
        c.localGet(0);
    }

    // ---------------------------------------------------------------- equals / same / hash / compare / format

    private void emitEquals(WasmModule.FuncDef fn, int fnArrEq, int fnColEq) {
        WasmCodeBuilder c = fn.code();
        int ra = fn.addLocal("ra", WasmValType.I32, 2);
        int rb = fn.addLocal("rb", WasmValType.I32, 2);
        int eqFn = fn.addLocal("eqFn", WasmValType.refNull(ts.eqFnSigType), 2);

        // Both null or identical reference
        c.localGet(0).localGet(1).refEq().if_().i32Const(1).return_().end();
        c.localGet(0).refIsNull().localGet(1).refIsNull().op(WasmInstr.SimpleOp.I32_OR)
                .if_().i32Const(0).return_().end();

        // Numbers (cross-type: 1 == 1.0, NaN == NaN)
        c.localGet(0).call(fnNumRank).localTee(ra).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(1).call(fnNumRank).localTee(rb).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                // Both integral?
                .localGet(ra).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S)
                .localGet(rb).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S)
                .op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(0).refAsNonNull().call(fnNumAsLong)
                .localGet(1).refAsNonNull().call(fnNumAsLong)
                .op(WasmInstr.SimpleOp.I64_EQ).return_()
                .end()
                .localGet(0).refAsNonNull().call(fnNumAsDouble)
                .localGet(1).refAsNonNull().call(fnNumAsDouble)
                .call(fnF64Compare).op(WasmInstr.SimpleOp.I32_EQZ).return_()
                .end()
                .i32Const(0).return_()
                .end();

        // Strings
        c.localGet(0).refTest(ts.stringType).if_()
                .localGet(1).refTest(ts.stringType).if_()
                .localGet(0).refCast(ts.stringType)
                .localGet(1).refCast(ts.stringType)
                .call(fnStrEq).return_()
                .end()
                .i32Const(0).return_()
                .end();

        // Bool
        c.localGet(0).refTest(ts.boxBoolType).if_()
                .localGet(1).refTest(ts.boxBoolType).if_()
                .localGet(0).refCast(ts.boxBoolType).structGet(ts.boxBoolType, 0)
                .localGet(1).refCast(ts.boxBoolType).structGet(ts.boxBoolType, 0)
                .op(WasmInstr.SimpleOp.I32_EQ).return_()
                .end()
                .i32Const(0).return_()
                .end();

        // Char
        c.localGet(0).refTest(ts.boxCharType).if_()
                .localGet(1).refTest(ts.boxCharType).if_()
                .localGet(0).refCast(ts.boxCharType).structGet(ts.boxCharType, 0)
                .localGet(1).refCast(ts.boxCharType).structGet(ts.boxCharType, 0)
                .op(WasmInstr.SimpleOp.I32_EQ).return_()
                .end()
                .i32Const(0).return_()
                .end();

        // Arrays (deep structural equality)
        if (fnArrEq >= 0) {
            c.localGet(0).refTest(ts.arrayBaseType).if_()
                    .localGet(1).refTest(ts.arrayBaseType).if_()
                    .localGet(0).refCast(ts.arrayBaseType)
                    .localGet(1).refCast(ts.arrayBaseType)
                    .call(fnArrEq).return_()
                    .end()
                    .i32Const(0).return_()
                    .end();
        }

        // Core collections ($List, $Set, $Entry, $Map)
        if (fnColEq >= 0) {
            c.localGet(0).refTest(ts.listType)
                    .localGet(0).refTest(ts.setType).op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0).refTest(ts.entryType).op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0).refTest(ts.mapType).op(WasmInstr.SimpleOp.I32_OR)
                    .if_()
                    .localGet(0).localGet(1).call(fnColEq).return_()
                    .end();
        }

        // Babble $Object custom/record equalsFn
        c.localGet(0).refTest(ts.objectType).if_()
                .localGet(0).refCast(ts.objectType).structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 6).localTee(eqFn)
                .refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(0).refCast(ts.objectType)
                .localGet(1)
                .localGet(eqFn).refAsNonNull().callRef(ts.eqFnSigType).return_()
                .end()
                .end();

        c.i32Const(0);
    }

    private void emitSame(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).localGet(1).refEq().if_().i32Const(1).return_().end();
        c.localGet(0).refIsNull().localGet(1).refIsNull().op(WasmInstr.SimpleOp.I32_OR)
                .if_().i32Const(0).return_().end();
        // Plain values (numbers, bool, char, string) compare by value
        c.localGet(0).call(fnNumRank).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S)
                .localGet(0).refTest(ts.boxBoolType).op(WasmInstr.SimpleOp.I32_OR)
                .localGet(0).refTest(ts.boxCharType).op(WasmInstr.SimpleOp.I32_OR)
                .localGet(0).refTest(ts.stringType).op(WasmInstr.SimpleOp.I32_OR)
                .if_()
                .localGet(1).call(fnNumRank).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S)
                .localGet(1).refTest(ts.boxBoolType).op(WasmInstr.SimpleOp.I32_OR)
                .localGet(1).refTest(ts.boxCharType).op(WasmInstr.SimpleOp.I32_OR)
                .localGet(1).refTest(ts.stringType).op(WasmInstr.SimpleOp.I32_OR)
                .if_()
                .localGet(0).localGet(1).call(fnEquals).return_()
                .end()
                .end();
        c.i32Const(0);
    }

    private void emitHashCode(WasmModule.FuncDef fn, int fnArrHash, int fnColHash) {
        WasmCodeBuilder c = fn.code();
        int r = fn.addLocal("r", WasmValType.I32, 1);
        int l = fn.addLocal("l", WasmValType.I64, 1);
        int d = fn.addLocal("d", WasmValType.F64, 1);
        int hFn = fn.addLocal("hFn", WasmValType.refNull(ts.hashFnSigType), 1);

        c.localGet(0).refIsNull().if_().i32Const(0).return_().end();
        c.localGet(0).refTest(ts.stringType).if_()
                .localGet(0).refCast(ts.stringType).call(fnStrHash).return_().end();
        c.localGet(0).call(fnNumRank).localTee(r).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(r).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S).if_()
                .localGet(0).refAsNonNull().call(fnNumAsLong).localTee(l)
                .localGet(l).i64Const(32L).op(WasmInstr.SimpleOp.I64_SHR_U).op(WasmInstr.SimpleOp.I64_XOR)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64).return_()
                .end()
                // Floating point: check if integral value in long range
                .localGet(0).refAsNonNull().call(fnNumAsDouble).localTee(d)
                .op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F64_S).localTee(l)
                .op(WasmInstr.SimpleOp.F64_CONVERT_I64_S).localGet(d).op(WasmInstr.SimpleOp.F64_EQ)
                .if_()
                .localGet(l).localGet(l).i64Const(32L).op(WasmInstr.SimpleOp.I64_SHR_U).op(WasmInstr.SimpleOp.I64_XOR)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64).return_()
                .end()
                .localGet(d).op(WasmInstr.SimpleOp.I64_REINTERPRET_F64).localTee(l)
                .localGet(l).i64Const(32L).op(WasmInstr.SimpleOp.I64_SHR_U).op(WasmInstr.SimpleOp.I64_XOR)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64).return_()
                .end();
        c.localGet(0).refTest(ts.boxBoolType).if_()
                .i32Const(1231).i32Const(1237)
                .localGet(0).refCast(ts.boxBoolType).structGet(ts.boxBoolType, 0).select().return_().end();
        c.localGet(0).refTest(ts.boxCharType).if_()
                .localGet(0).refCast(ts.boxCharType).structGet(ts.boxCharType, 0).return_().end();
        if (fnArrHash >= 0) {
            c.localGet(0).refTest(ts.arrayBaseType).if_()
                    .localGet(0).refCast(ts.arrayBaseType).call(fnArrHash).return_().end();
        }
        if (fnColHash >= 0) {
            c.localGet(0).refTest(ts.listType)
                    .localGet(0).refTest(ts.setType).op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0).refTest(ts.entryType).op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0).refTest(ts.mapType).op(WasmInstr.SimpleOp.I32_OR)
                    .if_()
                    .localGet(0).call(fnColHash).return_()
                    .end();
        }
        c.localGet(0).refTest(ts.objectType).if_()
                .localGet(0).refCast(ts.objectType).structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 7).localTee(hFn)
                .refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(0).refCast(ts.objectType)
                .localGet(hFn).refAsNonNull().callRef(ts.hashFnSigType).return_()
                .end()
                .end();
        c.i32Const(0);
    }

    private void emitCompare(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ra = fn.addLocal("ra", WasmValType.I32, 5);
        int rb = fn.addLocal("rb", WasmValType.I32, 5);

        c.localGet(0).call(fnNumRank).localTee(ra).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(1).call(fnNumRank).localTee(rb).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(ra).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S)
                .localGet(rb).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S)
                .op(WasmInstr.SimpleOp.I32_AND).if_()
                .localGet(0).refAsNonNull().call(fnNumAsLong)
                .localGet(1).refAsNonNull().call(fnNumAsLong)
                .op(WasmInstr.SimpleOp.I64_LT_S).if_().i32Const(-1).return_().end()
                .localGet(0).refAsNonNull().call(fnNumAsLong)
                .localGet(1).refAsNonNull().call(fnNumAsLong)
                .op(WasmInstr.SimpleOp.I64_GT_S).if_().i32Const(1).return_().end()
                .i32Const(0).return_()
                .end()
                .localGet(0).refAsNonNull().call(fnNumAsDouble)
                .localGet(1).refAsNonNull().call(fnNumAsDouble)
                .call(fnF64Compare).return_()
                .end()
                .end();

        c.localGet(0).refTest(ts.stringType).if_()
                .localGet(1).refTest(ts.stringType).if_()
                .localGet(0).refCast(ts.stringType)
                .localGet(1).refCast(ts.stringType)
                .call(fnStrCmp).return_()
                .end().end();

        c.localGet(0).refTest(ts.boxCharType).if_()
                .localGet(1).refTest(ts.boxCharType).if_()
                .localGet(0).refCast(ts.boxCharType).structGet(ts.boxCharType, 0)
                .localGet(1).refCast(ts.boxCharType).structGet(ts.boxCharType, 0)
                .op(WasmInstr.SimpleOp.I32_SUB).return_()
                .end().end();

        c.localGet(0).refTest(ts.boxBoolType).if_()
                .localGet(1).refTest(ts.boxBoolType).if_()
                .localGet(0).refCast(ts.boxBoolType).structGet(ts.boxBoolType, 0)
                .localGet(1).refCast(ts.boxBoolType).structGet(ts.boxBoolType, 0)
                .op(WasmInstr.SimpleOp.I32_SUB).return_()
                .end().end();

        emitStringLit(c, "Not comparable : ");
        c.localGet(2).call(fnStrConcat);
        emitStringLit(c, " and ");
        c.call(fnStrConcat).localGet(3).call(fnStrConcat)
                .localGet(4).call(fnThrowMsg).unreachable();
    }

    private void emitFormat(WasmModule.FuncDef fn, int fnArrFmt, int fnColFmt) {
        WasmCodeBuilder c = fn.code();
        int toStrFn = fn.addLocal("toStrFn", WasmValType.refNull(ts.toStrFnSigType), 1);

        c.localGet(0).refIsNull().if_();
        emitStringGlobal(c, sNull);
        c.return_().end();

        c.localGet(0).refTest(ts.stringType).if_()
                .localGet(0).refCast(ts.stringType).return_().end();
        c.localGet(0).refTest(ts.boxIntType).if_()
                .localGet(0).refCast(ts.boxIntType).structGet(ts.boxIntType, 0).call(fnIntToStr).return_().end();
        c.localGet(0).refTest(ts.boxDoubleType).if_()
                .localGet(0).refCast(ts.boxDoubleType).structGet(ts.boxDoubleType, 0).call(fnDoubleToStr).return_().end();
        c.localGet(0).refTest(ts.boxBoolType).if_()
                .localGet(0).refCast(ts.boxBoolType).structGet(ts.boxBoolType, 0)
                .if_(WasmValType.ref(ts.stringType));
        emitStringGlobal(c, sTrue);
        c.else_();
        emitStringGlobal(c, sFalse);
        c.end().return_().end();
        c.localGet(0).refTest(ts.boxLongType).if_()
                .localGet(0).refCast(ts.boxLongType).structGet(ts.boxLongType, 0).call(fnLongToStr).return_().end();
        c.localGet(0).refTest(ts.boxFloatType).if_()
                .localGet(0).refCast(ts.boxFloatType).structGet(ts.boxFloatType, 0).call(fnFloatToStr).return_().end();
        c.localGet(0).refTest(ts.boxShortType).if_()
                .localGet(0).refCast(ts.boxShortType).structGet(ts.boxShortType, 0).call(fnIntToStr).return_().end();
        c.localGet(0).refTest(ts.boxByteType).if_()
                .localGet(0).refCast(ts.boxByteType).structGet(ts.boxByteType, 0).call(fnIntToStr).return_().end();
        c.localGet(0).refTest(ts.boxCharType).if_()
                .localGet(0).refCast(ts.boxCharType).structGet(ts.boxCharType, 0).call(fnCharToStr).return_().end();
        c.localGet(0).refTest(ts.babbleExnType).if_();
        int msg = fn.addLocal("msg", WasmValType.refNull(ts.stringType), 1);
        c.localGet(0).refCast(ts.babbleExnType).structGet(ts.babbleExnType, 0).localTee(msg)
                .refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(msg).refAsNonNull().return_()
                .end();
        emitStringLit(c, "exception");
        c.return_().end();

        if (fnArrFmt >= 0) {
            c.localGet(0).refTest(ts.arrayBaseType).if_()
                    .localGet(0).refCast(ts.arrayBaseType).call(fnArrFmt).return_().end();
        }
        if (fnColFmt >= 0) {
            c.localGet(0).refTest(ts.listType)
                    .localGet(0).refTest(ts.setType).op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0).refTest(ts.entryType).op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0).refTest(ts.mapType).op(WasmInstr.SimpleOp.I32_OR)
                    .if_()
                    .localGet(0).call(fnColFmt).return_()
                    .end();
        }

        c.localGet(0).refTest(ts.objectType).if_()
                .localGet(0).refCast(ts.objectType).structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 8).localTee(toStrFn)
                .refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(0).refCast(ts.objectType)
                .localGet(toStrFn).refAsNonNull().callRef(ts.toStrFnSigType).return_()
                .end()
                .localGet(0).refCast(ts.objectType).structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 1).return_()
                .end();

        c.localGet(0).refTest(ts.moduleValType).if_();
        emitStringLit(c, "module ");
        c.localGet(0).refCast(ts.moduleValType).structGet(ts.moduleValType, 1)
                .call(fnStrConcat).return_().end();

        c.localGet(0).refTest(ts.classValType).if_();
        emitStringLit(c, "class ");
        c.localGet(0).refCast(ts.classValType).structGet(ts.classValType, 1)
                .call(fnStrConcat).return_().end();

        c.localGet(0).refTest(ts.ifaceValType).if_();
        emitStringLit(c, "interface ");
        c.localGet(0).refCast(ts.ifaceValType).structGet(ts.ifaceValType, 1)
                .call(fnStrConcat).return_().end();

        c.localGet(0).refTest(ts.overloadSetType).if_()
                .localGet(0).refCast(ts.overloadSetType).structGet(ts.overloadSetType, 1).return_().end();

        c.localGet(0).refTest(ts.fnBaseType).if_();
        int fnName = fn.addLocal("fnName", WasmValType.refNull(ts.stringType), 1);
        c.localGet(0).refCast(ts.fnBaseType).structGet(ts.fnBaseType, 1).localTee(fnName).refAsNonNull();
        emitStringLit(c, "<fn>");
        c.call(fnStrEq).if_();
        emitStringLit(c, "function");
        c.return_().end();
        emitStringLit(c, "function ");
        c.localGet(fnName).refAsNonNull().call(fnStrConcat).return_().end();

        emitStringLit(c, "<object>");
    }

    // ---------------------------------------------------------------- exceptions, assert, print

    private void emitMakeExn(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0)
                .localGet(1)
                .refNull(ts.anyArrType)
                .i32Const(0)
                .localGet(2)
                .structNew(ts.babbleExnType);
    }

    private void emitThrowMsg(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0)
                .refNull(ts.anyType)
                .localGet(1)
                .call(fnMakeExn)
                .localGet(1)
                .throw_(babbleExnTag);
    }

    private void emitAddSuppressed(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int exn = fn.addLocal("exn", WasmValType.refNull(ts.babbleExnType), 2);
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.anyArrType), 2);
        int cnt = fn.addLocal("cnt", WasmValType.I32, 2);
        int newArr = fn.addLocal("newArr", WasmValType.refNull(ts.anyArrType), 2);

        c.localGet(0).refTest(ts.babbleExnType).op(WasmInstr.SimpleOp.I32_EQZ).if_().return_().end();
        c.localGet(0).localGet(1).refEq().if_().return_().end();
        c.localGet(0).refCast(ts.babbleExnType).localSet(exn);
        c.localGet(exn).structGet(ts.babbleExnType, 2).localSet(arr);
        c.localGet(exn).structGet(ts.babbleExnType, 3).localSet(cnt);
        c.localGet(arr).refIsNull().if_()
                .i32Const(4).arrayNewDefault(ts.anyArrType).localSet(arr)
                .localGet(exn).localGet(arr).structSet(ts.babbleExnType, 2)
                .end();
        c.localGet(cnt).localGet(arr).refAsNonNull().arrayLen().op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(cnt).i32Const(2).op(WasmInstr.SimpleOp.I32_MUL).arrayNewDefault(ts.anyArrType).localTee(newArr)
                .i32Const(0).localGet(arr).refAsNonNull().i32Const(0).localGet(cnt)
                .arrayCopy(ts.anyArrType, ts.anyArrType)
                .localGet(exn).localGet(newArr).structSet(ts.babbleExnType, 2)
                .localGet(newArr).localSet(arr)
                .end();
        c.localGet(arr).refAsNonNull().localGet(cnt).localGet(1).arraySet(ts.anyArrType);
        c.localGet(exn).localGet(cnt).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).structSet(ts.babbleExnType, 3);
    }

    private void emitAssert(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(ts.boxBoolType).op(WasmInstr.SimpleOp.I32_EQZ).if_();
        emitStringLit(c, "Expression ");
        c.localGet(2).call(fnStrConcat);
        emitStringLit(c, " : asserts don't rely on truth values, please make sure that the test has a boolean result");
        c.call(fnStrConcat).localGet(3).call(fnThrowMsg).unreachable().end();

        c.localGet(0).refCast(ts.boxBoolType).structGet(ts.boxBoolType, 0).if_()
                .i32Const(1).return_()
                .end();

        // Failed assertion
        c.localGet(1).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ)
                .localGet(1).globalGet(gMissing).refEq().op(WasmInstr.SimpleOp.I32_EQZ)
                .op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(1).call(fnFormat).localGet(3).call(fnThrowMsg).unreachable()
                .end();

        emitStringLit(c, "Assertion failed at line ");
        c.localGet(3).call(fnIntToStr).call(fnStrConcat);
        emitStringLit(c, " : ");
        c.call(fnStrConcat).localGet(2).call(fnStrConcat)
                .localGet(3).call(fnThrowMsg).unreachable();
    }

    private void emitPrint(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int len = fn.addLocal("len", WasmValType.I32, 4);
        int i = fn.addLocal("i", WasmValType.I32, 4);
        int acc = fn.addLocal("acc", WasmValType.refNull(ts.stringType), 4);
        int targetStream = fn.addLocal("stream", WasmValType.I32, 4); // 0=stdout, 1=stderr

        c.globalGet(gEmptyStr).localSet(acc);
        c.localGet(0).arrayLen().localSet(len);
        c.loop()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(acc).refAsNonNull()
                .localGet(0).localGet(i).arrayGet(ts.anyArrType).call(fnFormat)
                .call(fnStrConcat).localSet(acc)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1)
                .end()
                .end();
        c.localGet(2).if_()
                .localGet(acc).refAsNonNull();
        emitStringGlobal(c, sNewline);
        c.call(fnStrConcat).localSet(acc)
                .end();

        // Check custom $Object with `append(s: string)` method first (works on BOTH wasm-js and wasm-bare, BIP025-D12!)
        int getMemFn = fn.addLocal("getMemFn", WasmValType.refNull(ts.getMemFnSigType), 4);
        int appendMethod = fn.addLocal("appendMethod", ts.anyRef(), 4);
        c.localGet(1).refTest(ts.objectType).if_()
                .localGet(1).refCast(ts.objectType).structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 9).localTee(getMemFn)
                .refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(1).refCast(ts.objectType);
        emitStringLit(c, "append");
        c.localGet(getMemFn).refAsNonNull().callRef(ts.getMemFnSigType).localTee(appendMethod)
                .refTest(ts.fnBaseType).if_()
                .localGet(appendMethod).refCast(ts.fnBaseType)
                .localGet(acc).arrayNewFixed(ts.anyArrType, 1)
                .refNull(ts.anyArrType)
                .localGet(appendMethod).refCast(ts.fnBaseType).structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType).drop().return_()
                .end()
                .end()
                .end();

        if (target == CompilationTarget.WASM_BARE) {
            emitStringLit(c, "Not supported on target wasm-bare : print");
            c.localGet(3).call(fnThrowMsg).unreachable();
            return;
        }

        // Host stdout / stderr (null / MISSING -> STDOUT; $HostStream -> stdout/stderr)
        c.localGet(1).refIsNull()
                .localGet(1).globalGet(gMissing).refEq().op(WasmInstr.SimpleOp.I32_OR)
                .if_()
                .localGet(acc).refAsNonNull().call(fnToExternStr).call(impWriteStdout).return_()
                .end();
        c.localGet(1).refTest(ts.hostStreamType).if_()
                .localGet(1).refCast(ts.hostStreamType).structGet(ts.hostStreamType, 0).localSet(targetStream)
                .localGet(targetStream).if_()
                .localGet(acc).refAsNonNull().call(fnToExternStr).call(impWriteStderr)
                .else_()
                .localGet(acc).refAsNonNull().call(fnToExternStr).call(impWriteStdout)
                .end()
                .return_()
                .end();

        emitStringLit(c, "print to: expects a Writer or PrintStream");
        c.localGet(3).call(fnThrowMsg).unreachable();
    }
}
