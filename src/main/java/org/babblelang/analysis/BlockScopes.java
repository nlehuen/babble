package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The block scope pass, run on every file before either mode executes it.
 * <ul>
 * <li>It computes the names each scope defines, so that they can be declared when the
 * scope is entered (hoisting).</li>
 * <li>It rejects two definitions of one name in a scope, and a definition in a
 * {@code while} test, which would run on every iteration.</li>
 * <li>It rejects a {@code return} outside of a function body, including one in a
 * parameter default value.</li>
 * </ul>
 * Scopes are opened by the file, function literals (shared by the parameters and the top
 * level of the body), packages, objects, and every other block : a block expression, an
 * {@code if} branch or a {@code while} body.
 */
public final class BlockScopes extends BabbleBaseVisitor<Void> {
    private enum ReturnTarget {
        NONE, FUNCTION, DEFAULT
    }

    private static final class ScopeBuilder {
        final Set<String> names = new HashSet<>();
        final List<String> declared = new ArrayList<>();
        int whileTests = 0;
    }

    private final ScopeAnalysis analysis = new ScopeAnalysis();
    private final Deque<ScopeBuilder> scopes = new ArrayDeque<>();
    private ReturnTarget returnTarget = ReturnTarget.NONE;

    private BlockScopes() {
    }

    public static ScopeAnalysis analyze(BabbleParser.FileContext file) {
        BlockScopes pass = new BlockScopes();
        pass.inScope(file, () -> pass.visitChildren(file));
        return pass.analysis;
    }

    private void inScope(ParserRuleContext owner, Runnable body) {
        ScopeBuilder scope = new ScopeBuilder();
        scopes.push(scope);
        try {
            body.run();
        } finally {
            scopes.pop();
        }
        analysis.setDeclared(owner, scope.declared.toArray(new String[0]));
    }

    private void declare(Token name, boolean hoisted) {
        ScopeBuilder scope = scopes.element();
        String text = name.getText();
        if (!scope.names.add(text)) {
            throw new AnalysisException("Name already defined : " + text, name.getLine());
        }
        if (hoisted) {
            scope.declared.add(text);
        }
    }

    @Override
    public Void visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
        ReturnTarget outer = returnTarget;
        try {
            inScope(ctx, () -> {
                List<String> parameters = new ArrayList<>();
                for (BabbleParser.ParameterDeclarationContext parameter : ctx.parametersDeclaration().parameterDeclaration()) {
                    declare(parameter.ID().getSymbol(), false);
                    parameters.add(parameter.ID().getText());
                    if (parameter.defaultValue != null) {
                        returnTarget = ReturnTarget.DEFAULT;
                        visit(parameter.defaultValue);
                    }
                }
                analysis.setParameters(ctx, parameters.toArray(new String[0]));
                returnTarget = ReturnTarget.FUNCTION;
                visitChildren(ctx.functionBlock);
            });
        } finally {
            returnTarget = outer;
        }
        return null;
    }

    @Override
    public Void visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
        inScope(ctx, () -> visitChildren(ctx.packageBlock));
        return null;
    }

    @Override
    public Void visitObjectExpression(BabbleParser.ObjectExpressionContext ctx) {
        inScope(ctx, () -> visitChildren(ctx.createBlock));
        return null;
    }

    /**
     * Only reached for blocks that open a scope of their own : function, package and
     * object bodies are visited through their owner.
     */
    @Override
    public Void visitBlock(BabbleParser.BlockContext ctx) {
        inScope(ctx, () -> visitChildren(ctx));
        return null;
    }

    @Override
    public Void visitDefExpression(BabbleParser.DefExpressionContext ctx) {
        if (scopes.element().whileTests > 0) {
            throw new AnalysisException("Definition in a while test : " + ctx.name.getText(), ctx.name.getLine());
        }
        declare(ctx.name, true);
        // The type annotation is not visited : a function type contains parameter
        // declarations, which are not real parameters.
        if (ctx.value != null) {
            visit(ctx.value);
        }
        return null;
    }

    @Override
    public Void visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
        ScopeBuilder scope = scopes.element();
        scope.whileTests++;
        try {
            visit(ctx.test);
        } finally {
            scope.whileTests--;
        }
        visit(ctx.whileBlock);
        return null;
    }

    @Override
    public Void visitReturnExpression(BabbleParser.ReturnExpressionContext ctx) {
        switch (returnTarget) {
            case NONE -> throw new AnalysisException("return outside of a function", ctx.getStart().getLine());
            case DEFAULT -> throw new AnalysisException("return in a parameter default", ctx.getStart().getLine());
            case FUNCTION -> visit(ctx.expression());
        }
        return null;
    }
}
