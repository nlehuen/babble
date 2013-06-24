package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Resolver;
import org.babblelang.engine.impl.Scope;
import org.babblelang.parser.BabbleParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

class JavaClass implements Resolver, Callable {
    private final Class _class;
    private Map<String, Object> members = new HashMap<String, Object>();

    JavaClass(Class _class) {
        this._class = _class;
    }

    public Scope bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Scope parent, Parameters parameters) {
        Scope scope = parent.enter(null);
        try {
            Constructor constructor = _class.getConstructor(parameters.typesArray());
            scope.define("constructor", constructor);
            scope.define("parameters", parameters);
            return scope;
        } catch (NoSuchMethodException nsme) {
            throw new IllegalArgumentException(nsme);
        }
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Resolver resolver) {
        Constructor constructor = (Constructor) resolver.get("constructor");
        Parameters parameters = (Parameters) resolver.get("parameters");
        try {
            return constructor.newInstance(parameters.valuesArray());
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public boolean isDeclared(String key) {
        return get(key) != null;
    }

    public Object get(String key) {
        Object result = members.get(key);

        if (result == null) {
            for (Class _class2 : _class.getClasses()) {
                if (Modifier.isPublic(_class2.getModifiers())) {
                    result = new JavaClass(_class2);
                    break;
                }
            }

            if (result == null) {
                for (Method method : _class.getMethods()) {
                    if (Modifier.isPublic(method.getModifiers())) {
                        result = new JavaMethod(_class, key);
                        break;
                    }
                }
            }

            if (result == null) {
                throw new IllegalArgumentException("No such key in " + _class.getCanonicalName() + " : " + key);
            }

            members.put(key, result);
        }

        return result;
    }
}
