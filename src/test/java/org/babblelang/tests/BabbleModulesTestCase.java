package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Modules : {@code export} and {@code import("file.ba")}. Each test writes its files to a
 * temporary directory and runs a main script named {@code main.ba} there.
 */
public class BabbleModulesTestCase {
    @TempDir
    Path directory;

    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();

    private void file(String name, String content) throws IOException {
        Path path = directory.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private Object run(String script) throws ScriptException {
        return run(script, directory.resolve("main.ba").toString());
    }

    private Object run(String script, String fileName) throws ScriptException {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        if (fileName != null) {
            context.setAttribute(ScriptEngine.FILENAME, fileName, ScriptContext.ENGINE_SCOPE);
        }
        context.setWriter(output);
        context.setErrorWriter(errors);
        return engine.eval(script, context);
    }

    private String failure(String script) {
        return Assertions.assertThrows(ScriptException.class, () -> run(script)).getMessage();
    }

    @Test
    public void exportsAreMembersOfTheModule() throws Exception {
        file("lib.ba", "export def answer = 42\nexport def twice = (n) -> ( n * 2 )");
        Assertions.assertEquals(84, run("def lib = import(\"lib.ba\") ; lib.twice(lib.answer)"));
        Assertions.assertEquals(84, run("def lib = import(\"./lib.ba\") ; lib.twice(n: lib.answer)"));
    }

    @Test
    public void onlyExportsAreVisible() throws Exception {
        file("lib.ba", "def secret = 1\nexport def open = () -> ( secret + 1 )");
        Assertions.assertEquals(2, run("import(\"lib.ba\").open()"));
        String message = failure("def lib = import(\"lib.ba\")\nlib.secret");
        Assertions.assertTrue(message.contains("No such export : secret in " + directory.resolve("lib.ba")), message);
        Assertions.assertTrue(message.endsWith("at line number 2"), message);
        // The names of a module are not names of the script importing it, and conversely.
        Assertions.assertTrue(failure("import(\"lib.ba\") ; open()").contains("No such name : open"));
        file("needy.ba", "export def get = () -> ( mine )");
        Assertions.assertTrue(failure("def mine = 1 ; import(\"needy.ba\").get()").contains("No such name : mine"));
    }

    @Test
    public void exportsAreReadOnlyFromOutside() throws Exception {
        file("lib.ba", "export def n = 1\nexport def bump = () -> ( n = n + 1 )");
        Assertions.assertTrue(failure("def lib = import(\"lib.ba\") ; lib.n = 5").contains("Cannot assign to an export : n"));
        // The module itself can reassign them, and importers see the current value.
        Assertions.assertEquals(3, run("def lib = import(\"lib.ba\") ; lib.bump() ; lib.bump() ; lib.n"));
    }

    @Test
    public void aModuleRunsOncePerEval() throws Exception {
        file("lib.ba", "println(\"loading\")\nexport def counter = java.util.ArrayList()");
        file("user.ba", "def lib = import(\"lib.ba\")\nexport def add = (v) -> ( lib.counter.add(v) )");
        Object size = run("def lib = import(\"lib.ba\") ; def user = import(\"user.ba\") ; user.add(1) ; import(\"./lib.ba\").counter.size()");
        Assertions.assertEquals(1, size);
        Assertions.assertEquals("loading" + System.lineSeparator(), output.toString());
        // Another eval starts afresh.
        Assertions.assertEquals(0, run("import(\"lib.ba\").counter.size()"));
    }

    @Test
    public void pathsAreRelativeToTheImportingFile() throws Exception {
        file("lib/deep/leaf.ba", "export def name = \"leaf\"");
        file("lib/branch.ba", "export def leaf = import(\"deep/leaf.ba\")\nexport def up = import(\"../top.ba\")");
        file("top.ba", "export def name = \"top\"");
        Assertions.assertEquals("leaf top", run("def b = import(\"lib/branch.ba\") ; b.leaf.name + \" \" + b.up.name"));
        // An absolute path works from anywhere, and so does a script without a file name.
        String absolute = directory.resolve("top.ba").toString().replace("\\", "\\\\");
        Assertions.assertEquals("top", run("import(\"" + absolute + "\").name", null));
    }

    @Test
    public void anImportInAFunctionIsRelativeToItsFile() throws Exception {
        file("lib/data.ba", "export def value = 7");
        file("lib/loader.ba", "export def load = () -> ( import(\"data.ba\").value )");
        Assertions.assertEquals(7, run("import(\"lib/loader.ba\").load()"));
    }

    @Test
    public void cyclesWork() throws Exception {
        file("a.ba", "def b = import(\"b.ba\")\nexport def name = \"a\"\nexport def other = () -> ( b.name )");
        file("b.ba", "def a = import(\"a.ba\")\nexport def name = \"b\"\nexport def other = () -> ( a.name )");
        Assertions.assertEquals("ba", run("import(\"a.ba\").other() + import(\"b.ba\").other()"));
    }

    @Test
    public void aCycleCannotReadAnExportBeforeItsDefinition() throws Exception {
        file("a.ba", "def b = import(\"b.ba\")\nexport def name = \"a\"");
        file("b.ba", "def a = import(\"a.ba\")\nexport def name = \"b\" + a.name");
        String message = failure("import(\"a.ba\")");
        Assertions.assertTrue(message.contains("Name used before definition : name"), message);
        Assertions.assertTrue(message.contains("b.ba at line number 2"), message);
    }

    @Test
    public void errorsNameTheModule() throws Exception {
        file("broken.ba", "export def x = 1\nexport def y = 1 + null");
        String message = failure("\nimport(\"broken.ba\")");
        Assertions.assertTrue(message.contains(directory.resolve("broken.ba") + " at line number 2"), message);
        Assertions.assertTrue(message.endsWith("main.ba at line number 2"), message);
        Assertions.assertFalse(message.contains("BabbleException: org.babblelang"), message);

        file("syntax.ba", "def = 1");
        Assertions.assertThrows(ScriptException.class, () -> run("import(\"syntax.ba\")"));

        String missing = failure("import(\"nowhere.ba\")");
        Assertions.assertTrue(missing.contains("Cannot import nowhere.ba : no such file"), missing);
    }

    @Test
    public void aFailedModuleIsNotKept() throws Exception {
        file("flaky.ba", "export def x = 1\nassert(ready, \"not ready\")");
        Assertions.assertTrue(failure("import(\"flaky.ba\")").contains("No such name : ready"));
        file("flaky.ba", "export def x = 1");
        Assertions.assertEquals(1, run("import(\"flaky.ba\").x"));
    }

    @Test
    public void exportOnlyAppliesToTheTopLevelOfAFile() {
        for (String script : new String[]{
                "def f = () -> ( export def x = 1 )",
                "if true then ( export def x = 1 )",
                "package p ( export def x = 1 )",
                "package p ( export package q ( ) )",
                "def C = class () ( export def x = 1 )"}) {
            String message = failure(script);
            Assertions.assertTrue(message.contains("export only applies to the top-level definitions of a file"), message);
        }
    }

    @Test
    public void exportedPackagesAndClasses() throws Exception {
        file("lib.ba", "export package geo ( def Point = value class (x, y) () ; def zero = Point(0, 0) )\n"
                + "export def Counter = class () ( def n = 0 ; def next = () -> ( this.n = this.n + 1 ) ) static ( def start = () -> ( this() ) )");
        Assertions.assertEquals(true, run("def lib = import(\"lib.ba\") ; lib.geo.zero == lib.geo.Point(0, 0)"));
        Assertions.assertEquals(2, run("def Counter = import(\"lib.ba\").Counter ; def c = Counter.start() ; c.next() ; c.next()"));
    }

    @Test
    public void aScriptCanExportToo() throws Exception {
        // export has no effect on a script that is not imported.
        Assertions.assertEquals(3, run("export def a = 1 ; export def b = a + 2 ; b"));
    }

    @Test
    public void modulesRunInTheModeOfTheEngineWithItsTypeChecker() throws Exception {
        file("typed.ba", "export def half = (n : int) : double -> ( n / 2 )\nhalf(\"x\")");
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.TYPECHECK, "error");
        ScriptContext context = new SimpleScriptContext();
        context.setAttribute(ScriptEngine.FILENAME, directory.resolve("main.ba").toString(), ScriptContext.ENGINE_SCOPE);
        context.setErrorWriter(errors);
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> engine.eval("import(\"typed.ba\")", context));
        Assertions.assertTrue(e.getMessage().contains("Type error : expected int, got string"), e.getMessage());
        Assertions.assertTrue(errors.toString().contains("typed.ba at line number 2"), errors.toString());
    }

    @Test
    public void typesFromModulesAreDynamic() throws Exception {
        file("lib.ba", "export def Circle = class (def r : int) ()\nexport package geo ( def Point = value class (x, y) () )");
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.TYPECHECK, "error");
        ScriptContext context = new SimpleScriptContext();
        context.setAttribute(ScriptEngine.FILENAME, directory.resolve("main.ba").toString(), ScriptContext.ENGINE_SCOPE);
        context.setErrorWriter(errors);
        Object result = engine.eval("def lib = import(\"lib.ba\") ; def Circle = lib.Circle\n"
                + "def a : lib.Circle = Circle(1) ; def b : Circle = lib.Circle(2) ; def p : lib.geo.Point = lib.geo.Point(1, 2)\n"
                + "def sum = (x : Circle, y : lib.Circle) : int -> ( x.r + y.r ) ; sum(a, b) + p.x", context);
        Assertions.assertEquals(4, result);
        Assertions.assertEquals("", errors.toString());
        // The types the module declares for itself are still enforced.
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> engine.eval("import(\"lib.ba\").Circle(\"wide\")", context));
        Assertions.assertTrue(e.getMessage().contains("Type error : expected int, got string"), e.getMessage());
    }

    @Test
    public void javaPackagesAreStillImported() throws Exception {
        Assertions.assertEquals(0, run("def util = import(\"java\").util ; util.ArrayList().size()"));
    }
}
