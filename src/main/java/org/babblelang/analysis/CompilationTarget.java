package org.babblelang.analysis;

import java.util.Locale;

/**
 * Compilation backend target profile ({@code BIP025-D3}, {@code BIP025-D8}).
 *
 * <p>Kept strictly ANTLR-free inside {@code org.babblelang.analysis} so front-end analysis,
 * HIR construction, Nanopass optimization ({@code FbipReusePass}), and backend emitters share
 * one target abstraction.
 */
public enum CompilationTarget {
    /** Standard JVM bytecode backend ({@code babblec -target jvm}, the default). */
    JVM("jvm"),
    /** WebAssembly 3.0 (WasmGC) targeting browsers and Node.js with {@code app.mjs} glue ({@code BIP025-D8}). */
    WASM_JS("wasm-js"),
    /** WebAssembly 3.0 (WasmGC) pure-compute module with zero host imports ({@code BIP025-D8}). */
    WASM_BARE("wasm-bare");

    private final String cliName;

    CompilationTarget(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }

    public boolean isJvm() {
        return this == JVM;
    }

    public boolean isWasm() {
        return this == WASM_JS || this == WASM_BARE;
    }

    public boolean isWasmBare() {
        return this == WASM_BARE;
    }

    public boolean isWasmJs() {
        return this == WASM_JS;
    }

    /**
     * Parses a CLI {@code -target} value.
     *
     * @throws IllegalArgumentException if unknown or unsupported
     */
    public static CompilationTarget parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return JVM;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "jvm" -> JVM;
            case "wasm-js", "wasm_js" -> WASM_JS;
            case "wasm-bare", "wasm_bare" -> WASM_BARE;
            case "wasm-wasi", "wasm_wasi" -> throw new IllegalArgumentException(
                    "Target wasm-wasi is deferred until the WasmGC Component Model Canonical ABI stabilizes (BIP025-D8)");
            default -> throw new IllegalArgumentException(
                    "Unknown compilation target : " + raw + " (expected jvm, wasm-js, or wasm-bare)");
        };
    }
}
