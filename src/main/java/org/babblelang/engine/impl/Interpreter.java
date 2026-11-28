package org.babblelang.engine.impl;

import java.util.ArrayList;
import java.util.List;
import javax.script.SimpleScriptContext;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTreeProperty;
import org.babblelang.analysis.BlockScopes;
import org.babblelang.analysis.CallMetadata;
import org.babblelang.analysis.LoweringTargets;
import org.babblelang.analysis.ScopeAnalysis;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.Arguments;
import org.babblelang.runtime.ArraySupport;
import org.babblelang.runtime.BabbleClass;
import org.babblelang.runtime.BabbleException;
import org.babblelang.runtime.BabbleInterface;
import org.babblelang.runtime.BabbleObjects;
import org.babblelang.runtime.CallSite;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.Calls;
import org.babblelang.runtime.ClassBody;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.Classes;
import org.babblelang.runtime.InterpretedInstance;
import org.babblelang.runtime.Members;
import org.babblelang.runtime.Namespace;
import org.babblelang.runtime.OpSite;
import org.babblelang.runtime.Operators;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Supers;
import org.babblelang.runtime.Truth;
import org.babblelang.runtime.Types;
import org.babblelang.runtime.Values;

public class Interpreter extends BabbleBaseVisitor<Object> {
    private final ScriptRuntime runtime;
    private final String sourceName;
    private ScopeAnalysis scopes;
    private Namespace namespace;
    private ParserRuleContext last;
    private BabbleClass activeCtorClass;
    private InterpretedClassBody activeCtorBody;
    private InterpretedInstance activeCtorInstance;

    // Static descriptions of the sites, built the first time each one runs.
    private final ParseTreeProperty<OpSite> opSites = new ParseTreeProperty<>();
    private final ParseTreeProperty<CallSite> callSites = new ParseTreeProperty<>();
    private final ParseTreeProperty<String[]> callNames = new ParseTreeProperty<>();

    /**
     * An interpreter on its own, outside of the engine : the block scope analysis runs when a file
     * is visited, and output goes to a default ScriptContext.
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
     * Evaluates an expression with the interpreter temporarily positioned in the given namespace,
     * restoring the current one afterwards. Used to run a function body in the namespace of its
     * call.
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
     * Evaluates a parameter default value in the namespace of the call. An error later in the
     * binding is about the call, so it is still reported at the call's line.
     */
    Object evaluateDefault(Namespace scope, BabbleParser.ExpressionContext expression) {
        ParserRuleContext call = last;
        Object value = widen(expression, evaluateIn(scope, expression));
        last = call;
        return value;
    }

    @Override
    public Object visitFile(BabbleParser.FileContext ctx) {
        last = ctx;
        if (scopes == null) {
            scopes = BlockScopes.analyze(ctx);
        }
        namespace.declare(scopes.declared(ctx), scopes.declaredFinals(ctx));
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
     * A class expression evaluates to a new class value, then runs its static block, if any, with
     * {@code this} as the class value.
     */
    @Override
    public Object visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
        last = ctx;
        Object[] interfaces = types(ctx.typeList());
        ClassShape shape = scopes.hierarchy().shape(ctx);
        InterpretedClassBody body = new InterpretedClassBody(ctx);
        BabbleClass babbleClass;
        int ctorCount = shape.constructorCount();
        if (ctorCount > 1) {
            ClassBody[] ctors = new ClassBody[ctorCount];
            for (int k = 0; k < ctorCount; k++) {
                final int idx = k;
                ctors[k] =
                        (bc, inst, rt, args, names, site) ->
                                body.constructAt(
                                        bc,
                                        (InterpretedInstance) inst,
                                        idx,
                                        rt,
                                        args,
                                        names,
                                        site,
                                        true);
            }
            babbleClass =
                    Classes.newClass(
                            shape,
                            namespace,
                            Arguments.NONE,
                            ctors,
                            interfaces,
                            InterpretedInstance::new);
        } else {
            babbleClass = Classes.newClass(shape, namespace, body, interfaces);
        }
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
        Object object =
                BabbleClass.object(
                        scopes.hierarchy().shape(ctx),
                        namespace,
                        new InterpretedClassBody(ctx),
                        interfaces,
                        runtime);
        last = ctx;
        return object;
    }

