package org.babblelang.wasm;

import org.babblelang.analysis.CompilationTarget;
import org.babblelang.analysis.PrimitiveKind;

/**
 * Emits numeric WasmGC runtime helpers for {@link WasmRuntimeGen}: number formatting, integer
 * parsing, primitive boxing/unboxing, IEEE-754 float/double comparisons, truthiness, and numeric
 * rank/widening conversions.
 */
final class WasmNumericRuntimeGen {
    private final WasmRuntimeGen rt;
    private final WasmTypeSystem ts;

    WasmNumericRuntimeGen(WasmRuntimeGen rt, WasmTypeSystem ts) {
        this.rt = rt;
        this.ts = ts;
    }

    // ---------------------------------------------------------------- number formatting & parsing

    void emitIntToStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).op(WasmInstr.SimpleOp.I64_EXTEND_I32_S).call(rt.fnLongToStr);
    }

    void emitLongToStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int buf = fn.addLocal("buf", WasmValType.refNull(ts.charArrType), 1);
        int pos = fn.addLocal("pos", WasmValType.I32, 1);
        int neg = fn.addLocal("neg", WasmValType.I32, 1);
        int mag = fn.addLocal("mag", WasmValType.I64, 1); // unsigned magnitude!
        int outLen = fn.addLocal("outLen", WasmValType.I32, 1);
        int out = fn.addLocal("out", WasmValType.refNull(ts.charArrType), 1);

        c.localGet(0).op(WasmInstr.SimpleOp.I64_EQZ).if_();
        rt.emitStringLit(c, "0");
        c.return_().end();

        c.i32Const(24).arrayNewDefault(ts.charArrType).localSet(buf);
        c.i32Const(24).localSet(pos);

        c.localGet(0)
                .i64Const(0L)
                .op(WasmInstr.SimpleOp.I64_LT_S)
                .if_()
                .i32Const(1)
                .localSet(neg)
                .i64Const(0L)
                .localGet(0)
                .op(WasmInstr.SimpleOp.I64_SUB)
                .localSet(mag) // handles Long.MIN_VALUE as unsigned 2^63!
                .else_()
                .localGet(0)
                .localSet(mag)
                .end();

        c.loop()
                .localGet(mag)
                .i64Const(0L)
                .op(WasmInstr.SimpleOp.I64_NE)
                .if_()
                .localGet(pos)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .localSet(pos)
                .localGet(buf)
                .localGet(pos)
                .localGet(mag)
                .i64Const(10L)
                .op(WasmInstr.SimpleOp.I64_REM_U)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .i32Const('0')
                .op(WasmInstr.SimpleOp.I32_ADD)
                .arraySet(ts.charArrType)
                .localGet(mag)
                .i64Const(10L)
                .op(WasmInstr.SimpleOp.I64_DIV_U)
                .localSet(mag)
                .br(1)
                .end()
                .end();

        c.localGet(neg)
                .if_()
                .localGet(pos)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .localSet(pos)
                .localGet(buf)
                .localGet(pos)
                .i32Const('-')
                .arraySet(ts.charArrType)
                .end();

        c.i32Const(24)
                .localGet(pos)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .localTee(outLen)
                .arrayNewDefault(ts.charArrType)
                .localSet(out);
        c.localGet(out)
                .i32Const(0)
                .localGet(buf)
                .localGet(pos)
                .localGet(outLen)
                .arrayCopy(ts.charArrType, ts.charArrType);
        c.localGet(out).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    void emitDoubleToStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        if (rt.target == CompilationTarget.WASM_JS) {
            c.localGet(0).call(rt.impFormatF64).call(rt.fnFromExternStr);
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
        rt.emitStringGlobal(c, rt.sNaN);
        c.return_().end();
        // Infinity / -Infinity
        c.localGet(0).f64Const(Double.POSITIVE_INFINITY).op(WasmInstr.SimpleOp.F64_EQ).if_();
        rt.emitStringGlobal(c, rt.sInfinity);
        c.return_().end();
        c.localGet(0).f64Const(Double.NEGATIVE_INFINITY).op(WasmInstr.SimpleOp.F64_EQ).if_();
        rt.emitStringGlobal(c, rt.sNegInfinity);
        c.return_().end();
        // -0.0 / 0.0
        c.localGet(0).op(WasmInstr.SimpleOp.I64_REINTERPRET_F64).localSet(bits);
        c.localGet(bits).i64Const(0x8000_0000_0000_0000L).op(WasmInstr.SimpleOp.I64_EQ).if_();
        rt.emitStringGlobal(c, rt.sNegZero);
        c.return_().end();
        c.localGet(bits).op(WasmInstr.SimpleOp.I64_EQZ).if_();
        rt.emitStringLit(c, "0.0");
        c.return_().end();

        c.localGet(bits).i64Const(0L).op(WasmInstr.SimpleOp.I64_LT_S).localSet(neg);
        c.localGet(0).op(WasmInstr.SimpleOp.F64_ABS).localSet(absD);
        // Scale out-of-range magnitudes into scientific notation so absD never overflows
        // Long.MAX_VALUE
        int exp = fn.addLocal("exp", WasmValType.I32, 1);
        int useSci = fn.addLocal("useSci", WasmValType.I32, 1);
        c.localGet(absD)
                .f64Const(1.0e7)
                .op(WasmInstr.SimpleOp.F64_GE)
                .localGet(absD)
                .f64Const(1.0e-3)
                .op(WasmInstr.SimpleOp.F64_LT)
                .op(WasmInstr.SimpleOp.I32_OR)
                .if_()
                .i32Const(1)
                .localSet(useSci)
                .loop()
                .localGet(absD)
                .f64Const(10.0)
                .op(WasmInstr.SimpleOp.F64_GE)
                .if_()
                .localGet(absD)
                .f64Const(10.0)
                .op(WasmInstr.SimpleOp.F64_DIV)
                .localSet(absD)
                .localGet(exp)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(exp)
                .br(1)
                .end()
                .end()
                .loop()
                .localGet(absD)
                .f64Const(1.0)
                .op(WasmInstr.SimpleOp.F64_LT)
                .if_()
                .localGet(absD)
                .f64Const(10.0)
                .op(WasmInstr.SimpleOp.F64_MUL)
                .localSet(absD)
                .localGet(exp)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .localSet(exp)
                .br(1)
                .end()
                .end()
                .end();
        // Fixed decimal for [1e-3, 1e7) or scaled mantissa [1.0, 10.0)
        c.localGet(absD).op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F64_S).localSet(whole);
        c.localGet(absD)
                .localGet(whole)
                .op(WasmInstr.SimpleOp.F64_CONVERT_I64_S)
                .op(WasmInstr.SimpleOp.F64_SUB)
                .f64Const(1_000_000.0)
                .op(WasmInstr.SimpleOp.F64_MUL)
                .op(WasmInstr.SimpleOp.F64_NEAREST)
                .op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F64_S)
                .localSet(frac);
        c.localGet(frac)
                .i64Const(1_000_000L)
                .op(WasmInstr.SimpleOp.I64_GE_S)
                .if_()
                .localGet(whole)
                .i64Const(1L)
                .op(WasmInstr.SimpleOp.I64_ADD)
                .localSet(whole)
                .i64Const(0L)
                .localSet(frac)
                .end();
        c.localGet(whole).call(rt.fnLongToStr);
        rt.emitStringLit(c, ".");
        c.call(rt.fnStrConcat).localSet(res);
        // Trim trailing zeros of frac (keep at least 1 digit)
        c.localGet(frac).op(WasmInstr.SimpleOp.I64_EQZ).if_().localGet(res).refAsNonNull();
        rt.emitStringLit(c, "0");
        c.call(rt.fnStrConcat).localSet(res).else_();
        // Leading zeros for frac
        int div = fn.addLocal("div", WasmValType.I64, 1);
        c.i64Const(100_000L).localSet(div);
        c.loop()
                .localGet(div)
                .i64Const(1L)
                .op(WasmInstr.SimpleOp.I64_GT_S)
                .if_()
                .localGet(frac)
                .localGet(div)
                .op(WasmInstr.SimpleOp.I64_LT_S)
                .if_()
                .localGet(res)
                .refAsNonNull();
        rt.emitStringLit(c, "0");
        c.call(rt.fnStrConcat)
                .localSet(res)
                .localGet(div)
                .i64Const(10L)
                .op(WasmInstr.SimpleOp.I64_DIV_S)
                .localSet(div)
                .br(2)
                .end()
                .end()
                .end();
        c.loop()
                .localGet(frac)
                .i64Const(10L)
                .op(WasmInstr.SimpleOp.I64_REM_S)
                .op(WasmInstr.SimpleOp.I64_EQZ)
                .if_()
                .localGet(frac)
                .i64Const(10L)
                .op(WasmInstr.SimpleOp.I64_DIV_S)
                .localSet(frac)
                .br(1)
                .end()
                .end();
        c.localGet(res)
                .refAsNonNull()
                .localGet(frac)
                .call(rt.fnLongToStr)
                .call(rt.fnStrConcat)
                .localSet(res);
        c.end();

        c.localGet(useSci).if_().localGet(res).refAsNonNull();
        rt.emitStringLit(c, "E");
        c.call(rt.fnStrConcat)
                .localGet(exp)
                .call(rt.fnIntToStr)
                .call(rt.fnStrConcat)
                .localSet(res)
                .end();

        c.localGet(neg).if_();
        rt.emitStringLit(c, "-");
        c.localGet(res).refAsNonNull().call(rt.fnStrConcat).return_().end();
        c.localGet(res).refAsNonNull();
    }

    void emitFloatToStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        if (rt.target == CompilationTarget.WASM_JS) {
            c.localGet(0).call(rt.impFormatF32).call(rt.fnFromExternStr);
        } else {
            c.localGet(0).op(WasmInstr.SimpleOp.F64_PROMOTE_F32).call(rt.fnDoubleToStr);
        }
    }

    void emitCharToStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0)
                .arrayNewFixed(ts.charArrType, 1)
                .localGet(0)
                .i32Const(1)
                .structNew(ts.stringType);
    }

    /**
     * Parses a decimal signed 64-bit integer from a stripped string. Returns {@code (value: i64,
     * status: i32)} where {@code 0 = OK}, {@code 1 = NOT_INT}, {@code 2 = OVERFLOW}.
     */
    void emitParseLong(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ca = fn.addLocal("ca", WasmValType.refNull(ts.charArrType), 1);
        int len = fn.addLocal("len", WasmValType.I32, 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        int neg = fn.addLocal("neg", WasmValType.I32, 1);
        int ch = fn.addLocal("ch", WasmValType.I32, 1);
        int acc =
                fn.addLocal(
                        "acc",
                        WasmValType.I64,
                        1); // accumulated as NEGATIVE so Long.MIN_VALUE fits cleanly!
        int limit = fn.addLocal("limit", WasmValType.I64, 1);
        int multmin = fn.addLocal("multmin", WasmValType.I64, 1);
        int digit = fn.addLocal("digit", WasmValType.I64, 1);
        int overflow = fn.addLocal("overflow", WasmValType.I32, 1);

        c.localGet(0)
                .call(rt.fnStrStrip)
                .structGet(ts.stringType, 0)
                .localTee(ca)
                .arrayLen()
                .localTee(len)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .i64Const(0L)
                .i32Const(1)
                .return_()
                .end();

        c.i64Const(-Long.MAX_VALUE).localSet(limit);
        c.localGet(ca)
                .i32Const(0)
                .arrayGetU(ts.charArrType)
                .localTee(ch)
                .i32Const('-')
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .i32Const(1)
                .localSet(neg)
                .i64Const(Long.MIN_VALUE)
                .localSet(limit)
                .i32Const(1)
                .localSet(i)
                .else_()
                .localGet(ch)
                .i32Const('+')
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .i32Const(1)
                .localSet(i)
                .end()
                .end();

        // Must have at least 1 digit
        c.localGet(i)
                .localGet(len)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_()
                .i64Const(0L)
                .i32Const(1)
                .return_()
                .end();

        c.localGet(limit).i64Const(10L).op(WasmInstr.SimpleOp.I64_DIV_S).localSet(multmin);

        c.loop()
                .localGet(i)
                .localGet(len)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(ca)
                .localGet(i)
                .arrayGetU(ts.charArrType)
                .localTee(ch)
                .i32Const('0')
                .op(WasmInstr.SimpleOp.I32_SUB)
                .localSet(ch)
                .localGet(ch)
                .i32Const(9)
                .op(WasmInstr.SimpleOp.I32_GT_U)
                .if_()
                .i64Const(0L)
                .i32Const(1)
                .return_()
                .end()
                .localGet(ch)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_U)
                .localSet(digit)
                .localGet(acc)
                .localGet(multmin)
                .op(WasmInstr.SimpleOp.I64_LT_S)
                .if_()
                .i32Const(1)
                .localSet(overflow)
                .end()
                .localGet(acc)
                .i64Const(10L)
                .op(WasmInstr.SimpleOp.I64_MUL)
                .localSet(acc)
                .localGet(acc)
                .localGet(limit)
                .localGet(digit)
                .op(WasmInstr.SimpleOp.I64_ADD)
                .op(WasmInstr.SimpleOp.I64_LT_S)
                .if_()
                .i32Const(1)
                .localSet(overflow)
                .end()
                .localGet(acc)
                .localGet(digit)
                .op(WasmInstr.SimpleOp.I64_SUB)
                .localSet(acc)
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end();

        c.localGet(overflow).if_().i64Const(0L).i32Const(2).return_().end();
        c.localGet(neg).if_().localGet(acc).i32Const(0).return_().end();
        c.i64Const(0L).localGet(acc).op(WasmInstr.SimpleOp.I64_SUB).i32Const(0);
    }

    // ---------------------------------------------------------------- boxing & unboxing

    void emitSimpleBox(WasmModule.FuncDef fn, int boxType, WasmInstr.SimpleOp normOp) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0);
        if (normOp != null) {
            c.op(normOp);
        }
        c.structNew(boxType);
    }

    void emitBoxBool(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0)
                .if_(ts.nonNullAnyRef())
                .globalGet(rt.gTrue)
                .else_()
                .globalGet(rt.gFalse)
                .end();
    }

    void emitBoxChar(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).i32Const(0xFFFF).op(WasmInstr.SimpleOp.I32_AND).structNew(ts.boxCharType);
    }

    private static void emitUnboxBranch(
            WasmCodeBuilder c, int boxType, WasmInstr.SimpleOp convertOp) {
        c.localGet(0).refTest(boxType).if_().localGet(0).refCast(boxType).structGet(boxType, 0);
        if (convertOp != null) {
            c.op(convertOp);
        }
        c.return_().end();
    }

    void emitUnboxIntegral(WasmModule.FuncDef fn, int exactBox, PrimitiveKind kind) {
        WasmCodeBuilder c = fn.code();
        emitUnboxBranch(c, exactBox, null);
        if (kind == PrimitiveKind.SHORT || kind == PrimitiveKind.INT) {
            emitUnboxBranch(c, ts.boxByteType, null);
        }
        if (kind == PrimitiveKind.INT) {
            emitUnboxBranch(c, ts.boxShortType, null);
        }
        rt.emitStringLit(c, "Expected " + kind.name().toLowerCase());
        c.localGet(1).call(rt.fnThrowMsg).unreachable();
    }

    void emitUnboxLong(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        emitUnboxBranch(c, ts.boxLongType, null);
        emitUnboxBranch(c, ts.boxIntType, WasmInstr.SimpleOp.I64_EXTEND_I32_S);
        emitUnboxBranch(c, ts.boxShortType, WasmInstr.SimpleOp.I64_EXTEND_I32_S);
        emitUnboxBranch(c, ts.boxByteType, WasmInstr.SimpleOp.I64_EXTEND_I32_S);
        rt.emitStringLit(c, "Expected long");
        c.localGet(1).call(rt.fnThrowMsg).unreachable();
    }

    void emitUnboxFloat(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int r = fn.addLocal("r", WasmValType.I32, 2);
        emitUnboxBranch(c, ts.boxFloatType, null);
        c.localGet(0)
                .call(rt.fnNumRank)
                .localTee(r)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .localGet(r)
                .i32Const(4)
                .op(WasmInstr.SimpleOp.I32_LE_S)
                .op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsDouble)
                .op(WasmInstr.SimpleOp.F32_DEMOTE_F64)
                .return_()
                .end();
        rt.emitStringLit(c, "Expected float");
        c.localGet(1).call(rt.fnThrowMsg).unreachable();
    }

    void emitUnboxDouble(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        emitUnboxBranch(c, ts.boxDoubleType, null);
        c.localGet(0)
                .call(rt.fnNumRank)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsDouble)
                .return_()
                .end();
        rt.emitStringLit(c, "Expected double");
        c.localGet(1).call(rt.fnThrowMsg).unreachable();
    }

    void emitUnboxBool(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        emitUnboxBranch(c, ts.boxBoolType, null);
        rt.emitStringLit(c, "Expected bool");
        c.localGet(1).call(rt.fnThrowMsg).unreachable();
    }

    void emitUnboxChar(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        emitUnboxBranch(c, ts.boxCharType, null);
        rt.emitStringLit(c, "Expected char");
        c.localGet(1).call(rt.fnThrowMsg).unreachable();
    }

    // ---------------------------------------------------------------- Java Double/Float.compare &
    // Truth

    /**
     * Exact {@code java.lang.Double.compare(a, b)}: {@code NaN == NaN}, {@code NaN > +Inf}, {@code
     * -0.0 < +0.0}.
     */
    void emitF64Compare(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ba = fn.addLocal("ba", WasmValType.I64, 2);
        int bb = fn.addLocal("bb", WasmValType.I64, 2);
        c.localGet(0).localGet(1).op(WasmInstr.SimpleOp.F64_LT).if_().i32Const(-1).return_().end();
        c.localGet(0).localGet(1).op(WasmInstr.SimpleOp.F64_GT).if_().i32Const(1).return_().end();
        // Either equal (incl. -0.0 == +0.0) or at least one is NaN
        c.localGet(0)
                .localGet(0)
                .op(WasmInstr.SimpleOp.F64_NE)
                .if_()
                // a is NaN
                .localGet(1)
                .localGet(1)
                .op(WasmInstr.SimpleOp.F64_NE)
                .if_()
                .i32Const(0)
                .return_()
                .end()
                .i32Const(1)
                .return_()
                .end();
        c.localGet(1).localGet(1).op(WasmInstr.SimpleOp.F64_NE).if_().i32Const(-1).return_().end();
        // Neither is NaN and a == b; distinguish -0.0 vs +0.0 via raw bits
        c.localGet(0).op(WasmInstr.SimpleOp.I64_REINTERPRET_F64).localSet(ba);
        c.localGet(1).op(WasmInstr.SimpleOp.I64_REINTERPRET_F64).localSet(bb);
        c.localGet(ba).localGet(bb).op(WasmInstr.SimpleOp.I64_EQ).if_().i32Const(0).return_().end();
        c.localGet(ba)
                .localGet(bb)
                .op(WasmInstr.SimpleOp.I64_LT_S)
                .if_()
                .i32Const(-1)
                .return_()
                .end();
        c.i32Const(1);
    }

    /** Exact {@code java.lang.Float.compare(a, b)}. */
    void emitF32Compare(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int ba = fn.addLocal("ba", WasmValType.I32, 2);
        int bb = fn.addLocal("bb", WasmValType.I32, 2);
        c.localGet(0).localGet(1).op(WasmInstr.SimpleOp.F32_LT).if_().i32Const(-1).return_().end();
        c.localGet(0).localGet(1).op(WasmInstr.SimpleOp.F32_GT).if_().i32Const(1).return_().end();
        c.localGet(0)
                .localGet(0)
                .op(WasmInstr.SimpleOp.F32_NE)
                .if_()
                .localGet(1)
                .localGet(1)
                .op(WasmInstr.SimpleOp.F32_NE)
                .if_()
                .i32Const(0)
                .return_()
                .end()
                .i32Const(1)
                .return_()
                .end();
        c.localGet(1).localGet(1).op(WasmInstr.SimpleOp.F32_NE).if_().i32Const(-1).return_().end();
        c.localGet(0).op(WasmInstr.SimpleOp.I32_REINTERPRET_F32).localSet(ba);
        c.localGet(1).op(WasmInstr.SimpleOp.I32_REINTERPRET_F32).localSet(bb);
        c.localGet(ba).localGet(bb).op(WasmInstr.SimpleOp.I32_EQ).if_().i32Const(0).return_().end();
        c.localGet(ba)
                .localGet(bb)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .i32Const(-1)
                .return_()
                .end();
        c.i32Const(1);
    }

    void emitTruth(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refIsNull().if_().i32Const(0).return_().end();
        c.localGet(0)
                .refTest(ts.boxBoolType)
                .if_()
                .localGet(0)
                .refCast(ts.boxBoolType)
                .structGet(ts.boxBoolType, 0)
                .return_()
                .end();
        c.localGet(0)
                .call(rt.fnNumRank)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsDouble)
                .f64Const(0.0)
                .op(WasmInstr.SimpleOp.F64_NE)
                .return_()
                .end();
        c.i32Const(1);
    }

    // ---------------------------------------------------------------- numeric helpers

    /** Returns numeric rank: 0=byte, 1=short, 2=int, 3=long, 4=float, 5=double, -1=not a number. */
    void emitNumRank(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).refTest(ts.boxIntType).if_().i32Const(2).return_().end();
        c.localGet(0).refTest(ts.boxDoubleType).if_().i32Const(5).return_().end();
        c.localGet(0).refTest(ts.boxLongType).if_().i32Const(3).return_().end();
        c.localGet(0).refTest(ts.boxFloatType).if_().i32Const(4).return_().end();
        c.localGet(0).refTest(ts.boxShortType).if_().i32Const(1).return_().end();
        c.localGet(0).refTest(ts.boxByteType).if_().i32Const(0).return_().end();
        c.i32Const(-1);
    }

    void emitNumAsLong(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0)
                .refTest(ts.boxIntType)
                .if_()
                .localGet(0)
                .refCast(ts.boxIntType)
                .structGet(ts.boxIntType, 0)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxLongType)
                .if_()
                .localGet(0)
                .refCast(ts.boxLongType)
                .structGet(ts.boxLongType, 0)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxShortType)
                .if_()
                .localGet(0)
                .refCast(ts.boxShortType)
                .structGet(ts.boxShortType, 0)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxByteType)
                .if_()
                .localGet(0)
                .refCast(ts.boxByteType)
                .structGet(ts.boxByteType, 0)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxDoubleType)
                .if_()
                .localGet(0)
                .refCast(ts.boxDoubleType)
                .structGet(ts.boxDoubleType, 0)
                .op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F64_S)
                .return_()
                .end();
        c.localGet(0)
                .refCast(ts.boxFloatType)
                .structGet(ts.boxFloatType, 0)
                .op(WasmInstr.SimpleOp.I64_TRUNC_SAT_F32_S);
    }

    void emitNumAsDouble(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0)
                .refTest(ts.boxDoubleType)
                .if_()
                .localGet(0)
                .refCast(ts.boxDoubleType)
                .structGet(ts.boxDoubleType, 0)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxIntType)
                .if_()
                .localGet(0)
                .refCast(ts.boxIntType)
                .structGet(ts.boxIntType, 0)
                .op(WasmInstr.SimpleOp.F64_CONVERT_I32_S)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxLongType)
                .if_()
                .localGet(0)
                .refCast(ts.boxLongType)
                .structGet(ts.boxLongType, 0)
                .op(WasmInstr.SimpleOp.F64_CONVERT_I64_S)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxFloatType)
                .if_()
                .localGet(0)
                .refCast(ts.boxFloatType)
                .structGet(ts.boxFloatType, 0)
                .op(WasmInstr.SimpleOp.F64_PROMOTE_F32)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.boxShortType)
                .if_()
                .localGet(0)
                .refCast(ts.boxShortType)
                .structGet(ts.boxShortType, 0)
                .op(WasmInstr.SimpleOp.F64_CONVERT_I32_S)
                .return_()
                .end();
        c.localGet(0)
                .refCast(ts.boxByteType)
                .structGet(ts.boxByteType, 0)
                .op(WasmInstr.SimpleOp.F64_CONVERT_I32_S);
    }

    void emitWidenNumeric(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int r = fn.addLocal("r", WasmValType.I32, 2);
        c.localGet(0)
                .call(rt.fnNumRank)
                .localTee(r)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(0)
                .return_()
                .end();
        c.localGet(r).localGet(1).op(WasmInstr.SimpleOp.I32_EQ).if_().localGet(0).return_().end();
        c.localGet(1)
                .i32Const(5)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsDouble)
                .call(rt.fnBoxDouble)
                .return_()
                .end();
        c.localGet(1)
                .i32Const(4)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsDouble)
                .op(WasmInstr.SimpleOp.F32_DEMOTE_F64)
                .call(rt.fnBoxFloat)
                .return_()
                .end();
        c.localGet(1)
                .i32Const(3)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsLong)
                .call(rt.fnBoxLong)
                .return_()
                .end();
        c.localGet(1)
                .i32Const(2)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsLong)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .call(rt.fnBoxInt)
                .return_()
                .end();
        c.localGet(1)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(0)
                .refAsNonNull()
                .call(rt.fnNumAsLong)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .call(rt.fnBoxShort)
                .return_()
                .end();
        c.localGet(0);
    }
}
