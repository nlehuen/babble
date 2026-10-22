package org.babblelang.runtime;

/**
 * The code of a method of a compiled class : a JVM instance method of the class, adapted
 * to this interface with LambdaMetafactory. The receiver comes first.
 */
@FunctionalInterface
public interface MethodBody {
    Object invoke(Object thiz, CompiledMethod self, ScriptRuntime rt, Object[] args, String[] names, CallSite site);
}
