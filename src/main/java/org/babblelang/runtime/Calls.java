package org.babblelang.runtime;

import org.babblelang.engine.BabbleException;

public final class Calls {
    private Calls() {
    }

    /**
     * Checks that a callee can be called. This runs before the arguments are evaluated,
     * so a call to something that is not callable fails without their side effects.
     */
    public static Callable callable(Object callee, CallSite site) {
        if (callee instanceof Callable callable) {
            return callable;
        }
        throw new BabbleException(site.calleeText() + " is not callable");
    }
}
