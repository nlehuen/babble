package org.babblelang;

import org.babblelang.engine.BabbleScriptEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The command line (docs/design/repl.md) : a script given as an argument runs, without the
 * interactive mode unless {@code -keep-alive} asks for it.
 */
public class MainTestCase {
    @TempDir
    Path directory;

    private record Run(int status, List<String> out, List<String> err) {
    }

    private static List<String> lines(StringWriter writer) {
        return writer.toString().isEmpty() ? List.of() : List.of(writer.toString().split("\\R"));
    }

    /** Runs the command line, with {@code input} as what is typed, and no console. */
    private static Run run(String input, String... args) throws Exception {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int status = Main.run(args, new BufferedReader(new StringReader(input)), new PrintWriter(out), new PrintWriter(err), false);
        return new Run(status, lines(out), lines(err));
    }

    private String script(String name, String source) throws Exception {
        Path path = directory.resolve(name);
        Files.writeString(path, source);
        return path.toString();
    }

    @Test
    public void testAScriptRunsWithoutTheInteractiveMode() throws Exception {
        String hello = script("hello.ba", ";; greets\ndef greet = (name) -> ( \"Hello, \" + name )\nprintln(greet(\"world\"))\n42\n");
        // What is typed is not read, and the script's value is not shown.
        Run run = run("1 + 1\n", hello);
        Assertions.assertEquals(new Run(0, List.of("Hello, world"), List.of()), run);
    }

    @Test
    public void testAScriptGetsItsArguments() throws Exception {
        String echo = script("echo.ba", "def i = 0\nwhile i < args.size() then ( println(i, \" \", args.get(i)) ; i = i + 1 )\nprintln(args.size())");
        Assertions.assertEquals(List.of("0 a", "1 -keep-alive", "2 b c", "3"), run("", echo, "a", "-keep-alive", "b c").out());
        Assertions.assertEquals(List.of("0"), run("", echo).out());
        // A script can define the name for itself.
        Assertions.assertEquals(List.of("mine"), run("", script("own.ba", "def args = \"mine\" ; println(args)"), "a").out());
    }

    @Test
    public void testKeepAliveContinuesInTheInteractiveMode() throws Exception {
        String definitions = script("definitions.ba", "def double = (n) -> ( n * 2 )\nprintln(\"loaded\")");
        Run run = run("double(21)\nargs.get(0)\n", "-keep-alive", definitions, "x");
        Assertions.assertEquals(new Run(0, List.of("loaded", "42", "\"x\""), List.of()), run);
        Assertions.assertEquals(List.of("loaded", "4"), run("double(2)\n", "--keep-alive", definitions).out());
    }

    @Test
    public void testAFailingScript() throws Exception {
        String failing = script("failing.ba", "def a = 1\nprintln(\"before\")\nnosuch\nprintln(\"after\")");
        Run run = run("", failing);
        Assertions.assertEquals(new Run(Main.FAILED, List.of("before"), List.of("Error : No such name : nosuch in " + failing + " at line number 3")), run);
        // With -keep-alive, the interactive mode starts all the same, with what the script defined until then.
        Assertions.assertEquals(new Run(0, List.of("before", "Error : No such name : nosuch in " + failing + " at line number 3", "1"), List.of()),
                run("a\n", "-keep-alive", failing));

        String syntax = script("syntax.ba", "def a = 1\ndef = 2");
        Assertions.assertEquals(new Run(Main.FAILED, List.of(), List.of("Error : Syntax error : unexpected '=' in " + syntax + " at line number 2 at column number 5")),
                run("", syntax));
        String overflow = script("overflow.ba", "def r = (n) -> ( recurse(n + 1) )\nr(1)");
        Assertions.assertEquals(new Run(Main.FAILED, List.of(), List.of("Error : Stack overflow in " + overflow)), run("", overflow));
    }

    @Test
    public void testEmptyScripts() throws Exception {
        Assertions.assertEquals(new Run(0, List.of(), List.of()), run("", script("empty.ba", "")));
        Assertions.assertEquals(new Run(0, List.of(), List.of()), run("", script("comments.ba", ";; nothing here\n\n")));
    }

    @Test
    public void testTheTypeCheckerAppliesToScripts() throws Exception {
        String typed = script("typed.ba", "def x : int = \"s\"\nprintln(\"ran\")");
        String before = System.getProperty(BabbleScriptEngine.TYPECHECK);
        try {
            System.setProperty(BabbleScriptEngine.TYPECHECK, "warn");
            Assertions.assertEquals(new Run(0, List.of("ran"), List.of("Warning : Type error : expected int, got string in " + typed + " at line number 1, column 15")),
                    run("", typed));
            // In error mode the script doesn't run, and its diagnostics are reported once.
            System.setProperty(BabbleScriptEngine.TYPECHECK, "error");
            Assertions.assertEquals(new Run(Main.FAILED, List.of(), List.of("Error : Type error : expected int, got string in " + typed + " at line number 1, column 15")),
                    run("", typed));
        } finally {
            if (before == null) {
                System.clearProperty(BabbleScriptEngine.TYPECHECK);
            } else {
                System.setProperty(BabbleScriptEngine.TYPECHECK, before);
            }
        }
    }

    @Test
    public void testWithoutAScriptTheInteractiveModeStarts() throws Exception {
        Assertions.assertEquals(new Run(0, List.of("2", "[]"), List.of()), run("1 + 1\nargs\n"));
        Assertions.assertEquals(new Run(0, List.of("2"), List.of()), run("1 + 1\n", "-keep-alive"));
    }

    @Test
    public void testCommandLineErrors() throws Exception {
        Run missing = run("", directory.resolve("missing.ba").toString());
        Assertions.assertEquals(new Run(Main.USAGE_ERROR, List.of(), List.of("Cannot read " + directory.resolve("missing.ba") + " : no such file")), missing);

        Run unknown = run("1\n", "-nope", "x.ba");
        Assertions.assertEquals(Main.USAGE_ERROR, unknown.status());
        Assertions.assertEquals(List.of(), unknown.out());
        List<String> expected = new ArrayList<>(List.of("Unknown option : -nope"));
        expected.addAll(List.of(Main.USAGE.split("\\R")));
        Assertions.assertEquals(expected, unknown.err());

        Run help = run("1\n", "-help");
        Assertions.assertEquals(new Run(0, List.of(Main.USAGE.split("\\R")), List.of()), help);
    }
}
