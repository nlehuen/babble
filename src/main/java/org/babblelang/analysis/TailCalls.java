package org.babblelang.analysis;

import java.util.function.Predicate;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleParser;

/**
 * Finds the self tail calls of a file (docs/design/bip024_tail-calls.md, {@code BIP024-D1}–{@code
 * D4}) : calls of a function to itself whose value is the function's result, which both backends
 * run as a jump back to the start of the function instead of a nested call.
 *
 * <p>A call is a self tail call when it is in tail position (the last expression of the function's
 * block, through block expressions, {@code if} branches and {@code forever}, or the value of a
 * {@code return} in statement position), calls the function itself ({@code recurse(...)}, the
 * function's own non-reassigned {@code def} by name, or {@code this.m(...)} in method {@code m} of
 * a class or object), binds its arguments statically, and nothing between it and the function's
 * result checks or widens its value. Runs after {@link LoweringAnalyzer}, whose call targets,
 * bindings, checks and widenings it reads, and after {@link EffectChecker}, whose inferred {@code
 * nothrow} it reads.
 *
 * <p>It also rejects code that can never end and cannot throw, unless {@code forever} acknowledges
 * it : a function that can only end in a self tail call ({@code BIP024-D7}), and a {@code while
 * true} loop with no {@code break} or {@code return} ({@code BIP024-D8}).
 */
final class TailCalls {
    static final String FOREVER_HINT = ". If this is intended, wrap the expression with forever";

    private final ScopeAnalysis analysis;
    private final Predicate<ParserRuleContext> nothrow;
    private final Predicate<BabbleParser.WhileExpressionContext> loopNothrow;

    private TailCalls(
            ScopeAnalysis analysis,
            Predicate<ParserRuleContext> nothrow,
            Predicate<BabbleParser.WhileExpressionContext> loopNothrow) {
        this.analysis = analysis;
        this.nothrow = nothrow;
        this.loopNothrow = loopNothrow;
    }

