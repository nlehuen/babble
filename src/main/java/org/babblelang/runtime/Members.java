package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Scope;

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
     * Only objects, class and interface values, namespaces and Java scopes have members.
     * Anything else is a language-level error, not a ClassCastException escaping the
     * runtime.
     */
    public static Object select(Object target, String name, String targetText) {
        if (target instanceof HasMembers members) {
            return members.getMember(name, false);
        }
        if (!(target instanceof Scope<?> scope)) {
            throw new BabbleException("Not a scope : " + targetText);
        }
        return scope.get(name).get();
    }

    /** {@code this.name} */
    public static Object selectOwn(Object thiz, String name) {
        if (thiz instanceof HasMembers members) {
            return members.getMember(name, true);
        }
        return select(thiz, name, "this");
    }

    /**
     * Checks the target of a member assignment. This runs before the assigned value is
     * evaluated, as the target comes first in the source.
     */
    public static Object assignable(Object target, String targetText) {
        if (!(target instanceof HasMembers) && !(target instanceof Scope<?>)) {
            throw new BabbleException("Not an assignable scope : " + targetText);
        }
        return target;
    }

    @SuppressWarnings("unchecked")
    public static Object assign(Object target, Object value, String name) {
        if (target instanceof HasMembers members) {
            members.setMember(name, value, false);
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
        return assign(assignable(thiz, "this"), value, name);
    }

    static BabbleException noSuchMember(String name) {
        return new BabbleException("No such member : " + name);
    }

    static BabbleException isPrivate(String name) {
        return new BabbleException(name + " is private");
    }

    static BabbleException usedBeforeDefinition(String name) {
        return new BabbleException("Member used before definition : " + name);
    }

    static BabbleException cannotAssignMethod(String name) {
        return new BabbleException("Cannot assign method : " + name);
    }

    static BabbleException readOnly(String name) {
        return new BabbleException("Value members are read-only : " + name);
    }
}
