package org.babblelang.runtime;

/**
 * The code of a compiled function literal : a static method of the generated script
 * class, adapted to this interface with LambdaMetafactory.
 */
@FunctionalInterface
public interface FunctionBody {
    Object invoke(CompiledFunction self, Object thiz, ScriptRuntime rt, Object[] args, String[] names, CallSite site);
}
