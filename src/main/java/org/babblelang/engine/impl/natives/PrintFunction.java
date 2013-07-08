package org.babblelang.engine.impl.natives;

import org.babblelang.engine.impl.Callable;
import org.babblelang.engine.impl.Interpreter;
import org.babblelang.engine.impl.Namespace;
import org.babblelang.engine.impl.Scope;
import org.babblelang.parser.BabbleParser;

import java.io.PrintStream;

public class PrintFunction implements Callable {
    private final boolean newLine;

    public PrintFunction(boolean newLine) {
        this.newLine = newLine;
    }

    public Namespace bindParameters(Interpreter interpreter, BabbleParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace namespace = parent.enter(null);
        namespace.define("...", true).set(parameters);
        return namespace;
    }

    public Object call(Interpreter interpreter, BabbleParser.CallContext callSite, Scope scope) {
        Parameters params = (Parameters) scope.get("...").get();

        PrintStream ps = (PrintStream) params.remove("to");
        if (ps == null) {
            ps = (PrintStream) scope.get("STDOUT").get();
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
