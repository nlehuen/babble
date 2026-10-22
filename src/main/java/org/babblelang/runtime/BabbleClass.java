package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Namespace;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The value of a {@code class} expression, or the anonymous class of an {@code object}
 * literal. Calling it constructs an instance.
 */
public final class BabbleClass extends BabbleType implements Callable {
    private final Namespace closure;
    private final Object[] captured;
    private final ClassBody body;
    private final InstanceFactory factory;
    private final Object[] interfaces;
    private final Map<String, BabbleFunction> defaults = new LinkedHashMap<>();
    private final Class<?>[] javaInterfaces;
    private final Map<String, List<Method>> javaDefaults = new ConcurrentHashMap<>();

    /**
     * @param closure    the namespace the class expression was evaluated in
     * @param interfaces the implemented interfaces : BabbleInterface values or Java interface classes
     */
    public BabbleClass(ClassShape shape, Namespace closure, ClassBody body, Object[] interfaces) {
        this(shape, closure, Arguments.NONE, body, interfaces);
    }

    /**
     * @param captured the cells of the outer variables the class body and its methods use
     *                 (compiled code) : stored once per class value, not per instance
     */
    public BabbleClass(ClassShape shape, Namespace closure, Object[] captured, ClassBody body, Object[] interfaces) {
        this(shape, closure, captured, body, interfaces, BabbleInstance::new);
    }

    /**
     * @param factory creates the instances : the class the compiler generated for the
     *                class expression, when it did
     */
    public BabbleClass(ClassShape shape, Namespace closure, Object[] captured, ClassBody body, Object[] interfaces, InstanceFactory factory) {
        super(shape);
        this.closure = closure;
        this.captured = captured;
        this.body = body;
        this.factory = factory;
        this.interfaces = interfaces;

        Map<String, BabbleFunction> inherited = new LinkedHashMap<>();
        Set<Class<?>> java = new LinkedHashSet<>();
        for (Object implemented : interfaces) {
            if (implemented instanceof BabbleInterface babble) {
                babble.collectDefaults(inherited);
                babble.collectJavaInterfaces(java);
            } else {
                java.add((Class<?>) implemented);
            }
        }
        inherited.forEach((name, function) -> {
            if (shape.memberIndex(name) < 0) {
                defaults.put(name, function);
            }
        });
        this.javaInterfaces = java.toArray(new Class<?>[0]);
    }

    public Namespace closure() {
        return closure;
    }

    public Object[] captured() {
        return captured;
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (shape.kind() == ClassShape.Kind.OBJECT) {
            throw new BabbleException("object literals cannot be constructed again");
        }
        return construct(rt, args, names, site);
    }

    public BabbleInstance construct(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        BabbleInstance instance = factory.create(this, rt);
        body.construct(this, instance, rt, args, names, site);
        return instance;
    }

    /** Creates the single instance of an object literal. */
    public static BabbleInstance object(ClassShape shape, Namespace closure, ClassBody body, Object[] interfaces, ScriptRuntime rt) {
        return object(shape, closure, Arguments.NONE, body, interfaces, rt);
    }

    public static BabbleInstance object(ClassShape shape, Namespace closure, Object[] captured, ClassBody body, Object[] interfaces, ScriptRuntime rt) {
        return new BabbleClass(shape, closure, captured, body, interfaces).construct(rt, Arguments.NONE, null, null);
    }

    public static BabbleInstance object(ClassShape shape, Namespace closure, Object[] captured, ClassBody body, Object[] interfaces,
                                        InstanceFactory factory, ScriptRuntime rt) {
        return new BabbleClass(shape, closure, captured, body, interfaces, factory).construct(rt, Arguments.NONE, null, null);
    }

    /** The default method an implemented interface provides for a name the class doesn't define. */
    public BabbleFunction defaultMethod(String name) {
        return defaults.get(name);
    }

    /** The Java default methods of that name, inherited from the implemented Java interfaces. */
    List<Method> javaDefaultMethods(String name) {
        return javaDefaults.computeIfAbsent(name, n -> {
            List<Method> result = new ArrayList<>();
            for (Class<?> java : javaInterfaces) {
                for (Method method : java.getMethods()) {
                    if (method.isDefault() && method.getName().equals(n)) {
                        result.add(method);
                    }
                }
            }
            return result;
        });
    }

    /** Every Java interface the class implements, directly or through a Babble interface. */
    public Class<?>[] javaInterfaces() {
        return javaInterfaces;
    }

    /** Whether instances can be passed where Java expects {@code type}. */
    public boolean isJava(Class<?> type) {
        if (type == Object.class) {
            return true;
        }
        for (Class<?> java : javaInterfaces) {
            if (type.isAssignableFrom(java)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the class implements {@code type}, a Babble interface or a Java one. */
    public boolean implementsType(Object type) {
        if (type instanceof Class<?> java) {
            return isJava(java);
        }
        for (Object implemented : interfaces) {
            if (implemented == type || implemented instanceof BabbleInterface babble && babble.extendsType(type)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "class " + name();
    }
}
