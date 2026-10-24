package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The analysis run on every file before either mode executes it. This first pass walks
 * the scopes ; {@link TypeChecks} then resolves what needs the whole file.
 * <ul>
 * <li>It computes the names each scope defines, so that they can be declared when the
 * scope is entered (hoisting), and the members of classes, objects and interfaces.</li>
 * <li>It rejects two definitions of one name in a scope, and a definition in a
 * {@code while} test, which would run on every iteration.</li>
 * <li>It checks where {@code return}, {@code this}, {@code super}, {@code private} and
 * header {@code def}s may appear.</li>
 * </ul>
 * Scopes are opened by the file, function literals (shared by the parameters and the top
 * level of the body), packages, class and object bodies, interface bodies, static blocks,
 * and every other block : a block expression, an {@code if} branch or a {@code while}
 * body. The top-level {@code def}s of a class, object, interface or static body declare
 * members, which bind no unqualified name.
 */
public final class BlockScopes extends BabbleBaseVisitor<Void> {
    private enum ReturnTarget {
        NONE, FUNCTION, DEFAULT, CLASS_BODY
    }

    record PendingSuper(BabbleParser.SuperExpressionContext ctx, TypeNode owner, ScopeNode scope, String member) {
    }

    record PendingAccess(ParserRuleContext ctx, BabbleParser.ExpressionContext receiver, String member, boolean assignment, ScopeNode scope) {
    }

    record PendingAssignment(String name, ScopeNode scope) {
    }

    /** A use of a lexical name : a plain name, an unqualified assignment, {@code this} or {@code recurse}. */
    record Occurrence(ParserRuleContext ctx, String name, ScopeNode scope) {
    }

    final ScopeAnalysis analysis = new ScopeAnalysis();
    final List<TypeNode> types = new ArrayList<>();
    final List<PendingSuper> supers = new ArrayList<>();
    final List<PendingAccess> accesses = new ArrayList<>();
    final List<PendingAssignment> assignments = new ArrayList<>();
    final Map<ParserRuleContext, ScopeNode> definitionScopes = new HashMap<>();
    final Map<ParserRuleContext, TypeNode> typeNodes = new HashMap<>();
    final Map<ParserRuleContext, ScopeNode> scopeNodes = new HashMap<>();
    final List<Occurrence> occurrences = new ArrayList<>();
    final List<BabbleParser.PackageExpressionContext> packages = new ArrayList<>();
    final Map<BabbleParser.PackageExpressionContext, ScopeNode> packageScopes = new HashMap<>();

    private final Deque<ScopeNode> scopes = new ArrayDeque<>();
    private ReturnTarget returnTarget = ReturnTarget.NONE;

    private BlockScopes() {
    }

    public static ScopeAnalysis analyze(BabbleParser.FileContext file) {
        return analyze(file, false);
    }

    /**
     * @param typecheck whether to run the type checker, whose diagnostics are warnings
     */
    public static ScopeAnalysis analyze(BabbleParser.FileContext file, boolean typecheck) {
        BlockScopes pass = new BlockScopes();
        pass.inScope(ScopeNode.Kind.FILE, file, null, () -> pass.visitChildren(file));
        new TypeChecks(pass).run();
        pass.analysis.setResolution(new Resolver(pass).run());
        if (typecheck) {
            try {
                new TypeChecker(pass).run(file);
            } catch (RuntimeException e) {
                // In warn mode, the checker never stops a script that would otherwise run.
                pass.analysis.warn("The type checker failed : " + e, file.getStart().getLine());
            }
        }
        return pass.analysis;
    }

    private ScopeNode current() {
        return scopes.element();
    }

    private ScopeNode inScope(ScopeNode.Kind kind, ParserRuleContext owner, TypeNode type, Runnable body) {
        ScopeNode scope = new ScopeNode(kind, scopes.peek(), owner, type);
        scopeNodes.put(owner, scope);
        scopes.push(scope);
        try {
            body.run();
        } finally {
            scopes.pop();
        }
        analysis.setDeclared(owner, scope.declared.toArray(new String[0]));
        return scope;
    }

