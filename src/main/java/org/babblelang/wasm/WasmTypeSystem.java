package org.babblelang.wasm;

import org.babblelang.analysis.CompilationTarget;
import org.babblelang.analysis.PrimitiveKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core WebAssembly 3.0 WasmGC type hierarchy and type-interning registry for Babble ({@code BIP025-P1},
 * {@code BIP025-D4}, {@code BIP025-D5}, {@code BIP025-D7}, {@code BIP025-D13}).
 *
 * <p>Standalone primitive array types (notably {@code $CharArr = (array (mut i16))}) are declared
 * outside the recursive type group so WasmGC iso-recursive equivalence matches the
 * {@code wasm:js-string} builtin imports directly.
 */
public final class WasmTypeSystem {
    private final WasmModule module;
    private final CompilationTarget target;

    // ---- Standalone non-recursive array & cell types (outside rec group) ----
    public final int charArrType;
    public final int i8ArrType;
    public final int i16ArrType;
    public final int i32ArrType;
    public final int i64ArrType;
    public final int f32ArrType;
    public final int f64ArrType;
    public final int i64ConstArrType;

    public final int byteCellType;
    public final int shortCellType;
    public final int intCellType;
    public final int longCellType;
    public final int floatCellType;
    public final int doubleCellType;
    public final int boolCellType;
    public final int charCellType;

    // ---- Core mutually recursive GC & function-signature types ----
    public final int anyType;
    public final int anyArrType;
    public final int refCellType;
    public final int sentinelType;
    public final int boxByteType;
    public final int boxShortType;
    public final int boxIntType;
    public final int boxLongType;
    public final int boxFloatType;
    public final int boxDoubleType;
    public final int boxBoolType;
    public final int boxCharType;
    public final int stringType;
    public final int typeDescType;
    public final int arrayBaseType;
    public final int byteArrayType;
    public final int shortArrayType;
    public final int intArrayType;
    public final int longArrayType;
    public final int floatArrayType;
    public final int doubleArrayType;
    public final int boolArrayType;
    public final int charArrayType;
    public final int refArrayType;
    public final int babbleExnType;
    public final int genericCallSigType;
    public final int fnBaseType;
    public final int overloadSetType;
    public final int itableBaseType;
    public final int itableArrType;
    public final int eqFnSigType;
    public final int hashFnSigType;
    public final int toStrFnSigType;
    public final int getMemFnSigType;
    public final int setMemFnSigType;
    public final int opFnSigType;
    public final int vtableType;
    public final int objectType;
    public final int classValType;
    public final int ifaceValType;
    public final int boundMethodType;
    public final int moduleValType;
    public final int hostStreamType;
    public final int listType;
    public final int setType;
    public final int entryType;
    public final int mapType;
    public final int collectionIterType;

    // ---- Exception & multi-value control block helper types ----
    public final int exnTagType;
    public final int exnCatchPairType;
    public final int exnCatchRefTripleType;

    // ---- Interned function signatures ----
    private final Map<FuncSigKey, Integer> internedFuncTypes = new LinkedHashMap<>();

    private record FuncSigKey(List<WasmValType> params, List<WasmValType> results) {
    }

