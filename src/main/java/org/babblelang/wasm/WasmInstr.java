package org.babblelang.wasm;

import java.util.List;
import java.util.Objects;

/**
 * Compact WebAssembly 3.0 instruction IR shared by {@link WasmWriter}, {@link WatPrinter}, and
 * {@link WasmVerifier} ({@code BIP025-P0}, {@code BIP025-D2}).
 */
public sealed interface WasmInstr permits
        WasmInstr.Simple,
        WasmInstr.I32Const,
        WasmInstr.I64Const,
        WasmInstr.F32Const,
        WasmInstr.F64Const,
        WasmInstr.Idx,
        WasmInstr.Idx2,
        WasmInstr.Block,
        WasmInstr.TryTable,
        WasmInstr.BrTable,
        WasmInstr.RefNull,
        WasmInstr.RefTypeOp,
        WasmInstr.BrOnCast,
        WasmInstr.SelectTyped {

    /** Block result signature: empty ({@code 0x40}), single value type, or concrete func type index. */
    sealed interface BlockType permits BlockType.Empty, BlockType.Val, BlockType.TypeIdx {
        record Empty() implements BlockType {
        }

        record Val(WasmValType type) implements BlockType {
            public Val {
                Objects.requireNonNull(type, "type");
            }
        }

        record TypeIdx(int typeIndex) implements BlockType {
        }

        Empty EMPTY = new Empty();

        static BlockType of(WasmValType type) {
            return type == null ? EMPTY : new Val(type);
        }
    }

    enum BlockKind {
        BLOCK(0x02, "block"),
        LOOP(0x03, "loop"),
        IF(0x04, "if");

        private final int opcode;
        private final String wat;

        BlockKind(int opcode, String wat) {
            this.opcode = opcode;
            this.wat = wat;
        }

        public int opcode() {
            return opcode;
        }

        public String wat() {
            return wat;
        }
    }

    enum CatchKind {
        CATCH(0x00, "catch"),
        CATCH_REF(0x01, "catch_ref"),
        CATCH_ALL(0x02, "catch_all"),
        CATCH_ALL_REF(0x03, "catch_all_ref");

        private final int opcode;
        private final String wat;

        CatchKind(int opcode, String wat) {
            this.opcode = opcode;
            this.wat = wat;
        }

        public int opcode() {
            return opcode;
        }

        public String wat() {
            return wat;
        }

        public boolean hasTag() {
            return this == CATCH || this == CATCH_REF;
        }
    }

    record CatchClause(CatchKind kind, int tagIndex, int labelIndex) {
        public CatchClause {
            Objects.requireNonNull(kind, "kind");
        }

        public static CatchClause catchTag(int tagIndex, int labelIndex) {
            return new CatchClause(CatchKind.CATCH, tagIndex, labelIndex);
        }

        public static CatchClause catchRefTag(int tagIndex, int labelIndex) {
            return new CatchClause(CatchKind.CATCH_REF, tagIndex, labelIndex);
        }
    }

    /** Zero-immediate opcodes (standard, 0xFB GC, and 0xFC saturating conversions). */
    enum SimpleOp {
        UNREACHABLE(0, 0x00, "unreachable"),
        NOP(0, 0x01, "nop"),
        ELSE(0, 0x05, "else"),
        END(0, 0x0B, "end"),
        THROW_REF(0, 0x0A, "throw_ref"),
        RETURN(0, 0x0F, "return"),
        DROP(0, 0x1A, "drop"),
        SELECT(0, 0x1B, "select"),
        // i32 comparisons
        I32_EQZ(0, 0x45, "i32.eqz"),
        I32_EQ(0, 0x46, "i32.eq"),
        I32_NE(0, 0x47, "i32.ne"),
        I32_LT_S(0, 0x48, "i32.lt_s"),
        I32_LT_U(0, 0x49, "i32.lt_u"),
        I32_GT_S(0, 0x4A, "i32.gt_s"),
        I32_GT_U(0, 0x4B, "i32.gt_u"),
        I32_LE_S(0, 0x4C, "i32.le_s"),
        I32_LE_U(0, 0x4D, "i32.le_u"),
        I32_GE_S(0, 0x4E, "i32.ge_s"),
        I32_GE_U(0, 0x4F, "i32.ge_u"),
        // i64 comparisons
        I64_EQZ(0, 0x50, "i64.eqz"),
        I64_EQ(0, 0x51, "i64.eq"),
        I64_NE(0, 0x52, "i64.ne"),
        I64_LT_S(0, 0x53, "i64.lt_s"),
        I64_LT_U(0, 0x54, "i64.lt_u"),
        I64_GT_S(0, 0x55, "i64.gt_s"),
        I64_GT_U(0, 0x56, "i64.gt_u"),
        I64_LE_S(0, 0x57, "i64.le_s"),
        I64_LE_U(0, 0x58, "i64.le_u"),
        I64_GE_S(0, 0x59, "i64.ge_s"),
        I64_GE_U(0, 0x5A, "i64.ge_u"),
        // f32 comparisons
        F32_EQ(0, 0x5B, "f32.eq"),
        F32_NE(0, 0x5C, "f32.ne"),
        F32_LT(0, 0x5D, "f32.lt"),
        F32_GT(0, 0x5E, "f32.gt"),
        F32_LE(0, 0x5F, "f32.le"),
        F32_GE(0, 0x60, "f32.ge"),
        // f64 comparisons
        F64_EQ(0, 0x61, "f64.eq"),
        F64_NE(0, 0x62, "f64.ne"),
        F64_LT(0, 0x63, "f64.lt"),
        F64_GT(0, 0x64, "f64.gt"),
        F64_LE(0, 0x65, "f64.le"),
        F64_GE(0, 0x66, "f64.ge"),
        // i32 arithmetic & bitwise
        I32_CLZ(0, 0x67, "i32.clz"),
        I32_CTZ(0, 0x68, "i32.ctz"),
        I32_POPCNT(0, 0x69, "i32.popcnt"),
        I32_ADD(0, 0x6A, "i32.add"),
        I32_SUB(0, 0x6B, "i32.sub"),
        I32_MUL(0, 0x6C, "i32.mul"),
        I32_DIV_S(0, 0x6D, "i32.div_s"),
        I32_DIV_U(0, 0x6E, "i32.div_u"),
        I32_REM_S(0, 0x6F, "i32.rem_s"),
        I32_REM_U(0, 0x70, "i32.rem_u"),
        I32_AND(0, 0x71, "i32.and"),
        I32_OR(0, 0x72, "i32.or"),
        I32_XOR(0, 0x73, "i32.xor"),
        I32_SHL(0, 0x74, "i32.shl"),
        I32_SHR_S(0, 0x75, "i32.shr_s"),
        I32_SHR_U(0, 0x76, "i32.shr_u"),
        // i64 arithmetic & bitwise
        I64_ADD(0, 0x7C, "i64.add"),
        I64_SUB(0, 0x7D, "i64.sub"),
        I64_MUL(0, 0x7E, "i64.mul"),
        I64_DIV_S(0, 0x7F, "i64.div_s"),
        I64_DIV_U(0, 0x80, "i64.div_u"),
        I64_REM_S(0, 0x81, "i64.rem_s"),
        I64_REM_U(0, 0x82, "i64.rem_u"),
        I64_AND(0, 0x83, "i64.and"),
        I64_OR(0, 0x84, "i64.or"),
        I64_XOR(0, 0x85, "i64.xor"),
        I64_SHL(0, 0x86, "i64.shl"),
        I64_SHR_S(0, 0x87, "i64.shr_s"),
        I64_SHR_U(0, 0x88, "i64.shr_u"),
        // f32 arithmetic
        F32_ABS(0, 0x8B, "f32.abs"),
        F32_NEG(0, 0x8C, "f32.neg"),
        F32_CEIL(0, 0x8D, "f32.ceil"),
        F32_FLOOR(0, 0x8E, "f32.floor"),
        F32_TRUNC(0, 0x8F, "f32.trunc"),
        F32_NEAREST(0, 0x90, "f32.nearest"),
        F32_SQRT(0, 0x91, "f32.sqrt"),
        F32_ADD(0, 0x92, "f32.add"),
        F32_SUB(0, 0x93, "f32.sub"),
        F32_MUL(0, 0x94, "f32.mul"),
        F32_DIV(0, 0x95, "f32.div"),
        F32_MIN(0, 0x96, "f32.min"),
        F32_MAX(0, 0x97, "f32.max"),
        // f64 arithmetic
        F64_ABS(0, 0x99, "f64.abs"),
        F64_NEG(0, 0x9A, "f64.neg"),
        F64_CEIL(0, 0x9B, "f64.ceil"),
        F64_FLOOR(0, 0x9C, "f64.floor"),
        F64_TRUNC(0, 0x9D, "f64.trunc"),
        F64_NEAREST(0, 0x9E, "f64.nearest"),
        F64_SQRT(0, 0x9F, "f64.sqrt"),
        F64_ADD(0, 0xA0, "f64.add"),
        F64_SUB(0, 0xA1, "f64.sub"),
        F64_MUL(0, 0xA2, "f64.mul"),
        F64_DIV(0, 0xA3, "f64.div"),
        F64_MIN(0, 0xA4, "f64.min"),
        F64_MAX(0, 0xA5, "f64.max"),
        // Conversions & reinterpretations
        I32_WRAP_I64(0, 0xA7, "i32.wrap_i64"),
        I64_EXTEND_I32_S(0, 0xAC, "i64.extend_i32_s"),
        I64_EXTEND_I32_U(0, 0xAD, "i64.extend_i32_u"),
        F32_CONVERT_I32_S(0, 0xB2, "f32.convert_i32_s"),
        F32_CONVERT_I64_S(0, 0xB4, "f32.convert_i64_s"),
        F32_DEMOTE_F64(0, 0xB6, "f32.demote_f64"),
        F64_CONVERT_I32_S(0, 0xB7, "f64.convert_i32_s"),
        F64_CONVERT_I64_S(0, 0xB9, "f64.convert_i64_s"),
        F64_PROMOTE_F32(0, 0xBB, "f64.promote_f32"),
        I32_REINTERPRET_F32(0, 0xBC, "i32.reinterpret_f32"),
        I64_REINTERPRET_F64(0, 0xBD, "i64.reinterpret_f64"),
        F32_REINTERPRET_I32(0, 0xBE, "f32.reinterpret_i32"),
        F64_REINTERPRET_I64(0, 0xBF, "f64.reinterpret_i64"),
        I32_EXTEND8_S(0, 0xC0, "i32.extend8_s"),
        I32_EXTEND16_S(0, 0xC1, "i32.extend16_s"),
        // Reference ops
        REF_IS_NULL(0, 0xD1, "ref.is_null"),
        REF_EQ(0, 0xD3, "ref.eq"),
        REF_AS_NON_NULL(0, 0xD4, "ref.as_non_null"),
        // GC zero-immediate ops (0xFB prefix)
        ARRAY_LEN(0xFB, 0x0F, "array.len"),
        ANY_CONVERT_EXTERN(0xFB, 0x1A, "any.convert_extern"),
        EXTERN_CONVERT_ANY(0xFB, 0x1B, "extern.convert_any"),
        REF_I31(0xFB, 0x1C, "ref.i31"),
        I31_GET_S(0xFB, 0x1D, "i31.get_s"),
        I31_GET_U(0xFB, 0x1E, "i31.get_u"),
        // Saturating float-to-int truncations (0xFC prefix; match Java (int)/(long) casts!)
        I32_TRUNC_SAT_F32_S(0xFC, 0x00, "i32.trunc_sat_f32_s"),
        I32_TRUNC_SAT_F64_S(0xFC, 0x02, "i32.trunc_sat_f64_s"),
        I64_TRUNC_SAT_F32_S(0xFC, 0x04, "i64.trunc_sat_f32_s"),
        I64_TRUNC_SAT_F64_S(0xFC, 0x06, "i64.trunc_sat_f64_s");

        private final int prefix;
        private final int opcode;
        private final String wat;

        SimpleOp(int prefix, int opcode, String wat) {
            this.prefix = prefix;
            this.opcode = opcode;
            this.wat = wat;
        }

        public int prefix() {
            return prefix;
        }

        public int opcode() {
            return opcode;
        }

        public String wat() {
            return wat;
        }
    }

    /** Single-index opcodes. */
    enum IdxOp {
        THROW(0, 0x08, "throw", IdxKind.TAG),
        BR(0, 0x0C, "br", IdxKind.LABEL),
        BR_IF(0, 0x0D, "br_if", IdxKind.LABEL),
        CALL(0, 0x10, "call", IdxKind.FUNC),
        RETURN_CALL(0, 0x12, "return_call", IdxKind.FUNC),
        CALL_REF(0, 0x14, "call_ref", IdxKind.TYPE),
        RETURN_CALL_REF(0, 0x15, "return_call_ref", IdxKind.TYPE),
        LOCAL_GET(0, 0x20, "local.get", IdxKind.LOCAL),
        LOCAL_SET(0, 0x21, "local.set", IdxKind.LOCAL),
        LOCAL_TEE(0, 0x22, "local.tee", IdxKind.LOCAL),
        GLOBAL_GET(0, 0x23, "global.get", IdxKind.GLOBAL),
        GLOBAL_SET(0, 0x24, "global.set", IdxKind.GLOBAL),
        REF_FUNC(0, 0xD2, "ref.func", IdxKind.FUNC),
        BR_ON_NULL(0, 0xD5, "br_on_null", IdxKind.LABEL),
        BR_ON_NON_NULL(0, 0xD6, "br_on_non_null", IdxKind.LABEL),
        // WasmGC 0xFB single-index ops
        STRUCT_NEW(0xFB, 0x00, "struct.new", IdxKind.TYPE),
        STRUCT_NEW_DEFAULT(0xFB, 0x01, "struct.new_default", IdxKind.TYPE),
        ARRAY_NEW(0xFB, 0x06, "array.new", IdxKind.TYPE),
        ARRAY_NEW_DEFAULT(0xFB, 0x07, "array.new_default", IdxKind.TYPE),
        ARRAY_GET(0xFB, 0x0B, "array.get", IdxKind.TYPE),
        ARRAY_GET_S(0xFB, 0x0C, "array.get_s", IdxKind.TYPE),
        ARRAY_GET_U(0xFB, 0x0D, "array.get_u", IdxKind.TYPE),
        ARRAY_SET(0xFB, 0x0E, "array.set", IdxKind.TYPE),
        ARRAY_FILL(0xFB, 0x10, "array.fill", IdxKind.TYPE);

        public enum IdxKind {
            FUNC, TYPE, LOCAL, GLOBAL, TAG, LABEL
        }

        private final int prefix;
        private final int opcode;
        private final String wat;
        private final IdxKind idxKind;

        IdxOp(int prefix, int opcode, String wat, IdxKind idxKind) {
            this.prefix = prefix;
            this.opcode = opcode;
            this.wat = wat;
            this.idxKind = idxKind;
        }

        public int prefix() {
            return prefix;
        }

        public int opcode() {
            return opcode;
        }

        public String wat() {
            return wat;
        }

        public IdxKind idxKind() {
            return idxKind;
        }
    }

    /** Two-index WasmGC (0xFB) opcodes. */
    enum Idx2Op {
        STRUCT_GET(0xFB, 0x02, "struct.get"),
        STRUCT_GET_S(0xFB, 0x03, "struct.get_s"),
        STRUCT_GET_U(0xFB, 0x04, "struct.get_u"),
        STRUCT_SET(0xFB, 0x05, "struct.set"),
        ARRAY_NEW_FIXED(0xFB, 0x08, "array.new_fixed"),
        ARRAY_NEW_DATA(0xFB, 0x09, "array.new_data"),
        ARRAY_COPY(0xFB, 0x11, "array.copy");

        private final int prefix;
        private final int opcode;
        private final String wat;

        Idx2Op(int prefix, int opcode, String wat) {
            this.prefix = prefix;
            this.opcode = opcode;
            this.wat = wat;
        }

        public int prefix() {
            return prefix;
        }

        public int opcode() {
            return opcode;
        }

        public String wat() {
            return wat;
        }
    }

    /** WasmGC reference test/cast ops (0xFB prefix + heaptype). */
    enum RefTypeOpKind {
        REF_TEST(0x14, "ref.test", false),
        REF_TEST_NULL(0x15, "ref.test", true),
        REF_CAST(0x16, "ref.cast", false),
        REF_CAST_NULL(0x17, "ref.cast", true);

        private final int subOpcode;
        private final String wat;
        private final boolean nullable;

        RefTypeOpKind(int subOpcode, String wat, boolean nullable) {
            this.subOpcode = subOpcode;
            this.wat = wat;
            this.nullable = nullable;
        }

        public int subOpcode() {
            return subOpcode;
        }

        public String wat() {
            return wat;
        }

        public boolean nullable() {
            return nullable;
        }
    }

    record Simple(SimpleOp op) implements WasmInstr {
    }

    record I32Const(int value) implements WasmInstr {
    }

    record I64Const(long value) implements WasmInstr {
    }

    record F32Const(float value) implements WasmInstr {
    }

    record F64Const(double value) implements WasmInstr {
    }

    record Idx(IdxOp op, int index) implements WasmInstr {
    }

    record Idx2(Idx2Op op, int index1, int index2) implements WasmInstr {
    }

    record Block(BlockKind kind, BlockType blockType) implements WasmInstr {
    }

    record TryTable(BlockType blockType, List<CatchClause> catches) implements WasmInstr {
        public TryTable {
            catches = List.copyOf(catches);
        }
    }

    record BrTable(List<Integer> labels, int defaultLabel) implements WasmInstr {
        public BrTable {
            labels = List.copyOf(labels);
        }
    }

    record RefNull(WasmValType.HeapType heapType) implements WasmInstr {
    }

    record RefTypeOp(RefTypeOpKind kind, WasmValType.HeapType heapType) implements WasmInstr {
    }

    record BrOnCast(boolean fail, int labelIndex, boolean srcNullable, boolean dstNullable,
                    WasmValType.HeapType srcType, WasmValType.HeapType dstType) implements WasmInstr {
    }

    record SelectTyped(WasmValType type) implements WasmInstr {
    }
}
