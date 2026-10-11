package org.babblelang.runtime;

import java.util.Objects;

/**
 * A Babble function selected through an object, bound to that object as its receiver.
 */
public final class BoundMethod implements Callable {
    private final BabbleFunction function;
    private final Object receiver;

    public BoundMethod(BabbleFunction function, Object receiver) {
        this.function = function;
        this.receiver = receiver;
    }

    public BabbleFunction function() {
        return function;
    }

    public Object receiver() {
        return receiver;
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        return function.invoke(receiver, rt, args, names, site);
    }

    /** Two selections of the same method on the same receiver are equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof BoundMethod other && other.function == function && other.receiver == receiver;
    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(function), System.identityHashCode(receiver));
    }

    @Override
    public String toString() {
        return "bound " + function;
    }
}
