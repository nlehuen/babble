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
                "package p ( def n = 1 ; def inc = () -> ( n += 1 ) ) ; p.inc() ; p.inc()",
                "package p ( def n = 1 ) ; def id = (v) -> ( v ) ; id(p).n = 2",
                "package p ( def n = 1 ) ; def id = (v) -> ( v ) ; id(p).n += 2",
                "def x = 1 ; x += 2 ; x *= 2.5 ; x -= 1 ; x /= 2 ; x",
                "def s = \"a\" ; s += 1 ; s",
                "def b = (v) -> ( v ) ; def x = b(true) ; x += 1",
                "nosuchname += 1",
                "x += 1 ; def x = 0",
                "def o = object ( def v = 1 ) ; o.v += 2 ; o.v",
                "def o = object ( def v = 1 ) ; def x = o ; x.w += 2",
                "def C = class () ( private def n = 1 ; def up = () -> ( this.n *= 3 ) ) ; def c = C() ; c.up() ; c.up()",
                "def P = value class (x) ( def plus = (o) -> ( P(this.x + o) ) ) ; def p = P(1) ; p += 2 ; p",
                "def P = value class (x) () ; def p = P(1) ; p.x += 1",
                "def n = null ; n += 1",
                "int(2.5) + int(\"3\") + double(1)",
                "int(1 / 0)",
                "double(\"nope\")",
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
                        + "def mk = (o) -> ( java.lang.Thread(o) ) ; mk(R()) ; mk(R()) ; mk(P())",
                // Unary minus, "else if" and lines starting with a dot.
                "def x = 3 ; def d = 2.5 ; \"\" + -x + \" \" + -d + \" \" + -2147483648 + \" \" + -x * 2 + \" \" + (5 - -x) + \" \" + -(-x) + \" \" + -java.lang.Long.MAX_VALUE",
                "def id = (v) -> ( v ) ; -id(-2147483648)",
                "def id = (v) -> ( v ) ; -id(\"a\")",
                "def neg = (v) -> ( -v ) ; neg(1) + neg(1.5) + neg(java.math.BigInteger.TEN)",
                "-2147483649",
                "def a = 1\n-a",
                "def sign = (n) -> (\n  if n < 0 then (\"-\")\n  else if n == 0 then (\"0\")\n  else if n < 10 then (\"s\")\n  else (\"l\")\n)\nsign(-5) + sign(0) + sign(3) + sign(42)",
                "def f = (n) -> ( if n == 1 then (\"a\") else if n == 2 then (\"b\") ) ; \"\" + f(1) + f(2) + f(3)",
                "def f = (n) -> ( if n == 1 then (10) else if n == 2 then (20) else (30) + 1 ) ; f(1) + f(2) + f(3)",
                "def f = (n) -> ( if n == 1 then ( def x = 1 ; x ) else if n == 2 then ( def x = 2 ; x ) else ( def x = 3 ; x ) ) ; f(1) * 100 + f(2) * 10 + f(3)",
                "if true then (1) else x",
                "def s = java.lang.StringBuilder()\n  .append(\"a\")\n\n  .append(-1)\n  .toString()\ns",
                "def o = object ( def v = 1 )\no\n  .v = 2\no.v",
                // Operator methods : a + b is a.plus(b) on a Babble instance.
                "def V = value class (x, y) ( def plus = (o) -> ( V(this.x + o.x, this.y + o.y) ) ; def minus = (o) -> ( V(this.x - o.x, this.y - o.y) )\n" + "  def times = (k) -> ( V(this.x * k, this.y * k) ) ; def div = (k) -> ( this * (1.0 / k) ) ; def neg = () -> ( V(-this.x, -this.y) ) )\ndef a = V(1, 2) ; def b = V(3, 5) ; \"\" + (a + b) + (a - b * 2) + -a + (a + b) / 2 + -(-a) + (\"<\" + a + \">\")",
                "def V = value class (x, y) ( def plus = (o) -> ( V(this.x + o.x, this.y + o.y) ) ; def minus = (o) -> ( V(this.x - o.x, this.y - o.y) )\n" + "  def times = (k) -> ( V(this.x * k, this.y * k) ) ; def div = (k) -> ( this * (1.0 / k) ) ; def neg = () -> ( V(-this.x, -this.y) ) )\ndef i = 0 ; def s = V(0, 0) ; while i < 5 then ( s = s + V(i, 1) * 2 - V(1, 1) ; i = i + 1 ) ; s",
                "def V = value class (x, y) ( def plus = (o) -> ( V(this.x + o.x, this.y + o.y) ) ; def minus = (o) -> ( V(this.x - o.x, this.y - o.y) )\n" + "  def times = (k) -> ( V(this.x * k, this.y * k) ) ; def div = (k) -> ( this * (1.0 / k) ) ; def neg = () -> ( V(-this.x, -this.y) ) )\ndef add = (p, q) -> ( p + q ) ; def i = 0 ; def s = \"\" ; while i < 4 then ( s = s + add(V(i, i), V(1, 1)) + add(i, 1) + add(\"x\", i) ; i = i + 1 ) ; s",
                "def C = class (def n) ( def plus = (o) -> ( C(this.n + o) ) ; def get = () -> ( this.n ) ; def twice = () -> ( (this + this.n).get() ) ) ; (C(2) + 3).get() + C(5).twice()",
                "def o = object ( def plus = (x) -> ( x * 10 ) ; def neg = () -> ( \"negated\" ) ) ; \"\" + (o + 4) + -o",
                "def S = interface ( def n : int ; def plus = (o) -> ( this.n + o.n ) ) ; def A = class (def n) implements S () ; A(1) + A(2)",
                "def P = class () () ; P() + 1",
                "def f = (p) -> ( -p ) ; def P = class () () ; f(P())",
                "def f = (p) -> ( 1 + p ) ; def P = class () ( def plus = (o) -> ( o ) ) ; f(P())",
                "def f = (p) -> ( p + 1 ) ; def P = class () ( private def plus = (o) -> ( o ) ) ; f(P())",
                "def f = (p) -> ( p * 2 ) ; def P = class () ( def times = 3 ) ; f(P())",
                "def f = (p) -> ( p - 2 ) ; def P = class () ( def minus = (a, b) -> ( a ) ) ; f(P())",
                "def f = (p) -> ( p / 2 ) ; def P = class () ( def div = (k) -> ( suppose(k > 2, \"too small\") ) ) ; f(P())",
                // Java calls' fast paths : one site, called again and again, with arguments
                // of kinds picking other overloads, receivers of other classes, and failures.
                "def Demo = import(\"org\").babblelang.tests.JavaClassDemo\ndef d = Demo() ; def vs = java.util.ArrayList() ; vs.add(1) ; vs.add(\"a\") ; vs.add(1.5) ; vs.add(true) ; vs.add(null) ; vs.add(2) ; vs.add(\"b\") ; vs.add(java.lang.StringBuilder(\"w\"))\n"
                        + "def over = (v) -> ( d.overloaded(v) ) ; def i = 0 ; def s = \"\" ; while i < 16 then ( s = s + over(vs.get(if i < 8 then ( i ) else ( i - 8 ))) + \",\" ; i = i + 1 ) ; s",
                "def first = (l) -> ( l.get(0) ) ; def a = java.util.ArrayList() ; a.add(1) ; def k = java.util.LinkedList() ; k.add(2)\n"
                        + "def ls = java.util.ArrayList() ; ls.add(a) ; ls.add(k) ; ls.add(java.util.List.of(3)) ; ls.add(a) ; ls.add(java.util.Collections.singletonList(4))\n"
                        + "def i = 0 ; def s = 0 ; while i < 10 then ( s = s * 10 + first(ls.get(if i < 5 then ( i ) else ( i - 5 ))) ; i = i + 1 ) ; s",
                "def root = (v) -> ( java.lang.Math.sqrt(v) ) ; def big = (v) -> ( java.lang.Math.max(v, 2) ) ; def i = 0 ; def s = \"\"\n"
                        + "while i < 4 then ( s = s + root(4) + root(9.0) + big(1) + big(3.5) + big(java.lang.Long.MAX_VALUE) + \";\" ; i = i + 1 ) ; s",
                "def sb = java.lang.StringBuilder() ; def add = (v) -> ( sb.append(v) ) ; def i = 0\n"
                        + "while i < 3 then ( add(i) ; add(\"-\") ; add(sb.length()) ; add(sb.charAt(0)) ; add(java.lang.StringBuilder(\"x\")) ; add(object ( def toString = () -> ( \"o\" ) )) ; i = i + 1 ) ; sb.toString()",
                "def Demo = import(\"org\").babblelang.tests.JavaClassDemo\ndef put = (d, v) -> ( d.nullable(v) ) ; def d = Demo() ; def i = 0 ; def s = \"\" ; while i < 4 then ( s = s + put(d, null) + put(d, \"x\") ; i = i + 1 ) ; s",
                "def l = java.util.ArrayList() ; def put = (v) -> ( l.add(v) ) ; def clear = () -> ( l.clear() ) ; def i = 0 ; while i < 3 then ( put(i) ; clear() ; put(i) ; i = i + 1 ) ; l.toString() + clear()",
                "def at = (l, i) -> ( l.get(i) ) ; def l = java.util.ArrayList() ; l.add(\"a\") ; def i = 0 ; while i < 5 then ( at(l, 0) ; i = i + 1 ) ; at(l, 3)",
                "def parse = (s) -> ( java.lang.Integer.parseInt(s) ) ; def i = 0 ; def n = 0 ; while i < 5 then ( n = n + parse(\"12\") ; i = i + 1 ) ; parse(\"nope\")",
                "def each = (l, f) -> ( l.forEach(f) ) ; def l = java.util.ArrayList() ; l.add(1) ; l.add(0) ; def i = 0 ; while i < 3 then ( each(l, (v) -> ( java.lang.Integer.parseInt(\"x\" + v) )) ; i = i + 1 )",
                "def each = (l) -> ( l.forEach((v) -> ( suppose(v > 0, \"positive\") )) ) ; def l = java.util.ArrayList() ; l.add(1) ; def i = 0 ; while i < 3 then ( each(l) ; i = i + 1 ) ; l.add(0) ; each(l)"
        );
    }

    private static Outcome run(String mode, String script) {
        return run(mode, script, null);
    }

    /** @param file the name of the script's file, which its relative imports are resolved against, or null */
    private static Outcome run(String mode, String script, String file) {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
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

    /**
     * Declared types checked at runtime (T9) : dynamic values reaching declared types,
     * and the conversions and acceptances that must not fail.
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
        Outcome interpreted = run("interpret", script, file);
        Assertions.assertEquals(interpreted, run("compile", script, file), file);
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
        assertSameOutcome(snippet);
    }
}
