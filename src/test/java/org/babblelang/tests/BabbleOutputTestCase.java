package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.BufferedWriter;
import java.io.StringWriter;

/**
 * print and println write to the writer of the eval's ScriptContext.
 */
public class BabbleOutputTestCase {
    private final ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();

    private ScriptContext context(StringWriter out) {
        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        context.setWriter(out);
        return context;
    }

    @Test
    public void testPrintWritesToTheContextWriter() throws Exception {
        StringWriter out = new StringWriter();
        engine.eval("println(\"hi\") ; print(1, \" \", 2.5) ; println()", context(out));
        Assertions.assertEquals("hi" + System.lineSeparator() + "1 2.5" + System.lineSeparator(), out.toString());
    }

    @Test
    public void testOutputOfAFunctionCalledElsewhereGoesToTheSameWriter() throws Exception {
        StringWriter out = new StringWriter();
        engine.eval("def f = () -> ( print(\"in f\") ) ; def g = () -> ( def STDOUT = null ; f() ) ; g()", context(out));
        Assertions.assertEquals("in f", out.toString());
    }

    @Test
    public void testStdoutIsTheContextWriter() throws Exception {
        StringWriter out = new StringWriter();
        engine.eval("print(\"a\", to: STDOUT) ; if STDOUT then ( print(\"b\") )", context(out));
        Assertions.assertEquals("ab", out.toString());
    }

    @Test
    public void testDefiningStdoutRedirectsNothing() throws Exception {
        StringWriter out = new StringWriter();
        engine.eval("def STDOUT = java.io.StringWriter() ; print(\"x\")", context(out));
        Assertions.assertEquals("x", out.toString());
    }

    @Test
    public void testToRedirectsASingleCall() throws Exception {
        StringWriter out = new StringWriter();
        StringWriter other = new StringWriter();
        ScriptContext context = context(out);
        context.setAttribute("other", other, ScriptContext.ENGINE_SCOPE);
        engine.eval("print(\"a\", to: other) ; print(\"b\")", context);
        Assertions.assertEquals("a", other.toString());
        Assertions.assertEquals("b", out.toString());
    }

    @Test
    public void testToRejectsWhatCannotBeWrittenTo() {
        ScriptException e = Assertions.assertThrows(ScriptException.class,
                () -> engine.eval("print(\"a\", to: 1)", context(new StringWriter())));
        Assertions.assertEquals("org.babblelang.engine.BabbleException: print to: expects a Writer or PrintStream in <input> at line number 1", e.getMessage());
    }

    @Test
    public void testOutputIsFlushedWhenTheScriptFails() {
        StringWriter out = new StringWriter();
        ScriptContext context = context(out);
        context.setWriter(new BufferedWriter(out));
        Assertions.assertThrows(ScriptException.class, () -> engine.eval("print(\"before\") ; nosuchname", context));
        Assertions.assertEquals("before", out.toString());
    }
}
