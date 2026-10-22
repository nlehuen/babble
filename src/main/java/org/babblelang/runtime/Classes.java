package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Namespace;

/**
 * What both modes call to evaluate class, object and interface expressions.
 * <p>
 * A class body runs in a namespace where {@code this} is the instance under
 * construction ; a static block in one where {@code this} is the class or interface
 * value ; an interface body in one that holds the interface value, which its defaults
 * are stored into.
 */
public final class Classes {
    private static final String INTERFACE = "$interface";

    private Classes() {
    }

    public static BabbleClass newClass(ClassShape shape, Namespace closure, ClassBody body, Object[] interfaces) {
        return new BabbleClass(shape, closure, body, interfaces);
    }

    public static BabbleClass newClass(ClassShape shape, Namespace closure, Object[] captured, ClassBody body, Object[] interfaces) {
        return new BabbleClass(shape, closure, captured, body, interfaces);
    }

    public static BabbleClass newClass(ClassShape shape, Namespace closure, Object[] captured, ClassBody body, Object[] interfaces,
                                       InstanceFactory factory) {
        return new BabbleClass(shape, closure, captured, body, interfaces, factory);
    }

    /**
     * Reads a member field of a compiled class directly ({@code this.x}) : using it before
     * its def has run is an error, as through {@link Members}.
     */
    public static Object member(Object value, String name) {
        if (value == Undefined.UNDEFINED) {
            throw Members.usedBeforeDefinition(name);
        }
        return value;
    }

    public static BabbleInterface newInterface(ClassShape shape, Object[] parents) {
        return new BabbleInterface(shape, parents);
    }

    /**
     * An entry of an implements or extends list, as the analysis resolved it : a Babble
     * interface, or a Java interface given as its class.
     */
    public static Object type(Object value, String text) {
        if (value instanceof BabbleInterface) {
            return value;
        }
        if (value instanceof JavaType java && java.javaClass().isInterface()) {
            return java.javaClass();
        }
        throw new BabbleException("Not an interface : " + text);
    }

    /** The namespace of a constructor call, a child of the class's closure. */
    public static Namespace constructorScope(BabbleClass babbleClass, BabbleInstance instance) {
        Namespace namespace = babbleClass.closure().enter(null);
        namespace.define("this", true).set(instance);
        return namespace;
    }

    public static Namespace staticScope(Namespace outer, BabbleType type) {
        Namespace namespace = outer.enter(null);
        namespace.define("this", true).set(type);
        return namespace;
    }

    public static Namespace interfaceScope(Namespace outer, BabbleInterface type) {
        Namespace namespace = outer.enter(null);
        namespace.define(INTERFACE, true).set(type);
        return namespace;
    }

    public static Object initMember(Namespace namespace, int index, Object value) {
        return ((BabbleInstance) namespace.lookup("this")).initMember(index, value);
    }

    public static Object initStatic(Namespace namespace, int index, Object value) {
        return ((BabbleType) namespace.lookup("this")).initStatic(index, value);
    }

    public static Object setDefault(Namespace namespace, int index, Object value) {
        return ((BabbleInterface) namespace.lookup(INTERFACE)).setDefault(index, value);
    }
}
