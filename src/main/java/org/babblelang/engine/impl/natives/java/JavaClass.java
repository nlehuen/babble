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
    private final Class<?> clazz;
    private final Map<String, Slot<Callable>> members = new HashMap<>();

    JavaClass(Class<?> clazz) {
        this.clazz = clazz;
    }

    public Namespace bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace namespace = parent.enter(null);
        Constructor<?> constructor = Overloads.resolveConstructor(clazz, parameters.valuesArray());
        namespace.define("constructor", true).set(constructor);
        namespace.define("parameters", true).set(parameters);
        return namespace;
    }

    public JavaObject call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope<Object> scope) {
        Constructor<?> constructor = (Constructor<?>) scope.get("constructor").get();
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
        return members.containsKey(key) || resolve(key) != null;
    }

    public Slot<Callable> get(String key) {
        return members.computeIfAbsent(key, k -> {
            Callable member = resolve(k);
            if (member == null) {
                throw new BabbleException("No such name in " + clazz.getCanonicalName() + " : " + k);
            }
            Slot<Callable> result = new Slot<>(k, true);
            result.set(member);
            return result;
        });
    }

    /**
     * The member named "key", or null when the class has none. Returning null rather than
     * throwing is what lets isDeclared() answer the question it is asked : the missing
     * member is the expected case there, not a failure.
     */
    private Callable resolve(String key) {
        for (Class<?> memberClass : clazz.getClasses()) {
            if (memberClass.getSimpleName().equals(key) && Modifier.isPublic(memberClass.getModifiers())) {
                return new JavaClass(memberClass);
            }
        }
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(key) && Modifier.isPublic(method.getModifiers())) {
                return new JavaMethod(clazz, key);
            }
        }
        return null;
    }
}
