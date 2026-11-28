package org.babblelang.runtime;

/**
 * Zero-coupling branchless non-null receiver checks for compiled bytecode.
 *
 * <p>Kept separate from {@link Members} so compiled member accesses on plain Babble classes do not
 * pull dynamic member/Java/array dispatch into tree-shaken AOT jars.
 */
public final class Receivers {
    private Receivers() {}

    /** Branchless receiver non-null check for compiled member reads and calls. */
    public static Object requireScope(Object target, String targetText) {
        if (target == null) {
            throw new BabbleException("Not a scope : " + targetText);
        }
        return target;
    }

    /** Branchless receiver non-null check for compiled member assignments. */
    public static Object requireAssignable(Object target, String targetText) {
        if (target == null) {
            throw new BabbleException("Not an assignable scope : " + targetText);
        }
        return target;
    }
}
