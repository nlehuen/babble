package org.babblelang.analysis;

import static org.babblelang.analysis.TypeAlgebra.canBeNull;
import static org.babblelang.analysis.TypeAlgebra.containsFunction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.Operators;

/**
 * Expression-level type-checking rules for {@link TypeChecker}: control flow, loops, selectors,
 * calls, blocks, operators, ranges, casts, function/overload literals, L-value assignments, and
 * scalar/numeric literals.
 */
final class ExpressionTyper {
    private final TypeChecker checker;

    ExpressionTyper(TypeChecker checker) {
        this.checker = checker;
    }

    private sealed interface LValue {
        record Local(
                ParserRuleContext declaration,
                Type declaredType,
                Type currentType,
                boolean inferred)
                implements LValue {}

        record Member(
                BabbleParser.DefExpressionContext declaration,
                Type declaredType,
                Type currentType,
                boolean inferred)
                implements LValue {}

        record JavaField(JavaTypeBridge.AssignedField field, Type declaredType, Type currentType)
                implements LValue {}

        record Implicit(Type currentType) implements LValue {}
    }

    Type typeOf(ParserRuleContext ctx, Type expected, TypeChecker.Role role) {
        return switch (ctx) {
            case BabbleParser.IfExpressionContext c -> checker.type(c.ifExpr(), expected, role);
            case BabbleParser.IfExprContext c -> visitIfExpr(c, expected, role);
            case BabbleParser.MatchExpressionContext c ->
                    checker.matchTyper.typeMatch(c, expected, role);
            case BabbleParser.DefExpressionContext c -> visitDefExpression(c, expected, role);
            case BabbleParser.WhileExpressionContext c -> visitWhileExpression(c, expected, role);
            case BabbleParser.TypeAliasExpressionContext c -> visitTypeAlias(c);
            case BabbleParser.AxiomExpressionContext c -> {
                if (AxiomData.isWasmImportAxiom(c)) {
                    checker.parseBabbleAxiom(c, checker.scopeOf(c), checker.checkingEffects);
                } else if (AxiomData.isBabbleNameAxiom(c) && checker.checkingEffects) {
                    checker.parseBabbleAxiom(c, checker.scopeOf(c), true);
                }
                yield Type.NULL;
            }
            case BabbleParser.ForExpressionContext c -> visitForExpression(c, expected, role);
            case BabbleParser.TryExpressionContext c ->
                    checker.exceptionTyper.typeTry(c, expected, role);
            case BabbleParser.ClassExpressionContext c -> visitClassExpression(c, expected, role);
            case BabbleParser.InterfaceExpressionContext c ->
                    visitInterfaceExpression(c, expected, role);
            case BabbleParser.ObjectExpressionContext c -> visitObjectExpression(c, expected, role);
            case BabbleParser.ThisExpressionContext c -> visitThisExpression(c, expected, role);
            case BabbleParser.SuperExpressionContext c -> visitSuperExpression(c, expected, role);
            case BabbleParser.SelectorContext c -> visitSelector(c, expected, role);
            case BabbleParser.NonNullAssertionContext c -> visitNonNullAssertion(c, expected, role);
            case BabbleParser.CoreScalarRefContext c -> visitCoreScalarRef(c, expected, role);
            case BabbleParser.CoreCollectionContext c ->
                    new Type.CoreCollection(c.coreCollectionKeyword().getText());
            case BabbleParser.CoreExceptionRefContext c ->
                    new Type.JavaClassValue(org.babblelang.runtime.BabbleException.class);
            case BabbleParser.TypeApplyContext c ->
                    checker.callSolver.applyTypes(
                            c, checker.synth(c.expression(), TypeChecker.Role.CALLEE));
            case BabbleParser.CallContext c -> visitCall(c, expected, role);
            case BabbleParser.BlockExpressionContext c -> visitBlockExpression(c, expected, role);
            case BabbleParser.BlockContext c -> visitBlock(c, expected, role);
            case BabbleParser.NegateContext c -> visitNegate(c, expected, role);
            case BabbleParser.BooleanNotContext c -> {
                checker.synth(c.expression());
                yield Type.BOOL;
            }
            case BabbleParser.BinaryOpContext c ->
                    checker.operators.binaryOp(c, checker.synth(c.left), checker.synth(c.right));
            case BabbleParser.RangeExpressionContext c -> visitRangeExpression(c, expected, role);
            case BabbleParser.IsExpressionContext c -> visitIsExpression(c, expected, role);
            case BabbleParser.AsExpressionContext c -> visitAsExpression(c, expected, role);
            case BabbleParser.BooleanOpContext c -> visitBooleanOp(c, expected, role);
            case BabbleParser.FunctionLiteralContext c -> visitFunctionLiteral(c, expected, role);
            case BabbleParser.OverloadExpressionContext c ->
                    visitOverloadExpression(c, expected, role);
            case BabbleParser.AssignExpressionContext c -> visitAssignExpression(c, expected, role);
            case BabbleParser.NullContext c -> Type.NULL;
            case BabbleParser.BooleanContext c -> Type.BOOL;
            case BabbleParser.RecurseContext c -> visitRecurse(c, expected, role);
            case BabbleParser.LongLiteralContext c -> checkLongLiteral(c, c.getText(), false);
            case BabbleParser.IntegerContext c -> checkIntLiteral(c, c.getText(), false, expected);
            case BabbleParser.FloatLiteralContext c -> checkFloatLiteral(c, c.getText(), false);
            case BabbleParser.DoubleContext c ->
                    checkDoubleLiteral(c, c.getText(), false, expected);
            case BabbleParser.CharLiteralContext c -> Type.CHAR;
            case BabbleParser.StringContext c -> Type.STRING;
            case BabbleParser.ReturnExpressionContext c -> visitReturnExpression(c, expected, role);
            case BabbleParser.ForeverExpressionContext c ->
                    checker.type(c.expression(), expected, role);
            case BabbleParser.BreakExpressionContext c -> visitBreakExpression(c, expected, role);
            case BabbleParser.ContinueExpressionContext c -> Type.NOTHING;
            case BabbleParser.ThrowExpressionContext c ->
                    checker.exceptionTyper.typeThrow(c, expected, role);
            default -> {
                Type last = null;
                for (int i = 0; i < ctx.getChildCount(); i++) {
                    last =
                            ctx.getChild(i) instanceof ParserRuleContext child
                                    ? typeOf(child, expected, role)
                                    : null;
                }
                yield last;
            }
        };
    }