    public WasmTypeSystem(WasmModule module, CompilationTarget target) {
        this.module = module;
        this.target = target;

        // 1. Standalone primitive array types (critical: $CharArr must NOT be inside a multi-type rec group
        // so wasm:js-string fromCharCodeArray/intoCharCodeArray signatures match under iso-recursive rules).
        this.charArrType = module.addArrayType("CharArr", WasmValType.I16, true);
        this.i8ArrType = module.addArrayType("I8Arr", WasmValType.I8, true);
        this.i16ArrType = module.addArrayType("I16Arr", WasmValType.I16, true);
        this.i32ArrType = module.addArrayType("I32Arr", WasmValType.I32, true);
        this.i64ArrType = module.addArrayType("I64Arr", WasmValType.I64, true);
        this.f32ArrType = module.addArrayType("F32Arr", WasmValType.F32, true);
        this.f64ArrType = module.addArrayType("F64Arr", WasmValType.F64, true);
        this.i64ConstArrType = module.addArrayType("I64ConstArr", WasmValType.I64, false);

        // Primitive capture cells (mutable value + defined-flag structs for Name-used-before-definition checks)
        this.byteCellType = module.addStructType("ByteCell", true, -1, List.of(field("v", WasmValType.I32, true), field("defined", WasmValType.I32, true)));
        this.shortCellType = module.addStructType("ShortCell", true, -1, List.of(field("v", WasmValType.I32, true), field("defined", WasmValType.I32, true)));
        this.intCellType = module.addStructType("IntCell", true, -1, List.of(field("v", WasmValType.I32, true), field("defined", WasmValType.I32, true)));
        this.longCellType = module.addStructType("LongCell", true, -1, List.of(field("v", WasmValType.I64, true), field("defined", WasmValType.I32, true)));
        this.floatCellType = module.addStructType("FloatCell", true, -1, List.of(field("v", WasmValType.F32, true), field("defined", WasmValType.I32, true)));
        this.doubleCellType = module.addStructType("DoubleCell", true, -1, List.of(field("v", WasmValType.F64, true), field("defined", WasmValType.I32, true)));
        this.boolCellType = module.addStructType("BoolCell", true, -1, List.of(field("v", WasmValType.I32, true), field("defined", WasmValType.I32, true)));
        this.charCellType = module.addStructType("CharCell", true, -1, List.of(field("v", WasmValType.I32, true), field("defined", WasmValType.I32, true)));

        // 2. Reserve indices for the core mutually recursive type group
        this.anyType = module.reserveTypeIndex();
        this.anyArrType = module.reserveTypeIndex();
        this.refCellType = module.reserveTypeIndex();
        this.sentinelType = module.reserveTypeIndex();
        this.boxByteType = module.reserveTypeIndex();
        this.boxShortType = module.reserveTypeIndex();
        this.boxIntType = module.reserveTypeIndex();
        this.boxLongType = module.reserveTypeIndex();
        this.boxFloatType = module.reserveTypeIndex();
        this.boxDoubleType = module.reserveTypeIndex();
        this.boxBoolType = module.reserveTypeIndex();
        this.boxCharType = module.reserveTypeIndex();
        this.stringType = module.reserveTypeIndex();
        this.typeDescType = module.reserveTypeIndex();
        this.arrayBaseType = module.reserveTypeIndex();
        this.byteArrayType = module.reserveTypeIndex();
        this.shortArrayType = module.reserveTypeIndex();
        this.intArrayType = module.reserveTypeIndex();
        this.longArrayType = module.reserveTypeIndex();
        this.floatArrayType = module.reserveTypeIndex();
        this.doubleArrayType = module.reserveTypeIndex();
        this.boolArrayType = module.reserveTypeIndex();
        this.charArrayType = module.reserveTypeIndex();
        this.refArrayType = module.reserveTypeIndex();
        this.babbleExnType = module.reserveTypeIndex();
        this.genericCallSigType = module.reserveTypeIndex();
        this.fnBaseType = module.reserveTypeIndex();
        this.overloadSetType = module.reserveTypeIndex();
        this.itableBaseType = module.reserveTypeIndex();
        this.itableArrType = module.reserveTypeIndex();
        this.eqFnSigType = module.reserveTypeIndex();
        this.hashFnSigType = module.reserveTypeIndex();
        this.toStrFnSigType = module.reserveTypeIndex();
        this.getMemFnSigType = module.reserveTypeIndex();
        this.setMemFnSigType = module.reserveTypeIndex();
        this.opFnSigType = module.reserveTypeIndex();
        this.vtableType = module.reserveTypeIndex();
        this.objectType = module.reserveTypeIndex();
        this.classValType = module.reserveTypeIndex();
        this.ifaceValType = module.reserveTypeIndex();
        this.boundMethodType = module.reserveTypeIndex();
        this.moduleValType = module.reserveTypeIndex();
        this.hostStreamType = module.reserveTypeIndex();
        this.listType = module.reserveTypeIndex();
        this.setType = module.reserveTypeIndex();
        this.entryType = module.reserveTypeIndex();
        this.mapType = module.reserveTypeIndex();
        this.collectionIterType = module.reserveTypeIndex();

        WasmValType refAnyNull = WasmValType.refNull(anyType);
        WasmValType refAnyArr = WasmValType.ref(anyArrType);
        WasmValType refAnyArrNull = WasmValType.refNull(anyArrType);
        WasmValType refStr = WasmValType.ref(stringType);
        WasmValType refStrNull = WasmValType.refNull(stringType);
        WasmValType refTypeDesc = WasmValType.ref(typeDescType);
        WasmValType refTypeDescNull = WasmValType.refNull(typeDescType);
        WasmValType refFnBase = WasmValType.ref(fnBaseType);
        WasmValType refGenericSig = WasmValType.ref(genericCallSigType);
        WasmValType refObj = WasmValType.ref(objectType);
        WasmValType refVTable = WasmValType.ref(vtableType);

        List<WasmTypeDef.SubType> rec = new ArrayList<>();
        // $Any (open empty root)
        rec.add(new WasmTypeDef.SubType(anyType, "Any", false, -1, new WasmTypeDef.StructType(List.of())));
        // $AnyArr
        rec.add(new WasmTypeDef.SubType(anyArrType, "AnyArr", true, -1,
                new WasmTypeDef.ArrayType(field("elem", refAnyNull, true))));
        // $RefCell
        rec.add(new WasmTypeDef.SubType(refCellType, "RefCell", true, -1,
                new WasmTypeDef.StructType(List.of(field("v", refAnyNull, true)))));
        // $Sentinel (0 = UNDEFINED, 1 = MISSING)
        rec.add(new WasmTypeDef.SubType(sentinelType, "Sentinel", true, anyType,
                new WasmTypeDef.StructType(List.of(field("kind", WasmValType.I32, false)))));
        // Primitive boxes (BIP025-D5)
        rec.add(boxSub(boxByteType, "BoxByte", WasmValType.I32));
        rec.add(boxSub(boxShortType, "BoxShort", WasmValType.I32));
        rec.add(boxSub(boxIntType, "BoxInt", WasmValType.I32));
        rec.add(boxSub(boxLongType, "BoxLong", WasmValType.I64));
        rec.add(boxSub(boxFloatType, "BoxFloat", WasmValType.F32));
        rec.add(boxSub(boxDoubleType, "BoxDouble", WasmValType.F64));
        rec.add(boxSub(boxBoolType, "BoxBool", WasmValType.I32));
        rec.add(boxSub(boxCharType, "BoxChar", WasmValType.I32));
        // $String (BIP025-D4)
        rec.add(new WasmTypeDef.SubType(stringType, "String", true, anyType, new WasmTypeDef.StructType(List.of(
                field("chars", WasmValType.ref(charArrType), false),
                field("hash", WasmValType.I32, true),
                field("hasHash", WasmValType.I32, true)
        ))));
        // $TypeDesc (for array runtime element types & covariant store checks)
        rec.add(new WasmTypeDef.SubType(typeDescType, "TypeDesc", true, anyType, new WasmTypeDef.StructType(List.of(
                field("kind", WasmValType.I32, false),
                field("id", WasmValType.I32, false),
                field("elem", refTypeDescNull, false),
                field("name", refStr, false)
        ))));
        // $ArrayBase + typed array wrappers
        rec.add(new WasmTypeDef.SubType(arrayBaseType, "ArrayBase", false, anyType, new WasmTypeDef.StructType(List.of(
                field("typeDesc", refTypeDesc, false)
        ))));
        rec.add(arrayWrapSub(byteArrayType, "ByteArray", refTypeDesc, i8ArrType));
        rec.add(arrayWrapSub(shortArrayType, "ShortArray", refTypeDesc, i16ArrType));
        rec.add(arrayWrapSub(intArrayType, "IntArray", refTypeDesc, i32ArrType));
        rec.add(arrayWrapSub(longArrayType, "LongArray", refTypeDesc, i64ArrType));
        rec.add(arrayWrapSub(floatArrayType, "FloatArray", refTypeDesc, f32ArrType));
        rec.add(arrayWrapSub(doubleArrayType, "DoubleArray", refTypeDesc, f64ArrType));
        rec.add(arrayWrapSub(boolArrayType, "BoolArray", refTypeDesc, i8ArrType));
        rec.add(arrayWrapSub(charArrayType, "CharArray", refTypeDesc, charArrType));
        rec.add(arrayWrapSub(refArrayType, "RefArray", refTypeDesc, anyArrType));
        // $BabbleException (BIP025-D13)
        rec.add(new WasmTypeDef.SubType(babbleExnType, "BabbleException", true, anyType, new WasmTypeDef.StructType(List.of(
                field("message", refStrNull, false),
                field("cause", refAnyNull, false),
                field("suppressed", refAnyArrNull, true),
                field("suppressedCount", WasmValType.I32, true),
                field("line", WasmValType.I32, false)
        ))));
        // $GenericCallSig & $FnBase & $OverloadSet
        rec.add(new WasmTypeDef.SubType(genericCallSigType, "GenericCallSig", true, -1, new WasmTypeDef.FuncType(
                List.of(refFnBase, refAnyArr, refAnyArrNull), List.of(refAnyNull))));
        rec.add(new WasmTypeDef.SubType(fnBaseType, "FnBase", false, anyType, new WasmTypeDef.StructType(List.of(
                field("genericCall", refGenericSig, false),
                field("name", refStr, false),
                field("fnId", WasmValType.I32, false)
        ))));
        rec.add(new WasmTypeDef.SubType(overloadSetType, "OverloadSet", true, fnBaseType, new WasmTypeDef.StructType(List.of(
                field("genericCall", refGenericSig, false),
                field("name", refStr, false),
                field("fnId", WasmValType.I32, false),
                field("alts", refAnyArr, false),
                field("keys", refAnyArr, false)
        ))));
        // Interfaces, VTable, Object, ClassVal, ModuleVal, HostStream
        rec.add(new WasmTypeDef.SubType(itableBaseType, "ITableBase", false, -1, new WasmTypeDef.StructType(List.of())));
        rec.add(new WasmTypeDef.SubType(itableArrType, "ITableArr", true, -1,
                new WasmTypeDef.ArrayType(field("elem", WasmValType.refNull(itableBaseType), false))));
        rec.add(new WasmTypeDef.SubType(eqFnSigType, "EqFnSig", true, -1,
                new WasmTypeDef.FuncType(List.of(refObj, refAnyNull), List.of(WasmValType.I32))));
        rec.add(new WasmTypeDef.SubType(hashFnSigType, "HashFnSig", true, -1,
                new WasmTypeDef.FuncType(List.of(refObj), List.of(WasmValType.I32))));
        rec.add(new WasmTypeDef.SubType(toStrFnSigType, "ToStrFnSig", true, -1,
                new WasmTypeDef.FuncType(List.of(refObj), List.of(refStr))));
        rec.add(new WasmTypeDef.SubType(getMemFnSigType, "GetMemFnSig", true, -1,
                new WasmTypeDef.FuncType(List.of(refObj, refStr), List.of(refAnyNull))));
        rec.add(new WasmTypeDef.SubType(setMemFnSigType, "SetMemFnSig", true, -1,
                new WasmTypeDef.FuncType(List.of(refObj, refStr, refAnyNull), List.of(refAnyNull))));
        rec.add(new WasmTypeDef.SubType(opFnSigType, "OpFnSig", true, -1,
                new WasmTypeDef.FuncType(List.of(refObj, refStr, refAnyNull), List.of(refAnyNull))));
        rec.add(new WasmTypeDef.SubType(vtableType, "VTable", true, anyType, new WasmTypeDef.StructType(List.of(
                field("classId", WasmValType.I32, false),
                field("name", refStr, false),
                field("ifaceBits", WasmValType.I64, false),
                field("ifaceWords", WasmValType.refNull(i64ConstArrType), false),
                field("itables", WasmValType.refNull(itableArrType), false),
                field("isRecord", WasmValType.I32, false),
                field("equalsFn", WasmValType.refNull(eqFnSigType), false),
                field("hashFn", WasmValType.refNull(hashFnSigType), false),
                field("toStringFn", WasmValType.refNull(toStrFnSigType), false),
                field("getMemberFn", WasmValType.refNull(getMemFnSigType), false),
                field("setMemberFn", WasmValType.refNull(setMemFnSigType), false),
                field("opFn", WasmValType.refNull(opFnSigType), false),
                field("typeId", refStr, false),
                field("ifaceCaps", refAnyArrNull, false)
        ))));
        rec.add(new WasmTypeDef.SubType(objectType, "Object", false, anyType, new WasmTypeDef.StructType(List.of(
                field("vtable", refVTable, false)
        ))));
        rec.add(new WasmTypeDef.SubType(classValType, "ClassVal", false, fnBaseType, new WasmTypeDef.StructType(List.of(
                field("genericCall", refGenericSig, false),
                field("name", refStr, false),
                field("fnId", WasmValType.I32, false),
                field("classId", WasmValType.I32, false),
                field("vtable", refVTable, false),
                field("statics", refAnyArrNull, false)
        ))));
        rec.add(new WasmTypeDef.SubType(ifaceValType, "IfaceVal", true, anyType, new WasmTypeDef.StructType(List.of(
                field("ifaceIdx", WasmValType.I32, false),
                field("name", refStr, false),
                field("statics", refAnyArrNull, false),
                field("capEnv", refAnyNull, false)
        ))));
        rec.add(new WasmTypeDef.SubType(boundMethodType, "BoundMethod", true, fnBaseType, new WasmTypeDef.StructType(List.of(
                field("genericCall", refGenericSig, false),
                field("name", refStr, false),
                field("fnId", WasmValType.I32, false),
                field("recv", refObj, false)
        ))));
        rec.add(new WasmTypeDef.SubType(moduleValType, "ModuleVal", true, anyType, new WasmTypeDef.StructType(List.of(
                field("modId", WasmValType.I32, false),
                field("name", refStr, false),
                field("exports", refAnyArr, false),
                field("exportNames", refAnyArr, false)
        ))));
        rec.add(new WasmTypeDef.SubType(hostStreamType, "HostStream", true, anyType, new WasmTypeDef.StructType(List.of(
                field("kind", WasmValType.I32, false)
        ))));
        // Portable core collections (BIP025-P3, BIP025-D6)
        rec.add(new WasmTypeDef.SubType(listType, "List", true, anyType, new WasmTypeDef.StructType(List.of(
                field("arr", refAnyArr, true),
                field("size", WasmValType.I32, true),
                field("parent", WasmValType.refNull(listType), true),
                field("offset", WasmValType.I32, true)
        ))));
        rec.add(new WasmTypeDef.SubType(setType, "Set", true, anyType, new WasmTypeDef.StructType(List.of(
                field("arr", refAnyArr, true),
                field("size", WasmValType.I32, true),
                field("sorted", WasmValType.I32, false),
                field("parentMap", WasmValType.refNull(mapType), true)
        ))));
        rec.add(new WasmTypeDef.SubType(entryType, "Entry", true, anyType, new WasmTypeDef.StructType(List.of(
                field("key", refAnyNull, false),
                field("val", refAnyNull, true)
        ))));
        rec.add(new WasmTypeDef.SubType(mapType, "Map", true, anyType, new WasmTypeDef.StructType(List.of(
                field("keys", refAnyArr, true),
                field("vals", refAnyArr, true),
                field("size", WasmValType.I32, true),
                field("sorted", WasmValType.I32, false)
        ))));
        rec.add(new WasmTypeDef.SubType(collectionIterType, "CollectionIter", true, anyType, new WasmTypeDef.StructType(List.of(
                field("arr", refAnyArr, false),
                field("size", WasmValType.I32, false),
                field("pos", WasmValType.I32, true)
        ))));

        module.commitRecGroup(rec);

        // 3. Exception tag & multi-value catch block types
        WasmValType refAny = WasmValType.ref(anyType);
        this.exnTagType = internFuncType("ExnTagType", List.of(refAny, WasmValType.I32), List.of());
        this.exnCatchPairType = internFuncType("ExnCatchPair", List.of(), List.of(refAny, WasmValType.I32));
        this.exnCatchRefTripleType = internFuncType("ExnCatchRefTriple", List.of(),
                List.of(refAny, WasmValType.I32, WasmValType.NON_NULL_EXN));
    }

