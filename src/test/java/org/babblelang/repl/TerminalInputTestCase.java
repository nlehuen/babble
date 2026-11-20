package org.babblelang.repl;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.BabbleSession;
import org.jline.reader.EOFError;
import org.jline.reader.Parser;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.script.ScriptContext;
import javax.script.SimpleScriptContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Line editing, history and completion in the interactive mode (docs/design/bip004_repl.md §9) :
 * what Tab offers, how Enter reads an unfinished input, and the line editor end to end on a
 * dumb terminal. Like the rest of the suite, it runs interpreted, then compiled.
 */
public class TerminalInputTestCase {
    @TempDir
    Path directory;

    private static BabbleSession session(StringWriter output) {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        context.setWriter(output);
        context.setErrorWriter(output);
        return engine.newSession(context);
    }

    private static List<String> complete(BabbleSession session, String buffer) {
        return Completions.at(session, buffer, buffer.length()).candidates();
    }

    // ---------------------------------------------------------------- completion

    @Test
    public void testCommandsAndNames() throws Exception {
        BabbleSession session = session(new StringWriter());
        session.eval("def twice = fn (n : int) : int -> ( n * 2 )");
        session.eval("def twenty = 20");
        session.eval("def twenty = 21");
        Assertions.assertEquals(Completions.COMMANDS, complete(session, ":"));
        Assertions.assertEquals(List.of(":load"), complete(session, "  :lo"));
        Assertions.assertEquals(List.of(), complete(session, ":help me"));
        // Names of earlier inputs, once each even when shadowed, implicits, and keywords.
        Assertions.assertEquals(List.of("twenty", "twice"), complete(session, "tw"));
        Assertions.assertEquals(List.of("twenty", "twice"), complete(session, "println(1 + tw"));
        Assertions.assertEquals(List.of("twenty", "twice"), complete(session, ":type tw"));
        Assertions.assertTrue(complete(session, "pr").containsAll(List.of("print", "println", "private")), complete(session, "pr").toString());
        Assertions.assertEquals(List.of("def"), complete(session, "de"));
        // Where the candidates go : the word before the cursor.
        Assertions.assertEquals(new Completions.Completion(10, List.of("twenty", "twice"), 0), Completions.at(session, "println(1+tw", 12));
    }

    @Test
    public void testMembers() throws Exception {
        BabbleSession session = session(new StringWriter());
        session.eval("def P = class (def x : int, def y : int) (\n  def norm = fn () : int -> ( this.x * this.x + this.y * this.y )\n"
                + "  private def secret = 1\n) static (\n  def origin = fn () : P -> ( P(0, 0) )\n  private def hidden = 2\n)");
        session.eval("def p = P(3, 4)");
        session.eval("def names : java.util.List of string = java.util.ArrayList()");
        Assertions.assertEquals(List.of("p.norm", "p.x", "p.y"), complete(session, "p."));
        Assertions.assertEquals(List.of("p.norm"), complete(session, "1 + p.n"));
        Assertions.assertEquals(List.of("P.origin"), complete(session, "P."));
        Assertions.assertEquals(List.of("java.lang.Math.sqrt"), complete(session, "java.lang.Math.sqr"));
        Assertions.assertTrue(complete(session, "java.lang.Math.").contains("java.lang.Math.PI"));
        Assertions.assertTrue(complete(session, "names.").containsAll(List.of("names.add", "names.size")), complete(session, "names.").toString());
        Assertions.assertEquals(15, Completions.at(session, "java.lang.Math.sqr", 18).shared());
        // Nothing unknown, nothing on Babble's scalars, nothing after a call or a number.
        Assertions.assertEquals(List.of(), complete(session, "nosuch."));
        Assertions.assertEquals(List.of(), complete(session, "p.x."));
        Assertions.assertEquals(List.of(), complete(session, "P(1, 2)."));
        Assertions.assertEquals(List.of(), complete(session, "1."));
    }

    /** Completion never runs code : what follows a call is not offered, and the call doesn't happen. */
    @Test
    public void testCompletionRunsNothing() throws Exception {
        StringWriter output = new StringWriter();
        BabbleSession session = session(output);
        session.eval("def made = 0");
        session.eval("def Box = record (v : int) ()");
        session.eval("def make = fn io () : Box -> ( made = made + 1 ; println(\"made\") ; Box(1) )");
        Assertions.assertEquals(List.of(), complete(session, "make()."));
        Assertions.assertEquals(List.of(), complete(session, "make."));
        Assertions.assertEquals(0, session.eval("made"));
        Assertions.assertEquals("", output.toString());
    }

