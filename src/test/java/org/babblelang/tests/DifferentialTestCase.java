package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.runtime.Namespace;
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
                "def f = (n) -> (\n  if n == 0 then (\n    suppose(false, \"deep\")\n  ) else (\n    recurse(n - 1)\n  )\n)\nf(3)",
                // Phase 3 : sites that see several kinds of callee or receiver, past the depth of their cache.
                "def fs = java.util.ArrayList() ; fs.add((a, b = 10) -> ( a + b )) ; fs.add((a) -> ( a * 2 )) ; fs.add(java.lang.Math.abs)\n"
                        + "fs.add((x, y = 1, z = 2) -> ( x + y + z )) ; fs.add(object ( def m = (v) -> ( v - 1 ) ).m) ; fs.add((v) -> ( v ))\n"
                        + "def i = 0 ; def s = \"\" ; while i < 12 then ( s = s + fs.get(if i < 6 then ( i ) else ( i - 6 ))(i - 3) + \",\" ; i = i + 1 ) ; s",
                "def f = (x, y = 2) -> ( package p ( def b = x + y ) ; p.b ) ; def i = 0 ; def s = 0 ; while i < 3 then ( s = s + f(i) + f(i, 1) ; i = i + 1 ) ; s",
                "def call = (h) -> ( h(1, 2) ) ; call((a, b) -> ( b )) + call((a, b, c = 5) -> ( c )) ; call((a) -> ( a ))",
                "def f = (a, b) -> ( a ) ; def i = 0 ; while i < 3 then ( f(i, i) ; i = i + 1 ) ; f(1)",
                "def f = (a, b = 2) -> ( a - b ) ; def i = 0 ; def s = 0 ; while i < 5 then ( s = s + f(b: i, a: 10) + f(i) ; i = i + 1 ) ; s",
                "def C = class (def k) ( def m = (x, y = 1) -> ( this.k * x + y ) ; def n = () -> ( this.m(2) + this.m(2, 0) + this.m(y: 5, x: 1) ) )\n"
                        + "def c = C(3) ; def i = 0 ; def s = 0 ; while i < 5 then ( s = s + c.m(i) + c.n() ; i = i + 1 ) ; s",
                "def C = class () ( def m = (x) -> ( x ) ; def n = () -> ( this.m(1, 2) ) ) ; C().n()",
                "def C = class () ( def m = (x) -> ( x ) ; def n = () -> ( this.m() ) ) ; C().n()",
                "def S = interface ( def k : int ; def d = (x = 1) -> ( x + this.k ) ) ; def A = class (def k) implements S () ; def B = class () implements S ( def k = 10 )\n"
                        + "def xs = java.util.ArrayList() ; xs.add(A(1)) ; xs.add(B()) ; xs.add(A(2)) ; def i = 0 ; def s = 0 ; while i < 6 then ( s = s + xs.get(if i < 3 then ( i ) else ( i - 3 )).d() ; i = i + 1 ) ; s",
                "def A = class () ( def v = 1 ) ; def B = class () ( def v = () -> ( 2 ) ) ; package p ( def v = 3 ) ; def D = class () () static ( def v = 5 )\n"
                        + "def vs = java.util.ArrayList() ; vs.add(A()) ; vs.add(B()) ; vs.add(object ( def v = 4 )) ; vs.add(p) ; vs.add(D) ; vs.add(A())\n"
                        + "def get = (o) -> ( o.v ) ; def i = 0 ; def s = 0 ; while i < 6 then ( def v = get(vs.get(i)) ; s = s + (if v is java.lang.Integer then ( v ) else ( v() )) ; i = i + 1 ) ; s",
                "def peek = (o) -> ( o.b ) ; def C = class (early) ( def a = if early then ( peek(this) ) else ( 0 ) ; def b = 1 ) ; peek(C(false)) + peek(C(false)) ; C(true)",
                "def A = class () ( def x = 1 ) ; def B = class () ( private def x = 2 ) ; def get = (o) -> ( o.x ) ; get(A()) + get(A()) ; get(B())",
                "def get = (o) -> ( o.x ) ; get(object ( def x = 1 )) ; get(1)",
                "def sb = java.lang.StringBuilder() ; def xs = java.util.ArrayList() ; xs.add(1) ; xs.add(\"a\") ; xs.add(2.5) ; xs.add(true) ; xs.add(java.lang.StringBuilder(\"x\"))\n"
                        + "def i = 0 ; while i < 10 then ( sb.append(xs.get(if i < 5 then ( i ) else ( i - 5 ))) ; i = i + 1 ) ; sb.toString()",
                "def sb = java.lang.StringBuilder() ; def i = 0 ; while i < 3 then ( sb.append(i) ; i = i + 1 ) ; sb.append(null)",
                "def R = class () implements java.lang.Runnable ( def run = () -> ( null ) ) ; def P = class () ()\n"
                        + "def mk = (o) -> ( java.lang.Thread(o) ) ; mk(R()) ; mk(R()) ; mk(P())"
        );
    }

    private static Outcome run(String mode, String script) {
        return run(mode, script, null);
    }

    /** @param typecheck the type checker setting, or null for the default */
    private static Outcome run(String mode, String script, String typecheck) {
        return run(mode, script, typecheck, null);
    }

    /** @param file the name of the script's file, which its relative imports are resolved against, or null */
    private static Outcome run(String mode, String script, String typecheck, String file) {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        if (typecheck != null) {
            engine.put(BabbleScriptEngine.TYPECHECK, typecheck);
        }
        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        context.setAttribute("assert", new AssertFunction(), ScriptContext.ENGINE_SCOPE);
        if (file != null) {
            context.setAttribute(ScriptEngine.FILENAME, file, ScriptContext.ENGINE_SCOPE);
        }
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

    /** In error mode, with declared types checked at runtime (T9). */
    private static Outcome assertSameTypedOutcome(String script) {
        Outcome interpreted = run("interpret", script, "error");
        Outcome compiled = run("compile", script, "error");
        Assertions.assertEquals(interpreted, compiled, script);
        return interpreted;
    }

    /**
     * Error mode : dynamic values reaching declared types, and the conversions and
     * acceptances that must not fail.
     */
    static List<String> typedSnippets() {
        String id = "def id = (x) -> ( x )\n";
        return List.of(
                id + "def f = (a : int, b : string = \"\") : int -> ( a ) ; f(1) + f(id(2), id(\"s\")) + f(b: id(null), a: 3)",
                id + "def f = (a : int) -> ( a ) ;\nf(\n  id(\"x\")\n)",
                id + "def f = (a : int, b : string = id(3)) -> ( a )\nf(1)",
                id + "def g = (a) : int -> (\n  def t = 1\n  id(a)\n)\ng(1) + g(2)\ng(\"s\")",
                id + "def g = (a) : string -> (\n  if a == 0 then ( return id(1) )\n  \"s\"\n)\ng(1)\ng(0)",
                id + "def d : double = id(1) ; def e : double = id(2.5) ; \"\" + d + \" \" + e",
                id + "def n : int = 1\nn = id(2)\nn = id(\"x\")",
                id + "def b : bool = id(null)",
                id + "def s : string = id(null) ; s",
                id + "def P = class (def x : int, y : double = 0) ( def label : string = \"p\" ; def set = (v) -> ( this.label = v ) ) ; def p = P(1, id(2)) ; p.set(id(\"q\")) ; p.label + p.x\np.set(id(2))",
                id + "def P = class (def x : int) () ; P(id(\"a\"))",
                id + "def P = class () ( def n : int = 0 ) ; def p = P() ; p.n = id(5) ; p.n = id(\"z\")",
                id + "def P = value class (x : int, y : int) () ; P(1, 2) == P(id(1), 2)\nP(1, id(2.5))",
                id + "def S = interface ( def area : () : double )\ndef Sq = class (def s) implements S ( def area = () -> ( 1.0 * this.s * this.s ) )\n"
                        + "def T = class () ()\ndef f = (s : S) : double -> ( s.area() )\nf(Sq(2)) + f(id(Sq(3)))\nf(id(T()))",
                id + "def S = interface ( def k : int ) ; def o = object implements S ( def k = 1 ) ; def f = (s : S) -> ( s.k ) ; f(id(o))\nf(id(object ()))",
                id + "def f = (l : java.util.List) -> ( l.size() ) ; f(id(java.util.ArrayList()))\nf(id(1))",
                id + "def R = class () implements java.lang.Runnable ( def run = () -> ( null ) ) ; def f = (r : java.lang.Runnable) -> ( 1 ) ; f(id(R())) + f(id(() -> ( 2 )))\nf(id(\"no\"))",
                id + "def h = (f : (x : int) : int) -> ( f(1) ) ; h(id((y) -> ( y + 1 ))) + h(id(java.lang.Math.abs))\nh(id(3))",
                id + "def C = class () () static ( def s : int = 1 ) ; C.s = id(2) ; C.s\nC.s = id(\"q\")",
                // Nominal by expression : the classes two calls create are one type.
                id + "def mk = () -> ( def C = class (def n : int) () ; object ( def make = () -> ( C(1) ) ; def take = (c : C) -> ( c.n ) ) )\n"
                        + "def a = mk() ; def b = mk() ; a.take(id(b.make()))\na.take(id(a))",
                "def fac = (n : int) : int -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) ) ; fac(10)",
                id + "def x : int = \"s\"\n1"
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    public void testFile(String file) throws Exception {
        String script = Files.readString(Path.of(file));
        Outcome interpreted = run("interpret", script, null, file);
        Assertions.assertEquals(interpreted, run("compile", script, null, file), file);
        Assertions.assertNull(interpreted.error(), file);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("snippets")
    public void testSnippet(String snippet) {
        assertSameOutcome(snippet);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("typedSnippets")
    public void testTypedSnippet(String snippet) {
        assertSameTypedOutcome(snippet);
    }

    /** The in-repo scripts pass the type checker in error mode, and run with their types enforced. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    public void testFileWithTypesEnforced(String file) throws Exception {
        String script = Files.readString(Path.of(file));
        Outcome outcome = run("interpret", script, "error", file);
        Assertions.assertEquals(outcome, run("compile", script, "error", file), file);
        Assertions.assertNull(outcome.error(), file);
    }
}