    private WasmTypeDef.SubType boxSub(int idx, String name, WasmValType valType) {
        return new WasmTypeDef.SubType(idx, name, true, anyType,
                new WasmTypeDef.StructType(List.of(field("v", valType, false))));
    }

    private WasmTypeDef.SubType arrayWrapSub(int idx, String name, WasmValType refTypeDesc, int arrTypeIdx) {
        return new WasmTypeDef.SubType(idx, name, true, arrayBaseType, new WasmTypeDef.StructType(List.of(
                field("typeDesc", refTypeDesc, false),
                field("data", WasmValType.ref(arrTypeIdx), false)
        )));
    }

    public static WasmTypeDef.Field field(String name, WasmValType type, boolean mutable) {
        return new WasmTypeDef.Field(name, type, mutable);
    }

    public WasmModule module() {
        return module;
    }

    public CompilationTarget target() {
        return target;
    }

    public WasmValType anyRef() {
        return WasmValType.refNull(anyType);
    }

    public WasmValType nonNullAnyRef() {
        return WasmValType.ref(anyType);
    }

    public WasmValType valTypeFor(PrimitiveKind kind) {
        if (kind == null || !kind.isPrimitive()) {
            return anyRef();
        }
        return switch (kind) {
            case BYTE, SHORT, INT, BOOL, CHAR -> WasmValType.I32;
            case LONG -> WasmValType.I64;
            case FLOAT -> WasmValType.F32;
            case DOUBLE -> WasmValType.F64;
            case NONE -> anyRef();
        };
    }

