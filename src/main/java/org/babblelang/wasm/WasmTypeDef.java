package org.babblelang.wasm;

import java.util.List;
import java.util.Objects;

/**
 * WebAssembly 3.0 composite type definitions ({@code func}, {@code struct}, {@code array}),
 * {@code sub}/{@code sub final} wrappers, and {@code rec} groups ({@code BIP025-P0}).
 */
public sealed interface WasmTypeDef permits
        WasmTypeDef.FuncType,
        WasmTypeDef.StructType,
        WasmTypeDef.ArrayType {

    record Field(String name, WasmValType type, boolean mutable) {
        public Field {
            Objects.requireNonNull(type, "type");
        }

        public static Field imm(String name, WasmValType type) {
            return new Field(name, type, false);
        }

        public static Field mut(String name, WasmValType type) {
            return new Field(name, type, true);
        }
    }

    record FuncType(List<WasmValType> params, List<WasmValType> results) implements WasmTypeDef {
        public FuncType {
            params = List.copyOf(params);
            results = List.copyOf(results);
        }
    }

    record StructType(List<Field> fields) implements WasmTypeDef {
        public StructType {
            fields = List.copyOf(fields);
        }
    }

    record ArrayType(Field element) implements WasmTypeDef {
        public ArrayType {
            Objects.requireNonNull(element, "element");
        }
    }

    /** A subtype entry inside the Wasm type section (optionally non-final and/or extending a supertype). */
    record SubType(int index, String name, boolean isFinal, int superTypeIndex, WasmTypeDef body) {
        public SubType {
            Objects.requireNonNull(body, "body");
        }

        public boolean hasSuper() {
            return superTypeIndex >= 0;
        }
    }

    /** A recursive type group ({@code rec (...)}) in the type section. Single-element non-recursive groups encode compactly. */
    record RecGroup(List<SubType> types) {
        public RecGroup {
            types = List.copyOf(types);
            if (types.isEmpty()) {
                throw new IllegalArgumentException("Empty Wasm rec group");
            }
        }
    }
}
