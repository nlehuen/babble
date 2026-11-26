package org.babblelang.runtime;

/** An unboxed {@code short} cell for captured lexical variables (BIP011). */
public final class ShortCell {
    public short value;
    private boolean defined;

    public ShortCell(short value, boolean defined) {
        this.value = value;
        this.defined = defined;
    }

    public static ShortCell undefined() {
        return new ShortCell((short) 0, false);
    }

    public static ShortCell of(short value) {
        return new ShortCell(value, true);
    }

    public short read(String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        return value;
    }

    public short define(short value) {
        this.value = value;
        this.defined = true;
        return value;
    }

    public short assign(short value, String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        this.value = value;
        return value;
    }
}
