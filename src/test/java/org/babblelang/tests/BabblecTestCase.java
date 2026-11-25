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
 * {@code babblec} (docs/design/bip006_module-sources-and-aot.md §5) : a program compiled ahead of
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
        List<String> command = new ArrayList<>();
        command.add("-jar");
        command.add(directory.resolve(jar).toString());
        Collections.addAll(command, args);
        return java(command);
    }

    /**
     * {@code java [-Dproperty…] -cp <jar>:<Babble> org.babblelang.runtime.Launcher args…} : with
     * Babble on the class path, as this test runs with it. Properties are the arguments that start
     * with "-D".
     */
    private Run runWithBabble(String jar, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        int i = 0;
        for (; i < args.length && args[i].startsWith("-D"); i++) {
            command.add(args[i]);
        }
        command.add("-cp");
        command.add(directory.resolve(jar) + java.io.File.pathSeparator + System.getProperty("java.class.path"));
        command.add("org.babblelang.runtime.Launcher");
        command.addAll(List.of(args).subList(i, args.length));
        return java(command);
    }

    private Run java(List<String> arguments) throws Exception {
        Path elsewhere = Files.createDirectories(directory.resolve("elsewhere"));
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(elsewhere.toFile());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("CLASSPATH");
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        Assertions.assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the program did not finish");
        return new Run(process.exitValue(), out, err);
    }

    private static final String SHAPES = "export def Shape = interface ( def area : fn () : double ; def describe = fn () -> ( \"area \" + this.area() ) )\n";

    private void program() throws IOException {
        file("lib/shapes.ba", SHAPES);
        file("lib/square.ba", "def shapes = import(\"shapes.ba\")\n"
                + "var made = 0\n"
                + "export def Square = class (def side : int) implements shapes.Shape ( def area = fn () : double -> ( 1.0 * this.side * this.side ) )\n"
                + "export def make = fn io (side : int) -> ( made = made + 1 ; Square(side) )\n"
                + "export def count = fn volatile () -> ( made )\n");
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
        file("lib/broken.ba", "def id = fn (v : any?) : any? -> ( v )\nexport def y = fn () : int -> ( 1 + (id(null) as int) )");
        file("main.ba", "println(\"started\")\nimport(\"lib/broken.ba\").y()");
        compile("main.ba", "app.jar");
        Run run = run("app.jar");
        Assertions.assertEquals(1, run.status());
        Assertions.assertEquals("started" + System.lineSeparator(), run.out());
        Assertions.assertTrue(run.err().contains("Error : Type error : expected int, got null in lib/broken.ba at line number 2"), run.err());
    }

    @Test
    public void dynamicImportsFindOnlyWhatTheJarHolds() throws Exception {
        file("lib/a.ba", "export def loaded : java.util.Map of (string, string) = java.util.HashMap()\nloaded.put(\"a\", \"a\")");
        file("lib/b.ba", "import(\"a.ba\").loaded.put(\"b\", \"b\")");
        file("main.ba", "def a = import(\"lib/a.ba\")\n"
                + "def load = fn io (n : string) : string -> ( import(\"lib/\" + n + \".ba\") ; a.loaded.get(n) as string )\n"
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

    /** A program whose main script imports b, c or what it is told, dynamically. */
    private void dynamicProgram() throws IOException {
        file("lib/a.ba", "println(\"loading a\")\nvar n = 0\nexport def next = fn io () : int -> ( n = n + 1 )\nexport def names : java.util.Map of (string, string) = java.util.HashMap()\nexport def actions : java.util.Map of (string, java.lang.Runnable) = java.util.HashMap()");
        file("lib/b.ba", "def a = import(\"a.ba\")\nimport(\"c.ba\")\nexport def name = \"b, \" + (a.names.get(\"c\") as string) + \", a at \" + a.next()\na.names.put(\"b\", name)");
        file("lib/c.ba", "def a = import(\"a.ba\")\nexport def name = \"c\"\na.names.put(\"c\", name)\ndef id = fn (v : any?) : any? -> ( v )\nexport def fail = fn () : int -> ( 1 + (id(null) as int) )\na.actions.put(\"c\", java.lang.Runnable(fn () -> ( fail() )))");
        file("main.ba", "def a = import(\"lib/a.ba\")\n"
                + "a.next()\n"
                + "def load = fn io (n : string) : string -> ( import(\"lib/\" + n + \".ba\") ; a.names.get(n) as string )\n"
                + "var i = 0\n"
                + "while i < args.size() then ( println(load(args.get(i) as string)) ; i = i + 1 )\n");
    }

    @Test
    public void withBabbleOnTheClassPathDynamicImportsCompile() throws Exception {
        dynamicProgram();
        compile("main.ba", "app.jar");
        Run run = runWithBabble("app.jar", "b", "b", "c");
        Assertions.assertEquals(0, run.status(), run.err());
        // lib/b.ba is compiled from its file, and imports lib/c.ba, compiled too, and lib/a.ba,
        // which is the jar's : it runs once, and its state is the one the main script saw.
        Assertions.assertEquals(String.join(System.lineSeparator(), "loading a", "b, c, a at 2", "b, c, a at 2", "c", ""), run.out());

        // Without Babble, the same jar can't.
        run = run("app.jar", "b");
        Assertions.assertEquals(1, run.status());
        Assertions.assertTrue(run.err().contains("Cannot import lib/b.ba : a compiled program only has the modules it imports statically"), run.err());
    }

    @Test
    public void compiledModulesFailLikeTheOthers() throws Exception {
        dynamicProgram();
        file("lib/bad.ba", "export def x = (\n");
        file("main.ba", "def a = import(\"lib/a.ba\") ; import(\"lib/\" + (args.get(0) as string) + \".ba\") ; (a.actions.get(args.get(0)) as java.lang.Runnable).run()\n");
        compile("main.ba", "app.jar");
        Run run = runWithBabble("app.jar", "c");
        Assertions.assertEquals(1, run.status());
        Assertions.assertTrue(run.err().contains("Error : Type error : expected int, got null in lib/c.ba at line number 5"), run.err());

        run = runWithBabble("app.jar", "bad");
        Assertions.assertEquals(1, run.status());
        Assertions.assertTrue(run.err().contains("Error : Cannot import lib/bad.ba : Syntax error : unexpected end of input in lib/bad.ba at line number 2"), run.err());

        run = runWithBabble("app.jar", "nope");
        Assertions.assertEquals(1, run.status());
        Assertions.assertTrue(run.err().contains("Error : Cannot import lib/nope.ba : no such file : "), run.err());

        run = runWithBabble("app.jar", "../../outside");
        Assertions.assertEquals(1, run.status());
        Assertions.assertTrue(run.err().contains("Error : Cannot import lib/../../outside.ba : no such module"), run.err());
    }

    @Test
    public void theRootIsFoundFromTheJar() throws Exception {
        dynamicProgram();
        // The jar elsewhere : the index says where the root is, relative to it.
        ScriptContext context = new SimpleScriptContext();
        context.setErrorWriter(errors);
        ProgramIndex index = Babblec.compile(directory.resolve("main.ba"), directory, directory.resolve("out/bin/app.jar"), context);
        Assertions.assertEquals("../..", index.root());
        Assertions.assertEquals("c" + System.lineSeparator(), runWithBabble("out/bin/app.jar", "c").out().replace("loading a" + System.lineSeparator(), ""));

        // Moved : the root is told.
        Path moved = Files.createDirectories(directory.resolve("moved"));
        Files.copy(directory.resolve("out/bin/app.jar"), moved.resolve("app.jar"));
        Run run = runWithBabble("moved/app.jar", "c");
        Assertions.assertEquals(1, run.status());
        Assertions.assertTrue(run.err().contains("Cannot import lib/c.ba : no such file"), run.err());
        run = runWithBabble("moved/app.jar", "-Dbabble.root=" + directory, "c");
        Assertions.assertEquals(0, run.status(), run.err());
        Assertions.assertTrue(run.out().endsWith("c" + System.lineSeparator()), run.out());
    }

    @Test
    public void exceptionHandlingWorksInAotJar() throws Exception {
        Files.writeString(directory.resolve("main.ba"), """
                def AppErr = record (code: int, msg: string) ()
                def v = try (
                    java.lang.Integer.parseInt("bad")
                ) catch : java.lang.NumberFormatException then (
                    try (
                        throw AppErr(404, "missing")
                    ) catch e: AppErr then (
                        e.code
                    ) finally (
                        println("cleaned")
                    )
                )
                println(v)
                if args.size() > 0 then (
                    throw AppErr(500, "uncaught")
                )
                """);
        compile("main.ba", "exc.jar");
        Run ok = run("exc.jar");
        Assertions.assertEquals(0, ok.status(), ok.err());
        Assertions.assertEquals("cleaned" + System.lineSeparator() + "404" + System.lineSeparator(), ok.out());

        Run fail = run("exc.jar", "boom");
        Assertions.assertEquals(1, fail.status());
        Assertions.assertTrue(fail.err().contains("AppErr(code: 500, msg: uncaught)"), fail.err());
    }
}
