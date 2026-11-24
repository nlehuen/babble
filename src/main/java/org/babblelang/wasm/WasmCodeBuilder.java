package org.babblelang.wasm;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for WebAssembly 3.0 function bodies and constant expressions ({@code BIP025-P0}).
 */
public final class WasmCodeBuilder {
    private final List<WasmInstr> instructions = new ArrayList<>();

    public List<WasmInstr> build() {
        return List.copyOf(instructions);
    }

    public int size() {
        return instructions.size();
    }

    public WasmCodeBuilder add(WasmInstr instr) {
        instructions.add(instr);
        return this;
    }

    public WasmCodeBuilder op(WasmInstr.SimpleOp op) {
        return add(new WasmInstr.Simple(op));
    }

    // ---------------------------------------------------------------- control flow

    public WasmCodeBuilder unreachable() { return op(WasmInstr.SimpleOp.UNREACHABLE); }
    public WasmCodeBuilder nop() { return op(WasmInstr.SimpleOp.NOP); }
    public WasmCodeBuilder block() { return add(new WasmInstr.Block(WasmInstr.BlockKind.BLOCK, WasmInstr.BlockType.EMPTY)); }
    public WasmCodeBuilder block(WasmValType result) { return add(new WasmInstr.Block(WasmInstr.BlockKind.BLOCK, WasmInstr.BlockType.of(result))); }
    public WasmCodeBuilder blockType(int funcTypeIdx) { return add(new WasmInstr.Block(WasmInstr.BlockKind.BLOCK, new WasmInstr.BlockType.TypeIdx(funcTypeIdx))); }
    public WasmCodeBuilder loop() { return add(new WasmInstr.Block(WasmInstr.BlockKind.LOOP, WasmInstr.BlockType.EMPTY)); }
    public WasmCodeBuilder loop(WasmValType result) { return add(new WasmInstr.Block(WasmInstr.BlockKind.LOOP, WasmInstr.BlockType.of(result))); }
    public WasmCodeBuilder if_() { return add(new WasmInstr.Block(WasmInstr.BlockKind.IF, WasmInstr.BlockType.EMPTY)); }
    public WasmCodeBuilder if_(WasmValType result) { return add(new WasmInstr.Block(WasmInstr.BlockKind.IF, WasmInstr.BlockType.of(result))); }
    public WasmCodeBuilder else_() { return op(WasmInstr.SimpleOp.ELSE); }
    public WasmCodeBuilder end() { return op(WasmInstr.SimpleOp.END); }
    public WasmCodeBuilder br(int label) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.BR, label)); }
    public WasmCodeBuilder brIf(int label) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.BR_IF, label)); }
    public WasmCodeBuilder brTable(List<Integer> labels, int defaultLabel) { return add(new WasmInstr.BrTable(labels, defaultLabel)); }
    public WasmCodeBuilder return_() { return op(WasmInstr.SimpleOp.RETURN); }
    public WasmCodeBuilder call(int funcIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.CALL, funcIdx)); }
    public WasmCodeBuilder returnCall(int funcIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.RETURN_CALL, funcIdx)); }
    public WasmCodeBuilder callRef(int typeIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.CALL_REF, typeIdx)); }
    public WasmCodeBuilder returnCallRef(int typeIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.RETURN_CALL_REF, typeIdx)); }
    public WasmCodeBuilder throw_(int tagIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.THROW, tagIdx)); }
    public WasmCodeBuilder throwRef() { return op(WasmInstr.SimpleOp.THROW_REF); }

    public WasmCodeBuilder tryTable(WasmValType result, List<WasmInstr.CatchClause> catches) {
        return add(new WasmInstr.TryTable(WasmInstr.BlockType.of(result), catches));
    }

    public WasmCodeBuilder tryTable(List<WasmInstr.CatchClause> catches) {
        return add(new WasmInstr.TryTable(WasmInstr.BlockType.EMPTY, catches));
    }

    // ---------------------------------------------------------------- parametric, locals, globals

    public WasmCodeBuilder drop() { return op(WasmInstr.SimpleOp.DROP); }
    public WasmCodeBuilder select() { return op(WasmInstr.SimpleOp.SELECT); }
    public WasmCodeBuilder select(WasmValType type) { return add(new WasmInstr.SelectTyped(type)); }
    public WasmCodeBuilder localGet(int idx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.LOCAL_GET, idx)); }
    public WasmCodeBuilder localSet(int idx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.LOCAL_SET, idx)); }
    public WasmCodeBuilder localTee(int idx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.LOCAL_TEE, idx)); }
    public WasmCodeBuilder globalGet(int idx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.GLOBAL_GET, idx)); }
    public WasmCodeBuilder globalSet(int idx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.GLOBAL_SET, idx)); }

    // ---------------------------------------------------------------- constants

    public WasmCodeBuilder i32Const(int v) { return add(new WasmInstr.I32Const(v)); }
    public WasmCodeBuilder i64Const(long v) { return add(new WasmInstr.I64Const(v)); }
    public WasmCodeBuilder f32Const(float v) { return add(new WasmInstr.F32Const(v)); }
    public WasmCodeBuilder f64Const(double v) { return add(new WasmInstr.F64Const(v)); }

    // ---------------------------------------------------------------- references & WasmGC

    public WasmCodeBuilder refNull(WasmValType.HeapType ht) { return add(new WasmInstr.RefNull(ht)); }
    public WasmCodeBuilder refNull(int typeIdx) { return add(new WasmInstr.RefNull(new WasmValType.HeapType.Indexed(typeIdx))); }
    public WasmCodeBuilder refIsNull() { return op(WasmInstr.SimpleOp.REF_IS_NULL); }
    public WasmCodeBuilder refFunc(int funcIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.REF_FUNC, funcIdx)); }
    public WasmCodeBuilder refEq() { return op(WasmInstr.SimpleOp.REF_EQ); }
    public WasmCodeBuilder refAsNonNull() { return op(WasmInstr.SimpleOp.REF_AS_NON_NULL); }
    public WasmCodeBuilder brOnNull(int label) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.BR_ON_NULL, label)); }
    public WasmCodeBuilder brOnNonNull(int label) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.BR_ON_NON_NULL, label)); }

    public WasmCodeBuilder structNew(int typeIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW, typeIdx)); }
    public WasmCodeBuilder structNewDefault(int typeIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.STRUCT_NEW_DEFAULT, typeIdx)); }
    public WasmCodeBuilder structGet(int typeIdx, int fieldIdx) { return add(new WasmInstr.Idx2(WasmInstr.Idx2Op.STRUCT_GET, typeIdx, fieldIdx)); }
    public WasmCodeBuilder structGetS(int typeIdx, int fieldIdx) { return add(new WasmInstr.Idx2(WasmInstr.Idx2Op.STRUCT_GET_S, typeIdx, fieldIdx)); }
    public WasmCodeBuilder structGetU(int typeIdx, int fieldIdx) { return add(new WasmInstr.Idx2(WasmInstr.Idx2Op.STRUCT_GET_U, typeIdx, fieldIdx)); }
    public WasmCodeBuilder structSet(int typeIdx, int fieldIdx) { return add(new WasmInstr.Idx2(WasmInstr.Idx2Op.STRUCT_SET, typeIdx, fieldIdx)); }

    public WasmCodeBuilder arrayNew(int typeIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.ARRAY_NEW, typeIdx)); }
    public WasmCodeBuilder arrayNewDefault(int typeIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.ARRAY_NEW_DEFAULT, typeIdx)); }
    public WasmCodeBuilder arrayNewFixed(int typeIdx, int count) { return add(new WasmInstr.Idx2(WasmInstr.Idx2Op.ARRAY_NEW_FIXED, typeIdx, count)); }
    public WasmCodeBuilder arrayNewData(int typeIdx, int dataIdx) { return add(new WasmInstr.Idx2(WasmInstr.Idx2Op.ARRAY_NEW_DATA, typeIdx, dataIdx)); }
    public WasmCodeBuilder arrayGet(int typeIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.ARRAY_GET, typeIdx)); }
    public WasmCodeBuilder arrayGetS(int typeIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.ARRAY_GET_S, typeIdx)); }
    public WasmCodeBuilder arrayGetU(int typeIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.ARRAY_GET_U, typeIdx)); }
    public WasmCodeBuilder arraySet(int typeIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.ARRAY_SET, typeIdx)); }
    public WasmCodeBuilder arrayLen() { return op(WasmInstr.SimpleOp.ARRAY_LEN); }
    public WasmCodeBuilder arrayFill(int typeIdx) { return add(new WasmInstr.Idx(WasmInstr.IdxOp.ARRAY_FILL, typeIdx)); }
    public WasmCodeBuilder arrayCopy(int dstTypeIdx, int srcTypeIdx) { return add(new WasmInstr.Idx2(WasmInstr.Idx2Op.ARRAY_COPY, dstTypeIdx, srcTypeIdx)); }

    public WasmCodeBuilder refTest(int typeIdx) { return add(new WasmInstr.RefTypeOp(WasmInstr.RefTypeOpKind.REF_TEST, new WasmValType.HeapType.Indexed(typeIdx))); }
    public WasmCodeBuilder refTestNull(int typeIdx) { return add(new WasmInstr.RefTypeOp(WasmInstr.RefTypeOpKind.REF_TEST_NULL, new WasmValType.HeapType.Indexed(typeIdx))); }
    public WasmCodeBuilder refCast(int typeIdx) { return add(new WasmInstr.RefTypeOp(WasmInstr.RefTypeOpKind.REF_CAST, new WasmValType.HeapType.Indexed(typeIdx))); }
    public WasmCodeBuilder refCastNull(int typeIdx) { return add(new WasmInstr.RefTypeOp(WasmInstr.RefTypeOpKind.REF_CAST_NULL, new WasmValType.HeapType.Indexed(typeIdx))); }
    public WasmCodeBuilder refCast(WasmValType.HeapType ht) { return add(new WasmInstr.RefTypeOp(WasmInstr.RefTypeOpKind.REF_CAST, ht)); }
    public WasmCodeBuilder refTest(WasmValType.HeapType ht) { return add(new WasmInstr.RefTypeOp(WasmInstr.RefTypeOpKind.REF_TEST, ht)); }
    public WasmCodeBuilder anyConvertExtern() { return op(WasmInstr.SimpleOp.ANY_CONVERT_EXTERN); }
    public WasmCodeBuilder externConvertAny() { return op(WasmInstr.SimpleOp.EXTERN_CONVERT_ANY); }
}