    public int boxTypeFor(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> boxByteType;
            case SHORT -> boxShortType;
            case INT -> boxIntType;
            case LONG -> boxLongType;
            case FLOAT -> boxFloatType;
            case DOUBLE -> boxDoubleType;
            case BOOL -> boxBoolType;
            case CHAR -> boxCharType;
            case NONE -> throw new IllegalArgumentException("No box type for NONE");
        };
    }

    public int cellTypeFor(PrimitiveKind kind) {
        if (kind == null || !kind.isPrimitive()) {
            return refCellType;
        }
        return switch (kind) {
            case BYTE -> byteCellType;
            case SHORT -> shortCellType;
            case INT -> intCellType;
            case LONG -> longCellType;
            case FLOAT -> floatCellType;
            case DOUBLE -> doubleCellType;
            case BOOL -> boolCellType;
            case CHAR -> charCellType;
            case NONE -> refCellType;
        };
    }

    /** Creates a concrete closure struct subtype of {@code $FnBase} with immutable captured cell fields. */
    public int addClosureType(String name, List<WasmValType> captureTypes) {
        List<WasmTypeDef.Field> fields = new ArrayList<>(3 + captureTypes.size());
        fields.add(field("genericCall", WasmValType.ref(genericCallSigType), false));
        fields.add(field("name", WasmValType.ref(stringType), false));
        fields.add(field("fnId", WasmValType.I32, false));
        for (int i = 0; i < captureTypes.size(); i++) {
            fields.add(field("cap" + i, captureTypes.get(i), false));
        }
        return module.addStructType(name, true, fnBaseType, fields);
    }

    public int internFuncType(List<WasmValType> params, List<WasmValType> results) {
        return internFuncType(null, params, results);
    }

    public int internFuncType(String debugName, List<WasmValType> params, List<WasmValType> results) {
        FuncSigKey key = new FuncSigKey(List.copyOf(params), List.copyOf(results));
        Integer existing = internedFuncTypes.get(key);
        if (existing != null) {
            return existing;
        }
        int idx = module.addFuncType(debugName, key.params(), key.results());
        internedFuncTypes.put(key, idx);
        return idx;
    }

    /** Emits a zero/null default constant expression for a Wasm value type (for global initializers). */
    public List<WasmInstr> defaultConstExpr(WasmValType type) {
        if (type == WasmValType.I32) {
            return List.of(new WasmInstr.I32Const(0));
        }
        if (type == WasmValType.I64) {
            return List.of(new WasmInstr.I64Const(0L));
        }
        if (type == WasmValType.F32) {
            return List.of(new WasmInstr.F32Const(0.0f));
        }
        if (type == WasmValType.F64) {
            return List.of(new WasmInstr.F64Const(0.0d));
        }
        if (type instanceof WasmValType.Ref r && r.nullable()) {
            return List.of(new WasmInstr.RefNull(r.heapType()));
        }
        throw new IllegalArgumentException("No default constant expression for non-null type : " + type.wat());
    }

    /** Emits a default value onto the stack inside a function body. */
    public void emitDefaultValue(WasmCodeBuilder code, WasmValType type) {
        if (type == WasmValType.I32) {
            code.i32Const(0);
        } else if (type == WasmValType.I64) {
            code.i64Const(0L);
        } else if (type == WasmValType.F32) {
            code.f32Const(0.0f);
        } else if (type == WasmValType.F64) {
            code.f64Const(0.0d);
        } else if (type instanceof WasmValType.Ref r && r.nullable()) {
            code.refNull(r.heapType());
        } else {
            throw new IllegalArgumentException("No default value for non-null type : " + type.wat());
        }
    }
}
