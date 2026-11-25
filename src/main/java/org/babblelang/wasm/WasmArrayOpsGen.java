package org.babblelang.wasm;

import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.PrimitiveKind;
import org.babblelang.ir.hir.HirArrayElementType;
import org.babblelang.ir.hir.HirExpr;

import java.util.List;

/**
 * Generates WebAssembly 3.0 WasmGC runtime helpers and HIR lowering for {@code BIP020}
 * {@code array of T} (all 8 unboxed primitive array wrappers + covariant {@code $RefArray}
 * with {@code $TypeDesc}, deep equality, deep hashCode, and {@code [a, b]} formatting)
 * ({@code BIP025-P2}, {@code BIP025-D3}).
 */
public final class WasmArrayOpsGen {
    private final WasmModule mod;
    private final WasmTypeSystem ts;
    private final WasmRuntimeGen rt;
    private final WasmRuntimeOpsGen ops;

    public final int fnArraySize;
    public final int fnArrayGetBoxed;
    public final int fnArraySetBoxed;
    public final int fnArrayDeepEquals;
    public final int fnArrayDeepHash;
    public final int fnArrayFormat;
    public final int fnCheckBounds;
    public final int fnCheckNegSize;
    public final int fnCheckRefStore;
    public final int fnTdSubtype;

    // Primitive TypeDesc singletons (initialized in init_array_types)
    public final int gTdByte;
    public final int gTdShort;
    public final int gTdInt;
    public final int gTdLong;
    public final int gTdFloat;
    public final int gTdDouble;
    public final int gTdBool;
    public final int gTdChar;
    public final int gTdAny;
    public final int gTdString;

    private final WasmModule.FuncDef fnInitArrayTypesDef;
    public final int fnInitArrayTypes;

    public WasmArrayOpsGen(WasmModule mod, WasmTypeSystem ts, WasmRuntimeGen rt, WasmRuntimeOpsGen ops) {
        this.mod = mod;
        this.ts = ts;
        this.rt = rt;
        this.ops = ops;

        WasmValType refTdNull = WasmValType.refNull(ts.typeDescType);
        this.gTdByte = mod.addGlobal("TD_byte", refTdNull, true, ts.defaultConstExpr(refTdNull));
        this.gTdShort = mod.addGlobal("TD_short", refTdNull, true, ts.defaultConstExpr(refTdNull));
        this.gTdInt = mod.addGlobal("TD_int", refTdNull, true, ts.defaultConstExpr(refTdNull));
        this.gTdLong = mod.addGlobal("TD_long", refTdNull, true, ts.defaultConstExpr(refTdNull));
        this.gTdFloat = mod.addGlobal("TD_float", refTdNull, true, ts.defaultConstExpr(refTdNull));
        this.gTdDouble = mod.addGlobal("TD_double", refTdNull, true, ts.defaultConstExpr(refTdNull));
        this.gTdBool = mod.addGlobal("TD_bool", refTdNull, true, ts.defaultConstExpr(refTdNull));
        this.gTdChar = mod.addGlobal("TD_char", refTdNull, true, ts.defaultConstExpr(refTdNull));
        this.gTdAny = mod.addGlobal("TD_any", refTdNull, true, ts.defaultConstExpr(refTdNull));
        this.gTdString = mod.addGlobal("TD_string", refTdNull, true, ts.defaultConstExpr(refTdNull));

        this.fnInitArrayTypesDef = mod.addFunction("init_array_types", ts.internFuncType(List.of(), List.of()));
        this.fnInitArrayTypes = fnInitArrayTypesDef.funcIndex();
        emitInitArrayTypes();

        WasmValType refArrBase = WasmValType.ref(ts.arrayBaseType);
        WasmValType refAnyNull = ts.anyRef();
        WasmValType refStr = WasmValType.ref(ts.stringType);
        WasmValType refTd = WasmValType.ref(ts.typeDescType);

        WasmModule.FuncDef dNeg = mod.addFunction("arr_check_neg",
                ts.internFuncType(List.of(WasmValType.I32, WasmValType.I32), List.of(WasmValType.I32)), List.of("n", "line"));
        WasmModule.FuncDef dBounds = mod.addFunction("arr_check_bounds",
                ts.internFuncType(List.of(WasmValType.I32, WasmValType.I32, WasmValType.I32), List.of()), List.of("idx", "len", "line"));
        WasmModule.FuncDef dTdSub = mod.addFunction("arr_td_subtype",
                ts.internFuncType(List.of(refTd, refTd), List.of(WasmValType.I32)), List.of("actual", "expected"));
        WasmModule.FuncDef dCheckStore = mod.addFunction("arr_check_ref_store",
                ts.internFuncType(List.of(WasmValType.ref(ts.refArrayType), refAnyNull, WasmValType.I32), List.of()),
                List.of("arr", "val", "line"));
        WasmModule.FuncDef dSize = mod.addFunction("arr_size",
                ts.internFuncType(List.of(refArrBase), List.of(WasmValType.I32)), List.of("a"));
        WasmModule.FuncDef dGet = mod.addFunction("arr_get_boxed",
                ts.internFuncType(List.of(refArrBase, WasmValType.I32, WasmValType.I32), List.of(refAnyNull)), List.of("a", "i", "line"));
        WasmModule.FuncDef dSet = mod.addFunction("arr_set_boxed",
                ts.internFuncType(List.of(refArrBase, WasmValType.I32, refAnyNull, WasmValType.I32), List.of(refAnyNull)), List.of("a", "i", "v", "line"));
        WasmModule.FuncDef dEq = mod.addFunction("arr_deep_equals",
                ts.internFuncType(List.of(refArrBase, refArrBase), List.of(WasmValType.I32)), List.of("a", "b"));
        WasmModule.FuncDef dHash = mod.addFunction("arr_deep_hash",
                ts.internFuncType(List.of(refArrBase), List.of(WasmValType.I32)), List.of("a"));
        WasmModule.FuncDef dFmt = mod.addFunction("arr_format",
                ts.internFuncType(List.of(refArrBase), List.of(refStr)), List.of("a"));

        this.fnCheckNegSize = dNeg.funcIndex();
        this.fnCheckBounds = dBounds.funcIndex();
        this.fnTdSubtype = dTdSub.funcIndex();
        this.fnCheckRefStore = dCheckStore.funcIndex();
        this.fnArraySize = dSize.funcIndex();
        this.fnArrayGetBoxed = dGet.funcIndex();
        this.fnArraySetBoxed = dSet.funcIndex();
        this.fnArrayDeepEquals = dEq.funcIndex();
        this.fnArrayDeepHash = dHash.funcIndex();
        this.fnArrayFormat = dFmt.funcIndex();

        emitCheckNeg(dNeg);
        emitCheckBounds(dBounds);
        emitTdSubtype(dTdSub);
        emitCheckRefStore(dCheckStore);
        emitArraySize(dSize);
        emitArrayGetBoxed(dGet);
        emitArraySetBoxed(dSet);
        emitArrayDeepEquals(dEq);
        emitArrayDeepHash(dHash);
        emitArrayFormat(dFmt);
    }

