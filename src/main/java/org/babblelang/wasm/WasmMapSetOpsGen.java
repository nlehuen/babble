package org.babblelang.wasm;

/**
 * Emits WasmGC {@code Set}/{@code SortedSet}, {@code Entry}, {@code Map}/{@code SortedMap}, and
 * {@code Iterator} runtime operations for {@link WasmCollectionOpsGen}.
 */
final class WasmMapSetOpsGen {
    private final WasmCollectionOpsGen col;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmListOpsGen listOps;

    WasmMapSetOpsGen(
            WasmCollectionOpsGen col,
            WasmTypeSystem ts,
            WasmRuntimeGen rt,
            WasmListOpsGen listOps) {
        this.col = col;
        this.ts = ts;
        this.rt = rt;
        this.listOps = listOps;
    }

    // ---------------------------------------------------------------- Set / SortedSet

    void emitSetOps(
            WasmModule.FuncDef dNew,
            WasmModule.FuncDef dAdd,
            WasmModule.FuncDef dContains,
            WasmModule.FuncDef dRem,
            WasmModule.FuncDef dClear,
            WasmModule.FuncDef dFirst,
            WasmModule.FuncDef dLast,
            WasmModule.FuncDef dAddAll,
            WasmModule.FuncDef dIter) {
        // set_new(sorted)
        dNew.code()
                .i32Const(4)
                .arrayNewDefault(ts.anyArrType)
                .i32Const(0)
                .localGet(0)
                .refNull(ts.mapType)
                .structNew(ts.setType);

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
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(arr)
                    .refAsNonNull()
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(1)
                    .call(rt.fnEquals)
                    .if_()
                    .i32Const(1)
                    .return_()
                    .end()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
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
            c.localGet(sz).op(WasmInstr.SimpleOp.I32_EQZ).if_().localGet(1).localGet(1);
            rt.emitStringLit(c, "a");
            rt.emitStringLit(c, "b");
            c.localGet(2).call(rt.fnCompare).drop().end();
            // Sorted set: linear/ordered scan with rt.fnCompare
            c.i32Const(0).localSet(pos);
            c.block()
                    .loop()
                    .localGet(pos)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(1)
                    .localGet(arr)
                    .refAsNonNull()
                    .localGet(pos)
                    .arrayGet(ts.anyArrType);
            rt.emitStringLit(c, "a");
            rt.emitStringLit(c, "b");
            c.localGet(2)
                    .call(rt.fnCompare)
                    .localTee(cmp)
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .i32Const(0)
                    .return_()
                    .end()
                    .localGet(cmp)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .br(3)
                    .end()
                    .localGet(pos)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(pos)
                    .br(1)
                    .end()
                    .end()
                    .end();
            c.else_();
            // Unsorted (insertion-ordered): duplicate check then append at sz
            c.localGet(0).localGet(1).call(col.fnSetContains).if_().i32Const(0).return_().end();
            c.localGet(sz).localSet(pos);
            c.end();

            // Grow backing array if full
            c.localGet(sz)
                    .localGet(cap)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .if_()
                    .localGet(cap)
                    .i32Const(2)
                    .op(WasmInstr.SimpleOp.I32_MUL)
                    .arrayNewDefault(ts.anyArrType)
                    .localTee(grown)
                    .i32Const(0)
                    .localGet(arr)
                    .refAsNonNull()
                    .i32Const(0)
                    .localGet(sz)
                    .arrayCopy(ts.anyArrType, ts.anyArrType)
                    .localGet(0)
                    .localGet(grown)
                    .refAsNonNull()
                    .structSet(ts.setType, 0)
                    .localGet(grown)
                    .localSet(arr)
                    .end();
            // Shift right from pos
            c.localGet(sz).localSet(k);
            c.loop()
                    .localGet(k)
                    .localGet(pos)
                    .op(WasmInstr.SimpleOp.I32_GT_S)
                    .if_()
                    .localGet(arr)
                    .refAsNonNull()
                    .localGet(k)
                    .localGet(arr)
                    .refAsNonNull()
                    .localGet(k)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(k)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .localSet(k)
                    .br(1)
                    .end()
                    .end();
            c.localGet(arr).refAsNonNull().localGet(pos).localGet(1).arraySet(ts.anyArrType);
            c.localGet(0)
                    .localGet(sz)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .structSet(ts.setType, 1);
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
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(arr)
                    .refAsNonNull()
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(1)
                    .call(rt.fnEquals)
                    .if_()
                    .localGet(i)
                    .localSet(k)
                    .localGet(sz)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .localSet(sz)
                    .loop()
                    .localGet(k)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(arr)
                    .refAsNonNull()
                    .localGet(k)
                    .localGet(arr)
                    .refAsNonNull()
                    .localGet(k)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(k)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(k)
                    .br(1)
                    .end()
                    .end()
                    .localGet(arr)
                    .refAsNonNull()
                    .localGet(sz)
                    .refNull(ts.anyType)
                    .arraySet(ts.anyArrType)
                    .localGet(0)
                    .localGet(sz)
                    .structSet(ts.setType, 1)
                    .localGet(0)
                    .structGet(ts.setType, 3)
                    .refIsNull()
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .localGet(0)
                    .structGet(ts.setType, 3)
                    .refAsNonNull()
                    .localGet(1)
                    .call(col.fnMapRemove)
                    .drop()
                    .end()
                    .i32Const(1)
                    .return_()
                    .end()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
            c.i32Const(0);
        }

