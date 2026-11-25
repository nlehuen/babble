package org.babblelang.wasm;

import java.util.List;

/**
 * Generates portable {@code string} operations over WasmGC {@code $String} ({@code (array (mut i16))},
 * {@code BIP025-D4}, {@code BIP025-I3}) with exact UTF-16 Java {@code String} semantics.
 */
public final class WasmStringOpsGen {
    private final WasmModule mod;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmArrayOpsGen arrays;

    public final int fnCharAt;
    public final int fnSubstring;
    public final int fnIndexOfChar;
    public final int fnIndexOfStr;
    public final int fnLastIndexOfChar;
    public final int fnLastIndexOfStr;
    public final int fnStartsWith;
    public final int fnEndsWith;
    public final int fnReplaceChar;
    public final int fnReplaceStr;
    public final int fnTrim;
    public final int fnToLower;
    public final int fnToUpper;
    public final int fnEqualsIgnoreCase;
    public final int fnCompareTo;
    public final int fnRepeat;
    public final int fnIsBlank;
    public final int fnSplit;
    public final int fnToCharArray;

    public WasmStringOpsGen(WasmModule mod, WasmTypeSystem ts, WasmRuntimeGen rt, WasmArrayOpsGen arrays) {
        this.mod = mod;
        this.ts = ts;
        this.rt = rt;
        this.arrays = arrays;

        WasmValType refStr = WasmValType.ref(ts.stringType);
        WasmValType refAny = ts.anyRef();

        WasmModule.FuncDef dCharAt = mod.addFunction("str_charAt",
                ts.internFuncType(List.of(refStr, WasmValType.I32, WasmValType.I32), List.of(WasmValType.I32)),
                List.of("s", "idx", "line"));
        WasmModule.FuncDef dSub = mod.addFunction("str_substring",
                ts.internFuncType(List.of(refStr, WasmValType.I32, WasmValType.I32, WasmValType.I32), List.of(refStr)),
                List.of("s", "start", "end", "line"));
        WasmModule.FuncDef dIdxChar = mod.addFunction("str_indexOfChar",
                ts.internFuncType(List.of(refStr, WasmValType.I32, WasmValType.I32), List.of(WasmValType.I32)),
                List.of("s", "ch", "from"));
        WasmModule.FuncDef dIdxStr = mod.addFunction("str_indexOfStr",
                ts.internFuncType(List.of(refStr, refStr, WasmValType.I32), List.of(WasmValType.I32)),
                List.of("s", "needle", "from"));
        WasmModule.FuncDef dLastChar = mod.addFunction("str_lastIndexOfChar",
                ts.internFuncType(List.of(refStr, WasmValType.I32, WasmValType.I32), List.of(WasmValType.I32)),
                List.of("s", "ch", "from"));
        WasmModule.FuncDef dLastStr = mod.addFunction("str_lastIndexOfStr",
                ts.internFuncType(List.of(refStr, refStr, WasmValType.I32), List.of(WasmValType.I32)),
                List.of("s", "needle", "from"));
        WasmModule.FuncDef dStarts = mod.addFunction("str_startsWith",
                ts.internFuncType(List.of(refStr, refStr, WasmValType.I32), List.of(WasmValType.I32)),
                List.of("s", "prefix", "offset"));
        WasmModule.FuncDef dEnds = mod.addFunction("str_endsWith",
                ts.internFuncType(List.of(refStr, refStr), List.of(WasmValType.I32)),
                List.of("s", "suffix"));
        WasmModule.FuncDef dRepChar = mod.addFunction("str_replaceChar",
                ts.internFuncType(List.of(refStr, WasmValType.I32, WasmValType.I32), List.of(refStr)),
                List.of("s", "oldCh", "newCh"));
        WasmModule.FuncDef dRepStr = mod.addFunction("str_replaceStr",
                ts.internFuncType(List.of(refStr, refStr, refStr), List.of(refStr)),
                List.of("s", "target", "repl"));
        WasmModule.FuncDef dTrim = mod.addFunction("str_trim",
                ts.internFuncType(List.of(refStr), List.of(refStr)), List.of("s"));
        WasmModule.FuncDef dLower = mod.addFunction("str_toLower",
                ts.internFuncType(List.of(refStr), List.of(refStr)), List.of("s"));
        WasmModule.FuncDef dUpper = mod.addFunction("str_toUpper",
                ts.internFuncType(List.of(refStr), List.of(refStr)), List.of("s"));
        WasmModule.FuncDef dEqIc = mod.addFunction("str_equalsIgnoreCase",
                ts.internFuncType(List.of(refStr, refAny), List.of(WasmValType.I32)), List.of("a", "b"));
        WasmModule.FuncDef dCmp = mod.addFunction("str_compareTo",
                ts.internFuncType(List.of(refStr, refStr), List.of(WasmValType.I32)), List.of("a", "b"));
        WasmModule.FuncDef dRepeat = mod.addFunction("str_repeat",
                ts.internFuncType(List.of(refStr, WasmValType.I32, WasmValType.I32), List.of(refStr)),
                List.of("s", "count", "line"));
        WasmModule.FuncDef dBlank = mod.addFunction("str_isBlank",
                ts.internFuncType(List.of(refStr), List.of(WasmValType.I32)), List.of("s"));
        WasmModule.FuncDef dSplit = mod.addFunction("str_split",
                ts.internFuncType(List.of(refStr, refStr), List.of(WasmValType.ref(ts.refArrayType))),
                List.of("s", "delim"));
        WasmModule.FuncDef dChars = mod.addFunction("str_toCharArray",
                ts.internFuncType(List.of(refStr), List.of(WasmValType.ref(ts.charArrayType))),
                List.of("s"));

        this.fnCharAt = dCharAt.funcIndex();
        this.fnSubstring = dSub.funcIndex();
        this.fnIndexOfChar = dIdxChar.funcIndex();
        this.fnIndexOfStr = dIdxStr.funcIndex();
        this.fnLastIndexOfChar = dLastChar.funcIndex();
        this.fnLastIndexOfStr = dLastStr.funcIndex();
        this.fnStartsWith = dStarts.funcIndex();
        this.fnEndsWith = dEnds.funcIndex();
        this.fnReplaceChar = dRepChar.funcIndex();
        this.fnReplaceStr = dRepStr.funcIndex();
        this.fnTrim = dTrim.funcIndex();
        this.fnToLower = dLower.funcIndex();
        this.fnToUpper = dUpper.funcIndex();
        this.fnEqualsIgnoreCase = dEqIc.funcIndex();
        this.fnCompareTo = dCmp.funcIndex();
        this.fnRepeat = dRepeat.funcIndex();
        this.fnIsBlank = dBlank.funcIndex();
        this.fnSplit = dSplit.funcIndex();
        this.fnToCharArray = dChars.funcIndex();

        emitCharAt(dCharAt);
        emitSubstring(dSub);
        emitIndexOfChar(dIdxChar);
        emitIndexOfStr(dIdxStr);
        emitLastIndexOfChar(dLastChar);
        emitLastIndexOfStr(dLastStr);
        emitStartsWith(dStarts);
        emitEndsWith(dEnds);
        emitReplaceChar(dRepChar);
        emitReplaceStr(dRepStr);
        emitTrim(dTrim);
        emitCaseConvert(dLower, true);
        emitCaseConvert(dUpper, false);
        emitEqualsIgnoreCase(dEqIc);
        emitCompareTo(dCmp);
        emitRepeat(dRepeat);
        emitIsBlank(dBlank);
        emitSplit(dSplit);
        emitToCharArray(dChars);
    }

