package org.babblelang.tests;

import org.babblelang.Repl;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.BabbleSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

/**
 * The interactive mode (docs/design/bip004_repl.md) : sessions, whose inputs see the definitions
 * of the previous ones, and the read-eval-print loop on top. Like the rest of the suite,
 * it runs interpreted, then compiled.
 */
public class ReplTestCase {
    private static BabbleSession session(StringWriter output) {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        context.setWriter(output);
        context.setErrorWriter(output);
        return engine.newSession(context);
    }

    private static BabbleSession session() {
        return session(new StringWriter());
    }

    private static void assertFails(BabbleSession session, String input, String message) {
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> session.eval(input), input);
        Assertions.assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    /** What the loop prints for these lines, without prompts. */
    private static List<String> transcript(String... lines) throws Exception {
        StringWriter output = new StringWriter();
        PrintWriter out = new PrintWriter(output);
        new Repl(session(output), new BufferedReader(new StringReader(String.join("\n", lines))), out, false).run();
        return output.toString().isEmpty() ? List.of() : List.of(output.toString().split("\\R"));
    }

    // ---------------------------------------------------------------- sessions

    /** A session is one long eval : a module imported by several inputs is loaded once, and keeps its state. */
    @Test
    public void aModuleIsLoadedOncePerSession(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Path counter = directory.resolve("counter.ba");
        java.nio.file.Files.writeString(counter, "println(\"loading\")\ndef n = 0\nexport def next = fn io () -> ( n = n + 1 )");
        String path = counter.toString().replace("\\", "\\\\");
        StringWriter output = new StringWriter();
        BabbleSession session = session(output);
        session.eval("import(\"" + path + "\").next()");
        Assertions.assertEquals(2, session.eval("import(\"" + path + "\").next()"));
        Assertions.assertEquals("loading" + System.lineSeparator(), output.toString());
    }

