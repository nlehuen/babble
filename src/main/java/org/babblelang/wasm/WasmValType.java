package org.babblelang.wasm;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

/**
 * WebAssembly 3.0 value types, packed storage types, and heap types ({@code BIP025-I0}, {@code BIP025-D1}).
 */
public sealed interface WasmValType permits
        WasmValType.Num,
        WasmValType.Packed,
        WasmValType.Ref {

    /** Numeric value types. */
    enum Num implements WasmValType {
        I32(0x7F, "i32"),
        I64(0x7E, "i64"),
        F32(0x7D, "f32"),
        F64(0x7C, "f64");

        private final int opcode;
        private final String wat;

        Num(int opcode, String wat) {
            this.opcode = opcode;
            this.wat = wat;
        }

        public int opcode() {
            return opcode;
        }

        @Override
        public String wat() {
            return wat;
        }

        @Override
        public void writeTo(ByteArrayOutputStream out) {
            out.write(opcode);
        }
    }

    /** Packed storage types valid only inside GC struct and array fields. */
    enum Packed implements WasmValType {
        I8(0x78, "i8"),
        I16(0x77, "i16");

        private final int opcode;
        private final String wat;

        Packed(int opcode, String wat) {
            this.opcode = opcode;
            this.wat = wat;
        }

        public int opcode() {
            return opcode;
        }

        @Override
        public String wat() {
            return wat;
        }

        @Override
        public void writeTo(ByteArrayOutputStream out) {
            out.write(opcode);
        }
    }

    /** Abstract or concrete heap type for GC, function, extern, and exception references. */
    sealed interface HeapType permits HeapType.Abstract, HeapType.Indexed {
        enum Abstract implements HeapType {
            NOEXN(0x74, "noexn"),
            NOFUNC(0x73, "nofunc"),
            NOEXTERN(0x72, "noextern"),
            NONE(0x71, "none"),
            FUNC(0x70, "func"),
            EXTERN(0x6F, "extern"),
            ANY(0x6E, "any"),
            EQ(0x6D, "eq"),
            I31(0x6C, "i31"),
            STRUCT(0x6B, "struct"),
            ARRAY(0x6A, "array"),
            EXN(0x69, "exn");

            private final int opcode;
            private final String wat;

            Abstract(int opcode, String wat) {
                this.opcode = opcode;
                this.wat = wat;
            }

            public int opcode() {
                return opcode;
            }

            @Override
            public String wat() {
                return wat;
            }

            @Override
            public void writeTo(ByteArrayOutputStream out) {
                out.write(opcode);
            }
        }

        record Indexed(int typeIndex) implements HeapType {
            public Indexed {
                if (typeIndex < 0) {
                    throw new IllegalArgumentException("Negative Wasm type index : " + typeIndex);
                }
            }

            @Override
            public String wat() {
                return String.valueOf(typeIndex);
            }

            @Override
            public void writeTo(ByteArrayOutputStream out) {
                WasmLeb128.writeS33(out, typeIndex);
            }
        }

        String wat();

        void writeTo(ByteArrayOutputStream out);
    }

    /** Reference value type: {@code (ref ht)} or {@code (ref null ht)}. */
    record Ref(HeapType heapType, boolean nullable) implements WasmValType {
        public Ref {
            Objects.requireNonNull(heapType, "heapType");
        }

        @Override
        public String wat() {
            if (nullable && heapType instanceof HeapType.Abstract a) {
                return switch (a) {
                    case ANY -> "anyref";
                    case EQ -> "eqref";
                    case I31 -> "i31ref";
                    case STRUCT -> "structref";
                    case ARRAY -> "arrayref";
                    case FUNC -> "funcref";
                    case EXTERN -> "externref";
                    case EXN -> "exnref";
                    case NONE -> "nullref";
                    case NOFUNC -> "nullfuncref";
                    case NOEXTERN -> "nullexternref";
                    case NOEXN -> "nullexnref";
                };
            }
            String ht = heapType instanceof HeapType.Indexed idx ? "$t" + idx.typeIndex() : heapType.wat();
            return nullable ? "(ref null " + ht + ")" : "(ref " + ht + ")";
        }

        @Override
        public void writeTo(ByteArrayOutputStream out) {
            if (nullable && heapType instanceof HeapType.Abstract a) {
                out.write(a.opcode());
                return;
            }
            out.write(nullable ? 0x63 : 0x64);
            heapType.writeTo(out);
        }
    }

    WasmValType I32 = Num.I32;
    WasmValType I64 = Num.I64;
    WasmValType F32 = Num.F32;
    WasmValType F64 = Num.F64;
    WasmValType I8 = Packed.I8;
    WasmValType I16 = Packed.I16;

    Ref ANYREF = new Ref(HeapType.Abstract.ANY, true);
    Ref EQREF = new Ref(HeapType.Abstract.EQ, true);
    Ref EXTERNREF = new Ref(HeapType.Abstract.EXTERN, true);
    Ref FUNCREF = new Ref(HeapType.Abstract.FUNC, true);
    Ref EXNREF = new Ref(HeapType.Abstract.EXN, true);
    Ref NON_NULL_EXN = new Ref(HeapType.Abstract.EXN, false);
    Ref NULLREF = new Ref(HeapType.Abstract.NONE, true);

    static Ref ref(int typeIndex) {
        return new Ref(new HeapType.Indexed(typeIndex), false);
    }

    static Ref refNull(int typeIndex) {
        return new Ref(new HeapType.Indexed(typeIndex), true);
    }

    static Ref ref(HeapType.Abstract ht) {
        return new Ref(ht, false);
    }

    static Ref refNull(HeapType.Abstract ht) {
        return new Ref(ht, true);
    }

    default boolean isPacked() {
        return this instanceof Packed;
    }

    /** Returns the stack/local value type corresponding to a storage type (packed i8/i16 widen to i32). */
    default WasmValType unpacked() {
        return this instanceof Packed ? I32 : this;
    }

    String wat();

    void writeTo(ByteArrayOutputStream out);
}
