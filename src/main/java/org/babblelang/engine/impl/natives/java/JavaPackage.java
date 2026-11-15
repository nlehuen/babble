package org.babblelang.engine.impl.natives.java;

import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.Scope;
import org.babblelang.runtime.Slot;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class JavaPackage implements Scope<Scope> {
    private final ImportFunction importer;
    private final String name;
    private final Map<String, Slot<Scope>> locals = new ConcurrentHashMap<>();

    JavaPackage(ImportFunction importer, String name) {
        this.importer = importer;
        this.name = name;
    }

    /**
     * Whether {@code key} names a class or a package that exists, without loading or
     * initialising anything. Unlike {@link #get}, which takes any other name for a package
     * (it can't tell one that has no classes yet from one that doesn't exist), this answers
     * false for a name that is neither : the JDK's packages come from the boot layer's
     * modules, the others from the class path, where a package is a directory (a jar built
     * without directory entries has packages this can't see).
     */
    public boolean isDeclared(String key) {
        Slot<Scope> cached = locals.get(key);
        if (cached != null && cached.get() instanceof JavaClass) {
            return true;
        }
        String qualified = name + '.' + key;
        ClassLoader loader = JavaPackage.class.getClassLoader();
        try {
            Class.forName(qualified, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            // Not a class : maybe a package.
        }
        String prefix = qualified + '.';
        for (Module module : ModuleLayer.boot().modules()) {
            for (String p : module.getPackages()) {
                if (p.equals(qualified) || p.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return loader != null && loader.getResource(qualified.replace('.', '/') + '/') != null;
    }

    public Slot<Scope> define(String key, boolean isFinal) {
        throw new BabbleException("Cannot define anything in a Java package");
    }

    @Override
    public String toString() {
        return "package " + name;
    }

    public Slot<Scope> get(String key) {
        return locals.computeIfAbsent(key, k -> {
            Slot<Scope> result = new Slot<>(k, true);
            String name2 = name + '.' + k;
            Scope value;
            try {
                value = JavaClass.of(Class.forName(name2));
            } catch (ClassNotFoundException cnfe) {
                value = importer.getPackage(name2);
            }
            result.set(value);
            return result;
        });
    }
}
