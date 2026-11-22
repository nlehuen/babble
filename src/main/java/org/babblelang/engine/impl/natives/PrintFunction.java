package org.babblelang.engine.impl.natives;

import org.babblelang.runtime.ArraySupport;
import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Values;

import java.io.IOException;

/**
 * {@code print} and {@code println} : they write their arguments to the writer of the
 * eval's ScriptContext, or to the {@code to:} argument when one is given.
 */
public class PrintFunction implements Callable {
    private final boolean newLine;

    public PrintFunction(boolean newLine) {
        this.newLine = newLine;
    }

    @Override
    public Object call(ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
        if (names == null && args.length >= 1 && args[0] instanceof org.babblelang.runtime.ArgumentsView.PackedRest pr) {
            boolean redirected = args.length > 1 && args[1] != org.babblelang.runtime.Arguments.MISSING;
            Appendable out = resolveOutput(rt, redirected, redirected ? args[1] : null);
            try {
                for (Object v : pr.iterable()) {
                    out.append(ArraySupport.format(v));
                }
                if (newLine) {
                    out.append(System.lineSeparator());
                }
            } catch (IOException e) {
                throw new BabbleException(e);
            }
            return null;
        }
        Object to = null;
        boolean redirected = false;
        if (names != null) {
            for (int i = 0; i < args.length; i++) {
                if ("to".equals(names[i])) {
                    to = args[i];
                    redirected = true;
                }
            }
        }
        Appendable out = resolveOutput(rt, redirected, to);

        // TODO : support printf
        try {
            for (int i = 0; i < args.length; i++) {
                if (names == null || !"to".equals(names[i])) {
                    out.append(ArraySupport.format(args[i]));
                }
            }
            if (newLine) {
                out.append(System.lineSeparator());
            }
        } catch (IOException e) {
            throw new BabbleException(e);
        }

        return null;
    }

    private static Appendable resolveOutput(ScriptRuntime rt, boolean redirected, Object to) {
        if (!redirected) {
            return rt.writer();
        }
        if (Values.unwrap(to) instanceof Appendable appendable) {
            return appendable;
        }
        throw new BabbleException("print to: expects a Writer or PrintStream");
    }
}
