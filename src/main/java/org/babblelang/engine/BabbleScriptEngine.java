package org.babblelang.engine;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.babblelang.engine.impl.BabbleCompiledScript;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

import javax.script.*;
import java.io.Reader;
import java.io.StringReader;

public class BabbleScriptEngine extends AbstractScriptEngine implements Compilable {
    private final BabbleScriptEngineFactory factory;

    BabbleScriptEngine(BabbleScriptEngineFactory factory) {
        super();
        this.factory = factory;
    }

    @Override
    public Object eval(String script, ScriptContext context) throws ScriptException {
        return eval(new StringReader(script), context);
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
        return compile(reader).eval(context);
    }

    @Override
    public CompiledScript compile(String script) throws ScriptException {
        return compile(new StringReader(script));
    }

    @Override
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

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return factory;
    }

}
