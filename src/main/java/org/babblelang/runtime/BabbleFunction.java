package org.babblelang.runtime;

/**
 * A function written in Babble, interpreted or compiled. Such a function can be called
 * on its own, or as a method through a {@link BoundMethod}, which makes the receiver
 * available as {@code this}.
 */
public interface BabbleFunction extends Callable {
    /**
     * @param thiz the receiver, or {@link Undefined#UNBOUND} for a plain call
     */
    Object invoke(Object thiz, ScriptRuntime rt, Object[] args, String[] names, CallSite site);

    @Override
    default Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        return invoke(Undefined.UNBOUND, rt, args, names, site);
    }

    /**
     * What {@code recurse} evaluates to inside a call : the function itself, or when it
     * was called as a method, the method bound to the same receiver, so that a method
     * that recurses still sees {@code this}.
     */
    default Callable recurseTarget(Object thiz) {
        return thiz == Undefined.UNBOUND ? this : new BoundMethod(this, thiz);
    }
}
