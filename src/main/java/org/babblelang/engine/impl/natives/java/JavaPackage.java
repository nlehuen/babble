package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.impl.Resolver;
import org.babblelang.engine.impl.Slot;

import java.util.HashMap;
import java.util.Map;

public class JavaPackage implements Resolver {
    private final ImportFunction importer;
    private final String name;
    private final Map<String, Slot> locals;

    public JavaPackage(ImportFunction importer, String name) {
        this.importer = importer;
        this.name = name;
        locals = new HashMap<String, Slot>();
    }

    public boolean isDeclared(String key) {
        return get(key) != null;
    }

    public Slot define(String key, boolean _final) {
        throw new IllegalStateException("Cannot define anything in a Java package");
    }

    public Slot get(String key) {
        Slot result = locals.get(key);

        if (result == null) {
            result = new Slot(key, true);
            locals.put(name, result);

            String name2 = name + '.' + key;
            Object value;

            try {
                value = new JavaClass(Class.forName(name2));
            } catch (ClassNotFoundException cnfe) {
                value = importer.getPackage(name2);
            }
            result.set(value);
        }

        return result;
    }
}
