package org.babblelang.engine.impl.natives.java;

import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class ImportFunction implements Callable {
    private final Map<String, JavaPackage> packages = new ConcurrentHashMap<>();

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        return getPackage((String) args[0]);
    }

    public JavaPackage getPackage(String name) {
        return packages.computeIfAbsent(name, n -> new JavaPackage(this, n));
    }
}
