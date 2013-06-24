package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Resolver;
import org.babblelang.engine.impl.Scope;
import org.babblelang.parser.BabbleParser;

import java.util.HashMap;
import java.util.Map;

public class ImportFunction implements Callable {
    private final Map<String, JavaPackage> packages = new HashMap<String, JavaPackage>();

    public Scope bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Scope parent, Parameters parameters) {
        Scope scope = parent.enter(null);
        Object first = parameters.values().iterator().next();
        scope.define("name", first);
        return scope;
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Resolver resolver) {
        String name = (String) resolver.get("name");
        return getPackage(name);
    }

    public JavaPackage getPackage(String name) {
        JavaPackage result = packages.get(name);
        if (result == null) {
            result = new JavaPackage(this, name);
            packages.put(name, result);
        }
        return result;
    }
}
