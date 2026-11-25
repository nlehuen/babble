package org.babblelang.compiler;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.BabbleScriptEngineFactory;
import org.babblelang.engine.FileModuleSource;
import org.babblelang.runtime.Launcher;
import org.babblelang.runtime.ProgramIndex;

import javax.script.ScriptContext;
import javax.script.ScriptException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * {@code babblec} : compiles a Babble program ahead of time into a jar that runs on a plain
 * JRE (docs/design/bip006_module-sources-and-aot.md §5) :
 * <pre>
 * java -jar babble.jar -compile main.ba -o app.jar [-root dir]
 * java -jar app.jar [arguments...]
 * </pre>
 * The jar holds the program's modules (its main script and every module it imports
 * statically, under {@code root}, by default the main script's directory), their
 * {@link ProgramIndex}, the runtime, and a manifest naming {@link Launcher}. Nothing in it
 * parses, analyses or compiles : no ANTLR, no ASM. The same program compiles to the same jar.
 */
public final class Babblec {
    /** Every entry's time : what makes two compilations of one program the same bytes. */
    private static final long ENTRY_TIME = 946_684_800_000L;

    private Babblec() {
    }

    /**
     * @param context where the analysis's warnings and the type checker's diagnostics go
     * @return the index written
     */
    public static ProgramIndex compile(Path main, Path root, Path jar, ScriptContext context) throws ScriptException, IOException {
        return compile(main, root, jar, context, null);
    }

    /**
     * @param context  where the analysis's warnings and the type checker's diagnostics go
     * @param optLevel explicit optimization level ({@code 0}, {@code 1}, or {@code 2}), or null to use the engine/system default
     * @return the index written
     */
    public static ProgramIndex compile(Path main, Path root, Path jar, ScriptContext context, Integer optLevel) throws ScriptException, IOException {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.setContext(context);
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.AOT_CROSS_MODULE, Boolean.TRUE);
        engine.put(BabbleCompiler.AOT_INLINE_CACHE, new LinkedHashMap<String, org.babblelang.ir.hir.HirFunction>());
        if (optLevel != null) {
            engine.put(BabbleCompiler.OPT_LEVEL, optLevel);
        }
        FileModuleSource source = FileModuleSource.rooted(root);
        List<BabbleScriptEngine.ProgramModule> modules = engine.compileProgram(source, main.toAbsolutePath().normalize().toString());