    @Override
    public Object visitInterfaceExpression(BabbleParser.InterfaceExpressionContext ctx) {
        last = ctx;
        BabbleInterface babbleInterface =
                Classes.newInterface(scopes.hierarchy().shape(ctx), types(ctx.typeList()));
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
     * The value of a type the analysis resolved statically : a name, then members. It is evaluated
     * like the equivalent expression.
     */
    private Object type(BabbleParser.TypeContext type) {
        List<String> ids =
                org.babblelang.analysis.ScopeAnalysis.simpleTypePath(
                        org.babblelang.analysis.ScopeAnalysis.simpleType(type));
        Object value = namespace.lookup(ids.get(0));
        StringBuilder text = new StringBuilder(ids.get(0));
        for (int i = 1; i < ids.size(); i++) {
            value = Members.select(value, ids.get(i), text.toString());
            text.append('.').append(ids.get(i));
        }
        return value;
    }

    /**
     * The constructor of an interpreted class : binds the arguments, header members going straight
     * into the instance, then runs the body.
     */
    private final class InterpretedClassBody implements ClassBody {
        private final ParserRuleContext ctx;
        private final BabbleParser.InitExpressionContext primaryInit;
        private final List<BabbleParser.InitExpressionContext> explicitInits;

        InterpretedClassBody(ParserRuleContext ctx) {
            this.ctx = ctx;
            BabbleParser.InitExpressionContext pInit = null;
            List<BabbleParser.InitExpressionContext> eInits = new ArrayList<>();
            BabbleParser.BlockContext bodyBlock =
                    ctx instanceof BabbleParser.ClassExpressionContext c
                            ? c.classBlock
                            : ((BabbleParser.ObjectExpressionContext) ctx).createBlock;
            if (bodyBlock != null) {
                for (BabbleParser.ExpressionContext stmt : bodyBlock.expression()) {
                    if (stmt instanceof BabbleParser.InitExpressionContext initCtx) {
                        if (initCtx.fnParameters() == null) {
                            pInit = initCtx;
                        } else {
                            eInits.add(initCtx);
                        }
                    }
                }
            }
            this.primaryInit = pInit;
            this.explicitInits = List.copyOf(eInits);
        }

        @Override
        public Object construct(
                BabbleClass babbleClass,
                Object instance,
                ScriptRuntime rt,
                Object[] args,
                String[] names,
                CallSite site) {
            return constructAt(
                    babbleClass, (InterpretedInstance) instance, 0, rt, args, names, site, true);
        }

        Object constructAt(
                BabbleClass babbleClass,
                InterpretedInstance inst,
                int ctorIndex,
                ScriptRuntime rt,
                Object[] args,
                String[] names,
                CallSite site,
                boolean outermost) {
            BabbleClass prevClass = activeCtorClass;
            InterpretedClassBody prevBody = activeCtorBody;
            InterpretedInstance prevInst = activeCtorInstance;
            activeCtorClass = babbleClass;
            activeCtorBody = this;
            activeCtorInstance = inst;
            try {
                if (ctx instanceof BabbleParser.ObjectExpressionContext obj) {
                    Namespace scope = Classes.constructorScope(babbleClass, inst);
                    runClassFieldAndMethodDefs(scope, obj.createBlock);
                    if (primaryInit != null) {
                        Namespace initScope =
                                scope.child(
                                        scopes.declared(primaryInit),
                                        scopes.declaredFinals(primaryInit));
                        runIn(initScope, primaryInit.initBlock.expression());
                    }
                    return inst;
                }
                BabbleParser.ClassExpressionContext cls = (BabbleParser.ClassExpressionContext) ctx;
                ClassShape shape = babbleClass.shape();
                ClassShape.ConstructorShape cs = shape.constructor(ctorIndex);
                Namespace scope = Classes.constructorScope(babbleClass, inst);
                BabbleParser.InitExpressionContext initCtx = initContextFor(cs, ctorIndex);
                if (!cs.isPrimary() && initCtx != null) {
                    scope.declare(scopes.declared(initCtx), scopes.declaredFinals(initCtx));
                }
                String[] parameters = cs.parameters();
                Arguments.Binder binder =
                        cs.restIndex() >= 0
                                ? Arguments.binder(
                                        parameters,
                                        cs.keywordOnlyStart(),
                                        cs.restIndex(),
                                        args,
                                        names)
                                : Arguments.binder(parameters, cs.keywordOnlyStart(), args, names);
                for (int i = 0; i < parameters.length; i++) {
                    Object value = binder.take(i);
                    ParserRuleContext paramDecl =
                            cs.isPrimary()
                                    ? cls.classParameters().classParam(i)
                                    : initCtx.fnParameters().fnParam(i);
                    if (value == Arguments.MISSING) {
                        BabbleParser.ExpressionContext defaultValue =
                                ScopeAnalysis.paramDefault(paramDecl);
                        if (defaultValue == null) {
                            throw binder.missing(i);
                        }
                        value = evaluateDefault(scope, defaultValue);
                    }
                    value = widen(paramDecl, value);
                    LoweringTargets.JavaSamTarget paramSam =
                            scopes.lowering().javaSamTarget(paramDecl);
                    if (paramSam != null) {
                        value =
                                Values.toJavaSam(
                                        value, paramSam.samInterface(), paramSam.markers(), rt);
                    }
                    value = Types.check(value, scopes.lowering().checkedType(paramDecl));
                    int member =
                            cs.isPrimary() && i < cs.parameterMembers().length
                                    ? cs.parameterMembers()[i]
                                    : -1;
                    if (member >= 0) {
                        inst.initMember(member, value);
                    } else {
                        scope.defineParameter(
                                parameters[i], value, !ScopeAnalysis.isVar(paramDecl));
                    }
                }
                binder.rejectLeftovers();
                boolean delegating = initCtx != null && containsDelegatingCall(initCtx.initBlock);
                if (!delegating) {
                    runClassFieldAndMethodDefs(scope, cls.classBlock);
                }
                if (initCtx != null) {
                    Namespace initScope =
                            cs.isPrimary()
                                    ? scope.child(
                                            scopes.declared(initCtx),
                                            scopes.declaredFinals(initCtx))
                                    : scope;
                    runIn(initScope, initCtx.initBlock.expression());
                }
                return inst;
            } finally {
                if (outermost) {
                    inst.finishInit();
                }
                activeCtorClass = prevClass;
                activeCtorBody = prevBody;
                activeCtorInstance = prevInst;
            }
        }

        private BabbleParser.InitExpressionContext initContextFor(
                ClassShape.ConstructorShape cs, int ctorIndex) {
            if (cs.isPrimary()) {
                return primaryInit;
            }
            int explicitIdx =
                    ((BabbleParser.ClassExpressionContext) ctx).classParameters() != null
                                    && !((BabbleParser.ClassExpressionContext) ctx)
                                            .classParameters()
                                            .classParam()
                                            .isEmpty()
                            ? ctorIndex - 1
                            : ctorIndex;
            return explicitIdx >= 0 && explicitIdx < explicitInits.size()
                    ? explicitInits.get(explicitIdx)
                    : null;
        }

        private boolean containsDelegatingCall(BabbleParser.BlockContext block) {
            if (block == null) {
                return false;
            }
            for (BabbleParser.ExpressionContext stmt : block.expression()) {
                if (stmt instanceof BabbleParser.CallContext cc
                        && scopes.calls().isDelegatingThisCall(cc)) {
                    return true;
                }
            }
            return false;
        }

        private void runClassFieldAndMethodDefs(Namespace scope, BabbleParser.BlockContext block) {
            if (block == null) {
                return;
            }
            Namespace before = namespace;
            namespace = scope;
            try {
                for (BabbleParser.ExpressionContext stmt : block.expression()) {
                    if (stmt instanceof BabbleParser.InitExpressionContext) {
                        continue;
                    }
                    visit(stmt);
                }
            } finally {
                namespace = before;
            }
        }
    }

    @Override
    public Object visitInitExpression(BabbleParser.InitExpressionContext ctx) {
        return null;
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
        String builtin = scopes.lowering().isBuiltin(ctx);
        if (builtin != null) {
            last = ctx;
            return Values.isBuiltin(value, builtin);
        }
        last = ctx;
        return Types.matches(value, scopes.lowering().checkedType(ctx));
    }

    /**
     * A type alias (BIP023) only stands for a type : its def's value, which nothing can read, is
     * null.
     */
    @Override
    public Object visitTypeAliasExpression(BabbleParser.TypeAliasExpressionContext ctx) {
        last = ctx;
        return null;
    }

    /**
     * An axiom (BIP019) is a compile-time assumption : it declares nothing at runtime and evaluates
     * to null.
     */
    @Override
    public Object visitAxiomExpression(BabbleParser.AxiomExpressionContext ctx) {
        last = ctx;
        return null;
    }

    @Override
    public Object visitAsExpression(BabbleParser.AsExpressionContext ctx) {
        last = ctx;
        Object value = visit(ctx.left);
        last = ctx;
        LoweringTargets.JavaSamTarget sam = scopes.lowering().asSamTarget(ctx);
        if (sam != null) {
            value = Values.toJavaSam(value, sam.samInterface(), sam.markers(), runtime);
        }
        return Types.check(value, scopes.lowering().checkedType(ctx));
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
        LoweringTargets.DefTarget target = scopes.lowering().defTarget(ctx);
        if (target.kind() == LoweringTargets.DefKind.MEMBER && ctx.value == null) {
            // Uninitialized field declaration (`def x: T` / `var y: T`) : leave UNDEFINED for init.
            return null;
        }
        Object value = null;
        if (ctx.value != null) {
            value = visit(ctx.value);
        }
        last = ctx;
        value = widen(ctx, value);
        return switch (target.kind()) {
            case LEXICAL -> namespace.initialize(id, value);
            case MEMBER -> Classes.initMember(namespace, target.index(), value);
            case STATIC -> Classes.initStatic(namespace, target.index(), value);
            case DEFAULT -> Classes.setDefault(namespace, target.index(), value);
            case REQUIRED -> null;
        };
    }

    private static final class BreakException extends ControlFlowSignal {
        final Object value;

        BreakException(Object value) {
            this.value = value;
        }
    }

    private static final class ContinueException extends ControlFlowSignal {
        static final ContinueException INSTANCE = new ContinueException();

        private ContinueException() {}
    }

    @Override
    public Object visitReturnExpression(BabbleParser.ReturnExpressionContext ctx) {
        last = ctx;
        Object value = visit(ctx.expression());
        last = ctx;
        value = widen(ctx, value);
        throw new ReturnException(value);
    }

    /** {@code forever e} : the value of {@code e}, which the analysis knows never ends (BIP024). */
    @Override
    public Object visitForeverExpression(BabbleParser.ForeverExpressionContext ctx) {
        last = ctx;
        return visit(ctx.expression());
    }

    @Override
    public Object visitBreakExpression(BabbleParser.BreakExpressionContext ctx) {
        last = ctx;
        Object value =
                ctx.expression() != null ? widen(ctx.expression(), visit(ctx.expression())) : null;
        throw new BreakException(value);
    }

    @Override
    public Object visitContinueExpression(BabbleParser.ContinueExpressionContext ctx) {
        last = ctx;
        throw ContinueException.INSTANCE;
    }

    @Override
    public Object visitThrowExpression(BabbleParser.ThrowExpressionContext ctx) {
        last = ctx;
        Object value = visit(ctx.expression());
        last = ctx;
        throw org.babblelang.runtime.Throwables.sneakyThrow(
                org.babblelang.runtime.Throwables.toThrowable(value));
    }

    @Override
    public Object visitTryExpression(BabbleParser.TryExpressionContext ctx) {
        last = ctx;
        ParserRuleContext failingAt = null;
        Throwable primary = null;
        Object result = null;
        try {
            try {
                result = widen(ctx.tryBlock, visitBlock(ctx.tryBlock));
            } catch (ControlFlowSignal | org.babblelang.engine.ExecutionInterruptedError control) {
                throw control;
            } catch (Throwable t) {
                failingAt = last;
                boolean matched = false;
                for (BabbleParser.CatchClauseContext cc : ctx.catchClause()) {
                    LoweringTargets.CatchTarget target = scopes.lowering().catchTarget(cc);
                    boolean nonErrorAny =
                            target != null ? target.catchesNonErrorAny() : (cc.type() == null);
                    boolean catchesJavaThrowable = target != null && target.catchesJavaThrowable();
                    String desc = target != null ? target.typeDescriptor() : null;
                    if (org.babblelang.runtime.Throwables.matchesCatch(
                            t, desc, nonErrorAny, catchesJavaThrowable)) {
                        failingAt = null;
                        Object bound =
                                catchesJavaThrowable
                                        ? Values.fromJava(t)
                                        : org.babblelang.runtime.Throwables.unwrapForCatch(t);
                        result = widen(cc.catchBlock, runCatchBlock(cc, bound));
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    primary = t;
                    throw org.babblelang.runtime.Throwables.sneakyThrow(t);
                }
            }
        } catch (ControlFlowSignal | org.babblelang.engine.ExecutionInterruptedError control) {
            throw control;
        } catch (Throwable t) {
            if (primary == null) {
                failingAt = last;
                primary = t;
            }
            throw org.babblelang.runtime.Throwables.sneakyThrow(t);
        } finally {
            if (ctx.finallyBlock != null) {
                if (primary != null) {
                    try {
                        visitBlock(ctx.finallyBlock);
                    } catch (Throwable secondary) {
                        org.babblelang.runtime.Throwables.addSuppressed(primary, secondary);
                    }
                    last = failingAt;
                } else {
                    visitBlock(ctx.finallyBlock);
                }
            }
        }
        return result;
    }

    @Override
    public Object visitMatchExpression(BabbleParser.MatchExpressionContext ctx) {
        last = ctx;
        Object scrutinee = visit(ctx.scrutinee);
        for (BabbleParser.CaseClauseContext cc : ctx.caseClause()) {
            last = cc;
            java.util.LinkedHashMap<String, Object> bindings = new java.util.LinkedHashMap<>();
            if (!matchPattern(scrutinee, cc.pattern(), bindings)) {
                continue;
            }
            Namespace before = namespace;
            if (!bindings.isEmpty()) {
                namespace = namespace.child(new String[0], new boolean[0]);
                for (java.util.Map.Entry<String, Object> entry : bindings.entrySet()) {
                    namespace.define(entry.getKey(), true).set(entry.getValue());
                }
            }
            try {
                if (cc.guard != null && !Truth.test(visit(cc.guard))) {
                    continue;
                }
                Object value = visit(cc.armBlock);
                return widen(cc.armBlock, value);
            } finally {
                namespace = before;
            }
        }
        if (ctx.elseClause() != null) {
            BabbleParser.ElseClauseContext ec = ctx.elseClause();
            last = ec;
            Namespace before = namespace;
            if (ec.name != null) {
                namespace = namespace.child(new String[0], new boolean[0]);
                namespace.define(ec.name.getText(), true).set(scrutinee);
            }
            try {
                Object value = visit(ec.elseBlock);
                return widen(ec.elseBlock, value);
            } finally {
                namespace = before;
            }
        }
        throw org.babblelang.runtime.MatchSupport.noMatchingCase(scrutinee);
    }

    private boolean matchPattern(
            Object value,
            BabbleParser.PatternContext pat,
            java.util.LinkedHashMap<String, Object> bindings) {
        for (BabbleParser.AtomicPatternContext atom : pat.atomicPattern()) {
            if (matchAtomicPattern(value, atom, bindings)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchAtomicPattern(
            Object value,
            BabbleParser.AtomicPatternContext atom,
            java.util.LinkedHashMap<String, Object> bindings) {
        return switch (atom) {
            case BabbleParser.ValuePatternContext vp -> {
                if (vp.expr instanceof BabbleParser.SelectorContext sel
                        && sel.expression() == null
                        && "_".equals(sel.ID().getText())) {
                    yield true;
                }
                var rangeSrc = scopes.lowering().rangePatternSource(vp);
                if (rangeSrc != null) {
                    Object lo = visit(rangeSrc.lo());
                    Object hi = visit(rangeSrc.hi());
                    Object step = rangeSrc.step() != null ? visit(rangeSrc.step()) : null;
                    yield org.babblelang.runtime.MatchSupport.rangeMatch(
                            value, lo, hi, rangeSrc.inclusive(), step);
                }
                Object target = visit(vp.expr);
                yield Operators.equal(value, target);
            }
            case BabbleParser.RelationalPatternContext rp -> {
                Object rhs = visit(rp.rhs);
                yield switch (rp.op.getType()) {
                    case BabbleLexer.EQ -> Operators.equal(value, rhs);
                    case BabbleLexer.NEQ -> !Operators.equal(value, rhs);
                    case BabbleLexer.LT ->
                            org.babblelang.runtime.MatchSupport.relational(
                                    value, rhs, org.babblelang.runtime.MatchSupport.OP_LT);
                    case BabbleLexer.LTE ->
                            org.babblelang.runtime.MatchSupport.relational(
                                    value, rhs, org.babblelang.runtime.MatchSupport.OP_LTE);
                    case BabbleLexer.GT ->
                            org.babblelang.runtime.MatchSupport.relational(
                                    value, rhs, org.babblelang.runtime.MatchSupport.OP_GT);
                    case BabbleLexer.GTE ->
                            org.babblelang.runtime.MatchSupport.relational(
                                    value, rhs, org.babblelang.runtime.MatchSupport.OP_GTE);
                    default -> false;
                };
            }
            case BabbleParser.MembershipPatternContext mp -> {
                Object container = visit(mp.container);
                yield org.babblelang.runtime.MatchSupport.inContainer(value, container);
            }
            case BabbleParser.TypeOrDestructurePatternContext tp -> {
                if (tp.patternArgs() != null) {
                    yield matchDestructure(value, tp, tp.patternArgs(), bindings);
                }
                LoweringTargets.PatternTypeTarget target = scopes.lowering().patternTypeTarget(tp);
                yield matchesPatternType(value, target);
            }
            case BabbleParser.BindingPatternContext bp -> {
                if (bp.patternArgs() != null) {
                    if (!matchDestructure(value, bp, bp.patternArgs(), bindings)) {
                        yield false;
                    }
                } else if (bp.patternBindingType() != null) {
                    LoweringTargets.PatternTypeTarget target =
                            scopes.lowering().patternTypeTarget(bp);
                    if (!matchesPatternType(value, target)) {
                        yield false;
                    }
                }
                bindings.put(bp.name.getText(), value);
                yield true;
            }
            default -> false;
        };
    }

    private boolean matchDestructure(
            Object value,
            ParserRuleContext owner,
            BabbleParser.PatternArgsContext args,
            java.util.LinkedHashMap<String, Object> bindings) {
        LoweringTargets.RecordDestructureTarget rd =
                scopes.lowering().recordDestructureTarget(owner);
        if (rd != null) {
            if (!matchesPatternType(value, rd.typeTest())) {
                return false;
            }
            List<BabbleParser.PatternArgContext> argList = args.patternArg();
            if (!rd.isJavaRecord()) {
                for (int i = 0; i < argList.size(); i++) {
                    String fn = rd.fieldNames()[i];
                    Object fv = Members.select(value, fn, fn);
                    if (!matchPattern(fv, argList.get(i).pattern(), bindings)) {
                        return false;
                    }
                }
                return true;
            }
            Object raw = Values.unwrap(value);
            for (int i = 0; i < argList.size(); i++) {
                Object fv;
                try {
                    fv = Values.fromJava(rd.javaAccessors()[i].invoke(raw));
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    Throwable cause = ite.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    throw new BabbleException(cause != null ? cause.toString() : ite.toString());
                } catch (ReflectiveOperationException roe) {
                    throw new BabbleException(roe.toString());
                }
                if (!matchPattern(fv, argList.get(i).pattern(), bindings)) {
                    return false;
                }
            }
            return true;
        }
        LoweringTargets.SequenceDestructureTarget sd =
                scopes.lowering().sequenceDestructureTarget(owner);
        if (sd != null) {
            if (sd.typeTest() != null) {
                if (!matchesPatternType(value, sd.typeTest())) {
                    return false;
                }
            } else if (sd.isArray()) {
                if (!ArraySupport.isArray(value)) {
                    return false;
                }
            }
            List<BabbleParser.PatternArgContext> argList = args.patternArg();
            int fixed = sd.fixedCount();
            if (!sd.isArray()) {
                Object raw = Values.unwrap(value);
                if (!(raw instanceof List<?> list)) {
                    return false;
                }
                int len = list.size();
                if (sd.hasRest() ? len < fixed : len != fixed) {
                    return false;
                }
                for (int i = 0; i < fixed; i++) {
                    if (!matchPattern(list.get(i), argList.get(i).pattern(), bindings)) {
                        return false;
                    }
                }
                if (args.restPattern() != null && args.restPattern().name != null) {
                    bindings.put(
                            args.restPattern().name.getText(),
                            org.babblelang.runtime.CoreCollections.listSlice(list, fixed));
                }
                return true;
            }
            Object rawArr = Values.unwrap(value);
            int len = ArraySupport.size(rawArr);
            if (sd.hasRest() ? len < fixed : len != fixed) {
                return false;
            }
            for (int i = 0; i < fixed; i++) {
                if (!matchPattern(
                        ArraySupport.get(rawArr, i), argList.get(i).pattern(), bindings)) {
                    return false;
                }
            }
            if (args.restPattern() != null && args.restPattern().name != null) {
                bindings.put(args.restPattern().name.getText(), ArraySupport.slice(rawArr, fixed));
            }
            return true;
        }
        return matchesPatternType(value, scopes.lowering().patternTypeTarget(owner));
    }

    private static boolean matchesPatternType(
            Object value, LoweringTargets.PatternTypeTarget target) {
        if (target == null) {
            return true;
        }
        if (target.descriptor() != null) {
            return Types.matches(value, target.descriptor());
        }
        if (target.builtin() != null) {
            return Types.matches(value, target.builtin());
        }
        return true;
    }

    private Object runCatchBlock(BabbleParser.CatchClauseContext cc, Object bound) {
        Namespace before = namespace;
        String[] declared = scopes.declared(cc.catchBlock);
        if (cc.name != null || declared.length > 0) {
            namespace = namespace.child(declared, scopes.declaredFinals(cc.catchBlock));
            if (cc.name != null) {
                namespace.define(cc.name.getText(), true).set(bound);
            }
        }
        try {
            return visitStatements(cc.catchBlock.expression());
        } finally {
            namespace = before;
        }
    }

    /**
     * A block that defines names gets a namespace of its own. Function and object bodies never do :
     * their owner already provides it.
     */
    @Override
    public Object visitBlock(BabbleParser.BlockContext ctx) {
        last = ctx;
        if (!scopes.opensScope(ctx)) {
            return visitStatements(ctx.expression());
        }
        Namespace before = namespace;
        namespace = namespace.child(scopes.declared(ctx), scopes.declaredFinals(ctx));
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
            return Types.check(result, scopes.lowering().checkedType(ctx));
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
        return Types.check(result, scopes.lowering().checkedType(ctx));
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
        if (method != null && BabbleObjects.isInstance(a)) {
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
            case BabbleLexer.FLOOR_DIV -> Operators.floorDiv(a, b, site);
            case BabbleLexer.MOD -> Operators.mod(a, b, site);
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
    @SuppressWarnings("unchecked")
    public Object visitRangeExpression(BabbleParser.RangeExpressionContext ctx) {
        last = ctx;
        Object left = widen(ctx.left, visit(ctx.left));
        Object right = widen(ctx.right, visit(ctx.right));
        last = ctx;
        boolean inclusive = ctx.op.getType() == BabbleLexer.RANGE;
        LoweringTargets.RangeTarget target = scopes.lowering().rangeTarget(ctx);
        if (target != null && left != null && right != null) {
            return switch (target.elemKind()) {
                case BYTE ->
                        org.babblelang.runtime.Range.ofByte(
                                ((Number) left).byteValue(),
                                ((Number) right).byteValue(),
                                inclusive);
                case SHORT ->
                        org.babblelang.runtime.Range.ofShort(
                                ((Number) left).shortValue(),
                                ((Number) right).shortValue(),
                                inclusive);
                case INT ->
                        org.babblelang.runtime.Range.ofInt(
                                ((Number) left).intValue(), ((Number) right).intValue(), inclusive);
                case LONG ->
                        org.babblelang.runtime.Range.ofLong(
                                ((Number) left).longValue(),
                                ((Number) right).longValue(),
                                inclusive);
                case CHAR ->
                        org.babblelang.runtime.Range.ofChar(
                                (Character) left, (Character) right, inclusive);
                case FLOAT ->
                        org.babblelang.runtime.Range.ofFloat(
                                ((Number) left).floatValue(),
                                ((Number) right).floatValue(),
                                inclusive);
                case DOUBLE ->
                        org.babblelang.runtime.Range.ofDouble(
                                ((Number) left).doubleValue(),
                                ((Number) right).doubleValue(),
                                inclusive);
                default ->
                        org.babblelang.runtime.Range.ofComparable(
                                (Comparable<Object>) left, (Comparable<Object>) right, inclusive);
            };
        }
        return org.babblelang.runtime.Range.of(
                left, right, inclusive, ctx.left.getText(), ctx.right.getText());
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
        Object resolved = scopes.lowering().resolvedLiteral(ctx);
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
        if (BabbleObjects.isInstance(value)) {
            return operatorCall(value, Operators.NEGATE, ctx.operand.getText(), null, null, ctx);
        }
        return Operators.negate(value, ctx.operand.getText());
    }

    /** {@code a.method(b)}, or {@code a.method()} without an argument, for an operator. */
    private Object operatorCall(
            Object a,
            String method,
            String receiver,
            String argument,
            Object b,
            ParserRuleContext ctx) {
        CallSite site =
                org.babblelang.analysis.SyntaxText.operatorSite(
                        ctx, receiver, method, argument, sourceName);
        Object member = Members.select(a, method, receiver);
        Object[] rawArgs = argument == null ? Arguments.NONE : new Object[] {b};
        CallMetadata.OverloadDispatchTarget ovDispatch = scopes.calls().overloadDispatch(ctx);
        if (ovDispatch != null) {
            if (!ovDispatch.isSplit()) {
                CallMetadata.OverloadCallTarget target = ovDispatch.direct();
                Callable chosen =
                        target.exactSet()
                                ? org.babblelang.runtime.OverloadSet.memberOf(
                                        member, target.altIndex())
                                : org.babblelang.runtime.OverloadSet.selectFrom(
                                        member, target.signatureKey());
                last = ctx;
                return chosen.call(runtime, rawArgs, null, site);
            }
            for (CallMetadata.OverloadSplitBranch branch : ovDispatch.splitBranches()) {
                if (matchesSplitChecks(branch.checks(), rawArgs)) {
                    CallMetadata.OverloadCallTarget target = branch.target();
                    Callable chosen =
                            target.exactSet()
                                    ? org.babblelang.runtime.OverloadSet.memberOf(
                                            member, target.altIndex())
                                    : org.babblelang.runtime.OverloadSet.selectFrom(
                                            member, target.signatureKey());
                    last = ctx;
                    return chosen.call(runtime, rawArgs, null, site);
                }
            }
            throw new BabbleException("No applicable overload of " + receiver + "." + method);
        }
        Callable callable = Calls.callable(member, site);
        last = ctx;
        return callable.call(runtime, rawArgs, null, site);
    }

    /**
     * {@code x op= v} : {@code x op v}, given the target's current value. An operator method on a
     * Babble instance, as for {@code a + b}.
     */
    private Object compound(
            BabbleParser.AssignExpressionContext ctx,
            String operator,
            Object current,
            Object value) {
        last = ctx;
        String target = org.babblelang.analysis.SyntaxText.targetText(ctx);
        String method = Operators.method(operator);
        if (BabbleObjects.isInstance(current)) {
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
        return visitIfExpr(ctx.ifExpr());
    }

    @Override
    public Object visitIfExpr(BabbleParser.IfExprContext ctx) {
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

    /** Cooperative cancellation point for loop back-edges and tail calls ({@code BIP041-D7}). */
    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new org.babblelang.engine.ExecutionInterruptedError();
        }
    }

    @Override
    public Object visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
        last = ctx;
        Object result = null;
        // The body block opens a fresh scope on every iteration.
        while (Truth.test(visit(ctx.test))) {
            checkInterrupted();
            try {
                result = widen(ctx.whileBlock, visit(ctx.whileBlock));
            } catch (ContinueException ignored) {
                // next iteration
            } catch (BreakException brk) {
                return brk.value;
            }
        }
        return result;
    }

    @Override
    public Object visitForExpression(BabbleParser.ForExpressionContext ctx) {
        last = ctx;
        Object iterable = visit(ctx.iterable);
        Object rawIter = Values.unwrap(iterable);
        LoweringTargets.ForLoopTarget target = scopes.lowering().forLoopTarget(ctx);
        if (BabbleObjects.isInstance(rawIter)
                && (target == null
                        || target.kind() == LoweringTargets.ForKind.JAVA_ITERABLE
                        || target.kind() == LoweringTargets.ForKind.JAVA_ITERATOR
                        || target.kind() == LoweringTargets.ForKind.DYNAMIC_UNION)) {
            Object face = BabbleObjects.face(rawIter);
            if (face instanceof java.lang.Iterable<?> || face instanceof java.util.Iterator<?>) {
                rawIter = face;
            }
        }
        String loopVar = ctx.name.getText();
        String[] blockDeclared = scopes.declared(ctx.forBlock);

        Object result = null;
        if (rawIter != null && rawIter.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(rawIter);
            for (int i = 0; i < len; i++) {
                checkInterrupted();
                Object elem = Values.fromJava(java.lang.reflect.Array.get(rawIter, i));
                try {
                    result = runForIteration(ctx, target, loopVar, blockDeclared, elem);
                } catch (ContinueException ignored) {
                    // next iteration
                } catch (BreakException brk) {
                    return brk.value;
                }
            }
            return result;
        }
        if (rawIter instanceof java.lang.Iterable<?> || rawIter instanceof java.util.Iterator<?>) {
            java.util.Iterator<?> it =
                    rawIter instanceof java.util.Iterator<?> i
                            ? i
                            : ((java.lang.Iterable<?>) rawIter).iterator();
            while (it.hasNext()) {
                checkInterrupted();
                Object elem = Values.fromJava(it.next());
                try {
                    result = runForIteration(ctx, target, loopVar, blockDeclared, elem);
                } catch (ContinueException ignored) {
                    // next iteration
                } catch (BreakException brk) {
                    return brk.value;
                }
            }
            return result;
        }
        // Babble custom iterable or iterator (Tier 3)
        boolean isIterable =
                target != null
                        && (target.kind() == LoweringTargets.ForKind.BABBLE_ITERABLE
                                || target.kind() == LoweringTargets.ForKind.BABBLE_TO_JAVA_ITERABLE
                                || (target.kind() == LoweringTargets.ForKind.DYNAMIC_UNION
                                        && Members.hasMember(iterable, "iterator")));
        Object iterObj = iterable;
        if (isIterable) {
            Callable iterFn = (Callable) Members.select(iterable, "iterator", "iterator");
            iterObj =
                    iterFn.call(
                            runtime,
                            new Object[0],
                            null,
                            new CallSite(
                                    sourceName,
                                    ctx.getStart().getLine(),
                                    "iterator",
                                    new String[0]));
        }
        Object unwrappedIter = Values.unwrap(iterObj);
        if (BabbleObjects.isInstance(unwrappedIter)
                && target != null
                && target.kind() == LoweringTargets.ForKind.BABBLE_TO_JAVA_ITERABLE) {
            unwrappedIter = BabbleObjects.face(unwrappedIter);
        }
        if (unwrappedIter instanceof java.util.Iterator<?> it) {
            while (it.hasNext()) {
                checkInterrupted();
                Object elem = Values.fromJava(it.next());
                try {
                    result = runForIteration(ctx, target, loopVar, blockDeclared, elem);
                } catch (ContinueException ignored) {
                    // next iteration
                } catch (BreakException brk) {
                    return brk.value;
                }
            }
            return result;
        }
        Callable hasNextFn = (Callable) Members.select(iterObj, "hasNext", "hasNext");
        Callable nextFn = (Callable) Members.select(iterObj, "next", "next");
        CallSite hasNextSite =
                new CallSite(sourceName, ctx.getStart().getLine(), "hasNext", new String[0]);
        CallSite nextSite =
                new CallSite(sourceName, ctx.getStart().getLine(), "next", new String[0]);
        while (Truth.test(hasNextFn.call(runtime, new Object[0], null, hasNextSite))) {
            checkInterrupted();
            Object elem = nextFn.call(runtime, new Object[0], null, nextSite);
            try {
                result = runForIteration(ctx, target, loopVar, blockDeclared, elem);
            } catch (ContinueException ignored) {
                // next iteration
            } catch (BreakException brk) {
                return brk.value;
            }
        }
        return result;
    }

    private Object runForIteration(
            BabbleParser.ForExpressionContext ctx,
            LoweringTargets.ForLoopTarget target,
            String loopVar,
            String[] blockDeclared,
            Object elem) {
        if (target != null && target.elemCheck() != null) {
            Types.check(elem, target.elemCheck());
        }
        LoweringTargets.JavaSamTarget elemSam = scopes.lowering().forElemSamTarget(ctx);
        if (elemSam != null) {
            elem = Values.toJavaSam(elem, elemSam.samInterface(), elemSam.markers(), runtime);
        }
        int elemRank = scopes.lowering().numericWideningRank(ctx);
        if (elemRank >= 0) {
            elem = Values.widenNumeric(elem, elemRank);
        }
        Namespace before = namespace;
        namespace = namespace.child(blockDeclared, scopes.declaredFinals(ctx.forBlock));
        namespace.define(loopVar, true).set(elem);
        try {
            return widen(ctx.forBlock, visitStatements(ctx.forBlock.expression()));
        } finally {
            namespace = before;
        }
    }

    @Override
    public Object visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
        last = ctx;
        String name = ScopeAnalysis.assignName(ctx);
        BabbleParser.ExpressionContext receiver = ScopeAnalysis.assignReceiver(ctx);
        // For x op= v, the target's value is read before v is evaluated, as in x = x op v.
        String operator = Operators.compound(ctx.op.getText());
        if (receiver == null) {
            Object current = operator == null ? null : namespace.lookup(name);
            Object value = visit(ctx.value);
            if (operator != null) {
                value = compound(ctx, operator, current, value);
            }
            last = ctx;
            value = widen(ctx, value);
            return namespace.assign(name, value);
        }
        if (receiver instanceof BabbleParser.ThisExpressionContext) {
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
        Object target = visit(receiver);
        last = ctx;
        Object scope = Members.assignable(target, receiver.getText());
        Object current = operator == null ? null : Members.select(scope, name, receiver.getText());
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
        Object resolved = scopes.lowering().resolvedLiteral(ctx);
        if (resolved != null) {
            return resolved;
        }
        String text = ctx.getText();
        return Long.parseLong(text.substring(0, text.length() - 1));
    }

    @Override
    public Object visitInteger(BabbleParser.IntegerContext ctx) {
        last = ctx;
        Object resolved = scopes.lowering().resolvedLiteral(ctx);
        if (resolved != null) {
            return resolved;
        }
        return Integer.parseInt(ctx.getText());
    }

    @Override
    public Object visitFloatLiteral(BabbleParser.FloatLiteralContext ctx) {
        last = ctx;
        Object resolved = scopes.lowering().resolvedLiteral(ctx);
        if (resolved != null) {
            return resolved;
        }
        String text = ctx.getText();
        return Float.parseFloat(text.substring(0, text.length() - 1));
    }

    @Override
    public Object visitDouble(BabbleParser.DoubleContext ctx) {
        last = ctx;
        Object resolved = scopes.lowering().resolvedLiteral(ctx);
        if (resolved != null) {
            return resolved;
        }
        return Double.parseDouble(ctx.getText());
    }

    @Override
    public Object visitCharLiteral(BabbleParser.CharLiteralContext ctx) {
        last = ctx;
        return org.babblelang.parser.StringLiterals.decodeChar(ctx.CHAR_LIT().getText());
    }

    @Override
    public Object visitString(BabbleParser.StringContext ctx) {
        last = ctx;
        return org.babblelang.parser.StringLiterals.decode(ctx);
    }

    @Override
    public Object visitNonNullAssertion(BabbleParser.NonNullAssertionContext ctx) {
        last = ctx;
        Object value = visit(ctx.expression());
        last = ctx;
        String expected = scopes.lowering().nonNullAssertionTarget(ctx);
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
    public Object visitCoreCollection(BabbleParser.CoreCollectionContext ctx) {
        last = ctx;
        return org.babblelang.runtime.CoreCollections.constructor(
                ctx.coreCollectionKeyword().getText());
    }

    @Override
    public Object visitCoreExceptionRef(BabbleParser.CoreExceptionRefContext ctx) {
        last = ctx;
        return org.babblelang.engine.impl.natives.java.JavaClass.of(BabbleException.class);
    }

    @Override
    public Object visitTypeApply(BabbleParser.TypeApplyContext ctx) {
        last = ctx;
        return visit(ctx.expression());
    }

    /** The value of a string literal, given its source text with the quotes. */
    public static String unescape(String literal) {
        return org.babblelang.parser.StringLiterals.unescape(literal);
    }

    @Override
    public Object visit(org.antlr.v4.runtime.tree.ParseTree tree) {
        Object value = super.visit(tree);
        if (tree instanceof ParserRuleContext prc) {
            LoweringTargets.OverloadSelection sel = scopes.calls().overloadSelection(prc);
            if (sel != null) {
                value =
                        sel.exactSet()
                                ? org.babblelang.runtime.OverloadSet.memberOf(value, sel.index())
                                : org.babblelang.runtime.OverloadSet.selectFrom(
                                        value, sel.signatureKey());
            }
        }
        return value;
    }

    @Override
    public Object visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
        last = ctx;
        return new Function(ctx, namespace, this);
    }

    @Override
    public Object visitOverloadExpression(BabbleParser.OverloadExpressionContext ctx) {
        last = ctx;
        List<BabbleParser.ExpressionContext> exprs = ctx.expression();
        Object[] operands = new Object[exprs.size()];
        for (int i = 0; i < exprs.size(); i++) {
            operands[i] = visit(exprs.get(i));
        }
        List<LoweringTargets.OverloadAltMetaInfo> metaInfos = scopes.calls().overloadMetas(ctx);
        org.babblelang.runtime.OverloadSet.AltMeta[] metas;
        if (metaInfos != null) {
            metas = new org.babblelang.runtime.OverloadSet.AltMeta[metaInfos.size()];
            for (int i = 0; i < metaInfos.size(); i++) {
                LoweringTargets.OverloadAltMetaInfo info = metaInfos.get(i);
                metas[i] =
                        new org.babblelang.runtime.OverloadSet.AltMeta(
                                info.signatureKey(),
                                info.display(),
                                info.minArity(),
                                info.maxArity(),
                                info.restIndex(),
                                info.paramDescriptors());
            }
        } else {
            metas = new org.babblelang.runtime.OverloadSet.AltMeta[0];
        }
        last = ctx;
        return org.babblelang.runtime.OverloadSet.of(operands, metas);
    }

    @Override
    public Object visitCall(BabbleParser.CallContext ctx) {
        last = ctx;
        LoweringTargets.ArrayCreationTarget arrCreate = scopes.lowering().arrayCreationTarget(ctx);
        if (arrCreate != null) {
            Object[] args = arguments(ctx);
            last = ctx;
            Object created =
                    switch (arrCreate.kind()) {
                        case SIZE -> {
                            int len = ((Number) args[0]).intValue();
                            yield arrCreate.babbleTypeId() != null
                                    ? ArraySupport.newBabbleArray(
                                            arrCreate.babbleTypeId(),
                                            arrCreate.babbleDisplayName(),
                                            arrCreate.babbleConformIds(),
                                            arrCreate.nestedDims(),
                                            len)
                                    : ArraySupport.newArray(arrCreate.componentClass(), len);
                        }
                        case OF ->
                                arrCreate.babbleTypeId() != null
                                        ? ArraySupport.ofBabble(
                                                arrCreate.babbleTypeId(),
                                                arrCreate.babbleDisplayName(),
                                                arrCreate.babbleConformIds(),
                                                arrCreate.nestedDims(),
                                                args)
                                        : ArraySupport.of(
                                                arrCreate.componentClass(), args, runtime);
                        case FROM ->
                                arrCreate.babbleTypeId() != null
                                        ? ArraySupport.fromBabble(
                                                arrCreate.babbleTypeId(),
                                                arrCreate.babbleDisplayName(),
                                                arrCreate.babbleConformIds(),
                                                arrCreate.nestedDims(),
                                                args[0],
                                                runtime)
                                        : ArraySupport.from(
                                                arrCreate.componentClass(), args[0], runtime);
                    };
            return Types.check(created, scopes.lowering().checkedType(ctx));
        }
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.expression()
                        instanceof BabbleParser.SuperExpressionContext superExpression) {
            return Types.check(
                    superCall(ctx, selector, superExpression), scopes.lowering().checkedType(ctx));
        }
        CallMetadata.OverloadDispatchTarget ovDispatch = scopes.calls().overloadDispatch(ctx);
        if (ovDispatch != null) {
            return invokeOverloadDispatch(ctx, ovDispatch);
        }
        if (ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.safe != null) {
            BabbleParser.ExpressionContext receiverExpr = selector.expression();
            Object receiver = visit(receiverExpr);
            last = selector;
            if (receiver == null) {
                return null;
            }
            Object callee =
                    Types.check(
                            Members.select(
                                    receiver,
                                    ScopeAnalysis.selectorName(selector),
                                    receiverExpr.getText()),
                            scopes.lowering().checkedType(selector));
            last = ctx;
            CallSite site = callSite(ctx);
            var callable = Calls.callable(callee, site);
            Object[] args = bindStaticArguments(ctx, arguments(ctx));
            last = ctx;
            return Types.check(
                    callable.call(runtime, args, effectiveCallNames(ctx), site),
                    scopes.lowering().checkedType(ctx));
        }
        LoweringTargets.JavaCallTarget javaCall = scopes.lowering().javaCallTarget(ctx);
        if (javaCall != null
                && !javaCall.isStatic()
                && javaCall.unwrapReceiver()
                && javaCall.method().getDeclaringClass().isInterface()
                && ctx.expression() instanceof BabbleParser.SelectorContext selector
                && selector.expression() != null) {
            BabbleParser.ExpressionContext receiverExpr = selector.expression();
            Object receiver = visit(receiverExpr);
            last = selector;
            Object target = Values.toJava(receiver, javaCall.method().getDeclaringClass(), runtime);
            Object callee =
                    Types.check(
                            Members.select(
                                    target,
                                    ScopeAnalysis.selectorName(selector),
                                    receiverExpr.getText()),
                            scopes.lowering().checkedType(selector));
            last = ctx;
            CallSite site = callSite(ctx);
            var callable = Calls.callable(callee, site);
            Object[] args = arguments(ctx);
            last = ctx;
            return Types.check(
                    callable.call(runtime, args, callNames.get(ctx), site),
                    scopes.lowering().checkedType(ctx));
        }
        if (scopes.calls().isDelegatingThisCall(ctx)) {
            int targetIdx = scopes.calls().constructorIndex(ctx);
            CallSite site = callSite(ctx);
            Object[] args = bindStaticArguments(ctx, arguments(ctx));
            last = ctx;
            activeCtorBody.constructAt(
                    activeCtorClass,
                    activeCtorInstance,
                    targetIdx,
                    runtime,
                    args,
                    effectiveCallNames(ctx),
                    site,
                    false);
            return null;
        }
        if (scopes.calls().isSelfTailCall(ctx)) {
            // The callee is the running function itself (recurse, its own def, or this.m) : BIP024.
            Object[] args = bindStaticArguments(ctx, arguments(ctx));
            last = ctx;
            return new TailCall(args, effectiveCallNames(ctx));
        }
        Object callee = visit(ctx.expression());
        last = ctx;
        CallSite site = callSite(ctx);
        if (callee instanceof BabbleClass bc && scopes.calls().constructorIndex(ctx) >= 0) {
            int ctorIdx = scopes.calls().constructorIndex(ctx);
            Object[] args = bindStaticArguments(ctx, arguments(ctx));
            last = ctx;
            return Types.check(
                    bc.constructWithIndex(ctorIdx, runtime, args, effectiveCallNames(ctx), site),
                    scopes.lowering().checkedType(ctx));
        }
        var callable = Calls.callable(callee, site);
        Object[] args = bindStaticArguments(ctx, arguments(ctx));
        last = ctx;
        return Types.check(
                callable.call(runtime, args, effectiveCallNames(ctx), site),
                scopes.lowering().checkedType(ctx));
    }

    private Object invokeOverloadDispatch(
            BabbleParser.CallContext ctx, CallMetadata.OverloadDispatchTarget dispatch) {
        Object callee = visit(ctx.expression());
        Object[] rawArgs = arguments(ctx);
        last = ctx;
        CallSite site = callSite(ctx);
        if (!dispatch.isSplit()) {
            CallMetadata.OverloadCallTarget target = dispatch.direct();
            org.babblelang.runtime.Callable chosen =
                    target.exactSet()
                            ? org.babblelang.runtime.OverloadSet.memberOf(callee, target.altIndex())
                            : org.babblelang.runtime.OverloadSet.selectFrom(
                                    callee, target.signatureKey());
            Object[] args = bindArgumentsWith(target.binding(), rawArgs);
            last = ctx;
            return Types.check(
                    chosen.call(runtime, args, null, site), scopes.lowering().checkedType(ctx));
        }
        for (CallMetadata.OverloadSplitBranch branch : dispatch.splitBranches()) {
            if (matchesSplitChecks(branch.checks(), rawArgs)) {
                CallMetadata.OverloadCallTarget target = branch.target();
                org.babblelang.runtime.Callable chosen =
                        target.exactSet()
                                ? org.babblelang.runtime.OverloadSet.memberOf(
                                        callee, target.altIndex())
                                : org.babblelang.runtime.OverloadSet.selectFrom(
                                        callee, target.signatureKey());
                Object[] adapted = rawArgs;
                if (target.binding() != null && target.binding().argAdapts() != null) {
                    adapted = rawArgs.clone();
                    for (int i = 0; i < adapted.length; i++) {
                        LoweringTargets.SpreadAdapt a = target.binding().adaptForArg(i);
                        if (a != null && a.kind() != LoweringTargets.SpreadKind.NONE) {
                            adapted[i] = adaptSpread(adapted[i], a);
                        }
                    }
                }
                Object[] args = bindArgumentsWith(target.binding(), adapted);
                last = ctx;
                return Types.check(
                        chosen.call(runtime, args, null, site), scopes.lowering().checkedType(ctx));
            }
        }
        throw new BabbleException("No applicable overload of " + ctx.expression().getText());
    }

    private static boolean matchesSplitChecks(
            List<LoweringTargets.OverloadSplitCheck> checks, Object[] rawArgs) {
        for (LoweringTargets.OverloadSplitCheck chk : checks) {
            Object v = rawArgs[chk.argIndex()];
            if (chk.isNull()) {
                if (v != null) {
                    return false;
                }
            } else {
                if (v == null) {
                    return false;
                }
                for (String desc : chk.descriptors()) {
                    if (!Types.matches(v, desc)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** {@code super.m(args)} : the default method the analysis resolved, called on {@code this}. */
    private Object superCall(
            BabbleParser.CallContext ctx,
            BabbleParser.SelectorContext selector,
            BabbleParser.SuperExpressionContext superExpression) {
        Object thiz = namespace.lookup("this");
        Object type =
                Classes.type(
                        type(scopes.hierarchy().superTarget(superExpression)),
                        superExpression.getText());
        CallSite site = callSite(ctx);
        Object[] args = bindStaticArguments(ctx, arguments(ctx));
        last = ctx;
        return Supers.call(
                thiz,
                type,
                ScopeAnalysis.selectorName(selector),
                runtime,
                args,
                effectiveCallNames(ctx),
                site);
    }

    private Object[] bindStaticArguments(BabbleParser.CallContext ctx, Object[] rawArgs) {
        return bindArgumentsWith(scopes.calls().argumentBinding(ctx), rawArgs);
    }

    private Object[] bindArgumentsWith(LoweringTargets.ArgumentBinding binding, Object[] rawArgs) {
        if (binding == null) {
            return rawArgs;
        }
        int[] map = binding.paramToArg();
        int restParam = binding.restParam();
        int effective = map.length;
        while (effective > 0 && effective - 1 != restParam && map[effective - 1] < 0) {
            effective--;
        }
        if (effective == 0) {
            return Arguments.NONE;
        }
        Object[] pos = new Object[effective];
        for (int p = 0; p < effective; p++) {
            if (p == restParam) {
                int[] restArgs = binding.restArgs();
                Object[] parts = new Object[restArgs.length];
                for (int k = 0; k < restArgs.length; k++) {
                    parts[k] = rawArgs[restArgs[k]];
                }
                pos[p] =
                        org.babblelang.runtime.Spreads.packedRest(
                                org.babblelang.runtime.Spreads.packRest(
                                        parts, binding.restArgIsSpread()));
            } else {
                int a = map[p];
                pos[p] = a >= 0 ? rawArgs[a] : Arguments.MISSING;
            }
        }
        return pos;
    }

    private String[] effectiveCallNames(BabbleParser.CallContext ctx) {
        return scopes.calls().argumentBinding(ctx) != null ? null : callNames.get(ctx);
    }

    private Object widen(ParserRuleContext ctx, Object value) {
        int rank = scopes.lowering().numericWideningRank(ctx);
        if (rank >= 0) {
            value = Values.widenNumeric(value, rank);
        }
        return adaptJavaSam(ctx, value);
    }

    private Object adaptJavaSam(ParserRuleContext ctx, Object value) {
        LoweringTargets.JavaSamTarget sam = scopes.lowering().javaSamTarget(ctx);
        return sam != null
                ? Values.toJavaSam(value, sam.samInterface(), sam.markers(), runtime)
                : value;
    }

    private Object[] arguments(BabbleParser.CallContext ctx) {
        List<BabbleParser.CallParameterContext> parameters = ctx.callParameters().callParameter();
        LoweringTargets.SpreadAdapt[] adapts = scopes.calls().callSpreadAdapts(ctx);
        Object[] args = parameters.isEmpty() ? Arguments.NONE : new Object[parameters.size()];
        for (int i = 0; i < args.length; i++) {
            BabbleParser.ExpressionContext expression = parameters.get(i).expression();
            Object value = widen(expression, visit(expression));
            if (adapts != null && i < adapts.length && adapts[i] != null) {
                value = adaptSpread(value, adapts[i]);
            }
            args[i] = value;
        }
        return args;
    }

    private Object adaptSpread(Object value, LoweringTargets.SpreadAdapt adapt) {
        LoweringTargets.JavaSamTarget sam = adapt.elementSamTarget();
        Class<?> samCls = sam != null ? sam.samInterface() : null;
        Class<?>[] markers = sam != null ? sam.markers() : null;
        return switch (adapt.kind()) {
            case NONE, AS_IS -> value;
            case TO_ITERABLE ->
                    samCls != null
                            ? org.babblelang.runtime.Spreads.asIterable(
                                    value, samCls, markers, runtime)
                            : org.babblelang.runtime.Spreads.asIterable(value);
            case TO_LIST_BORROWED ->
                    samCls != null
                            ? org.babblelang.runtime.Spreads.asList(
                                    value, false, samCls, markers, runtime)
                            : org.babblelang.runtime.Spreads.asList(value, true);
            case TO_LIST_COPY ->
                    samCls != null
                            ? org.babblelang.runtime.Spreads.asList(
                                    value, false, samCls, markers, runtime)
                            : org.babblelang.runtime.Spreads.asList(value, false);
            case TO_ARRAY ->
                    samCls != null
                            ? org.babblelang.runtime.Spreads.asArray(
                                    value, adapt.arrayComponentType(), samCls, markers, runtime)
                            : org.babblelang.runtime.Spreads.asArray(
                                    value, adapt.arrayComponentType(), runtime);
            case TO_SAM ->
                    samCls != null ? Values.toJavaSam(value, samCls, markers, runtime) : value;
        };
    }

    private CallSite callSite(BabbleParser.CallContext ctx) {
        CallSite site = callSites.get(ctx);
        if (site == null) {
            site = org.babblelang.analysis.SyntaxText.callSite(ctx, sourceName);
            callSites.put(ctx, site);
            callNames.put(ctx, org.babblelang.analysis.SyntaxText.callNames(ctx));
        }
        return site;
    }

    @Override
    public Object visitRecurse(BabbleParser.RecurseContext ctx) {
        last = ctx;
        return namespace.lookup("$recurse");
    }
}
