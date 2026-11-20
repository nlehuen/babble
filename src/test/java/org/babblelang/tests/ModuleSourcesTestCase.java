package org.babblelang.tests;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.ModuleId;
import org.babblelang.engine.ModuleSource;
import org.babblelang.engine.ModuleText;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Module sources (docs/design/bip006_module-sources-and-aot.md §4) : where imports read modules from,
 * when it is not the file system. The source here keeps modules in memory, by "/"-separated
 * path, and records what the engine asks it.
 */
public class ModuleSourcesTestCase {
    /** Modules in memory : keys are normalised absolute paths, display names "mem:" + key. */
    static final class MemorySource implements ModuleSource {
        final Map<String, String> texts = new HashMap<>();
        final Map<String, Integer> versions = new HashMap<>();
        final List<String> resolved = new ArrayList<>();
        int reads;

        MemorySource put(String path, String text) {
            texts.put(path, text);
            versions.merge(path, 1, Integer::sum);
            return this;
        }

        @Override
        public ModuleId resolve(String name, ModuleId importer) throws IOException {
            if (name.equals("broken.ba")) {
                throw new IOException("the database is down");
            }
            String directory = importer == null ? "/" : importer.key().substring(0, importer.key().lastIndexOf('/') + 1);
            String path = normalise(name.startsWith("/") ? name : directory + name);
            resolved.add(name + " from " + (importer == null ? "nowhere" : importer.key()) + " -> " + path);
            return texts.containsKey(path) ? new ModuleId(path, "mem:" + path) : null;
        }

        @Override
        public ModuleId identify(String sourceName) {
            return sourceName.startsWith("/") ? new ModuleId(sourceName, "mem:" + sourceName) : null;
        }

        @Override
        public ModuleText read(ModuleId module) throws IOException {
            reads++;
            return new ModuleText(texts.get(module.key()), String.valueOf(versions.get(module.key())));
        }

        @Override
        public String version(ModuleId module) {
            return String.valueOf(versions.get(module.key()));
        }

        private static String normalise(String path) {
            Deque<String> parts = new ArrayDeque<>();
            for (String part : path.split("/")) {
                if (part.equals("..")) {
                    parts.pollLast();
                } else if (!part.isEmpty() && !part.equals(".")) {
                    parts.addLast(part);
                }
            }
            return "/" + String.join("/", parts);
        }
    }

    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();

