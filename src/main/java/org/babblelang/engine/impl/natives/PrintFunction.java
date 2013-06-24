package org.babblelang.engine.impl.natives;

import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Resolver;
import org.babblelang.engine.impl.Scope;
import org.babblelang.parser.BabbleParser;

import java.io.PrintStream;

public class PrintFunction implements Callable {
    private final boolean newLine;

    public PrintFunction(boolean newLine) {
        this.newLine = newLine;
    }

    public Scope bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Scope parent, Parameters parameters) {
        Scope scope = parent.enter(null);
        scope.define("...", parameters);
        return scope;
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Resolver resolver) {
        Parameters params = (Parameters) resolver.get("...");

        PrintStream ps = (PrintStream) params.remove("to");
        if (ps == null) {
            ps = (PrintStream) resolver.get("STDOUT");
        }

        // TODO : support printf
        for (Object o : params.values()) {
            ps.print(o);
        }

        if (newLine) {
            ps.println();
        }

        return null;
    }
}
