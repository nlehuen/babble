package org.babblelang.runtime;


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
        return enter(closure, recurseTarget, thiz, declared, null);
    }

    public static Namespace enter(Namespace closure, Callable recurseTarget, Object thiz, String[] declared, boolean[] finals) {
        Namespace namespace = closure.enter();
        namespace.define("$recurse", true).set(recurseTarget);
        if (thiz != Undefined.UNBOUND) {
            namespace.define("this", true).set(thiz);
        }
        namespace.declare(declared, finals);
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

    /** Checks an out-of-band definition flag for an unboxed primitive variable. */
    public static void checkDefined(boolean defined, String name) {
        if (!defined) {
            throw new BabbleException("Name used before definition : " + name);
        }
    }
}
