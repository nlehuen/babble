package org.babblelang.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Objects;

/**
 * LEB128 encoding helpers for WebAssembly 3.0 binary emission ({@code BIP025-I0}).
 */
final class WasmLeb128 {
    private WasmLeb128() {
    }

    static void writeU32(ByteArrayOutputStream out, int value) {
        long v = Integer.toUnsignedLong(value);
        do {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                b |= 0x80;
            }
            out.write(b);
        } while (v != 0);
    }

    static void writeS32(ByteArrayOutputStream out, int value) {
        writeSigned(out, value);
    }

    static void writeS33(ByteArrayOutputStream out, long value) {
        writeSigned(out, value);
    }

    static void writeS64(ByteArrayOutputStream out, long value) {
        writeSigned(out, value);
    }

    private static void writeSigned(ByteArrayOutputStream out, long value) {
        boolean more = true;
        while (more) {
            int b = (int) (value & 0x7F);
            value >>= 7;
            boolean signBit = (b & 0x40) != 0;
            if ((value == 0 && !signBit) || (value == -1 && signBit)) {
                more = false;
            } else {
                b |= 0x80;
            }
            out.write(b);
        }
    }

    static void writeF32(ByteArrayOutputStream out, float value) {
        int bits = Float.floatToRawIntBits(value);
        out.write(bits & 0xFF);
        out.write((bits >>> 8) & 0xFF);
        out.write((bits >>> 16) & 0xFF);
        out.write((bits >>> 24) & 0xFF);
    }

    static void writeF64(ByteArrayOutputStream out, double value) {
        long bits = Double.doubleToRawLongBits(value);
        for (int i = 0; i < 8; i++) {
            out.write((int) ((bits >>> (i * 8)) & 0xFF));
        }
    }

    static void writeName(ByteArrayOutputStream out, String name) {
        byte[] utf8 = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeU32(out, utf8.length);
        out.write(utf8, 0, utf8.length);
    }
}
