package org.babblelang.wasm;

import java.util.List;

/**
 * Generates WasmGC runtime support for Babble's portable core collections ({@code list}, {@code
 * set}, {@code sortedset}, {@code map}, {@code sortedmap}, {@code entry}, and {@code
 * $CollectionIter}) ({@code BIP025-I3}, {@code BIP025-D6}) with exact JVM {@code java.util.*}
 * semantics.
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
        WasmModule.FuncDef dListNew =
                mod.addFunction(
                        "list_new",
                        ts.internFuncType(List.of(WasmValType.I32), List.of(refList)),
                        List.of("cap"));
        WasmModule.FuncDef dListAdd =
                mod.addFunction(
                        "list_add",
                        ts.internFuncType(List.of(refList, refAny), List.of(WasmValType.I32)),
                        List.of("l", "v"));
        WasmModule.FuncDef dListAddAt =
                mod.addFunction(
                        "list_addAt",
                        ts.internFuncType(
                                List.of(refList, WasmValType.I32, refAny, WasmValType.I32),
                                List.of()),
                        List.of("l", "i", "v", "line"));
        WasmModule.FuncDef dListGet =
                mod.addFunction(
                        "list_get",
                        ts.internFuncType(
                                List.of(refList, WasmValType.I32, WasmValType.I32),
                                List.of(refAny)),
                        List.of("l", "i", "line"));
        WasmModule.FuncDef dListSet =
                mod.addFunction(
                        "list_set",
                        ts.internFuncType(
                                List.of(refList, WasmValType.I32, refAny, WasmValType.I32),
                                List.of(refAny)),
                        List.of("l", "i", "v", "line"));
        WasmModule.FuncDef dListRemAt =
                mod.addFunction(
                        "list_removeAt",
                        ts.internFuncType(
                                List.of(refList, WasmValType.I32, WasmValType.I32),
                                List.of(refAny)),
                        List.of("l", "i", "line"));
        WasmModule.FuncDef dListRemVal =
                mod.addFunction(
                        "list_removeVal",
                        ts.internFuncType(List.of(refList, refAny), List.of(WasmValType.I32)),
                        List.of("l", "v"));
        WasmModule.FuncDef dListIdx =
                mod.addFunction(
                        "list_indexOf",
                        ts.internFuncType(List.of(refList, refAny), List.of(WasmValType.I32)),
                        List.of("l", "v"));
        WasmModule.FuncDef dListLastIdx =
                mod.addFunction(
                        "list_lastIndexOf",
                        ts.internFuncType(List.of(refList, refAny), List.of(WasmValType.I32)),
                        List.of("l", "v"));
        WasmModule.FuncDef dListContains =
                mod.addFunction(
                        "list_contains",
                        ts.internFuncType(List.of(refList, refAny), List.of(WasmValType.I32)),
                        List.of("l", "v"));
        WasmModule.FuncDef dListClear =
                mod.addFunction(
                        "list_clear", ts.internFuncType(List.of(refList), List.of()), List.of("l"));
        WasmModule.FuncDef dListAddAll =
                mod.addFunction(
                        "list_addAll",
                        ts.internFuncType(
                                List.of(refList, refAny, WasmValType.I32),
                                List.of(WasmValType.I32)),
                        List.of("l", "src", "line"));
        WasmModule.FuncDef dListSub =
                mod.addFunction(
                        "list_subList",
                        ts.internFuncType(
                                List.of(refList, WasmValType.I32, WasmValType.I32, WasmValType.I32),
                                List.of(refList)),
                        List.of("l", "from", "to", "line"));
        WasmModule.FuncDef dListSlice =
                mod.addFunction(
                        "list_slice",
                        ts.internFuncType(List.of(refList, WasmValType.I32), List.of(refList)),
                        List.of("l", "from"));
        WasmModule.FuncDef dListIter =
                mod.addFunction(
                        "list_iter",
                        ts.internFuncType(List.of(refList), List.of(refIter)),
                        List.of("l"));

        WasmModule.FuncDef dSetNew =
                mod.addFunction(
                        "set_new",
                        ts.internFuncType(List.of(WasmValType.I32), List.of(refSet)),
                        List.of("sorted"));
        WasmModule.FuncDef dSetAdd =
                mod.addFunction(
                        "set_add",
                        ts.internFuncType(
                                List.of(refSet, refAny, WasmValType.I32), List.of(WasmValType.I32)),
                        List.of("s", "v", "line"));
        WasmModule.FuncDef dSetContains =
                mod.addFunction(
                        "set_contains",
                        ts.internFuncType(List.of(refSet, refAny), List.of(WasmValType.I32)),
                        List.of("s", "v"));
        WasmModule.FuncDef dSetRem =
                mod.addFunction(
                        "set_remove",
                        ts.internFuncType(List.of(refSet, refAny), List.of(WasmValType.I32)),
                        List.of("s", "v"));
        WasmModule.FuncDef dSetClear =
                mod.addFunction(
                        "set_clear", ts.internFuncType(List.of(refSet), List.of()), List.of("s"));
        WasmModule.FuncDef dSetFirst =
                mod.addFunction(
                        "set_first",
                        ts.internFuncType(List.of(refSet, WasmValType.I32), List.of(refAny)),
                        List.of("s", "line"));
        WasmModule.FuncDef dSetLast =
                mod.addFunction(
                        "set_last",
                        ts.internFuncType(List.of(refSet, WasmValType.I32), List.of(refAny)),
                        List.of("s", "line"));
        WasmModule.FuncDef dSetAddAll =
                mod.addFunction(
                        "set_addAll",
                        ts.internFuncType(
                                List.of(refSet, refAny, WasmValType.I32), List.of(WasmValType.I32)),
                        List.of("s", "src", "line"));
        WasmModule.FuncDef dSetIter =
                mod.addFunction(
                        "set_iter",
                        ts.internFuncType(List.of(refSet), List.of(refIter)),
                        List.of("s"));

        WasmModule.FuncDef dEntryNew =
                mod.addFunction(
                        "entry_new",
                        ts.internFuncType(List.of(refAny, refAny), List.of(refEntry)),
                        List.of("k", "v"));
        WasmModule.FuncDef dEntrySetVal =
                mod.addFunction(
                        "entry_setValue",
                        ts.internFuncType(
                                List.of(refEntry, refAny, WasmValType.I32), List.of(refAny)),
                        List.of("e", "v", "line"));

        WasmModule.FuncDef dMapNew =
                mod.addFunction(
                        "map_new",
                        ts.internFuncType(List.of(WasmValType.I32), List.of(refMap)),
                        List.of("sorted"));
        WasmModule.FuncDef dMapPut =
                mod.addFunction(
                        "map_put",
                        ts.internFuncType(
                                List.of(refMap, refAny, refAny, WasmValType.I32), List.of(refAny)),
                        List.of("m", "k", "v", "line"));
        WasmModule.FuncDef dMapGet =
                mod.addFunction(
                        "map_get",
                        ts.internFuncType(List.of(refMap, refAny), List.of(refAny)),
                        List.of("m", "k"));
        WasmModule.FuncDef dMapGetDef =
                mod.addFunction(
                        "map_getOrDefault",
                        ts.internFuncType(List.of(refMap, refAny, refAny), List.of(refAny)),
                        List.of("m", "k", "def"));
        WasmModule.FuncDef dMapHasK =
                mod.addFunction(
                        "map_containsKey",
                        ts.internFuncType(List.of(refMap, refAny), List.of(WasmValType.I32)),
                        List.of("m", "k"));
        WasmModule.FuncDef dMapHasV =
                mod.addFunction(
                        "map_containsValue",
                        ts.internFuncType(List.of(refMap, refAny), List.of(WasmValType.I32)),
                        List.of("m", "v"));
        WasmModule.FuncDef dMapRem =
                mod.addFunction(
                        "map_remove",
                        ts.internFuncType(List.of(refMap, refAny), List.of(refAny)),
                        List.of("m", "k"));
        WasmModule.FuncDef dMapClear =
                mod.addFunction(
                        "map_clear", ts.internFuncType(List.of(refMap), List.of()), List.of("m"));
        WasmModule.FuncDef dMapKeys =
                mod.addFunction(
                        "map_keySet",
                        ts.internFuncType(List.of(refMap), List.of(refSet)),
                        List.of("m"));
        WasmModule.FuncDef dMapVals =
                mod.addFunction(
                        "map_values",
                        ts.internFuncType(List.of(refMap), List.of(refList)),
                        List.of("m"));
        WasmModule.FuncDef dMapEntries =
                mod.addFunction(
                        "map_entrySet",
                        ts.internFuncType(List.of(refMap), List.of(refSet)),
                        List.of("m"));
        WasmModule.FuncDef dMapFirstK =
                mod.addFunction(
                        "map_firstKey",
                        ts.internFuncType(List.of(refMap, WasmValType.I32), List.of(refAny)),
                        List.of("m", "line"));
        WasmModule.FuncDef dMapLastK =
                mod.addFunction(
                        "map_lastKey",
                        ts.internFuncType(List.of(refMap, WasmValType.I32), List.of(refAny)),
                        List.of("m", "line"));
        WasmModule.FuncDef dMapPutAll =
                mod.addFunction(
                        "map_putAll",
                        ts.internFuncType(List.of(refMap, refAny, WasmValType.I32), List.of()),
                        List.of("m", "src", "line"));

        WasmModule.FuncDef dIterHas =
                mod.addFunction(
                        "iter_hasNext",
                        ts.internFuncType(List.of(refIter), List.of(WasmValType.I32)),
                        List.of("it"));
        WasmModule.FuncDef dIterNext =
                mod.addFunction(
                        "iter_next",
                        ts.internFuncType(List.of(refIter, WasmValType.I32), List.of(refAny)),
                        List.of("it", "line"));

        WasmModule.FuncDef dColEq =
                mod.addFunction(
                        "col_equals",
                        ts.internFuncType(List.of(refAny, refAny), List.of(WasmValType.I32)),
                        List.of("a", "b"));
        WasmModule.FuncDef dColHash =
                mod.addFunction(
                        "col_hashCode",
                        ts.internFuncType(List.of(refAny), List.of(WasmValType.I32)),
                        List.of("v"));
        WasmModule.FuncDef dColFmt =
                mod.addFunction(
                        "col_format",
                        ts.internFuncType(List.of(refAny), List.of(refStr)),
                        List.of("v"));

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
        WasmListOpsGen listOps = new WasmListOpsGen(this, ts, rt);
        WasmMapSetOpsGen mapSetOps = new WasmMapSetOpsGen(this, ts, rt, listOps);
        listOps.emitListOps(
                dListNew,
                dListAdd,
                dListAddAt,
                dListGet,
                dListSet,
                dListRemAt,
                dListRemVal,
                dListIdx,
                dListLastIdx,
                dListContains,
                dListClear,
                dListAddAll,
                dListSub,
                dListSlice,
                dListIter);
        mapSetOps.emitSetOps(
                dSetNew,
                dSetAdd,
                dSetContains,
                dSetRem,
                dSetClear,
                dSetFirst,
                dSetLast,
                dSetAddAll,
                dSetIter);
        mapSetOps.emitEntryNew(dEntryNew);
        mapSetOps.emitEntrySetValue(dEntrySetVal);
        mapSetOps.emitMapOps(
                dMapNew,
                dMapPut,
                dMapGet,
                dMapGetDef,
                dMapHasK,
                dMapHasV,
                dMapRem,
                dMapClear,
                dMapKeys,
                dMapVals,
                dMapEntries,
                dMapFirstK,
                dMapLastK,
                dMapPutAll);
        mapSetOps.emitIterOps(dIterHas, dIterNext);
        emitColEq(dColEq);
        emitColHash(dColHash);
        emitColFmt(dColFmt);
    }

    // ---------------------------------------------------------------- Equality, HashCode,
    // Formatting

    private void emitColEq(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int i = fn.addLocal("i", WasmValType.I32, 2);
        int sz = fn.addLocal("sz", WasmValType.I32, 2);
        // $List == $List (order-sensitive)
        c.localGet(0)
                .refTest(ts.listType)
                .localGet(1)
                .refTest(ts.listType)
                .op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(0)
                .refCast(ts.listType)
                .structGet(ts.listType, 1)
                .localTee(sz)
                .localGet(1)
                .refCast(ts.listType)
                .structGet(ts.listType, 1)
                .op(WasmInstr.SimpleOp.I32_NE)
                .if_()
                .i32Const(0)
                .return_()
                .end()
                .i32Const(0)
                .localSet(i)
                .loop()
                .localGet(i)
                .localGet(sz)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(0)
                .refCast(ts.listType)
                .structGet(ts.listType, 0)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .localGet(1)
                .refCast(ts.listType)
                .structGet(ts.listType, 0)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .call(rt.fnEquals)
                .op(WasmInstr.SimpleOp.I32_EQZ)
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
                .end()
                .i32Const(1)
                .return_()
                .end();
        // $Set == $Set (order-independent)
        c.localGet(0)
                .refTest(ts.setType)
                .localGet(1)
                .refTest(ts.setType)
                .op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(0)
                .refCast(ts.setType)
                .structGet(ts.setType, 1)
                .localTee(sz)
                .localGet(1)
                .refCast(ts.setType)
                .structGet(ts.setType, 1)
                .op(WasmInstr.SimpleOp.I32_NE)
                .if_()
                .i32Const(0)
                .return_()
                .end()
                .i32Const(0)
                .localSet(i)
                .loop()
                .localGet(i)
                .localGet(sz)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(1)
                .refCast(ts.setType)
                .localGet(0)
                .refCast(ts.setType)
                .structGet(ts.setType, 0)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .call(fnSetContains)
                .op(WasmInstr.SimpleOp.I32_EQZ)
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
                .end()
                .i32Const(1)
                .return_()
                .end();
        // $Entry == $Entry
        c.localGet(0)
                .refTest(ts.entryType)
                .localGet(1)
                .refTest(ts.entryType)
                .op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(0)
                .refCast(ts.entryType)
                .structGet(ts.entryType, 0)
                .localGet(1)
                .refCast(ts.entryType)
                .structGet(ts.entryType, 0)
                .call(rt.fnEquals)
                .localGet(0)
                .refCast(ts.entryType)
                .structGet(ts.entryType, 1)
                .localGet(1)
                .refCast(ts.entryType)
                .structGet(ts.entryType, 1)
                .call(rt.fnEquals)
                .op(WasmInstr.SimpleOp.I32_AND)
                .return_()
                .end();
        // $Map == $Map (order-independent)
        int kTmp = fn.addLocal("kTmp", ts.anyRef(), 2);
        c.localGet(0)
                .refTest(ts.mapType)
                .localGet(1)
                .refTest(ts.mapType)
                .op(WasmInstr.SimpleOp.I32_AND)
                .if_()
                .localGet(0)
                .refCast(ts.mapType)
                .structGet(ts.mapType, 2)
                .localTee(sz)
                .localGet(1)
                .refCast(ts.mapType)
                .structGet(ts.mapType, 2)
                .op(WasmInstr.SimpleOp.I32_NE)
                .if_()
                .i32Const(0)
                .return_()
                .end()
                .i32Const(0)
                .localSet(i)
                .loop()
                .localGet(i)
                .localGet(sz)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(0)
                .refCast(ts.mapType)
                .structGet(ts.mapType, 0)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .localSet(kTmp)
                .localGet(1)
                .refCast(ts.mapType)
                .localGet(kTmp)
                .call(fnMapContainsKey)
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .i32Const(0)
                .return_()
                .end()
                .localGet(0)
                .refCast(ts.mapType)
                .structGet(ts.mapType, 1)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .localGet(1)
                .refCast(ts.mapType)
                .localGet(kTmp)
                .call(fnMapGet)
                .call(rt.fnEquals)
                .op(WasmInstr.SimpleOp.I32_EQZ)
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
                .end()
                .i32Const(1)
                .return_()
                .end();
        c.i32Const(0);
    }

    private void emitColHash(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int h = fn.addLocal("h", WasmValType.I32, 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        int sz = fn.addLocal("sz", WasmValType.I32, 1);
        // $List: Java List.hashCode (h = 1; h = 31*h + hash(e))
        c.localGet(0)
                .refTest(ts.listType)
                .if_()
                .i32Const(1)
                .localSet(h)
                .localGet(0)
                .refCast(ts.listType)
                .structGet(ts.listType, 1)
                .localSet(sz)
                .i32Const(0)
                .localSet(i)
                .loop()
                .localGet(i)
                .localGet(sz)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(h)
                .i32Const(31)
                .op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(0)
                .refCast(ts.listType)
                .structGet(ts.listType, 0)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .call(rt.fnHashCode)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(h)
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end()
                .localGet(h)
                .return_()
                .end();
        // $Set: Java Set.hashCode (sum of element hashes)
        c.localGet(0)
                .refTest(ts.setType)
                .if_()
                .i32Const(0)
                .localSet(h)
                .localGet(0)
                .refCast(ts.setType)
                .structGet(ts.setType, 1)
                .localSet(sz)
                .i32Const(0)
                .localSet(i)
                .loop()
                .localGet(i)
                .localGet(sz)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(h)
                .localGet(0)
                .refCast(ts.setType)
                .structGet(ts.setType, 0)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .call(rt.fnHashCode)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(h)
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end()
                .localGet(h)
                .return_()
                .end();
        // $Entry: hash(k) ^ hash(v)
        c.localGet(0)
                .refTest(ts.entryType)
                .if_()
                .localGet(0)
                .refCast(ts.entryType)
                .structGet(ts.entryType, 0)
                .call(rt.fnHashCode)
                .localGet(0)
                .refCast(ts.entryType)
                .structGet(ts.entryType, 1)
                .call(rt.fnHashCode)
                .op(WasmInstr.SimpleOp.I32_XOR)
                .return_()
                .end();
        // $Map: sum of (hash(k) ^ hash(v))
        c.localGet(0)
                .refTest(ts.mapType)
                .if_()
                .i32Const(0)
                .localSet(h)
                .localGet(0)
                .refCast(ts.mapType)
                .structGet(ts.mapType, 2)
                .localSet(sz)
                .i32Const(0)
                .localSet(i)
                .loop()
                .localGet(i)
                .localGet(sz)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(h)
                .localGet(0)
                .refCast(ts.mapType)
                .structGet(ts.mapType, 0)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .call(rt.fnHashCode)
                .localGet(0)
                .refCast(ts.mapType)
                .structGet(ts.mapType, 1)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .call(rt.fnHashCode)
                .op(WasmInstr.SimpleOp.I32_XOR)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(h)
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end()
                .localGet(h)
                .return_()
                .end();
        c.i32Const(0);
    }

    private void emitColFmt(WasmModule.FuncDef fn) {
        int gStack =
                mod.addGlobal(
                        "G_COL_FMT_STACK",
                        WasmValType.refNull(ts.anyArrType),
                        true,
                        ts.defaultConstExpr(WasmValType.refNull(ts.anyArrType)));
        int gDepth =
                mod.addGlobal(
                        "G_COL_FMT_DEPTH",
                        WasmValType.I32,
                        true,
                        List.of(new WasmInstr.I32Const(0)));

        WasmCodeBuilder c = fn.code();
        int out = fn.addLocal("out", WasmValType.refNull(ts.stringType), 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        int sz = fn.addLocal("sz", WasmValType.I32, 1);
        int depth = fn.addLocal("depth", WasmValType.I32, 1);
        int stack = fn.addLocal("stack", WasmValType.refNull(ts.anyArrType), 1);
        int exn = fn.addLocal("exn", WasmValType.EXNREF, 1);

        // $Entry -> "k=v" (does not cycle directly without going through a collection/map)
        c.localGet(0)
                .refTest(ts.entryType)
                .if_()
                .localGet(0)
                .refCast(ts.entryType)
                .structGet(ts.entryType, 0)
                .call(rt.fnFormat);
        rt.emitStringLit(c, "=");
        c.call(rt.fnStrConcat)
                .localGet(0)
                .refCast(ts.entryType)
                .structGet(ts.entryType, 1)
                .call(rt.fnFormat)
                .call(rt.fnStrConcat)
                .return_()
                .end();

        // Lazy-init visited stack (capacity 64) for cycle detection on $List / $Set / $Map
        c.globalGet(gStack)
                .refIsNull()
                .if_()
                .i32Const(64)
                .arrayNewDefault(ts.anyArrType)
                .globalSet(gStack)
                .end();
        c.globalGet(gStack).localSet(stack);
        c.globalGet(gDepth).localSet(depth);

        // Cycle check
        c.i32Const(0).localSet(i);
        c.block()
                .loop()
                .localGet(i)
                .localGet(depth)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .brIf(1)
                .localGet(stack)
                .refAsNonNull()
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .localGet(0)
                .refEq()
                .if_()
                .localGet(0)
                .refTest(ts.mapType)
                .if_();
        rt.emitStringLit(c, "(this Map)");
        c.return_().end();
        rt.emitStringLit(c, "(this Collection)");
        c.return_()
                .end()
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(0)
                .end()
                .end();

        // Push self onto visited stack
        c.localGet(depth)
                .i32Const(64)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(stack)
                .refAsNonNull()
                .localGet(depth)
                .localGet(0)
                .arraySet(ts.anyArrType)
                .localGet(depth)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .globalSet(gDepth)
                .end();

        // Guard formatting with try_table (catch_all_ref) so throwing element toString() restores
        // stack
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
                .localGet(0)
                .refCast(ts.mapType)
                .structGet(ts.mapType, 2)
                .localSet(sz)
                .i32Const(0)
                .localSet(i)
                .loop()
                .localGet(i)
                .localGet(sz)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(i)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GT_S)
                .if_()
                .localGet(out)
                .refAsNonNull();
        rt.emitStringLit(c, ", ");
        c.call(rt.fnStrConcat)
                .localSet(out)
                .end()
                .localGet(out)
                .refAsNonNull()
                .localGet(0)
                .refCast(ts.mapType)
                .structGet(ts.mapType, 0)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .call(rt.fnFormat)
                .call(rt.fnStrConcat);
        rt.emitStringLit(c, "=");
        c.call(rt.fnStrConcat)
                .localGet(0)
                .refCast(ts.mapType)
                .structGet(ts.mapType, 1)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .call(rt.fnFormat)
                .call(rt.fnStrConcat)
                .localSet(out)
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end()
                .localGet(out)
                .refAsNonNull();
        rt.emitStringLit(c, "}");
        c.call(rt.fnStrConcat).localSet(out).else_();
        rt.emitStringLit(c, "<collection>");
        c.localSet(out).end().end().end();

        c.end(); // end try_table
        c.br(1); // normal completion -> jump past catch_exn
        c.end(); // end catch_exn -> stack: exnref
        c.localSet(exn);
        c.localGet(depth)
                .i32Const(64)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(stack)
                .refAsNonNull()
                .localGet(depth)
                .refNull(ts.anyType)
                .arraySet(ts.anyArrType)
                .end();
        c.localGet(depth).globalSet(gDepth);
        c.localGet(exn).refAsNonNull().throwRef();
        c.end(); // end done

        // Pop visited stack and clear slot
        c.localGet(depth)
                .i32Const(64)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(stack)
                .refAsNonNull()
                .localGet(depth)
                .refNull(ts.anyType)
                .arraySet(ts.anyArrType)
                .end();
        c.localGet(depth).globalSet(gDepth);

        c.localGet(out).refAsNonNull();
    }

    private void emitSeqFmt(
            WasmCodeBuilder c, int structType, int arrField, int szField, int out, int i, int sz) {
        rt.emitStringLit(c, "[");
        c.localSet(out)
                .localGet(0)
                .refCast(structType)
                .structGet(structType, szField)
                .localSet(sz)
                .i32Const(0)
                .localSet(i)
                .loop()
                .localGet(i)
                .localGet(sz)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(i)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GT_S)
                .if_()
                .localGet(out)
                .refAsNonNull();
        rt.emitStringLit(c, ", ");
        c.call(rt.fnStrConcat)
                .localSet(out)
                .end()
                .localGet(out)
                .refAsNonNull()
                .localGet(0)
                .refCast(structType)
                .structGet(structType, arrField)
                .localGet(i)
                .arrayGet(ts.anyArrType)
                .call(rt.fnFormat)
                .call(rt.fnStrConcat)
                .localSet(out)
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end()
                .localGet(out)
                .refAsNonNull();
        rt.emitStringLit(c, "]");
        c.call(rt.fnStrConcat).localSet(out);
    }
}
