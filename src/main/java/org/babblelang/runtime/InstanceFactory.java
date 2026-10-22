package org.babblelang.runtime;

/**
 * Creates the instances of a class : the array-backed {@link BabbleInstance}, or the
 * JVM class the compiler generated for the class expression.
 */
@FunctionalInterface
public interface InstanceFactory {
    BabbleInstance create(BabbleClass babbleClass, ScriptRuntime rt);
}
