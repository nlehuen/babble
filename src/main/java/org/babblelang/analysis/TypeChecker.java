package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Operators;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The type checker (docs/design/type-system.md) : the fourth pass of the analysis, after
 * {@link Resolver}. It types every expression of the file and reports, as errors, what
 * contradicts the annotations or is bound to fail at runtime : a value of the wrong type
 * where a type is declared, calls that don't match a known signature, members that don't
 * exist, operators applied to values they reject.
 * <p>
 * Typing is gradual : whatever is unannotated and can't be inferred is {@link Type#DYNAMIC},
 * which never produces an error. An unannotated def that is never reassigned takes the
 * type of its value, unannotated function literals infer their result from their body and
 * receive parameter types from context where an expected function or functional interface
 * type is known, and {@code is} checks narrow non-reassigned lexical bindings along
 * control-flow branches (T8). Declared types are also recorded so the backends check them
 * at runtime where dynamic values cross into typed code (T9).
 */
final class TypeChecker {
    private record Diagnostic(int line, int column, String message) {
    }

    private record Narrowing(
            Map<ParserRuleContext, Type> whenTrue,
            Map<ParserRuleContext, Type> whenFalse) {
        static final Narrowing EMPTY = new Narrowing(Map.of(), Map.of());

        Narrowing invert() {
            return new Narrowing(whenFalse, whenTrue);
        }
    }

    private record Widening(Type type, int line) {
    }

    private static final Comparator<Diagnostic> ORDER = Comparator.comparingInt(Diagnostic::line)
            .thenComparingInt(Diagnostic::column).thenComparing(Diagnostic::message);

    private final BlockScopes pass;
    private final boolean strict;
    private final Map<ParserRuleContext, ScopeNode> occurrenceScopes = new HashMap<>();
    private final Set<ParserRuleContext> reassigned = new HashSet<>();
    private final Map<ParserRuleContext, Type> types = new HashMap<>();
    private final Map<ParserRuleContext, Type> declarationTypes = new HashMap<>();
    private final Map<BabbleParser.TypeContext, Type> annotations = new HashMap<>();
    private final Set<ParserRuleContext> inProgress = new HashSet<>();
    private final Set<BabbleParser.FunctionLiteralContext> functions = new LinkedHashSet<>();
    private final Set<Diagnostic> diagnostics = new TreeSet<>(ORDER);
    private final Set<Diagnostic> warnings = new TreeSet<>(ORDER);
    private final Set<ParserRuleContext> widened = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Typer typer = new Typer();
    private final Map<ParserRuleContext, Type> expectedTypes = new IdentityHashMap<>();
    private final Map<BabbleParser.FunctionLiteralContext, Type> contextualReturnTypes = new IdentityHashMap<>();
    private final Map<BabbleParser.FunctionLiteralContext, List<BabbleParser.ReturnExpressionContext>> functionReturns = new IdentityHashMap<>();
    private final Deque<BabbleParser.FunctionLiteralContext> inferenceStack = new ArrayDeque<>();
    private final Set<BabbleParser.FunctionLiteralContext> inferringResult = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BabbleParser.FunctionLiteralContext> recursiveFunctions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<BabbleParser.FunctionLiteralContext, Type> provisionalResults = new IdentityHashMap<>();
    private final Deque<Map<ParserRuleContext, Type>> narrowings = new ArrayDeque<>();
    private final Map<ParserRuleContext, Type> inferredDefs = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> nextInferredDefs = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> inferredMembers = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> nextInferredMembers = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Widening> defWidenings = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Widening> memberWidenings = new IdentityHashMap<>();
    private final Set<ParserRuleContext> failedDefs = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * The checkers of the files this one imports statically, transitively, by file : what
     * this file uses from another is typed by that file's checker (docs/design/modules.md §8.2).
     */
    private final Map<ParserRuleContext, TypeChecker> owners = new HashMap<>();

    private BabbleParser.FileContext file;

    TypeChecker(BlockScopes pass) {
        this.pass = pass;
        this.strict = pass.strict;
    }

    void run(BabbleParser.FileContext file) {
        this.file = file;
        for (ModuleInterface module : pass.modules.values()) {
            TypeChecker checker = module.checker();
            if (checker != null) {
                owners.put(checker.file, checker);
                owners.putAll(checker.owners);
            }
        }
        if (pass.session != null) {
            occurrenceScopes.putAll(pass.session.occurrenceScopes);
            reassigned.addAll(pass.session.reassigned);
        }
        for (BlockScopes.Occurrence occurrence : pass.occurrences) {
            occurrenceScopes.put(occurrence.ctx(), occurrence.scope());
        }
        for (BlockScopes.PendingAssignment assignment : pass.assignments) {
            if (resolveName(null, assignment.name(), assignment.scope()) instanceof ParserRuleContext declaration) {
                reassigned.add(declaration);
            }
        }
        new ReturnFinder().visit(file);
        if (strict) {
            int iterations = 0;
            boolean changed;
            do {
                resetPass();
                walk(file);
                for (BabbleParser.FunctionLiteralContext function : functions) {
                    checkResult(function);
                }
                changed = commitInferred();
            } while (changed && ++iterations < 64);
            checkMissingTypes(file);
        } else {
            walk(file);
            for (BabbleParser.FunctionLiteralContext function : functions) {
                checkResult(function);
            }
        }
        for (ParserRuleContext ctx : widened) {
            pass.analysis.widenToDouble(ctx);
        }
        for (Diagnostic warning : warnings) {
            pass.analysis.warn(warning.message(), warning.line(), warning.column());
        }
        for (Diagnostic diagnostic : diagnostics) {
            pass.analysis.typeDiagnostic(diagnostic.message(), diagnostic.line(), diagnostic.column());
        }
    }

    private void resetPass() {
        types.clear();
        declarationTypes.clear();
        annotations.clear();
        inProgress.clear();
        functions.clear();
        diagnostics.clear();
        warnings.clear();
        widened.clear();
        expectedTypes.clear();
        contextualReturnTypes.clear();
        inferenceStack.clear();
        inferringResult.clear();
        recursiveFunctions.clear();
        provisionalResults.clear();
        narrowings.clear();
        nextInferredDefs.clear();
        nextInferredMembers.clear();
        failedDefs.clear();
    }

    private boolean commitInferred() {
        Map<ParserRuleContext, Type> filteredDefs = new IdentityHashMap<>();
        for (Map.Entry<ParserRuleContext, Type> entry : nextInferredDefs.entrySet()) {
            if (entry.getValue() != Type.NULL) {
                filteredDefs.put(entry.getKey(), entry.getValue());
            }
        }
        Map<ParserRuleContext, Type> filteredMembers = new IdentityHashMap<>();
        for (Map.Entry<ParserRuleContext, Type> entry : nextInferredMembers.entrySet()) {
            if (entry.getValue() != Type.NULL) {
                filteredMembers.put(entry.getKey(), entry.getValue());
            }
        }
        boolean changed = !filteredDefs.equals(inferredDefs) || !filteredMembers.equals(inferredMembers);
        if (changed) {
            inferredDefs.clear();
            inferredDefs.putAll(filteredDefs);
            inferredMembers.clear();
            inferredMembers.putAll(filteredMembers);
        }
        return changed;
    }

    Map<ParserRuleContext, Type> frozenDeclarations() {
        Map<ParserRuleContext, Type> frozen = new HashMap<>();
        for (Map.Entry<ParserRuleContext, Type> entry : declarationTypes.entrySet()) {
            if (inFile(entry.getKey()) && !isUnknown(entry.getValue()) && entry.getValue() != Type.NULL) {
                frozen.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<ParserRuleContext, Type> entry : inferredDefs.entrySet()) {
            if (inFile(entry.getKey()) && !isUnknown(entry.getValue()) && entry.getValue() != Type.NULL) {
                frozen.put(entry.getKey(), entry.getValue());
            }
        }
        return frozen;
    }

    Map<ParserRuleContext, Type> frozenMembers() {
        Map<ParserRuleContext, Type> frozen = new HashMap<>();
        for (TypeNode node : pass.types) {
            for (TypeNode.Member member : node.members.values()) {
                freezeMember(member, frozen);
            }
            for (TypeNode.Member member : node.statics.values()) {
                freezeMember(member, frozen);
            }
        }
        return frozen;
    }

    private void freezeMember(TypeNode.Member member, Map<ParserRuleContext, Type> frozen) {
        if (inFile(member.declaration)) {
            Type type = memberType(member);
            if (!isUnknown(type) && type != Type.NULL) {
                frozen.put(member.declaration, type);
            }
        }
    }

    private final class ReturnFinder extends BabbleBaseVisitor<Void> {
        @Override
        public Void visitReturnExpression(BabbleParser.ReturnExpressionContext ctx) {
            BabbleParser.FunctionLiteralContext function = enclosingFunction(ctx);
            if (function != null) {
                functionReturns.computeIfAbsent(function, k -> new ArrayList<>()).add(ctx);
            }
            return super.visitReturnExpression(ctx);
        }
    }

    private static BabbleParser.FunctionLiteralContext enclosingFunction(ParserRuleContext ctx) {
        for (ParserRuleContext p = ctx.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.FunctionLiteralContext function) {
                return function;
            }
        }
        return null;
    }

    private void warn(String message, ParserRuleContext at) {
        if (!inFile(at)) {
            // In a session, typing a name can lead into an earlier input, reported when it was one.
            return;
        }
        if (strict && !message.startsWith("Missing type") && !message.startsWith("Unknown type")
                && !message.startsWith("Integer out of range")) {
            String note = wideningNote(at);
            if (note != null) {
                message += note;
            }
        }
        diagnostics.add(new Diagnostic(at.getStart().getLine(), at.getStart().getCharPositionInLine() + 1, message));
    }

    private String wideningNote(ParserRuleContext at) {
        int line = at.getStart().getLine();
        String note = findWideningNote(at, line);
        if (note != null) {
            return note;
        }
        if (at.getParent() instanceof BabbleParser.AssignExpressionContext assign && at == assign.value) {
            return assignmentWideningNote(assign, line);
        }
        if (at instanceof BabbleParser.AssignExpressionContext assign) {
            return assignmentWideningNote(assign, line);
        }
        return null;
    }

