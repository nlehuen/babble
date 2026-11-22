package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleParser;

/**
 * Finds the self tail calls of a file (docs/design/bip024_tail-calls.md, {@code BIP024-D1}–{@code D4}) :
 * calls of a function to itself whose value is the function's result, which both backends run as a
 * jump back to the start of the function instead of a nested call.
 *
 * <p>A call is a self tail call when it is in tail position (the last expression of the function's
 * block, through block expressions and {@code if} branches, or the value of a {@code return} in
 * statement position), calls the function itself ({@code recurse(...)}, the function's own
 * non-reassigned {@code def} by name, or {@code this.m(...)} in method {@code m} of a class or
 * object), binds its arguments statically, and nothing between it and the function's result checks
 * or widens its value. Runs after {@link LoweringAnalyzer}, whose call targets, bindings, checks and
 * widenings it reads.
 */
final class TailCalls {
    private final ScopeAnalysis analysis;

    private TailCalls(ScopeAnalysis analysis) {
        this.analysis = analysis;
    }

    static void publish(ScopeAnalysis analysis, BabbleParser.FileContext file) {
        TailCalls tailCalls = new TailCalls(analysis);
        new BabbleBaseVisitor<Void>() {
            @Override
            public Void visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
                tailCalls.function(ctx);
                return super.visitFunctionLiteral(ctx);
            }
        }.visit(file);
    }

    private void function(BabbleParser.FunctionLiteralContext function) {
        if (isInterfaceMember(function) || analysis.parameters(function) == null) {
            return;
        }
        block(function, function.functionBlock, true);
    }

    /** The statements of a block in statement position, the last one in tail position when the block is. */
    private void block(BabbleParser.FunctionLiteralContext function, BabbleParser.BlockContext block, boolean tail) {
        ParseTree last = null;
        for (ParseTree child : block.children) {
            if (child instanceof BabbleParser.ExpressionContext || child instanceof BabbleParser.EffectDeclarationContext) {
                last = child;
            }
        }
        for (BabbleParser.ExpressionContext statement : block.expression()) {
            statement(function, statement, tail && statement == last);
        }
    }

    /** An expression in statement position : nothing waits on the operand stack below its value. */
    private void statement(BabbleParser.FunctionLiteralContext function, BabbleParser.ExpressionContext expression, boolean tail) {
        switch (expression) {
            case BabbleParser.BlockExpressionContext b -> block(function, b.block(), tail);
            case BabbleParser.IfExpressionContext i -> ifExpr(function, i.ifExpr(), tail);
            case BabbleParser.WhileExpressionContext w -> block(function, w.whileBlock, false);
            case BabbleParser.ForExpressionContext f -> block(function, f.forBlock, false);
            case BabbleParser.ReturnExpressionContext r -> statement(function, r.expression(), true);
            case BabbleParser.CallContext call -> {
                if (tail && isSelfTailCall(function, call)) {
                    analysis.setSelfTailCall(call);
                }
            }
            default -> {
            }
        }
    }

    private void ifExpr(BabbleParser.FunctionLiteralContext function, BabbleParser.IfExprContext ifExpr, boolean tail) {
        block(function, ifExpr.thenBlock, tail);
        if (ifExpr.elseIf != null) {
            ifExpr(function, ifExpr.elseIf, tail);
        } else if (ifExpr.elseBlock != null) {
            block(function, ifExpr.elseBlock, tail);
        }
    }

    private boolean isSelfTailCall(BabbleParser.FunctionLiteralContext function, BabbleParser.CallContext call) {
        if (!callsItself(function, call) || !bindsStatically(function, call)) {
            return false;
        }
        // Nothing between the call and the function's result may check or widen the value (BIP024-D4).
        for (ParserRuleContext node = call; node != null && node != function.functionBlock; node = node.getParent()) {
            if (analysis.numericWideningRank(node) >= 0 || analysis.checkedType(node) != null) {
                return false;
            }
        }
        return true;
    }

    private boolean callsItself(BabbleParser.FunctionLiteralContext function, BabbleParser.CallContext call) {
        BabbleParser.ExpressionContext callee = call.expression();
        if (callee instanceof BabbleParser.RecurseContext) {
            return true;
        }
        if (analysis.babbleCallFunction(call) == function) {
            return true;
        }
        if (callee instanceof BabbleParser.SelectorContext selector && selector.safe == null
                && selector.expression() instanceof BabbleParser.ThisExpressionContext) {
            BabbleParser.DefExpressionContext def = methodDef(function);
            return def != null && def.name.getText().equals(ScopeAnalysis.selectorName(selector));
        }
        return false;
    }

    /** The arguments are bound at compile time, or positional and as many as the parameters. */
    private boolean bindsStatically(BabbleParser.FunctionLiteralContext function, BabbleParser.CallContext call) {
        if (analysis.argumentBinding(call) != null) {
            return true;
        }
        var arguments = call.callParameters().callParameter();
        for (BabbleParser.CallParameterContext argument : arguments) {
            if (argument.memberName() != null) {
                return false;
            }
        }
        return arguments.size() == analysis.parameters(function).length;
    }

    /** The {@code def} of a method of a class or object whose value is this function, or null. */
    private static BabbleParser.DefExpressionContext methodDef(BabbleParser.FunctionLiteralContext function) {
        if (!(function.getParent() instanceof BabbleParser.DefExpressionContext def) || def.value != function
                || !(def.getParent() instanceof BabbleParser.BlockContext body)) {
            return null;
        }
        ParserRuleContext owner = body.getParent();
        if (owner instanceof BabbleParser.ClassExpressionContext c && c.classBlock == body
                || owner instanceof BabbleParser.ObjectExpressionContext o && o.createBlock == body) {
            return def;
        }
        return null;
    }

    /** Interface methods dispatch virtually, so a call to themselves may run an override (BIP024-D1). */
    private static boolean isInterfaceMember(BabbleParser.FunctionLiteralContext function) {
        return function.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == function
                && def.getParent() instanceof BabbleParser.BlockContext body
                && body.getParent() instanceof BabbleParser.InterfaceExpressionContext i && i.interfaceBlock == body;
    }
}
