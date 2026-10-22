package org.babblelang.runtime;

/**
 * A variable captured by a closure (compiler phase 2) : the method that declares it and
 * the functions that capture it share the cell. A hoisted definition starts out
 * {@link Undefined#UNDEFINED}.
 */
public final class Cell {
    public Object value;

    public Cell(Object value) {
        this.value = value;
    }

    public static Cell undefined() {
        return new Cell(Undefined.UNDEFINED);
    }

    public static Cell of(Object value) {
        return new Cell(value);
    }

    /** Reads a variable that may not be defined yet. */
    public Object read(String name) {
        return Frames.defined(value, name);
    }

    /** {@code def x = value} */
    public Object define(Object value) {
        this.value = value;
        return value;
    }

    /** {@code x = value} : the definition must have run. */
    public Object assign(Object value, String name) {
        Frames.checkAssignable(this.value, name);
        this.value = value;
        return value;
    }
}
