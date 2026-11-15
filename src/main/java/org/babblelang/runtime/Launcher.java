package org.babblelang.runtime;

import org.babblelang.engine.impl.natives.java.JavaObject;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The main class of a program {@code babblec} compiled into a jar
 * (docs/design/module-sources-and-aot.md §5.3) : it runs the main module the jar's
 * {@link ProgramIndex} names, with the program's arguments in {@code args}, as
 * {@code java -jar babble.jar script.ba} would, but with nothing to parse, analyse or compile.
 * It exits with status 1 when the program fails, the message on standard error.
 */
public final class Launcher {
    static final int FAILED = 1;

    private final ProgramIndex index;
    private final Namespace implicits = Implicits.create();
    private final Writer out;
    private final Object args;

    Launcher(ProgramIndex index, Writer out, List<String> args) {
        this.index = index;
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
        ProgramIndex index;
        try (InputStream in = Launcher.class.getClassLoader().getResourceAsStream(ProgramIndex.RESOURCE)) {
            if (in == null) {
                err.println("Error : not a compiled Babble program, no " + ProgramIndex.RESOURCE);
                return FAILED;
            }
            index = ProgramIndex.read(in);
        } catch (java.io.IOException e) {
            err.println("Error : " + e.getMessage());
            return FAILED;
        }
        return new Launcher(index, out, Arrays.asList(args)).run(err);
    }

    int run(PrintWriter err) {
        JarModules modules = new JarModules();
        ProgramIndex.Entry main = index.modules().get(index.main());
        try {
            Namespace root = newRoot();
            runModule(main, new ScriptRuntime(root, out, modules), root);
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
        Namespace root = Implicits.withOutput(implicits, out).enter(null);
        root.define("args", false).set(args);
        return root;
    }

    private static void runModule(ProgramIndex.Entry entry, ScriptRuntime runtime, Namespace root) throws Throwable {
        Class<?> script = Class.forName(entry.className(), true, Launcher.class.getClassLoader());
        MethodHandle run = MethodHandles.privateLookupIn(script, MethodHandles.lookup())
                .findStatic(script, "run", MethodType.methodType(Object.class, ScriptRuntime.class, Namespace.class));
        Object ignored = (Object) run.invokeExact(runtime, root);
    }

    /**
     * What the engine would say : the message, and the program's innermost line, from the
     * stack trace's frames of the program's classes, whose source files are module names.
     */
    private String message(Throwable t) {
        String message = t instanceof BabbleException ? t.getMessage() : t.toString();
        for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getLineNumber() >= 0 && isProgramClass(frame.getClassName())) {
                return message + " in " + frame.getFileName() + " at line number " + frame.getLineNumber();
            }
        }
        return message;
    }

    private boolean isProgramClass(String className) {
        for (ProgramIndex.Entry entry : index.modules().values()) {
            if (className.equals(entry.className()) || className.startsWith(entry.className() + "$")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The program's modules : found in the index, by the path rules the program was compiled
     * with, and loaded once each. Its own static imports are all there ; a dynamic import of
     * a module it doesn't import statically isn't.
     */
    private final class JarModules implements Modules {
        private final Map<String, Module> loaded = new HashMap<>();
        private final Map<String, String> keysByName = new HashMap<>();

        JarModules() {
            for (ProgramIndex.Entry entry : index.modules().values()) {
                keysByName.put(entry.displayName(), entry.key());
            }
        }

        @Override
        public Module load(String name, String importer, ScriptRuntime rt) {
            String importerKey = importer != null ? keysByName.get(importer) : null;
            String key = LogicalPaths.resolve(name, importerKey);
            ProgramIndex.Entry entry = key != null ? index.modules().get(key) : null;
            if (entry == null) {
                throw new BabbleException("Cannot import " + name + " : a compiled program only has the modules it imports statically");
            }
            synchronized (loaded) {
                Module module = loaded.get(key);
                if (module != null) {
                    return module;
                }
                Namespace root = newRoot();
                module = new Module(entry.displayName(), root, entry.exports());
                loaded.put(key, module);
                try {
                    runModule(entry, new ScriptRuntime(root, out, this), root);
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
    }
}
