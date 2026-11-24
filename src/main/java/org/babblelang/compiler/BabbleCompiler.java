package org.babblelang.compiler;

import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.runtime.BabbleException;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.runtime.Namespace;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ScriptRuntime;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    public static final String OPT_LEVEL = "babble.opt";
    public static final String DUMP_IR = "babble.dumpIr";
    /** Per-engine {@link org.babblelang.ir.IrObserver} binding key for parallel-safe IR capture (BIP029-I1). */
    public static final String IR_OBSERVER = "babble.irObserver";
    /** Internal engine attribute enabling cross-module small-export inlining in {@code babblec} AOT only (BIP014-D2). */
    static final String AOT_CROSS_MODULE = "babble.aotCrossModule";
    /** Internal engine attribute holding small exported HIR functions compiled so far during a {@code babblec} run. */
    static final String AOT_INLINE_CACHE = "babble.aotInlineCache";
    /** Default optimization level in compile mode (BIP014-D1). */
    public static final int DEFAULT_OPT_LEVEL = 1;

    private static final String PACKAGE = "org/babblelang/generated/";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private BabbleCompiler() {
    }

    /** Resolves the active optimization level ({@code 0}, {@code 1}, or {@code 2}). */
    public static int resolveOptLevel(BabbleScriptEngine engine) {
        Object value = engine != null ? engine.get(OPT_LEVEL) : null;
        if (value == null) {
            value = System.getProperty(OPT_LEVEL);
        }
        if (value == null) {
            return DEFAULT_OPT_LEVEL;
        }
        int parsed;
        if (value instanceof Number n) {
            parsed = n.intValue();
        } else {
            String text = value.toString().trim();
            if (text.isEmpty()) {
                return DEFAULT_OPT_LEVEL;
            }
            if (text.startsWith("-O") || text.startsWith("O")) {
                text = text.substring(text.indexOf('O') + 1);
            }
            try {
                parsed = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid optimization level : " + value);
            }
        }
        if (parsed < 0 || parsed > 2) {
            throw new IllegalArgumentException("Invalid optimization level : " + value);
        }
        return parsed;
    }

    /** Resolves the IR dump target ({@code "hir"}, {@code "all"}, {@code "true"}, or null). */
    public static String resolveDumpIr(BabbleScriptEngine engine) {
        Object value = engine != null ? engine.get(DUMP_IR) : null;
        if (value == null) {
            value = System.getProperty(DUMP_IR);
        }
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() || "false".equalsIgnoreCase(text) ? null : text;
    }

    public static CompiledBabbleScript compile(BabbleScriptEngine engine, BabbleParser.FileContext file, ScopeAnalysis scopes, String sourceName) {
        return compile(engine, file, scopes, sourceName, null);
    }

    /**
     * @param moduleKey the key of the module compiled, which names its classes, or null for a
     *                  script that is not a module, whose classes get a name of their own
     */
    public static CompiledBabbleScript compile(BabbleScriptEngine engine, BabbleParser.FileContext file, ScopeAnalysis scopes, String sourceName,
                                               String moduleKey) {
        return compile(engine, file, scopes, sourceName, moduleKey, List.of(), Map.of(), Map.of());
    }

    public static CompiledBabbleScript compile(BabbleScriptEngine engine, BabbleParser.FileContext file, ScopeAnalysis scopes, String sourceName,
                                               String moduleKey, List<ClassLoader> dependencyLoaders, Map<String, String> knownInterfaceJvmNames) {
        return compile(engine, file, scopes, sourceName, moduleKey, dependencyLoaders, knownInterfaceJvmNames, Map.of());
    }

    public static CompiledBabbleScript compile(BabbleScriptEngine engine, BabbleParser.FileContext file, ScopeAnalysis scopes, String sourceName,
                                               String moduleKey, List<ClassLoader> dependencyLoaders, Map<String, String> knownInterfaceJvmNames,
                                               Map<String, String> knownClassJvmNames) {
        String internalName = moduleKey != null ? className(moduleKey) : PACKAGE + "Script$" + COUNTER.incrementAndGet();
        ClassLoader runtimeLoader = BabbleCompiler.class.getClassLoader();
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        List<ClassLoader> delegates = new ArrayList<>();
        if (tccl != null && tccl != runtimeLoader) {
            delegates.add(tccl);
        }
        if (dependencyLoaders != null) {
            for (ClassLoader dependencyLoader : dependencyLoaders) {
                if (dependencyLoader != null) {
                    delegates.add(dependencyLoader);
                }
            }
        }
        ClassLoader parentLoader = delegates.isEmpty() ? runtimeLoader : new CompositeClassLoader(runtimeLoader, delegates);
        org.babblelang.ir.hir.HirModule hir = org.babblelang.ir.hir.HirBuilder.build(
                internalName, sourceName, file, scopes, knownInterfaceJvmNames, knownClassJvmNames);
        int optLevel = resolveOptLevel(engine);
        boolean verify = Boolean.getBoolean(VERIFY);
        String dumpIr = resolveDumpIr(engine);
        org.babblelang.analysis.BuiltinAxioms activeAxioms = engine != null
                ? engine.builtinAxioms()
                : org.babblelang.analysis.BuiltinAxioms.standard();
        String dumpAxioms = System.getProperty("babble.dumpAxioms");
        if (dumpAxioms != null && !dumpAxioms.isBlank()) {
            org.babblelang.analysis.AxiomDumper.dumpTargets(dumpAxioms, activeAxioms,
                    new java.io.PrintWriter(System.err, true));
        }
        org.babblelang.ir.IrObserver observer = resolveIrObserver(engine, dumpIr, internalName, optLevel);
        boolean aotCrossModule = optLevel > 0 && engine != null && Boolean.TRUE.equals(engine.get(AOT_CROSS_MODULE));
        @SuppressWarnings("unchecked")
        Map<String, org.babblelang.ir.hir.HirFunction> aotInlineCache = aotCrossModule
                ? (Map<String, org.babblelang.ir.hir.HirFunction>) engine.get(AOT_INLINE_CACHE)
                : null;
        org.babblelang.opt.PassContext passCtx = new org.babblelang.opt.PassContext(
                optLevel, verify, dumpIr, aotCrossModule,
                aotInlineCache != null ? aotInlineCache : Map.of());
        org.babblelang.ir.hir.HirModule unoptimized = hir;
        hir = org.babblelang.analysis.BuiltinAxioms.withCurrent(activeAxioms,
                () -> org.babblelang.opt.PassManager.defaultFor(optLevel).optimize(
                        unoptimized, passCtx, observer, moduleKey));
        if (aotCrossModule && aotInlineCache != null && moduleKey != null) {
            aotInlineCache.putAll(org.babblelang.opt.hir.InlinePass.collectCrossModuleExports(hir));
        }
        HirEmitter emitter = new HirEmitter(hir, parentLoader);
        Map<String, byte[]> classes = emitter.generate();

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

        ScriptClassLoader loader = new ScriptClassLoader(parentLoader, byBinaryName);
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
        return new CompiledBabbleScript(engine, scriptClass, classes, run, sourceName, hir.startLine(), hir.exports(),
                hir.ownInterfaceJvmNames(), hir.ownClassJvmNames());
    }

    /**
     * The internal name of a module's script class, from its key : the same every time the
     * module is compiled (docs/design/bip006_module-sources-and-aot.md §5.2). {@code lib/vec3.ba} is
     * {@code babble/lib/vec3$ba} ; a scheme ({@code file:}, {@code classpath:}) is dropped, each
     * "." becomes "$", and a character a class name can't hold, "_".
     */
    public static String className(String moduleKey) {
        String path = moduleKey.replaceFirst("^[a-z]+:", "");
        StringBuilder name = new StringBuilder("babble");
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            name.append('/');
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c == '.') {
                    name.append('$');
                } else if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '$') {
                    name.append(c);
                } else {
                    name.append('_');
                }
            }
        }
        return name.toString();
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

    private static org.babblelang.ir.IrObserver resolveIrObserver(BabbleScriptEngine engine, String dumpIr,
                                                                  String internalName, int optLevel) {
        org.babblelang.ir.IrObserver engineObserver = null;
        if (engine != null && engine.get(IR_OBSERVER) instanceof org.babblelang.ir.IrObserver obs) {
            engineObserver = obs;
        }
        org.babblelang.ir.IrObserver cliObserver = org.babblelang.ir.CliIrDumper.forSpec(dumpIr, internalName, optLevel);
        return org.babblelang.ir.IrObserver.combine(engineObserver, cliObserver);
    }
}
