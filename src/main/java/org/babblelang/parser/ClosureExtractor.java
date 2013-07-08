package org.babblelang.parser;

import org.babblelang.engine.impl.Namespace;

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
        private Namespace functionNamespace = new Namespace();

        @Override
        public Void visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
            functionNamespace = functionNamespace.enter(ctx.name.getText());
            visit(ctx.packageBlock);
            functionNamespace = functionNamespace.leave();
            return null;
        }


        @Override
        public Void visitBlock(BabbleParser.BlockContext ctx) {
            functionNamespace = functionNamespace.enter(null);
            super.visitBlock(ctx);
            functionNamespace = functionNamespace.leave();
            return null;
        }

        @Override
        public Void visitParameterDeclaration(BabbleParser.ParameterDeclarationContext ctx) {
            functionNamespace.define(ctx.ID().getText(), true);
            return super.visitParameterDeclaration(ctx);
        }

        @Override
        public Void visitDefExpression(BabbleParser.DefExpressionContext ctx) {
            super.visitDefExpression(ctx);
            functionNamespace.define(ctx.name.getText(), true);
            return null;
        }

        @Override
        public Void visitSelector(BabbleParser.SelectorContext ctx) {
            if (ctx.expression() == null) {
                String name = ctx.ID().getText();

                // if the ID has been defined within the function
                // then we don't need it in the closure
                if (!functionNamespace.isDeclared(name)) {
                    closureKeys.add(name);
                }

                return null;
            }

            return super.visitSelector(ctx);
        }

        private Set<String> getClosureKeys() {
            return closureKeys;
        }
    }
}