    private String findWideningNote(ParseTree tree, int diagnosticLine) {
        if (tree instanceof BabbleParser.SelectorContext selector) {
            String name = selector.ID().getText();
            if (selector.expression() == null) {
                ScopeNode scope = occurrenceScopes.get(selector);
                if (scope != null && resolveName(selector, name, scope) instanceof ParserRuleContext decl) {
                    Widening widening = defWidenings.get(decl);
                    if (widening != null && widening.line() > diagnosticLine) {
                        return " (" + name + " is " + widening.type() + " because of the assignment at line " + widening.line() + ")";
                    }
                }
            } else {
                Type receiver = types.get(selector.expression());
                TypeNode.Member member = targetMember(receiver, name);
                if (member != null) {
                    Widening widening = memberWidenings.get(member.declaration);
                    if (widening != null && widening.line() > diagnosticLine) {
                        return " (" + name + " is " + widening.type() + " because of the assignment at line " + widening.line() + ")";
                    }
                }
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
        return null;
    }

    private String assignmentWideningNote(BabbleParser.AssignExpressionContext assign, int diagnosticLine) {
        String name = assign.name.getText();
        if (assign.namespace == null) {
            ScopeNode scope = occurrenceScopes.get(assign);
            if (scope != null && resolveName(assign, name, scope) instanceof ParserRuleContext decl) {
                Widening widening = defWidenings.get(decl);
                if (widening != null && widening.line() > diagnosticLine) {
                    return " (" + name + " is " + widening.type() + " because of the assignment at line " + widening.line() + ")";
                }
            }
        } else {
            Type receiver = types.get(assign.namespace);
            TypeNode.Member member = targetMember(receiver, name);
            if (member != null) {
                Widening widening = memberWidenings.get(member.declaration);
                if (widening != null && widening.line() > diagnosticLine) {
                    return " (" + name + " is " + widening.type() + " because of the assignment at line " + widening.line() + ")";
                }
            }
        }
        return null;
    }

    private static TypeNode.Member targetMember(Type receiver, String name) {
        if (receiver instanceof Type.Instance instance) {
            return findMember(instance.node(), name);
        }
        if (receiver instanceof Type.ClassValue value) {
            return value.node().statics.get(name);
        }
        return null;
    }

    private void analysisWarn(String message, ParserRuleContext at) {
        if (!inFile(at)) {
            return;
        }
        warnings.add(new Diagnostic(at.getStart().getLine(), at.getStart().getCharPositionInLine() + 1, message));
    }

    private boolean inFile(ParserRuleContext ctx) {
        ParserRuleContext root = ctx;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        return root == file;
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
        if (tree instanceof BabbleParser.BlockContext block
                && block.getParent() instanceof BabbleParser.FunctionLiteralContext fn
                && fn.functionBlock == block) {
            typeOf(block, expectedReturnType(fn));
        } else if (tree instanceof BabbleParser.ExpressionContext || tree instanceof BabbleParser.BlockContext) {
            typeOf((ParserRuleContext) tree);
        }
        recordCheck(tree);
        if (tree instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.defaultValue != null && parameter.type() != null) {
            Type declared = annotation(parameter.type(), scopeOf(parameter));
            expect(typeOf(parameter.defaultValue, declared), declared, parameter.defaultValue);
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            walk(tree.getChild(i));
        }
    }

    private void checkMissingTypes(BabbleParser.FileContext file) {
        new BabbleBaseVisitor<Void>() {
            @Override
            public Void visitSimpleType(BabbleParser.SimpleTypeContext ctx) {
                return null;
            }

            @Override
            public Void visitFunctionType(BabbleParser.FunctionTypeContext ctx) {
                return null;
            }

            @Override
            public Void visitDefExpression(BabbleParser.DefExpressionContext def) {
                if (def.type() == null && !failedDefs.contains(def)) {
                    ScopeAnalysis.DefKind kind = pass.analysis.defTarget(def).kind();
                    Type inferred = (kind == ScopeAnalysis.DefKind.MEMBER || kind == ScopeAnalysis.DefKind.STATIC)
                            ? inferredMembers.get(def) : inferredDefs.get(def);
                    if (inferred == null || isUnknown(inferred) || inferred == Type.NULL) {
                        warn("Missing type : " + def.name.getText(), def);
                    }
                }
                return super.visitDefExpression(def);
            }

            @Override
            public Void visitParameterDeclaration(BabbleParser.ParameterDeclarationContext parameter) {
                Type type = declarationTypes.get(parameter);
                if (parameter.type() == null && (type == null || isUnknown(type)) && !failedDefs.contains(parameter)) {
                    warn("Missing type : " + parameter.ID().getText(), parameter);
                }
                return super.visitParameterDeclaration(parameter);
            }
        }.visit(file);
    }

    // ---------------------------------------------------------------- runtime checks (T9)

    /**
     * Records the declared type each boundary checks at runtime : parameters of functions
     * and class headers, results of functions, defs, and unqualified assignments to
     * annotated names. Members and statics also carry theirs in the class shape, for
     * assignments through a receiver.
     */
    private void recordCheck(ParseTree tree) {
        if (tree instanceof BabbleParser.ParameterDeclarationContext parameter) {
            Type type = parameter.type() != null
                    ? annotation(parameter.type(), scopeOf(parameter))
                    : (strict ? declarationTypes.get(parameter) : null);
            if (parameter.type() != null || (strict && type == Type.DOUBLE)) {
                String descriptor = descriptor(type);
                pass.analysis.setCheckedType(parameter, descriptor);
                if (parameter.getParent().getParent() instanceof BabbleParser.ClassExpressionContext cls
                        && (parameter.DEF() != null || pass.typeNodes.get(cls).kind == org.babblelang.runtime.ClassShape.Kind.VALUE_CLASS)) {
                    putType(pass.typeNodes.get(cls).memberTypes, parameter.ID().getText(), descriptor);
                }
            }
        } else if (tree instanceof BabbleParser.FunctionLiteralContext function && function.type() != null) {
            pass.analysis.setCheckedType(function, descriptor(annotation(function.type(), scopeOf(function))));
        } else if (tree instanceof BabbleParser.DefExpressionContext def) {
            ScopeAnalysis.DefKind kind = pass.analysis.defTarget(def).kind();
            if (kind == ScopeAnalysis.DefKind.DEFAULT || kind == ScopeAnalysis.DefKind.REQUIRED) {
                return;
            }
            String descriptor = null;
            if (def.type() != null) {
                descriptor = descriptor(annotation(def.type(), scopeOf(def)));
            } else if (strict) {
                Type inferred = (kind == ScopeAnalysis.DefKind.MEMBER || kind == ScopeAnalysis.DefKind.STATIC)
                        ? inferredMembers.get(def) : inferredDefs.get(def);
                if (inferred == Type.DOUBLE) {
                    descriptor = descriptor(Type.DOUBLE);
                }
            }
            if (descriptor != null) {
                pass.analysis.setCheckedType(def, descriptor);
                if (kind == ScopeAnalysis.DefKind.MEMBER) {
                    putType(scopeOf(def).type.memberTypes, def.name.getText(), descriptor);
                } else if (kind == ScopeAnalysis.DefKind.STATIC) {
                    putType(scopeOf(def).type.staticTypes, def.name.getText(), descriptor);
                }
            }
        } else if (tree instanceof BabbleParser.AssignExpressionContext assign && assign.namespace == null
                && resolveName(assign, assign.name.getText(), occurrenceScopes.get(assign)) instanceof ParserRuleContext declaration) {
            Type declared = declaredType(declaration);
            if (declared != null) {
                pass.analysis.setCheckedType(assign, descriptor(declared));
            } else if (strict && typeOfDeclaration(declaration) == Type.DOUBLE) {
                pass.analysis.setCheckedType(assign, descriptor(Type.DOUBLE));
            }
        }
    }

    private static void putType(Map<String, String> types, String name, String descriptor) {
        if (descriptor != null) {
            types.put(name, descriptor);
        }
    }

    /** How the runtime checks a type ({@code Types}), or null when it needs no check. */
    private static String descriptor(Type type) {
        if (type == Type.INT || type == Type.DOUBLE || type == Type.BOOL || type == Type.STRING) {
            return type + "|" + type + "|";
        }
        if (type instanceof Type.Function) {
            return "fn|" + type + "|";
        }
        if (type instanceof Type.Instance instance) {
            return "babble|" + instance.node().name + "|" + instance.node().id;
        }
        if (type instanceof Type.JavaInstance java) {
            return "java|" + java.type().getName() + "|" + java.type().getName();
        }
        if (type instanceof Type.JavaArray array) {
            return "java|" + array + "|" + array.javaClass().getName();
        }
        return null;
    }

    // ---------------------------------------------------------------- scopes and names

    /** The innermost scope a node is in. */
    /**
     * A question about a construct of another file, answered by that file's checker : null
     * when that file has none (the checker is off, or it is in an import cycle with this one).
     * The checker is shared by every file importing its file, so it answers one at a time.
     */
    private <T> T ask(ParserRuleContext ctx, java.util.function.Function<TypeChecker, T> question) {
        ParserRuleContext root = ctx;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        TypeChecker owner = owners.get(root);
        if (owner == null) {
            return null;
        }
        synchronized (owner) {
            return question.apply(owner);
        }
    }

    /** Whether a construct is part of what this pass analysed, rather than of an imported file. */
    private boolean isLocal(ParserRuleContext ctx) {
        for (ParserRuleContext p = ctx; p != null; p = p.getParent()) {
            if (pass.scopeNodes.containsKey(p) || pass.session != null && pass.session.scopeNodes.containsKey(p)) {
                return true;
            }
        }
        return false;
    }

    private ScopeNode scopeOf(ParserRuleContext ctx) {
        for (ParserRuleContext p = ctx; p != null; p = p.getParent()) {
            ScopeNode scope = pass.scopeNodes.get(p);
            if (scope == null && pass.session != null) {
                scope = pass.session.scopeNodes.get(p);
            }
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
        if (!isLocal(declaration)) {
            Type type = ask(declaration, owner -> owner.typeOfDeclaration(declaration));
            return type != null ? type : Type.DYNAMIC;
        }
        if (strict && !inFile(declaration) && pass.session != null) {
            Type frozen = pass.session.declarationTypes.get(declaration);
            if (frozen != null) {
                return frozen;
            }
        }
        if (declaration instanceof BabbleParser.DefExpressionContext def && def.type() != null) {
            Type declared = annotation(def.type(), scopeOf(def));
            declarationTypes.put(declaration, declared);
            return declared;
        }
        if (declaration instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.type() != null) {
            Type declared = annotation(parameter.type(), scopeOf(parameter));
            declarationTypes.put(declaration, declared);
            return declared;
        }
        if (declaration instanceof BabbleParser.DefExpressionContext def
                && (strict || !reassigned.contains(def))) {
            BabbleParser.FunctionLiteralContext literal = unwrapFunctionLiteral(def.value);
            if (literal != null && (inferringResult.contains(literal) || provisionalResults.containsKey(literal))) {
                return recursiveSignature(literal);
            }
        }
        Type cached = declarationTypes.get(declaration);
        if (cached != null) {
            return cached;
        }
        if (strict && inferredDefs.containsKey(declaration)
                && !(declaration instanceof BabbleParser.DefExpressionContext def
                && !reassigned.contains(def) && unwrapFunctionLiteral(def.value) != null)) {
            return inferredDefs.get(declaration);
        }
        if (!inProgress.add(declaration)) {
            if (declaration instanceof BabbleParser.DefExpressionContext def
                    && def.type() == null && (strict || !reassigned.contains(def))) {
                BabbleParser.FunctionLiteralContext literal = unwrapFunctionLiteral(def.value);
                if (literal != null) {
                    if (inferringResult.contains(literal) || provisionalResults.containsKey(literal)) {
                        return recursiveSignature(literal);
                    }
                    Type litCached = types.get(literal);
                    if (litCached != null) {
                        return litCached;
                    }
                }
                if (strict) {
                    warn("Missing type : " + def.name.getText() + " (recursive)", def);
                    failedDefs.add(def);
                }
            }
            return Type.DYNAMIC;
        }
        try {
            Type type = Type.DYNAMIC;
            if (declaration instanceof BabbleParser.DefExpressionContext def) {
                if (def.value != null && (strict || !reassigned.contains(def))) {
                    BabbleParser.FunctionLiteralContext literal = unwrapFunctionLiteral(def.value);
                    if (literal != null && (inferringResult.contains(literal) || provisionalResults.containsKey(literal))) {
                        return recursiveSignature(literal);
                    }
                    Type valueType = typeOf(def.value);
                    type = strict ? (valueType == Type.NOTHING ? Type.DYNAMIC : valueType) : inferred(valueType);
                }
            } else if (strict && declaration instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.defaultValue != null) {
                Type valueType = typeOf(parameter.defaultValue);
                if (valueType != Type.NULL && valueType != Type.NOTHING && valueType != Type.DYNAMIC) {
                    type = valueType;
                }
            }
            if (!strict || type != Type.DYNAMIC) {
                declarationTypes.put(declaration, type);
            }
            return type;
        } finally {
            inProgress.remove(declaration);
        }
    }

    /** An inferred type is never stricter than what runs : null says nothing about later values. */
    private Type inferred(Type type) {
        if (strict) {
            return type == Type.NOTHING ? Type.DYNAMIC : type;
        }
        return type == Type.NULL || type == Type.NOTHING ? Type.DYNAMIC : type;
    }

    /** The annotation a def or parameter declares, or null. */
    private Type declaredType(ParserRuleContext declaration) {
        if (!isLocal(declaration)) {
            return ask(declaration, owner -> owner.declaredType(declaration));
        }
        if (strict && !inFile(declaration) && pass.session != null) {
            Type frozen = pass.session.declarationTypes.get(declaration);
            if (frozen != null) {
                return frozen;
            }
        }
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
            result = new Type.Function(functionTypeParameters(function.parametersDeclaration(), scope),
                    function.type() != null ? annotation(function.type(), scope) : (strict ? Type.ANY : Type.DYNAMIC));
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

    private List<Type.Parameter> functionTypeParameters(BabbleParser.ParametersDeclarationContext declaration, ScopeNode scope) {
        List<Type.Parameter> result = new ArrayList<>();
        for (BabbleParser.ParameterDeclarationContext parameter : declaration.parameterDeclaration()) {
            Type type;
            if (parameter.type() != null) {
                type = annotation(parameter.type(), scope);
            } else {
                if (strict) {
                    warn("Missing type : " + parameter.ID().getText(), parameter);
                }
                type = Type.DYNAMIC;
            }
            result.add(new Type.Parameter(parameter.ID().getText(), type, parameter.defaultValue != null));
        }
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
                case "any": return Type.ANY;
                case "dynamic": return strict ? null : Type.DYNAMIC;
                default: return null;
            }
        }
        if (resolved instanceof BabbleParser.DefExpressionContext def && (strict || !reassigned.contains(def))) {
            if (path.size() == 1) {
                Type local = classType(def.value);
                if (local != null) {
                    return local;
                }
            } else {
                String imported = importedPackage(def.value);
                if (imported != null) {
                    return javaType(imported + "." + String.join(".", path.subList(1, path.size())));
                }
            }
            if (fromModule(def.value, scopeOf(def), 0)) {
                return typeThroughModule(typeOfDeclaration(def), path, type);
            }
            return null;
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

    /**
     * Whether a value is {@code import("file.ba")}, or selected from a never-reassigned def
     * of such a value : {@code def shapes = import("shapes.ba") ; def Circle = shapes.Circle}.
     */
    private boolean fromModule(BabbleParser.ExpressionContext value, ScopeNode scope, int depth) {
        if (value == null || depth > 16) {
            return false;
        }
        if (value instanceof BabbleParser.CallContext call) {
            return call.expression() instanceof BabbleParser.SelectorContext callee && callee.expression() == null
                    && callee.ID().getText().equals("import")
                    && call.callParameters().callParameter().size() == 1
                    && call.callParameters().callParameter(0).expression() instanceof BabbleParser.StringContext string
                    && org.babblelang.engine.impl.Interpreter.unescape(string.getText()).endsWith(".ba");
        }
        if (value instanceof BabbleParser.SelectorContext selector) {
            if (selector.expression() != null) {
                return fromModule(selector.expression(), scope, depth + 1);
            }
            return resolveName(null, selector.ID().getText(), scope) instanceof BabbleParser.DefExpressionContext def
                    && (strict || !reassigned.contains(def)) && fromModule(def.value, scopeOf(def), depth + 1);
        }
        return false;
    }

    /**
     * A type named through a module : {@code shapes.Circle}, {@code lib.geo.Point}, or
     * {@code Circle} after {@code def Circle = shapes.Circle}. The class or interface the path
     * ends on, as its own file types it ; dynamic when a file on the way can't be typed (a
     * cycle) ; null when the path names no class or interface.
     */
    private Type typeThroughModule(Type type, List<String> path, ParserRuleContext at) {
        for (int i = 1; i < path.size() && type != null && type != Type.DYNAMIC; i++) {
            if (type instanceof Type.Module module) {
                if (strict && module.module().checker() == null && module.module().exports().contains(path.get(i))) {
                    warn("Missing type : " + path.get(i) + " (import cycle with " + module.module().name() + ")", at);
                    return Type.DYNAMIC;
                }
                type = exportType(module.module(), path.get(i));
            } else if (type instanceof Type.Package pkg) {
                type = packageMember(pkg, path.get(i));
            } else {
                return null;
            }
        }
        if (type == Type.DYNAMIC) {
            return Type.DYNAMIC;
        }
        return type instanceof Type.ClassValue value ? new Type.Instance(value.node()) : null;
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
                    return index == path.size() - 1 && (strict || !reassigned.contains(def)) ? classType(def.value) : null;
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
            String name = org.babblelang.engine.impl.Interpreter.unescape(string.getText());
            // A file name imports a Babble module, whose members are not known statically.
            return name.endsWith(".ba") ? null : name;
        }
        return null;
    }

    /** A Java class named in a type position, nested classes included. */
    private Type javaType(String name) {
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
    private Type fromJava(Class<?> java) {
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
        if (java.isArray()) {
            return new Type.JavaArray(java, fromJava(java.getComponentType()));
        }
        if (java == Object.class) {
            return strict ? Type.ANY : Type.DYNAMIC;
        }
        if (java.isPrimitive()) {
            return strict ? new Type.JavaInstance(JavaTypes.box(java)) : Type.DYNAMIC;
        }
        return new Type.JavaInstance(java);
    }

    /**
     * The type of what a Java method returns, or a Java field holds : an instance of its
     * class, whose members are checked. Not for an interface, as a Babble instance passed to
     * Java comes back as itself where its interface is declared, nor for an array, or a class
     * whose members Babble reaches through others : those are {@code dynamic}.
     */
    private Type javaResult(Class<?> java) {
        Type type = fromJava(java);
        if (type instanceof Type.JavaInstance) {
            if (strict) {
                if (!java.isInterface() && !JavaTypes.isAccessible(java)) {
                    Class<?> accessible = accessibleType(java);
                    return accessible != null ? fromJava(accessible) : Type.ANY;
                }
                return type;
            }
            if (java.isInterface() || java.isArray() || !JavaTypes.isAccessible(java)) {
                return Type.DYNAMIC;
            }
        }
        return type;
    }

    private static Class<?> accessibleType(Class<?> type) {
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (current != type && current != Object.class && JavaTypes.isAccessible(current)) {
                return current;
            }
            if (current.getSuperclass() != null && current.getSuperclass() != Object.class) {
                queue.add(current.getSuperclass());
            }
            queue.addAll(Arrays.asList(current.getInterfaces()));
        }
        return null;
    }

    // ---------------------------------------------------------------- expressions

    private Type typeOf(ParserRuleContext ctx, Type expected) {
        if (ctx == null) {
            return Type.DYNAMIC;
        }
        if (expected != null && expected != Type.DYNAMIC && !types.containsKey(ctx)) {
            expectedTypes.put(ctx, expected);
        }
        return typeOf(ctx);
    }

    private Type expectedType(ParserRuleContext ctx) {
        return expectedTypes.get(ctx);
    }

    private Type typeOf(ParserRuleContext ctx) {
        if (!isLocal(ctx)) {
            Type type = ask(ctx, owner -> owner.typeOf(ctx));
            return type != null ? type : Type.DYNAMIC;
        }
        Type cached = types.get(ctx);
        if (cached != null) {
            return cached;
        }
        if (!inProgress.add(ctx)) {
            if (ctx instanceof BabbleParser.FunctionLiteralContext literal
                    && (inferringResult.contains(literal) || provisionalResults.containsKey(literal))) {
                return recursiveSignature(literal);
            }
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

    /** The type of a function literal : its parameters and annotated or inferred result. */
    private Type.Function signature(BabbleParser.FunctionLiteralContext literal) {
        applyContextualType(literal);
        List<Type.Parameter> params = functionParameters(literal);
        typeDefaultValues(literal);
        Type result;
        if (literal.type() != null) {
            result = annotation(literal.type(), scopeOf(literal));
        } else if (provisionalResults.containsKey(literal)) {
            result = provisionalResults.get(literal);
        } else if (inferringResult.contains(literal)) {
            return recursiveSignature(literal);
        } else {
            result = inferFunctionResult(literal);
        }
        return new Type.Function(params, result);
    }

    private Type.Function recursiveSignature(BabbleParser.FunctionLiteralContext literal) {
        List<Type.Parameter> params = functionParameters(literal);
        if (provisionalResults.containsKey(literal)) {
            return new Type.Function(params, provisionalResults.get(literal));
        }
        if (!inferenceStack.isEmpty() && inferenceStack.peek() == literal) {
            recursiveFunctions.add(literal);
            return new Type.Function(params, Type.NOTHING);
        }
        if (strict) {
            for (BabbleParser.FunctionLiteralContext fn : inferenceStack) {
                BabbleParser.DefExpressionContext def = owningDef(fn);
                if (def != null) {
                    warn("Missing type : " + def.name.getText() + " (recursive)", def);
                    failedDefs.add(def);
                } else {
                    warn("Missing type : function (recursive)", fn);
                }
                if (fn == literal) {
                    break;
                }
            }
        }
        return new Type.Function(params, Type.DYNAMIC);
    }

    private static BabbleParser.DefExpressionContext owningDef(BabbleParser.FunctionLiteralContext literal) {
        ParserRuleContext child = literal;
        while (child.getParent() instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            child = block;
        }
        return child.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == child ? def : null;
    }

    private List<Type.Parameter> functionParameters(BabbleParser.FunctionLiteralContext literal) {
        ScopeNode scope = scopeOf(literal);
        List<Type.Parameter> result = new ArrayList<>();
        for (BabbleParser.ParameterDeclarationContext parameter : literal.parametersDeclaration().parameterDeclaration()) {
            Type type;
            if (parameter.type() != null) {
                type = annotation(parameter.type(), scope);
            } else if (declarationTypes.containsKey(parameter)) {
                type = declarationTypes.get(parameter);
            } else if (strict && parameter.defaultValue != null) {
                Type defType = typeOf(parameter.defaultValue);
                if (defType != Type.NULL && defType != Type.NOTHING && defType != Type.DYNAMIC) {
                    declarationTypes.put(parameter, defType);
                    type = defType;
                } else {
                    type = Type.DYNAMIC;
                }
            } else {
                type = Type.DYNAMIC;
            }
            result.add(new Type.Parameter(parameter.ID().getText(), type, parameter.defaultValue != null));
        }
        return result;
    }

    private void typeDefaultValues(BabbleParser.FunctionLiteralContext literal) {
        ScopeNode scope = scopeOf(literal);
        for (BabbleParser.ParameterDeclarationContext parameter : literal.parametersDeclaration().parameterDeclaration()) {
            if (parameter.defaultValue != null) {
                Type expected = parameter.type() != null
                        ? annotation(parameter.type(), scope)
                        : declarationTypes.get(parameter);
                if (!types.containsKey(parameter.defaultValue)) {
                    Type actual = typeOf(parameter.defaultValue, expected);
                    if (strict && expected != null && expected != Type.DYNAMIC) {
                        expect(actual, expected, parameter.defaultValue);
                    }
                } else if (strict && expected != null && expected != Type.DYNAMIC) {
                    expect(types.get(parameter.defaultValue), expected, parameter.defaultValue);
                }
            }
        }
    }

    private void applyContextualType(BabbleParser.FunctionLiteralContext literal) {
        Type expected = expectedType(literal);
        if (expected == null && literal.getParent() instanceof BabbleParser.DefExpressionContext def
                && def.value == literal && def.type() != null) {
            expected = annotation(def.type(), scopeOf(def));
        }
        if (expected == null) {
            return;
        }
        List<Type> expectedParamTypes = null;
        Type expectedResult = null;
        if (expected instanceof Type.Function fn) {
            expectedParamTypes = fn.parameters().stream().map(Type.Parameter::type).toList();
            expectedResult = fn.result();
        } else if (expected instanceof Type.JavaInstance java) {
            Method sam = JavaTypes.functionalMethod(java.type());
            if (sam != null) {
                expectedParamTypes = Arrays.stream(sam.getParameterTypes()).map(this::fromJava).toList();
                expectedResult = fromJava(sam.getReturnType());
            }
        }
        if (expectedParamTypes == null) {
            return;
        }
        List<BabbleParser.ParameterDeclarationContext> params = literal.parametersDeclaration().parameterDeclaration();
        int required = 0;
        for (BabbleParser.ParameterDeclarationContext param : params) {
            if (param.defaultValue == null) {
                required++;
            }
        }
        if (required <= expectedParamTypes.size() && params.size() >= expectedParamTypes.size()) {
            for (int i = 0; i < expectedParamTypes.size(); i++) {
                BabbleParser.ParameterDeclarationContext param = params.get(i);
                if (param.type() == null && !declarationTypes.containsKey(param)) {
                    Type paramType = expectedParamTypes.get(i);
                    if (paramType != null && paramType != Type.DYNAMIC) {
                        declarationTypes.put(param, paramType);
                    }
                }
            }
        }
        if (literal.type() == null && expectedResult != null
                && expectedResult != Type.DYNAMIC && expectedResult != Type.NULL && expectedResult != Type.NOTHING) {
            contextualReturnTypes.putIfAbsent(literal, expectedResult);
        }
    }

    private Type expectedReturnType(BabbleParser.FunctionLiteralContext function) {
        if (function.type() != null) {
            return annotation(function.type(), scopeOf(function));
        }
        return contextualReturnTypes.get(function);
    }

    private Type inferFunctionResult(BabbleParser.FunctionLiteralContext literal) {
        if (literal.functionBlock == null) {
            return Type.DYNAMIC;
        }
        List<Diagnostic> savedDiagnostics = new ArrayList<>(diagnostics);
        List<Diagnostic> savedWarnings = new ArrayList<>(warnings);
        inferringResult.add(literal);
        inferenceStack.push(literal);
        Type expectedReturn = contextualReturnTypes.get(literal);
        Type pass1;
        try {
            pass1 = computeBodyResult(literal, expectedReturn);
        } finally {
            inferenceStack.pop();
            inferringResult.remove(literal);
        }
        Type candidate = inferred(pass1);
        if (recursiveFunctions.remove(literal)) {
            if (candidate != Type.DYNAMIC) {
                diagnostics.clear();
                diagnostics.addAll(savedDiagnostics);
                warnings.clear();
                warnings.addAll(savedWarnings);
                clearSubtreeTypes(literal.functionBlock);
                provisionalResults.put(literal, candidate);
                inferenceStack.push(literal);
                try {
                    Type pass2 = computeBodyResult(literal, expectedReturn);
                    candidate = inferred(pass2);
                } finally {
                    inferenceStack.pop();
                    provisionalResults.remove(literal);
                }
            } else if (strict && pass1 == Type.NOTHING) {
                BabbleParser.DefExpressionContext def = owningDef(literal);
                if (def != null) {
                    warn("Missing type : " + def.name.getText() + " (recursive)", def);
                    failedDefs.add(def);
                } else {
                    warn("Missing type : function (recursive)", literal);
                }
            }
        }
        return candidate;
    }

    private Type computeBodyResult(BabbleParser.FunctionLiteralContext literal, Type expectedReturn) {
        Type bodyType = typeOf(literal.functionBlock, expectedReturn);
        Type result = bodyType;
        List<BabbleParser.ReturnExpressionContext> returns = functionReturns.getOrDefault(literal, List.of());
        List<Type> returnTypes = new ArrayList<>(returns.size());
        for (BabbleParser.ReturnExpressionContext ret : returns) {
            Type retType = ret.expression() != null ? typeOf(ret.expression(), expectedReturn) : Type.NULL;
            returnTypes.add(retType);
            if (strict && expectedReturn != Type.ANY) {
                Type next = join(result, retType);
                if (next == Type.DYNAMIC && !isUnknown(result) && !isUnknown(retType)) {
                    warn("Type error : incompatible return types : " + result + " and " + retType, ret);
                }
                result = next;
            } else {
                result = join(result, retType);
            }
        }
        if (strict && expectedReturn == Type.ANY) {
            return Type.ANY;
        }
        if (literal.type() == null && result == Type.DOUBLE) {
            if (bodyType == Type.INT) {
                widened.add(literal.functionBlock);
            }
            for (int i = 0; i < returns.size(); i++) {
                BabbleParser.ReturnExpressionContext ret = returns.get(i);
                if (ret.expression() != null && returnTypes.get(i) == Type.INT) {
                    widened.add(ret);
                }
            }
        }
        return result;
    }

    private void clearSubtreeTypes(ParseTree tree) {
        if (tree instanceof ParserRuleContext ctx) {
            types.remove(ctx);
            expectedTypes.remove(ctx);
            contextualReturnTypes.remove(ctx);
            widened.remove(ctx);
            if (ctx instanceof BabbleParser.DefExpressionContext || ctx instanceof BabbleParser.ParameterDeclarationContext) {
                declarationTypes.remove(ctx);
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            clearSubtreeTypes(tree.getChild(i));
        }
    }

    private static BabbleParser.FunctionLiteralContext unwrapFunctionLiteral(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            expr = block.block().expression(0);
        }
        return expr instanceof BabbleParser.FunctionLiteralContext literal ? literal : null;
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

    // ---------------------------------------------------------------- flow-sensitive narrowing

    private <T> T withNarrowing(Map<ParserRuleContext, Type> scope, Supplier<T> action) {
        if (scope.isEmpty()) {
            return action.get();
        }
        narrowings.push(scope);
        try {
            return action.get();
        } finally {
            narrowings.pop();
        }
    }

    private Type narrowedType(ParserRuleContext declaration) {
        if (reassigned.contains(declaration)) {
            return null;
        }
        for (Map<ParserRuleContext, Type> scope : narrowings) {
            Type narrowed = scope.get(declaration);
            if (narrowed != null) {
                return narrowed;
            }
        }
        return null;
    }

    private Narrowing extractNarrowing(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            return extractNarrowing(block.block().expression(0));
        }
        if (expr instanceof BabbleParser.BooleanNotContext notCtx) {
            return extractNarrowing(notCtx.expression()).invert();
        }
        if (expr instanceof BabbleParser.BooleanOpContext opCtx) {
            Narrowing left = extractNarrowing(opCtx.left);
            Narrowing right = extractNarrowing(opCtx.right);
            if (opCtx.op.getType() == BabbleLexer.AND) {
                return new Narrowing(mergeNarrowings(left.whenTrue(), right.whenTrue()), Map.of());
            }
            return new Narrowing(Map.of(), mergeNarrowings(left.whenFalse(), right.whenFalse()));
        }
        if (expr instanceof BabbleParser.IsExpressionContext isCtx) {
            ParserRuleContext decl = narrowableDeclaration(isCtx.left);
            if (decl == null) {
                return Narrowing.EMPTY;
            }
            Type target = isBuiltinTarget(isCtx);
            if (target == null) {
                target = instanceTypeOfTypeValue(typeOf(isCtx.right));
            }
            Type current = typeOf(isCtx.left);
            if (target == null || target == Type.DYNAMIC
                    || (current != Type.DYNAMIC && current != Type.NOTHING && assignable(current, target))) {
                return Narrowing.EMPTY;
            }
            return new Narrowing(Map.of(decl, target), Map.of());
        }
        return Narrowing.EMPTY;
    }

    private Type.Simple isBuiltinTarget(BabbleParser.IsExpressionContext ctx) {
        if (ctx.right instanceof BabbleParser.SelectorContext selector && selector.expression() == null) {
            String name = selector.ID().getText();
            if (resolveName(selector, name, occurrenceScopes.get(selector)) == null) {
                return switch (name) {
                    case "int" -> Type.INT;
                    case "double" -> Type.DOUBLE;
                    case "bool" -> Type.BOOL;
                    case "string" -> Type.STRING;
                    case "any" -> Type.ANY;
                    default -> null;
                };
            }
        }
        return null;
    }

    private static Map<ParserRuleContext, Type> mergeNarrowings(
            Map<ParserRuleContext, Type> first,
            Map<ParserRuleContext, Type> second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        Map<ParserRuleContext, Type> merged = new IdentityHashMap<>(first);
        merged.putAll(second);
        return merged;
    }

    private ParserRuleContext narrowableDeclaration(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            expr = block.block().expression(0);
        }
        if (expr instanceof BabbleParser.SelectorContext selector && selector.expression() == null) {
            Object resolved = resolveName(selector, selector.ID().getText(), occurrenceScopes.get(selector));
            if ((resolved instanceof BabbleParser.DefExpressionContext
                    || resolved instanceof BabbleParser.ParameterDeclarationContext)
                    && !reassigned.contains((ParserRuleContext) resolved)) {
                return (ParserRuleContext) resolved;
            }
        }
        return null;
    }

    private Type instanceTypeOfTypeValue(Type typeValue) {
        if (typeValue instanceof Type.ClassValue cv) {
            return new Type.Instance(cv.node());
        }
        if (typeValue instanceof Type.JavaClassValue jcv) {
            return fromJava(jcv.type());
        }
        return null;
    }

    /** The type of {@code this} : the class or interface whose body or method it is in, or the class value in a static block. */
    private Type thisType(ScopeNode scope) {
        ScopeNode thisScope = BlockScopes.thisScope(scope);
        if (thisScope == null) {
            return Type.DYNAMIC;
        }
        return thisScope.kind == ScopeNode.Kind.STATIC_BODY ? new Type.ClassValue(thisScope.type) : new Type.Instance(thisScope.type);
    }

    private static boolean isValueUsed(ParserRuleContext ctx) {
        ParserRuleContext current = ctx;
        while (true) {
            ParserRuleContext parent = current.getParent();
            if (parent == null || parent instanceof BabbleParser.FileContext) {
                return false;
            }
            if (parent instanceof BabbleParser.BlockContext block) {
                List<BabbleParser.ExpressionContext> exprs = block.expression();
                if (exprs.isEmpty() || exprs.get(exprs.size() - 1) != current) {
                    return false;
                }
                ParserRuleContext blockParent = block.getParent();
                if (blockParent instanceof BabbleParser.FunctionLiteralContext fn) {
                    return fn.functionBlock == block;
                }
                if (blockParent instanceof BabbleParser.ClassExpressionContext
                        || blockParent instanceof BabbleParser.InterfaceExpressionContext
                        || blockParent instanceof BabbleParser.ObjectExpressionContext
                        || blockParent instanceof BabbleParser.PackageExpressionContext) {
                    return false;
                }
                current = block;
                continue;
            }
            if (parent instanceof BabbleParser.BlockExpressionContext
                    || parent instanceof BabbleParser.IfExpressionContext ifExpr && ifExpr.test != current
                    || parent instanceof BabbleParser.WhileExpressionContext whileExpr && whileExpr.test != current) {
                current = parent;
                continue;
            }
            return true;
        }
    }

    private void recordInferredDef(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        recordInferred(nextInferredDefs, defWidenings, decl, valueType, at);
    }

    private void recordInferredMember(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        recordInferred(nextInferredMembers, memberWidenings, decl, valueType, at);
    }

    private void recordInferred(Map<ParserRuleContext, Type> map, Map<ParserRuleContext, Widening> widenMap,
                                ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        if (isUnknown(valueType)) {
            failedDefs.add(decl);
            return;
        }
        Type current = map.get(decl);
        if (current == null) {
            map.put(decl, valueType);
            return;
        }
        Type joined = join(current, valueType);
        if (joined == Type.DYNAMIC) {
            warn("Type error : expected " + current + ", got " + valueType, at);
            return;
        }
        if (!joined.equals(current)) {
            map.put(decl, joined);
            if (current == Type.INT && joined == Type.DOUBLE) {
                ParserRuleContext site = at instanceof BabbleParser.AssignExpressionContext ? at
                        : at.getParent() instanceof BabbleParser.AssignExpressionContext assign ? assign : null;
                if (site != null) {
                    widenMap.put(decl, new Widening(joined, site.getStart().getLine()));
                }
            }
        }
    }

    private final class Typer extends BabbleBaseVisitor<Type> {
        @Override
        public Type visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
            return Type.NULL;
        }

        @Override
        public Type visitIfExpression(BabbleParser.IfExpressionContext ctx) {
            typeOf(ctx.test);
            Narrowing narrowing = extractNarrowing(ctx.test);
            Type expected = expectedType(ctx);
            Type thenType = withNarrowing(narrowing.whenTrue(), () -> typeOf(ctx.thenBlock, expected));
            ParserRuleContext otherwise = ctx.elseBlock != null ? ctx.elseBlock : ctx.elseIf;
            Type elseType = otherwise != null
                    ? withNarrowing(narrowing.whenFalse(), () -> typeOf(otherwise, expected))
                    : Type.NULL;
            if (expected == Type.ANY) {
                return Type.ANY;
            }
            boolean used = isValueUsed(ctx);
            if (strict && expected != null && expected != Type.DYNAMIC && used) {
                expect(thenType, expected, ctx.thenBlock);
                if (otherwise != null) {
                    expect(elseType, expected, otherwise);
                } else if (!assignable(Type.NULL, expected)) {
                    warn("Type error : expected " + expected + ", got null", ctx);
                }
                if (expected == Type.DOUBLE) {
                    if (thenType == Type.INT) {
                        widened.add(ctx.thenBlock);
                    }
                    if (elseType == Type.INT && otherwise != null) {
                        widened.add(otherwise);
                    }
                }
                return expected;
            }
            Type joined = join(thenType, elseType);
            if (joined == Type.DOUBLE) {
                if (thenType == Type.INT) {
                    widened.add(ctx.thenBlock);
                }
                if (elseType == Type.INT && otherwise != null) {
                    widened.add(otherwise);
                }
            }
            if (strict && joined == Type.DYNAMIC) {
                if (!used) {
                    return Type.NULL;
                }
                if (!isUnknown(thenType) && !isUnknown(elseType)) {
                    warn("Type error : the branches of if have different types : " + thenType + " and " + elseType, ctx);
                }
            }
            return joined;
        }

        @Override
        public Type visitDefExpression(BabbleParser.DefExpressionContext ctx) {
            ScopeAnalysis.DefKind kind = pass.analysis.defTarget(ctx).kind();
            if (kind == ScopeAnalysis.DefKind.REQUIRED) {
                return Type.NULL;
            }
            Type declared = ctx.type() != null ? annotation(ctx.type(), scopeOf(ctx)) : null;
            Type value = ctx.value != null ? typeOf(ctx.value, declared) : Type.NULL;
            if (declared != null) {
                expect(value, declared, ctx.value != null ? ctx.value : ctx);
            } else if (strict) {
                ParserRuleContext at = ctx.value != null ? ctx.value : ctx;
                if (kind == ScopeAnalysis.DefKind.MEMBER || kind == ScopeAnalysis.DefKind.STATIC) {
                    recordInferredMember(ctx, value, at);
                    if (inferredMembers.get(ctx) == Type.DOUBLE && value == Type.INT) {
                        widened.add(ctx);
                    }
                } else {
                    recordInferredDef(ctx, value, at);
                    if (inferredDefs.get(ctx) == Type.DOUBLE && value == Type.INT) {
                        widened.add(ctx);
                    }
                }
            }
            return value;
        }

        @Override
        public Type visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
            typeOf(ctx.test);
            Narrowing narrowing = extractNarrowing(ctx.test);
            Type expected = expectedType(ctx);
            Type bodyType = withNarrowing(narrowing.whenTrue(),
                    () -> typeOf(ctx.whileBlock, strict && expected == Type.ANY ? Type.ANY : null));
            boolean used = isValueUsed(ctx);
            if (strict && expected != null && expected != Type.DYNAMIC && used) {
                if (expected == Type.ANY) {
                    return Type.ANY;
                }
                expect(bodyType, expected, ctx.whileBlock);
                if (!assignable(Type.NULL, expected)) {
                    warn("Type error : expected " + expected + ", got null", ctx);
                }
                if (expected == Type.DOUBLE && bodyType == Type.INT) {
                    widened.add(ctx.whileBlock);
                }
                return expected;
            }
            Type joined = join(bodyType, Type.NULL);
            if (joined == Type.DOUBLE && bodyType == Type.INT) {
                widened.add(ctx.whileBlock);
            }
            if (strict && joined == Type.DYNAMIC) {
                if (!used) {
                    return Type.NULL;
                }
                if (!isUnknown(bodyType)) {
                    warn("Type error : the branches of while have different types : " + bodyType + " and null", ctx);
                }
            }
            return joined;
        }

        @Override
        public Type visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
            if (strict && ctx.parametersDeclaration() != null) {
                constructorParameters(ctx);
            }
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
                    Type narrowed = narrowedType(declaration);
                    return narrowed != null ? narrowed : typeOfDeclaration(declaration);
                }
                if (resolved instanceof List<?> packages) {
                    @SuppressWarnings("unchecked")
                    List<BabbleParser.PackageExpressionContext> list = (List<BabbleParser.PackageExpressionContext>) packages;
                    return new Type.Package(name, list);
                }
                return implicitType(name);
            }
            if (receiver instanceof BabbleParser.SuperExpressionContext superExpr) {
                if (strict) {
                    TypeChecks.Provider provider = pass.superProviders.get(superExpr);
                    if (provider != null) {
                        if (provider.function() != null) {
                            return typeOf(provider.function());
                        }
                        if (provider.owner() instanceof Class<?> javaInterface) {
                            return new Type.JavaMethod(javaInterface, provider.name());
                        }
                    }
                }
                return Type.DYNAMIC;
            }
            return member(typeOf(receiver), name, receiver instanceof BabbleParser.ThisExpressionContext, ctx, receiver);
        }

