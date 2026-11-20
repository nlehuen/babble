package org.babblelang.engine;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.babblelang.analysis.AnalysisSession;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.compiler.CompiledBabbleScript;
import org.babblelang.runtime.Namespace;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.Modules;
import org.babblelang.runtime.ScriptRuntime;

import javax.script.ScriptContext;
import javax.script.ScriptException;
import java.util.SortedSet;

/**
 * An interactive session (docs/design/bip004_repl.md) : successive inputs, each a small script
 * that sees what the previous ones defined. It runs in the engine's mode, interpreted or
 * compiled.
 * <p>
 * Each input that defines names runs in a namespace nested in the previous one's, and is
 * analysed the same way. So defining a name again shadows the earlier definition for the
 * inputs that follow, while the functions of earlier inputs keep the definitions they
 * were written against ; an assignment, {@code x = …}, changes a variable for everyone.
 */
public final class BabbleSession {
    /** What a text is, as an input : see {@link #classify}. */
    public enum Input {
        /** Nothing to run : blank, or only comments. */
        EMPTY,
        /** It stops in the middle of something : more lines are needed. */
        INCOMPLETE,
        /** Ready for {@link #eval}, which reports its errors, syntax errors included. */
        COMPLETE
    }

    private final BabbleScriptEngine engine;
    private final ScriptContext context;
    private final AnalysisSession analysis = new AnalysisSession();
    private Namespace namespace;
    /** The modules of the whole session : an input sees the modules earlier inputs imported. */
    private final Modules modules;

    BabbleSession(BabbleScriptEngine engine, ScriptContext context) {
        this.engine = engine;
        this.context = context;
        ScriptRuntime first = engine.createRuntime(context);
        this.namespace = first.root();
        this.modules = first.modules();
    }

    /**
     * Tells whether a text read so far can be evaluated, or needs more lines : it stops
     * inside parentheses or a string, or after a token that calls for a continuation.
     */
    public static Input classify(String source) {
        try {
            BabbleScriptEngine.parse(CharStreams.fromString(source));
            return Input.COMPLETE;
        } catch (ParseFailure e) {
            if (!e.atEnd()) {
                return Input.COMPLETE;
            }
            return hasTokens(source) ? Input.INCOMPLETE : Input.EMPTY;
        }
    }

    private static boolean hasTokens(String source) {
        BabbleLexer lexer = BabbleScriptEngine.lexer(CharStreams.fromString(source));
        try {
            for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
                // Comments are tokens too, on a channel the parser does not read.
                if (token.getChannel() == Token.DEFAULT_CHANNEL && token.getType() != BabbleLexer.NL
                        && token.getType() != BabbleLexer.SEMICOLON) {
                    return true;
                }
            }
            return false;
        } catch (ParseFailure e) {
            // An unfinished string.
            return true;
        }
    }

    /**
     * Runs an input, and returns its value. An input that fails before it runs, on a
     * syntax error or in the analysis, leaves the session as it was. One that fails while
     * running keeps what it defined until then.
     */
    public Object eval(String source) throws ScriptException {
        return eval(source, BabbleScriptEngine.sourceName(context));
    }

    /**
     * @param sourceName what errors call the input : a file's name, when it is one
     */
    public Object eval(String source, String sourceName) throws ScriptException {
        BabbleParser.FileContext file = parse(source, sourceName);
        ScopeAnalysis scopes = engine.analyze(file, sourceName, context, analysis);
        BabbleScript script = engine.script(file, scopes, sourceName);
        if (analysis.commit(scopes)) {
            namespace = namespace.enter();
        }
        if (script instanceof CompiledBabbleScript compiled) {
            analysis.recordCompilation(compiled.getScriptClass().getClassLoader(),
                    compiled.interfaceJvmNames(), compiled.classJvmNames());
        }
        return script.eval(new ScriptRuntime(namespace, context.getWriter(), modules));
    }

    /**
     * The static type of an input's value, as type diagnostics show it ({@code list of int},
     * {@code fn (n : int) : int}), or null when it has none : the input is analysed as
     * {@link #eval} would, but it does not run, and the session stays as it was
     * (docs/design/bip004_repl.md §9). Its syntax and type errors are reported as {@code eval}'s.
     */
    public String typeOf(String source) throws ScriptException {
        String sourceName = BabbleScriptEngine.sourceName(context);
        BabbleParser.FileContext file = parse(source, sourceName);
        return engine.analyze(file, sourceName, context, analysis).resultType();
    }

    /** Every name an input could use now : what earlier inputs defined, the bindings, and the implicits. */
    public SortedSet<String> names() {
        return namespace.names();
    }

    /**
     * The value of a name, for completion : it runs nothing. Null when the name is unknown,
     * or its definition hasn't run.
     */
    public Object peek(String name) {
        try {
            return namespace.isDeclared(name) ? namespace.lookup(name) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BabbleParser.FileContext parse(String source, String sourceName) throws ScriptException {
        try {
            return BabbleScriptEngine.parse(CharStreams.fromString(source));
        } catch (ParseFailure e) {
            throw e.toScriptException(sourceName);
        }
    }
}