    @Test
    public void testModuleMembers() throws Exception {
        Files.writeString(directory.resolve("shapes.ba"), "export def area = fn (r : double) : double -> ( r * r )\nexport def unit = 1\ndef inner = 2");
        BabbleSession session = session(new StringWriter());
        session.eval("def shapes = import(\"" + directory.resolve("shapes.ba").toString().replace("\\", "\\\\") + "\")");
        Assertions.assertEquals(List.of("shapes.area", "shapes.unit"), complete(session, "shapes."));
    }

    @Test
    public void testFiles() throws Exception {
        Files.writeString(directory.resolve("shapes.ba"), "");
        Files.writeString(directory.resolve("shades.ba"), "");
        Files.createDirectory(directory.resolve("sub"));
        Files.writeString(directory.resolve(".hidden"), "");
        BabbleSession session = session(new StringWriter());
        String dir = directory.toString().replace('\\', '/') + "/";
        Assertions.assertEquals(List.of(dir + "shades.ba", dir + "shapes.ba", dir + "sub/"), complete(session, ":load " + dir + "s"));
        Assertions.assertEquals(List.of(dir + "shapes.ba"), complete(session, ":l  \"" + dir + "shap"));
        Assertions.assertEquals(List.of(dir + ".hidden"), complete(session, ":load " + dir + "."));
        Completions.Completion completion = Completions.at(session, ":load " + dir + "sh", (":load " + dir + "sh").length());
        Assertions.assertEquals(6, completion.start());
        Assertions.assertEquals(dir.length(), completion.shared());
        Assertions.assertEquals(List.of(), complete(session, ":load " + dir + "nosuch/x"));
    }

    // ---------------------------------------------------------------- the line editor

    @Test
    public void testEnterAcceptsOnlyCompleteInputs() {
        BabbleLineParser parser = new BabbleLineParser(null);
        Assertions.assertThrows(EOFError.class, () -> parser.parse("def f = fn () -> (", 18, Parser.ParseContext.ACCEPT_LINE));
        Assertions.assertThrows(EOFError.class, () -> parser.parse("1 +", 3, Parser.ParseContext.ACCEPT_LINE));
        Assertions.assertThrows(EOFError.class, () -> parser.parse("\"open", 5, Parser.ParseContext.ACCEPT_LINE));
        Assertions.assertEquals("1 + 2", parser.parse("1 + 2", 5, Parser.ParseContext.ACCEPT_LINE).line());
        // Syntax errors are complete : evaluating reports them.
        Assertions.assertEquals("def = 3", parser.parse("def = 3", 7, Parser.ParseContext.ACCEPT_LINE).line());
        Assertions.assertEquals("", parser.parse("", 0, Parser.ParseContext.ACCEPT_LINE).line());
        Assertions.assertEquals(":type (1 +", parser.parse(":type (1 +", 10, Parser.ParseContext.ACCEPT_LINE).line());
        // Only Enter waits for the rest : completion reads unfinished inputs.
        Assertions.assertEquals("(1 +", parser.parse("(1 +", 4, Parser.ParseContext.COMPLETE).line());
    }

    /** End to end on a dumb terminal : multi-line inputs come whole, Ctrl-D leaves, inputs are kept whole in the history. */
    @Test
    public void testTheLineEditor() throws Exception {
        Path history = directory.resolve("history");
        String typed = "def twice = fn (n : int) : int -> (\n  n * 2\n)\ntwice(21)\n:type twice\n";
        StringWriter output = new StringWriter();
        BabbleSession session = session(output);
        ByteArrayOutputStream screen = new ByteArrayOutputStream();
        Terminal terminal = new DumbTerminal(new ByteArrayInputStream(typed.getBytes(StandardCharsets.UTF_8)), screen);
        List<String> lines = new ArrayList<>();
        try (TerminalInput input = new TerminalInput(session, terminal, history)) {
            for (String line = input.readLine(">> "); line != null; line = input.readLine(">> ")) {
                lines.add(line);
            }
        }
        Assertions.assertEquals(List.of("def twice = fn (n : int) : int -> (\n  n * 2\n)", "twice(21)", ":type twice"), lines);
        String saved = Files.readString(history);
        Assertions.assertTrue(saved.contains("twice(21)") && saved.contains(":type twice"), saved);
        Assertions.assertEquals(3, saved.lines().count(), saved);

        // The loop on top of it sees complete inputs.
        StringWriter shown = new StringWriter();
        BabbleSession loopSession = session(shown);
        Terminal again = new DumbTerminal(new ByteArrayInputStream(typed.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream());
        try (TerminalInput input = new TerminalInput(loopSession, again, null)) {
            new org.babblelang.Repl(loopSession, input, new PrintWriter(shown), false).run();
        }
        Assertions.assertEquals(List.of("function twice", "42", "fn (n : int) : int"), List.of(shown.toString().split("\\R")));
    }
}
