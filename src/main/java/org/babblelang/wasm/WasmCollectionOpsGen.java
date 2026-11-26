package org.babblelang.wasm;

import java.util.List;

/**
 * Generates WasmGC runtime support for Babble's portable core collections ({@code list}, {@code set},
 * {@code sortedset}, {@code map}, {@code sortedmap}, {@code entry}, and {@code $CollectionIter})
 * ({@code BIP025-I3}, {@code BIP025-D6}) with exact JVM {@code java.util.*} semantics.
 */
public final class WasmCollectionOpsGen {
    private final WasmModule mod;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;

    // List
    public final int fnListNew;
    public final int fnListAdd;
    public final int fnListAddAt;
    public final int fnListGet;
    public final int fnListSet;
    public final int fnListRemoveAt;
    public final int fnListRemoveVal;
    public final int fnListIndexOf;
    public final int fnListLastIndexOf;
    public final int fnListContains;
    public final int fnListClear;
    public final int fnListAddAll;
    public final int fnListSubList;
    public final int fnListSlice;
    public final int fnListIter;

    // Set / SortedSet
    public final int fnSetNew;
    public final int fnSetAdd;
    public final int fnSetContains;
    public final int fnSetRemove;
    public final int fnSetClear;
    public final int fnSetFirst;
    public final int fnSetLast;
    public final int fnSetAddAll;
    public final int fnSetIter;

    // Entry
    public final int fnEntryNew;
    public final int fnEntrySetValue;

    // Map / SortedMap
    public final int fnMapNew;
    public final int fnMapPut;
    public final int fnMapGet;
    public final int fnMapGetOrDefault;
    public final int fnMapContainsKey;
    public final int fnMapContainsValue;
    public final int fnMapRemove;
    public final int fnMapClear;
    public final int fnMapKeySet;
    public final int fnMapValues;
    public final int fnMapEntrySet;
    public final int fnMapFirstKey;
    public final int fnMapLastKey;
    public final int fnMapPutAll;

    // Iterator
    public final int fnIterHasNext;
    public final int fnIterNext;

    // Polymorphic runtime hooks
    public final int fnColEq;
    public final int fnColHash;
    public final int fnColFmt;

    public WasmCollectionOpsGen(WasmModule mod, WasmTypeSystem ts, WasmRuntimeGen rt) {
        this.mod = mod;
        this.ts = ts;
        this.rt = rt;

        WasmValType refAny = ts.anyRef();
        WasmValType refStr = WasmValType.ref(ts.stringType);
        WasmValType refList = WasmValType.ref(ts.listType);
        WasmValType refSet = WasmValType.ref(ts.setType);
        WasmValType refEntry = WasmValType.ref(ts.entryType);
        WasmValType refMap = WasmValType.ref(ts.mapType);
        WasmValType refIter = WasmValType.ref(ts.collectionIterType);

        // Declare functions
        WasmModule.FuncDef dListNew = mod.addFunction("list_new", ts.internFuncType(List.of(WasmValType.I32), List.of(refList)), List.of("cap"));
        WasmModule.FuncDef dListAdd = mod.addFunction("list_add", ts.internFuncType(List.of(refList, refAny), List.of(WasmValType.I32)), List.of("l", "v"));
        WasmModule.FuncDef dListAddAt = mod.addFunction("list_addAt", ts.internFuncType(List.of(refList, WasmValType.I32, refAny, WasmValType.I32), List.of()), List.of("l", "i", "v", "line"));
        WasmModule.FuncDef dListGet = mod.addFunction("list_get", ts.internFuncType(List.of(refList, WasmValType.I32, WasmValType.I32), List.of(refAny)), List.of("l", "i", "line"));
        WasmModule.FuncDef dListSet = mod.addFunction("list_set", ts.internFuncType(List.of(refList, WasmValType.I32, refAny, WasmValType.I32), List.of(refAny)), List.of("l", "i", "v", "line"));
        WasmModule.FuncDef dListRemAt = mod.addFunction("list_removeAt", ts.internFuncType(List.of(refList, WasmValType.I32, WasmValType.I32), List.of(refAny)), List.of("l", "i", "line"));
        WasmModule.FuncDef dListRemVal = mod.addFunction("list_removeVal", ts.internFuncType(List.of(refList, refAny), List.of(WasmValType.I32)), List.of("l", "v"));
        WasmModule.FuncDef dListIdx = mod.addFunction("list_indexOf", ts.internFuncType(List.of(refList, refAny), List.of(WasmValType.I32)), List.of("l", "v"));
        WasmModule.FuncDef dListLastIdx = mod.addFunction("list_lastIndexOf", ts.internFuncType(List.of(refList, refAny), List.of(WasmValType.I32)), List.of("l", "v"));
        WasmModule.FuncDef dListContains = mod.addFunction("list_contains", ts.internFuncType(List.of(refList, refAny), List.of(WasmValType.I32)), List.of("l", "v"));
        WasmModule.FuncDef dListClear = mod.addFunction("list_clear", ts.internFuncType(List.of(refList), List.of()), List.of("l"));
        WasmModule.FuncDef dListAddAll = mod.addFunction("list_addAll", ts.internFuncType(List.of(refList, refAny, WasmValType.I32), List.of(WasmValType.I32)), List.of("l", "src", "line"));
        WasmModule.FuncDef dListSub = mod.addFunction("list_subList", ts.internFuncType(List.of(refList, WasmValType.I32, WasmValType.I32, WasmValType.I32), List.of(refList)), List.of("l", "from", "to", "line"));
        WasmModule.FuncDef dListSlice = mod.addFunction("list_slice", ts.internFuncType(List.of(refList, WasmValType.I32), List.of(refList)), List.of("l", "from"));
        WasmModule.FuncDef dListIter = mod.addFunction("list_iter", ts.internFuncType(List.of(refList), List.of(refIter)), List.of("l"));

        WasmModule.FuncDef dSetNew = mod.addFunction("set_new", ts.internFuncType(List.of(WasmValType.I32), List.of(refSet)), List.of("sorted"));
        WasmModule.FuncDef dSetAdd = mod.addFunction("set_add", ts.internFuncType(List.of(refSet, refAny, WasmValType.I32), List.of(WasmValType.I32)), List.of("s", "v", "line"));
        WasmModule.FuncDef dSetContains = mod.addFunction("set_contains", ts.internFuncType(List.of(refSet, refAny), List.of(WasmValType.I32)), List.of("s", "v"));
        WasmModule.FuncDef dSetRem = mod.addFunction("set_remove", ts.internFuncType(List.of(refSet, refAny), List.of(WasmValType.I32)), List.of("s", "v"));
        WasmModule.FuncDef dSetClear = mod.addFunction("set_clear", ts.internFuncType(List.of(refSet), List.of()), List.of("s"));
        WasmModule.FuncDef dSetFirst = mod.addFunction("set_first", ts.internFuncType(List.of(refSet, WasmValType.I32), List.of(refAny)), List.of("s", "line"));
        WasmModule.FuncDef dSetLast = mod.addFunction("set_last", ts.internFuncType(List.of(refSet, WasmValType.I32), List.of(refAny)), List.of("s", "line"));
        WasmModule.FuncDef dSetAddAll = mod.addFunction("set_addAll", ts.internFuncType(List.of(refSet, refAny, WasmValType.I32), List.of(WasmValType.I32)), List.of("s", "src", "line"));
        WasmModule.FuncDef dSetIter = mod.addFunction("set_iter", ts.internFuncType(List.of(refSet), List.of(refIter)), List.of("s"));

        WasmModule.FuncDef dEntryNew = mod.addFunction("entry_new", ts.internFuncType(List.of(refAny, refAny), List.of(refEntry)), List.of("k", "v"));
        WasmModule.FuncDef dEntrySetVal = mod.addFunction("entry_setValue", ts.internFuncType(List.of(refEntry, refAny, WasmValType.I32), List.of(refAny)), List.of("e", "v", "line"));

        WasmModule.FuncDef dMapNew = mod.addFunction("map_new", ts.internFuncType(List.of(WasmValType.I32), List.of(refMap)), List.of("sorted"));
        WasmModule.FuncDef dMapPut = mod.addFunction("map_put", ts.internFuncType(List.of(refMap, refAny, refAny, WasmValType.I32), List.of(refAny)), List.of("m", "k", "v", "line"));
        WasmModule.FuncDef dMapGet = mod.addFunction("map_get", ts.internFuncType(List.of(refMap, refAny), List.of(refAny)), List.of("m", "k"));
        WasmModule.FuncDef dMapGetDef = mod.addFunction("map_getOrDefault", ts.internFuncType(List.of(refMap, refAny, refAny), List.of(refAny)), List.of("m", "k", "def"));
        WasmModule.FuncDef dMapHasK = mod.addFunction("map_containsKey", ts.internFuncType(List.of(refMap, refAny), List.of(WasmValType.I32)), List.of("m", "k"));
        WasmModule.FuncDef dMapHasV = mod.addFunction("map_containsValue", ts.internFuncType(List.of(refMap, refAny), List.of(WasmValType.I32)), List.of("m", "v"));
        WasmModule.FuncDef dMapRem = mod.addFunction("map_remove", ts.internFuncType(List.of(refMap, refAny), List.of(refAny)), List.of("m", "k"));
        WasmModule.FuncDef dMapClear = mod.addFunction("map_clear", ts.internFuncType(List.of(refMap), List.of()), List.of("m"));
        WasmModule.FuncDef dMapKeys = mod.addFunction("map_keySet", ts.internFuncType(List.of(refMap), List.of(refSet)), List.of("m"));
        WasmModule.FuncDef dMapVals = mod.addFunction("map_values", ts.internFuncType(List.of(refMap), List.of(refList)), List.of("m"));
        WasmModule.FuncDef dMapEntries = mod.addFunction("map_entrySet", ts.internFuncType(List.of(refMap), List.of(refSet)), List.of("m"));
        WasmModule.FuncDef dMapFirstK = mod.addFunction("map_firstKey", ts.internFuncType(List.of(refMap, WasmValType.I32), List.of(refAny)), List.of("m", "line"));
        WasmModule.FuncDef dMapLastK = mod.addFunction("map_lastKey", ts.internFuncType(List.of(refMap, WasmValType.I32), List.of(refAny)), List.of("m", "line"));
        WasmModule.FuncDef dMapPutAll = mod.addFunction("map_putAll", ts.internFuncType(List.of(refMap, refAny, WasmValType.I32), List.of()), List.of("m", "src", "line"));

        WasmModule.FuncDef dIterHas = mod.addFunction("iter_hasNext", ts.internFuncType(List.of(refIter), List.of(WasmValType.I32)), List.of("it"));
        WasmModule.FuncDef dIterNext = mod.addFunction("iter_next", ts.internFuncType(List.of(refIter, WasmValType.I32), List.of(refAny)), List.of("it", "line"));

        WasmModule.FuncDef dColEq = mod.addFunction("col_equals", ts.internFuncType(List.of(refAny, refAny), List.of(WasmValType.I32)), List.of("a", "b"));
        WasmModule.FuncDef dColHash = mod.addFunction("col_hashCode", ts.internFuncType(List.of(refAny), List.of(WasmValType.I32)), List.of("v"));
        WasmModule.FuncDef dColFmt = mod.addFunction("col_format", ts.internFuncType(List.of(refAny), List.of(refStr)), List.of("v"));

        this.fnListNew = dListNew.funcIndex();
        this.fnListAdd = dListAdd.funcIndex();
        this.fnListAddAt = dListAddAt.funcIndex();
        this.fnListGet = dListGet.funcIndex();
        this.fnListSet = dListSet.funcIndex();
        this.fnListRemoveAt = dListRemAt.funcIndex();
        this.fnListRemoveVal = dListRemVal.funcIndex();
        this.fnListIndexOf = dListIdx.funcIndex();
        this.fnListLastIndexOf = dListLastIdx.funcIndex();
        this.fnListContains = dListContains.funcIndex();
        this.fnListClear = dListClear.funcIndex();
        this.fnListAddAll = dListAddAll.funcIndex();
        this.fnListSubList = dListSub.funcIndex();
        this.fnListSlice = dListSlice.funcIndex();
        this.fnListIter = dListIter.funcIndex();

        this.fnSetNew = dSetNew.funcIndex();
        this.fnSetAdd = dSetAdd.funcIndex();
        this.fnSetContains = dSetContains.funcIndex();
        this.fnSetRemove = dSetRem.funcIndex();
        this.fnSetClear = dSetClear.funcIndex();
        this.fnSetFirst = dSetFirst.funcIndex();
        this.fnSetLast = dSetLast.funcIndex();
        this.fnSetAddAll = dSetAddAll.funcIndex();
        this.fnSetIter = dSetIter.funcIndex();

        this.fnEntryNew = dEntryNew.funcIndex();
        this.fnEntrySetValue = dEntrySetVal.funcIndex();

        this.fnMapNew = dMapNew.funcIndex();
        this.fnMapPut = dMapPut.funcIndex();
        this.fnMapGet = dMapGet.funcIndex();
        this.fnMapGetOrDefault = dMapGetDef.funcIndex();
        this.fnMapContainsKey = dMapHasK.funcIndex();
        this.fnMapContainsValue = dMapHasV.funcIndex();
        this.fnMapRemove = dMapRem.funcIndex();
        this.fnMapClear = dMapClear.funcIndex();
        this.fnMapKeySet = dMapKeys.funcIndex();
        this.fnMapValues = dMapVals.funcIndex();
        this.fnMapEntrySet = dMapEntries.funcIndex();
        this.fnMapFirstKey = dMapFirstK.funcIndex();
        this.fnMapLastKey = dMapLastK.funcIndex();
        this.fnMapPutAll = dMapPutAll.funcIndex();

        this.fnIterHasNext = dIterHas.funcIndex();
        this.fnIterNext = dIterNext.funcIndex();

        this.fnColEq = dColEq.funcIndex();
        this.fnColHash = dColHash.funcIndex();
        this.fnColFmt = dColFmt.funcIndex();

        // Emit bodies
        emitListOps(dListNew, dListAdd, dListAddAt, dListGet, dListSet, dListRemAt, dListRemVal,
                dListIdx, dListLastIdx, dListContains, dListClear, dListAddAll, dListSub, dListSlice, dListIter);
        emitSetOps(dSetNew, dSetAdd, dSetContains, dSetRem, dSetClear, dSetFirst, dSetLast, dSetAddAll, dSetIter);
        emitEntryNew(dEntryNew);
        emitEntrySetValue(dEntrySetVal);
        emitMapOps(dMapNew, dMapPut, dMapGet, dMapGetDef, dMapHasK, dMapHasV, dMapRem,
                dMapClear, dMapKeys, dMapVals, dMapEntries, dMapFirstK, dMapLastK, dMapPutAll);
        emitIterOps(dIterHas, dIterNext);
        emitColEq(dColEq);
        emitColHash(dColHash);
        emitColFmt(dColFmt);
    }

