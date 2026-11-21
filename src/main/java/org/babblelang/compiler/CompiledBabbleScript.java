package org.babblelang.compiler;

import org.babblelang.engine.BabbleScript;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.runtime.ArraySupport;
import org.babblelang.runtime.ScriptRuntime;

import javax.script.ScriptException;
import java.lang.invoke.MethodHandle;
import java.util.Map;

/**
 * A script compiled to a JVM class. The class holds no state of its own, so it serves
 * every eval : each one gets fresh namespaces and its own ScriptRuntime.
 */
public final class CompiledBabbleScript extends BabbleScript {
    private final Class<?> scriptClass;
    private final Map<String, byte[]> classes;
    private final MethodHandle run;
    private final String sourceName;
    private final int firstLine;
    private final String[] exports;
    private final Map<String, String> interfaceJvmNames;
    private final Map<String, String> classJvmNames;

    CompiledBabbleScript(BabbleScriptEngine engine, Class<?> scriptClass, Map<String, byte[]> classes, MethodHandle run, String sourceName, int firstLine, String[] exports) {
        this(engine, scriptClass, classes, run, sourceName, firstLine, exports, Map.of(), Map.of());
    }

    CompiledBabbleScript(BabbleScriptEngine engine, Class<?> scriptClass, Map<String, byte[]> classes, MethodHandle run, String sourceName, int firstLine, String[] exports, Map<String, String> interfaceJvmNames) {
        this(engine, scriptClass, classes, run, sourceName, firstLine, exports, interfaceJvmNames, Map.of());
    }

    CompiledBabbleScript(BabbleScriptEngine engine, Class<?> scriptClass, Map<String, byte[]> classes, MethodHandle run, String sourceName, int firstLine, String[] exports, Map<String, String> interfaceJvmNames, Map<String, String> classJvmNames) {
        super(engine);
        this.exports = exports;
        this.scriptClass = scriptClass;
        this.classes = classes;
        this.run = run;
        this.sourceName = sourceName;
        this.firstLine = firstLine;
        this.interfaceJvmNames = interfaceJvmNames != null ? Map.copyOf(interfaceJvmNames) : Map.of();
        this.classJvmNames = classJvmNames != null ? Map.copyOf(classJvmNames) : Map.of();
    }

    /** The generated class, for tests and debugging. */
    public Class<?> getScriptClass() {
        return scriptClass;
    }

    /** The class file of the script class, for tests and debugging. */
    public byte[] getClassBytes() {
        return classes.values().iterator().next().clone();
    }

    /**
     * The class files of the script class, then of the classes generated for class and
     * object expressions, by internal name.
     */
    public Map<String, byte[]> getClasses() {
        return java.util.Collections.unmodifiableMap(classes);
    }

    /** Internal JVM names of Babble interfaces compiled by this script, keyed by {@code ClassShape.id()}. */
    public Map<String, String> interfaceJvmNames() {
        return interfaceJvmNames;
    }

    /** Internal JVM names of static top-level Babble classes compiled by this script, keyed by {@code ClassShape.id()}. */
    public Map<String, String> classJvmNames() {
        return classJvmNames;
    }

    @Override
    public String[] exports() {
        return exports.clone();
    }

    @Override
    public Object eval(ScriptRuntime runtime) throws ScriptException {
        try {
            return (Object) run.invokeExact(runtime, runtime.root());
        } catch (Exception e) {
            throw new ScriptException(ArraySupport.formatThrowable(e), sourceName, lineOf(e));
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new ScriptException(ArraySupport.formatThrowable(t), sourceName, lineOf(t));
        } finally {
            BabbleScriptEngine.flush(runtime.writer());
        }
    }

    /**
     * The Babble line of the innermost frame of the script's classes that has one : the
     * script class, or the class of a class expression, whose names extend the script
     * class's with a '$'. Runtime helpers are skipped, so a failure inside an operator
     * reports the operator's line.
     * Frames without a line are skipped too : that is a function prologue binding
     * arguments, and the error is then reported at the line of the call.
     */
    private int lineOf(Throwable t) {
        String name = scriptClass.getName();
        for (StackTraceElement element : t.getStackTrace()) {
            String className = element.getClassName();
            if ((className.equals(name) || className.startsWith(name + "$")) && element.getLineNumber() >= 0) {
                return element.getLineNumber();
            }
        }
        return firstLine;
    }
}
