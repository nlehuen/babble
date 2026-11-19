package org.babblelang.runtime;

/**
 * An unboxed {@code float} cell for captured lexical variables (BIP011).
 */
public final class FloatCell {
    public float value;
    private boolean defined;

    public FloatCell(float value, boolean defined) {
        this.value = value;
        this.defined = defined;
    }

    public static FloatCell undefined() {
        return new FloatCell(0.0f, false);
    }

    public static FloatCell of(float value) {
        return new FloatCell(value, true);
    }

    public float read(String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        return value;
    }

    public float define(float value) {
        this.value = value;
        this.defined = true;
        return value;
    }

    public float assign(float value, String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        this.value = value;
        return value;
    }
}
