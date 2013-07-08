package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Namespace;
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

    public Namespace bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace namespace = parent.enter(null);

        try {
            Method method = _class.getMethod(name, parameters.typesArray());
            namespace.define("method", true).set(method);
            namespace.define("parameters", true).set(parameters);
            namespace.define("this", false).set(_class);
        } catch (NoSuchMethodException nsme) {
            throw new BabbleException(nsme);
        }

        return namespace;
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope scope) {
        Method method = (Method) scope.get("method").get();
        Parameters parameters = (Parameters) scope.get("parameters").get();
        try {
            return method.invoke(scope.get("this").get(), parameters.valuesArray());
        } catch (Exception e) {
            throw new BabbleException(e);
        }
    }
}