    private Type visitIfExpr(BabbleParser.IfExprContext ctx, Type expected, TypeChecker.Role role) {
        checker.synth(ctx.test);
        TypeEnv.Refinement refinement = checker.narrowing.extractRefinement(ctx.test);
        TypeEnv baseEnv = checker.env;

        checker.env = baseEnv.apply(refinement.whenTrue());
        Type thenType = checker.type(ctx.thenBlock, expected, role);
        TypeEnv thenEnv = checker.env;

        ParserRuleContext otherwise = ctx.elseBlock != null ? ctx.elseBlock : ctx.elseIf;
        Type elseType;
        TypeEnv elseEnv;
        if (otherwise != null) {
            checker.env = baseEnv.apply(refinement.whenFalse());
            elseType = checker.type(otherwise, expected, role);
            elseEnv = checker.env;
        } else {
            elseType = Type.NULL;
            elseEnv = baseEnv;
        }
        checker.env = TypeEnv.joinBranches(baseEnv, thenEnv, elseEnv);

        boolean used = role.isUsed();
        if (expected != null && expected != Type.ERROR && used) {
            checker.subsumes(ctx.thenBlock, thenType, expected);
            if (otherwise != null) {
                checker.subsumes(otherwise, elseType, expected);
            } else if (!checker.algebra.assignable(Type.NULL, expected)) {
                checker.warn("Type error : expected " + expected + ", got null", ctx);
            }
            return expected;
        }
        Type joined = checker.algebra.join(thenType, elseType);
        if (joined == null) {
            if (!used) {
                return Type.NULL;
            }
            if (!TypeChecker.isUnresolved(thenType) && !TypeChecker.isUnresolved(elseType)) {
                checker.warn(
                        "Type error : the branches of if have different checker.types : "
                                + thenType
                                + " and "
                                + elseType,
                        ctx);
            }
            return Type.ERROR;
        }
        checker.coerce(ctx.thenBlock, thenType, joined);
        checker.coerce(otherwise, elseType, joined);
        return joined;
    }

    private Type visitDefExpression(
            BabbleParser.DefExpressionContext ctx, Type expected, TypeChecker.Role role) {
        LoweringTargets.DefKind kind = checker.pass.analysis.lowering().defTarget(ctx).kind();
        if (kind == LoweringTargets.DefKind.REQUIRED) {
            return Type.NULL;
        }
        Type declared = ctx.type() != null ? checker.annotator.declaredType(ctx) : null;
        Type value = ctx.value != null ? checker.check(ctx.value, declared) : Type.NULL;
        ParserRuleContext errorSite = ctx.value != null ? ctx.value : ctx;
        if (declared != null) {
            if (kind == LoweringTargets.DefKind.DEFAULT
                    && declared != Type.ERROR
                    && ((ctx.value instanceof BabbleParser.FunctionLiteralContext
                                    && !(declared instanceof Type.Function))
                            || (ctx.value instanceof BabbleParser.OverloadExpressionContext
                                    && !(declared instanceof Type.Overloaded)))) {
                if (checker.algebra.assignable(value, declared)) {
                    checker.warn(
                            "An interface default must have a function type : "
                                    + ctx.name.getText(),
                            ctx.type());
                } else {
                    checker.subsumes(errorSite, ctx, value, declared);
                }
                checker.samTargets.remove(ctx);
                return Type.ERROR;
            }
            checker.subsumes(errorSite, ctx, value, declared);
            if ((kind == LoweringTargets.DefKind.MEMBER || kind == LoweringTargets.DefKind.STATIC)
                    && checker.samTargets.containsKey(ctx)) {
                ScopeNode bodyScope = checker.scopeOf(ctx);
                if (bodyScope != null && bodyScope.type != null) {
                    boolean isStatic = kind == LoweringTargets.DefKind.STATIC;
                    TypeNode.Member member =
                            isStatic
                                    ? bodyScope.type.statics.get(ctx.name.getText())
                                    : bodyScope.type.members.get(ctx.name.getText());
                    if (member != null && member.isMethod()) {
                        checker.demoteMemberToField(bodyScope.type, member, ctx, isStatic);
                        checker.pass
                                .analysis
                                .hierarchy()
                                .setShape(bodyScope.type.ctx, bodyScope.type.shape());
                        checker.needsReResolve = true;
                    }
                }
            }
        } else {
            checker.flowInto(
                    ctx,
                    kind == LoweringTargets.DefKind.MEMBER
                            || kind == LoweringTargets.DefKind.STATIC,
                    value,
                    errorSite,
                    ctx);
        }
        return value;
    }

    private Type visitWhileExpression(
            BabbleParser.WhileExpressionContext ctx, Type expected, TypeChecker.Role role) {
        checker.env = checker.env.evictAll(checker.narrowing.assignedLocals(ctx));
        checker.synth(ctx.test);
        TypeEnv afterTest = checker.env;
        TypeEnv.Refinement refinement = checker.narrowing.extractRefinement(ctx.test);
        Type blockExpected =
                (expected == Type.ANY || expected == Type.NULLABLE_ANY) ? expected : null;
        checker.env = afterTest.apply(refinement.whenTrue());
        TypeChecker.LoopContext loop =
                new TypeChecker.LoopContext(
                        expected != null && expected != Type.ERROR && role.isUsed()
                                ? expected
                                : null);
        checker.loopStack.push(loop);
        Type bodyType;
        try {
            bodyType = checker.type(ctx.whileBlock, blockExpected, role);
        } finally {
            checker.loopStack.pop();
        }
        checker.env = TypeEnv.joinBranches(afterTest, checker.env, afterTest);
        return finishLoop(ctx, ctx.whileBlock, "while", bodyType, loop, expected, role);
    }

    private Type visitForExpression(
            BabbleParser.ForExpressionContext ctx, Type expected, TypeChecker.Role role) {
        Type iterType = checker.synth(ctx.iterable);
        TypeChecker.ForIterableInfo info = resolveIterableInfo(iterType, ctx);
        if (info != null) {
            checker.forInfos.put(ctx, info);
        }
        Type elemType = info != null ? info.elemType() : Type.ERROR;
        Type loopVarType = elemType;
        if (ctx.type() != null) {
            Type declared = checker.annotator.annotation(ctx.type(), checker.scopeOf(ctx));
            if (declared != Type.ERROR) {
                if (elemType != Type.ERROR && !checker.algebra.assignable(elemType, declared)) {
                    checker.reportTypeMismatch(elemType, declared, ctx.type());
                }
                int targetRank = TypeAlgebra.numericWideningTargetRank(elemType, declared);
                if (targetRank >= 0) {
                    checker.widened.merge(ctx, targetRank, Math::max);
                }
                if (TypeAlgebra.mayContainBabbleCallable(elemType)) {
                    JavaTypeBridge.JavaSamResolution.Valid valid =
                            checker.resolveSamTargetForFlow(elemType, declared);
                    if (valid != null) {
                        checker.forElemSamTargets.put(ctx, valid.toTarget());
                    }
                }
                loopVarType = declared;
            } else {
                loopVarType = Type.ERROR;
            }
        }
        checker.declarationTypes.put(ctx, loopVarType);
        checker.env = checker.env.evictAll(checker.narrowing.assignedLocals(ctx));
        TypeEnv beforeLoop = checker.env;
        Type blockExpected =
                (expected == Type.ANY || expected == Type.NULLABLE_ANY) ? expected : null;
        TypeChecker.LoopContext loop =
                new TypeChecker.LoopContext(
                        expected != null && expected != Type.ERROR && role.isUsed()
                                ? expected
                                : null);
        checker.loopStack.push(loop);
        Type bodyType;
        try {
            bodyType = checker.type(ctx.forBlock, blockExpected, role);
        } finally {
            checker.loopStack.pop();
        }
        checker.env = TypeEnv.joinBranches(beforeLoop, checker.env, beforeLoop);
        return finishLoop(ctx, ctx.forBlock, "for", bodyType, loop, expected, role);
    }

