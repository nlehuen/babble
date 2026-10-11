package org.babblelang.runtime;

/**
 * The Babble value of a Java class, as {@code java.util.ArrayList} evaluates to.
 */
public interface JavaType {
    Class<?> javaClass();
}