        dClear.code()
                .localGet(0)
                .i32Const(0)
                .structSet(ts.setType, 1)
                .localGet(0)
                .structGet(ts.setType, 3)
                .refIsNull()
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .localGet(0)
                .structGet(ts.setType, 3)
                .refAsNonNull()
                .call(col.fnMapClear)
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
            c.localGet(0)
                    .structGet(ts.setType, 0)
                    .localGet(0)
                    .structGet(ts.setType, 1)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .arrayGet(ts.anyArrType);
        }

        listOps.emitCollectionAddAll(dAddAll, false);

        dIter.code()
                .localGet(0)
                .structGet(ts.setType, 0)
                .localGet(0)
                .structGet(ts.setType, 1)
                .i32Const(0)
                .structNew(ts.collectionIterType);
    }

    // ---------------------------------------------------------------- Entry & Map / SortedMap

    void emitEntryNew(WasmModule.FuncDef d) {
        d.code().localGet(0).localGet(1).refNull(ts.mapType).structNew(ts.entryType);
    }

    void emitEntrySetValue(WasmModule.FuncDef d) {
        WasmCodeBuilder c = d.code();
        int prev = d.addLocal("prev", ts.anyRef(), 3);
        c.localGet(0).structGet(ts.entryType, 1).localSet(prev);
        c.localGet(0).localGet(1).structSet(ts.entryType, 1);
        c.localGet(0)
                .structGet(ts.entryType, 2)
                .refIsNull()
                .op(WasmInstr.SimpleOp.I32_EQZ)
                .if_()
                .localGet(0)
                .structGet(ts.entryType, 2)
                .refAsNonNull()
                .localGet(0)
                .structGet(ts.entryType, 0)
                .localGet(1)
                .localGet(2)
                .call(col.fnMapPut)
                .drop()
                .end();
        c.localGet(prev);
    }

    void emitMapOps(
            WasmModule.FuncDef dNew,
            WasmModule.FuncDef dPut,
            WasmModule.FuncDef dGet,
            WasmModule.FuncDef dGetDef,
            WasmModule.FuncDef dHasK,
            WasmModule.FuncDef dHasV,
            WasmModule.FuncDef dRem,
            WasmModule.FuncDef dClear,
            WasmModule.FuncDef dKeys,
            WasmModule.FuncDef dVals,
            WasmModule.FuncDef dEntries,
            WasmModule.FuncDef dFirstK,
            WasmModule.FuncDef dLastK,
            WasmModule.FuncDef dPutAll) {
        // map_new(sorted)
        dNew.code()
                .i32Const(4)
                .arrayNewDefault(ts.anyArrType)
                .i32Const(4)
                .arrayNewDefault(ts.anyArrType)
                .i32Const(0)
                .localGet(0)
                .structNew(ts.mapType);

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
            c.localGet(sz).op(WasmInstr.SimpleOp.I32_EQZ).if_().localGet(1).localGet(1);
            rt.emitStringLit(c, "a");
            rt.emitStringLit(c, "b");
            c.localGet(3).call(rt.fnCompare).drop().end();
            // Sorted map: find position via rt.fnCompare
            c.i32Const(0).localSet(pos);
            c.block()
                    .loop()
                    .localGet(pos)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(1)
                    .localGet(ks)
                    .refAsNonNull()
                    .localGet(pos)
                    .arrayGet(ts.anyArrType);
            rt.emitStringLit(c, "a");
            rt.emitStringLit(c, "b");
            c.localGet(3)
                    .call(rt.fnCompare)
                    .localTee(cmp)
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .localGet(vs)
                    .refAsNonNull()
                    .localGet(pos)
                    .arrayGet(ts.anyArrType)
                    .localSet(prev)
                    .localGet(vs)
                    .refAsNonNull()
                    .localGet(pos)
                    .localGet(2)
                    .arraySet(ts.anyArrType)
                    .localGet(prev)
                    .return_()
                    .end()
                    .localGet(cmp)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .br(3)
                    .end()
                    .localGet(pos)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(pos)
                    .br(1)
                    .end()
                    .end()
                    .end();
            c.else_();
            // Insertion-ordered map: check existing key
            c.i32Const(0).localSet(pos);
            c.loop()
                    .localGet(pos)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(ks)
                    .refAsNonNull()
                    .localGet(pos)
                    .arrayGet(ts.anyArrType)
                    .localGet(1)
                    .call(rt.fnEquals)
                    .if_()
                    .localGet(vs)
                    .refAsNonNull()
                    .localGet(pos)
                    .arrayGet(ts.anyArrType)
                    .localSet(prev)
                    .localGet(vs)
                    .refAsNonNull()
                    .localGet(pos)
                    .localGet(2)
                    .arraySet(ts.anyArrType)
                    .localGet(prev)
                    .return_()
                    .end()
                    .localGet(pos)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(pos)
                    .br(1)
                    .end()
                    .end();
            c.localGet(sz).localSet(pos);
            c.end();

            // Grow if full
            c.localGet(sz)
                    .localGet(cap)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .if_()
                    .localGet(cap)
                    .i32Const(2)
                    .op(WasmInstr.SimpleOp.I32_MUL)
                    .arrayNewDefault(ts.anyArrType)
                    .localTee(gKs)
                    .i32Const(0)
                    .localGet(ks)
                    .refAsNonNull()
                    .i32Const(0)
                    .localGet(sz)
                    .arrayCopy(ts.anyArrType, ts.anyArrType)
                    .localGet(cap)
                    .i32Const(2)
                    .op(WasmInstr.SimpleOp.I32_MUL)
                    .arrayNewDefault(ts.anyArrType)
                    .localTee(gVs)
                    .i32Const(0)
                    .localGet(vs)
                    .refAsNonNull()
                    .i32Const(0)
                    .localGet(sz)
                    .arrayCopy(ts.anyArrType, ts.anyArrType)
                    .localGet(0)
                    .localGet(gKs)
                    .refAsNonNull()
                    .structSet(ts.mapType, 0)
                    .localGet(0)
                    .localGet(gVs)
                    .refAsNonNull()
                    .structSet(ts.mapType, 1)
                    .localGet(gKs)
                    .localSet(ks)
                    .localGet(gVs)
                    .localSet(vs)
                    .end();
            // Shift right from pos
            c.localGet(sz).localSet(k);
            c.loop()
                    .localGet(k)
                    .localGet(pos)
                    .op(WasmInstr.SimpleOp.I32_GT_S)
                    .if_()
                    .localGet(ks)
                    .refAsNonNull()
                    .localGet(k)
                    .localGet(ks)
                    .refAsNonNull()
                    .localGet(k)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(vs)
                    .refAsNonNull()
                    .localGet(k)
                    .localGet(vs)
                    .refAsNonNull()
                    .localGet(k)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(k)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .localSet(k)
                    .br(1)
                    .end()
                    .end();
            c.localGet(ks).refAsNonNull().localGet(pos).localGet(1).arraySet(ts.anyArrType);
            c.localGet(vs).refAsNonNull().localGet(pos).localGet(2).arraySet(ts.anyArrType);
            c.localGet(0)
                    .localGet(sz)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .structSet(ts.mapType, 2);
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
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(ks)
                    .refAsNonNull()
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(1)
                    .call(rt.fnEquals)
                    .if_()
                    .localGet(0)
                    .structGet(ts.mapType, 1)
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .return_()
                    .end()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
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
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(ks)
                    .refAsNonNull()
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(1)
                    .call(rt.fnEquals)
                    .if_()
                    .localGet(0)
                    .structGet(ts.mapType, 1)
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .return_()
                    .end()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
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
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(ks)
                    .refAsNonNull()
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(1)
                    .call(rt.fnEquals)
                    .if_()
                    .i32Const(1)
                    .return_()
                    .end()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
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
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(vs)
                    .refAsNonNull()
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(1)
                    .call(rt.fnEquals)
                    .if_()
                    .i32Const(1)
                    .return_()
                    .end()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
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
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(ks)
                    .refAsNonNull()
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(1)
                    .call(rt.fnEquals)
                    .if_()
                    .localGet(vs)
                    .refAsNonNull()
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localSet(prev)
                    .localGet(i)
                    .localSet(k)
                    .localGet(sz)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .localSet(sz)
                    .loop()
                    .localGet(k)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(ks)
                    .refAsNonNull()
                    .localGet(k)
                    .localGet(ks)
                    .refAsNonNull()
                    .localGet(k)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(vs)
                    .refAsNonNull()
                    .localGet(k)
                    .localGet(vs)
                    .refAsNonNull()
                    .localGet(k)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .arrayGet(ts.anyArrType)
                    .arraySet(ts.anyArrType)
                    .localGet(k)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(k)
                    .br(1)
                    .end()
                    .end()
                    .localGet(ks)
                    .refAsNonNull()
                    .localGet(sz)
                    .refNull(ts.anyType)
                    .arraySet(ts.anyArrType)
                    .localGet(vs)
                    .refAsNonNull()
                    .localGet(sz)
                    .refNull(ts.anyType)
                    .arraySet(ts.anyArrType)
                    .localGet(0)
                    .localGet(sz)
                    .structSet(ts.mapType, 2)
                    .localGet(prev)
                    .return_()
                    .end()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
            c.refNull(ts.anyType);
        }

        dClear.code().localGet(0).i32Const(0).structSet(ts.mapType, 2);

        // map_keySet(m)
        {
            WasmCodeBuilder c = dKeys.code();
            int s = dKeys.addLocal("s", WasmValType.refNull(ts.setType), 1);
            int i = dKeys.addLocal("i", WasmValType.I32, 1);
            int sz = dKeys.addLocal("sz", WasmValType.I32, 1);
            c.localGet(0).structGet(ts.mapType, 3).call(col.fnSetNew).localSet(s);
            c.localGet(0).structGet(ts.mapType, 2).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(s)
                    .refAsNonNull()
                    .localGet(0)
                    .structGet(ts.mapType, 0)
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .i32Const(0)
                    .call(col.fnSetAdd)
                    .drop()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
            c.localGet(s).refAsNonNull().localGet(0).structSet(ts.setType, 3);
            c.localGet(s).refAsNonNull();
        }
        // map_values(m)
        {
            WasmCodeBuilder c = dVals.code();
            int l = dVals.addLocal("l", WasmValType.refNull(ts.listType), 1);
            int i = dVals.addLocal("i", WasmValType.I32, 1);
            int sz = dVals.addLocal("sz", WasmValType.I32, 1);
            c.localGet(0).structGet(ts.mapType, 2).localTee(sz).call(col.fnListNew).localSet(l);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(l)
                    .refAsNonNull()
                    .localGet(0)
                    .structGet(ts.mapType, 1)
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .call(col.fnListAdd)
                    .drop()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
            c.localGet(l).refAsNonNull();
        }
        // map_entrySet(m)
        {
            WasmCodeBuilder c = dEntries.code();
            int s = dEntries.addLocal("s", WasmValType.refNull(ts.setType), 1);
            int i = dEntries.addLocal("i", WasmValType.I32, 1);
            int sz = dEntries.addLocal("sz", WasmValType.I32, 1);
            c.i32Const(0).call(col.fnSetNew).localSet(s);
            c.localGet(0).structGet(ts.mapType, 2).localSet(sz);
            c.i32Const(0).localSet(i);
            c.loop()
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(s)
                    .refAsNonNull()
                    .localGet(0)
                    .structGet(ts.mapType, 0)
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(0)
                    .structGet(ts.mapType, 1)
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(0)
                    .structNew(ts.entryType)
                    .i32Const(0)
                    .call(col.fnSetAdd)
                    .drop()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
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
            c.localGet(0)
                    .structGet(ts.mapType, 0)
                    .localGet(0)
                    .structGet(ts.mapType, 2)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
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
            c.localGet(1)
                    .refTest(ts.mapType)
                    .if_()
                    .localGet(1)
                    .refCast(ts.mapType)
                    .localTee(sm)
                    .structGet(ts.mapType, 2)
                    .localSet(sz)
                    .i32Const(0)
                    .localSet(i)
                    .loop()
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(0)
                    .localGet(sm)
                    .refAsNonNull()
                    .structGet(ts.mapType, 0)
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(sm)
                    .refAsNonNull()
                    .structGet(ts.mapType, 1)
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(2)
                    .call(col.fnMapPut)
                    .drop()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end()
                    .end();
        }
    }

    // ---------------------------------------------------------------- Iterator

    void emitIterOps(WasmModule.FuncDef dHas, WasmModule.FuncDef dNext) {
        dHas.code()
                .localGet(0)
                .structGet(ts.collectionIterType, 2)
                .localGet(0)
                .structGet(ts.collectionIterType, 1)
                .op(WasmInstr.SimpleOp.I32_LT_S);
        WasmCodeBuilder c = dNext.code();
        int p = dNext.addLocal("p", WasmValType.I32, 2);
        c.localGet(0)
                .structGet(ts.collectionIterType, 2)
                .localTee(p)
                .localGet(0)
                .structGet(ts.collectionIterType, 1)
                .op(WasmInstr.SimpleOp.I32_GE_S)
                .if_();
        rt.emitStringLit(c, "No such element");
        c.localGet(1).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(0)
                .localGet(p)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .structSet(ts.collectionIterType, 2);
        c.localGet(0).structGet(ts.collectionIterType, 0).localGet(p).arrayGet(ts.anyArrType);
    }
}
