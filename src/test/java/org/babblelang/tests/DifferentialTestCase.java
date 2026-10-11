package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.impl.Namespace;
import org.babblelang.runtime.BabbleFunction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.SimpleScriptContext;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs scripts in both modes and compares what can be observed : the result, the output
 * written to the ScriptContext, and the error.
 */
public class DifferentialTestCase {
    private record Outcome(String result, String output, String error) {
    }

    static List<String> files() {
        return ScriptTestCase.data().stream().toList();
    }

    static List<String> snippets() {
        return List.of(
                "1 + 2 * 3",
                "2147483647 + 1",
                "4 / 2",
                "\"a\" + null",
                "1 + \"a\"",
                "1 <\n \"x\"",
                "def x = 99999999999 ; x",
                "1 == 1.0 and not ( 1 != 1.0 )",
                "def i = 0 ; while i < 10 then ( i = i + 1 )",
                "def i = 0 ; while i < 0 then ( i = i + 1 )",
                "if 0 then ( 1 )",
                "def f = (a, b) -> ( a - b ) ; f(b: 1, a: 3) + f(5, 2)",
                "def f = (a, b) -> ( a ) ; f(1, a: 2)",
                "def f = (a) -> ( a ) ; f(a: 1, a: 2)",
                "def f = (a) -> ( a ) ; f(1, a: 2)",
                "def f = (a) -> ( a ) ; f(1, b: 2, 3)",
                "def f = () -> ( 1 ) ; f()()",
                "def f = 1 ;\nf(\n  nosuchname\n)",
                "def s = \"hi\"\ns.length",
                "def s = \"hi\"\ns.length = 1",
                "println = 1",
                "def o = object ( def v = 1 ; def get = () -> ( v ) ) ; o.v = 2 ; o.get()",
                "def mk = () -> ( def hidden = 5 ; object ( def v = 1 ) ) ; mk().hidden",
                "def mk = () -> ( def up = () -> ( this.v = this.v + 1 ) ; object ( def v = 1 ) ) ; def o = mk() ; o.up() ; o.up()",
                "def fac = (n) -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) ) ; fac(10)",
                "package p ( def a = 1 ) ; package p ( def b = a + 1 ) ; p.b",
                "package p ( def a = 1 ) ; package p ( def a = 2 )",
                "def a = 1 ; def f = () -> ( def b = a ; def a = 2 ; b ) ; f()",
                "def x\nx = x + 1",
                "def f = (a, b = c, c = 1) -> ( b ) ; f(1)",
                "def f = (a = (def z = 2)) -> ( a + z ) ; f()",
                "def l = java.util.ArrayList() ; l.add(\"a\") ; l.get(1)",
                "java.lang.Math.abs(\"x\")",
                "println(\"a\", 1, null, to: STDOUT) ; print(1.5)",
                "suppose(1 == 2)",
                "suppose(1)",
                "def f = (n) -> (\n  if n == 0 then (\n    suppose(false, \"deep\")\n  ) else (\n    recurse(n - 1)\n  )\n)\nf(3)"
        );
    }

    private static Outcome run(String mode, String script) {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        context.setAttribute("assert", new AssertFunction(), ScriptContext.ENGINE_SCOPE);
        StringWriter out = new StringWriter();
        context.setWriter(out);
        try {
            Object result = engine.eval(script, context);
            return new Outcome(describe(result), out.toString(), null);
        } catch (Throwable t) {
            return new Outcome(null, out.toString(), t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static String describe(Object value) {
        if (value instanceof BabbleFunction) {
            return "<function>";
        }
        if (value instanceof Namespace) {
            return "<namespace>";
        }
        return value == null ? "null" : value.getClass().getName() + ":" + value;
    }

    private static void assertSameOutcome(String script) {
        Outcome interpreted = run("interpret", script);
        Outcome compiled = run("compile", script);
        Assertions.assertEquals(interpreted, compiled, script);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    public void testFile(String file) throws Exception {
        assertSameOutcome(Files.readString(Path.of(file)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("snippets")
    public void testSnippet(String snippet) {
        assertSameOutcome(snippet);
    }
}
