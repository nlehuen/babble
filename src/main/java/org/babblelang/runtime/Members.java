package org.babblelang.runtime;

import org.babblelang.engine.impl.natives.java.JavaClass;
import org.babblelang.engine.impl.natives.java.JavaObject;

/**
 * Member selection, {@code target.name}, and member assignment, {@code target.name = v}.
 * <p>
 * {@code this.name} takes a separate path, {@link #selectOwn} and {@link #assignOwn},
 * which is the only one that may reach a private member.
 */
public final class Members {
    private Members() {
    }

    /**
     * Objects, class and interface values, namespaces, Java scopes and Java objects have
     * members. Babble's own values (null, numbers, booleans, strings, functions) have none :
     * selecting on them is a language-level error, not a ClassCastException escaping the
     * runtime.
     */
    public static Object select(Object target, String name, String targetText) {
        if (target instanceof HasMembers members) {
            return members.getMember(name, false);
        }
        if (BabbleObjects.isCompiledInstance(target)) {
            return BabbleObjects.getMember(target, name, false);
        }
        if (target instanceof Scope<?> scope) {
            return scope.get(name).get();
        }
        Object unwrapped = Values.unwrap(target);
        if (unwrapped != null && unwrapped.getClass().isArray()) {
            return ArraySupport.member(unwrapped, name);
        }
        if (isJavaObject(target)) {
            // As Java calls return them : not wrapped, but their methods are as callable.
            return JavaObject.member(target, name);
        }
        throw new BabbleException("Not a scope : " + targetText);
    }

    /**
     * A Java object with Java members (including {@code String}), as opposed to Babble's
     * memberless values: null, the 8 primitive scalars, and callables (BIP011-D5).
     */
    private static boolean isJavaObject(Object target) {
        return target != null
                && !(target instanceof Byte) && !(target instanceof Short)
                && !(target instanceof Integer) && !(target instanceof Long)
                && !(target instanceof Float) && !(target instanceof Double)
                && !(target instanceof Boolean) && !(target instanceof Character)
                && !(target instanceof Callable);
    }

    /** {@code this.name} */
    public static Object selectOwn(Object thiz, String name) {
        if (thiz instanceof HasMembers members) {
            return members.getMember(name, true);
        }
        if (BabbleObjects.isCompiledInstance(thiz)) {
            return BabbleObjects.getMember(thiz, name, true);
        }
        return select(thiz, name, "this");
    }

    /**
     * Checks the target of a member assignment. This runs before the assigned value is
     * evaluated, as the target comes first in the source.
     */
    public static Object assignable(Object target, String targetText) {
        if (!(target instanceof HasMembers) && !BabbleObjects.isCompiledInstance(target)
                && !(target instanceof Scope<?>) && !isJavaObject(target)) {
            throw new BabbleException("Not an assignable scope : " + targetText);
        }
        return target;
    }

    @SuppressWarnings("unchecked")
    public static Object assign(Object target, Object value, String name) {
        if (target instanceof HasMembers members) {
            members.setMember(name, value, false);
        } else if (BabbleObjects.isCompiledInstance(target)) {
            BabbleObjects.setMember(target, name, value, false);
        } else if (target instanceof JavaClass || target instanceof JavaValue || isJavaObject(target)) {
            // A Java field : a class's static one, or an object's.
            JavaObject.assign(target, name, value);
        } else {
            ((Scope<Object>) target).get(name).assign(value);
        }
        return value;
    }

    /** {@code this.name = value} */
    public static Object assignOwn(Object thiz, Object value, String name) {
        if (thiz instanceof HasMembers members) {
            members.setMember(name, value, true);
            return value;
        }
        if (BabbleObjects.isCompiledInstance(thiz)) {
            BabbleObjects.setMember(thiz, name, value, true);
            return value;
        }
        return assign(assignable(thiz, "this"), value, name);
    }

    public static BabbleException notAScope(String targetText) {
        return new BabbleException("Not a scope : " + targetText);
    }

    public static BabbleException notAssignable(String targetText) {
        return new BabbleException("Not an assignable scope : " + targetText);
    }

    static BabbleException noSuchMember(String name) {
        return new BabbleException("No such member : " + name);
    }

    static BabbleException isPrivate(String name) {
        return new BabbleException(name + " is private");
    }

    public static BabbleException usedBeforeDefinition(String name) {
        return new BabbleException("Member used before definition : " + name);
    }

    static BabbleException cannotAssignMethod(String name) {
        return new BabbleException("Cannot assign method : " + name);
    }

    static BabbleException readOnly(String name) {
        return new BabbleException("Value members are read-only : " + name);
    }
}
