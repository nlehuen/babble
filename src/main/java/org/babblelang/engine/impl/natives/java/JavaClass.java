package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.*;
import org.babblelang.parser.BabbleParser;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

public class JavaClass implements Scope<Callable>, Callable<JavaObject> { // Changed to public
    private final Class clazz;
    private final Map<String, Slot<Callable>> members = new HashMap<>();

    public JavaClass(Class clazz) { // Changed to public
        this.clazz = clazz;
    }

    public Class<?> getWrappedClass() { // Added for testing
        return clazz;
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
        try {
            return get(key) != null; // If get() doesn't throw and returns non-null slot.
                                     // The slot itself might contain null if that's how it's set,
                                     // but get() would throw for non-existent names.
        } catch (BabbleException e) {
            return false; // If get() throws (e.g. "No such name"), it's not declared.
        }
    }

    public Slot<Callable> get(String key) {
        return members.computeIfAbsent(key, k -> {
            Slot<Callable> result = new Slot<>(k, true);

            // Check for public inner classes
            for (Class<?> memberClass : clazz.getClasses()) {
                if (memberClass.getSimpleName().equals(k) && Modifier.isPublic(memberClass.getModifiers())) {
                    result.set(new JavaClass(memberClass));
                    return result; // Return as soon as found
                }
            }

            // Check for public methods if no inner class was found
            for (Method method : clazz.getMethods()) {
                if (method.getName().equals(k) && Modifier.isPublic(method.getModifiers())) {
                    result.set(new JavaMethod(clazz, k));
                    return result; // Return as soon as found
                }
            }

            // If neither an inner class nor a method was found
            throw new BabbleException("No such name in " + clazz.getCanonicalName() + " : " + k);
        });
    }
}
