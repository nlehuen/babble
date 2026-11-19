package org.babblelang.engine.impl;

import org.babblelang.runtime.Namespace;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.babblelang.analysis.BlockScopes;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.runtime.BabbleException;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.Arguments;
import org.babblelang.runtime.BabbleClass;
import org.babblelang.runtime.BabbleInstance;
import org.babblelang.runtime.BabbleInterface;
import org.babblelang.runtime.BabbleType;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.Calls;
import org.babblelang.runtime.ClassBody;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.Classes;
import org.babblelang.runtime.Members;
import org.babblelang.runtime.OpSite;
import org.babblelang.runtime.Operators;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Supers;
import org.babblelang.runtime.Truth;
import org.babblelang.runtime.Types;
import org.babblelang.runtime.Values;

import javax.script.SimpleScriptContext;
import java.util.List;

public class Interpreter extends BabbleBaseVisitor<Object> {
    private final ScriptRuntime runtime;
    private final String sourceName;
    private ScopeAnalysis scopes;
    private Namespace namespace;
    private ParserRuleContext last;

    // Static descriptions of the sites, built the first time each one runs.
    private final ParseTreeProperty<OpSite> opSites = new ParseTreeProperty<>();
    private final ParseTreeProperty<CallSite> callSites = new ParseTreeProperty<>();
    private final ParseTreeProperty<String[]> callNames = new ParseTreeProperty<>();

    /**
     * An interpreter on its own, outside of the engine : the block scope analysis runs
     * when a file is visited, and output goes to a default ScriptContext.
     */
    public Interpreter(Namespace root) {
        this(new ScriptRuntime(root, new SimpleScriptContext().getWriter(), null), null, "<input>");
    }

    public Interpreter(ScriptRuntime runtime, ScopeAnalysis scopes, String sourceName) {
        this.runtime = runtime;
        this.scopes = scopes;
        this.sourceName = sourceName;
        this.namespace = runtime.root();
    }

    public ParserRuleContext getLast() {
        return last;
    }

    ScopeAnalysis scopes() {
        return scopes;
    }

    /** Positions the interpreter at a node, for the line of an error raised now. */
    void at(ParserRuleContext ctx) {
        last = ctx;
    }

    /**
     * Evaluates an expression with the interpreter temporarily positioned in the given
     * namespace, restoring the current one afterwards. Used to run a function body in the
     * namespace of its call.
     */
    Object evaluateIn(Namespace scope, ParserRuleContext expression) {
        Namespace before = namespace;
        namespace = scope;
        try {
            return visit(expression);
        } finally {
            namespace = before;
        }
    }

    /**
     * Evaluates a parameter default value in the namespace of the call. An error later in
     * the binding is about the call, so it is still reported at the call's line.
     */
    Object evaluateDefault(Namespace scope, BabbleParser.ExpressionContext expression) {
        ParserRuleContext call = last;
        Object value = evaluateIn(scope, expression);
        last = call;
        return value;
    }

    @Override
    public Object visitFile(BabbleParser.FileContext ctx) {
        last = ctx;
        if (scopes == null) {
            scopes = BlockScopes.analyze(ctx);
        }
        namespace.declare(scopes.declared(ctx));
        Object result = null;
        try {
            for (BabbleParser.ExpressionContext statement : ctx.expression()) {
                result = visit(statement);
            }
        } catch (ReturnException re) {
            // The analysis rejects a return outside of a function body before anything
            // runs, so this is only a safety net.
            throw new BabbleException("return outside of a function");
        }
        return result;
    }

    // ---------------------------------------------------------------- classes

