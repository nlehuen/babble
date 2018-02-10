package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.*;
import org.babblelang.parser.BabbleParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

class JavaClass implements Scope<Callable>, Callable<JavaObject> {
    private final Class clazz;
    private final Map<String, Slot<Callable>> members = new HashMap<>();

    JavaClass(Class clazz) {
        this.clazz = clazz;
    }

    public Namespace bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace namespace = parent.enter(null);
        try {
            //noinspection unchecked
            Constructor<Object> constructor = clazz.getConstructor(parameters.typesArray());
            namespace.define("constructor", true).set(constructor);
            namespace.define("parameters", true).set(parameters);
            return namespace;
        } catch (NoSuchMethodException nsme) {
            throw new BabbleException(nsme);
        }
    }

    public JavaObject call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope scope) {
        Constructor constructor = (Constructor) scope.get("constructor").get();
        Parameters parameters = (Parameters) scope.get("parameters").get();
        try {
            return new JavaObject(this, constructor.newInstance(parameters.valuesArray()));
        } catch (Exception e) {
            throw new BabbleException(e);
        }
    }

    public Slot<Callable> define(String key, boolean isFinal) {
        throw new BabbleException("Cannot define anything in a Java class");
    }

    public boolean isDeclared(String key) {
        return get(key) != null;
    }

    public Slot<Callable> get(String key) {
        Slot<Callable> result = members.get(key);

        if (result == null) {
            result = new Slot<>(key, true);
            members.put(key, result);

            for (Class memberClass : clazz.getClasses()) {
                if (memberClass.getSimpleName().equals(key) && Modifier.isPublic(memberClass.getModifiers())) {
                    result.set(new JavaClass(memberClass));
                    break;
                }
            }

            if (!result.isSet()) {
                for (Method method : clazz.getMethods()) {
                    if (method.getName().equals(key) && Modifier.isPublic(method.getModifiers())) {
                        result.set(new JavaMethod(clazz, key));
                        break;
                    }
                }
            }

            if (!result.isSet()) {
                throw new BabbleException("No such name in " + clazz.getCanonicalName() + " : " + key);
            }
        }

        return result;
    }
}