    private Type finishLoop(
            ParserRuleContext loopCtx,
            BabbleParser.BlockContext loopBlock,
            String loopName,
            Type bodyType,
            TypeChecker.LoopContext loop,
            Type expected,
            TypeChecker.Role role) {
        boolean used = role.isUsed();
        if (expected != null && expected != Type.ERROR && used) {
            if (bodyType != Type.NOTHING) {
                checker.subsumes(loopBlock, bodyType, expected);
            }
            for (int i = 0; i < loop.breaks.size(); i++) {
                checker.subsumes(loop.breaks.get(i).expression(), loop.breakTypes.get(i), expected);
            }
            if (!checker.algebra.assignable(Type.NULL, expected)) {
                checker.warn("Type error : expected " + expected + ", got null", loopCtx);
            }
            return expected;
        }
        Type joined = Type.NULL;
        if (bodyType != Type.NOTHING) {
            joined = checker.algebra.join(bodyType, joined);
        }
        if (joined != null) {
            for (Type bt : loop.breakTypes) {
                joined = checker.algebra.join(bt, joined);
                if (joined == null) {
                    break;
                }
            }
        }
        if (joined == null) {
            if (!used) {
                return Type.NULL;
            }
            if (!TypeChecker.isUnresolved(bodyType)) {
                checker.warn(
                        "Type error : the branches of "
                                + loopName
                                + " have different checker.types : "
                                + bodyType
                                + " and null",
                        loopCtx);
            }
            return Type.ERROR;
        }
        if (bodyType != Type.NOTHING) {
            checker.coerce(loopBlock, bodyType, joined);
        }
        for (int i = 0; i < loop.breaks.size(); i++) {
            checker.coerce(loop.breaks.get(i).expression(), loop.breakTypes.get(i), joined);
        }
        return joined;
    }

    private TypeChecker.ForIterableInfo resolveIterableInfo(
            Type iterType, BabbleParser.ForExpressionContext ctx) {
        if (iterType == null || iterType == Type.ERROR || iterType == Type.NOTHING) {
            return null;
        }
        if (Type.containsNull(iterType)) {
            checker.warn(
                    "Type error : cannot iterate over nullable type " + iterType, ctx.iterable);
            return null;
        }
        TypeChecker.ForIterableInfo resolved = checker.members.resolveNonNullIterable(iterType);
        if (resolved == null) {
            if (Type.rangeElement(iterType) != null) {
                checker.warn(
                        "Not iterable : "
                                + TypeDisplay.display(TypeAlgebra.unwrapFrozen(iterType))
                                + " is not discrete",
                        ctx.iterable);
            } else {
                checker.warn("Not iterable : " + iterType, ctx.iterable);
            }
        }
        return resolved;
    }

    private Type visitClassExpression(
            BabbleParser.ClassExpressionContext ctx, Type expected, TypeChecker.Role role) {
        if (ctx.classParameters() != null) {
            checker.constructorParameters(checker.pass.typeNodes.get(ctx));
        }
        return new Type.ClassValue(checker.pass.typeNodes.get(ctx));
    }

    private Type visitInterfaceExpression(
            BabbleParser.InterfaceExpressionContext ctx, Type expected, TypeChecker.Role role) {
        return new Type.ClassValue(checker.pass.typeNodes.get(ctx));
    }

    private Type visitObjectExpression(
            BabbleParser.ObjectExpressionContext ctx, Type expected, TypeChecker.Role role) {
        return new Type.Instance(checker.pass.typeNodes.get(ctx));
    }

    private Type visitThisExpression(
            BabbleParser.ThisExpressionContext ctx, Type expected, TypeChecker.Role role) {
        Type result = checker.thisType(checker.occurrenceScopes.get(ctx));
        if (result instanceof Type.ClassValue cv
                && cv.node().ctx instanceof BabbleParser.ClassExpressionContext cls
                && checker.inFile(cls)
                && !role.isClassSafe()) {
            checker.nextEscapingTargets.add(cls);
        }
        return result;
    }

    private Type visitSuperExpression(
            BabbleParser.SuperExpressionContext ctx, Type expected, TypeChecker.Role role) {
        return Type.ERROR;
    }

    /**
     * {@code def name = type T} (BIP023) : only as a def's value, which takes no annotation of its
     * own.
     */
    private Type visitTypeAlias(BabbleParser.TypeAliasExpressionContext ctx) {
        if (!(ctx.getParent() instanceof BabbleParser.DefExpressionContext def)
                || def.value != ctx) {
            checker.warn(
                    "A type alias is the value of a def : "
                            + ctx.getText().replaceFirst("^type", "type "),
                    ctx);
            return Type.ERROR;
        }
        if (def.type() != null) {
            checker.warn("A type alias takes no annotation : " + def.name.getText(), def.type());
            return Type.ERROR;
        }
        return checker.annotator.aliasOf(def);
    }

    private Type visitSelector(
            BabbleParser.SelectorContext ctx, Type expected, TypeChecker.Role role) {
        Type result = selectUninstantiated(ctx);
        if (result instanceof Type.TypeAlias) {
            checker.warn("A type alias is not a value : " + ctx.getText(), ctx);
            return Type.ERROR;
        }
        checker.checkSelectorEscape(ctx, result, role);
        if (result instanceof Type.Function fn
                && !fn.typeParameters().isEmpty()
                && !role.isCallee()) {
            result = checker.callSolver.instantiateFunctionValue(fn, ctx, expected);
        }
        if (ctx.safe != null && result != Type.ERROR) {
            return Type.nullable(result);
        }
        return result;
    }

