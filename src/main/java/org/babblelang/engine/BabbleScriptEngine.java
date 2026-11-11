package org.babblelang.engine;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.babblelang.analysis.AnalysisException;
import org.babblelang.analysis.BlockScopes;
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

    /** A compiled script, in either mode, as the engine runs it : as a script or as a module. */
    public interface Script {
        /** Runs the script on a runtime built by {@link #createRuntime}, and returns its value. */
        Object run(ScriptRuntime runtime) throws ScriptException;

        /** The names the script exports. */
        String[] exports();
    }

    private static final String DEFAULT_SOURCE_NAME = "<input>";

    private static final BaseErrorListener FAIL_ON_LEXICAL_ERROR = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
            throw new BabbleException("Lexical error at line " + line + ":" + charPositionInLine + " : " + msg);
        }
    };

    private final BabbleScriptEngineFactory factory;
    private final Namespace implicits;

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
        Path key;
        try {
            key = file.toRealPath();
        } catch (IOException e) {
            throw new BabbleException("Cannot import " + name + " : no such file : " + file);
        }
        Map<Path, Module> modules = rt.modules();
        synchronized (modules) {
            Module module = modules.get(key);
            if (module != null) {
                return module;
            }
            try {
                CompiledScript compiled;
                try (Reader reader = Files.newBufferedReader(key)) {
                    compiled = compile(reader, file.toString(), rt.context());
                } catch (IOException e) {
                    throw new BabbleException("Cannot import " + name + " : " + e);
                }
                Script script = (Script) compiled;
                ScriptRuntime runtime = createRuntime(rt.context(), modules);
                module = new Module(file.toString(), runtime.root(), script.exports());
                modules.put(key, module);
                try {
                    script.run(runtime);
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

    private static String sourceName(ScriptContext context) {
        Object name = context.getAttribute(ScriptEngine.FILENAME);
        return name != null ? name.toString() : DEFAULT_SOURCE_NAME;
    }

    private CompiledScript compile(Reader script, String sourceName, ScriptContext context) throws ScriptException {
        BabbleParser.FileContext file;
        try {
            CharStream input = CharStreams.fromReader(script);
            BabbleLexer lexer = new BabbleLexer(input);
            // An error strategy only governs the parser : without this the lexer reports to
            // ANTLR's ConsoleErrorListener, which prints to stderr, skips the offending
            // character and lets compilation succeed on a mangled token stream.
            lexer.removeErrorListeners();
            lexer.addErrorListener(FAIL_ON_LEXICAL_ERROR);
            CommonTokenStream tokenStream = new CommonTokenStream(lexer);
            BabbleParser parser = new BabbleParser(tokenStream);
            // BailErrorStrategy already aborts on a syntax error ; dropping the default
            // listener just stops it printing to stderr on the way out.
            parser.removeErrorListeners();
            parser.setErrorHandler(new BailErrorStrategy());
            file = parser.file();
        } catch (Exception e) {
            throw new ScriptException(e);
        }

        ScopeAnalysis scopes;
        ScopeAnalysis.Typecheck typecheck;
        try {
            typecheck = getTypecheck();
        } catch (BabbleException e) {
            throw new ScriptException(e);
        }
        try {
            scopes = BlockScopes.analyze(file, typecheck);
        } catch (AnalysisException e) {
            // Reported exactly like the same error found at runtime.
            throw new ScriptException(new BabbleException(e.getMessage()).toString(), sourceName, e.getLine());
        }
        report(scopes, typecheck, sourceName, context);
        if (typecheck == ScopeAnalysis.Typecheck.ERROR && !scopes.typeDiagnostics().isEmpty()) {
            // Every diagnostic went to the error writer ; the first one fails the compilation.
            ScopeAnalysis.Warning first = scopes.typeDiagnostics().get(0);
            throw new ScriptException(new BabbleException(first.message()).toString(), sourceName, first.line(), first.column());
        }

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

    public Bindings createBindings() {
        return new SimpleBindings();
    }

    public ScriptEngineFactory getFactory() {
        return factory;
    }

}
