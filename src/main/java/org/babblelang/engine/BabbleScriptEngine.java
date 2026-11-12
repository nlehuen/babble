package org.babblelang.engine;

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
import org.babblelang.analysis.ModuleInterface;
import org.babblelang.analysis.ModuleLoader;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.compiler.BabbleCompiler;
import org.babblelang.engine.impl.BabbleCompiledScript;
import org.babblelang.engine.impl.Namespace;
import org.babblelang.engine.impl.natives.AssertFunction;
import org.babblelang.engine.impl.natives.ClassofFunction;
import org.babblelang.engine.impl.natives.PrintFunction;
import org.babblelang.engine.impl.natives.SameFunction;
import org.babblelang.engine.impl.natives.java.ImportFunction;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.Module;
import org.babblelang.runtime.ScriptRuntime;

import javax.script.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class BabbleScriptEngine extends AbstractScriptEngine implements Compilable {
    /**
     * The name of the system property, and of the engine attribute, that selects the
     * {@link Mode}. The attribute wins : {@code engine.put(MODE, "compile")}.
     */
    public static final String MODE = "babble.mode";

    /**
     * The name of the system property, and of the engine attribute, that turns the type
     * checker on : {@code off} (the default) ; {@code warn}, which reports its diagnostics
     * to the context's error writer ; or {@code error}, which fails the compilation on them
     * and checks declared types at runtime (T9). The attribute wins.
     */
    public static final String TYPECHECK = "babble.typecheck";

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
    public static final String MODULE_EXTENSION = ".ba";

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
     * A compiled module, as the cache keeps it (docs/design/modules.md §9) : for the mode and
     * typecheck setting it was compiled with, and the sources of every file its analysis
     * read, its own included. It is stale when one of them changed.
     */
    private record CompiledModule(Mode mode, ScopeAnalysis.Typecheck typecheck, Map<Path, String> sources,
                                  BabbleScript script, ModuleInterface moduleInterface) {
    }

    /** A file whose analysis is under way : a file importing it back, in a cycle, gets this. */
    private record InProgress(ModuleInterface moduleInterface, String source) {
    }

    /** Guards the module cache and the analyses in progress : modules compile one at a time. */
    private final Object moduleLock = new Object();
    private final Map<Path, CompiledModule> moduleCache = new HashMap<>();
    private final Map<Path, InProgress> inProgress = new HashMap<>();

    BabbleScriptEngine(BabbleScriptEngineFactory factory) {
        super();
        this.factory = factory;

        implicits = new Namespace();

        ImportFunction importFunction = new ImportFunction();
        implicits.define("java", true).set(importFunction.getPackage("java"));
        implicits.define("javax", true).set(importFunction.getPackage("javax"));
        implicits.define("import", true).set(importFunction);
        implicits.define("importe", true).set(importFunction);
        implicits.define("print", true).set(new PrintFunction(false));
        implicits.define("println", true).set(new PrintFunction(true));
        implicits.define("affiche", true).set(new PrintFunction(false));
        implicits.define("afficherc", true).set(new PrintFunction(true));
        implicits.define("assert", true).set(new AssertFunction());
        implicits.define("suppose", true).set(new AssertFunction());
        implicits.define("same", true).set(new SameFunction());
        implicits.define("classof", true).set(new ClassofFunction());
    }

    public Namespace getImplicits() {
        return implicits;
    }

    /**
     * The mode {@link #compile} uses : the {@link #MODE} engine attribute, else the system
     * property, else {@link Mode#INTERPRET}.
     */
    public Mode getMode() {
        Object mode = get(MODE);
        if (mode == null) {
            mode = System.getProperty(MODE);
        }
        return mode == null ? Mode.INTERPRET : Mode.parse(mode);
    }

    /** What {@link #compile} does with types : the {@link #TYPECHECK} attribute, else the system property. */
    public ScopeAnalysis.Typecheck getTypecheck() {
        Object value = get(TYPECHECK);
        if (value == null) {
            value = System.getProperty(TYPECHECK);
        }
        String setting = value == null ? "off" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (setting) {
            case "off" -> ScopeAnalysis.Typecheck.OFF;
            case "warn" -> ScopeAnalysis.Typecheck.WARN;
            case "error" -> ScopeAnalysis.Typecheck.ERROR;
            default -> throw new BabbleException("Unknown " + TYPECHECK + " : " + value + " (expected off, warn or error)");
        };
    }

    /**
     * Builds the namespaces of one eval : the engine's implicits, then a namespace binding
     * {@code STDOUT} to the context's writer, then the script root, which holds the
     * ENGINE_SCOPE bindings and the script's own top-level definitions.
     */
    public ScriptRuntime createRuntime(ScriptContext context) {
        return createRuntime(context, new HashMap<>());
    }

    private ScriptRuntime createRuntime(ScriptContext context, Map<Path, Module> modules) {
        Namespace output = implicits.enter(null);
        Writer writer = context.getWriter();
        output.define("STDOUT", true).set(writer == null || writer instanceof PrintWriter ? writer : new PrintWriter(writer));
        Namespace root = output.enter(null);

        // TODO : handle ScriptContext.GLOBAL_SCOPE
        for (Map.Entry<String, Object> binding : context.getBindings(ScriptContext.ENGINE_SCOPE).entrySet()) {
            root.define(binding.getKey(), false).set(binding.getValue());
        }
        return new ScriptRuntime(root, context, this, modules);
    }

    /**
     * {@code import("path.ba")} : the module of a Babble file. A relative path is resolved
     * against the directory of the importing file when its source name is a file (see
     * {@link ScriptEngine#FILENAME}), else against the working directory.
     * <p>
     * A file runs once per eval, in the mode of the engine, with a root of its own : every
     * import of it gets the same module. The module is registered before the file runs,
     * so files may import each other ; what such a cycle cannot do is read an export
     * before its definition ran.
     */
    public Module importModule(ScriptRuntime rt, String name, String importerName) {
        Path file = resolveModule(name, importerName);
        Path key = realPath(name, file);
        Map<Path, Module> modules = rt.modules();
        synchronized (modules) {
            Module module = modules.get(key);
            if (module != null) {
                return module;
            }
            try {
                BabbleScript script = compiledModule(name, key, file.toString(), rt.context()).script();
                ScriptRuntime runtime = createRuntime(rt.context(), modules);
                module = new Module(file.toString(), runtime.root(), script.exports());
                modules.put(key, module);
                try {
                    script.eval(runtime);
                } catch (ScriptException | RuntimeException e) {
                    modules.remove(key);
                    throw e;
                }
                return module;
            } catch (ScriptException e) {
                // The message ends with the file and line of the module : the importing
                // script then adds its own, which reads as a trace.
                String message = e.getMessage();
                String prefix = BabbleException.class.getName() + ": ";
                throw new BabbleException(message.startsWith(prefix) ? message.substring(prefix.length()) : message);
            }
        }
    }

    private static Path realPath(String name, Path file) {
        try {
            return file.toRealPath();
        } catch (IOException e) {
            throw new BabbleException("Cannot import " + name + " : no such file : " + file);
        }
    }

    /**
     * The compiled module of a file, from the cache when nothing it was compiled from
     * changed. Compiling it analyses the files it imports statically, through this cache.
     */
    private CompiledModule compiledModule(String name, Path key, String sourceName, ScriptContext context) throws ScriptException {
        synchronized (moduleLock) {
            String source = read(name, key);
            Mode mode = getMode();
            ScopeAnalysis.Typecheck typecheck;
            try {
                typecheck = getTypecheck();
            } catch (BabbleException e) {
                throw new ScriptException(e);
            }
            CompiledModule cached = moduleCache.get(key);
            if (cached != null && cached.mode() == mode && cached.typecheck() == typecheck && isFresh(cached)) {
                return cached;
            }
            BabbleParser.FileContext file;
            try {
                file = parse(CharStreams.fromString(source, sourceName));
            } catch (ParseFailure e) {
                throw e.toScriptException(sourceName);
            }
            Loader loader = new Loader(sourceName, context, key, source);
            ScopeAnalysis scopes;
            try {
                scopes = analyze(file, sourceName, context, null, loader);
            } finally {
                inProgress.remove(key);
            }
            loader.sources.put(key, source);
            CompiledModule module = new CompiledModule(mode, typecheck, Map.copyOf(loader.sources),
                    script(file, scopes, sourceName), scopes.moduleInterface());
            moduleCache.put(key, module);
            return module;
        }
    }

    private static boolean isFresh(CompiledModule module) {
        for (Map.Entry<Path, String> source : module.sources().entrySet()) {
            try {
                if (!Files.readString(source.getKey()).equals(source.getValue())) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private static String read(String name, Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new BabbleException("Cannot import " + name + " : " + e);
        }
    }

    /**
     * What the analysis of one file loads its static imports with : their paths are relative
     * to that file. It notes the sources of the files it read, for the cache.
     */
    private final class Loader implements ModuleLoader {
        private final String importerName;
        private final ScriptContext context;
        /** The file being analysed, when it is a module, else null. */
        private final Path self;
        private final String selfSource;
        final Map<Path, String> sources = new HashMap<>();

        Loader(String importerName, ScriptContext context, Path self, String selfSource) {
            this.importerName = importerName;
            this.context = context;
            this.self = self;
            this.selfSource = selfSource;
        }

        @Override
        public void started(ModuleInterface moduleInterface) {
            moduleInterface.setName(importerName);
            if (self != null) {
                inProgress.put(self, new InProgress(moduleInterface, selfSource));
            }
        }

        @Override
        public ModuleInterface load(String name, int line) {
            synchronized (moduleLock) {
                try {
                    Path file = resolveModule(name, importerName);
                    Path key = realPath(name, file);
                    InProgress pending = inProgress.get(key);
                    if (pending != null) {
                        sources.put(key, pending.source());
                        return pending.moduleInterface();
                    }
                    CompiledModule module = compiledModule(name, key, file.toString(), context);
                    sources.putAll(module.sources());
                    return module.moduleInterface();
                } catch (BabbleException e) {
                    throw new AnalysisException(e.getMessage(), line);
                } catch (ScriptException e) {
                    // The message ends with the module's file and line ; the importing file adds its own.
                    String message = e.getMessage();
                    String prefix = BabbleException.class.getName() + ": ";
                    throw new AnalysisException(message.startsWith(prefix) ? message.substring(prefix.length()) : message, line);
                }
            }
        }
    }

    private static Path resolveModule(String name, String importerName) {
        try {
            Path path = Path.of(name);
            if (!path.isAbsolute() && importerName != null) {
                Path directory = Path.of(importerName).getParent();
                if (directory != null && Files.isDirectory(directory)) {
                    path = directory.resolve(path);
                }
            }
            return path.normalize();
        } catch (InvalidPathException e) {
            throw new BabbleException("Cannot import " + name + " : " + e.getMessage());
        }
    }

    /**
     * The analysis warnings go to the context's error writer, before anything runs, and so
     * do the type checker's diagnostics : as warnings, or in error mode, as errors.
     */
    private static void report(ScopeAnalysis scopes, ScopeAnalysis.Typecheck typecheck, String sourceName, ScriptContext context) {
        Writer errors = context.getErrorWriter();
        if (errors == null || scopes.warnings().isEmpty() && scopes.typeDiagnostics().isEmpty()) {
            return;
        }
        String typeLabel = typecheck == ScopeAnalysis.Typecheck.ERROR ? "Error" : "Warning";
        try {
            for (ScopeAnalysis.Warning warning : scopes.warnings()) {
                errors.write(line("Warning", warning, sourceName));
            }
            for (ScopeAnalysis.Warning diagnostic : scopes.typeDiagnostics()) {
                errors.write(line(typeLabel, diagnostic, sourceName));
            }
            errors.flush();
        } catch (IOException e) {
            // Warnings that cannot be written are not worth failing the compilation for.
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
        Writer writer = context.getWriter();
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                // Nothing more can be done about output that cannot be written.
            }
        }
    }

    public Object eval(String script, ScriptContext context) throws ScriptException {
        return eval(new StringReader(script), context);
    }

    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        return compile(reader, sourceName(context), context).eval(context);
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
        BabbleParser parser = new BabbleParser(new CommonTokenStream(lexer(input)));
        // BailErrorStrategy already aborts on a syntax error ; dropping the default
        // listener just stops it printing to stderr on the way out.
        parser.removeErrorListeners();
        parser.setErrorHandler(new BailErrorStrategy());
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
        ScopeAnalysis.Typecheck typecheck;
        try {
            typecheck = getTypecheck();
        } catch (BabbleException e) {
            throw new ScriptException(e);
        }
        try {
            scopes = BlockScopes.analyze(file, typecheck, session, loader != null ? loader : new Loader(sourceName, context, null, null));
        } catch (AnalysisException e) {
            // Reported exactly like the same error found at runtime.
            throw new ScriptException(new BabbleException(e.getMessage()).toString(), sourceName, e.getLine());
        }
        report(scopes, typecheck, sourceName, context);
        if (typecheck == ScopeAnalysis.Typecheck.ERROR && !scopes.typeDiagnostics().isEmpty()) {
            // Every diagnostic went to the error writer ; the first one fails the compilation.
            ScopeAnalysis.Warning first = scopes.typeDiagnostics().get(0);
            throw new TypeCheckException(new BabbleException(first.message()).toString(), sourceName, first.line(), first.column());
        }
        return scopes;
    }

    /** The script of an analysed file, for the engine's mode. */
    BabbleScript script(BabbleParser.FileContext file, ScopeAnalysis scopes, String sourceName) throws ScriptException {
        try {
            return switch (getMode()) {
                case INTERPRET -> new BabbleCompiledScript(this, file, scopes, sourceName);
                case COMPILE -> BabbleCompiler.compile(this, file, scopes, sourceName);
            };
        } catch (RuntimeException e) {
            // A bad mode, or code the compiler cannot generate (a method over 64 KB).
            throw new ScriptException(e);
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