    private ScriptEngine engine(MemorySource source) {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODULE_SOURCE, source);
        return engine;
    }

    private Object run(ScriptEngine engine, String script, String fileName) throws ScriptException {
        ScriptContext context = new SimpleScriptContext();
        context.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);
        if (fileName != null) {
            context.setAttribute(ScriptEngine.FILENAME, fileName, ScriptContext.ENGINE_SCOPE);
        }
        context.setWriter(output);
        context.setErrorWriter(errors);
        return engine.eval(script, context);
    }

    private static final String SHAPES = "export def Shape = interface ( def area : fn () : double ; def describe = fn () -> ( \"area \" + this.area() ) )";

    @Test
    public void importsReadModulesFromTheSource() throws Exception {
        MemorySource source = new MemorySource()
                .put("/app/lib/shapes.ba", SHAPES)
                .put("/app/lib/square.ba", "def shapes = import(\"shapes.ba\")\n"
                        + "export def Square = class (def side : int) implements shapes.Shape ( def area = fn () : double -> ( this.side * this.side ) )");
        Object result = run(engine(source), "import(\"lib/square.ba\").Square(3).describe()", "/app/main.ba");
        Assertions.assertEquals("area 9.0", result);
        // Names are resolved by the source, from the importing module : the main script is
        // the module the source identifies its file name as. Each import is resolved when
        // the importing file is analysed (static), then when it runs.
        Assertions.assertEquals(List.of(
                "lib/square.ba from /app/main.ba -> /app/lib/square.ba",
                "shapes.ba from /app/lib/square.ba -> /app/lib/shapes.ba",
                "lib/square.ba from /app/main.ba -> /app/lib/square.ba",
                "shapes.ba from /app/lib/square.ba -> /app/lib/shapes.ba"), source.resolved);
    }

    @Test
    public void aScriptTheSourceDoesNotKnowImportsFromNowhere() throws Exception {
        MemorySource source = new MemorySource().put("/lib.ba", "export def answer = 42");
        Assertions.assertEquals(42, run(engine(source), "import(\"lib.ba\").answer", null));
        Assertions.assertEquals("lib.ba from nowhere -> /lib.ba", source.resolved.get(0));
    }

    @Test
    public void anImportInAFunctionIsResolvedFromItsModule() throws Exception {
        MemorySource source = new MemorySource()
                .put("/lib/registry.ba", "export def values : java.util.List of int = java.util.ArrayList()")
                .put("/lib/data.ba", "import(\"registry.ba\").values.add(7)")
                .put("/lib/loader.ba", "def registry = import(\"registry.ba\")\nexport def load = fn io (name : string) : int -> ( import(name) ; registry.values.get(0) as int )");
        // A dynamic import : resolved when it runs, from the module the function is in.
        Assertions.assertEquals(7, run(engine(source), "import(\"lib/loader.ba\").load(\"data.ba\")", "/main.ba"));
        Assertions.assertTrue(source.resolved.contains("data.ba from /lib/loader.ba -> /lib/data.ba"), source.resolved.toString());
    }

    @Test
    public void messagesUseDisplayNames() {
        MemorySource source = new MemorySource().put("/lib.ba", "export def x = 1\nexport def y = 1 + null");
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run(engine(source), "import(\"lib.ba\")", "/main.ba"));
        Assertions.assertTrue(e.getMessage().contains("mem:/lib.ba at line number 2"), e.getMessage());
        Assertions.assertTrue(e.getMessage().endsWith("in /main.ba at line number 1"), e.getMessage());
    }

    @Test
    public void aModuleTheSourceDoesNotHaveIsNoSuchModule() {
        MemorySource source = new MemorySource();
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run(engine(source), "println(\"started\")\nimport(\"nope.ba\")", "/main.ba"));
        Assertions.assertTrue(e.getMessage().contains("Cannot import nope.ba : no such module"), e.getMessage());
        // A static import : found missing before anything runs.
        Assertions.assertEquals("", output.toString());

        e = Assertions.assertThrows(ScriptException.class, () -> run(engine(source), "def n = \"nope\"\nimport(n + \".ba\")", "/main.ba"));
        Assertions.assertTrue(e.getMessage().contains("Cannot import nope.ba : no such module"), e.getMessage());
    }

    @Test
    public void aSourceFailingSaysWhy() {
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run(engine(new MemorySource()), "import(\"broken.ba\")", "/main.ba"));
        Assertions.assertTrue(e.getMessage().contains("Cannot import broken.ba : the database is down"), e.getMessage());
    }

    @Test
    public void versionsTellWhatIsStale() throws Exception {
        MemorySource source = new MemorySource().put("/lib.ba", "export def answer = 1");
        ScriptEngine engine = engine(source);
        Assertions.assertEquals(1, run(engine, "import(\"lib.ba\").answer", "/main.ba"));
        Assertions.assertEquals(1, run(engine, "import(\"lib.ba\").answer", "/main.ba"));
        Assertions.assertEquals(1, source.reads, "compiled once, while its version is the same");

        // A new version is compiled again.
        source.put("/lib.ba", "export def answer = 2");
        Assertions.assertEquals(2, run(engine, "import(\"lib.ba\").answer", "/main.ba"));
        Assertions.assertEquals(2, source.reads);

        // The version is what tells : a text changed under the same version is not seen.
        source.texts.put("/lib.ba", "export def answer = 3");
        Assertions.assertEquals(2, run(engine, "import(\"lib.ba\").answer", "/main.ba"));
    }

    @Test
    public void aChangedDependencyRechecksItsImporters() throws Exception {
        MemorySource source = new MemorySource()
                .put("/shapes.ba", SHAPES)
                .put("/square.ba", "def shapes = import(\"shapes.ba\")\n"
                        + "export def Square = class () implements shapes.Shape ( def area = fn () : double -> ( 4 ) )");
        ScriptEngine engine = engine(source);
        Assertions.assertEquals(4.0, run(engine, "import(\"square.ba\").Square().area()", "/main.ba"));
        source.put("/shapes.ba", SHAPES.replace("def area : fn () : double", "def area : fn () : double ; def perimeter : fn () : double"));
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run(engine, "import(\"square.ba\").Square().area()", "/main.ba"));
        Assertions.assertTrue(e.getMessage().contains("Square does not implement Shape.perimeter"), e.getMessage());
    }

    @Test
    public void twoNamesForOneKeyAreOneModule() throws Exception {
        MemorySource source = new MemorySource().put("/lib/counter.ba", "println(\"loading\")\ndef n = 0\nexport def next = fn io () -> ( n = n + 1 )");
        Object result = run(engine(source), "import(\"lib/counter.ba\").next()\nimport(\"lib/../lib/./counter.ba\").next()", "/main.ba");
        Assertions.assertEquals(2, result);
        Assertions.assertEquals("loading" + System.lineSeparator(), output.toString());
    }

    @Test
    public void aMapResolvesPathsLikeDirectories() throws Exception {
        org.babblelang.engine.MapModuleSource map = new org.babblelang.engine.MapModuleSource()
                .put("app/lib/a.ba", "export def x = 1")
                .put("./top.ba", "export def y = 2");
        ModuleId a = map.resolve("lib/a.ba", map.identify("app/main.ba"));
        Assertions.assertEquals(new ModuleId("app/lib/a.ba", "app/lib/a.ba"), a);
        Assertions.assertEquals(a, map.resolve("../app/./lib/a.ba", map.identify("app/main.ba")));
        Assertions.assertEquals(new ModuleId("top.ba", "top.ba"), map.resolve("/top.ba", a));
        Assertions.assertEquals(new ModuleId("top.ba", "top.ba"), map.resolve("../../top.ba", a));
        // Nothing above the root, and nothing the map doesn't hold.
        Assertions.assertNull(map.resolve("../../../top.ba", a));
        Assertions.assertNull(map.resolve("b.ba", a));
        Assertions.assertEquals("export def x = 1", map.read(a).text());
        Assertions.assertNotEquals(map.read(a).version(), map.put("app/lib/a.ba", "export def x = 3").read(a).version());
        Assertions.assertThrows(IllegalArgumentException.class, () -> map.put("..", "x"));
    }

    @Test
    public void theAttributeMustBeAModuleSource() {
        ScriptEngine engine = BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.put(BabbleScriptEngine.MODULE_SOURCE, "files please");
        ScriptException e = Assertions.assertThrows(ScriptException.class, () -> run(engine, "1", null));
        Assertions.assertTrue(e.getMessage().contains("babble.moduleSource is not a ModuleSource"), e.getMessage());
    }
}
