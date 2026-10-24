package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.JavaTypes;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The type checker (docs/design/type-system.md), in warn mode : the fourth pass of the
 * analysis, after {@link Resolver}. It types every expression of the file and reports, as
 * warnings, what contradicts the annotations or is bound to fail at runtime : a value of
 * the wrong type where a type is declared, calls that don't match a known signature,
 * members that don't exist, operators applied to values they reject.
 * <p>
 * Typing is gradual : whatever is unannotated and can't be inferred is {@link Type#DYNAMIC},
 * which never produces a warning. An unannotated def that is never reassigned takes the
 * type of its value (T8).
 */
final class TypeChecker {
    private record Diagnostic(int line, int column, String message) {
    }

    private static final Comparator<Diagnostic> ORDER = Comparator.comparingInt(Diagnostic::line)
            .thenComparingInt(Diagnostic::column).thenComparing(Diagnostic::message);

    private final BlockScopes pass;
    private final Map<ParserRuleContext, ScopeNode> occurrenceScopes = new HashMap<>();
    private final Set<ParserRuleContext> reassigned = new HashSet<>();
    private final Map<ParserRuleContext, Type> types = new HashMap<>();
    private final Map<ParserRuleContext, Type> declarationTypes = new HashMap<>();
    private final Map<BabbleParser.TypeContext, Type> annotations = new HashMap<>();
    private final Set<ParserRuleContext> inProgress = new HashSet<>();
    private final List<BabbleParser.FunctionLiteralContext> functions = new ArrayList<>();
    private final Set<Diagnostic> diagnostics = new TreeSet<>(ORDER);
    private final Typer typer = new Typer();

    TypeChecker(BlockScopes pass) {
        this.pass = pass;
    }

    void run(BabbleParser.FileContext file) {
        for (BlockScopes.Occurrence occurrence : pass.occurrences) {
            occurrenceScopes.put(occurrence.ctx(), occurrence.scope());
        }
        for (BlockScopes.PendingAssignment assignment : pass.assignments) {
            if (resolveName(null, assignment.name(), assignment.scope()) instanceof ParserRuleContext declaration) {
                reassigned.add(declaration);
            }
        }
        walk(file);
        for (BabbleParser.FunctionLiteralContext function : functions) {
            checkResult(function);
        }
        for (Diagnostic diagnostic : diagnostics) {
            pass.analysis.warn(diagnostic.message(), diagnostic.line(), diagnostic.column());
        }
    }

    private void warn(String message, ParserRuleContext at) {
        diagnostics.add(new Diagnostic(at.getStart().getLine(), at.getStart().getCharPositionInLine() + 1, message));
    }

    /** Types every expression and block, and resolves every annotation, in source order. */
    private void walk(ParseTree tree) {
        if (tree instanceof BabbleParser.TypeContext type) {
            // Only annotations are walked to : implements lists and super qualifiers are TypeChecks'.
            annotation(type, scopeOf(type));
            return;
        }
        if (tree instanceof BabbleParser.TypeListContext || tree instanceof BabbleParser.SuperExpressionContext) {
            return;
        }
        if (tree instanceof BabbleParser.ExpressionContext || tree instanceof BabbleParser.BlockContext) {
            typeOf((ParserRuleContext) tree);
        }
        if (tree instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.defaultValue != null && parameter.type() != null) {
            expect(typeOf(parameter.defaultValue), annotation(parameter.type(), scopeOf(parameter)), parameter.defaultValue);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            walk(tree.getChild(i));
        }
    }

    // ---------------------------------------------------------------- scopes and names

    /** The innermost scope a node is in. */
    private ScopeNode scopeOf(ParserRuleContext ctx) {
        for (ParserRuleContext p = ctx; p != null; p = p.getParent()) {
            ScopeNode scope = pass.scopeNodes.get(p);
            if (scope != null) {
                return scope;
            }
        }
        throw new IllegalStateException("No scope for " + ctx.getText());
    }

