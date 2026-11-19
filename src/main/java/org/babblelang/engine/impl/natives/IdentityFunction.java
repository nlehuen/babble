package org.babblelang.engine.impl.natives;

import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;

/**
 * Zero-cost identity built-in used for {@code move(x)} and {@code freeze(x)} (BIP007-P2).
 */
public class IdentityFunction implements Callable {
    private final String name;

    public IdentityFunction(String name) {
        this.name = name;
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (args.length != 1) {
            throw new BabbleException(name + "() takes 1 argument, " + args.length + " given");
        }
        return args[0];
    }
}
