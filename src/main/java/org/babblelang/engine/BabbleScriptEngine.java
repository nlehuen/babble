package org.babblelang.engine;

import org.babblelang.runtime.BabbleException;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.IntStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.LexerNoViableAltException;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.babblelang.analysis.AnalysisSession;
import org.babblelang.analysis.AnalysisException;
import org.babblelang.analysis.BlockScopes;
import org.babblelang.analysis.HostEffects;
import org.babblelang.analysis.ModuleInterface;
import org.babblelang.analysis.ModuleLoader;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.compiler.CompiledBabbleScript;
import org.babblelang.engine.impl.BabbleCompiledScript;
import org.babblelang.runtime.Namespace;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.Implicits;
import org.babblelang.runtime.Module;
import org.babblelang.runtime.Modules;
import org.babblelang.runtime.ScriptRuntime;

import javax.script.*;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

public class BabbleScriptEngine extends AbstractScriptEngine implements Compilable {
    /**
     * The name of the system property, and of the engine attribute, that selects the
     * {@link Mode}. The attribute wins : {@code engine.put(MODE, "compile")}.
     */
    public static final String MODE = "babble.mode";

    /** How a script is run : by the tree-walking interpreter, or as JVM bytecode. */
    public enum Mode {
        INTERPRET, COMPILE;

        static Mode parse(Object value) {
            if (value instanceof Mode mode) {
                return mode;
            }
            try {
                return valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BabbleException("Unknown " + MODE + " : " + value + " (expected interpret or compile)");
            }
        }
    }

    /** {@code import} takes a name with this extension for a Babble file, any other for a Java package. */
    public static final String MODULE_EXTENSION = Module.EXTENSION;

    /**
     * The name of the engine attribute holding the {@link ModuleSource} imports read modules
     * from. Without it, modules are files ({@link FileModuleSource}).
     */
    public static final String MODULE_SOURCE = "babble.moduleSource";

    private static final String DEFAULT_SOURCE_NAME = "<input>";

