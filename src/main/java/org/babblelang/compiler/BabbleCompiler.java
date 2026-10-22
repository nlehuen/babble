package org.babblelang.compiler;

import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.engine.BabbleException;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.engine.impl.Namespace;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ScriptRuntime;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compiles a parsed and analysed Babble file to a JVM class, and loads it.
 * <p>
 * Debugging switches, as system properties :
 * <ul>
 * <li>{@code babble.dumpClasses=<dir>} writes each generated class file under {@code dir},
 * for {@code javap -c -p}.</li>
 * <li>{@code babble.trace=true} prints a listing of each generated class to stderr.</li>
 * <li>{@code babble.verify=true} checks each generated class with ASM's
 * CheckClassAdapter, and fails the compilation if it finds a problem.</li>
 * </ul>
 * The last two need {@code asm-util} on the class path.
 */
public final class BabbleCompiler {
    public static final String DUMP_CLASSES = "babble.dumpClasses";
    public static final String TRACE = "babble.trace";
    public static final String VERIFY = "babble.verify";

    private static final String PACKAGE = "org/babblelang/generated/";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private BabbleCompiler() {
    }

    public static CompiledBabbleScript compile(BabbleScriptEngine engine, BabbleParser.FileContext file, ScopeAnalysis scopes, String sourceName) {
        String internalName = PACKAGE + "Script$" + COUNTER.incrementAndGet();
        ClassLoader runtimeLoader = BabbleCompiler.class.getClassLoader();
        Map<String, byte[]> classes = new CodeGenerator(internalName, sourceName, scopes, runtimeLoader).generate(file);

        String dump = System.getProperty(DUMP_CLASSES);
        Map<String, byte[]> byBinaryName = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> generated : classes.entrySet()) {
            if (dump != null) {
                dump(Path.of(dump), generated.getKey(), generated.getValue());
            }
            if (Boolean.getBoolean(TRACE)) {
                Debugging.trace(generated.getValue());
            }
            byBinaryName.put(generated.getKey().replace('/', '.'), generated.getValue());
        }

        ScriptClassLoader loader = new ScriptClassLoader(runtimeLoader, byBinaryName);
        Class<?> scriptClass;
        try {
            scriptClass = loader.loadClass(internalName.replace('/', '.'));
        } catch (ClassNotFoundException e) {
            throw new BabbleException("Cannot load the compiled script", e);
        }
        if (Boolean.getBoolean(VERIFY)) {
            // After loading : the verifier resolves the compiled classes through their loader.
            for (byte[] bytes : classes.values()) {
                Debugging.verify(bytes, loader);
            }
        }
        MethodHandle run;
        try {
            run = MethodHandles.privateLookupIn(scriptClass, MethodHandles.lookup())
                    .findStatic(scriptClass, "run", MethodType.methodType(Object.class, ScriptRuntime.class, Namespace.class));
        } catch (ReflectiveOperationException e) {
            throw new BabbleException("Cannot load the compiled script", e);
        }
        return new CompiledBabbleScript(engine, scriptClass, classes, run, sourceName, file.getStart().getLine());
    }

    private static void dump(Path directory, String internalName, byte[] bytes) {
        Path path = directory.resolve(internalName + ".class");
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new BabbleException("Cannot dump " + path, e);
        }
    }
}
