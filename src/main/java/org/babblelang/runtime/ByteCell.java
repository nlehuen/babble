package org.babblelang.runtime;

/**
 * An unboxed {@code byte} cell for captured lexical variables (BIP011).
 */
public final class ByteCell {
    public byte value;
    private boolean defined;

    public ByteCell(byte value, boolean defined) {
        this.value = value;
        this.defined = defined;
    }

    public static ByteCell undefined() {
        return new ByteCell((byte) 0, false);
    }

    public static ByteCell of(byte value) {
        return new ByteCell(value, true);
    }

    public byte read(String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        return value;
    }

    public byte define(byte value) {
        this.value = value;
        this.defined = true;
        return value;
    }

    public byte assign(byte value, String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        this.value = value;
        return value;
    }
}
