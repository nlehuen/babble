package org.babblelang.engine.impl.natives;

import org.babblelang.engine.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;

/**
 * {@code same(a, b)} : identity, except for numbers, booleans and strings, for which it
 * is {@code ==}.
 */
public class SameFunction implements Callable {
    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (args.length != 2) {
            throw new BabbleException("same() takes 2 arguments, " + args.length + " given");
        }
        return Values.same(args[0], args[1]);
    }
}
