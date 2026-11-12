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
    public void typesFromModulesAreCheckedInErrorMode() throws Exception {
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

    // ---------------------------------------------------------------- static imports (M2)

    /** One engine for several evals : what its module cache keeps between them. */
    private Object run(ScriptEngine engine, String script) throws ScriptException {
        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        context.setAttribute(ScriptEngine.FILENAME, directory.resolve("main.ba").toString(), ScriptContext.ENGINE_SCOPE);
        context.setWriter(output);
        context.setErrorWriter(errors);
        return engine.eval(script, context);
    }

    private static final String SHAPES = "export def Shape = interface (\n"
            + "    def area : () : double\n"
            + "    def describe = () -> ( \"area \" + this.area() )\n"
            + ")\n";

    @Test
    public void aMissingFileIsFoundBeforeRunning() {
        String message = failure("println(\"started\")\nimport(\"nowhere.ba\")");
        Assertions.assertTrue(message.contains("Cannot import nowhere.ba : no such file"), message);
        Assertions.assertTrue(message.endsWith("main.ba at line number 2"), message);
        Assertions.assertEquals("", output.toString());
    }

    @Test
    public void aDynamicImportIsResolvedWhenItRuns() {
        String message = failure("println(\"started\")\ndef name = \"nowhere\"\nimport(name + \".ba\")");
        Assertions.assertTrue(message.contains("Cannot import nowhere.ba : no such file"), message);
        Assertions.assertEquals("started" + System.lineSeparator(), output.toString());
    }

    @Test
    public void aBrokenModuleIsFoundBeforeRunning() throws Exception {
        file("syntax.ba", "export def x = 1\ndef = 2");
        String message = failure("println(\"started\")\nimport(\"syntax.ba\")");
        Assertions.assertTrue(message.contains("Syntax error"), message);
        Assertions.assertTrue(message.contains("syntax.ba at line number 2"), message);
        Assertions.assertTrue(message.endsWith("main.ba at line number 2"), message);
        Assertions.assertFalse(message.contains("BabbleException: org.babblelang"), message);

        file("misplaced.ba", "export def x = 1\nreturn x");
        message = failure("import(\"misplaced.ba\")");
        Assertions.assertTrue(message.contains("misplaced.ba at line number 2"), message);
        Assertions.assertEquals("", output.toString());
    }

    @Test
    public void aClassImplementsAnImportedInterface() throws Exception {
        file("shapes.ba", SHAPES);
        Assertions.assertEquals("area 9 area 4 area 1", run("def shapes = import(\"shapes.ba\")\n"
                + "def Square = class (def side) implements shapes.Shape ( def area = () -> ( this.side * this.side ) )\n"
                + "def Shape = shapes.Shape\n"
                + "def Rect = class (def w, def h) implements Shape ( def area = () -> ( this.w * this.h ) )\n"
                + "def Unit = import(\"shapes.ba\").Shape\n"
                + "def u = object implements Unit ( def area = () -> ( 1 ) )\n"
                + "assert(Square(3) is Shape and u is shapes.Shape)\n"
                + "Square(3).describe() + \" \" + Rect(2, 2).describe() + \" \" + u.describe()"));
        Assertions.assertEquals("", errors.toString());
    }

    @Test
    public void anImportedInterfaceIsCheckedLikeALocalOne() throws Exception {
        file("shapes.ba", SHAPES);
        String message = failure("println(\"started\")\ndef shapes = import(\"shapes.ba\")\n"
                + "def Empty = class () implements shapes.Shape ( )");
        Assertions.assertTrue(message.contains("Empty does not implement Shape.area"), message);
        Assertions.assertTrue(message.endsWith("main.ba at line number 3"), message);
        Assertions.assertEquals("", output.toString());
    }

    @Test
    public void interfacesExtendAndCallImportedInterfaces() throws Exception {
        file("shapes.ba", SHAPES);
        Assertions.assertEquals("square, area 4 / area 9", run("def shapes = import(\"shapes.ba\")\n"
                + "def Named = interface extends shapes.Shape ( def name : () : string )\n"
                + "def Square = class () implements Named (\n"
                + "    def area = () -> ( 4 )\n"
                + "    def name = () -> ( \"square\" )\n"
                + "    def describe = () -> ( this.name() + \", \" + super.describe() )\n"
                + ")\n"
                + "def Big = class () implements shapes.Shape (\n"
                + "    def area = () -> ( 9 )\n"
                + "    def describe = () -> ( super(shapes.Shape).describe() )\n"
                + ")\n"
                + "Square().describe() + \" / \" + Big().describe()"));
    }

    @Test
    public void reExportsAndExportedPackagesHoldInterfaces() throws Exception {
        file("shapes.ba", SHAPES);
        file("lib/all.ba", "export def Shape = import(\"../shapes.ba\").Shape\n"
                + "export def shapes = import(\"../shapes.ba\")\n"
                + "export package geo ( def Solid = interface ( def volume : () : double ) )");
        Assertions.assertEquals(8, run("def all = import(\"lib/all.ba\")\n"
                + "def A = class () implements all.Shape ( def area = () -> ( 1 ) )\n"
                + "def B = class () implements all.shapes.Shape ( def area = () -> ( 2 ) )\n"
                + "def C = class () implements all.geo.Solid ( def volume = () -> ( 5 ) )\n"
                + "A().area() + B().area() + C().volume()"));
    }

    @Test
    public void onlyExportedInterfacesCanBeImplemented() throws Exception {
        file("hidden.ba", "def Shape = interface ( def area : () : double )\nexport def x = 1");
        String message = failure("def hidden = import(\"hidden.ba\")\ndef C = class () implements hidden.Shape ( def area = () -> ( 1 ) )");
        Assertions.assertTrue(message.contains("implements needs a statically known interface"), message);
    }

    @Test
    public void anInterfaceOfAFileInACycleIsNotKnownStatically() throws Exception {
        file("a.ba", "def b = import(\"b.ba\")\nexport def Shape = interface ( def area : () : double )");
        file("b.ba", "def a = import(\"a.ba\")\nexport def Square = class () implements a.Shape ( def area = () -> ( 1 ) )");
        String message = failure("import(\"a.ba\")");
        Assertions.assertTrue(message.contains("is still being analysed (an import cycle)"), message);
        Assertions.assertTrue(message.contains("b.ba at line number 2"), message);
    }

    @Test
    public void missingExportsAreReportedBeforeRunning() throws Exception {
        file("lib.ba", "export def answer = 42\ndef secret = 1");
        String message = failure("def lib = import(\"lib.ba\")\nprintln(\"started\")\nlib.secret");
        Assertions.assertTrue(errors.toString().startsWith("Warning : No such export : secret in " + directory.resolve("lib.ba")), errors.toString());
        Assertions.assertTrue(errors.toString().contains("main.ba at line number 3, column 5"), errors.toString());
        // A warning : the script runs, and fails where the warning said it would.
        Assertions.assertEquals("started" + System.lineSeparator(), output.toString());
        Assertions.assertTrue(message.contains("No such export : secret"), message);

        errors.getBuffer().setLength(0);
        failure("def lib = import(\"lib.ba\")\nlib.answer = 1");
        Assertions.assertTrue(errors.toString().startsWith("Warning : Cannot assign to an export : answer"), errors.toString());

        errors.getBuffer().setLength(0);
        failure("import(\"lib.ba\").nope");
        Assertions.assertTrue(errors.toString().startsWith("Warning : No such export : nope"), errors.toString());
    }

    @Test
    public void missingExportsAreErrorsInErrorMode() throws Exception {
        file("lib.ba", "export def answer = 42");
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.TYPECHECK, "error");
        ScriptException e = Assertions.assertThrows(ScriptException.class,
                () -> run(engine, "def lib = import(\"lib.ba\")\nprintln(\"started\")\nlib.anwser"));
        Assertions.assertTrue(e.getMessage().contains("No such export : anwser"), e.getMessage());
        Assertions.assertEquals("", output.toString());
    }

    @Test
    public void aScriptDefiningImportCallsItsOwn() throws Exception {
        Assertions.assertEquals("got nowhere.ba", run("def import = (n) -> ( \"got \" + n )\nimport(\"nowhere.ba\")"));
    }

    @Test
    public void anEngineCompilesAModuleOnceUntilItChanges() throws Exception {
        // The module warns when it is analysed : the warnings count its analyses.
        file("lib.ba", "def other = import(\"other.ba\")\nexport def f = () -> ( other.nope )");
        file("other.ba", "export def x = 1");
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        run(engine, "import(\"lib.ba\")");
        run(engine, "import(\"lib.ba\") ; import(\"./lib.ba\")");
        Assertions.assertEquals(1, errors.toString().split("No such export : nope", -1).length - 1, errors.toString());

        // A change to a file it depends on recompiles it.
        file("other.ba", "export def x = 2");
        run(engine, "import(\"lib.ba\")");
        Assertions.assertEquals(2, errors.toString().split("No such export : nope", -1).length - 1, errors.toString());
    }

    @Test
    public void aChangedInterfaceRechecksTheFilesImplementingIt() throws Exception {
        file("shapes.ba", SHAPES);
        file("square.ba", "def shapes = import(\"shapes.ba\")\n"
                + "export def Square = class () implements shapes.Shape ( def area = () -> ( 4 ) )");
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        Assertions.assertEquals(4, run(engine, "import(\"square.ba\").Square().area()"));
        file("shapes.ba", SHAPES.replace("def area : () : double", "def area : () : double\n    def perimeter : () : double"));
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run(engine, "import(\"square.ba\").Square().area()"));
        Assertions.assertTrue(e.getMessage().contains("Square does not implement Shape.perimeter"), e.getMessage());
    }

    @Test
    public void staticImportsWorkInBothModesAndWithTypes() throws Exception {
        file("shapes.ba", "export def Shape = interface ( def area : () : double ; def twice = () : double -> ( 2 * this.area() ) )");
        for (String mode : new String[]{"interpret", "compile"}) {
            ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
            engine.put(BabbleScriptEngine.MODE, mode);
            engine.put(BabbleScriptEngine.TYPECHECK, "error");
            Object result = run(engine, "def shapes = import(\"shapes.ba\")\n"
                    + "def Sq = class (def s : double) implements shapes.Shape ( def area = () : double -> ( this.s * this.s ) )\n"
                    + "def t : double = Sq(1.5).twice()\nt");
            Assertions.assertEquals(4.5, result, mode);
        }
        Assertions.assertEquals("", errors.toString());
    }

    /**
     * The selection cache reads the fields of another file's compiled class with private
     * access to it (docs/design/modules.md §8.3) : Babble's private members stay private.
     */
    @Test
    public void privateMembersOfImportedClassesStayPrivateOnceCached() throws Exception {
        file("lib.ba", "export def P = class () ( def open = 1 ; private def secret = 2 )");
        for (String mode : new String[]{"interpret", "compile"}) {
            ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
            engine.put(BabbleScriptEngine.MODE, mode);
            String script = "def P = import(\"lib.ba\").P\n"
                    + "def read = (o) -> ( o.open )\ndef peek = (o) -> ( o.secret )\n"
                    + "def i = 0 ; def s = 0 ; while i < 10000 then ( s = s + read(P()) ; i = i + 1 )\n";
            Assertions.assertEquals(10000, run(engine, script + "s"), mode);
            ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run(engine, script + "peek(P())"), mode);
            Assertions.assertTrue(e.getMessage().contains("secret is private"), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- types across modules (M3)

    /** The type diagnostics of a script, in warn mode, without running it : "line:column message", file names dropped. */
    private java.util.List<String> typeWarnings(String script) throws Exception {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.TYPECHECK, "warn");
        ScriptContext context = new SimpleScriptContext();
        context.setAttribute(ScriptEngine.FILENAME, directory.resolve("main.ba").toString(), ScriptContext.ENGINE_SCOPE);
        StringWriter warnings = new StringWriter();
        context.setErrorWriter(warnings);
        engine.setContext(context);
        ((javax.script.Compilable) engine).compile(script);
        java.util.List<String> result = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Warning : (.*) in (\\S+) at line number (\\d+)(?:, column (\\d+))?").matcher(warnings.toString());
        while (m.find()) {
            String file = java.nio.file.Path.of(m.group(2)).getFileName().toString();
            result.add(file + ":" + m.group(3) + ":" + m.group(4) + " " + m.group(1));
        }
        return result;
    }

    private static final String LIB = "export def half = (n : int) : double -> ( n / 2 )\n"
            + "export def Circle = class (def r : int) ( def area = () : double -> ( 3.14 * this.r * this.r ) )\n"
            + "export def Square = class (def side : int) ()\n"
            + "export def count = 1\n"
            + "export package geo ( def Point = value class (x : int, y : int) () )\n";

    @Test
    public void exportsHaveTheirTypes() throws Exception {
        file("lib.ba", LIB);
        Assertions.assertEquals(java.util.List.of(
                "main.ba:2:10 Type error : expected int, got string",
                "main.ba:3:18 Type error : expected string, got double",
                "main.ba:4:18 Type error : expected string, got int"),
                typeWarnings("def lib = import(\"lib.ba\")\n"
                        + "lib.half(\"x\")\n"
                        + "def h : string = lib.half(2)\n"
                        + "def c : string = lib.count"));
    }

    @Test
    public void importedClassesAreTyped() throws Exception {
        file("lib.ba", LIB);
        Assertions.assertEquals(java.util.List.of(
                "main.ba:3:8 Type error : expected int, got string",
                "main.ba:4:1 Missing parameter : r",
                "main.ba:5:1 No such member : nope",
                "main.ba:6:18 Type error : expected string, got double"),
                typeWarnings("def lib = import(\"lib.ba\")\n"
                        + "def Circle = lib.Circle\n"
                        + "Circle(\"wide\")\n"
                        + "lib.Circle()\n"
                        + "Circle(1).nope\n"
                        + "def a : string = Circle(1).area()"));
    }

    @Test
    public void importedClassesAreTypes() throws Exception {
        file("lib.ba", LIB);
        Assertions.assertEquals(java.util.List.of(
                "main.ba:3:22 Type error : expected Circle, got Square",
                "main.ba:4:18 Type error : expected Circle, got int",
                "main.ba:5:25 Type error : expected Point, got Circle",
                "main.ba:6:9 Unknown type : lib.Nope",
                "main.ba:7:9 Unknown type : lib.count"),
                typeWarnings("def lib = import(\"lib.ba\")\n"
                        + "def Circle = lib.Circle\n"
                        + "def a : lib.Circle = lib.Square(1)\n"
                        + "def b : Circle = 1\n"
                        + "def p : lib.geo.Point = Circle(1)\n"
                        + "def x : lib.Nope = 1\n"
                        + "def y : lib.count = 1\n"
                        + "def ok : lib.Circle = Circle(2) ; def pt : lib.geo.Point = lib.geo.Point(1, 2)"));
    }

    @Test
    public void importedInterfacesAreTypes() throws Exception {
        file("shapes.ba", SHAPES);
        Assertions.assertEquals(java.util.List.of(
                "main.ba:5:9 Type error : expected Shape, got Other",
                "main.ba:6:18 Type error : expected string, got double"),
                typeWarnings("def shapes = import(\"shapes.ba\")\n"
                        + "def Sq = class () implements shapes.Shape ( def area = () -> ( 1.0 ) )\n"
                        + "def Other = class () ()\n"
                        + "def measure = (s : shapes.Shape) : double -> ( s.area() )\n"
                        + "measure(Other())\n"
                        + "def d : string = measure(Sq())\n"
                        + "Sq().describe()"));
    }

    @Test
    public void typesFollowReExports() throws Exception {
        file("lib.ba", LIB);
        file("all.ba", "export def lib = import(\"lib.ba\")\nexport def Circle = import(\"lib.ba\").Circle");
        Assertions.assertEquals(java.util.List.of(
                "main.ba:2:12 Type error : expected int, got string",
                "main.ba:3:22 Type error : expected Circle, got int"),
                typeWarnings("def all = import(\"all.ba\")\n"
                        + "all.Circle(\"x\")\n"
                        + "def c : all.Circle = 1\n"
                        + "def d : all.lib.Circle = all.Circle(1)"));
    }

    @Test
    public void filesInACycleAreDynamicToEachOther() throws Exception {
        file("a.ba", "def b = import(\"b.ba\")\nexport def f = (n : int) -> ( n )\nexport def g = () -> ( b.h(\"x\") )");
        file("b.ba", "def a = import(\"a.ba\")\nexport def h = (n : int) -> ( a.f(\"y\") )");
        // b is checked while a is still being analysed : a is dynamic to it. a sees b's types.
        Assertions.assertEquals(java.util.List.of("a.ba:3:28 Type error : expected int, got string"),
                typeWarnings("import(\"a.ba\")"));
    }

    @Test
    public void errorModeChecksImportedTypesAtRuntime() throws Exception {
        file("lib.ba", LIB);
        for (String mode : new String[]{"interpret", "compile"}) {
            ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
            engine.put(BabbleScriptEngine.MODE, mode);
            engine.put(BabbleScriptEngine.TYPECHECK, "error");
            String script = "def lib = import(\"lib.ba\")\ndef id = (x) -> ( x )\n";
            Assertions.assertEquals(2, run(engine, script + "def c : lib.Circle = id(lib.Circle(2))\nc.r"), mode);
            ScriptException e = Assertions.assertThrows(ScriptException.class,
                    () -> run(engine, script + "def c : lib.Circle = id(lib.Square(2))"), mode);
            Assertions.assertTrue(e.getMessage().contains("Type error : expected Circle, got Square"), e.getMessage());
        }
    }

    /** A known limit (docs/design/modules.md §8.2), with a message that says what likely happened. */
    @Test
    public void aModuleChangedAfterCompilationIsNamedInTypeErrors() throws Exception {
        file("lib.ba", "export def C = class () ()");
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.TYPECHECK, "error");
        engine.put(ScriptEngine.FILENAME, directory.resolve("main.ba").toString());
        javax.script.CompiledScript script = ((javax.script.Compilable) engine)
                .compile("def lib = import(\"lib.ba\")\ndef id = (x) -> ( x )\ndef c : lib.C = id(lib.C())\n\"ok\"");
        Assertions.assertEquals("ok", script.eval());
        file("lib.ba", "export def C = class () ( def x = 1 )");
        ScriptException e = Assertions.assertThrows(ScriptException.class, script::eval);
        Assertions.assertTrue(e.getMessage().contains("expected C, got C, from another class expression of that name"), e.getMessage());
    }

    @Test
    public void javaPackagesAreStillImported() throws Exception {
        Assertions.assertEquals(0, run("def util = import(\"java\").util ; util.ArrayList().size()"));
    }
}
