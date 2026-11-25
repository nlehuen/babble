package org.babblelang.wasm;

import org.babblelang.analysis.CompilationTarget;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates dynamic operator helpers, scalar conversions ({@code byte()}..{@code string()}),
 * {@code is <builtin>}, {@code classof}, and first-class built-in function singletons
 * ({@code BIP025-P1}).
 */
public final class WasmRuntimeOpsGen {
    public static final int BUILTIN_BYTE = 0;
    public static final int BUILTIN_SHORT = 1;
    public static final int BUILTIN_INT = 2;
    public static final int BUILTIN_LONG = 3;
    public static final int BUILTIN_FLOAT = 4;
    public static final int BUILTIN_DOUBLE = 5;
    public static final int BUILTIN_BOOL = 6;
    public static final int BUILTIN_CHAR = 7;
    public static final int BUILTIN_STRING = 8;
    public static final int BUILTIN_ANY = 9;
    public static final int BUILTIN_EXCEPTION = 10;
    public static final int BUILTIN_LIST = 11;
    public static final int BUILTIN_SET = 12;
    public static final int BUILTIN_SORTEDSET = 13;
    public static final int BUILTIN_MAP = 14;
    public static final int BUILTIN_SORTEDMAP = 15;
    public static final int BUILTIN_ENTRY = 16;

    private final WasmModule mod;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final CompilationTarget target;

    public final int fnDynAdd;
    public final int fnDynSub;
    public final int fnDynMul;
    public final int fnDynDiv;
    public final int fnDynNeg;

    public final int fnConvByte;
    public final int fnConvShort;
    public final int fnConvInt;
    public final int fnConvLong;
    public final int fnConvFloat;
    public final int fnConvDouble;
    public final int fnConvBool;
    public final int fnConvChar;

    public final int fnIsBuiltin;
    public final int fnClassof;

    // Built-in generic call dispatchers (for first-class references to print, int, exception, etc.)
    private final Map<String, Integer> builtinFnGlobals = new LinkedHashMap<>();
    private final WasmModule.FuncDef fnInitBuiltinsDef;
    public final int fnInitBuiltins;

    public WasmRuntimeOpsGen(WasmModule mod, WasmTypeSystem ts, WasmRuntimeGen rt, CompilationTarget target) {
        this.mod = mod;
        this.ts = ts;
        this.rt = rt;
        this.target = target;

        WasmValType refAnyNull = ts.anyRef();
        WasmValType refStr = WasmValType.ref(ts.stringType);
        int tBinOp = ts.internFuncType(List.of(refAnyNull, refAnyNull, refStr, refStr, WasmValType.I32), List.of(refAnyNull));
        int tUnOp = ts.internFuncType(List.of(refAnyNull, refStr, WasmValType.I32), List.of(refAnyNull));
        int tConvI32 = ts.internFuncType(List.of(refAnyNull, refStr, WasmValType.I32), List.of(WasmValType.I32));
        int tConvI64 = ts.internFuncType(List.of(refAnyNull, refStr, WasmValType.I32), List.of(WasmValType.I64));
        int tConvF32 = ts.internFuncType(List.of(refAnyNull, refStr, WasmValType.I32), List.of(WasmValType.F32));
        int tConvF64 = ts.internFuncType(List.of(refAnyNull, refStr, WasmValType.I32), List.of(WasmValType.F64));

        WasmModule.FuncDef dAdd = mod.addFunction("dyn_add", tBinOp, List.of("a", "b", "lt", "rt", "line"));
        WasmModule.FuncDef dSub = mod.addFunction("dyn_sub", tBinOp, List.of("a", "b", "lt", "rt", "line"));
        WasmModule.FuncDef dMul = mod.addFunction("dyn_mul", tBinOp, List.of("a", "b", "lt", "rt", "line"));
        WasmModule.FuncDef dDiv = mod.addFunction("dyn_div", tBinOp, List.of("a", "b", "lt", "rt", "line"));
        WasmModule.FuncDef dNeg = mod.addFunction("dyn_neg", tUnOp, List.of("a", "t", "line"));

        WasmModule.FuncDef dCByte = mod.addFunction("conv_byte", tConvI32, List.of("v", "t", "line"));
        WasmModule.FuncDef dCShort = mod.addFunction("conv_short", tConvI32, List.of("v", "t", "line"));
        WasmModule.FuncDef dCInt = mod.addFunction("conv_int", tConvI32, List.of("v", "t", "line"));
        WasmModule.FuncDef dCLong = mod.addFunction("conv_long", tConvI64, List.of("v", "t", "line"));
        WasmModule.FuncDef dCFloat = mod.addFunction("conv_float", tConvF32, List.of("v", "t", "line"));
        WasmModule.FuncDef dCDouble = mod.addFunction("conv_double", tConvF64, List.of("v", "t", "line"));
        WasmModule.FuncDef dCBool = mod.addFunction("conv_bool", tConvI32, List.of("v", "t", "line"));
        WasmModule.FuncDef dCChar = mod.addFunction("conv_char", tConvI32, List.of("v", "t", "line"));

        WasmModule.FuncDef dIsBuiltin = mod.addFunction("is_builtin",
                ts.internFuncType(List.of(refAnyNull, WasmValType.I32), List.of(WasmValType.I32)), List.of("v", "kind"));
        WasmModule.FuncDef dClassof = mod.addFunction("classof",
                ts.internFuncType(List.of(refAnyNull), List.of(refAnyNull)), List.of("v"));

        this.fnDynAdd = dAdd.funcIndex();
        this.fnDynSub = dSub.funcIndex();
        this.fnDynMul = dMul.funcIndex();
        this.fnDynDiv = dDiv.funcIndex();
        this.fnDynNeg = dNeg.funcIndex();

        this.fnConvByte = dCByte.funcIndex();
        this.fnConvShort = dCShort.funcIndex();
        this.fnConvInt = dCInt.funcIndex();
        this.fnConvLong = dCLong.funcIndex();
        this.fnConvFloat = dCFloat.funcIndex();
        this.fnConvDouble = dCDouble.funcIndex();
        this.fnConvBool = dCBool.funcIndex();
        this.fnConvChar = dCChar.funcIndex();

        this.fnIsBuiltin = dIsBuiltin.funcIndex();
        this.fnClassof = dClassof.funcIndex();

        this.fnInitBuiltinsDef = mod.addFunction("init_builtins", ts.internFuncType(List.of(), List.of()));
        this.fnInitBuiltins = fnInitBuiltinsDef.funcIndex();

        emitDynBinary(dAdd, "+", "plus");
        emitDynBinary(dSub, "-", "minus");
        emitDynBinary(dMul, "*", "times");
        emitDynBinary(dDiv, "/", "div");
        emitDynNeg(dNeg);

        emitConvIntegral(dCByte, "byte", -128L, 127L, -129.0, 128.0);
        emitConvIntegral(dCShort, "short", -32768L, 32767L, -32769.0, 32768.0);
        emitConvInt(dCInt);
        emitConvLong(dCLong);
        emitConvFloat(dCFloat);
        emitConvDouble(dCDouble);
        emitConvBool(dCBool);
        emitConvChar(dCChar);

        emitIsBuiltin(dIsBuiltin);
        emitClassof(dClassof);
    }

