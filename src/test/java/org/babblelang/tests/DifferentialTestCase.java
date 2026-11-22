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
                "def x = 1 ; x += 2 ; x *= 2.5 ; x -= 1 ; x /= 2 ; x",
                "def s = \"a\" ; s += 1 ; s",
                "def b = fn (v : any) : any -> ( v ) ; def x : any = b(true) ; x += 1",
                "nosuchname += 1",
                "x += 1 ; def x = 0",
                "def o = object ( def v = 1 ) ; o.v += 2 ; o.v",
                "def o = object ( def v = 1 ) ; def x = o ; x.w += 2",
                "def C = class () ( private def n = 1 ; def up = fn () -> ( this.n *= 3 ) ) ; def c = C() ; c.up() ; c.up()",
                "def P = record (x : int) ( def plus = fn (o : int) : P -> ( P(this.x + o) ) ) ; def p = P(1) ; p += 2 ; p",
                "def P = record (x : int) () ; def p = P(1) ; p.x += 1",
                "def n = null ; n += 1",
                "int(2.5) + int(\"3\") + double(1)",
                "int(1 / 0)",
                "double(\"nope\")",
                "\"a\" + null",
                "1 + \"a\"",
                "1 <\n \"x\"",
                "def r = fn (n : int) : int -> ( recurse(n + 1) ) ; r(1)",
                "def x = 0 ; while true then ( x += 1 )",
                "def r = fn (n : int) : int -> ( forever recurse(n + 1) ) ; def x = 0 ; forever while (true) then ( assert(x < 3, \"done\") ; x += 1 )",
                "def x = 99999999999 ; x",
                "1 == 1.0 and not ( 1 != 1.0 )",
                "def i = 0 ; while i < 10 then ( i = i + 1 ) ; null",
                "def i = 0 ; while i < 0 then ( i = i + 1 ) ; null",
                "if 0 then ( 1 )",
                "def f = fn (a : int, b : int) : int -> ( a - b ) ; f(b: 1, a: 3) + f(5, 2)",
                "def f = fn (a : int, b : int) : int -> ( a ) ; f(1, a: 2)",
                "def f = fn (a : int) : int -> ( a ) ; f(a: 1, a: 2)",
                "def f = fn (a : int) : int -> ( a ) ; f(1, a: 2)",
                "def f = fn (a : int) : int -> ( a ) ; f(1, b: 2, 3)",
                "def f = fn () -> ( 1 ) ; f()()",
                "def f = 1 ;\nf(\n  nosuchname\n)",
                "def s = \"hi\"\ns.length()",
                "def s = \"hi\"\ns.nope",
                "def s = \"hi\"\ns.length = 1",
                "println = 1",
                "def o = object ( def v = 1 ; def get = fn () -> ( this.v ) ) ; o.v = 2 ; o.get()",
                "def mk = fn () -> ( def hidden = 5 ; object ( def v = 1 ) ) ; mk().hidden",
                "def o = object ( def v = 1 ; def up = fn () -> ( this.v = this.v + 1 ) ) ; o.up() ; o.up()",
                "def P = record (x : int, y : int) ( def plus = fn (o : P) : P -> ( P(this.x + o.x, this.y + o.y) ) ) ; P(1, 2).plus(P(3, 4))",
                "def P = record (x : double, y : double) () ; P(1, 2) == P(1.0, 2.0) and not same(P(1, 2), P(1, 2))",
                "def C = class (private def n = 0) ( def next = fn () -> ( this.n = this.n + 1 ) ) ; def c = C() ; c.next() ; def d = c ; d.n",
                "def C = class () ( def a = this.b ; def b = 1 ) ; C()",
                "def S = interface ( def area : fn () : double\n def describe = fn () -> ( \"area \" + this.area() ) )\n"
                        + "def Sq = class (def s : int) implements S ( def area = fn () : double -> ( 1.0 * this.s * this.s ) ; def describe = fn () -> ( \"[\" + super.describe() + \"]\" ) )\n"
                        + "Sq(2).describe()",
                "def C = class (x : int) ( def get = fn () -> ( x ) ) static ( def make = fn () : C -> ( this(7) ) ) ; C.make().get()",
                "def C = class () () ; def c = C() ; c is C and not ( 1 is C ) and classof(c) == C",
                "def C = class () ( def toString = fn () -> ( \"C!\" ) ) ; println(C()) ; \"<\" + C() + \">\"",
                "def l = java.util.ArrayList() ; l.add(3) ; l.add(1) ; l.add(2) ; l.sort(fn (a, b) -> ( (a as int) - (b as int) )) ; l.toString()",
                "def l = java.util.ArrayList() ; l.add(3) ; l.sort(fn (a) -> ( a ))",
                "def It = class (private def n : int) implements java.util.Iterator ( def hasNext = fn () : bool -> ( this.n > 0 ) ; def next = fn () : any -> ( this.n = this.n - 1 ; this.n + 1 ) )\n"
                        + "def it = It(3) ; it.forEachRemaining(fn (v) -> ( print(v) ))",
                "def fac = fn (n : int) : int -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) ) ; fac(10)",
                "def a = 1 ; def f = fn () -> ( def b = a ; def a = 2 ; b ) ; f()",
                "def x : any\nx = x + 1",
                "def f = fn (a : int, b = c, c = 1) -> ( b ) ; f(1)",
                "def f = fn (a = (def z = 2)) -> ( a + z ) ; f()",
                "def l = java.util.ArrayList() ; l.add(\"a\") ; l.get(1)",
                "java.lang.Math.abs(\"x\")",
                "println(\"a\", 1, null, to: STDOUT) ; print(1.5)",
                // Phase 2 : variables as JVM locals and cells.
                "def Box = class (def f : fn () : int) () ; def l = java.util.ArrayList() ; def i = 0 ; while i < 3 then ( def x = i ; l.add(Box(fn () -> ( x ))) ; i = i + 1 ) ; (l.get(0) as Box).f() + (l.get(2) as Box).f()",
                "def f = fn (a : int, b = fn () -> ( c ), c = 5) -> ( b() ) ; f(1)",
                "def f = fn (a : int, b = c) -> ( b ) ; f(1)",
                "def c = 7 ; def f = fn (a : int, b = c, c = 1) -> ( b ) ; f(1)",
                "def mk = fn (k : int) -> ( class (x : int) ( def get = fn () -> ( x + k ) ) ) ; mk(10)(1).get()",
                "def mk = fn (k : int) -> ( class () () static ( def make = fn () -> ( k ) ) ) ; mk(3).make()",
                "def mk = fn () -> ( def n = 0 ; object ( def inc = fn () -> ( n = n + 1 ) ) ) ; def o = mk() ; o.inc() ; o.inc()",
                "def f = fn (n : int) : int -> ( if n == 0 then ( 0 ) else ( (fn () -> ( recurse(n - 1) ))() ) ) ; f(3)",
                "def f = fn () -> ( def g = fn () -> ( a = 1 ) ; g() ; def a = 2 ) ; f()",
                "def f = fn () -> ( def g = fn () -> ( a ) ; def a = g() ) ; f()",
                "def f = fn (a : int, b : any) -> ( b ) ; def i = 0 ; f(1, while i < 3 then ( i = i + 1 ))",
                "def f = fn () -> ( def i = 0 ; def s = 0 ; while i < 1000 then ( def sq = i * i ; s = s + sq ; i = i + 1 ) ; s ) ; f()",
                "def f = fn () : fn () : int -> ( if true then ( def x = 1 ; fn () -> ( x ) ) else ( null ) ) ; f()()",
                "def f = fn (x : int) -> ( x = x + 1 ; x ) ; f(1)",
                "suppose(1 == 2)",
                "suppose(1)",
                "def f = fn (n : int) : any -> (\n  if n == 0 then (\n    suppose(false, \"deep\")\n  ) else (\n    recurse(n - 1)\n  )\n)\nf(3)",
                // Phase 3 : sites that see several kinds of callee or receiver, past the depth of their cache.
                "def Box = class (def fn : fn (x : int) : int) () ; def fs = java.util.ArrayList() ; fs.add(Box(fn (a : int, b : int = 10) -> ( a + b ))) ; fs.add(Box(fn (a : int) -> ( a * 2 ))) ; fs.add(Box(java.lang.Math.abs))\n"
                        + "fs.add(Box(fn (x : int, y : int = 1, z : int = 2) -> ( x + y + z ))) ; fs.add(Box(object ( def m = fn (v : int) -> ( v - 1 ) ).m)) ; fs.add(Box(fn (v : int) -> ( v )))\n"
                        + "def i = 0 ; def s = \"\" ; while i < 12 then ( s = s + (fs.get(if i < 6 then ( i ) else ( i - 6 )) as Box).fn(i - 3) + \",\" ; i = i + 1 ) ; s",
                "def call = fn (h : fn (a : int, b : int) : int) -> ( h(1, 2) ) ; call(fn (a, b) -> ( b )) + call(fn (a, b, c = 5) -> ( c )) ; call(fn (a) -> ( a ))",
                "def f = fn (a : int, b : int) -> ( a ) ; def i = 0 ; while i < 3 then ( f(i, i) ; i = i + 1 ) ; f(1)",
                "def f = fn (a : int, b = 2) -> ( a - b ) ; def i = 0 ; def s = 0 ; while i < 5 then ( s = s + f(b: i, a: 10) + f(i) ; i = i + 1 ) ; s",
                "def C = class (def k : int) ( def m = fn (x : int, y = 1) -> ( this.k * x + y ) ; def n = fn () -> ( this.m(2) + this.m(2, 0) + this.m(y: 5, x: 1) ) )\n"
                        + "def c = C(3) ; def i = 0 ; def s = 0 ; while i < 5 then ( s = s + c.m(i) + c.n() ; i = i + 1 ) ; s",
                "def C = class () ( def m = fn (x : int) -> ( x ) ; def n = fn () -> ( this.m(1, 2) ) ) ; C().n()",
                "def C = class () ( def m = fn (x : int) -> ( x ) ; def n = fn () -> ( this.m() ) ) ; C().n()",
                "def S = interface ( def k : int ; def d = fn (x = 1) -> ( x + this.k ) ) ; def A = class (def k : int) implements S () ; def B = class () implements S ( def k = 10 )\n"
                        + "def xs = java.util.ArrayList() ; xs.add(A(1)) ; xs.add(B()) ; xs.add(A(2)) ; def i = 0 ; def s = 0 ; while i < 6 then ( s = s + (xs.get(if i < 3 then ( i ) else ( i - 3 )) as S).d() ; i = i + 1 ) ; s",
                "def A = class () ( def v = 1 ) ; def B = class () ( def v = fn () -> ( 2 ) ) ; def D = class () () static ( def v = 5 )\n"
                        + "def vs = java.util.ArrayList() ; vs.add(A()) ; vs.add(B()) ; vs.add(object ( def v = 4 )) ; vs.add(D) ; vs.add(A())\n"
                        + "def get = fn (o) -> ( o.v ) ; def i = 0 ; def s = 0 ; while i < 5 then ( def v = get(vs.get(i)) ; s = s + (if v is java.lang.Integer then ( v ) else ( v() )) ; i = i + 1 ) ; s",
                "def peek = fn (o) -> ( o.b ) ; def C = class (early : bool) ( def a = if early then ( peek(this) ) else ( 0 ) ; def b = 1 ) ; peek(C(false)) + peek(C(false)) ; C(true)",
                "def A = class () ( def x = 1 ) ; def B = class () ( private def x = 2 ) ; def get = fn (o : any) -> ( o.x ) ; get(A()) + get(A()) ; get(B())",
                "def get = fn (o : any) -> ( o.x ) ; get(object ( def x = 1 )) ; get(1)",
                "def sb = java.lang.StringBuilder() ; def xs = java.util.ArrayList() ; xs.add(1) ; xs.add(\"a\") ; xs.add(2.5) ; xs.add(true) ; xs.add(java.lang.StringBuilder(\"x\"))\n"
                        + "def i = 0 ; while i < 10 then ( sb.append(xs.get(if i < 5 then ( i ) else ( i - 5 ))) ; i = i + 1 ) ; sb.toString()",
                "def sb = java.lang.StringBuilder() ; def i = 0 ; while i < 3 then ( sb.append(i) ; i = i + 1 ) ; sb.append(null)",
                "def R = class () implements java.lang.Runnable ( def run = fn () -> ( null ) ) ; def P = class () ()\n"
                        + "def mk = fn (o : any) -> ( java.lang.Thread(o as java.lang.Runnable) ) ; mk(R()) ; mk(R()) ; mk(P())",
                // Unary minus, "else if" and lines starting with a dot.
                "def x = 3 ; def d = 2.5 ; \"\" + -x + \" \" + -d + \" \" + -2147483648 + \" \" + -x * 2 + \" \" + (5 - -x) + \" \" + -(-x) + \" \" + -java.lang.Long.MAX_VALUE",
                "def id = fn (v : any) : any -> ( v ) ; -id(-2147483648)",
                "def id = fn (v : any) : any -> ( v ) ; -id(\"a\")",
                "def neg = fn (v : any) -> ( -v ) ; neg(1) + neg(1.5) + neg(java.math.BigInteger.TEN)",
                "-2147483649",
                "def a = 1\n-a",
                "def sign = fn (n : int) : string -> (\n  if n < 0 then (\"-\")\n  else if n == 0 then (\"0\")\n  else if n < 10 then (\"s\")\n  else (\"l\")\n)\nsign(-5) + sign(0) + sign(3) + sign(42)",
                "def f = fn (n : int) : string -> ( if n == 1 then (\"a\") else if n == 2 then (\"b\") ) ; \"\" + f(1) + f(2) + f(3)",
                "def f = fn (n : int) : int -> ( if n == 1 then (10) else if n == 2 then (20) else (30) + 1 ) ; f(1) + f(2) + f(3)",
                "def f = fn (n : int) : int -> ( if n == 1 then ( def x = 1 ; x ) else if n == 2 then ( def x = 2 ; x ) else ( def x = 3 ; x ) ) ; f(1) * 100 + f(2) * 10 + f(3)",
                "if true then (1) else x",
                "def s = java.lang.StringBuilder()\n  .append(\"a\")\n\n  .append(-1)\n  .toString()\ns",
                "def o = object ( def v = 1 )\no\n  .v = 2\no.v",
                // Operator methods : a + b is a.plus(b) on a Babble instance.
                "def V = record (x : double, y : double) ( def plus = fn (o : V) : V -> ( V(this.x + o.x, this.y + o.y) ) ; def minus = fn (o : V) : V -> ( V(this.x - o.x, this.y - o.y) )\n" + "  def times = fn (k : double) : V -> ( V(this.x * k, this.y * k) ) ; def div = fn (k : double) : V -> ( this * (1.0 / k) ) ; def neg = fn () : V -> ( V(-this.x, -this.y) ) )\ndef a = V(1, 2) ; def b = V(3, 5) ; \"\" + (a + b) + (a - b * 2) + -a + (a + b) / 2 + -(-a) + (\"<\" + a + \">\")",
                "def V = record (x : double, y : double) ( def plus = fn (o : V) : V -> ( V(this.x + o.x, this.y + o.y) ) ; def minus = fn (o : V) : V -> ( V(this.x - o.x, this.y - o.y) )\n" + "  def times = fn (k : double) : V -> ( V(this.x * k, this.y * k) ) ; def div = fn (k : double) : V -> ( this * (1.0 / k) ) ; def neg = fn () : V -> ( V(-this.x, -this.y) ) )\ndef i = 0 ; def s = V(0, 0) ; while i < 5 then ( s = s + V(i, 1) * 2 - V(1, 1) ; i = i + 1 ) ; s",
                "def V = record (x : double, y : double) ( def plus = fn (o : V) : V -> ( V(this.x + o.x, this.y + o.y) ) ; def minus = fn (o : V) : V -> ( V(this.x - o.x, this.y - o.y) )\n" + "  def times = fn (k : double) : V -> ( V(this.x * k, this.y * k) ) ; def div = fn (k : double) : V -> ( this * (1.0 / k) ) ; def neg = fn () : V -> ( V(-this.x, -this.y) ) )\ndef add = fn (p, q) -> ( p + q ) ; def i = 0 ; def s = \"\" ; while i < 4 then ( s = s + add(V(i, i), V(1, 1)) + add(i, 1) + add(\"x\", i) ; i = i + 1 ) ; s",
                "def C = class (def n : int) ( def plus = fn (o : int) : C -> ( C(this.n + o) ) ; def get = fn () -> ( this.n ) ; def twice = fn () : int -> ( (this + this.n).get() ) ) ; (C(2) + 3).get() + C(5).twice()",
                "def o = object ( def plus = fn (x : int) -> ( x * 10 ) ; def neg = fn () -> ( \"negated\" ) ) ; \"\" + (o + 4) + -o",
                "def S = interface ( def n : int ; def plus = fn (o : S) : int -> ( this.n + o.n ) ) ; def A = class (def n : int) implements S () ; A(1) + A(2)",
                "def P = class () () ; P() + 1",
                "def f = fn (p : any) -> ( -p ) ; def P = class () () ; f(P())",
                "def f = fn (p : any) -> ( 1 + p ) ; def P = class () ( def plus = fn (o : any) -> ( o ) ) ; f(P())",
                "def f = fn (p : any) -> ( p + 1 ) ; def P = class () ( private def plus = fn (o : any) -> ( o ) ) ; f(P())",
                "def f = fn (p : any) -> ( p * 2 ) ; def P = class () ( def times = 3 ) ; f(P())",
                "def f = fn (p : any) -> ( p - 2 ) ; def P = class () ( def minus = fn (a : any, b : any) -> ( a ) ) ; f(P())",
                "def f = fn (p : any) -> ( p / 2 ) ; def P = class () ( def div = fn (k : int) -> ( suppose(k > 2, \"too small\") ) ) ; f(P())",
                // Java calls' fast paths : one site, called again and again, with arguments
                // of kinds picking other overloads, receivers of other classes, and failures.
                "def Demo = import(\"org\").babblelang.tests.JavaClassDemo\ndef d = Demo() ; def vs = java.util.ArrayList() ; vs.add(1) ; vs.add(\"a\") ; vs.add(1.5) ; vs.add(true) ; vs.add(null) ; vs.add(2) ; vs.add(\"b\") ; vs.add(java.lang.StringBuilder(\"w\"))\n"
                        + "def over = fn (v : any) -> ( d.overloaded(v) ) ; def i = 0 ; def s = \"\" ; while i < 16 then ( s = s + over(vs.get(if i < 8 then ( i ) else ( i - 8 ))) + \",\" ; i = i + 1 ) ; s",
                "def first = fn (l : java.util.List) -> ( l.get(0) ) ; def a = java.util.ArrayList() ; a.add(1) ; def k = java.util.LinkedList() ; k.add(2)\n"
                        + "def ls = java.util.ArrayList() ; ls.add(a) ; ls.add(k) ; ls.add(java.util.List.of(3)) ; ls.add(a) ; ls.add(java.util.Collections.singletonList(4))\n"
                        + "def i = 0 ; def s = 0 ; while i < 10 then ( s = s * 10 + (first(ls.get(if i < 5 then ( i ) else ( i - 5 )) as java.util.List) as int) ; i = i + 1 ) ; s",
                "def root = fn (v : any) -> ( java.lang.Math.sqrt(v as double) ) ; def big = fn (v : any) -> ( java.lang.Math.max(v as int, 2) ) ; def i = 0 ; def s = \"\"\n"
                        + "while i < 4 then ( s = s + root(4.0) + root(9.0) + big(1) + big(3) + \";\" ; i = i + 1 ) ; s",
                "def sb = java.lang.StringBuilder() ; def add = fn (v : any) -> ( sb.append(v) ) ; def i = 0\n"
                        + "while i < 3 then ( add(i) ; add(\"-\") ; add(sb.length()) ; add(sb.charAt(0)) ; add(java.lang.StringBuilder(\"x\")) ; add(object ( def toString = fn () -> ( \"o\" ) )) ; i = i + 1 ) ; sb.toString()",
                "def Demo = import(\"org\").babblelang.tests.JavaClassDemo\ndef put = fn (d : Demo, v : string) -> ( d.nullable(v) ) ; def d = Demo() ; def i = 0 ; def s = \"\" ; while i < 4 then ( s = s + put(d, null) + put(d, \"x\") ; i = i + 1 ) ; s",
                "def l = java.util.ArrayList() ; def put = fn (v : any) -> ( l.add(v) ) ; def clear = fn () -> ( l.clear() ) ; def i = 0 ; while i < 3 then ( put(i) ; clear() ; put(i) ; i = i + 1 ) ; l.toString() + clear()",
                "def at = fn (l : java.util.List, i : int) -> ( l.get(i) ) ; def l = java.util.ArrayList() ; l.add(\"a\") ; def i = 0 ; while i < 5 then ( at(l, 0) ; i = i + 1 ) ; at(l, 3)",
                "def parse = fn (s : string) -> ( java.lang.Integer.parseInt(s) ) ; def i = 0 ; def n = 0 ; while i < 5 then ( n = n + parse(\"12\") ; i = i + 1 ) ; parse(\"nope\")",
                "def each = fn (l : java.util.List, f : java.util.function.Consumer) -> ( l.forEach(f) ) ; def l = java.util.ArrayList() ; l.add(1) ; l.add(0) ; def i = 0 ; while i < 3 then ( each(l, fn (v) -> ( java.lang.Integer.parseInt(\"x\" + v) )) ; i = i + 1 )",
                "def each = fn (l : java.util.List) -> ( l.forEach(fn (v) -> ( suppose((v as int) > 0, \"positive\") )) ) ; def l = java.util.ArrayList() ; l.add(1) ; def i = 0 ; while i < 3 then ( each(l) ; i = i + 1 ) ; l.add(0) ; each(l)",
                // P1 : any, built-in is, as casts, Java arrays and if/while int-to-double conversion.
                "def f = fn (v : any) : any -> ( if v is int then ( v + 1 ) else if v is double then ( v * 2 ) else if v is string then ( v + \"!\" ) else if v is bool then ( if v then ( 10 ) else ( 20 ) ) else if v is any then ( 99 ) else ( -1 ) ) ; \"\" + f(3) + \",\" + f(2.5) + \",\" + f(\"abcd\") + \",\" + f(true) + \",\" + f(java.lang.Object()) + \",\" + f(null)",
                "def C = class (def x : int) () ; def f = fn (v : any) -> ( def int = C ; if v is int then ( v.x ) else ( 0 ) ) ; f(C(7)) + f(7) + int(\"42\")",
                "1 + 2 as int == 3 and (1 + 2 as double) is double and classof(1 as double) == java.lang.Double",
                "def v : any = \"hi\" ; (v as string) + \"!\"",
                "def v : any = 123 ; v as string",
                "(null as string) == null",
                "def f = fn (v : any) -> ( v as int ) ; f(null)",
                "def f = fn (v : any) -> ( v as double ) ; f(null)",
                "def f = fn (v : any) -> ( v as bool ) ; f(null)",
                "def S = interface ( def k : int ) ; def A = class (def k : int) implements S () ; def B = class () () ; def f = fn (x : any) -> ( (x as S).k ) ; f(A(5)) ; f(B())",
                "def parts = java.util.regex.Pattern.compile(\",\").split(\"a,b,c\") ; \"\" + parts.size() + \":\" + java.util.Arrays.asList(parts)",
                "def parts = java.util.regex.Pattern.compile(\",\").split(\"a,b\") ; def id = fn (x : any) : any -> ( x ) ; id(parts).length = 1",
                "def parts = java.util.regex.Pattern.compile(\",\").split(\"a,b\") ; def id = fn (x : any) : any -> ( x ) ; id(parts).length",
                "def pick = fn (c : bool) -> ( if c then ( 1 ) else ( 2.0 ) ) ; def w = pick(true) ; \"\" + classof(w) + \":\" + (w + w)",
                "def pick2 = fn (n : int) -> ( if n == 0 then ( 1.0 ) else if n == 1 then ( 2 ) else ( 3 ) ) ; \"\" + classof(pick2(1)) + \":\" + classof(pick2(2)) + \":\" + (pick2(1) + pick2(2))",
                "def ret = fn (c : bool) -> ( if c then ( return 1 ) ; 2.0 ) ; def w = ret(true) ; \"\" + classof(w) + \":\" + (w + w)",
                "java.lang.Class.forName(\"no.such.Class\")",
                "import(\"org\").babblelang.tests.JavaClassDemo.boxedInt(false)",
                "import(\"org\").babblelang.tests.JavaClassDemo.boxedDouble(false)",
                "import(\"org\").babblelang.tests.JavaClassDemo.boxedBool(false)",
                "def id = fn (x : any?) : any? -> ( x ) ; (id(null) as java.awt.Point).x = 1",
                "def id = fn (x : any?) : any? -> ( x ) ; (id(null) as java.awt.Point).x",
                "java.lang.Class.forName(\"java.lang.String\").getEnumConstants().size()"
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
        // Both modes represent instances cleanly via BabbleObjects.
        if (org.babblelang.runtime.BabbleObjects.isInstance(value)) {
            return "<instance>:" + value;
        }
        if (value != null && value.getClass().isArray()) {
            String tag = value instanceof Object[] ? "<array>" : value.getClass().getName();
            return tag + ":" + org.babblelang.runtime.ArraySupport.deepToString(value);
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
        String id = "def id = fn (x : any) : any -> ( x )\n";
        return List.of(
                id + "def f = fn (a : int, b : string = \"\") : int -> ( a ) ; f(1) + f(id(2) as int, id(\"s\") as string) + f(b: id(null) as string, a: 3)",
                id + "def f = fn (a : int) -> ( a ) ;\nf(\n  id(\"x\") as int\n)",
                id + "def f = fn (a : int, b : string = id(3) as string) -> ( a )\nf(1)",
                id + "def g = fn (a : any) : int -> (\n  def t = 1\n  id(a) as int\n)\ng(1) + g(2)\ng(\"s\")",
                id + "def g = fn (a : int) : string -> (\n  if a == 0 then ( return id(1) as string )\n  \"s\"\n)\ng(1)\ng(0)",
                id + "def d : double = id(1) as double ; def e : double = id(2.5) as double ; \"\" + d + \" \" + e",
                id + "def n : int = 1\nn = id(2) as int\nn = id(\"x\") as int",
                id + "def b : bool = id(null) as bool",
                id + "def s : string = id(null) as string ; s",
                id + "def P = class (def x : int, y : double = 0) ( def label : string = \"p\" ; def set = fn (v : any) -> ( this.label = v as string ) ) ; def p = P(1, id(2) as double) ; p.set(id(\"q\")) ; p.label + p.x\np.set(id(2))",
                id + "def P = class (def x : int) () ; P(id(\"a\") as int)",
                id + "def P = class () ( def n : int = 0 ) ; def p = P() ; p.n = id(5) as int ; p.n = id(\"z\") as int",
                id + "def P = record (x : int, y : int) () ; P(1, 2) == P(id(1) as int, 2)\nP(1, id(2.5) as int)",
                id + "def S = interface ( def area : fn () : double )\ndef Sq = class (def s : int) implements S ( def area = fn () : double -> ( 1.0 * this.s * this.s ) )\n"
                        + "def T = class () ()\ndef f = fn (s : S) : double -> ( s.area() )\nf(Sq(2)) + f(id(Sq(3)) as S)\nf(id(T()) as S)",
                id + "def S = interface ( def k : int ) ; def o = object implements S ( def k = 1 ) ; def f = fn (s : S) -> ( s.k ) ; f(id(o) as S)\nf(id(object ()) as S)",
                id + "def f = fn (l : java.util.List) -> ( l.size() ) ; f(id(java.util.ArrayList()) as java.util.List)\nf(id(1) as java.util.List)",
                id + "def R = class () implements java.lang.Runnable ( def run = fn () -> ( null ) ) ; def f = fn (r : java.lang.Runnable) -> ( 1 ) ; f(id(R()) as java.lang.Runnable) + f(id(fn () -> ( 2 )) as java.lang.Runnable)\nf(id(\"no\") as java.lang.Runnable)",
                "def h = fn (f : fn (x : int) : int) -> ( f(1) ) ; h(fn (y) -> ( y + 1 )) + h(java.lang.Math.abs)",
                id + "def C = class () () static ( def s : int = 1 ) ; C.s = id(2) as int ; C.s\nC.s = id(\"q\") as int",
                // Nominal by expression : the classes two calls create are one type.
                id + "def mk = fn () -> ( def C = class (def n : int) () ; object ( def make = fn () -> ( C(1) ) ; def take = fn (c : any) -> ( (c as C).n ) ) )\n"
                        + "def a = mk() ; def b = mk() ; a.take(id(b.make()))\na.take(id(a))",
                "def fac = fn (n : int) : int -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) ) ; fac(10)",
                id + "def x : int = \"s\"\n1",
                // P6a : runtime checks and conversions for unions, nullables, and strict non-nullable any.
                "def id = fn (x : any?) : any? -> ( x )\ndef f = fn (x : int | string) : int | string -> ( x )\nf(id(true) as (int | string))",
                "def id = fn (x : any?) : any? -> ( x )\ndef a : any = id(null) as any",
                "def id = fn (x : any?) : any? -> ( x )\ndef d : double? = id(2) as double?\n\"\" + classof(d) + \":\" + d + \":\" + (id(null) as double?)",
                "def id = fn (x : any?) : any? -> ( x )\ndef u : int | double = id(2) as (int | double)\nclassof(u)",
                // P6b : Java generic boundary check rejects corrupted element read and permits null.
                "def l : java.util.List of int = java.util.ArrayList()\n(l as java.util.List).add(null)\nl.get(0)\n(l as java.util.List).add(\"bad\")\nl.get(1)",
                // BIP022 : intersections checked at runtime through as and is, and narrowing by is.
                id + "def S = interface ( def area : fn () : double )\ndef N = interface ( def name : fn () : string )\n"
                        + "def T = class () implements S, N ( def area = fn () : double -> ( 2.0 ) ; def name = fn () : string -> ( \"t\" ) )\n"
                        + "def D = class () implements S ( def area = fn () : double -> ( 1.0 ) )\n"
                        + "def f = fn (x : S & N) : string -> ( x.name() + x.area() ) ; def s : S = T()\n"
                        + "f(id(T()) as S & N) + (if s is N then ( s.name() ) else ( \"-\" )) + (D() is S & N)\nf(id(D()) as S & N)",
                // BIP022-I2 : SAM+markers across def/param/return/assign/as/Java, covariant dispatch, is Shape & Serializable, and bad dynamic cast.
                id + "def Demo = import(\"org.babblelang.tests\").JavaClassDemo\n"
                        + "def S = interface ( def area : fn () : double )\n"
                        + "def A = interface ( def m : fn () : any? ) ; def B = interface ( def m : fn () : int )\n"
                        + "def C = class () implements A, B ( def m = fn () : int -> ( 41 ) )\n"
                        + "def r : java.lang.Runnable & java.io.Serializable = fn () -> ()\n"
                        + "def pass = fn (x : java.lang.Runnable & java.io.Serializable) : bool -> ( Demo.isSerializableRunnable(x) )\n"
                        + "def mk = fn () : java.lang.Runnable & java.io.Serializable -> ( fn () -> () )\n"
                        + "def loc : java.lang.Runnable & java.io.Serializable = fn () -> () ; loc = fn () -> ()\n"
                        + "def cov = fn (x : A & B) : int -> ( x.m() + 1 )\n"
                        + "\"\" + pass(r) + \":\" + pass(mk()) + \":\" + pass(loc) + \":\" + pass(id(fn () -> ()) as (java.lang.Runnable & java.io.Serializable)) + \":\" + Demo.runSerializable(fn () -> ()) + \":\" + (r is S & java.io.Serializable) + \":\" + cov(C())\n"
                        + "id(fn () -> ()) as (java.lang.Runnable & java.lang.AutoCloseable)",
                // BIP023 : an alias checked at runtime through as and is.
                id + "def S = interface ( def area : fn () : double )\ndef N = interface ( def name : fn () : string )\ndef SN = type S & N\n"
                        + "def T = class () implements S, N ( def area = fn () : double -> ( 2.0 ) ; def name = fn () : string -> ( \"t\" ) )\n"
                        + "def D = class () implements S ( def area = fn () : double -> ( 1.0 ) )\n"
                        + "(id(T()) as SN).name() + (D() is SN)\n(id(D()) as SN).area()",
                // BIP020 : array out-of-bounds, negative length, covariant ArrayStoreException, generic creation, primitive type-var, frozen set, non-null default, alias call, and .length.
                "def a = array.of(10, 20) ; a.get(5)",
                "def a = array.of(10, 20) ; a.set(-1, 99)",
                "(array of int)(-2)",
                "def Shape = interface ( def area : fn () : int )\n"
                        + "def Circle = class (def r : int) implements Shape ( def area = fn () : int -> ( this.r ) )\n"
                        + "def Square = class (def s : int) implements Shape ( def area = fn () : int -> ( this.s ) )\n"
                        + "def cs = array.of(Circle(1))\n"
                        + "def shapes : array of Shape = cs\n"
                        + "shapes.set(0, Square(2))",
                "def mk of T = fn () : array of T -> ( array(1) )",
                "def first of T = fn io (a : array of T) : T -> ( a.get(0) ) ; first(\"abc\".toCharArray())",
                "def f = fn (a : frozen array of int) -> ( a.set(0, 1) )",
                "(array of string)(3)",
                "def Ints = type array of int ; (Ints)(10)",
                "def a = array.of(1, 2) ; a.length",
                // BIP022-I2 TypeVar->SAM negative controls: (fn ())|int and any against RS bound.
                "def RS = type java.lang.Runnable & java.io.Serializable ; def f = fn of (T : RS) (x : T) : bool -> ( true ) ; def u : (fn ()) | int = fn () -> () ; f(u)",
                "def RS = type java.lang.Runnable & java.io.Serializable ; def f = fn of (T : RS) (x : T) : bool -> ( true ) ; def a : any = fn () -> () ; f(a)",
                // BIP022-I2 followup : spread of frozen array/list copies without mutating frozen sources.
                "def BoxI = class (def xs : array of int) () ; def BoxS = class (def xs : array of string) () ; def MutA = class (def v : int) ()\n"
                        + "def fi : frozen array of int = freeze(array.of(5, 6)) ; def fs : frozen array of string = freeze(array.of(\"ok\", \"b\"))\n"
                        + "def fli : frozen list of int = freeze(list(5, 6)) ; def flm : frozen list of MutA = freeze(list(MutA(5)))\n"
                        + "def fArr = overload ( fn (k : int, xs : array of int) : BoxI -> ( BoxI(xs) ), fn (k : string, xs : array of int) : int -> ( 0 ) )\n"
                        + "def fStr = fn (xs : array of string) : BoxS -> ( BoxS(xs) )\n"
                        + "def fList = overload ( fn of T (k : int, xs : list of T) : list of T -> ( xs ), fn of T (k : string, xs : list of T) : int -> ( 0 ) )\n"
                        + "def u : int | string = 1\n"
                        + "def ra = fArr(u, ...fi) ; if ra is BoxI then ( ra.xs.set(0, 99) )\n"
                        + "fStr(...fs).xs.set(0, \"x\")\n"
                        + "def rli = fList(u, ...fli) ; if not (rli is int) then ( rli.set(0, 99) )\n"
                        + "def rlm = fList(u, ...flm) ; if not (rlm is int) then ( rlm.clear() )\n"
                        + "assert(fi.get(0) == 5 and fs.get(0) == \"ok\" and fli.get(0) == 5 and flm.size() == 1)\n"
                        + "\"\" + fi.get(0) + \":\" + fs.get(0) + \":\" + fli.get(0) + \":\" + flm.size()",
                // BIP022-I2 followup : Runnable array spread rt propagation, boxed numeric widening, unique/post-spread origins, and shared Iterable copy.
                "def ran = false\n"
                        + "def takeR = fn io (xs : array of java.lang.Runnable) : array of java.lang.Runnable -> ( xs )\n"
                        + "def ar = takeR(...list.of(fn io () -> ( ran = true ))) ; ar.get(0).run()\n"
                        + "def takeD = fn (xs : array of (double?)) : array of (double?) -> ( xs )\n"
                        + "def takeL = fn (xs : array of (long?)) : array of (long?) -> ( xs )\n"
                        + "def ai = array.of(1, 2)\n"
                        + "def d1 = takeD(...ai) ; def l1 = takeL(...ai)\n"
                        + "def takeU = fn (unique xs : list of int) : list of int -> ( xs.set(0, 77) ; xs )\n"
                        + "def fa : frozen array of int = freeze(array.of(10, 20))\n"
                        + "def u = takeU(...fa)\n"
                        + "def idL = fn (xs : list of int) : list of int -> ( xs )\n"
                        + "def copyMut = fn (xs : int...) : int -> ( def l = idL(...xs) ; l.add(99) ; l.size() )\n"
                        + "def leakIter = fn (shared xs : java.lang.Iterable of int) : java.lang.Iterable of int -> ( xs )\n"
                        + "def fl : frozen list of int = freeze(list(10, 20, 30))\n"
                        + "def it = leakIter(...fl).iterator() ; it.next() ; it.remove()\n"
                        + "\"\" + ran + \":\" + d1.get(0) + \":\" + l1.get(1) + \":\" + u.get(0) + \":\" + fa.get(0) + \":\" + copyMut(1, 2) + \":\" + fl.size()",
                // BIP022-I2 Wave 2 negative parity : non-overloaded generic neg, and frozen soundness rejections.
                "def OpSingleNeg = class () ( def neg = fn of T () : T? -> ( null ) ) ; -OpSingleNeg()",
                "def MutA = class (def v : int) ( def inc = fn mut () -> ( this.v += 1 ) ) ; def idM = fn (xs : list of MutA) : list of MutA -> ( xs ) ; def exploit2 = fn io () : frozen array of MutA -> ( def ma = array.of(MutA(10)) ; def l = idM(...ma) ; def fma = freeze(ma) ; l.get(0).inc() ; fma ) ; exploit2().get(0).v",
                "def MutA = class (def v : int) ( def inc = fn mut () -> ( this.v += 1 ) ) ; def sink : (list of MutA)? = null ; def leak = fn io (shared xs : list of MutA) -> ( sink = xs ) ; def exploit3 = fn io () : frozen array of MutA -> ( def ma = array.of(MutA(10)) ; leak(...ma) ; freeze(ma) ) ; exploit3()"
        );
    }

    /** Static inference : int -> double widening conversions and runtime parity. */
    static List<String> strictSnippets() {
        return List.of(
                "def x = 1 ; def c1 = classof(x) ; x = 2 ; def c2 = classof(x) ; x = 2.5 ; \"\" + c1 + \":\" + c2 + \":\" + (x + 1)",
                "def x = 1 ; def c1 = classof(x) ; x /= 2 ; \"\" + c1 + \":\" + x",
                "def C = class () ( def x = 1 ; def widen = fn mut () -> ( this.x = 1.5 ) ) ; def c = C() ; def c1 = classof(c.x) ; c.x = 2 ; def c2 = classof(c.x) ; c.widen() ; \"\" + c1 + \":\" + c2 + \":\" + c.x",
                "def C = class (def k : double = 1) () static ( def s = 1 ) ; C.s = 2.5 ; \"\" + classof(C().k) + \":\" + C.s",
                "def d : double = 1 ; d = 2 ; \"\" + classof(d) + \":\" + d",
                "def m = null ; m = \"ok\" ; m",
                "def f = fn (sep = \",\", n = 1) -> ( sep + n ) ; f() + \"|\" + f(\";\", 2)",
                // P5 : widening without redundant T9 runtime checks (contravariant callbacks, defaults, returns, early returns, contextual lambdas).
                "def apply = fn (f : fn (x : int) : double) : double -> ( f(3) ) ; def r = apply(fn (d : double) : double -> ( d )) ; \"\" + classof(r) + \":\" + r",
                "def f = fn (d : double = 1) -> ( d ) ; \"\" + classof(f()) + \":\" + f()",
                "def f = fn (b : bool) : double -> ( if b then ( return 1 ) ; 2 ) ; \"\" + classof(f(true)) + \":\" + classof(f(false)) + \":\" + f(true) + \":\" + f(false)",
                "def f : fn () : double = fn () -> ( 1 ) ; \"\" + classof(f()) + \":\" + f()",
                "def g : fn (b : bool) : double = fn (b) -> ( if b then ( return 1 ) ; 2 ) ; \"\" + classof(g(true)) + \":\" + classof(g(false)) + \":\" + g(false)",
                // P6a : union & nullable widening, narrowing, while-loop narrowing, and equality narrowing parity.
                "def f = fn (b1 : bool, b2 : bool) : double? -> ( if b1 then ( 1 ) else if b2 then ( 2.5 ) else ( null ) ) ; \"\" + classof(f(true, false)) + \":\" + f(true, false) + \":\" + f(false, false)",
                "def x = null ; x = 1 ; x = 2.5 ; \"\" + classof(x) + \":\" + x",
                "def N = class (def v : int, def next : N?) () ; def cur : N? = N(1, N(2, null)) ; def s = 0 ; while cur != null then ( s += cur.v ; cur = cur.next ) ; s",
                "def f = fn (x : int | string | null) -> ( if x is int then ( x + 1 ) else if x is string then ( x + \"!\" ) else ( \"nil\" ) ) ; \"\" + f(1) + \":\" + f(\"a\") + \":\" + f(null)",
                // Contextual double widening through call arguments, nested lambdas, nullable return, and interface widened fields.
                "def take = fn (f : fn () : double) : double -> ( f() ) ; def r = take(fn () -> ( 1 )) ; \"\" + classof(r) + \":\" + r",
                "def mk : fn () : fn () : double = fn () -> ( fn () -> ( 1 ) ) ; def v = mk()() ; \"\" + classof(v) + \":\" + v",
                "def f : fn () : double? = fn () -> ( 1 ) ; def v = f() ; \"\" + classof(v) + \":\" + v",
                "def I = interface ( def x : double ) ; def C = class () implements I ( def x = 1 ; def widen = fn mut () -> ( this.x = 1.5 ) ) ; def i : I = C() ; \"\" + classof(i.x) + \":\" + i.x",
                // P6b : generic double widening, covariant narrowing with is, generic methods and Java collections.
                "def Box of T = class (def v : T) ()\ndef b : Box of double = Box(1)\ndef l : java.util.List of double = java.util.ArrayList() ; l.add(2)\n\"\" + classof(b.v) + \":\" + classof(l.get(0))",
                // Safe navigation (?.) : calls, field reads, chains, single LHS eval, short-circuit args, int -> double? widening.
                "def B = class (def v : int) () ; def A = class (def n : A? = null) ( def f = fn () : B -> ( B(9) ) ) ; def mk = fn (b : bool) : A? -> ( if b then ( A(A()) ) else ( null ) ) ; \"\" + mk(true)?.f()?.v + \":\" + mk(false)?.f()?.v + \":\" + mk(true)?.n?.f()?.v",
                "def n = 0 ; def m = 0 ; def A = class () ( def f = fn (x : int) -> ( x + 1 ) ) ; def recv = fn io (b : bool) : A? -> ( n += 1 ; if b then ( A() ) else ( null ) ) ; def arg = fn io () : int -> ( m += 1 ; 5 ) ; def r1 = recv(false)?.f(arg()) ; def r2 = recv(true)?.f(arg()) ; \"\" + r1 + \":\" + r2 + \":\" + n + \":\" + m",
                "def A = class () ( def g = fn () : int -> ( 3 ) ) ; def mk = fn (b : bool) : A? -> ( if b then ( A() ) else ( null ) ) ; def d1 : double? = mk(true)?.g() ; def d0 : double? = mk(false)?.g() ; \"\" + classof(d1) + \":\" + d1 + \":\" + d0",
                // P7 : call-site parameter inference, int -> double conversion, methods, operators, constructors, recursion, nullables.
                "def f = fn (w) -> ( \"\" + classof(w) + \":\" + w ) ; f(2) + \"|\" + f(1.5)",
                "def V = record (x, y) ( def plus = fn (o) -> ( V(this.x + o.x, this.y + o.y) ) ; def scale = fn (k = 1) -> ( V(this.x * k, this.y * k) ) ) static ( def make = fn (a, b) -> ( this(a, b) ) ) ; def v = V.make(1, 2) + V(1.5, 3) ; \"\" + v.scale(2) + \"|\" + v.scale()",
                "def fac = fn (n) -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) ) ; fac(6)",
                "def takesStr = fn (x : string) -> ( x + \"!\" ) ; def greet = fn (s = null) -> ( if s == null then ( \"none\" ) else ( takesStr(s) ) ) ; greet() + \",\" + greet(\"abc\")",
                // RM001 : edge-case parity for NaN, -0.0, int wraparound, narrowed nullables, array.length, Java fields and exceptions.
                "def nz = -0.0 ; def pz = 0.0 ; def nan = 0.0 / 0.0 ; \"\" + (nz == pz) + \":\" + (nz < pz) + \":\" + (pz < nz) + \":\" + (1.0 / nz) + \":\" + (nan == nan) + \":\" + (nan != nan) + \":\" + (nan < 1.0) + \":\" + (nan > 1.0)",
                "def minV = -2147483648 ; def maxV = 2147483647 ; \"\" + (maxV + 1) + \":\" + (minV - 1) + \":\" + (-minV) + \":\" + (minV * -1)",
                "def f = fn (a : int?, b : double?, c : bool?) : double -> ( (if a != null then ( a + 1 ) else ( 0 )) + (if b != null then ( b * 2 ) else ( 0.5 )) + (if c != null and c then ( 10 ) else ( 20 )) ) ; \"\" + f(3, 1.5, true) + \":\" + f(null, null, false) + \":\" + f(null, 2, null)",
                "def d = import(\"org\").babblelang.tests.JavaClassDemo(\"n\") ; d.count = 4 ; d.count += 5 ; def a = java.lang.String(\"xyz\").getBytes() ; \"\" + d.count + \":\" + a.size() + \":\" + d.label",
                "def g = fn io () : int -> ( java.lang.Math.max(1, return 7) ) ; g()",
                "def sb = java.lang.StringBuilder() ; import(\"org\").babblelang.tests.JavaClassDemo.appendChar(sb, java.lang.String(\"x\").charAt(0)) ; sb.toString()",
                // BIP022-I2 : union callable -> SAM+markers flows & as casts, class/object/static SAM fields, operator overload union split on frozen interfaces.
                "def Demo = import(\"org.babblelang.tests\").JavaClassDemo ; def dyn = fn (x : any) : any -> ( x ) ; def x : (fn ()) | int = fn () -> () ; def take = fn (y : (java.lang.Runnable & java.io.Serializable) | int) : bool -> ( y is java.lang.Runnable & java.io.Serializable and Demo.isSerializableRunnable(y) ) ; def y : (java.lang.Runnable & java.io.Serializable) | int = x ; def a = dyn(fn () -> ()) as ((java.lang.Runnable & java.io.Serializable) | int) ; def b = dyn(fn () -> ()) as (java.lang.Runnable | int) ; \"\" + take(x) + \":\" + take(42) + \":\" + Demo.isSerializableRunnable(y) + \":\" + Demo.isSerializableRunnable(a) + \":\" + (b is java.lang.Runnable)",
                "def Demo = import(\"org.babblelang.tests\").JavaClassDemo ; def C = class (def k : int = 9) ( def mr : java.lang.Runnable & java.io.Serializable = fn () -> () ; def sup : java.util.function.IntSupplier & java.io.Serializable = fn () : int -> ( this.k ) ) static ( def sr : java.lang.Runnable & java.io.Serializable = fn () -> () ) ; def o = object ( def orr : java.lang.Runnable & java.io.Serializable = fn () -> () ) ; \"\" + Demo.isSerializableRunnable(C().mr) + \":\" + C().sup.getAsInt() + \":\" + Demo.isSerializableRunnable(C.sr) + \":\" + Demo.isSerializableRunnable(o.orr)",
                "def Shape = interface ( def area : fn () : double ) ; def Named = interface ( def name : fn () : string ) ; def RD = record () implements Shape ( def area = fn () : double -> ( 3.0 ) ) ; def RN = record () implements Named ( def name = fn () : string -> ( \"rnote\" ) ) ; def RT = record () implements Shape, Named ( def area = fn () : double -> ( 1.5 ) ; def name = fn () : string -> ( \"rtile\" ) ) ; def Box = class () ( def plus = overload ( fn (s : frozen Shape) : string -> ( \"FS:\" + s.area() ), fn (n : frozen Named) : string -> ( \"FN:\" + n.name() ), fn (x : frozen (Shape & Named)) : string -> ( \"FSN:\" + x.name() ) ) ) ; def pick = fn (k : int) : frozen Shape | frozen Named -> ( if k == 0 then ( RD() ) else if k == 1 then ( RN() ) else ( RT() ) ) ; \"\" + (Box() + pick(0)) + \"|\" + (Box() + pick(1)) + \"|\" + (Box() + pick(2))",
                // BIP022-I2 Iteration 3 regressions : For elemSamTarget across -O1 ConstFold/DCE/Inline, FOR_01 returning fn, interface static SAM, 3-way & mixed operator overload union splits.
                "def Demo = import(\"org.babblelang.tests\").JavaClassDemo ; def I = interface () static ( def sr : java.lang.Runnable & java.io.Serializable = fn () -> () ) ; def k = 1 + 2 ; def f1 : (fn ()) | int = fn () -> () ; def check = fn (xs : list of ((fn ()) | int)) : bool -> ( def ok = false ; for x : (java.lang.Runnable & java.io.Serializable) | int in xs then ( if k == 3 and x is java.lang.Runnable & java.io.Serializable then ( ok = Demo.isSerializableRunnable(x) ) ) ; ok ) ; def res = for x : (java.lang.Runnable & java.io.Serializable) | int in list.of(f1) then ( fn (n : int) : int -> ( n + 1 ) ) ; def ran = false ; def take = fn io (shared r : java.lang.Runnable?) : bool -> ( if r != null then ( r.run() ; true ) else ( false ) ) ; \"\" + check(list.of(f1, 42)) + \":\" + res!(41) + \":\" + Demo.isSerializableRunnable(I.sr) + \":\" + take(for i in list.of(1) then ( fn io () -> ( ran = true ) )) + \":\" + ran",
                "def Shape = interface ( def area : fn () : double ) ; def Named = interface ( def name : fn () : string ) ; def Label = interface ( def tag : fn () : string ) ; def SOnly = record () implements Shape ( def area = fn () : double -> ( 1.0 ) ) ; def NOnly = record () implements Named ( def name = fn () : string -> ( \"n\" ) ) ; def LOnly = record () implements Label ( def tag = fn () : string -> ( \"l\" ) ) ; def SN = record () implements Shape, Named ( def area = fn () : double -> ( 2.0 ) ; def name = fn () : string -> ( \"sn\" ) ) ; def SL = record () implements Shape, Label ( def area = fn () : double -> ( 3.0 ) ; def tag = fn () : string -> ( \"sl\" ) ) ; def NL = record () implements Named, Label ( def name = fn () : string -> ( \"nl\" ) ; def tag = fn () : string -> ( \"nl\" ) ) ; def SNL = record () implements Shape, Named, Label ( def area = fn () : double -> ( 4.0 ) ; def name = fn () : string -> ( \"snl\" ) ; def tag = fn () : string -> ( \"snl\" ) ) ; def Box3 = class () ( def plus = overload ( fn (s : frozen Shape) : string -> ( \"S:\" + s.area() ), fn (n : frozen Named) : string -> ( \"N:\" + n.name() ), fn (l : frozen Label) : string -> ( \"L:\" + l.tag() ), fn (x : frozen (Shape & Named)) : string -> ( \"SN:\" + x.name() ), fn (x : frozen (Shape & Label)) : string -> ( \"SL:\" + x.tag() ), fn (x : frozen (Named & Label)) : string -> ( \"NL:\" + x.tag() ), fn (x : frozen (Shape & Named & Label)) : string -> ( \"SNL:\" + x.name() ), fn (k : int) : string -> ( \"I:\" + k ) ) ) ; def pick3 = fn (k : int) : frozen Shape | frozen Named | frozen Label -> ( if k == 0 then ( SOnly() ) else if k == 1 then ( NOnly() ) else if k == 2 then ( LOnly() ) else if k == 3 then ( SN() ) else if k == 4 then ( SL() ) else if k == 5 then ( NL() ) else ( SNL() ) ) ; def pickInt = fn (k : int) : frozen Shape | int | frozen Named -> ( if k == 0 then ( SOnly() ) else if k == 1 then ( 42 ) else if k == 2 then ( NOnly() ) else ( SN() ) ) ; def b = Box3() ; \"\" + (b + pick3(0)) + \",\" + (b + pick3(1)) + \",\" + (b + pick3(2)) + \",\" + (b + pick3(3)) + \",\" + (b + pick3(4)) + \",\" + (b + pick3(5)) + \",\" + (b + pick3(6)) + \"|\" + (b + pickInt(0)) + \",\" + (b + pickInt(1)) + \",\" + (b + pickInt(2)) + \",\" + (b + pickInt(3))",
                // BIP022-I2 Iteration 4 regressions : non-operator union split topological sort (1-arg & 2-arg), operator overload default-borrowed frozen args, nested class super inside SAM field.
                "def A = interface ( def a : fn () : int ) ; def B = interface ( def b : fn () : int ) ; def C = interface ( def c : fn () : int ) ; def Base = interface ( def m : fn () : int ) ; def Sub = interface extends Base ( def s : fn () : int ) ; def E = class () () ; def F = class () () ; def G = class () () ; def H = class () () ; def SubAB = interface extends A, B ( def z : fn () : int ) ; def X = class () implements A, B, C, Sub, SubAB ( def a = fn () : int -> ( 1 ) ; def b = fn () : int -> ( 2 ) ; def c = fn () : int -> ( 3 ) ; def m = fn () : int -> ( 4 ) ; def s = fn () : int -> ( 5 ) ; def z = fn () : int -> ( 6 ) ) ; def g = overload ( fn (x : A, y : Base) : string -> ( \"ABase\" ), fn (x : A, y : any) : string -> ( \"Aany\" ), fn (x : B, y : any) : string -> ( \"Bany\" ), fn (x : C, y : any) : string -> ( \"Cany\" ), fn (x : A & B, y : Base) : string -> ( \"ABBase\" ), fn (x : A & B, y : any) : string -> ( \"ABany\" ), fn (x : A & C, y : Base) : string -> ( \"ACBase\" ), fn (x : A & C, y : any) : string -> ( \"ACany\" ), fn (x : B & C, y : any) : string -> ( \"BCany\" ), fn (x : A & B & C, y : Base) : string -> ( \"ABCBase\" ), fn (x : A & B & C, y : Sub) : string -> ( \"ABCSub\" ), fn (x : A & B & C, y : any) : string -> ( \"ABCany\" ) ) ; def px = fn () : A | B | C -> ( X() ) ; def py = fn () : double | Base | string | Sub | int -> ( X() ) ; def f1 = overload ( fn (x : Base) : string -> ( \"Base\" ), fn (x : Sub) : string -> ( \"Sub\" ), fn (x : int) : string -> ( \"int\" ), fn (x : string) : string -> ( \"str\" ), fn (x : E) : string -> ( \"E\" ), fn (x : F) : string -> ( \"F\" ), fn (x : G) : string -> ( \"G\" ), fn (x : H) : string -> ( \"H\" ), fn (x : null) : string -> ( \"null\" ) ) ; def p1 = fn (k : int) : Base | int | string | E | F | G | H | null | Sub -> ( if k == 0 then ( X() ) else ( null ) ) ; def f2 = overload ( fn (x : A) : string -> ( \"A\" ), fn (x : B) : string -> ( \"B\" ), fn (x : A & B) : string -> ( \"A&B\" ), fn (x : SubAB) : string -> ( \"SubAB\" ), fn (x : int) : string -> ( \"int\" ), fn (x : string) : string -> ( \"str\" ), fn (x : E) : string -> ( \"E\" ), fn (x : F) : string -> ( \"F\" ), fn (x : G) : string -> ( \"G\" ), fn (x : H) : string -> ( \"H\" ), fn (x : null) : string -> ( \"null\" ) ) ; def p2 = fn () : A | int | E | F | (A & B) | G | H | B | string | SubAB | null -> ( X() ) ; g(px(), py()) + \":\" + f1(p1(0)) + \":\" + f2(p2())",
                "def A = interface ( def a : fn () : int ) ; def B = interface ( def b : fn () : int ) ; def RA = record () implements A ( def a = fn () : int -> ( 1 ) ) ; def RB = record () implements B ( def b = fn () : int -> ( 2 ) ) ; def RAB = record () implements A, B ( def a = fn () : int -> ( 1 ) ; def b = fn () : int -> ( 2 ) ) ; def Op = class () ( def plus = overload ( fn (x : A) : string -> ( \"A\" ), fn (x : B) : string -> ( \"B\" ), fn (x : A & B) : string -> ( \"A&B\" ), fn (x : int) : string -> ( \"int\" ) ) ) ; def fa : frozen A = RA() ; def pick = fn (k : int) : frozen A | frozen B -> ( if k == 0 then ( RA() ) else if k == 1 then ( RB() ) else ( RAB() ) ) ; def Base = interface ( def m = fn () : int -> ( 42 ) ) ; def out = 0 ; def S = class () implements Base ( def r : java.lang.Runnable = fn io () -> ( def Inner = class () implements Base ( def g = fn () : int -> ( super.m() ) ) ; out = Inner().g() ) ) ; S().r.run() ; (Op() + fa) + \":\" + (Op() + pick(0)) + \":\" + (Op() + pick(1)) + \":\" + (Op() + pick(2)) + \":\" + out",
                // BIP022-I2 Iteration 5 regressions : generic operator overloads with frozen lists/unions, 5-interface union split (31 combos), and 2-arg 4x3 union split (30 combos).
                "def Op = class () ( def plus = overload ( fn of T (x : list of T) : int -> ( x.size() ), fn (x : int) : int -> ( x ) ) ) ; def OpT = class () ( def plus = overload ( fn of T (x : list of T) : T -> ( x.get(0) ), fn (x : int) : int -> ( x ) ) ) ; def fl : frozen list of int = freeze(list.of(10, 20)) ; def p1 = fn (b : bool) : frozen list of int | int -> ( if b then ( fl ) else ( 9 ) ) ; def p2 = fn (b : bool) : list of int | int -> ( if b then ( list.of(7) ) else ( 9 ) ) ; def a : int = Op() + fl ; def b : int = OpT() + p1(true) ; def c : int = OpT() + p2(true) ; \"\" + a + \":\" + b + \":\" + c",
                "def A = interface ( def a : fn () : int ) ; def B = interface ( def b : fn () : int ) ; def C = interface ( def c : fn () : int ) ; def D = interface ( def d : fn () : int ) ; def E = interface ( def e : fn () : int ) ; def All = class () implements A, B, C, D, E ( def a = fn () : int -> ( 1 ) ; def b = fn () : int -> ( 2 ) ; def c = fn () : int -> ( 3 ) ; def d = fn () : int -> ( 4 ) ; def e = fn () : int -> ( 5 ) ) ; def f5 = overload ( fn (x : A) : int -> ( 1 ), fn (x : B) : int -> ( 1 ), fn (x : C) : int -> ( 1 ), fn (x : D) : int -> ( 1 ), fn (x : E) : int -> ( 1 ), fn (x : A & B) : int -> ( 2 ), fn (x : A & C) : int -> ( 2 ), fn (x : A & D) : int -> ( 2 ), fn (x : A & E) : int -> ( 2 ), fn (x : B & C) : int -> ( 2 ), fn (x : B & D) : int -> ( 2 ), fn (x : B & E) : int -> ( 2 ), fn (x : C & D) : int -> ( 2 ), fn (x : C & E) : int -> ( 2 ), fn (x : D & E) : int -> ( 2 ), fn (x : A & B & C) : int -> ( 3 ), fn (x : A & B & D) : int -> ( 3 ), fn (x : A & B & E) : int -> ( 3 ), fn (x : A & C & D) : int -> ( 3 ), fn (x : A & C & E) : int -> ( 3 ), fn (x : A & D & E) : int -> ( 3 ), fn (x : B & C & D) : int -> ( 3 ), fn (x : B & C & E) : int -> ( 3 ), fn (x : B & D & E) : int -> ( 3 ), fn (x : C & D & E) : int -> ( 3 ), fn (x : A & B & C & D) : int -> ( 4 ), fn (x : A & B & C & E) : int -> ( 4 ), fn (x : A & B & D & E) : int -> ( 4 ), fn (x : A & C & D & E) : int -> ( 4 ), fn (x : B & C & D & E) : int -> ( 4 ), fn (x : A & B & C & D & E) : int -> ( 5 ) ) ; def SuperA = interface ( def sa : fn () : int ) ; def IA = interface extends SuperA ( def ia : fn () : int ) ; def SubIA = interface extends IA ( def subia : fn () : int ) ; def SuperB = interface ( def sb : fn () : int ) ; def X = class () implements SubIA, B, SuperB, C, D ( def sa = fn () -> ( 1 ) ; def ia = fn () -> ( 1 ) ; def subia = fn () -> ( 1 ) ; def b = fn () -> ( 1 ) ; def sb = fn () -> ( 1 ) ; def c = fn () -> ( 1 ) ; def d = fn () -> ( 1 ) ) ; def g = overload ( fn (x : SuperA, y : SuperB) : int -> ( 1 ), fn (x : SuperA, y : C) : int -> ( 1 ), fn (x : SuperA, y : D) : int -> ( 1 ), fn (x : SuperA, y : SuperB & C) : int -> ( 2 ), fn (x : SuperA, y : SuperB & D) : int -> ( 2 ), fn (x : SuperA, y : C & D) : int -> ( 2 ), fn (x : SuperA, y : SuperB & C & D) : int -> ( 3 ), fn (x : IA, y : SuperB) : int -> ( 1 ), fn (x : IA, y : C) : int -> ( 1 ), fn (x : IA, y : D) : int -> ( 1 ), fn (x : IA, y : SuperB & C) : int -> ( 2 ), fn (x : IA, y : SuperB & D) : int -> ( 2 ), fn (x : IA, y : C & D) : int -> ( 2 ), fn (x : IA, y : SuperB & C & D) : int -> ( 3 ), fn (x : SubIA, y : SuperB) : int -> ( 1 ), fn (x : SubIA, y : C) : int -> ( 1 ), fn (x : SubIA, y : D) : int -> ( 1 ), fn (x : SubIA, y : SuperB & C) : int -> ( 2 ), fn (x : SubIA, y : SuperB & D) : int -> ( 2 ), fn (x : SubIA, y : C & D) : int -> ( 2 ), fn (x : SubIA, y : SuperB & C & D) : int -> ( 3 ), fn (x : IA & B, y : SuperB) : int -> ( 2 ), fn (x : IA & B, y : C) : int -> ( 2 ), fn (x : IA & B, y : D) : int -> ( 2 ), fn (x : IA & B, y : SuperB & C) : int -> ( 3 ), fn (x : IA & B, y : SuperB & D) : int -> ( 3 ), fn (x : IA & B, y : C & D) : int -> ( 3 ), fn (x : IA & B, y : SuperB & C & D) : int -> ( 4 ), fn (x : SubIA & B, y : SuperB) : int -> ( 3 ), fn (x : SubIA & B, y : C) : int -> ( 3 ), fn (x : SubIA & B, y : D) : int -> ( 3 ), fn (x : SubIA & B, y : SuperB & C) : int -> ( 4 ), fn (x : SubIA & B, y : SuperB & D) : int -> ( 4 ), fn (x : SubIA & B, y : C & D) : int -> ( 4 ), fn (x : SubIA & B, y : SuperB & C & D) : int -> ( 5 ) ) ; def u5 : A | B | C | D | E = All() ; def ux : SuperA | SubIA | (IA & B) | IA = X() ; def uy : SuperB | (C & D) | C = X() ; \"\" + f5(u5) + \":\" + g(ux, uy)",
                // BIP022-I2 Wave 2 regressions : custom Iterable spreads, SAM+markers array/list spreads, union-split spreads, non-overloaded generic plus.
                "def Demo = import(\"org.babblelang.tests\").JavaClassDemo ; def MyIter = class () implements java.lang.Iterable of int ( def iterator = fn () : java.util.Iterator of int -> ( list(1, 2, 3).iterator() ) ) ; def sumL = fn (shared xs : list of int) : int -> ( def s = 0 ; for x in xs then ( s += x ) ; s ) ; def sumR = fn (shared xs : int...) : int -> ( def s = 0 ; for x in xs then ( s += x ) ; s ) ; def m = MyIter() ; def fns = list(fn () -> (), fn () -> ()) ; def chkA = fn (shared xs : array of (java.lang.Runnable & java.io.Serializable)) : bool -> ( xs.get(0) is java.lang.Runnable and xs.get(0) is java.io.Serializable ) ; def OpPlus = class () ( def plus = fn of T (x : T) : T -> ( x ) ) ; \"\" + sumL(...m) + \":\" + sumR(...m) + \":\" + list(from: m).size() + \":\" + Demo.runSerVarargs(...fns) + \":\" + chkA(...fns) + \":\" + (OpPlus() + 42)",
                // BIP022-I2 Wave 3 regressions : variadic SAM & SAM+markers rest spreads, mixed direct+spread, ArgumentsView forwarding, frozen borrowed rest, union-split overload rest.
                "def takeRestRun = fn io (shared xs : java.lang.Runnable...) : string -> ( def out = \"\" ; for x in xs then ( x.run() ; out = out + (x is java.lang.Runnable) + \";\" ) ; out ) ; def takeRestSer = fn io (shared xs : (java.lang.Runnable & java.io.Serializable)...) : string -> ( def out = \"\" ; for x in xs then ( x.run() ; out = out + (x is java.lang.Runnable) + \":\" + (x is java.io.Serializable) + \";\" ) ; out ) ; def fwd = fn io (shared xs : (java.lang.Runnable & java.io.Serializable)...) : string -> ( takeRestSer(...xs) ) ; def ov = overload ( fn io (n : int, shared xs : (java.lang.Runnable & java.io.Serializable)...) : string -> ( \"i:\" + takeRestSer(...xs) ), fn io (s : string, shared xs : (java.lang.Runnable & java.io.Serializable)...) : string -> ( \"s:\" + takeRestSer(...xs) ) ) ; def pick = fn (b : bool) : int | string -> ( if b then ( 1 ) else ( \"x\" ) ) ; def fns = list(fn io () -> ()) ; def ffns = freeze(list(fn io () -> ())) ; def takeBorrowed = fn (xs : (java.lang.Runnable & java.io.Serializable)...) : string -> ( def out = \"\" ; for x in xs then ( out = out + (x is java.lang.Runnable) + \":\" + (x is java.io.Serializable) ) ; out ) ; takeRestRun(...fns) + \"|\" + takeRestSer(fn io () -> (), ...fns) + \"|\" + fwd(...fns) + \"|\" + takeBorrowed(...ffns) + \"|\" + ov(pick(false), ...fns)",
                // BIP022-I2 Area B regressions : TO_SAM union-split rest/non-rest, generic union-split SAM+markers, and generic overloaded operator SAM promotion.
                "def RS = type java.lang.Runnable & java.io.Serializable ; def hits = 0 ; def fns = list(fn io () -> ( hits += 1 ))\n"
                        + "def ov = overload ( fn io (k : int, shared xs : RS...) : int -> ( for x in xs then ( x.run() ) ; hits ), fn io (k : string, shared xs : RS...) : int -> ( for x in xs then ( x.run() ) ; hits ) )\n"
                        + "def ovRest = overload ( fn io (k : int, shared xs : RS...) : int -> ( 0 ), fn io (k : string, shared xs : RS...) : int -> ( for x in xs then ( x.run() ) ; hits ) )\n"
                        + "def ovGen = overload ( fn of (T : RS) io (k : int, shared xs : T...) : int -> ( 0 ), fn of (T : RS) io (k : string, shared xs : T...) : int -> ( for x in xs then ( x.run() ) ; hits ) )\n"
                        + "def ovGenNonRest = overload ( fn of (T : RS) io (k : int, shared r : T) : int -> ( 0 ), fn of (T : RS) io (k : string, shared r : T) : int -> ( r.run() ; hits ) )\n"
                        + "def ovGenList = overload ( fn of (T : RS) io (k : int, shared xs : list of T) : int -> ( 0 ), fn of (T : RS) io (k : string, shared xs : list of T) : int -> ( for x in xs then ( x.run() ) ; hits ) )\n"
                        + "def OpOverloaded = class () ( def plus = overload ( fn of (T : RS) (r : T) : bool -> ( r is java.lang.Runnable and r is java.io.Serializable ), fn (x : int) : int -> ( x + 1 ) ) )\n"
                        + "def u : int | string = 1 ; def u2 : int | string = \"s\"\n"
                        + "\"\" + ov(u, fn io () -> ( hits += 2 ), ...fns) + \":\" + ovRest(u2, fn io () -> ( hits += 4 )) + \":\" + ovGen(u2, ...fns) + \":\" + ovGenNonRest(u2, fn io () -> ( hits += 8 )) + \":\" + ovGenList(u2, ...fns) + \":\" + (OpOverloaded() + (fn () -> ())) + \":\" + (OpOverloaded() + 41)",
                // BIP022-I2 TypeVar->SAM union/nullable promotion regressions (both Challengers).
                "def Demo = import(\"org.babblelang.tests\").JavaClassDemo ; def RS = type java.lang.Runnable & java.io.Serializable\n"
                        + "def MyRunSer = class () implements java.lang.Runnable, java.io.Serializable ( def run = fn () -> () )\n"
                        + "def existingRS : RS = fn io () -> () ; def fns = list(fn io () -> ()) ; def u : int | string = \"s\"\n"
                        + "def ovGenMix = overload ( fn of (T : RS) io (k : int, shared xs : T...) : int -> ( -1 ), fn of (T : RS) io (k : string, shared xs : T...) : int -> ( def c = 0 ; for x in xs then ( def r : RS = x ; assert(Demo.runSerializable(r)) ; c += 1 ) ; c ) )\n"
                        + "def genMix = fn of (T : RS) io (shared xs : T...) : int -> ( def c = 0 ; for x in xs then ( assert(x is java.lang.Runnable and x is java.io.Serializable) ; c += 1 ) ; c )\n"
                        + "def takeT = fn of (T : RS) io (shared r : T) : bool -> ( r is java.lang.Runnable and r is java.io.Serializable )\n"
                        + "def takeOptT = fn of (T : RS?) io (shared r : T) : bool -> ( r == null or (r is java.lang.Runnable and r is java.io.Serializable) )\n"
                        + "def idOpt = fn of (T : RS?) (r : T) : T -> ( r ) ; def nn : RS = idOpt(fn () -> ())\n"
                        + "def fnOrRs : (fn io ()) | RS = fn io () -> () ; def optFn : (fn io ())? = fn io () -> () ; def optNull : (fn io ())? = null\n"
                        + "def takeTwoRet = fn of (T : RS) (a : T, b : T) : RS -> ( b ) ; def r : RS = takeTwoRet(MyRunSer(), fn () -> ())\n"
                        + "def xs : list of RS = list.of(r) ; def d = Demo() ; d.action = r ; d.runAction()\n"
                        + "def uMix : MyRunSer | (fn ()) = fn () -> () ; def idT = fn of T (x : T) : T -> ( x ) ; def bu : (fn ()) | int = fn () -> () ; def br : (fn ()) | int = idT(bu)\n"
                        + "def OpOverloaded = class () ( def plus = overload ( fn of (T : RS) (x : T) : bool -> ( Demo.isSerializableRunnable(x) ), fn (x : int) : int -> ( x + 1 ) ) )\n"
                        + "def ovGen = overload ( fn of (T : RS) (k : int, shared xs : T...) : int -> ( 0 ), fn of (T : RS) (k : string, shared xs : T...) : int -> ( def c = 0 ; for x in xs then ( assert(x is java.lang.Runnable and x is java.io.Serializable) ; c += 1 ) ; c ) )\n"
                        + "\"\" + ovGenMix(u, existingRS, fn io () -> (), ...fns) + \":\" + genMix(existingRS, fn io () -> (), ...fns) + \":\" + takeT(fnOrRs) + \":\" + takeOptT(optFn) + \":\" + takeOptT(optNull) + \":\" + Demo.runSerializable(nn) + \":\" + Demo.runSerArray(...xs) + \":\" + Demo.runSerVarargs(existingRS, fn () -> ()) + \":\" + Demo.runSerVarargs(MyRunSer(), fn () -> ()) + \":\" + (OpOverloaded() + uMix) + \":\" + Demo.runSerializable(uMix) + \":\" + ovGen(u, MyRunSer(), fn () -> ()) + \":\" + (not (br is int))"
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("strictSnippets")
    public void testStrictSnippet(String snippet) {
        Outcome interpreted = run("interpret", snippet);
        Assertions.assertNull(interpreted.error(), snippet);
        assertSameOutcome(snippet);
    }
}
