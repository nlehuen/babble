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
 * Golden tests of the type checker (docs/design/type-system.md) : for each snippet, the
 * diagnostics expected, as {@code line:column message}.
 */
public class TypeCheckerTestCase {
    private static List<String> diagnostics(String source) {
        BabbleParser parser = new BabbleParser(new CommonTokenStream(new BabbleLexer(CharStreams.fromString(source))));
        parser.setErrorHandler(new BailErrorStrategy());
        ScopeAnalysis analysis = BlockScopes.analyze(parser.file());
        return analysis.typeDiagnostics().stream().map(w -> w.line() + ":" + w.column() + " " + w.message()).toList();
    }

    private static void assertDiagnostics(String source, String... expected) {
        Assertions.assertEquals(List.of(expected), diagnostics(source), source);
    }

    static Collection<String> scripts() {
        return ScriptTestCase.data();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scripts")
    public void testTheScriptsCheckCleanly(String file) throws Exception {
        assertDiagnostics(Files.readString(Path.of(file)));
    }

    @Test
    public void testUnannotatedCodeIsNeverReported() {
        // Gradual typing : everything here is dynamic, wrong or not.
        assertDiagnostics("def f = (a, b) -> ( a + b ) ; f(1, 2) ; f(\"x\", 2)\n"
                + "def g = (x) -> ( x.foo(1) ) ; g(1)\n"
                + "def i = 0 ; i = i + 1 ; i.y ; def n = null ; n + 1");
    }

    @Test
    public void testAnnotatedDefinitions() {
        assertDiagnostics("def a : int = \"s\"\ndef b : string = 1\ndef c : double = 1\ndef d : bool = 1 < 2\ndef e : int = 1.5",
                "1:15 Type error : expected int, got string",
                "2:18 Type error : expected string, got int",
                "5:15 Type error : expected int, got double");
        // T2 : numbers and booleans are never null, everything else may be.
        assertDiagnostics("def a : int = null\ndef b : string = null\ndef c : bool\ndef d : double = if true then ( 1 ) else ( 2.5 )",
                "1:15 Type error : expected int, got null",
                "3:1 Type error : expected bool, got null");
        assertDiagnostics("def a : int = 1\na = \"x\"\ndef f = (n : int) -> ( n = 2.5 )",
                "2:5 Type error : expected int, got string",
                "3:28 Type error : expected int, got double");
    }

    @Test
    public void testUnknownTypes() {
        assertDiagnostics("def a : Unknown = 1\ndef f = (x : java.util.Nope) -> ( x )\ndef v = 1\ndef g = (x : v) -> ( x )",
                "1:9 Unknown type : Unknown",
                "2:14 Unknown type : java.util.Nope",
                "4:14 Unknown type : v");
    }

    @Test
    public void testCalls() {
        String f = "def f = (a : int, b : string = \"\") : int -> ( a )\n";
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
        assertDiagnostics("def f = (n : int) : string -> ( n )\ndef g = (n : int) : int -> ( if n > 0 then ( return \"a\" ) else ( n ) )\n"
                        + "def h = () : int -> ( )",
                "1:33 Type error : expected string, got int",
                "2:53 Type error : expected int, got string",
                "3:21 Type error : expected int, got null");
    }

    @Test
    public void testResultsOfBranchesAndRecursion() {
        // if without else may be null : joined with int, nothing is known.
        assertDiagnostics("def k = (n : int) : int -> ( if n > 0 then ( n ) )");
        assertDiagnostics("def fac = (n : int) : int -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) )\ndef s : string = fac(3)",
                "2:18 Type error : expected string, got int");
        assertDiagnostics("def f = (n : int) : string -> ( if n < 2 then ( \"a\" ) else ( recurse(\"x\") ) )",
                "1:70 Type error : expected int, got string");
    }

    @Test
    public void testInference() {
        // T8 : a def never reassigned has its value's type.
        assertDiagnostics("def n = 1\ndef s : string = n\ndef f = (a : int) : int -> ( a )\ndef g = f\ng(\"x\")",
                "2:18 Type error : expected string, got int",
                "5:3 Type error : expected int, got string");
        // An unannotated function still has a known parameter list : its arity is checked, not its types.
        assertDiagnostics("def u = (a, b) -> ( a )\nu(1)\nu(\"x\", null)", "2:1 Missing parameter : b");
        // Reassigned, or initialised with null : dynamic.
        assertDiagnostics("def n = 1\nn = \"x\"\ndef s : string = n\ndef m = null\ndef t : int = m");
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
        String point = "def Point = class (def x : int, y = 0) (\n  def norm = () : int -> ( this.x + this.missing )\n  def label : string = \"p\"\n) static ( def origin = () -> ( this(0) ) )\n";
        assertDiagnostics(point + "def p = Point(1)\ndef n : string = p.x\np.label = 1\np.nope\nPoint(\"a\")\nPoint.origin()\nPoint.nope",
                "2:37 No such member : missing",
                "6:18 Type error : expected string, got int",
                "7:11 Type error : expected string, got int",
                "8:1 No such member : nope",
                "9:7 Type error : expected int, got string",
                "11:1 No such member : nope");
        assertDiagnostics("def s = \"a\"\ns.length\ndef f = () -> ( 1 )\nf.x",
                "2:1 Not a scope : s",
                "4:1 Not a scope : f");
        assertDiagnostics("def o = object ( def v : int = 1 ; private def w = 2 ; def get = () -> ( this.w ) )\ndef s : string = o.v",
                "2:18 Type error : expected string, got int");
    }

    @Test
    public void testUnaryMinusAndElseIf() {
        assertDiagnostics("def a : int = -1\n"
                        + "def b : double = -1\n"
                        + "def c : int = -1.5\n"
                        + "def d = -\"s\"\n"
                        + "def e : int = -a * 2\n"
                        + "def f = (n : int) : string -> ( if n < 0 then (\"-\") else if n == 0 then (\"0\") else (\"+\") )\n"
                        + "def g = (n : int) : string -> ( if n < 0 then (\"-\") else if n == 0 then (0) else (\"+\") )\n"
                        + "def h = (v) -> ( if v is java.lang.Integer then (v + 1) else if v is java.lang.String then (v.nope) else (0) )",
                "3:15 Type error : expected int, got double",
                "4:10 Not a number : \"s\"",
                // Narrowed through "else if" : a string has no members.
                "8:93 Not a scope : v");
    }

    @Test
    public void testJavaFieldsAndResults() {
        assertDiagnostics("def pi : double = java.lang.Math.PI\n"
                        + "def bad : int = java.lang.Math.PI\n"
                        + "def n : int = java.lang.Runtime.getRuntime().availableProcessors()\n"
                        + "java.lang.Runtime.getRuntime().nope()\n"
                        + "java.lang.StringBuilder().append(\"a\").nope\n"
                        + "java.lang.Math.NOPE\n"
                        // An interface's results stay dynamic : a Babble instance may come back as itself.
                        + "java.util.List.of(1).iterator().nope",
                "2:17 Type error : expected int, got double",
                "4:1 No such name in java.lang.Runtime : nope",
                "5:1 No such name in java.lang.StringBuilder : nope",
                "6:1 No such name in java.lang.Math : NOPE");
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
        String shape = "def Shape = interface ( def area : () : double ; def describe = () : string -> ( \"area \" + this.area() ) )\n"
                + "def Sq = class (def s) implements Shape ( def area = () -> ( 1.0 * this.s * this.s ) )\n";
        assertDiagnostics(shape + "def f = (x : Shape) : string -> ( x.describe() )\nf(Sq(2))\nf(1)\nf(object ())\nf(null)",
                "5:3 Type error : expected Shape, got int",
                "6:3 Type error : expected Shape, got object");
        assertDiagnostics(shape + "def g = (x : Shape) -> ( x.nope )\ndef h : int = Sq(1).area()\ndef k : string = Sq(1).describe()",
                "3:26 No such member : nope");
        assertDiagnostics(shape + "def i : Sq = Sq(1)\ndef j : Shape = i\ndef k : Sq = j",
                "5:14 Type error : expected Sq, got Shape");
    }

    @Test
    public void testPackages() {
        assertDiagnostics("package p ( def x : int = 1 ; package q ( def y = \"s\" ) )\ndef a : string = p.x\ndef b : int = p.q.y",
                "2:18 Type error : expected string, got int",
                "3:15 Type error : expected int, got string");
    }

    @Test
    public void testFunctionTypes() {
        String apply = "def apply = (f : (x : int) : int, v : int) : int -> ( f(v) )\n";
        assertDiagnostics(apply + "apply((y) -> ( y ), 1)\napply((y, z = 2) -> ( y ), 1)\napply((a, b) -> ( a ), 1)\napply(1, 2)\napply((s : string) -> ( 1 ), 1)",
                "4:7 Type error : expected (x : int) : int, got (a, b)",
                "5:7 Type error : expected (x : int) : int, got int",
                "6:7 Type error : expected (x : int) : int, got (s : string) : int");
    }

    @Test
    public void testFunctionReturnTypeInference() {
        assertDiagnostics("def inc = (x : int) -> ( x + 1 )\ndef s : string = inc(1)",
                "2:18 Type error : expected string, got int");
        assertDiagnostics("def same = (n : int) -> ( if n > 0 then ( 1 ) else ( 2 ) )\n"
                        + "def widen = (n : int) -> ( if n > 0 then ( 1 ) else ( 2.5 ) )\n"
                        + "def nullable = (n : int) -> ( if n > 0 then ( \"a\" ) else ( null ) )\n"
                        + "def mixed = (n : int) -> ( if n > 0 then ( 1 ) else ( \"a\" ) )\n"
                        + "def a : string = same(1)\ndef b : int = widen(1)\ndef c : int = nullable(1)\ndef d : int = mixed(1)",
                "5:18 Type error : expected string, got int",
                "6:15 Type error : expected int, got double",
                "7:15 Type error : expected int, got string");
        assertDiagnostics("def r1 = (n : int) -> ( if n > 0 then ( return 1 ) ; 2 )\n"
                        + "def r2 = (n : int) -> ( if n > 0 then ( return 1 ) else ( return 2 ) )\n"
                        + "def r3 = (n : int) -> ( if n > 0 then ( return 1 ) ; \"s\" )\n"
                        + "def a : string = r1(1)\ndef b : string = r2(1)\ndef c : int = r3(1)",
                "4:18 Type error : expected string, got int",
                "5:18 Type error : expected string, got int");
        // Empty and null-returning functions infer dynamic (T8).
        assertDiagnostics("def e = () -> ()\ndef n = () -> ( null )\ndef a : int = e()\ndef b : int = n()");
        // Self-recursive functions infer when parameter types determine the body type.
        assertDiagnostics("def fac = (n : int) -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) )\n"
                        + "def sum = (n : int) -> ( if n < 1 then ( 0 ) else ( n + sum(n - 1) ) )\n"
                        + "def dyn = (n) -> ( if n < 2 then ( 1 ) else ( n * recurse(n - 1) ) )\n"
                        + "def a : string = fac(5)\ndef b : string = sum(5)\ndef c : string = dyn(5)",
                "4:18 Type error : expected string, got int",
                "5:18 Type error : expected string, got int");
    }

    @Test
    public void testContextualTyping() {
        assertDiagnostics("def apply = (f : (x : int) : int, v : int) : int -> ( f(v) )\n"
                        + "apply((x) -> ( x + 1 ), 1)\napply(v: 1, f: (x) -> ( x * 2 ))\n"
                        + "apply((x) -> ( x.nope ), 1)\napply((x) -> ( \"s\" ), 1)",
                "4:16 Not a scope : x",
                "5:7 Type error : expected (x : int) : int, got (x : int) : string");
        assertDiagnostics("def Point = class (def x : int) ()\n"
                        + "def f : (p : Point) : int = (p) -> ( p.x )\n"
                        + "def bad : (p : Point) : int = (p) -> ( p.nope )\n"
                        + "f = (q) -> ( q.nope )\n"
                        + "def add : (a : int) : (b : int) : int = (a) -> ( (b) -> ( a + b ) )\n"
                        + "def s : string = add(1)(2)\n"
                        + "def badAdd : (a : int) : (b : int) : int = (a) -> ( (b) -> ( b.nope ) )\n"
                        + "def branch : (p : Point) : int = if true then ( (p) -> ( p.x ) ) else ( (p) -> ( p.nope ) )\n"
                        + "def wrapped : (p : Point) : int = ( (p) -> ( p.nope ) )\n"
                        + "def Box = class (def cb : (x : int) : int) () ; Box((x) -> ( x.nope ))",
                "3:40 No such member : nope",
                "4:14 No such member : nope",
                "6:18 Type error : expected string, got int",
                "7:62 Not a scope : b",
                "8:82 No such member : nope",
                "9:46 No such member : nope",
                "10:62 Not a scope : x");
        assertDiagnostics("def ok1 = java.util.function.IntUnaryOperator((x) -> ( x + 1 ))\n"
                        + "def n1 : string = ok1.applyAsInt(2)\n"
                        + "def bad1 : java.util.function.IntUnaryOperator = (x) -> ( x.nope )\n"
                        + "java.util.concurrent.atomic.AtomicInteger(1).updateAndGet((x) -> ( x.nope ))",
                "2:19 Type error : expected string, got int",
                "3:59 Not a scope : x",
                "4:68 Not a scope : x");
    }

    @Test
    public void testIsTypeNarrowing() {
        assertDiagnostics("def Point = class (def x : int) ()\n"
                        + "def f = (p) -> (\n"
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
                "3:20 Type error : expected string, got int",
                "5:32 No such member : nope",
                "6:49 No such member : nope",
                "8:26 No such member : nope",
                "10:31 No such member : nope",
                "11:27 No such member : nope");
        assertDiagnostics("def Point = class (def x : int) ()\n"
                        + "def getX = (p) -> (\n  if not (p is Point) then ( return 0 )\n  p.x\n)\n"
                        + "def s : string = getX(null)\n"
                        + "def bad = (p) -> (\n  if not (p is Point) then ( return 0 )\n  p.nope\n)\n"
                        + "def pos = (p) -> (\n  if p is Point then ( 0 ) else ( return 1 )\n  p.nope\n)",
                "6:18 Type error : expected string, got int",
                "9:3 No such member : nope",
                "13:3 No such member : nope");
        assertDiagnostics("def Shape = interface ( def area : () : double )\n"
                        + "def Sq = class (def side : int) implements Shape ( def area = () -> ( 1.0 * this.side * this.side ) )\n"
                        + "def sideOf = (s : Shape) -> (\n"
                        + "  if s is Sq then ( def a : string = s.side ; s.nope )\n"
                        + "  if not (s is Sq) then ( return 0 )\n  s.side\n)\n"
                        + "def t : string = sideOf(Sq(2))",
                "4:38 Type error : expected string, got int",
                "4:47 No such member : nope",
                "8:18 Type error : expected string, got int");
        assertDiagnostics("def fn = (x) -> (\n"
                        + "  def a : string = if x is java.lang.Integer then ( x + 1 ) else ( 0 )\n"
                        + "  if x is java.lang.Integer then ( x.nope )\n"
                        + "  if x is java.util.List then ( x.nope() )\n)\n"
                        + "def r = 1\nr = \"a\"\nif r is java.lang.Integer then ( r.nope )",
                "2:20 Type error : expected string, got int",
                "3:36 Not a scope : x",
                "4:33 No such name in java.util.List : nope");
    }

    @Test
    public void testJava() {
        assertDiagnostics("def l = java.util.ArrayList()\nl.add(1)\nl.nope()\njava.lang.Math.abs(\"x\")\ndef m : string = java.lang.Math.max(1, 2)\njava.lang.Math.nope\n"
                        + "def b : java.util.List = l\ndef c : java.util.Map = l",
                "3:1 No such name in java.util.ArrayList : nope",
                "4:1 No applicable overload of java.lang.Math.abs for (string)",
                "5:18 Type error : expected string, got int",
                "6:1 No such name in java.lang.Math : nope",
                "8:25 Type error : expected java.util.Map, got java.util.ArrayList");
        // Babble functions convert to functional interfaces, and classes implement Java interfaces.
        assertDiagnostics("def l = java.util.ArrayList()\nl.removeIf((v) -> ( v > 2 ))\n"
                + "def R = class () implements java.lang.Runnable ( def run = () -> ( null ) )\njava.lang.Thread(R())\ndef r : java.lang.Runnable = R()\n"
                + "def P = class () ()\njava.lang.Thread(P())",
                "7:1 No applicable overload of java.lang.Thread for (P)");
    }

    @Test
    public void testTypeErrorsFailTheCompilation() {
        // Every diagnostic is written as an error, and the first one fails the compilation.
        String script = "def a : int = \"s\"\ndef f = (n : int) -> ( n )\nf(true)";
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
        // A dynamic value of the wrong type : nothing static to report, fails at runtime.
        String script = "def id = (x) -> ( x )\ndef f = (n : int) -> ( n )\nf(id(\"s\"))";
        for (String mode : List.of("interpret", "compile")) {
            ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run(script, mode, new StringWriter()));
            Assertions.assertEquals("org.babblelang.runtime.BabbleException: Type error : expected int, got string in <input> at line number 3", e.getMessage(), mode);
        }
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
