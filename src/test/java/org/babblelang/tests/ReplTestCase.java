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
 * The interactive mode (docs/design/repl.md) : sessions, whose inputs see the definitions
 * of the previous ones, and the read-eval-print loop on top. Like the rest of the suite,
 * it runs interpreted, then compiled.
 */
public class ReplTestCase {
    private static BabbleSession session(String typecheck, StringWriter output) {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.TYPECHECK, typecheck);
        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        context.setWriter(output);
        context.setErrorWriter(output);
        return engine.newSession(context);
    }

    private static BabbleSession session() {
        return session("off", new StringWriter());
    }

    private static void assertFails(BabbleSession session, String input, String message) {
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> session.eval(input), input);
        Assertions.assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    /** What the loop prints for these lines, without prompts. */
    private static List<String> typedTranscript(String typecheck, String... lines) throws Exception {
        StringWriter output = new StringWriter();
        PrintWriter out = new PrintWriter(output);
        new Repl(session(typecheck, output), new BufferedReader(new StringReader(String.join("\n", lines))), out, false).run();
        return output.toString().isEmpty() ? List.of() : List.of(output.toString().split("\\R"));
    }

    private static List<String> transcript(String... lines) throws Exception {
        return typedTranscript("off", lines);
    }

    // ---------------------------------------------------------------- sessions

    /** A session is one long eval : a module imported by several inputs is loaded once, and keeps its state. */
    @Test
    public void aModuleIsLoadedOncePerSession(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Path counter = directory.resolve("counter.ba");
        java.nio.file.Files.writeString(counter, "println(\"loading\")\ndef n = 0\nexport def next = () -> ( n = n + 1 )");
        String path = counter.toString().replace("\\", "\\\\");
        StringWriter output = new StringWriter();
        BabbleSession session = session("off", output);
        session.eval("import(\"" + path + "\").next()");
        Assertions.assertEquals(2, session.eval("import(\"" + path + "\").next()"));
        Assertions.assertEquals("loading" + System.lineSeparator(), output.toString());
    }

    /** A module an earlier input imported is known statically to the inputs that follow. */
    @Test
    public void staticImportsAcrossInputs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Path shapes = directory.resolve("shapes.ba");
        java.nio.file.Files.writeString(shapes, "export def Shape = interface ( def area : () : double ; def describe = () -> ( \"area \" + this.area() ) )");
        StringWriter errors = new StringWriter();
        BabbleSession session = session("off", errors);
        session.eval("def shapes = import(\"" + shapes.toString().replace("\\", "\\\\") + "\")");
        session.eval("def Square = class () implements shapes.Shape ( def area = () -> ( 4 ) )");
        Assertions.assertEquals("area 4", session.eval("Square().describe()"));
        assertFails(session, "shapes.nope", "No such export : nope");
        Assertions.assertTrue(errors.toString().contains("Warning : No such export : nope"), errors.toString());
    }

    @Test
    public void testInputsSeeEarlierDefinitions() throws Exception {
        BabbleSession session = session();
        Assertions.assertEquals(5, session.eval("def x = 5"));
        Assertions.assertEquals(10, session.eval("x * 2"));
        session.eval("def twice = (f, v) -> ( f(f(v)) )");
        Assertions.assertEquals(7, session.eval("twice((n) -> ( n + 1 ), x)"));
        session.eval("package p ( def a = 1 )");
        Assertions.assertEquals(1, session.eval("p.a"));
        Assertions.assertNull(session.eval("println(x)"));
    }

    @Test
    public void testRedefinitionShadowsAndAssignmentChanges() throws Exception {
        BabbleSession session = session();
        session.eval("def x = 1");
        session.eval("def get = () -> ( x )");
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
        session.eval("def even = (n) -> ( if n == 0 then ( true ) else ( odd(n - 1) ) )");
        session.eval("def odd = (n) -> ( if n == 0 then ( false ) else ( even(n - 1) ) )");
        assertFails(session, "even(2)", "No such name : odd");
        // Functions that call each other go in one input.
        session.eval("def even = (n) -> ( if n == 0 then ( true ) else ( odd(n - 1) ) )\n"
                + "def odd = (n) -> ( if n == 0 then ( false ) else ( even(n - 1) ) )");
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
        session.eval("def Shape = interface ( def area : () : double ; def describe = () -> ( \"area \" + this.area() ) )");
        session.eval("def Sq = class (def s) implements Shape ( def area = () -> ( this.s * this.s ) ; def describe = () -> ( \"[\" + super.describe() + \"]\" ) )");
        Assertions.assertEquals("[area 4]", session.eval("Sq(2).describe()"));
        Assertions.assertEquals(true, session.eval("Sq(2) is Shape"));
        // The static checks know the interface of an earlier input.
        assertFails(session, "def Bad = class () implements Shape ()", "Bad does not implement Shape.area");
        session.eval("def sq = Sq(3)");
        assertFails(session, "sq.nope", "No such member : nope");
        session.eval("def C = class (private def n = 0) ( def next = () -> ( this.n = this.n + 1 ) )");
        session.eval("def c = C()");
        assertFails(session, "c.n", "n is private");
        Assertions.assertEquals(2, session.eval("c.next() ; c.next()"));
        // Once assigned, a name is no longer a statically known interface.
        session.eval("def I = interface ( def m = () -> ( 1 ) )");
        session.eval("I = 3");
        assertFails(session, "class () implements I ()", "implements needs a statically known interface");
    }

    // ---------------------------------------------------------------- the type checker

    @Test
    public void testWarningsAreForTheInputBeingTyped() throws Exception {
        StringWriter output = new StringWriter();
        BabbleSession session = session("warn", output);
        session.eval("def f = (n : int) : int -> ( n )");
        session.eval("def a : int = \"s\"");
        Assertions.assertEquals("Warning : Type error : expected int, got string in <input> at line number 1, column 15" + System.lineSeparator(), output.toString());
        output.getBuffer().setLength(0);
        // Using the earlier definitions doesn't report them again.
        session.eval("a ; f(1)");
        Assertions.assertEquals("", output.toString());
        session.eval("f(\"s\")");
        session.eval("def s : string = f(2)");
        Assertions.assertEquals("Warning : Type error : expected int, got string in <input> at line number 1, column 3" + System.lineSeparator()
                + "Warning : Type error : expected string, got int in <input> at line number 1, column 18" + System.lineSeparator(), output.toString());
    }

    @Test
    public void testErrorModeAcrossInputs() throws Exception {
        BabbleSession session = session("error", new StringWriter());
        assertFails(session, "def x : int = \"s\"", "Type error : expected int, got string");
        assertFails(session, "x", "No such name : x");
        // A class of an earlier input, as a type : checked statically, and at runtime.
        session.eval("def P = class (def x : int) ()");
        session.eval("def f = (p : P) -> ( p.x )");
        session.eval("def id = (v) -> ( v )");
        Assertions.assertEquals(1, session.eval("f(P(1))"));
        assertFails(session, "f(3)", "Type error : expected P, got int");
        assertFails(session, "f(id(3))", "Type error : expected P, got int");
        assertFails(session, "def q : Q = 1", "Unknown type : Q");
        // A new P is another type : f still takes the one it was written against.
        session.eval("def Old = P");
        session.eval("def P = class (def x : int) ()");
        assertFails(session, "f(P(1))", "Type error : expected P, got P");
        Assertions.assertEquals(2, session.eval("f(Old(2))"));
    }

    @Test
    public void testInferenceFollowsAssignments() throws Exception {
        BabbleSession session = session("error", new StringWriter());
        session.eval("def n = 1");
        assertFails(session, "def s : string = n", "Type error : expected string, got int");
        // Once assigned, nothing is known of n any more.
        session.eval("n = \"x\"");
        Assertions.assertEquals("x", session.eval("def s : string = n"));
    }

    // ---------------------------------------------------------------- reading inputs

    @Test
    public void testIncompleteInputs() {
        for (String complete : List.of("1 + 2", "def x", "def f = () -> ( 1 )\n", "if a then ( 1 )", "f(1,\n 2)", "def = 3", "1 + )", "a # b", "\"a\nb\"")) {
            Assertions.assertEquals(BabbleSession.Input.COMPLETE, BabbleSession.classify(complete), complete);
        }
        for (String incomplete : List.of("1 +", "1 +\n", "def x =", "def f = (a,", "def f = () -> (", "def f = () -> (\n  1\n", "if a then", "if a then ( 1 ) else",
                "f(", "class () (\n def a = 1\n", "\"abc", "println(\"a\n")) {
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
                        "java.lang.Math", "package", "hi"),
                transcript("1 + 2", "\"a\\\"b\"", "1.5", "1 < 2", "def f = () -> ( 1 )", "() -> ( 1 )", "def P = value class (x, y) ()", "P(1, 2)", "def I = interface ()",
                        "def l = java.util.ArrayList() ; l.add(1) ; l", "java.util", "java.lang.Math", "package p ( def a = 1 ) ; p",
                        // null is not shown, and output is.
                        "null", "def n", "println(\"hi\")"));
    }

    @Test
    public void testInputsSpanLines() throws Exception {
        Assertions.assertEquals(List.of("function twice", "7", "3", "\"a", "b\""),
                transcript("def twice = (f, v) -> (", "  f(f(v))", "", ")", "twice((n) -> ( n + 1 ), 5)", "1 +", "  2", ";; nothing", "", "\"a", "b\""));
    }

    @Test
    public void testErrorsDoNotEndTheLoop() throws Exception {
        Assertions.assertEquals(List.of("Error : No such name : nosuch", "Error : Syntax error : unexpected '='", "Error : Lexical error at line 1:2 : token recognition error at: '#'",
                        "Error : Not a number : true (line 2)", "function r", "Error : Stack overflow", "1"),
                transcript("nosuch", "def = 3", "1 # 2", "def a = (", "  1 + true", ")", "def r = (n) -> ( recurse(n + 1) )", "r(1)", "1"));
    }

    @Test
    public void testCommands() throws Exception {
        List<String> help = transcript(":help");
        Assertions.assertTrue(help.size() > 3 && help.get(0).startsWith("Type an expression"), help.toString());
        Assertions.assertEquals(List.of("Unknown command : :nope (:help lists them)", "Error : No such name : f", "1"),
                transcript(":nope", "def f = () -> (", ":cancel", "f", "1", ":quit", "2"));
        Assertions.assertEquals(List.of("1"), transcript("1", "  :q  ", "2"));
    }

    @Test
    public void testPrompts() throws Exception {
        StringWriter output = new StringWriter();
        String input = "def f = (n) -> (\n  n * 2\n)\nf(21)\n";
        new Repl(session("off", output), new BufferedReader(new StringReader(input)), new PrintWriter(output), true).run();
        String nl = System.lineSeparator();
        Assertions.assertEquals("Babble. :help for help, :quit to leave." + nl
                + ">> .. .. function f" + nl
                + ">> 42" + nl
                + ">> " + nl, output.toString());
    }

    @Test
    public void testTypeErrorsInTheLoop() throws Exception {
        // In error mode the diagnostics are the errors : the input doesn't run.
        Assertions.assertEquals(List.of("function half", "Error : Type error : expected int, got string in <input> at line number 1, column 6", "2.0"),
                typedTranscript("error", "def half = (n : int) : double -> ( n / 2 )", "half(\"4\")", "half(4)"));
        Assertions.assertEquals(List.of("function half", "Warning : Type error : expected int, got string in <input> at line number 1, column 6",
                        "Error : Not a number : n", "2.0"),
                typedTranscript("warn", "def half = (n : int) : double -> ( n / 2 )", "half(\"4\")", "half(4)"));
    }
}