        @Override
        public Type visitCall(BabbleParser.CallContext ctx) {
            Type callee = typeOf(ctx.expression());
            ModuleInterface module = pass.modules.get(ctx);
            Type result;
            if (module != null) {
                for (BabbleParser.CallParameterContext argument : ctx.callParameters().callParameter()) {
                    typeOf(argument.expression());
                }
                result = new Type.Module(module);
            } else if (strict && isUnshadowedImport(ctx.expression())
                    && ctx.callParameters().callParameter().size() == 1
                    && ctx.callParameters().callParameter(0).ID() == null
                    && ctx.callParameters().callParameter(0).expression() instanceof BabbleParser.StringContext string) {
                typeOf(string);
                String target = org.babblelang.engine.impl.Interpreter.unescape(string.getText());
                result = target.endsWith(".ba") ? Type.ANY : new Type.JavaPackage(target);
            } else {
                result = call(callee, ctx);
                for (BabbleParser.CallParameterContext argument : ctx.callParameters().callParameter()) {
                    typeOf(argument.expression());
                }
            }
            return result;
        }

        private boolean isUnshadowedImport(BabbleParser.ExpressionContext expression) {
            if (expression instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
                String name = sel.ID().getText();
                return (name.equals("import") || name.equals("importe"))
                        && resolveName(sel, name, occurrenceScopes.get(sel)) == null;
            }
            return false;
        }