    /**
     * A class expression evaluates to a new class value, then runs its static block, if
     * any, with {@code this} as the class value.
     */
    @Override
    public Object visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
        last = ctx;
        Object[] interfaces = types(ctx.typeList());
        BabbleClass babbleClass = Classes.newClass(scopes.shape(ctx), namespace, new InterpretedClassBody(ctx), interfaces);
        if (ctx.staticBlock != null) {
            runIn(Classes.staticScope(namespace, babbleClass), ctx.staticBlock.expression());
        }
        last = ctx;
        return babbleClass;
    }

    /** An object literal is the single instance of an anonymous class. */
    @Override
    public Object visitObjectExpression(BabbleParser.ObjectExpressionContext ctx) {
        last = ctx;
        Object[] interfaces = types(ctx.typeList());
        BabbleInstance object = BabbleClass.object(scopes.shape(ctx), namespace, new InterpretedClassBody(ctx), interfaces, runtime);
        last = ctx;
        return object;
    }

    @Override
    public Object visitInterfaceExpression(BabbleParser.InterfaceExpressionContext ctx) {
        last = ctx;
        BabbleInterface babbleInterface = Classes.newInterface(scopes.shape(ctx), types(ctx.typeList()));
        runIn(Classes.interfaceScope(namespace, babbleInterface), ctx.interfaceBlock.expression());
        if (ctx.staticBlock != null) {
            runIn(Classes.staticScope(namespace, babbleInterface), ctx.staticBlock.expression());
        }
        last = ctx;
        return babbleInterface;
    }

    private void runIn(Namespace scope, List<BabbleParser.ExpressionContext> statements) {
        Namespace before = namespace;
        namespace = scope;
        try {
            visitStatements(statements);
        } finally {
            namespace = before;
        }
    }

    private Object[] types(BabbleParser.TypeListContext typeList) {
        if (typeList == null) {
            return new Object[0];
        }
        Object[] result = new Object[typeList.type().size()];
        for (int i = 0; i < result.length; i++) {
            BabbleParser.TypeContext type = typeList.type(i);
            result[i] = Classes.type(type(type), type.getText());
        }
        return result;
    }

    /**
     * The value of a type the analysis resolved statically : a name, then members. It is
     * evaluated like the equivalent expression.
     */
    private Object type(BabbleParser.TypeContext type) {
        List<String> ids = org.babblelang.analysis.ScopeAnalysis.simpleTypePath(org.babblelang.analysis.ScopeAnalysis.simpleType(type));
        Object value = namespace.lookup(ids.get(0));
        StringBuilder text = new StringBuilder(ids.get(0));
        for (int i = 1; i < ids.size(); i++) {
            value = Members.select(value, ids.get(i), text.toString());
            text.append('.').append(ids.get(i));
        }
        return value;
    }

    /**
     * The constructor of an interpreted class : binds the arguments, header members going
     * straight into the instance, then runs the body.
     */
    private final class InterpretedClassBody implements ClassBody {
        private final ParserRuleContext ctx;

        InterpretedClassBody(ParserRuleContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void construct(BabbleClass babbleClass, BabbleInstance instance, ScriptRuntime rt, Object[] args, String[] names, CallSite site) {
            Namespace scope = Classes.constructorScope(babbleClass, instance);
            ClassShape shape = babbleClass.shape();
            String[] parameters = shape.parameters();
            Arguments.Binder binder = Arguments.binder(parameters, args, names);
            for (int i = 0; i < parameters.length; i++) {
                Object value = binder.take(i);
                if (value == Arguments.MISSING) {
                    BabbleParser.ExpressionContext defaultValue = ((BabbleParser.ClassExpressionContext) ctx)
                            .parametersDeclaration().parameterDeclaration(i).defaultValue;
                    if (defaultValue == null) {
                        throw binder.missing(i);
                    }
                    value = evaluateDefault(scope, defaultValue);
                }
                value = widen(((BabbleParser.ClassExpressionContext) ctx).parametersDeclaration().parameterDeclaration(i), value);
                int member = shape.parameterMember(i);
                if (member >= 0) {
                    instance.initMember(member, value);
                } else {
                    scope.defineParameter(parameters[i], value);
                }
            }
            binder.rejectLeftovers();
            BabbleParser.BlockContext body = ctx instanceof BabbleParser.ClassExpressionContext c ? c.classBlock
                    : ((BabbleParser.ObjectExpressionContext) ctx).createBlock;
            runIn(scope, body.expression());
        }
    }

    @Override
    public Object visitThisExpression(BabbleParser.ThisExpressionContext ctx) {
        last = ctx;
        return namespace.lookup("this");
    }

    @Override
    public Object visitIsExpression(BabbleParser.IsExpressionContext ctx) {
        last = ctx;
        Object value = visit(ctx.left);
        String builtin = scopes.isBuiltin(ctx);
        if (builtin != null) {
            last = ctx;
            return Values.isBuiltin(value, builtin);
        }
        Object type = visit(ctx.right);
        last = ctx;
        return Values.is(value, type, ctx.right.getText());
    }

    @Override
    public Object visitAsExpression(BabbleParser.AsExpressionContext ctx) {
        last = ctx;
        Object value = visit(ctx.left);
        last = ctx;
        return Types.check(value, scopes.checkedType(ctx));
    }

    @Override
    public Object visitSuperExpression(BabbleParser.SuperExpressionContext ctx) {
        // The analysis only lets super appear as the callee of a call.
        throw new IllegalStateException("super must be called directly");
    }

    @Override
    public Object visitDefExpression(BabbleParser.DefExpressionContext ctx) {
        last = ctx;
        String id = ctx.name.getText();
        Object value = null;
        if (ctx.value != null) {
            value = visit(ctx.value);
        }
        last = ctx;
        value = widen(ctx, value);
        ScopeAnalysis.DefTarget target = scopes.defTarget(ctx);
        return switch (target.kind()) {
            case LEXICAL -> namespace.initialize(id, value);
            case MEMBER -> Classes.initMember(namespace, target.index(), value);
            case STATIC -> Classes.initStatic(namespace, target.index(), value);
            case DEFAULT -> Classes.setDefault(namespace, target.index(), value);
            case REQUIRED -> null;
        };
    }

    @Override
    public Object visitReturnExpression(BabbleParser.ReturnExpressionContext ctx) {
        last = ctx;
        Object value = visit(ctx.expression());
        last = ctx;
        value = widen(ctx, value);
        throw new ReturnException(value);
    }

    /**
     * A block that defines names gets a namespace of its own. Function and
     * object bodies never do : their owner already provides it.
     */
    @Override
    public Object visitBlock(BabbleParser.BlockContext ctx) {
        last = ctx;
        if (!scopes.opensScope(ctx)) {
            return visitStatements(ctx.expression());
        }
        Namespace before = namespace;
        namespace = namespace.child(scopes.declared(ctx));
        try {
            return visitStatements(ctx.expression());
        } finally {
            namespace = before;
        }
    }

    private Object visitStatements(List<BabbleParser.ExpressionContext> statements) {
        Object result = null;
        for (BabbleParser.ExpressionContext statement : statements) {
            result = visit(statement);
        }
        return result;
    }

    @Override
    public Object visitSelector(BabbleParser.SelectorContext ctx) {
        last = ctx;
        String name = ScopeAnalysis.selectorName(ctx);
        BabbleParser.ExpressionContext expression = ctx.expression();
        if (expression == null) {
            return namespace.lookup(name);
        }
        if (ctx.safe != null) {
            Object value = visit(expression);
            last = ctx;
            if (value == null) {
                return null;
            }
            Object result = Members.select(value, name, expression.getText());
            last = ctx;
            return Types.check(result, scopes.checkedType(ctx));
        }
        Object result;
        if (expression instanceof BabbleParser.ThisExpressionContext) {
            result = Members.selectOwn(namespace.lookup("this"), name);
        } else {
            Object value = visit(expression);
            last = ctx;
            result = Members.select(value, name, expression.getText());
        }
        last = ctx;
        return Types.check(result, scopes.checkedType(ctx));
    }

    @Override
    public Object visitNull(BabbleParser.NullContext ctx) {
        last = ctx;
        return null;
    }

    @Override
    public Object visitBoolean(BabbleParser.BooleanContext ctx) {
        last = ctx;
        return Boolean.parseBoolean(ctx.getText());
    }

    @Override
    public Object visitBinaryOp(BabbleParser.BinaryOpContext ctx) {
        last = ctx;
        Object a = visit(ctx.left);
        Object b = visit(ctx.right);
        last = ctx;

        String method = Operators.method(ctx.op.getText());
        if (method != null && a instanceof BabbleInstance) {
            // An operator method : a + b is a.plus(b).
            b = widen(ctx.right, b);
            return operatorCall(a, method, ctx.left.getText(), ctx.right.getText(), b, ctx);
        }

        OpSite site = opSites.get(ctx);
        if (site == null) {
            site = new OpSite(ctx.left.getText(), ctx.right.getText());
            opSites.put(ctx, site);
        }

        return switch (ctx.op.getType()) {
            case BabbleLexer.MUL -> Operators.mul(a, b, site);
            case BabbleLexer.DIV -> Operators.div(a, b, site);
            case BabbleLexer.PLUS -> Operators.add(a, b, site);
            case BabbleLexer.MINUS -> Operators.sub(a, b, site);
            case BabbleLexer.LT -> Operators.lt(a, b, site);
            case BabbleLexer.LTE -> Operators.le(a, b, site);
            case BabbleLexer.EQ -> Operators.eq(a, b, site);
            case BabbleLexer.NEQ -> Operators.ne(a, b, site);
            case BabbleLexer.GTE -> Operators.ge(a, b, site);
            case BabbleLexer.GT -> Operators.gt(a, b, site);
            default -> throw new IllegalStateException("Bad op : " + ctx.op.getText());
        };
    }

    @Override
    public Object visitBooleanOp(BabbleParser.BooleanOpContext ctx) {
        last = ctx;
        boolean a = Truth.test(visit(ctx.left));
        if (ctx.op.getType() == BabbleLexer.OR) {
            return a || Truth.test(visit(ctx.right));
        } else {
            return a && Truth.test(visit(ctx.right));
        }
    }

    @Override
    public Object visitBooleanNot(BabbleParser.BooleanNotContext ctx) {
        last = ctx;
        return !Truth.test(visit(ctx.expression()));
    }

    @Override
    public Object visitNegate(BabbleParser.NegateContext ctx) {
        Object resolved = scopes.resolvedLiteral(ctx);
        if (resolved != null) {
            last = ctx;
            return resolved;
        }
        if (ctx.operand instanceof BabbleParser.IntegerContext literal) {
            // A literal : "-2147483648" is an int, though "2147483648" isn't.
            last = ctx;
            return Integer.parseInt("-" + literal.getText());
        }
        Object value = visit(ctx.operand);
        last = ctx;
        if (value instanceof BabbleInstance) {
            return operatorCall(value, Operators.NEGATE, ctx.operand.getText(), null, null, ctx);
        }
        return Operators.negate(value, ctx.operand.getText());
    }

    /** {@code a.method(b)}, or {@code a.method()} without an argument, for an operator. */
    private Object operatorCall(Object a, String method, String receiver, String argument, Object b, ParserRuleContext ctx) {
        CallSite site = operatorSite(ctx, receiver, method, argument, sourceName);
        Callable callable = Calls.callable(Members.select(a, method, receiver), site);
        last = ctx;
        return callable.call(runtime, argument == null ? Arguments.NONE : new Object[]{b}, null, site);
    }

    /**
     * The call an operator on a Babble instance makes, for its messages : {@code a + b} is
     * {@code a.plus(b)}, given the source texts of the receiver and the argument (null for
     * {@code -a}, {@code a.neg()}).
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
        return ctx.namespace == null ? name : ctx.namespace.getText() + "." + name;
    }

    /**
     * {@code x op= v} : {@code x op v}, given the target's current value. An operator method on
     * a Babble instance, as for {@code a + b}.
     */
    private Object compound(BabbleParser.AssignExpressionContext ctx, String operator, Object current, Object value) {
        last = ctx;
        String target = targetText(ctx);
        String method = Operators.method(operator);
        if (current instanceof BabbleInstance) {
            return operatorCall(current, method, target, ctx.value.getText(), value, ctx);
        }
        OpSite site = opSites.get(ctx);
        if (site == null) {
            site = new OpSite(target, ctx.value.getText());
            opSites.put(ctx, site);
        }
        return Operators.arithmetic(operator, current, value, site);
    }

    @Override
    public Object visitIfExpression(BabbleParser.IfExpressionContext ctx) {
        last = ctx;
        if (Truth.test(visit(ctx.test))) {
            last = ctx;
            Object value = visit(ctx.thenBlock);
            return widen(ctx.thenBlock, value);
        } else {
            last = ctx;
            if (ctx.elseBlock != null) {
                Object value = visit(ctx.elseBlock);
                return widen(ctx.elseBlock, value);
            } else if (ctx.elseIf != null) {
                Object value = visit(ctx.elseIf);
                return widen(ctx.elseIf, value);
            } else {
                return null;
            }
        }
    }

    @Override
    public Object visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
        last = ctx;
        Object result = null;
        // The body block opens a fresh scope on every iteration.
        while (Truth.test(visit(ctx.test))) {
            result = widen(ctx.whileBlock, visit(ctx.whileBlock));
        }
        return result;
    }

    @Override
    public Object visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
        last = ctx;
        String name = ScopeAnalysis.assignName(ctx);
        // For x op= v, the target's value is read before v is evaluated, as in x = x op v.
        String operator = Operators.compound(ctx.op.getText());
        if (ctx.namespace == null) {
            Object current = operator == null ? null : namespace.lookup(name);
            Object value = visit(ctx.value);
            if (operator != null) {
                value = compound(ctx, operator, current, value);
            }
            last = ctx;
            value = widen(ctx, value);
            return namespace.assign(name, value);
        }
        if (ctx.namespace instanceof BabbleParser.ThisExpressionContext) {
            Object thiz = namespace.lookup("this");
            Object current = operator == null ? null : Members.selectOwn(thiz, name);
            Object value = visit(ctx.value);
            if (operator != null) {
                value = compound(ctx, operator, current, value);
            }
            last = ctx;
            value = widen(ctx, value);
            return Members.assignOwn(thiz, value, name);
        }
        // The target is checked before the value is evaluated.
        Object target = visit(ctx.namespace);
        last = ctx;
        Object scope = Members.assignable(target, ctx.namespace.getText());
        Object current = operator == null ? null : Members.select(scope, name, ctx.namespace.getText());
        Object value = visit(ctx.value);
        if (operator != null) {
            value = compound(ctx, operator, current, value);
        }
        last = ctx;
        value = widen(ctx, value);
        return Members.assign(scope, value, name);
    }

    @Override
    public Object visitLongLiteral(BabbleParser.LongLiteralContext ctx) {
        last = ctx;
        Object resolved = scopes.resolvedLiteral(ctx);
        if (resolved != null) {
            return resolved;
        }
        String text = ctx.getText();
        return Long.parseLong(text.substring(0, text.length() - 1));
    }

    @Override
    public Object visitInteger(BabbleParser.IntegerContext ctx) {
        last = ctx;
        Object resolved = scopes.resolvedLiteral(ctx);
        if (resolved != null) {
            return resolved;
        }
        return Integer.parseInt(ctx.getText());
    }

    @Override
    public Object visitFloatLiteral(BabbleParser.FloatLiteralContext ctx) {
        last = ctx;
        Object resolved = scopes.resolvedLiteral(ctx);
        if (resolved != null) {
            return resolved;
        }
        String text = ctx.getText();
        return Float.parseFloat(text.substring(0, text.length() - 1));
    }

    @Override
    public Object visitDouble(BabbleParser.DoubleContext ctx) {
        last = ctx;
        Object resolved = scopes.resolvedLiteral(ctx);
        if (resolved != null) {
            return resolved;
        }
        return Double.parseDouble(ctx.getText());
    }

    @Override
    public Object visitCharLiteral(BabbleParser.CharLiteralContext ctx) {
        last = ctx;
        return unescapeChar(ctx.CHAR_LIT().getText());
    }

    @Override
    public Object visitString(BabbleParser.StringContext ctx) {
        last = ctx;
        return unescape(ctx.STRING().getText());
    }

    @Override
    public Object visitNonNullAssertion(BabbleParser.NonNullAssertionContext ctx) {
        last = ctx;
        Object value = visit(ctx.expression());
        last = ctx;
        String expected = scopes.nonNullAssertionTarget(ctx);
        if (expected != null) {
            return org.babblelang.runtime.JavaTypes.checkNotNull(value, expected);
        }
        return value;
    }

    @Override
    public Object visitCoreScalarRef(BabbleParser.CoreScalarRefContext ctx) {
        last = ctx;
        return namespace.lookup(ctx.coreScalarKeyword().getText());
    }

    @Override
    public Object visitTypeApplyCall(BabbleParser.TypeApplyCallContext ctx) {
        last = ctx;
        Object callee = visit(ctx.expression());
        last = ctx;
        if (!scopes.isTypeApplyCall(ctx)) {
            // Curried value call: f(a)(b)
            List<BabbleParser.TypeContext> firstArgs = ctx.typeArguments().type();
            Object[] firstValues = new Object[firstArgs.size()];
            for (int i = 0; i < firstArgs.size(); i++) {
                firstValues[i] = type(firstArgs.get(i));
            }
            CallSite firstSite = new CallSite(sourceName, ctx.getStart().getLine(), ctx.expression().getText(), new String[firstArgs.size()]);
            callee = Calls.callable(callee, firstSite).call(runtime, firstValues, null, firstSite);
            last = ctx;
        }
        List<BabbleParser.CallParameterContext> params = ctx.callParameters().callParameter();
        Object[] args = params.isEmpty() ? Arguments.NONE : new Object[params.size()];
        String[] names = null;
        for (int i = 0; i < params.size(); i++) {
            args[i] = visit(params.get(i).expression());
            String name = ScopeAnalysis.callParamName(params.get(i));
            if (name != null) {
                if (names == null) {
                    names = new String[params.size()];
                }
                names[i] = name;
            }
        }
        last = ctx;
        String[] argTexts = new String[params.size()];
        for (int i = 0; i < params.size(); i++) {
            argTexts[i] = params.get(i).expression().getText();
        }
        CallSite site = new CallSite(sourceName, ctx.getStart().getLine(), ctx.expression().getText(), argTexts);
        return Types.check(Calls.callable(callee, site).call(runtime, args, names, site), scopes.checkedType(ctx));
    }

    /**
     * The value of a string literal, given its source text with the quotes.
     */
    public static String unescape(String literal) {
        String body = literal.substring(1, literal.length() - 1);
        if (body.indexOf('\\') < 0) {
            return body;
        }
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(++i);
                switch (next) {
                    case 'b' -> out.append('\b');
                    case 't' -> out.append('\t');
                    case 'n' -> out.append('\n');
                    case 'f' -> out.append('\f');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\'' -> out.append('\'');
                    case '\\' -> out.append('\\');
                    case '0' -> out.append('\0');
                    case 'u' -> {
                        if (i + 4 < body.length()) {
                            out.append((char) Integer.parseInt(body.substring(i + 1, i + 5), 16));
                            i += 4;
                        } else {
                            out.append('u');
                        }
                    }
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The value of a character literal, given its source text with the single quotes. */
    public static char unescapeChar(String literal) {
        String decoded = unescape(literal);
        return decoded.charAt(0);
    }

    @Override
    public Object visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
        last = ctx;
        return new Function(ctx, namespace, this);
    }

    @Override
    public Object visitCall(BabbleParser.CallContext ctx) {
        last = ctx;
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.expression() instanceof BabbleParser.SuperExpressionContext superExpression) {
            return Types.check(superCall(ctx, selector, superExpression), scopes.checkedType(ctx));
        }
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector && selector.safe != null) {
            BabbleParser.ExpressionContext receiverExpr = selector.expression();
            Object receiver = visit(receiverExpr);
            last = selector;
            if (receiver == null) {
                return null;
            }
            Object callee = Types.check(Members.select(receiver, ScopeAnalysis.selectorName(selector), receiverExpr.getText()),
                    scopes.checkedType(selector));
            last = ctx;
            CallSite site = callSite(ctx);
            var callable = Calls.callable(callee, site);
            Object[] args = arguments(ctx);
            last = ctx;
            return Types.check(callable.call(runtime, args, callNames.get(ctx), site), scopes.checkedType(ctx));
        }
        ScopeAnalysis.JavaCallTarget javaCall = scopes.javaCallTarget(ctx);
        if (javaCall != null && !javaCall.isStatic() && javaCall.unwrapReceiver()
                && javaCall.method().getDeclaringClass().isInterface()
                && ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.expression() != null) {
            BabbleParser.ExpressionContext receiverExpr = selector.expression();
            Object receiver = visit(receiverExpr);
            last = selector;
            Object target = Values.toJava(receiver, javaCall.method().getDeclaringClass(), runtime);
            Object callee = Types.check(Members.select(target, ScopeAnalysis.selectorName(selector), receiverExpr.getText()),
                    scopes.checkedType(selector));
            last = ctx;
            CallSite site = callSite(ctx);
            var callable = Calls.callable(callee, site);
            Object[] args = arguments(ctx);
            last = ctx;
            return Types.check(callable.call(runtime, args, callNames.get(ctx), site), scopes.checkedType(ctx));
        }
        Object callee = visit(ctx.expression());
        last = ctx;
        CallSite site = callSite(ctx);
        var callable = Calls.callable(callee, site);
        Object[] args = arguments(ctx);
        last = ctx;
        return Types.check(callable.call(runtime, args, callNames.get(ctx), site), scopes.checkedType(ctx));
    }

    /** {@code super.m(args)} : the default method the analysis resolved, called on {@code this}. */
    private Object superCall(BabbleParser.CallContext ctx, BabbleParser.SelectorContext selector, BabbleParser.SuperExpressionContext superExpression) {
        Object thiz = namespace.lookup("this");
        Object type = Classes.type(type(scopes.superTarget(superExpression)), superExpression.getText());
        CallSite site = callSite(ctx);
        Object[] args = arguments(ctx);
        last = ctx;
        return Supers.call(thiz, type, ScopeAnalysis.selectorName(selector), runtime, args, callNames.get(ctx), site);
    }

    private Object widen(ParserRuleContext ctx, Object value) {
        int rank = scopes.numericWideningRank(ctx);
        return rank >= 0 ? Values.widenNumeric(value, rank) : value;
    }

    private Object[] arguments(BabbleParser.CallContext ctx) {
        List<BabbleParser.CallParameterContext> parameters = ctx.callParameters().callParameter();
        Object[] args = parameters.isEmpty() ? Arguments.NONE : new Object[parameters.size()];
        for (int i = 0; i < args.length; i++) {
            BabbleParser.ExpressionContext expression = parameters.get(i).expression();
            Object value = visit(expression);
            args[i] = widen(expression, value);
        }
        return args;
    }

    private CallSite callSite(BabbleParser.CallContext ctx) {
        CallSite site = callSites.get(ctx);
        if (site == null) {
            site = callSite(ctx, sourceName);
            callSites.put(ctx, site);
            callNames.put(ctx, callNames(ctx));
        }
        return site;
    }

    /**
     * The static description of a call, as both modes build it.
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
            String name = org.babblelang.analysis.ScopeAnalysis.callParamName(parameters.get(i));
            if (name != null) {
                if (names == null) {
                    names = new String[parameters.size()];
                }
                names[i] = name;
            }
        }
        return names;
    }

    @Override
    public Object visitRecurse(BabbleParser.RecurseContext ctx) {
        last = ctx;
        return namespace.lookup("$recurse");
    }
}
