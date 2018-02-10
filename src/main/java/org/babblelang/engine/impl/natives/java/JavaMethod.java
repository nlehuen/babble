package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Namespace;
import org.babblelang.engine.impl.Scope;
import org.babblelang.parser.BabbleParser;

import java.lang.reflect.Method;

class JavaMethod implements Callable {
    private final Class clazz;
    private final String name;

    JavaMethod(Class clazz, String name) {
        this.clazz = clazz;
        this.name = name;
    }

    public Namespace bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace namespace = parent.enter(null);

        try {
            //noinspection unchecked
            Method method = clazz.getMethod(name, parameters.typesArray());
            namespace.define("method", true).set(method);
            namespace.define("parameters", true).set(parameters);
            namespace.define("this", false).set(clazz);
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
