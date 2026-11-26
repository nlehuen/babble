package org.babblelang.runtime;

/** An unboxed {@code char} cell for captured lexical variables (BIP011). */
public final class CharCell {
    public char value;
    private boolean defined;

    public CharCell(char value, boolean defined) {
        this.value = value;
        this.defined = defined;
    }

    public static CharCell undefined() {
        return new CharCell('\0', false);
    }

    public static CharCell of(char value) {
        return new CharCell(value, true);
    }

    public char read(String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        return value;
    }

    public char define(char value) {
        this.value = value;
        this.defined = true;
        return value;
    }

    public char assign(char value, String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
        this.value = value;
        return value;
    }
}
