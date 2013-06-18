package org.babblelang.engine.impl;

import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleParser;

import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

public class BabbleCompiledScript extends CompiledScript {
    private final BabbleScriptEngine engine;
    private final BabbleParser.FileContext file;

    private Scope FILE = new Scope();

    private class Compiler extends BabbleBaseVisitor<Void> {
        private Scope currentScope = FILE;

        @Override
        public Void visitPackageStatement(BabbleParser.PackageStatementContext ctx) {
            Scope packageScope = new Scope(currentScope);
            currentScope.put(ctx.getText(), packageScope);
            currentScope = packageScope;
            super.visitPackageStatement(ctx);
            currentScope = currentScope.getParent();
            return null;
        }

        @Override
        public Void visitStatement(BabbleParser.StatementContext ctx) {
            new StatementRunner(currentScope).run(ctx);
            return null;
        }
    }

    private class Runner extends BabbleBaseVisitor<Object> {
        @Override
        public Object visitFile(BabbleParser.FileContext ctx) {
            return new StatementRunner(FILE).run(ctx.statement());
        }
    }

    public BabbleCompiledScript(BabbleScriptEngine engine, BabbleParser.FileContext file) {
        this.engine = engine;
        this.file = file;
        new Compiler().visit(file);
    }

    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        return new Runner().visit(file);
    }

    @Override
    public ScriptEngine getEngine() {
        return engine;
    }
}
