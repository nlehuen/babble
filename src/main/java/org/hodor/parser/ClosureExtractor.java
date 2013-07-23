package org.hodor.parser;

import org.hodor.engine.impl.Namespace;

import java.util.HashSet;
import java.util.Set;

public final class ClosureExtractor {

    public static Set<String> closures(HodorParser.FunctionLiteralContext function) {
        Visitor v = new Visitor();
        v.visit(function);
        return v.closureKeys;
    }

    private ClosureExtractor() {
    }

    private static class Visitor extends HodorBaseVisitor<Void> {
        private final Set<String> closureKeys = new HashSet<String>();
        private Namespace functionNamespace = new Namespace();

        @Override
        public Void visitPackageExpression(HodorParser.PackageExpressionContext ctx) {
            functionNamespace = functionNamespace.enter(ctx.name.getText());
            visit(ctx.packageBlock);
            functionNamespace = functionNamespace.leave();
            return null;
        }


        @Override
        public Void visitBlock(HodorParser.BlockContext ctx) {
            functionNamespace = functionNamespace.enter(null);
            super.visitBlock(ctx);
            functionNamespace = functionNamespace.leave();
            return null;
        }

        @Override
        public Void visitParameterDeclaration(HodorParser.ParameterDeclarationContext ctx) {
            functionNamespace.define(ctx.ID().getText(), true);
            return super.visitParameterDeclaration(ctx);
        }

        @Override
        public Void visitDefExpression(HodorParser.DefExpressionContext ctx) {
            super.visitDefExpression(ctx);
            functionNamespace.define(ctx.name.getText(), true);
            return null;
        }

        @Override
        public Void visitSelector(HodorParser.SelectorContext ctx) {
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
