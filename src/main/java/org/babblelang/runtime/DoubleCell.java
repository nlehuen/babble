package org.babblelang.runtime;

/** An unboxed {@code double} cell for captured lexical variables (RM001). */
public final class DoubleCell {
    public double value;
    private boolean defined;

    public DoubleCell(double value, boolean defined) {
        this.value = value;
        this.defined = defined;
    }

    public static DoubleCell undefined() {
        return new DoubleCell(0.0, false);
    }

    public static DoubleCell of(double value) {
        return new DoubleCell(value, true);
    }

    /** Reads a variable that may not be defined yet. */
    public double read(String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        return value;
    }

    /** {@code def x = value} */
    public double define(double value) {
        this.value = value;
        this.defined = true;
        return value;
    }

    /** {@code x = value} : the definition must have run. */
    public double assign(double value, String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        this.value = value;
        return value;
    }
}
