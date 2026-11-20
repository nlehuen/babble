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
import java.util.LinkedHashMap;
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

    record PendingAssignment(BabbleParser.AssignExpressionContext ctx, String name, ScopeNode scope) {
    }

    /** {@code import("file.ba")} : a static import (docs/design/bip005_modules.md §4.2). */
    record PendingImport(BabbleParser.CallContext ctx, String name, ScopeNode scope) {
    }

    /** A use of a lexical name : a plain name, an unqualified assignment, {@code this} or {@code recurse}. */
    record Occurrence(ParserRuleContext ctx, String name, ScopeNode scope) {
    }

    record PendingEffectDecl(BabbleParser.EffectDeclarationContext ctx, ScopeNode scope) {
    }

    final ScopeAnalysis analysis = new ScopeAnalysis();
    final List<TypeNode> types = new ArrayList<>();
    final List<PendingSuper> supers = new ArrayList<>();
    final List<PendingAccess> accesses = new ArrayList<>();
    final List<PendingAssignment> assignments = new ArrayList<>();
    final Map<ParserRuleContext, ScopeNode> definitionScopes = new HashMap<>();
    /**
     * The {@code axiom def}s of each scope (BIP019-D8) : names given a type without a value, which
     * comes from outside the program ; they declare nothing at runtime.
     */
    final Map<ScopeNode, Map<String, BabbleParser.AxiomDefExpressionContext>> axiomDefs = new HashMap<>();
    final Map<ParserRuleContext, TypeNode> typeNodes = new HashMap<>();
    final Map<ParserRuleContext, ScopeNode> scopeNodes = new HashMap<>();
    final List<Occurrence> occurrences = new ArrayList<>();
    final List<PendingImport> imports = new ArrayList<>();
    final List<PendingEffectDecl> effectDeclarations = new ArrayList<>();
    /** The interfaces of the files imported statically, by import call. */
    final Map<BabbleParser.CallContext, ModuleInterface> modules = new LinkedHashMap<>();

    /** The interactive session the file is an input of, or null. */
    final AnalysisSession session;
    final HostEffects hostEffects;
    final Map<BabbleParser.SuperExpressionContext, TypeChecks.Provider> superProviders = new HashMap<>();
    /** The file's type and effect summary, once analysed (what a session keeps of it). */
    TypedModuleSummary summary;
    ScopeNode fileScope;

    private final Deque<ScopeNode> scopes = new ArrayDeque<>();
    private ReturnTarget returnTarget = ReturnTarget.NONE;
    private int loopDepth = 0;

    /** The key of the module analysed, its types' ids derive from, or null : see {@link ModuleLoader#moduleKey}. */
    private String moduleKey;
    /** How many types of each name the module has so far, for their ids. */
    private final Map<String, Integer> typeNames = new HashMap<>();

    private BlockScopes(AnalysisSession session, HostEffects hostEffects) {
        this.session = session;
        this.hostEffects = hostEffects != null ? hostEffects : HostEffects.DEFAULT;
        if (session != null) {
            // What earlier inputs defined is found like what the file defines.
            typeNodes.putAll(session.typeNodes);
            definitionScopes.putAll(session.definitionScopes);
            modules.putAll(session.modules);
            if (session.scope != null) {
                scopes.push(session.scope);
            }
        }
    }

    public static ScopeAnalysis analyze(BabbleParser.FileContext file) {
        return analyze(file, null);
    }

    /**
     * @param session the interactive session the file is an input of, whose earlier inputs
     *                it sees the definitions of, or null. The session only changes when
     *                the result is {@link AnalysisSession#commit committed}.
     */
    public static ScopeAnalysis analyze(BabbleParser.FileContext file, AnalysisSession session) {
        return analyze(file, session, null);
    }

    /**
     * @param loader what loads the files the file imports statically, or null to leave
     *               every import to runtime
     */
    public static ScopeAnalysis analyze(BabbleParser.FileContext file, AnalysisSession session, ModuleLoader loader) {
        return analyze(file, session, loader, HostEffects.DEFAULT);
    }

    public static ScopeAnalysis analyze(BabbleParser.FileContext file, AnalysisSession session, ModuleLoader loader, HostEffects hostEffects) {
        BlockScopes pass = new BlockScopes(session, hostEffects);
        pass.moduleKey = loader != null ? loader.moduleKey() : null;
        pass.analysis.setPass(pass);
        if (session != null) {
            pass.analysis.setSession(session);
        }
        pass.fileScope = pass.inScope(ScopeNode.Kind.FILE, file, null, () -> pass.visitChildren(file));
        // The exported names are known : a file importing this one back, in a cycle, sees them.
        ModuleInterface self = new ModuleInterface(pass);
        if (loader != null) {
            loader.started(self);
            for (PendingImport pending : pass.imports) {
                // A script that defines its own import calls it, not the implicit one.
                if (TypeChecks.resolveName("import", pending.scope()) == null) {
                    ModuleInterface imported = loader.load(pending.name(), pending.ctx().getStart().getLine());
                    pass.modules.put(pending.ctx(), imported);
                    self.addDependency(imported);
                }
            }
        }
        pass.analysis.setImportedModules(pass.modules.values());
        TypeChecks checks = new TypeChecks(pass);
        checks.run();
        self.complete(checks);
        pass.analysis.setModuleInterface(self);
        pass.analysis.setResolution(new Resolver(pass).run());
        TypeChecker checker = new TypeChecker(pass);
        checker.run(file);
        pass.summary = checker.summary();
        self.typed(pass.summary);
        // The shapes now carry the declared types of members and statics.
        for (TypeNode type : pass.types) {
            pass.analysis.setShape(type.ctx, type.shape());
            if (session == null) {
                type.detachAst();
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
        int outerLoopDepth = loopDepth;
        returnTarget = target;
        loopDepth = 0;
        try {
            body.run();
        } finally {
            returnTarget = outer;
            loopDepth = outerLoopDepth;
        }
    }

    private void inLoop(Runnable body) {
        loopDepth++;
        try {
            body.run();
        } finally {
            loopDepth--;
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

    private static String typeName(ParserRuleContext ctx, String anonymous) {
        String name = ScopeAnalysis.definedName(ctx);
        return name != null ? name : anonymous;
    }

    /** {@code lib/vec3.ba#Vec3}, then {@code lib/vec3.ba#Vec3#1} for a second type of that name, in a module. */
    private String typeId(String name) {
        if (moduleKey == null) {
            return TypeNode.nextId();
        }
        int seen = typeNames.merge(name, 1, Integer::sum) - 1;
        return moduleKey + "#" + name + (seen > 0 ? "#" + seen : "");
    }

    private TypeNode newType(ParserRuleContext ctx, ClassShape.Kind kind, String name, BabbleParser.TypeListContext typeList) {
        List<BabbleParser.TypeContext> parentTypes = typeList != null ? typeList.type() : List.of();
        TypeNode type = new TypeNode(ctx, typeId(name), kind, name, current(), parentTypes);
        types.add(type);
        typeNodes.put(ctx, type);
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
        TypeNode owner = memberOf;
        boolean isStatic = staticMethod;
        inReturnTarget(ReturnTarget.FUNCTION, () -> {
            inScope(ScopeNode.Kind.FUNCTION, ctx, null, () -> {
                current().memberOf = owner;
                current().staticMethod = isStatic;
                List<String> parameters = new ArrayList<>();
                int required = 0;
                for (BabbleParser.FnParamContext parameter : ctx.fnParameters().fnParam()) {
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
    public Void visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
        boolean value = ctx.RECORD() != null;
        TypeNode type = newType(ctx, value ? ClassShape.Kind.VALUE_CLASS : ClassShape.Kind.CLASS, typeName(ctx, "class"), ctx.typeList());
        boolean isValue = value;

        inReturnTarget(ReturnTarget.CLASS_BODY, () -> {
            inScope(ScopeNode.Kind.CLASS_BODY, ctx, type, () -> {
                Set<String> parameterNames = new HashSet<>();
                if (ctx.classParameters() != null) {
                    for (BabbleParser.ClassParamContext parameter : ctx.classParameters().classParam()) {
                        Token name = parameter.ID().getSymbol();
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
                if (!ctx.interfaceBlock.effectDeclaration().isEmpty()) {
                    throw error("An interface can only declare members", ctx.interfaceBlock.effectDeclaration().get(0));
                }
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
     * Only reached for blocks that open a scope of their own : function, class,
     * object and interface bodies are visited through their owner.
     */
    @Override
    public Void visitBlock(BabbleParser.BlockContext ctx) {
        inScope(ScopeNode.Kind.BLOCK, ctx, null, () -> visitChildren(ctx));
        return null;
    }

    @Override
    public Void visitEffectDeclaration(BabbleParser.EffectDeclarationContext ctx) {
        ScopeNode scope = current();
        if (scope.whileTests > 0) {
            throw error("Effect declaration in a while test", ctx);
        }
        if (ctx.EXPORT() != null && scope.kind != ScopeNode.Kind.FILE) {
            throw error("export only applies to the top-level definitions of a file", ctx.EXPORT().getSymbol());
        }
        effectDeclarations.add(new PendingEffectDecl(ctx, scope));
        return null;
    }

    // ---------------------------------------------------------------- definitions

    /** {@code export def} : only for the names of the file itself. */
    private void export(org.antlr.v4.runtime.tree.TerminalNode export, Token name) {
        if (export == null) {
            return;
        }
        if (current().kind != ScopeNode.Kind.FILE) {
            throw error("export only applies to the top-level definitions of a file : " + name.getText(), export.getSymbol());
        }
        analysis.addExport(name.getText());
    }

    @Override
    public Void visitDefExpression(BabbleParser.DefExpressionContext ctx) {
        ScopeNode scope = current();
        if (scope.whileTests > 0) {
            throw error("Definition in a while test : " + ctx.name.getText(), ctx.name);
        }
        export(ctx.EXPORT(), ctx.name);
        boolean isPrivate = ctx.PRIVATE() != null;
        BabbleParser.FunctionLiteralContext function = ctx.value instanceof BabbleParser.FunctionLiteralContext literal ? literal : null;
        int flags = (function != null ? ClassShape.METHOD : 0) | (isPrivate ? ClassShape.PRIVATE : 0);

        if (ctx.typeParameters() != null) {
            Set<String> typeParameterNames = new HashSet<>();
            for (org.antlr.v4.runtime.tree.TerminalNode id : ctx.typeParameters().ID()) {
                if (!typeParameterNames.add(id.getText())) {
                    throw error("Name already defined : " + id.getText(), id.getSymbol());
                }
            }
            if (scope.kind != ScopeNode.Kind.INTERFACE_BODY
                    && !(ctx.value instanceof BabbleParser.FunctionLiteralContext
                    || ctx.value instanceof BabbleParser.ClassExpressionContext
                    || ctx.value instanceof BabbleParser.InterfaceExpressionContext)) {
                throw error("Type parameters only apply to functions, classes, and interfaces : " + ctx.name.getText(), ctx.typeParameters());
            }
        }

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
    public Void visitAxiomDefExpression(BabbleParser.AxiomDefExpressionContext ctx) {
        String name = ctx.name != null ? ctx.name.getText() : ctx.scalarName.getText();
        axiomDefs.computeIfAbsent(current(), s -> new HashMap<>()).put(name, ctx);
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
        inLoop(() -> visit(ctx.whileBlock));
        return null;
    }

    @Override
    public Void visitForExpression(BabbleParser.ForExpressionContext ctx) {
        visit(ctx.iterable);
        inLoop(() -> inScope(ScopeNode.Kind.BLOCK, ctx.forBlock, null, () -> {
            declare(ctx.name, ctx, false);
            visitChildren(ctx.forBlock);
        }));
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

    @Override
    public Void visitBreakExpression(BabbleParser.BreakExpressionContext ctx) {
        if (loopDepth == 0) {
            throw error("break outside of a loop", ctx);
        }
        if (ctx.expression() != null) {
            visit(ctx.expression());
        }
        return null;
    }

    @Override
    public Void visitContinueExpression(BabbleParser.ContinueExpressionContext ctx) {
        if (loopDepth == 0) {
            throw error("continue outside of a loop", ctx);
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
        return ctx.getParent() instanceof BabbleParser.SelectorContext selector
                && selector.expression() == ctx
                && !(selector.getParent() instanceof BabbleParser.CallContext call && call.expression() == selector);
    }

    @Override
    public Void visitSuperExpression(BabbleParser.SuperExpressionContext ctx) {
        if (!(ctx.getParent() instanceof BabbleParser.SelectorContext selector && selector.expression() == ctx
                && selector.safe == null
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
        supers.add(new PendingSuper(ctx, owner, current(), ScopeAnalysis.selectorName(selector)));
        return null;
    }

    @Override
    public Void visitCall(BabbleParser.CallContext ctx) {
        String name = staticImport(ctx);
        if (name != null) {
            imports.add(new PendingImport(ctx, name, current()));
        }
        return visitChildren(ctx);
    }

    /** The file a call imports statically : {@code import("….ba")} with a string literal, or null. */
    static String staticImport(BabbleParser.CallContext call) {
        if (call.expression() instanceof BabbleParser.SelectorContext callee && callee.expression() == null
                && callee.ID().getText().equals("import")
                && call.callParameters().callParameter().size() == 1
                && ScopeAnalysis.callParamName(call.callParameters().callParameter(0)) == null
                && call.callParameters().callParameter(0).expression() instanceof BabbleParser.StringContext string) {
            String name = org.babblelang.engine.impl.Interpreter.unescape(string.getText());
            return name.endsWith(".ba") ? name : null;
        }
        return null;
    }

    @Override
    public Void visitSelector(BabbleParser.SelectorContext ctx) {
        BabbleParser.ExpressionContext receiver = ctx.expression();
        if (receiver == null) {
            occurrences.add(new Occurrence(ctx, ctx.ID().getText(), current()));
        }
        if (receiver != null && !(receiver instanceof BabbleParser.SuperExpressionContext)) {
            accesses.add(new PendingAccess(ctx, receiver, ScopeAnalysis.selectorName(ctx), false, current()));
        }
        return visitChildren(ctx);
    }

    @Override
    public Void visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
        if (!ScopeAnalysis.isValidAssignTarget(ctx)) {
            throw error("Cannot assign to expression", ctx.lhs);
        }
        BabbleParser.ExpressionContext receiver = ScopeAnalysis.assignReceiver(ctx);
        String name = ScopeAnalysis.assignName(ctx);
        if (receiver == null) {
            assignments.add(new PendingAssignment(ctx, name, current()));
            occurrences.add(new Occurrence(ctx, name, current()));
        } else {
            accesses.add(new PendingAccess(ctx, receiver, name, true, current()));
            visit(receiver);
        }
        visit(ctx.value);
        return null;
    }

    @Override
    public Void visitAsExpression(BabbleParser.AsExpressionContext ctx) {
        return visit(ctx.left);
    }
}
