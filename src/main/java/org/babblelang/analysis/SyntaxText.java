package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.CallSite;

import java.util.List;

/**
 * Source-text and {@link CallSite} formatting helpers shared by the analysis passes,
 * the {@code Interpreter}, and {@code HirBuilder} (BIP027-I1).
 */
public final class SyntaxText {
    private SyntaxText() {
    }

    /**
     * Call site for an operator method call, matching the messages of the equivalent explicit call
     * ({@code a + b}, {@code a.plus(b)}; {@code -a}, {@code a.neg()}).
     */
    public static CallSite operatorSite(ParserRuleContext ctx, String receiver, String method, String argument, String sourceName) {
        String[] argTexts = argument == null ? new String[0] : new String[]{argument};
        return new CallSite(sourceName, ctx.getStart().getLine(), receiver + "." + method, argTexts);
    }

    /**
     * The source text of an assignment's target : {@code x}, or {@code obj.x}, which is the
     * left operand of the operator a compound assignment applies.
     */
    public static String targetText(BabbleParser.AssignExpressionContext ctx) {
        String name = ScopeAnalysis.assignName(ctx);
        BabbleParser.ExpressionContext receiver = ScopeAnalysis.assignReceiver(ctx);
        return receiver == null ? name : receiver.getText() + "." + name;
    }

    /**
     * The static description of a call, as both backends build it.
     */
    public static CallSite callSite(BabbleParser.CallContext ctx, String sourceName) {
        List<BabbleParser.CallParameterContext> parameters = ctx.callParameters().callParameter();
        String[] argTexts = new String[parameters.size()];
        for (int i = 0; i < argTexts.length; i++) {
            argTexts[i] = parameters.get(i).expression().getText();
        }
        return new CallSite(sourceName, ctx.getStart().getLine(), ctx.expression().getText(), argTexts);
    }

    /**
     * The names of a call's arguments, parallel to them, or null when none is named.
     */
    public static String[] callNames(BabbleParser.CallContext ctx) {
        List<BabbleParser.CallParameterContext> parameters = ctx.callParameters().callParameter();
        String[] names = null;
        for (int i = 0; i < parameters.size(); i++) {
            String name = ScopeAnalysis.callParamName(parameters.get(i));
            if (name != null) {
                if (names == null) {
                    names = new String[parameters.size()];
                }
                names[i] = name;
            }
        }
        return names;
    }
}
