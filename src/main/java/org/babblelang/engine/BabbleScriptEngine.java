package org.babblelang.engine;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.babblelang.engine.impl.BabbleCompiledScript;
import org.babblelang.engine.impl.Scope;
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
    private final Scope implicits;

    BabbleScriptEngine(BabbleScriptEngineFactory factory) {
        super();
        this.factory = factory;

        implicits = new Scope();

        ImportFunction importFunction = new ImportFunction();
        implicits.define("java", importFunction.getPackage("java"));
        implicits.define("javax", importFunction.getPackage("javax"));
        implicits.define("import", importFunction);
        implicits.define("importe", importFunction);
        implicits.define("print", new PrintFunction(false));
        implicits.define("println", new PrintFunction(true));
        implicits.define("affiche", new PrintFunction(false));
        implicits.define("afficherc", new PrintFunction(true));
        implicits.define("assert", new AssertFunction());
        implicits.define("suppose", new AssertFunction());
        implicits.define("STDOUT", System.out);
    }

    public Scope getImplicits() {
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
            CharStream input = new ANTLRInputStream(script);
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