    private void inReturnTarget(ReturnTarget target, Runnable body) {
        ReturnTarget outer = returnTarget;
        returnTarget = target;
        try {
            body.run();
        } finally {
            returnTarget = outer;
        }
    }

    private static AnalysisException error(String message, Token token) {
        return new AnalysisException(message, token.getLine());
    }

    private static AnalysisException error(String message, ParserRuleContext ctx) {
        return new AnalysisException(message, ctx.getStart().getLine());
    }

    /** Declares a lexical name in the current scope. */
    private void declare(Token name, ParserRuleContext declaration, boolean hoisted) {
        ScopeNode scope = current();
        String text = name.getText();
        if (scope.names.containsKey(text)) {
            throw error("Name already defined : " + text, name);
        }
        scope.names.put(text, declaration);
        definitionScopes.put(declaration, scope);
        if (hoisted) {
            scope.declared.add(text);
        }
    }

    private static TypeNode.Member addMember(Map<String, TypeNode.Member> members, Token name, int flags,
                                             ParserRuleContext declaration, BabbleParser.FunctionLiteralContext function) {
        String text = name.getText();
        if (members.containsKey(text)) {
            throw error("Member already defined : " + text, name);
        }
        TypeNode.Member member = new TypeNode.Member(text, flags, members.size(), declaration, function);
        members.put(text, member);
        return member;
    }

    /**
     * {@code def} and {@code private} on parameters are only for class headers, and a type
     * annotation can contain parameter declarations too, inside a function type.
     */
    private static void checkPlainParameters(ParseTree tree) {
        if (tree == null) {
            return;
        }
        if (tree instanceof BabbleParser.ParameterDeclarationContext parameter) {
            checkPlainParameter(parameter);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            ParseTree child = tree.getChild(i);
            // Default values are visited, and checked, as expressions.
            if (!(child instanceof BabbleParser.ExpressionContext)) {
                checkPlainParameters(child);
            }
        }
    }

    private static void checkPlainParameter(BabbleParser.ParameterDeclarationContext parameter) {
        if (parameter.DEF() != null) {
            throw error("def parameters are only allowed in a class header", parameter.DEF().getSymbol());
        }
        if (parameter.PRIVATE() != null) {
            throw error("private only applies to members", parameter.PRIVATE().getSymbol());
        }
    }

    private static String typeName(ParserRuleContext ctx, String anonymous) {
        String name = ScopeAnalysis.definedName(ctx);
        return name != null ? name : anonymous;
    }

    private TypeNode newType(ParserRuleContext ctx, ClassShape.Kind kind, String name, BabbleParser.TypeListContext typeList) {
        List<BabbleParser.TypeContext> parentTypes = typeList != null ? typeList.type() : List.of();
        TypeNode type = new TypeNode(ctx, kind, name, current(), parentTypes);
        types.add(type);
        typeNodes.put(ctx, type);
        for (BabbleParser.TypeContext parentType : parentTypes) {
            checkPlainParameters(parentType);
        }
        return type;
    }

    // ---------------------------------------------------------------- scopes

