package org.babblelang.engine.impl.natives;

import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;

/**
 * {@code discard(x)} : evaluates its argument and returns {@code null} ({@code void}) (BIP007-D8).
 */
public class DiscardFunction implements Callable {
    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (args.length != 1) {
            throw new BabbleException("discard() takes 1 argument, " + args.length + " given");
        }
        return null;
    }
}