    private void emitCharAt(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 3);
        c.localGet(0).structGet(ts.stringType, 0).localTee(arr).arrayLen();
        c.localGet(1).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S);
        c.localGet(1).localGet(arr).refAsNonNull().arrayLen().op(WasmInstr.SimpleOp.I32_GE_S);
        c.op(WasmInstr.SimpleOp.I32_OR).if_();
        rt.emitStringLit(c, "String index out of bounds");
        c.localGet(2).call(rt.fnThrowMsg).unreachable().end();
        c.drop().localGet(arr).refAsNonNull().localGet(1).arrayGetU(ts.charArrType);
    }

    private void emitSubstring(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 4);
        int len = fn.addLocal("len", WasmValType.I32, 4);
        int subLen = fn.addLocal("subLen", WasmValType.I32, 4);
        int out = fn.addLocal("out", WasmValType.refNull(ts.charArrType), 4);
        c.localGet(0).structGet(ts.stringType, 0).localTee(arr).arrayLen().localSet(len);
        // Bounds: start < 0 || end > len || start > end
        c.localGet(1).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S)
                .localGet(2).localGet(len).op(WasmInstr.SimpleOp.I32_GT_S).op(WasmInstr.SimpleOp.I32_OR)
                .localGet(1).localGet(2).op(WasmInstr.SimpleOp.I32_GT_S).op(WasmInstr.SimpleOp.I32_OR)
                .if_();
        rt.emitStringLit(c, "String index out of bounds");
        c.localGet(3).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(2).localGet(1).op(WasmInstr.SimpleOp.I32_SUB).localTee(subLen)
                .arrayNewDefault(ts.charArrType).localTee(out)
                .i32Const(0).localGet(arr).refAsNonNull().localGet(1).localGet(subLen)
                .arrayCopy(ts.charArrType, ts.charArrType);
        c.localGet(out).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    private void emitIndexOfChar(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 3);
        int len = fn.addLocal("len", WasmValType.I32, 3);
        int i = fn.addLocal("i", WasmValType.I32, 3);
        c.localGet(0).structGet(ts.stringType, 0).localTee(arr).arrayLen().localSet(len);
        c.localGet(2).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_().i32Const(0).localSet(i)
                .else_().localGet(2).localSet(i).end();
        c.loop()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(arr).refAsNonNull().localGet(i).arrayGetU(ts.charArrType)
                .localGet(1).op(WasmInstr.SimpleOp.I32_EQ).if_().localGet(i).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end();
        c.i32Const(-1);
    }

    private void emitIndexOfStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int sArr = fn.addLocal("sArr", WasmValType.refNull(ts.charArrType), 3);
        int nArr = fn.addLocal("nArr", WasmValType.refNull(ts.charArrType), 3);
        int sLen = fn.addLocal("sLen", WasmValType.I32, 3);
        int nLen = fn.addLocal("nLen", WasmValType.I32, 3);
        int i = fn.addLocal("i", WasmValType.I32, 3);
        int j = fn.addLocal("j", WasmValType.I32, 3);
        int limit = fn.addLocal("limit", WasmValType.I32, 3);
        int ok = fn.addLocal("ok", WasmValType.I32, 3);

        c.localGet(0).structGet(ts.stringType, 0).localTee(sArr).arrayLen().localSet(sLen);
        c.localGet(1).structGet(ts.stringType, 0).localTee(nArr).arrayLen().localSet(nLen);
        c.localGet(2).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_().i32Const(0).localSet(i)
                .else_().localGet(2).localSet(i).end();
        c.localGet(nLen).op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(i).localGet(sLen).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                .localGet(sLen).return_().end()
                .localGet(i).return_().end();
        c.localGet(sLen).localGet(nLen).op(WasmInstr.SimpleOp.I32_SUB).localSet(limit);
        c.loop()
                .localGet(i).localGet(limit).op(WasmInstr.SimpleOp.I32_LE_S).if_()
                .i32Const(1).localSet(ok).i32Const(0).localSet(j)
                .block().loop()
                .localGet(j).localGet(nLen).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(sArr).refAsNonNull().localGet(i).localGet(j).op(WasmInstr.SimpleOp.I32_ADD).arrayGetU(ts.charArrType)
                .localGet(nArr).refAsNonNull().localGet(j).arrayGetU(ts.charArrType)
                .op(WasmInstr.SimpleOp.I32_NE).if_().i32Const(0).localSet(ok).br(3).end()
                .localGet(j).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(j)
                .br(1).end().end().end()
                .localGet(ok).if_().localGet(i).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end();
        c.i32Const(-1);
    }

    private void emitLastIndexOfChar(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 3);
        int len = fn.addLocal("len", WasmValType.I32, 3);
        int i = fn.addLocal("i", WasmValType.I32, 3);
        c.localGet(0).structGet(ts.stringType, 0).localTee(arr).arrayLen().localSet(len);
        c.localGet(2).localGet(len).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localTee(i);
        c.op(WasmInstr.SimpleOp.I32_LT_S).if_().localGet(2).localSet(i).end();
        c.loop()
                .localGet(i).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .localGet(arr).refAsNonNull().localGet(i).arrayGetU(ts.charArrType)
                .localGet(1).op(WasmInstr.SimpleOp.I32_EQ).if_().localGet(i).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(i)
                .br(1).end().end();
        c.i32Const(-1);
    }

    private void emitLastIndexOfStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int sArr = fn.addLocal("sArr", WasmValType.refNull(ts.charArrType), 3);
        int nArr = fn.addLocal("nArr", WasmValType.refNull(ts.charArrType), 3);
        int sLen = fn.addLocal("sLen", WasmValType.I32, 3);
        int nLen = fn.addLocal("nLen", WasmValType.I32, 3);
        int i = fn.addLocal("i", WasmValType.I32, 3);
        int j = fn.addLocal("j", WasmValType.I32, 3);
        int maxStart = fn.addLocal("maxStart", WasmValType.I32, 3);
        int ok = fn.addLocal("ok", WasmValType.I32, 3);

        c.localGet(0).structGet(ts.stringType, 0).localTee(sArr).arrayLen().localSet(sLen);
        c.localGet(1).structGet(ts.stringType, 0).localTee(nArr).arrayLen().localSet(nLen);
        c.localGet(sLen).localGet(nLen).op(WasmInstr.SimpleOp.I32_SUB).localSet(maxStart);
        c.localGet(2).localGet(maxStart).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                .localGet(maxStart).localSet(i)
                .else_().localGet(2).localSet(i).end();
        c.loop()
                .localGet(i).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                .i32Const(1).localSet(ok).i32Const(0).localSet(j)
                .block().loop()
                .localGet(j).localGet(nLen).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(sArr).refAsNonNull().localGet(i).localGet(j).op(WasmInstr.SimpleOp.I32_ADD).arrayGetU(ts.charArrType)
                .localGet(nArr).refAsNonNull().localGet(j).arrayGetU(ts.charArrType)
                .op(WasmInstr.SimpleOp.I32_NE).if_().i32Const(0).localSet(ok).br(3).end()
                .localGet(j).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(j)
                .br(1).end().end().end()
                .localGet(ok).if_().localGet(i).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(i)
                .br(1).end().end();
        c.i32Const(-1);
    }

    private void emitStartsWith(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int sArr = fn.addLocal("sArr", WasmValType.refNull(ts.charArrType), 3);
        int pArr = fn.addLocal("pArr", WasmValType.refNull(ts.charArrType), 3);
        int sLen = fn.addLocal("sLen", WasmValType.I32, 3);
        int pLen = fn.addLocal("pLen", WasmValType.I32, 3);
        int i = fn.addLocal("i", WasmValType.I32, 3);
        c.localGet(0).structGet(ts.stringType, 0).localTee(sArr).arrayLen().localSet(sLen);
        c.localGet(1).structGet(ts.stringType, 0).localTee(pArr).arrayLen().localSet(pLen);
        c.localGet(2).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S)
                .localGet(2).localGet(pLen).op(WasmInstr.SimpleOp.I32_ADD).localGet(sLen).op(WasmInstr.SimpleOp.I32_GT_S)
                .op(WasmInstr.SimpleOp.I32_OR).if_().i32Const(0).return_().end();
        c.i32Const(0).localSet(i);
        c.loop()
                .localGet(i).localGet(pLen).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(sArr).refAsNonNull().localGet(2).localGet(i).op(WasmInstr.SimpleOp.I32_ADD).arrayGetU(ts.charArrType)
                .localGet(pArr).refAsNonNull().localGet(i).arrayGetU(ts.charArrType)
                .op(WasmInstr.SimpleOp.I32_NE).if_().i32Const(0).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end();
        c.i32Const(1);
    }

    private void emitEndsWith(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).localGet(1)
                .localGet(0).structGet(ts.stringType, 0).arrayLen()
                .localGet(1).structGet(ts.stringType, 0).arrayLen()
                .op(WasmInstr.SimpleOp.I32_SUB)
                .call(fnStartsWith);
    }

    private void emitReplaceChar(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 3);
        int len = fn.addLocal("len", WasmValType.I32, 3);
        int out = fn.addLocal("out", WasmValType.refNull(ts.charArrType), 3);
        int i = fn.addLocal("i", WasmValType.I32, 3);
        int ch = fn.addLocal("ch", WasmValType.I32, 3);
        c.localGet(0).structGet(ts.stringType, 0).localTee(arr).arrayLen().localTee(len)
                .arrayNewDefault(ts.charArrType).localSet(out);
        c.i32Const(0).localSet(i);
        c.loop()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(arr).refAsNonNull().localGet(i).arrayGetU(ts.charArrType).localTee(ch)
                .localGet(1).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(out).refAsNonNull().localGet(i).localGet(2).arraySet(ts.charArrType)
                .else_()
                .localGet(out).refAsNonNull().localGet(i).localGet(ch).arraySet(ts.charArrType)
                .end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end();
        c.localGet(out).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    private void emitReplaceStr(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int sLen = fn.addLocal("sLen", WasmValType.I32, 3);
        int tLen = fn.addLocal("tLen", WasmValType.I32, 3);
        int pos = fn.addLocal("pos", WasmValType.I32, 3);
        int hit = fn.addLocal("hit", WasmValType.I32, 3);
        int res = fn.addLocal("res", WasmValType.refNull(ts.stringType), 3);
        c.localGet(0).structGet(ts.stringType, 0).arrayLen().localSet(sLen);
        c.localGet(1).structGet(ts.stringType, 0).arrayLen().localSet(tLen);
        rt.emitStringLit(c, "");
        c.localSet(res);
        // Empty target: interleave repl between every char
        c.localGet(tLen).op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(2).localSet(res)
                .i32Const(0).localSet(pos)
                .loop()
                .localGet(pos).localGet(sLen).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(res).refAsNonNull()
                .localGet(0).localGet(pos).localGet(pos).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).i32Const(0).call(fnSubstring)
                .call(rt.fnStrConcat)
                .localGet(2).call(rt.fnStrConcat).localSet(res)
                .localGet(pos).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(pos)
                .br(1).end().end()
                .localGet(res).refAsNonNull().return_().end();

        c.i32Const(0).localSet(pos);
        c.block().loop()
                .localGet(0).localGet(1).localGet(pos).call(fnIndexOfStr).localTee(hit)
                .i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(res).refAsNonNull()
                .localGet(0).localGet(pos).localGet(sLen).i32Const(0).call(fnSubstring)
                .call(rt.fnStrConcat).localSet(res)
                .br(2).end()
                .localGet(res).refAsNonNull()
                .localGet(0).localGet(pos).localGet(hit).i32Const(0).call(fnSubstring)
                .call(rt.fnStrConcat)
                .localGet(2).call(rt.fnStrConcat).localSet(res)
                .localGet(hit).localGet(tLen).op(WasmInstr.SimpleOp.I32_ADD).localSet(pos)
                .br(0).end().end();
        c.localGet(res).refAsNonNull();
    }

    private void emitTrim(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 1);
        int start = fn.addLocal("start", WasmValType.I32, 1);
        int end = fn.addLocal("end", WasmValType.I32, 1);
        c.localGet(0).structGet(ts.stringType, 0).localTee(arr).arrayLen().localSet(end);
        c.i32Const(0).localSet(start);
        c.loop()
                .localGet(start).localGet(end).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(arr).refAsNonNull().localGet(start).arrayGetU(ts.charArrType)
                .i32Const(32).op(WasmInstr.SimpleOp.I32_LE_S).if_()
                .localGet(start).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(start)
                .br(2).end().end().end();
        c.loop()
                .localGet(end).localGet(start).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                .localGet(arr).refAsNonNull().localGet(end).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).arrayGetU(ts.charArrType)
                .i32Const(32).op(WasmInstr.SimpleOp.I32_LE_S).if_()
                .localGet(end).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(end)
                .br(2).end().end().end();
        c.localGet(0).localGet(start).localGet(end).i32Const(0).call(fnSubstring);
    }

    private void emitCaseConvert(WasmModule.FuncDef fn, boolean toLower) {
        WasmCodeBuilder c = fn.code();
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 1);
        int len = fn.addLocal("len", WasmValType.I32, 1);
        int out = fn.addLocal("out", WasmValType.refNull(ts.charArrType), 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        int ch = fn.addLocal("ch", WasmValType.I32, 1);
        int lo = toLower ? 'A' : 'a';
        int hi = toLower ? 'Z' : 'z';
        int delta = toLower ? 32 : -32;
        c.localGet(0).structGet(ts.stringType, 0).localTee(arr).arrayLen().localTee(len)
                .arrayNewDefault(ts.charArrType).localSet(out);
        c.i32Const(0).localSet(i);
        c.loop()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(arr).refAsNonNull().localGet(i).arrayGetU(ts.charArrType).localTee(ch)
                .i32Const(lo).op(WasmInstr.SimpleOp.I32_GE_S)
                .localGet(ch).i32Const(hi).op(WasmInstr.SimpleOp.I32_LE_S).op(WasmInstr.SimpleOp.I32_AND).if_()
                .localGet(ch).i32Const(delta).op(WasmInstr.SimpleOp.I32_ADD).localSet(ch).end()
                .localGet(out).refAsNonNull().localGet(i).localGet(ch).arraySet(ts.charArrType)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end();
        c.localGet(out).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    private void emitEqualsIgnoreCase(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(1).refTest(ts.stringType).op(WasmInstr.SimpleOp.I32_EQZ).if_().i32Const(0).return_().end();
        c.localGet(0).call(fnToLower)
                .localGet(1).refCast(ts.stringType).call(fnToLower)
                .call(rt.fnStrEq);
    }

    private void emitCompareTo(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int aArr = fn.addLocal("aArr", WasmValType.refNull(ts.charArrType), 2);
        int bArr = fn.addLocal("bArr", WasmValType.refNull(ts.charArrType), 2);
        int aLen = fn.addLocal("aLen", WasmValType.I32, 2);
        int bLen = fn.addLocal("bLen", WasmValType.I32, 2);
        int lim = fn.addLocal("lim", WasmValType.I32, 2);
        int i = fn.addLocal("i", WasmValType.I32, 2);
        int d = fn.addLocal("d", WasmValType.I32, 2);
        c.localGet(0).structGet(ts.stringType, 0).localTee(aArr).arrayLen().localSet(aLen);
        c.localGet(1).structGet(ts.stringType, 0).localTee(bArr).arrayLen().localSet(bLen);
        c.localGet(aLen).localGet(bLen).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_(WasmValType.I32).localGet(aLen).else_().localGet(bLen).end().localSet(lim);
        c.i32Const(0).localSet(i);
        c.loop()
                .localGet(i).localGet(lim).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(aArr).refAsNonNull().localGet(i).arrayGetU(ts.charArrType)
                .localGet(bArr).refAsNonNull().localGet(i).arrayGetU(ts.charArrType)
                .op(WasmInstr.SimpleOp.I32_SUB).localTee(d)
                .if_().localGet(d).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end();
        c.localGet(aLen).localGet(bLen).op(WasmInstr.SimpleOp.I32_SUB);
    }

    private void emitRepeat(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 3);
        int len = fn.addLocal("len", WasmValType.I32, 3);
        int out = fn.addLocal("out", WasmValType.refNull(ts.charArrType), 3);
        int i = fn.addLocal("i", WasmValType.I32, 3);
        c.localGet(1).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_();
        rt.emitStringLit(c, "Negative repeat count");
        c.localGet(2).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(0).structGet(ts.stringType, 0).localTee(arr).arrayLen().localSet(len);
        c.localGet(len).localGet(1).op(WasmInstr.SimpleOp.I32_MUL)
                .arrayNewDefault(ts.charArrType).localSet(out);
        c.i32Const(0).localSet(i);
        c.loop()
                .localGet(i).localGet(1).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(out).refAsNonNull()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(arr).refAsNonNull().i32Const(0).localGet(len)
                .arrayCopy(ts.charArrType, ts.charArrType)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end();
        c.localGet(out).refAsNonNull().i32Const(0).i32Const(0).structNew(ts.stringType);
    }

    private void emitIsBlank(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.charArrType), 1);
        int len = fn.addLocal("len", WasmValType.I32, 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        c.localGet(0).structGet(ts.stringType, 0).localTee(arr).arrayLen().localSet(len);
        c.i32Const(0).localSet(i);
        c.loop()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(arr).refAsNonNull().localGet(i).arrayGetU(ts.charArrType)
                .i32Const(32).op(WasmInstr.SimpleOp.I32_GT_S).if_().i32Const(0).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end();
        c.i32Const(1);
    }

    private void emitSplit(WasmModule.FuncDef fn) {
        // Literal delimiter split (also trims trailing empty strings like Java String.split(delim) default)
        WasmCodeBuilder c = fn.code();
        int sLen = fn.addLocal("sLen", WasmValType.I32, 2);
        int dLen = fn.addLocal("dLen", WasmValType.I32, 2);
        int count = fn.addLocal("count", WasmValType.I32, 2);
        int pos = fn.addLocal("pos", WasmValType.I32, 2);
        int hit = fn.addLocal("hit", WasmValType.I32, 2);
        int arr = fn.addLocal("arr", WasmValType.refNull(ts.anyArrType), 2);
        int idx = fn.addLocal("idx", WasmValType.I32, 2);

        c.localGet(0).structGet(ts.stringType, 0).arrayLen().localSet(sLen);
        c.localGet(1).structGet(ts.stringType, 0).arrayLen().localSet(dLen);

        // Pass 1: count parts
        c.i32Const(1).localSet(count);
        c.i32Const(0).localSet(pos);
        c.localGet(dLen).if_()
                .block().loop()
                .localGet(0).localGet(1).localGet(pos).call(fnIndexOfStr).localTee(hit)
                .i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_().br(2).end()
                .localGet(count).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(count)
                .localGet(hit).localGet(dLen).op(WasmInstr.SimpleOp.I32_ADD).localSet(pos)
                .br(0).end().end()
                .end();

        c.localGet(count).arrayNewDefault(ts.anyArrType).localSet(arr);
        c.localGet(dLen).op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(arr).refAsNonNull().i32Const(0).localGet(0).arraySet(ts.anyArrType)
                .else_()
                .i32Const(0).localSet(pos)
                .i32Const(0).localSet(idx)
                .block().loop()
                .localGet(0).localGet(1).localGet(pos).call(fnIndexOfStr).localTee(hit)
                .i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(arr).refAsNonNull().localGet(idx)
                .localGet(0).localGet(pos).localGet(sLen).i32Const(0).call(fnSubstring)
                .arraySet(ts.anyArrType)
                .br(2).end()
                .localGet(arr).refAsNonNull().localGet(idx)
                .localGet(0).localGet(pos).localGet(hit).i32Const(0).call(fnSubstring)
                .arraySet(ts.anyArrType)
                .localGet(idx).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(idx)
                .localGet(hit).localGet(dLen).op(WasmInstr.SimpleOp.I32_ADD).localSet(pos)
                .br(0).end().end()
                .end();

        c.globalGet(arrays.gTdString).refAsNonNull()
                .localGet(arr).refAsNonNull()
                .structNew(ts.refArrayType);
    }

    private void emitToCharArray(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int src = fn.addLocal("src", WasmValType.refNull(ts.charArrType), 1);
        int len = fn.addLocal("len", WasmValType.I32, 1);
        int dst = fn.addLocal("dst", WasmValType.refNull(ts.charArrType), 1);
        c.localGet(0).structGet(ts.stringType, 0).localTee(src).arrayLen().localTee(len)
                .arrayNewDefault(ts.charArrType).localTee(dst)
                .i32Const(0).localGet(src).refAsNonNull().i32Const(0).localGet(len)
                .arrayCopy(ts.charArrType, ts.charArrType);
        c.globalGet(arrays.gTdChar).refAsNonNull()
                .localGet(dst).refAsNonNull()
                .structNew(ts.charArrayType);
    }
}
