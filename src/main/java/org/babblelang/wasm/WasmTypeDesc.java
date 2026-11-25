package org.babblelang.wasm;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Babble runtime type descriptors ({@code kind|display|payload}, matching
 * {@code org.babblelang.runtime.Types}) at Wasm compile time for portability checking
 * ({@link WasmPortabilityChecker}) and exact {@code is}/{@code as} lowering ({@code BIP025-I5}).
 */
public record WasmTypeDesc(String kind, String display, String payload, List<WasmTypeDesc> members) {

    public static WasmTypeDesc parse(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return null;
        }
        int first = descriptor.indexOf('|');
        int last = descriptor.lastIndexOf('|');
        if (first < 0 || first == last) {
            return new WasmTypeDesc(descriptor, descriptor, "", List.of());
        }
        String kind = descriptor.substring(0, first);
        String display = descriptor.substring(first + 1, last);
        String payload = descriptor.substring(last + 1);
        if ("union".equals(kind) || "inter".equals(kind)) {
            String[] parts = payload.split(",", -1);
            List<WasmTypeDesc> ms = new ArrayList<>(parts.length);
            for (String part : parts) {
                if (!part.isEmpty()) {
                    ms.add(parse(decode(part)));
                }
            }
            return new WasmTypeDesc(kind, display, payload, List.copyOf(ms));
        }
        return new WasmTypeDesc(kind, display, payload, List.of());
    }

    private static String decode(String component) {
        StringBuilder sb = new StringBuilder(component.length());
        for (int i = 0; i < component.length(); i++) {
            char c = component.charAt(i);
            if (c == '%' && i + 2 < component.length()) {
                String hex = component.substring(i + 1, i + 3);
                switch (hex) {
                    case "25" -> { sb.append('%'); i += 2; continue; }
                    case "2C" -> { sb.append(','); i += 2; continue; }
                    case "7C" -> { sb.append('|'); i += 2; continue; }
                    default -> {}
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
