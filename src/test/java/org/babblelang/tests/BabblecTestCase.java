package org.babblelang.tests;

import org.babblelang.compiler.Babblec;
import org.babblelang.runtime.ProgramIndex;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.script.ScriptContext;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * {@code babblec} (docs/design/module-sources-and-aot.md §5) : a program compiled ahead of
 * time into a jar, run in a fresh JVM with that jar alone on its class path.
 */
public class BabblecTestCase {
    @TempDir
    Path directory;

    private final StringWriter errors = new StringWriter();

    private void file(String name, String content) throws IOException {
        Path path = directory.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private ProgramIndex compile(String main, String jar) throws Exception {
        ScriptContext context = new SimpleScriptContext();
        context.setErrorWriter(errors);
        return Babblec.compile(directory.resolve(main), directory, directory.resolve(jar), context);
    }

    record Run(int status, String out, String err) {
    }

    /** {@code java -jar <jar> args…}, from another directory, with nothing else on the class path. */
    private Run run(String jar, String... args) throws Exception {
        Path elsewhere = Files.createDirectories(directory.resolve("elsewhere"));
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-jar");
        command.add(directory.resolve(jar).toString());
        Collections.addAll(command, args);
        ProcessBuilder builder = new ProcessBuilder(command).directory(elsewhere.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("CLASSPATH");
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        Assertions.assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the program did not finish");
        return new Run(process.exitValue(), out, err);
    }

    private static final String SHAPES = "export def Shape = interface ( def area : () : double ; def describe = () -> ( \"area \" + this.area() ) )\n";

    private void program() throws IOException {
        file("lib/shapes.ba", SHAPES);
        file("lib/square.ba", "def shapes = import(\"shapes.ba\")\n"
                + "def made = 0\n"
                + "export def Square = class (def side : int) implements shapes.Shape ( def area = () : double -> ( 1.0 * this.side * this.side ) )\n"
                + "export def make = (side : int) -> ( made = made + 1 ; Square(side) )\n"
                + "export def count = () -> ( made )\n");
        file("main.ba", "def square = import(\"lib/square.ba\")\n"
                + "println(square.make(3).describe(), \" \", args.get(0), \" \", args.size())\n"
                + "import(\"lib/square.ba\").make(2)\n"
                + "println(\"made \", square.count())\n");
    }

    @Test
    public void aCompiledProgramRunsOnItsOwn() throws Exception {
        program();
        ProgramIndex index = compile("main.ba", "app.jar");
        Assertions.assertEquals("main.ba", index.main());
        Assertions.assertEquals(List.of("main.ba", "lib/shapes.ba", "lib/square.ba"), List.copyOf(index.modules().keySet()));
        Run run = run("app.jar", "hello", "world");
        Assertions.assertEquals(0, run.status(), run.err());
        // The modules run once, share their state, and the program gets its arguments.
        Assertions.assertEquals("area 9.0 hello 2" + System.lineSeparator() + "made 2" + System.lineSeparator(), run.out());
    }

    @Test
    public void theJarHoldsTheProgramAndTheRuntimeOnly() throws Exception {
        program();
        compile("main.ba", "app.jar");
        try (JarFile jar = new JarFile(directory.resolve("app.jar").toFile())) {
            Assertions.assertEquals("org.babblelang.runtime.Launcher", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            List<String> names = jar.stream().map(e -> e.getName()).collect(Collectors.toList());
            Assertions.assertTrue(names.contains(ProgramIndex.RESOURCE), names.toString());
            Assertions.assertTrue(names.contains("babble/main$ba.class"), names.toString());
            Assertions.assertTrue(names.contains("babble/lib/square$ba.class"), names.toString());
            Assertions.assertTrue(names.contains("org/babblelang/runtime/Bootstrap.class"), names.toString());
            for (String name : names) {
                Assertions.assertFalse(name.startsWith("org/antlr/") || name.startsWith("org/objectweb/")
                        || name.startsWith("org/babblelang/parser/") || name.startsWith("org/babblelang/analysis/")
                        || name.startsWith("org/babblelang/compiler/")
                        || name.startsWith("org/babblelang/engine/") && !name.startsWith("org/babblelang/engine/impl/natives/"), name);
            }
        }
    }

    @Test
    public void aProgramThatFailsSaysWhereAndExitsWithOne() throws Exception {
        file("lib/broken.ba", "export def x = 1\nexport def y = () -> ( 1 + null )");
        file("main.ba", "println(\"started\")\nimport(\"lib/broken.ba\").y()");
        compile("main.ba", "app.jar");
        Run run = run("app.jar");
        Assertions.assertEquals(1, run.status());
        Assertions.assertEquals("started" + System.lineSeparator(), run.out());
        Assertions.assertTrue(run.err().contains("Error : Not a number : null in lib/broken.ba at line number 2"), run.err());
    }

    @Test
    public void dynamicImportsFindOnlyWhatTheJarHolds() throws Exception {
        file("lib/a.ba", "export def name = \"a\"");
        file("lib/b.ba", "export def name = \"b\"");
        file("main.ba", "def a = import(\"lib/a.ba\")\n"
                + "def load = (n) -> ( import(\"lib/\" + n + \".ba\").name )\n"
                + "println(load(\"a\"))\n"
                + "println(load(\"b\"))\n");
        compile("main.ba", "app.jar");
        Run run = run("app.jar");
        // lib/a.ba is imported statically too : it is in the jar. lib/b.ba isn't.
        Assertions.assertEquals("a" + System.lineSeparator(), run.out());
        Assertions.assertEquals(1, run.status());
        Assertions.assertTrue(run.err().contains("Cannot import lib/b.ba : a compiled program only has the modules it imports statically"), run.err());
    }

    @Test
    public void aProgramCompilesToTheSameJarEveryTime() throws Exception {
        program();
        compile("main.ba", "one.jar");
        compile("main.ba", "two.jar");
        Assertions.assertArrayEquals(Files.readAllBytes(directory.resolve("one.jar")), Files.readAllBytes(directory.resolve("two.jar")));
    }

    @Test
    public void errorsAreFoundWhenCompiling() throws Exception {
        file("main.ba", "import(\"nowhere.ba\")");
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> compile("main.ba", "app.jar"));
        Assertions.assertTrue(e.getMessage().contains("Cannot import nowhere.ba"), e.getMessage());
        Assertions.assertFalse(Files.exists(directory.resolve("app.jar")));

        // Modules stay under the root.
        file("app/main.ba", "import(\"../outside.ba\")");
        file("outside.ba", "export def x = 1");
        ScriptContext context = new SimpleScriptContext();
        context.setErrorWriter(errors);
        e = Assertions.assertThrows(ScriptException.class,
                () -> Babblec.compile(directory.resolve("app/main.ba"), directory.resolve("app"), directory.resolve("app.jar"), context));
        Assertions.assertTrue(e.getMessage().contains("Cannot import ../outside.ba : no such module"), e.getMessage());
    }
}
