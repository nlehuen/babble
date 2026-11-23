package org.babblelang.runtime;

import org.babblelang.engine.impl.natives.java.JavaObject;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The main class of a program {@code babblec} compiled into a jar
 * (docs/design/bip006_module-sources-and-aot.md §5.3) : it runs the main module the jar's
 * {@link ProgramIndex} names, with the program's arguments in {@code args}, as
 * {@code java -jar babble.jar script.ba} would, but with nothing to parse, analyse or compile.
 * It exits with status 1 when the program fails, the message on standard error.
 * <p>
 * A module the jar doesn't hold, imported dynamically, is compiled from the program's root by
 * a {@link ModuleCompiler} when Babble is on the class path ({@code java -cp app.jar:babble.jar
 * org.babblelang.runtime.Launcher}) ; the root is the system property {@value #ROOT}, else the
 * one the index gives relative to the jar's directory, else the working directory.
 */
public final class Launcher {
    static final int FAILED = 1;

    /** The system property naming the program's root, where modules the jar doesn't hold are compiled from. */
    public static final String ROOT = "babble.root";

    private final ProgramIndex index;
    /** The directory of the jar (or class directory) the index is in, if known. */
    private final Path location;
    private final JarModules modules;
    private final Namespace implicits = Implicits.create();
    private final Writer out;
    private final Object args;

    Launcher(ProgramIndex index, Path location, Writer out, List<String> args) {
        this.index = index;
        this.location = location;
        this.modules = new JarModules();
        this.out = out;
        this.args = JavaObject.of(new ArrayList<>(args));
    }

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out);
        PrintWriter err = new PrintWriter(System.err, true);
        int status = launch(args, out, err);
        if (status != 0) {
            // Not on success : threads the program started may still be working.
            System.exit(status);
        }
    }

    /** Runs the program, and returns the exit status. */
    static int launch(String[] args, PrintWriter out, PrintWriter err) {
        URL resource = Launcher.class.getClassLoader().getResource(ProgramIndex.RESOURCE);
        if (resource == null) {
            err.println("Error : not a compiled Babble program, no " + ProgramIndex.RESOURCE);
            return FAILED;
        }
        ProgramIndex index;
        try (InputStream in = resource.openStream()) {
            index = ProgramIndex.read(in);
        } catch (java.io.IOException e) {
            err.println("Error : " + e.getMessage());
            return FAILED;
        }
        return new Launcher(index, locationOf(resource), out, Arrays.asList(args)).run(err);
    }

    /**
     * The directory holding the jar the index is in : {@code jar:file:/a/app.jar!/babble/modules.index}
     * gives {@code /a}. For a class directory, the directory holding it. Null if not a file.
     */
    static Path locationOf(URL resource) {
        try {
            if (resource.getProtocol().equals("jar")) {
                String path = resource.getPath();
                int separator = path.indexOf("!/");
                return separator < 0 ? null : Path.of(new URI(path.substring(0, separator))).getParent();
            }
            if (resource.getProtocol().equals("file")) {
                Path classes = Path.of(resource.toURI()).getParent().getParent();
                return classes.getParent();
            }
        } catch (Exception e) {
            // Somewhere else : no location.
        }
        return null;
    }

    /** Where modules the jar doesn't hold are compiled from. */
    Path root() {
        String property = System.getProperty(ROOT);
        if (property != null && !property.isEmpty()) {
            return Path.of(property).toAbsolutePath().normalize();
        }
        if (location != null && index.root() != null) {
            return location.resolve(index.root()).normalize();
        }
        return Path.of("").toAbsolutePath();
    }

    int run(PrintWriter err) {
        ProgramIndex.Entry main = index.modules().get(index.main());
        try {
            Namespace root = newRoot();
            runModule(loadClass(main), new ScriptRuntime(root, out, modules), root);
            return 0;
        } catch (StackOverflowError e) {
            flush();
            err.println("Error : Stack overflow in " + main.displayName());
        } catch (Throwable t) {
            flush();
            err.println("Error : " + message(t));
        } finally {
            flush();
        }
        return FAILED;
    }

    private void flush() {
        try {
            out.flush();
        } catch (java.io.IOException e) {
            // Nothing more can be done about output that cannot be written.
        }
    }

    /** A module's root namespace : the built-ins, the output, and {@code args}, as the engine gives a script's bindings. */
    private Namespace newRoot() {
        Namespace root = Implicits.withOutput(implicits, out).enter();
        root.define("args", false).set(args);
        return root;
    }

    private static Class<?> loadClass(ProgramIndex.Entry entry) throws ClassNotFoundException {
        return Class.forName(entry.className(), true, Launcher.class.getClassLoader());
    }

    private static void runModule(Class<?> script, ScriptRuntime runtime, Namespace root) throws Throwable {
        MethodHandle run = MethodHandles.privateLookupIn(script, MethodHandles.lookup())
                .findStatic(script, "run", MethodType.methodType(Object.class, ScriptRuntime.class, Namespace.class));
        Object ignored = (Object) run.invokeExact(runtime, root);
    }

    /**
     * What the engine would say : the message, and the program's innermost line, from the
     * stack trace's frames of the program's classes, whose source files are module names.
     */
    private String message(Throwable t) {
        String message;
        if (t instanceof BabbleException) {
            message = t.getMessage();
        } else if (t instanceof ThrownException te) {
            message = ArraySupport.format(te.value());
        } else if (t instanceof ArrayStoreException
                || t instanceof ArrayIndexOutOfBoundsException
                || t instanceof NegativeArraySizeException) {
            message = ArraySupport.formatThrowable(t);
        } else {
            message = ArraySupport.formatJavaException(t);
        }
        for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getLineNumber() >= 0 && isProgramClass(frame.getClassName())) {
                return message + " in " + frame.getFileName() + " at line number " + frame.getLineNumber();
            }
        }
        return message;
    }

    private boolean isProgramClass(String className) {
        for (ProgramIndex.Entry entry : index.modules().values()) {
            if (isClassOf(className, entry.className())) {
                return true;
            }
        }
        for (String compiled : modules.compiledClasses) {
            if (isClassOf(className, compiled)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClassOf(String className, String scriptClass) {
        return className.equals(scriptClass) || className.startsWith(scriptClass + "$");
    }

    /**
     * The program's modules : found in the index, by the path rules the program was compiled
     * with, and loaded once each. Its own static imports are all there ; a dynamic import of
     * a module it doesn't import statically isn't, and is compiled when a {@link ModuleCompiler}
     * is on the class path.
     */
    private final class JarModules implements Modules {
        private final Map<String, Module> loaded = new HashMap<>();
        private final Map<String, String> keysByName = new ConcurrentHashMap<>();
        /** The script classes of the modules compiled while running, for error lines. */
        final Set<String> compiledClasses = ConcurrentHashMap.newKeySet();
        private ModuleCompiler compiler;
        private boolean compilerLookedUp;

        JarModules() {
            for (ProgramIndex.Entry entry : index.modules().values()) {
                keysByName.put(entry.displayName(), entry.key());
            }
        }

        @Override
        public Module load(String name, String importer, ScriptRuntime rt) {
            String importerKey = importer != null ? keysByName.get(importer) : null;
            String key = LogicalPaths.resolve(name, importerKey);
            if (key == null) {
                throw new BabbleException("Cannot import " + name + " : no such module");
            }
            synchronized (loaded) {
                Module module = loaded.get(key);
                if (module != null) {
                    return module;
                }
                ProgramIndex.Entry entry = index.modules().get(key);
                Class<?> script;
                if (entry != null) {
                    try {
                        script = loadClass(entry);
                    } catch (ClassNotFoundException e) {
                        throw new BabbleException("Cannot import " + name + " : " + e);
                    }
                } else {
                    ModuleCompiler.Compiled compiled = compile(name, key);
                    entry = new ProgramIndex.Entry(compiled.key(), compiled.displayName(), compiled.script().getName(), compiled.exports());
                    script = compiled.script();
                    keysByName.put(entry.displayName(), entry.key());
                    compiledClasses.add(entry.className());
                }
                Namespace root = newRoot();
                module = new Module(entry.displayName(), root, entry.exports());
                loaded.put(key, module);
                try {
                    runModule(script, new ScriptRuntime(root, out, this), root);
                } catch (RuntimeException | Error e) {
                    loaded.remove(key);
                    throw e;
                } catch (Throwable t) {
                    loaded.remove(key);
                    throw new BabbleException("Cannot import " + name + " : " + t);
                }
                return module;
            }
        }

        private ModuleCompiler.Compiled compile(String name, String key) {
            if (!compilerLookedUp) {
                compilerLookedUp = true;
                compiler = ServiceLoader.load(ModuleCompiler.class, Launcher.class.getClassLoader()).findFirst().orElse(null);
            }
            if (compiler == null) {
                throw new BabbleException("Cannot import " + name + " : a compiled program only has the modules it imports statically");
            }
            try {
                return compiler.compile(root(), key);
            } catch (BabbleException e) {
                throw new BabbleException("Cannot import " + name + " : " + e.getMessage());
            }
        }
    }
}
