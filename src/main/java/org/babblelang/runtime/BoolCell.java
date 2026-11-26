package org.babblelang.runtime;

/** An unboxed {@code bool} cell for captured lexical variables (RM001). */
public final class BoolCell {
    public boolean value;
    private boolean defined;

    public BoolCell(boolean value, boolean defined) {
        this.value = value;
        this.defined = defined;
    }

    public static BoolCell undefined() {
        return new BoolCell(false, false);
    }

    public static BoolCell of(boolean value) {
        return new BoolCell(value, true);
    }

    /** Reads a variable that may not be defined yet. */
    public boolean read(String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        return value;
    }

    /** {@code def x = value} */
    public boolean define(boolean value) {
        this.value = value;
        this.defined = true;
        return value;
    }

    /** {@code x = value} : the definition must have run. */
    public boolean assign(boolean value, String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        this.value = value;
        return value;
    }
}
