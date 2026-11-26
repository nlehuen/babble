package org.babblelang.tests;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.ClassPathModuleSource;
import org.babblelang.engine.FileModuleSource;
import org.babblelang.engine.ModuleSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What is particular to modules in files and in the class path : the default source's real paths, a
 * rooted source confining imports to a directory, and modules shipped as resources
 * (docs/design/bip006_module-sources-and-aot.md §4.2). Everything else about modules is
 * BabbleModulesTestCase's, in memory.
 */
public class FileModulesTestCase {
    @TempDir Path directory;

    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();

    private Path file(String name, String content) throws IOException {
        Path path = directory.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private Object run(ModuleSource source, String script, String fileName) throws ScriptException {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        if (source != null) {
            engine.put(BabbleScriptEngine.MODULE_SOURCE, source);
        }
        ScriptContext context = engine.getContext();
        if (fileName != null) {
            context.setAttribute(ScriptEngine.FILENAME, fileName, ScriptContext.ENGINE_SCOPE);
        }
        context.setWriter(output);
        context.setErrorWriter(errors);
        return engine.eval(script);
    }

    private Object run(String script) throws ScriptException {
        return run(null, script, directory.resolve("main.ba").toString());
    }

    private String failure(ModuleSource source, String script, String fileName) {
        return Assertions.assertThrows(ScriptException.class, () -> run(source, script, fileName))
                .getMessage();
    }

    // ---------------------------------------------------------------- the default source

    @Test
    public void filesAreTheDefault() throws Exception {
        file("lib.ba", "export def answer = 42");
        Assertions.assertEquals(42, run("import(\"lib.ba\").answer"));
        // A script named after a file that does not exist imports from that file's directory.
        Assertions.assertFalse(Files.exists(directory.resolve("main.ba")));
    }

    @Test
    public void everySpellingOfAFileIsOneModule() throws Exception {
        file(
                "lib/counter.ba",
                "println(\"loading\")\nvar n = 0\nexport def next = fn io () -> ( n = n + 1 )");
        Files.createSymbolicLink(directory.resolve("link.ba"), directory.resolve("lib/counter.ba"));
        Object result =
                run(
                        "import(\"lib/counter.ba\").next()\nimport(\"lib/../lib/counter.ba\").next()\nimport(\"link.ba\").next()");
        Assertions.assertEquals(3, result);
        Assertions.assertEquals("loading" + System.lineSeparator(), output.toString());
    }

    @Test
    public void anAbsolutePathWorksFromAnywhere() throws Exception {
        Path top = file("top.ba", "export def name = \"top\"");
        Assertions.assertEquals(
                "top",
                run(null, "import(\"" + top.toString().replace("\\", "\\\\") + "\").name", null));
    }

    @Test
    public void messagesNameFilesByTheirPaths() throws Exception {
        file("broken.ba", "export def x = 1\nexport def y = 1 + null");
        String message =
                failure(null, "\nimport(\"broken.ba\")", directory.resolve("main.ba").toString());
        Assertions.assertTrue(
                message.contains(directory.resolve("broken.ba") + " at line number 2"), message);
        message = failure(null, "import(\"nowhere.ba\")", directory.resolve("main.ba").toString());
        Assertions.assertTrue(
                message.contains(
                        "Cannot import nowhere.ba : no such file : "
                                + directory.resolve("nowhere.ba")),
                message);
    }

    // ---------------------------------------------------------------- a rooted source

    @Test
    public void aRootedSourceNamesModulesFromItsRoot() throws Exception {
        file("app/lib/shapes.ba", "export def Shape = interface ( def area : fn () : double )");
        file("app/lib/broken.ba", "def shapes = import(\"shapes.ba\")\nexport def y = 1 + null");
        FileModuleSource source = FileModuleSource.rooted(directory.resolve("app"));
        // Display names are relative to the root : the same on every machine.
        String message =
                failure(
                        source,
                        "import(\"lib/broken.ba\")",
                        directory.resolve("app/main.ba").toString());
        Assertions.assertTrue(message.contains("in lib/broken.ba at line number 2"), message);
        Assertions.assertFalse(message.contains(directory.toString() + "/app/lib"), message);
        // A name from the root, and a script named relative to the root.
        file("app/lib/answer.ba", "export def answer = 42");
        Assertions.assertEquals(
                42, run(source, "import(\"/lib/answer.ba\").answer", "deep/down/main.ba"));
        Assertions.assertEquals(
                42, run(source, "import(\"../../lib/answer.ba\").answer", "deep/down/main.ba"));
    }

    @Test
    public void aRootedSourceKeepsImportsInside() throws Exception {
        file("secret.ba", "export def secret = 1");
        file("app/lib.ba", "export def x = 1");
        Files.createSymbolicLink(
                directory.resolve("app/escape.ba"), directory.resolve("secret.ba"));
        FileModuleSource source = FileModuleSource.rooted(directory.resolve("app"));
        String main = directory.resolve("app/main.ba").toString();
        Assertions.assertTrue(
                failure(source, "import(\"../secret.ba\")", main)
                        .contains("Cannot import ../secret.ba : no such module"));
        Assertions.assertTrue(
                failure(source, "import(\"escape.ba\")", main)
                        .contains("Cannot import escape.ba : no such module"));
        Assertions.assertTrue(
                failure(source, "import(\"" + directory.resolve("secret.ba") + "\")", main)
                        .contains("no such"));
        Assertions.assertEquals(1, run(source, "import(\"lib.ba\").x", main));
    }

    @Test
    public void aRootedSourceSeesChanges() throws Exception {
        file("app/lib.ba", "export def answer = 1");
        FileModuleSource source = FileModuleSource.rooted(directory.resolve("app"));
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODULE_SOURCE, source);
        engine.put(ScriptEngine.FILENAME, "main.ba");
        Assertions.assertEquals(1, engine.eval("import(\"lib.ba\").answer"));
        file("app/lib.ba", "export def answer = 2");
        Assertions.assertEquals(2, engine.eval("import(\"lib.ba\").answer"));
    }

    // ---------------------------------------------------------------- the class path

    @Test
    public void modulesCanBeResources() throws Exception {
        ClassPathModuleSource source =
                new ClassPathModuleSource(getClass().getClassLoader(), "babble-modules");
        Assertions.assertEquals(
                "area 9.0",
                run(source, "import(\"lib/square.ba\").Square(3).describe()", "main.ba"));
        Assertions.assertTrue(
                failure(source, "import(\"lib/nope.ba\")", "main.ba")
                        .contains("Cannot import lib/nope.ba : no such module"));
        Assertions.assertTrue(
                failure(source, "import(\"../babble-modules/lib/shapes.ba\")", "main.ba")
                        .contains("no such module"));
    }
}