    private void emitInitArrayTypes() {
        WasmCodeBuilder c = fnInitArrayTypesDef.code();
        initTd(c, gTdByte, 1, 0, "array of byte");
        initTd(c, gTdShort, 1, 1, "array of short");
        initTd(c, gTdInt, 1, 2, "array of int");
        initTd(c, gTdLong, 1, 3, "array of long");
        initTd(c, gTdFloat, 1, 4, "array of float");
        initTd(c, gTdDouble, 1, 5, "array of double");
        initTd(c, gTdBool, 1, 6, "array of bool");
        initTd(c, gTdChar, 1, 7, "array of char");
        initTd(c, gTdAny, 0, 0, "array of any");
        initTd(c, gTdString, 2, 8, "array of string");
    }

    private void initTd(WasmCodeBuilder c, int globalIdx, int kind, int id, String name) {
        c.i32Const(kind).i32Const(id).refNull(ts.typeDescType);
        rt.emitStringLit(c, name);
        c.structNew(ts.typeDescType).globalSet(globalIdx);
    }

    public int wrapperTypeFor(PrimitiveKind k) {
        if (k == null || !k.isPrimitive()) {
            return ts.refArrayType;
        }
        return switch (k) {
            case BYTE -> ts.byteArrayType;
            case SHORT -> ts.shortArrayType;
            case INT -> ts.intArrayType;
            case LONG -> ts.longArrayType;
            case FLOAT -> ts.floatArrayType;
            case DOUBLE -> ts.doubleArrayType;
            case BOOL -> ts.boolArrayType;
            case CHAR -> ts.charArrayType;
            case NONE -> ts.refArrayType;
        };
    }

    public int backingArrTypeFor(PrimitiveKind k) {
        if (k == null || !k.isPrimitive()) {
            return ts.anyArrType;
        }
        return switch (k) {
            case BYTE, BOOL -> ts.i8ArrType;
            case SHORT -> ts.i16ArrType;
            case INT -> ts.i32ArrType;
            case LONG -> ts.i64ArrType;
            case FLOAT -> ts.f32ArrType;
            case DOUBLE -> ts.f64ArrType;
            case CHAR -> ts.charArrType;
            case NONE -> ts.anyArrType;
        };
    }

    public int primTypeDescGlobal(PrimitiveKind k) {
        if (k == null || !k.isPrimitive()) {
            return gTdAny;
        }
        return switch (k) {
            case BYTE -> gTdByte;
            case SHORT -> gTdShort;
            case INT -> gTdInt;
            case LONG -> gTdLong;
            case FLOAT -> gTdFloat;
            case DOUBLE -> gTdDouble;
            case BOOL -> gTdBool;
            case CHAR -> gTdChar;
            case NONE -> gTdAny;
        };
    }

    // ---------------------------------------------------------------- runtime helpers