    static void publish(
            ScopeAnalysis analysis,
            BabbleParser.FileContext file,
            Predicate<ParserRuleContext> nothrow,
            Predicate<BabbleParser.WhileExpressionContext> loopNothrow) {
        TailCalls tailCalls = new TailCalls(analysis, nothrow, loopNothrow);
        new BabbleBaseVisitor<Void>() {
            @Override
            public Void visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
                tailCalls.function(ctx);
                return super.visitFunctionLiteral(ctx);
            }

            @Override
            public Void visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
                tailCalls.loop(ctx);
                return super.visitWhileExpression(ctx);
            }
        }.visit(file);
    }

    private void function(BabbleParser.FunctionLiteralContext function) {
        if (isInterfaceMember(function) || analysis.calls().parameters(function) == null) {
            return;
        }
        block(function, function.functionBlock, true);
        BabbleParser.ExpressionContext tail = lastExpression(function.functionBlock);
        if (tail != null
                && alwaysRecurses(tail)
                && !returnsOtherwise(function.functionBlock)
                && !acknowledged(tail)
                && !acknowledgedReturn(function.functionBlock)
                && nothrow.test(function)) {
            String name = ScopeAnalysis.functionName(function);
            error(
                    "Recursion without a base case : "
                            + (name != null ? name : "function")
                            + FOREVER_HINT,
                    tail);
        }
    }

    /** {@code while true} with no way out and nothing that can throw never ends (BIP024-D8). */
    private void loop(BabbleParser.WhileExpressionContext loop) {
        if (isTrue(loop.test)
                && !exits(loop.whileBlock)
                && loopNothrow.test(loop)
                && !acknowledgedLoop(loop)) {
            error("Loop without an exit : while true" + FOREVER_HINT, loop);
        }
    }

    private void error(String message, ParserRuleContext at) {
        analysis.typeDiagnostic(
                message, at.getStart().getLine(), at.getStart().getCharPositionInLine() + 1);
    }

    private static BabbleParser.ExpressionContext lastExpression(BabbleParser.BlockContext block) {
        ParseTree last = null;
        for (ParseTree child : block.children) {
            if (child instanceof BabbleParser.ExpressionContext) {
                last = child;
            }
        }
        return last instanceof BabbleParser.ExpressionContext expression ? expression : null;
    }

    /** Whether an expression in tail position always ends in a self tail call (BIP024-D7). */
    private boolean alwaysRecurses(BabbleParser.ExpressionContext expression) {
        return switch (expression) {
            case BabbleParser.CallContext call -> analysis.calls().isSelfTailCall(call);
            case BabbleParser.BlockExpressionContext b -> {
                BabbleParser.ExpressionContext last = lastExpression(b.block());
                yield last != null && alwaysRecurses(last);
            }
            case BabbleParser.IfExpressionContext i -> alwaysRecurses(i.ifExpr());
            case BabbleParser.MatchExpressionContext m -> alwaysRecurses(m);
            case BabbleParser.ReturnExpressionContext r -> alwaysRecurses(r.expression());
            case BabbleParser.ForeverExpressionContext f -> alwaysRecurses(f.expression());
            default -> false;
        };
    }

    private boolean alwaysRecurses(BabbleParser.IfExprContext ifExpr) {
        BabbleParser.ExpressionContext then = lastExpression(ifExpr.thenBlock);
        if (then == null || !alwaysRecurses(then)) {
            return false;
        }
        if (ifExpr.elseIf != null) {
            return alwaysRecurses(ifExpr.elseIf);
        }
        BabbleParser.ExpressionContext otherwise =
                ifExpr.elseBlock != null ? lastExpression(ifExpr.elseBlock) : null;
        return otherwise != null && alwaysRecurses(otherwise);
    }

    private boolean alwaysRecurses(BabbleParser.MatchExpressionContext match) {
        for (BabbleParser.CaseClauseContext cc : match.caseClause()) {
            BabbleParser.ExpressionContext arm = lastExpression(cc.armBlock);
            if (arm == null || !alwaysRecurses(arm)) {
                return false;
            }
        }
        if (match.elseClause() != null) {
            BabbleParser.ExpressionContext otherwise = lastExpression(match.elseClause().elseBlock);
            return otherwise != null && alwaysRecurses(otherwise);
        }
        return !match.caseClause().isEmpty();
    }

    /** Whether {@code forever} wraps an expression on the tail paths of an expression. */
    private static boolean acknowledged(BabbleParser.ExpressionContext expression) {
        return switch (expression) {
            case BabbleParser.ForeverExpressionContext f -> true;
            case BabbleParser.BlockExpressionContext b -> {
                BabbleParser.ExpressionContext last = lastExpression(b.block());
                yield last != null && acknowledged(last);
            }
            case BabbleParser.IfExpressionContext i -> acknowledged(i.ifExpr());
            case BabbleParser.MatchExpressionContext m -> acknowledged(m);
            case BabbleParser.ReturnExpressionContext r -> acknowledged(r.expression());
            default -> false;
        };
    }

    private static boolean acknowledged(BabbleParser.IfExprContext ifExpr) {
        BabbleParser.ExpressionContext then = lastExpression(ifExpr.thenBlock);
        if (then != null && acknowledged(then)) {
            return true;
        }
        if (ifExpr.elseIf != null) {
            return acknowledged(ifExpr.elseIf);
        }
        BabbleParser.ExpressionContext otherwise =
                ifExpr.elseBlock != null ? lastExpression(ifExpr.elseBlock) : null;
        return otherwise != null && acknowledged(otherwise);
    }

    private static boolean acknowledged(BabbleParser.MatchExpressionContext match) {
        for (BabbleParser.CaseClauseContext cc : match.caseClause()) {
            BabbleParser.ExpressionContext arm = lastExpression(cc.armBlock);
            if (arm != null && acknowledged(arm)) {
                return true;
            }
        }
        if (match.elseClause() != null) {
            BabbleParser.ExpressionContext otherwise = lastExpression(match.elseClause().elseBlock);
            return otherwise != null && acknowledged(otherwise);
        }
        return false;
    }

    /**
     * Whether a {@code return} of the function's own body acknowledges its value with {@code
     * forever}.
     */
    private static boolean acknowledgedReturn(ParseTree tree) {
        if (isNested(tree)) {
            return false;
        }
        if (tree instanceof BabbleParser.ReturnExpressionContext r
                && acknowledged(r.expression())) {
            return true;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (acknowledgedReturn(tree.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the function's own body holds a {@code return} that may end it with a value. */
    private boolean returnsOtherwise(ParseTree tree) {
        if (isNested(tree)) {
            return false;
        }
        if (tree instanceof BabbleParser.ReturnExpressionContext r
                && !alwaysRecurses(r.expression())) {
            return true;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (returnsOtherwise(tree.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    /** A function, class, object or interface inside the code being looked at : its own code. */
    private static boolean isNested(ParseTree tree) {
        return tree instanceof BabbleParser.FunctionLiteralContext
                || tree instanceof BabbleParser.ClassExpressionContext
                || tree instanceof BabbleParser.ObjectExpressionContext
                || tree instanceof BabbleParser.InterfaceExpressionContext;
    }

    private static boolean isTrue(BabbleParser.ExpressionContext test) {
        while (test instanceof BabbleParser.BlockExpressionContext b
                && b.block().expression().size() == 1) {
            test = b.block().expression(0);
        }
        return test instanceof BabbleParser.BooleanContext && "true".equals(test.getText());
    }

    /**
     * Whether a loop body may leave the loop : a {@code break} of this loop, or a {@code return}.
     */
    private static boolean exits(ParseTree tree) {
        return exits(tree, false);
    }

    private static boolean exits(ParseTree tree, boolean innerLoop) {
        if (isNested(tree)) {
            return false;
        }
        if (tree instanceof BabbleParser.ReturnExpressionContext
                || !innerLoop && tree instanceof BabbleParser.BreakExpressionContext) {
            return true;
        }
        boolean inner =
                innerLoop
                        || tree instanceof BabbleParser.WhileExpressionContext
                        || tree instanceof BabbleParser.ForExpressionContext;
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (exits(tree.getChild(i), inner)) {
                return true;
            }
        }
        return false;
    }

    /** {@code forever while true then (...)}, possibly in parentheses. */
    private static boolean acknowledgedLoop(BabbleParser.WhileExpressionContext loop) {
        ParserRuleContext node = loop;
        while (true) {
            ParserRuleContext parent = node.getParent();
            if (parent instanceof BabbleParser.ForeverExpressionContext) {
                return true;
            }
            if (parent instanceof BabbleParser.BlockContext b
                    && b.expression().size() == 1
                    && b.getParent() instanceof BabbleParser.BlockExpressionContext be) {
                node = be;
                continue;
            }
            return false;
        }
    }

    /**
     * The statements of a block in statement position, the last one in tail position when the block
     * is.
     */
    private void block(
            BabbleParser.FunctionLiteralContext function,
            BabbleParser.BlockContext block,
            boolean tail) {
        block(function, block, tail, false);
    }

    private void block(
            BabbleParser.FunctionLiteralContext function,
            BabbleParser.BlockContext block,
            boolean tail,
            boolean inHandler) {
        ParseTree last = null;
        for (ParseTree child : block.children) {
            if (child instanceof BabbleParser.ExpressionContext) {
                last = child;
            }
        }
        for (BabbleParser.ExpressionContext statement : block.expression()) {
            statement(function, statement, tail && statement == last, inHandler);
        }
    }

    /** An expression in statement position : nothing waits on the operand stack below its value. */
    private void statement(
            BabbleParser.FunctionLiteralContext function,
            BabbleParser.ExpressionContext expression,
            boolean tail,
            boolean inHandler) {
        switch (expression) {
            case BabbleParser.BlockExpressionContext b ->
                    block(function, b.block(), tail, inHandler);
            case BabbleParser.IfExpressionContext i ->
                    ifExpr(function, i.ifExpr(), tail, inHandler);
            case BabbleParser.MatchExpressionContext m -> {
                for (BabbleParser.CaseClauseContext cc : m.caseClause()) {
                    block(function, cc.armBlock, tail, inHandler);
                }
                if (m.elseClause() != null) {
                    block(function, m.elseClause().elseBlock, tail, inHandler);
                }
            }
            case BabbleParser.WhileExpressionContext w ->
                    block(function, w.whileBlock, false, inHandler);
            case BabbleParser.ForExpressionContext f ->
                    block(function, f.forBlock, false, inHandler);
            case BabbleParser.TryExpressionContext t -> {
                // tryBlock runs under catch/finally handlers -> never tail, and return inside it is
                // not tail.
                block(function, t.tryBlock, false, true);
                boolean hasFinally = t.finallyBlock != null;
                for (BabbleParser.CatchClauseContext cc : t.catchClause()) {
                    block(function, cc.catchBlock, tail && !hasFinally, inHandler || hasFinally);
                }
                if (hasFinally) {
                    block(function, t.finallyBlock, false, true);
                }
            }
            case BabbleParser.ReturnExpressionContext r ->
                    statement(function, r.expression(), !inHandler, inHandler);
            case BabbleParser.ForeverExpressionContext f ->
                    statement(function, f.expression(), tail, inHandler);
            case BabbleParser.CallContext call -> {
                if (tail && isSelfTailCall(function, call)) {
                    analysis.calls().setSelfTailCall(call);
                }
            }
            default -> {}
        }
    }

    private void ifExpr(
            BabbleParser.FunctionLiteralContext function,
            BabbleParser.IfExprContext ifExpr,
            boolean tail,
            boolean inHandler) {
        block(function, ifExpr.thenBlock, tail, inHandler);
        if (ifExpr.elseIf != null) {
            ifExpr(function, ifExpr.elseIf, tail, inHandler);
        } else if (ifExpr.elseBlock != null) {
            block(function, ifExpr.elseBlock, tail, inHandler);
        }
    }

    private boolean isSelfTailCall(
            BabbleParser.FunctionLiteralContext function, BabbleParser.CallContext call) {
        if (!callsItself(function, call) || !bindsStatically(function, call)) {
            return false;
        }
        // Nothing between the call and the function's result may check, widen, or adapt the value
        // (BIP024-D4).
        for (ParserRuleContext node = call;
                node != null && node != function.functionBlock;
                node = node.getParent()) {
            if (analysis.lowering().numericWideningRank(node) >= 0
                    || analysis.lowering().checkedType(node) != null
                    || analysis.lowering().javaSamTarget(node) != null) {
                return false;
            }
        }
        return analysis.lowering().javaSamTarget(function.functionBlock) == null;
    }

    private boolean callsItself(
            BabbleParser.FunctionLiteralContext function, BabbleParser.CallContext call) {
        BabbleParser.ExpressionContext callee = call.expression();
        if (callee instanceof BabbleParser.RecurseContext) {
            return true;
        }
        if (analysis.calls().babbleCallFunction(call) == function) {
            return true;
        }
        if (callee instanceof BabbleParser.SelectorContext selector
                && selector.safe == null
                && selector.expression() instanceof BabbleParser.ThisExpressionContext) {
            BabbleParser.DefExpressionContext def = methodDef(function);
            return def != null && def.name.getText().equals(ScopeAnalysis.selectorName(selector));
        }
        return false;
    }

    /**
     * The arguments are bound at compile time, or positional and as many as the parameters ;
     * neither a rest parameter nor a spread argument ({@code BIP021}) is involved.
     */
    private boolean bindsStatically(
            BabbleParser.FunctionLiteralContext function, BabbleParser.CallContext call) {
        var arguments = call.callParameters().callParameter();
        if (analysis.calls().restIndex(function) >= 0
                || arguments.stream().anyMatch(a -> a.spread != null)) {
            return false;
        }
        if (analysis.calls().argumentBinding(call) != null) {
            return true;
        }
        for (BabbleParser.CallParameterContext argument : arguments) {
            if (argument.memberName() != null) {
                return false;
            }
        }
        return arguments.size() == analysis.calls().parameters(function).length;
    }

    /** The {@code def} of a method of a class or object whose value is this function, or null. */
    private static BabbleParser.DefExpressionContext methodDef(
            BabbleParser.FunctionLiteralContext function) {
        if (!(function.getParent() instanceof BabbleParser.DefExpressionContext def)
                || ScopeAnalysis.isVar(def)
                || def.value != function
                || !(def.getParent() instanceof BabbleParser.BlockContext body)) {
            return null;
        }
        ParserRuleContext owner = body.getParent();
        if (owner instanceof BabbleParser.ClassExpressionContext c && c.classBlock == body
                || owner instanceof BabbleParser.ObjectExpressionContext o
                        && o.createBlock == body) {
            return def;
        }
        return null;
    }

    /**
     * Interface methods dispatch virtually, so a call to themselves may run an override
     * (BIP024-D1).
     */
    private static boolean isInterfaceMember(BabbleParser.FunctionLiteralContext function) {
        return function.getParent() instanceof BabbleParser.DefExpressionContext def
                && def.value == function
                && def.getParent() instanceof BabbleParser.BlockContext body
                && body.getParent() instanceof BabbleParser.InterfaceExpressionContext i
                && i.interfaceBlock == body;
    }
}
