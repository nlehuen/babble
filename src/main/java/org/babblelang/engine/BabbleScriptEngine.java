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
import org.babblelang.runtime.ScriptRuntime;

import javax.script.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.util.Locale;
import java.util.Map;

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

    /**
     * Builds the namespaces of one eval : the engine's implicits, then a namespace binding
     * {@code STDOUT} to the context's writer, then the script root, which holds the
     * ENGINE_SCOPE bindings and the script's own top-level definitions.
     */
    public ScriptRuntime createRuntime(ScriptContext context) {
        Namespace output = implicits.enter(null);
        Writer writer = context.getWriter();
        output.define("STDOUT", true).set(writer == null || writer instanceof PrintWriter ? writer : new PrintWriter(writer));
        Namespace root = output.enter(null);

        // TODO : handle ScriptContext.GLOBAL_SCOPE
        for (Map.Entry<String, Object> binding : context.getBindings(ScriptContext.ENGINE_SCOPE).entrySet()) {
            root.define(binding.getKey(), false).set(binding.getValue());
        }
        return new ScriptRuntime(root, context, this);
    }

    /** The analysis warnings go to the context's error writer, before anything runs. */
    private static void warn(ScopeAnalysis scopes, String sourceName, ScriptContext context) {
        Writer errors = context.getErrorWriter();
        if (errors == null || scopes.warnings().isEmpty()) {
            return;
        }
        try {
            for (ScopeAnalysis.Warning warning : scopes.warnings()) {
                errors.write("Warning : " + warning.message() + " in " + sourceName + " at line number " + warning.line() + System.lineSeparator());
            }
            errors.flush();
        } catch (IOException e) {
            // Warnings that cannot be written are not worth failing the compilation for.
        }
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
        try {
            scopes = BlockScopes.analyze(file);
        } catch (AnalysisException e) {
            // Reported exactly like the same error found at runtime.
            throw new ScriptException(new BabbleException(e.getMessage()).toString(), sourceName, e.getLine());
        }
        warn(scopes, sourceName, context);

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
