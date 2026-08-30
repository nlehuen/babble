package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Scope;
import org.babblelang.engine.impl.Slot;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class JavaPackage implements Scope<Scope> {
    private static final ClassLoader LOADER = JavaPackage.class.getClassLoader();

    private final ImportFunction importer;
    private final String name;
    private final Map<String, Slot<Scope>> locals = new HashMap<>();

    JavaPackage(ImportFunction importer, String name) {
        this.importer = importer;
        this.name = name;
    }

    /**
     * Whether this package has a member of that name : a class, or a sub-package that
     * really exists. Answering with get() instead would always say yes, since an
     * unknown name is turned into a fresh sub-package rather than reported as missing.
     */
    public boolean isDeclared(String key) {
        if (locals.containsKey(key)) {
            return true;
        }
        String member = name + '.' + key;
        return loadClass(member) != null || isPackage(member);
    }

    public Slot<Scope> define(String key, boolean isFinal) {
        throw new BabbleException("Cannot define anything in a Java package");
    }

    public Slot<Scope> get(String key) {
        return locals.computeIfAbsent(key, k -> {
            Slot<Scope> result = new Slot<>(k, true);
            String member = name + '.' + k;
            Class<?> clazz = loadClass(member);
            // An unknown name is assumed to be a package : "java.util" has to resolve
            // before "java.util.ArrayList" can, and only the last segment is a class.
            result.set(clazz != null ? new JavaClass(clazz) : importer.getPackage(member));
            return result;
        });
    }

    /**
     * The named class, or null when there is none. Loading without initialising keeps
     * a mere lookup from running the class's static initialisers.
     */
    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name, false, LOADER);
        } catch (ClassNotFoundException cnfe) {
            return null;
        }
    }

    /**
     * Whether the name designates a package that exists. Packages cannot be enumerated
     * in general, so this looks in the two places Babble imports from : the modules of
     * the boot layer, which cover the JDK, and the class path, where a package is a
     * directory, either a real one or an entry in a jar.
     * <p>
     * A package that is in neither — one living only in a jar built without directory
     * entries, say — is reported as undeclared even though get() still resolves it.
     */
    private static boolean isPackage(String name) {
        String prefix = name + '.';
        for (Module module : ModuleLayer.boot().modules()) {
            for (String modulePackage : module.getPackages()) {
                // Intermediate packages have no entry of their own : "java" exists
                // because "java.lang" does.
                if (modulePackage.equals(name) || modulePackage.startsWith(prefix)) {
                    return true;
                }
            }
        }
        try {
            return LOADER.getResources(name.replace('.', '/')).hasMoreElements();
        } catch (IOException ioe) {
            return false;
        }
    }
}