    // ---------------------------------------------------------------- List

    private void emitListOps(WasmModule.FuncDef dNew, WasmModule.FuncDef dAdd, WasmModule.FuncDef dAddAt,
                             WasmModule.FuncDef dGet, WasmModule.FuncDef dSet, WasmModule.FuncDef dRemAt,
                             WasmModule.FuncDef dRemVal, WasmModule.FuncDef dIdx, WasmModule.FuncDef dLastIdx,
                             WasmModule.FuncDef dContains, WasmModule.FuncDef dClear, WasmModule.FuncDef dAddAll,
                             WasmModule.FuncDef dSub, WasmModule.FuncDef dSlice, WasmModule.FuncDef dIter) {
        // list_new(cap)
        {
            WasmCodeBuilder c = dNew.code();
            int cap = dNew.addLocal("c", WasmValType.I32, 1);
            c.localGet(0).i32Const(4).op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_(WasmValType.I32).i32Const(4).else_().localGet(0).end().localSet(cap);
            c.localGet(cap).arrayNewDefault(ts.anyArrType).i32Const(0)
                    .refNull(ts.listType).i32Const(0).structNew(ts.listType);
        }
        // list_add(l, v) -> i32 (1)
        {
            WasmCodeBuilder c = dAdd.code();
            int arr = dAdd.addLocal("arr", WasmValType.refNull(ts.anyArrType), 2);
            int sz = dAdd.addLocal("sz", WasmValType.I32, 2);
            int cap = dAdd.addLocal("cap", WasmValType.I32, 2);
            int grown = dAdd.addLocal("grown", WasmValType.refNull(ts.anyArrType), 2);
            c.localGet(0).structGet(ts.listType, 0).localTee(arr).arrayLen().localSet(cap);
            c.localGet(0).structGet(ts.listType, 1).localSet(sz);
            c.localGet(sz).localGet(cap).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                    .localGet(cap).i32Const(2).op(WasmInstr.SimpleOp.I32_MUL).arrayNewDefault(ts.anyArrType).localTee(grown)
                    .i32Const(0).localGet(arr).refAsNonNull().i32Const(0).localGet(sz)
                    .arrayCopy(ts.anyArrType, ts.anyArrType)
                    .localGet(0).localGet(grown).refAsNonNull().structSet(ts.listType, 0)
                    .localGet(grown).localSet(arr)
                    .end();
            c.localGet(arr).refAsNonNull().localGet(sz).localGet(1).arraySet(ts.anyArrType);
            c.localGet(0).localGet(sz).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).structSet(ts.listType, 1);
            // Propagate to backing parent list if this is a subList view
            c.localGet(0).structGet(ts.listType, 2).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                    .localGet(0).structGet(ts.listType, 2).refAsNonNull()
                    .localGet(0).structGet(ts.listType, 3).localGet(sz).op(WasmInstr.SimpleOp.I32_ADD)
                    .localGet(1).i32Const(0).call(fnListAddAt)
                    .end();
            c.i32Const(1);
        }
        // list_addAt(l, i, v, line)
        {
            WasmCodeBuilder c = dAddAt.code();
            int sz = dAddAt.addLocal("sz", WasmValType.I32, 4);
            int cap = dAddAt.addLocal("cap", WasmValType.I32, 4);
            int arr = dAddAt.addLocal("arr", WasmValType.refNull(ts.anyArrType), 4);
            int grown = dAddAt.addLocal("grown", WasmValType.refNull(ts.anyArrType), 4);
            int k = dAddAt.addLocal("k", WasmValType.I32, 4);
            c.localGet(0).structGet(ts.listType, 1).localSet(sz);
            c.localGet(1).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S)
                    .localGet(1).localGet(sz).op(WasmInstr.SimpleOp.I32_GT_S).op(WasmInstr.SimpleOp.I32_OR).if_();
            rt.emitStringLit(c, "Index out of bounds");
            c.localGet(3).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0).structGet(ts.listType, 0).localTee(arr).arrayLen().localSet(cap);
            c.localGet(sz).localGet(cap).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                    .localGet(cap).i32Const(2).op(WasmInstr.SimpleOp.I32_MUL).arrayNewDefault(ts.anyArrType).localTee(grown)
                    .i32Const(0).localGet(arr).refAsNonNull().i32Const(0).localGet(sz)
                    .arrayCopy(ts.anyArrType, ts.anyArrType)
                    .localGet(0).localGet(grown).refAsNonNull().structSet(ts.listType, 0)
                    .localGet(grown).localSet(arr)
                    .end();
            c.localGet(sz).localSet(k);
            c.loop()
                    .localGet(k).localGet(1).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                    .localGet(arr).refAsNonNull().localGet(k)
                    .localGet(arr).refAsNonNull().localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(k)
                    .br(1).end().end();
            c.localGet(arr).refAsNonNull().localGet(1).localGet(2).arraySet(ts.anyArrType);
            c.localGet(0).localGet(sz).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).structSet(ts.listType, 1);
            c.localGet(0).structGet(ts.listType, 2).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                    .localGet(0).structGet(ts.listType, 2).refAsNonNull()
                    .localGet(0).structGet(ts.listType, 3).localGet(1).op(WasmInstr.SimpleOp.I32_ADD)
                    .localGet(2).localGet(3).call(fnListAddAt)
                    .end();
        }
        // list_get(l, i, line)
        {
            WasmCodeBuilder c = dGet.code();
            c.localGet(1).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S)
                    .localGet(1).localGet(0).structGet(ts.listType, 1).op(WasmInstr.SimpleOp.I32_GE_S)
                    .op(WasmInstr.SimpleOp.I32_OR).if_();
            rt.emitStringLit(c, "Index out of bounds");
            c.localGet(2).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0).structGet(ts.listType, 0).localGet(1).arrayGet(ts.anyArrType);
        }
        // list_set(l, i, v, line) -> prev
        {
            WasmCodeBuilder c = dSet.code();
            int prev = dSet.addLocal("prev", ts.anyRef(), 4);
            c.localGet(1).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S)
                    .localGet(1).localGet(0).structGet(ts.listType, 1).op(WasmInstr.SimpleOp.I32_GE_S)
                    .op(WasmInstr.SimpleOp.I32_OR).if_();
            rt.emitStringLit(c, "Index out of bounds");
            c.localGet(3).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0).structGet(ts.listType, 0).localGet(1).arrayGet(ts.anyArrType).localSet(prev);
            c.localGet(0).structGet(ts.listType, 0).localGet(1).localGet(2).arraySet(ts.anyArrType);
            c.localGet(0).structGet(ts.listType, 2).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                    .localGet(0).structGet(ts.listType, 2).refAsNonNull()
                    .localGet(0).structGet(ts.listType, 3).localGet(1).op(WasmInstr.SimpleOp.I32_ADD)
                    .localGet(2).localGet(3).call(fnListSet).drop()
                    .end();
            c.localGet(prev);
        }
        // list_removeAt(l, i, line) -> prev
        {
            WasmCodeBuilder c = dRemAt.code();
            int sz = dRemAt.addLocal("sz", WasmValType.I32, 3);
            int arr = dRemAt.addLocal("arr", WasmValType.refNull(ts.anyArrType), 3);
            int prev = dRemAt.addLocal("prev", ts.anyRef(), 3);
            int k = dRemAt.addLocal("k", WasmValType.I32, 3);
            c.localGet(0).structGet(ts.listType, 1).localSet(sz);
            c.localGet(1).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S)
                    .localGet(1).localGet(sz).op(WasmInstr.SimpleOp.I32_GE_S).op(WasmInstr.SimpleOp.I32_OR).if_();
            rt.emitStringLit(c, "Index out of bounds");
            c.localGet(2).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0).structGet(ts.listType, 0).localTee(arr).localGet(1).arrayGet(ts.anyArrType).localSet(prev);
            c.localGet(1).localSet(k);
            c.localGet(sz).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(sz);
            c.loop()
                    .localGet(k).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(arr).refAsNonNull().localGet(k)
                    .localGet(arr).refAsNonNull().localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(k)
                    .br(1).end().end();
            c.localGet(arr).refAsNonNull().localGet(sz).refNull(ts.anyType).arraySet(ts.anyArrType);
            c.localGet(0).localGet(sz).structSet(ts.listType, 1);
            c.localGet(0).structGet(ts.listType, 2).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                    .localGet(0).structGet(ts.listType, 2).refAsNonNull()
                    .localGet(0).structGet(ts.listType, 3).localGet(1).op(WasmInstr.SimpleOp.I32_ADD)
                    .localGet(2).call(fnListRemoveAt).drop()
                    .end();
            c.localGet(prev);
        }
        // list_indexOf(l, v)
        {
            WasmCodeBuilder c = dIdx.code();
            int arr = dIdx.addLocal("arr", WasmValType.refNull(ts.anyArrType), 2);
            int sz = dIdx.addLocal("sz", WasmValType.I32, 2);
            int i = dIdx.addLocal("i", WasmValType.I32, 2);
            c.localGet(0).structGet(ts.listType, 0).localSet(arr);
            c.localGet(0).structGet(ts.listType, 1).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(arr).refAsNonNull().localGet(i).arrayGet(ts.anyArrType)
                    .localGet(1).call(rt.fnEquals).if_().localGet(i).return_().end()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.i32Const(-1);
        }
        // list_lastIndexOf(l, v)
        {
            WasmCodeBuilder c = dLastIdx.code();
            int arr = dLastIdx.addLocal("arr", WasmValType.refNull(ts.anyArrType), 2);
            int i = dLastIdx.addLocal("i", WasmValType.I32, 2);
            c.localGet(0).structGet(ts.listType, 0).localSet(arr);
            c.localGet(0).structGet(ts.listType, 1).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(i);
            c.loop()
                    .localGet(i).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                    .localGet(arr).refAsNonNull().localGet(i).arrayGet(ts.anyArrType)
                    .localGet(1).call(rt.fnEquals).if_().localGet(i).return_().end()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(i)
                    .br(1).end().end();
            c.i32Const(-1);
        }
        // list_contains(l, v)
        dContains.code().localGet(0).localGet(1).call(fnListIndexOf).i32Const(0).op(WasmInstr.SimpleOp.I32_GE_S);
        // list_removeVal(l, v)
        {
            WasmCodeBuilder c = dRemVal.code();
            int idx = dRemVal.addLocal("idx", WasmValType.I32, 2);
            c.localGet(0).localGet(1).call(fnListIndexOf).localTee(idx)
                    .i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_().i32Const(0).return_().end();
            c.localGet(0).localGet(idx).i32Const(0).call(fnListRemoveAt).drop().i32Const(1);
        }
        // list_clear(l)
        {
            WasmCodeBuilder c = dClear.code();
            int rem = dClear.addLocal("rem", WasmValType.I32, 1);
            c.localGet(0).structGet(ts.listType, 2).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                    .localGet(0).structGet(ts.listType, 1).localSet(rem)
                    .loop()
                    .localGet(rem).i32Const(0).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                    .localGet(0).structGet(ts.listType, 2).refAsNonNull()
                    .localGet(0).structGet(ts.listType, 3).i32Const(0).call(fnListRemoveAt).drop()
                    .localGet(rem).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(rem)
                    .br(1).end().end()
                    .end();
            c.localGet(0).i32Const(0).structSet(ts.listType, 1);
        }
        // list_addAll(l, src, line)
        emitCollectionAddAll(dAddAll, true);
        // list_subList(l, from, to, line)
        {
            WasmCodeBuilder c = dSub.code();
            int sz = dSub.addLocal("sz", WasmValType.I32, 4);
            int res = dSub.addLocal("res", WasmValType.refNull(ts.listType), 4);
            int i = dSub.addLocal("i", WasmValType.I32, 4);
            c.localGet(0).structGet(ts.listType, 1).localSet(sz);
            c.localGet(1).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S)
                    .localGet(2).localGet(sz).op(WasmInstr.SimpleOp.I32_GT_S).op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(1).localGet(2).op(WasmInstr.SimpleOp.I32_GT_S).op(WasmInstr.SimpleOp.I32_OR).if_();
            rt.emitStringLit(c, "Index out of bounds");
            c.localGet(3).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(2).localGet(1).op(WasmInstr.SimpleOp.I32_SUB).call(fnListNew).localSet(res);
            c.localGet(1).localSet(i);
            c.loop()
                    .localGet(i).localGet(2).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(res).refAsNonNull()
                    .localGet(0).structGet(ts.listType, 0).localGet(i).arrayGet(ts.anyArrType)
                    .call(fnListAdd).drop()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            // Link live backing parent & offset AFTER initial copy
            c.localGet(res).refAsNonNull().localGet(0).structSet(ts.listType, 2);
            c.localGet(res).refAsNonNull().localGet(1).structSet(ts.listType, 3);
            c.localGet(res).refAsNonNull();
        }
        // list_slice(l, from) -> fresh independent $List containing l[from..size)
        {
            WasmCodeBuilder c = dSlice.code();
            int sz = dSlice.addLocal("sz", WasmValType.I32, 2);
            int n = dSlice.addLocal("n", WasmValType.I32, 2);
            int res = dSlice.addLocal("res", WasmValType.refNull(ts.listType), 2);
            int i = dSlice.addLocal("i", WasmValType.I32, 2);
            c.localGet(0).structGet(ts.listType, 1).localTee(sz)
                    .localGet(1).op(WasmInstr.SimpleOp.I32_SUB).localTee(n)
                    .i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .i32Const(0).localSet(n)
                    .end();
            c.localGet(n).call(fnListNew).localSet(res);
            c.localGet(1).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(res).refAsNonNull()
                    .localGet(0).structGet(ts.listType, 0).localGet(i).arrayGet(ts.anyArrType)
                    .call(fnListAdd).drop()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.localGet(res).refAsNonNull();
        }
        // list_iter(l)
        dIter.code().localGet(0).structGet(ts.listType, 0)
                .localGet(0).structGet(ts.listType, 1)
                .i32Const(0).structNew(ts.collectionIterType);
    }

    private void emitCollectionAddAll(WasmModule.FuncDef fn, boolean isList) {
        WasmCodeBuilder c = fn.code();
        int changed = fn.addLocal("ch", WasmValType.I32, 3);
        int i = fn.addLocal("i", WasmValType.I32, 3);
        int sz = fn.addLocal("sz", WasmValType.I32, 3);
        int srcArr = fn.addLocal("srcArr", WasmValType.refNull(ts.anyArrType), 3);

        // Reject null source (Java addAll throws NullPointerException)
        c.localGet(1).refIsNull().if_();
        rt.emitStringLit(c, "NullPointerException");
        c.localGet(2).call(rt.fnThrowMsg).unreachable().end();

        // From $List
        c.localGet(1).refTest(ts.listType).if_()
                .localGet(1).refCast(ts.listType).structGet(ts.listType, 0).localSet(srcArr)
                .localGet(1).refCast(ts.listType).structGet(ts.listType, 1).localSet(sz);
        emitLoopPushFromAnyArr(c, isList, srcArr, sz, i, changed);
        c.localGet(changed).return_().end();

        // From $Set
        c.localGet(1).refTest(ts.setType).if_()
                .localGet(1).refCast(ts.setType).structGet(ts.setType, 0).localSet(srcArr)
                .localGet(1).refCast(ts.setType).structGet(ts.setType, 1).localSet(sz);
        emitLoopPushFromAnyArr(c, isList, srcArr, sz, i, changed);
        c.localGet(changed).return_().end();

        // From $RefArray
        c.localGet(1).refTest(ts.refArrayType).if_()
                .localGet(1).refCast(ts.refArrayType).structGet(ts.refArrayType, 1).localTee(srcArr)
                .arrayLen().localSet(sz);
        emitLoopPushFromAnyArr(c, isList, srcArr, sz, i, changed);
        c.localGet(changed).return_().end();

        // From primitive arrays
        emitPrimArrayAddAll(c, isList, ts.intArrayType, ts.i32ArrType, rt.fnBoxInt, false, false, sz, i, changed);
        emitPrimArrayAddAll(c, isList, ts.doubleArrayType, ts.f64ArrType, rt.fnBoxDouble, false, false, sz, i, changed);
        emitPrimArrayAddAll(c, isList, ts.longArrayType, ts.i64ArrType, rt.fnBoxLong, false, false, sz, i, changed);
        emitPrimArrayAddAll(c, isList, ts.floatArrayType, ts.f32ArrType, rt.fnBoxFloat, false, false, sz, i, changed);
        emitPrimArrayAddAll(c, isList, ts.byteArrayType, ts.i8ArrType, rt.fnBoxByte, true, false, sz, i, changed);
        emitPrimArrayAddAll(c, isList, ts.shortArrayType, ts.i16ArrType, rt.fnBoxShort, true, false, sz, i, changed);
        emitPrimArrayAddAll(c, isList, ts.boolArrayType, ts.i8ArrType, rt.fnBoxBool, false, true, sz, i, changed);
        emitPrimArrayAddAll(c, isList, ts.charArrayType, ts.charArrType, rt.fnBoxChar, false, true, sz, i, changed);

        // From Babble $Object implementing iterator()
        int itAny = fn.addLocal("itAny", ts.anyRef(), 3);
        int fnTmp = fn.addLocal("fnTmp", WasmValType.refNull(ts.fnBaseType), 3);
        int emptyArgs = fn.addLocal("eArgs", WasmValType.refNull(ts.anyArrType), 3);
        c.localGet(1).refTest(ts.objectType).if_();
        c.arrayNewFixed(ts.anyArrType, 0).localSet(emptyArgs);
        // Call src.iterator()
        c.localGet(1).refCast(ts.objectType);
        rt.emitStringLit(c, "iterator");
        c.localGet(1).refCast(ts.objectType).structGet(ts.objectType, 0).structGet(ts.vtableType, 9).refAsNonNull()
                .callRef(ts.getMemFnSigType).refCast(ts.fnBaseType).localTee(fnTmp).refAsNonNull()
                .localGet(emptyArgs).refAsNonNull().refNull(ts.anyArrType)
                .localGet(fnTmp).refAsNonNull().structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType).localSet(itAny);
        // If iterator is $CollectionIter
        c.localGet(itAny).refTest(ts.collectionIterType).if_()
                .loop()
                .localGet(itAny).refCast(ts.collectionIterType).call(fnIterHasNext).if_()
                .localGet(0)
                .localGet(itAny).refCast(ts.collectionIterType).localGet(2).call(fnIterNext);
        if (isList) {
            c.call(fnListAdd);
        } else {
            c.localGet(2).call(fnSetAdd);
        }
        c.localGet(changed).op(WasmInstr.SimpleOp.I32_OR).localSet(changed)
                .br(1).end().end()
                .localGet(changed).return_().end();
        // Else Babble iterator object with hasNext() / next()
        c.localGet(itAny).refTest(ts.objectType).if_()
                .loop()
                .localGet(itAny).refCast(ts.objectType);
        rt.emitStringLit(c, "hasNext");
        c.localGet(itAny).refCast(ts.objectType).structGet(ts.objectType, 0).structGet(ts.vtableType, 9).refAsNonNull()
                .callRef(ts.getMemFnSigType).refCast(ts.fnBaseType).localTee(fnTmp).refAsNonNull()
                .localGet(emptyArgs).refAsNonNull().refNull(ts.anyArrType)
                .localGet(fnTmp).refAsNonNull().structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType).call(rt.fnTruth).if_()
                .localGet(0)
                .localGet(itAny).refCast(ts.objectType);
        rt.emitStringLit(c, "next");
        c.localGet(itAny).refCast(ts.objectType).structGet(ts.objectType, 0).structGet(ts.vtableType, 9).refAsNonNull()
                .callRef(ts.getMemFnSigType).refCast(ts.fnBaseType).localTee(fnTmp).refAsNonNull()
                .localGet(emptyArgs).refAsNonNull().refNull(ts.anyArrType)
                .localGet(fnTmp).refAsNonNull().structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType);
        if (isList) {
            c.call(fnListAdd);
        } else {
            c.localGet(2).call(fnSetAdd);
        }
        c.localGet(changed).op(WasmInstr.SimpleOp.I32_OR).localSet(changed)
                .br(1).end().end()
                .end();
        c.end();
        c.localGet(changed);
    }

    private void emitLoopPushFromAnyArr(WasmCodeBuilder c, boolean isList, int srcArr, int sz, int i, int changed) {
        c.i32Const(0).localSet(i);
        c.loop()
                .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(0).localGet(srcArr).refAsNonNull().localGet(i).arrayGet(ts.anyArrType);
        if (isList) {
            c.call(fnListAdd);
        } else {
            c.localGet(2).call(fnSetAdd);
        }
        c.localGet(changed).op(WasmInstr.SimpleOp.I32_OR).localSet(changed)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end();
    }

    private void emitPrimArrayAddAll(WasmCodeBuilder c, boolean isList, int wrapType, int dataArrType, int boxFn,
                                     boolean signed, boolean unsigned, int sz, int i, int changed) {
        c.localGet(1).refTest(wrapType).if_()
                .localGet(1).refCast(wrapType).structGet(wrapType, 1).arrayLen().localSet(sz)
                .i32Const(0).localSet(i)
                .loop()
                .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(0)
                .localGet(1).refCast(wrapType).structGet(wrapType, 1).localGet(i);
        if (signed) c.arrayGetS(dataArrType);
        else if (unsigned) c.arrayGetU(dataArrType);
        else c.arrayGet(dataArrType);
        c.call(boxFn);
        if (isList) c.call(fnListAdd);
        else c.localGet(2).call(fnSetAdd);
        c.localGet(changed).op(WasmInstr.SimpleOp.I32_OR).localSet(changed)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end()
                .localGet(changed).return_().end();
    }

    // ---------------------------------------------------------------- Set / SortedSet

    private void emitSetOps(WasmModule.FuncDef dNew, WasmModule.FuncDef dAdd, WasmModule.FuncDef dContains,
                            WasmModule.FuncDef dRem, WasmModule.FuncDef dClear, WasmModule.FuncDef dFirst,
                            WasmModule.FuncDef dLast, WasmModule.FuncDef dAddAll, WasmModule.FuncDef dIter) {
        // set_new(sorted)
        dNew.code().i32Const(4).arrayNewDefault(ts.anyArrType).i32Const(0).localGet(0)
                .refNull(ts.mapType).structNew(ts.setType);

        // set_contains(s, v)
        {
            WasmCodeBuilder c = dContains.code();
            int arr = dContains.addLocal("arr", WasmValType.refNull(ts.anyArrType), 2);
            int sz = dContains.addLocal("sz", WasmValType.I32, 2);
            int i = dContains.addLocal("i", WasmValType.I32, 2);
            c.localGet(0).structGet(ts.setType, 0).localSet(arr);
            c.localGet(0).structGet(ts.setType, 1).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(arr).refAsNonNull().localGet(i).arrayGet(ts.anyArrType)
                    .localGet(1).call(rt.fnEquals).if_().i32Const(1).return_().end()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.i32Const(0);
        }

        // set_add(s, v, line) -> 1 if added, 0 if already present
        {
            WasmCodeBuilder c = dAdd.code();
            int arr = dAdd.addLocal("arr", WasmValType.refNull(ts.anyArrType), 3);
            int sz = dAdd.addLocal("sz", WasmValType.I32, 3);
            int cap = dAdd.addLocal("cap", WasmValType.I32, 3);
            int pos = dAdd.addLocal("pos", WasmValType.I32, 3);
            int cmp = dAdd.addLocal("cmp", WasmValType.I32, 3);
            int k = dAdd.addLocal("k", WasmValType.I32, 3);
            int grown = dAdd.addLocal("grown", WasmValType.refNull(ts.anyArrType), 3);

            // Map.keySet() views do not support add()
            c.localGet(0).structGet(ts.setType, 3).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_();
            rt.emitStringLit(c, "UnsupportedOperationException");
            c.localGet(2).call(rt.fnThrowMsg).unreachable().end();

            c.localGet(0).structGet(ts.setType, 0).localTee(arr).arrayLen().localSet(cap);
            c.localGet(0).structGet(ts.setType, 1).localSet(sz);

            // Find insertion position (or detect duplicate)
            c.localGet(0).structGet(ts.setType, 2).if_();
            // Sorted set: validate comparability even on the first element (matching TreeSet)
            c.localGet(sz).op(WasmInstr.SimpleOp.I32_EQZ).if_()
                    .localGet(1).localGet(1);
            rt.emitStringLit(c, "a");
            rt.emitStringLit(c, "b");
            c.localGet(2).call(rt.fnCompare).drop().end();
            // Sorted set: linear/ordered scan with rt.fnCompare
            c.i32Const(0).localSet(pos);
            c.block().loop()
                    .localGet(pos).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(1).localGet(arr).refAsNonNull().localGet(pos).arrayGet(ts.anyArrType);
            rt.emitStringLit(c, "a");
            rt.emitStringLit(c, "b");
            c.localGet(2).call(rt.fnCompare).localTee(cmp)
                    .op(WasmInstr.SimpleOp.I32_EQZ).if_().i32Const(0).return_().end()
                    .localGet(cmp).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_().br(3).end()
                    .localGet(pos).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(pos)
                    .br(1).end().end().end();
            c.else_();
            // Unsorted (insertion-ordered): duplicate check then append at sz
            c.localGet(0).localGet(1).call(fnSetContains).if_().i32Const(0).return_().end();
            c.localGet(sz).localSet(pos);
            c.end();

            // Grow backing array if full
            c.localGet(sz).localGet(cap).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                    .localGet(cap).i32Const(2).op(WasmInstr.SimpleOp.I32_MUL).arrayNewDefault(ts.anyArrType).localTee(grown)
                    .i32Const(0).localGet(arr).refAsNonNull().i32Const(0).localGet(sz)
                    .arrayCopy(ts.anyArrType, ts.anyArrType)
                    .localGet(0).localGet(grown).refAsNonNull().structSet(ts.setType, 0)
                    .localGet(grown).localSet(arr)
                    .end();
            // Shift right from pos
            c.localGet(sz).localSet(k);
            c.loop()
                    .localGet(k).localGet(pos).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                    .localGet(arr).refAsNonNull().localGet(k)
                    .localGet(arr).refAsNonNull().localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(k)
                    .br(1).end().end();
            c.localGet(arr).refAsNonNull().localGet(pos).localGet(1).arraySet(ts.anyArrType);
            c.localGet(0).localGet(sz).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).structSet(ts.setType, 1);
            c.i32Const(1);
        }

        // set_remove(s, v)
        {
            WasmCodeBuilder c = dRem.code();
            int arr = dRem.addLocal("arr", WasmValType.refNull(ts.anyArrType), 2);
            int sz = dRem.addLocal("sz", WasmValType.I32, 2);
            int i = dRem.addLocal("i", WasmValType.I32, 2);
            int k = dRem.addLocal("k", WasmValType.I32, 2);
            c.localGet(0).structGet(ts.setType, 0).localSet(arr);
            c.localGet(0).structGet(ts.setType, 1).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(arr).refAsNonNull().localGet(i).arrayGet(ts.anyArrType)
                    .localGet(1).call(rt.fnEquals).if_()
                    .localGet(i).localSet(k)
                    .localGet(sz).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(sz)
                    .loop()
                    .localGet(k).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(arr).refAsNonNull().localGet(k)
                    .localGet(arr).refAsNonNull().localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(k)
                    .br(1).end().end()
                    .localGet(arr).refAsNonNull().localGet(sz).refNull(ts.anyType).arraySet(ts.anyArrType)
                    .localGet(0).localGet(sz).structSet(ts.setType, 1)
                    .localGet(0).structGet(ts.setType, 3).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                    .localGet(0).structGet(ts.setType, 3).refAsNonNull().localGet(1).call(fnMapRemove).drop()
                    .end()
                    .i32Const(1).return_().end()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.i32Const(0);
        }

        dClear.code()
                .localGet(0).i32Const(0).structSet(ts.setType, 1)
                .localGet(0).structGet(ts.setType, 3).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(0).structGet(ts.setType, 3).refAsNonNull().call(fnMapClear)
                .end();

        // set_first(s, line)
        {
            WasmCodeBuilder c = dFirst.code();
            c.localGet(0).structGet(ts.setType, 1).op(WasmInstr.SimpleOp.I32_EQZ).if_();
            rt.emitStringLit(c, "Empty set");
            c.localGet(1).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0).structGet(ts.setType, 0).i32Const(0).arrayGet(ts.anyArrType);
        }
        // set_last(s, line)
        {
            WasmCodeBuilder c = dLast.code();
            c.localGet(0).structGet(ts.setType, 1).op(WasmInstr.SimpleOp.I32_EQZ).if_();
            rt.emitStringLit(c, "Empty set");
            c.localGet(1).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0).structGet(ts.setType, 0)
                    .localGet(0).structGet(ts.setType, 1).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB)
                    .arrayGet(ts.anyArrType);
        }

        emitCollectionAddAll(dAddAll, false);

        dIter.code().localGet(0).structGet(ts.setType, 0)
                .localGet(0).structGet(ts.setType, 1)
                .i32Const(0).structNew(ts.collectionIterType);
    }

    // ---------------------------------------------------------------- Entry & Map / SortedMap

    private void emitEntryNew(WasmModule.FuncDef d) {
        d.code().localGet(0).localGet(1).refNull(ts.mapType).structNew(ts.entryType);
    }

    private void emitEntrySetValue(WasmModule.FuncDef d) {
        WasmCodeBuilder c = d.code();
        int prev = d.addLocal("prev", ts.anyRef(), 3);
        c.localGet(0).structGet(ts.entryType, 1).localSet(prev);
        c.localGet(0).localGet(1).structSet(ts.entryType, 1);
        c.localGet(0).structGet(ts.entryType, 2).refIsNull().op(WasmInstr.SimpleOp.I32_EQZ).if_()
                .localGet(0).structGet(ts.entryType, 2).refAsNonNull()
                .localGet(0).structGet(ts.entryType, 0)
                .localGet(1).localGet(2).call(fnMapPut).drop()
                .end();
        c.localGet(prev);
    }

    private void emitMapOps(WasmModule.FuncDef dNew, WasmModule.FuncDef dPut, WasmModule.FuncDef dGet,
                            WasmModule.FuncDef dGetDef, WasmModule.FuncDef dHasK, WasmModule.FuncDef dHasV,
                            WasmModule.FuncDef dRem, WasmModule.FuncDef dClear, WasmModule.FuncDef dKeys,
                            WasmModule.FuncDef dVals, WasmModule.FuncDef dEntries, WasmModule.FuncDef dFirstK,
                            WasmModule.FuncDef dLastK, WasmModule.FuncDef dPutAll) {
        // map_new(sorted)
        dNew.code().i32Const(4).arrayNewDefault(ts.anyArrType)
                .i32Const(4).arrayNewDefault(ts.anyArrType)
                .i32Const(0).localGet(0).structNew(ts.mapType);

        // map_put(m, k, v, line) -> previous value or null
        {
            WasmCodeBuilder c = dPut.code();
            int ks = dPut.addLocal("ks", WasmValType.refNull(ts.anyArrType), 4);
            int vs = dPut.addLocal("vs", WasmValType.refNull(ts.anyArrType), 4);
            int sz = dPut.addLocal("sz", WasmValType.I32, 4);
            int cap = dPut.addLocal("cap", WasmValType.I32, 4);
            int pos = dPut.addLocal("pos", WasmValType.I32, 4);
            int cmp = dPut.addLocal("cmp", WasmValType.I32, 4);
            int prev = dPut.addLocal("prev", ts.anyRef(), 4);
            int k = dPut.addLocal("k", WasmValType.I32, 4);
            int gKs = dPut.addLocal("gKs", WasmValType.refNull(ts.anyArrType), 4);
            int gVs = dPut.addLocal("gVs", WasmValType.refNull(ts.anyArrType), 4);

            c.localGet(0).structGet(ts.mapType, 0).localTee(ks).arrayLen().localSet(cap);
            c.localGet(0).structGet(ts.mapType, 1).localSet(vs);
            c.localGet(0).structGet(ts.mapType, 2).localSet(sz);

            c.localGet(0).structGet(ts.mapType, 3).if_();
            // Sorted map: validate key comparability even on first entry (matching TreeMap)
            c.localGet(sz).op(WasmInstr.SimpleOp.I32_EQZ).if_()
                    .localGet(1).localGet(1);
            rt.emitStringLit(c, "a");
            rt.emitStringLit(c, "b");
            c.localGet(3).call(rt.fnCompare).drop().end();
            // Sorted map: find position via rt.fnCompare
            c.i32Const(0).localSet(pos);
            c.block().loop()
                    .localGet(pos).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(1).localGet(ks).refAsNonNull().localGet(pos).arrayGet(ts.anyArrType);
            rt.emitStringLit(c, "a");
            rt.emitStringLit(c, "b");
            c.localGet(3).call(rt.fnCompare).localTee(cmp)
                    .op(WasmInstr.SimpleOp.I32_EQZ).if_()
                    .localGet(vs).refAsNonNull().localGet(pos).arrayGet(ts.anyArrType).localSet(prev)
                    .localGet(vs).refAsNonNull().localGet(pos).localGet(2).arraySet(ts.anyArrType)
                    .localGet(prev).return_().end()
                    .localGet(cmp).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_().br(3).end()
                    .localGet(pos).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(pos)
                    .br(1).end().end().end();
            c.else_();
            // Insertion-ordered map: check existing key
            c.i32Const(0).localSet(pos);
            c.loop()
                    .localGet(pos).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(ks).refAsNonNull().localGet(pos).arrayGet(ts.anyArrType)
                    .localGet(1).call(rt.fnEquals).if_()
                    .localGet(vs).refAsNonNull().localGet(pos).arrayGet(ts.anyArrType).localSet(prev)
                    .localGet(vs).refAsNonNull().localGet(pos).localGet(2).arraySet(ts.anyArrType)
                    .localGet(prev).return_().end()
                    .localGet(pos).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(pos)
                    .br(1).end().end();
            c.localGet(sz).localSet(pos);
            c.end();

            // Grow if full
            c.localGet(sz).localGet(cap).op(WasmInstr.SimpleOp.I32_GE_S).if_()
                    .localGet(cap).i32Const(2).op(WasmInstr.SimpleOp.I32_MUL).arrayNewDefault(ts.anyArrType).localTee(gKs)
                    .i32Const(0).localGet(ks).refAsNonNull().i32Const(0).localGet(sz).arrayCopy(ts.anyArrType, ts.anyArrType)
                    .localGet(cap).i32Const(2).op(WasmInstr.SimpleOp.I32_MUL).arrayNewDefault(ts.anyArrType).localTee(gVs)
                    .i32Const(0).localGet(vs).refAsNonNull().i32Const(0).localGet(sz).arrayCopy(ts.anyArrType, ts.anyArrType)
                    .localGet(0).localGet(gKs).refAsNonNull().structSet(ts.mapType, 0)
                    .localGet(0).localGet(gVs).refAsNonNull().structSet(ts.mapType, 1)
                    .localGet(gKs).localSet(ks).localGet(gVs).localSet(vs)
                    .end();
            // Shift right from pos
            c.localGet(sz).localSet(k);
            c.loop()
                    .localGet(k).localGet(pos).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                    .localGet(ks).refAsNonNull().localGet(k)
                    .localGet(ks).refAsNonNull().localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(vs).refAsNonNull().localGet(k)
                    .localGet(vs).refAsNonNull().localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(k)
                    .br(1).end().end();
            c.localGet(ks).refAsNonNull().localGet(pos).localGet(1).arraySet(ts.anyArrType);
            c.localGet(vs).refAsNonNull().localGet(pos).localGet(2).arraySet(ts.anyArrType);
            c.localGet(0).localGet(sz).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).structSet(ts.mapType, 2);
            c.refNull(ts.anyType);
        }

        // map_get(m, k)
        {
            WasmCodeBuilder c = dGet.code();
            int ks = dGet.addLocal("ks", WasmValType.refNull(ts.anyArrType), 2);
            int sz = dGet.addLocal("sz", WasmValType.I32, 2);
            int i = dGet.addLocal("i", WasmValType.I32, 2);
            c.localGet(0).structGet(ts.mapType, 0).localSet(ks);
            c.localGet(0).structGet(ts.mapType, 2).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(ks).refAsNonNull().localGet(i).arrayGet(ts.anyArrType)
                    .localGet(1).call(rt.fnEquals).if_()
                    .localGet(0).structGet(ts.mapType, 1).localGet(i).arrayGet(ts.anyArrType).return_().end()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.refNull(ts.anyType);
        }

        // map_getOrDefault(m, k, def)
        {
            WasmCodeBuilder c = dGetDef.code();
            int ks = dGetDef.addLocal("ks", WasmValType.refNull(ts.anyArrType), 3);
            int sz = dGetDef.addLocal("sz", WasmValType.I32, 3);
            int i = dGetDef.addLocal("i", WasmValType.I32, 3);
            c.localGet(0).structGet(ts.mapType, 0).localSet(ks);
            c.localGet(0).structGet(ts.mapType, 2).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(ks).refAsNonNull().localGet(i).arrayGet(ts.anyArrType)
                    .localGet(1).call(rt.fnEquals).if_()
                    .localGet(0).structGet(ts.mapType, 1).localGet(i).arrayGet(ts.anyArrType).return_().end()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.localGet(2);
        }

        // map_containsKey(m, k)
        {
            WasmCodeBuilder c = dHasK.code();
            int ks = dHasK.addLocal("ks", WasmValType.refNull(ts.anyArrType), 2);
            int sz = dHasK.addLocal("sz", WasmValType.I32, 2);
            int i = dHasK.addLocal("i", WasmValType.I32, 2);
            c.localGet(0).structGet(ts.mapType, 0).localSet(ks);
            c.localGet(0).structGet(ts.mapType, 2).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(ks).refAsNonNull().localGet(i).arrayGet(ts.anyArrType)
                    .localGet(1).call(rt.fnEquals).if_().i32Const(1).return_().end()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.i32Const(0);
        }

        // map_containsValue(m, v)
        {
            WasmCodeBuilder c = dHasV.code();
            int vs = dHasV.addLocal("vs", WasmValType.refNull(ts.anyArrType), 2);
            int sz = dHasV.addLocal("sz", WasmValType.I32, 2);
            int i = dHasV.addLocal("i", WasmValType.I32, 2);
            c.localGet(0).structGet(ts.mapType, 1).localSet(vs);
            c.localGet(0).structGet(ts.mapType, 2).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(vs).refAsNonNull().localGet(i).arrayGet(ts.anyArrType)
                    .localGet(1).call(rt.fnEquals).if_().i32Const(1).return_().end()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.i32Const(0);
        }

        // map_remove(m, k) -> old val or null
        {
            WasmCodeBuilder c = dRem.code();
            int ks = dRem.addLocal("ks", WasmValType.refNull(ts.anyArrType), 2);
            int vs = dRem.addLocal("vs", WasmValType.refNull(ts.anyArrType), 2);
            int sz = dRem.addLocal("sz", WasmValType.I32, 2);
            int i = dRem.addLocal("i", WasmValType.I32, 2);
            int k = dRem.addLocal("k", WasmValType.I32, 2);
            int prev = dRem.addLocal("prev", ts.anyRef(), 2);
            c.localGet(0).structGet(ts.mapType, 0).localSet(ks);
            c.localGet(0).structGet(ts.mapType, 1).localSet(vs);
            c.localGet(0).structGet(ts.mapType, 2).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(ks).refAsNonNull().localGet(i).arrayGet(ts.anyArrType)
                    .localGet(1).call(rt.fnEquals).if_()
                    .localGet(vs).refAsNonNull().localGet(i).arrayGet(ts.anyArrType).localSet(prev)
                    .localGet(i).localSet(k)
                    .localGet(sz).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(sz)
                    .loop()
                    .localGet(k).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(ks).refAsNonNull().localGet(k)
                    .localGet(ks).refAsNonNull().localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(vs).refAsNonNull().localGet(k)
                    .localGet(vs).refAsNonNull().localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(k).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(k)
                    .br(1).end().end()
                    .localGet(ks).refAsNonNull().localGet(sz).refNull(ts.anyType).arraySet(ts.anyArrType)
                    .localGet(vs).refAsNonNull().localGet(sz).refNull(ts.anyType).arraySet(ts.anyArrType)
                    .localGet(0).localGet(sz).structSet(ts.mapType, 2)
                    .localGet(prev).return_().end()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.refNull(ts.anyType);
        }

        dClear.code().localGet(0).i32Const(0).structSet(ts.mapType, 2);

        // map_keySet(m)
        {
            WasmCodeBuilder c = dKeys.code();
            int s = dKeys.addLocal("s", WasmValType.refNull(ts.setType), 1);
            int i = dKeys.addLocal("i", WasmValType.I32, 1);
            int sz = dKeys.addLocal("sz", WasmValType.I32, 1);
            c.localGet(0).structGet(ts.mapType, 3).call(fnSetNew).localSet(s);
            c.localGet(0).structGet(ts.mapType, 2).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(s).refAsNonNull()
                    .localGet(0).structGet(ts.mapType, 0).localGet(i).arrayGet(ts.anyArrType)
                    .i32Const(0).call(fnSetAdd).drop()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.localGet(s).refAsNonNull().localGet(0).structSet(ts.setType, 3);
            c.localGet(s).refAsNonNull();
        }
        // map_values(m)
        {
            WasmCodeBuilder c = dVals.code();
            int l = dVals.addLocal("l", WasmValType.refNull(ts.listType), 1);
            int i = dVals.addLocal("i", WasmValType.I32, 1);
            int sz = dVals.addLocal("sz", WasmValType.I32, 1);
            c.localGet(0).structGet(ts.mapType, 2).localTee(sz).call(fnListNew).localSet(l);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(l).refAsNonNull()
                    .localGet(0).structGet(ts.mapType, 1).localGet(i).arrayGet(ts.anyArrType)
                    .call(fnListAdd).drop()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.localGet(l).refAsNonNull();
        }
        // map_entrySet(m)
        {
            WasmCodeBuilder c = dEntries.code();
            int s = dEntries.addLocal("s", WasmValType.refNull(ts.setType), 1);
            int i = dEntries.addLocal("i", WasmValType.I32, 1);
            int sz = dEntries.addLocal("sz", WasmValType.I32, 1);
            c.i32Const(0).call(fnSetNew).localSet(s);
            c.localGet(0).structGet(ts.mapType, 2).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(s).refAsNonNull()
                    .localGet(0).structGet(ts.mapType, 0).localGet(i).arrayGet(ts.anyArrType)
                    .localGet(0).structGet(ts.mapType, 1).localGet(i).arrayGet(ts.anyArrType)
                    .localGet(0).structNew(ts.entryType)
                    .i32Const(0).call(fnSetAdd).drop()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end();
            c.localGet(s).refAsNonNull();
        }
        // map_firstKey / lastKey
        {
            WasmCodeBuilder c = dFirstK.code();
            c.localGet(0).structGet(ts.mapType, 2).op(WasmInstr.SimpleOp.I32_EQZ).if_();
            rt.emitStringLit(c, "Empty map");
            c.localGet(1).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0).structGet(ts.mapType, 0).i32Const(0).arrayGet(ts.anyArrType);
        }
        {
            WasmCodeBuilder c = dLastK.code();
            c.localGet(0).structGet(ts.mapType, 2).op(WasmInstr.SimpleOp.I32_EQZ).if_();
            rt.emitStringLit(c, "Empty map");
            c.localGet(1).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0).structGet(ts.mapType, 0)
                    .localGet(0).structGet(ts.mapType, 2).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB)
                    .arrayGet(ts.anyArrType);
        }
        // map_putAll(m, src, line)
        {
            WasmCodeBuilder c = dPutAll.code();
            int i = dPutAll.addLocal("i", WasmValType.I32, 3);
            int sz = dPutAll.addLocal("sz", WasmValType.I32, 3);
            int sm = dPutAll.addLocal("sm", WasmValType.refNull(ts.mapType), 3);
            c.localGet(1).refIsNull().if_();
            rt.emitStringLit(c, "NullPointerException");
            c.localGet(2).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(1).refTest(ts.mapType).if_()
                    .localGet(1).refCast(ts.mapType).localTee(sm).structGet(ts.mapType, 2).localSet(sz)
                    .i32Const(0).localSet(i)
                    .loop()
                    .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                    .localGet(0)
                    .localGet(sm).refAsNonNull().structGet(ts.mapType, 0).localGet(i).arrayGet(ts.anyArrType)
                    .localGet(sm).refAsNonNull().structGet(ts.mapType, 1).localGet(i).arrayGet(ts.anyArrType)
                    .localGet(2).call(fnMapPut).drop()
                    .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                    .br(1).end().end()
                    .end();
        }
    }

    // ---------------------------------------------------------------- Iterator

    private void emitIterOps(WasmModule.FuncDef dHas, WasmModule.FuncDef dNext) {
        dHas.code().localGet(0).structGet(ts.collectionIterType, 2)
                .localGet(0).structGet(ts.collectionIterType, 1)
                .op(WasmInstr.SimpleOp.I32_LT_S);
        WasmCodeBuilder c = dNext.code();
        int p = dNext.addLocal("p", WasmValType.I32, 2);
        c.localGet(0).structGet(ts.collectionIterType, 2).localTee(p)
                .localGet(0).structGet(ts.collectionIterType, 1).op(WasmInstr.SimpleOp.I32_GE_S).if_();
        rt.emitStringLit(c, "No such element");
        c.localGet(1).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(0).localGet(p).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).structSet(ts.collectionIterType, 2);
        c.localGet(0).structGet(ts.collectionIterType, 0).localGet(p).arrayGet(ts.anyArrType);
    }

    // ---------------------------------------------------------------- Equality, HashCode, Formatting

    private void emitColEq(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int i = fn.addLocal("i", WasmValType.I32, 2);
        int sz = fn.addLocal("sz", WasmValType.I32, 2);
        // $List == $List (order-sensitive)
        c.localGet(0).refTest(ts.listType).localGet(1).refTest(ts.listType).op(WasmInstr.SimpleOp.I32_AND).if_()
                .localGet(0).refCast(ts.listType).structGet(ts.listType, 1).localTee(sz)
                .localGet(1).refCast(ts.listType).structGet(ts.listType, 1).op(WasmInstr.SimpleOp.I32_NE)
                .if_().i32Const(0).return_().end()
                .i32Const(0).localSet(i)
                .loop()
                .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(0).refCast(ts.listType).structGet(ts.listType, 0).localGet(i).arrayGet(ts.anyArrType)
                .localGet(1).refCast(ts.listType).structGet(ts.listType, 0).localGet(i).arrayGet(ts.anyArrType)
                .call(rt.fnEquals).op(WasmInstr.SimpleOp.I32_EQZ).if_().i32Const(0).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end()
                .i32Const(1).return_().end();
        // $Set == $Set (order-independent)
        c.localGet(0).refTest(ts.setType).localGet(1).refTest(ts.setType).op(WasmInstr.SimpleOp.I32_AND).if_()
                .localGet(0).refCast(ts.setType).structGet(ts.setType, 1).localTee(sz)
                .localGet(1).refCast(ts.setType).structGet(ts.setType, 1).op(WasmInstr.SimpleOp.I32_NE)
                .if_().i32Const(0).return_().end()
                .i32Const(0).localSet(i)
                .loop()
                .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(1).refCast(ts.setType)
                .localGet(0).refCast(ts.setType).structGet(ts.setType, 0).localGet(i).arrayGet(ts.anyArrType)
                .call(fnSetContains).op(WasmInstr.SimpleOp.I32_EQZ).if_().i32Const(0).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end()
                .i32Const(1).return_().end();
        // $Entry == $Entry
        c.localGet(0).refTest(ts.entryType).localGet(1).refTest(ts.entryType).op(WasmInstr.SimpleOp.I32_AND).if_()
                .localGet(0).refCast(ts.entryType).structGet(ts.entryType, 0)
                .localGet(1).refCast(ts.entryType).structGet(ts.entryType, 0).call(rt.fnEquals)
                .localGet(0).refCast(ts.entryType).structGet(ts.entryType, 1)
                .localGet(1).refCast(ts.entryType).structGet(ts.entryType, 1).call(rt.fnEquals)
                .op(WasmInstr.SimpleOp.I32_AND).return_().end();
        // $Map == $Map (order-independent)
        int kTmp = fn.addLocal("kTmp", ts.anyRef(), 2);
        c.localGet(0).refTest(ts.mapType).localGet(1).refTest(ts.mapType).op(WasmInstr.SimpleOp.I32_AND).if_()
                .localGet(0).refCast(ts.mapType).structGet(ts.mapType, 2).localTee(sz)
                .localGet(1).refCast(ts.mapType).structGet(ts.mapType, 2).op(WasmInstr.SimpleOp.I32_NE)
                .if_().i32Const(0).return_().end()
                .i32Const(0).localSet(i)
                .loop()
                .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(0).refCast(ts.mapType).structGet(ts.mapType, 0).localGet(i).arrayGet(ts.anyArrType).localSet(kTmp)
                .localGet(1).refCast(ts.mapType).localGet(kTmp).call(fnMapContainsKey)
                .op(WasmInstr.SimpleOp.I32_EQZ).if_().i32Const(0).return_().end()
                .localGet(0).refCast(ts.mapType).structGet(ts.mapType, 1).localGet(i).arrayGet(ts.anyArrType)
                .localGet(1).refCast(ts.mapType).localGet(kTmp).call(fnMapGet)
                .call(rt.fnEquals).op(WasmInstr.SimpleOp.I32_EQZ).if_().i32Const(0).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end()
                .i32Const(1).return_().end();
        c.i32Const(0);
    }

    private void emitColHash(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int h = fn.addLocal("h", WasmValType.I32, 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        int sz = fn.addLocal("sz", WasmValType.I32, 1);
        // $List: Java List.hashCode (h = 1; h = 31*h + hash(e))
        c.localGet(0).refTest(ts.listType).if_()
                .i32Const(1).localSet(h)
                .localGet(0).refCast(ts.listType).structGet(ts.listType, 1).localSet(sz)
                .i32Const(0).localSet(i)
                .loop()
                .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(h).i32Const(31).op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(0).refCast(ts.listType).structGet(ts.listType, 0).localGet(i).arrayGet(ts.anyArrType)
                .call(rt.fnHashCode).op(WasmInstr.SimpleOp.I32_ADD).localSet(h)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end()
                .localGet(h).return_().end();
        // $Set: Java Set.hashCode (sum of element hashes)
        c.localGet(0).refTest(ts.setType).if_()
                .i32Const(0).localSet(h)
                .localGet(0).refCast(ts.setType).structGet(ts.setType, 1).localSet(sz)
                .i32Const(0).localSet(i)
                .loop()
                .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(h)
                .localGet(0).refCast(ts.setType).structGet(ts.setType, 0).localGet(i).arrayGet(ts.anyArrType)
                .call(rt.fnHashCode).op(WasmInstr.SimpleOp.I32_ADD).localSet(h)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end()
                .localGet(h).return_().end();
        // $Entry: hash(k) ^ hash(v)
        c.localGet(0).refTest(ts.entryType).if_()
                .localGet(0).refCast(ts.entryType).structGet(ts.entryType, 0).call(rt.fnHashCode)
                .localGet(0).refCast(ts.entryType).structGet(ts.entryType, 1).call(rt.fnHashCode)
                .op(WasmInstr.SimpleOp.I32_XOR).return_().end();
        // $Map: sum of (hash(k) ^ hash(v))
        c.localGet(0).refTest(ts.mapType).if_()
                .i32Const(0).localSet(h)
                .localGet(0).refCast(ts.mapType).structGet(ts.mapType, 2).localSet(sz)
                .i32Const(0).localSet(i)
                .loop()
                .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(h)
                .localGet(0).refCast(ts.mapType).structGet(ts.mapType, 0).localGet(i).arrayGet(ts.anyArrType).call(rt.fnHashCode)
                .localGet(0).refCast(ts.mapType).structGet(ts.mapType, 1).localGet(i).arrayGet(ts.anyArrType).call(rt.fnHashCode)
                .op(WasmInstr.SimpleOp.I32_XOR)
                .op(WasmInstr.SimpleOp.I32_ADD).localSet(h)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end()
                .localGet(h).return_().end();
        c.i32Const(0);
    }

    private void emitColFmt(WasmModule.FuncDef fn) {
        int gStack = mod.addGlobal("G_COL_FMT_STACK", WasmValType.refNull(ts.anyArrType), true,
                ts.defaultConstExpr(WasmValType.refNull(ts.anyArrType)));
        int gDepth = mod.addGlobal("G_COL_FMT_DEPTH", WasmValType.I32, true, List.of(new WasmInstr.I32Const(0)));

        WasmCodeBuilder c = fn.code();
        int out = fn.addLocal("out", WasmValType.refNull(ts.stringType), 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        int sz = fn.addLocal("sz", WasmValType.I32, 1);
        int depth = fn.addLocal("depth", WasmValType.I32, 1);
        int stack = fn.addLocal("stack", WasmValType.refNull(ts.anyArrType), 1);
        int exn = fn.addLocal("exn", WasmValType.EXNREF, 1);

        // $Entry -> "k=v" (does not cycle directly without going through a collection/map)
        c.localGet(0).refTest(ts.entryType).if_()
                .localGet(0).refCast(ts.entryType).structGet(ts.entryType, 0).call(rt.fnFormat);
        rt.emitStringLit(c, "=");
        c.call(rt.fnStrConcat)
                .localGet(0).refCast(ts.entryType).structGet(ts.entryType, 1).call(rt.fnFormat)
                .call(rt.fnStrConcat).return_().end();

        // Lazy-init visited stack (capacity 64) for cycle detection on $List / $Set / $Map
        c.globalGet(gStack).refIsNull().if_()
                .i32Const(64).arrayNewDefault(ts.anyArrType).globalSet(gStack)
                .end();
        c.globalGet(gStack).localSet(stack);
        c.globalGet(gDepth).localSet(depth);

        // Cycle check
        c.i32Const(0).localSet(i);
        c.block().loop()
                .localGet(i).localGet(depth).op(WasmInstr.SimpleOp.I32_GE_S).brIf(1)
                .localGet(stack).refAsNonNull().localGet(i).arrayGet(ts.anyArrType)
                .localGet(0).refEq().if_()
                .localGet(0).refTest(ts.mapType).if_();
        rt.emitStringLit(c, "(this Map)");
        c.return_().end();
        rt.emitStringLit(c, "(this Collection)");
        c.return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(0).end().end();

        // Push self onto visited stack
        c.localGet(depth).i32Const(64).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(stack).refAsNonNull().localGet(depth).localGet(0).arraySet(ts.anyArrType)
                .localGet(depth).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).globalSet(gDepth)
                .end();

        // Guard formatting with try_table (catch_all_ref) so throwing element toString() restores stack
        c.block(); // done (depth 1 from try_table)
        c.block(WasmValType.EXNREF); // catch_exn (depth 0 from try_table)
        c.tryTable(List.of(WasmInstr.CatchClause.catchAllRef(0)));

        // $List or $Set -> "[a, b]"
        c.localGet(0).refTest(ts.listType).if_();
        emitSeqFmt(c, ts.listType, 0, 1, out, i, sz);
        c.else_().localGet(0).refTest(ts.setType).if_();
        emitSeqFmt(c, ts.setType, 0, 1, out, i, sz);
        c.else_().localGet(0).refTest(ts.mapType).if_();
        // $Map -> "{k1=v1, k2=v2}"
        rt.emitStringLit(c, "{");
        c.localSet(out)
                .localGet(0).refCast(ts.mapType).structGet(ts.mapType, 2).localSet(sz)
                .i32Const(0).localSet(i)
                .loop()
                .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(i).i32Const(0).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                .localGet(out).refAsNonNull();
        rt.emitStringLit(c, ", ");
        c.call(rt.fnStrConcat).localSet(out).end()
                .localGet(out).refAsNonNull()
                .localGet(0).refCast(ts.mapType).structGet(ts.mapType, 0).localGet(i).arrayGet(ts.anyArrType).call(rt.fnFormat)
                .call(rt.fnStrConcat);
        rt.emitStringLit(c, "=");
        c.call(rt.fnStrConcat)
                .localGet(0).refCast(ts.mapType).structGet(ts.mapType, 1).localGet(i).arrayGet(ts.anyArrType).call(rt.fnFormat)
                .call(rt.fnStrConcat).localSet(out)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end()
                .localGet(out).refAsNonNull();
        rt.emitStringLit(c, "}");
        c.call(rt.fnStrConcat).localSet(out)
                .else_();
        rt.emitStringLit(c, "<collection>");
        c.localSet(out).end().end().end();

        c.end(); // end try_table
        c.br(1); // normal completion -> jump past catch_exn
        c.end(); // end catch_exn -> stack: exnref
        c.localSet(exn);
        c.localGet(depth).i32Const(64).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(stack).refAsNonNull().localGet(depth).refNull(ts.anyType).arraySet(ts.anyArrType)
                .end();
        c.localGet(depth).globalSet(gDepth);
        c.localGet(exn).refAsNonNull().throwRef();
        c.end(); // end done

        // Pop visited stack and clear slot
        c.localGet(depth).i32Const(64).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(stack).refAsNonNull().localGet(depth).refNull(ts.anyType).arraySet(ts.anyArrType)
                .end();
        c.localGet(depth).globalSet(gDepth);

        c.localGet(out).refAsNonNull();
    }

    private void emitSeqFmt(WasmCodeBuilder c, int structType, int arrField, int szField, int out, int i, int sz) {
        rt.emitStringLit(c, "[");
        c.localSet(out)
                .localGet(0).refCast(structType).structGet(structType, szField).localSet(sz)
                .i32Const(0).localSet(i)
                .loop()
                .localGet(i).localGet(sz).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(i).i32Const(0).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                .localGet(out).refAsNonNull();
        rt.emitStringLit(c, ", ");
        c.call(rt.fnStrConcat).localSet(out).end()
                .localGet(out).refAsNonNull()
                .localGet(0).refCast(structType).structGet(structType, arrField).localGet(i).arrayGet(ts.anyArrType)
                .call(rt.fnFormat).call(rt.fnStrConcat).localSet(out)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1).end().end()
                .localGet(out).refAsNonNull();
        rt.emitStringLit(c, "]");
        c.call(rt.fnStrConcat).localSet(out);
    }
}
