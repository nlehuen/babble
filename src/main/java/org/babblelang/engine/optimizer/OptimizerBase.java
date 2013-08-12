package org.babblelang.engine.optimizer;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.RuleNode;
import org.babblelang.parser.BabbleBaseVisitor;

public class OptimizerBase extends BabbleBaseVisitor<RuleNode> {
    @Override
    public RuleNode visitChildren(@NotNull RuleNode node) {
        super.visitChildren(node);
        return node;
    }

    protected static void remove(ParserRuleContext ctx1) {
        ctx1.getParent().children.remove(ctx1);
    }

    protected static <T extends ParserRuleContext> T replace(ParserRuleContext ctx1, T ctx2) {
        ParserRuleContext parent = ctx1.getParent();
        int index = parent.children.indexOf(ctx1);
        ctx2.parent = parent;
        parent.children.set(index, ctx2);
        return ctx2;
    }

    protected static <T extends ParseTree> T replace(ParserRuleContext ctx1, T ctx2) {
        ParserRuleContext parent = ctx1.getParent();
        int index = parent.children.indexOf(ctx1);
        parent.children.set(index, ctx2);
        return ctx2;
    }
}
