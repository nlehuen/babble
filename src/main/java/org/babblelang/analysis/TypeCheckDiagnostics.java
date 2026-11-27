package org.babblelang.analysis;

import java.util.Map;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;

/**
 * Diagnostics, widening provenance notes, missing-type verification, and {@code is}-target /
 * erased-array checks for {@link TypeChecker}.
 */
final class TypeCheckDiagnostics {
    private final TypeChecker checker;

    TypeCheckDiagnostics(TypeChecker checker) {
        this.checker = checker;
    }

    void warn(String message, ParserRuleContext at) {
        if (!inFile(at)) {
            // In a session, typing a name can lead into an earlier input, reported when it was one.
            return;
        }
        if (!message.startsWith("Missing type")
                && !message.startsWith("Unknown type")
                && !message.startsWith("Integer out of range")) {
            String note = wideningNote(at);
            if (note != null) {
                message += note;
            }
        }
        checker.diagnostics.add(
                new TypeChecker.Diagnostic(
                        at.getStart().getLine(),
                        at.getStart().getCharPositionInLine() + 1,
                        message));
    }

    private String wideningNote(ParserRuleContext at) {
        int line = at.getStart().getLine();
        String note = findWideningNote(at, line);
        if (note != null) {
            return note;
        }
        if (at.getParent() instanceof BabbleParser.AssignExpressionContext assign
                && at == assign.value) {
            return assignmentWideningNote(assign, line);
        }
        if (at instanceof BabbleParser.AssignExpressionContext assign) {
            return assignmentWideningNote(assign, line);
        }
        return null;
    }

