package org.babblelang.runtime;

/**
 * The constructor of a class : binds the arguments and runs the class body for a new
 * instance. Interpreted, or a static method of a compiled script class.
 */
@FunctionalInterface
public interface ClassBody {
    void construct(BabbleClass babbleClass, BabbleInstance instance, ScriptRuntime rt, Object[] args, String[] names, CallSite site);
}