    private void emitCheckNeg(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S).if_();
        rt.emitStringLit(c, "java.lang.NegativeArraySizeException: ");
        c.localGet(0).call(rt.fnIntToStr).call(rt.fnStrConcat)
                .localGet(1).call(rt.fnThrowMsg).unreachable().end();
        c.localGet(0);
    }

    private void emitCheckBounds(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        c.localGet(0).i32Const(0).op(WasmInstr.SimpleOp.I32_LT_S)
                .localGet(0).localGet(1).op(WasmInstr.SimpleOp.I32_GE_S)
                .op(WasmInstr.SimpleOp.I32_OR).if_();
        rt.emitStringLit(c, "java.lang.ArrayIndexOutOfBoundsException: Index ");
        c.localGet(0).call(rt.fnIntToStr).call(rt.fnStrConcat);
        rt.emitStringLit(c, " out of bounds for length ");
        c.call(rt.fnStrConcat).localGet(1).call(rt.fnIntToStr).call(rt.fnStrConcat)
                .localGet(2).call(rt.fnThrowMsg).unreachable().end();
    }

    private void emitTdSubtype(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        // params: 0=actual ($TypeDesc), 1=expected ($TypeDesc) -> i32
        int ak = fn.addLocal("ak", WasmValType.I32, 2);
        int ek = fn.addLocal("ek", WasmValType.I32, 2);
        c.localGet(1).structGet(ts.typeDescType, 0).localTee(ek)
                .i32Const(0).op(WasmInstr.SimpleOp.I32_EQ).if_().i32Const(1).return_().end();
        c.localGet(0).structGet(ts.typeDescType, 0).localSet(ak);
        // Both nested arrays (kind == 5): recurse on elem TypeDesc
        c.localGet(ak).i32Const(5).op(WasmInstr.SimpleOp.I32_EQ)
                .localGet(ek).i32Const(5).op(WasmInstr.SimpleOp.I32_EQ).op(WasmInstr.SimpleOp.I32_AND).if_()
                .localGet(0).structGet(ts.typeDescType, 2).refAsNonNull()
                .localGet(1).structGet(ts.typeDescType, 2).refAsNonNull()
                .call(fnTdSubtype).return_().end();
        // Same kind and same id
        c.localGet(ak).localGet(ek).op(WasmInstr.SimpleOp.I32_EQ)
                .localGet(0).structGet(ts.typeDescType, 1)
                .localGet(1).structGet(ts.typeDescType, 1).op(WasmInstr.SimpleOp.I32_EQ)
                .op(WasmInstr.SimpleOp.I32_AND);
    }

    private void emitCheckRefStore(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        // params: 0=arr ($RefArray), 1=val (any?), 2=line (i32)
        int td = fn.addLocal("td", WasmValType.refNull(ts.typeDescType), 3);
        int kind = fn.addLocal("kind", WasmValType.I32, 3);
        int id = fn.addLocal("id", WasmValType.I32, 3);
        int ok = fn.addLocal("ok", WasmValType.I32, 3);
        c.localGet(1).refIsNull().if_().return_().end();
        c.localGet(0).structGet(ts.arrayBaseType, 0).localTee(td).refAsNonNull()
                .structGet(ts.typeDescType, 0).localTee(kind)
                .i32Const(0).op(WasmInstr.SimpleOp.I32_EQ).if_().return_().end();
        c.localGet(td).refAsNonNull().structGet(ts.typeDescType, 1).localSet(id);
        c.i32Const(1).localSet(ok);
        // kind == 2: string
        c.localGet(kind).i32Const(2).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(1).refTest(ts.stringType).localSet(ok)
                .end();
        // kind == 3: Babble class (exact classId)
        c.localGet(kind).i32Const(3).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(1).refTest(ts.objectType).if_(WasmValType.I32)
                .localGet(1).refCast(ts.objectType).structGet(ts.objectType, 0)
                .structGet(ts.vtableType, 0).localGet(id).op(WasmInstr.SimpleOp.I32_EQ)
                .else_().i32Const(0).end().localSet(ok)
                .end();
        // kind == 4: Babble interface (bitset)
        c.localGet(kind).i32Const(4).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(1).refTest(ts.objectType).if_(WasmValType.I32)
                .localGet(id).i32Const(64).op(WasmInstr.SimpleOp.I32_LT_S).if_(WasmValType.I32)
                .localGet(1).refCast(ts.objectType).structGet(ts.objectType, 0).structGet(ts.vtableType, 2)
                .i64Const(1L).localGet(id).op(WasmInstr.SimpleOp.I64_EXTEND_I32_U).op(WasmInstr.SimpleOp.I64_SHL)
                .op(WasmInstr.SimpleOp.I64_AND).i64Const(0L).op(WasmInstr.SimpleOp.I64_NE)
                .else_()
                .localGet(1).refCast(ts.objectType).structGet(ts.objectType, 0).structGet(ts.vtableType, 3).refAsNonNull()
                .localGet(id).i32Const(64).op(WasmInstr.SimpleOp.I32_DIV_U).arrayGet(ts.i64ConstArrType)
                .i64Const(1L).localGet(id).i32Const(63).op(WasmInstr.SimpleOp.I32_AND)
                .op(WasmInstr.SimpleOp.I64_EXTEND_I32_U).op(WasmInstr.SimpleOp.I64_SHL)
                .op(WasmInstr.SimpleOp.I64_AND).i64Const(0L).op(WasmInstr.SimpleOp.I64_NE)
                .end()
                .else_().i32Const(0).end().localSet(ok)
                .end();
        // kind == 5: nested array (covariant TypeDesc subtype check)
        c.localGet(kind).i32Const(5).op(WasmInstr.SimpleOp.I32_EQ).if_()
                .localGet(1).refTest(ts.arrayBaseType).if_(WasmValType.I32)
                .localGet(1).refCast(ts.arrayBaseType).structGet(ts.arrayBaseType, 0)
                .localGet(td).refAsNonNull().structGet(ts.typeDescType, 2).refAsNonNull()
                .call(fnTdSubtype)
                .else_().i32Const(0).end().localSet(ok)
                .end();
        c.localGet(ok).op(WasmInstr.SimpleOp.I32_EQZ).if_();
        rt.emitStringLit(c, "java.lang.ArrayStoreException: ");
        c.localGet(1).call(ops.fnDescribeType).call(rt.fnStrConcat)
                .localGet(2).call(rt.fnThrowMsg).unreachable().end();
    }

    private void emitArraySize(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        PrimitiveKind[] kinds = {
                PrimitiveKind.INT, PrimitiveKind.DOUBLE, PrimitiveKind.NONE, PrimitiveKind.LONG,
                PrimitiveKind.FLOAT, PrimitiveKind.BOOL, PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.CHAR
        };
        for (PrimitiveKind k : kinds) {
            int wt = wrapperTypeFor(k);
            c.localGet(0).refTest(wt).if_()
                    .localGet(0).refCast(wt).structGet(wt, 1).arrayLen().return_()
                    .end();
        }
        c.i32Const(0);
    }

    private void emitArrayGetBoxed(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int len = fn.addLocal("len", WasmValType.I32, 3);
        c.localGet(0).call(fnArraySize).localSet(len);
        c.localGet(1).localGet(len).localGet(2).call(fnCheckBounds);

        c.localGet(0).refTest(ts.refArrayType).if_()
                .localGet(0).refCast(ts.refArrayType).structGet(ts.refArrayType, 1)
                .localGet(1).arrayGet(ts.anyArrType).return_().end();
        c.localGet(0).refTest(ts.intArrayType).if_()
                .localGet(0).refCast(ts.intArrayType).structGet(ts.intArrayType, 1)
                .localGet(1).arrayGet(ts.i32ArrType).call(rt.fnBoxInt).return_().end();
        c.localGet(0).refTest(ts.doubleArrayType).if_()
                .localGet(0).refCast(ts.doubleArrayType).structGet(ts.doubleArrayType, 1)
                .localGet(1).arrayGet(ts.f64ArrType).call(rt.fnBoxDouble).return_().end();
        c.localGet(0).refTest(ts.longArrayType).if_()
                .localGet(0).refCast(ts.longArrayType).structGet(ts.longArrayType, 1)
                .localGet(1).arrayGet(ts.i64ArrType).call(rt.fnBoxLong).return_().end();
        c.localGet(0).refTest(ts.floatArrayType).if_()
                .localGet(0).refCast(ts.floatArrayType).structGet(ts.floatArrayType, 1)
                .localGet(1).arrayGet(ts.f32ArrType).call(rt.fnBoxFloat).return_().end();
        c.localGet(0).refTest(ts.boolArrayType).if_()
                .localGet(0).refCast(ts.boolArrayType).structGet(ts.boolArrayType, 1)
                .localGet(1).arrayGetU(ts.i8ArrType).call(rt.fnBoxBool).return_().end();
        c.localGet(0).refTest(ts.byteArrayType).if_()
                .localGet(0).refCast(ts.byteArrayType).structGet(ts.byteArrayType, 1)
                .localGet(1).arrayGetS(ts.i8ArrType).call(rt.fnBoxByte).return_().end();
        c.localGet(0).refTest(ts.shortArrayType).if_()
                .localGet(0).refCast(ts.shortArrayType).structGet(ts.shortArrayType, 1)
                .localGet(1).arrayGetS(ts.i16ArrType).call(rt.fnBoxShort).return_().end();
        c.localGet(0).refTest(ts.charArrayType).if_()
                .localGet(0).refCast(ts.charArrayType).structGet(ts.charArrayType, 1)
                .localGet(1).arrayGetU(ts.charArrType).call(rt.fnBoxChar).return_().end();
        c.refNull(ts.anyType);
    }

    private void emitArraySetBoxed(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int len = fn.addLocal("len", WasmValType.I32, 4);
        c.localGet(0).call(fnArraySize).localSet(len);
        c.localGet(1).localGet(len).localGet(3).call(fnCheckBounds);

        c.localGet(0).refTest(ts.refArrayType).if_()
                .localGet(0).refCast(ts.refArrayType).localGet(2).localGet(3).call(fnCheckRefStore)
                .localGet(0).refCast(ts.refArrayType).structGet(ts.refArrayType, 1)
                .localGet(1).localGet(2).arraySet(ts.anyArrType)
                .localGet(2).return_().end();
        c.localGet(0).refTest(ts.intArrayType).if_()
                .localGet(0).refCast(ts.intArrayType).structGet(ts.intArrayType, 1)
                .localGet(1).localGet(2).localGet(3).call(rt.fnUnboxInt).arraySet(ts.i32ArrType)
                .localGet(2).return_().end();
        c.localGet(0).refTest(ts.doubleArrayType).if_()
                .localGet(0).refCast(ts.doubleArrayType).structGet(ts.doubleArrayType, 1)
                .localGet(1).localGet(2).localGet(3).call(rt.fnUnboxDouble).arraySet(ts.f64ArrType)
                .localGet(2).return_().end();
        c.localGet(0).refTest(ts.longArrayType).if_()
                .localGet(0).refCast(ts.longArrayType).structGet(ts.longArrayType, 1)
                .localGet(1).localGet(2).localGet(3).call(rt.fnUnboxLong).arraySet(ts.i64ArrType)
                .localGet(2).return_().end();
        c.localGet(0).refTest(ts.floatArrayType).if_()
                .localGet(0).refCast(ts.floatArrayType).structGet(ts.floatArrayType, 1)
                .localGet(1).localGet(2).localGet(3).call(rt.fnUnboxFloat).arraySet(ts.f32ArrType)
                .localGet(2).return_().end();
        c.localGet(0).refTest(ts.boolArrayType).if_()
                .localGet(0).refCast(ts.boolArrayType).structGet(ts.boolArrayType, 1)
                .localGet(1).localGet(2).localGet(3).call(rt.fnUnboxBool).arraySet(ts.i8ArrType)
                .localGet(2).return_().end();
        c.localGet(0).refTest(ts.byteArrayType).if_()
                .localGet(0).refCast(ts.byteArrayType).structGet(ts.byteArrayType, 1)
                .localGet(1).localGet(2).localGet(3).call(rt.fnUnboxByte).arraySet(ts.i8ArrType)
                .localGet(2).return_().end();
        c.localGet(0).refTest(ts.shortArrayType).if_()
                .localGet(0).refCast(ts.shortArrayType).structGet(ts.shortArrayType, 1)
                .localGet(1).localGet(2).localGet(3).call(rt.fnUnboxShort).arraySet(ts.i16ArrType)
                .localGet(2).return_().end();
        c.localGet(0).refTest(ts.charArrayType).if_()
                .localGet(0).refCast(ts.charArrayType).structGet(ts.charArrayType, 1)
                .localGet(1).localGet(2).localGet(3).call(rt.fnUnboxChar).arraySet(ts.charArrType)
                .localGet(2).return_().end();
        c.localGet(2);
    }

    private void emitArrayDeepEquals(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int len = fn.addLocal("len", WasmValType.I32, 2);
        int i = fn.addLocal("i", WasmValType.I32, 2);
        c.localGet(0).localGet(1).refEq().if_().i32Const(1).return_().end();
        c.localGet(0).call(fnArraySize).localTee(len)
                .localGet(1).call(fnArraySize).op(WasmInstr.SimpleOp.I32_NE)
                .if_().i32Const(0).return_().end();
        c.loop()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(0).localGet(i).i32Const(0).call(fnArrayGetBoxed)
                .localGet(1).localGet(i).i32Const(0).call(fnArrayGetBoxed)
                .call(rt.fnEquals).op(WasmInstr.SimpleOp.I32_EQZ)
                .if_().i32Const(0).return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1)
                .end()
                .end();
        c.i32Const(1);
    }

    private void emitArrayDeepHash(WasmModule.FuncDef fn) {
        WasmCodeBuilder c = fn.code();
        int len = fn.addLocal("len", WasmValType.I32, 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        int h = fn.addLocal("h", WasmValType.I32, 1);
        c.i32Const(1).localSet(h);
        c.localGet(0).call(fnArraySize).localSet(len);
        c.loop()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(h).i32Const(31).op(WasmInstr.SimpleOp.I32_MUL)
                .localGet(0).localGet(i).i32Const(0).call(fnArrayGetBoxed).call(rt.fnHashCode)
                .op(WasmInstr.SimpleOp.I32_ADD).localSet(h)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1)
                .end()
                .end();
        c.localGet(h);
    }

    private void emitArrayFormat(WasmModule.FuncDef fn) {
        int gStack = mod.addGlobal("G_ARR_FMT_STACK", WasmValType.refNull(ts.anyArrType), true,
                ts.defaultConstExpr(WasmValType.refNull(ts.anyArrType)));
        int gDepth = mod.addGlobal("G_ARR_FMT_DEPTH", WasmValType.I32, true, List.of(new WasmInstr.I32Const(0)));

        WasmCodeBuilder c = fn.code();
        int len = fn.addLocal("len", WasmValType.I32, 1);
        int i = fn.addLocal("i", WasmValType.I32, 1);
        int acc = fn.addLocal("acc", WasmValType.refNull(ts.stringType), 1);
        int depth = fn.addLocal("depth", WasmValType.I32, 1);
        int stack = fn.addLocal("stack", WasmValType.refNull(ts.anyArrType), 1);

        // Lazy-init visited stack (capacity 64)
        c.globalGet(gStack).refIsNull().if_()
                .i32Const(64).arrayNewDefault(ts.anyArrType).globalSet(gStack)
                .end();
        c.globalGet(gStack).localSet(stack);
        c.globalGet(gDepth).localSet(depth);

        // Cycle check: if self is already in stack[0..depth), return "[...]"
        c.i32Const(0).localSet(i);
        c.block().loop()
                .localGet(i).localGet(depth).op(WasmInstr.SimpleOp.I32_GE_S).brIf(1)
                .localGet(stack).refAsNonNull().localGet(i).arrayGet(ts.anyArrType)
                .localGet(0).refEq().if_();
        rt.emitStringLit(c, "[...]");
        c.return_().end()
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(0).end().end();

        // Push self onto visited stack
        c.localGet(depth).i32Const(64).op(WasmInstr.SimpleOp.I32_LT_S).if_()
                .localGet(stack).refAsNonNull().localGet(depth).localGet(0).arraySet(ts.anyArrType)
                .localGet(depth).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).globalSet(gDepth)
                .end();

        rt.emitStringLit(c, "[");
        c.localSet(acc);
        c.localGet(0).call(fnArraySize).localSet(len);
        c.i32Const(0).localSet(i);
        c.loop()
                .localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S)
                .if_()
                .localGet(i).i32Const(0).op(WasmInstr.SimpleOp.I32_GT_S).if_()
                .localGet(acc).refAsNonNull();
        rt.emitStringLit(c, ", ");
        c.call(rt.fnStrConcat).localSet(acc)
                .end()
                .localGet(acc).refAsNonNull()
                .localGet(0).localGet(i).i32Const(0).call(fnArrayGetBoxed).call(rt.fnFormat)
                .call(rt.fnStrConcat).localSet(acc)
                .localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(1)
                .end()
                .end();

        // Pop visited stack
        c.localGet(depth).globalSet(gDepth);

        c.localGet(acc).refAsNonNull();
        rt.emitStringLit(c, "]");
        c.call(rt.fnStrConcat);
    }

    // ---------------------------------------------------------------- HIR emission: ArrayCreate & ArrayOp

    public void emitArrayCreate(WasmFuncEmitter fe, HirExpr.ArrayCreate ac) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int line = ac.meta().line();
        PrimitiveKind ek = ac.elemKind();
        int wrapType = wrapperTypeFor(ek);
        int backingType = backingArrTypeFor(ek);

        switch (ac.kind()) {
            case SIZE -> {
                emitTypeDesc(fe, c, ek, ac.elementType());
                fe.emitAs(ac.args().get(0), PrimitiveKind.INT);
                c.i32Const(line).call(fnCheckNegSize)
                        .arrayNewDefault(backingType)
                        .structNew(wrapType);
                ctx.stackKind = PrimitiveKind.NONE;
            }
            case OF -> {
                int n = ac.args().size();
                int[] elemLocals = new int[n];
                for (int i = 0; i < n; i++) {
                    fe.emitAs(ac.args().get(i), ek);
                    elemLocals[i] = ctx.newLocal("$ae" + i, ts.valTypeFor(ek));
                    c.localSet(elemLocals[i]);
                }
                emitTypeDesc(fe, c, ek, ac.elementType());
                for (int i = 0; i < n; i++) {
                    c.localGet(elemLocals[i]);
                }
                c.arrayNewFixed(backingType, n).structNew(wrapType);
                ctx.stackKind = PrimitiveKind.NONE;
            }
            case FROM -> {
                // Copy from $ArrayBase, $List, $Set, or any Babble Iterable
                fe.emitBoxed(ac.args().get(0));
                int src = ctx.newLocal("$fromSrc", ts.anyRef());
                int len = ctx.newLocal("$fromLen", WasmValType.I32);
                int dst = ctx.newLocal("$fromDst", WasmValType.refNull(backingType));
                int idx = ctx.newLocal("$fromI", WasmValType.I32);
                int srcArr = ctx.newLocal("$fromArr", WasmValType.refNull(ts.anyArrType));
                c.localSet(src);

                c.localGet(src).refTest(ts.arrayBaseType).if_();
                ctx.pushBlock("arr_from_arr");
                c.localGet(src).refCast(ts.arrayBaseType).call(fnArraySize).localTee(len)
                        .arrayNewDefault(backingType).localSet(dst);
                c.i32Const(0).localSet(idx);
                ctx.pushBlock("arr_from_loop");
                c.loop();
                c.localGet(idx).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S).if_();
                ctx.pushBlock("arr_from_if");
                c.localGet(dst).refAsNonNull().localGet(idx)
                        .localGet(src).refCast(ts.arrayBaseType).localGet(idx).i32Const(line).call(fnArrayGetBoxed);
                if (ek.isPrimitive()) {
                    c.i32Const(line).call(rt.unboxFuncFor(ek));
                }
                c.arraySet(backingType)
                        .localGet(idx).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(idx)
                        .br(1);
                ctx.popBlock();
                c.end();
                ctx.popBlock();
                c.end();
                c.else_();
                // Convert $List / $Set / Babble Iterable into (srcArr, len)
                c.localGet(src).refTest(ts.listType).if_()
                        .localGet(src).refCast(ts.listType).structGet(ts.listType, 0).localSet(srcArr)
                        .localGet(src).refCast(ts.listType).structGet(ts.listType, 1).localSet(len)
                        .else_()
                        .localGet(src).refTest(ts.setType).if_()
                        .localGet(src).refCast(ts.setType).structGet(ts.setType, 0).localSet(srcArr)
                        .localGet(src).refCast(ts.setType).structGet(ts.setType, 1).localSet(len)
                        .else_();
                // Materialize general iterable into a temporary $List
                int tmpList = ctx.newLocal("$fromTmpL", WasmValType.refNull(ts.listType));
                c.i32Const(4).call(fe.linker().collections().fnListNew).localTee(tmpList).refAsNonNull()
                        .localGet(src).i32Const(line).call(fe.linker().collections().fnListAddAll).drop()
                        .localGet(tmpList).refAsNonNull().structGet(ts.listType, 0).localSet(srcArr)
                        .localGet(tmpList).refAsNonNull().structGet(ts.listType, 1).localSet(len);
                c.end().end();
                c.localGet(len).arrayNewDefault(backingType).localSet(dst);
                c.i32Const(0).localSet(idx);
                ctx.pushBlock("arr_from_col_loop");
                c.loop();
                c.localGet(idx).localGet(len).op(WasmInstr.SimpleOp.I32_LT_S).if_();
                ctx.pushBlock("arr_from_col_if");
                c.localGet(dst).refAsNonNull().localGet(idx)
                        .localGet(srcArr).refAsNonNull().localGet(idx).arrayGet(ts.anyArrType);
                if (ek.isPrimitive()) {
                    c.i32Const(line).call(rt.unboxFuncFor(ek));
                }
                c.arraySet(backingType)
                        .localGet(idx).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(idx)
                        .br(1);
                ctx.popBlock();
                c.end();
                ctx.popBlock();
                c.end();
                ctx.popBlock();
                c.end(); // end if arrayBaseType

                emitTypeDesc(fe, c, ek, ac.elementType());
                c.localGet(dst).refAsNonNull().structNew(wrapType);
                ctx.stackKind = PrimitiveKind.NONE;
            }
        }
    }

    public void emitArrayOp(WasmFuncEmitter fe, HirExpr.ArrayOp op) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int line = op.meta().line();
        PrimitiveKind ek = op.elemKind();
        int wrapType = wrapperTypeFor(ek);
        int backingType = backingArrTypeFor(ek);

        switch (op.kind()) {
            case SIZE -> {
                fe.emitBoxed(op.receiver());
                c.refCast(wrapType).structGet(wrapType, 1).arrayLen();
                ctx.stackKind = PrimitiveKind.INT;
            }
            case GET -> {
                fe.emitBoxed(op.receiver());
                int arr = ctx.newLocal("$arr", WasmValType.refNull(backingType));
                int idx = ctx.newLocal("$idx", WasmValType.I32);
                c.refCast(wrapType).structGet(wrapType, 1).localSet(arr);
                fe.emitAs(op.args().get(0), PrimitiveKind.INT);
                c.localTee(idx).localGet(arr).refAsNonNull().arrayLen().i32Const(line).call(fnCheckBounds);
                c.localGet(arr).refAsNonNull().localGet(idx);
                emitBackingArrayGet(c, ek, backingType);
                ctx.stackKind = ek;
            }
            case SET -> {
                fe.emitBoxed(op.receiver());
                int wrap = ctx.newLocal("$awrap", WasmValType.refNull(wrapType));
                int arr = ctx.newLocal("$arr", WasmValType.refNull(backingType));
                int idx = ctx.newLocal("$idx", WasmValType.I32);
                int val = ctx.newLocal("$val", ts.valTypeFor(ek));
                c.refCast(wrapType).localTee(wrap).structGet(wrapType, 1).localSet(arr);
                fe.emitAs(op.args().get(0), PrimitiveKind.INT);
                c.localSet(idx);
                fe.emitAs(op.args().get(1), ek);
                c.localSet(val);
                c.localGet(idx).localGet(arr).refAsNonNull().arrayLen().i32Const(line).call(fnCheckBounds);
                if (ek == PrimitiveKind.NONE) {
                    c.localGet(wrap).refAsNonNull().localGet(val).i32Const(line).call(fnCheckRefStore);
                }
                c.localGet(arr).refAsNonNull().localGet(idx).localGet(val).arraySet(backingType);
                c.localGet(val);
                ctx.stackKind = ek;
            }
            case EQUALS -> {
                fe.emitBoxed(op.receiver());
                fe.emitBoxed(op.args().get(0));
                c.call(rt.fnEquals);
                ctx.stackKind = PrimitiveKind.BOOL;
            }
            case HASH_CODE -> {
                fe.emitBoxed(op.receiver());
                c.refCast(ts.arrayBaseType).call(fnArrayDeepHash);
                ctx.stackKind = PrimitiveKind.INT;
            }
            case TO_STRING -> {
                fe.emitBoxed(op.receiver());
                c.refCast(ts.arrayBaseType).call(fnArrayFormat);
                ctx.stackKind = PrimitiveKind.NONE;
            }
        }
    }

    private void emitBackingArrayGet(WasmCodeBuilder c, PrimitiveKind ek, int backingType) {
        if (ek == PrimitiveKind.BYTE || ek == PrimitiveKind.SHORT) {
            c.arrayGetS(backingType);
        } else if (ek == PrimitiveKind.BOOL || ek == PrimitiveKind.CHAR) {
            c.arrayGetU(backingType);
        } else {
            c.arrayGet(backingType);
        }
    }

    private void emitTypeDesc(WasmFuncEmitter fe, WasmCodeBuilder c, PrimitiveKind ek, HirArrayElementType elemType) {
        if (ek != null && ek.isPrimitive()) {
            c.globalGet(primTypeDescGlobal(ek)).refAsNonNull();
            return;
        }
        if (elemType == null || elemType instanceof HirArrayElementType.Any) {
            c.globalGet(gTdAny).refAsNonNull();
            return;
        }
        if (elemType instanceof HirArrayElementType.Primitive p) {
            c.globalGet(primTypeDescGlobal(p.kind())).refAsNonNull();
            return;
        }
        if (elemType instanceof HirArrayElementType.StringType) {
            c.globalGet(gTdString).refAsNonNull();
            return;
        }
        if (elemType instanceof HirArrayElementType.BabbleType bt) {
            WasmObjectSystem objects = fe.linker().objects();
            if (bt.isInterface()) {
                WasmObjectSystem.IfaceInfo ii = objects.ifaceByTypeId(bt.typeId());
                if (ii != null) {
                    c.i32Const(4).i32Const(ii.ifaceIdx).refNull(ts.typeDescType);
                    rt.emitStringLit(c, "array of " + ii.hir.shape().name());
                    c.structNew(ts.typeDescType);
                    return;
                }
            } else {
                WasmObjectSystem.ClassInfo ci = objects.classByTypeId(bt.typeId());
                if (ci != null) {
                    c.i32Const(3).i32Const(ci.classId).refNull(ts.typeDescType);
                    rt.emitStringLit(c, "array of " + ci.shape.name());
                    c.structNew(ts.typeDescType);
                    return;
                }
            }
            c.globalGet(gTdAny).refAsNonNull();
            return;
        }
        if (elemType instanceof HirArrayElementType.Array arr) {
            c.i32Const(5).i32Const(0);
            emitTypeDesc(fe, c, null, arr.element());
            rt.emitStringLit(c, "array of (" + formatArrayDisplayName(fe, arr.element()) + ")");
            c.structNew(ts.typeDescType);
            return;
        }
        c.globalGet(gTdAny).refAsNonNull();
    }

    private String formatArrayDisplayName(WasmFuncEmitter fe, HirArrayElementType elemType) {
        if (elemType instanceof HirArrayElementType.Primitive p) {
            return "array of " + p.kind().name().toLowerCase(java.util.Locale.ROOT);
        }
        if (elemType instanceof HirArrayElementType.StringType) {
            return "array of string";
        }
        if (elemType instanceof HirArrayElementType.BabbleType bt) {
            WasmObjectSystem objects = fe.linker().objects();
            if (bt.isInterface()) {
                WasmObjectSystem.IfaceInfo ii = objects.ifaceByTypeId(bt.typeId());
                if (ii != null) return "array of " + ii.hir.shape().name();
            } else {
                WasmObjectSystem.ClassInfo ci = objects.classByTypeId(bt.typeId());
                if (ci != null) return "array of " + ci.shape.name();
            }
            return "array of any";
        }
        if (elemType instanceof HirArrayElementType.Array arr) {
            return "array of (" + formatArrayDisplayName(fe, arr.element()) + ")";
        }
        return "array of any";
    }

    /** Allocates an array wrapper of {@code ek} and populates it from a {@code $List} local. */
    public void emitArrayFromList(WasmFuncEmitter fe, int listLocal, PrimitiveKind ek, int line) {
        WasmFuncEmitter.EmitCtx ctx = fe.ctx();
        WasmCodeBuilder c = ctx.code;
        int wrapType = wrapperTypeFor(ek);
        int backingType = backingArrTypeFor(ek);
        int len = ctx.newLocal("$afLen", WasmValType.I32);
        int i = ctx.newLocal("$afI", WasmValType.I32);
        int arrObj = ctx.newLocal("$afObj", WasmValType.ref(ts.arrayBaseType));
        c.localGet(listLocal).structGet(ts.listType, 1).localSet(len);
        emitTypeDesc(fe, c, ek, null);
        c.localGet(len).arrayNewDefault(backingType).structNew(wrapType).localSet(arrObj);
        c.i32Const(0).localSet(i);
        int brk = ctx.pushBlock("af_brk");
        c.block();
        int loop = ctx.pushBlock("af_loop");
        c.loop();
        c.localGet(i).localGet(len).op(WasmInstr.SimpleOp.I32_GE_S).brIf(ctx.relDepth(brk));
        c.localGet(arrObj).localGet(i)
                .localGet(listLocal).structGet(ts.listType, 0).localGet(i).arrayGet(ts.anyArrType)
                .i32Const(line).call(fnArraySetBoxed).drop();
        c.localGet(i).i32Const(1).op(WasmInstr.SimpleOp.I32_ADD).localSet(i)
                .br(ctx.relDepth(loop));
        ctx.popBlock();
        c.end();
        ctx.popBlock();
        c.end();
        c.localGet(arrObj);
    }
}
