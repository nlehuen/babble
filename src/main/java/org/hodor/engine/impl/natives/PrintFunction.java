package org.hodor.engine.impl.natives;

import org.hodor.engine.impl.Callable;
import org.hodor.engine.impl.Interpreter;
import org.hodor.engine.impl.Namespace;
import org.hodor.engine.impl.Scope;
import org.hodor.parser.HodorParser;

import java.io.PrintStream;

public class PrintFunction implements Callable {
    private final boolean newLine;

    public PrintFunction(boolean newLine) {
        this.newLine = newLine;
    }

    public Namespace bindParameters(Interpreter interpreter, HodorParser.CallContext callSite, Namespace parent, Parameters parameters) {
        Namespace namespace = parent.enter(null);
        namespace.define("parameters", true).set(parameters);
        return namespace;
    }

    public Object call(Interpreter interpreter, HodorParser.CallContext callSite, Scope scope) {
        Parameters params = (Parameters) scope.get("parameters").get();

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
