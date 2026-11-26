package org.babblelang.compiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Parent-first class loader delegating unknown classes to an ordered list of dependency loaders
 * (such as the thread context class loader and dependency modules' {@link ScriptClassLoader}s).
 * Nested {@code CompositeClassLoader}s are flattened on construction while preserving delegation
 * order and deduplicating by identity.
 */
final class CompositeClassLoader extends ClassLoader {
    static {
        registerAsParallelCapable();
    }

    private final List<ClassLoader> delegates;

    CompositeClassLoader(ClassLoader parent, List<ClassLoader> delegates) {
        super(parent);
        List<ClassLoader> flat = new ArrayList<>();
        Set<ClassLoader> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(this);
        if (parent != null) {
            seen.add(parent);
        }
        if (delegates != null) {
            for (ClassLoader delegate : delegates) {
                addFlattened(delegate, seen, flat);
            }
        }
        this.delegates = List.copyOf(flat);
    }

    private static void addFlattened(
            ClassLoader loader, Set<ClassLoader> seen, List<ClassLoader> flat) {
        if (loader == null) {
            return;
        }
        if (loader instanceof CompositeClassLoader composite) {
            for (ClassLoader nested : composite.delegates) {
                addFlattened(nested, seen, flat);
            }
            addFlattened(composite.getParent(), seen, flat);
            return;
        }
        if (seen.add(loader)) {
            flat.add(loader);
        }
    }

    List<ClassLoader> delegates() {
        return delegates;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        for (ClassLoader delegate : delegates) {
            try {
                return delegate.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException(name);
    }
}
