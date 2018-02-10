package org.babblelang.engine;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.babblelang.engine.impl.BabbleCompiledScript;
import org.babblelang.engine.impl.Namespace;
import org.babblelang.engine.impl.natives.AssertFunction;
import org.babblelang.engine.impl.natives.PrintFunction;
import org.babblelang.engine.impl.natives.java.ImportFunction;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

import javax.script.*;
import java.io.Reader;
import java.io.StringReader;

public class BabbleScriptEngine extends AbstractScriptEngine implements Compilable {
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
        implicits.define("STDOUT", true).set(System.out);
    }

    public Namespace getImplicits() {
        return implicits;
    }

    public Object eval(String script, ScriptContext context) throws ScriptException {
        return eval(new StringReader(script), context);
    }

    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        return compile(reader).eval(context);
    }

    public CompiledScript compile(String script) throws ScriptException {
        return compile(new StringReader(script));
    }

    public CompiledScript compile(Reader script) throws ScriptException {
        try {
            CharStream input = CharStreams.fromReader(script);
            BabbleLexer lexer = new BabbleLexer(input);
            CommonTokenStream tokenStream = new CommonTokenStream(lexer);
            BabbleParser parser = new BabbleParser(tokenStream);
            parser.setErrorHandler(new BailErrorStrategy());
            BabbleParser.FileContext file = parser.file();
            return compile(file);
        } catch (Exception e) {
            throw new ScriptException(e);
        }
    }

    private BabbleCompiledScript compile(BabbleParser.FileContext file) {
        return new BabbleCompiledScript(this, file);
    }

    public Bindings createBindings() {
        return new SimpleBindings();
    }

    public ScriptEngineFactory getFactory() {
        return factory;
    }

}
