package org.babblelang.runtime;

/**
 * The calling convention shared by the interpreter, compiled code and natives.
 */
public interface Callable {
    /**
     * @param rt    the runtime of the eval making the call
     * @param args  argument values in call order
     * @param names parallel to args : the argument's name, or null if positional.
     *              Shared and immutable per call site ; null when no argument is named.
     * @param site  static description of the call site, for error messages
     */
    Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site);
}
