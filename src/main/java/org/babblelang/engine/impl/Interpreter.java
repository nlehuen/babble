package org.babblelang.engine.impl;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.babblelang.analysis.BlockScopes;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.engine.BabbleException;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.Arguments;
import org.babblelang.runtime.BabbleClass;
import org.babblelang.runtime.BabbleInstance;
import org.babblelang.runtime.BabbleInterface;
import org.babblelang.runtime.BabbleType;
import org.babblelang.runtime.CallSite;
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
        this(new ScriptRuntime(root, new SimpleScriptContext(), null), null, "<input>");
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

    @Override
    public Object visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
        last = ctx;
        Namespace before = namespace;
        namespace = namespace.enterPackage(ctx.name.getText(), scopes.declared(ctx));
        try {
            visitStatements(ctx.packageBlock.expression());
            last = ctx;
        } finally {
            // Restoring the saved namespace rather than calling leave() keeps the
            // interpreter consistent even when the body unwinds. "last" stays where
            // the failure happened, so the reported line still points at it.
            namespace = before;
        }
        return null;
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
        List<org.antlr.v4.runtime.tree.TerminalNode> ids = ((BabbleParser.SimpleTypeContext) type).ID();
        Object value = namespace.lookup(ids.get(0).getText());
        StringBuilder text = new StringBuilder(ids.get(0).getText());
        for (int i = 1; i < ids.size(); i++) {
            value = Members.select(value, ids.get(i).getText(), text.toString());
            text.append('.').append(ids.get(i).getText());
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
        Object type = visit(ctx.right);
        last = ctx;
        return Values.is(value, type, ctx.right.getText());
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
        throw new ReturnException(value);
    }

    /**
     * A block that defines names gets a namespace of its own. Function, package and
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
        String name = ctx.ID().getText();
        BabbleParser.ExpressionContext expression = ctx.expression();
        if (expression == null) {
            return namespace.lookup(name);
        }
        if (expression instanceof BabbleParser.ThisExpressionContext) {
            return Members.selectOwn(namespace.lookup("this"), name);
        }
        Object value = visit(expression);
        last = ctx;
        return Members.select(value, name, expression.getText());
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
    public Object visitIfExpression(BabbleParser.IfExpressionContext ctx) {
        last = ctx;
        if (Truth.test(visit(ctx.test))) {
            last = ctx;
            return visit(ctx.thenBlock);
        } else {
            last = ctx;
            if (ctx.elseBlock != null) {
                return visit(ctx.elseBlock);
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
            result = visit(ctx.whileBlock);
        }
        return result;
    }

    @Override
    public Object visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
        last = ctx;
        String name = ctx.name.getText();
        if (ctx.namespace == null) {
            Object value = visit(ctx.value);
            last = ctx;
            return namespace.assign(name, value);
        }
        if (ctx.namespace instanceof BabbleParser.ThisExpressionContext) {
            Object thiz = namespace.lookup("this");
            Object value = visit(ctx.value);
            last = ctx;
            return Members.assignOwn(thiz, value, name);
        }
        // The target is checked before the value is evaluated.
        Object target = visit(ctx.namespace);
        last = ctx;
        Object scope = Members.assignable(target, ctx.namespace.getText());
        Object value = visit(ctx.value);
        last = ctx;
        return Members.assign(scope, value, name);
    }

    @Override
    public Object visitInteger(BabbleParser.IntegerContext ctx) {
        last = ctx;
        return Integer.parseInt(ctx.getText());
    }

    @Override
    public Object visitDouble(BabbleParser.DoubleContext ctx) {
        last = ctx;
        return Double.parseDouble(ctx.getText());
    }

    @Override
    public Object visitString(BabbleParser.StringContext ctx) {
        last = ctx;
        return unescape(ctx.STRING().getText());
    }

    /**
     * The value of a string literal, given its source text with the quotes.
     */
    public static String unescape(String literal) {
        literal = literal.substring(1, literal.length() - 1);
        return literal.replace("\\\\", "\\").replace("\\\"", "\"");
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
            return superCall(ctx, selector, superExpression);
        }
        Object callee = visit(ctx.expression());
        last = ctx;
        CallSite site = callSite(ctx);
        var callable = Calls.callable(callee, site);
        Object[] args = arguments(ctx);
        last = ctx;
        return callable.call(runtime, args, callNames.get(ctx), site);
    }

    /** {@code super.m(args)} : the default method the analysis resolved, called on {@code this}. */
    private Object superCall(BabbleParser.CallContext ctx, BabbleParser.SelectorContext selector, BabbleParser.SuperExpressionContext superExpression) {
        Object thiz = namespace.lookup("this");
        Object type = Classes.type(type(scopes.superTarget(superExpression)), superExpression.getText());
        CallSite site = callSite(ctx);
        Object[] args = arguments(ctx);
        last = ctx;
        return Supers.call(thiz, type, selector.ID().getText(), runtime, args, callNames.get(ctx), site);
    }

    private Object[] arguments(BabbleParser.CallContext ctx) {
        List<BabbleParser.CallParameterContext> parameters = ctx.callParameters().callParameter();
        Object[] args = parameters.isEmpty() ? Arguments.NONE : new Object[parameters.size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = visit(parameters.get(i).expression());
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
            if (parameters.get(i).ID() != null) {
                if (names == null) {
                    names = new String[parameters.size()];
                }
                names[i] = parameters.get(i).ID().getText();
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
