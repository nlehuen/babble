package org.babblelang.tests;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.babblelang.analysis.BlockScopes;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Golden tests of the type checker (docs/design/bip003_type-system.md) : for each snippet, the
 * diagnostics expected, as {@code line:column message}.
 */
public class TypeCheckerTestCase {
    private static List<String> diagnostics(String source) {
        BabbleParser parser = new BabbleParser(new CommonTokenStream(new BabbleLexer(CharStreams.fromString(source))));
        parser.setErrorHandler(new BailErrorStrategy());
        ScopeAnalysis analysis = BlockScopes.analyze(parser.file());
        return analysis.typeDiagnostics().stream().map(w -> w.line() + ":" + w.column() + " " + w.message()).toList();
    }

    private static List<String> warnings(String source) {
        BabbleParser parser = new BabbleParser(new CommonTokenStream(new BabbleLexer(CharStreams.fromString(source))));
        parser.setErrorHandler(new BailErrorStrategy());
        ScopeAnalysis analysis = BlockScopes.analyze(parser.file());
        return analysis.warnings().stream().map(w -> w.line() + ":" + w.column() + " " + w.message()).toList();
    }

    private static void assertDiagnostics(String source, String... expected) {
        Assertions.assertEquals(List.of(expected), diagnostics(source), source);
    }

    private static void assertWarnings(String source, String... expected) {
        Assertions.assertEquals(List.of(expected), warnings(source), source);
    }

    static Collection<String> scripts() {
        return ScriptTestCase.data();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scripts")
    public void testTheScriptsCheckCleanly(String file) throws Exception {
        Path path = Path.of(file).toAbsolutePath().normalize();
        StringWriter errors = new StringWriter();
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODULE_SOURCE, org.babblelang.engine.FileModuleSource.rooted(path.getParent()));
        engine.getContext().setErrorWriter(errors);
        engine.getContext().setWriter(new StringWriter());
        engine.getContext().setAttribute(ScriptEngine.FILENAME, path.toString(), ScriptContext.ENGINE_SCOPE);
        engine.compile(Files.readString(path));
        Assertions.assertEquals("", errors.toString(), file);
    }

    @Test
    public void testUnannotatedParametersRequireTypes() {
        assertDiagnostics("def f = fn (a, b) -> ( a + b )\n"
                        + "def g = fn (x) -> ( x.foo(1) )\n"
                        + "def i = 0 ; i = i + 1 ; i.y\n"
                        + "def n = null",
                "1:13 Missing type : a",
                "1:16 Missing type : b",
                "2:13 Missing type : x",
                "3:25 Not a scope : i",
                "4:1 Missing type : n");
    }

    @Test
    public void testAnnotatedDefinitions() {
        assertDiagnostics("def a : int = \"s\"\ndef b : string = 1\ndef c : double = 1\ndef d : bool = 1 < 2\ndef e : int = 1.5",
                "1:15 Type error : expected int, got string",
                "2:18 Type error : expected string, got int",
                "5:15 Type error : expected int, got double");
        // P6a : plain types (including string and any) are non-nullable; use T? for nullable.
        assertDiagnostics("def a : int = null\ndef b : string = null\ndef c : bool\ndef d : any = null\ndef e : string? = null\ndef f : any?\ndef g : double = if true then ( 1 ) else ( 2.5 )",
                "1:15 Type error : expected int, got null",
                "2:18 Type error : expected string, got null",
                "3:1 Type error : expected bool, got null",
                "4:15 Type error : expected any, got null");
        assertDiagnostics("def a : int = 1\na = \"x\"\ndef f = fn (n : int) -> ( n = 2.5 )",
                "2:5 Type error : expected int, got string",
                "3:31 Type error : expected int, got double");
    }

    @Test
    public void testUnknownTypes() {
        assertDiagnostics("def a : Unknown = 1\ndef f = fn (x : java.util.Nope) -> ( x )\ndef v = 1\ndef g = fn (x : v) -> ( x )",
                "1:9 Unknown type : Unknown",
                "2:17 Unknown type : java.util.Nope",
                "4:17 Unknown type : v");
    }

    @Test
    public void testCalls() {
        String f = "def f = fn (a : int, b : string = \"\") : int -> ( a )\n";
        assertDiagnostics(f + "f(1) ; f(1, \"x\") ; f(b: \"x\", a: 2)");
        assertDiagnostics(f + "f(\"x\")\nf(1, 2)\nf()\nf(1, \"x\", 3)\nf(1, c: 2)",
                "2:3 Type error : expected int, got string",
                "3:6 Type error : expected string, got int",
                "4:1 Missing parameter : a",
                "5:1 Too many parameters : 2 declared, 3 given",
                "6:1 No such parameter : c");
        // The result type flows on.
        assertDiagnostics(f + "def s : string = f(1)", "2:18 Type error : expected string, got int");
        assertDiagnostics("1()\n\"a\"(2)\ndef o = object () ; o()",
                "1:1 1 is not callable",
                "2:1 \"a\" is not callable",
                "3:21 o is not callable");
    }

    @Test
    public void testResults() {
        assertDiagnostics("def f = fn (n : int) : string -> ( n )\ndef g = fn (n : int) : int -> ( if n > 0 then ( return \"a\" ) else ( n ) )\n"
                        + "def h = fn () : int -> ( )",
                "1:36 Type error : expected string, got int",
                "2:56 Type error : expected int, got string",
                "3:24 Type error : expected int, got null");
    }

    @Test
    public void testResultsOfBranchesAndRecursion() {
        // if without else may be null : incompatible with int.
        assertDiagnostics("def k = fn (n : int) : int -> ( if n > 0 then ( n ) )",
                "1:33 Type error : expected int, got null");
        assertDiagnostics("def fac = fn (n : int) : int -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) )\ndef s : string = fac(3)",
                "2:18 Type error : expected string, got int");
        assertDiagnostics("def f = fn (n : int) : string -> ( if n < 2 then ( \"a\" ) else ( recurse(\"x\") ) )",
                "1:73 Type error : expected int, got string");
    }

    @Test
    public void testInference() {
        // T8 : a def has its initial value's type.
        assertDiagnostics("def n = 1\ndef s : string = n\ndef f = fn (a : int) : int -> ( a )\ndef g = f\ng(\"x\")",
                "2:18 Type error : expected string, got int",
                "5:3 Type error : expected int, got string");
        assertDiagnostics("def u = fn (a, b) -> ( a )\nu(1)\nu(\"x\", null)",
                "1:16 Missing type : b",
                "2:1 Missing parameter : b",
                "3:3 Type error : expected int, got string");
        assertDiagnostics("def n = 1\nn = \"x\"\ndef s : string = n\ndef m = null\ndef t : int = m",
                "2:5 Type error : expected int, got string",
                "3:18 Type error : expected string, got int",
                "4:1 Missing type : m",
                "5:15 Type error : expected int, got null");
    }

    @Test
    public void testNumberConversions() {
        assertDiagnostics("def a : int = int(2.5)\ndef b : double = double(\"1\")\ndef c : string = int(1.5)\ndef d : int = double(1)\nint(1, 2)",
                "3:18 Type error : expected string, got int",
                "4:15 Type error : expected int, got double",
                "5:1 Too many parameters : 1 declared, 2 given");
    }

    @Test
    public void testCompoundAssignment() {
        // Typed as x = x op v : the result against what the target declares, the operands as for op.
        assertDiagnostics("def a : int = 1\na += 2\na *= 2.0\na /= 2\ndef s : string = \"x\"\ns += 1\ns -= 1\ndef d : double = 1.0\nd /= 3",
                "3:1 Type error : expected int, got double",
                "4:1 Type error : expected int, got double",
                "7:1 Not a number : s");
        assertDiagnostics("def C = class () ( def n : int = 0 ; def m = fn mut () -> ( this.n /= 2 ) )\ndef c = C()\nc.n += 1\nc.n += \"x\"\nc.q += 1",
                "1:61 Type error : expected int, got double",
                "4:8 Not a number : \"x\"",
                "5:1 No such member : q");
        assertDiagnostics("def V = record (x : int) ( def plus = fn (o : V) : V -> ( V(this.x + o.x) ) )\ndef v : V = V(1)\nv += V(2)\nv += 1\nv -= V(1)",
                "4:6 Type error : expected V, got int",
                "5:1 No such member : minus");
    }

    @Test
    public void testOperators() {
        assertDiagnostics("def a : int = 1 + 2\ndef b : int = 1 + 2.0\ndef c : int = 4 / 2\ndef d : string = \"a\" + 1\ndef e : bool = 1 < 2",
                "2:15 Type error : expected int, got double",
                "3:15 Type error : expected int, got double");
        assertDiagnostics("1 + true\n\"a\" - 1\n1 < \"a\"\nnull + 1\n\"a\" < \"b\" ; 1 < 2.5 ; true == 1",
                "1:5 Not a number : true",
                "2:1 Not a number : \"a\"",
                "3:1 Not comparable : 1 and \"a\"",
                "4:1 Not a number : null");
    }

    @Test
    public void testMembers() {
        String point = "def Point = class (def x : int, y = 0) (\n  def norm = fn () : int -> ( this.x + this.missing )\n  def label : string = \"p\"\n) static ( def origin = fn () -> ( this(0) ) )\n";
        assertDiagnostics(point + "def p = Point(1)\ndef n : string = p.x\np.label = 1\np.nope\nPoint(\"a\")\nPoint.origin()\nPoint.nope",
                "2:40 No such member : missing",
                "6:18 Type error : expected string, got int",
                "7:11 Type error : expected string, got int",
                "8:1 No such member : nope",
                "9:7 Type error : expected int, got string",
                "11:1 No such member : nope");
        assertDiagnostics("def s = \"a\"\ns.nope\ndef f = fn () -> ( 1 )\nf.x",
                "2:1 No such name in java.lang.String : nope",
                "4:1 Not a scope : f");
        assertDiagnostics("def o = object ( def v : int = 1 ; private def w = 2 ; def get = fn () -> ( this.w ) )\ndef s : string = o.v",
                "2:18 Type error : expected string, got int");
    }

    @Test
    public void testOperatorMethods() {
        assertDiagnostics("def V = record (x : double) ( def plus = fn (o : V) : V -> ( V(this.x + o.x) ) ; def times = fn (k : double) : V -> ( V(this.x * k) ) ; def neg = fn () : V -> ( this * -1.0 ) )\n"
                        + "def a = V(1.0)\n"
                        + "def b : V = a + a * 2.0 + -a\n"
                        + "def c : double = a + a\n"
                        + "a + 1\n"
                        + "a * a\n"
                        + "a - a\n"
                        + "def P = class () ( def plus = fn (p : int, q : int) -> ( p ) ; def times = 3 )\n"
                        + "P() + 1\n"
                        + "P() * 2\n"
                        + "-P()\n"
                        + "def d = fn (x : any) -> ( x - a )",
                "4:18 Type error : expected double, got V",
                "5:5 Type error : expected V, got int",
                "6:5 Type error : expected double, got V",
                "7:1 No such member : minus",
                "9:1 Missing parameter : q",
                "10:1 P().times is not callable",
                "11:1 No such member : neg",
                "12:27 Not a number : x",
                "12:31 Not a number : a");
    }

