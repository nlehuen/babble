package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Resolver;
import org.babblelang.engine.impl.Scope;
import org.babblelang.parser.BabbleParser;

import java.lang.reflect.Method;

class JavaMethod implements Callable {
    private final Class _class;
    private final String name;

    public JavaMethod(Class _class, String name) {
        this._class = _class;
        this.name = name;
    }

    public Scope bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Scope parent, Parameters parameters) {
        Scope scope = parent.enter(null);

        try {
            Method method = _class.getMethod(name, parameters.typesArray());
            scope.define("method", method);
            scope.define("parameters", parameters);
        } catch (NoSuchMethodException nsme) {
            throw new IllegalArgumentException(nsme);
        }

        return scope;
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Resolver resolver) {
        Method method = (Method) resolver.get("method");
        Parameters parameters = (Parameters) resolver.get("parameters");
        try {
            return method.invoke(null, parameters.valuesArray());
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