    Type selectUninstantiated(BabbleParser.SelectorContext ctx) {
        String name = ScopeAnalysis.selectorName(ctx);
        BabbleParser.ExpressionContext receiver = ctx.expression();
        if (receiver == null) {
            Object resolved = checker.resolveName(ctx, name, checker.occurrenceScopes.get(ctx));
            if (resolved instanceof ParserRuleContext declaration) {
                Type narrowed = checker.narrowing.narrowedType(declaration);
                return narrowed != null ? narrowed : checker.typeOfDeclaration(declaration);
            }
            Type.Function babbleAx =
                    checker.resolveBabbleAxiom(name, checker.occurrenceScopes.get(ctx));
            if (babbleAx != null) {
                return babbleAx;
            }
            if ("wasm".equals(name)) {
                Type wasmNs = checker.resolveWasmNamespace(checker.occurrenceScopes.get(ctx));
                if (wasmNs != null) {
                    return wasmNs;
                }
            }
            return checker.operators.implicitType(name);
        }
        if (receiver instanceof BabbleParser.SuperExpressionContext superExpr) {
            TypeChecks.Provider provider = checker.pass.superProviders.get(superExpr);
            if (provider != null) {
                Type selfType = checker.thisType(checker.occurrenceScopes.get(ctx));
                if (provider.hasFunction() && provider.owner() instanceof TypeNode parentNode) {
                    TypeNode.Member defaultMember = parentNode.members.get(provider.name());
                    Type fnType =
                            defaultMember != null
                                    ? checker.memberType(parentNode, defaultMember)
                                    : Type.ERROR;
                    if (selfType instanceof Type.Instance selfInst) {
                        Type projected = checker.algebra.projectToBabble(selfInst, parentNode);
                        if (projected instanceof Type.Instance projInst) {
                            return checker.algebra.substitute(
                                    fnType, checker.algebra.instanceEnv(projInst));
                        }
                    }
                    return fnType;
                }
                if (provider.owner() instanceof Class<?> javaInterface) {
                    Type projected = checker.algebra.projectToJava(selfType, javaInterface);
                    List<Type> typeArgs =
                            projected instanceof Type.JavaInstance ji
                                    ? ji.typeArguments()
                                    : List.of();
                    return new Type.JavaMethod(javaInterface, typeArgs, provider.name());
                }
            }
            return Type.ERROR;
        }
        if (ctx.safe != null) {
            Type receiverType = checker.synth(receiver, TypeChecker.Role.SELECTOR_RECEIVER);
            if (TypeChecker.isUnresolved(receiverType)) {
                return Type.ERROR;
            }
            if (!canBeNull(receiverType)) {
                checker.warn("Not nullable : " + receiver.getText(), ctx);
                return Type.ERROR;
            }
            Type target = receiverType == Type.NULL ? Type.NULL : Type.withoutNull(receiverType);
            return checker.members.member(
                    target,
                    name,
                    receiver instanceof BabbleParser.ThisExpressionContext,
                    ctx,
                    receiver);
        }
        return checker.members.member(
                checker.synth(receiver, TypeChecker.Role.SELECTOR_RECEIVER),
                name,
                receiver instanceof BabbleParser.ThisExpressionContext,
                ctx,
                receiver);
    }

    private Type visitCall(BabbleParser.CallContext ctx, Type expected, TypeChecker.Role role) {
        if (ctx.expression() instanceof BabbleParser.SelectorContext safeSelector
                && safeSelector.safe != null) {
            Type callee = this.selectUninstantiated(safeSelector);
            checker.types.put(
                    safeSelector, callee == Type.ERROR ? Type.ERROR : Type.nullable(callee));
            Type result = checker.callSolver.call(callee, ctx, expected, true);
            for (BabbleParser.CallParameterContext argument :
                    ctx.callParameters().callParameter()) {
                checker.synth(argument.expression());
            }
            return result == Type.ERROR ? Type.ERROR : Type.nullable(result);
        }
        Type callee = checker.synth(ctx.expression(), TypeChecker.Role.CALLEE);
        ModuleInterface module = checker.pass.modules.get(ctx);
        Type result;
        if (module != null) {
            for (BabbleParser.CallParameterContext argument :
                    ctx.callParameters().callParameter()) {
                checker.synth(argument.expression());
            }
            result = new Type.Module(module);
        } else if (isUnshadowedImport(ctx.expression())
                && ctx.callParameters().callParameter().size() == 1
                && ScopeAnalysis.callParamName(ctx.callParameters().callParameter(0)) == null
                && ctx.callParameters().callParameter(0).expression()
                        instanceof BabbleParser.StringContext string) {
            checker.synth(string);
            String target = org.babblelang.parser.StringLiterals.decode(string);
            result = target.endsWith(".ba") ? Type.ANY : new Type.JavaPackage(target);
        } else {
            result = checker.callSolver.call(callee, ctx, expected, true);
            for (BabbleParser.CallParameterContext argument :
                    ctx.callParameters().callParameter()) {
                checker.synth(argument.expression());
            }
        }
        return result;
    }

    private boolean isUnshadowedImport(BabbleParser.ExpressionContext expression) {
        if (expression instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
            String name = sel.ID().getText();
            return (name.equals("import") || name.equals("importe"))
                    && checker.resolveName(sel, name, checker.occurrenceScopes.get(sel)) == null;
        }
        return false;
    }

    private Type visitBlockExpression(
            BabbleParser.BlockExpressionContext ctx, Type expected, TypeChecker.Role role) {
        TypeChecker.Role blockRole =
                ctx.block().expression().size() == 1
                        ? role
                        : (role == TypeChecker.Role.STATEMENT
                                ? TypeChecker.Role.STATEMENT
                                : TypeChecker.Role.VALUE);
        return checker.type(ctx.block(), expected, blockRole);
    }

    private Type visitBlock(BabbleParser.BlockContext ctx, Type expected, TypeChecker.Role role) {
        List<BabbleParser.ExpressionContext> statements = ctx.expression();
        if (statements.isEmpty()) {
            return Type.NULL;
        }
        TypeEnv blockEntryEnv = checker.env;
        Type type = Type.NULL;
        try {
            for (int i = 0; i < statements.size(); i++) {
                BabbleParser.ExpressionContext statement = statements.get(i);
                type =
                        i == statements.size() - 1
                                ? checker.type(statement, expected, role)
                                : checker.stmt(statement);
                if (i < statements.size() - 1
                        && statement instanceof BabbleParser.IfExpressionContext ifExprCtx) {
                    BabbleParser.IfExprContext ifCtx = ifExprCtx.ifExpr();
                    Type thenType = checker.types.getOrDefault(ifCtx.thenBlock, Type.ERROR);
                    ParserRuleContext otherwise =
                            ifCtx.elseBlock != null ? ifCtx.elseBlock : ifCtx.elseIf;
                    Type elseType =
                            otherwise != null
                                    ? checker.types.getOrDefault(otherwise, Type.ERROR)
                                    : Type.NULL;
                    if (thenType == Type.NOTHING && elseType != Type.NOTHING) {
                        checker.env =
                                checker.env.apply(
                                        checker.narrowing
                                                .extractRefinement(ifCtx.test)
                                                .whenFalse());
                    } else if (elseType == Type.NOTHING && thenType != Type.NOTHING) {
                        checker.env =
                                checker.env.apply(
                                        checker.narrowing.extractRefinement(ifCtx.test).whenTrue());
                    }
                }
            }
            return type;
        } finally {
            checker.env = TypeEnv.joinBranches(blockEntryEnv, checker.env, blockEntryEnv);
        }
    }