        @Override
        public Type visitBlockExpression(BabbleParser.BlockExpressionContext ctx) {
            return typeOf(ctx.block(), expectedType(ctx));
        }

        @Override
        public Type visitBlock(BabbleParser.BlockContext ctx) {
            Type expected = expectedType(ctx);
            List<BabbleParser.ExpressionContext> statements = ctx.expression();
            if (statements.isEmpty()) {
                return Type.NULL;
            }
            Type type = Type.NULL;
            int pushedGuards = 0;
            try {
                for (int i = 0; i < statements.size(); i++) {
                    BabbleParser.ExpressionContext statement = statements.get(i);
                    type = i == statements.size() - 1 ? typeOf(statement, expected) : typeOf(statement);
                    if (i < statements.size() - 1 && statement instanceof BabbleParser.IfExpressionContext ifCtx) {
                        Type thenType = typeOf(ifCtx.thenBlock);
                        ParserRuleContext otherwise = ifCtx.elseBlock != null ? ifCtx.elseBlock : ifCtx.elseIf;
                        Type elseType = otherwise != null ? typeOf(otherwise) : Type.NULL;
                        Map<ParserRuleContext, Type> guard = Map.of();
                        if (thenType == Type.NOTHING && elseType != Type.NOTHING) {
                            guard = extractNarrowing(ifCtx.test).whenFalse();
                        } else if (elseType == Type.NOTHING && thenType != Type.NOTHING) {
                            guard = extractNarrowing(ifCtx.test).whenTrue();
                        }
                        if (!guard.isEmpty()) {
                            narrowings.push(guard);
                            pushedGuards++;
                        }
                    }
                }
                return type;
            } finally {
                for (int i = 0; i < pushedGuards; i++) {
                    narrowings.pop();
                }
            }
        }