    private static final BaseErrorListener FAIL_ON_LEXICAL_ERROR = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
            // A string still open where the text stops : more text could close it.
            boolean atEnd = recognizer instanceof Lexer lexer && e instanceof LexerNoViableAltException noViableAlt
                    && lexer.getInputStream().LA(1) == IntStream.EOF
                    && lexer.getInputStream().getText(Interval.of(noViableAlt.getStartIndex(), noViableAlt.getStartIndex())).equals("\"");
            throw new ParseFailure("Lexical error at line " + line + ":" + charPositionInLine + " : " + msg, line, charPositionInLine + 1, true, atEnd);
        }
    };

    private final BabbleScriptEngineFactory factory;
    private final Namespace implicits;

    /**
     * A compiled module, as the cache keeps it (docs/design/bip005_modules.md §9) : for the source
     * and mode it was compiled with, and the versions of every module its analysis
     * read, its own included. It is stale when one of them changed.
     */
    private record CompiledModule(ModuleSource source, Mode mode, Map<ModuleId, String> versions,
                                  BabbleScript script, ModuleInterface moduleInterface) {
    }

    /** A module whose analysis is under way : a module importing it back, in a cycle, gets this. */
    private record InProgress(ModuleInterface moduleInterface, String version) {
    }

    /** Guards the module cache and the analyses in progress : modules compile one at a time. */
    private final Object moduleLock = new Object();
    /** By module key. */
    private final Map<String, CompiledModule> moduleCache = new HashMap<>();
    private final Map<String, InProgress> inProgress = new HashMap<>();
    /**
     * The identity of each module compiled, by the source name it was compiled with : what
     * an import inside it, which only knows its file's source name, is resolved from.
     */
    private final Map<String, ModuleId> compiledAs = new HashMap<>();

    private boolean allowAxioms = true;
    private boolean bytecodeEffectAnalysis = false;
    private final Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> hostEffectOverrides = new LinkedHashMap<>();

    BabbleScriptEngine(BabbleScriptEngineFactory factory) {
        super();
        this.factory = factory;

        implicits = Implicits.create();
    }

    public Namespace getImplicits() {
        return implicits;
    }

    /**
     * Controls whether script-level {@code axiom} and {@code impure} Java target declarations
     * are accepted during type checking (default {@code true}).
     */
    public BabbleScriptEngine allowAxioms(boolean allow) {
        synchronized (moduleLock) {
            if (this.allowAxioms != allow) {
                this.allowAxioms = allow;
                this.moduleCache.clear();
            }
        }
        return this;
    }

    public boolean allowsAxioms() {
        synchronized (moduleLock) {
            return allowAxioms;
        }
    }

    /**
     * Opt-in ASM bytecode static effect analysis for unannotated closed Java helper methods
     * ({@code BIP007-P3}, default {@code false}).
     */
    public BabbleScriptEngine enableBytecodeEffectAnalysis(boolean enable) {
        synchronized (moduleLock) {
            if (this.bytecodeEffectAnalysis != enable) {
                this.bytecodeEffectAnalysis = enable;
                this.moduleCache.clear();
            }
        }
        return this;
    }

    public boolean isBytecodeEffectAnalysisEnabled() {
        synchronized (moduleLock) {
            return bytecodeEffectAnalysis;
        }
    }

    /** Registers a per-engine effect assumption for all methods and constructors of {@code target}. */
    public BabbleScriptEngine assumeEffects(Class<?> target, org.babblelang.effects.Effect... effects) {
        Objects.requireNonNull(target, "target");
        HostEffects.OverrideEntry entry = HostEffects.OverrideEntry.fromEffects(HostEffects.TargetKind.CLASS, effects);
        synchronized (moduleLock) {
            hostEffectOverrides.put(HostEffects.JavaTarget.ofClass(target), entry);
            moduleCache.clear();
        }
        return this;
    }

    /** Registers a per-engine effect assumption for all overloads of {@code methodName} on {@code owner}. */
    public BabbleScriptEngine assumeEffects(Class<?> owner, String methodName, org.babblelang.effects.Effect... effects) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(methodName, "methodName");
        if (!HostEffects.hasMethodNamed(owner, methodName)) {
            throw new IllegalArgumentException("No such method : " + methodName + " on " + owner.getName());
        }
        HostEffects.OverrideEntry entry = HostEffects.OverrideEntry.fromEffects(HostEffects.TargetKind.METHOD, effects);
        synchronized (moduleLock) {
            hostEffectOverrides.put(HostEffects.JavaTarget.ofMethod(owner, methodName), entry);
            moduleCache.clear();
        }
        return this;
    }

    /** Registers a per-engine effect assumption for constructors of {@code target} only. */
    public BabbleScriptEngine assumeConstructorEffects(Class<?> target, org.babblelang.effects.Effect... effects) {
        Objects.requireNonNull(target, "target");
        HostEffects.OverrideEntry entry = HostEffects.OverrideEntry.fromEffects(HostEffects.TargetKind.CONSTRUCTOR, effects);
        synchronized (moduleLock) {
            hostEffectOverrides.put(HostEffects.JavaTarget.ofConstructor(target), entry);
            moduleCache.clear();
        }
        return this;
    }

    /** Marks {@code target} (all constructors and methods) as {@code io} on this engine. */
    public BabbleScriptEngine markIO(Class<?> target) {
        Objects.requireNonNull(target, "target");
        synchronized (moduleLock) {
            hostEffectOverrides.put(HostEffects.JavaTarget.ofClass(target), HostEffects.OverrideEntry.ioEntry());
            moduleCache.clear();
        }
        return this;
    }

    /** Marks one or more named methods on {@code owner} as {@code io} on this engine. */
    public BabbleScriptEngine markIO(Class<?> owner, String... methodNames) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(methodNames, "methodNames");
        if (methodNames.length == 0) {
            throw new IllegalArgumentException("Expected at least one method name");
        }
        for (String name : methodNames) {
            Objects.requireNonNull(name, "methodName");
            if (!HostEffects.hasMethodNamed(owner, name)) {
                throw new IllegalArgumentException("No such method : " + name + " on " + owner.getName());
            }
        }
        synchronized (moduleLock) {
            for (String name : methodNames) {
                hostEffectOverrides.put(HostEffects.JavaTarget.ofMethod(owner, name), HostEffects.OverrideEntry.ioEntry());
            }
            moduleCache.clear();
        }
        return this;
    }

    HostEffects hostEffects() {
        synchronized (moduleLock) {
            if (allowAxioms && !bytecodeEffectAnalysis && hostEffectOverrides.isEmpty()) {
                return HostEffects.DEFAULT;
            }
            return new HostEffects(allowAxioms, bytecodeEffectAnalysis, hostEffectOverrides);
        }
    }

    /**
     * The mode {@link #compile} uses : the {@link #MODE} engine attribute, else the system
     * property, else {@link Mode#COMPILE}. The interpreter is kept as the reference
     * implementation the test suite checks the compiler against.
     */
    public Mode getMode() {
        Object mode = get(MODE);
        if (mode == null) {
            mode = System.getProperty(MODE);
        }
        return mode == null ? Mode.COMPILE : Mode.parse(mode);
    }

    /**
     * Builds the namespaces of one eval : the engine's implicits, then a namespace binding
     * {@code STDOUT} to the context's writer, then the script root, which holds the
     * ENGINE_SCOPE bindings and the script's own top-level definitions.
     */
    public ScriptRuntime createRuntime(ScriptContext context) {
        return createRuntime(context, newModules(context));
    }

    private ScriptRuntime createRuntime(ScriptContext context, Modules modules) {
        Namespace root = Implicits.withOutput(implicits, context.getWriter()).enter();

        // TODO : handle ScriptContext.GLOBAL_SCOPE
        for (Map.Entry<String, Object> binding : context.getBindings(ScriptContext.ENGINE_SCOPE).entrySet()) {
            root.define(binding.getKey(), false).set(binding.getValue());
        }
        return new ScriptRuntime(root, context.getWriter(), modules);
    }

    /**
     * The modules of one eval, or of one interactive session : each module is loaded at most
     * once, compiled through the engine's cache, and run on a runtime of its own under the
     * same context.
     */
    Modules newModules(ScriptContext context) {
        return new EvalModules(context);
    }

    /** Where imported modules come from : the {@link #MODULE_SOURCE} attribute, else files. */
    public ModuleSource getModuleSource() {
        Object source = get(MODULE_SOURCE);
        if (source == null) {
            return FileModuleSource.INSTANCE;
        }
        if (source instanceof ModuleSource moduleSource) {
            return moduleSource;
        }
        throw new BabbleException(MODULE_SOURCE + " is not a ModuleSource : " + source);
    }

    private final class EvalModules implements Modules {
        private final ScriptContext context;
        /** The modules loaded so far, by key. */
        private final Map<String, Module> loaded = new HashMap<>();

        EvalModules(ScriptContext context) {
            this.context = context;
        }

        /**
         * {@code import("x.ba")} : a module runs once per eval, in the mode of the engine, with
         * a root of its own : every import of it gets the same module. The module is
         * registered before it runs, so modules may import each other ; what such a cycle
         * cannot do is read an export before its definition ran.
         */
        @Override
        public Module load(String name, String importer, ScriptRuntime rt) {
            ModuleSource source = getModuleSource();
            ModuleId id = resolve(source, name, importerId(source, importer));
            synchronized (loaded) {
                Module module = loaded.get(id.key());
                if (module != null) {
                    return module;
                }
                try {
                    BabbleScript script = compiledModule(source, name, id, context).script();
                    ScriptRuntime runtime = createRuntime(context, this);
                    module = new Module(id.displayName(), runtime.root(), script.exports());
                    loaded.put(id.key(), module);
                    try {
                        script.eval(runtime);
                    } catch (ScriptException | RuntimeException e) {
                        loaded.remove(id.key());
                        throw e;
                    }
                    return module;
                } catch (ScriptException e) {
                    // The message ends with the module's name and line : the importing script
                    // then adds its own, which reads as a trace.
                    throw new BabbleException(withoutClassName(e));
                }
            }
        }
    }

    private static String withoutClassName(ScriptException e) {
        String message = e.getMessage();
        String prefix = BabbleException.class.getName() + ": ";
        return message.startsWith(prefix) ? message.substring(prefix.length()) : message;
    }

    /** The module an import names, or a BabbleException saying why there is none. */
    private static ModuleId resolve(ModuleSource source, String name, ModuleId importer) {
        ModuleId id;
        try {
            id = source.resolve(name, importer);
        } catch (NoSuchFileException e) {
            throw new BabbleException("Cannot import " + name + " : no such file : " + e.getFile());
        } catch (IOException e) {
            throw new BabbleException("Cannot import " + name + " : " + e.getMessage());
        }
        if (id == null) {
            throw new BabbleException("Cannot import " + name + " : no such module");
        }
        return id;
    }

    /**
     * The identity of the module a source name is : a module this engine compiled under that
     * name, else what the source resolves the name to, from nowhere (the main script's file),
     * else none.
     */
    private ModuleId importerId(ModuleSource source, String sourceName) {
        if (sourceName == null) {
            return null;
        }
        synchronized (moduleLock) {
            ModuleId compiled = compiledAs.get(sourceName);
            if (compiled != null) {
                return compiled;
            }
        }
        return source.identify(sourceName);
    }

    /**
     * The compiled module, from the cache when nothing it was compiled from changed.
     * Compiling it analyses the modules it imports statically, through this cache.
     */
    private CompiledModule compiledModule(ModuleSource source, String name, ModuleId id, ScriptContext context) throws ScriptException {
        synchronized (moduleLock) {
            Mode mode = getMode();
            CompiledModule cached = moduleCache.get(id.key());
            if (cached != null && cached.source() == source && cached.mode() == mode && isFresh(cached)) {
                return cached;
            }
            ModuleText text;
            try {
                text = source.read(id);
            } catch (IOException e) {
                throw new BabbleException("Cannot import " + name + " : " + e.getMessage());
            }
            String sourceName = id.displayName();
            BabbleParser.FileContext file;
            try {
                file = parse(CharStreams.fromString(text.text(), sourceName));
            } catch (ParseFailure e) {
                throw e.toScriptException(sourceName);
            }
            compiledAs.put(sourceName, id);
            Loader loader = new Loader(sourceName, context, source, id, text.version());
            ScopeAnalysis scopes;
            try {
                scopes = analyze(file, sourceName, context, null, loader);
            } finally {
                inProgress.remove(id.key());
            }
            loader.versions.put(id, text.version());
            BabbleScript compiledScript = script(file, scopes, sourceName, id);
            if (compiledScript instanceof CompiledBabbleScript compiled && scopes.moduleInterface() != null) {
                scopes.moduleInterface().setCompilation(compiled.getScriptClass().getClassLoader(),
                        compiled.interfaceJvmNames(), compiled.classJvmNames());
            }
            CompiledModule module = new CompiledModule(source, mode, Map.copyOf(loader.versions),
                    compiledScript, scopes.moduleInterface());
            moduleCache.put(id.key(), module);
            return module;
        }
    }

    /** A module of a program compiled ahead of time : what {@code babblec} writes into the jar. */
    public record ProgramModule(ModuleId id, String[] exports, CompiledBabbleScript script) {
    }

    /**
     * Compiles a program ahead of time (docs/design/bip006_module-sources-and-aot.md §5) : its main
     * script, as a module of the source, and every module it imports statically, transitively.
     * The main module comes first, the others by key. Modules it only imports dynamically
     * are not compiled.
     */
    public List<ProgramModule> compileProgram(ModuleSource source, String mainName) throws ScriptException {
        if (getMode() != Mode.COMPILE) {
            throw new ScriptException("A program compiles to bytecode : " + MODE + " must be compile");
        }
        ModuleId main = source.identify(mainName);
        if (main == null) {
            throw new ScriptException("Cannot compile " + mainName + " : not a module of " + source);
        }
        synchronized (moduleLock) {
            CompiledModule program;
            try {
                program = compiledModule(source, mainName, main, getContext());
            } catch (BabbleException e) {
                throw new ScriptException(e.getMessage());
            }
            List<ProgramModule> result = new ArrayList<>();
            result.add(programModule(main, program));
            program.versions().keySet().stream()
                    .filter(id -> !id.key().equals(main.key()))
                    .sorted(java.util.Comparator.comparing(ModuleId::key))
                    .forEach(id -> result.add(programModule(id, moduleCache.get(id.key()))));
            return result;
        }
    }

    private static ProgramModule programModule(ModuleId id, CompiledModule module) {
        return new ProgramModule(id, module.script().exports(), (CompiledBabbleScript) module.script());
    }

    private static boolean isFresh(CompiledModule module) {
        for (Map.Entry<ModuleId, String> version : module.versions().entrySet()) {
            try {
                if (!module.source().version(version.getKey()).equals(version.getValue())) {
                    return false;
                }
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * What the analysis of one file loads its static imports with : names are resolved from
     * that file's module. It notes the versions of the modules it read, for the cache.
     */
    private final class Loader implements ModuleLoader {
        private final String importerName;
        private final ScriptContext context;
        private final ModuleSource source;
        /** The module being analysed, or null for a script that is not one of the source's modules. */
        private final ModuleId self;
        private final String selfVersion;
        final Map<ModuleId, String> versions = new HashMap<>();

        Loader(String importerName, ScriptContext context, ModuleSource source, ModuleId self, String selfVersion) {
            this.importerName = importerName;
            this.context = context;
            this.source = source;
            this.self = self;
            this.selfVersion = selfVersion;
        }

        @Override
        public String moduleKey() {
            return self != null ? self.key() : null;
        }

        @Override
        public void started(ModuleInterface moduleInterface) {
            moduleInterface.setName(importerName);
            if (self != null) {
                inProgress.put(self.key(), new InProgress(moduleInterface, selfVersion));
            }
        }

        @Override
        public ModuleInterface load(String name, int line) {
            synchronized (moduleLock) {
                try {
                    ModuleId importer = self != null ? self : importerId(source, importerName);
                    ModuleId id = resolve(source, name, importer);
                    InProgress pending = inProgress.get(id.key());
                    if (pending != null) {
                        versions.put(id, pending.version());
                        return pending.moduleInterface();
                    }
                    CompiledModule module = compiledModule(source, name, id, context);
                    versions.putAll(module.versions());
                    return module.moduleInterface();
                } catch (BabbleException e) {
                    throw new AnalysisException(e.getMessage(), line);
                } catch (ScriptException e) {
                    // The message ends with the module's name and line ; the importing file adds its own.
                    throw new AnalysisException(withoutClassName(e), line);
                }
            }
        }
    }

    /**
     * The analysis warnings go to the context's error writer before anything runs, and so
     * do the type checker's diagnostics, as errors.
     */
    private static void report(ScopeAnalysis scopes, String sourceName, ScriptContext context) {
        Writer errors = context.getErrorWriter();
        if (errors == null || scopes.warnings().isEmpty() && scopes.typeDiagnostics().isEmpty()) {
            return;
        }
        try {
            for (ScopeAnalysis.Warning warning : scopes.warnings()) {
                errors.write(line("Warning", warning, sourceName));
            }
            for (ScopeAnalysis.Warning diagnostic : scopes.typeDiagnostics()) {
                errors.write(line("Error", diagnostic, sourceName));
            }
            errors.flush();
        } catch (IOException e) {
            // Diagnostics that cannot be written are not worth failing the compilation for.
        }
    }

    private static String line(String label, ScopeAnalysis.Warning warning, String sourceName) {
        return label + " : " + warning.message() + " in " + sourceName + " at line number " + warning.line()
                + (warning.column() > 0 ? ", column " + warning.column() : "") + System.lineSeparator();
    }

    /**
     * Output written through a buffered writer must not be lost when a script fails.
     */
    public static void flush(ScriptContext context) {
        flush(context.getWriter());
    }

    public static void flush(Writer writer) {
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                // Nothing more can be done about output that cannot be written.
            }
        }
    }

    public static final String CHECK_AXIOMS = org.babblelang.analysis.AxiomChecker.PROPERTY;

    public boolean isCheckAxiomsEnabled(ScriptContext context) {
        Object attr = context != null ? context.getAttribute(CHECK_AXIOMS) : get(CHECK_AXIOMS);
        if (attr instanceof Boolean b) {
            return b;
        }
        if (attr != null) {
            return Boolean.parseBoolean(attr.toString());
        }
        return Boolean.parseBoolean(System.getProperty(CHECK_AXIOMS, "false"));
    }

    /** Evaluates {@code script} and runs property-based axiom fuzzing on all top-level {@code axiom} functions. */
    public org.babblelang.analysis.AxiomChecker.Report checkAxioms(String script) throws ScriptException {
        return checkAxioms(script, new org.babblelang.analysis.AxiomChecker());
    }

    /** Evaluates {@code script} and runs property-based axiom fuzzing using a custom {@link org.babblelang.analysis.AxiomChecker}. */
    public org.babblelang.analysis.AxiomChecker.Report checkAxioms(String script, org.babblelang.analysis.AxiomChecker checker) throws ScriptException {
        ScriptContext context = getContext();
        String sourceName = sourceName(context);
        BabbleParser.FileContext file = parseSource(script, sourceName);
        ScopeAnalysis scopes = analyze(file, sourceName, context, null);
        BabbleScript compiled = script(file, scopes, sourceName);
        ScriptRuntime runtime = createRuntime(context);
        compiled.eval(runtime);
        return checker.check(scopes, file, runtime);
    }

    public Object eval(String script, ScriptContext context) throws ScriptException {
        return eval(new StringReader(script), context);
    }

    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        if (!isCheckAxiomsEnabled(context)) {
            return compile(reader, sourceName(context), context).eval(context);
        }
        String sourceName = sourceName(context);
        BabbleParser.FileContext file;
        try {
            file = parse(CharStreams.fromReader(reader, sourceName));
        } catch (IOException e) {
            throw new ScriptException(e);
        }
        ScopeAnalysis scopes = analyze(file, sourceName, context, null);
        BabbleScript compiled = script(file, scopes, sourceName);
        ScriptRuntime runtime = createRuntime(context);
        Object result = compiled.eval(runtime);
        org.babblelang.analysis.AxiomChecker.Report report = new org.babblelang.analysis.AxiomChecker().check(scopes, file, runtime);
        if (!report.ok()) {
            throw new ScriptException(report.failures().get(0).toString());
        }
        return result;
    }

    public CompiledScript compile(String script) throws ScriptException {
        return compile(new StringReader(script));
    }

    public CompiledScript compile(Reader script) throws ScriptException {
        return compile(script, sourceName(getContext()), getContext());
    }

    static String sourceName(ScriptContext context) {
        Object name = context.getAttribute(ScriptEngine.FILENAME);
        return name != null ? name.toString() : DEFAULT_SOURCE_NAME;
    }

    /** A lexer that fails on the first character it doesn't recognise. */
    static BabbleLexer lexer(CharStream input) {
        BabbleLexer lexer = new BabbleLexer(input);
        // An error strategy only governs the parser : without this the lexer reports to
        // ANTLR's ConsoleErrorListener, which prints to stderr, skips the offending
        // character and lets compilation succeed on a mangled token stream.
        lexer.removeErrorListeners();
        lexer.addErrorListener(FAIL_ON_LEXICAL_ERROR);
        return lexer;
    }

    /**
     * @throws ParseFailure on a lexical or syntax error
     */
    static BabbleParser.FileContext parse(CharStream input) {
        CommonTokenStream tokens = new CommonTokenStream(lexer(input));
        BabbleParser parser = new BabbleParser(tokens);
        // BailErrorStrategy already aborts on a syntax error ; dropping the default
        // listener just stops it printing to stderr on the way out.
        parser.removeErrorListeners();
        parser.setErrorHandler(new BailErrorStrategy());
        parser.getInterpreter().setPredictionMode(org.antlr.v4.runtime.atn.PredictionMode.SLL);
        try {
            return parser.file();
        } catch (ParseCancellationException sllFailure) {
            tokens.seek(0);
            parser.reset();
            parser.getInterpreter().setPredictionMode(org.antlr.v4.runtime.atn.PredictionMode.LL);
            try {
                return parser.file();
            } catch (ParseCancellationException e) {
                Token token = e.getCause() instanceof RecognitionException recognition && recognition.getOffendingToken() != null
                        ? recognition.getOffendingToken() : parser.getCurrentToken();
                String found = switch (token.getType()) {
                    case Token.EOF -> "end of input";
                    case BabbleLexer.NL -> "end of line";
                    default -> "'" + token.getText() + "'";
                };
                throw new ParseFailure("Syntax error : unexpected " + found, token.getLine(), token.getCharPositionInLine() + 1,
                        false, token.getType() == Token.EOF);
            }
        }
    }

    /**
     * Parses a source text, without analysing it : what tools working on the text, such as
     * the formatter, start from.
     *
     * @throws ScriptException on a lexical or syntax error, worded as {@code compile} does
     */
    public static BabbleParser.FileContext parseSource(String text, String sourceName) throws ScriptException {
        try {
            return parse(CharStreams.fromString(text, sourceName));
        } catch (ParseFailure e) {
            throw e.toScriptException(sourceName);
        }
    }

    private CompiledScript compile(Reader script, String sourceName, ScriptContext context) throws ScriptException {
        BabbleParser.FileContext file;
        try {
            file = parse(CharStreams.fromReader(script));
        } catch (ParseFailure e) {
            throw e.toScriptException(sourceName);
        } catch (Exception e) {
            throw new ScriptException(e);
        }
        return script(file, analyze(file, sourceName, context, null, null), sourceName);
    }

    /**
     * Runs the analysis, and reports its warnings and the type checker's diagnostics.
     *
     * @param session the interactive session the file is an input of, or null
     */
    ScopeAnalysis analyze(BabbleParser.FileContext file, String sourceName, ScriptContext context, AnalysisSession session) throws ScriptException {
        return analyze(file, sourceName, context, session, null);
    }

    /**
     * @param loader what loads the file's static imports, or null for one relative to
     *               {@code sourceName}
     */
    private ScopeAnalysis analyze(BabbleParser.FileContext file, String sourceName, ScriptContext context, AnalysisSession session,
                                  Loader loader) throws ScriptException {
        ScopeAnalysis scopes;
        ModuleSource source;
        try {
            source = getModuleSource();
        } catch (BabbleException e) {
            throw new ScriptException(e);
        }
        try {
            scopes = BlockScopes.analyze(file, session, loader != null ? loader : new Loader(sourceName, context, source, null, null), hostEffects());
        } catch (AnalysisException e) {
            // Reported exactly like the same error found at runtime.
            throw new ScriptException(new BabbleException(e.getMessage()).toString(), sourceName, e.getLine());
        }
        report(scopes, sourceName, context);
        if (!scopes.typeDiagnostics().isEmpty()) {
            // Every diagnostic went to the error writer ; the first one fails the compilation.
            ScopeAnalysis.Warning first = scopes.typeDiagnostics().get(0);
            throw new TypeCheckException(new BabbleException(first.message()).toString(), sourceName, first.line(), first.column());
        }
        return scopes;
    }

    /** The script of an analysed file, for the engine's mode. */
    BabbleScript script(BabbleParser.FileContext file, ScopeAnalysis scopes, String sourceName) throws ScriptException {
        return script(file, scopes, sourceName, null);
    }

    /** @param module the module compiled, whose key names its classes, or null for a script that is not one */
    private BabbleScript script(BabbleParser.FileContext file, ScopeAnalysis scopes, String sourceName, ModuleId module) throws ScriptException {
        try {
            return switch (getMode()) {
                case INTERPRET -> new BabbleCompiledScript(this, file, scopes, sourceName);
                case COMPILE -> {
                    List<ClassLoader> dependencyLoaders = new ArrayList<>();
                    Map<String, String> knownInterfaceJvmNames = new LinkedHashMap<>();
                    Map<String, String> knownClassJvmNames = new LinkedHashMap<>();
                    collectCompileDependencies(scopes, dependencyLoaders, knownInterfaceJvmNames, knownClassJvmNames);
                    yield BabbleCompiler.compile(this, file, scopes, sourceName,
                            module != null ? module.key() : null, dependencyLoaders, knownInterfaceJvmNames, knownClassJvmNames);
                }
            };
        } catch (RuntimeException e) {
            // A bad mode, or code the compiler cannot generate (a method over 64 KB).
            throw new ScriptException(e);
        }
    }

    private static void collectCompileDependencies(ScopeAnalysis scopes, List<ClassLoader> dependencyLoaders,
                                                   Map<String, String> knownInterfaceJvmNames,
                                                   Map<String, String> knownClassJvmNames) {
        Queue<ModuleInterface> queue = new ArrayDeque<>();
        Set<ModuleInterface> visitedModules = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ClassLoader> seenLoaders = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ModuleInterface mod : scopes.importedModules()) {
            if (mod != null && visitedModules.add(mod)) {
                queue.add(mod);
            }
        }
        while (!queue.isEmpty()) {
            ModuleInterface mod = queue.poll();
            ClassLoader cl = mod.compiledClassLoader();
            if (cl != null && seenLoaders.add(cl)) {
                dependencyLoaders.add(cl);
            }
            knownInterfaceJvmNames.putAll(mod.interfaceJvmNames());
            knownClassJvmNames.putAll(mod.classJvmNames());
            for (ModuleInterface dep : mod.dependencies()) {
                if (dep != null && visitedModules.add(dep)) {
                    queue.add(dep);
                }
            }
        }
        AnalysisSession session = scopes.session();
        if (session != null) {
            for (ClassLoader cl : session.classLoaders()) {
                if (cl != null && seenLoaders.add(cl)) {
                    dependencyLoaders.add(cl);
                }
            }
            knownInterfaceJvmNames.putAll(session.interfaceJvmNames());
            knownClassJvmNames.putAll(session.classJvmNames());
        }
    }

    /**
     * An interactive session : successive inputs, each seeing the definitions of the
     * previous ones. Its output goes to the engine's context.
     */
    public BabbleSession newSession() {
        return newSession(getContext());
    }

    public BabbleSession newSession(ScriptContext context) {
        return new BabbleSession(this, context);
    }

    public Bindings createBindings() {
        return new SimpleBindings();
    }

    public ScriptEngineFactory getFactory() {
        return factory;
    }

}
