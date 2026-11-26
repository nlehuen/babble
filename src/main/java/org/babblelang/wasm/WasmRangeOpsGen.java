package org.babblelang.wasm;

import java.util.List;

/**
 * Generates pure-Wasm WasmGC runtime helpers for Babble first-class ranges ({@code BIP031}): {@code
 * $RangeBase}, {@code $IntRange} ({@code byte}/{@code short}/{@code int}/{@code char}), {@code
 * $LongRange}, {@code $DoubleRange} ({@code float}/{@code double}), {@code $StringRange}, and
 * {@code $RangeIter}.
 */
public final class WasmRangeOpsGen {
    public static final int KIND_BYTE = 0;
    public static final int KIND_SHORT = 1;
    public static final int KIND_INT = 2;
    public static final int KIND_LONG = 3;
    public static final int KIND_CHAR = 4;
    public static final int KIND_FLOAT = 5;
    public static final int KIND_DOUBLE = 6;
    public static final int KIND_STRING = 7;

    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;

    public final int fnValidateStepInt;
    public final int fnValidateStepLong;
    public final int fnLastInt;
    public final int fnLastLong;

    public final int fnRangeStart;
    public final int fnRangeEnd;
    public final int fnRangeIsEmpty;
    public final int fnRangeContainsInt;
    public final int fnRangeContainsLong;
    public final int fnRangeContainsDouble;
    public final int fnRangeContainsBoxed;
    public final int fnRangeStepInt;
    public final int fnRangeStepLong;
    public final int fnRangeStep;
    public final int fnRangeReversed;
    public final int fnRangeIter;
    public final int fnRangeIterHasNext;
    public final int fnRangeIterNext;

    public final int fnRangeEq;
    public final int fnRangeHash;
    public final int fnRangeFmt;