    @Test
    public void testUnaryMinusAndElseIf() {
        assertDiagnostics("def a : int = -1\n"
                        + "def b : double = -1\n"
                        + "def c : int = -1.5\n"
                        + "def d = -\"s\"\n"
                        + "def e : int = -a * 2\n"
                        + "def f = fn (n : int) : string -> ( if n < 0 then (\"-\") else if n == 0 then (\"0\") else (\"+\") )\n"
                        + "def g = fn (n : int) : string -> ( if n < 0 then (\"-\") else if n == 0 then (0) else (\"+\") )\n"
                        + "def h = fn (v : any) -> ( if v is java.lang.Integer then (v + 1) else if v is java.lang.String then (v.nope) else (0) )",
                "3:15 Type error : expected int, got double",
                "4:10 Not a number : \"s\"",
                "7:76 Type error : expected string, got int",
                // Narrowed through "else if" : string exposes java.lang.String members, not arbitrary names.
                "8:102 No such name in java.lang.String : nope");
    }

    @Test
    public void testJavaFieldsAndResults() {
        assertDiagnostics("def pi : double = java.lang.Math.PI\n"
                        + "def bad : int = java.lang.Math.PI\n"
                        + "def n : int = java.lang.Runtime.getRuntime().availableProcessors()\n"
                        + "java.lang.Runtime.getRuntime().nope()\n"
                        + "java.lang.StringBuilder().append(\"a\").nope\n"
                        + "java.lang.Math.NOPE\n"
                        + "java.util.List.of(1).iterator().nope",
                "2:17 Type error : expected int, got double",
                "4:1 No such name in java.lang.Runtime : nope",
                "5:1 No such name in java.lang.StringBuilder : nope",
                "6:1 No such name in java.lang.Math : NOPE",
                "7:1 No such name in java.util.Iterator : nope");
    }

    @Test
    public void testJavaInstanceFields() {
        assertDiagnostics("def p = java.awt.Point(1, 2)\n"
                        + "def x : int = p.x\n"
                        + "p.x = \"a\"\n"
                        + "java.awt.Point.x\n"
                        + "java.awt.Point.x = 1\n"
                        + "java.lang.Math.PI = 3\n"
                        + "p.nope = 1\n"
                        + "java.lang.Thread().uncaughtExceptionHandler = 1\n"
                        + "def s : string = p.y",
                "3:7 Type error : expected int, got string",
                "4:1 Not a static field : java.awt.Point.x",
                "5:1 Not a static field : java.awt.Point.x",
                "6:1 Cannot assign final field : java.lang.Math.PI",
                "7:1 No such field in java.awt.Point : nope",
                "8:1 No such field in java.lang.Thread : uncaughtExceptionHandler",
                "9:18 Type error : expected string, got int");
    }

    @Test
    public void testInterfacesAreNominal() {
        String shape = "def Shape = interface ( def area : fn () : double ; def describe = fn () : string -> ( \"area \" + this.area() ) )\n"
                + "def Sq = class (def s : double) implements Shape ( def area = fn () -> ( 1.0 * this.s * this.s ) )\n";
        assertDiagnostics(shape + "def f = fn (x : Shape) : string -> ( x.describe() )\nf(Sq(2))\nf(1)\nf(object ())\nf(null)",
                "5:3 Type error : expected Shape, got int",
                "6:3 Type error : expected Shape, got object",
                "7:3 Type error : expected Shape, got null");
        assertDiagnostics(shape + "def g = fn (x : Shape) -> ( x.nope )\ndef h : int = Sq(1).area()\ndef k : string = Sq(1).describe()",
                "3:29 No such member : nope",
                "4:15 Type error : expected int, got double");
        assertDiagnostics(shape + "def i : Sq = Sq(1)\ndef j : Shape = i\ndef k : Sq = j",
                "5:14 Type error : expected Sq, got Shape");
    }

    @Test
    public void testFunctionTypes() {
        String apply = "def apply = fn (f : fn (x : int) : int, v : int) : int -> ( f(v) )\n";
        assertDiagnostics(apply + "apply(fn (y) -> ( y ), 1)\napply(fn (y, z = 2) -> ( y ), 1)\napply(fn (a, b) -> ( a ), 1)\napply(1, 2)\napply(fn (s : string) -> ( 1 ), 1)",
                "4:7 Type error : expected (x : int) : int, got (a, b)",
                "4:11 Missing type : a",
                "4:14 Missing type : b",
                "5:7 Type error : expected (x : int) : int, got int",
                "6:7 Type error : expected (x : int) : int, got (s : string) : int");
    }

    @Test
    public void testFunctionReturnTypeInference() {
        assertDiagnostics("def inc = fn (x : int) -> ( x + 1 )\ndef s : string = inc(1)",
                "2:18 Type error : expected string, got int");
        assertDiagnostics("def same = fn (n : int) -> ( if n > 0 then ( 1 ) else ( 2 ) )\n"
                        + "def widen = fn (n : int) -> ( if n > 0 then ( 1 ) else ( 2.5 ) )\n"
                        + "def nullable = fn (n : int) -> ( if n > 0 then ( \"a\" ) else ( null ) )\n"
                        + "def mixed = fn (n : int) -> ( if n > 0 then ( 1 ) else ( \"a\" ) )\n"
                        + "def a : string = same(1)\ndef b : int = widen(1)\ndef c : int = nullable(1)\ndef d : int = mixed(1)",
                "5:18 Type error : expected string, got int",
                "6:15 Type error : expected int, got double",
                "7:15 Type error : expected int, got string?",
                "8:15 Type error : expected int, got int | string");
        assertDiagnostics("def r1 = fn (n : int) -> ( if n > 0 then ( return 1 ) ; 2 )\n"
                        + "def r2 = fn (n : int) -> ( if n > 0 then ( return 1 ) else ( return 2 ) )\n"
                        + "def r3 = fn (n : int) -> ( if n > 0 then ( return 1 ) ; \"s\" )\n"
                        + "def a : string = r1(1)\ndef b : string = r2(1)\ndef c : int = r3(1)",
                "4:18 Type error : expected string, got int",
                "5:18 Type error : expected string, got int",
                "6:15 Type error : expected int, got string | int");
        // Empty and null-returning functions infer null return type.
        assertDiagnostics("def e = fn () -> ()\ndef n = fn () -> ( null )\ndef a : int = e()\ndef b : int = n()",
                "3:15 Type error : expected int, got null",
                "4:15 Type error : expected int, got null");
        // Self-recursive functions infer when parameter types determine the body type or are inferred from calls.
        assertDiagnostics("def fac = fn (n : int) -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) )\n"
                        + "def sum = fn (n : int) -> ( if n < 1 then ( 0 ) else ( n + sum(n - 1) ) )\n"
                        + "def dyn = fn (n) -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) )\n"
                        + "def uncalled = fn (n) -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) )\n"
                        + "def a : string = fac(5)\ndef b : string = sum(5)\ndef c : string = dyn(5)",
                "4:20 Missing type : n",
                "5:18 Type error : expected string, got int",
                "6:18 Type error : expected string, got int",
                "7:18 Type error : expected string, got int");
    }

    @Test
    public void testContextualTyping() {
        assertDiagnostics("def apply = fn (f : fn (x : int) : int, v : int) : int -> ( f(v) )\n"
                        + "apply(fn (x) -> ( x + 1 ), 1)\napply(v: 1, f: fn (x) -> ( x * 2 ))\n"
                        + "apply(fn (x) -> ( x.nope ), 1)\napply(fn (x) -> ( \"s\" ), 1)",
                "4:19 Not a scope : x",
                "5:7 Type error : expected (x : int) : int, got (x : int) : string");
        assertDiagnostics("def Point = class (def x : int) ()\n"
                        + "def f : fn (p : Point) : int = fn (p) -> ( p.x )\n"
                        + "def bad : fn (p : Point) : int = fn (p) -> ( p.nope )\n"
                        + "f = fn (q) -> ( q.nope )\n"
                        + "def add : fn (a : int) : fn (b : int) : int = fn (a) -> ( fn (b) -> ( a + b ) )\n"
                        + "def s : string = add(1)(2)\n"
                        + "def badAdd : fn (a : int) : fn (b : int) : int = fn (a) -> ( fn (b) -> ( b.nope ) )\n"
                        + "def branch : fn (p : Point) : int = if true then ( fn (p) -> ( p.x ) ) else ( fn (p) -> ( p.nope ) )\n"
                        + "def wrapped : fn (p : Point) : int = ( fn (p) -> ( p.nope ) )\n"
                        + "def Box = class (def cb : fn (x : int) : int) () ; Box(fn (x) -> ( x.nope ))",
                "3:46 No such member : nope",
                "4:17 No such member : nope",
                "6:18 Type error : expected string, got int",
                "7:74 Not a scope : b",
                "8:91 No such member : nope",
                "9:52 No such member : nope",
                "10:68 Not a scope : x");
        assertDiagnostics("def ok1 = java.util.function.IntUnaryOperator(fn (x) -> ( x + 1 ))\n"
                        + "def n1 : string = ok1.applyAsInt(2)\n"
                        + "def bad1 : java.util.function.IntUnaryOperator = fn (x) -> ( x.nope )\n"
                        + "java.util.concurrent.atomic.AtomicInteger(1).updateAndGet(fn (x) -> ( x.nope ))",
                "2:19 Type error : expected string, got int",
                "3:62 Not a scope : x",
                "4:71 Not a scope : x");
    }

    @Test
    public void testIsTypeNarrowing() {
        assertDiagnostics("def Point = class (def x : int) ()\n"
                        + "def f = fn (p : any) -> (\n"
                        + "  def a : string = if p is Point then ( p.x ) else ( 0 )\n"
                        + "  def b = if not (p is Point) then ( 0 ) else ( p.x )\n"
                        + "  def c = if p is Point then ( p.nope )\n"
                        + "  def d = if not (p is Point) then ( 0 ) else ( p.nope )\n"
                        + "  def e = p is Point and p.x > 0\n"
                        + "  def g = p is Point and p.nope > 0\n"
                        + "  def h = not (p is Point) or p.x > 0\n"
                        + "  def i = not (p is Point) or p.nope > 0\n"
                        + "  while p is Point then ( p.nope )\n"
                        + "  b\n)",
                "3:39 Type error : expected string, got int",
                "3:52 Type error : expected string, got int",
                "5:32 No such member : nope",
                "6:49 No such member : nope",
                "8:26 No such member : nope",
                "10:31 No such member : nope",
                "11:27 No such member : nope");
        assertDiagnostics("def Point = class (def x : int) ()\n"
                        + "def getX = fn (p : any?) -> (\n  if not (p is Point) then ( return 0 )\n  p.x\n)\n"
                        + "def s : string = getX(null)\n"
                        + "def bad = fn (p : any) -> (\n  if not (p is Point) then ( return 0 )\n  p.nope\n)\n"
                        + "def pos = fn (p : any) -> (\n  if p is Point then ( 0 ) else ( return 1 )\n  p.nope\n)\n"
                        + "bad(null)",
                "6:18 Type error : expected string, got int",
                "9:3 No such member : nope",
                "13:3 No such member : nope",
                "15:5 Type error : expected any, got null");
        assertDiagnostics("def Shape = interface ( def area : fn () : double )\n"
                        + "def Sq = class (def side : int) implements Shape ( def area = fn () -> ( 1.0 * this.side * this.side ) )\n"
                        + "def sideOf = fn (s : Shape) -> (\n"
                        + "  if s is Sq then ( def a : string = s.side ; s.nope )\n"
                        + "  if not (s is Sq) then ( return 0 )\n  s.side\n)\n"
                        + "def t : string = sideOf(Sq(2))",
                "4:38 Type error : expected string, got int",
                "4:47 No such member : nope",
                "8:18 Type error : expected string, got int");
        assertDiagnostics("def f = fn (x : any) -> (\n"
                        + "  def a : string = if x is java.lang.Integer then ( x + 1 ) else ( 0 )\n"
                        + "  if x is java.lang.Integer then ( x.nope )\n"
                        + "  if x is java.util.List then ( x.nope() )\n)\n"
                        + "def r : any = 1\nr = \"a\"\nif r is java.lang.Integer then ( r.nope )",
                "2:51 Type error : expected string, got int",
                "2:66 Type error : expected string, got int",
                "3:36 Not a scope : x",
                "4:33 No such name in java.util.List : nope",
                "8:34 Not a scope : r");
    }

