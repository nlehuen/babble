package org.babblelang.compiler;

import java.util.Map;

/**
 * Loads the classes of one compiled script : the script class, and the class of each
 * class or object expression. There is one loader per compilation, so they can be
 * unloaded once their CompiledScript is unreachable. Classes are defined on first use,
 * when the JVM resolves a reference to them.
 */
final class ScriptClassLoader extends ClassLoader {
    static {
        registerAsParallelCapable();
    }

    private final Map<String, byte[]> classes;

    /**
     * @param classes the class files, by binary name
     */
    ScriptClassLoader(ClassLoader parent, Map<String, byte[]> classes) {
        super(parent);
        this.classes = classes;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classes.get(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }
}