        /** As {@code Operators.negate} : an int stays an int, another number is a double. */
        @Override
        public Type visitNegate(BabbleParser.NegateContext ctx) {
            if (strict && ctx.operand instanceof BabbleParser.IntegerContext literal) {
                try {
                    Integer.parseInt("-" + literal.getText());
                } catch (NumberFormatException e) {
                    warn("Integer out of range : -" + literal.getText(), ctx);
                }
                types.put(literal, Type.INT);
                return Type.INT;
            }
            Type operand = typeOf(ctx.operand);
            if (operand == Type.NOTHING) {
                return Type.NOTHING;
            }
            if (operand instanceof Type.Instance) {
                return operatorCall(operand, Operators.NEGATE, null, null, ctx, ctx.operand.getText());
            }
            if (!number(operand, ctx.operand) || isUnknown(operand)) {
                return Type.DYNAMIC;
            }
            return operand == Type.INT ? Type.INT : Type.DOUBLE;
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
            Type.Simple builtin = isBuiltinTarget(ctx);
            if (builtin != null) {
                pass.analysis.setIsBuiltin(ctx, builtin.name());
            } else {
                typeOf(ctx.right);
            }
            return Type.BOOL;
        }

        @Override
        public Type visitAsExpression(BabbleParser.AsExpressionContext ctx) {
            Type source = typeOf(ctx.left);
            Type target = annotation(ctx.right, scopeOf(ctx));
            if (target == Type.DYNAMIC) {
                return Type.DYNAMIC;
            }
            if (target instanceof Type.Function) {
                warn("Cannot cast to a function type", ctx.right);
                return target;
            }
            if (!canCast(source, target)) {
                warn("Type error : expected " + target + ", got " + source, ctx.left);
            } else if (alwaysIs(source, target)) {
                analysisWarn("Redundant cast : " + target, ctx);
            }
            pass.analysis.setCheckedType(ctx, descriptor(target));
            return target;
        }

