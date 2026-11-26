package org.babblelang.wasm;

/**
 * Emits WasmGC {@code List} runtime operations and shared {@code addAll} for {@link
 * WasmCollectionOpsGen}.
 */
final class WasmListOpsGen {
    private final WasmCollectionOpsGen col;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;

    WasmListOpsGen(WasmCollectionOpsGen col, WasmTypeSystem ts, WasmRuntimeGen rt) {
        this.col = col;
        this.ts = ts;
        this.rt = rt;
    }

    // ---------------------------------------------------------------- List

    void emitListOps(
            WasmModule.FuncDef dNew,
            WasmModule.FuncDef dAdd,
            WasmModule.FuncDef dAddAt,
            WasmModule.FuncDef dGet,
            WasmModule.FuncDef dSet,
            WasmModule.FuncDef dRemAt,
            WasmModule.FuncDef dRemVal,
            WasmModule.FuncDef dIdx,
            WasmModule.FuncDef dLastIdx,
            WasmModule.FuncDef dContains,
            WasmModule.FuncDef dClear,
            WasmModule.FuncDef dAddAll,
            WasmModule.FuncDef dSub,
            WasmModule.FuncDef dSlice,
            WasmModule.FuncDef dIter) {
        // list_new(cap)
        {
            WasmCodeBuilder c = dNew.code();
            int cap = dNew.addLocal("c", WasmValType.I32, 1);
            c.localGet(0)
                    .i32Const(4)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_(WasmValType.I32)
                    .i32Const(4)
                    .else_()
                    .localGet(0)
                    .end()
                    .localSet(cap);
            c.localGet(cap)
                    .arrayNewDefault(ts.anyArrType)
                    .i32Const(0)
                    .refNull(ts.listType)
                    .i32Const(0)
                    .structNew(ts.listType);
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
                    .structSet(ts.listType, 0)
                    .localGet(grown)
                    .localSet(arr)
                    .end();
            c.localGet(arr).refAsNonNull().localGet(sz).localGet(1).arraySet(ts.anyArrType);
            c.localGet(0)
                    .localGet(sz)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .structSet(ts.listType, 1);
            // Propagate to backing parent list if this is a subList view
            c.localGet(0)
                    .structGet(ts.listType, 2)
                    .refIsNull()
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .localGet(0)
                    .structGet(ts.listType, 2)
                    .refAsNonNull()
                    .localGet(0)
                    .structGet(ts.listType, 3)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localGet(1)
                    .i32Const(0)
                    .call(col.fnListAddAt)
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
            c.localGet(1)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .localGet(1)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_GT_S)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .if_();
            rt.emitStringLit(c, "Index out of bounds");
            c.localGet(3).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0).structGet(ts.listType, 0).localTee(arr).arrayLen().localSet(cap);
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
                    .structSet(ts.listType, 0)
                    .localGet(grown)
                    .localSet(arr)
                    .end();
            c.localGet(sz).localSet(k);
            c.loop()
                    .localGet(k)
                    .localGet(1)
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
            c.localGet(arr).refAsNonNull().localGet(1).localGet(2).arraySet(ts.anyArrType);
            c.localGet(0)
                    .localGet(sz)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .structSet(ts.listType, 1);
            c.localGet(0)
                    .structGet(ts.listType, 2)
                    .refIsNull()
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .localGet(0)
                    .structGet(ts.listType, 2)
                    .refAsNonNull()
                    .localGet(0)
                    .structGet(ts.listType, 3)
                    .localGet(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localGet(2)
                    .localGet(3)
                    .call(col.fnListAddAt)
                    .end();
        }
        // list_get(l, i, line)
        {
            WasmCodeBuilder c = dGet.code();
            c.localGet(1)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .localGet(1)
                    .localGet(0)
                    .structGet(ts.listType, 1)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .if_();
            rt.emitStringLit(c, "Index out of bounds");
            c.localGet(2).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0).structGet(ts.listType, 0).localGet(1).arrayGet(ts.anyArrType);
        }
        // list_set(l, i, v, line) -> prev
        {
            WasmCodeBuilder c = dSet.code();
            int prev = dSet.addLocal("prev", ts.anyRef(), 4);
            c.localGet(1)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .localGet(1)
                    .localGet(0)
                    .structGet(ts.listType, 1)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .if_();
            rt.emitStringLit(c, "Index out of bounds");
            c.localGet(3).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0)
                    .structGet(ts.listType, 0)
                    .localGet(1)
                    .arrayGet(ts.anyArrType)
                    .localSet(prev);
            c.localGet(0).structGet(ts.listType, 0).localGet(1).localGet(2).arraySet(ts.anyArrType);
            c.localGet(0)
                    .structGet(ts.listType, 2)
                    .refIsNull()
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .localGet(0)
                    .structGet(ts.listType, 2)
                    .refAsNonNull()
                    .localGet(0)
                    .structGet(ts.listType, 3)
                    .localGet(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localGet(2)
                    .localGet(3)
                    .call(col.fnListSet)
                    .drop()
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
            c.localGet(1)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .localGet(1)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .if_();
            rt.emitStringLit(c, "Index out of bounds");
            c.localGet(2).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(0)
                    .structGet(ts.listType, 0)
                    .localTee(arr)
                    .localGet(1)
                    .arrayGet(ts.anyArrType)
                    .localSet(prev);
            c.localGet(1).localSet(k);
            c.localGet(sz).i32Const(1).op(WasmInstr.SimpleOp.I32_SUB).localSet(sz);
            c.loop()
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
                    .end();
            c.localGet(arr).refAsNonNull().localGet(sz).refNull(ts.anyType).arraySet(ts.anyArrType);
            c.localGet(0).localGet(sz).structSet(ts.listType, 1);
            c.localGet(0)
                    .structGet(ts.listType, 2)
                    .refIsNull()
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .localGet(0)
                    .structGet(ts.listType, 2)
                    .refAsNonNull()
                    .localGet(0)
                    .structGet(ts.listType, 3)
                    .localGet(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localGet(2)
                    .call(col.fnListRemoveAt)
                    .drop()
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
                    .return_()
                    .end()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_ADD)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
            c.i32Const(-1);
        }
        // list_lastIndexOf(l, v)
        {
            WasmCodeBuilder c = dLastIdx.code();
            int arr = dLastIdx.addLocal("arr", WasmValType.refNull(ts.anyArrType), 2);
            int i = dLastIdx.addLocal("i", WasmValType.I32, 2);
            c.localGet(0).structGet(ts.listType, 0).localSet(arr);
            c.localGet(0)
                    .structGet(ts.listType, 1)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .localSet(i);
            c.loop()
                    .localGet(i)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_GE_S)
                    .if_()
                    .localGet(arr)
                    .refAsNonNull()
                    .localGet(i)
                    .arrayGet(ts.anyArrType)
                    .localGet(1)
                    .call(rt.fnEquals)
                    .if_()
                    .localGet(i)
                    .return_()
                    .end()
                    .localGet(i)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .localSet(i)
                    .br(1)
                    .end()
                    .end();
            c.i32Const(-1);
        }
        // list_contains(l, v)
        dContains
                .code()
                .localGet(0)
                .localGet(1)
                .call(col.fnListIndexOf)
                .i32Const(0)
                .op(WasmInstr.SimpleOp.I32_GE_S);
        // list_removeVal(l, v)
        {
            WasmCodeBuilder c = dRemVal.code();
            int idx = dRemVal.addLocal("idx", WasmValType.I32, 2);
            c.localGet(0)
                    .localGet(1)
                    .call(col.fnListIndexOf)
                    .localTee(idx)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .i32Const(0)
                    .return_()
                    .end();
            c.localGet(0).localGet(idx).i32Const(0).call(col.fnListRemoveAt).drop().i32Const(1);
        }
        // list_clear(l)
        {
            WasmCodeBuilder c = dClear.code();
            int rem = dClear.addLocal("rem", WasmValType.I32, 1);
            c.localGet(0)
                    .structGet(ts.listType, 2)
                    .refIsNull()
                    .op(WasmInstr.SimpleOp.I32_EQZ)
                    .if_()
                    .localGet(0)
                    .structGet(ts.listType, 1)
                    .localSet(rem)
                    .loop()
                    .localGet(rem)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_GT_S)
                    .if_()
                    .localGet(0)
                    .structGet(ts.listType, 2)
                    .refAsNonNull()
                    .localGet(0)
                    .structGet(ts.listType, 3)
                    .i32Const(0)
                    .call(col.fnListRemoveAt)
                    .drop()
                    .localGet(rem)
                    .i32Const(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .localSet(rem)
                    .br(1)
                    .end()
                    .end()
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
            c.localGet(1)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .localGet(2)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_GT_S)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .localGet(1)
                    .localGet(2)
                    .op(WasmInstr.SimpleOp.I32_GT_S)
                    .op(WasmInstr.SimpleOp.I32_OR)
                    .if_();
            rt.emitStringLit(c, "Index out of bounds");
            c.localGet(3).call(rt.fnThrowMsg).unreachable().end();
            c.localGet(2)
                    .localGet(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .call(col.fnListNew)
                    .localSet(res);
            c.localGet(1).localSet(i);
            c.loop()
                    .localGet(i)
                    .localGet(2)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(res)
                    .refAsNonNull()
                    .localGet(0)
                    .structGet(ts.listType, 0)
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
            c.localGet(0)
                    .structGet(ts.listType, 1)
                    .localTee(sz)
                    .localGet(1)
                    .op(WasmInstr.SimpleOp.I32_SUB)
                    .localTee(n)
                    .i32Const(0)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .i32Const(0)
                    .localSet(n)
                    .end();
            c.localGet(n).call(col.fnListNew).localSet(res);
            c.localGet(1).localSet(i);
            c.loop()
                    .localGet(i)
                    .localGet(sz)
                    .op(WasmInstr.SimpleOp.I32_LT_S)
                    .if_()
                    .localGet(res)
                    .refAsNonNull()
                    .localGet(0)
                    .structGet(ts.listType, 0)
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
            c.localGet(res).refAsNonNull();
        }
        // list_iter(l)
        dIter.code()
                .localGet(0)
                .structGet(ts.listType, 0)
                .localGet(0)
                .structGet(ts.listType, 1)
                .i32Const(0)
                .structNew(ts.collectionIterType);
    }

    void emitCollectionAddAll(WasmModule.FuncDef fn, boolean isList) {
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
        c.localGet(1)
                .refTest(ts.listType)
                .if_()
                .localGet(1)
                .refCast(ts.listType)
                .structGet(ts.listType, 0)
                .localSet(srcArr)
                .localGet(1)
                .refCast(ts.listType)
                .structGet(ts.listType, 1)
                .localSet(sz);
        emitLoopPushFromAnyArr(c, isList, srcArr, sz, i, changed);
        c.localGet(changed).return_().end();

        // From $Set
        c.localGet(1)
                .refTest(ts.setType)
                .if_()
                .localGet(1)
                .refCast(ts.setType)
                .structGet(ts.setType, 0)
                .localSet(srcArr)
                .localGet(1)
                .refCast(ts.setType)
                .structGet(ts.setType, 1)
                .localSet(sz);
        emitLoopPushFromAnyArr(c, isList, srcArr, sz, i, changed);
        c.localGet(changed).return_().end();

        // From $RefArray
        c.localGet(1)
                .refTest(ts.refArrayType)
                .if_()
                .localGet(1)
                .refCast(ts.refArrayType)
                .structGet(ts.refArrayType, 1)
                .localTee(srcArr)
                .arrayLen()
                .localSet(sz);
        emitLoopPushFromAnyArr(c, isList, srcArr, sz, i, changed);
        c.localGet(changed).return_().end();

        // From primitive arrays
        emitPrimArrayAddAll(
                c,
                isList,
                ts.intArrayType,
                ts.i32ArrType,
                rt.fnBoxInt,
                false,
                false,
                sz,
                i,
                changed);
        emitPrimArrayAddAll(
                c,
                isList,
                ts.doubleArrayType,
                ts.f64ArrType,
                rt.fnBoxDouble,
                false,
                false,
                sz,
                i,
                changed);
        emitPrimArrayAddAll(
                c,
                isList,
                ts.longArrayType,
                ts.i64ArrType,
                rt.fnBoxLong,
                false,
                false,
                sz,
                i,
                changed);
        emitPrimArrayAddAll(
                c,
                isList,
                ts.floatArrayType,
                ts.f32ArrType,
                rt.fnBoxFloat,
                false,
                false,
                sz,
                i,
                changed);
        emitPrimArrayAddAll(
                c,
                isList,
                ts.byteArrayType,
                ts.i8ArrType,
                rt.fnBoxByte,
                true,
                false,
                sz,
                i,
                changed);
        emitPrimArrayAddAll(
                c,
                isList,
                ts.shortArrayType,
                ts.i16ArrType,
                rt.fnBoxShort,
                true,
                false,
                sz,
                i,
                changed);
        emitPrimArrayAddAll(
                c,
                isList,
                ts.boolArrayType,
                ts.i8ArrType,
                rt.fnBoxBool,
                false,
                true,
                sz,
                i,
                changed);
        emitPrimArrayAddAll(
                c,
                isList,
                ts.charArrayType,
                ts.charArrType,
                rt.fnBoxChar,
                false,
                true,
                sz,
                i,
                changed);

        // From Babble $Object implementing iterator()
        int itAny = fn.addLocal("itAny", ts.anyRef(), 3);
        int fnTmp = fn.addLocal("fnTmp", WasmValType.refNull(ts.fnBaseType), 3);
        int emptyArgs = fn.addLocal("eArgs", WasmValType.refNull(ts.anyArrType), 3);
        c.localGet(1).refTest(ts.objectType).if_();
        c.arrayNewFixed(ts.anyArrType, 0).localSet(emptyArgs);
        // Call src.iterator()
        c.localGet(1).refCast(ts.objectType);
        rt.emitStringLit(c, "iterator");
        c.localGet(1)
                .refCast(ts.objectType)
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 9)
                .refAsNonNull()
                .callRef(ts.getMemFnSigType)
                .refCast(ts.fnBaseType)
                .localTee(fnTmp)
                .refAsNonNull()
                .localGet(emptyArgs)
                .refAsNonNull()
                .refNull(ts.anyArrType)
                .localGet(fnTmp)
                .refAsNonNull()
                .structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType)
                .localSet(itAny);
        // If iterator is $CollectionIter
        c.localGet(itAny)
                .refTest(ts.collectionIterType)
                .if_()
                .loop()
                .localGet(itAny)
                .refCast(ts.collectionIterType)
                .call(col.fnIterHasNext)
                .if_()
                .localGet(0)
                .localGet(itAny)
                .refCast(ts.collectionIterType)
                .localGet(2)
                .call(col.fnIterNext);
        if (isList) {
            c.call(col.fnListAdd);
        } else {
            c.localGet(2).call(col.fnSetAdd);
        }
        c.localGet(changed)
                .op(WasmInstr.SimpleOp.I32_OR)
                .localSet(changed)
                .br(1)
                .end()
                .end()
                .localGet(changed)
                .return_()
                .end();
        // Else Babble iterator object with hasNext() / next()
        c.localGet(itAny)
                .refTest(ts.objectType)
                .if_()
                .loop()
                .localGet(itAny)
                .refCast(ts.objectType);
        rt.emitStringLit(c, "hasNext");
        c.localGet(itAny)
                .refCast(ts.objectType)
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 9)
                .refAsNonNull()
                .callRef(ts.getMemFnSigType)
                .refCast(ts.fnBaseType)
                .localTee(fnTmp)
                .refAsNonNull()
                .localGet(emptyArgs)
                .refAsNonNull()
                .refNull(ts.anyArrType)
                .localGet(fnTmp)
                .refAsNonNull()
                .structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType)
                .call(rt.fnTruth)
                .if_()
                .localGet(0)
                .localGet(itAny)
                .refCast(ts.objectType);
        rt.emitStringLit(c, "next");
        c.localGet(itAny)
                .refCast(ts.objectType)
                .structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 9)
                .refAsNonNull()
                .callRef(ts.getMemFnSigType)
                .refCast(ts.fnBaseType)
                .localTee(fnTmp)
                .refAsNonNull()
                .localGet(emptyArgs)
                .refAsNonNull()
                .refNull(ts.anyArrType)
                .localGet(fnTmp)
                .refAsNonNull()
                .structGet(ts.fnBaseType, 0)
                .callRef(ts.genericCallSigType);
        if (isList) {
            c.call(col.fnListAdd);
        } else {
            c.localGet(2).call(col.fnSetAdd);
        }
        c.localGet(changed).op(WasmInstr.SimpleOp.I32_OR).localSet(changed).br(1).end().end().end();
        c.end();
        c.localGet(changed);
    }

    void emitLoopPushFromAnyArr(
            WasmCodeBuilder c, boolean isList, int srcArr, int sz, int i, int changed) {
        c.i32Const(0).localSet(i);
        c.loop()
                .localGet(i)
                .localGet(sz)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(0)
                .localGet(srcArr)
                .refAsNonNull()
                .localGet(i)
                .arrayGet(ts.anyArrType);
        if (isList) {
            c.call(col.fnListAdd);
        } else {
            c.localGet(2).call(col.fnSetAdd);
        }
        c.localGet(changed)
                .op(WasmInstr.SimpleOp.I32_OR)
                .localSet(changed)
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end();
    }

    void emitPrimArrayAddAll(
            WasmCodeBuilder c,
            boolean isList,
            int wrapType,
            int dataArrType,
            int boxFn,
            boolean signed,
            boolean unsigned,
            int sz,
            int i,
            int changed) {
        c.localGet(1)
                .refTest(wrapType)
                .if_()
                .localGet(1)
                .refCast(wrapType)
                .structGet(wrapType, 1)
                .arrayLen()
                .localSet(sz)
                .i32Const(0)
                .localSet(i)
                .loop()
                .localGet(i)
                .localGet(sz)
                .op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(0)
                .localGet(1)
                .refCast(wrapType)
                .structGet(wrapType, 1)
                .localGet(i);
        if (signed) c.arrayGetS(dataArrType);
        else if (unsigned) c.arrayGetU(dataArrType);
        else c.arrayGet(dataArrType);
        c.call(boxFn);
        if (isList) c.call(col.fnListAdd);
        else c.localGet(2).call(col.fnSetAdd);
        c.localGet(changed)
                .op(WasmInstr.SimpleOp.I32_OR)
                .localSet(changed)
                .localGet(i)
                .i32Const(1)
                .op(WasmInstr.SimpleOp.I32_ADD)
                .localSet(i)
                .br(1)
                .end()
                .end()
                .localGet(changed)
                .return_()
                .end();
    }
}
