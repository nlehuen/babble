package org.babblelang.runtime;

/**
 * Sentinel values that never escape into Babble code.
 */
public enum Undefined {
    /** The receiver of a function that was not called as a method. */
    UNBOUND
}
