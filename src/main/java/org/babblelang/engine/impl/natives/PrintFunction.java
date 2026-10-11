package org.babblelang.engine.impl.natives;

import org.babblelang.engine.BabbleException;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ScriptRuntime;

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
        Appendable out;
        if (!redirected) {
            out = rt.writer();
        } else if (to instanceof Appendable appendable) {
            out = appendable;
        } else {
            throw new BabbleException("print to: expects a Writer or PrintStream");
        }

        // TODO : support printf
        try {
            for (int i = 0; i < args.length; i++) {
                if (names == null || !"to".equals(names[i])) {
                    out.append(String.valueOf(args[i]));
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
}