    @Test
    public void testJava() {
        assertDiagnostics("def l : java.util.ArrayList of int = java.util.ArrayList()\nl.add(1)\nl.nope()\njava.lang.Math.abs(\"x\")\ndef m : string = java.lang.Math.max(1, 2)\njava.lang.Math.nope\n"
                        + "def b : java.util.List of int = l\ndef c : java.util.Map of (string, int) = l",
                "3:1 No such name in java.util.ArrayList : nope",
                "4:1 No applicable overload of java.lang.Math.abs for (string)",
                "5:18 Type error : expected string, got int",
                "6:1 No such name in java.lang.Math : nope",
                "8:42 Type error : expected map(string, int), got java.util.ArrayList(int)");
        // Babble functions convert to functional interfaces, and classes implement Java interfaces.
        assertDiagnostics("def l : java.util.List of any = java.util.ArrayList()\nl.removeIf(fn (v) -> ( v is int and v > 2 ))\n"
                + "def R = class () implements java.lang.Runnable ( def run = fn () -> ( null ) )\njava.lang.Thread(R())\ndef r : java.lang.Runnable = R()\n"
                + "def P = class () ()\njava.lang.Thread(P())",
                "7:1 No applicable overload of java.lang.Thread for (P)");
    }

    @Test
    public void testTypeErrorsFailTheCompilation() {
        // Every diagnostic is written as an error, and the first one fails the compilation.
        String script = "def a : int = \"s\"\ndef f = fn (n : int) -> ( n )\nf(true)";
        for (String mode : List.of("interpret", "compile")) {
            StringWriter errors = new StringWriter();
            ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run(script, mode, errors));
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: Type error : expected int, got string in <input> at line number 1 at column number 15",
                    e.getMessage(), mode);
            Assertions.assertEquals(1, e.getLineNumber());
            Assertions.assertEquals(15, e.getColumnNumber());
            Assertions.assertEquals("Error : Type error : expected int, got string in <input> at line number 1, column 15" + System.lineSeparator()
                    + "Error : Type error : expected int, got bool in <input> at line number 3, column 3"
                    + System.lineSeparator(), errors.toString(), mode);
        }
    }

    @Test
    public void testDeclaredTypesAreCheckedAtRuntime() {
        // An any value of the wrong type : nothing static to report, fails at runtime at the cast.
        String script = "def id = fn (x : any) : any -> ( x )\ndef f = fn (n : int) : int -> ( n )\nf(id(\"s\") as int)";
        for (String mode : List.of("interpret", "compile")) {
            ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run(script, mode, new StringWriter()));
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: Type error : expected int, got string in <input> at line number 3", e.getMessage(), mode);
        }
    }

    @Test
    public void testAny() {
        assertDiagnostics("def a : any = 1\ndef b : any = \"s\"\ndef c : any = true\ndef d : any? = null\n"
                + "def f = fn io (shared v : any?) : any? -> (\n"
                + "  def ok1 : bool = v == 1\n"
                + "  def ok2 : bool = v != null\n"
                + "  def ok3 : string = \"x = \" + v\n"
                + "  println(v) ; discard(same(v, 1)) ; discard(classof(v)) ; java.lang.System.out.println(v)\n"
                + "  def l : java.util.List of any? = java.util.ArrayList() ; l.add(v)\n"
                + "  def cond = true\n"
                + "  def ok4 : any = if cond then ( 1 ) else ( \"a\" )\n"
                + "  def ok5 : any? = if cond then ( 1 )\n"
                + "  v\n)\nf(1) ; f(\"x\") ; f(null)");
        assertDiagnostics("def badNull : any = null\n"
                + "def f = fn (v : any) -> (\n"
                + "  v.x\n"
                + "  v.x = 1\n"
                + "  v()\n"
                + "  v + 1\n"
                + "  1 + v\n"
                + "  -v\n"
                + "  v < 1\n"
                + "  1 < v\n"
                + "  def i : int = v\n"
                + "  def s : string = v\n"
                + "  java.lang.Math.abs(v)\n)\n"
                + "f(null)",
                "1:21 Type error : expected any, got null",
                "3:3 Not a scope : v",
                "4:3 Not a scope : v",
                "5:3 v is not callable",
                "6:3 Not a number : v",
                "7:7 Not a number : v",
                "8:4 Not a number : v",
                "9:3 Not comparable : v and 1",
                "10:3 Not comparable : 1 and v",
                "11:17 Type error : expected int, got any",
                "12:20 Type error : expected string, got any",
                "13:3 No applicable overload of java.lang.Math.abs for (any)",
                "15:3 Type error : expected any, got null");
    }

    @Test
    public void testBuiltinIsAndNarrowing() {
        assertDiagnostics("def f = fn (v : any) -> (\n"
                + "  if v is int then ( def n : int = v + 1 ; v.nope )\n"
                + "  if v is double then ( def d : double = v + 0.5 ; def n : int = v )\n"
                + "  if v is bool then ( def b : bool = v ; def n : int = v )\n"
                + "  if v is string then ( def s : string = v ; def n : int = v )\n"
                + "  if v is any then ( def a : any = v ; v + 1 )\n"
                + "  if not (v is string) then ( return \"none\" )\n"
                + "  def ok : string = v\n"
                + "  def bad : int = v\n"
                + "  v\n)",
                "2:44 Not a scope : v",
                "3:66 Type error : expected int, got double",
                "4:56 Type error : expected int, got bool",
                "5:60 Type error : expected int, got string",
                "6:40 Not a number : v",
                "9:19 Type error : expected int, got string");
        assertDiagnostics("def Point = class (def x : int) ()\n"
                + "def g1 = fn (v : any) -> (\n"
                + "  def ok1 = v is int and v + 1 > 0\n"
                + "  def ok2 : string = if not (v is string) then ( \"no\" ) else ( v )\n"
                + "  ok1\n)\n"
                + "def g2 = fn (v : any) -> (\n"
                + "  def P = Point\n"
                + "  if v is P then ( def bad : string = v.x )\n"
                + "  0\n)",
                "9:39 Type error : expected string, got int");
        // Built-in int(x) conversion is unaffected outside the right of is.
        assertDiagnostics("def n : int = int(\"42\") + 1");
    }

    @Test
    public void testAsCasts() {
        String decls = "def P = class (def x : int) ()\n"
                + "def Q = class (def y : int) ()\n"
                + "def S = interface ( def area : fn () : double )\n"
                + "def T = interface ( def tag : fn () : string )\n"
                + "def C = class () implements S ( def area = fn () -> ( 1.0 ) )\n";
        assertDiagnostics(decls
                + "def f = fn (v : any, s : S, n : java.lang.Number) -> (\n"
                + "  def a : int = v as int\n"
                + "  def b : double = v as double\n"
                + "  def c : bool = v as bool\n"
                + "  def d : string = (v as string) + \"!\"\n"
                + "  def e : double = (s as C).area()\n"
                + "  def t : T = s as T\n"
                + "  def k : int = n as int\n"
                + "  def w : double = 1 as double\n"
                + "  def ns : string? = null as string?\n"
                + "  def np : P? = null as P?\n"
                + "  def na : any? = null as any?\n"
                + "  a + k\n)");
        assertWarnings(decls
                + "def f = fn (v : any, s : S, n : java.lang.Number) -> (\n"
                + "  (v as int) + (1 as double)\n"
                + "  def ns : string? = null as string?\n"
                + "  def np : P? = null as P?\n"
                + "  n as int\n)");
        assertDiagnostics(decls
                + "def a = 1 as string\n"
                + "def b = 1.5 as int\n"
                + "def c = null as int\n"
                + "def d = null as double\n"
                + "def e = null as bool\n"
                + "def f1 = null as string\n"
                + "def f2 = null as P\n"
                + "def f3 = null as any\n"
                + "def g = P(1) as Q\n"
                + "def h = P(1) as S\n"
                + "def i = \"x\" as java.lang.Runnable\n"
                + "def j = fn (v : any) -> ( v as fn (x : int) : int )",
                "6:9 Type error : expected string, got int",
                "7:9 Type error : expected int, got double",
                "8:9 Type error : expected int, got null",
                "9:9 Type error : expected double, got null",
                "10:9 Type error : expected bool, got null",
                "11:10 Type error : expected string, got null",
                "12:10 Type error : expected P, got null",
                "13:10 Type error : expected any, got null",
                "14:9 Type error : expected Q, got P",
                "15:9 Type error : expected S, got P",
                "16:9 Type error : expected java.lang.Runnable, got string",
                "17:32 Cannot cast to a function type");
        // Non-fatal warnings for redundant casts.
        String redundant = decls
                + "def a = 1 as int\n"
                + "def b = \"x\" as string\n"
                + "def c = C() as S\n"
                + "def d = 1 as any\n"
                + "def e = fn (v : any) -> ( v as any )";
        assertDiagnostics(redundant);
        assertWarnings(redundant,
                "6:9 Redundant cast : int",
                "7:9 Redundant cast : string",
                "8:9 Redundant cast : S",
                "9:9 Redundant cast : any",
                "10:27 Redundant cast : any");
    }

    @Test
    public void testJavaArrays() {
        assertDiagnostics("def parts = java.util.regex.Pattern.compile(\",\").split(\"a,b\")\n"
                + "def n : int = parts.size()\n"
                + "java.util.Arrays.asList(parts)\n"
                + "def s : string = parts.size()\n"
                + "parts.length = 1\n"
                + "parts.length\n"
                + "parts.nope",
                "4:18 Type error : expected string, got int",
                "5:1 No such field in array of string : length (use size())",
                "6:1 No such name in array of string : length (use size())",
                "7:1 No such name in array of string : nope");
    }

    @Test
    public void testBip020ArraysVarianceCastsAndBridges() {
        String source = "def Shape = interface ( def area : fn () : int )\n"
                + "def Named = interface ( def name : fn () : string )\n"
                + "def Circle = class (def r : int) implements Shape, Named (\n"
                + "  def area = fn () : int -> ( this.r )\n"
                + "  def name = fn () : string -> ( \"c\" )\n"
                + ")\n"
                + "def strs = (array of string).of(\"a\")\n"
                + "def badNull : array of string? = strs\n"
                + "def okBorrow = fn (xs : array of string?) : int -> ( xs.size() )\n"
                + "okBorrow(strs)\n"
                + "java.util.Arrays.toString(strs)\n"
                + "def inters : array of (Shape & Named) = (array of (Shape & Named)).of(Circle(1))\n"
                + "def badToShape : array of Shape = inters\n"
                + "def unions = (array of (int | string)).of(1, \"x\")\n"
                + "def badToAny : array of any = unions\n"
                + "def x : any = strs\n"
                + "def w1 = x is array of string\n"
                + "def w2 = x as array of Circle\n"
                + "def ns : (array of string)? = null\n"
                + "def okNullCast = ns as (array of int)?\n"
                + "def badNonNullCast = strs as array of int\n"
                + "def tests = import(\"org.babblelang.tests\")\n"
                + "def BadImpl = class () implements tests.JavaClassDemo.ArrayTransformer of string (\n"
                + "  def transform = fn (a : array of string) : array of string -> ( a )\n"
                + ")\n"
                + "def badIfaceArg : (tests.JavaClassDemo.ArrayTransformer of int)? = null";
        assertDiagnostics(source,
                "8:34 Type error : expected array of string?, got array of string",
                "13:35 Type error : expected array of Shape, got array of (Shape & Named)",
                "15:31 Type error : expected array of any, got array of (int | string)",
                "21:22 Type error : expected array of int, got array of string",
                "26:60 Type error : type argument int cannot be a primitive in array of E");
        assertWarnings(source,
                "17:10 Unchecked cast : array of string",
                "18:10 Unchecked cast : array of Circle");
    }

    @Test
    public void testIfIntToDoubleConversion() {
        assertDiagnostics("def f = fn (c : bool) -> ( if c then ( 1 ) else ( 2.0 ) )\n"
                + "def g = fn (c : bool) -> ( if c then ( return 1 ) ; 2.0 )\n"
                + "def s1 : string = f(true)\n"
                + "def s2 : string = g(true)",
                "3:19 Type error : expected string, got double",
                "4:19 Type error : expected string, got double");
    }

    // ---------------------------------------------------------------- static typing rules (docs/design/bip008_static-typing.md)

    @Test
    public void testStrictS1UnannotatedParametersAndDefaults() {
        assertDiagnostics("def f = fn (a, b) -> ( a )\n"
                + "def g = fn (sep = \",\", n = 1, bad = null) -> ( sep + n )\n"
                + "def h = fn (a : fn (x) : int) -> ( 1 )\n"
                + "def s : string = g(1)",
                "1:13 Missing type : a",
                "1:16 Missing type : b",
                "2:31 Missing type : bad",
                "3:21 Unknown type : x",
                "4:20 Type error : expected string, got int");
        // Default values are checked against declared parameter types, and contextual types fill omitted parameter types.
        assertDiagnostics("def apply = fn (f : fn (x : int) : int, v : int) : int -> ( f(v) )\n"
                + "def a : int = apply(fn (y) -> ( y + 1 ), 2)\n"
                + "def ok = fn (sep : string = \"a\", x : int = 1) -> ( sep + x )\n"
                + "def bad = fn (x : int = \"nope\") -> ( x )\n"
                + "ok(1)",
                "4:25 Type error : expected int, got string",
                "5:4 Type error : expected string, got int");
    }

    @Test
    public void testStrictS2MembersAndClassHeaders() {
        assertDiagnostics("def C = class (x, y = 1, def z = \"a\") (\n"
                + "  def m = null\n"
                + "  def n\n"
                + "  def ok = null\n"
                + "  def setOk = fn mut (v : string) -> ( this.ok = v )\n"
                + "  def a = this.b\n"
                + "  def b = this.a\n"
                + ") static (\n"
                + "  def s = 1\n"
                + "  def bad = null\n"
                + ")\n"
                + "def I = interface ( def req ; def deflt = fn (a) -> ( 1 ) )\n"
                + "C(1, \"bad\").z = 2\n"
                + "C.s = \"wrong\"",
                "2:3 Missing type : m",
                "3:3 Missing type : n",
                "6:3 Missing type : a (recursive)",
                "10:3 Missing type : bad",
                "12:21 Missing type : req",
                "12:47 Missing type : a",
                "13:6 Type error : expected int, got string",
                "13:17 Type error : expected string, got int",
                "14:7 Type error : expected int, got string");
    }

    @Test
    public void testStrictS3S4DefInferenceAndWidening() {
        assertDiagnostics("def empty\n"
                + "def nulled = null\n"
                + "def m = null\n"
                + "m = \"ok\"\n"
                + "def len : int = m.nope\n"
                + "def i = 0\n"
                + "i = \"a\"\n"
                + "def intNull = null\n"
                + "intNull = 1\n"
                + "def intUse : int = intNull\n"
                + "m = 1",
                "1:1 Missing type : empty",
                "2:1 Missing type : nulled",
                "5:17 Not a scope : m",
                "7:5 Type error : expected int, got string",
                "10:20 Type error : expected int, got int?",
                "11:5 Type error : expected string?, got int");
        // Widening int -> double across later assignments notifies earlier uses with the widening line.
        assertDiagnostics("def i = 1\n"
                + "def use : int = i\n"
                + "i = 1.5\n"
                + "def j = 10\n"
                + "def k : int = j\n"
                + "j = j / 2\n"
                + "def C = class () (\n"
                + "  def x = 1\n"
                + "  def f = fn () : int -> ( this.x )\n"
                + "  def g = fn mut () -> ( this.x = 1.5 )\n"
                + ")",
                "2:17 Type error : expected int, got double (i is double because of the assignment at line 3)",
                "5:15 Type error : expected int, got double (j is double because of the assignment at line 6)",
                "9:28 Type error : expected int, got double (x is double because of the assignment at line 10)");
    }

    @Test
    public void testStrictS5IfWhileAndReturns() {
        assertDiagnostics("def c = true\n"
                + "if c then ( 1 ) else ( \"a\" )\n"
                + "while c then ( 1 )\n"
                + "def a : any = if c then ( 1 ) else ( \"a\" )\n"
                + "def b = if c then ( 1 ) else ( \"a\" )\n"
                + "def d = if c then ( 1 )\n"
                + "def w = while c then ( 1 )\n"
                + "def f = fn (n : int) -> ( if n > 0 then ( return 1 ) ; \"s\" )\n"
                + "def bCheck : int = b\n"
                + "def dCheck : int = d\n"
                + "def wCheck : int = w\n"
                + "def fCheck : int = f(1)",
                "9:20 Type error : expected int, got int | string",
                "10:20 Type error : expected int, got int?",
                "11:20 Type error : expected int, got int?",
                "12:20 Type error : expected int, got string | int");
    }

    @Test
    public void testStrictS6JavaObjectInterfacesArraysInaccessible() {
        assertDiagnostics("def l = java.util.List.of(1, 2)\n"
                + "l.get(0).nope\n"
                + "def n : int = l.get(0) as int\n"
                + "def it = l.iterator()\n"
                + "def ok : bool = it.hasNext()\n"
                + "def ts : string = it.toString()\n"
                + "it.nope()\n"
                + "def parts = java.util.regex.Pattern.compile(\",\").split(\"a,b\")\n"
                + "def len : int = parts.size()\n"
                + "parts.nope\n"
                + "def p = java.lang.ProcessBuilder(java.util.Arrays.asList(parts)).start()\n"
                + "def code : int = p.exitValue()\n"
                + "p.nope",
                "2:1 Not a scope : l.get(0)",
                "7:1 No such name in java.util.Iterator : nope",
                "10:1 No such name in array of string : nope",
                "13:1 No such name in java.lang.Process : nope");
    }

    @Test
    public void testStrictS7OverloadAmbiguity() {
        assertDiagnostics("def sb = java.lang.StringBuilder()\n"
                + "sb.append(null)\n"
                + "def n : int = java.lang.Math.abs(1)",
                "2:1 Ambiguous call to java.lang.StringBuilder.append for (null)");
    }

    @Test
    public void testStrictS8S11ImplicitsBindingsAndDynamicImports() {
        assertDiagnostics("println(\"a\", 1, to: STDOUT)\n"
                + "println(1, to: \"bad\")\n"
                + "def ok : bool = assert(true, 123)\n"
                + "assert(1)\n"
                + "def b : bool = same(1, \"a\")\n"
                + "classof(1).nope\n"
                + "def sz : int = args.size()\n"
                + "def n : int = int(\"1\") + int(double(\"2.5\"))\n"
                + "def path = \"other.ba\"\n"
                + "def m = import(path)\n"
                + "m.foo\n"
                + "import(1)\n"
                + "externalBinding + 1",
                "2:16 Type error : expected java.lang.Appendable, got string",
                "4:8 Type error : expected bool, got int",
                "6:1 Not a scope : classof(1)",
                "11:1 Not a scope : m",
                "12:8 Type error : expected string, got int",
                "13:1 Not a number : externalBinding");
    }

    @Test
    public void testStrictS9Super() {
        assertDiagnostics("def I = interface ( def area : fn () : double ; def describe = fn () : string -> ( \"area=\" + this.area() ) )\n"
                + "def C = class () implements I (\n"
                + "  def area = fn () -> ( 1.0 )\n"
                + "  def describe = fn () : string -> ( super.describe() )\n"
                + "  def bad = fn () : int -> ( super(I).describe() )\n"
                + ")\n"
                + "def s : int = C().describe()",
                "5:30 Type error : expected int, got string",
                "7:15 Type error : expected int, got string");
    }

    @Test
    public void testSelfTailCallsWithoutBaseCase() throws Exception {
        // BIP024-D7 : a function that can only end in a self tail call and cannot throw would loop forever.
        assertDiagnostics("def r = fn (n : int) : int -> ( recurse(n + 1) )\n"
                + "def s = fn (n : int) : int -> ( if n > 0 then ( s(n - 1) ) else if n < 0 then ( return recurse(n + 1) ) else ( s(1) ) )\n"
                + "def W = class () ( def walk = fn (n : int) : int -> ( this.walk(n + 1) ) )\n"
                + "def a = fn (n : int) : int -> ( assert(n < 5) ; recurse(n + 1) )\n"
                + "def b = fn (n : int) : int -> ( while n > 3 then ( return n ) ; recurse(n + 1) )\n"
                + "def c = fn (n : int) : int -> ( 1 + recurse(n + 1) )\n"
                + "def d = fn (n : int) : int? -> ( if n > 0 then ( recurse(n - 1) ) )",
                "1:33 Recursion without a base case : r. If this is intended, wrap the expression with forever",
                "2:33 Recursion without a base case : s. If this is intended, wrap the expression with forever",
                "3:55 Recursion without a base case : walk. If this is intended, wrap the expression with forever");
        // forever acknowledges it, on the expression or on any expression of its tail paths.
        assertDiagnostics("def r = fn (n : int) : int -> ( forever recurse(n + 1) )\n"
                + "def s = fn (n : int) : int -> ( forever if n > 0 then ( s(n - 1) ) else ( s(1) ) )\n"
                + "def t = fn (n : int) : int -> ( if n > 0 then ( t(n - 1) ) else ( forever t(1) ) )\n"
                + "def u = fn (n : int) -> ( forever (while true then ( n += 1 )) )");
    }

    @Test
    public void testLoopsWithoutExit() throws Exception {
        // BIP024-D8 : while true with no break or return and nothing that can throw never ends.
        assertDiagnostics("def x = 0\n"
                + "while true then ( x += 1 )\n"
                + "def f = fn (): int -> ( def i = 0 ; while (true) then ( i += 1 ; while true then ( break ) ) ; i )\n"
                + "while true then ( if x > 3 then ( break ) ; x += 1 )\n"
                + "def g = fn (): int -> ( while true then ( return 1 ) ; 0 )\n"
                + "while true then ( assert(x < 9) ; x += 1 )\n"
                + "forever while true then ( x += 1 )",
                "2:1 Loop without an exit : while true. If this is intended, wrap the expression with forever",
                "3:37 Loop without an exit : while true. If this is intended, wrap the expression with forever");
    }

    @Test
    public void testStrictS10RecursionAndImportCycles() throws Exception {
        assertDiagnostics("def even = fn (n : int) -> ( if n == 0 then ( true ) else ( odd(n - 1) ) )\n"
                + "def odd = fn (n : int) -> ( if n == 0 then ( false ) else ( even(n - 1) ) )\n"
                + "def inf = fn (n : int) -> ( recurse(n - 1) )\n"
                + "def fac = fn (n : int) -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) )",
                "1:1 Missing type : even (recursive)",
                "2:1 Missing type : odd (recursive)",
                "3:1 Missing type : inf (recursive)",
                "3:29 Recursion without a base case : inf. If this is intended, wrap the expression with forever");

        // Import cycle across files reports Missing type : <name> (import cycle with <module>).
        org.babblelang.engine.MapModuleSource source = new org.babblelang.engine.MapModuleSource()
                .put("a.ba", "def b = import(\"b.ba\")\nexport def f = fn (n : int) : int -> ( b.g(n) )\n")
                .put("b.ba", "def a = import(\"a.ba\")\nexport def g = fn (n : int) : int -> ( a.f(n) )\nexport def C = class () ()\nexport def bad = fn (v : a.f) -> ( 1 )\nexport def x = a.f(1)\n");
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODULE_SOURCE, source);
        engine.put(ScriptEngine.FILENAME, "main.ba");
        StringWriter errors = new StringWriter();
        engine.getContext().setErrorWriter(errors);
        Assertions.assertThrows(ScriptException.class, () -> engine.compile("import(\"a.ba\").f(1)"));
        Assertions.assertTrue(errors.toString().contains("Missing type : f (import cycle with a.ba)"), errors.toString());
    }

    @Test
    public void testStrictS12S13LiteralsAndDynamicRemoved() {
        assertDiagnostics("def a = 2147483648\n"
                + "def b : int = -2147483648\n"
                + "def c = -2147483649\n"
                + "def d : dynamic = 1\n"
                + "def f = fn (x : dynamic) -> ( x )",
                "1:9 Integer out of range : 2147483648",
                "3:9 Integer out of range : -2147483649",
                "4:9 Unknown type : dynamic",
                "5:17 Unknown type : dynamic");
    }

    @Test
    public void testStrictReplClosedInference() throws Exception {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        StringWriter errors = new StringWriter();
        SimpleScriptContext context = new SimpleScriptContext();
        context.setWriter(new StringWriter());
        context.setErrorWriter(errors);
        org.babblelang.engine.BabbleSession session = engine.newSession(context);
        session.eval("def x = 1");
        session.eval("def C = class (def k = 1) ( def m = \"a\" )");
        session.eval("def c = C()");
        // Inference from input 1 is closed : widening x or c.k or mutating c.m incompatibly fails.
        ScriptException e1 = Assertions.assertThrows(ScriptException.class, () -> session.eval("x = 1.5"));
        Assertions.assertTrue(e1.getMessage().contains("Type error : expected int, got double"), e1.getMessage());
        ScriptException e2 = Assertions.assertThrows(ScriptException.class, () -> session.eval("c.k = 1.5"));
        Assertions.assertTrue(e2.getMessage().contains("Type error : expected int, got double"), e2.getMessage());
        ScriptException e3 = Assertions.assertThrows(ScriptException.class, () -> session.eval("c.m = 2"));
        Assertions.assertTrue(e3.getMessage().contains("Type error : expected string, got int"), e3.getMessage());
        // Unassigned null in REPL input 1 fails immediately in input 1.
        ScriptException e4 = Assertions.assertThrows(ScriptException.class, () -> session.eval("def u = null"));
        Assertions.assertTrue(e4.getMessage().contains("Missing type : u"), e4.getMessage());
    }

    @Test
    public void testTypeAliases() {
        // BIP023 : placement, annotation, recursion, values, and type arguments.
        assertDiagnostics("def Box of A = type list of A\n"
                        + "def L = type (int | L)?\n"
                        + "def T : int = type int\n"
                        + "def f = fn () -> ( type int )\n"
                        + "def v = Box\n"
                        + "def b : Box = list.of(1)\n"
                        + "def c : Box of (int, string) = list.of(1)\n"
                        + "def d : Box of int = list.of(\"x\")",
                "2:14 Recursive type alias : L",
                "3:9 A type alias takes no annotation : T",
                "4:20 A type alias is the value of a def : type int",
                "5:9 A type alias is not a value : Box",
                "6:9 Missing type arguments : Box",
                "7:9 Wrong number of type arguments : expected 1, got 2",
                "8:22 Type error : expected list(int), got list(string)");
    }

    @Test
    public void testIntersectionTypes() {
        // BIP022 : operands, nullable operands, member conflicts, and A & B as an argument.
        assertDiagnostics("def Shape = interface ( def area : fn () : double )\n"
                        + "def Named = interface ( def name : fn () : string )\n"
                        + "def Disk = class () implements Shape ( def area = fn () : double -> ( 1.0 ) )\n"
                        + "def A = interface ( def m : fn () : int )\n"
                        + "def B = interface ( def m : fn () : string )\n"
                        + "def bad1 : Disk & Named = Disk()\n"
                        + "def bad2 : Shape & Named? = null\n"
                        + "def bad3 = fn (x : A & B) : string -> ( \"\" + x.m() )\n"
                        + "def need = fn (x : Shape & Named) : string -> ( x.name() )\n"
                        + "need(Disk())",
                "6:12 Only interfaces intersect : Disk & Named",
                "7:20 Nullable inside an intersection : Named? (write (Shape & Named)?)",
                "8:46 Conflicting member m in A & B",
                "10:6 Type error : expected Shape & Named, got Disk");
    }

    @Test
    public void testIntersectionJavaAndFunctionalErrors() {
        assertDiagnostics("def Demo = import(\"org.babblelang.tests\").JavaClassDemo\n"
                        + "def Shape = interface ( def area : fn () : double )\n"
                        + "def Named = interface ( def name : fn () : string )\n"
                        + "def badSam1 : java.lang.Runnable & java.lang.AutoCloseable = fn () -> ()\n"
                        + "def badSam2 : Shape & java.lang.Runnable = fn () -> ()\n"
                        + "def badJava = fn (x : Demo.LeftInt & Demo.RightStrVal) : any? -> ( x.val() )\n"
                        + "def A = interface ( def tag : fn () : int )\n"
                        + "def badMix = fn (x : A & Demo.RightStr) : any? -> ( x.tag() )\n"
                        + "def f = overload ( fn (s : Shape) : int -> ( 1 ), fn (n : Named) : int -> ( 2 ) )\n"
                        + "def Tile = class () implements Shape, Named ( def area = fn () : double -> ( 1.0 ) ; def name = fn () : string -> ( \"t\" ) )\n"
                        + "def both : Shape & Named = Tile()\n"
                        + "f(both)",
                "4:62 Not a functional interface : java.lang.Runnable & java.lang.AutoCloseable",
                "5:44 Not a functional interface : Shape & java.lang.Runnable",
                "6:68 Conflicting member val in org.babblelang.tests.JavaClassDemo$LeftInt & org.babblelang.tests.JavaClassDemo$RightStrVal",
                "8:53 Conflicting member tag in A & org.babblelang.tests.JavaClassDemo$RightStr",
                "9:9 Ambiguous overloads of f : f(Shape & Named) fits fn (s : Shape) : int and fn (n : Named) : int");

        assertDiagnostics("def Demo = import(\"org.babblelang.tests\").JavaClassDemo\n"
                        + "def I = interface ( def ir : java.lang.Runnable & java.io.Serializable = fn () -> () )\n"
                        + "def badEff = fn () -> ( Demo.runSameSigGeneric(fn io () -> ( println(\"x\") )) )",
                "2:30 An interface default must have a function type : ir",
                "3:14 Function badEff violates pure guarantee (performs I/O or external mutation) : mark function as io");

        assertDiagnostics("def Base = interface ( def f = fn () : int -> ( 1 ) )\n"
                        + "def Sub = class () implements Base ( def run : java.lang.Runnable = fn () -> ( super.f() ) )\n"
                        + "def C = class () implements java.lang.Runnable ( def run : java.lang.Runnable = fn () -> () )\n"
                        + "def M = class () implements Base ( def m : (fn (int) : int) | java.lang.Runnable = fn () -> ( super.f() ) )\n"
                        + "def N1 = class () implements Base ( def r : java.lang.Runnable = fn () -> ( def g = fn () : int -> ( super.f() ) ; () ) )\n"
                        + "def N2 = record () implements Base ( def r : java.lang.Runnable = fn () -> ( def g = fn () : int -> ( super.f() ) ; () ) )\n"
                        + "def N3 = class () implements Base ( def r : (fn (int) : int) | java.lang.Runnable = fn () -> ( def g = fn () : int -> ( super.f() ) ; () ) )\n"
                        + "def Ok = class () implements Base ( def r : java.lang.Runnable = fn () -> ( def Inner = class () implements Base ( def g = fn () : int -> ( super.f() ) ) ; () ) )",
                "2:80 super outside of a method",
                "3:9 C does not implement java.lang.Runnable.run",
                "4:95 super outside of a method",
                "5:102 super outside of a method",
                "6:103 super outside of a method",
                "7:121 super outside of a method");

        assertDiagnostics("def A = interface ( def a : fn () : int )\n"
                        + "def RA = record () implements A ( def a = fn () : int -> ( 1 ) )\n"
                        + "def Box = class (def item : A) ()\n"
                        + "def BoxG of T = class (def xs : list of T) ()\n"
                        + "def OpMut = class () ( def plus = overload ( fn (mut x : A) : int -> ( 1 ), fn (x : int) : int -> ( 2 ) ) )\n"
                        + "def OpAlias = class () ( def plus = overload ( fn (x : A) : Box -> ( Box(x) ), fn (x : int) : Box -> ( Box(RA()) ) ) )\n"
                        + "def OpGen = class () ( def plus = overload ( fn of T (x : list of T) : int -> ( x.size() ), fn (x : int) : int -> ( x ) ) )\n"
                        + "def OpGenT = class () ( def plus = overload ( fn of T (x : list of T) : T -> ( x.get(0) ), fn (x : int) : int -> ( x ) ) )\n"
                        + "def fBox = overload ( fn (x : A) : Box -> ( Box(x) ), fn (x : int) : Box -> ( Box(RA()) ) )\n"
                        + "def fGenBox = overload ( fn of T (xs : list of T) : BoxG of T -> ( BoxG(xs) ), fn (x : int) : int -> ( x ) )\n"
                        + "def fSpread = overload ( fn (k : int, xs : A...) : Box -> ( def b = Box(RA()) ; for a in xs then ( b = Box(a) ) ; b ), fn (k : string, xs : A...) : Box -> ( Box(RA()) ) )\n"
                        + "def fa : frozen A = RA()\n"
                        + "def ua : frozen A | int = fa\n"
                        + "def fl : frozen list of int = freeze(list.of(1, 2))\n"
                        + "def ul : frozen list of int | int = fl\n"
                        + "def uli : list of int | int = list.of(3)\n"
                        + "def faArr : frozen array of A = freeze(array.of(RA() as A))\n"
                        + "def uks : int | string = 1\n"
                        + "def bad1 = OpMut() + fa\n"
                        + "def bad2 = OpAlias() + fa\n"
                        + "def bad3 = fBox(ua)\n"
                        + "def bad4 = fGenBox(ul)\n"
                        + "def bad5 = fSpread(uks, ...faArr)\n"
                        + "def ok1 : int = OpGen() + fl\n"
                        + "def ok2 : int = OpGenT() + ul\n"
                        + "def ok3 : int = OpGenT() + uli\n"
                        + "def fMutList = overload ( fn (k : int, xs : list of A) : list of A -> ( xs ), fn (k : string, xs : list of A) : int -> ( 0 ) )\n"
                        + "def fGenList = overload ( fn of T (k : int, xs : list of T) : list of T -> ( xs ), fn of T (k : string, xs : list of T) : int -> ( 0 ) )\n"
                        + "def faList : frozen list of RA = freeze(list(RA()))\n"
                        + "def bad6 = fMutList(uks, ...faList)\n"
                        + "def ok4 = fGenList(uks, ...faList)",
                "19:12 No applicable overload of OpMut().plus for (frozen A) : candidates are (mut x : A), (x : int)",
                "20:12 No applicable overload of OpAlias().plus for (frozen A) : candidates are (x : A), (x : int)",
                "21:12 No applicable overload of fBox for (frozen A | int) : candidates are (x : A), (x : int)",
                "22:12 No applicable overload of fGenBox for (frozen list of int | int) : candidates are (xs : list of T), (x : int)",
                "23:12 No applicable overload of fSpread for (int | string, ...frozen array of A) : candidates are (k : int, xs : A...), (k : string, xs : A...)",
                "30:12 No applicable overload of fMutList for (int | string, ...frozen list of RA) : candidates are (k : int, xs : list of A), (k : string, xs : list of A)");

        assertDiagnostics("def OpU = class () ( def plus = overload ( fn of (T, U) (x : T) : U? -> ( null ) ) )\n"
                        + "def badU = OpU() + 1",
                "2:12 Missing type : cannot infer type arguments for OpU().plus");

        assertDiagnostics("def OpNeg = class () ( def neg = overload ( fn of T () : T? -> ( null ), fn (x : int) : int -> ( -x ) ) )\n"
                        + "def OpNegL = class () ( def neg = overload ( fn of T () : list of T -> ( list.of() ), fn (x : int) : int -> ( -x ) ) )\n"
                        + "def OpSingleNeg = class () ( def neg = fn of T () : T? -> ( null ) )\n"
                        + "def OpSinglePlus = class () ( def plus = fn of T (x : T) : T -> ( x ) )\n"
                        + "def badN1 = -OpNeg()\n"
                        + "def badN2 = -OpNegL()\n"
                        + "def badN3 = -OpSingleNeg()\n"
                        + "def okP : int = OpSinglePlus() + 42",
                "5:13 Missing type : cannot infer type arguments for OpNeg().neg",
                "6:13 Missing type : cannot infer type arguments for OpNegL().neg",
                "7:13 Missing type : cannot infer type arguments for OpSingleNeg().neg");

        assertDiagnostics("def Shape = interface ( def area : fn () : int )\n"
                        + "def Named = interface ( def name : fn () : string )\n"
                        + "def Box = class () implements Shape, Named ( def area = fn () : int -> ( 1 ) ; def name = fn () : string -> ( \"b\" ) )\n"
                        + "def g = overload ( fn (xs : list of int, s : Shape) : int -> ( xs.size() ), fn (xs : array of int, n : Named) : int -> ( xs.size() ) )\n"
                        + "def u : Shape | Named = Box()\n"
                        + "def fs = set.of(1, 2)\n"
                        + "def badAmbig = g(...fs, u)",
                "7:16 Cannot dispatch g on Shape | Named : its alternatives are not told apart at runtime");

        assertDiagnostics("def MutA = class (def mut v : int) ( def inc = fn mut () -> ( this.v += 1 ) )\n"
                        + "def leakIter = fn (shared xs : java.lang.Iterable of MutA) : java.lang.Iterable of MutA -> ( xs )\n"
                        + "def fl : frozen list of MutA = freeze(list(MutA(1)))\n"
                        + "def badIter = leakIter(...fl)\n"
                        + "def idM = fn (xs : list of MutA) : list of MutA -> ( xs )\n"
                        + "def badMut = fn (xs : MutA...) : int -> ( def l = idM(...xs) ; l.get(0).inc() ; 0 )\n"
                        + "def idI = fn (xs : list of int) : list of int -> ( xs )\n"
                        + "def okMut = fn (xs : int...) : int -> ( def l = idI(...xs) ; l.add(99) ; l.size() )\n"
                        + "def takeU = fn (unique xs : list of MutA) : list of MutA -> ( xs )\n"
                        + "def ma = array.of(MutA(1))\n"
                        + "def badU = takeU(...ma)",
                "4:27 Type error : expected MutA elements, got frozen MutA",
                "6:64 Cannot mutate parameter xs (mut method l.get(0).inc) : mark parameter as mut xs",
                "11:21 Argument for unique parameter xs must be unique");

        assertDiagnostics("def takeRestSer = fn io (shared xs : (java.lang.Runnable & java.io.Serializable)...) : string -> ( \"\" )\n"
                        + "def takeBorrowed = fn (xs : (java.lang.Runnable & java.io.Serializable)...) : string -> ( \"\" )\n"
                        + "def fns = list(fn io () -> ())\n"
                        + "def ffns = freeze(list(fn io () -> ()))\n"
                        + "def ok1 = takeRestSer(...fns)\n"
                        + "def ok2 = takeBorrowed(...ffns)\n"
                        + "def bad1 = takeRestSer(...ffns)",
                "7:27 Type error : expected java.lang.Runnable & java.io.Serializable elements, got frozen (io () : null)");
    }

    @Test
    public void testP6aUnionAndNullableTypes() {
        // Normalization, T?, null type, any vs any?, non-nullability of plain types.
        assertDiagnostics("def a : int | null = null\n"
                        + "def b : int? = a\n"
                        + "def c : (int | string) | int? = \"x\"\n"
                        + "def d : any = c\n"
                        + "def e : null = null\n"
                        + "def bad1 : int = b\n"
                        + "def bad2 : string = c\n"
                        + "def bad3 : any = b\n"
                        + "def bad4 : fn (x : int) : int = null\n"
                        + "def bad5 : any? = null ; def bad6 : any = bad5",
                "4:15 Type error : expected any, got int | string | null",
                "6:18 Type error : expected int, got int?",
                "7:21 Type error : expected string, got int | string | null",
                "8:18 Type error : expected any, got int?",
                "9:33 Type error : expected (x : int) : int, got null",
                "10:43 Type error : expected any, got any?");

        // Reassignment widening: int <-> null notifies earlier uses; incompatible reassignments still fail.
        assertDiagnostics("def z = 1\n"
                        + "def u : int = z\n"
                        + "z = null\n"
                        + "def n = null\n"
                        + "n = 1\n"
                        + "n = 2.5\n"
                        + "def badD : int = n\n"
                        + "def s = \"a\"\n"
                        + "s = 1",
                "2:15 Type error : expected int, got int? (z is int? because of the assignment at line 3)",
                "7:18 Type error : expected int, got double?",
                "9:5 Type error : expected string, got int");

        // Shared members, operator methods, and numeric arithmetic/comparison on unions.
        assertDiagnostics("def A = class (def tag : string = \"A\", def onlyA = 1) ( def plus = fn (k : int) : int -> ( k ) )\n"
                        + "def B = class (def tag : string = \"B\") ( def plus = fn (k : int) : int -> ( k * 2 ) )\n"
                        + "def C = class (def tag : string = \"C\") ()\n"
                        + "def f = fn (ab : A | B, abc : A | B | C, n : int | double, m : int | string) -> (\n"
                        + "  def t : string = ab.tag\n"
                        + "  def p : int = ab + 1\n"
                        + "  def sum : double = n + 1\n"
                        + "  def neg : double = -n\n"
                        + "  def cmp : bool = n < 10\n"
                        + "  ab.onlyA\n"
                        + "  abc + 1\n"
                        + "  m + 1\n"
                        + "  m < 1\n)",
                "10:3 No such member : onlyA",
                "11:3 No such member : plus",
                "12:3 Not a number : m",
                "13:3 Not comparable : m and 1");

        // Subtraction narrowing (`else` after `is`), `== null`/`!= null`/truthiness, and invalidation.
        assertDiagnostics("def N = class (def v : int, def next : N?) ()\n"
                        + "def f = fn (x : int | string, y : string?, node : N?) -> (\n"
                        + "  def ok1 : string = if x is int then ( \"\" + (x + 1) ) else ( x + \"!\" )\n"
                        + "  def ok2 : string = if y is any then ( y + \"!\" ) else ( \"nil\" )\n"
                        + "  def ok3 : string = if y == null then ( \"nil\" ) else ( y + \"!\" )\n"
                        + "  def ok4 : int = if node then ( node.v ) else ( 0 )\n"
                        + "  def cur : N? = node\n"
                        + "  while cur != null then ( def val : int = cur.v ; cur = cur.next )\n"
                        + "  cur.v\n"
                        + ")\n"
                        + "def g = fn (x : string?) -> (\n"
                        + "  if x != null then (\n"
                        + "    def cb = fn () -> ( x + \"!\" )\n"
                        + "    x = null\n"
                        + "    x + \"!\"\n"
                        + "  )\n)",
                "9:3 Not a scope : cur",
                "13:25 Not a number : x",
                "13:29 Not a number : \"!\"",
                "15:5 Not a number : x",
                "15:9 Not a number : \"!\"");

        // Casts with unions/nullables and Java overload resolution.
        assertDiagnostics("def f = fn (v : any?, n : int?, u : int | string) -> (\n"
                        + "  def a : int? = v as int?\n"
                        + "  def b : (int | string) = v as (int | string)\n"
                        + "  def s : string = java.lang.String.valueOf(u)\n"
                        + "  java.lang.Math.abs(n)\n"
                        + "  v as (fn (k : int) : int)?\n)",
                "5:3 No applicable overload of java.lang.Math.abs for (int?)",
                "6:8 Cannot cast to a function type");
        assertWarnings("def u = 1 as int?\ndef w = 1 as (int | string)",
                "1:9 Redundant cast : int?",
                "2:9 Redundant cast : int | string");
    }

    @Test
    public void testFunctionReturnWideningSoundness() {
        assertDiagnostics("def f = fn () -> ( 1 )\n"
                        + "def bad1 : fn () : double = f\n"
                        + "def bad2 : fn () : double = fn () : int -> ( 1 )\n"
                        + "def take = fn (cb : fn () : double) : double -> ( cb() )\n"
                        + "take(f)\n"
                        + "take(fn () : int -> ( 1 ))\n"
                        + "take(fn () -> ( 1 ))\n"
                        + "def okWrapped : fn () : double = ( fn () -> ( 1 ) )\n"
                        + "def opt = fn () : int? -> ( 1 )\n"
                        + "def bad3 : fn () : double? = opt\n"
                        + "def uni = fn () : int | string -> ( 1 )\n"
                        + "def bad4 : fn () : double | string = uni\n"
                        + "def hoBad : fn (cb : fn () : int) : int = fn (cb : fn () : double) : int -> ( 1 )\n"
                        + "def nestedBad : fn () : (fn () : double) = fn () : fn () : int -> ( fn () : int -> ( 1 ) )\n"
                        + "def okUnion : fn () : int | double = fn () : int -> ( 1 )\n"
                        + "def okAny : fn () : any = fn () : int -> ( 1 )\n"
                        + "def okCtxOpt : fn () : double? = fn () -> ( 1 )\n"
                        + "def okCtxNested : fn () : fn () : double = fn () -> ( fn () -> ( 1 ) )\n"
                        + "def okParamWiden = fn (cb : fn (x : int) : double) : double -> ( cb(1) )\n"
                        + "okParamWiden(fn (d : double) : double -> ( d ))",
                "2:29 Type error : expected () : double, got () : int",
                "3:29 Type error : expected () : double, got () : int",
                "5:6 Type error : expected () : double, got () : int",
                "6:6 Type error : expected () : double, got () : int",
                "10:30 Type error : expected () : double?, got () : int?",
                "12:38 Type error : expected () : double | string, got () : int | string",
                "13:43 Type error : expected (cb : () : int) : int, got (cb : () : double) : int",
                "14:44 Type error : expected () : () : double, got () : () : int");
    }

    @Test
    public void testInterfaceMemberTypeConformance() {
        String iface = "def I = interface (\n"
                + "  def area : fn () : double\n"
                + "  def count : int\n"
                + "  def show = fn () : string -> ( \"I\" )\n"
                + ")\n";
        // Classes, value classes, objects, and extending interfaces rejecting mismatched member types.
        assertDiagnostics(iface
                        + "def Bad1 = class () implements I ( def area = fn () : int -> ( 1 ) ; def count : string = \"a\" ; def show = fn () -> ( 1 ) )\n"
                        + "def Bad2 = class () implements I ( def area = fn () -> ( 1 ) ; def count = 1.5 )\n"
                        + "def Bad3 = class () implements I ( def area = 1 ; def count = fn (n : int) -> ( n ) )\n"
                        + "def BadArity = class () implements I ( def area = fn (n : int) : double -> ( 1.0 * n ) ; def count = 1 )\n"
                        + "def BadParam = interface ( def apply : fn (x : int) : int )\n"
                        + "def BadParamImpl = class () implements BadParam ( def apply = fn (x : string) : int -> ( 1 ) )\n"
                        + "def BadVal = record (area : fn () : double, count : string) implements I ()\n"
                        + "def BadHeader = class (def area : fn () : double, def count : bool) implements I ()\n"
                        + "def badObj = object implements I ( def area = fn () -> ( 1 ) ; def count = \"no\" )\n"
                        + "def Sub = interface extends I ( def area : fn () : int )",
                "6:36 Type error : expected () : double, got () : int",
                "6:70 Type error : expected int, got string",
                "6:97 Type error : expected () : string, got () : int",
                "7:36 Type error : expected () : double, got () : int",
                "7:64 Type error : expected int, got double",
                "8:36 Type error : expected () : double, got int",
                "8:51 Type error : expected int, got (n : int) : int",
                "9:40 Type error : expected () : double, got (n : int) : double",
                "11:51 Type error : expected (x : int) : int, got (x : string) : int",
                "12:45 Type error : expected int, got string",
                "13:51 Type error : expected int, got bool",
                "14:36 Type error : expected () : double, got () : int",
                "14:64 Type error : expected int, got string",
                "15:33 Type error : expected () : double, got () : int");

        // Inherited defaults conflicting with another interface's requirement.
        assertDiagnostics("def A = interface ( def m : fn () : double )\n"
                        + "def B = interface ( def m = fn () -> ( 1 ) )\n"
                        + "def C = class () implements A, B ()\n"
                        + "def J = interface extends A, B ()",
                "3:9 Type error : expected () : double, got () : int",
                "4:9 Type error : expected () : double, got () : int");

        // Sound covariance, contravariance, optional trailing parameters, and widened fields pass cleanly.
        assertDiagnostics("def Base = interface ( def tag : string )\n"
                        + "def Sub = class (def tag : string) implements Base ()\n"
                        + "def Service = interface (\n"
                        + "  def get : fn () : Base\n"
                        + "  def opt : fn () : string?\n"
                        + "  def accept : fn (x : int) : int\n"
                        + "  def val : double\n"
                        + "  def ok = fn () -> ( 1 )\n"
                        + ")\n"
                        + "def Child = interface extends Service ()\n"
                        + "def Impl = class () implements Child (\n"
                        + "  def get = fn () : Sub -> ( Sub(\"s\") )\n"
                        + "  def opt = fn () : string -> ( \"s\" )\n"
                        + "  def accept = fn (x : any, extra = 0) : int -> ( extra )\n"
                        + "  def val = 1\n"
                        + "  def widen = fn mut () -> ( this.val = 1.5 )\n"
                        + ")");
    }

    @Test
    public void testP6bGenerics() {
        // Positive cases: Babble generics, Java generics, bounds, value-class covariance, is/as raw narrowing, nullability.
        assertDiagnostics("def id of T = fn (x : T) : T -> ( x )\n"
                + "def Box of T = record (def value : T) (\n"
                + "  def mapBox of U = fn (f : fn (x : T) : U) : Box of U -> ( Box(f(this.value)) )\n"
                + ")\n"
                + "def n : int = id(1)\n"
                + "def idStr : fn (s : string) : string = id\n"
                + "def s : string = idStr(\"ok\")\n"
                + "def bi : Box of int = Box(5)\n"
                + "def bu : Box of (int | double) = bi\n"
                + "def ba : Box of any = bi\n"
                + "def mb : (Box of int)? = bi\n"
                + "def narrowed : int = if mb is Box then ( mb.value ) else ( 0 )\n"
                + "def rawCast : any = (\"hi\" as any)\n"
                + "def jl : java.util.List of string = java.util.ArrayList()\n"
                + "def first : string? = jl.get(0)\n"
                + "def takeNullable of T = fn (x : T) : any? -> ( x )\n"
                + "takeNullable(1)");

        // Negative cases: missing type args, wrong arity, uninferred, bounds, covariance, as targets, T vs any, static scope.
        assertDiagnostics("def Box of T = class (def value : T) () static ( def bad : T? = null )\n"
                + "def id of T = fn (x : T) : T -> ( x )\n"
                + "def rawAnnot : java.util.List = java.util.List.of(1)\n"
                + "def rawBabble : Box = Box(1)\n"
                + "def badArity : Box of (int, string) = Box(1)\n"
                + "def badPrimArg : int of string = 1\n"
                + "def badTvArg of T = fn (x : T of int) -> ( x )\n"
                + "def uninferredJava = java.util.ArrayList()\n"
                + "def polyFn = id\n"
                + "def bi : Box of int = Box(1)\n"
                + "def bd : Box of double = bi\n"
                + "def anyVal : any = bi\n"
                + "def paramCast = anyVal as Box of int\n"
                + "def tvCast of T = fn (x : any) -> ( x as T )\n"
                + "def tvToAny of T = fn (x : T) : any -> ( x )\n"
                + "def jl : java.util.List of string = java.util.ArrayList()\n"
                + "jl.add(42)\n"
                + "def il = java.util.List.of(1, 2)\n"
                + "def badElem : int = il.get(0)\n"
                + "def badElemCast = il.get(0) as string\n"
                + "def ptList = java.util.List.of(object ())\n"
                + "java.util.Collections.sort(ptList)",
                "1:60 Unknown type : T",
                "3:16 Missing type arguments : java.util.List",
                "4:17 Missing type arguments : Box",
                "5:16 Wrong number of type arguments : expected 1, got 2",
                "6:18 Wrong number of type arguments : expected 0, got 1",
                "7:29 Wrong number of type arguments : expected 0, got 1",
                "8:22 Missing type : cannot infer type arguments for java.util.ArrayList",
                "11:26 Type error : expected Box(double), got Box(int)",
                "13:27 Cannot cast to parameterized type : Box of int",
                "14:42 Cannot cast to type parameter : T",
                "15:42 Type error : expected any, got T",
                "17:1 No applicable overload of java.util.List.add for (int)",
                "20:19 Type error : expected string, got int",
                "22:1 Type error : type argument object does not satisfy bound T of java.util.Collections.sort");

        org.babblelang.analysis.AnalysisException dup = Assertions.assertThrows(
                org.babblelang.analysis.AnalysisException.class,
                () -> diagnostics("def f of (T, T) = fn (x : T) -> ( x )"));
        Assertions.assertTrue(dup.getMessage().contains("Name already defined : T"), dup.getMessage());

        org.babblelang.analysis.AnalysisException misplaced = Assertions.assertThrows(
                org.babblelang.analysis.AnalysisException.class,
                () -> diagnostics("def bad of T = 42"));
        Assertions.assertTrue(misplaced.getMessage().contains("Type parameters only apply to functions, classes, interfaces, and type aliases : bad"), misplaced.getMessage());
    }

    @Test
    public void testSafeCallOperator() {
        // Positive cases: methods, fields, chaining, unions, interfaces, generics, Java interop, widening, late null reassignment.
        assertDiagnostics("def B = class (def v : int) ( def g = fn () : int -> ( this.v ) )\n"
                + "def A = class (def x : int, def next : A? = null, def cb : (fn () : int)? = null) (\n"
                + "  def f = fn (d : int = 1) : B -> ( B(this.x + d) )\n"
                + "  def getInt = fn () : int -> ( this.x )\n"
                + "  def id of T = fn (v : T) : T -> ( v )\n"
                + ")\n"
                + "def opt : A? = A(1, A(2))\n"
                + "def b : B? = opt?.f()\n"
                + "def v : int? = opt?.next?.f(3)?.g()\n"
                + "def x : int? = opt?.x\n"
                + "def d : double? = opt?.getInt()\n"
                + "def func : (fn (n : int) : int)? = opt?.id\n"
                + "def optCb : (fn () : int)? = opt?.cb\n"
                + "def I = interface ( def k : int )\n"
                + "def C = class (def k : int) implements I ()\n"
                + "def D = class (def k : int) implements I ()\n"
                + "def u : C | D | null = C(5)\n"
                + "def k : int? = u?.k\n"
                + "def iface : I? = u\n"
                + "def ik : int? = iface?.k\n"
                + "def jl : (java.util.List of B)? = java.util.List.of(B(9))\n"
                + "def jv : int? = jl?.get(0)?.v\n"
                + "def late = A(4)\n"
                + "def lateB : B? = late?.f()\n"
                + "late = null");

        // Negative cases: provably non-null LHS, regular '.' after '?.', (a?.f)(), nullable callee, null?., this?., any?., narrowed.
        assertDiagnostics("def B = class (def v : int) ( def g = fn () : int -> ( this.v ) )\n"
                + "def A = class (def x : int, def nullableCb : (fn () : int)? = null) (\n"
                + "  def f = fn () : B -> ( B(this.x) )\n"
                + "  def m = fn () -> ( this?.f() )\n"
                + ")\n"
                + "def a : A = A(1)\n"
                + "def bad1 : B? = a?.f()\n"
                + "def bad2 : B? = A(1)?.f()\n"
                + "def bad3 : B = (A(1) as A?)?.f()\n"
                + "def opt : A? = a\n"
                + "def bad4 = opt?.f().g()\n"
                + "def bad5 = (opt?.f)()\n"
                + "def bad6 = opt?.nullableCb()\n"
                + "def bad7 = null?.f()\n"
                + "def anyVal : any = a\n"
                + "def bad8 = anyVal?.f()\n"
                + "def narrowedCheck = if opt != null then ( opt?.f() ) else ( null )\n"
                + "def narrowedIs = if opt is A then ( opt?.f() ) else ( null )\n"
                + "def narrowedTruth = if opt then ( opt?.f() ) else ( null )\n"
                + "def uNonNull : A | B = a\n"
                + "def bad9 = uNonNull?.nonexistent()\n"
                + "def i = 1\n"
                + "def bad10 = i?.toString()",
                "4:22 Not nullable : this",
                "7:17 Not nullable : a",
                "8:17 Not nullable : A(1)",
                "9:16 Type error : expected B, got B?",
                "11:12 Not a scope : opt?.f()",
                "12:12 (opt?.f) is not callable",
                "13:12 opt?.nullableCb is not callable",
                "14:12 Not a scope : null",
                "16:12 Not nullable : anyVal",
                "17:43 Not nullable : opt",
                "18:37 Not nullable : opt",
                "19:35 Not nullable : opt",
                "21:12 Not nullable : uNonNull",
                "23:13 Not nullable : i");

        org.babblelang.analysis.AnalysisException superSafe = Assertions.assertThrows(
                org.babblelang.analysis.AnalysisException.class,
                () -> diagnostics("def I = interface ( def m = fn () -> ( 1 ) )\n"
                        + "def C = class () implements I ( def m = fn () -> ( super?.m() ) )"));
        Assertions.assertTrue(superSafe.getMessage().contains("super must be called directly"), superSafe.getMessage());

        // Safe assignment a?.x = 1 is rejected as an invalid assignment target.
        org.babblelang.analysis.AnalysisException safeAssign = Assertions.assertThrows(
                org.babblelang.analysis.AnalysisException.class,
                () -> diagnostics("def A = class (def x : int) () ; def a : A? = null ; a?.x = 1"));
        Assertions.assertTrue(safeAssign.getMessage().contains("Cannot assign to expression"), safeAssign.getMessage());
    }

    @Test
    public void testP7CallSiteParameterInference() {
        // Functions, widening (int -> double, T <-> null), named/default args, methods, operators, constructors, recursion.
        assertDiagnostics("def area = fn (w, h) -> ( w * h )\n"
                + "area(2, 3)\n"
                + "def d : double = area(1.5, 2)\n"
                + "def opt = fn (a = null, b = 10) -> ( (if a == null then ( \"none\" ) else ( a )) + \":\" + b )\n"
                + "def o1 : string = opt()\n"
                + "def o2 : string = opt(b: 5, a: \"ok\")\n"
                + "def Point = record (x, y) (\n"
                + "  def plus = fn (o) -> ( Point(this.x + o.x, this.y + o.y) )\n"
                + "  def scale = fn (k) -> ( this.move(this.x * k, this.y * k) )\n"
                + "  private def move = fn (nx, ny) -> ( Point(nx, ny) )\n"
                + ") static (\n"
                + "  def make = fn (a, b) -> ( this(a, b) )\n"
                + ")\n"
                + "def p1 : Point = Point.make(1, 2)\n"
                + "def p2 : Point = p1 + Point(1.5, 3)\n"
                + "def p3 : Point = p2.scale(2)\n"
                + "def pOpt : Point? = p3\n"
                + "def p4 : Point? = pOpt?.scale(3)\n"
                + "def okIs : bool = p1 is Point\n"
                + "def obj = object ( def greet = fn (who) -> ( \"hi \" + who ) )\n"
                + "def g : string = obj.greet(\"world\")\n"
                + "def fact = fn (n) -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) )\n"
                + "def f5 : int = fact(5)\n"
                + "def even = fn (n) : bool -> ( if n == 0 then ( true ) else ( odd(n - 1) ) )\n"
                + "def odd = fn (n) : bool -> ( if n == 0 then ( false ) else ( even(n - 1) ) )\n"
                + "def e4 : bool = even(4)\n"
                + "def Box of T = class (def v : T) ( def tag = fn (label) -> ( label ) )\n"
                + "def b : Box of int = Box(1)\n"
                + "def lbl : string = b.tag(\"box\")");

        // Conflicts in source order and widening notes for earlier diagnostics.
        assertDiagnostics("def area = fn (w, h) -> ( w * h )\n"
                        + "def bad : int = area(2, 3)\n"
                        + "area(1.5, 2)\n"
                        + "area(\"x\", 1)\n"
                        + "def check = fn (x) -> ( def i : int = x )\n"
                        + "check(1)\n"
                        + "check(2.5)",
                "2:17 Type error : expected int, got double (w is double because of the call at line 3)",
                "4:6 Type error : expected double, got string",
                "5:39 Type error : expected int, got double (x is double because of the call at line 7)");

        // Exclusions: escaping functions, exported defs/classes, interface implementations, escaping classes, never-called & null-only.
        assertDiagnostics("def esc1 = fn (x) -> ( x )\n"
                        + "def ref = esc1\n"
                        + "esc1(1)\n"
                        + "def esc2 = fn (x) -> ( x )\n"
                        + "def apply = fn (cb : fn (a : int) : int, v : int) -> ( cb(v) )\n"
                        + "apply(esc2, 1)\n"
                        + "def esc3 = fn (x) -> ( if false then ( return recurse ) ; fn (v : int) -> ( v ) )\n"
                        + "esc3(1)\n"
                        + "def reass = fn (x) -> ( x )\n"
                        + "reass = fn (x : int) -> ( x )\n"
                        + "reass(1)\n"
                        + "export def expFn = fn (x) -> ( x )\n"
                        + "expFn(1)\n"
                        + "export def ExpCls = class (hx) (\n"
                        + "  def pubM = fn (y) -> ( y )\n"
                        + "  private def privM = fn (z) -> ( this.pubM(z) )\n"
                        + "  def callPriv = fn () -> ( this.privM(1) )\n"
                        + ") static (\n"
                        + "  def st = fn (w) -> ( w )\n"
                        + ")\n"
                        + "ExpCls(1).pubM(2)\n"
                        + "ExpCls.st(3)\n"
                        + "def Iface = interface ( def deflt = fn (a) -> ( a ) )\n"
                        + "def BabbleI = interface ( def m : fn (x : int) : int )\n"
                        + "def BabbleImpl = class () implements BabbleI ( def m = fn (x) -> ( x ) )\n"
                        + "BabbleImpl().m(1)\n"
                        + "def JavaImpl = class () implements java.util.function.IntUnaryOperator ( def applyAsInt = fn (x) -> ( x ) )\n"
                        + "JavaImpl().applyAsInt(1)\n"
                        + "def EscCls = class (x) ()\n"
                        + "def Alias = EscCls\n"
                        + "EscCls(1)\n"
                        + "def EscThis = class (x) () static ( def self = fn () -> ( this ) )\n"
                        + "EscThis(1)\n"
                        + "def never = fn (x) -> ( x )\n"
                        + "def onlyNull = fn (x) -> ( x )\n"
                        + "onlyNull(null)",
                "1:16 Missing type : x",
                "4:16 Missing type : x",
                "7:16 Missing type : x",
                "9:17 Missing type : x",
                "12:24 Missing type : x",
                "14:28 Missing type : hx",
                "15:18 Missing type : y",
                "19:16 Missing type : w",
                "23:41 Missing type : a",
                "25:60 Missing type : x",
                "27:95 Missing type : x",
                "29:21 Missing type : x",
                "32:22 Missing type : x",
                "34:17 Missing type : x",
                "35:20 Missing type : x");
    }

    @Test
    public void testBip021P1OptionalAndNamedParameters() {
        assertDiagnostics("def badOpt : fn (x : int, y : int = ...) : int = fn (x : int, y : int) : int -> ( x + y )\n"
                        + "def badKw : fn (x : int, y : int) : int = fn (x : int, ..., y : int) : int -> ( x + y )\n"
                        + "def f = fn (a : int, b : int, c : int = 0) : int -> ( a + b + c )\n"
                        + "f(b: 2, 1)\n"
                        + "f(1, a: 2)\n"
                        + "f(a: 1, a: 2)\n"
                        + "def kw = fn (a : int, ..., b : int) : int -> ( a + b )\n"
                        + "kw(1, 2)\n"
                        + "kw(1)\n"
                        + "kw(1, b: 2)\n"
                        + "def okOpt : fn (x : int, y : int = ...) : int = fn (a : int, b : int = 5) : int -> ( a + b )\n"
                        + "okOpt(x: 1)\n"
                        + "okOpt(y: 2, x: 1)",
                "1:50 Type error : expected (x : int, y : int = ...) : int, got (x : int, y : int) : int",
                "2:43 Type error : expected (x : int, y : int) : int, got (x : int, ..., y : int) : int",
                "4:1 Named argument out of position : b",
                "5:1 Parameter given twice : a",
                "6:1 Parameter given twice : a",
                "8:1 Too many parameters : 1 declared, 2 given",
                "9:1 Missing parameter : b");
    }

    @Test
    public void testBip021P3GenericFunctionsAndBounds() {
        assertDiagnostics("def Plain = class () ()\n"
                        + "def biggest of (T : java.lang.Comparable of T) = fn (a : T, b : T) : T -> ( if a.compareTo(b) > 0 then ( a ) else ( b ) )\n"
                        + "biggest(3, 7)\n"
                        + "biggest(\"a\", \"b\")\n"
                        + "biggest(Plain(), Plain())\n"
                        + "def Shape = interface ( def area : fn () : double )\n"
                        + "def Named = interface ( def name : fn () : string )\n"
                        + "def OnlyShape = class () implements Shape ( def area = fn () : double -> ( 1.0 ) )\n"
                        + "def show of (T : Shape & Named) = fn (x : T) : string -> ( x.name() + \":\" + x.area() )\n"
                        + "show(OnlyShape())\n"
                        + "def both = fn (f : fn of T (t : T) : T) : string -> ( \"\" + f(1) + f(\"a\") )\n"
                        + "both(fn (t : int) : int -> ( t ))\n"
                        + "both(fn (t) -> ( t + 1 ))\n"
                        + "def badDefault = fn of T (a : T, b : T = null) : T -> ( b )\n"
                        + "def optGen = fn of T (x : T? = null) : T? -> ( x )\n"
                        + "optGen()\n"
                        + "def okOptGen : int? = optGen()",
                "5:1 Type error : type argument Plain does not satisfy bound java.lang.Comparable(Plain)",
                "10:1 Type error : type argument OnlyShape does not satisfy bound Shape & Named",
                "12:6 Type error : expected fn of T (t : T) : T, got fn (t : int) : int",
                "13:18 Not a number : t",
                "14:42 Type error : expected T, got null",
                "16:1 Missing type : cannot infer type arguments for optGen");

        org.babblelang.analysis.AnalysisException twice = Assertions.assertThrows(
                org.babblelang.analysis.AnalysisException.class,
                () -> diagnostics("def f of T = fn of T (t : T) : T -> ( t )"));
        Assertions.assertTrue(twice.getMessage().contains("Type parameters declared twice : f"), twice.getMessage());
    }

    @Test
    public void testAllScriptsCompileCleanly() throws Exception {
        // Every .ba test script and example compiles cleanly with static typing.
        List<Path> all = new java.util.ArrayList<>();
        for (String s : ScriptTestCase.data()) {
            all.add(Path.of(s));
        }
        all.addAll(ExamplesTestCase.scripts());

        int passed = 0;
        List<String> report = new java.util.ArrayList<>();
        for (Path script : all) {
            Path file = script.toAbsolutePath().normalize();
            StringWriter errors = new StringWriter();
            BabbleScriptEngine scriptEngine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
            scriptEngine.put(BabbleScriptEngine.MODULE_SOURCE, org.babblelang.engine.FileModuleSource.rooted(file.getParent()));
            scriptEngine.getContext().setErrorWriter(errors);
            scriptEngine.getContext().setWriter(new StringWriter());
            scriptEngine.getContext().setAttribute(ScriptEngine.FILENAME, file.toString(), ScriptContext.ENGINE_SCOPE);
            try {
                scriptEngine.compile(Files.readString(file));
                passed++;
            } catch (ScriptException e) {
                long lines = errors.toString().lines().count();
                String first = errors.toString().lines().findFirst().orElse(e.getMessage());
                report.add(script + " (" + lines + " diagnostics; first: " + first + ")");
            }
        }
        Assertions.assertFalse(all.isEmpty());
        Assertions.assertTrue(report.isEmpty(), passed + "/" + all.size() + " clean:\n" + String.join("\n", report));
    }

    private static Object run(String script, String mode, StringWriter errors) throws ScriptException {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        ScriptContext context = new SimpleScriptContext();
        context.setErrorWriter(errors);
        context.setWriter(new StringWriter());
        return engine.eval(script, context);
    }
}