        @Override
        public Type visitBooleanOp(BabbleParser.BooleanOpContext ctx) {
            typeOf(ctx.left);
            Narrowing leftNarrowing = extractNarrowing(ctx.left);
            Map<ParserRuleContext, Type> rightScope =
                    ctx.op.getType() == BabbleLexer.AND ? leftNarrowing.whenTrue() : leftNarrowing.whenFalse();
            withNarrowing(rightScope, () -> typeOf(ctx.right));
            return Type.BOOL;
        }

        @Override
        public Type visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
            if (inFile(ctx)) {
                functions.add(ctx);
            }
            Type.Function sig = signature(ctx);
            types.put(ctx, sig);
            if (ctx.functionBlock != null && !types.containsKey(ctx.functionBlock)
                    && (!strict || ctx.type() == null || inferenceStack.isEmpty())) {
                typeOf(ctx.functionBlock, expectedReturnType(ctx));
            }
            return sig;
        }

        @Override
        public Type visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
            String operator = Operators.compound(ctx.op.getText());
            if (operator != null) {
                return compoundAssignment(ctx, operator);
            }
            String name = ctx.name.getText();
            Type declared = null;
            ParserRuleContext inferredTarget = null;
            boolean memberTarget = false;
            if (ctx.namespace == null) {
                if (resolveName(ctx, name, occurrenceScopes.get(ctx)) instanceof ParserRuleContext declaration) {
                    declared = declaredType(declaration);
                    if (strict && declared == null) {
                        inferredTarget = declaration;
                    }
                }
            } else {
                Type receiver = typeOf(ctx.namespace);
                java.lang.reflect.Field field = assignedJavaField(receiver, name, ctx);
                if (field != null) {
                    Type value = typeOf(ctx.value);
                    if (isFunction(value) && JavaTypes.isFunctionalInterface(field.getType())) {
                        warn("Cannot assign a function to " + field.getDeclaringClass().getCanonicalName() + "." + name
                                + " : convert it first, as " + field.getType().getSimpleName() + "(f)", ctx.value);
                    } else {
                        expect(value, fromJava(field.getType()), ctx.value);
                    }
                    return value;
                }
                boolean own = ctx.namespace instanceof BabbleParser.ThisExpressionContext;
                declared = assignedMemberType(receiver, name, own, ctx);
                if (strict && declared == null) {
                    TypeNode.Member member = assignedBabbleMember(receiver, name, own);
                    if (member != null && member.function == null && member.declaration instanceof BabbleParser.DefExpressionContext def
                            && isLocal(def) && inFile(def)) {
                        inferredTarget = def;
                        memberTarget = true;
                    }
                }
            }
            Type value = typeOf(ctx.value, declared);
            if (declared != null) {
                expect(value, declared, ctx.value);
                if (strict && declared == Type.DOUBLE && value == Type.INT) {
                    widened.add(ctx);
                }
            } else if (strict && inferredTarget != null) {
                if (memberTarget) {
                    recordInferredMember(inferredTarget, value, ctx.value);
                    if (inferredMembers.get(inferredTarget) == Type.DOUBLE && value == Type.INT) {
                        widened.add(ctx);
                    }
                } else {
                    recordInferredDef(inferredTarget, value, ctx.value);
                    if (inferredDefs.get(inferredTarget) == Type.DOUBLE && value == Type.INT) {
                        widened.add(ctx);
                    }
                }
            }
            return value;
        }

        /**
         * {@code x op= v} : typed as {@code x = x op v}, the operator's result checked against
         * what the target declares.
         */
        private Type compoundAssignment(BabbleParser.AssignExpressionContext ctx, String operator) {
            String name = ctx.name.getText();
            Type declared = null;
            Type current = Type.DYNAMIC;
            ParserRuleContext inferredTarget = null;
            boolean memberTarget = false;
            if (ctx.namespace == null) {
                if (resolveName(ctx, name, occurrenceScopes.get(ctx)) instanceof ParserRuleContext declaration) {
                    declared = declaredType(declaration);
                    Type narrowed = narrowedType(declaration);
                    current = narrowed != null ? narrowed : typeOfDeclaration(declaration);
                    if (strict && declared == null) {
                        inferredTarget = declaration;
                    }
                }
            } else {
                Type receiver = typeOf(ctx.namespace);
                java.lang.reflect.Field field = assignedJavaField(receiver, name, ctx);
                if (field != null) {
                    declared = fromJava(field.getType());
                    current = declared;
                } else {
                    boolean own = ctx.namespace instanceof BabbleParser.ThisExpressionContext;
                    declared = assignedMemberType(receiver, name, own, ctx);
                    if (declared != null) {
                        current = declared;
                    } else if (strict) {
                        TypeNode.Member member = assignedBabbleMember(receiver, name, own);
                        if (member != null) {
                            current = memberType(member);
                            if (member.function == null && member.declaration instanceof BabbleParser.DefExpressionContext def
                                    && isLocal(def) && inFile(def)) {
                                inferredTarget = def;
                                memberTarget = true;
                            }
                        }
                    }
                }
            }
            Type value = typeOf(ctx.value);
            Type result = binaryOp(arithmeticToken(operator), operator, ctx, org.babblelang.engine.impl.Interpreter.targetText(ctx), ctx, current,
                    ctx.value.getText(), ctx.value, value);
            if (declared != null) {
                expect(result, declared, ctx);
                if (strict && declared == Type.DOUBLE && result == Type.INT) {
                    widened.add(ctx);
                }
            } else if (strict && inferredTarget != null) {
                if (memberTarget) {
                    recordInferredMember(inferredTarget, result, ctx);
                    if (inferredMembers.get(inferredTarget) == Type.DOUBLE && result == Type.INT) {
                        widened.add(ctx);
                    }
                } else {
                    recordInferredDef(inferredTarget, result, ctx);
                    if (inferredDefs.get(inferredTarget) == Type.DOUBLE && result == Type.INT) {
                        widened.add(ctx);
                    }
                }
            }
            return result;
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
            BabbleParser.FunctionLiteralContext function = enclosingFunction(ctx);
            if (function == null) {
                return Type.DYNAMIC;
            }
            if (inferringResult.contains(function) || provisionalResults.containsKey(function)) {
                return recursiveSignature(function);
            }
            return signature(function);
        }

        @Override
        public Type visitInteger(BabbleParser.IntegerContext ctx) {
            try {
                Integer.parseInt(ctx.getText());
                return Type.INT;
            } catch (NumberFormatException e) {
                if (strict) {
                    warn("Integer out of range : " + ctx.getText(), ctx);
                    return Type.INT;
                }
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
            BabbleParser.FunctionLiteralContext function = enclosingFunction(ctx);
            Type expected = function != null ? expectedReturnType(function) : null;
            Type value = ctx.expression() != null ? typeOf(ctx.expression(), expected) : Type.NULL;
            if (function != null && function.type() != null) {
                expect(value, annotation(function.type(), scopeOf(function)), ctx.expression());
            }
            return Type.NOTHING;
        }
    }

    // ---------------------------------------------------------------- members

    /** {@code receiver.name}, read. */
    private Type member(Type receiver, String name, boolean own, ParserRuleContext at, BabbleParser.ExpressionContext receiverExpression) {
        return member(receiver, name, own, at, receiverExpression.getText());
    }

    /** {@code receiver.name}, read, given the receiver's source text. */
    private Type member(Type receiver, String name, boolean own, ParserRuleContext at, String receiverText) {
        if (receiver == Type.DYNAMIC || receiver == Type.NOTHING) {
            return Type.DYNAMIC;
        }
        if (receiver instanceof Type.Instance instance) {
            TypeNode.Member member = findMember(instance.node(), name);
            if (member != null) {
                if (member.isPrivate() && !own) {
                    if (strict) {
                        warn(name + " is private", at);
                    }
                    return Type.DYNAMIC;
                }
                return memberType(member);
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
                if (member.isPrivate() && !own) {
                    if (strict) {
                        warn(name + " is private", at);
                    }
                    return Type.DYNAMIC;
                }
                return memberType(member);
            }
            warn("No such member : " + name, at);
            return Type.DYNAMIC;
        }
        if (receiver instanceof Type.Package pkg) {
            Type found = packageMember(pkg, name);
            if (found != null) {
                return found;
            }
            if (strict) {
                warn("No such member : " + name, at);
            }
            return Type.DYNAMIC;
        }
        if (receiver instanceof Type.Module module) {
            if (strict && module.module().checker() == null && module.module().exports().contains(name)) {
                warn("Missing type : " + name + " (import cycle with " + module.module().name() + ")", at);
                return Type.DYNAMIC;
            }
            // A missing export is TypeChecks' to report.
            Type export = exportType(module.module(), name);
            return export != null ? export : Type.DYNAMIC;
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
            return javaMember(value.type(), name, true, at);
        }
        if (receiver instanceof Type.JavaInstance instance) {
            return javaMember(instance.type(), name, false, at);
        }
        if (receiver instanceof Type.JavaArray array) {
            if ("length".equals(name)) {
                return Type.INT;
            }
            return javaMember(array.javaClass(), name, false, at);
        }
        // Numbers, booleans, strings, null, functions, any : nothing has members but objects,
        // classes, packages and Java values.
        warn("Not a scope : " + receiverText, at);
        return Type.DYNAMIC;
    }

    /** What {@code receiver.name = value} must assign : the member's declared or closed type, or null if inferred locally. */
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
        } else if (receiver instanceof Type.Package) {
            // Bound to fail : a package changes only through its own code.
            warn("Cannot assign to a package member : " + name, at);
        } else if (strict ? !(receiver instanceof Type.Module || receiver instanceof Type.JavaPackage
                || receiver instanceof Type.JavaClassValue || receiver instanceof Type.JavaInstance
                || receiver instanceof Type.JavaArray || isUnknown(receiver)) : receiver == Type.ANY) {
            String receiverText = at instanceof BabbleParser.AssignExpressionContext assign
                    ? assign.namespace.getText() : name;
            warn("Not a scope : " + receiverText, at);
        }
        if (member == null) {
            return null;
        }
        if (member.isPrivate() && !own) {
            if (strict) {
                warn(name + " is private", at);
            }
            return null;
        }
        if (strict) {
            if (!isLocal(member.declaration) || (!inFile(member.declaration) && pass.session != null)) {
                Type closed = memberType(member);
                return closed == Type.DYNAMIC ? null : closed;
            }
            if (member.declaration instanceof BabbleParser.ParameterDeclarationContext) {
                Type type = typeOfDeclaration(member.declaration);
                return type == Type.DYNAMIC ? null : type;
            }
            if (member.function != null) {
                return memberType(member);
            }
        }
        return declaredType(member.declaration);
    }

    private static TypeNode.Member assignedBabbleMember(Type receiver, String name, boolean own) {
        TypeNode.Member member = null;
        if (receiver instanceof Type.Instance instance) {
            member = findMember(instance.node(), name);
        } else if (receiver instanceof Type.ClassValue value) {
            member = value.node().statics.get(name);
        }
        return member != null && (!member.isPrivate() || own) ? member : null;
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
                return strict ? new Type.JavaMethod((Class<?>) parent, name) : Type.DYNAMIC;
            }
        }
        return null;
    }

    /** A member's annotation, method signature, or (in strict mode) inferred type. */
    private Type memberType(TypeNode.Member member) {
        if (!isLocal(member.declaration)) {
            Type type = ask(member.declaration, owner -> owner.memberType(member));
            return type != null ? type : Type.DYNAMIC;
        }
        if (strict && !inFile(member.declaration) && pass.session != null) {
            Type frozen = pass.session.memberTypes.get(member.declaration);
            if (frozen != null) {
                return frozen;
            }
        }
        Type declared = declaredType(member.declaration);
        if (declared != null) {
            return declared;
        }
        if (strict && member.declaration instanceof BabbleParser.ParameterDeclarationContext) {
            return typeOfDeclaration(member.declaration);
        }
        if (member.function != null) {
            return typeOf(member.function);
        }
        if (strict && member.declaration instanceof BabbleParser.DefExpressionContext def) {
            Type inferred = inferredMembers.get(def);
            if (inferred != null) {
                return inferred;
            }
            if (!inProgress.add(def)) {
                warn("Missing type : " + def.name.getText() + " (recursive)", def);
                failedDefs.add(def);
                return Type.DYNAMIC;
            }
            try {
                if (def.value == null) {
                    return Type.NULL;
                }
                Type value = typeOf(def.value);
                return value == Type.NOTHING ? Type.DYNAMIC : value;
            } finally {
                inProgress.remove(def);
            }
        }
        return Type.DYNAMIC;
    }

    /**
     * The type of a module's export : as its own file's checker types it. Null when the
     * module doesn't export the name ; dynamic when its file can't be typed (a cycle).
     */
    private static Type exportType(ModuleInterface module, String name) {
        if (!module.exports().contains(name)) {
            return null;
        }
        TypeChecker checker = module.checker();
        if (checker == null) {
            return Type.DYNAMIC;
        }
        synchronized (checker) {
            return checker.typeOfExport(name);
        }
    }

    private Type typeOfExport(String name) {
        ParserRuleContext definition = pass.fileScope.names.get(name);
        if (definition != null) {
            return typeOfDeclaration(definition);
        }
        List<BabbleParser.PackageExpressionContext> packages = pass.fileScope.packages.get(name);
        return packages != null ? new Type.Package(name, packages) : Type.DYNAMIC;
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
        return nested.isEmpty() ? null : new Type.Package(name, nested);
    }

    /** As {@code JavaPackage} finds them : a class with that exact name, else a package. */
    private static Class<?> loadTopLevel(String name) {
        try {
            return Class.forName(name, false, TypeChecker.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /**
     * A method or a public field of a Java class, as {@code JavaClass} looks them up : through
     * the class itself, only a static field.
     */
    private Type javaMember(Class<?> java, String name, boolean onClass, ParserRuleContext at) {
        for (Method method : java.getMethods()) {
            if (method.getName().equals(name)) {
                return new Type.JavaMethod(java, name);
            }
        }
        if (strict && java.isInterface() && !onClass) {
            for (Method method : Object.class.getMethods()) {
                if (method.getName().equals(name)) {
                    return new Type.JavaMethod(Object.class, name);
                }
            }
        }
        java.lang.reflect.Field field = javaField(java, name);
        if (field != null) {
            if (onClass && !Modifier.isStatic(field.getModifiers())) {
                warn("Not a static field : " + java.getCanonicalName() + "." + name, at);
                return Type.DYNAMIC;
            }
            return javaResult(field.getType());
        }
        warn("No such name in " + java.getCanonicalName() + " : " + name, at);
        return Type.DYNAMIC;
    }

    /** A public field Babble can reach, static or not, or null. */
    private static java.lang.reflect.Field javaField(Class<?> java, String name) {
        try {
            java.lang.reflect.Field field = java.getField(name);
            return JavaTypes.isAccessible(field.getDeclaringClass()) ? field : null;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * The field {@code receiver.name = value} assigns, when the receiver is a Java class or
     * object, reporting what {@code JavaClass.assign} would refuse. Null when it isn't Java,
     * or the field can't be assigned.
     */
    private java.lang.reflect.Field assignedJavaField(Type receiver, String name, ParserRuleContext at) {
        if (receiver instanceof Type.JavaArray array) {
            String description = array.javaClass().getCanonicalName() + "." + name;
            if ("length".equals(name)) {
                warn("Cannot assign final field : " + description, at);
            } else {
                warn("No such field in " + array.javaClass().getCanonicalName() + " : " + name, at);
            }
            return null;
        }
        boolean onClass = receiver instanceof Type.JavaClassValue;
        Class<?> java = onClass ? ((Type.JavaClassValue) receiver).type()
                : receiver instanceof Type.JavaInstance instance ? instance.type() : null;
        if (java == null) {
            return null;
        }
        java.lang.reflect.Field field = javaField(java, name);
        String description = java.getCanonicalName() + "." + name;
        if (field == null) {
            warn("No such field in " + java.getCanonicalName() + " : " + name, at);
        } else if (onClass && !Modifier.isStatic(field.getModifiers())) {
            warn("Not a static field : " + description, at);
        } else if (Modifier.isFinal(field.getModifiers())) {
            warn("Cannot assign final field : " + description, at);
        } else {
            return field;
        }
        return null;
    }

    // ---------------------------------------------------------------- calls

    private Type call(Type callee, BabbleParser.CallContext ctx) {
        List<BabbleParser.CallParameterContext> arguments = ctx.callParameters().callParameter();
        if (callee == Type.DYNAMIC || callee == Type.NOTHING) {
            return Type.DYNAMIC;
        }
        if (callee == Type.PRINT) {
            Type appendable = new Type.JavaInstance(Appendable.class);
            for (BabbleParser.CallParameterContext argument : arguments) {
                if (argument.ID() != null && "to".equals(argument.ID().getText())) {
                    expect(typeOf(argument.expression(), appendable), appendable, argument.expression());
                } else {
                    typeOf(argument.expression(), Type.ANY);
                }
            }
            return Type.NULL;
        }
        if (callee instanceof Type.Function function) {
            bind(function.parameters(), arguments, ctx);
            return function.result();
        }
        if (callee instanceof Type.ClassValue value && !value.node().isInterface()) {
            TypeNode node = value.node();
            if (node.ctx instanceof BabbleParser.ClassExpressionContext cls) {
                List<Type.Parameter> parameters = constructorParameters(cls);
                if (parameters != null) {
                    bind(parameters, arguments, ctx);
                }
            }
            return new Type.Instance(node);
        }
        if (callee instanceof Type.JavaClassValue value) {
            Class<?> java = value.type();
            if (java.isInterface() && arguments.size() == 1 && arguments.get(0).ID() == null
                    && JavaTypes.isFunctionalInterface(java)) {
                Type expectedSam = new Type.JavaInstance(java);
                if (isFunction(typeOf(arguments.get(0).expression(), expectedSam))) {
                    return expectedSam;
                }
            }
            javaCall(Arrays.asList(java.getConstructors()), java.getCanonicalName(), arguments, ctx);
            return new Type.JavaInstance(java);
        }
        if (callee instanceof Type.JavaMethod method) {
            List<Method> candidates = JavaTypes.methods(method.owner(), method.name());
            List<Method> applicable = javaCall(candidates, method.owner().getCanonicalName() + "." + method.name(), arguments, ctx);
            Set<Class<?>> results = applicable.stream().map(Method::getReturnType).collect(Collectors.toSet());
            if (results.size() == 1) {
                return javaResult(results.iterator().next());
            }
            // With every argument's type known, the runtime picks the most specific overload.
            boolean known = strict
                    ? arguments.stream().noneMatch(a -> isUnknown(typeOf(a.expression())))
                    : arguments.stream().noneMatch(a -> isUnknown(typeOf(a.expression())) || typeOf(a.expression()) == Type.NULL);
            Method best = known ? mostSpecific(applicable) : null;
            return best != null ? javaResult(best.getReturnType()) : Type.DYNAMIC;
        }
        warn(ctx.expression().getText() + " is not callable", ctx);
        return Type.DYNAMIC;
    }

    /** A class's constructor parameters, or null when they are another file's that can't be typed. */
    private List<Type.Parameter> constructorParameters(BabbleParser.ClassExpressionContext cls) {
        if (!isLocal(cls)) {
            return ask(cls, owner -> owner.constructorParameters(cls));
        }
        if (cls.parametersDeclaration() == null) {
            return List.of();
        }
        ScopeNode scope = scopeOf(cls);
        if (!strict) {
            return parameters(cls.parametersDeclaration(), scope);
        }
        List<Type.Parameter> result = new ArrayList<>();
        for (BabbleParser.ParameterDeclarationContext parameter : cls.parametersDeclaration().parameterDeclaration()) {
            Type type = parameterType(parameter, scope);
            if (parameter.defaultValue != null && !types.containsKey(parameter.defaultValue)) {
                Type actual = typeOf(parameter.defaultValue, type != Type.DYNAMIC ? type : null);
                if (type != Type.DYNAMIC) {
                    expect(actual, type, parameter.defaultValue);
                }
            }
            result.add(new Type.Parameter(parameter.ID().getText(), type, parameter.defaultValue != null));
        }
        return result;
    }

    private Type parameterType(BabbleParser.ParameterDeclarationContext parameter, ScopeNode scope) {
        if (parameter.type() != null) {
            return annotation(parameter.type(), scope);
        }
        if (strict && !inFile(parameter) && pass.session != null) {
            Type frozen = pass.session.declarationTypes.get(parameter);
            if (frozen != null) {
                return frozen;
            }
        }
        if (declarationTypes.containsKey(parameter)) {
            return declarationTypes.get(parameter);
        }
        if (strict && parameter.defaultValue != null) {
            Type defType = typeOf(parameter.defaultValue);
            if (defType != Type.NULL && defType != Type.NOTHING && defType != Type.DYNAMIC) {
                declarationTypes.put(parameter, defType);
                return defType;
            }
        }
        return Type.DYNAMIC;
    }

    private static boolean isFunction(Type type) {
        return type instanceof Type.Function || type instanceof Type.JavaMethod
                || type instanceof Type.ClassValue value && !value.node().isInterface()
                || type == Type.PRINT || type == Type.DYNAMIC;
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
            expect(typeOf(argument, parameter.type()), parameter.type(), argument);
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
        List<E> matching = new ArrayList<>();
        for (E candidate : candidates) {
            if (candidate.getParameterCount() == arguments.size()) {
                matching.add(candidate);
            }
        }
        List<Type> argumentTypes = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            BabbleParser.ExpressionContext argument = arguments.get(i).expression();
            Type expected = expectedJavaArgumentType(matching, i, argument);
            Type actual = typeOf(argument, expected);
            argumentTypes.add(actual);
            final int index = i;
            matching.removeIf(candidate -> !applicable(candidate.getParameterTypes()[index], actual));
        }
        if (matching.isEmpty() && !candidates.isEmpty()) {
            warn("No applicable overload of " + description + " for "
                    + argumentTypes.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")")), ctx);
        } else if (strict && matching.size() > 1
                && argumentTypes.stream().noneMatch(TypeChecker::isUnknown)
                && mostSpecific(matching) == null) {
            warn("Ambiguous call to " + description + " for "
                    + argumentTypes.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")")), ctx);
        }
        return matching;
    }

    private <E extends Executable> Type expectedJavaArgumentType(
            List<E> candidates, int index, BabbleParser.ExpressionContext argument) {
        if (candidates.isEmpty()) {
            return null;
        }
        Set<Class<?>> paramTypes = new LinkedHashSet<>();
        for (E candidate : candidates) {
            paramTypes.add(candidate.getParameterTypes()[index]);
        }
        if (paramTypes.size() == 1) {
            return fromJava(paramTypes.iterator().next());
        }
        if (unwrapFunctionLiteral(argument) != null) {
            Class<?> sam = null;
            for (Class<?> candidateType : paramTypes) {
                if (JavaTypes.isFunctionalInterface(candidateType)) {
                    if (sam != null && sam != candidateType) {
                        return null;
                    }
                    sam = candidateType;
                }
            }
            if (sam != null) {
                return new Type.JavaInstance(sam);
            }
        }
        return null;
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
        if (type instanceof Type.JavaArray array) {
            return array.javaClass();
        }
        return type instanceof Type.JavaInstance instance ? instance.type() : null;
    }

    // ---------------------------------------------------------------- operators

    private Type binaryOp(BabbleParser.BinaryOpContext ctx, Type left, Type right) {
        return binaryOp(ctx.op.getType(), ctx.op.getText(), ctx, ctx.left.getText(), ctx.left, left, ctx.right.getText(), ctx.right, right);
    }

    /**
     * {@code left op right}, given the operands' source texts and where to report each : the
     * operands of a binary expression, or the target and the value of a compound assignment.
     */
    private Type binaryOp(int op, String operator, ParserRuleContext ctx, String leftText, ParserRuleContext leftAt, Type left,
                          String rightText, ParserRuleContext rightAt, Type right) {
        boolean seeding = !inferenceStack.isEmpty() && recursiveFunctions.contains(inferenceStack.peek());
        String method = Operators.method(operator);
        if (method != null && left instanceof Type.Instance) {
            return operatorCall(left, method, rightAt, right, ctx, leftText);
        }
        switch (op) {
            case BabbleLexer.PLUS:
                if (left == Type.STRING) {
                    return Type.STRING;
                }
                if (isUnknown(left) && !(seeding && left == Type.NOTHING && isNumber(right))) {
                    return Type.DYNAMIC;
                }
                // Fall through : anything but a string on the left adds numbers.
            case BabbleLexer.MINUS:
            case BabbleLexer.MUL:
            case BabbleLexer.DIV:
                if (left == Type.DYNAMIC) {
                    // Maybe an instance with the operator's method : the right operand is its argument.
                    return Type.DYNAMIC;
                }
                boolean numbers = number(left, leftText, leftAt) & number(right, rightText, rightAt);
                if (op == BabbleLexer.DIV) {
                    return Type.DOUBLE;
                }
                if (seeding && numbers) {
                    if (left == Type.NOTHING && isNumber(right)) {
                        return right;
                    }
                    if (right == Type.NOTHING && isNumber(left)) {
                        return left;
                    }
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
                    warn("Not comparable : " + leftText + " and " + rightText, ctx);
                }
                return Type.BOOL;
        }
    }

    private static final Type.Function INT_CONVERSION = new Type.Function(List.of(new Type.Parameter("value", Type.DYNAMIC, false)), Type.INT);
    private static final Type.Function DOUBLE_CONVERSION = new Type.Function(List.of(new Type.Parameter("value", Type.DYNAMIC, false)), Type.DOUBLE);
    private static final Type.Function STRICT_INT_CONVERSION = new Type.Function(List.of(new Type.Parameter("value", Type.ANY, false)), Type.INT);
    private static final Type.Function STRICT_DOUBLE_CONVERSION = new Type.Function(List.of(new Type.Parameter("value", Type.ANY, false)), Type.DOUBLE);
    private static final Type.Function ASSERT_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("c", Type.BOOL, false), new Type.Parameter("message", Type.ANY, true)), Type.BOOL);
    private static final Type.Function SAME_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("a", Type.ANY, false), new Type.Parameter("b", Type.ANY, false)), Type.BOOL);
    private static final Type.Function CLASSOF_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("v", Type.ANY, false)), Type.ANY);
    private static final Type.Function IMPORT_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("name", Type.STRING, false)), Type.ANY);
    private static final Type STDOUT_TYPE = new Type.JavaInstance(java.io.PrintWriter.class);
    private static final Type ARGS_TYPE = new Type.JavaInstance(java.util.List.class);

    /**
     * A name the analysis doesn't declare : a root Java package, a number conversion
     * ({@code int(x)}, {@code double(x)}), or (in strict mode) implicit signatures and {@code any} bindings.
     */
    private Type implicitType(String name) {
        if (!strict) {
            return switch (name) {
                case "java", "javax" -> new Type.JavaPackage(name);
                case "int" -> INT_CONVERSION;
                case "double" -> DOUBLE_CONVERSION;
                default -> Type.DYNAMIC;
            };
        }
        return switch (name) {
            case "java", "javax" -> new Type.JavaPackage(name);
            case "int" -> STRICT_INT_CONVERSION;
            case "double" -> STRICT_DOUBLE_CONVERSION;
            case "print", "println", "affiche", "afficherc" -> Type.PRINT;
            case "assert", "suppose" -> ASSERT_FUNCTION;
            case "same" -> SAME_FUNCTION;
            case "classof" -> CLASSOF_FUNCTION;
            case "import", "importe" -> IMPORT_FUNCTION;
            case "STDOUT" -> STDOUT_TYPE;
            case "args" -> ARGS_TYPE;
            default -> Type.ANY;
        };
    }

    /** The lexer's token for an arithmetic operator. */
    private static int arithmeticToken(String operator) {
        return switch (operator) {
            case "+" -> BabbleLexer.PLUS;
            case "-" -> BabbleLexer.MINUS;
            case "*" -> BabbleLexer.MUL;
            case "/" -> BabbleLexer.DIV;
            default -> throw new IllegalArgumentException(operator);
        };
    }

    private static boolean isNumber(Type type) {
        return type == Type.INT || type == Type.DOUBLE;
    }

    private static boolean isUnknown(Type type) {
        return type == Type.DYNAMIC || type == Type.NOTHING;
    }

    /** Whether an operand can be a number, reporting it when it can't. */
    private boolean number(Type type, BabbleParser.ExpressionContext operand) {
        return number(type, operand.getText(), operand);
    }

    private static boolean isNumeric(Type type) {
        return type == Type.INT || type == Type.DOUBLE
                || type instanceof Type.JavaInstance instance && Number.class.isAssignableFrom(instance.type());
    }

    private boolean number(Type type, String text, ParserRuleContext at) {
        if (isUnknown(type) || isNumeric(type)) {
            return true;
        }
        warn("Not a number : " + text, at);
        return false;
    }

    /** {@code Operators.compare} : two numbers, or a Comparable and a value of its class. */
    private static boolean comparable(Type left, Type right) {
        if (isUnknown(left) || isUnknown(right)) {
            return true;
        }
        if (isNumeric(left) && isNumeric(right)) {
            return true;
        }
        Class<?> leftClass = javaClassOf(left);
        Class<?> rightClass = javaClassOf(right);
        return leftClass != null && rightClass != null && Comparable.class.isAssignableFrom(leftClass) && leftClass.isAssignableFrom(rightClass);
    }

    /**
     * {@code a + b}, or {@code -a}, on an instance of a class or interface : typed as the call
     * of its operator method, {@code a.plus(b)} ({@code argument} null for {@code a.neg()}).
     */
    private Type operatorCall(Type receiver, String method, ParserRuleContext argument, Type argumentType,
                              ParserRuleContext at, String receiverText) {
        Type callee = member(receiver, method, false, at, receiverText);
        if (!(callee instanceof Type.Function function)) {
            if (!isUnknown(callee) && !isFunction(callee)) {
                warn(receiverText + "." + method + " is not callable", at);
            }
            return Type.DYNAMIC;
        }
        List<Type.Parameter> parameters = function.parameters();
        int given = argument == null ? 0 : 1;
        if (given < parameters.size() && !parameters.get(given).optional()) {
            warn("Missing parameter : " + parameters.get(given).name(), at);
        } else if (given > parameters.size()) {
            warn("Too many parameters : " + parameters.size() + " declared, " + given + " given", at);
        } else if (argument != null) {
            expect(argumentType, parameters.get(0).type(), argument);
        }
        return function.result();
    }

    // ---------------------------------------------------------------- assignability

    /** Whether a value of type {@code from} can be used where {@code to} is declared. */
    static boolean assignable(Type from, Type to) {
        if (from.equals(to) || from == Type.DYNAMIC || to == Type.DYNAMIC || to == Type.ANY || from == Type.NOTHING) {
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
        if (to instanceof Type.JavaArray target) {
            return from instanceof Type.JavaArray array && target.javaClass().isAssignableFrom(array.javaClass());
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

    /** Whether {@code from as to} can succeed at runtime (static impossibility check for {@code as}). */
    private static boolean canCast(Type from, Type to) {
        if (from == Type.DYNAMIC || from == Type.NOTHING || to == Type.DYNAMIC || from == Type.ANY || to == Type.ANY) {
            return true;
        }
        if (from == Type.NULL) {
            return assignable(Type.NULL, to);
        }
        if (from == Type.INT && to == Type.DOUBLE) {
            return true;
        }
        if (from == Type.DOUBLE && to == Type.INT) {
            return false;
        }
        if (assignable(from, to) || assignable(to, from)) {
            return true;
        }
        if (from instanceof Type.Instance f && to instanceof Type.Instance t) {
            return f.node().isInterface() && t.node().isInterface();
        }
        if (from instanceof Type.Instance f && to instanceof Type.JavaInstance t) {
            return f.node().isInterface() && t.type().isInterface();
        }
        if (from instanceof Type.JavaInstance f && to instanceof Type.Instance t) {
            return f.type().isInterface() && t.node().isInterface();
        }
        Class<?> a = javaClassOf(from);
        Class<?> b = javaClassOf(to);
        if (a != null && b != null) {
            return (a.isInterface() && b.isInterface())
                    || (a.isInterface() && !Modifier.isFinal(b.getModifiers()))
                    || (b.isInterface() && !Modifier.isFinal(a.getModifiers()));
        }
        return false;
    }

    /** Whether {@code from as to} always holds with no conversion (redundant cast warning). */
    private static boolean alwaysIs(Type from, Type to) {
        return from != Type.DYNAMIC && from != Type.NOTHING && from != Type.NULL
                && to != Type.DYNAMIC && !(from == Type.INT && to == Type.DOUBLE)
                && assignable(from, to);
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
