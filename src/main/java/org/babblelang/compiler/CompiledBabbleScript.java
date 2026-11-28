package org.babblelang.compiler;

import java.lang.invoke.MethodHandle;
import java.util.Map;
import java.util.Set;
import javax.script.ScriptException;
import org.babblelang.engine.BabbleScript;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.runtime.ArraySupport;
import org.babblelang.runtime.ScriptRuntime;

/**
 * A script compiled to a JVM class. The class holds no state of its own, so it serves every eval :
 * each one gets fresh namespaces and its own ScriptRuntime.
 */
public final class CompiledBabbleScript extends BabbleScript {
    /**
     * Static summary of root implicit and {@code import} usage observed in the final compiled HIR,
     * used by {@link Babblec} to generate a minimal {@code babble/$Implicits} and tree-shake unused
     * runtime classes.
     */
    public record AotUsage(
            boolean hasDynamicImport, boolean hasJavaImport, Set<String> usedImplicits) {
        public static final AotUsage EMPTY = new AotUsage(false, false, Set.of());

        public AotUsage {
            usedImplicits = usedImplicits != null ? Set.copyOf(usedImplicits) : Set.of();
        }
    }

    private final Class<?> scriptClass;
    private final Map<String, byte[]> classes;
    private final MethodHandle run;
    private final String sourceName;
    private final int firstLine;
    private final String[] exports;
    private final Map<String, String> interfaceJvmNames;
    private final Map<String, String> classJvmNames;
    private final Map<String, org.babblelang.analysis.LoweringTargets.ExportedFunctionTarget>
            exportedFunctions;
    private final Map<String, org.babblelang.ir.hir.HirFunction> crossModuleInlineExports;
    private final AotUsage aotUsage;

    CompiledBabbleScript(
            BabbleScriptEngine engine,
            Class<?> scriptClass,
            Map<String, byte[]> classes,
            MethodHandle run,
            String sourceName,
            int firstLine,
            String[] exports) {
        this(
                engine,
                scriptClass,
                classes,
                run,
                sourceName,
                firstLine,
                exports,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }

    CompiledBabbleScript(
            BabbleScriptEngine engine,
            Class<?> scriptClass,
            Map<String, byte[]> classes,
            MethodHandle run,
            String sourceName,
            int firstLine,
            String[] exports,
            Map<String, String> interfaceJvmNames) {
        this(
                engine,
                scriptClass,
                classes,
                run,
                sourceName,
                firstLine,
                exports,
                interfaceJvmNames,
                Map.of(),
                Map.of(),
                Map.of());
    }

    CompiledBabbleScript(
            BabbleScriptEngine engine,
            Class<?> scriptClass,
            Map<String, byte[]> classes,
            MethodHandle run,
            String sourceName,
            int firstLine,
            String[] exports,
            Map<String, String> interfaceJvmNames,
            Map<String, String> classJvmNames) {
        this(
                engine,
                scriptClass,
                classes,
                run,
                sourceName,
                firstLine,
                exports,
                interfaceJvmNames,
                classJvmNames,
                Map.of(),
                Map.of());
    }

    CompiledBabbleScript(
            BabbleScriptEngine engine,
            Class<?> scriptClass,
            Map<String, byte[]> classes,
            MethodHandle run,
            String sourceName,
            int firstLine,
            String[] exports,
            Map<String, String> interfaceJvmNames,
            Map<String, String> classJvmNames,
            Map<String, org.babblelang.analysis.LoweringTargets.ExportedFunctionTarget>
                    exportedFunctions,
            Map<String, org.babblelang.ir.hir.HirFunction> crossModuleInlineExports) {
        this(
                engine,
                scriptClass,
                classes,
                run,
                sourceName,
                firstLine,
                exports,
                interfaceJvmNames,
                classJvmNames,
                exportedFunctions,
                crossModuleInlineExports,
                AotUsage.EMPTY);
    }

    CompiledBabbleScript(
            BabbleScriptEngine engine,
            Class<?> scriptClass,
            Map<String, byte[]> classes,
            MethodHandle run,
            String sourceName,
            int firstLine,
            String[] exports,
            Map<String, String> interfaceJvmNames,
            Map<String, String> classJvmNames,
            Map<String, org.babblelang.analysis.LoweringTargets.ExportedFunctionTarget>
                    exportedFunctions,
            Map<String, org.babblelang.ir.hir.HirFunction> crossModuleInlineExports,
            AotUsage aotUsage) {
        super(engine);
        this.exports = exports;
        this.scriptClass = scriptClass;
        this.classes = classes;
        this.run = run;
        this.sourceName = sourceName;
        this.firstLine = firstLine;
        this.interfaceJvmNames =
                interfaceJvmNames != null ? Map.copyOf(interfaceJvmNames) : Map.of();
        this.classJvmNames = classJvmNames != null ? Map.copyOf(classJvmNames) : Map.of();
        this.exportedFunctions =
                exportedFunctions != null ? Map.copyOf(exportedFunctions) : Map.of();
        this.crossModuleInlineExports =
                crossModuleInlineExports != null ? Map.copyOf(crossModuleInlineExports) : Map.of();
        this.aotUsage = aotUsage != null ? aotUsage : AotUsage.EMPTY;
    }

    /** Static implicit and import usage recorded from the compiled HIR for AOT jar trimming. */
    public AotUsage aotUsage() {
        return aotUsage;
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
     * The class files of the script class, then of the classes generated for class and object
     * expressions, by internal name.
     */
    public Map<String, byte[]> getClasses() {
        return java.util.Collections.unmodifiableMap(classes);
    }

    /**
     * Internal JVM names of Babble interfaces compiled by this script, keyed by {@code
     * ClassShape.id()}.
     */
    public Map<String, String> interfaceJvmNames() {
        return interfaceJvmNames;
    }

    /**
     * Internal JVM names of static top-level Babble classes compiled by this script, keyed by
     * {@code ClassShape.id()}.
     */
    public Map<String, String> classJvmNames() {
        return classJvmNames;
    }

    /** Direct static JVM targets of top-level exported Babble functions compiled by this script. */
    public Map<String, org.babblelang.analysis.LoweringTargets.ExportedFunctionTarget>
            exportedFunctions() {
        return exportedFunctions;
    }

    /**
     * Small self-contained pure exported HIR functions and {@code fastRecord} methods eligible for
     * cross-module inlining.
     */
    public Map<String, org.babblelang.ir.hir.HirFunction> crossModuleInlineExports() {
        return crossModuleInlineExports;
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
     * The Babble line of the innermost frame of the script's classes that has one : the script
     * class, or the class of a class expression, whose names extend the script class's with a '$'.
     * Runtime helpers are skipped, so a failure inside an operator reports the operator's line.
     * Frames without a line are skipped too : that is a function prologue binding arguments, and
     * the error is then reported at the line of the call.
     */
    private int lineOf(Throwable t) {
        String name = scriptClass.getName();
        for (StackTraceElement element : t.getStackTrace()) {
            String className = element.getClassName();
            if ((className.equals(name) || className.startsWith(name + "$"))
                    && element.getLineNumber() >= 0) {
                return element.getLineNumber();
            }
        }
        return firstLine;
    }
}