    /**
     * As {@code Operators.negate} : byte/short/int -> int, long -> long, float -> float, double ->
     * double; folds negated literals.
     */
    private Type visitNegate(BabbleParser.NegateContext ctx, Type expected, TypeChecker.Role role) {
        if (ctx.operand instanceof BabbleParser.IntegerContext literal) {
            Type resolved = checkIntLiteral(ctx, literal.getText(), true, expected);
            checker.types.put(literal, resolved);
            return resolved;
        }
        if (ctx.operand instanceof BabbleParser.LongLiteralContext literal) {
            Type resolved = checkLongLiteral(ctx, literal.getText(), true);
            checker.types.put(literal, resolved);
            return resolved;
        }
        if (ctx.operand instanceof BabbleParser.DoubleContext literal) {
            Type resolved = checkDoubleLiteral(ctx, literal.getText(), true, expected);
            checker.types.put(literal, resolved);
            return resolved;
        }
        if (ctx.operand instanceof BabbleParser.FloatLiteralContext literal) {
            Type resolved = checkFloatLiteral(ctx, literal.getText(), true);
            checker.types.put(literal, resolved);
            return resolved;
        }
        Type operand = checker.synth(ctx.operand);
        if (operand == Type.NOTHING) {
            return Type.NOTHING;
        }
        if (operand instanceof Type.Instance) {
            return checker.operators.operatorCall(
                    operand, Operators.NEGATE, null, null, ctx, ctx.operand.getText());
        }
        if (operand instanceof Type.Union union) {
            if (Type.containsNull(union)) {
                checker.operators.number(operand, ctx.operand);
                return Type.ERROR;
            }
            boolean allNumericOrInstance =
                    union.members().stream()
                            .allMatch(m ->
                                    m instanceof Type.Instance || checker.operators.isNumeric(m));
            if (!allNumericOrInstance) {
                checker.operators.number(operand, ctx.operand);
                return Type.ERROR;
            }
            List<Type> results = new ArrayList<>(union.members().size());
            for (Type m : union.members()) {
                if (m instanceof Type.Instance) {
                    Type r =
                            checker.operators.operatorCall(
                                    m, Operators.NEGATE, null, null, ctx, ctx.operand.getText());
                    if (r == Type.ERROR) {
                        return Type.ERROR;
                    }
                    results.add(r);
                } else {
                    results.add(OperatorTypes.negateResult(m));
                }
            }
            return Type.union(results);
        }
        if (!checker.operators.number(operand, ctx.operand) || TypeChecker.isUnresolved(operand)) {
            return Type.ERROR;
        }
        return OperatorTypes.negateResult(operand);
    }

    private Type visitRangeExpression(
            BabbleParser.RangeExpressionContext ctx, Type expected, TypeChecker.Role role) {
        Type expectedElem = null;
        if (expected != null && expected != Type.ERROR && expected != Type.NOTHING) {
            expectedElem = Type.rangeElement(Type.withoutNull(expected));
        }
        Type left =
                expectedElem != null
                        ? checker.check(ctx.left, expectedElem)
                        : checker.synth(ctx.left);
        // §3.4 : `1..10.step(5)` / `0..<n.reversed()` parses as `1..(10.step(5))` because
        // member
        // access binds tighter than `..`/`..<`. When the bound's receiver lacks the member and
        // `range of T` has it, suggest parenthesizing the range.
        BabbleParser.SelectorContext unparenSel = findUnparenthesizedRangeSelector(ctx.right);
        if (unparenSel != null) {
            String mName = ScopeAnalysis.selectorName(unparenSel);
            if (MemberTypes.RANGE_MEMBER_NAMES.contains(mName)) {
                Type recvType = checker.synth(unparenSel.expression());
                if (recvType != Type.ERROR
                        && !TypeChecker.isUnresolved(recvType)
                        && !checker.members.hasMember(recvType, mName)) {
                    String recvText = unparenSel.expression().getText();
                    String rightText = ctx.right.getText();
                    int dotIdx =
                            unparenSel.getChild(1).getText().equals(".")
                                    ? recvText.length()
                                    : rightText.indexOf("." + mName);
                    String suffix =
                            dotIdx >= 0 && dotIdx < rightText.length()
                                    ? rightText.substring(dotIdx)
                                    : "." + mName;
                    String suggestion =
                            "(" + ctx.left.getText() + ctx.op.getText() + recvText + ")" + suffix;
                    checker.warn(
                            "No such member : "
                                    + mName
                                    + ". Parenthesize the range : "
                                    + suggestion,
                            ctx.right);
                    BabbleParser.ExpressionContext mark = ctx.right;
                    while (mark != null) {
                        checker.types.put(mark, Type.ERROR);
                        if (mark == unparenSel) break;
                        if (mark instanceof BabbleParser.CallContext c) mark = c.expression();
                        else if (mark instanceof BabbleParser.SelectorContext s)
                            mark = s.expression();
                        else break;
                    }
                    return Type.ERROR;
                }
            }
        }
        Type right =
                expectedElem != null
                        ? checker.check(ctx.right, expectedElem)
                        : checker.synth(ctx.right);
        if (left == Type.ERROR
                || right == Type.ERROR
                || TypeChecker.isUnresolved(left)
                || TypeChecker.isUnresolved(right)) {
            return Type.ERROR;
        }
        if (Type.containsNull(left)) {
            checker.warn("Range bound cannot be null : " + left, ctx.left);
            return Type.ERROR;
        }
        if (Type.containsNull(right)) {
            checker.warn("Range bound cannot be null : " + right, ctx.right);
            return Type.ERROR;
        }
        if (Type.isNumericScalar(left) && Type.isNumericScalar(right)) {
            int rank = Math.max(Type.numericRank(left), Type.numericRank(right));
            Type elem = Type.numericOfRank(rank);
            checker.coerce(ctx.left, left, elem);
            checker.coerce(ctx.right, right, elem);
            return new Type.JavaInstance(org.babblelang.runtime.Range.class, List.of(elem));
        }
        if (left == Type.CHAR && right == Type.CHAR) {
            return new Type.JavaInstance(org.babblelang.runtime.Range.class, List.of(Type.CHAR));
        }
        if (OperatorTypes.comparable(left, right)) {
            Type elem;
            if (left.equals(right)) {
                elem = left;
            } else if (checker.algebra.assignable(left, right)) {
                elem = right;
            } else if (checker.algebra.assignable(right, left)) {
                elem = left;
            } else {
                elem = checker.algebra.join(left, right);
            }
            if (elem != null && elem != Type.ERROR) {
                return new Type.JavaInstance(org.babblelang.runtime.Range.class, List.of(elem));
            }
        }
        checker.warn("Not comparable : " + left + " and " + right, ctx);
        return Type.ERROR;
    }

