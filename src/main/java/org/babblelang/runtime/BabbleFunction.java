package org.babblelang.runtime;

/**
 * A function written in Babble, interpreted or compiled. Such a function can be called on its own,
 * or as a method through a {@link BoundMethod}, which makes the receiver available as {@code this}.
 */
public interface BabbleFunction extends Callable {
    /**
     * @param thiz the receiver, or {@link Undefined#UNBOUND} for a plain call
     */
    Object invoke(Object thiz, ScriptRuntime rt, Object[] args, String[] names, CallSite site);

    /** The number of parameters without a default value, or -1 if unknown. */
    default int minArity() {
        return -1;
    }

    /** The number of parameters, or -1 if unknown. */
    default int maxArity() {
        return -1;
    }

    /** The index of the rest parameter ({@code xs: T...}), or -1 if none. */
    default int restIndex() {
        return -1;
    }

    /**
     * Whether this Babble method can implement/override a Java {@code default} interface overload
     * with parameter types {@code javaParams} (or whether Java's {@code default} overload should be
     * kept).
     */
    default boolean acceptsJavaDefaultOverload(Class<?>[] javaParams) {
        int min = minArity();
        int max = maxArity();
        if (min >= 0 && javaParams.length < min) {
            return false;
        }
        return max < 0 || javaParams.length <= max;
    }

    @Override
    default Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        return invoke(Undefined.UNBOUND, rt, args, names, site);
    }

    /**
     * What {@code recurse} evaluates to inside a call : the function itself, or when it was called
     * as a method, the method bound to the same receiver, so that a method that recurses still sees
     * {@code this}.
     */
    default Callable recurseTarget(Object thiz) {
        return thiz == Undefined.UNBOUND ? this : new BoundMethod(this, thiz);
    }
}
