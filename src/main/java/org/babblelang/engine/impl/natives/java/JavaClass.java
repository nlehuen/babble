package org.babblelang.engine.impl.natives.java;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Scope;
import org.babblelang.engine.impl.Slot;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.JavaType;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Overloads;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JavaClass implements Scope<Callable>, Callable, JavaType {
    private static final Map<Class<?>, JavaClass> CLASSES = new ConcurrentHashMap<>();

    private final Class<?> clazz;
    private final Map<String, Slot<Callable>> members = new HashMap<>();

    JavaClass(Class<?> clazz) {
        this.clazz = clazz;
    }

    /** The Babble value of a Java class, as {@code classof} returns it. */
    public static JavaClass of(Class<?> clazz) {
        return CLASSES.computeIfAbsent(clazz, JavaClass::new);
    }

    @Override
    public Class<?> javaClass() {
        return clazz;
    }

    /**
     * Constructs an instance. Calling a functional interface with one Babble function is
     * an explicit conversion : {@code java.util.concurrent.Callable(() -> ( 1 ))}.
     */
    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (clazz.isInterface() && args.length == 1 && Values.isBabbleCallable(args[0]) && JavaTypes.isFunctionalInterface(clazz)) {
            return Values.toJava(args[0], clazz, rt);
        }
        Constructor<?> constructor = Overloads.resolveConstructor(clazz, args);
        Object[] java = Values.toJava(args, constructor.getParameterTypes(), rt);
        try {
            return new JavaObject(this, constructor.newInstance(java));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof BabbleException babble) {
                throw babble;
            }
            throw new BabbleException(e);
        } catch (Exception e) {
            throw new BabbleException(e);
        }
    }

    /** Values of the same Java class are equal, whichever path produced them. */
    @Override
    public boolean equals(Object o) {
        return o instanceof JavaClass other && other.clazz == clazz;
    }

    @Override
    public int hashCode() {
        return clazz.hashCode();
    }

    @Override
    public String toString() {
        return clazz.getName();
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
                    result.set(JavaClass.of(memberClass));
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