    private static BabbleParser.SelectorContext findUnparenthesizedRangeSelector(
            BabbleParser.ExpressionContext expr) {
        BabbleParser.ExpressionContext cur = expr;
        BabbleParser.SelectorContext innermost = null;
        while (true) {
            if (cur instanceof BabbleParser.CallContext call) {
                cur = call.expression();
            } else if (cur instanceof BabbleParser.SelectorContext sel
                    && sel.expression() != null
                    && sel.safe == null) {
                innermost = sel;
                cur = sel.expression();
            } else {
                break;
            }
        }
        return innermost;
    }

    private Type visitIsExpression(
            BabbleParser.IsExpressionContext ctx, Type expected, TypeChecker.Role role) {
        Type source = checker.synth(ctx.left);
        Type target = checker.resolveIsTarget(ctx);
        if (target != Type.ERROR && checker.isUncheckedArrayCheck(source, target)) {
            checker.analysisWarn("Unchecked cast : " + target, ctx);
        }
        return Type.BOOL;
    }

    private Type visitNonNullAssertion(
            BabbleParser.NonNullAssertionContext ctx, Type expected, TypeChecker.Role role) {
        Type source = checker.synth(ctx.expression());
        if (source == Type.ERROR || source == Type.NOTHING) {
            return source;
        }
        if (source == Type.NULL) {
            checker.warn("Type error : expected any, got null", ctx.expression());
            return Type.ERROR;
        }
        if (!Type.containsNull(source)) {
            checker.analysisWarn("Redundant non-null assertion : " + source, ctx);
            return source;
        }
        Type nonNull = Type.withoutNull(source);
        checker.pass.analysis.lowering().setNonNullAssertionTarget(ctx, nonNull.toString());
        return nonNull;
    }

    private Type visitCoreScalarRef(
            BabbleParser.CoreScalarRefContext ctx, Type expected, TypeChecker.Role role) {
        String name = ctx.coreScalarKeyword().getText();
        Type.Function babbleAx = checker.resolveBabbleAxiom(name, checker.scopeOf(ctx));
        return babbleAx != null ? babbleAx : checker.operators.implicitType(name);
    }

    private Type visitAsExpression(
            BabbleParser.AsExpressionContext ctx, Type expected, TypeChecker.Role role) {
        Type source = checker.synth(ctx.left);
        Type target = checker.annotator.annotation(ctx.right, checker.scopeOf(ctx), true);
        if (target == Type.ERROR) {
            return Type.ERROR;
        }
        if (target != null && target.hasTypeVar()) {
            checker.warn("Cannot cast to type parameter : " + target, ctx.right);
            return Type.ERROR;
        }
        if (containsFunction(target)) {
            checker.warn("Cannot cast to a function type", ctx.right);
            return target;
        }
        if (!checker.algebra.canCast(source, target)) {
            checker.reportTypeMismatch(source, target, ctx.left);
        } else {
            if (checker.algebra.alwaysIs(source, target)) {
                checker.analysisWarn("Redundant cast : " + target, ctx);
            } else if (checker.isUncheckedArrayCheck(source, target)) {
                checker.analysisWarn("Unchecked cast : " + target, ctx);
            }
            JavaTypeBridge.JavaSamResolution.Valid valid =
                    checker.resolveSamTargetForAs(source, target);
            if (valid != null) {
                checker.asSamTargets.put(ctx, valid.toTarget());
            }
        }
        checker.pass.analysis.lowering().setCheckedType(ctx, LoweringAnalyzer.descriptor(target));
        return target;
    }

    private Type visitBooleanOp(
            BabbleParser.BooleanOpContext ctx, Type expected, TypeChecker.Role role) {
        checker.synth(ctx.left);
        TypeEnv saved = checker.env;
        TypeEnv.Refinement leftRef = checker.narrowing.extractRefinement(ctx.left);
        Map<ParserRuleContext, Type> rightGuard =
                ctx.op.getType() == BabbleLexer.AND ? leftRef.whenTrue() : leftRef.whenFalse();
        if (!rightGuard.isEmpty() && checker.inFile(ctx)) {
            checker.pass.analysis.lowering().markLogicRightNarrowed(ctx);
        }
        checker.env = saved.apply(rightGuard);
        try {
            checker.synth(ctx.right);
        } finally {
            checker.env = TypeEnv.joinBranches(saved, checker.env, saved);
        }
        return Type.BOOL;
    }

    private Type visitFunctionLiteral(
            BabbleParser.FunctionLiteralContext ctx, Type expected, TypeChecker.Role role) {
        if (checker.inFile(ctx)) {
            checker.functions.add(ctx);
        }
        Type.Function sig = checker.signature(ctx, expected);
        checker.types.put(ctx, sig);
        if (ctx.functionBlock != null
                && !checker.types.containsKey(ctx.functionBlock)
                && (ctx.type() == null || checker.inferenceStack.isEmpty())) {
            TypeEnv saved = checker.env;
            checker.env = saved.enterCallable();
            try {
                checker.check(ctx.functionBlock, checker.expectedReturnType(ctx));
            } finally {
                checker.env = saved;
            }
        }
        return sig;
    }

