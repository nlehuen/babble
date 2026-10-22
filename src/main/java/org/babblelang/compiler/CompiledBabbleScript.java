package org.babblelang.compiler;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.runtime.ScriptRuntime;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.lang.invoke.MethodHandle;
import java.util.Map;

/**
 * A script compiled to a JVM class. The class holds no state of its own, so it serves
 * every eval : each one gets fresh namespaces and its own ScriptRuntime.
 */
public final class CompiledBabbleScript extends CompiledScript {
    private final BabbleScriptEngine engine;
    private final Class<?> scriptClass;
    private final Map<String, byte[]> classes;
    private final MethodHandle run;
    private final String sourceName;
    private final int firstLine;

    CompiledBabbleScript(BabbleScriptEngine engine, Class<?> scriptClass, Map<String, byte[]> classes, MethodHandle run, String sourceName, int firstLine) {
        this.engine = engine;
        this.scriptClass = scriptClass;
        this.classes = classes;
        this.run = run;
        this.sourceName = sourceName;
        this.firstLine = firstLine;
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

    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        ScriptRuntime runtime = engine.createRuntime(context);
        try {
            return (Object) run.invokeExact(runtime, runtime.root());
        } catch (Exception e) {
            throw new ScriptException(e.toString(), sourceName, lineOf(e));
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new ScriptException(t.toString(), sourceName, lineOf(t));
        } finally {
            BabbleScriptEngine.flush(context);
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

    @Override
    public ScriptEngine getEngine() {
        return engine;
    }
}
