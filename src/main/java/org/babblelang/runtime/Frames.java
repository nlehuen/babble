package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Namespace;

public final class Frames {
    private Frames() {
    }

    /**
     * The namespace of a function call, a child of its closure. It defines
     * {@code $recurse}, {@code this} when the function is called as a method, and
     * pre-declares the names the body defines (block scope hoisting). Parameters are
     * defined afterwards, while they are bound.
     */
    public static Namespace enter(Namespace closure, Callable recurseTarget, Object thiz, String[] declared) {
        Namespace namespace = closure.enter(null);
        namespace.define("$recurse", true).set(recurseTarget);
        if (thiz != Undefined.UNBOUND) {
            namespace.define("this", true).set(thiz);
        }
        namespace.declare(declared);
        return namespace;
    }

    /**
     * Reads a JVM local or a cell holding a hoisted definition : using it before its def
     * has run is an error, as reading an unset namespace slot is.
     */
    public static Object defined(Object value, String name) {
        if (value == Undefined.UNDEFINED) {
            throw new BabbleException("Name used before definition : " + name);
        }
        return value;
    }

    /** Checks that a variable can be assigned : its definition has run. */
    public static void checkAssignable(Object current, String name) {
        defined(current, name);
    }
}
