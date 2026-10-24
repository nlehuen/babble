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
 * Golden tests of the type checker in warn mode (docs/design/type-system.md) : for each
 * snippet, the diagnostics expected, as {@code line:column message}.
 */
public class TypeCheckerTestCase {
    private static List<String> diagnostics(String source) {
        BabbleParser parser = new BabbleParser(new CommonTokenStream(new BabbleLexer(CharStreams.fromString(source))));
        parser.setErrorHandler(new BailErrorStrategy());
        ScopeAnalysis analysis = BlockScopes.analyze(parser.file(), true);
        return analysis.warnings().stream().map(w -> w.line() + ":" + w.column() + " " + w.message()).toList();
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
                "6:7 Type error : expected (x : int) : int, got (s : string)");
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
    public void testTheEngineSwitch() throws Exception {
        String script = "def a : int = \"s\"\n1";
        for (String mode : List.of("interpret", "compile")) {
            Assertions.assertEquals("Warning : Type error : expected int, got string in <input> at line number 1, column 15" + System.lineSeparator(),
                    errors(script, mode, "warn"), mode);
            Assertions.assertEquals("", errors(script, mode, "off"), mode);
        }
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> errors(script, "interpret", "loud"));
        Assertions.assertTrue(e.getMessage().contains("Unknown babble.typecheck : loud (expected off or warn)"), e.getMessage());
    }

    private static String errors(String script, String mode, String typecheck) throws ScriptException {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODE, mode);
        engine.put(BabbleScriptEngine.TYPECHECK, typecheck);
        ScriptContext context = new SimpleScriptContext();
        StringWriter errors = new StringWriter();
        context.setErrorWriter(errors);
        context.setWriter(new StringWriter());
        Assertions.assertEquals(1, engine.eval(script, context));
        return errors.toString();
    }
}
