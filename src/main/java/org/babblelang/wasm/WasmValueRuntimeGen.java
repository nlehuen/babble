package org.babblelang.wasm;

import org.babblelang.analysis.CompilationTarget;

/**
 * Emits polymorphic value and I/O WasmGC runtime helpers for {@link WasmRuntimeGen}: {@code
 * equals}, {@code same}, {@code hashCode}, {@code compare}, {@code format}, exceptions, {@code
 * assert}, and {@code print}.
 */
final class WasmValueRuntimeGen {
    private final WasmRuntimeGen rt;
    private final WasmTypeSystem ts;

    WasmValueRuntimeGen(WasmRuntimeGen rt, WasmTypeSystem ts) {
        this.rt = rt;
        this.ts = ts;
    }

    // ---------------------------------------------------------------- equals / same / hash /
    // compare / format

    void emitEquals(WasmModule.FuncDef fn, int fnArrEq, int fnColEq, int fnRangeEq) {
        WasmCodeBuilder c = fn.code();
        int ra = fn.addLocal("ra", WasmValType.I32, 2);
        int rb = fn.addLocal("rb", WasmValType.I32, 2);
        int eqFn = fn.addLocal("eqFn", WasmValType.refNull(ts.eqFnSigType), 2);

        // Both null or identical reference
        c.localGet(0).localGet(1).refEq().if_().i32Const(1).return_().end();
        c.localGet(0)
                .refIsNull()
                .localGet(1)
                .refIsNull()
                .op(WasmInstr.SimpleOp.I32_OR)
                .if_()
                .i32Const(0)
                .return_()
                .end();

        // Numbers (cross-type: 1 == 1.0, NaN == NaN)
        c.localGet(0)
                .call(rt.fnNumRank)
                .localTee(ra)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_()
                .localGet(1)
                .call(rt.fnNumRank)
                .localTee(rb)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_()
                // Both integral?
                .localGet(ra)
                .i32Const(3)
                .op(WasmInstr.SimpleOp.I32_LE_S)
                .localGet(rb)
                .i32Const(3)
                .op(WasmInstr.SimpleOp.I32_LE_S)
                .op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsLong)
                .localGet(1)
                .refAsNonNull()
                .call(rt.fnNumAsLong)
                .op(WasmInstr.SimpleOp.I64_EQ)
                .return_()
                .end()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsDouble)
                .localGet(1)
                .refAsNonNull()
                .call(rt.fnNumAsDouble)
                .call(rt.fnF64Compare)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end();

        // Strings
        c.localGet(0)
                .refTest(ts.stringType)
                .if_()
                .localGet(1)
                .refTest(ts.stringType)
                .if_()
                .localGet(0)
                .refCast(ts.stringType)
                .localGet(1)
                .refCast(ts.stringType)
                .call(rt.fnStrEq)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end();

        // Bool
        c.localGet(0)
                .refTest(ts.boxBoolType)
                .if_()
                .localGet(1)
                .refTest(ts.boxBoolType)
                .if_()
                .localGet(0)
                .refCast(ts.boxBoolType)
                .structGet(ts.boxBoolType, 0)
                .localGet(1)
                .refCast(ts.boxBoolType)
                .structGet(ts.boxBoolType, 0)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end();

        // Char
        c.localGet(0)
                .refTest(ts.boxCharType)
                .if_()
                .localGet(1)
                .refTest(ts.boxCharType)
                .if_()
                .localGet(0)
                .refCast(ts.boxCharType)
                .structGet(ts.boxCharType, 0)
                .localGet(1)
                .refCast(ts.boxCharType)
                .structGet(ts.boxCharType, 0)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end();

        // Arrays (deep structural equality)
        if (fnArrEq >= 0) {
            c.localGet(0)
                    .refTest(ts.arrayBaseType)
                    .if_()
                    .localGet(1)
                    .refTest(ts.arrayBaseType)
                    .if_()
                    .localGet(0)
                    .refCast(ts.arrayBaseType)
                    .localGet(1)
                    .refCast(ts.arrayBaseType)
                    .call(fnArrEq)
                    .return_()
                    .end()
                    .i32Const(0)
                    .return_()
                    .end();
        }

        // Core collections ($List, $Set, $Entry, $Map)
        if (fnColEq >= 0) {
            c.localGet(0)
                    .refTest(ts.listType)
                    .localGet(0)
                    .refTest(ts.setType)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0)
                    .refTest(ts.entryType)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0)
                    .refTest(ts.mapType)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .if_()
                    .localGet(0)
                    .localGet(1)
                    .call(fnColEq)
                    .return_()
                    .end();
        }
        if (fnRangeEq >= 0) {
            c.localGet(0)
                    .refTest(ts.rangeBaseType)
                    .if_()
                    .localGet(0)
                    .refCast(ts.rangeBaseType)
                    .localGet(1)
                    .call(fnRangeEq)
                    .return_()
                    .end();
        }

        // Babble $Object custom/record equalsFn
        c.localGet(0)
                .refTest(ts.objectType)
                .if_()
                .localGet(0)
                .refCast(ts.objectType)
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 6)
                .localTee(eqFn)
                .refIsNull()
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .localGet(0)
                .refCast(ts.objectType)
                .localGet(1)
                .localGet(eqFn)
                .refAsNonNull()
                .callRef(ts.eqFnSigType)
                .return_()
                .end()
                .end();

        c.i32Const(0);
    }

    void emitSame(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).localGet(1).refEq().if_().i32Const(1).return_().end();
        c.localGet(0)
                .refIsNull()
                .localGet(1)
                .refIsNull()
                .op(WasmInstr.SimpleOp.I32_OR)
                .if_()
                .i32Const(0)
                .return_()
                .end();
        // Plain values (numbers, bool, char, string) compare by value
        c.localGet(0)
                .call(rt.fnNumRank)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .localGet(0)
                .refTest(ts.boxBoolType)
                .op(WasmInstr.SimpleOp.I32_OR)
                .localGet(0)
                .refTest(ts.boxCharType)
                .op(WasmInstr.SimpleOp.I32_OR)
                .localGet(0)
                .refTest(ts.stringType)
                .op(WasmInstr.SimpleOp.I32_OR)
                .if_()
                .localGet(1)
                .call(rt.fnNumRank)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .localGet(1)
                .refTest(ts.boxBoolType)
                .op(WasmInstr.SimpleOp.I32_OR)
                .localGet(1)
                .refTest(ts.boxCharType)
                .op(WasmInstr.SimpleOp.I32_OR)
                .localGet(1)
                .refTest(ts.stringType)
                .op(WasmInstr.SimpleOp.I32_OR)
                .if_()
                .localGet(0)
                .localGet(1)
                .call(rt.fnEquals)
                .return_()
                .end()
                .end();
        c.i32Const(0);
    }

    void emitHashCode(WasmModule.FuncDef fn, int fnArrHash, int fnColHash, int fnRangeHash) {
        WasmCodeBuilder c = fn.code();
        int r = fn.addLocal("r", WasmValType.I32, 1);
        int l = fn.addLocal("l", WasmValType.I64, 1);
        int d = fn.addLocal("d", WasmValType.F64, 1);
        int hFn = fn.addLocal("hFn", WasmValType.refNull(ts.hashFnSigType), 1);

        c.localGet(0).refIsNull().if_().i32Const(0).return_().end();
        c.localGet(0)
                .refTest(ts.stringType)
                .if_()
                .localGet(0)
                .refCast(ts.stringType)
                .call(rt.fnStrHash)
                .return_()
                .end();
        c.localGet(0)
                .call(rt.fnNumRank)
                .localTee(r)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_()
                .localGet(r)
                .i32Const(3)
                .op(WasmInstr.SimpleOp.I32_LE_S)
                .if_()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsLong)
                .localTee(l)
                .localGet(l)
                .i64Const(32L)
                .op(WasmInstr.SimpleOp.I64_SHR_U)
                .op(WasmInstr.SimpleOp.I64_XOR)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .return_()
                .end()
                // Floating point: check if integral value in long range
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsDouble)
                .localTee(d)
                .op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F64_S)
                .localTee(l)
                .op(WasmInstr.SimpleOp.F64_CONVERT_I64_S)
                .localGet(d)
                .op(WasmInstr.SimpleOp.F64_EQ)
                .if_()
                .localGet(l)
                .localGet(l)
                .i64Const(32L)
                .op(WasmInstr.SimpleOp.I64_SHR_U)
                .op(WasmInstr.SimpleOp.I64_XOR)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .return_()
                .end()
                .localGet(d)
                .op(WasmInstr.SimpleOp.I64_REINTERPRET_F64)
                .localTee(l)
                .localGet(l)
                .i64Const(32L)
                .op(WasmInstr.SimpleOp.I64_SHR_U)
                .op(WasmInstr.SimpleOp.I64_XOR)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxBoolType)
                .if_()
                .i32Const(1231)
                .i32Const(1237)
                .localGet(0)
                .refCast(ts.boxBoolType)
                .structGet(ts.boxBoolType, 0)
                .select()
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxCharType)
                .if_()
                .localGet(0)
                .refCast(ts.boxCharType)
                .structGet(ts.boxCharType, 0)
                .return_()
                .end();
        if (fnArrHash >= 0) {
            c.localGet(0)
                    .refTest(ts.arrayBaseType)
                    .if_()
                    .localGet(0)
                    .refCast(ts.arrayBaseType)
                    .call(fnArrHash)
                    .return_()
                    .end();
        }
        if (fnColHash >= 0) {
            c.localGet(0)
                    .refTest(ts.listType)
                    .localGet(0)
                    .refTest(ts.setType)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0)
                    .refTest(ts.entryType)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0)
                    .refTest(ts.mapType)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .if_()
                    .localGet(0)
                    .call(fnColHash)
                    .return_()
                    .end();
        }
        if (fnRangeHash >= 0) {
            c.localGet(0)
                    .refTest(ts.rangeBaseType)
                    .if_()
                    .localGet(0)
                    .refCast(ts.rangeBaseType)
                    .call(fnRangeHash)
                    .return_()
                    .end();
        }
        c.localGet(0)
                .refTest(ts.objectType)
                .if_()
                .localGet(0)
                .refCast(ts.objectType)
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 7)
                .localTee(hFn)
                .refIsNull()
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .localGet(0)
                .refCast(ts.objectType)
                .localGet(hFn)
                .refAsNonNull()
                .callRef(ts.hashFnSigType)
                .return_()
                .end()
                .end();
        c.i32Const(0);
    }

    void emitCompare(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ra = fn.addLocal("ra", WasmValType.I32, 5);
        int rb = fn.addLocal("rb", WasmValType.I32, 5);

        c.localGet(0)
                .call(rt.fnNumRank)
                .localTee(ra)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_()
                .localGet(1)
                .call(rt.fnNumRank)
                .localTee(rb)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_()
                .localGet(ra)
                .i32Const(3)
                .op(WasmInstr.SimpleOp.I32_LE_S)
                .localGet(rb)
                .i32Const(3)
                .op(WasmInstr.SimpleOp.I32_LE_S)
                .op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsLong)
                .localGet(1)
                .refAsNonNull()
                .call(rt.fnNumAsLong)
                .op(WasmInstr.SimpleOp.I64_LT_S)
                .if_()
                .i32Const(-1)
                .return_()
                .end()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsLong)
                .localGet(1)
                .refAsNonNull()
                .call(rt.fnNumAsLong)
                .op(WasmInstr.SimpleOp.I64_GT_S)
                .if_()
                .i32Const(1)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsDouble)
                .localGet(1)
                .refAsNonNull()
                .call(rt.fnNumAsDouble)
                .call(rt.fnF64Compare)
                .return_()
                .end()
                .end();

        c.localGet(0)
                .refTest(ts.stringType)
                .if_()
                .localGet(1)
                .refTest(ts.stringType)
                .if_()
                .localGet(0)
                .refCast(ts.stringType)
                .localGet(1)
                .refCast(ts.stringType)
                .call(rt.fnStrCmp)
                .return_()
                .end()
                .end();

        c.localGet(0)
                .refTest(ts.boxCharType)
                .if_()
                .localGet(1)
                .refTest(ts.boxCharType)
                .if_()
                .localGet(0)
                .refCast(ts.boxCharType)
                .structGet(ts.boxCharType, 0)
                .localGet(1)
                .refCast(ts.boxCharType)
                .structGet(ts.boxCharType, 0)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .return_()
                .end()
                .end();

        c.localGet(0)
                .refTest(ts.boxBoolType)
                .if_()
                .localGet(1)
                .refTest(ts.boxBoolType)
                .if_()
                .localGet(0)
                .refCast(ts.boxBoolType)
                .structGet(ts.boxBoolType, 0)
                .localGet(1)
                .refCast(ts.boxBoolType)
                .structGet(ts.boxBoolType, 0)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .return_()
                .end()
                .end();

        rt.emitStringLit(c, "Not comparable : ");
        c.localGet(2).call(rt.fnStrConcat);
        rt.emitStringLit(c, " and ");
        c.call(rt.fnStrConcat)
                .localGet(3)
                .call(rt.fnStrConcat)
                .localGet(4)
                .call(rt.fnThrowMsg)
                .unreachable();
    }

    void emitFormat(WasmModule.FuncDef fn, int fnArrFmt, int fnColFmt, int fnRangeFmt) {
        WasmCodeBuilder c = fn.code();
        int toStrFn = fn.addLocal("toStrFn", WasmValType.refNull(ts.toStrFnSigType), 1);

        c.localGet(0).refIsNull().if_();
        rt.emitStringGlobal(c, rt.sNull);
        c.return_().end();

        c.localGet(0)
                .refTest(ts.stringType)
                .if_()
                .localGet(0)
                .refCast(ts.stringType)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxIntType)
                .if_()
                .localGet(0)
                .refCast(ts.boxIntType)
                .structGet(ts.boxIntType, 0)
                .call(rt.fnIntToStr)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxDoubleType)
                .if_()
                .localGet(0)
                .refCast(ts.boxDoubleType)
                .structGet(ts.boxDoubleType, 0)
                .call(rt.fnDoubleToStr)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxBoolType)
                .if_()
                .localGet(0)
                .refCast(ts.boxBoolType)
                .structGet(ts.boxBoolType, 0)
                .if_(WasmValType.ref(ts.stringType));
        rt.emitStringGlobal(c, rt.sTrue);
        c.else_();
        rt.emitStringGlobal(c, rt.sFalse);
        c.end().return_().end();
        c.localGet(0)
                .refTest(ts.boxLongType)
                .if_()
                .localGet(0)
                .refCast(ts.boxLongType)
                .structGet(ts.boxLongType, 0)
                .call(rt.fnLongToStr)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxFloatType)
                .if_()
                .localGet(0)
                .refCast(ts.boxFloatType)
                .structGet(ts.boxFloatType, 0)
                .call(rt.fnFloatToStr)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxShortType)
                .if_()
                .localGet(0)
                .refCast(ts.boxShortType)
                .structGet(ts.boxShortType, 0)
                .call(rt.fnIntToStr)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxByteType)
                .if_()
                .localGet(0)
                .refCast(ts.boxByteType)
                .structGet(ts.boxByteType, 0)
                .call(rt.fnIntToStr)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxCharType)
                .if_()
                .localGet(0)
                .refCast(ts.boxCharType)
                .structGet(ts.boxCharType, 0)
                .call(rt.fnCharToStr)
                .return_()
                .end();
        c.localGet(0).refTest(ts.babbleExnType).if_();
        int msg = fn.addLocal("msg", WasmValType.refNull(ts.stringType), 1);
        c.localGet(0)
                .refCast(ts.babbleExnType)
                .structGet(ts.babbleExnType, 0)
                .localTee(msg)
                .refIsNull()
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .localGet(msg)
                .refAsNonNull()
                .return_()
                .end();
        rt.emitStringLit(c, "exception");
        c.return_().end();

        if (fnArrFmt >= 0) {
            c.localGet(0)
                    .refTest(ts.arrayBaseType)
                    .if_()
                    .localGet(0)
                    .refCast(ts.arrayBaseType)
                    .call(fnArrFmt)
                    .return_()
                    .end();
        }
        if (fnColFmt >= 0) {
            c.localGet(0)
                    .refTest(ts.listType)
                    .localGet(0)
                    .refTest(ts.setType)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0)
                    .refTest(ts.entryType)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(0)
                    .refTest(ts.mapType)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .if_()
                    .localGet(0)
                    .call(fnColFmt)
                    .return_()
                    .end();
        }
        if (fnRangeFmt >= 0) {
            c.localGet(0)
                    .refTest(ts.rangeBaseType)
                    .if_()
                    .localGet(0)
                    .refCast(ts.rangeBaseType)
                    .call(fnRangeFmt)
                    .return_()
                    .end();
        }

        c.localGet(0)
                .refTest(ts.objectType)
                .if_()
                .localGet(0)
                .refCast(ts.objectType)
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 8)
                .localTee(toStrFn)
                .refIsNull()
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .localGet(0)
                .refCast(ts.objectType)
                .localGet(toStrFn)
                .refAsNonNull()
                .callRef(ts.toStrFnSigType)
                .return_()
                .end()
                .localGet(0)
                .refCast(ts.objectType)
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 1)
                .return_()
                .end();

        c.localGet(0).refTest(ts.moduleValType).if_();
        rt.emitStringLit(c, "module ");
        c.localGet(0)
                .refCast(ts.moduleValType)
                .structGet(ts.moduleValType, 1)
                .call(rt.fnStrConcat)
                .return_()
                .end();

        c.localGet(0).refTest(ts.classValType).if_();
        rt.emitStringLit(c, "class ");
        c.localGet(0)
                .refCast(ts.classValType)
                .structGet(ts.classValType, 1)
                .call(rt.fnStrConcat)
                .return_()
                .end();

        c.localGet(0).refTest(ts.ifaceValType).if_();
        rt.emitStringLit(c, "interface ");
        c.localGet(0)
                .refCast(ts.ifaceValType)
                .structGet(ts.ifaceValType, 1)
                .call(rt.fnStrConcat)
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.overloadSetType)
                .if_()
                .localGet(0)
                .refCast(ts.overloadSetType)
                .structGet(ts.overloadSetType, 1)
                .return_()
                .end();

        c.localGet(0).refTest(ts.fnBaseType).if_();
        int fnName = fn.addLocal("fnName", WasmValType.refNull(ts.stringType), 1);
        c.localGet(0)
                .refCast(ts.fnBaseType)
                .structGet(ts.fnBaseType, 1)
                .localTee(fnName)
                .refAsNonNull();
        rt.emitStringLit(c, "<fn>");
        c.call(rt.fnStrEq).if_();
        rt.emitStringLit(c, "function");
        c.return_().end();
        rt.emitStringLit(c, "function ");
        c.localGet(fnName).refAsNonNull().call(rt.fnStrConcat).return_().end();

        rt.emitStringLit(c, "<object>");
    }

    // ---------------------------------------------------------------- exceptions, assert, print

    void emitMakeExn(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0)
                .localGet(1)
                .refNull(ts.anyArrType)
                .i32Const(0)
                .localGet(2)
                .structNew(ts.babbleExnType);
    }

    void emitThrowMsg(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0)
                .refNull(ts.anyType)
                .localGet(1)
                .call(rt.fnMakeExn)
                .localGet(1)
                .throw_(rt.babbleExnTag);
    }

    void emitAddSuppressed(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int exn = fn.addLocal("exn", WasmValType.refNull(ts.babbleExnType), 2);
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.anyArrType), 2);
        int cnt = fn.addLocal("cnt", WasmValType.I32, 2);
        int newArr = fn.addLocal("newArr", WasmValType.refNull(ts.anyArrType), 2);

        c.localGet(0)
                .refTest(ts.babbleExnType)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .return_()
                .end();
        c.localGet(0).localGet(1).refEq().if_().return_().end();
        c.localGet(0).refCast(ts.babbleExnType).localSet(exn);
        c.localGet(exn).structGet(ts.babbleExnType, 2).localSet(arr);
        c.localGet(exn).structGet(ts.babbleExnType, 3).localSet(cnt);
        c.localGet(arr)
                .refIsNull()
                .if_()
                .i32Const(4)
                .arrayNewDefault(ts.anyArrType)
                .localSet(arr)
                .localGet(exn)
                .localGet(arr)
                .structSet(ts.babbleExnType, 2)
                .end();
        c.localGet(cnt)
                .localGet(arr)
                .refAsNonNull()
                .arrayLen()
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_()
                .localGet(cnt)
                .i32Const(2)
                .op(WasmInstr.SimpleOp.I32_MUL)
                .arrayNewDefault(ts.anyArrType)
                .localTee(newArr)
                .i32Const(0)
                .localGet(arr)
                .refAsNonNull()
                .i32Const(0)
                .localGet(cnt)
                .arrayCopy(ts.anyArrType, ts.anyArrType)
                .localGet(exn)
                .localGet(newArr)
                .structSet(ts.babbleExnType, 2)
                .localGet(newArr)
                .localSet(arr)
                .end();
        c.localGet(arr).refAsNonNull().localGet(cnt).localGet(1).arraySet(ts.anyArrType);
        c.localGet(exn)
                .localGet(cnt)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .structSet(ts.babbleExnType, 3);
    }

    void emitAssert(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(ts.boxBoolType).op(WasmInstr.SimpleOp.I32_EQZ).if_();
        rt.emitStringLit(c, "Expression ");
        c.localGet(2).call(rt.fnStrConcat);
        rt.emitStringLit(
                c,
                " : asserts don't rely on truth values, please make sure that the test has a boolean result");
        c.call(rt.fnStrConcat).localGet(3).call(rt.fnThrowMsg).unreachable().end();

        c.localGet(0)
                .refCast(ts.boxBoolType)
                .structGet(ts.boxBoolType, 0)
                .if_()
                .i32Const(1)
                .return_()
                .end();

        // Failed assertion
        c.localGet(1)
                .refIsNull()
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .localGet(1)
                .globalGet(rt.gMissing)
                .refEq()
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(1)
                .call(rt.fnFormat)
                .localGet(3)
                .call(rt.fnThrowMsg)
                .unreachable()
                .end();

        rt.emitStringLit(c, "Assertion failed at line ");
        c.localGet(3).call(rt.fnIntToStr).call(rt.fnStrConcat);
        rt.emitStringLit(c, " : ");
        c.call(rt.fnStrConcat)
                .localGet(2)
                .call(rt.fnStrConcat)
                .localGet(3)
                .call(rt.fnThrowMsg)
                .unreachable();
    }

    void emitPrint(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int len = fn.addLocal("len", WasmValType.I32, 4);
        int i = fn.addLocal("i", WasmValType.I32, 4);
        int acc = fn.addLocal("acc", WasmValType.refNull(ts.stringType), 4);
        int targetStream = fn.addLocal("stream", WasmValType.I32, 4); // 0=stdout, 1=stderr

        c.globalGet(rt.gEmptyStr).localSet(acc);
        c.localGet(0).arrayLen().localSet(len);
        c.loop()
                .localGet(i)
                .localGet(len)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(acc)
                .refAsNonNull()
                .localGet(0)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .call(rt.fnFormat)
                .call(rt.fnStrConcat)
                .localSet(acc)
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end();
        c.localGet(2).if_().localGet(acc).refAsNonNull();
        rt.emitStringGlobal(c, rt.sNewline);
        c.call(rt.fnStrConcat).localSet(acc).end();

        // Check custom $Object with `append(s: string)` method first (works on BOTH wasm-js and
        // wasm-bare, BIP025-D12!)
        int getMemFn = fn.addLocal("getMemFn", WasmValType.refNull(ts.getMemFnSigType), 4);
        int appendMethod = fn.addLocal("appendMethod", ts.anyRef(), 4);
        c.localGet(1)
                .refTest(ts.objectType)
                .if_()
                .localGet(1)
                .refCast(ts.objectType)
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 9)
                .localTee(getMemFn)
                .refIsNull()
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .localGet(1)
                .refCast(ts.objectType);
        rt.emitStringLit(c, "append");
        c.localGet(getMemFn)
                .refAsNonNull()
                .callRef(ts.getMemFnSigType)
                .localTee(appendMethod)
                .refTest(ts.fnBaseType)
                .if_()
                .localGet(appendMethod)
                .refCast(ts.fnBaseType)
                .localGet(acc)
                .arrayNewFixed(ts.anyArrType, 1)
                .refNull(ts.anyArrType)
                .localGet(appendMethod)
                .refCast(ts.fnBaseType)
                .structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType)
                .drop()
                .return_()
                .end()
                .end()
                .end();

        if (rt.target == CompilationTarget.WASM_BARE) {
            rt.emitStringLit(c, "Not supported on rt.target wasm-bare : print");
            c.localGet(3).call(rt.fnThrowMsg).unreachable();
            return;
        }

        // Host stdout / stderr (null / MISSING -> STDOUT; $HostStream -> stdout/stderr)
        c.localGet(1)
                .refIsNull()
                .localGet(1)
                .globalGet(rt.gMissing)
                .refEq()
                .op(WasmInstr.SimpleOp.I32_OR)
                .if_()
                .localGet(acc)
                .refAsNonNull()
                .call(rt.fnToExternStr)
                .call(rt.impWriteStdout)
                .return_()
                .end();
        c.localGet(1)
                .refTest(ts.hostStreamType)
                .if_()
                .localGet(1)
                .refCast(ts.hostStreamType)
                .structGet(ts.hostStreamType, 0)
                .localSet(targetStream)
                .localGet(targetStream)
                .if_()
                .localGet(acc)
                .refAsNonNull()
                .call(rt.fnToExternStr)
                .call(rt.impWriteStderr)
                .else_()
                .localGet(acc)
                .refAsNonNull()
                .call(rt.fnToExternStr)
                .call(rt.impWriteStdout)
                .end()
                .return_()
                .end();

        rt.emitStringLit(c, "print to: expects a Writer or PrintStream");
        c.localGet(3).call(rt.fnThrowMsg).unreachable();
    }
}
