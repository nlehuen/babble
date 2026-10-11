package org.babblelang.runtime;

/**
 * A Babble-side wrapper of a Java object, unwrapped whenever the value goes to Java.
 */
public interface JavaValue {
    Object javaValue();
}
