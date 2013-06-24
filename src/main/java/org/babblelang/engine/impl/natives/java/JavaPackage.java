package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.impl.Resolver;

import java.util.HashMap;
import java.util.Map;

public class JavaPackage implements Resolver {
    private final ImportFunction importer;
    private final String name;
    private final Package _package;
    private Map<String, Object> children = new HashMap<String, Object>();

    public JavaPackage(ImportFunction importer, String name) {
        this.importer = importer;
        this.name = name;
        _package = Package.getPackage(name);
    }

    public boolean isDeclared(String key) {
        return Package.getPackage(name + '.' + key) != null;
    }

    public Object get(String key) {
        Object result = children.get(key);

        if (result == null) {
            String name2 = name + '.' + key;

            if (Package.getPackage(name2) != null) {
                result = importer.getPackage(name2);
            } else {
                try {
                    result = new JavaClass(Class.forName(name2));
                } catch (ClassNotFoundException cnfe) {
                    throw new RuntimeException(cnfe);
                }
            }

            children.put(key, result);
        }

        return result;
    }
}
