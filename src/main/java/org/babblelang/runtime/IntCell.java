package org.babblelang.runtime;

/** An unboxed {@code int} cell for captured lexical variables (RM001). */
public final class IntCell {
    public int value;
    private boolean defined;

    public IntCell(int value, boolean defined) {
        this.value = value;
        this.defined = defined;
    }

    public static IntCell undefined() {
        return new IntCell(0, false);
    }

    public static IntCell of(int value) {
        return new IntCell(value, true);
    }

    /** Reads a variable that may not be defined yet. */
    public int read(String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        return value;
    }

    /** {@code def x = value} */
    public int define(int value) {
        this.value = value;
        this.defined = true;
        return value;
    }

    /** {@code x = value} : the definition must have run. */
    public int assign(int value, String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        this.value = value;
        return value;
    }
}