    private Type visitOverloadExpression(
            BabbleParser.OverloadExpressionContext ctx, Type expected, TypeChecker.Role role) {
        String ownerName = overloadExprOwnerName(ctx);
        List<Type.Function> alts = new ArrayList<>();
        List<BabbleParser.FunctionLiteralContext> flatLiterals = new ArrayList<>();
        List<BabbleParser.FunctionLiteralContext> pendingBodies = new ArrayList<>();
        boolean failed = false;
        for (BabbleParser.ExpressionContext op : ctx.expression()) {
            if (op instanceof BabbleParser.FunctionLiteralContext lit) {
                if (checker.inFile(lit)) {
                    checker.functions.add(lit);
                }
                boolean litMissing = false;
                for (BabbleParser.FnParamContext param : lit.fnParameters().fnParam()) {
                    if (param.type() == null) {
                        checker.warn("Missing type : " + param.ID().getText(), param);
                        litMissing = true;
                    }
                }
                if (lit.type() == null) {
                    checker.warn("Missing type : return type of overload alternative", lit);
                    litMissing = true;
                }
                if (litMissing) {
                    failed = true;
                    continue;
                }
                Type.Function sig = checker.signature(lit, null);
                checker.types.put(lit, sig);
                alts.add(sig);
                flatLiterals.add(checker.inFile(lit) ? lit : null);
                pendingBodies.add(lit);
            } else {
                Type t = checker.synth(op);
                if (t == Type.ERROR) {
                    failed = true;
                } else if (t instanceof Type.Function fn) {
                    alts.add(fn);
                    BabbleParser.FunctionLiteralContext lit = checker.functionLiterals.get(fn);
                    flatLiterals.add(lit != null && checker.inFile(lit) ? lit : null);
                } else if (t instanceof Type.Overloaded nested) {
                    alts.addAll(nested.alternatives());
                    List<BabbleParser.FunctionLiteralContext> nestedLits =
                            checker.resolveOverloadLiterals(op);
                    if (nestedLits != null && nestedLits.size() == nested.alternatives().size()) {
                        flatLiterals.addAll(nestedLits);
                    } else {
                        for (int i = 0; i < nested.alternatives().size(); i++) {
                            flatLiterals.add(null);
                        }
                    }
                } else {
                    checker.warn("Type error : expected function, got " + t, op);
                    failed = true;
                }
            }
        }
        Type result = Type.ERROR;
        if (!failed) {
            String ambig = OverloadSolver.checkWellFormed(checker.algebra, ownerName, alts);
            if (ambig != null) {
                checker.warn(ambig, ctx);
            } else {
                Type.Overloaded ovType = new Type.Overloaded(alts);
                checker.types.put(ctx, ovType);
                checker.overloadAltLiterals.put(ctx, Collections.unmodifiableList(flatLiterals));
                result = ovType;
            }
        }
        // Type literal bodies after registering the overload set type so recursive calls to the
        // set resolve.
        for (BabbleParser.FunctionLiteralContext lit : pendingBodies) {
            if (lit.functionBlock != null && !checker.types.containsKey(lit.functionBlock)) {
                TypeEnv saved = checker.env;
                checker.env = saved.enterCallable();
                try {
                    checker.check(lit.functionBlock, checker.expectedReturnType(lit));
                } finally {
                    checker.env = saved;
                }
            }
        }
        return result;
    }

    private String overloadExprOwnerName(BabbleParser.OverloadExpressionContext ctx) {
        if (ctx.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == ctx) {
            return def.name.getText();
        }
        return "overload";
    }

    private LValue resolveLValue(BabbleParser.AssignExpressionContext ctx, boolean readCurrent) {
        String name = ScopeAnalysis.assignName(ctx);
        BabbleParser.ExpressionContext receiverExpr = ScopeAnalysis.assignReceiver(ctx);
        if (receiverExpr == null) {
            Object resolved = checker.resolveName(ctx, name, checker.occurrenceScopes.get(ctx));
            if (resolved instanceof ParserRuleContext declaration) {
                Type current = Type.ERROR;
                if (readCurrent) {
                    Type narrowed = checker.narrowing.narrowedType(declaration);
                    current = narrowed != null ? narrowed : checker.typeOfDeclaration(declaration);
                }
                Type declared =
                        ScopeAnalysis.isParamDecl(declaration)
                                ? checker.typeOfDeclaration(declaration)
                                : checker.annotator.declaredType(declaration);
                return new LValue.Local(declaration, declared, current, declared == null);
            }
            return new LValue.Implicit(
                    readCurrent ? checker.operators.implicitType(name) : Type.ERROR);
        }
        Type receiver = checker.synth(receiverExpr, TypeChecker.Role.SELECTOR_RECEIVER);
        Object field = JavaTypeBridge.assignedField(receiver, name);
        if (field instanceof String error) {
            checker.warn(error, ctx);
        } else if (field instanceof JavaTypeBridge.AssignedField assigned) {
            Type declared = assigned.type(receiver, false, checker.algebra);
            Type current = readCurrent ? assigned.type(receiver, true, checker.algebra) : declared;
            return new LValue.JavaField(assigned, declared, current);
        }
        boolean own = receiverExpr instanceof BabbleParser.ThisExpressionContext;
        Type declared = checker.members.assignedMemberType(receiver, name, own, ctx);
        TypeNode.Member member = checker.members.assignedBabbleMember(receiver, name, own);
        if (member != null && member.function != null && checker.inFile(member.function)) {
            checker.nextEscapingTargets.add(member.function);
        }
        Type current = Type.ERROR;
        if (readCurrent) {
            if (declared != null) {
                current = declared;
            } else if (member != null) {
                TypeNode ownerNode =
                        receiver instanceof Type.Instance inst
                                ? inst.node()
                                : (receiver instanceof Type.ClassValue cv ? cv.node() : null);
                current =
                        receiver instanceof Type.Instance inst
                                ? checker.algebra.substitute(
                                        checker.memberType(ownerNode, member),
                                        checker.algebra.instanceEnv(inst))
                                : checker.memberType(ownerNode, member);
            }
        }
        if (declared == null
                && member != null
                && !member.hasFunction
                && member.declaration instanceof BabbleParser.DefExpressionContext def
                && checker.isLocal(def)
                && checker.inFile(def)) {
            return new LValue.Member(def, null, current, true);
        }
        return new LValue.Member(null, declared, current, false);
    }

    private void writeLValue(
            BabbleParser.AssignExpressionContext ctx,
            LValue target,
            Type value,
            ParserRuleContext errorSite) {
        switch (target) {
            case LValue.JavaField jf -> {
                String error = jf.field().functionError(value, ScopeAnalysis.assignName(ctx));
                if (error != null) {
                    checker.warn(error, errorSite);
                } else {
                    checker.subsumes(errorSite, ctx, value, jf.declaredType());
                }
            }
            case LValue.Local local -> {
                if (local.declaredType() != null) {
                    checker.subsumes(errorSite, ctx, value, local.declaredType());
                } else if (local.inferred()) {
                    checker.flowInto(local.declaration(), false, value, errorSite, ctx);
                }
                checker.env = checker.env.evict(local.declaration());
            }
            case LValue.Member member -> {
                if (member.declaredType() != null) {
                    checker.subsumes(errorSite, ctx, value, member.declaredType());
                } else if (member.inferred() && member.declaration() != null) {
                    checker.flowInto(member.declaration(), true, value, errorSite, ctx);
                }
            }
            case LValue.Implicit ignored -> {}
        }
    }