    public WasmRangeOpsGen(WasmModule mod, WasmTypeSystem ts, WasmRuntimeGen rt) {
        this.ts = ts;
        this.rt = rt;

        WasmValType refAnyNull = ts.anyRef();
        WasmValType refStr = WasmValType.ref(ts.stringType);
        WasmValType refRange = WasmValType.ref(ts.rangeBaseType);
        WasmValType refIntRange = WasmValType.ref(ts.intRangeType);
        WasmValType refLongRange = WasmValType.ref(ts.longRangeType);
        WasmValType refDoubleRange = WasmValType.ref(ts.doubleRangeType);
        WasmValType refIter = WasmValType.ref(ts.rangeIterType);

        WasmModule.FuncDef dValStepI =
                mod.addFunction(
                        "range_validate_step_i",
                        ts.internFuncType(
                                List.of(WasmValType.I32, WasmValType.I32),
                                List.of(WasmValType.I32)),
                        List.of("step", "line"));
        WasmModule.FuncDef dValStepL =
                mod.addFunction(
                        "range_validate_step_l",
                        ts.internFuncType(
                                List.of(WasmValType.I64, WasmValType.I32),
                                List.of(WasmValType.I64)),
                        List.of("step", "line"));
        WasmModule.FuncDef dLastI =
                mod.addFunction(
                        "range_last_i",
                        ts.internFuncType(
                                List.of(
                                        WasmValType.I32,
                                        WasmValType.I32,
                                        WasmValType.I32,
                                        WasmValType.I32),
                                List.of(WasmValType.I32)),
                        List.of("start", "end", "incl", "step"));
        WasmModule.FuncDef dLastL =
                mod.addFunction(
                        "range_last_l",
                        ts.internFuncType(
                                List.of(
                                        WasmValType.I64,
                                        WasmValType.I64,
                                        WasmValType.I32,
                                        WasmValType.I64),
                                List.of(WasmValType.I64)),
                        List.of("start", "end", "incl", "step"));

        WasmModule.FuncDef dStart =
                mod.addFunction(
                        "range_start",
                        ts.internFuncType(List.of(refRange), List.of(refAnyNull)),
                        List.of("r"));
        WasmModule.FuncDef dEnd =
                mod.addFunction(
                        "range_end",
                        ts.internFuncType(List.of(refRange), List.of(refAnyNull)),
                        List.of("r"));
        WasmModule.FuncDef dIsEmpty =
                mod.addFunction(
                        "range_is_empty",
                        ts.internFuncType(List.of(refRange), List.of(WasmValType.I32)),
                        List.of("r"));
        WasmModule.FuncDef dContainsI =
                mod.addFunction(
                        "range_contains_i",
                        ts.internFuncType(
                                List.of(refIntRange, WasmValType.I32), List.of(WasmValType.I32)),
                        List.of("r", "x"));
        WasmModule.FuncDef dContainsL =
                mod.addFunction(
                        "range_contains_l",
                        ts.internFuncType(
                                List.of(refLongRange, WasmValType.I64), List.of(WasmValType.I32)),
                        List.of("r", "x"));
        WasmModule.FuncDef dContainsD =
                mod.addFunction(
                        "range_contains_d",
                        ts.internFuncType(
                                List.of(refDoubleRange, WasmValType.F64), List.of(WasmValType.I32)),
                        List.of("r", "x"));
        WasmModule.FuncDef dContainsBoxed =
                mod.addFunction(
                        "range_contains_boxed",
                        ts.internFuncType(List.of(refRange, refAnyNull), List.of(WasmValType.I32)),
                        List.of("r", "x"));
        WasmModule.FuncDef dStepI =
                mod.addFunction(
                        "range_step_i",
                        ts.internFuncType(
                                List.of(refIntRange, WasmValType.I32, WasmValType.I32),
                                List.of(refIntRange)),
                        List.of("r", "step", "line"));
        WasmModule.FuncDef dStepL =
                mod.addFunction(
                        "range_step_l",
                        ts.internFuncType(
                                List.of(refLongRange, WasmValType.I64, WasmValType.I32),
                                List.of(refLongRange)),
                        List.of("r", "step", "line"));
        WasmModule.FuncDef dStep =
                mod.addFunction(
                        "range_step",
                        ts.internFuncType(
                                List.of(refRange, refAnyNull, WasmValType.I32), List.of(refRange)),
                        List.of("r", "step", "line"));
        WasmModule.FuncDef dReversed =
                mod.addFunction(
                        "range_reversed",
                        ts.internFuncType(List.of(refRange, WasmValType.I32), List.of(refRange)),
                        List.of("r", "line"));
        WasmModule.FuncDef dIter =
                mod.addFunction(
                        "range_iter",
                        ts.internFuncType(List.of(refRange, WasmValType.I32), List.of(refIter)),
                        List.of("r", "line"));
        WasmModule.FuncDef dIterHasNext =
                mod.addFunction(
                        "range_iter_has_next",
                        ts.internFuncType(List.of(refIter), List.of(WasmValType.I32)),
                        List.of("it"));
        WasmModule.FuncDef dIterNext =
                mod.addFunction(
                        "range_iter_next",
                        ts.internFuncType(List.of(refIter, WasmValType.I32), List.of(refAnyNull)),
                        List.of("it", "line"));

        WasmModule.FuncDef dEq =
                mod.addFunction(
                        "range_eq",
                        ts.internFuncType(List.of(refRange, refAnyNull), List.of(WasmValType.I32)),
                        List.of("a", "b"));
        WasmModule.FuncDef dHash =
                mod.addFunction(
                        "range_hash",
                        ts.internFuncType(List.of(refRange), List.of(WasmValType.I32)),
                        List.of("r"));
        WasmModule.FuncDef dFmt =
                mod.addFunction(
                        "range_fmt",
                        ts.internFuncType(List.of(refRange), List.of(refStr)),
                        List.of("r"));

        this.fnValidateStepInt = dValStepI.funcIndex();
        this.fnValidateStepLong = dValStepL.funcIndex();
        this.fnLastInt = dLastI.funcIndex();
        this.fnLastLong = dLastL.funcIndex();

        this.fnRangeStart = dStart.funcIndex();
        this.fnRangeEnd = dEnd.funcIndex();
        this.fnRangeIsEmpty = dIsEmpty.funcIndex();
        this.fnRangeContainsInt = dContainsI.funcIndex();
        this.fnRangeContainsLong = dContainsL.funcIndex();
        this.fnRangeContainsDouble = dContainsD.funcIndex();
        this.fnRangeContainsBoxed = dContainsBoxed.funcIndex();
        this.fnRangeStepInt = dStepI.funcIndex();
        this.fnRangeStepLong = dStepL.funcIndex();
        this.fnRangeStep = dStep.funcIndex();
        this.fnRangeReversed = dReversed.funcIndex();
        this.fnRangeIter = dIter.funcIndex();
        this.fnRangeIterHasNext = dIterHasNext.funcIndex();
        this.fnRangeIterNext = dIterNext.funcIndex();

        this.fnRangeEq = dEq.funcIndex();
        this.fnRangeHash = dHash.funcIndex();
        this.fnRangeFmt = dFmt.funcIndex();

        emitValidateStepInt(dValStepI);
        emitValidateStepLong(dValStepL);
        emitLastInt(dLastI);
        emitLastLong(dLastL);
        emitRangeBound(dStart, true);
        emitRangeBound(dEnd, false);
        emitRangeIsEmpty(dIsEmpty);
        emitRangeContainsInt(dContainsI);
        emitRangeContainsLong(dContainsL);
        emitRangeContainsDouble(dContainsD);
        emitRangeContainsBoxed(dContainsBoxed);
        emitRangeStepInt(dStepI);
        emitRangeStepLong(dStepL);
        emitRangeStep(dStep);
        emitRangeReversed(dReversed);
        emitRangeIter(dIter);
        emitRangeIterHasNext(dIterHasNext);
        emitRangeIterNext(dIterNext);
        emitRangeEq(dEq);
        emitRangeHash(dHash);
        emitRangeFmt(dFmt);
    }

