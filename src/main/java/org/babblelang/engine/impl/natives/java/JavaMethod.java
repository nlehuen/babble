package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.*;
import org.babblelang.parser.BabbleParser;

import java.lang.reflect.Method;

public class JavaMethod implements Callable { // Changed to public
    private final Class clazz;
    private final String name;

    public JavaMethod(Class clazz, String name) { // Changed to public
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
