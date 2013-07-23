package org.hodor.engine;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.hodor.engine.impl.HodorCompiledScript;
import org.hodor.engine.impl.Namespace;
import org.hodor.engine.impl.natives.AssertFunction;
import org.hodor.engine.impl.natives.PrintFunction;
import org.hodor.engine.impl.natives.java.ImportFunction;
import org.hodor.parser.HodorLexer;
import org.hodor.parser.HodorParser;

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
        implicits.define("hodooor", true).set(new PrintFunction(false));
        implicits.define("hodOOOR", true).set(new PrintFunction(true));
        implicits.define("HODORRRR?", true).set(new AssertFunction());
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
            CharStream input = new ANTLRInputStream(script);
            HodorLexer lexer = new HodorLexer(input);
            CommonTokenStream tokenStream = new CommonTokenStream(lexer);
            HodorParser parser = new HodorParser(tokenStream);
            parser.setErrorHandler(new BailErrorStrategy());
            HodorParser.FileContext file = parser.file();
            return compile(file);
        } catch (Exception e) {
            throw new ScriptException(e);
        }
    }

    private HodorCompiledScript compile(HodorParser.FileContext file) {
        return new HodorCompiledScript(this, file);
    }

    public Bindings createBindings() {
        return new SimpleBindings();
    }

    public ScriptEngineFactory getFactory() {
        return factory;
    }

}
