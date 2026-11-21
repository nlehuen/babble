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
                "def parts = java.util.regex.Pattern.compile(\",\").split(\"a,b,c\") ; \"\" + parts.length + \":\" + java.util.Arrays.asList(parts)",
                "def parts = java.util.regex.Pattern.compile(\",\").split(\"a,b\") ; def id = fn (x : any) : any -> ( x ) ; id(parts).length = 1",
                "def pick = fn (c : bool) -> ( if c then ( 1 ) else ( 2.0 ) ) ; def w = pick(true) ; \"\" + classof(w) + \":\" + (w + w)",
                "def pick2 = fn (n : int) -> ( if n == 0 then ( 1.0 ) else if n == 1 then ( 2 ) else ( 3 ) ) ; \"\" + classof(pick2(1)) + \":\" + classof(pick2(2)) + \":\" + (pick2(1) + pick2(2))",
                "def ret = fn (c : bool) -> ( if c then ( return 1 ) ; 2.0 ) ; def w = ret(true) ; \"\" + classof(w) + \":\" + (w + w)",
                "java.lang.Class.forName(\"no.such.Class\")",
                "import(\"org\").babblelang.tests.JavaClassDemo.boxedInt(false)",
                "import(\"org\").babblelang.tests.JavaClassDemo.boxedDouble(false)",
                "import(\"org\").babblelang.tests.JavaClassDemo.boxedBool(false)",
                "def id = fn (x : any?) : any? -> ( x ) ; (id(null) as java.awt.Point).x = 1",
                "def id = fn (x : any?) : any? -> ( x ) ; (id(null) as java.awt.Point).x",
                "java.lang.Class.forName(\"java.lang.String\").getEnumConstants().length"
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
                // BIP023 : an alias checked at runtime through as and is.
                id + "def S = interface ( def area : fn () : double )\ndef N = interface ( def name : fn () : string )\ndef SN = type S & N\n"
                        + "def T = class () implements S, N ( def area = fn () : double -> ( 2.0 ) ; def name = fn () : string -> ( \"t\" ) )\n"
                        + "def D = class () implements S ( def area = fn () : double -> ( 1.0 ) )\n"
                        + "(id(T()) as SN).name() + (D() is SN)\n(id(D()) as SN).area()"
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
                "def d = import(\"org\").babblelang.tests.JavaClassDemo(\"n\") ; d.count = 4 ; d.count += 5 ; def a = java.lang.String(\"xyz\").getBytes() ; \"\" + d.count + \":\" + a.length + \":\" + d.label",
                "def g = fn io () : int -> ( java.lang.Math.max(1, return 7) ) ; g()",
                "def sb = java.lang.StringBuilder() ; import(\"org\").babblelang.tests.JavaClassDemo.appendChar(sb, java.lang.String(\"x\").charAt(0)) ; sb.toString()"
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
