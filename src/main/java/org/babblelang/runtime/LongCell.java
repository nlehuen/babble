package org.babblelang.runtime;

/**
 * An unboxed {@code long} cell for captured lexical variables (BIP011).
 */
public final class LongCell {
    public long value;
    private boolean defined;

    public LongCell(long value, boolean defined) {
        this.value = value;
        this.defined = defined;
    }

    public static LongCell undefined() {
        return new LongCell(0L, false);
    }

    public static LongCell of(long value) {
        return new LongCell(value, true);
    }

    public long read(String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        return value;
    }

    public long define(long value) {
        this.value = value;
        this.defined = true;
        return value;
    }

    public long assign(long value, String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        this.value = value;
        return value;
    }
}
