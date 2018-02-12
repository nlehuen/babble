package org.babblelang.engine.optimizer;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.RuleNode;
import org.babblelang.parser.BabbleBaseVisitor;

public class OptimizerBase extends BabbleBaseVisitor<RuleNode> {
    @Override
    public RuleNode visitChildren(RuleNode node) {
        super.visitChildren(node);
        return node;
    }

    protected static <T1 extends ParserRuleContext, T2 extends ParserRuleContext> T2 replace(T1 ctx1, T2 ctx2) {
        ParserRuleContext parent = ctx1.getParent();
        int index = parent.children.indexOf(ctx1);
        ctx2.parent = parent;
        parent.children.set(index, ctx2);
        return ctx2;
    }

}
