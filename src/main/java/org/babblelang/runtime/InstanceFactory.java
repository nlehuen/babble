package org.babblelang.runtime;

/**
 * Creates the instances of a class : the array-backed {@link InterpretedInstance}, or the plain JVM
 * class the compiler generated for the class expression.
 */
@FunctionalInterface
public interface InstanceFactory {
    Object create(BabbleClass babbleClass, ScriptRuntime rt);
}