    private void emitValidateStepInt(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).i32Const(0).op(WasmInstr.SimpleOp.I32_LE_S).if_();
        rt.emitStringLit(c, "Range step must be positive : ");
        c.localGet(0)
                .call(rt.fnIntToStr)
                .call(rt.fnStrConcat)
                .localGet(1)
                .call(rt.fnThrowMsg)
                .unreachable()
                .end();
        c.localGet(0);
    }

    private void emitValidateStepLong(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).i64Const(0L).op(WasmInstr.SimpleOp.I64_LE_S).if_();
        rt.emitStringLit(c, "Range step must be positive : ");
        c.localGet(0)
                .call(rt.fnLongToStr)
                .call(rt.fnStrConcat)
                .localGet(1)
                .call(rt.fnThrowMsg)
                .unreachable()
                .end();
        c.localGet(0);
    }

    private void emitLastInt(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int lb = fn.addLocal("lb", WasmValType.I64, 4);
        int span = fn.addLocal("span", WasmValType.I64, 4);
        c.localGet(1)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .localGet(2)
                .if_(WasmValType.I64)
                .i64Const(0L)
                .else_()
                .i64Const(1L)
                .end()
                .op(WasmInstr.SimpleOp.I64_SUB)
                .localTee(lb)
                .localGet(0)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .op(WasmInstr.SimpleOp.I64_SUB)
                .localSet(span);
        c.localGet(lb)
                .localGet(span)
                .localGet(3)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .op(WasmInstr.SimpleOp.I64_REM_S)
                .op(WasmInstr.SimpleOp.I64_SUB)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64);
    }

    private void emitLastLong(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int lb = fn.addLocal("lb", WasmValType.I64, 4);
        int span = fn.addLocal("span", WasmValType.I64, 4);
        c.localGet(1)
                .localGet(2)
                .if_(WasmValType.I64)
                .i64Const(0L)
                .else_()
                .i64Const(1L)
                .end()
                .op(WasmInstr.SimpleOp.I64_SUB)
                .localTee(lb)
                .localGet(0)
                .op(WasmInstr.SimpleOp.I64_SUB)
                .localSet(span);
        c.localGet(lb)
                .localGet(span)
                .localGet(3)
                .op(WasmInstr.SimpleOp.I64_REM_U)
                .op(WasmInstr.SimpleOp.I64_SUB);
    }

    private void emitRangeBound(WasmModule.FuncDef fn, boolean isStart) {
        WasmCodeBuilder c = fn.code();
        int fieldIdx = isStart ? 2 : 3;
        int k = fn.addLocal("k", WasmValType.I32, 1);
        int iv = fn.addLocal("iv", WasmValType.I32, 1);
        c.localGet(0).structGet(ts.rangeBaseType, 0).localSet(k);

        c.localGet(0)
                .refTest(ts.intRangeType)
                .if_()
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, fieldIdx)
                .localSet(iv)
                .localGet(k)
                .i32Const(KIND_BYTE)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(iv)
                .call(rt.fnBoxByte)
                .return_()
                .end()
                .localGet(k)
                .i32Const(KIND_SHORT)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(iv)
                .call(rt.fnBoxShort)
                .return_()
                .end()
                .localGet(k)
                .i32Const(KIND_CHAR)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(iv)
                .call(rt.fnBoxChar)
                .return_()
                .end()
                .localGet(iv)
                .call(rt.fnBoxInt)
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.longRangeType)
                .if_()
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, fieldIdx)
                .call(rt.fnBoxLong)
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.doubleRangeType)
                .if_()
                .localGet(k)
                .i32Const(KIND_FLOAT)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(0)
                .refCast(ts.doubleRangeType)
                .structGet(ts.doubleRangeType, fieldIdx)
                .op(WasmInstr.SimpleOp.F32_DEMOTE_F64)
                .call(rt.fnBoxFloat)
                .return_()
                .end()
                .localGet(0)
                .refCast(ts.doubleRangeType)
                .structGet(ts.doubleRangeType, fieldIdx)
                .call(rt.fnBoxDouble)
                .return_()
                .end();

        c.localGet(0).refCast(ts.stringRangeType).structGet(ts.stringRangeType, fieldIdx);
    }

    private void emitRangeIsEmpty(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int incl = fn.addLocal("incl", WasmValType.I32, 1);
        int cmp = fn.addLocal("cmp", WasmValType.I32, 1);
        c.localGet(0).structGet(ts.rangeBaseType, 1).localSet(incl);

        c.localGet(0)
                .refTest(ts.intRangeType)
                .if_()
                .localGet(incl)
                .if_(WasmValType.I32)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 2)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 3)
                .op(WasmInstr.SimpleOp.I32_GT_S)
                .else_()
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 2)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 3)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .end()
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.longRangeType)
                .if_()
                .localGet(incl)
                .if_(WasmValType.I32)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 2)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 3)
                .op(WasmInstr.SimpleOp.I64_GT_S)
                .else_()
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 2)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 3)
                .op(WasmInstr.SimpleOp.I64_GE_S)
                .end()
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.doubleRangeType)
                .if_()
                .localGet(incl)
                .if_(WasmValType.I32)
                .localGet(0)
                .refCast(ts.doubleRangeType)
                .structGet(ts.doubleRangeType, 2)
                .localGet(0)
                .refCast(ts.doubleRangeType)
                .structGet(ts.doubleRangeType, 3)
                .op(WasmInstr.SimpleOp.F64_LE)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .else_()
                .localGet(0)
                .refCast(ts.doubleRangeType)
                .structGet(ts.doubleRangeType, 2)
                .localGet(0)
                .refCast(ts.doubleRangeType)
                .structGet(ts.doubleRangeType, 3)
                .op(WasmInstr.SimpleOp.F64_LT)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .end()
                .return_()
                .end();

        c.localGet(0)
                .refCast(ts.stringRangeType)
                .structGet(ts.stringRangeType, 2)
                .localGet(0)
                .refCast(ts.stringRangeType)
                .structGet(ts.stringRangeType, 3)
                .call(rt.fnStrCmp)
                .localSet(cmp)
                .localGet(incl)
                .if_(WasmValType.I32)
                .localGet(cmp)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GT_S)
                .else_()
                .localGet(cmp)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .end();
    }

    private void emitRangeContainsInt(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int s = fn.addLocal("s", WasmValType.I32, 2);
        int e = fn.addLocal("e", WasmValType.I32, 2);
        int step = fn.addLocal("step", WasmValType.I32, 2);
        c.localGet(0).structGet(ts.intRangeType, 2).localSet(s);
        c.localGet(0).structGet(ts.intRangeType, 3).localSet(e);
        c.localGet(1).localGet(s).op(WasmInstr.SimpleOp.I32_LT_S).if_().i32Const(0).return_().end();
        c.localGet(0)
                .structGet(ts.intRangeType, 1)
                .if_()
                .localGet(1)
                .localGet(e)
                .op(WasmInstr.SimpleOp.I32_GT_S)
                .if_()
                .i32Const(0)
                .return_()
                .end()
                .else_()
                .localGet(1)
                .localGet(e)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_()
                .i32Const(0)
                .return_()
                .end()
                .end();
        c.localGet(0)
                .structGet(ts.intRangeType, 4)
                .localTee(step)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .i32Const(1)
                .return_()
                .end();
        c.localGet(1)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .localGet(s)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .op(WasmInstr.SimpleOp.I64_SUB)
                .localGet(step)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .op(WasmInstr.SimpleOp.I64_REM_S)
                .i64Const(0L)
                .op(WasmInstr.SimpleOp.I64_EQ);
    }

    private void emitRangeContainsLong(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int s = fn.addLocal("s", WasmValType.I64, 2);
        int e = fn.addLocal("e", WasmValType.I64, 2);
        int step = fn.addLocal("step", WasmValType.I64, 2);
        c.localGet(0).structGet(ts.longRangeType, 2).localSet(s);
        c.localGet(0).structGet(ts.longRangeType, 3).localSet(e);
        c.localGet(1).localGet(s).op(WasmInstr.SimpleOp.I64_LT_S).if_().i32Const(0).return_().end();
        c.localGet(0)
                .structGet(ts.longRangeType, 1)
                .if_()
                .localGet(1)
                .localGet(e)
                .op(WasmInstr.SimpleOp.I64_GT_S)
                .if_()
                .i32Const(0)
                .return_()
                .end()
                .else_()
                .localGet(1)
                .localGet(e)
                .op(WasmInstr.SimpleOp.I64_GE_S)
                .if_()
                .i32Const(0)
                .return_()
                .end()
                .end();
        c.localGet(0)
                .structGet(ts.longRangeType, 4)
                .localTee(step)
                .i64Const(1L)
                .op(WasmInstr.SimpleOp.I64_EQ)
                .if_()
                .i32Const(1)
                .return_()
                .end();
        c.localGet(1)
                .localGet(s)
                .op(WasmInstr.SimpleOp.I64_SUB)
                .localGet(step)
                .op(WasmInstr.SimpleOp.I64_REM_U)
                .i64Const(0L)
                .op(WasmInstr.SimpleOp.I64_EQ);
    }

    private void emitRangeContainsDouble(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(1)
                .localGet(0)
                .structGet(ts.doubleRangeType, 2)
                .op(WasmInstr.SimpleOp.F64_GE)
                .if_()
                .localGet(0)
                .structGet(ts.doubleRangeType, 1)
                .if_()
                .localGet(1)
                .localGet(0)
                .structGet(ts.doubleRangeType, 3)
                .op(WasmInstr.SimpleOp.F64_LE)
                .return_()
                .else_()
                .localGet(1)
                .localGet(0)
                .structGet(ts.doubleRangeType, 3)
                .op(WasmInstr.SimpleOp.F64_LT)
                .return_()
                .end()
                .end();
        c.i32Const(0);
    }

    private void emitRangeContainsBoxed(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int k = fn.addLocal("k", WasmValType.I32, 2);
        int cmp = fn.addLocal("cmp", WasmValType.I32, 2);
        c.localGet(1).refIsNull().if_().i32Const(0).return_().end();
        c.localGet(0).structGet(ts.rangeBaseType, 0).localSet(k);

        c.localGet(0)
                .refTest(ts.intRangeType)
                .if_()
                .localGet(k)
                .i32Const(KIND_BYTE)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(1)
                .refTest(ts.boxByteType)
                .if_()
                .localGet(0)
                .refCast(ts.intRangeType)
                .localGet(1)
                .refCast(ts.boxByteType)
                .structGet(ts.boxByteType, 0)
                .call(fnRangeContainsInt)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end()
                .localGet(k)
                .i32Const(KIND_SHORT)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(1)
                .refTest(ts.boxShortType)
                .if_()
                .localGet(0)
                .refCast(ts.intRangeType)
                .localGet(1)
                .refCast(ts.boxShortType)
                .structGet(ts.boxShortType, 0)
                .call(fnRangeContainsInt)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end()
                .localGet(k)
                .i32Const(KIND_CHAR)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(1)
                .refTest(ts.boxCharType)
                .if_()
                .localGet(0)
                .refCast(ts.intRangeType)
                .localGet(1)
                .refCast(ts.boxCharType)
                .structGet(ts.boxCharType, 0)
                .call(fnRangeContainsInt)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end()
                .localGet(1)
                .refTest(ts.boxIntType)
                .if_()
                .localGet(0)
                .refCast(ts.intRangeType)
                .localGet(1)
                .refCast(ts.boxIntType)
                .structGet(ts.boxIntType, 0)
                .call(fnRangeContainsInt)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.longRangeType)
                .if_()
                .localGet(1)
                .refTest(ts.boxLongType)
                .if_()
                .localGet(0)
                .refCast(ts.longRangeType)
                .localGet(1)
                .refCast(ts.boxLongType)
                .structGet(ts.boxLongType, 0)
                .call(fnRangeContainsLong)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.doubleRangeType)
                .if_()
                .localGet(k)
                .i32Const(KIND_FLOAT)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(1)
                .refTest(ts.boxFloatType)
                .if_()
                .localGet(0)
                .refCast(ts.doubleRangeType)
                .localGet(1)
                .refCast(ts.boxFloatType)
                .structGet(ts.boxFloatType, 0)
                .op(WasmInstr.SimpleOp.F64_PROMOTE_F32)
                .call(fnRangeContainsDouble)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end()
                .localGet(1)
                .refTest(ts.boxDoubleType)
                .if_()
                .localGet(0)
                .refCast(ts.doubleRangeType)
                .localGet(1)
                .refCast(ts.boxDoubleType)
                .structGet(ts.boxDoubleType, 0)
                .call(fnRangeContainsDouble)
                .return_()
                .end()
                .i32Const(0)
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.stringRangeType)
                .if_()
                .localGet(1)
                .refTest(ts.stringType)
                .if_()
                .localGet(1)
                .refCast(ts.stringType)
                .localGet(0)
                .refCast(ts.stringRangeType)
                .structGet(ts.stringRangeType, 2)
                .call(rt.fnStrCmp)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_()
                .localGet(1)
                .refCast(ts.stringType)
                .localGet(0)
                .refCast(ts.stringRangeType)
                .structGet(ts.stringRangeType, 3)
                .call(rt.fnStrCmp)
                .localSet(cmp)
                .localGet(0)
                .structGet(ts.rangeBaseType, 1)
                .if_(WasmValType.I32)
                .localGet(cmp)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_LE_S)
                .else_()
                .localGet(cmp)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .end()
                .return_()
                .end()
                .end()
                .end();

        c.i32Const(0);
    }

    private void emitRangeStepInt(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int valid = fn.addLocal("valid", WasmValType.I32, 3);
        c.localGet(1).localGet(2).call(fnValidateStepInt).localSet(valid);
        c.localGet(0)
                .structGet(ts.intRangeType, 0)
                .localGet(0)
                .structGet(ts.intRangeType, 1)
                .localGet(0)
                .structGet(ts.intRangeType, 2)
                .localGet(0)
                .structGet(ts.intRangeType, 3)
                .localGet(valid)
                .localGet(0)
                .structGet(ts.intRangeType, 5)
                .structNew(ts.intRangeType);
    }

    private void emitRangeStepLong(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int valid = fn.addLocal("valid", WasmValType.I64, 3);
        c.localGet(1).localGet(2).call(fnValidateStepLong).localSet(valid);
        c.localGet(0)
                .structGet(ts.longRangeType, 0)
                .localGet(0)
                .structGet(ts.longRangeType, 1)
                .localGet(0)
                .structGet(ts.longRangeType, 2)
                .localGet(0)
                .structGet(ts.longRangeType, 3)
                .localGet(valid)
                .localGet(0)
                .structGet(ts.longRangeType, 5)
                .structNew(ts.longRangeType);
    }

    private void emitRangeStep(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0)
                .refTest(ts.intRangeType)
                .if_()
                .localGet(0)
                .refCast(ts.intRangeType)
                .localGet(1)
                .localGet(2)
                .call(rt.fnUnboxInt)
                .localGet(2)
                .call(fnRangeStepInt)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.longRangeType)
                .if_()
                .localGet(0)
                .refCast(ts.longRangeType)
                .localGet(1)
                .localGet(2)
                .call(rt.fnUnboxLong)
                .localGet(2)
                .call(fnRangeStepLong)
                .return_()
                .end();
        rt.emitStringLit(c, "Range is not discrete");
        c.localGet(2).call(rt.fnThrowMsg).unreachable();
    }

    private void emitRangeReversed(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0)
                .refTest(ts.intRangeType)
                .if_()
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 0)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 1)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 2)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 3)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 4)
                .i32Const(1)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 5)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .structNew(ts.intRangeType)
                .return_()
                .end();
        c.localGet(0)
                .refTest(ts.longRangeType)
                .if_()
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 0)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 1)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 2)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 3)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 4)
                .i32Const(1)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 5)
                .op(WasmInstr.SimpleOp.I32_SUB)
                .structNew(ts.longRangeType)
                .return_()
                .end();
        rt.emitStringLit(c, "Range is not discrete");
        c.localGet(1).call(rt.fnThrowMsg).unreachable();
    }

    private void emitRangeIter(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int k = fn.addLocal("k", WasmValType.I32, 2);
        int s = fn.addLocal("s", WasmValType.I64, 2);
        int last = fn.addLocal("last", WasmValType.I64, 2);
        int step = fn.addLocal("step", WasmValType.I64, 2);
        int desc = fn.addLocal("desc", WasmValType.I32, 2);
        c.localGet(0).structGet(ts.rangeBaseType, 0).localSet(k);

        c.localGet(0)
                .refTest(ts.intRangeType)
                .if_()
                .localGet(0)
                .call(fnRangeIsEmpty)
                .if_()
                .localGet(k)
                .i64Const(0L)
                .i64Const(0L)
                .i64Const(1L)
                .i32Const(0)
                .i32Const(0)
                .structNew(ts.rangeIterType)
                .return_()
                .end()
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 2)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .localSet(s)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 4)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .localSet(step)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 5)
                .localSet(desc)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 2)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 3)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 1)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 4)
                .call(fnLastInt)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_S)
                .localSet(last)
                .localGet(k)
                .localGet(last)
                .localGet(s)
                .localGet(desc)
                .select()
                .localGet(s)
                .localGet(last)
                .localGet(desc)
                .select()
                .localGet(step)
                .localGet(desc)
                .i32Const(1)
                .structNew(ts.rangeIterType)
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.longRangeType)
                .if_()
                .localGet(0)
                .call(fnRangeIsEmpty)
                .if_()
                .localGet(k)
                .i64Const(0L)
                .i64Const(0L)
                .i64Const(1L)
                .i32Const(0)
                .i32Const(0)
                .structNew(ts.rangeIterType)
                .return_()
                .end()
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 2)
                .localSet(s)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 4)
                .localSet(step)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 5)
                .localSet(desc)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 2)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 3)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 1)
                .localGet(step)
                .call(fnLastLong)
                .localSet(last)
                .localGet(k)
                .localGet(last)
                .localGet(s)
                .localGet(desc)
                .select()
                .localGet(s)
                .localGet(last)
                .localGet(desc)
                .select()
                .localGet(step)
                .localGet(desc)
                .i32Const(1)
                .structNew(ts.rangeIterType)
                .return_()
                .end();

        rt.emitStringLit(c, "Range is not discrete");
        c.localGet(1).call(rt.fnThrowMsg).unreachable();
    }

    private void emitRangeIterHasNext(WasmModule.FuncDef fn) {
        fn.code().localGet(0).structGet(ts.rangeIterType, 5);
    }

    private void emitRangeIterNext(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int cur = fn.addLocal("cur", WasmValType.I64, 2);
        int k = fn.addLocal("k", WasmValType.I32, 2);
        c.localGet(0).structGet(ts.rangeIterType, 5).op(WasmInstr.SimpleOp.I32_EQZ).if_();
        rt.emitStringLit(c, "No more elements");
        c.localGet(1).call(rt.fnThrowMsg).unreachable().end();

        c.localGet(0).structGet(ts.rangeIterType, 1).localSet(cur);
        c.localGet(cur)
                .localGet(0)
                .structGet(ts.rangeIterType, 2)
                .op(WasmInstr.SimpleOp.I64_EQ)
                .if_()
                .localGet(0)
                .i32Const(0)
                .structSet(ts.rangeIterType, 5)
                .else_()
                .localGet(0)
                .localGet(0)
                .structGet(ts.rangeIterType, 4)
                .if_(WasmValType.I64)
                .localGet(cur)
                .localGet(0)
                .structGet(ts.rangeIterType, 3)
                .op(WasmInstr.SimpleOp.I64_SUB)
                .else_()
                .localGet(cur)
                .localGet(0)
                .structGet(ts.rangeIterType, 3)
                .op(WasmInstr.SimpleOp.I64_ADD)
                .end()
                .structSet(ts.rangeIterType, 1)
                .end();

        c.localGet(0).structGet(ts.rangeIterType, 0).localSet(k);
        c.localGet(k)
                .i32Const(KIND_LONG)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(cur)
                .call(rt.fnBoxLong)
                .return_()
                .end();
        c.localGet(k)
                .i32Const(KIND_BYTE)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(cur)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .call(rt.fnBoxByte)
                .return_()
                .end();
        c.localGet(k)
                .i32Const(KIND_SHORT)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(cur)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .call(rt.fnBoxShort)
                .return_()
                .end();
        c.localGet(k)
                .i32Const(KIND_CHAR)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .if_()
                .localGet(cur)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .call(rt.fnBoxChar)
                .return_()
                .end();
        c.localGet(cur).op(WasmInstr.SimpleOp.I32_WRAP_I64).call(rt.fnBoxInt);
    }

    private void emitRangeEq(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int rb = fn.addLocal("rb", WasmValType.refNull(ts.rangeBaseType), 2);
        c.localGet(1)
                .refTest(ts.rangeBaseType)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .i32Const(0)
                .return_()
                .end();
        c.localGet(1).refCast(ts.rangeBaseType).localSet(rb);
        c.localGet(0)
                .structGet(ts.rangeBaseType, 0)
                .localGet(rb)
                .refAsNonNull()
                .structGet(ts.rangeBaseType, 0)
                .op(WasmInstr.SimpleOp.I32_NE)
                .if_()
                .i32Const(0)
                .return_()
                .end();
        c.localGet(0)
                .structGet(ts.rangeBaseType, 1)
                .localGet(rb)
                .refAsNonNull()
                .structGet(ts.rangeBaseType, 1)
                .op(WasmInstr.SimpleOp.I32_NE)
                .if_()
                .i32Const(0)
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.intRangeType)
                .if_()
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 2)
                .localGet(rb)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 2)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 3)
                .localGet(rb)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 3)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .op(WasmInstr.SimpleOp.I32_AND)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 4)
                .localGet(rb)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 4)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .op(WasmInstr.SimpleOp.I32_AND)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 5)
                .localGet(rb)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 5)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .op(WasmInstr.SimpleOp.I32_AND)
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.longRangeType)
                .if_()
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 2)
                .localGet(rb)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 2)
                .op(WasmInstr.SimpleOp.I64_EQ)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 3)
                .localGet(rb)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 3)
                .op(WasmInstr.SimpleOp.I64_EQ)
                .op(WasmInstr.SimpleOp.I32_AND)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 4)
                .localGet(rb)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 4)
                .op(WasmInstr.SimpleOp.I64_EQ)
                .op(WasmInstr.SimpleOp.I32_AND)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 5)
                .localGet(rb)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 5)
                .op(WasmInstr.SimpleOp.I32_EQ)
                .op(WasmInstr.SimpleOp.I32_AND)
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.doubleRangeType)
                .if_()
                .localGet(0)
                .refCast(ts.doubleRangeType)
                .structGet(ts.doubleRangeType, 2)
                .localGet(rb)
                .refCast(ts.doubleRangeType)
                .structGet(ts.doubleRangeType, 2)
                .call(rt.fnF64Compare)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .localGet(0)
                .refCast(ts.doubleRangeType)
                .structGet(ts.doubleRangeType, 3)
                .localGet(rb)
                .refCast(ts.doubleRangeType)
                .structGet(ts.doubleRangeType, 3)
                .call(rt.fnF64Compare)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .op(WasmInstr.SimpleOp.I32_AND)
                .return_()
                .end();

        c.localGet(0)
                .refCast(ts.stringRangeType)
                .structGet(ts.stringRangeType, 2)
                .localGet(rb)
                .refCast(ts.stringRangeType)
                .structGet(ts.stringRangeType, 2)
                .call(rt.fnStrEq)
                .localGet(0)
                .refCast(ts.stringRangeType)
                .structGet(ts.stringRangeType, 3)
                .localGet(rb)
                .refCast(ts.stringRangeType)
                .structGet(ts.stringRangeType, 3)
                .call(rt.fnStrEq)
                .op(WasmInstr.SimpleOp.I32_AND);
    }

    private void emitRangeHash(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int h = fn.addLocal("h", WasmValType.I32, 1);
        c.localGet(0)
                .structGet(ts.rangeBaseType, 0)
                .i32Const(31)
                .op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(0)
                .structGet(ts.rangeBaseType, 1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .i32Const(31)
                .op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(0)
                .call(fnRangeStart)
                .call(rt.fnHashCode)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .i32Const(31)
                .op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(0)
                .call(fnRangeEnd)
                .call(rt.fnHashCode)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(h);

        c.localGet(0)
                .refTest(ts.intRangeType)
                .if_()
                .localGet(h)
                .i32Const(31)
                .op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 4)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .i32Const(31)
                .op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 5)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .return_()
                .end();

        c.localGet(0)
                .refTest(ts.longRangeType)
                .if_()
                .localGet(h)
                .i32Const(31)
                .op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 4)
                .op(WasmInstr.SimpleOp.I32_WRAP_I64)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .i32Const(31)
                .op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 5)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .return_()
                .end();

        c.localGet(h);
    }

    private void emitRangeFmt(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int out = fn.addLocal("out", WasmValType.refNull(ts.stringType), 1);
        int hasStep = fn.addLocal("hasStep", WasmValType.I32, 1);
        int isDesc = fn.addLocal("isDesc", WasmValType.I32, 1);
        c.localGet(0).call(fnRangeStart).call(rt.fnFormat);
        c.localGet(0).structGet(ts.rangeBaseType, 1).if_(WasmValType.ref(ts.stringType));
        rt.emitStringLit(c, "..");
        c.else_();
        rt.emitStringLit(c, "..<");
        c.end().call(rt.fnStrConcat);
        c.localGet(0).call(fnRangeEnd).call(rt.fnFormat).call(rt.fnStrConcat).localSet(out);

        c.localGet(0)
                .refTest(ts.intRangeType)
                .if_()
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 4)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_NE)
                .localSet(hasStep)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 5)
                .localSet(isDesc)
                .localGet(hasStep)
                .localGet(isDesc)
                .op(WasmInstr.SimpleOp.I32_OR)
                .if_();
        rt.emitStringLit(c, "(");
        c.localGet(out).refAsNonNull().call(rt.fnStrConcat);
        rt.emitStringLit(c, ")");
        c.call(rt.fnStrConcat).localSet(out).localGet(hasStep).if_().localGet(out).refAsNonNull();
        rt.emitStringLit(c, ".step(");
        c.call(rt.fnStrConcat)
                .localGet(0)
                .refCast(ts.intRangeType)
                .structGet(ts.intRangeType, 4)
                .call(rt.fnIntToStr)
                .call(rt.fnStrConcat);
        rt.emitStringLit(c, ")");
        c.call(rt.fnStrConcat)
                .localSet(out)
                .end()
                .localGet(isDesc)
                .if_()
                .localGet(out)
                .refAsNonNull();
        rt.emitStringLit(c, ".reversed()");
        c.call(rt.fnStrConcat).localSet(out).end().end().end();

        c.localGet(0)
                .refTest(ts.longRangeType)
                .if_()
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 4)
                .i64Const(1L)
                .op(WasmInstr.SimpleOp.I64_NE)
                .localSet(hasStep)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 5)
                .localSet(isDesc)
                .localGet(hasStep)
                .localGet(isDesc)
                .op(WasmInstr.SimpleOp.I32_OR)
                .if_();
        rt.emitStringLit(c, "(");
        c.localGet(out).refAsNonNull().call(rt.fnStrConcat);
        rt.emitStringLit(c, ")");
        c.call(rt.fnStrConcat).localSet(out).localGet(hasStep).if_().localGet(out).refAsNonNull();
        rt.emitStringLit(c, ".step(");
        c.call(rt.fnStrConcat)
                .localGet(0)
                .refCast(ts.longRangeType)
                .structGet(ts.longRangeType, 4)
                .call(rt.fnLongToStr)
                .call(rt.fnStrConcat);
        rt.emitStringLit(c, ")");
        c.call(rt.fnStrConcat)
                .localSet(out)
                .end()
                .localGet(isDesc)
                .if_()
                .localGet(out)
                .refAsNonNull();
        rt.emitStringLit(c, ".reversed()");
        c.call(rt.fnStrConcat).localSet(out).end().end().end();

        c.localGet(out).refAsNonNull();
    }
}