    /**
     * What a name refers to from a scope : a def or parameter declaration, the list of
     * package expressions of that name, or null for a name the analysis doesn't know (the
     * script's bindings and the implicits).
     */
    private static Object resolveName(ParserRuleContext occurrence, String name, ScopeNode scope) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            ParserRuleContext declaration = s.names.get(name);
            if (declaration != null && !(occurrence != null && declaration instanceof BabbleParser.ParameterDeclarationContext parameter
                    && notBoundYet(occurrence, s, parameter))) {
                return declaration;
            }
            List<BabbleParser.PackageExpressionContext> packages = s.packages.get(name);
            if (packages != null) {
                return packages;
            }
        }
        return null;
    }

    /** A parameter default doesn't see the parameters from its own onwards (as in Resolver). */
    private static boolean notBoundYet(ParserRuleContext occurrence, ScopeNode scope, BabbleParser.ParameterDeclarationContext declaration) {
        for (ParserRuleContext p = occurrence; p != null && p != scope.owner; p = p.getParent()) {
            if (p instanceof BabbleParser.FunctionLiteralContext) {
                return false;
            }
            if (p instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.getParent().getParent() == scope.owner) {
                List<BabbleParser.ParameterDeclarationContext> parameters =
                        ((BabbleParser.ParametersDeclarationContext) parameter.getParent()).parameterDeclaration();
                return parameters.indexOf(parameter) <= parameters.indexOf(declaration);
            }
        }
        return false;
    }

    /** The type of a def or parameter : its annotation, or for a def never reassigned, its value's (T8). */
    private Type typeOfDeclaration(ParserRuleContext declaration) {
        Type cached = declarationTypes.get(declaration);
        if (cached != null) {
            return cached;
        }
        if (!inProgress.add(declaration)) {
            return Type.DYNAMIC;
        }
        try {
            Type type = Type.DYNAMIC;
            if (declaration instanceof BabbleParser.DefExpressionContext def) {
                if (def.type() != null) {
                    type = annotation(def.type(), scopeOf(def));
                } else if (def.value != null && !reassigned.contains(def)) {
                    type = inferred(typeOf(def.value));
                }
            } else if (declaration instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.type() != null) {
                type = annotation(parameter.type(), scopeOf(parameter));
            }
            declarationTypes.put(declaration, type);
            return type;
        } finally {
            inProgress.remove(declaration);
        }
    }

    /** An inferred type is never stricter than what runs : null says nothing about later values. */
    private static Type inferred(Type type) {
        return type == Type.NULL || type == Type.NOTHING ? Type.DYNAMIC : type;
    }

    /** The annotation a def or parameter declares, or null. */
    private Type declaredType(ParserRuleContext declaration) {
        if (declaration instanceof BabbleParser.DefExpressionContext def && def.type() != null) {
            return annotation(def.type(), scopeOf(def));
        }
        if (declaration instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.type() != null) {
            return annotation(parameter.type(), scopeOf(parameter));
        }
        return null;
    }

    // ---------------------------------------------------------------- annotations

    private Type annotation(BabbleParser.TypeContext type, ScopeNode scope) {
        Type cached = annotations.get(type);
        if (cached != null) {
            return cached;
        }
        Type result;
        if (type instanceof BabbleParser.FunctionTypeContext function) {
            result = new Type.Function(parameters(function.parametersDeclaration(), scope),
                    function.type() != null ? annotation(function.type(), scope) : Type.DYNAMIC);
        } else {
            result = simpleType((BabbleParser.SimpleTypeContext) type, scope);
            if (result == null) {
                warn("Unknown type : " + type.getText(), type);
                result = Type.DYNAMIC;
            }
        }
        annotations.put(type, result);
        return result;
    }

    private List<Type.Parameter> parameters(BabbleParser.ParametersDeclarationContext declaration, ScopeNode scope) {
        List<Type.Parameter> result = new ArrayList<>();
        for (BabbleParser.ParameterDeclarationContext parameter : declaration.parameterDeclaration()) {
            result.add(new Type.Parameter(parameter.ID().getText(),
                    parameter.type() != null ? annotation(parameter.type(), scope) : Type.DYNAMIC, parameter.defaultValue != null));
        }
        return result;
    }

    /**
     * T1 : the built-in names, a class or interface literal bound to a def that is never
     * reassigned (possibly reached through packages), or a Java class. Null when unknown.
     */
    private Type simpleType(BabbleParser.SimpleTypeContext type, ScopeNode scope) {
        List<String> path = type.ID().stream().map(Object::toString).toList();
        Object resolved = resolveName(null, path.get(0), scope);
        if (path.size() == 1 && resolved == null) {
            switch (path.get(0)) {
                case "int": return Type.INT;
                case "double": return Type.DOUBLE;
                case "bool": return Type.BOOL;
                case "string": return Type.STRING;
                case "dynamic": return Type.DYNAMIC;
                default: return null;
            }
        }
        if (resolved instanceof BabbleParser.DefExpressionContext def && !reassigned.contains(def)) {
            if (path.size() == 1) {
                return classType(def.value);
            }
            String imported = importedPackage(def.value);
            return imported != null ? javaType(imported + "." + String.join(".", path.subList(1, path.size()))) : null;
        }
        if (resolved instanceof List<?> packages) {
            @SuppressWarnings("unchecked")
            List<BabbleParser.PackageExpressionContext> list = (List<BabbleParser.PackageExpressionContext>) packages;
            return typeInPackages(list, path, 1);
        }
        if (resolved == null && (path.get(0).equals("java") || path.get(0).equals("javax"))) {
            return javaType(String.join(".", path));
        }
        return null;
    }

    private Type classType(BabbleParser.ExpressionContext value) {
        if (value instanceof BabbleParser.ClassExpressionContext || value instanceof BabbleParser.InterfaceExpressionContext) {
            return new Type.Instance(pass.typeNodes.get(value));
        }
        return null;
    }

    private Type typeInPackages(List<BabbleParser.PackageExpressionContext> packages, List<String> path, int index) {
        if (index >= path.size()) {
            return null;
        }
        List<BabbleParser.PackageExpressionContext> nested = new ArrayList<>();
        for (BabbleParser.PackageExpressionContext pkg : packages) {
            for (BabbleParser.ExpressionContext statement : pkg.packageBlock.expression()) {
                if (statement instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(path.get(index))) {
                    return index == path.size() - 1 && !reassigned.contains(def) ? classType(def.value) : null;
                }
                if (statement instanceof BabbleParser.PackageExpressionContext inner && inner.name.getText().equals(path.get(index))) {
                    nested.add(inner);
                }
            }
        }
        return nested.isEmpty() ? null : typeInPackages(nested, path, index + 1);
    }

    /** The literal argument of {@code import("…")}, or null. */
    private static String importedPackage(BabbleParser.ExpressionContext value) {
        if (value instanceof BabbleParser.CallContext call
                && call.expression() instanceof BabbleParser.SelectorContext callee && callee.expression() == null
                && callee.ID().getText().equals("import")
                && call.callParameters().callParameter().size() == 1
                && call.callParameters().callParameter(0).expression() instanceof BabbleParser.StringContext string) {
            return org.babblelang.engine.impl.Interpreter.unescape(string.getText());
        }
        return null;
    }

    /** A Java class named in a type position, nested classes included. */
    private static Type javaType(String name) {
        Class<?> java = loadClass(name);
        return java != null ? fromJava(java) : null;
    }

    private static Class<?> loadClass(String name) {
        String candidate = name;
        while (true) {
            try {
                return Class.forName(candidate, false, TypeChecker.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                int dot = candidate.lastIndexOf('.');
                if (dot < 0) {
                    return null;
                }
                candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
            }
        }
    }

    /** The Babble type of a Java class : Babble's numbers, booleans and strings are Java's. */
    private static Type fromJava(Class<?> java) {
        if (java == int.class || java == Integer.class) {
            return Type.INT;
        }
        if (java == double.class || java == Double.class) {
            return Type.DOUBLE;
        }
        if (java == boolean.class || java == Boolean.class) {
            return Type.BOOL;
        }
        if (java == String.class) {
            return Type.STRING;
        }
        if (java == void.class) {
            return Type.NULL;
        }
        if (java == Object.class || java.isPrimitive()) {
            return Type.DYNAMIC;
        }
        return new Type.JavaInstance(java);
    }

    /**
     * The type of what a Java method returns. Results come back unwrapped, so apart from
     * numbers, booleans and strings, nothing more is known of them.
     */
    private static Type javaResult(Class<?> java) {
        Type type = fromJava(java);
        return type instanceof Type.JavaInstance ? Type.DYNAMIC : type;
    }

    // ---------------------------------------------------------------- expressions

    private Type typeOf(ParserRuleContext ctx) {
        Type cached = types.get(ctx);
        if (cached != null) {
            return cached;
        }
        if (!inProgress.add(ctx)) {
            return Type.DYNAMIC;
        }
        try {
            Type type = typer.visit(ctx);
            if (type == null) {
                type = Type.DYNAMIC;
            }
            types.put(ctx, type);
            return type;
        } finally {
            inProgress.remove(ctx);
        }
    }

    /** The type of a function literal : its parameters and annotated result. Its body isn't looked at. */
    private Type.Function signature(BabbleParser.FunctionLiteralContext literal) {
        ScopeNode scope = scopeOf(literal);
        return new Type.Function(parameters(literal.parametersDeclaration(), scope),
                literal.type() != null ? annotation(literal.type(), scope) : Type.DYNAMIC);
    }

    /** A function's value, its last statement, must match its annotated result. */
    private void checkResult(BabbleParser.FunctionLiteralContext function) {
        if (function.type() == null) {
            return;
        }
        Type expected = annotation(function.type(), scopeOf(function));
        List<BabbleParser.ExpressionContext> statements = function.functionBlock.expression();
        if (statements.isEmpty()) {
            expect(Type.NULL, expected, function.functionBlock);
        } else {
            BabbleParser.ExpressionContext last = statements.get(statements.size() - 1);
            expect(typeOf(last), expected, last);
        }
    }

    private void expect(Type actual, Type expected, ParserRuleContext at) {
        if (!assignable(actual, expected)) {
            warn("Type error : expected " + expected + ", got " + actual, at);
        }
    }

    /** The type of {@code this} : the class or interface whose body or method it is in, or the class value in a static block. */
    private Type thisType(ScopeNode scope) {
        ScopeNode thisScope = BlockScopes.thisScope(scope);
        if (thisScope == null) {
            return Type.DYNAMIC;
        }
        return thisScope.kind == ScopeNode.Kind.STATIC_BODY ? new Type.ClassValue(thisScope.type) : new Type.Instance(thisScope.type);
    }

    private final class Typer extends BabbleBaseVisitor<Type> {
        @Override
        public Type visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
            return Type.NULL;
        }

        @Override
        public Type visitIfExpression(BabbleParser.IfExpressionContext ctx) {
            typeOf(ctx.test);
            return join(typeOf(ctx.thenBlock), ctx.elseBlock != null ? typeOf(ctx.elseBlock) : Type.NULL);
        }

        @Override
        public Type visitDefExpression(BabbleParser.DefExpressionContext ctx) {
            ScopeAnalysis.DefKind kind = pass.analysis.defTarget(ctx).kind();
            if (kind == ScopeAnalysis.DefKind.REQUIRED) {
                return Type.NULL;
            }
            Type value = ctx.value != null ? typeOf(ctx.value) : Type.NULL;
            if (ctx.type() != null) {
                expect(value, annotation(ctx.type(), scopeOf(ctx)), ctx.value != null ? ctx.value : ctx);
            }
            return value;
        }

        @Override
        public Type visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
            typeOf(ctx.test);
            return join(typeOf(ctx.whileBlock), Type.NULL);
        }

        @Override
        public Type visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
            return new Type.ClassValue(pass.typeNodes.get(ctx));
        }

        @Override
        public Type visitInterfaceExpression(BabbleParser.InterfaceExpressionContext ctx) {
            return new Type.ClassValue(pass.typeNodes.get(ctx));
        }

        @Override
        public Type visitObjectExpression(BabbleParser.ObjectExpressionContext ctx) {
            return new Type.Instance(pass.typeNodes.get(ctx));
        }

        @Override
        public Type visitThisExpression(BabbleParser.ThisExpressionContext ctx) {
            return thisType(occurrenceScopes.get(ctx));
        }

        @Override
        public Type visitSuperExpression(BabbleParser.SuperExpressionContext ctx) {
            return Type.DYNAMIC;
        }

        @Override
        public Type visitSelector(BabbleParser.SelectorContext ctx) {
            String name = ctx.ID().getText();
            BabbleParser.ExpressionContext receiver = ctx.expression();
            if (receiver == null) {
                Object resolved = resolveName(ctx, name, occurrenceScopes.get(ctx));
                if (resolved instanceof ParserRuleContext declaration) {
                    return typeOfDeclaration(declaration);
                }
                if (resolved instanceof List<?> packages) {
                    @SuppressWarnings("unchecked")
                    List<BabbleParser.PackageExpressionContext> list = (List<BabbleParser.PackageExpressionContext>) packages;
                    return new Type.Package(name, list);
                }
                return name.equals("java") || name.equals("javax") ? new Type.JavaPackage(name) : Type.DYNAMIC;
            }
            if (receiver instanceof BabbleParser.SuperExpressionContext) {
                return Type.DYNAMIC;
            }
            return member(typeOf(receiver), name, receiver instanceof BabbleParser.ThisExpressionContext, ctx, receiver);
        }

        @Override
        public Type visitCall(BabbleParser.CallContext ctx) {
            Type callee = typeOf(ctx.expression());
            for (BabbleParser.CallParameterContext argument : ctx.callParameters().callParameter()) {
                typeOf(argument.expression());
            }
            return call(callee, ctx);
        }

        @Override
        public Type visitBlockExpression(BabbleParser.BlockExpressionContext ctx) {
            return typeOf(ctx.block());
        }

        @Override
        public Type visitBlock(BabbleParser.BlockContext ctx) {
            Type type = Type.NULL;
            for (BabbleParser.ExpressionContext statement : ctx.expression()) {
                type = typeOf(statement);
            }
            return type;
        }

        @Override
        public Type visitBooleanNot(BabbleParser.BooleanNotContext ctx) {
            typeOf(ctx.expression());
            return Type.BOOL;
        }

        @Override
        public Type visitBinaryOp(BabbleParser.BinaryOpContext ctx) {
            return binaryOp(ctx, typeOf(ctx.left), typeOf(ctx.right));
        }

        @Override
        public Type visitIsExpression(BabbleParser.IsExpressionContext ctx) {
            typeOf(ctx.left);
            typeOf(ctx.right);
            return Type.BOOL;
        }

        @Override
        public Type visitBooleanOp(BabbleParser.BooleanOpContext ctx) {
            typeOf(ctx.left);
            typeOf(ctx.right);
            return Type.BOOL;
        }

        @Override
        public Type visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
            functions.add(ctx);
            return signature(ctx);
        }

        @Override
        public Type visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
            Type value = typeOf(ctx.value);
            String name = ctx.name.getText();
            Type declared = null;
            if (ctx.namespace == null) {
                if (resolveName(ctx, name, occurrenceScopes.get(ctx)) instanceof ParserRuleContext declaration) {
                    declared = declaredType(declaration);
                }
            } else {
                declared = assignedMemberType(typeOf(ctx.namespace), name, ctx.namespace instanceof BabbleParser.ThisExpressionContext, ctx);
            }
            if (declared != null) {
                expect(value, declared, ctx.value);
            }
            return value;
        }

        @Override
        public Type visitNull(BabbleParser.NullContext ctx) {
            return Type.NULL;
        }

        @Override
        public Type visitBoolean(BabbleParser.BooleanContext ctx) {
            return Type.BOOL;
        }

        @Override
        public Type visitRecurse(BabbleParser.RecurseContext ctx) {
            for (ParserRuleContext p = ctx.getParent(); p != null; p = p.getParent()) {
                if (p instanceof BabbleParser.FunctionLiteralContext function) {
                    return signature(function);
                }
            }
            return Type.DYNAMIC;
        }

        @Override
        public Type visitInteger(BabbleParser.IntegerContext ctx) {
            try {
                Integer.parseInt(ctx.getText());
                return Type.INT;
            } catch (NumberFormatException e) {
                return Type.DYNAMIC;
            }
        }

        @Override
        public Type visitDouble(BabbleParser.DoubleContext ctx) {
            return Type.DOUBLE;
        }

        @Override
        public Type visitString(BabbleParser.StringContext ctx) {
            return Type.STRING;
        }

        @Override
        public Type visitReturnExpression(BabbleParser.ReturnExpressionContext ctx) {
            Type value = typeOf(ctx.expression());
            for (ParserRuleContext p = ctx.getParent(); p != null; p = p.getParent()) {
                if (p instanceof BabbleParser.FunctionLiteralContext function) {
                    if (function.type() != null) {
                        expect(value, annotation(function.type(), scopeOf(function)), ctx.expression());
                    }
                    break;
                }
            }
            return Type.NOTHING;
        }
    }

    // ---------------------------------------------------------------- members

    /** {@code receiver.name}, read. */
    private Type member(Type receiver, String name, boolean own, ParserRuleContext at, BabbleParser.ExpressionContext receiverExpression) {
        if (receiver == Type.DYNAMIC || receiver == Type.NOTHING) {
            return Type.DYNAMIC;
        }
        if (receiver instanceof Type.Instance instance) {
            TypeNode.Member member = findMember(instance.node(), name);
            if (member != null) {
                return member.isPrivate() && !own ? Type.DYNAMIC : memberType(member);
            }
            Type inherited = inheritedMember(instance.node(), name);
            if (inherited != null) {
                return inherited;
            }
            warn("No such member : " + name, at);
            return Type.DYNAMIC;
        }
        if (receiver instanceof Type.ClassValue value) {
            TypeNode.Member member = value.node().statics.get(name);
            if (member != null) {
                return member.isPrivate() && !own ? Type.DYNAMIC : memberType(member);
            }
            warn("No such member : " + name, at);
            return Type.DYNAMIC;
        }
        if (receiver instanceof Type.Package pkg) {
            return packageMember(pkg, name);
        }
        if (receiver instanceof Type.JavaPackage pkg) {
            String qualified = pkg.name() + "." + name;
            Class<?> java = loadTopLevel(qualified);
            return java != null ? new Type.JavaClassValue(java) : new Type.JavaPackage(qualified);
        }
        if (receiver instanceof Type.JavaClassValue value) {
            for (Class<?> memberClass : value.type().getClasses()) {
                if (memberClass.getSimpleName().equals(name) && Modifier.isPublic(memberClass.getModifiers())) {
                    return new Type.JavaClassValue(memberClass);
                }
            }
            return javaMethod(value.type(), name, at);
        }
        if (receiver instanceof Type.JavaInstance instance) {
            return javaMethod(instance.type(), name, at);
        }
        // Numbers, booleans, strings, null, functions : nothing has members but objects,
        // classes, packages and Java values.
        warn("Not a scope : " + receiverExpression.getText(), at);
        return Type.DYNAMIC;
    }

    /** What {@code receiver.name = value} must assign : the member's annotation, or null. */
    private Type assignedMemberType(Type receiver, String name, boolean own, ParserRuleContext at) {
        TypeNode.Member member = null;
        if (receiver instanceof Type.Instance instance) {
            member = findMember(instance.node(), name);
            if (member == null && inheritedMember(instance.node(), name) == null) {
                warn("No such member : " + name, at);
            }
        } else if (receiver instanceof Type.ClassValue value) {
            member = value.node().statics.get(name);
            if (member == null) {
                warn("No such member : " + name, at);
            }
        }
        if (member == null || member.isPrivate() && !own) {
            return null;
        }
        return declaredType(member.declaration);
    }

    /** A member of a class or object, or for an interface, of it or the interfaces it extends. */
    private static TypeNode.Member findMember(TypeNode node, String name) {
        TypeNode.Member member = node.members.get(name);
        if (member != null || !node.isInterface()) {
            return member;
        }
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode parentNode) {
                member = findMember(parentNode, name);
                if (member != null) {
                    return member;
                }
            }
        }
        return null;
    }

    /**
     * A member the class or interface gets from what it implements or extends : a Babble
     * default, or any method of a Java interface. Null when there is none.
     */
    private Type inheritedMember(TypeNode node, String name) {
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode parentNode) {
                TypeNode.Member member = parentNode.members.get(name);
                if (member != null && (member.function != null || node.isInterface())) {
                    return memberType(member);
                }
                Type inherited = inheritedMember(parentNode, name);
                if (inherited != null) {
                    return inherited;
                }
            } else if (Arrays.stream(((Class<?>) parent).getMethods()).anyMatch(m -> m.getName().equals(name))) {
                // Answered by a Babble method or a Java default : its signature is the Babble one, unknown here.
                return Type.DYNAMIC;
            }
        }
        return null;
    }

    /** A member's annotation, or a method's signature : members are otherwise dynamic, since obj.x = … can change them. */
    private Type memberType(TypeNode.Member member) {
        Type declared = declaredType(member.declaration);
        if (declared != null) {
            return declared;
        }
        return member.function != null ? typeOf(member.function) : Type.DYNAMIC;
    }

    private Type packageMember(Type.Package pkg, String name) {
        List<BabbleParser.PackageExpressionContext> nested = new ArrayList<>();
        for (BabbleParser.PackageExpressionContext expression : pkg.packages()) {
            for (BabbleParser.ExpressionContext statement : expression.packageBlock.expression()) {
                if (statement instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(name)) {
                    return typeOfDeclaration(def);
                }
                if (statement instanceof BabbleParser.PackageExpressionContext inner && inner.name.getText().equals(name)) {
                    nested.add(inner);
                }
            }
        }
        return nested.isEmpty() ? Type.DYNAMIC : new Type.Package(name, nested);
    }

    /** As {@code JavaPackage} finds them : a class with that exact name, else a package. */
    private static Class<?> loadTopLevel(String name) {
        try {
            return Class.forName(name, false, TypeChecker.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private Type javaMethod(Class<?> java, String name, ParserRuleContext at) {
        for (Method method : java.getMethods()) {
            if (method.getName().equals(name)) {
                return new Type.JavaMethod(java, name);
            }
        }
        warn("No such name in " + java.getCanonicalName() + " : " + name, at);
        return Type.DYNAMIC;
    }

    // ---------------------------------------------------------------- calls

    private Type call(Type callee, BabbleParser.CallContext ctx) {
        List<BabbleParser.CallParameterContext> arguments = ctx.callParameters().callParameter();
        if (callee == Type.DYNAMIC || callee == Type.NOTHING) {
            return Type.DYNAMIC;
        }
        if (callee instanceof Type.Function function) {
            bind(function.parameters(), arguments, ctx);
            return function.result();
        }
        if (callee instanceof Type.ClassValue value && !value.node().isInterface()) {
            TypeNode node = value.node();
            if (node.ctx instanceof BabbleParser.ClassExpressionContext cls) {
                List<Type.Parameter> parameters = cls.parametersDeclaration() != null
                        ? parameters(cls.parametersDeclaration(), scopeOf(cls)) : List.of();
                bind(parameters, arguments, ctx);
            }
            return new Type.Instance(node);
        }
        if (callee instanceof Type.JavaClassValue value) {
            Class<?> java = value.type();
            if (java.isInterface() && arguments.size() == 1 && isFunction(typeOf(arguments.get(0).expression()))
                    && JavaTypes.isFunctionalInterface(java)) {
                return Type.DYNAMIC;
            }
            javaCall(Arrays.asList(java.getConstructors()), java.getCanonicalName(), arguments, ctx);
            return new Type.JavaInstance(java);
        }
        if (callee instanceof Type.JavaMethod method) {
            List<Method> candidates = new ArrayList<>();
            for (Method candidate : method.owner().getMethods()) {
                if (candidate.getName().equals(method.name()) && !candidate.isBridge() && !candidate.isSynthetic()) {
                    candidates.add(candidate);
                }
            }
            List<Method> applicable = javaCall(candidates, method.owner().getCanonicalName() + "." + method.name(), arguments, ctx);
            Set<Class<?>> results = applicable.stream().map(Method::getReturnType).collect(Collectors.toSet());
            if (results.size() == 1) {
                return javaResult(results.iterator().next());
            }
            // With every argument's type known, the runtime picks the most specific overload.
            boolean known = arguments.stream().noneMatch(a -> isUnknown(typeOf(a.expression())) || typeOf(a.expression()) == Type.NULL);
            Method best = known ? mostSpecific(applicable) : null;
            return best != null ? javaResult(best.getReturnType()) : Type.DYNAMIC;
        }
        warn(ctx.expression().getText() + " is not callable", ctx);
        return Type.DYNAMIC;
    }

    private static boolean isFunction(Type type) {
        return type instanceof Type.Function || type instanceof Type.JavaMethod
                || type instanceof Type.ClassValue value && !value.node().isInterface() || type == Type.DYNAMIC;
    }

    /** The argument binding rules of {@code Arguments}, applied to a known signature. */
    private void bind(List<Type.Parameter> parameters, List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext ctx) {
        int count = arguments.size();
        String[] names = new String[count];
        boolean named = false;
        for (int i = 0; i < count; i++) {
            if (arguments.get(i).ID() != null) {
                names[i] = arguments.get(i).ID().getText();
                named = true;
            }
        }
        boolean[] consumed = new boolean[count];
        for (int index = 0; index < parameters.size(); index++) {
            Type.Parameter parameter = parameters.get(index);
            int found = -1;
            for (int i = 0; i < count; i++) {
                if (parameter.name().equals(names[i])) {
                    found = i;
                    consumed[i] = true;
                }
            }
            if (found < 0 && index < count && names[index] == null) {
                found = index;
                consumed[index] = true;
            }
            if (found < 0) {
                if (!parameter.optional()) {
                    warn("Missing parameter : " + parameter.name(), ctx);
                }
                continue;
            }
            BabbleParser.ExpressionContext argument = arguments.get(found).expression();
            expect(typeOf(argument), parameter.type(), argument);
        }
        if (!named) {
            if (count > parameters.size()) {
                warn("Too many parameters : " + parameters.size() + " declared, " + count + " given", ctx);
            }
            return;
        }
        Set<String> distinctNames = new LinkedHashSet<>();
        List<String> unknownNames = new ArrayList<>();
        int positional = 0;
        int extraPositional = 0;
        for (int i = 0; i < count; i++) {
            if (names[i] == null) {
                positional++;
                if (!consumed[i]) {
                    extraPositional++;
                }
            } else if (distinctNames.add(names[i]) && !consumed[i]) {
                unknownNames.add(names[i]);
            }
        }
        if (!unknownNames.isEmpty()) {
            warn("No such parameter : " + String.join(", ", unknownNames), ctx);
        } else if (extraPositional > 0) {
            warn("Too many parameters : " + parameters.size() + " declared, " + (positional + distinctNames.size()) + " given", ctx);
        }
    }

    /**
     * Overload resolution against the arguments' static types, a dynamic argument fitting
     * any parameter. Reports a call no overload can accept, and returns the applicable ones.
     */
    private <E extends Executable> List<E> javaCall(List<E> candidates, String description, List<BabbleParser.CallParameterContext> arguments,
                                                    BabbleParser.CallContext ctx) {
        List<Type> argumentTypes = arguments.stream().map(a -> typeOf(a.expression())).toList();
        List<E> applicable = new ArrayList<>();
        for (E candidate : candidates) {
            Class<?>[] parameterTypes = candidate.getParameterTypes();
            if (parameterTypes.length != argumentTypes.size()) {
                continue;
            }
            boolean fits = true;
            for (int i = 0; i < parameterTypes.length && fits; i++) {
                fits = applicable(parameterTypes[i], argumentTypes.get(i));
            }
            if (fits) {
                applicable.add(candidate);
            }
        }
        if (applicable.isEmpty() && !candidates.isEmpty()) {
            warn("No applicable overload of " + description + " for "
                    + argumentTypes.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")")), ctx);
        }
        return applicable;
    }

    /** The overload more specific than all the others, as {@code Overloads.resolve} finds it, or null. */
    private static <E extends Executable> E mostSpecific(List<E> applicable) {
        for (E candidate : applicable) {
            if (applicable.stream().allMatch(other -> other == candidate || isMoreSpecific(candidate, other))) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isMoreSpecific(Executable candidate, Executable other) {
        Class<?>[] candidateTypes = candidate.getParameterTypes();
        Class<?>[] otherTypes = other.getParameterTypes();
        for (int i = 0; i < candidateTypes.length; i++) {
            Class<?> from = JavaTypes.box(candidateTypes[i]);
            Class<?> to = JavaTypes.box(otherTypes[i]);
            if (!to.isAssignableFrom(from) && !JavaTypes.isWidening(from, to)) {
                return false;
            }
        }
        return true;
    }

    /** Whether an argument of a static type can be passed to a Java parameter (as {@code Overloads} decides at runtime). */
    private static boolean applicable(Class<?> parameter, Type argument) {
        if (argument == Type.DYNAMIC || argument == Type.NOTHING) {
            return true;
        }
        if (argument == Type.NULL) {
            return !parameter.isPrimitive();
        }
        Class<?> java = javaClassOf(argument);
        if (java != null) {
            return JavaTypes.box(parameter).isAssignableFrom(java) || parameter.isPrimitive() && JavaTypes.isWidening(java, JavaTypes.box(parameter));
        }
        if (argument instanceof Type.Instance instance) {
            return parameter == Object.class || implementsJava(instance.node(), parameter);
        }
        if (isFunction(argument)) {
            return parameter == Object.class || JavaTypes.isFunctionalInterface(parameter);
        }
        return !parameter.isPrimitive();
    }

    private static Class<?> javaClassOf(Type type) {
        if (type instanceof Type.Simple simple) {
            return simple.javaClass();
        }
        return type instanceof Type.JavaInstance instance ? instance.type() : null;
    }

    // ---------------------------------------------------------------- operators

    private Type binaryOp(BabbleParser.BinaryOpContext ctx, Type left, Type right) {
        int op = ctx.op.getType();
        switch (op) {
            case BabbleLexer.PLUS:
                if (left == Type.STRING) {
                    return Type.STRING;
                }
                if (isUnknown(left)) {
                    return Type.DYNAMIC;
                }
                // Fall through : anything but a string on the left adds numbers.
            case BabbleLexer.MINUS:
            case BabbleLexer.MUL:
            case BabbleLexer.DIV:
                boolean numbers = number(left, ctx.left) & number(right, ctx.right);
                if (op == BabbleLexer.DIV) {
                    return Type.DOUBLE;
                }
                if (!numbers || isUnknown(left) || isUnknown(right)) {
                    return Type.DYNAMIC;
                }
                return left == Type.INT && right == Type.INT ? Type.INT : Type.DOUBLE;
            case BabbleLexer.EQ:
            case BabbleLexer.NEQ:
                return Type.BOOL;
            default:
                if (!comparable(left, right)) {
                    warn("Not comparable : " + ctx.left.getText() + " and " + ctx.right.getText(), ctx);
                }
                return Type.BOOL;
        }
    }

    private static boolean isUnknown(Type type) {
        return type == Type.DYNAMIC || type == Type.NOTHING;
    }

    /** Whether an operand can be a number, reporting it when it can't. */
    private boolean number(Type type, BabbleParser.ExpressionContext operand) {
        if (isUnknown(type) || type == Type.INT || type == Type.DOUBLE
                || type instanceof Type.JavaInstance instance && Number.class.isAssignableFrom(instance.type())) {
            return true;
        }
        warn("Not a number : " + operand.getText(), operand);
        return false;
    }

    /** {@code Operators.compare} : two numbers, or a Comparable and a value of its class. */
    private static boolean comparable(Type left, Type right) {
        if (isUnknown(left) || isUnknown(right)) {
            return true;
        }
        if ((left == Type.INT || left == Type.DOUBLE) && (right == Type.INT || right == Type.DOUBLE)) {
            return true;
        }
        Class<?> leftClass = javaClassOf(left);
        Class<?> rightClass = javaClassOf(right);
        return leftClass != null && rightClass != null && Comparable.class.isAssignableFrom(leftClass) && leftClass.isAssignableFrom(rightClass);
    }

    // ---------------------------------------------------------------- assignability

    /** Whether a value of type {@code from} can be used where {@code to} is declared. */
    static boolean assignable(Type from, Type to) {
        if (from.equals(to) || from == Type.DYNAMIC || to == Type.DYNAMIC || from == Type.NOTHING) {
            return true;
        }
        if (from == Type.NULL) {
            // T2 : numbers and booleans are never null ; everything else may be.
            return to != Type.INT && to != Type.DOUBLE && to != Type.BOOL && to != Type.NOTHING;
        }
        if (from == Type.INT && to == Type.DOUBLE) {
            return true;
        }
        if (to instanceof Type.JavaInstance java) {
            Class<?> fromClass = javaClassOf(from);
            if (fromClass != null) {
                return java.type().isAssignableFrom(fromClass);
            }
            if (from instanceof Type.Instance instance) {
                return implementsJava(instance.node(), java.type());
            }
            return isFunction(from) && JavaTypes.isFunctionalInterface(java.type());
        }
        if (to instanceof Type.Instance target) {
            return from instanceof Type.Instance instance && isSubtype(instance.node(), target.node());
        }
        if (to instanceof Type.Function target) {
            if (from instanceof Type.Function function) {
                return functionAssignable(function, target);
            }
            return isFunction(from);
        }
        return false;
    }

    /**
     * A function can stand for another when it accepts the other's arguments by position,
     * at types at least as wide, needs no more of them, and returns a narrower result.
     * Parameter names aren't compared : a callback is normally called by position.
     */
    private static boolean functionAssignable(Type.Function from, Type.Function to) {
        if (from.parameters().size() < to.parameters().size()) {
            return false;
        }
        for (int i = 0; i < from.parameters().size(); i++) {
            Type.Parameter parameter = from.parameters().get(i);
            if (i < to.parameters().size()) {
                if (!assignable(to.parameters().get(i).type(), parameter.type())) {
                    return false;
                }
            } else if (!parameter.optional()) {
                return false;
            }
        }
        return assignable(from.result(), to.result());
    }

    private static boolean isSubtype(TypeNode node, TypeNode target) {
        if (node == target) {
            return true;
        }
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode parentNode && isSubtype(parentNode, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean implementsJava(TypeNode node, Class<?> java) {
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode parentNode ? implementsJava(parentNode, java) : java.isAssignableFrom((Class<?>) parent)) {
                return true;
            }
        }
        return java == Object.class;
    }

    /** The type of a value that is one of two : {@code if} branches, a loop or null. */
    private static Type join(Type a, Type b) {
        if (a.equals(b) || b == Type.NOTHING) {
            return a;
        }
        if (a == Type.NOTHING) {
            return b;
        }
        if ((a == Type.INT || a == Type.DOUBLE) && (b == Type.INT || b == Type.DOUBLE)) {
            return Type.DOUBLE;
        }
        if (a == Type.NULL && assignable(Type.NULL, b)) {
            return b;
        }
        if (b == Type.NULL && assignable(Type.NULL, a)) {
            return a;
        }
        return Type.DYNAMIC;
    }
}