    @Override
    public Void visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
        TypeNode memberOf = null;
        boolean staticMethod = false;
        if (ctx.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == ctx) {
            ScopeNode scope = current();
            if (scope.kind == ScopeNode.Kind.CLASS_BODY || scope.kind == ScopeNode.Kind.INTERFACE_BODY) {
                memberOf = scope.type;
            } else if (scope.kind == ScopeNode.Kind.STATIC_BODY) {
                memberOf = scope.type;
                staticMethod = true;
            }
        }
        checkPlainParameters(ctx.type());
        TypeNode owner = memberOf;
        boolean isStatic = staticMethod;
        inReturnTarget(ReturnTarget.FUNCTION, () -> {
            inScope(ScopeNode.Kind.FUNCTION, ctx, null, () -> {
                current().memberOf = owner;
                current().staticMethod = isStatic;
                List<String> parameters = new ArrayList<>();
                int required = 0;
                for (BabbleParser.ParameterDeclarationContext parameter : ctx.parametersDeclaration().parameterDeclaration()) {
                    checkPlainParameter(parameter);
                    checkPlainParameters(parameter.type());
                    declare(parameter.ID().getSymbol(), parameter, false);
                    parameters.add(parameter.ID().getText());
                    if (parameter.defaultValue != null) {
                        inReturnTarget(ReturnTarget.DEFAULT, () -> visit(parameter.defaultValue));
                    } else {
                        required++;
                    }
                }
                analysis.setParameters(ctx, parameters.toArray(new String[0]), required);
                visitChildren(ctx.functionBlock);
            });
        });
        return null;
    }

    @Override
    public Void visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
        current().packages.computeIfAbsent(ctx.name.getText(), n -> new ArrayList<>()).add(ctx);
        packages.add(ctx);
        packageScopes.put(ctx, current());
        inScope(ScopeNode.Kind.PACKAGE, ctx, null, () -> visitChildren(ctx.packageBlock));
        return null;
    }

    @Override
    public Void visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
        boolean value = false;
        if (ctx.modifier != null) {
            if (!ctx.modifier.getText().equals("value")) {
                throw error("Unknown class modifier : " + ctx.modifier.getText(), ctx.modifier);
            }
            value = true;
        }
        TypeNode type = newType(ctx, value ? ClassShape.Kind.VALUE_CLASS : ClassShape.Kind.CLASS, typeName(ctx, "class"), ctx.typeList());
        boolean isValue = value;

        inReturnTarget(ReturnTarget.CLASS_BODY, () -> {
            inScope(ScopeNode.Kind.CLASS_BODY, ctx, type, () -> {
                Set<String> parameterNames = new HashSet<>();
                if (ctx.parametersDeclaration() != null) {
                    for (BabbleParser.ParameterDeclarationContext parameter : ctx.parametersDeclaration().parameterDeclaration()) {
                        Token name = parameter.ID().getSymbol();
                        checkPlainParameters(parameter.type());
                        if (!parameterNames.add(name.getText())) {
                            throw error("Name already defined : " + name.getText(), name);
                        }
                        boolean member = parameter.DEF() != null || isValue;
                        if (parameter.PRIVATE() != null && !member) {
                            throw error("private only applies to members", parameter.PRIVATE().getSymbol());
                        }
                        if (isValue && parameter.DEF() != null) {
                            analysis.warn("def is redundant on a value class parameter : " + name.getText(), name.getLine());
                        }
                        type.parameters.add(name.getText());
                        if (member) {
                            int flags = parameter.PRIVATE() != null ? ClassShape.PRIVATE : 0;
                            type.parameterMembers.add(addMember(type.members, name, flags, parameter, null).index);
                        } else {
                            type.parameterMembers.add(-1);
                            declare(name, parameter, false);
                        }
                        if (parameter.defaultValue != null) {
                            visit(parameter.defaultValue);
                        } else {
                            type.requiredParameters++;
                        }
                    }
                }
                visitChildren(ctx.classBlock);
            });
            if (ctx.staticBlock != null) {
                inScope(ScopeNode.Kind.STATIC_BODY, ctx.staticBlock, type, () -> visitChildren(ctx.staticBlock));
            }
        });

        for (TypeNode.Member member : type.members.values()) {
            if (isValue && (member.name.equals("equals") || member.name.equals("hashCode"))) {
                throw error(member.name + " is generated for value classes", member.declaration);
            }
            if (!isValue && member.name.equals("equals")) {
                analysis.warn("equals has no effect on == : use a value class", member.declaration.getStart().getLine());
            }
        }
        return null;
    }

    @Override
    public Void visitObjectExpression(BabbleParser.ObjectExpressionContext ctx) {
        TypeNode type = newType(ctx, ClassShape.Kind.OBJECT, "object", ctx.typeList());
        inReturnTarget(ReturnTarget.CLASS_BODY,
                () -> inScope(ScopeNode.Kind.CLASS_BODY, ctx, type, () -> visitChildren(ctx.createBlock)));
        return null;
    }

    @Override
    public Void visitInterfaceExpression(BabbleParser.InterfaceExpressionContext ctx) {
        TypeNode type = newType(ctx, ClassShape.Kind.INTERFACE, typeName(ctx, "interface"), ctx.typeList());
        inReturnTarget(ReturnTarget.CLASS_BODY, () -> {
            inScope(ScopeNode.Kind.INTERFACE_BODY, ctx, type, () -> {
                for (BabbleParser.ExpressionContext statement : ctx.interfaceBlock.expression()) {
                    if (!(statement instanceof BabbleParser.DefExpressionContext)) {
                        throw error("An interface can only declare members", statement);
                    }
                    visit(statement);
                }
            });
            if (ctx.staticBlock != null) {
                inScope(ScopeNode.Kind.STATIC_BODY, ctx.staticBlock, type, () -> visitChildren(ctx.staticBlock));
            }
        });
        return null;
    }

    /**
     * Only reached for blocks that open a scope of their own : function, package, class,
     * object and interface bodies are visited through their owner.
     */
    @Override
    public Void visitBlock(BabbleParser.BlockContext ctx) {
        inScope(ScopeNode.Kind.BLOCK, ctx, null, () -> visitChildren(ctx));
        return null;
    }

    // ---------------------------------------------------------------- definitions

    @Override
    public Void visitDefExpression(BabbleParser.DefExpressionContext ctx) {
        ScopeNode scope = current();
        if (scope.whileTests > 0) {
            throw error("Definition in a while test : " + ctx.name.getText(), ctx.name);
        }
        checkPlainParameters(ctx.type());
        boolean isPrivate = ctx.PRIVATE() != null;
        BabbleParser.FunctionLiteralContext function = ctx.value instanceof BabbleParser.FunctionLiteralContext literal ? literal : null;
        int flags = (function != null ? ClassShape.METHOD : 0) | (isPrivate ? ClassShape.PRIVATE : 0);

        switch (scope.kind) {
            case CLASS_BODY -> {
                TypeNode.Member member = addMember(scope.type.members, ctx.name, flags, ctx, function);
                analysis.setDefTarget(ctx, new ScopeAnalysis.DefTarget(ScopeAnalysis.DefKind.MEMBER, member.index));
            }
            case STATIC_BODY -> {
                TypeNode.Member member = addMember(scope.type.statics, ctx.name, flags, ctx, function);
                analysis.setDefTarget(ctx, new ScopeAnalysis.DefTarget(ScopeAnalysis.DefKind.STATIC, member.index));
            }
            case INTERFACE_BODY -> {
                if (isPrivate) {
                    throw error("Interface members cannot be private : " + ctx.name.getText(), ctx.PRIVATE().getSymbol());
                }
                if (ctx.value != null && function == null) {
                    throw error("An interface default must be a function literal : " + ctx.name.getText(), ctx.name);
                }
                TypeNode.Member member = addMember(scope.type.members, ctx.name, flags, ctx, function);
                analysis.setDefTarget(ctx, new ScopeAnalysis.DefTarget(
                        function != null ? ScopeAnalysis.DefKind.DEFAULT : ScopeAnalysis.DefKind.REQUIRED, member.index));
            }
            default -> {
                if (isPrivate) {
                    throw error("private only applies to members", ctx.PRIVATE().getSymbol());
                }
                declare(ctx.name, ctx, true);
            }
        }
        if (ctx.value != null) {
            visit(ctx.value);
        }
        return null;
    }

    @Override
    public Void visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
        ScopeNode scope = current();
        scope.whileTests++;
        try {
            visit(ctx.test);
        } finally {
            scope.whileTests--;
        }
        visit(ctx.whileBlock);
        return null;
    }

    @Override
    public Void visitReturnExpression(BabbleParser.ReturnExpressionContext ctx) {
        switch (returnTarget) {
            case NONE -> throw error("return outside of a function", ctx);
            case DEFAULT -> throw error("return in a parameter default", ctx);
            case CLASS_BODY -> throw error("return in a class body", ctx);
            case FUNCTION -> visit(ctx.expression());
        }
        return null;
    }

    // ---------------------------------------------------------------- this, super, members

    /** The scope that gives {@code this} its meaning : a class, object, interface or static body. */
    static ScopeNode thisScope(ScopeNode scope) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            if (s.kind == ScopeNode.Kind.CLASS_BODY || s.kind == ScopeNode.Kind.INTERFACE_BODY || s.kind == ScopeNode.Kind.STATIC_BODY) {
                return s;
            }
        }
        return null;
    }

    @Override
    public Void visitRecurse(BabbleParser.RecurseContext ctx) {
        occurrences.add(new Occurrence(ctx, "$recurse", current()));
        return null;
    }

    @Override
    public Void visitThisExpression(BabbleParser.ThisExpressionContext ctx) {
        occurrences.add(new Occurrence(ctx, "this", current()));
        if (thisScope(current()) == null) {
            throw error("this outside of a class", ctx);
        }
        // A value class under construction must not let "this" escape : outside of its
        // methods, it may only read fields that are already defined.
        for (ScopeNode s = current(); s != null && s.kind != ScopeNode.Kind.FUNCTION; s = s.parent) {
            if (s.kind == ScopeNode.Kind.CLASS_BODY) {
                if (s.type.kind == ClassShape.Kind.VALUE_CLASS && !isFieldRead(ctx)) {
                    throw error("this cannot escape a value class under construction", ctx);
                }
                break;
            }
            if (s.kind == ScopeNode.Kind.STATIC_BODY || s.kind == ScopeNode.Kind.INTERFACE_BODY) {
                break;
            }
        }
        return null;
    }

    /** {@code this.x}, not called : or the target of an assignment, reported as read-only later. */
    private static boolean isFieldRead(BabbleParser.ThisExpressionContext ctx) {
        if (ctx.getParent() instanceof BabbleParser.AssignExpressionContext assign && assign.namespace == ctx) {
            return true;
        }
        return ctx.getParent() instanceof BabbleParser.SelectorContext selector
                && selector.expression() == ctx
                && !(selector.getParent() instanceof BabbleParser.CallContext call && call.expression() == selector);
    }

    @Override
    public Void visitSuperExpression(BabbleParser.SuperExpressionContext ctx) {
        if (!(ctx.getParent() instanceof BabbleParser.SelectorContext selector && selector.expression() == ctx
                && selector.getParent() instanceof BabbleParser.CallContext call && call.expression() == selector)) {
            throw error("super must be called directly", ctx);
        }
        // The outermost function literal before the type body must be one of its methods.
        ScopeNode function = null;
        TypeNode owner = null;
        for (ScopeNode s = current(); s != null; s = s.parent) {
            if (s.kind == ScopeNode.Kind.FUNCTION) {
                function = s;
            } else if (s.kind == ScopeNode.Kind.CLASS_BODY || s.kind == ScopeNode.Kind.INTERFACE_BODY) {
                if (function != null && function.parent == s && function.memberOf == s.type && !function.staticMethod) {
                    owner = s.type;
                }
                break;
            } else if (s.kind == ScopeNode.Kind.STATIC_BODY) {
                break;
            }
        }
        if (owner == null) {
            throw error("super outside of a method", ctx);
        }
        if (ctx.qualifier != null) {
            checkPlainParameters(ctx.qualifier);
        }
        supers.add(new PendingSuper(ctx, owner, current(), selector.ID().getText()));
        return null;
    }

    @Override
    public Void visitSelector(BabbleParser.SelectorContext ctx) {
        BabbleParser.ExpressionContext receiver = ctx.expression();
        if (receiver == null) {
            occurrences.add(new Occurrence(ctx, ctx.ID().getText(), current()));
        }
        if (receiver != null && !(receiver instanceof BabbleParser.SuperExpressionContext)) {
            accesses.add(new PendingAccess(ctx, receiver, ctx.ID().getText(), false, current()));
        }
        return visitChildren(ctx);
    }

    @Override
    public Void visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
        if (ctx.namespace == null) {
            assignments.add(new PendingAssignment(ctx.name.getText(), current()));
            occurrences.add(new Occurrence(ctx, ctx.name.getText(), current()));
        } else {
            accesses.add(new PendingAccess(ctx, ctx.namespace, ctx.name.getText(), true, current()));
        }
        return visitChildren(ctx);
    }
}