    /**
     * The first note found in {@code tree}, innermost names first, then the parameters of a call's
     * function.
     */
    private String findWideningNote(ParseTree tree, int diagnosticLine) {
        if (tree instanceof BabbleParser.SelectorContext selector) {
            String note =
                    noteForName(
                            selector,
                            ScopeAnalysis.selectorName(selector),
                            selector.expression(),
                            diagnosticLine);
            if (note != null) {
                return note;
            }
        } else if (tree instanceof BabbleParser.AssignExpressionContext assign) {
            String note = assignmentWideningNote(assign, diagnosticLine);
            if (note != null) {
                return note;
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            String found = findWideningNote(tree.getChild(i), diagnosticLine);
            if (found != null) {
                return found;
            }
        }
        if (tree instanceof BabbleParser.CallContext call
                && checker.types.get(call.expression()) instanceof Type.Function fn
                && checker.functionLiterals.get(fn)
                        instanceof BabbleParser.FunctionLiteralContext lit) {
            for (BabbleParser.FnParamContext param : lit.fnParameters().fnParam()) {
                String note =
                        wideningNote(
                                param.ID().getText(),
                                checker.paramWidenings.get(param),
                                null,
                                diagnosticLine);
                if (note != null) {
                    return note;
                }
            }
        }
        return null;
    }

    private String assignmentWideningNote(
            BabbleParser.AssignExpressionContext assign, int diagnosticLine) {
        return noteForName(
                assign,
                ScopeAnalysis.assignName(assign),
                ScopeAnalysis.assignReceiver(assign),
                diagnosticLine);
    }

    /**
     * Why {@code name} (a local, or a member of {@code receiver}) has a type wider than at the line
     * of the diagnostic : a later call widened the parameter, or a later assignment the slot.
     */
    private String noteForName(
            ParserRuleContext occurrence,
            String name,
            BabbleParser.ExpressionContext receiver,
            int diagnosticLine) {
        ParserRuleContext declaration;
        Map<ParserRuleContext, TypeChecker.Widening> assignments;
        if (receiver == null) {
            ScopeNode scope = checker.occurrenceScopes.get(occurrence);
            declaration =
                    scope != null
                                    && checker.resolveName(occurrence, name, scope)
                                            instanceof ParserRuleContext decl
                            ? decl
                            : null;
            assignments = checker.defWidenings;
        } else {
            TypeNode.Member member =
                    checker.members.targetMember(checker.types.get(receiver), name);
            declaration = member != null ? member.declaration : null;
            assignments = checker.memberWidenings;
        }
        return declaration == null
                ? null
                : wideningNote(
                        name,
                        checker.paramWidenings.get(declaration),
                        assignments.get(declaration),
                        diagnosticLine);
    }

    private static String wideningNote(
            String name,
            TypeChecker.Widening call,
            TypeChecker.Widening assignment,
            int diagnosticLine) {
        if (call != null && call.line() > diagnosticLine) {
            return " ("
                    + name
                    + " is "
                    + call.type()
                    + " because of the call at line "
                    + call.line()
                    + ")";
        }
        if (assignment != null && assignment.line() > diagnosticLine) {
            return " ("
                    + name
                    + " is "
                    + assignment.type()
                    + " because of the assignment at line "
                    + assignment.line()
                    + ")";
        }
        return null;
    }

    void analysisWarn(String message, ParserRuleContext at) {
        if (!inFile(at)) {
            return;
        }
        checker.warnings.add(
                new TypeChecker.Diagnostic(
                        at.getStart().getLine(),
                        at.getStart().getCharPositionInLine() + 1,
                        message));
    }

    boolean inFile(ParserRuleContext ctx) {
        ParserRuleContext root = ctx;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        return root == checker.file;
    }

    /** Types every expression and block, and resolves every annotation, in source order. */
    void walk(ParseTree tree) {
        if (tree instanceof BabbleParser.AxiomExpressionContext axiom) {
            checker.synth(axiom);
            return;
        }
        if (tree instanceof BabbleParser.TypeContext type) {
            // Only annotations are walked to : implements lists, super qualifiers, and is/as
            // targets are handled elsewhere.
            if (type.getParent() instanceof BabbleParser.AsExpressionContext asCtx
                            && asCtx.right == type
                    || type.getParent() instanceof BabbleParser.IsExpressionContext isCtx
                            && isCtx.right == type
                    || type.getParent() instanceof BabbleParser.PatternBindingTypeContext) {
                return;
            }
            checker.annotator.annotation(type, checker.scopeOf(type));
            return;
        }
        if (tree instanceof BabbleParser.TypeListContext list) {
            for (BabbleParser.TypeContext parentType : list.type()) {
                checker.annotator.annotation(parentType, checker.scopeOf(parentType));
            }
            return;
        }
        if (tree instanceof BabbleParser.SuperExpressionContext sup) {
            if (sup.qualifier != null) {
                checker.annotator.annotation(sup.qualifier, checker.scopeOf(sup), true);
            }
            return;
        }
        if (tree instanceof BabbleParser.BlockContext block
                && block.getParent() instanceof BabbleParser.FunctionLiteralContext fn
                && fn.functionBlock == block) {
            TypeEnv saved = checker.env;
            checker.env = saved.enterCallable();
            try {
                checker.check(block, checker.expectedReturnType(fn));
            } finally {
                checker.env = saved;
            }
        } else if (tree instanceof BabbleParser.BlockContext block
                && block.getParent() instanceof BabbleParser.InitExpressionContext initCtx
                && initCtx.initBlock == block) {
            TypeEnv saved = checker.env;
            checker.env = saved.enterCallable();
            try {
                checker.stmt(block);
            } finally {
                checker.env = saved;
            }
        } else if (tree instanceof BabbleParser.ExpressionContext expr
                && expr.getParent() instanceof BabbleParser.FileContext) {
            checker.stmt(expr);
        } else if (tree instanceof BabbleParser.BlockContext block
                && (block.getParent() instanceof BabbleParser.ClassExpressionContext
                        || block.getParent() instanceof BabbleParser.InterfaceExpressionContext
                        || block.getParent() instanceof BabbleParser.ObjectExpressionContext)) {
            checker.stmt(block);
        } else if (tree instanceof BabbleParser.ExpressionContext
                || tree instanceof BabbleParser.BlockContext) {
            checker.synth((ParserRuleContext) tree);
        }
        if (tree instanceof ParserRuleContext parameter
                && ScopeAnalysis.isParamDecl(parameter)
                && ScopeAnalysis.paramDefault(parameter) != null
                && ScopeAnalysis.paramType(parameter) != null) {
            Type declared =
                    checker.annotator.annotation(
                            ScopeAnalysis.paramType(parameter), checker.scopeOf(parameter));
            BabbleParser.ExpressionContext defVal = ScopeAnalysis.paramDefault(parameter);
            Type actual = checker.check(defVal, declared);
            checker.subsumes(defVal, null, actual, declared);
            checker.coerceSam(defVal, actual, declared);
        }
        if (tree instanceof BabbleParser.AssignExpressionContext assign) {
            BabbleParser.ExpressionContext receiver = ScopeAnalysis.assignReceiver(assign);
            if (receiver != null) {
                walk(receiver);
            }
            if (assign.value != null) {
                walk(assign.value);
            }
            return;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            walk(tree.getChild(i));
        }
    }

    void checkMissingTypes(BabbleParser.FileContext file) {
        new BabbleBaseVisitor<Void>() {
            @Override
            public Void visitType(BabbleParser.TypeContext ctx) {
                return null;
            }

            @Override
            public Void visitDefExpression(BabbleParser.DefExpressionContext def) {
                if (def.type() == null && !checker.failedDefs.contains(def)) {
                    LoweringTargets.DefKind kind =
                            checker.pass.analysis.lowering().defTarget(def).kind();
                    Type inferred =
                            (kind == LoweringTargets.DefKind.MEMBER
                                            || kind == LoweringTargets.DefKind.STATIC)
                                    ? checker.inferredMembers.get(def)
                                    : checker.inferredDefs.get(def);
                    if (inferred == null
                            || TypeChecker.isUnresolved(inferred)
                            || inferred == Type.NULL) {
                        warn("Missing type : " + def.name.getText(), def);
                    }
                }
                return super.visitDefExpression(def);
            }

            private void checkParam(ParserRuleContext parameter) {
                Type type = checker.declarationTypes.get(parameter);
                if (ScopeAnalysis.paramType(parameter) == null
                        && (type == null || TypeChecker.isUnresolved(type))
                        && !checker.failedDefs.contains(parameter)) {
                    warn("Missing type : " + ScopeAnalysis.paramId(parameter).getText(), parameter);
                }
            }

            @Override
            public Void visitClassParam(BabbleParser.ClassParamContext parameter) {
                checkParam(parameter);
                return super.visitClassParam(parameter);
            }

            @Override
            public Void visitFnParam(BabbleParser.FnParamContext parameter) {
                checkParam(parameter);
                return super.visitFnParam(parameter);
            }
        }.visit(file);
    }

    Type isTargetType(BabbleParser.IsExpressionContext ctx) {
        return checker.isTargetTypes.get(ctx);
    }

    Type resolveIsTarget(BabbleParser.IsExpressionContext ctx) {
        if (checker.isTargetTypes.containsKey(ctx)) {
            return checker.isTargetTypes.get(ctx);
        }
        BabbleParser.SimpleTypeContext singleSimple = null;
        if (ctx.right.nullableType().size() == 1
                && ctx.right.nullableType(0) instanceof BabbleParser.NonParenTypeContext nonParen
                && nonParen.nonParenNullableType()
                        instanceof BabbleParser.SimpleTypeContext simple) {
            singleSimple = simple;
            if ("void".equals(simple.typeName().getText())) {
                warn("Type error : cannot test 'is void'", simple);
                checker.isTargetTypes.put(ctx, Type.ERROR);
                return Type.ERROR;
            }
            if (simple.OF() != null
                    && !"array".equals(simple.typeName().getText())
                    && !checker.annotator.resolvesToArrayAlias(simple, checker.scopeOf(ctx))) {
                warn(
                        "Cannot test erased type arguments with 'is' : use 'is "
                                + simple.typeName().getText()
                                + "'",
                        simple);
                checker.isTargetTypes.put(ctx, Type.ERROR);
                return Type.ERROR;
            }
        }
        if (singleSimple != null && singleSimple.frozen != null) {
            warn("Cannot test 'frozen' with 'is'", singleSimple);
            checker.isTargetTypes.put(ctx, Type.ERROR);
            return Type.ERROR;
        }
        Type target = checker.annotator.annotation(ctx.right, checker.scopeOf(ctx), true);
        if (target == Type.ERROR) {
            checker.isTargetTypes.put(ctx, Type.ERROR);
            return Type.ERROR;
        }
        if (target.anyMatch(Type.Frozen.class::isInstance)) {
            warn("Cannot test 'frozen' with 'is'", ctx.right);
            checker.isTargetTypes.put(ctx, Type.ERROR);
            return Type.ERROR;
        }
        if (target.hasTypeVar()) {
            warn("Cannot test type parameter with 'is' : " + target, ctx.right);
            checker.isTargetTypes.put(ctx, Type.ERROR);
            return Type.ERROR;
        }
        if (TypeAlgebra.containsFunction(target)) {
            warn("Cannot test a function type with 'is'", ctx.right);
            checker.isTargetTypes.put(ctx, Type.ERROR);
            return Type.ERROR;
        }
        if (singleSimple != null
                && singleSimple.typeName().coreTypeKeyword() != null
                && (singleSimple.nullMod == null
                        || singleSimple.nullMod.getType() != BabbleLexer.QUESTION)
                && singleSimple.OF() == null) {
            checker.pass.analysis.lowering().setIsBuiltin(ctx, singleSimple.typeName().getText());
        }
        checker.pass.analysis.lowering().setCheckedType(ctx, LoweringAnalyzer.descriptor(target));
        checker.isTargetTypes.put(ctx, target);
        return target;
    }

    private static boolean isArrayTypeErasedAtRuntime(Type.Array array) {
        Type elem = array.element();
        if (Type.isPrimitiveScalar(elem)) {
            return false;
        }
        if (elem instanceof Type.Frozen) {
            return true;
        }
        Type base = Type.withoutNull(elem);
        if (Type.containsNull(elem) && Type.isPrimitiveScalar(base)) {
            return false;
        }
        if (!Type.containsNull(elem)) {
            return true;
        }
        if (base instanceof Type.JavaInstance ji) {
            for (Type arg : ji.typeArguments()) {
                if (!Type.NULLABLE_ANY.equals(arg)) {
                    return true;
                }
            }
            return false;
        }
        if (base instanceof Type.Array inner) {
            return isArrayTypeErasedAtRuntime(inner);
        }
        return base != Type.STRING && base != Type.ANY;
    }

    private static boolean containsErasedArray(Type target) {
        if (target == null
                || target == Type.ERROR
                || target == Type.NOTHING
                || target == Type.NULL) {
            return false;
        }
        return switch (target) {
            case Type.Array a -> isArrayTypeErasedAtRuntime(a);
            case Type.Union u ->
                    u.members().stream().anyMatch(TypeCheckDiagnostics::containsErasedArray);
            case Type.Intersection i ->
                    i.members().stream().anyMatch(TypeCheckDiagnostics::containsErasedArray);
            case Type.Frozen f -> containsErasedArray(f.of());
            default -> false;
        };
    }

    boolean isUncheckedArrayCheck(Type source, Type target) {
        if (!containsErasedArray(target)) {
            return false;
        }
        for (Type m : TypeAlgebra.alternatives(source)) {
            if (m != Type.NULL
                    && checker.algebra.overlapsErased(m, target)
                    && !checker.algebra.assignable(m, target)) {
                return true;
            }
        }
        return false;
    }
}
