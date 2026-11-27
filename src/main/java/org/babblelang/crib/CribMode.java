package org.babblelang.crib;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.babblelang.engine.BabbleScriptEngine;

/** Execution modes supported by {@code POST /crib?<options>} ({@code BIP041-D2}). */
public enum CribMode {
    INTERPRET("interpret", BabbleScriptEngine.Mode.INTERPRET, -1, false),
    O0("O0", BabbleScriptEngine.Mode.COMPILE, 0, false),
    O1("O1", BabbleScriptEngine.Mode.COMPILE, 1, false),
    O2("O2", BabbleScriptEngine.Mode.COMPILE, 2, false),
    WASM_O1("wasm-O1", null, 1, true),
    WASM_O2("wasm-O2", null, 2, true);

    public static final List<CribMode> DEFAULT_JVM_MODES = List.of(INTERPRET, O0, O1, O2);
    public static final List<CribMode> ALL_MODES = List.of(INTERPRET, O0, O1, O2, WASM_O1, WASM_O2);

    private final String label;
    private final BabbleScriptEngine.Mode engineMode;
    private final int optLevel;
    private final boolean wasm;

    CribMode(String label, BabbleScriptEngine.Mode engineMode, int optLevel, boolean wasm) {
        this.label = label;
        this.engineMode = engineMode;
        this.optLevel = optLevel;
        this.wasm = wasm;
    }

    public String label() {
        return label;
    }

    public BabbleScriptEngine.Mode engineMode() {
        return engineMode;
    }

    public int optLevel() {
        return optLevel;
    }

    public Integer optLevelOrNull() {
        return optLevel >= 0 ? optLevel : null;
    }

    public boolean isWasm() {
        return wasm;
    }

    /** Parses a single mode token (case-insensitive, supporting common aliases). */
    public static CribMode parseOne(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty execution mode");
        }
        String norm = raw.trim().toLowerCase(Locale.ROOT);
        return switch (norm) {
            case "interpret", "interp", "interpreter" -> INTERPRET;
            case "o0", "-o0", "0", "compile-o0" -> O0;
            case "o1", "-o1", "1", "compile-o1", "compile" -> O1;
            case "o2", "-o2", "2", "compile-o2" -> O2;
            case "wasm", "wasm-o1", "wasm-js", "wasm1" -> WASM_O1;
            case "wasm-o2", "wasm2" -> WASM_O2;
            default ->
                    throw new IllegalArgumentException(
                            "Unknown execution mode : '"
                                    + raw
                                    + "' (expected interpret, O0, O1, O2, wasm, wasm-O1, wasm-O2, or all)");
        };
    }

    /**
     * Parses a comma-separated mode specification (null/blank defaults to {@link
     * #DEFAULT_JVM_MODES}).
     */
    public static List<CribMode> parseList(String raw, boolean wasmAvailable) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_JVM_MODES;
        }
        Set<CribMode> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("all")) {
                result.addAll(wasmAvailable ? ALL_MODES : DEFAULT_JVM_MODES);
            } else if (token.equalsIgnoreCase("jvm")) {
                result.addAll(DEFAULT_JVM_MODES);
            } else {
                result.add(parseOne(token));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No execution modes specified");
        }
        return new ArrayList<>(result);
    }
}
