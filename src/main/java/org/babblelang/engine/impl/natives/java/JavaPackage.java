package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Scope;
import org.babblelang.engine.impl.Slot;

import java.util.HashMap;
import java.util.Map;

public class JavaPackage implements Scope<Scope> {
    private final ImportFunction importer;
    private final String name;
    private final Map<String, Slot<Scope>> locals = new HashMap<String, Slot<Scope>>();

    JavaPackage(ImportFunction importer, String name) {
        this.importer = importer;
        this.name = name;
    }

    public boolean isDeclared(String key) {
        return get(key) != null;
    }

    public Slot<Scope> define(String key, boolean isFinal) {
        throw new BabbleException("Cannot define anything in a Java package");
    }

    public Slot<Scope> get(String key) {
        Slot<Scope> result = locals.get(key);

        if (result == null) {
            result = new Slot<Scope>(key, true);
            locals.put(name, result);
            String name2 = name + '.' + key;
            Scope value;
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
