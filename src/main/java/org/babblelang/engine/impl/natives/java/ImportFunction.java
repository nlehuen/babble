package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * {@code import("org")} is a root Java package ; {@code import("lib/shapes.ba")}, a name
 * ending in {@code .ba}, is the module of a Babble file.
 */
public class ImportFunction implements Callable {
    private final Map<String, JavaPackage> packages = new ConcurrentHashMap<>();

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (args.length != 1 || !(args[0] instanceof String name)) {
            throw new BabbleException("import expects one name : a Java package, or a .ba file");
        }
        if (name.endsWith(BabbleScriptEngine.MODULE_EXTENSION)) {
            if (!(rt.engine() instanceof BabbleScriptEngine engine)) {
                throw new BabbleException("Cannot import " + name + " without an engine");
            }
            return engine.importModule(rt, name, site != null ? site.sourceName() : null);
        }
        return getPackage(name);
    }

    public JavaPackage getPackage(String name) {
        return packages.computeIfAbsent(name, n -> new JavaPackage(this, n));
    }
}