    public static int builtinId(String name) {
        if (name == null) {
            return -1;
        }
        int bar = name.indexOf('|');
        String kind = bar >= 0 ? name.substring(0, bar) : name;
        if (kind.endsWith("?")) {
            kind = kind.substring(0, kind.length() - 1);
        }
        return switch (kind) {
            case "byte" -> BUILTIN_BYTE;
            case "short" -> BUILTIN_SHORT;
            case "int" -> BUILTIN_INT;
            case "long" -> BUILTIN_LONG;
            case "float" -> BUILTIN_FLOAT;
            case "double" -> BUILTIN_DOUBLE;
            case "bool" -> BUILTIN_BOOL;
            case "char" -> BUILTIN_CHAR;
            case "string" -> BUILTIN_STRING;
            case "any" -> BUILTIN_ANY;
            case "exception" -> BUILTIN_EXCEPTION;
            case "list" -> BUILTIN_LIST;
            case "set" -> BUILTIN_SET;
            case "sortedset" -> BUILTIN_SORTEDSET;
            case "map" -> BUILTIN_MAP;
            case "sortedmap" -> BUILTIN_SORTEDMAP;
            case "entry" -> BUILTIN_ENTRY;
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- dynamic operators

    private void emitDynBinary(WasmModule.FuncDef fn, String op, String opMethod) {
        WasmCodeBuilder c = fn.code();
        int ra = fn.addLocal("ra", WasmValType.I32, 5);
        int rb = fn.addLocal("rb", WasmValType.I32, 5);
        int opFn = fn.addLocal("opFn", WasmValType.refNull(ts.opFnSigType), 5);

        // 1. Custom operator method on Babble $Object
        c.localGet(0).refTest(ts.objectType).if_()
                .localGet(0).refCast(ts.objectType).structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 11).localTee(opFn)
                .refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(0).refCast(ts.objectType);
        rt.emitStringLit(c, opMethod);
        c.localGet(1).localGet(opFn).refAsNonNull().callRef(ts.opFnSigType).return_()
                .end().end();

        // 2. String concatenation for '+'
        if ("+".equals(op)) {
            c.localGet(0).refTest(ts.stringType).if_()
                    .localGet(0).refCast(ts.stringType)
                    .localGet(1).call(rt.fnFormat)
                    .call(rt.fnStrConcat).return_()
                    .end();
        }

        // 3. Numeric check
        c.localGet(0).call(rt.fnNumRank).localTee(ra).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_();
        rt.emitStringLit(c, "Not a number : ");
        c.localGet(2).call(rt.fnStrConcat).localGet(4).call(rt.fnThrowMsg).unreachable().end();

        c.localGet(1).call(rt.fnNumRank).localTee(rb).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_();
        rt.emitStringLit(c, "Not a number : ");
        c.localGet(3).call(rt.fnStrConcat).localGet(4).call(rt.fnThrowMsg).unreachable().end();

        if ("/".equals(op)) {
            // float if at least one is float and neither is double (5) or long (3)
            c.localGet(ra).i32Const(4).op(WasmInstr.SimpleOp.I32_EQ)
                    .localGet(rb).i32Const(4).op(WasmInstr.SimpleOp.I32_EQ).op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(ra).i32Const(3).op(WasmInstr.SimpleOp.I32_NE).op(WasmInstr.SimpleOp.I32_AND)
                    .localGet(rb).i32Const(3).op(WasmInstr.SimpleOp.I32_NE).op(WasmInstr.SimpleOp.I32_AND)
                    .localGet(ra).i32Const(5).op(WasmInstr.SimpleOp.I32_NE).op(WasmInstr.SimpleOp.I32_AND)
                    .localGet(rb).i32Const(5).op(WasmInstr.SimpleOp.I32_NE).op(WasmInstr.SimpleOp.I32_AND)
                    .if_()
                    .localGet(0).refAsNonNull().call(rt.fnNumAsDouble).op(WasmInstr.SimpleOp.F32_DEMOTE_F64)
                    .localGet(1).refAsNonNull().call(rt.fnNumAsDouble).op(WasmInstr.SimpleOp.F32_DEMOTE_F64)
                    .op(WasmInstr.SimpleOp.F32_DIV).call(rt.fnBoxFloat).return_()
                    .end();
            c.localGet(0).refAsNonNull().call(rt.fnNumAsDouble)
                    .localGet(1).refAsNonNull().call(rt.fnNumAsDouble)
                    .op(WasmInstr.SimpleOp.F64_DIV).call(rt.fnBoxDouble);
            return;
        }

        WasmInstr.SimpleOp i32Op = switch (op) {
            case "+" -> WasmInstr.SimpleOp.I32_ADD;
            case "-" -> WasmInstr.SimpleOp.I32_SUB;
            case "*" -> WasmInstr.SimpleOp.I32_MUL;
            default -> throw new AssertionError();
        };
        WasmInstr.SimpleOp i64Op = switch (op) {
            case "+" -> WasmInstr.SimpleOp.I64_ADD;
            case "-" -> WasmInstr.SimpleOp.I64_SUB;
            case "*" -> WasmInstr.SimpleOp.I64_MUL;
            default -> throw new AssertionError();
        };
        WasmInstr.SimpleOp f32Op = switch (op) {
            case "+" -> WasmInstr.SimpleOp.F32_ADD;
            case "-" -> WasmInstr.SimpleOp.F32_SUB;
            case "*" -> WasmInstr.SimpleOp.F32_MUL;
            default -> throw new AssertionError();
        };
        WasmInstr.SimpleOp f64Op = switch (op) {
            case "+" -> WasmInstr.SimpleOp.F64_ADD;
            case "-" -> WasmInstr.SimpleOp.F64_SUB;
            case "*" -> WasmInstr.SimpleOp.F64_MUL;
            default -> throw new AssertionError();
        };

        // Both <= int (2) -> int
        c.localGet(ra).i32Const(2).op(WasmInstr.SimpleOp.I32_LE_S)
                .localGet(rb).i32Const(2).op(WasmInstr.SimpleOp.I32_LE_S).op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(0).refAsNonNull().call(rt.fnNumAsLong).op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .localGet(1).refAsNonNull().call(rt.fnNumAsLong).op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .op(i32Op).call(rt.fnBoxInt).return_()
                .end();
        // Both <= long (3) -> long
        c.localGet(ra).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S)
                .localGet(rb).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S).op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(0).refAsNonNull().call(rt.fnNumAsLong)
                .localGet(1).refAsNonNull().call(rt.fnNumAsLong)
                .op(i64Op).call(rt.fnBoxLong).return_()
                .end();
        // Both <= float (4) -> float
        c.localGet(ra).i32Const(4).op(WasmInstr.SimpleOp.I32_LE_S)
                .localGet(rb).i32Const(4).op(WasmInstr.SimpleOp.I32_LE_S).op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(0).refAsNonNull().call(rt.fnNumAsDouble).op(WasmInstr.SimpleOp.F32_DEMOTE_F64)
                .localGet(1).refAsNonNull().call(rt.fnNumAsDouble).op(WasmInstr.SimpleOp.F32_DEMOTE_F64)
                .op(f32Op).call(rt.fnBoxFloat).return_()
                .end();
        // Double
        c.localGet(0).refAsNonNull().call(rt.fnNumAsDouble)
                .localGet(1).refAsNonNull().call(rt.fnNumAsDouble)
                .op(f64Op).call(rt.fnBoxDouble);
    }

    private void emitDynNeg(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int r = fn.addLocal("r", WasmValType.I32, 3);
        int opFn = fn.addLocal("opFn", WasmValType.refNull(ts.opFnSigType), 3);

        c.localGet(0).refTest(ts.objectType).if_()
                .localGet(0).refCast(ts.objectType).structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 11).localTee(opFn)
                .refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(0).refCast(ts.objectType);
        rt.emitStringLit(c, "neg");
        c.refNull(ts.anyType).localGet(opFn).refAsNonNull().callRef(ts.opFnSigType).return_()
                .end().end();

        c.localGet(0).call(rt.fnNumRank).localTee(r).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_();
        rt.emitStringLit(c, "Not a number : ");
        c.localGet(1).call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable().end();

        c.localGet(r).i32Const(2).op(WasmInstr.SimpleOp.I32_LE_S).if_()
                .i32Const(0).localGet(0).refAsNonNull().call(rt.fnNumAsLong).op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .op(WasmInstr.SimpleOp.I32_SUB).call(rt.fnBoxInt).return_().end();
        c.localGet(r).i32Const(3).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .i64Const(0L).localGet(0).refAsNonNull().call(rt.fnNumAsLong)
                .op(WasmInstr.SimpleOp.I64_SUB).call(rt.fnBoxLong).return_().end();
        c.localGet(r).i32Const(4).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(0).refAsNonNull().call(rt.fnNumAsDouble).op(WasmInstr.SimpleOp.F32_DEMOTE_F64)
                .op(WasmInstr.SimpleOp.F32_NEG).call(rt.fnBoxFloat).return_().end();
        c.localGet(0).refAsNonNull().call(rt.fnNumAsDouble).op(WasmInstr.SimpleOp.F64_NEG).call(rt.fnBoxDouble);
    }

    // ---------------------------------------------------------------- conversions (ConvertFunction parity)

    private void emitConvIntegral(WasmModule.FuncDef fn, String typeName, long minL, long maxL, double minExcl, double maxExcl) {
        WasmCodeBuilder c = fn.code();
        int r = fn.addLocal("r", WasmValType.I32, 3);
        int l = fn.addLocal("l", WasmValType.I64, 3);
        int st = fn.addLocal("st", WasmValType.I32, 3);
        int d = fn.addLocal("d", WasmValType.F64, 3);
        int ch = fn.addLocal("ch", WasmValType.I32, 3);
        int exactBox = "byte".equals(typeName) ? ts.boxByteType : ts.boxShortType;

        c.localGet(0).refTest(exactBox).if_()
                .localGet(0).refCast(exactBox).structGet(exactBox, 0).return_().end();
        c.localGet(0).refTest(ts.boxCharType).if_()
                .localGet(0).refCast(ts.boxCharType).structGet(ts.boxCharType, 0).localTee(ch)
                .i32Const((int) maxL).op(WasmInstr.SimpleOp.I32_GT_S).if_();
        emitOutOfRange(c, typeName, 0, 1, 2);
        c.end().localGet(ch).return_().end();

        c.localGet(0).refTest(ts.stringType).if_()
                .localGet(0).refCast(ts.stringType).call(rt.fnParseLong).localSet(st).localSet(l)
                .localGet(st).i32Const(0).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(l).i64Const(minL).op(WasmInstr.SimpleOp.I64_GE_S)
                .localGet(l).i64Const(maxL).op(WasmInstr.SimpleOp.I64_LE_S).op(WasmInstr.SimpleOp.I32_AND).if_()
                .localGet(l).op(WasmInstr.SimpleOp.I32_WRAP_I64).return_()
                .end()
                .i32Const(2).localSet(st)
                .end()
                .localGet(st).i32Const(2).op(WasmInstr.SimpleOp.I32_EQ).if_();
        rt.emitStringLit(c, "Out of the " + typeName + " range : \"");
        c.localGet(0).refCast(ts.stringType).call(rt.fnStrConcat);
        rt.emitStringLit(c, "\"");
        c.call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable().end();
        rt.emitStringLit(c, "Not a " + typeName + " : \"");
        c.localGet(0).refCast(ts.stringType).call(rt.fnStrConcat);
        rt.emitStringLit(c, "\"");
        c.call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable().end();

        c.localGet(0).call(rt.fnNumRank).localTee(r).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(r).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S).if_()
                .localGet(0).refAsNonNull().call(rt.fnNumAsLong).localTee(l)
                .i64Const(minL).op(WasmInstr.SimpleOp.I64_LT_S)
                .localGet(l).i64Const(maxL).op(WasmInstr.SimpleOp.I64_GT_S).op(WasmInstr.SimpleOp.I32_OR).if_();
        emitOutOfRange(c, typeName, 0, 1, 2);
        c.end().localGet(l).op(WasmInstr.SimpleOp.I32_WRAP_I64).return_().end()
                .localGet(0).refAsNonNull().call(rt.fnNumAsDouble).localTee(d)
                .localGet(d).op(WasmInstr.SimpleOp.F64_NE)
                .localGet(d).f64Const(maxExcl).op(WasmInstr.SimpleOp.F64_GE).op(WasmInstr.SimpleOp.I32_OR)
                .localGet(d).f64Const(minExcl).op(WasmInstr.SimpleOp.F64_LE).op(WasmInstr.SimpleOp.I32_OR).if_();
        emitOutOfRange(c, typeName, 0, 1, 2);
        c.end().localGet(d).op(WasmInstr.SimpleOp.I32_TRUNC_SAT_F64_S).return_().end();

        rt.emitStringLit(c, "Not a number : ");
        c.localGet(1).call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable();
    }

    private void emitConvInt(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int r = fn.addLocal("r", WasmValType.I32, 3);
        int l = fn.addLocal("l", WasmValType.I64, 3);
        int st = fn.addLocal("st", WasmValType.I32, 3);
        int d = fn.addLocal("d", WasmValType.F64, 3);

        c.localGet(0).refTest(ts.boxIntType).if_()
                .localGet(0).refCast(ts.boxIntType).structGet(ts.boxIntType, 0).return_().end();
        c.localGet(0).refTest(ts.boxCharType).if_()
                .localGet(0).refCast(ts.boxCharType).structGet(ts.boxCharType, 0).return_().end();
        c.localGet(0).refTest(ts.stringType).if_()
                .localGet(0).refCast(ts.stringType).call(rt.fnParseLong).localSet(st).localSet(l)
                .localGet(st).op(WasmInstr.SimpleOp.I32_EQZ)
                .localGet(l).i64Const(Integer.MIN_VALUE).op(WasmInstr.SimpleOp.I64_GE_S).op(WasmInstr.SimpleOp.I32_AND)
                .localGet(l).i64Const(Integer.MAX_VALUE).op(WasmInstr.SimpleOp.I64_LE_S).op(WasmInstr.SimpleOp.I32_AND)
                .if_().localGet(l).op(WasmInstr.SimpleOp.I32_WRAP_I64).return_().end();
        rt.emitStringLit(c, "Not an int : \"");
        c.localGet(0).refCast(ts.stringType).call(rt.fnStrConcat);
        rt.emitStringLit(c, "\"");
        c.call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable().end();

        c.localGet(0).call(rt.fnNumRank).localTee(r).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(r).i32Const(2).op(WasmInstr.SimpleOp.I32_LE_S).if_()
                .localGet(0).refAsNonNull().call(rt.fnNumAsLong).op(WasmInstr.SimpleOp.I32_WRAP_I64).return_().end()
                .localGet(r).i32Const(3).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(0).refAsNonNull().call(rt.fnNumAsLong).localTee(l)
                .i64Const(Integer.MIN_VALUE).op(WasmInstr.SimpleOp.I64_LT_S)
                .localGet(l).i64Const(Integer.MAX_VALUE).op(WasmInstr.SimpleOp.I64_GT_S).op(WasmInstr.SimpleOp.I32_OR).if_();
        emitOutOfRange(c, "int", 0, 1, 2);
        c.end().localGet(l).op(WasmInstr.SimpleOp.I32_WRAP_I64).return_().end()
                .localGet(0).refAsNonNull().call(rt.fnNumAsDouble).localTee(d)
                .localGet(d).op(WasmInstr.SimpleOp.F64_NE)
                .localGet(d).f64Const(2147483648.0).op(WasmInstr.SimpleOp.F64_GE).op(WasmInstr.SimpleOp.I32_OR)
                .localGet(d).f64Const(-2147483649.0).op(WasmInstr.SimpleOp.F64_LE).op(WasmInstr.SimpleOp.I32_OR).if_();
        emitOutOfRange(c, "int", 0, 1, 2);
        c.end().localGet(d).op(WasmInstr.SimpleOp.I32_TRUNC_SAT_F64_S).return_().end();

        rt.emitStringLit(c, "Not a number : ");
        c.localGet(1).call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable();
    }

    private void emitConvLong(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int r = fn.addLocal("r", WasmValType.I32, 3);
        int l = fn.addLocal("l", WasmValType.I64, 3);
        int st = fn.addLocal("st", WasmValType.I32, 3);
        int d = fn.addLocal("d", WasmValType.F64, 3);

        c.localGet(0).refTest(ts.boxLongType).if_()
                .localGet(0).refCast(ts.boxLongType).structGet(ts.boxLongType, 0).return_().end();
        c.localGet(0).refTest(ts.boxCharType).if_()
                .localGet(0).refCast(ts.boxCharType).structGet(ts.boxCharType, 0).op(WasmInstr.SimpleOp.I64_EXTEND_I32_U).return_().end();
        c.localGet(0).refTest(ts.stringType).if_()
                .localGet(0).refCast(ts.stringType).call(rt.fnParseLong).localSet(st).localSet(l)
                .localGet(st).op(WasmInstr.SimpleOp.I32_EQZ).if_().localGet(l).return_().end()
                .localGet(st).i32Const(2).op(WasmInstr.SimpleOp.I32_EQ).if_();
        rt.emitStringLit(c, "Out of the long range : \"");
        c.localGet(0).refCast(ts.stringType).call(rt.fnStrConcat);
        rt.emitStringLit(c, "\"");
        c.call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable().end();
        rt.emitStringLit(c, "Not a long : \"");
        c.localGet(0).refCast(ts.stringType).call(rt.fnStrConcat);
        rt.emitStringLit(c, "\"");
        c.call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable().end();

        c.localGet(0).call(rt.fnNumRank).localTee(r).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(r).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S).if_()
                .localGet(0).refAsNonNull().call(rt.fnNumAsLong).return_().end()
                .localGet(0).refAsNonNull().call(rt.fnNumAsDouble).localTee(d)
                .localGet(d).op(WasmInstr.SimpleOp.F64_NE)
                .localGet(d).f64Const(9223372036854775808.0).op(WasmInstr.SimpleOp.F64_GE).op(WasmInstr.SimpleOp.I32_OR)
                .localGet(d).f64Const(-9223372036854775808.0).op(WasmInstr.SimpleOp.F64_LT).op(WasmInstr.SimpleOp.I32_OR).if_();
        emitOutOfRange(c, "long", 0, 1, 2);
        c.end().localGet(d).op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F64_S).return_().end();

        rt.emitStringLit(c, "Not a number : ");
        c.localGet(1).call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable();
    }

    private void emitConvFloat(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int f = fn.addLocal("f", WasmValType.F32, 3);
        int ok = fn.addLocal("ok", WasmValType.I32, 3);

        c.localGet(0).refTest(ts.boxFloatType).if_()
                .localGet(0).refCast(ts.boxFloatType).structGet(ts.boxFloatType, 0).return_().end();
        c.localGet(0).refTest(ts.stringType).if_();
        if (target == CompilationTarget.WASM_JS) {
            c.localGet(0).refCast(ts.stringType).call(rt.fnStrStrip).call(rt.fnToExternStr).call(rt.impParseF32)
                    .localSet(ok).localSet(f)
                    .localGet(ok).if_().localGet(f).return_().end();
        }
        rt.emitStringLit(c, "Not a float : \"");
        c.localGet(0).refCast(ts.stringType).call(rt.fnStrConcat);
        rt.emitStringLit(c, "\"");
        c.call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable().end();

        c.localGet(0).call(rt.fnNumRank).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(0).refAsNonNull().call(rt.fnNumAsDouble).op(WasmInstr.SimpleOp.F32_DEMOTE_F64).return_().end();

        rt.emitStringLit(c, "Not a number : ");
        c.localGet(1).call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable();
    }

    private void emitConvDouble(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int d = fn.addLocal("d", WasmValType.F64, 3);
        int ok = fn.addLocal("ok", WasmValType.I32, 3);

        c.localGet(0).refTest(ts.boxDoubleType).if_()
                .localGet(0).refCast(ts.boxDoubleType).structGet(ts.boxDoubleType, 0).return_().end();
        c.localGet(0).refTest(ts.stringType).if_();
        if (target == CompilationTarget.WASM_JS) {
            c.localGet(0).refCast(ts.stringType).call(rt.fnStrStrip).call(rt.fnToExternStr).call(rt.impParseF64)
                    .localSet(ok).localSet(d)
                    .localGet(ok).if_().localGet(d).return_().end();
        } else {
            // Bare fallback: integer parse
            int l = fn.addLocal("l", WasmValType.I64, 3);
            c.localGet(0).refCast(ts.stringType).call(rt.fnParseLong).localSet(ok).localSet(l)
                    .localGet(ok).op(WasmInstr.SimpleOp.I32_EQZ).if_()
                    .localGet(l).op(WasmInstr.SimpleOp.F64_CONVERT_I64_S).return_().end();
        }
        rt.emitStringLit(c, "Not a double : \"");
        c.localGet(0).refCast(ts.stringType).call(rt.fnStrConcat);
        rt.emitStringLit(c, "\"");
        c.call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable().end();

        c.localGet(0).call(rt.fnNumRank).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(0).refAsNonNull().call(rt.fnNumAsDouble).return_().end();

        rt.emitStringLit(c, "Not a number : ");
        c.localGet(1).call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable();
    }

    private void emitConvBool(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(ts.boxBoolType).if_()
                .localGet(0).refCast(ts.boxBoolType).structGet(ts.boxBoolType, 0).return_().end();
        c.localGet(0).refTest(ts.stringType).if_()
                .localGet(0).refCast(ts.stringType);
        rt.emitStringGlobal(c, rt.sTrue);
        c.call(rt.fnStrEq).if_().i32Const(1).return_().end()
                .localGet(0).refCast(ts.stringType);
        rt.emitStringGlobal(c, rt.sFalse);
        c.call(rt.fnStrEq).if_().i32Const(0).return_().end();
        rt.emitStringLit(c, "Not a boolean : \"");
        c.localGet(0).refCast(ts.stringType).call(rt.fnStrConcat);
        rt.emitStringLit(c, "\"");
        c.call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable().end();

        rt.emitStringLit(c, "Not a boolean : ");
        c.localGet(1).call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable();
    }

    private void emitConvChar(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int r = fn.addLocal("r", WasmValType.I32, 3);
        int l = fn.addLocal("l", WasmValType.I64, 3);
        int ca = fn.addLocal("ca", WasmValType.refNull(ts.charArrType), 3);

        c.localGet(0).refTest(ts.boxCharType).if_()
                .localGet(0).refCast(ts.boxCharType).structGet(ts.boxCharType, 0).return_().end();
        c.localGet(0).refTest(ts.stringType).if_()
                .localGet(0).refCast(ts.stringType).structGet(ts.stringType, 0).localTee(ca)
                .arrayLen().i32Const(1).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(ca).i32Const(0).arrayGetU(ts.charArrType).return_()
                .end();
        rt.emitStringLit(c, "Expected single-character string : \"");
        c.localGet(0).refCast(ts.stringType).call(rt.fnStrConcat);
        rt.emitStringLit(c, "\"");
        c.call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable().end();

        c.localGet(0).call(rt.fnNumRank).localTee(r).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S)
                .localGet(r).i32Const(3).op(WasmInstr.SimpleOp.I32_LE_S).op(WasmInstr.SimpleOp.I32_AND).if_()
                .localGet(0).refAsNonNull().call(rt.fnNumAsLong).localTee(l)
                .i64Const(0L).op(WasmInstr.SimpleOp.I64_LT_S)
                .localGet(l).i64Const(65535L).op(WasmInstr.SimpleOp.I64_GT_S).op(WasmInstr.SimpleOp.I32_OR).if_();
        emitOutOfRange(c, "char", 0, 1, 2);
        c.end().localGet(l).op(WasmInstr.SimpleOp.I32_WRAP_I64).return_().end();

        rt.emitStringLit(c, "Not a char : ");
        c.localGet(1).call(rt.fnStrConcat).localGet(2).call(rt.fnThrowMsg).unreachable();
    }

    private void emitOutOfRange(WasmCodeBuilder c, String typeName, int valLocal, int textLocal, int lineLocal) {
        rt.emitStringLit(c, "Out of the " + typeName + " range : ");
        c.localGet(valLocal).call(rt.fnFormat).call(rt.fnStrConcat)
                .localGet(lineLocal).call(rt.fnThrowMsg).unreachable();
    }

    // ---------------------------------------------------------------- isBuiltin & classof

    private void emitIsBuiltin(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(1).i32Const(BUILTIN_ANY).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(0).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).return_().end();
        c.localGet(0).refIsNull().if_().i32Const(0).return_().end();

        int[] boxMap = {
                BUILTIN_BYTE, ts.boxByteType,
                BUILTIN_SHORT, ts.boxShortType,
                BUILTIN_INT, ts.boxIntType,
                BUILTIN_LONG, ts.boxLongType,
                BUILTIN_FLOAT, ts.boxFloatType,
                BUILTIN_DOUBLE, ts.boxDoubleType,
                BUILTIN_BOOL, ts.boxBoolType,
                BUILTIN_CHAR, ts.boxCharType,
                BUILTIN_STRING, ts.stringType,
                BUILTIN_EXCEPTION, ts.babbleExnType
        };
        for (int i = 0; i < boxMap.length; i += 2) {
            c.localGet(1).i32Const(boxMap[i]).op(WasmInstr.SimpleOp.I32_EQ).if_()
                    .localGet(0).refTest(boxMap[i + 1]).return_().end();
        }
        c.i32Const(0);
    }

    private void emitClassof(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        // Returns $VTable or null for primitives/null; in P2 this maps classId -> $ClassVal global!
        c.refNull(ts.anyType);
    }

    // ---------------------------------------------------------------- first-class builtin callables

    /**
     * Returns the global index holding a first-class {@code $FnBase} singleton for a Babble built-in
     * function name (created lazily on demand so unused built-ins emit zero code).
     */
    public int builtinCallableGlobal(String name) {
        Integer existing = builtinFnGlobals.get(name);
        if (existing != null) {
            return existing;
        }
        WasmModule.FuncDef impl = mod.addFunction("builtin_" + name, ts.genericCallSigType, List.of("self", "args", "names"));
        mod.declareFuncRef(impl.funcIndex());
        emitBuiltinGenericBody(impl, name);

        int g = mod.addGlobal("G_BUILTIN_" + name, WasmValType.refNull(ts.fnBaseType), true,
                ts.defaultConstExpr(WasmValType.refNull(ts.fnBaseType)));
        builtinFnGlobals.put(name, g);

        WasmCodeBuilder init = fnInitBuiltinsDef.code();
        init.refFunc(impl.funcIndex());
        rt.emitStringLit(init, name);
        init.i32Const(-1).structNew(ts.fnBaseType).globalSet(g);
        return g;
    }

    private void emitBuiltinGenericBody(WasmModule.FuncDef fn, String name) {
        WasmCodeBuilder c = fn.code();
        // params: 0=self ($FnBase), 1=args ($AnyArr), 2=names ($AnyArr?)
        switch (name) {
            case "print", "affiche", "println", "afficherc" -> {
                boolean nl = "println".equals(name) || "afficherc".equals(name);
                c.localGet(1).refNull(ts.anyType).i32Const(nl ? 1 : 0).i32Const(0).call(rt.fnPrint)
                        .refNull(ts.anyType);
            }
            case "assert", "suppose" -> {
                int len = fn.addLocal("len", WasmValType.I32, 3);
                c.localGet(1).arrayLen().localTee(len).op(WasmInstr.SimpleOp.I32_EQZ).if_();
                rt.emitStringLit(c, "assert() called without parameters");
                c.i32Const(0).call(rt.fnThrowMsg).unreachable().end();
                c.localGet(1).i32Const(0).arrayGet(ts.anyArrType);
                c.localGet(len).i32Const(1).op(WasmInstr.SimpleOp.I32_GT_S).if_(ts.anyRef())
                        .localGet(1).i32Const(1).arrayGet(ts.anyArrType)
                        .else_().refNull(ts.anyType).end();
                rt.emitStringLit(c, "assert");
                c.i32Const(0).call(rt.fnAssert).call(rt.fnBoxBool);
            }
            case "same" -> {
                c.localGet(1).i32Const(0).arrayGet(ts.anyArrType)
                        .localGet(1).i32Const(1).arrayGet(ts.anyArrType)
                        .call(rt.fnSame).call(rt.fnBoxBool);
            }
            case "classof" -> c.localGet(1).i32Const(0).arrayGet(ts.anyArrType).call(fnClassof);
            case "move", "freeze" -> c.localGet(1).i32Const(0).arrayGet(ts.anyArrType);
            case "discard" -> c.refNull(ts.anyType);
            case "string" -> c.localGet(1).i32Const(0).arrayGet(ts.anyArrType).call(rt.fnFormat);
            case "byte" -> emitConvWrapper(c, name, fnConvByte, rt.fnBoxByte);
            case "short" -> emitConvWrapper(c, name, fnConvShort, rt.fnBoxShort);
            case "int" -> emitConvWrapper(c, name, fnConvInt, rt.fnBoxInt);
            case "long" -> emitConvWrapper(c, name, fnConvLong, rt.fnBoxLong);
            case "float" -> emitConvWrapper(c, name, fnConvFloat, rt.fnBoxFloat);
            case "double" -> emitConvWrapper(c, name, fnConvDouble, rt.fnBoxDouble);
            case "bool" -> emitConvWrapper(c, name, fnConvBool, rt.fnBoxBool);
            case "char" -> emitConvWrapper(c, name, fnConvChar, rt.fnBoxChar);
            case "exception" -> {
                int len = fn.addLocal("len", WasmValType.I32, 3);
                int msg = fn.addLocal("msg", WasmValType.refNull(ts.stringType), 3);
                int cause = fn.addLocal("cause", ts.anyRef(), 3);
                c.localGet(1).arrayLen().localSet(len);
                c.localGet(len).i32Const(0).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                        .localGet(1).i32Const(0).arrayGet(ts.anyArrType)
                        .refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                        .localGet(1).i32Const(0).arrayGet(ts.anyArrType).call(rt.fnFormat).localSet(msg)
                        .end().end();
                c.localGet(len).i32Const(1).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                        .localGet(1).i32Const(1).arrayGet(ts.anyArrType).localSet(cause).end();
                c.localGet(msg).localGet(cause).i32Const(0).call(rt.fnMakeExn);
            }
            default -> {
                rt.emitStringLit(c, "Unsupported built-in : " + name);
                c.i32Const(0).call(rt.fnThrowMsg).unreachable();
            }
        }
    }

    private void emitConvWrapper(WasmCodeBuilder c, String name, int convFn, int boxFn) {
        c.localGet(1).i32Const(0).arrayGet(ts.anyArrType);
        rt.emitStringLit(c, name);
        c.i32Const(0).call(convFn).call(boxFn);
    }
}
