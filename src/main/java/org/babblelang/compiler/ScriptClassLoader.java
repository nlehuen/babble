package org.babblelang.compiler;

/**
 * Loads the class of one compiled script. There is one loader per compilation, so the
 * class can be unloaded once its CompiledScript is unreachable.
 */
final class ScriptClassLoader extends ClassLoader {
    static {
        registerAsParallelCapable();
    }

    ScriptClassLoader(ClassLoader parent) {
        super(parent);
    }

    Class<?> define(String binaryName, byte[] bytes) {
        return defineClass(binaryName, bytes, 0, bytes.length);
    }
}