    private Type visitAssignExpression(
            BabbleParser.AssignExpressionContext ctx, Type expected, TypeChecker.Role role) {
        String operator = Operators.compound(ctx.op.getText());
        if (operator != null) {
            return compoundAssignment(ctx, operator);
        }
        LValue target = resolveLValue(ctx, false);
        Type declared =
                switch (target) {
                    case LValue.JavaField jf -> jf.declaredType();
                    case LValue.Local local -> local.declaredType();
                    case LValue.Member member -> member.declaredType();
                    case LValue.Implicit ignored -> null;
                };
        Type value = checker.check(ctx.value, declared);
        writeLValue(ctx, target, value, ctx.value);
        return value;
    }

    /**
     * {@code x op= v} : typed as {@code x = x op v}, the operator's result checked against what the
     * target declares.
     */
    private Type compoundAssignment(BabbleParser.AssignExpressionContext ctx, String operator) {
        LValue target = resolveLValue(ctx, true);
        Type current =
                switch (target) {
                    case LValue.JavaField jf -> jf.currentType();
                    case LValue.Local local -> local.currentType();
                    case LValue.Member member -> member.currentType();
                    case LValue.Implicit imp -> imp.currentType();
                };
        Type value = checker.synth(ctx.value);
        Type result =
                checker.operators.binaryOp(
                        checker.operators.arithmeticToken(operator),
                        operator,
                        ctx,
                        SyntaxText.targetText(ctx),
                        ctx,
                        current,
                        ctx.value.getText(),
                        ctx.value,
                        value);
        if (target instanceof LValue.JavaField jf) {
            checker.subsumes(ctx, result, jf.declaredType());
        } else {
            writeLValue(ctx, target, result, ctx);
        }
        return result;
    }

    private Type visitRecurse(
            BabbleParser.RecurseContext ctx, Type expected, TypeChecker.Role role) {
        BabbleParser.FunctionLiteralContext function = checker.enclosingFunction(ctx);
        if (function == null) {
            return Type.ERROR;
        }
        if (checker.inFile(function) && !role.isCallee()) {
            checker.nextEscapingTargets.add(function);
        }
        if (checker.inferringResult.contains(function)
                || checker.provisionalResults.containsKey(function)) {
            return checker.recursiveSignature(function);
        }
        return checker.signature(function, expected);
    }

    private Type contextualNumericTarget(Type expected) {
        if (expected == null || expected == Type.ERROR) {
            return null;
        }
        Type base = Type.withoutNull(expected);
        return Type.isNumericScalar(base) ? base : null;
    }

    private Type checkIntLiteral(
            ParserRuleContext ctx, String digits, boolean negated, Type expected) {
        String full = negated ? "-" + digits : digits;
        Type target = contextualNumericTarget(expected);
        if (target == Type.BYTE) {
            try {
                checker.resolvedLiterals.put(ctx, Byte.parseByte(full));
            } catch (NumberFormatException e) {
                checker.warn("Out of byte range : " + full, ctx);
            }
            return Type.BYTE;
        }
        if (target == Type.SHORT) {
            try {
                checker.resolvedLiterals.put(ctx, Short.parseShort(full));
            } catch (NumberFormatException e) {
                checker.warn("Out of short range : " + full, ctx);
            }
            return Type.SHORT;
        }
        if (target == Type.LONG) {
            try {
                checker.resolvedLiterals.put(ctx, Long.parseLong(full));
            } catch (NumberFormatException e) {
                checker.warn("Out of long range : " + full, ctx);
            }
            return Type.LONG;
        }
        try {
            int val = Integer.parseInt(full);
            if (negated) {
                checker.resolvedLiterals.put(ctx, val);
            }
        } catch (NumberFormatException e) {
            checker.warn("Integer out of range : " + full, ctx);
        }
        return Type.INT;
    }

    private Type checkLongLiteral(ParserRuleContext ctx, String raw, boolean negated) {
        String digits = raw.substring(0, raw.length() - 1);
        String full = negated ? "-" + digits : digits;
        try {
            long val = Long.parseLong(full);
            if (negated) {
                checker.resolvedLiterals.put(ctx, val);
            }
        } catch (NumberFormatException e) {
            checker.warn("Out of long range : " + (negated ? "-" : "") + raw, ctx);
        }
        return Type.LONG;
    }

    private Type checkFloatLiteral(ParserRuleContext ctx, String raw, boolean negated) {
        String digits = raw.substring(0, raw.length() - 1);
        String full = negated ? "-" + digits : digits;
        float val = Float.parseFloat(full);
        if (Float.isInfinite(val)) {
            checker.warn("Out of float range : " + (negated ? "-" : "") + raw, ctx);
        } else if (negated) {
            checker.resolvedLiterals.put(ctx, val);
        }
        return Type.FLOAT;
    }

    private Type checkDoubleLiteral(
            ParserRuleContext ctx, String raw, boolean negated, Type expected) {
        boolean explicitDouble = raw.endsWith("d") || raw.endsWith("D");
        String digits = explicitDouble ? raw.substring(0, raw.length() - 1) : raw;
        String full = negated ? "-" + digits : digits;
        if (!explicitDouble && contextualNumericTarget(expected) == Type.FLOAT) {
            float fv = Float.parseFloat(full);
            if (Float.isInfinite(fv)) {
                checker.warn("Out of float range : " + (negated ? "-" : "") + raw, ctx);
            } else {
                checker.resolvedLiterals.put(ctx, fv);
            }
            return Type.FLOAT;
        }
        double dv = Double.parseDouble(full);
        if (Double.isInfinite(dv)) {
            checker.warn("Out of double range : " + (negated ? "-" : "") + raw, ctx);
        } else if (negated) {
            checker.resolvedLiterals.put(ctx, dv);
        }
        return Type.DOUBLE;
    }

    private Type visitReturnExpression(
            BabbleParser.ReturnExpressionContext ctx, Type expected, TypeChecker.Role role) {
        BabbleParser.FunctionLiteralContext function = checker.enclosingFunction(ctx);
        Type returnType = function != null ? checker.expectedReturnType(function) : null;
        Type value =
                ctx.expression() != null ? checker.check(ctx.expression(), returnType) : Type.NULL;
        if (function != null && function.type() != null) {
            Type declared =
                    checker.annotator.annotation(function.type(), checker.scopeOf(function));
            checker.subsumes(
                    ctx.expression() != null ? ctx.expression() : ctx, ctx, value, declared);
        }
        return Type.NOTHING;
    }

    private Type visitBreakExpression(
            BabbleParser.BreakExpressionContext ctx, Type expected, TypeChecker.Role role) {
        TypeChecker.LoopContext loop = checker.loopStack.peek();
        if (ctx.expression() != null) {
            Type value = checker.check(ctx.expression(), loop != null ? loop.expected : null);
            if (loop != null) {
                loop.breaks.add(ctx);
                loop.breakTypes.add(value);
            }
        } else if (loop != null) {
            loop.hasBareBreak = true;
        }
        return Type.NOTHING;
    }
}