    /** A module an earlier input imported is known statically to the inputs that follow. */
    @Test
    public void staticImportsAcrossInputs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Path shapes = directory.resolve("shapes.ba");
        java.nio.file.Files.writeString(shapes, "export def Shape = interface ( def area : fn () : double ; def describe = fn () -> ( \"area \" + this.area() ) )");
        StringWriter errors = new StringWriter();
        BabbleSession session = session(errors);
        session.eval("def shapes = import(\"" + shapes.toString().replace("\\", "\\\\") + "\")");
        session.eval("def Square = class () implements shapes.Shape ( def area = fn () : double -> ( 4 ) )");
        Assertions.assertEquals("area 4.0", session.eval("Square().describe()"));
        assertFails(session, "shapes.nope", "No such export : nope");
        Assertions.assertTrue(errors.toString().contains("Error : No such export : nope"), errors.toString());
    }

    @Test
    public void testInputsSeeEarlierDefinitions() throws Exception {
        BabbleSession session = session();
        Assertions.assertEquals(5, session.eval("def x = 5"));
        Assertions.assertEquals(10, session.eval("x * 2"));
        session.eval("def twice = fn (f : fn (n : int) : int, v : int) : int -> ( f(f(v)) )");
        Assertions.assertEquals(7, session.eval("twice(fn (n : int) : int -> ( n + 1 ), x)"));
        Assertions.assertNull(session.eval("println(x)"));
    }

    @Test
    public void testRedefinitionShadowsAndAssignmentChanges() throws Exception {
        BabbleSession session = session();
        session.eval("def x = 1");
        session.eval("def get = fn () -> ( x )");
        // An assignment changes the variable everyone sees.
        session.eval("x = 5");
        Assertions.assertEquals(5, session.eval("get()"));
        // A new definition is another variable : get keeps the one it was written against.
        session.eval("def x = 10");
        Assertions.assertEquals(5, session.eval("get()"));
        Assertions.assertEquals(10, session.eval("x"));
        session.eval("x = 11");
        Assertions.assertEquals(5, session.eval("get()"));
        // Inside one input, a name is still defined only once.
        assertFails(session, "def y = 1 ; def y = 2", "Name already defined : y");
    }

    @Test
    public void testAFunctionDoesNotSeeLaterDefinitions() throws Exception {
        BabbleSession session = session();
        assertFails(session, "def even = fn io (n : int) : bool -> ( if n == 0 then ( true ) else ( odd(n - 1) ) )", "odd is not callable");
        // Functions that call each other go in one input.
        session.eval("def even = fn (n : int) : bool -> ( if n == 0 then ( true ) else ( odd(n - 1) ) )\n"
                + "def odd = fn (n : int) : bool -> ( if n == 0 then ( false ) else ( even(n - 1) ) )");
        Assertions.assertEquals(true, session.eval("even(10)"));
    }

    @Test
    public void testFailedInputs() throws Exception {
        BabbleSession session = session();
        // Rejected before it runs : nothing of it remains.
        assertFails(session, "def a = 1 ; def a = 2", "Name already defined : a");
        assertFails(session, "a", "No such name : a");
        assertFails(session, "def b = 1 ; this", "this outside of a class");
        assertFails(session, "b", "No such name : b");
        assertFails(session, "def c = ", "Syntax error : unexpected end of input");
        assertFails(session, "def = 3", "Syntax error : unexpected '='");
        // Failing while it runs : what it defined until then remains.
        assertFails(session, "def d = 1 ; nosuch ; def e = 2", "No such name : nosuch");
        Assertions.assertEquals(1, session.eval("d"));
        assertFails(session, "e", "Name used before definition : e");
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> session.eval("def f = 1\n\n1 + true"));
        Assertions.assertEquals(3, e.getLineNumber());
    }

    @Test
    public void testClassesAndInterfacesAcrossInputs() throws Exception {
        BabbleSession session = session();
        session.eval("def Shape = interface ( def area : fn () : double ; def describe = fn () -> ( \"area \" + this.area() ) )");
        session.eval("def Sq = class (def s : int) implements Shape ( def area = fn () : double -> ( this.s * this.s ) ; def describe = fn () -> ( \"[\" + super.describe() + \"]\" ) )");
        Assertions.assertEquals("[area 4.0]", session.eval("Sq(2).describe()"));
        Assertions.assertEquals(true, session.eval("Sq(2) is Shape"));
        // The static checks know the interface of an earlier input.
        assertFails(session, "def Bad = class () implements Shape ()", "Bad does not implement Shape.area");
        assertFails(session, "def BadType = class () implements Shape ( def area = fn () -> ( 4 ) )",
                "Type error : expected () : double, got () : int");
        session.eval("def sq = Sq(3)");
        assertFails(session, "sq.nope", "No such member : nope");
        session.eval("def C = class (private def n = 0) ( def next = fn mut () -> ( this.n = this.n + 1 ) )");
        session.eval("def c = C()");
        assertFails(session, "c.n", "n is private");
        Assertions.assertEquals(2, session.eval("c.next() ; c.next()"));
        // Once assigned, a name is no longer a statically known interface.
        session.eval("def I = interface ( def m = fn () -> ( 1 ) )");
        assertFails(session, "I = 3", "Type error : expected interface I, got int");
        session.eval("def id = fn (x : any) : any -> ( x ) ; def J : any = id(I)");
        assertFails(session, "class () implements J ()", "implements needs a statically known interface");
    }

    // ---------------------------------------------------------------- the type checker

    @Test
    public void testDiagnosticsAreForTheInputBeingTyped() throws Exception {
        StringWriter output = new StringWriter();
        BabbleSession session = session(output);
        session.eval("def f = fn (n : int) : int -> ( n )");
        assertFails(session, "def a : int = \"s\"", "Type error : expected int, got string");
        Assertions.assertEquals("Error : Type error : expected int, got string in <input> at line number 1, column 15" + System.lineSeparator(), output.toString());
        output.getBuffer().setLength(0);
        // Valid inputs after a failed one carry no earlier diagnostics.
        Assertions.assertEquals(1, session.eval("f(1)"));
        Assertions.assertEquals("", output.toString());
        assertFails(session, "f(\"s\")\ndef s : string = f(2)", "Type error : expected int, got string");
        Assertions.assertEquals("Error : Type error : expected int, got string in <input> at line number 1, column 3" + System.lineSeparator()
                + "Error : Type error : expected string, got int in <input> at line number 2, column 18" + System.lineSeparator(), output.toString());
    }

    @Test
    public void testTypesAcrossInputs() throws Exception {
        BabbleSession session = session(new StringWriter());
        assertFails(session, "def x : int = \"s\"", "Type error : expected int, got string");
        assertFails(session, "x", "No such name : x");
        // A class of an earlier input, as a type : checked statically, and at runtime.
        session.eval("def P = class (def x : int) ()");
        session.eval("def f = fn (p : P) -> ( p.x )");
        session.eval("def id = fn (v : any) : any -> ( v )");
        Assertions.assertEquals(1, session.eval("f(P(1))"));
        assertFails(session, "f(3)", "Type error : expected P, got int");
        assertFails(session, "f(id(3) as P)", "Type error : expected P, got int");
        assertFails(session, "def q : Q = 1", "Unknown type : Q");
        // A new P is another type : f still takes the one it was written against.
        session.eval("def Old = P");
        session.eval("def P = class (def x : int) ()");
        assertFails(session, "f(P(1))", "Type error : expected P, got P");
        Assertions.assertEquals(2, session.eval("f(Old(2))"));
    }

    @Test
    public void testInferenceFollowsAssignments() throws Exception {
        BabbleSession session = session(new StringWriter());
        session.eval("def n = 1");
        assertFails(session, "def s : string = n", "Type error : expected string, got int");
        assertFails(session, "n = \"x\"", "Type error : expected int, got string");
        Assertions.assertEquals(1, session.eval("n"));
    }

    @Test
    public void testP7ClosedAtInputEnd() throws Exception {
        BabbleSession session = session(new StringWriter());
        Assertions.assertEquals(3, session.eval("def f = fn (x) -> ( x + 1 )\nf(2)"));
        Assertions.assertEquals(5, session.eval("f(4)"));
        assertFails(session, "f(1.5)", "Type error : expected int, got double");
        assertFails(session, "f(\"x\")", "Type error : expected int, got string");
        assertFails(session, "def uncalled = fn (x) -> ( x + 1 )", "Missing type : x");
        Assertions.assertEquals(1, session.eval("def P = class (def x) ()\nP(1).x"));
        assertFails(session, "P(1.5)", "Type error : expected int, got double");
    }

    // ---------------------------------------------------------------- reading inputs

    @Test
    public void testIncompleteInputs() {
        for (String complete : List.of("1 + 2", "def x", "def f = fn () -> ( 1 )\n", "if a then ( 1 )", "f(1,\n 2)", "def = 3", "1 + )", "a # b", "\"a\nb\"")) {
            Assertions.assertEquals(BabbleSession.Input.COMPLETE, BabbleSession.classify(complete), complete);
        }
        for (String incomplete : List.of("1 +", "1 +\n", "def x =", "def f = fn (a,", "def f = fn () -> (", "def f = fn () -> (\n  1\n", "if a then", "if a then ( 1 ) else",
                "f(", "class () (\n def a = 1\n", "a?.", "\"abc", "println(\"a\n")) {
            Assertions.assertEquals(BabbleSession.Input.INCOMPLETE, BabbleSession.classify(incomplete), incomplete);
        }
        for (String empty : List.of("", "\n", "  \n\n", ";; a comment\n", ";;( a\nlong comment ;;)\n", ";")) {
            Assertions.assertEquals(BabbleSession.Input.EMPTY, BabbleSession.classify(empty), empty);
        }
    }

    // ---------------------------------------------------------------- the loop

    @Test
    public void testValuesAreShown() throws Exception {
        Assertions.assertEquals(List.of("3", "\"a\\\"b\"", "1.5", "true", "function f", "function", "class P", "P(x: 1, y: 2)", "interface I", "[1]", "package java.util",
                        "java.lang.Math", "hi"),
                transcript("1 + 2", "\"a\\\"b\"", "1.5", "1 < 2", "def f = fn () -> ( 1 )", "fn () -> ( 1 )", "def P = record (x : int, y : int) ()", "P(1, 2)", "def I = interface ()",
                        "def l : java.util.List of int = java.util.ArrayList() ; l.add(1) ; l", "java.util", "java.lang.Math",
                        // null is not shown, and output is.
                        "null", "def n : any?", "println(\"hi\")"));
    }

    @Test
    public void testInputsSpanLines() throws Exception {
        Assertions.assertEquals(List.of("function twice", "7", "3", "\"a", "b\""),
                transcript("def twice = fn (f : fn (n : int) : int, v : int) : int -> (", "  f(f(v))", "", ")", "twice(fn (n : int) : int -> ( n + 1 ), 5)", "1 +", "  2", ";; nothing", "", "\"a", "b\""));
    }

    @Test
    public void testErrorsDoNotEndTheLoop() throws Exception {
        Assertions.assertEquals(List.of("Error : No such name : nosuch", "Error : Syntax error : unexpected '='", "Error : Lexical error at line 1:2 : token recognition error at: '#'",
                        "Error : Not a number : true in <input> at line number 2, column 7", "function r", "Error : Stack overflow", "1"),
                transcript("nosuch", "def = 3", "1 # 2", "def a = (", "  1 + true", ")", "def r = fn (n : int) : int -> ( recurse(n + 1) )", "r(1)", "1"));
    }

    @Test
    public void testCommands() throws Exception {
        List<String> help = transcript(":help");
        Assertions.assertTrue(help.size() > 3 && help.get(0).startsWith("Type an expression"), help.toString());
        Assertions.assertEquals(List.of("Unknown command : :nope (:help lists them)", "Error : No such name : f", "1"),
                transcript(":nope", "def f = fn () -> (", ":cancel", "f", "1", ":quit", "2"));
        Assertions.assertEquals(List.of("1"), transcript("1", "  :q  ", "2"));
    }

    @Test
    public void testPrompts() throws Exception {
        StringWriter output = new StringWriter();
        String input = "def f = fn (n : int) : int -> (\n  n * 2\n)\nf(21)\n";
        new Repl(session(output), new BufferedReader(new StringReader(input)), new PrintWriter(output), true).run();
        String nl = System.lineSeparator();
        Assertions.assertEquals("Babble. :help for help, :quit to leave." + nl
                + ">> .. .. function f" + nl
                + ">> 42" + nl
                + ">> " + nl, output.toString());
    }

    @Test
    public void testTypeErrorsInTheLoop() throws Exception {
        // Diagnostics are errors : the bad input doesn't run, the loop keeps going.
        Assertions.assertEquals(List.of("function half", "Error : Type error : expected int, got string in <input> at line number 1, column 6", "2.0"),
                transcript("def half = fn (n : int) : double -> ( n / 2 )", "half(\"4\")", "half(4)"));
    }

    @Test
    public void testSafeNavigationInSession() throws Exception {
        BabbleSession session = session();
        session.eval("def A = class (def x : int) ( def f = fn () : int -> ( this.x + 1 ) )");
        session.eval("def some : A? = A(10)");
        session.eval("def none : A? = null");
        Assertions.assertEquals(11, session.eval("some?.f()"));
        Assertions.assertNull(session.eval("none?.f()"));
        assertFails(session, "def nonNull : A = A(1) ; nonNull?.f()", "Not nullable : nonNull");
    }

    @Test
    public void testRm001UnboxedFieldsAndTypedCallsAcrossInputs() throws Exception {
        BabbleSession session = session();
        session.eval("def P = class (def i : int, def d : double, def b : bool) (\n"
                + "  def sum = fn (k : int) : double -> ( this.i + this.d + k )\n"
                + ")");
        session.eval("def p = P(2, 1.5, true)");
        session.eval("def add = fn (x : int, y : int) : int -> ( x + y )");
        Assertions.assertEquals(true, session.eval("p.b"));
        Assertions.assertEquals(2, session.eval("p.i"));
        Assertions.assertEquals(1.5, session.eval("p.d"));
        Assertions.assertEquals(7.5, session.eval("p.sum(4)"));
        Assertions.assertEquals(9, session.eval("add(4, 5)"));
    }

    @Test
    public void testRm002EffectsAndAxiomsAcrossInputs() throws Exception {
        BabbleSession session = session(new StringWriter());
        // Inferred pure deterministic nothrow function persists across inputs:
        session.eval("def sq = fn (x: int): int -> (x * x)");
        session.eval("def quad = fn nothrow (x: int): int -> (sq(sq(x)))");
        Assertions.assertEquals(81, session.eval("quad(3)"));

        // `mut` method and static call-site exclusivity persist across inputs:
        session.eval("def Box = class (def v: int) ( def addFrom = fn mut (other: Box) -> ( this.v += other.v ) )");
        session.eval("def a = Box(10) ; def b = Box(5)");
        session.eval("a.addFrom(b)");
        Assertions.assertEquals(15, session.eval("a.v"));
        assertFails(session, "a.addFrom(a)", "Exclusivity error");
        assertFails(session, "def bad = fn volatile (x: Box): int -> ( x.addFrom(b) ; x.v )", "Cannot mutate parameter x");

        // Top-level `axiom` persists across REPL turns:
        assertFails(session, "def badSep = fn (): string -> (java.lang.System.lineSeparator())", "violates pure guarantee");
        session.eval("axiom pure nothrow on java.lang.System.lineSeparator");
        session.eval("def okSep = fn nothrow (): string -> (java.lang.System.lineSeparator())");
        Assertions.assertEquals(System.lineSeparator(), session.eval("okSep()"));

        // Multi-line incomplete classification with effect headers:
        Assertions.assertEquals(BabbleSession.Input.INCOMPLETE, BabbleSession.classify("def f = fn io (x: int): int -> ("));
        Assertions.assertEquals(BabbleSession.Input.COMPLETE, BabbleSession.classify("axiom pure on java.lang.Math"));
    }

    // ---------------------------------------------------------------- :type and :load (§9)

    /** {@code :type} shows the static type of an expression as source writes it, and runs nothing. */
    @Test
    public void testTypeCommand() throws Exception {
        Assertions.assertEquals(List.of("class P", "function twice", "function hello", "class Box",
                        "int", "string", "double", "bool", "list of int", "map of (string, int)", "list of string",
                        "class P", "P", "P?", "fn (n : int) : int", "fn (f : fn (n : int) : int, v : int) : int", "fn io () : null",
                        "int | string", "(list of int)?", "Box of int", "list of (int | string)"),
                transcript("def P = record (x : int, y : int) ()",
                        "def twice = fn (f : fn (n : int) : int, v : int) : int -> ( f(f(v)) )",
                        "def hello = fn io () -> ( println(\"hi\") )",
                        "def m : P? = null",
                        "def Box of T = class (def v : T) ()",
                        ":type 1 + 2", ":type \"a\"", ":t 1.5", ":type 1 < 2", ":type list.of(1, 2)", ":type map.of(\"a\", 1)", ":type args",
                        ":type P", ":type P(1, 2)", ":type m", ":type fn (n : int) : int -> ( n )", ":type twice", ":type hello",
                        ":type def u : int | string = 1 ; u", ":type def nl : (list of int)? = null ; nl", ":type Box(1)",
                        ":type def lu : list of (int | string) = list.of() ; lu"));
    }

    /** {@code :type} shows intersections (BIP022), and the intersection {@code is} narrows to. */
    @Test
    public void testTypeCommandShowsIntersections() throws Exception {
        Assertions.assertEquals(List.of("interface Shape", "interface Named", "function f", "Named & Shape", "(Shape & Named)?", "Shape & Named"),
                transcript("def Shape = interface ( def area : fn () : double )",
                        "def Named = interface ( def name : fn () : string )",
                        "def f = fn (x : Shape) : Shape -> ( x )",
                        ":type def a : Named & Shape & Shape = f(object implements Shape, Named ( def area = fn () : double -> ( 1.0 ) ; def name = fn () : string -> ( \"o\" ) )) as Named & Shape ; a",
                        ":type def b : (Shape & Named)? = null ; b",
                        ":type def s : Shape = f(object implements Shape, Named ( def area = fn () : double -> ( 1.0 ) ; def name = fn () : string -> ( \"o\" ) )) ; if s is Named then ( s ) else ( s as Shape & Named )"));
    }

    /** Aliases (BIP023) stand for their types : {@code :type} shows what they stand for, across inputs. */
    @Test
    public void testTypeAliasesAcrossInputs() throws Exception {
        Assertions.assertEquals(List.of("interface Shape", "interface Named", "(Shape & Named)?", "list of int"),
                transcript("def Shape = interface ( def area : fn () : double )",
                        "def Named = interface ( def name : fn () : string )",
                        "def SN = type Shape & Named ; def Box of T = type list of T",
                        ":type def a : SN? = null ; a",
                        ":type def b : Box of int = list.of(1) ; b"));
    }

    /** {@code :type} neither runs its input nor keeps what it defines ; its errors are reported as an input's. */
    @Test
    public void testTypeCommandLeavesTheSessionAlone() throws Exception {
        Assertions.assertEquals(List.of("int", "Error : No such name : y"),
                transcript(":type println(\"ran\") ; def y = 3", "y"));
        List<String> errors = transcript(":type 1 + true", ":type nosuch", ":type def = 3", ":type", ":type (1 +", "1");
        Assertions.assertEquals(6, errors.size(), errors.toString());
        Assertions.assertEquals("Error : Not a number : true in <input> at line number 1, column 5", errors.get(0));
        // An unknown name is looked up at runtime, as eval would : statically, it is any.
        Assertions.assertEquals("any", errors.get(1));
        Assertions.assertEquals("Error : Syntax error : unexpected '='", errors.get(2));
        Assertions.assertEquals(":type needs a complete expression : :type 1 + 2", errors.get(3));
        Assertions.assertEquals(":type needs a complete expression : :type 1 + 2", errors.get(4));
        Assertions.assertEquals("1", errors.get(5));
    }

    /** {@code :load} runs a file as an input of the session : quietly, with its errors naming it, and its imports relative to it. */
    @Test
    public void testLoadCommand(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Files.writeString(directory.resolve("lib.ba"), "export def three = 3");
        java.nio.file.Path shapes = directory.resolve("shapes.ba");
        java.nio.file.Files.writeString(shapes, "def lib = import(\"lib.ba\")\ndef square = fn (n : int) : int -> ( n * n )\nprintln(\"loaded\")\nsquare(lib.three)");
        java.nio.file.Path failing = directory.resolve("failing.ba");
        java.nio.file.Files.writeString(failing, "def before = 1\n\ndef bad = java.lang.Integer.parseInt(\"x\")\ndef after = 2");
        java.nio.file.Path empty = directory.resolve("empty.ba");
        java.nio.file.Files.writeString(empty, ";; nothing\n");
        List<String> lines = transcript(":load " + shapes, "square(lib.three)", ":load \"" + failing + "\"", "before", "after",
                ":load " + empty, ":load " + directory.resolve("nosuch.ba"), ":load", "1");
        Assertions.assertEquals("loaded", lines.get(0));
        Assertions.assertEquals("9", lines.get(1));
        Assertions.assertTrue(lines.get(2).startsWith("Error : ") && lines.get(2).contains("in " + failing + " at line number 3"), lines.get(2));
        Assertions.assertEquals("1", lines.get(3));
        Assertions.assertTrue(lines.get(4).startsWith("Error : Name used before definition : after"), lines.get(4));
        Assertions.assertEquals("Cannot read " + directory.resolve("nosuch.ba") + " : no such file", lines.get(5));
        Assertions.assertEquals(":load needs a file : :load script.ba", lines.get(6));
        Assertions.assertEquals("1", lines.get(7));
        Assertions.assertEquals(8, lines.size(), lines.toString());
    }
}