        Map<String, ProgramIndex.Entry> entries = new LinkedHashMap<>();
        Map<String, byte[]> files = new TreeMap<>();
        for (BabbleScriptEngine.ProgramModule module : modules) {
            String className = module.script().getScriptClass().getName();
            entries.put(module.id().key(), new ProgramIndex.Entry(module.id().key(), module.id().displayName(), className, module.exports()));
            for (Map.Entry<String, byte[]> file : module.script().getClasses().entrySet()) {
                files.put(file.getKey() + ".class", file.getValue());
            }
        }
        ProgramIndex index = new ProgramIndex(modules.get(0).id().key(), relativeRoot(root, jar), entries);
        files.putAll(runtimeClasses());
        write(jar, index, files);
        return index;
    }

    /**
     * Compiles a Babble program ahead of time to a WebAssembly 3.0 {@code .wasm} module
     * (plus sibling {@code .mjs} loader on {@code wasm-js}) ({@code BIP025-I1}).
     *
     * @return the number of linked modules compiled into the output binary
     */
    public static int compileWasm(
            Path main,
            Path root,
            Path outWasm,
            org.babblelang.analysis.CompilationTarget target,
            ScriptContext context,
            Integer optLevel,
            boolean wasmOpt
    ) throws ScriptException, IOException {
        BabbleScriptEngine engine = (BabbleScriptEngine) BabbleScriptEngineFactory.INSTANCE.getScriptEngine();
        engine.setContext(context);
        engine.put(BabbleScriptEngine.MODE, "compile");
        engine.put(BabbleCompiler.AOT_CROSS_MODULE, Boolean.TRUE);
        engine.put(BabbleCompiler.AOT_INLINE_CACHE, new LinkedHashMap<String, org.babblelang.ir.hir.HirFunction>());
        if (optLevel != null) {
            engine.put(BabbleCompiler.OPT_LEVEL, optLevel);
        }
        FileModuleSource source = FileModuleSource.rooted(root);
        List<BabbleScriptEngine.HirProgramModule> hirMods = engine.compileProgramToHir(
                source, main.toAbsolutePath().normalize().toString(), target);

        List<org.babblelang.wasm.WasmProgramLinker.ModuleInput> inputs = hirMods.stream()
                .map(m -> new org.babblelang.wasm.WasmProgramLinker.ModuleInput(
                        m.id().key(), m.id().displayName(), m.hir(), m.staticImportKeys()))
                .toList();
        org.babblelang.wasm.WasmCompiler.Options defaults = org.babblelang.wasm.WasmCompiler.Options.defaults(target);
        org.babblelang.wasm.WasmCompiler.Options opts = new org.babblelang.wasm.WasmCompiler.Options(
                target, defaults.verify(), defaults.debugNames(), defaults.dumpWat(), wasmOpt);
        org.babblelang.wasm.WasmCompiler.CompileResult result = org.babblelang.wasm.WasmCompiler.compile(
                inputs, opts, outWasm.getFileName().toString());
        if (!result.ok()) {
            Writer err = context.getErrorWriter();
            for (org.babblelang.wasm.WasmPortabilityChecker.Diagnostic d : result.portabilityErrors()) {
                err.write(d.format() + "\n");
            }
            err.flush();
            org.babblelang.wasm.WasmPortabilityChecker.Diagnostic first = result.portabilityErrors().get(0);
            throw new org.babblelang.engine.TypeCheckException(first.format(), main.getFileName().toString(), first.line(), 1);
        }
        org.babblelang.wasm.WasmCompiler.writeOutputs(result, outWasm, opts);
        return inputs.size();
    }

    /**
     * The root seen from the jar's directory, for the modules the jar doesn't hold, compiled
     * when Babble is on the class path : "." when the jar is next to the main script, as by
     * default. None when there is no relative path between them.
     */
    private static String relativeRoot(Path root, Path jar) throws IOException {
        Path directory = jar.toAbsolutePath().normalize().getParent();
        if (directory == null) {
            return null;
        }
        Files.createDirectories(directory);
        try {
            String relative = directory.toRealPath().relativize(root.toRealPath()).toString()
                    .replace(directory.getFileSystem().getSeparator(), "/");
            return relative.isEmpty() ? "." : relative;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void write(Path jar, ProgramIndex index, Map<String, byte[]> files) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, Launcher.class.getName());
        manifest.getMainAttributes().put(new Attributes.Name("Created-By"), "babblec");
        if (jar.toAbsolutePath().getParent() != null) {
            Files.createDirectories(jar.toAbsolutePath().getParent());
        }
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            JarEntry manifestEntry = entry(JarFile.MANIFEST_NAME);
            out.putNextEntry(manifestEntry);
            manifest.write(out);
            out.closeEntry();
            out.putNextEntry(entry(ProgramIndex.RESOURCE));
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            index.write(writer);
            writer.flush();
            out.closeEntry();
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                out.putNextEntry(entry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static JarEntry entry(String name) {
        JarEntry entry = new JarEntry(name);
        entry.setTime(ENTRY_TIME);
        return entry;
    }

    /** Whether a class is part of what a compiled program needs to run (§3, RuntimeBoundaryTestCase). */
    static boolean isRuntimeClass(String internalName) {
        return internalName.startsWith("org/babblelang/runtime/") || internalName.startsWith("org/babblelang/engine/impl/natives/");
    }

    /** The runtime's class files, from wherever this Babble's classes are : a jar, or a directory. */
    static Map<String, byte[]> runtimeClasses() throws IOException {
        Path location;
        try {
            location = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IOException("Cannot find the Babble runtime's classes", e);
        }
        Map<String, byte[]> classes = new TreeMap<>();
        if (Files.isDirectory(location)) {
            try (Stream<Path> paths = Files.walk(location)) {
                for (Path path : (Iterable<Path>) paths::iterator) {
                    String name = location.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                    if (name.endsWith(".class") && isRuntimeClass(name)) {
                        classes.put(name, Files.readAllBytes(path));
                    }
                }
            }
        } else {
            try (JarFile babble = new JarFile(location.toFile())) {
                Enumeration<JarEntry> entries = babble.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class") && isRuntimeClass(entry.getName())) {
                        classes.put(entry.getName(), babble.getInputStream(entry).readAllBytes());
                    }
                }
            }
        }
        if (!classes.containsKey(Launcher.class.getName().replace('.', '/') + ".class")) {
            throw new IOException("The Babble runtime's classes are not in " + location);
        }
        return classes;
    }
}
