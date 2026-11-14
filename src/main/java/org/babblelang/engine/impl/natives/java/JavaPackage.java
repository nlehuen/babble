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

    public boolean isDeclared(String key) {
        return get(key) != null;
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
