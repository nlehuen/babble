package org.babblelang.parser;

import org.babblelang.engine.impl.Scope;

import java.util.HashSet;
import java.util.Set;

public final class ClosureExtractor {

    public static Set<String> closures(BabbleParser.FunctionLiteralContext function) {
        Visitor v = new Visitor();
        v.visit(function);
        return v.closureKeys;
    }

    private ClosureExtractor() {
    }

    private static class Visitor extends BabbleBaseVisitor<Void> {
        private final Set<String> closureKeys = new HashSet<String>();
        private Scope functionScope = new Scope();

        @Override
        public Void visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
            functionScope = functionScope.enter(ctx.name.getText());
            visit(ctx.packageBlock);
            functionScope = functionScope.leave();
            return null;
        }

        @Override
        public Void visitParenthesis(BabbleParser.ParenthesisContext ctx) {
            functionScope = functionScope.enter(null);
            super.visitParenthesis(ctx);
            functionScope = functionScope.leave();
            return null;
        }

        @Override
        public Void visitParameterDeclaration(BabbleParser.ParameterDeclarationContext ctx) {
            functionScope.define(ctx.ID().getText(), true);
            return super.visitParameterDeclaration(ctx);
        }

        @Override
        public Void visitDefExpression(BabbleParser.DefExpressionContext ctx) {
            super.visitDefExpression(ctx);
            functionScope.define(ctx.name.getText(), true);
            return null;
        }

        @Override
        public Void visitId(BabbleParser.IdContext ctx) {
            String name = ctx.ID().getText();

            // if the ID has been defined within the function
            // then we don't need it in the closure
            if (!functionScope.isDeclared(name)) {
                closureKeys.add(name);
            }

            return super.visitId(ctx);
        }

        private Set<String> getClosureKeys() {
            return closureKeys;
        }
    }
}
