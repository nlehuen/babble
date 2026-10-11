package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;
import org.babblelang.engine.impl.Scope;

/**
 * Member selection, {@code target.name}, and member assignment, {@code target.name = v}.
 */
public final class Members {
    private Members() {
    }

    /**
     * Only namespaces, objects and Java scopes have members. Anything else is a
     * language-level error, not a ClassCastException escaping the runtime.
     */
    public static Object select(Object target, String name, String targetText) {
        if (!(target instanceof Scope<?> scope)) {
            throw new BabbleException("Not a scope : " + targetText);
        }
        return scope.get(name).get();
    }

    /**
     * Checks the target of a member assignment. This runs before the assigned value is
     * evaluated, as the target comes first in the source.
     */
    public static Scope<?> assignable(Object target, String targetText) {
        if (!(target instanceof Scope<?> scope)) {
            throw new BabbleException("Not an assignable scope : " + targetText);
        }
        return scope;
    }

    @SuppressWarnings("unchecked")
    public static Object assign(Scope<?> scope, Object value, String name) {
        ((Scope<Object>) scope).get(name).assign(value);
        return value;
    }
}
