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
                "def o = object ( def v = 1 ; def get = () -> ( this.v ) ) ; o.v = 2 ; o.get()",
                "def mk = () -> ( def hidden = 5 ; object ( def v = 1 ) ) ; mk().hidden",
                "def o = object ( def v = 1 ; def up = () -> ( this.v = this.v + 1 ) ) ; o.up() ; o.up()",
                "def P = value class (x, y) ( def plus = (o) -> ( P(this.x + o.x, this.y + o.y) ) ) ; P(1, 2).plus(P(3, 4))",
                "def P = value class (x, y) () ; P(1, 2) == P(1.0, 2.0) and not same(P(1, 2), P(1, 2))",
                "def C = class (private def n = 0) ( def next = () -> ( this.n = this.n + 1 ) ) ; def c = C() ; c.next() ; def d = c ; d.n",
                "def C = class () ( def a = this.b ; def b = 1 ) ; C()",
                "def S = interface ( def area : () : double\n def describe = () -> ( \"area \" + this.area() ) )\n"
                        + "def Sq = class (def s) implements S ( def area = () -> ( this.s * this.s ) ; def describe = () -> ( \"[\" + super.describe() + \"]\" ) )\n"
                        + "Sq(2).describe()",
                "def C = class (x) ( def get = () -> ( x ) ) static ( def make = () -> ( this(7) ) ) ; C.make().get()",
                "def C = class () () ; def c = C() ; c is C and not ( 1 is C ) and classof(c) == C",
                "def C = class () ( def toString = () -> ( \"C!\" ) ) ; println(C()) ; \"<\" + C() + \">\"",
                "def l = java.util.ArrayList() ; l.add(3) ; l.add(1) ; l.add(2) ; l.sort((a, b) -> ( a - b )) ; l.toString()",
                "def l = java.util.ArrayList() ; l.add(3) ; l.sort((a) -> ( a ))",
                "def It = class (private def n) implements java.util.Iterator ( def hasNext = () -> ( this.n > 0 ) ; def next = () -> ( this.n = this.n - 1 ; this.n + 1 ) )\n"
                        + "def it = It(3) ; it.forEachRemaining((v) -> ( print(v) ))",
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
                // Phase 2 : variables as JVM locals and cells.
                "def l = java.util.ArrayList() ; def i = 0 ; while i < 3 then ( def x = i ; l.add(() -> ( x )) ; i = i + 1 ) ; l.get(0)() + l.get(2)()",
                "def f = () -> ( def a = 1 ; package p ( def b = a + 1 ) ; p.b ) ; f()",
                "def f = (a, b = () -> ( c ), c = 5) -> ( b() ) ; f(1)",
                "def f = (a, b = c) -> ( b ) ; f(1)",
                "def c = 7 ; def f = (a, b = c, c = 1) -> ( b ) ; f(1)",
                "def mk = (k) -> ( class (x) ( def get = () -> ( x + k ) ) ) ; mk(10)(1).get()",
                "def mk = (k) -> ( class () () static ( def make = () -> ( k ) ) ) ; mk(3).make()",
                "def mk = () -> ( def n = 0 ; object ( def inc = () -> ( n = n + 1 ) ) ) ; def o = mk() ; o.inc() ; o.inc()",
                "def f = (n) -> ( if n == 0 then ( 0 ) else ( (() -> ( recurse(n - 1) ))() ) ) ; f(3)",
                "def f = () -> ( def g = () -> ( a = 1 ) ; g() ; def a = 2 ) ; f()",
                "def f = () -> ( def g = () -> ( a ) ; def a = g() ) ; f()",
                "def f = (a, b) -> ( b ) ; def i = 0 ; f(1, while i < 3 then ( i = i + 1 ))",
                "def f = () -> ( def i = 0 ; def s = 0 ; while i < 1000 then ( def sq = i * i ; s = s + sq ; i = i + 1 ) ; s ) ; f()",
                "def f = () -> ( if true then ( def x = 1 ; () -> ( x ) ) else ( null ) ) ; f()()",
                "def f = (x) -> ( x = x + 1 ; x ) ; f(1)",
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
        // Compiled classes extend BabbleInstance : what matters is that it is an instance.
        if (value instanceof org.babblelang.runtime.BabbleInstance) {
            return "<instance>:" + value;
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
