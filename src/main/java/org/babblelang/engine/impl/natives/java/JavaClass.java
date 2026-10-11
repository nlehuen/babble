package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Scope;
import org.babblelang.engine.impl.Slot;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

class JavaClass implements Scope<Callable>, Callable {
    private final Class<?> clazz;
    private final Map<String, Slot<Callable>> members = new HashMap<>();

    JavaClass(Class<?> clazz) {
        this.clazz = clazz;
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        Constructor<?> constructor = Overloads.resolveConstructor(clazz, args);
        try {
            return new JavaObject(this, constructor.newInstance(args));
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
        return members.computeIfAbsent(key, k -> {
            Slot<Callable> result = new Slot<>(k, true);

            for (Class<?> memberClass : clazz.getClasses()) {
                if (memberClass.getSimpleName().equals(k) && Modifier.isPublic(memberClass.getModifiers())) {
                    result.set(new JavaClass(memberClass));
                    break;
                }
            }

            if (!result.isSet()) {
                for (Method method : clazz.getMethods()) {
                    if (method.getName().equals(k) && Modifier.isPublic(method.getModifiers())) {
                        result.set(new JavaMethod(clazz, k));
                        break;
                    }
                }
            }

            if (!result.isSet()) {
                throw new BabbleException("No such name in " + clazz.getCanonicalName() + " : " + k);
            }

            return result;
        });
    }
}
