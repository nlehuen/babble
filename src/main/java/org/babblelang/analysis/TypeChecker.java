package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Operators;

import java.lang.reflect.Executable;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The type checker (docs/design/static-typing.md) : the fourth pass of the analysis, after
 * {@link Resolver}. It types every expression of the file and reports, as errors, what
 * contradicts the annotations, leaves a declaration without a static type, or is bound to
 * fail at runtime : a value of the wrong type where a type is declared, calls that don't
 * match a known signature, members that don't exist, operators applied to values they reject.
 * <p>
 * Typing is static : an unannotated def takes the type of its value and widens along
 * reassignments via {@code join}, unannotated function literals infer their result from
 * their body and receive parameter types from context where an expected function or
 * functional interface type is known, and {@code is}, {@code == null}, {@code != null},
 * and truthiness checks narrow lexical bindings along control-flow branches (T8). Use
 * {@code any} or {@code any?} with {@code as} casts where heterogeneity is intentional.
 * Declared types are also recorded so the backends check them at runtime where {@code any}
 * or untyped script bindings cross into typed code (T9).
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

    private record NarrowScope(
            Map<ParserRuleContext, Type> types,
            Set<ParserRuleContext> killed,
            boolean maskReassigned) {
    }

    private record Widening(Type type, int line) {
    }

    private static final Comparator<Diagnostic> ORDER = Comparator.comparingInt(Diagnostic::line)
            .thenComparingInt(Diagnostic::column).thenComparing(Diagnostic::message);

    private final BlockScopes pass;
    private final Map<ParserRuleContext, ScopeNode> occurrenceScopes = new HashMap<>();
    private final Set<ParserRuleContext> reassigned = new HashSet<>();
    private final Set<ParserRuleContext> capturedReassigned = new HashSet<>();
    private final Map<ParserRuleContext, Type> types = new HashMap<>();
    private final Map<ParserRuleContext, Type> declarationTypes = new HashMap<>();
    private final Map<BabbleParser.TypeContext, Type> annotations = new HashMap<>();
    private final Set<ParserRuleContext> inProgress = new HashSet<>();
    private final Set<BabbleParser.FunctionLiteralContext> functions = new LinkedHashSet<>();
    private final Set<Diagnostic> diagnostics = new TreeSet<>(ORDER);
    private final Set<Diagnostic> warnings = new TreeSet<>(ORDER);
    private final Set<ParserRuleContext> widened = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ParserRuleContext, String> callChecks = new IdentityHashMap<>();
    private final Map<BabbleParser.DefExpressionContext, List<Type.TypeVar>> defTypeVars = new IdentityHashMap<>();
    private final Typer typer = new Typer();
    private final Map<ParserRuleContext, Type> expectedTypes = new IdentityHashMap<>();
    private final Map<BabbleParser.FunctionLiteralContext, Type> contextualReturnTypes = new IdentityHashMap<>();
    private final Map<BabbleParser.FunctionLiteralContext, List<BabbleParser.ReturnExpressionContext>> functionReturns = new IdentityHashMap<>();
    private final Deque<BabbleParser.FunctionLiteralContext> inferenceStack = new ArrayDeque<>();
    private final Set<BabbleParser.FunctionLiteralContext> inferringResult = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BabbleParser.FunctionLiteralContext> recursiveFunctions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<BabbleParser.FunctionLiteralContext, Type> provisionalResults = new IdentityHashMap<>();
    private final Deque<NarrowScope> narrowings = new ArrayDeque<>();
    private final Map<ParserRuleContext, Type> inferredDefs = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> nextInferredDefs = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> inferredMembers = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> nextInferredMembers = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> inferredParams = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> nextInferredParams = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Widening> defWidenings = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Widening> memberWidenings = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Widening> paramWidenings = new IdentityHashMap<>();
    private final Set<ParserRuleContext> escapingTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> nextEscapingTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> p7Params = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Type.Function, BabbleParser.FunctionLiteralContext> functionLiterals = new IdentityHashMap<>();
    private final Set<TypeNode> exportedTypeNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> failedDefs = Collections.newSetFromMap(new IdentityHashMap<>());
    /**
     * The checkers of the files this one imports statically, transitively, by file : what
     * this file uses from another is typed by that file's checker (docs/design/modules.md §8.2).
     */
    private final Map<ParserRuleContext, TypeChecker> owners = new HashMap<>();

    private BabbleParser.FileContext file;

    TypeChecker(BlockScopes pass) {
        this.pass = pass;
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
            capturedReassigned.addAll(pass.session.reassigned);
        }
        for (BlockScopes.Occurrence occurrence : pass.occurrences) {
            occurrenceScopes.put(occurrence.ctx(), occurrence.scope());
        }
        for (BlockScopes.PendingAssignment assignment : pass.assignments) {
            if (resolveName(null, assignment.name(), assignment.scope()) instanceof ParserRuleContext declaration) {
                reassigned.add(declaration);
                if (!Objects.equals(callableScope(assignment.scope()), callableScope(scopeOf(declaration)))) {
                    capturedReassigned.add(declaration);
                }
            }
        }
        for (TypeNode node : pass.types) {
            BabbleParser.DefExpressionContext def = owningDefExpr(node.ctx);
            if (isExported(def)) {
                exportedTypeNodes.add(node);
            }
        }
        new ReturnFinder().visit(file);
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
        checkInterfaces();
        checkMissingTypes(file);
        for (ParserRuleContext ctx : widened) {
            pass.analysis.widenToDouble(ctx);
        }
        for (Map.Entry<ParserRuleContext, String> entry : callChecks.entrySet()) {
            pass.analysis.setCheckedType(entry.getKey(), entry.getValue());
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
        callChecks.clear();
        expectedTypes.clear();
        contextualReturnTypes.clear();
        inferenceStack.clear();
        inferringResult.clear();
        recursiveFunctions.clear();
        provisionalResults.clear();
        narrowings.clear();
        nextInferredDefs.clear();
        nextInferredMembers.clear();
        nextInferredParams.clear();
        nextEscapingTargets.clear();
        p7Params.clear();
        functionLiterals.clear();
        failedDefs.clear();
    }

    private boolean commitInferred() {
        boolean escapedChanged = escapingTargets.addAll(nextEscapingTargets);
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
        Map<ParserRuleContext, Type> filteredParams = new IdentityHashMap<>();
        for (Map.Entry<ParserRuleContext, Type> entry : nextInferredParams.entrySet()) {
            if (entry.getValue() != Type.NULL && entry.getKey() instanceof BabbleParser.ParameterDeclarationContext param
                    && !escapingTargets.contains(ownerOfParam(param))) {
                filteredParams.put(entry.getKey(), entry.getValue());
            }
        }
        boolean changed = escapedChanged || !filteredDefs.equals(inferredDefs) || !filteredMembers.equals(inferredMembers)
                || !filteredParams.equals(inferredParams);
        if (changed) {
            inferredDefs.clear();
            inferredDefs.putAll(filteredDefs);
            inferredMembers.clear();
            inferredMembers.putAll(filteredMembers);
            inferredParams.clear();
            inferredParams.putAll(filteredParams);
        }
        return changed;
    }

    Map<ParserRuleContext, Type> frozenDeclarations() {
        Map<ParserRuleContext, Type> frozen = new HashMap<>();
        for (Map.Entry<ParserRuleContext, Type> entry : declarationTypes.entrySet()) {
            if (inFile(entry.getKey()) && !isUnresolved(entry.getValue()) && entry.getValue() != Type.NULL) {
                frozen.put(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<ParserRuleContext, Type> entry : inferredDefs.entrySet()) {
            if (inFile(entry.getKey()) && !isUnresolved(entry.getValue()) && entry.getValue() != Type.NULL) {
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
            if (!isUnresolved(type) && type != Type.NULL) {
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
        if (!message.startsWith("Missing type") && !message.startsWith("Unknown type")
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
                    Widening paramWidening = paramWidenings.get(decl);
                    if (paramWidening != null && paramWidening.line() > diagnosticLine) {
                        return " (" + name + " is " + paramWidening.type() + " because of the call at line " + paramWidening.line() + ")";
                    }
                    Widening widening = defWidenings.get(decl);
                    if (widening != null && widening.line() > diagnosticLine) {
                        return " (" + name + " is " + widening.type() + " because of the assignment at line " + widening.line() + ")";
                    }
                }
            } else {
                Type receiver = types.get(selector.expression());
                TypeNode.Member member = targetMember(receiver, name);
                if (member != null) {
                    Widening paramWidening = paramWidenings.get(member.declaration);
                    if (paramWidening != null && paramWidening.line() > diagnosticLine) {
                        return " (" + name + " is " + paramWidening.type() + " because of the call at line " + paramWidening.line() + ")";
                    }
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
        if (tree instanceof BabbleParser.CallContext call && types.get(call.expression()) instanceof Type.Function fn) {
            BabbleParser.FunctionLiteralContext lit = functionLiterals.get(fn);
            if (lit != null) {
                for (BabbleParser.ParameterDeclarationContext param : lit.parametersDeclaration().parameterDeclaration()) {
                    Widening paramWidening = paramWidenings.get(param);
                    if (paramWidening != null && paramWidening.line() > diagnosticLine) {
                        return " (" + param.ID().getText() + " is " + paramWidening.type()
                                + " because of the call at line " + paramWidening.line() + ")";
                    }
                }
            }
        }
        return null;
    }

    private String assignmentWideningNote(BabbleParser.AssignExpressionContext assign, int diagnosticLine) {
        String name = assign.name.getText();
        if (assign.namespace == null) {
            ScopeNode scope = occurrenceScopes.get(assign);
            if (scope != null && resolveName(assign, name, scope) instanceof ParserRuleContext decl) {
                Widening paramWidening = paramWidenings.get(decl);
                if (paramWidening != null && paramWidening.line() > diagnosticLine) {
                    return " (" + name + " is " + paramWidening.type() + " because of the call at line " + paramWidening.line() + ")";
                }
                Widening widening = defWidenings.get(decl);
                if (widening != null && widening.line() > diagnosticLine) {
                    return " (" + name + " is " + widening.type() + " because of the assignment at line " + widening.line() + ")";
                }
            }
        } else {
            Type receiver = types.get(assign.namespace);
            TypeNode.Member member = targetMember(receiver, name);
            if (member != null) {
                Widening paramWidening = paramWidenings.get(member.declaration);
                if (paramWidening != null && paramWidening.line() > diagnosticLine) {
                    return " (" + name + " is " + paramWidening.type() + " because of the call at line " + paramWidening.line() + ")";
                }
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
            // Only annotations are walked to : implements lists, super qualifiers, and as targets are handled elsewhere.
            if (type.getParent() instanceof BabbleParser.AsExpressionContext asCtx && asCtx.right == type) {
                return;
            }
            annotation(type, scopeOf(type));
            return;
        }
        if (tree instanceof BabbleParser.TypeListContext list) {
            for (BabbleParser.TypeContext parentType : list.type()) {
                annotation(parentType, scopeOf(parentType));
            }
            return;
        }
        if (tree instanceof BabbleParser.SuperExpressionContext sup) {
            if (sup.qualifier != null) {
                annotation(sup.qualifier, scopeOf(sup), true);
            }
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
            public Void visitType(BabbleParser.TypeContext ctx) {
                return null;
            }

            @Override
            public Void visitDefExpression(BabbleParser.DefExpressionContext def) {
                if (def.type() == null && !failedDefs.contains(def)) {
                    ScopeAnalysis.DefKind kind = pass.analysis.defTarget(def).kind();
                    Type inferred = (kind == ScopeAnalysis.DefKind.MEMBER || kind == ScopeAnalysis.DefKind.STATIC)
                            ? inferredMembers.get(def) : inferredDefs.get(def);
                    if (inferred == null || isUnresolved(inferred) || inferred == Type.NULL) {
                        warn("Missing type : " + def.name.getText(), def);
                    }
                }
                return super.visitDefExpression(def);
            }

            @Override
            public Void visitParameterDeclaration(BabbleParser.ParameterDeclarationContext parameter) {
                Type type = declarationTypes.get(parameter);
                if (parameter.type() == null && (type == null || isUnresolved(type)) && !failedDefs.contains(parameter)) {
                    warn("Missing type : " + parameter.ID().getText(), parameter);
                }
                return super.visitParameterDeclaration(parameter);
            }
        }.visit(file);
    }

    /** Checks each class, object, and extending interface against all ancestor Babble interface member types. */
    private void checkInterfaces() {
        for (TypeNode type : pass.types) {
            if (type.parents.isEmpty()) {
                continue;
            }
            List<Type.TypeVar> vars = typeVarsOf(type);
            Type.Instance selfInst = vars.isEmpty()
                    ? new Type.Instance(type)
                    : new Type.Instance(type, List.copyOf(vars));
            Map<String, TypeChecks.Provider> defaults = inheritedDefaults(type);
            Set<TypeNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object parent : type.parents) {
                if (parent instanceof TypeNode iface) {
                    checkInterfaceConformance(type, selfInst, iface, defaults, seen);
                }
            }
        }
    }

    private static Map<String, TypeChecks.Provider> inheritedDefaults(TypeNode type) {
        Map<String, TypeChecks.Provider> result = new LinkedHashMap<>();
        for (Object parent : type.parents) {
            if (parent instanceof TypeNode iface && iface.defaults != null) {
                for (TypeChecks.Provider provider : iface.defaults.values()) {
                    if (!type.members.containsKey(provider.name())) {
                        result.putIfAbsent(provider.name(), provider);
                    }
                }
            }
        }
        return result;
    }

    private void checkInterfaceConformance(TypeNode type, Type.Instance selfInst, TypeNode iface, Map<String, TypeChecks.Provider> defaults, Set<TypeNode> seen) {
        if (!seen.add(iface)) {
            return;
        }
        Type projectedIface = projectToBabble(selfInst, iface);
        Map<Type.TypeVar, Type> ifaceEnv = projectedIface instanceof Type.Instance proj ? instanceEnv(proj) : Map.of();
        for (TypeNode.Member req : iface.members.values()) {
            if (req.function == null && declaredType(req.declaration) == null) {
                continue;
            }
            Type required = substitute(memberType(req), ifaceEnv);
            if (isUnresolved(required)) {
                continue;
            }
            TypeNode.Member impl = type.members.get(req.name);
            if (impl != null) {
                if (impl.isPrivate()) {
                    continue;
                }
                Type actual = memberType(impl);
                if (!isUnresolved(actual) && !assignable(actual, required, false)) {
                    warn("Type error : expected " + required + ", got " + actual, impl.declaration);
                }
            } else {
                TypeChecks.Provider provider = defaults.get(req.name);
                if (provider != null && provider.owner() instanceof TypeNode owner
                        && (!type.isInterface() || owner != iface)) {
                    TypeNode.Member defaultMember = owner.members.get(req.name);
                    if (defaultMember != null) {
                        Type projectedOwner = projectToBabble(selfInst, owner);
                        Map<Type.TypeVar, Type> ownerEnv = projectedOwner instanceof Type.Instance proj ? instanceEnv(proj) : Map.of();
                        Type actual = substitute(memberType(defaultMember), ownerEnv);
                        if (!isUnresolved(actual) && !assignable(actual, required, false)) {
                            warn("Type error : expected " + required + ", got " + actual, type.ctx);
                        }
                    }
                }
            }
        }
        for (Object parent : iface.parents) {
            if (parent instanceof TypeNode parentIface) {
                checkInterfaceConformance(type, selfInst, parentIface, defaults, seen);
            }
        }
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
                    : declarationTypes.get(parameter);
            if (type != null && containsType(type, Type.DOUBLE) && !containsType(type, Type.INT)) {
                widened.add(parameter);
            }
            if (parameter.type() != null || (type != null && containsType(type, Type.DOUBLE) && !containsType(type, Type.INT))) {
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
            } else {
                Type inferred = (kind == ScopeAnalysis.DefKind.MEMBER || kind == ScopeAnalysis.DefKind.STATIC)
                        ? inferredMembers.get(def) : inferredDefs.get(def);
                if (inferred != null && containsType(inferred, Type.DOUBLE) && !containsType(inferred, Type.INT)) {
                    descriptor = descriptor(inferred);
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
            } else {
                Type inferred = typeOfDeclaration(declaration);
                if (inferred != null && containsType(inferred, Type.DOUBLE) && !containsType(inferred, Type.INT)) {
                    pass.analysis.setCheckedType(assign, descriptor(inferred));
                }
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
        if (type == Type.INT || type == Type.DOUBLE || type == Type.BOOL || type == Type.STRING
                || type == Type.NULL || type == Type.ANY) {
            return type + "|" + type + "|";
        }
        if (type == Type.NULLABLE_ANY || type instanceof Type.TypeVar) {
            return null;
        }
        if (type instanceof Type.Union union) {
            StringBuilder payload = new StringBuilder();
            for (Type member : union.members()) {
                String sub = descriptor(member);
                if (sub == null) {
                    return null;
                }
                if (!payload.isEmpty()) {
                    payload.append(',');
                }
                payload.append(org.babblelang.runtime.Types.encodeComponent(sub));
            }
            return "union|" + union + "|" + payload;
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

    private static ScopeNode callableScope(ScopeNode scope) {
        ScopeNode s = scope;
        while (s != null && s.kind == ScopeNode.Kind.BLOCK) {
            s = s.parent;
        }
        return s;
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

    /** The type of a def or parameter : its annotation or its inferred type (T8). */
    private Type typeOfDeclaration(ParserRuleContext declaration) {
        if (!isLocal(declaration)) {
            Type type = ask(declaration, owner -> owner.typeOfDeclaration(declaration));
            return type != null ? type : Type.ERROR;
        }
        if (!inFile(declaration) && pass.session != null) {
            Type frozen = pass.session.declarationTypes.get(declaration);
            if (frozen != null) {
                return frozen;
            }
        }
        if (declaration instanceof BabbleParser.DefExpressionContext def && def.type() != null) {
            Type declared = declaredType(def);
            declarationTypes.put(declaration, declared);
            return declared;
        }
        if (declaration instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.type() != null) {
            Type declared = annotation(parameter.type(), scopeOf(parameter));
            declarationTypes.put(declaration, declared);
            return declared;
        }
        if (declaration instanceof BabbleParser.DefExpressionContext def) {
            BabbleParser.FunctionLiteralContext literal = unwrapFunctionLiteral(def.value);
            if (literal != null && (inferringResult.contains(literal) || provisionalResults.containsKey(literal))) {
                return recursiveSignature(literal);
            }
        }
        Type cached = declarationTypes.get(declaration);
        if (cached != null) {
            return cached;
        }
        if (declaration instanceof BabbleParser.ParameterDeclarationContext parameter) {
            Type inferredParam = inferredParams.get(parameter);
            if (inferredParam != null && isInferrableCallable(ownerOfParam(parameter))) {
                declarationTypes.put(parameter, inferredParam);
                return inferredParam;
            }
        }
        if (inferredDefs.containsKey(declaration)
                && !(declaration instanceof BabbleParser.DefExpressionContext def
                && !reassigned.contains(def) && unwrapFunctionLiteral(def.value) != null)) {
            return inferredDefs.get(declaration);
        }
        if (!inProgress.add(declaration)) {
            if (declaration instanceof BabbleParser.DefExpressionContext def && def.type() == null) {
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
                warn("Missing type : " + def.name.getText() + " (recursive)", def);
                failedDefs.add(def);
            }
            return Type.ERROR;
        }
        try {
            Type type = Type.ERROR;
            if (declaration instanceof BabbleParser.DefExpressionContext def) {
                if (def.value != null) {
                    BabbleParser.FunctionLiteralContext literal = unwrapFunctionLiteral(def.value);
                    if (literal != null && (inferringResult.contains(literal) || provisionalResults.containsKey(literal))) {
                        return recursiveSignature(literal);
                    }
                    Type valueType = typeOf(def.value);
                    type = inferred(valueType);
                }
            } else if (declaration instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.defaultValue != null) {
                Type valueType = typeOf(parameter.defaultValue);
                if (valueType != Type.NULL && !isUnresolved(valueType)) {
                    type = valueType;
                }
            }
            if (type != Type.ERROR) {
                declarationTypes.put(declaration, type);
            }
            return type;
        } finally {
            inProgress.remove(declaration);
        }
    }

    /** An inferred result type : nothing cannot be stored as a value type. */
    private Type inferred(Type type) {
        return type == Type.NOTHING ? Type.ERROR : type;
    }

    /** The annotation a def or parameter declares, or null. */
    private Type declaredType(ParserRuleContext declaration) {
        if (!isLocal(declaration)) {
            return ask(declaration, owner -> owner.declaredType(declaration));
        }
        if (!inFile(declaration) && pass.session != null) {
            Type frozen = pass.session.declarationTypes.get(declaration);
            if (frozen != null) {
                return frozen;
            }
        }
        if (declaration instanceof BabbleParser.DefExpressionContext def && def.type() != null) {
            Type declared = annotation(def.type(), scopeOf(def));
            if (def.typeParameters() != null && declared instanceof Type.Function fn) {
                return new Type.Function(typeVarsOf(def), fn.parameters(), fn.result());
            }
            return declared;
        }
        if (declaration instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.type() != null) {
            return annotation(parameter.type(), scopeOf(parameter));
        }
        return null;
    }

    private List<Type.TypeVar> typeVarsOf(BabbleParser.DefExpressionContext def) {
        if (def == null || def.typeParameters() == null) {
            return List.of();
        }
        return defTypeVars.computeIfAbsent(def, d -> {
            List<org.antlr.v4.runtime.tree.TerminalNode> ids = d.typeParameters().ID();
            List<Type.TypeVar> vars = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                vars.add(new Type.TypeVar(d, ids.get(i).getText(), i));
            }
            return List.copyOf(vars);
        });
    }

    private List<Type.TypeVar> typeVarsOf(TypeNode node) {
        if (node == null || node.typeParameters.isEmpty()) {
            return List.of();
        }
        if (!isLocal(node.ctx)) {
            List<Type.TypeVar> remote = ask(node.ctx, owner -> owner.typeVarsOf(node));
            return remote != null ? remote : List.of();
        }
        if (node.ctx.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == node.ctx) {
            return typeVarsOf(def);
        }
        return List.of();
    }

    private Type.TypeVar resolveTypeVar(String name, ParserRuleContext at, ScopeNode scope) {
        if (at != null) {
            for (ParserRuleContext p = at; p != null; p = p.getParent()) {
                if (p.getParent() instanceof BabbleParser.DefExpressionContext def && def.type() == p && def.typeParameters() != null) {
                    for (Type.TypeVar tv : typeVarsOf(def)) {
                        if (tv.name().equals(name)) {
                            return tv;
                        }
                    }
                    break;
                }
            }
        }
        for (ScopeNode s = scope; s != null; s = s.parent) {
            if ((s.kind == ScopeNode.Kind.CLASS_BODY || s.kind == ScopeNode.Kind.INTERFACE_BODY) && s.type != null) {
                for (Type.TypeVar tv : typeVarsOf(s.type)) {
                    if (tv.name().equals(name)) {
                        return tv;
                    }
                }
            } else if (s.kind == ScopeNode.Kind.FUNCTION
                    && s.owner.getParent() instanceof BabbleParser.DefExpressionContext def
                    && def.value == s.owner && def.typeParameters() != null) {
                for (Type.TypeVar tv : typeVarsOf(def)) {
                    if (tv.name().equals(name)) {
                        return tv;
                    }
                }
            }
        }
        return null;
    }

    private Type parentAnnotation(TypeNode node, BabbleParser.TypeContext parentType) {
        if (node == null || parentType == null) {
            return Type.ERROR;
        }
        if (!isLocal(node.ctx)) {
            Type remote = ask(node.ctx, owner -> owner.parentAnnotation(node, parentType));
            return remote != null ? remote : Type.ERROR;
        }
        return annotation(parentType, scopeOf(parentType));
    }

    // ---------------------------------------------------------------- annotations

    private Type annotation(BabbleParser.TypeContext type, ScopeNode scope) {
        return annotation(type, scope, false);
    }

    private Type annotation(BabbleParser.TypeContext type, ScopeNode scope, boolean allowRaw) {
        if (!allowRaw) {
            Type cached = annotations.get(type);
            if (cached != null) {
                return cached;
            }
        }
        List<BabbleParser.NullableTypeContext> parts = type.nullableType();
        List<Type> members = new ArrayList<>(parts.size());
        for (BabbleParser.NullableTypeContext part : parts) {
            Type member = nullableAnnotation(part, scope, allowRaw);
            if (member == Type.ERROR) {
                if (!allowRaw) {
                    annotations.put(type, Type.ERROR);
                }
                return Type.ERROR;
            }
            members.add(member);
        }
        Type result = Type.union(members);
        if (!allowRaw) {
            annotations.put(type, result);
        }
        return result;
    }

    private Type nullableAnnotation(BabbleParser.NullableTypeContext nullable, ScopeNode scope, boolean allowRaw) {
        Type base = primaryAnnotation(nullable.primaryType(), scope, allowRaw);
        if (base == Type.ERROR) {
            return Type.ERROR;
        }
        return nullable.question != null ? Type.nullable(base) : base;
    }

    private Type primaryAnnotation(BabbleParser.PrimaryTypeContext primary, ScopeNode scope, boolean allowRaw) {
        if (primary instanceof BabbleParser.FunctionTypeContext function) {
            return new Type.Function(functionTypeParameters(function.parametersDeclaration(), scope),
                    function.type() != null ? annotation(function.type(), scope) : Type.NULLABLE_ANY);
        }
        if (primary instanceof BabbleParser.NullTypeContext) {
            return Type.NULL;
        }
        if (primary instanceof BabbleParser.ParenTypeContext paren) {
            return annotation(paren.type(), scope, allowRaw);
        }
        BabbleParser.SimpleTypeContext simple = (BabbleParser.SimpleTypeContext) primary;
        Type result = simpleType(simple, scope, allowRaw);
        if (result == null) {
            warn("Unknown type : " + simple.getText(), simple);
            return Type.ERROR;
        }
        return result;
    }

    private List<Type.Parameter> functionTypeParameters(BabbleParser.ParametersDeclarationContext declaration, ScopeNode scope) {
        List<Type.Parameter> result = new ArrayList<>();
        for (BabbleParser.ParameterDeclarationContext parameter : declaration.parameterDeclaration()) {
            Type type;
            if (parameter.type() != null) {
                type = annotation(parameter.type(), scope);
            } else {
                warn("Missing type : " + parameter.ID().getText(), parameter);
                type = Type.ERROR;
            }
            result.add(new Type.Parameter(parameter.ID().getText(), type, parameter.defaultValue != null));
        }
        return result;
    }

    /**
     * T1 : the built-in names, generic type parameters, a class or interface literal bound to
     * a def (possibly reached through packages or modules), or a Java class. Null when unknown.
     */
    private Type simpleType(BabbleParser.SimpleTypeContext type, ScopeNode scope, boolean allowRaw) {
        List<String> path = type.ID().stream().map(Object::toString).toList();
        if (path.size() == 1) {
            Type.TypeVar tv = resolveTypeVar(path.get(0), type, scope);
            if (tv != null) {
                if (type.typeArguments() != null) {
                    warn("Wrong number of type arguments : expected 0, got " + type.typeArguments().type().size(), type);
                    return Type.ERROR;
                }
                return tv;
            }
        }
        Object resolved = resolveName(null, path.get(0), scope);
        if (path.size() == 1 && resolved == null) {
            Type builtin = switch (path.get(0)) {
                case "int" -> Type.INT;
                case "double" -> Type.DOUBLE;
                case "bool" -> Type.BOOL;
                case "string" -> Type.STRING;
                case "any" -> Type.ANY;
                default -> null;
            };
            if (builtin == null) {
                return null;
            }
            if (type.typeArguments() != null) {
                warn("Wrong number of type arguments : expected 0, got " + type.typeArguments().type().size(), type);
                return Type.ERROR;
            }
            return builtin;
        }
        if (resolved instanceof BabbleParser.DefExpressionContext def) {
            if (path.size() == 1) {
                TypeNode node = classNode(def.value);
                if (node != null) {
                    return instantiateBabbleType(node, type, scope, allowRaw);
                }
            } else {
                String imported = importedPackage(def.value);
                if (imported != null) {
                    Class<?> java = loadClass(imported + "." + String.join(".", path.subList(1, path.size())));
                    return java != null ? instantiateJavaType(java, type, scope, allowRaw) : null;
                }
            }
            if (fromModule(def.value, scopeOf(def), 0)) {
                Type through = typeThroughModule(typeOfDeclaration(def), path, type);
                if (through == null || through == Type.ERROR) {
                    return through;
                }
                if (through instanceof Type.Instance inst) {
                    return instantiateBabbleType(inst.node(), type, scope, allowRaw);
                }
                return null;
            }
            return null;
        }
        if (resolved instanceof List<?> packages) {
            @SuppressWarnings("unchecked")
            List<BabbleParser.PackageExpressionContext> list = (List<BabbleParser.PackageExpressionContext>) packages;
            TypeNode node = nodeInPackages(list, path, 1);
            return node != null ? instantiateBabbleType(node, type, scope, allowRaw) : null;
        }
        if (resolved == null && (path.get(0).equals("java") || path.get(0).equals("javax"))) {
            Class<?> java = loadClass(String.join(".", path));
            return java != null ? instantiateJavaType(java, type, scope, allowRaw) : null;
        }
        return null;
    }

    private List<Type> resolveTypeArguments(BabbleParser.SimpleTypeContext type, ScopeNode scope) {
        if (type.typeArguments() == null) {
            return null;
        }
        List<Type> args = new ArrayList<>(type.typeArguments().type().size());
        for (BabbleParser.TypeContext arg : type.typeArguments().type()) {
            Type resolved = annotation(arg, scope, false);
            if (resolved == Type.ERROR) {
                return null;
            }
            args.add(resolved);
        }
        return args;
    }

    private Type instantiateBabbleType(TypeNode node, BabbleParser.SimpleTypeContext simple, ScopeNode scope, boolean allowRaw) {
        int expected = node.typeParameters.size();
        BabbleParser.TypeArgumentsContext argCtx = simple.typeArguments();
        if (argCtx == null) {
            if (expected == 0) {
                return new Type.Instance(node);
            }
            if (allowRaw) {
                return rawInstance(node);
            }
            warn("Missing type arguments : " + simple.getText(), simple);
            return Type.ERROR;
        }
        if (allowRaw) {
            warn("Cannot cast to parameterized type : " + simple.getText(), simple);
            return Type.ERROR;
        }
        List<Type> args = resolveTypeArguments(simple, scope);
        if (args == null) {
            return Type.ERROR;
        }
        if (args.size() != expected) {
            warn("Wrong number of type arguments : expected " + expected + ", got " + args.size(), simple);
            return Type.ERROR;
        }
        return new Type.Instance(node, args);
    }

    private Type instantiateJavaType(Class<?> java, BabbleParser.SimpleTypeContext simple, ScopeNode scope, boolean allowRaw) {
        TypeVariable<?>[] params = java.getTypeParameters();
        int expected = params.length;
        BabbleParser.TypeArgumentsContext argCtx = simple.typeArguments();
        if (argCtx == null) {
            if (expected == 0) {
                return fromJava(java);
            }
            if (allowRaw) {
                return rawJavaInstance(java);
            }
            warn("Missing type arguments : " + simple.getText(), simple);
            return Type.ERROR;
        }
        if (allowRaw) {
            warn("Cannot cast to parameterized type : " + simple.getText(), simple);
            return Type.ERROR;
        }
        List<Type> args = resolveTypeArguments(simple, scope);
        if (args == null) {
            return Type.ERROR;
        }
        if (args.size() != expected) {
            warn("Wrong number of type arguments : expected " + expected + ", got " + args.size(), simple);
            return Type.ERROR;
        }
        Map<TypeVariable<?>, Type> env = javaTypeEnv(java, args);
        for (int i = 0; i < expected; i++) {
            if (!satisfiesJavaBounds(args.get(i), params[i], env)) {
                warn("Type error : type argument " + args.get(i) + " does not satisfy bound " + params[i].getName()
                        + " of " + java.getName(), simple.typeArguments().type(i));
                return Type.ERROR;
            }
        }
        return new Type.JavaInstance(java, args);
    }

    private static Type.Instance rawInstance(TypeNode node) {
        if (node.typeParameters.isEmpty()) {
            return new Type.Instance(node);
        }
        return new Type.Instance(node, Collections.nCopies(node.typeParameters.size(), Type.NULLABLE_ANY));
    }

    private Type rawJavaInstance(Class<?> java) {
        TypeVariable<?>[] params = java.getTypeParameters();
        if (params.length == 0) {
            return fromJava(java);
        }
        return new Type.JavaInstance(java, Collections.nCopies(params.length, Type.NULLABLE_ANY));
    }

    /**
     * Whether a value is {@code import("file.ba")}, or selected from a def of such a value :
     * {@code def shapes = import("shapes.ba") ; def Circle = shapes.Circle}.
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
                    && fromModule(def.value, scopeOf(def), depth + 1);
        }
        return false;
    }

    /**
     * A type named through a module : {@code shapes.Circle}, {@code lib.geo.Point}, or
     * {@code Circle} after {@code def Circle = shapes.Circle}. The class or interface the path
     * ends on, as its own file types it ; error when a file on the way can't be typed (a cycle) ;
     * null when the path names no class or interface.
     */
    private Type typeThroughModule(Type type, List<String> path, ParserRuleContext at) {
        for (int i = 1; i < path.size() && type != null && type != Type.ERROR; i++) {
            if (type instanceof Type.Module module) {
                if (module.module().checker() == null && module.module().exports().contains(path.get(i))) {
                    warn("Missing type : " + path.get(i) + " (import cycle with " + module.module().name() + ")", at);
                    return Type.ERROR;
                }
                type = exportType(module.module(), path.get(i));
            } else if (type instanceof Type.Package pkg) {
                type = packageMember(pkg, path.get(i));
            } else {
                return null;
            }
        }
        if (type == Type.ERROR) {
            return Type.ERROR;
        }
        return type instanceof Type.ClassValue value ? new Type.Instance(value.node()) : null;
    }

    private TypeNode classNode(BabbleParser.ExpressionContext value) {
        if (value instanceof BabbleParser.ClassExpressionContext || value instanceof BabbleParser.InterfaceExpressionContext) {
            return pass.typeNodes.get(value);
        }
        return null;
    }

    private TypeNode nodeInPackages(List<BabbleParser.PackageExpressionContext> packages, List<String> path, int index) {
        if (index >= path.size()) {
            return null;
        }
        List<BabbleParser.PackageExpressionContext> nested = new ArrayList<>();
        for (BabbleParser.PackageExpressionContext pkg : packages) {
            for (BabbleParser.ExpressionContext statement : pkg.packageBlock.expression()) {
                if (statement instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(path.get(index))) {
                    return index == path.size() - 1 ? classNode(def.value) : null;
                }
                if (statement instanceof BabbleParser.PackageExpressionContext inner && inner.name.getText().equals(path.get(index))) {
                    nested.add(inner);
                }
            }
        }
        return nested.isEmpty() ? null : nodeInPackages(nested, path, index + 1);
    }

    /** The literal argument of {@code import("…")}, or a subpackage selected from it, or null. */
    private static String importedPackage(BabbleParser.ExpressionContext value) {
        if (value instanceof BabbleParser.SelectorContext selector && selector.expression() != null) {
            String base = importedPackage(selector.expression());
            return base != null ? base + "." + selector.ID().getText() : null;
        }
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
            return Type.ANY;
        }
        if (java.isPrimitive()) {
            return new Type.JavaInstance(JavaTypes.box(java));
        }
        if (java.getTypeParameters().length > 0) {
            return new Type.JavaInstance(java, Collections.nCopies(java.getTypeParameters().length, Type.NULLABLE_ANY));
        }
        return new Type.JavaInstance(java);
    }

    private Map<TypeVariable<?>, Type> javaTypeEnv(Class<?> cls, List<Type> typeArgs) {
        Map<TypeVariable<?>, Type> env = new HashMap<>();
        if (cls == null) {
            return env;
        }
        TypeVariable<?>[] ownParams = cls.getTypeParameters();
        for (int i = 0; i < Math.min(ownParams.length, typeArgs.size()); i++) {
            env.put(ownParams[i], typeArgs.get(i));
        }
        populateSuperJavaEnv(cls, env);
        return env;
    }

    private void populateSuperJavaEnv(Class<?> cls, Map<TypeVariable<?>, Type> env) {
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        queue.add(cls);
        visited.add(cls);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            List<java.lang.reflect.Type> supers = new ArrayList<>();
            if (current.getGenericSuperclass() != null) {
                supers.add(current.getGenericSuperclass());
            }
            Collections.addAll(supers, current.getGenericInterfaces());
            for (java.lang.reflect.Type sup : supers) {
                if (sup instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
                    TypeVariable<?>[] rawParams = raw.getTypeParameters();
                    java.lang.reflect.Type[] rawArgs = pt.getActualTypeArguments();
                    for (int i = 0; i < Math.min(rawParams.length, rawArgs.length); i++) {
                        env.putIfAbsent(rawParams[i], fromJavaType(rawArgs[i], env, false, false));
                    }
                    if (visited.add(raw)) {
                        queue.add(raw);
                    }
                } else if (sup instanceof Class<?> raw && visited.add(raw)) {
                    queue.add(raw);
                }
            }
        }
    }

    private Type fromJavaType(java.lang.reflect.Type type, Map<TypeVariable<?>, Type> env, boolean widenTopTypeVar, boolean forParam) {
        if (type instanceof TypeVariable<?> tv) {
            Type resolved = env.get(tv);
            if (resolved == null) {
                resolved = Type.NULLABLE_ANY;
            }
            return widenTopTypeVar ? Type.nullable(resolved) : resolved;
        }
        if (type instanceof WildcardType wt) {
            Type resolved;
            if (forParam && wt.getLowerBounds().length > 0) {
                resolved = fromJavaType(wt.getLowerBounds()[0], env, false, false);
            } else if (wt.getUpperBounds().length > 0 && wt.getUpperBounds()[0] != Object.class) {
                resolved = fromJavaType(wt.getUpperBounds()[0], env, false, false);
            } else {
                resolved = Type.NULLABLE_ANY;
            }
            return widenTopTypeVar ? Type.nullable(resolved) : resolved;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            java.lang.reflect.Type[] rawArgs = pt.getActualTypeArguments();
            List<Type> args = new ArrayList<>(rawArgs.length);
            for (java.lang.reflect.Type arg : rawArgs) {
                args.add(fromJavaType(arg, env, false, forParam));
            }
            if (raw.getTypeParameters().length == args.size()) {
                return new Type.JavaInstance(raw, args);
            }
            return fromJava(raw);
        }
        if (type instanceof GenericArrayType gat) {
            Type component = fromJavaType(gat.getGenericComponentType(), env, false, false);
            Class<?> arrayClass = java.lang.reflect.Array.newInstance(erasedClass(gat.getGenericComponentType()), 0).getClass();
            return new Type.JavaArray(arrayClass, component);
        }
        if (type instanceof Class<?> cls) {
            return fromJava(cls);
        }
        return Type.NULLABLE_ANY;
    }

    private static Class<?> erasedClass(java.lang.reflect.Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof GenericArrayType gat) {
            return java.lang.reflect.Array.newInstance(erasedClass(gat.getGenericComponentType()), 0).getClass();
        }
        if (type instanceof TypeVariable<?> tv && tv.getBounds().length > 0) {
            return erasedClass(tv.getBounds()[0]);
        }
        return Object.class;
    }

    private boolean satisfiesJavaBounds(Type actual, TypeVariable<?> param, Map<TypeVariable<?>, Type> env) {
        if (actual == Type.ERROR || actual == Type.NOTHING || actual instanceof Type.TypeVar) {
            return true;
        }
        for (java.lang.reflect.Type bound : param.getBounds()) {
            if (bound == Object.class) {
                continue;
            }
            if (!satisfiesJavaBound(actual, bound, env)) {
                return false;
            }
        }
        return true;
    }

    private boolean satisfiesJavaBound(Type actual, java.lang.reflect.Type bound, Map<TypeVariable<?>, Type> env) {
        if (bound == Object.class) {
            return true;
        }
        if (bound instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            Type projected = projectToJava(actual, raw);
            if (!(projected instanceof Type.JavaInstance proj)) {
                return false;
            }
            java.lang.reflect.Type[] ptArgs = pt.getActualTypeArguments();
            List<Type> actualArgs = proj.typeArguments();
            if (ptArgs.length != actualArgs.size()) {
                return true;
            }
            for (int i = 0; i < ptArgs.length; i++) {
                java.lang.reflect.Type ptArg = ptArgs[i];
                Type a = actualArgs.get(i);
                if (ptArg instanceof WildcardType wt) {
                    if (wt.getLowerBounds().length > 0) {
                        Type lower = fromJavaType(wt.getLowerBounds()[0], env, false, false);
                        if (!assignable(lower, a)) {
                            return false;
                        }
                    } else if (wt.getUpperBounds().length > 0 && wt.getUpperBounds()[0] != Object.class) {
                        Type upper = fromJavaType(wt.getUpperBounds()[0], env, false, false);
                        if (!covariantAssignable(a, upper)) {
                            return false;
                        }
                    }
                } else {
                    Type expectedArg = fromJavaType(ptArg, env, false, false);
                    if (!covariantAssignable(a, expectedArg)) {
                        return false;
                    }
                }
            }
            return true;
        }
        Type expectedBound = fromJavaType(bound, env, false, false);
        return assignable(actual, expectedBound);
    }

    /**
     * The type of what a Java method returns, or a Java field holds : an instance of its
     * class (whose members are checked), or the closest accessible supertype / {@code any}
     * when the concrete class is package-private or non-public.
     */
    private Type javaResult(java.lang.reflect.Type genericType, Map<TypeVariable<?>, Type> env) {
        return makeAccessible(fromJavaType(genericType, env, true, false));
    }

    private Type makeAccessible(Type type) {
        if (type instanceof Type.Union union) {
            return Type.union(union.members().stream().map(this::makeAccessible).toList());
        }
        if (type instanceof Type.JavaInstance ji && !ji.type().isInterface() && !JavaTypes.isAccessible(ji.type())) {
            Class<?> accessible = accessibleType(ji.type());
            if (accessible == null) {
                return Type.ANY;
            }
            Type projected = projectToJava(ji, accessible);
            return projected != null ? projected : fromJava(accessible);
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
            return Type.ERROR;
        }
        if (expected != null && expected != Type.ERROR && !types.containsKey(ctx)) {
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
            return type != null ? type : Type.ERROR;
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
            return Type.ERROR;
        }
        try {
            Type type = typer.visit(ctx);
            if (type == null) {
                type = Type.ERROR;
            }
            types.put(ctx, type);
            return type;
        } finally {
            inProgress.remove(ctx);
        }
    }

    private Type.Function recordFunctionLiteral(Type.Function fn, BabbleParser.FunctionLiteralContext literal) {
        if (inFile(literal)) {
            functionLiterals.put(fn, literal);
        }
        return fn;
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
        return recordFunctionLiteral(new Type.Function(typeVarsOf(owningDef(literal)), params, result), literal);
    }

    private Type.Function recursiveSignature(BabbleParser.FunctionLiteralContext literal) {
        List<Type.TypeVar> tparams = typeVarsOf(owningDef(literal));
        List<Type.Parameter> params = functionParameters(literal);
        if (provisionalResults.containsKey(literal)) {
            return recordFunctionLiteral(new Type.Function(tparams, params, provisionalResults.get(literal)), literal);
        }
        if (!inferenceStack.isEmpty() && inferenceStack.peek() == literal) {
            recursiveFunctions.add(literal);
            return recordFunctionLiteral(new Type.Function(tparams, params, Type.NOTHING), literal);
        }
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
        return recordFunctionLiteral(new Type.Function(tparams, params, Type.ERROR), literal);
    }

    private static BabbleParser.DefExpressionContext owningDef(BabbleParser.FunctionLiteralContext literal) {
        return owningDefExpr(literal);
    }

    private static BabbleParser.DefExpressionContext owningDefExpr(ParserRuleContext expr) {
        ParserRuleContext child = expr;
        while (child.getParent() instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            child = block;
        }
        return child.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == child ? def : null;
    }

    private static ParserRuleContext ownerOfParam(BabbleParser.ParameterDeclarationContext parameter) {
        ParserRuleContext parent = parameter.getParent();
        return parent != null ? parent.getParent() : null;
    }

    private boolean isExported(BabbleParser.DefExpressionContext def) {
        if (def == null || !inFile(def)) {
            return false;
        }
        if (def.EXPORT() != null) {
            return true;
        }
        BabbleParser.PackageExpressionContext outermost = null;
        for (ParserRuleContext p = def.getParent(); p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.PackageExpressionContext pkg) {
                outermost = pkg;
            } else if (p instanceof BabbleParser.FunctionLiteralContext
                    || p instanceof BabbleParser.ClassExpressionContext
                    || p instanceof BabbleParser.InterfaceExpressionContext
                    || p instanceof BabbleParser.ObjectExpressionContext) {
                return false;
            }
        }
        return outermost != null && pass.analysis.exportsName(outermost.name.getText());
    }

    private static boolean implementsInterfaceMethod(TypeNode node, String name) {
        return implementsInterfaceMethod(node, name, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean implementsInterfaceMethod(TypeNode node, String name, Set<TypeNode> seen) {
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode parentNode) {
                if (seen.add(parentNode)) {
                    if (parentNode.members.containsKey(name)
                            || implementsInterfaceMethod(parentNode, name, seen)) {
                        return true;
                    }
                }
            } else if (parent instanceof Class<?> javaIface) {
                for (Method method : javaIface.getMethods()) {
                    if (method.getName().equals(name)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isInferrableCallable(ParserRuleContext owner) {
        if (owner == null || !inFile(owner) || escapingTargets.contains(owner)) {
            return false;
        }
        if (owner instanceof BabbleParser.ClassExpressionContext cls) {
            TypeNode node = pass.typeNodes.get(cls);
            if (node == null || exportedTypeNodes.contains(node)) {
                return false;
            }
            BabbleParser.DefExpressionContext def = owningDefExpr(cls);
            return def != null && !reassigned.contains(def) && !isExported(def);
        }
        if (owner instanceof BabbleParser.FunctionLiteralContext fn) {
            ScopeNode fnScope = scopeOf(fn);
            if (fnScope.memberOf != null) {
                if (fnScope.memberOf.isInterface()) {
                    return false;
                }
                if (!(fn.getParent() instanceof BabbleParser.DefExpressionContext def && def.value == fn)) {
                    return false;
                }
                String memberName = def.name.getText();
                TypeNode.Member member = fnScope.staticMethod
                        ? fnScope.memberOf.statics.get(memberName)
                        : fnScope.memberOf.members.get(memberName);
                boolean isPrivate = member != null && member.isPrivate();
                if (exportedTypeNodes.contains(fnScope.memberOf) && !isPrivate) {
                    return false;
                }
                if (!fnScope.staticMethod && !isPrivate && implementsInterfaceMethod(fnScope.memberOf, memberName)) {
                    return false;
                }
                return true;
            }
            BabbleParser.DefExpressionContext def = owningDefExpr(fn);
            return def != null && !reassigned.contains(def) && !isExported(def);
        }
        return false;
    }

    private List<Type.Parameter> functionParameters(BabbleParser.FunctionLiteralContext literal) {
        ScopeNode scope = scopeOf(literal);
        List<Type.Parameter> result = new ArrayList<>();
        for (BabbleParser.ParameterDeclarationContext parameter : literal.parametersDeclaration().parameterDeclaration()) {
            Type type = parameterType(parameter, scope);
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
                    if (expected != null && expected != Type.ERROR) {
                        expect(actual, expected, parameter.defaultValue);
                    }
                } else if (expected != null && expected != Type.ERROR) {
                    expect(types.get(parameter.defaultValue), expected, parameter.defaultValue);
                }
            }
        }
    }

    private Type.Function samFunctionType(Type.JavaInstance java) {
        Method sam = JavaTypes.functionalMethod(java.type());
        if (sam == null) {
            return null;
        }
        Map<TypeVariable<?>, Type> env = javaTypeEnv(java.type(), java.typeArguments());
        java.lang.reflect.Type[] genericParams = sam.getGenericParameterTypes();
        List<Type.Parameter> params = new ArrayList<>(genericParams.length);
        for (int i = 0; i < genericParams.length; i++) {
            params.add(new Type.Parameter("p" + i, fromJavaType(genericParams[i], env, false, true), false));
        }
        Type returnType = fromJavaType(sam.getGenericReturnType(), env, false, false);
        return new Type.Function(params, returnType);
    }

    private static boolean containsSyntheticTypeVar(Type type) {
        if (type instanceof Type.TypeVar tv) {
            return tv.owner() == null;
        }
        if (type instanceof Type.Union u) {
            return u.members().stream().anyMatch(TypeChecker::containsSyntheticTypeVar);
        }
        if (type instanceof Type.Function f) {
            return containsSyntheticTypeVar(f.result())
                    || f.parameters().stream().anyMatch(p -> containsSyntheticTypeVar(p.type()));
        }
        if (type instanceof Type.Instance inst) {
            return inst.typeArguments().stream().anyMatch(TypeChecker::containsSyntheticTypeVar);
        }
        if (type instanceof Type.JavaInstance ji) {
            return ji.typeArguments().stream().anyMatch(TypeChecker::containsSyntheticTypeVar);
        }
        if (type instanceof Type.JavaArray ja) {
            return containsSyntheticTypeVar(ja.element());
        }
        return false;
    }

    private void applyContextualType(BabbleParser.FunctionLiteralContext literal) {
        Type expected = expectedType(literal);
        if (expected == null && literal.getParent() instanceof BabbleParser.DefExpressionContext def
                && def.value == literal && def.type() != null) {
            expected = declaredType(def);
        }
        if (expected == null) {
            return;
        }
        Type base = Type.withoutNull(expected);
        List<Type> expectedParamTypes = null;
        Type expectedResult = null;
        if (base instanceof Type.Function fn) {
            expectedParamTypes = fn.parameters().stream().map(Type.Parameter::type).toList();
            expectedResult = fn.result();
        } else if (base instanceof Type.JavaInstance java) {
            Type.Function sam = samFunctionType(java);
            if (sam != null) {
                expectedParamTypes = sam.parameters().stream().map(Type.Parameter::type).toList();
                expectedResult = sam.result();
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
                    if (paramType != null && paramType != Type.ERROR && !containsSyntheticTypeVar(paramType)) {
                        declarationTypes.put(param, paramType);
                    }
                }
            }
        }
        if (literal.type() == null && expectedResult != null
                && expectedResult != Type.NULL && !isUnresolved(expectedResult)
                && !containsSyntheticTypeVar(expectedResult)) {
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
            return Type.ERROR;
        }
        List<Diagnostic> savedDiagnostics = new ArrayList<>(diagnostics);
        List<Diagnostic> savedWarnings = new ArrayList<>(warnings);
        inferringResult.add(literal);
        inferenceStack.push(literal);
        Type expectedReturn = contextualReturnTypes.get(literal);
        Type pass1;
        try {
            pass1 = withFunctionBarrier(() -> computeBodyResult(literal, expectedReturn));
        } finally {
            inferenceStack.pop();
            inferringResult.remove(literal);
        }
        Type candidate = inferred(pass1);
        if (recursiveFunctions.remove(literal)) {
            if (candidate != Type.ERROR) {
                diagnostics.clear();
                diagnostics.addAll(savedDiagnostics);
                warnings.clear();
                warnings.addAll(savedWarnings);
                clearSubtreeTypes(literal.functionBlock);
                provisionalResults.put(literal, candidate);
                inferenceStack.push(literal);
                try {
                    Type pass2 = withFunctionBarrier(() -> computeBodyResult(literal, expectedReturn));
                    candidate = inferred(pass2);
                } finally {
                    inferenceStack.pop();
                    provisionalResults.remove(literal);
                }
            } else if (pass1 == Type.NOTHING) {
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
            Type next = join(result, retType);
            if (next == null) {
                if (!isUnresolved(result) && !isUnresolved(retType)) {
                    warn("Type error : incompatible return types : " + result + " and " + retType, ret);
                }
                next = Type.ERROR;
            }
            result = next;
        }
        if (literal.type() == null) {
            Type target = expectedReturn != null && expectedReturn != Type.ERROR ? expectedReturn : result;
            if (needsDoubleWidening(bodyType, target)) {
                widened.add(literal.functionBlock);
            }
            for (int i = 0; i < returns.size(); i++) {
                BabbleParser.ReturnExpressionContext ret = returns.get(i);
                if (ret.expression() != null && needsDoubleWidening(returnTypes.get(i), target)) {
                    widened.add(ret);
                }
            }
            if (expectedReturn != null && expectedReturn != Type.ERROR && needsDoubleWidening(result, expectedReturn)) {
                result = replaceIntWithDouble(result);
            }
        }
        return result;
    }

    private static Type replaceIntWithDouble(Type type) {
        if (type == Type.INT) {
            return Type.DOUBLE;
        }
        if (type instanceof Type.Union union) {
            List<Type> updated = new ArrayList<>(union.members().size());
            for (Type member : union.members()) {
                updated.add(member == Type.INT ? Type.DOUBLE : member);
            }
            return Type.union(updated);
        }
        return type;
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
            Type actual = typeOf(last);
            expect(actual, expected, last);
            if (needsDoubleWidening(actual, expected)) {
                widened.add(function.functionBlock);
            }
        }
    }

    private void expect(Type actual, Type expected, ParserRuleContext at) {
        if (actual != Type.ERROR && expected != Type.ERROR && !assignable(actual, expected)) {
            warn("Type error : expected " + expected + ", got " + actual, at);
        }
    }

    // ---------------------------------------------------------------- flow-sensitive narrowing

    private <T> T withNarrowing(Map<ParserRuleContext, Type> scope, Supplier<T> action) {
        if (scope.isEmpty()) {
            return action.get();
        }
        narrowings.push(new NarrowScope(scope, Collections.newSetFromMap(new IdentityHashMap<>()), false));
        try {
            return action.get();
        } finally {
            narrowings.pop();
        }
    }

    private <T> T withFunctionBarrier(Supplier<T> action) {
        if (narrowings.isEmpty()) {
            return action.get();
        }
        narrowings.push(new NarrowScope(Map.of(), Collections.newSetFromMap(new IdentityHashMap<>()), true));
        try {
            return action.get();
        } finally {
            narrowings.pop();
        }
    }

    private void killNarrowing(ParserRuleContext declaration) {
        if (declaration == null) {
            return;
        }
        for (NarrowScope scope : narrowings) {
            scope.killed().add(declaration);
        }
    }

    private void killAllNarrowings(Set<ParserRuleContext> declarations) {
        if (declarations.isEmpty()) {
            return;
        }
        for (NarrowScope scope : narrowings) {
            scope.killed().addAll(declarations);
        }
    }

    private List<Set<ParserRuleContext>> snapshotKilled() {
        List<Set<ParserRuleContext>> snapshot = new ArrayList<>(narrowings.size());
        for (NarrowScope scope : narrowings) {
            Set<ParserRuleContext> copy = Collections.newSetFromMap(new IdentityHashMap<>());
            copy.addAll(scope.killed());
            snapshot.add(copy);
        }
        return snapshot;
    }

    private Set<ParserRuleContext> collectNewKills(List<Set<ParserRuleContext>> before) {
        Set<ParserRuleContext> added = Collections.newSetFromMap(new IdentityHashMap<>());
        int i = 0;
        for (NarrowScope scope : narrowings) {
            Set<ParserRuleContext> prior = i < before.size() ? before.get(i) : Set.of();
            for (ParserRuleContext decl : scope.killed()) {
                if (!prior.contains(decl)) {
                    added.add(decl);
                }
            }
            i++;
        }
        return added;
    }

    private void restoreKilled(List<Set<ParserRuleContext>> before) {
        int i = 0;
        for (NarrowScope scope : narrowings) {
            scope.killed().clear();
            if (i < before.size()) {
                scope.killed().addAll(before.get(i));
            }
            i++;
        }
    }

    private Set<ParserRuleContext> assignedLocals(ParseTree tree) {
        Set<ParserRuleContext> assigned = Collections.newSetFromMap(new IdentityHashMap<>());
        collectAssignedLocals(tree, assigned);
        return assigned;
    }

    private void collectAssignedLocals(ParseTree tree, Set<ParserRuleContext> assigned) {
        if (tree instanceof BabbleParser.FunctionLiteralContext
                || tree instanceof BabbleParser.ClassExpressionContext
                || tree instanceof BabbleParser.ObjectExpressionContext
                || tree instanceof BabbleParser.InterfaceExpressionContext) {
            return;
        }
        if (tree instanceof BabbleParser.AssignExpressionContext assign && assign.namespace == null) {
            ScopeNode scope = occurrenceScopes.get(assign);
            if (scope != null && resolveName(assign, assign.name.getText(), scope) instanceof ParserRuleContext decl) {
                assigned.add(decl);
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            collectAssignedLocals(tree.getChild(i), assigned);
        }
    }

    private Type narrowedType(ParserRuleContext declaration) {
        if (capturedReassigned.contains(declaration)) {
            return null;
        }
        for (NarrowScope scope : narrowings) {
            if (scope.killed().contains(declaration)) {
                return null;
            }
            if (scope.maskReassigned() && reassigned.contains(declaration)) {
                return null;
            }
            Type narrowed = scope.types().get(declaration);
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
        if (expr instanceof BabbleParser.BinaryOpContext opCtx
                && (opCtx.op.getType() == BabbleLexer.EQ || opCtx.op.getType() == BabbleLexer.NEQ)) {
            BabbleParser.ExpressionContext other = null;
            if (isNullLiteral(opCtx.right)) {
                other = opCtx.left;
            } else if (isNullLiteral(opCtx.left)) {
                other = opCtx.right;
            }
            if (other != null) {
                ParserRuleContext decl = narrowableDeclaration(other);
                if (decl != null) {
                    Type current = typeOf(other);
                    if (!isUnresolved(current) && Type.containsNull(current)) {
                        Type nonNull = Type.withoutNull(current);
                        Map<ParserRuleContext, Type> whenNotNull = nonNull != Type.NOTHING ? Map.of(decl, nonNull) : Map.of();
                        Narrowing eqNull = new Narrowing(Map.of(decl, Type.NULL), whenNotNull);
                        return opCtx.op.getType() == BabbleLexer.EQ ? eqNull : eqNull.invert();
                    }
                }
            }
            return Narrowing.EMPTY;
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
            if (target == null || target == Type.ERROR
                    || (!isUnresolved(current) && isRuntimeSubtype(current, target))) {
                return Narrowing.EMPTY;
            }
            Type narrowedTrue = !isUnresolved(current) ? narrowTarget(current, target) : target;
            Type subtracted = !isUnresolved(current) ? subtractType(current, target) : Type.NOTHING;
            Map<ParserRuleContext, Type> whenFalse = (subtracted != Type.NOTHING && !subtracted.equals(current))
                    ? Map.of(decl, subtracted) : Map.of();
            return new Narrowing(Map.of(decl, narrowedTrue), whenFalse);
        }
        ParserRuleContext decl = narrowableDeclaration(expr);
        if (decl != null) {
            Type current = typeOf(expr);
            if (!isUnresolved(current) && Type.containsNull(current)) {
                Type nonNull = Type.withoutNull(current);
                if (nonNull != Type.NOTHING) {
                    return new Narrowing(Map.of(decl, nonNull), Map.of());
                }
            }
        }
        return Narrowing.EMPTY;
    }

    private static boolean isNullLiteral(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            expr = block.block().expression(0);
        }
        return expr instanceof BabbleParser.NullContext;
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

    private Map<ParserRuleContext, Type> mergeNarrowings(
            Map<ParserRuleContext, Type> first,
            Map<ParserRuleContext, Type> second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        Map<ParserRuleContext, Type> merged = new IdentityHashMap<>(first);
        for (Map.Entry<ParserRuleContext, Type> entry : second.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), (a, b) ->
                    isRuntimeSubtype(a, b) && !isRuntimeSubtype(b, a) ? a : b);
        }
        return merged;
    }

    private ParserRuleContext narrowableDeclaration(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            expr = block.block().expression(0);
        }
        if (expr instanceof BabbleParser.SelectorContext selector && selector.expression() == null) {
            Object resolved = resolveName(selector, selector.ID().getText(), occurrenceScopes.get(selector));
            if (resolved instanceof BabbleParser.DefExpressionContext
                    || resolved instanceof BabbleParser.ParameterDeclarationContext) {
                ParserRuleContext decl = (ParserRuleContext) resolved;
                if (!capturedReassigned.contains(decl)
                        && (!reassigned.contains(decl) || Objects.equals(callableScope(occurrenceScopes.get(selector)), callableScope(scopeOf(decl))))) {
                    return decl;
                }
            }
        }
        return null;
    }

    private Type instanceTypeOfTypeValue(Type typeValue) {
        if (typeValue instanceof Type.ClassValue cv) {
            return rawInstance(cv.node());
        }
        if (typeValue instanceof Type.JavaClassValue jcv) {
            return rawJavaInstance(jcv.type());
        }
        return null;
    }

    /** The type of {@code this} : the class or interface whose body or method it is in, or the class value in a static block. */
    private Type thisType(ScopeNode scope) {
        ScopeNode thisScope = BlockScopes.thisScope(scope);
        if (thisScope == null) {
            return Type.ERROR;
        }
        if (thisScope.kind == ScopeNode.Kind.STATIC_BODY) {
            return new Type.ClassValue(thisScope.type);
        }
        List<Type.TypeVar> vars = typeVarsOf(thisScope.type);
        return vars.isEmpty() ? new Type.Instance(thisScope.type)
                : new Type.Instance(thisScope.type, List.copyOf(vars));
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

    private void recordInferredParam(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        if (isUnresolved(valueType)) {
            return;
        }
        if (containsUnresolved(valueType)) {
            return;
        }
        if (hasForeignTypeVar(valueType, decl)) {
            Type currentInferred = inferredParams.get(decl);
            if (currentInferred != null) {
                expect(valueType, currentInferred, at);
            }
            return;
        }
        Type current = nextInferredParams.get(decl);
        if (current == null) {
            nextInferredParams.put(decl, valueType);
            return;
        }
        Type joined = reassignmentJoin(current, valueType);
        if (joined == null) {
            warn("Type error : expected " + current + ", got " + valueType, at);
            return;
        }
        if (!joined.equals(current)) {
            nextInferredParams.put(decl, joined);
            if ((containsType(joined, Type.DOUBLE) && !containsType(current, Type.DOUBLE))
                    || (Type.containsNull(joined) && !Type.containsNull(current))) {
                ParserRuleContext site = at.getParent() instanceof BabbleParser.CallParameterContext cp
                        && cp.getParent() instanceof BabbleParser.CallParametersContext cps
                        && cps.getParent() instanceof BabbleParser.CallContext call ? call : at;
                paramWidenings.put(decl, new Widening(joined, site.getStart().getLine()));
            }
        }
    }

    private static boolean containsUnresolved(Type type) {
        if (type == null || isUnresolved(type)) {
            return true;
        }
        if (type instanceof Type.TypeVar tv) {
            return tv.owner() == null;
        }
        if (type instanceof Type.Union u) {
            return u.members().stream().anyMatch(TypeChecker::containsUnresolved);
        }
        if (type instanceof Type.Function f) {
            return containsUnresolved(f.result())
                    || f.parameters().stream().anyMatch(p -> containsUnresolved(p.type()));
        }
        if (type instanceof Type.Instance inst) {
            return inst.typeArguments().stream().anyMatch(TypeChecker::containsUnresolved);
        }
        if (type instanceof Type.JavaInstance ji) {
            return ji.typeArguments().stream().anyMatch(TypeChecker::containsUnresolved);
        }
        if (type instanceof Type.JavaArray ja) {
            return containsUnresolved(ja.element());
        }
        return false;
    }

    private static boolean hasForeignTypeVar(Type type, ParserRuleContext decl) {
        if (type instanceof Type.TypeVar tv) {
            return !isTypeVarInScope(tv, decl);
        }
        if (type instanceof Type.Union u) {
            return u.members().stream().anyMatch(m -> hasForeignTypeVar(m, decl));
        }
        if (type instanceof Type.Function f) {
            return hasForeignTypeVar(f.result(), decl)
                    || f.parameters().stream().anyMatch(p -> hasForeignTypeVar(p.type(), decl));
        }
        if (type instanceof Type.Instance inst) {
            return inst.typeArguments().stream().anyMatch(a -> hasForeignTypeVar(a, decl));
        }
        if (type instanceof Type.JavaInstance ji) {
            return ji.typeArguments().stream().anyMatch(a -> hasForeignTypeVar(a, decl));
        }
        if (type instanceof Type.JavaArray ja) {
            return hasForeignTypeVar(ja.element(), decl);
        }
        return false;
    }

    private static boolean isTypeVarInScope(Type.TypeVar tv, ParserRuleContext decl) {
        if (tv.owner() == null) {
            return false;
        }
        for (ParserRuleContext p = decl; p != null; p = p.getParent()) {
            if (p == tv.owner()) {
                return true;
            }
            if (p.getParent() instanceof BabbleParser.ClassExpressionContext cls && cls.staticBlock == p
                    && owningDefExpr(cls) == tv.owner()) {
                return false;
            }
        }
        return false;
    }

    private static BabbleParser.ClassExpressionContext unwrapClassExpression(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            expr = block.block().expression(0);
        }
        return expr instanceof BabbleParser.ClassExpressionContext cls ? cls : null;
    }

    private void checkSelectorEscape(BabbleParser.SelectorContext ctx, Type result) {
        boolean callee = isCalleeOfCall(ctx);
        boolean classSafe = callee || isReceiverOfSelector(ctx) || isTypeTestRight(ctx);
        if (!callee || !classSafe) {
            for (Type member : Type.membersOf(result)) {
                if (member instanceof Type.Function fn) {
                    BabbleParser.FunctionLiteralContext lit = functionLiterals.get(fn);
                    if (lit != null && inFile(lit) && !callee) {
                        nextEscapingTargets.add(lit);
                    }
                } else if (member instanceof Type.ClassValue cv
                        && cv.node().ctx instanceof BabbleParser.ClassExpressionContext cls
                        && inFile(cls) && !classSafe) {
                    nextEscapingTargets.add(cls);
                }
            }
            if (result == Type.ERROR && ctx.expression() == null) {
                Object resolved = resolveName(ctx, ctx.ID().getText(), occurrenceScopes.get(ctx));
                if (resolved instanceof BabbleParser.DefExpressionContext def && inFile(def)) {
                    BabbleParser.FunctionLiteralContext lit = unwrapFunctionLiteral(def.value);
                    if (lit != null && !callee) {
                        nextEscapingTargets.add(lit);
                    }
                    BabbleParser.ClassExpressionContext cls = unwrapClassExpression(def.value);
                    if (cls != null && !classSafe) {
                        nextEscapingTargets.add(cls);
                    }
                }
            }
        }
    }

    private void recordInferred(Map<ParserRuleContext, Type> map, Map<ParserRuleContext, Widening> widenMap,
                                ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        if (isUnresolved(valueType)) {
            failedDefs.add(decl);
            return;
        }
        Type current = map.get(decl);
        if (current == null) {
            map.put(decl, valueType);
            return;
        }
        Type joined = reassignmentJoin(current, valueType);
        if (joined == null) {
            warn("Type error : expected " + current + ", got " + valueType, at);
            return;
        }
        if (!joined.equals(current)) {
            map.put(decl, joined);
            if ((containsType(joined, Type.DOUBLE) && !containsType(current, Type.DOUBLE))
                    || (Type.containsNull(joined) && !Type.containsNull(current))) {
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
            List<Set<ParserRuleContext>> before = snapshotKilled();
            Type thenType = withNarrowing(narrowing.whenTrue(), () -> typeOf(ctx.thenBlock, expected));
            Set<ParserRuleContext> thenKilled = collectNewKills(before);
            restoreKilled(before);
            ParserRuleContext otherwise = ctx.elseBlock != null ? ctx.elseBlock : ctx.elseIf;
            Type elseType = otherwise != null
                    ? withNarrowing(narrowing.whenFalse(), () -> typeOf(otherwise, expected))
                    : Type.NULL;
            killAllNarrowings(thenKilled);
            boolean used = isValueUsed(ctx);
            if (expected != null && expected != Type.ERROR && used) {
                expect(thenType, expected, ctx.thenBlock);
                if (otherwise != null) {
                    expect(elseType, expected, otherwise);
                } else if (!assignable(Type.NULL, expected)) {
                    warn("Type error : expected " + expected + ", got null", ctx);
                }
                if (needsDoubleWidening(thenType, expected)) {
                    widened.add(ctx.thenBlock);
                }
                if (otherwise != null && needsDoubleWidening(elseType, expected)) {
                    widened.add(otherwise);
                }
                return expected;
            }
            Type joined = join(thenType, elseType);
            if (joined == null) {
                if (!used) {
                    return Type.NULL;
                }
                if (!isUnresolved(thenType) && !isUnresolved(elseType)) {
                    warn("Type error : the branches of if have different types : " + thenType + " and " + elseType, ctx);
                }
                return Type.ERROR;
            }
            if (needsDoubleWidening(thenType, joined)) {
                widened.add(ctx.thenBlock);
            }
            if (otherwise != null && needsDoubleWidening(elseType, joined)) {
                widened.add(otherwise);
            }
            return joined;
        }

        @Override
        public Type visitDefExpression(BabbleParser.DefExpressionContext ctx) {
            ScopeAnalysis.DefKind kind = pass.analysis.defTarget(ctx).kind();
            if (kind == ScopeAnalysis.DefKind.REQUIRED) {
                return Type.NULL;
            }
            Type declared = ctx.type() != null ? declaredType(ctx) : null;
            Type value = ctx.value != null ? typeOf(ctx.value, declared) : Type.NULL;
            if (declared != null) {
                expect(value, declared, ctx.value != null ? ctx.value : ctx);
                if (needsDoubleWidening(value, declared)) {
                    widened.add(ctx);
                }
            } else {
                ParserRuleContext at = ctx.value != null ? ctx.value : ctx;
                if (kind == ScopeAnalysis.DefKind.MEMBER || kind == ScopeAnalysis.DefKind.STATIC) {
                    recordInferredMember(ctx, value, at);
                    Type inferred = inferredMembers.get(ctx);
                    if (inferred != null && needsDoubleWidening(value, inferred)) {
                        widened.add(ctx);
                    }
                } else {
                    recordInferredDef(ctx, value, at);
                    Type inferred = inferredDefs.get(ctx);
                    if (inferred != null && needsDoubleWidening(value, inferred)) {
                        widened.add(ctx);
                    }
                }
            }
            return value;
        }

        @Override
        public Type visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
            killAllNarrowings(assignedLocals(ctx));
            typeOf(ctx.test);
            Narrowing narrowing = extractNarrowing(ctx.test);
            Type expected = expectedType(ctx);
            Type blockExpected = (expected == Type.ANY || expected == Type.NULLABLE_ANY) ? expected : null;
            Type bodyType = withNarrowing(narrowing.whenTrue(),
                    () -> typeOf(ctx.whileBlock, blockExpected));
            boolean used = isValueUsed(ctx);
            if (expected != null && expected != Type.ERROR && used) {
                expect(bodyType, expected, ctx.whileBlock);
                if (!assignable(Type.NULL, expected)) {
                    warn("Type error : expected " + expected + ", got null", ctx);
                }
                if (needsDoubleWidening(bodyType, expected)) {
                    widened.add(ctx.whileBlock);
                }
                return expected;
            }
            Type joined = join(bodyType, Type.NULL);
            if (joined == null) {
                if (!used) {
                    return Type.NULL;
                }
                if (!isUnresolved(bodyType)) {
                    warn("Type error : the branches of while have different types : " + bodyType + " and null", ctx);
                }
                return Type.ERROR;
            }
            if (needsDoubleWidening(bodyType, joined)) {
                widened.add(ctx.whileBlock);
            }
            return joined;
        }

        @Override
        public Type visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
            if (ctx.parametersDeclaration() != null) {
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
            Type result = thisType(occurrenceScopes.get(ctx));
            if (result instanceof Type.ClassValue cv && cv.node().ctx instanceof BabbleParser.ClassExpressionContext cls
                    && inFile(cls) && !isCalleeOfCall(ctx) && !isReceiverOfSelector(ctx) && !isTypeTestRight(ctx)) {
                nextEscapingTargets.add(cls);
            }
            return result;
        }

        @Override
        public Type visitSuperExpression(BabbleParser.SuperExpressionContext ctx) {
            return Type.ERROR;
        }

        @Override
        public Type visitSelector(BabbleParser.SelectorContext ctx) {
            Type result = selectUninstantiated(ctx);
            checkSelectorEscape(ctx, result);
            if (result instanceof Type.Function fn && !fn.typeParameters().isEmpty() && !isCalleeOfCall(ctx)) {
                result = instantiateFunctionValue(fn, ctx);
            }
            if (ctx.safe != null && result != Type.ERROR) {
                return Type.nullable(result);
            }
            return result;
        }

        private Type selectUninstantiated(BabbleParser.SelectorContext ctx) {
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
                TypeChecks.Provider provider = pass.superProviders.get(superExpr);
                if (provider != null) {
                    Type selfType = thisType(occurrenceScopes.get(ctx));
                    if (provider.function() != null) {
                        Type fnType = typeOf(provider.function());
                        if (provider.owner() instanceof TypeNode parentNode && selfType instanceof Type.Instance selfInst) {
                            Type projected = projectToBabble(selfInst, parentNode);
                            if (projected instanceof Type.Instance projInst) {
                                return substitute(fnType, instanceEnv(projInst));
                            }
                        }
                        return fnType;
                    }
                    if (provider.owner() instanceof Class<?> javaInterface) {
                        Type projected = projectToJava(selfType, javaInterface);
                        List<Type> typeArgs = projected instanceof Type.JavaInstance ji ? ji.typeArguments() : List.of();
                        return new Type.JavaMethod(javaInterface, typeArgs, provider.name());
                    }
                }
                return Type.ERROR;
            }
            if (ctx.safe != null) {
                Type receiverType = typeOf(receiver);
                if (isUnresolved(receiverType)) {
                    return Type.ERROR;
                }
                if (!canBeNull(receiverType)) {
                    warn("Not nullable : " + receiver.getText(), ctx);
                    return Type.ERROR;
                }
                Type target = receiverType == Type.NULL ? Type.NULL : Type.withoutNull(receiverType);
                return member(target, name, receiver instanceof BabbleParser.ThisExpressionContext, ctx, receiver);
            }
            return member(typeOf(receiver), name, receiver instanceof BabbleParser.ThisExpressionContext, ctx, receiver);
        }

        @Override
        public Type visitCall(BabbleParser.CallContext ctx) {
            if (ctx.expression() instanceof BabbleParser.SelectorContext safeSelector && safeSelector.safe != null) {
                Type callee = typer.selectUninstantiated(safeSelector);
                types.put(safeSelector, callee == Type.ERROR ? Type.ERROR : Type.nullable(callee));
                Type result = call(callee, ctx);
                for (BabbleParser.CallParameterContext argument : ctx.callParameters().callParameter()) {
                    typeOf(argument.expression());
                }
                return result == Type.ERROR ? Type.ERROR : Type.nullable(result);
            }
            Type callee = typeOf(ctx.expression());
            ModuleInterface module = pass.modules.get(ctx);
            Type result;
            if (module != null) {
                for (BabbleParser.CallParameterContext argument : ctx.callParameters().callParameter()) {
                    typeOf(argument.expression());
                }
                result = new Type.Module(module);
            } else if (isUnshadowedImport(ctx.expression())
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
                            narrowings.push(new NarrowScope(guard, Collections.newSetFromMap(new IdentityHashMap<>()), false));
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
            if (ctx.operand instanceof BabbleParser.IntegerContext literal) {
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
            if (operand instanceof Type.Union union) {
                if (Type.containsNull(union)) {
                    number(operand, ctx.operand);
                    return Type.ERROR;
                }
                boolean allNumericOrInstance = union.members().stream()
                        .allMatch(m -> m instanceof Type.Instance || isNumeric(m));
                if (!allNumericOrInstance) {
                    number(operand, ctx.operand);
                    return Type.ERROR;
                }
                List<Type> results = new ArrayList<>(union.members().size());
                for (Type m : union.members()) {
                    if (m instanceof Type.Instance) {
                        Type r = operatorCall(m, Operators.NEGATE, null, null, ctx, ctx.operand.getText());
                        if (r == Type.ERROR) {
                            return Type.ERROR;
                        }
                        results.add(r);
                    } else {
                        results.add(m == Type.INT ? Type.INT : Type.DOUBLE);
                    }
                }
                return Type.union(results);
            }
            if (!number(operand, ctx.operand) || isUnresolved(operand)) {
                return Type.ERROR;
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
            Type target = annotation(ctx.right, scopeOf(ctx), true);
            if (target == Type.ERROR) {
                return Type.ERROR;
            }
            if (containsTypeVar(target)) {
                warn("Cannot cast to type parameter : " + ctx.right.getText(), ctx.right);
                return Type.ERROR;
            }
            if (containsFunction(target)) {
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
                    && (ctx.type() == null || inferenceStack.isEmpty())) {
                withFunctionBarrier(() -> typeOf(ctx.functionBlock, expectedReturnType(ctx)));
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
            ParserRuleContext reassignedLocal = null;
            ParserRuleContext inferredTarget = null;
            boolean memberTarget = false;
            if (ctx.namespace == null) {
                if (resolveName(ctx, name, occurrenceScopes.get(ctx)) instanceof ParserRuleContext declaration) {
                    reassignedLocal = declaration;
                    if (declaration instanceof BabbleParser.ParameterDeclarationContext) {
                        declared = typeOfDeclaration(declaration);
                    } else {
                        declared = declaredType(declaration);
                        if (declared == null) {
                            inferredTarget = declaration;
                        }
                    }
                }
            } else {
                Type receiver = typeOf(ctx.namespace);
                java.lang.reflect.Field field = assignedJavaField(receiver, name, ctx);
                if (field != null) {
                    Type fieldType = javaFieldType(receiver, field, false);
                    Type value = typeOf(ctx.value, fieldType);
                    if (isFunction(value) && JavaTypes.isFunctionalInterface(field.getType())) {
                        warn("Cannot assign a function to " + field.getDeclaringClass().getCanonicalName() + "." + name
                                + " : convert it first, as " + field.getType().getSimpleName() + "(f)", ctx.value);
                    } else {
                        expect(value, fieldType, ctx.value);
                        if (needsDoubleWidening(value, fieldType)) {
                            widened.add(ctx);
                        }
                    }
                    return value;
                }
                boolean own = ctx.namespace instanceof BabbleParser.ThisExpressionContext;
                declared = assignedMemberType(receiver, name, own, ctx);
                TypeNode.Member member = assignedBabbleMember(receiver, name, own);
                if (member != null && member.function != null && inFile(member.function)) {
                    nextEscapingTargets.add(member.function);
                }
                if (declared == null && member != null && member.function == null
                        && member.declaration instanceof BabbleParser.DefExpressionContext def
                        && isLocal(def) && inFile(def)) {
                    inferredTarget = def;
                    memberTarget = true;
                }
            }
            Type value = typeOf(ctx.value, declared);
            if (declared != null) {
                expect(value, declared, ctx.value);
                if (needsDoubleWidening(value, declared)) {
                    widened.add(ctx);
                }
            } else if (inferredTarget != null) {
                if (memberTarget) {
                    recordInferredMember(inferredTarget, value, ctx.value);
                    Type inferred = inferredMembers.get(inferredTarget);
                    if (inferred != null && needsDoubleWidening(value, inferred)) {
                        widened.add(ctx);
                    }
                } else {
                    recordInferredDef(inferredTarget, value, ctx.value);
                    Type inferred = inferredDefs.get(inferredTarget);
                    if (inferred != null && needsDoubleWidening(value, inferred)) {
                        widened.add(ctx);
                    }
                }
            }
            killNarrowing(reassignedLocal);
            return value;
        }

        /**
         * {@code x op= v} : typed as {@code x = x op v}, the operator's result checked against
         * what the target declares.
         */
        private Type compoundAssignment(BabbleParser.AssignExpressionContext ctx, String operator) {
            String name = ctx.name.getText();
            Type declared = null;
            Type current = Type.ERROR;
            ParserRuleContext reassignedLocal = null;
            ParserRuleContext inferredTarget = null;
            boolean memberTarget = false;
            if (ctx.namespace == null) {
                Object resolved = resolveName(ctx, name, occurrenceScopes.get(ctx));
                if (resolved instanceof ParserRuleContext declaration) {
                    reassignedLocal = declaration;
                    Type narrowed = narrowedType(declaration);
                    current = narrowed != null ? narrowed : typeOfDeclaration(declaration);
                    if (declaration instanceof BabbleParser.ParameterDeclarationContext) {
                        declared = typeOfDeclaration(declaration);
                    } else {
                        declared = declaredType(declaration);
                        if (declared == null) {
                            inferredTarget = declaration;
                        }
                    }
                } else if (resolved == null) {
                    current = implicitType(name);
                }
            } else {
                Type receiver = typeOf(ctx.namespace);
                java.lang.reflect.Field field = assignedJavaField(receiver, name, ctx);
                if (field != null) {
                    declared = javaFieldType(receiver, field, false);
                    current = javaFieldType(receiver, field, true);
                } else {
                    boolean own = ctx.namespace instanceof BabbleParser.ThisExpressionContext;
                    declared = assignedMemberType(receiver, name, own, ctx);
                    if (declared != null) {
                        current = declared;
                    } else {
                        TypeNode.Member member = assignedBabbleMember(receiver, name, own);
                        if (member != null) {
                            if (member.function != null && inFile(member.function)) {
                                nextEscapingTargets.add(member.function);
                            }
                            current = receiver instanceof Type.Instance inst
                                    ? substitute(memberType(member), instanceEnv(inst))
                                    : memberType(member);
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
                if (needsDoubleWidening(result, declared)) {
                    widened.add(ctx);
                }
            } else if (inferredTarget != null) {
                if (memberTarget) {
                    recordInferredMember(inferredTarget, result, ctx);
                    Type inferred = inferredMembers.get(inferredTarget);
                    if (inferred != null && needsDoubleWidening(result, inferred)) {
                        widened.add(ctx);
                    }
                } else {
                    recordInferredDef(inferredTarget, result, ctx);
                    Type inferred = inferredDefs.get(inferredTarget);
                    if (inferred != null && needsDoubleWidening(result, inferred)) {
                        widened.add(ctx);
                    }
                }
            }
            killNarrowing(reassignedLocal);
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
                return Type.ERROR;
            }
            if (inFile(function) && !isCalleeOfCall(ctx)) {
                nextEscapingTargets.add(function);
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
            } catch (NumberFormatException e) {
                warn("Integer out of range : " + ctx.getText(), ctx);
            }
            return Type.INT;
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
                Type declared = annotation(function.type(), scopeOf(function));
                expect(value, declared, ctx.expression() != null ? ctx.expression() : ctx);
                if (needsDoubleWidening(value, declared)) {
                    widened.add(ctx);
                }
            }
            return Type.NOTHING;
        }
    }

    // ---------------------------------------------------------------- members

    private record ResolvedMember(TypeNode.Member member, Map<Type.TypeVar, Type> env) {
    }

    /** {@code receiver.name}, read. */
    private Type member(Type receiver, String name, boolean own, ParserRuleContext at, BabbleParser.ExpressionContext receiverExpression) {
        return member(receiver, name, own, at, receiverExpression.getText());
    }

    /** {@code receiver.name}, read, given the receiver's source text. */
    private Type member(Type receiver, String name, boolean own, ParserRuleContext at, String receiverText) {
        if (isUnresolved(receiver)) {
            return Type.ERROR;
        }
        if (receiver instanceof Type.Union union) {
            if (Type.containsNull(union)) {
                warn("Not a scope : " + receiverText, at);
                return Type.ERROR;
            }
            List<Type> memberTypes = new ArrayList<>(union.members().size());
            for (Type member : union.members()) {
                Type resolved = member(member, name, own, at, receiverText);
                if (resolved == Type.ERROR) {
                    return Type.ERROR;
                }
                memberTypes.add(resolved);
            }
            return Type.union(memberTypes);
        }
        if (receiver instanceof Type.Instance instance) {
            ResolvedMember resolved = findMember(instance, name);
            if (resolved != null) {
                if (resolved.member().isPrivate() && !own) {
                    warn(name + " is private", at);
                    return Type.ERROR;
                }
                return substitute(memberType(resolved.member()), resolved.env());
            }
            Type inherited = inheritedMember(instance, name);
            if (inherited != null) {
                return inherited;
            }
            warn("No such member : " + name, at);
            return Type.ERROR;
        }
        if (receiver instanceof Type.ClassValue value) {
            TypeNode.Member member = value.node().statics.get(name);
            if (member != null) {
                if (member.isPrivate() && !own) {
                    warn(name + " is private", at);
                    return Type.ERROR;
                }
                return memberType(member);
            }
            warn("No such member : " + name, at);
            return Type.ERROR;
        }
        if (receiver instanceof Type.Package pkg) {
            Type found = packageMember(pkg, name);
            if (found != null) {
                return found;
            }
            warn("No such member : " + name, at);
            return Type.ERROR;
        }
        if (receiver instanceof Type.Module module) {
            if (module.module().checker() == null && module.module().exports().contains(name)) {
                warn("Missing type : " + name + " (import cycle with " + module.module().name() + ")", at);
                return Type.ERROR;
            }
            // A missing export is TypeChecks' to report.
            Type export = exportType(module.module(), name);
            return export != null ? export : Type.ERROR;
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
            return javaMember(value.type(), List.of(), name, true, at);
        }
        if (receiver instanceof Type.JavaInstance instance) {
            return javaMember(instance.type(), instance.typeArguments(), name, false, at);
        }
        if (receiver instanceof Type.JavaArray array) {
            if ("length".equals(name)) {
                return Type.INT;
            }
            return javaMember(array.javaClass(), List.of(), name, false, at);
        }
        // Numbers, booleans, strings, null, functions, any : nothing has members but objects,
        // classes, packages and Java values.
        warn("Not a scope : " + receiverText, at);
        return Type.ERROR;
    }

    /** What {@code receiver.name = value} must assign : the member's declared or closed type, or null if inferred locally. */
    private Type assignedMemberType(Type receiver, String name, boolean own, ParserRuleContext at) {
        TypeNode.Member member = null;
        Map<Type.TypeVar, Type> env = Map.of();
        Type.Instance receiverInstance = null;
        if (receiver instanceof Type.Instance instance) {
            receiverInstance = instance;
            ResolvedMember resolved = findMember(instance, name);
            if (resolved != null) {
                member = resolved.member();
                env = resolved.env();
            } else if (inheritedMember(instance, name) == null) {
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
        } else if (!(receiver instanceof Type.Module || receiver instanceof Type.JavaPackage
                || receiver instanceof Type.JavaClassValue || receiver instanceof Type.JavaInstance
                || receiver instanceof Type.JavaArray || isUnresolved(receiver))) {
            String receiverText = at instanceof BabbleParser.AssignExpressionContext assign
                    ? assign.namespace.getText() : name;
            warn("Not a scope : " + receiverText, at);
        }
        if (member == null) {
            return null;
        }
        if (member.isPrivate() && !own) {
            warn(name + " is private", at);
            return null;
        }
        if (!isLocal(member.declaration) || (!inFile(member.declaration) && pass.session != null)) {
            Type closed = memberType(member);
            return closed == Type.ERROR ? null : substitute(closed, env);
        }
        if (member.declaration instanceof BabbleParser.ParameterDeclarationContext) {
            Type type = typeOfDeclaration(member.declaration);
            return type == Type.ERROR ? null : substitute(type, env);
        }
        if (member.function != null) {
            return substitute(memberType(member), env);
        }
        Type declared = declaredType(member.declaration);
        if (declared != null) {
            return substitute(declared, env);
        }
        if (receiverInstance != null && !receiverInstance.node().typeParameters.isEmpty() && !own) {
            Type closed = memberType(member);
            if (closed != Type.ERROR && containsTypeVar(closed)) {
                return substitute(closed, env);
            }
        }
        return null;
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

    private ResolvedMember findMember(Type.Instance instance, String name) {
        TypeNode node = instance.node();
        Map<Type.TypeVar, Type> env = instanceEnv(instance);
        TypeNode.Member member = node.members.get(name);
        if (member != null || !node.isInterface()) {
            return member != null ? new ResolvedMember(member, env) : null;
        }
        for (BabbleParser.TypeContext parentType : node.parentTypes) {
            Type parent = substitute(parentAnnotation(node, parentType), env);
            if (parent instanceof Type.Instance parentInst) {
                ResolvedMember found = findMember(parentInst, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * A member the class or interface gets from what it implements or extends : a Babble
     * default, or any method of a Java interface. Null when there is none.
     */
    private Type inheritedMember(Type.Instance instance, String name) {
        TypeNode node = instance.node();
        Map<Type.TypeVar, Type> env = instanceEnv(instance);
        for (BabbleParser.TypeContext parentType : node.parentTypes) {
            Type parent = substitute(parentAnnotation(node, parentType), env);
            if (parent instanceof Type.Instance parentInst) {
                TypeNode.Member member = parentInst.node().members.get(name);
                if (member != null && (member.function != null || node.isInterface())) {
                    return substitute(memberType(member), instanceEnv(parentInst));
                }
                Type inherited = inheritedMember(parentInst, name);
                if (inherited != null) {
                    return inherited;
                }
            } else if (parent instanceof Type.JavaInstance javaParent
                    && Arrays.stream(javaParent.type().getMethods()).anyMatch(m -> m.getName().equals(name))) {
                return new Type.JavaMethod(javaParent.type(), javaParent.typeArguments(), name);
            }
        }
        return null;
    }

    /** A member's annotation, method signature, or inferred type. */
    private Type memberType(TypeNode.Member member) {
        if (!isLocal(member.declaration)) {
            Type type = ask(member.declaration, owner -> owner.memberType(member));
            return type != null ? type : Type.ERROR;
        }
        if (!inFile(member.declaration) && pass.session != null) {
            Type frozen = pass.session.memberTypes.get(member.declaration);
            if (frozen != null) {
                return frozen;
            }
        }
        Type declared = declaredType(member.declaration);
        if (declared != null) {
            return declared;
        }
        if (member.declaration instanceof BabbleParser.ParameterDeclarationContext) {
            return typeOfDeclaration(member.declaration);
        }
        if (member.function != null) {
            return typeOf(member.function);
        }
        if (member.declaration instanceof BabbleParser.DefExpressionContext def) {
            Type inferred = inferredMembers.get(def);
            if (inferred != null) {
                return inferred;
            }
            if (!inProgress.add(def)) {
                warn("Missing type : " + def.name.getText() + " (recursive)", def);
                failedDefs.add(def);
                return Type.ERROR;
            }
            try {
                if (def.value == null) {
                    return Type.NULL;
                }
                Type value = typeOf(def.value);
                return value == Type.NOTHING ? Type.ERROR : value;
            } finally {
                inProgress.remove(def);
            }
        }
        return Type.ERROR;
    }

    /**
     * The type of a module's export : as its own file's checker types it. Null when the
     * module doesn't export the name ; error when its file can't be typed (a cycle).
     */
    private static Type exportType(ModuleInterface module, String name) {
        if (!module.exports().contains(name)) {
            return null;
        }
        TypeChecker checker = module.checker();
        if (checker == null) {
            return Type.ERROR;
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
        return packages != null ? new Type.Package(name, packages) : Type.ERROR;
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
    private Type javaMember(Class<?> java, List<Type> typeArgs, String name, boolean onClass, ParserRuleContext at) {
        for (Method method : java.getMethods()) {
            if (method.getName().equals(name)) {
                return new Type.JavaMethod(java, onClass ? List.of() : typeArgs, name);
            }
        }
        if (java.isInterface() && !onClass) {
            for (Method method : Object.class.getMethods()) {
                if (method.getName().equals(name)) {
                    return new Type.JavaMethod(Object.class, List.of(), name);
                }
            }
        }
        java.lang.reflect.Field field = javaField(java, name);
        if (field != null) {
            if (onClass && !Modifier.isStatic(field.getModifiers())) {
                warn("Not a static field : " + java.getCanonicalName() + "." + name, at);
                return Type.ERROR;
            }
            Map<TypeVariable<?>, Type> env = onClass ? Map.of() : javaTypeEnv(java, typeArgs);
            java.lang.reflect.Type genericType = field.getGenericType();
            Type result = javaResult(genericType, env);
            if (genericType instanceof TypeVariable<?> || genericType instanceof WildcardType) {
                String desc = descriptor(result);
                if (desc != null) {
                    callChecks.put(at, desc);
                }
            }
            return result;
        }
        warn("No such name in " + java.getCanonicalName() + " : " + name, at);
        return Type.ERROR;
    }

    private Type javaFieldType(Type receiver, java.lang.reflect.Field field, boolean widenTopTypeVar) {
        Map<TypeVariable<?>, Type> env = receiver instanceof Type.JavaInstance ji
                ? javaTypeEnv(ji.type(), ji.typeArguments())
                : Map.of();
        return makeAccessible(fromJavaType(field.getGenericType(), env, widenTopTypeVar, false));
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

    private static boolean isCalleeOfCall(ParserRuleContext ctx) {
        ParserRuleContext current = ctx;
        while (current.getParent() instanceof BabbleParser.BlockExpressionContext block
                && block.block().expression().size() == 1) {
            current = block;
        }
        return current.getParent() instanceof BabbleParser.CallContext call && call.expression() == current;
    }

    private static boolean isReceiverOfSelector(ParserRuleContext ctx) {
        ParserRuleContext current = ctx;
        while (current.getParent() instanceof BabbleParser.BlockExpressionContext block
                && block.block().expression().size() == 1) {
            current = block;
        }
        return (current.getParent() instanceof BabbleParser.SelectorContext selector && selector.expression() == current)
                || (current.getParent() instanceof BabbleParser.AssignExpressionContext assign && assign.namespace == current);
    }

    private static boolean isTypeTestRight(ParserRuleContext ctx) {
        ParserRuleContext current = ctx;
        while (current.getParent() instanceof BabbleParser.BlockExpressionContext block
                && block.block().expression().size() == 1) {
            current = block;
        }
        return current.getParent() instanceof BabbleParser.IsExpressionContext isExpr && isExpr.right == current;
    }

    private Type instantiateFunctionValue(Type.Function fn, BabbleParser.SelectorContext ctx) {
        Type expected = expectedType(ctx);
        if (expected == null) {
            warn("Missing type : cannot infer type arguments for " + ctx.ID().getText(), ctx);
            return Type.ERROR;
        }
        Type target = Type.withoutNull(expected);
        Type.Function expectedFn = null;
        if (target instanceof Type.Function f) {
            expectedFn = f;
        } else if (target instanceof Type.JavaInstance ji && JavaTypes.isFunctionalInterface(ji.type())) {
            expectedFn = samFunctionType(ji);
        }
        if (expectedFn == null || !expectedFn.typeParameters().isEmpty()) {
            warn("Missing type : cannot infer type arguments for " + ctx.ID().getText(), ctx);
            return Type.ERROR;
        }
        Map<Type.TypeVar, Type> inferred = new HashMap<>();
        Set<Type.TypeVar> solving = Set.copyOf(fn.typeParameters());
        Type.Function unparameterized = new Type.Function(fn.parameters(), fn.result());
        if (!unify(unparameterized, expectedFn, solving, inferred, false)) {
            return unparameterized;
        }
        for (Type.TypeVar tv : fn.typeParameters()) {
            if (!inferred.containsKey(tv)) {
                warn("Missing type : cannot infer type arguments for " + ctx.ID().getText(), ctx);
                return Type.ERROR;
            }
        }
        return substitute(unparameterized, inferred);
    }

    private static boolean containsTypeVar(Type type) {
        if (type instanceof Type.TypeVar) {
            return true;
        }
        if (type instanceof Type.Union u) {
            return u.members().stream().anyMatch(TypeChecker::containsTypeVar);
        }
        if (type instanceof Type.Function f) {
            return containsTypeVar(f.result()) || f.parameters().stream().anyMatch(p -> containsTypeVar(p.type()));
        }
        if (type instanceof Type.Instance inst) {
            return inst.typeArguments().stream().anyMatch(TypeChecker::containsTypeVar);
        }
        if (type instanceof Type.JavaInstance ji) {
            return ji.typeArguments().stream().anyMatch(TypeChecker::containsTypeVar);
        }
        if (type instanceof Type.JavaArray ja) {
            return containsTypeVar(ja.element());
        }
        return false;
    }

    private List<BabbleParser.ParameterDeclarationContext> parameterDecls(Type.Function fn) {
        BabbleParser.FunctionLiteralContext lit = functionLiterals.get(fn);
        return lit != null ? lit.parametersDeclaration().parameterDeclaration() : null;
    }

    private static List<BabbleParser.ParameterDeclarationContext> parameterDecls(TypeNode node) {
        return node.ctx instanceof BabbleParser.ClassExpressionContext cls && cls.parametersDeclaration() != null
                ? cls.parametersDeclaration().parameterDeclaration() : null;
    }

    private Type call(Type callee, BabbleParser.CallContext ctx) {
        List<BabbleParser.CallParameterContext> arguments = ctx.callParameters().callParameter();
        if (callee == Type.ERROR || callee == Type.NOTHING) {
            return Type.ERROR;
        }
        if (callee == Type.PRINT) {
            Type appendable = new Type.JavaInstance(Appendable.class);
            for (BabbleParser.CallParameterContext argument : arguments) {
                if (argument.ID() != null && "to".equals(argument.ID().getText())) {
                    expect(typeOf(argument.expression(), appendable), appendable, argument.expression());
                } else {
                    typeOf(argument.expression(), Type.NULLABLE_ANY);
                }
            }
            return Type.NULL;
        }
        if (callee instanceof Type.Union union) {
            if (Type.containsNull(union)) {
                warn(ctx.expression().getText() + " is not callable", ctx);
                return Type.ERROR;
            }
            for (Type member : union.members()) {
                if (!isFunction(member)) {
                    warn(ctx.expression().getText() + " is not callable", ctx);
                    return Type.ERROR;
                }
            }
            List<Type> results = new ArrayList<>(union.members().size());
            for (Type member : union.members()) {
                Type r = call(member, ctx);
                if (r == Type.ERROR) {
                    return Type.ERROR;
                }
                results.add(r);
            }
            return Type.union(results);
        }
        if (callee instanceof Type.Function function) {
            List<BabbleParser.ParameterDeclarationContext> decls = parameterDecls(function);
            if (function.typeParameters().isEmpty()) {
                bind(function.parameters(), decls, arguments, ctx);
                return function.result();
            }
            return genericBabbleCall(function.typeParameters(), function.parameters(), decls, function.result(),
                    arguments, ctx, ctx.expression().getText());
        }
        if (callee instanceof Type.ClassValue value && !value.node().isInterface()) {
            TypeNode node = value.node();
            List<Type.TypeVar> classVars = typeVarsOf(node);
            List<Type.Parameter> parameters = node.ctx instanceof BabbleParser.ClassExpressionContext cls
                    ? constructorParameters(cls) : List.of();
            List<BabbleParser.ParameterDeclarationContext> decls = parameterDecls(node);
            if (classVars.isEmpty()) {
                if (parameters != null) {
                    bind(parameters, decls, arguments, ctx);
                }
                return new Type.Instance(node);
            }
            if (parameters == null) {
                parameters = List.of();
            }
            Type patternReturn = new Type.Instance(node, List.copyOf(classVars));
            return genericBabbleCall(classVars, parameters, decls, patternReturn, arguments, ctx, node.name);
        }
        if (callee instanceof Type.JavaClassValue value) {
            Class<?> java = value.type();
            if (java.isInterface() && arguments.size() == 1 && arguments.get(0).ID() == null
                    && JavaTypes.isFunctionalInterface(java)) {
                return javaGenericSamCall(java, arguments.get(0).expression(), ctx);
            }
            return javaConstructorCall(java, arguments, ctx);
        }
        if (callee instanceof Type.JavaMethod method) {
            List<Method> candidates = JavaTypes.methods(method.owner(), method.name());
            Map<TypeVariable<?>, Type> receiverEnv = method.typeArguments().isEmpty()
                    ? Map.of() : javaTypeEnv(method.owner(), method.typeArguments());
            return javaMethodCall(candidates, method.owner().getCanonicalName() + "." + method.name(),
                    receiverEnv, arguments, ctx);
        }
        warn(ctx.expression().getText() + " is not callable", ctx);
        return Type.ERROR;
    }

    private Type genericBabbleCall(List<Type.TypeVar> solvingList, List<Type.Parameter> parameters,
                                   List<BabbleParser.ParameterDeclarationContext> declarations,
                                   Type resultPattern, List<BabbleParser.CallParameterContext> arguments,
                                   BabbleParser.CallContext ctx, String description) {
        Type.Parameter[] argParams = mapArgumentsToParameters(parameters, arguments);
        Set<Type.TypeVar> solving = Set.copyOf(solvingList);
        Map<Type.TypeVar, Type> inferred = new HashMap<>();
        Map<Type.TypeVar, Type> retHints = new HashMap<>();
        Type ctxExpected = expectedType(ctx);
        if (ctxExpected != null && ctxExpected != Type.ERROR) {
            unifyReturn(resultPattern, ctxExpected, solving, retHints);
        }
        List<Type> paramPatterns = new ArrayList<>(arguments.size());
        List<BabbleParser.ExpressionContext> argExprs = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            paramPatterns.add(argParams[i] != null ? argParams[i].type() : null);
            argExprs.add(arguments.get(i).expression());
        }
        Type[] argTypes = new Type[arguments.size()];
        boolean progress;
        do {
            progress = false;
            for (int i = 0; i < arguments.size(); i++) {
                if (argTypes[i] != null) {
                    continue;
                }
                Map<Type.TypeVar, Type> env = placeholderEnvWithHints(solving, inferred, retHints);
                Type paramPattern = paramPatterns.get(i);
                Type expectedArg = paramPattern != null ? substitute(paramPattern, env) : null;
                if (!needsDeferredLambdaTyping(argExprs.get(i), expectedArg)) {
                    Type actual = typeOf(argExprs.get(i), expectedArg);
                    argTypes[i] = actual;
                    if (paramPattern != null) {
                        unify(paramPattern, actual, solving, inferred, true);
                    }
                    progress = true;
                }
            }
        } while (progress);

        if (inferred.size() < solving.size() && !retHints.isEmpty()) {
            retHints.forEach(inferred::putIfAbsent);
        }
        Map<Type.TypeVar, Type> finalHintEnv = placeholderEnvWithHints(solving, inferred, retHints);
        for (int i = 0; i < arguments.size(); i++) {
            if (argTypes[i] == null) {
                Type paramPattern = paramPatterns.get(i);
                Type expectedArg = paramPattern != null ? substitute(paramPattern, finalHintEnv) : null;
                Type actual = typeOf(argExprs.get(i), expectedArg);
                argTypes[i] = actual;
                if (paramPattern != null) {
                    unify(paramPattern, actual, solving, inferred, true);
                }
            }
        }
        applyDoubleWideningHint(inferred, retHints, paramPatterns, argExprs);
        boolean argErrored = Arrays.stream(argTypes).anyMatch(TypeChecker::isUnresolved);
        for (Type.TypeVar tv : solvingList) {
            if (!inferred.containsKey(tv)) {
                if (!argErrored) {
                    warn("Missing type : cannot infer type arguments for " + description, ctx);
                }
                return Type.ERROR;
            }
        }
        List<Type.Parameter> substitutedParams = new ArrayList<>(parameters.size());
        for (Type.Parameter p : parameters) {
            substitutedParams.add(new Type.Parameter(p.name(), substitute(p.type(), inferred), p.optional()));
        }
        bind(substitutedParams, declarations, arguments, ctx);
        return substitute(resultPattern, inferred);
    }

    private static Type.Parameter[] mapArgumentsToParameters(List<Type.Parameter> parameters,
                                                             List<BabbleParser.CallParameterContext> arguments) {
        int count = arguments.size();
        Type.Parameter[] mapping = new Type.Parameter[count];
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            if (arguments.get(i).ID() != null) {
                names[i] = arguments.get(i).ID().getText();
            }
        }
        for (int index = 0; index < parameters.size(); index++) {
            Type.Parameter parameter = parameters.get(index);
            int found = -1;
            for (int i = 0; i < count; i++) {
                if (parameter.name().equals(names[i])) {
                    found = i;
                }
            }
            if (found < 0 && index < count && names[index] == null) {
                found = index;
            }
            if (found >= 0) {
                mapping[found] = parameter;
            }
        }
        return mapping;
    }

    private static Map<Type.TypeVar, Type> placeholderEnvWithHints(Set<Type.TypeVar> solving,
                                                                   Map<Type.TypeVar, Type> inferred,
                                                                   Map<Type.TypeVar, Type> hints) {
        Map<Type.TypeVar, Type> env = new HashMap<>(solving.size());
        for (Type.TypeVar tv : solving) {
            Type resolved = inferred.get(tv);
            if (resolved == null && hints != null) {
                resolved = hints.get(tv);
            }
            env.put(tv, resolved != null ? resolved : new Type.TypeVar(null, tv.name(), tv.index()));
        }
        return env;
    }

    private boolean needsDeferredLambdaTyping(BabbleParser.ExpressionContext argExpr, Type expectedArg) {
        BabbleParser.FunctionLiteralContext literal = unwrapFunctionLiteral(argExpr);
        if (literal != null) {
            boolean hasUnannotated = false;
            for (BabbleParser.ParameterDeclarationContext p : literal.parametersDeclaration().parameterDeclaration()) {
                if (p.type() == null && p.defaultValue == null) {
                    hasUnannotated = true;
                    break;
                }
            }
            if (!hasUnannotated) {
                return false;
            }
            if (expectedArg == null) {
                return true;
            }
            return paramInputHasSyntheticTypeVar(Type.withoutNull(expectedArg));
        }
        BabbleParser.ExpressionContext unwrapped = argExpr;
        while (unwrapped instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            unwrapped = block.block().expression(0);
        }
        if (unwrapped instanceof BabbleParser.SelectorContext selector
                && isGenericFunctionSelector(selector)
                && (expectedArg == null || paramInputHasSyntheticTypeVar(Type.withoutNull(expectedArg)))) {
            return true;
        }
        return false;
    }

    private boolean isGenericFunctionSelector(BabbleParser.SelectorContext selector) {
        if (selector.expression() == null) {
            Object resolved = resolveName(selector, selector.ID().getText(), occurrenceScopes.get(selector));
            return resolved instanceof BabbleParser.DefExpressionContext def && def.typeParameters() != null;
        }
        if (selector.expression() instanceof BabbleParser.SelectorContext) {
            Type t = typer.selectUninstantiated(selector);
            return t instanceof Type.Function fn && !fn.typeParameters().isEmpty();
        }
        return false;
    }

    private static void applyDoubleWideningHint(Map<Type.TypeVar, Type> inferred,
                                                Map<Type.TypeVar, Type> retHints,
                                                List<Type> paramPatterns,
                                                List<BabbleParser.ExpressionContext> argExprs) {
        if (retHints.isEmpty()) {
            return;
        }
        retHints.forEach(inferred::putIfAbsent);
        for (Map.Entry<Type.TypeVar, Type> entry : retHints.entrySet()) {
            Type.TypeVar tv = entry.getKey();
            Type hint = entry.getValue();
            Type current = inferred.get(tv);
            if (current != null && isIntOrDouble(current) && needsDoubleWidening(current, hint)
                    && canWidenTypeVarToDouble(tv, paramPatterns, argExprs)) {
                inferred.put(tv, Type.containsNull(current) ? Type.nullable(Type.DOUBLE) : Type.DOUBLE);
            }
        }
    }

    private static boolean canWidenTypeVarToDouble(Type.TypeVar tv,
                                                   List<Type> paramPatterns,
                                                   List<BabbleParser.ExpressionContext> argExprs) {
        for (int i = 0; i < paramPatterns.size(); i++) {
            Type pat = paramPatterns.get(i);
            if (pat == null) {
                continue;
            }
            if (Type.withoutNull(pat).equals(tv)) {
                continue;
            }
            if (pat instanceof Type.Function fn
                    && Type.withoutNull(fn.result()).equals(tv)
                    && fn.parameters().stream().noneMatch(p -> mentionsTypeVar(p.type(), tv))
                    && i < argExprs.size()) {
                BabbleParser.FunctionLiteralContext lit = unwrapFunctionLiteral(argExprs.get(i));
                if (lit != null && lit.type() == null) {
                    continue;
                }
            }
            if (mentionsTypeVar(pat, tv)) {
                return false;
            }
        }
        return true;
    }

    private static boolean mentionsTypeVar(Type type, Type.TypeVar target) {
        if (type == null) {
            return false;
        }
        if (type.equals(target)) {
            return true;
        }
        if (type instanceof Type.Union u) {
            return u.members().stream().anyMatch(m -> mentionsTypeVar(m, target));
        }
        if (type instanceof Type.Function f) {
            return mentionsTypeVar(f.result(), target)
                    || f.parameters().stream().anyMatch(p -> mentionsTypeVar(p.type(), target));
        }
        if (type instanceof Type.Instance inst) {
            return inst.typeArguments().stream().anyMatch(a -> mentionsTypeVar(a, target));
        }
        if (type instanceof Type.JavaInstance ji) {
            return ji.typeArguments().stream().anyMatch(a -> mentionsTypeVar(a, target));
        }
        if (type instanceof Type.JavaArray ja) {
            return mentionsTypeVar(ja.element(), target);
        }
        return false;
    }

    private boolean paramInputHasSyntheticTypeVar(Type expectedBase) {
        if (expectedBase instanceof Type.Function fn) {
            for (Type.Parameter p : fn.parameters()) {
                if (containsSyntheticTypeVar(p.type())) {
                    return true;
                }
            }
            return false;
        }
        if (expectedBase instanceof Type.JavaInstance ji && JavaTypes.isFunctionalInterface(ji.type())) {
            Type.Function sam = samFunctionType(ji);
            if (sam != null) {
                return paramInputHasSyntheticTypeVar(sam);
            }
        }
        return containsSyntheticTypeVar(expectedBase);
    }

    private Type javaGenericSamCall(Class<?> java, BabbleParser.ExpressionContext argExpr, BabbleParser.CallContext ctx) {
        TypeVariable<?>[] typeParams = java.getTypeParameters();
        if (typeParams.length == 0) {
            Type expectedSam = new Type.JavaInstance(java);
            if (isFunction(typeOf(argExpr, expectedSam))) {
                return expectedSam;
            }
            return javaConstructorCall(java, ctx.callParameters().callParameter(), ctx);
        }
        List<Type.TypeVar> solvingList = new ArrayList<>(typeParams.length);
        Map<TypeVariable<?>, Type.TypeVar> tvToSynthetic = new HashMap<>();
        for (int i = 0; i < typeParams.length; i++) {
            Type.TypeVar tv = new Type.TypeVar(null, typeParams[i].getName(), i);
            solvingList.add(tv);
            tvToSynthetic.put(typeParams[i], tv);
        }
        Set<Type.TypeVar> solving = Set.copyOf(solvingList);
        Type.JavaInstance samPattern = new Type.JavaInstance(java, List.copyOf(solvingList));
        Type.Function samFn = samFunctionType(samPattern);
        Map<Type.TypeVar, Type> retHints = new HashMap<>();
        Type ctxExpected = expectedType(ctx);
        if (ctxExpected != null && ctxExpected != Type.ERROR) {
            unifyReturn(samPattern, ctxExpected, solving, retHints);
        }
        Map<Type.TypeVar, Type> inferred = new HashMap<>();
        Type expectedForArg = substitute(samPattern, placeholderEnvWithHints(solving, inferred, retHints));
        Type actualArg = typeOf(argExpr, expectedForArg);
        if (isUnresolved(actualArg)) {
            return Type.ERROR;
        }
        if (!isFunction(actualArg)) {
            return javaConstructorCall(java, ctx.callParameters().callParameter(), ctx);
        }
        if (actualArg instanceof Type.Function actualFn && samFn != null) {
            unify(samFn, actualFn, solving, inferred, true);
        }
        applyDoubleWideningHint(inferred, retHints, samFn != null ? List.of(samFn) : List.of(), List.of(argExpr));
        List<Type> solvedArgs = new ArrayList<>(typeParams.length);
        Map<TypeVariable<?>, Type> env = new HashMap<>();
        for (int i = 0; i < typeParams.length; i++) {
            Type resolved = inferred.get(solvingList.get(i));
            if (resolved == null) {
                warn("Missing type : cannot infer type arguments for " + java.getCanonicalName(), ctx);
                return Type.ERROR;
            }
            solvedArgs.add(resolved);
            env.put(typeParams[i], resolved);
        }
        for (int i = 0; i < typeParams.length; i++) {
            if (!satisfiesJavaBounds(solvedArgs.get(i), typeParams[i], env)) {
                warn("Type error : type argument " + solvedArgs.get(i) + " does not satisfy bound " + typeParams[i].getName()
                        + " of " + java.getCanonicalName(), argExpr);
                return Type.ERROR;
            }
        }
        Type.JavaInstance result = new Type.JavaInstance(java, solvedArgs);
        if (actualArg instanceof Type.Function actualFn) {
            expect(actualFn, result, argExpr);
        }
        return result;
    }

    private Type javaConstructorCall(Class<?> java, List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext ctx) {
        List<Executable> candidates = Arrays.asList(java.getConstructors());
        List<Type> argumentTypes = new ArrayList<>();
        List<Executable> matching = javaCall(candidates, java.getCanonicalName(), Map.of(), arguments, ctx, argumentTypes);
        TypeVariable<?>[] classParams = java.getTypeParameters();
        if (classParams.length == 0) {
            if (!matching.isEmpty()) {
                Executable winner = matching.size() == 1 ? matching.get(0) : mostSpecific(matching);
                if (winner != null) {
                    checkJavaArgumentWidening(winner, Map.of(), arguments, argumentTypes);
                }
            }
            return new Type.JavaInstance(java);
        }
        if (matching.isEmpty()) {
            return Type.ERROR;
        }
        Executable winner = matching.size() == 1 ? matching.get(0) : mostSpecific(matching);
        if (winner == null) {
            return Type.ERROR;
        }
        Map<TypeVariable<?>, Type.TypeVar> tvToSynthetic = new HashMap<>();
        List<Type.TypeVar> classSolving = new ArrayList<>(classParams.length);
        int idx = 0;
        for (TypeVariable<?> tp : classParams) {
            Type.TypeVar tv = new Type.TypeVar(null, tp.getName(), idx++);
            classSolving.add(tv);
            tvToSynthetic.put(tp, tv);
        }
        for (TypeVariable<?> tp : winner.getTypeParameters()) {
            tvToSynthetic.putIfAbsent(tp, new Type.TypeVar(null, tp.getName(), idx++));
        }
        Set<Type.TypeVar> solving = Set.copyOf(tvToSynthetic.values());
        Map<TypeVariable<?>, Type> patternEnv = new HashMap<>(tvToSynthetic);
        Map<Type.TypeVar, Type> inferred = new HashMap<>();
        java.lang.reflect.Type[] genericParams = winner.getGenericParameterTypes();
        List<Type> paramPatterns = new ArrayList<>(arguments.size());
        List<BabbleParser.ExpressionContext> argExprs = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            Type paramPat = fromJavaType(genericParams[i], patternEnv, false, true);
            paramPatterns.add(paramPat);
            argExprs.add(arguments.get(i).expression());
            unify(paramPat, argumentTypes.get(i), solving, inferred, true);
        }
        Type.JavaInstance patternReturn = new Type.JavaInstance(java, List.copyOf(classSolving));
        Type ctxExpected = expectedType(ctx);
        if (ctxExpected != null && ctxExpected != Type.ERROR) {
            Map<Type.TypeVar, Type> retHints = new HashMap<>();
            unifyReturn(patternReturn, ctxExpected, solving, retHints);
            applyDoubleWideningHint(inferred, retHints, paramPatterns, argExprs);
        }
        boolean argErrored = argumentTypes.stream().anyMatch(TypeChecker::isUnresolved);
        Map<TypeVariable<?>, Type> solvedEnv = new HashMap<>();
        List<Type> solvedClassArgs = new ArrayList<>(classParams.length);
        for (TypeVariable<?> tp : tvToSynthetic.keySet()) {
            Type resolved = inferred.get(tvToSynthetic.get(tp));
            if (resolved == null) {
                if (!argErrored) {
                    warn("Missing type : cannot infer type arguments for " + java.getCanonicalName(), ctx);
                }
                return Type.ERROR;
            }
            solvedEnv.put(tp, resolved);
        }
        for (int i = 0; i < classParams.length; i++) {
            Type resolved = solvedEnv.get(classParams[i]);
            solvedClassArgs.add(resolved);
            if (!satisfiesJavaBounds(resolved, classParams[i], solvedEnv)) {
                warn("Type error : type argument " + resolved + " does not satisfy bound " + classParams[i].getName()
                        + " of " + java.getCanonicalName(), ctx);
                return Type.ERROR;
            }
        }
        for (TypeVariable<?> tp : winner.getTypeParameters()) {
            Type resolved = solvedEnv.get(tp);
            if (!satisfiesJavaBounds(resolved, tp, solvedEnv)) {
                warn("Type error : type argument " + resolved + " does not satisfy bound " + tp.getName()
                        + " of " + java.getCanonicalName(), ctx);
                return Type.ERROR;
            }
        }
        checkJavaArgumentWidening(winner, solvedEnv, arguments, argumentTypes);
        return new Type.JavaInstance(java, solvedClassArgs);
    }

    private Type javaMethodCall(List<Method> candidates, String description, Map<TypeVariable<?>, Type> receiverEnv,
                                List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext ctx) {
        List<Type> argumentTypes = new ArrayList<>();
        List<Method> applicable = javaCall(candidates, description, receiverEnv, arguments, ctx, argumentTypes);
        if (applicable.isEmpty()) {
            return Type.ERROR;
        }
        Method best = applicable.size() == 1 ? applicable.get(0) : null;
        if (best == null) {
            boolean known = argumentTypes.stream().noneMatch(TypeChecker::isUnresolved);
            best = known ? mostSpecific(applicable) : null;
        }
        if (best == null) {
            Set<Class<?>> results = applicable.stream().map(Method::getReturnType).collect(Collectors.toSet());
            if (results.size() == 1 && applicable.stream().allMatch(m -> m.getTypeParameters().length == 0)) {
                return javaResult(applicable.get(0).getGenericReturnType(), receiverEnv);
            }
            return Type.ERROR;
        }
        TypeVariable<?>[] methodParams = best.getTypeParameters();
        if (methodParams.length == 0) {
            checkJavaArgumentWidening(best, receiverEnv, arguments, argumentTypes);
            java.lang.reflect.Type genericRet = best.getGenericReturnType();
            Type result = javaResult(genericRet, receiverEnv);
            if (genericRet instanceof TypeVariable<?> || genericRet instanceof WildcardType) {
                String desc = descriptor(result);
                if (desc != null) {
                    callChecks.put(ctx, desc);
                }
            }
            return result;
        }
        Map<TypeVariable<?>, Type.TypeVar> tvToSynthetic = new HashMap<>();
        for (int i = 0; i < methodParams.length; i++) {
            tvToSynthetic.put(methodParams[i], new Type.TypeVar(null, methodParams[i].getName(), i));
        }
        Set<Type.TypeVar> solving = Set.copyOf(tvToSynthetic.values());
        Map<TypeVariable<?>, Type> patternEnv = new HashMap<>(receiverEnv);
        patternEnv.putAll(tvToSynthetic);
        Map<Type.TypeVar, Type> inferred = new HashMap<>();
        java.lang.reflect.Type[] genericParams = best.getGenericParameterTypes();
        List<Type> paramPatterns = new ArrayList<>(arguments.size());
        List<BabbleParser.ExpressionContext> argExprs = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            Type paramPat = fromJavaType(genericParams[i], patternEnv, false, true);
            paramPatterns.add(paramPat);
            argExprs.add(arguments.get(i).expression());
            unify(paramPat, argumentTypes.get(i), solving, inferred, true);
        }
        Type ctxExpected = expectedType(ctx);
        if (ctxExpected != null && ctxExpected != Type.ERROR) {
            Map<Type.TypeVar, Type> retHints = new HashMap<>();
            Type retPat = fromJavaType(best.getGenericReturnType(), patternEnv, false, false);
            unifyReturn(retPat, ctxExpected, solving, retHints);
            applyDoubleWideningHint(inferred, retHints, paramPatterns, argExprs);
        }
        boolean argErrored = argumentTypes.stream().anyMatch(TypeChecker::isUnresolved);
        Map<TypeVariable<?>, Type> solvedEnv = new HashMap<>(receiverEnv);
        for (TypeVariable<?> tp : methodParams) {
            Type resolved = inferred.get(tvToSynthetic.get(tp));
            if (resolved == null) {
                if (!argErrored) {
                    warn("Missing type : cannot infer type arguments for " + description, ctx);
                }
                return Type.ERROR;
            }
            solvedEnv.put(tp, resolved);
        }
        for (TypeVariable<?> tp : methodParams) {
            Type resolved = solvedEnv.get(tp);
            if (!satisfiesJavaBounds(resolved, tp, solvedEnv)) {
                warn("Type error : type argument " + resolved + " does not satisfy bound " + tp.getName()
                        + " of " + description, ctx);
                return Type.ERROR;
            }
        }
        checkJavaArgumentWidening(best, solvedEnv, arguments, argumentTypes);
        java.lang.reflect.Type genericRet = best.getGenericReturnType();
        Type result = javaResult(genericRet, solvedEnv);
        if (genericRet instanceof TypeVariable<?> || genericRet instanceof WildcardType) {
            String desc = descriptor(result);
            if (desc != null) {
                callChecks.put(ctx, desc);
            }
        }
        return result;
    }

    private void checkJavaArgumentWidening(Executable candidate, Map<TypeVariable<?>, Type> env,
                                           List<BabbleParser.CallParameterContext> arguments, List<Type> argumentTypes) {
        java.lang.reflect.Type[] genericParams = candidate.getGenericParameterTypes();
        for (int i = 0; i < arguments.size(); i++) {
            if (i < argumentTypes.size()) {
                Type paramType = fromJavaType(genericParams[i], env, false, true);
                if (needsDoubleWidening(argumentTypes.get(i), paramType)) {
                    widened.add(arguments.get(i).expression());
                }
            }
        }
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
        List<Type.Parameter> result = new ArrayList<>();
        for (BabbleParser.ParameterDeclarationContext parameter : cls.parametersDeclaration().parameterDeclaration()) {
            Type type = parameterType(parameter, scope);
            if (parameter.defaultValue != null && !types.containsKey(parameter.defaultValue)) {
                Type actual = typeOf(parameter.defaultValue, type != Type.ERROR ? type : null);
                if (type != Type.ERROR) {
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
        if (!inFile(parameter) && pass.session != null) {
            Type frozen = pass.session.declarationTypes.get(parameter);
            if (frozen != null) {
                return frozen;
            }
        }
        if (declarationTypes.containsKey(parameter)) {
            return declarationTypes.get(parameter);
        }
        Type defType = null;
        if (parameter.defaultValue != null) {
            defType = typeOf(parameter.defaultValue);
            if (defType != Type.NULL && !isUnresolved(defType)) {
                declarationTypes.put(parameter, defType);
                return defType;
            }
        }
        if (parameter.defaultValue == null || defType == Type.NULL) {
            if (isInferrableCallable(ownerOfParam(parameter))) {
                p7Params.add(parameter);
                if (defType == Type.NULL) {
                    nextInferredParams.putIfAbsent(parameter, Type.NULL);
                }
                Type inferred = inferredParams.get(parameter);
                if (inferred != null) {
                    declarationTypes.put(parameter, inferred);
                    return inferred;
                }
            }
        }
        return Type.ERROR;
    }

    private static boolean isFunction(Type type) {
        if (type instanceof Type.Union union) {
            return union.members().stream().allMatch(TypeChecker::isFunction);
        }
        return type instanceof Type.Function || type instanceof Type.JavaMethod
                || type instanceof Type.ClassValue value && !value.node().isInterface()
                || type == Type.PRINT;
    }

    /** The argument binding rules of {@code Arguments}, applied to a known signature. */
    private void bind(List<Type.Parameter> parameters, List<BabbleParser.ParameterDeclarationContext> declarations,
                      List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext ctx) {
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
            BabbleParser.ParameterDeclarationContext decl = declarations != null && index < declarations.size()
                    ? declarations.get(index) : null;
            Type actual = typeOf(argument, parameter.type());
            if (decl != null && p7Params.contains(decl)) {
                Type inferredParam = inferredParams.get(decl);
                boolean substitutedClassTypeVar = inferredParam != null && containsTypeVar(inferredParam)
                        && !parameter.type().equals(inferredParam);
                if (substitutedClassTypeVar) {
                    expect(actual, parameter.type(), argument);
                    if (needsDoubleWidening(actual, parameter.type())) {
                        widened.add(argument);
                    }
                } else {
                    recordInferredParam(decl, actual, argument);
                    Type latest = inferredParams.get(decl);
                    if (latest != null && needsDoubleWidening(actual, latest)) {
                        widened.add(argument);
                    }
                }
            } else {
                expect(actual, parameter.type(), argument);
                if (needsDoubleWidening(actual, parameter.type())) {
                    widened.add(argument);
                }
            }
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
     * Overload resolution against the arguments' static types. Reports a call no overload
     * can accept, or that is ambiguous, and returns the applicable ones.
     */
    private <E extends Executable> List<E> javaCall(List<E> candidates, String description, Map<TypeVariable<?>, Type> receiverEnv,
                                                    List<BabbleParser.CallParameterContext> arguments, BabbleParser.CallContext ctx,
                                                    List<Type> outArgumentTypes) {
        List<E> matching = new ArrayList<>();
        for (E candidate : candidates) {
            if (candidate.getParameterCount() == arguments.size()) {
                matching.add(candidate);
            }
        }
        Type[] argTypes = new Type[arguments.size()];
        boolean[] deferred = new boolean[arguments.size()];
        for (int i = 0; i < arguments.size(); i++) {
            BabbleParser.ExpressionContext argument = arguments.get(i).expression();
            Type expected = expectedGenericJavaArgumentType(matching, i, argument, receiverEnv);
            if (needsDeferredLambdaTyping(argument, expected)) {
                deferred[i] = true;
                continue;
            }
            Type actual = typeOf(argument, expected);
            argTypes[i] = actual;
            final int index = i;
            matching.removeIf(candidate -> !applicableGeneric(candidate, index, actual, receiverEnv));
        }
        for (int i = 0; i < arguments.size(); i++) {
            if (!deferred[i]) {
                continue;
            }
            BabbleParser.ExpressionContext argument = arguments.get(i).expression();
            Map<TypeVariable<?>, Type> refinedEnv = refineJavaEnvFromArgs(matching, receiverEnv, argTypes, deferred, ctx);
            Type expected = expectedGenericJavaArgumentType(matching, i, argument, refinedEnv);
            Type actual = typeOf(argument, expected);
            argTypes[i] = actual;
            final int index = i;
            matching.removeIf(candidate -> !applicableGeneric(candidate, index, actual, refinedEnv));
        }
        outArgumentTypes.clear();
        for (Type t : argTypes) {
            outArgumentTypes.add(t);
        }
        if (matching.isEmpty() && !candidates.isEmpty()) {
            warn("No applicable overload of " + description + " for "
                    + outArgumentTypes.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")")), ctx);
        } else if (matching.size() > 1
                && outArgumentTypes.stream().noneMatch(TypeChecker::isUnresolved)
                && mostSpecific(matching) == null) {
            warn("Ambiguous call to " + description + " for "
                    + outArgumentTypes.stream().map(Type::toString).collect(Collectors.joining(", ", "(", ")")), ctx);
        }
        return matching;
    }

    private <E extends Executable> Map<TypeVariable<?>, Type> refineJavaEnvFromArgs(
            List<E> matching, Map<TypeVariable<?>, Type> receiverEnv, Type[] argTypes, boolean[] deferred,
            BabbleParser.CallContext ctx) {
        if (matching.size() != 1) {
            return receiverEnv;
        }
        E cand = matching.get(0);
        Map<TypeVariable<?>, Type.TypeVar> tvToSyn = candidateSolvingVars(cand);
        if (tvToSyn.isEmpty()) {
            return receiverEnv;
        }
        Set<Type.TypeVar> solving = Set.copyOf(tvToSyn.values());
        Map<TypeVariable<?>, Type> patternEnv = new HashMap<>(receiverEnv);
        patternEnv.putAll(tvToSyn);
        Map<Type.TypeVar, Type> inferred = new HashMap<>();
        java.lang.reflect.Type[] genericParams = cand.getGenericParameterTypes();
        for (int i = 0; i < argTypes.length; i++) {
            if (!deferred[i] && argTypes[i] != null) {
                Type paramPat = fromJavaType(genericParams[i], patternEnv, false, true);
                unify(paramPat, argTypes[i], solving, inferred, true);
            }
        }
        Type ctxExpected = expectedType(ctx);
        if (ctxExpected != null && ctxExpected != Type.ERROR) {
            Type retPat;
            if (cand instanceof Method m) {
                retPat = fromJavaType(m.getGenericReturnType(), patternEnv, false, false);
            } else {
                Class<?> cls = cand.getDeclaringClass();
                List<Type> clsVars = Arrays.stream(cls.getTypeParameters())
                        .map(tp -> (Type) tvToSyn.get(tp)).toList();
                retPat = new Type.JavaInstance(cls, clsVars);
            }
            unifyReturn(retPat, ctxExpected, solving, inferred);
        }
        Map<TypeVariable<?>, Type> refined = new HashMap<>(receiverEnv);
        tvToSyn.forEach((tv, syn) -> {
            if (inferred.containsKey(syn)) {
                refined.put(tv, inferred.get(syn));
            }
        });
        return refined;
    }

    private static Map<TypeVariable<?>, Type.TypeVar> candidateSolvingVars(Executable cand) {
        Map<TypeVariable<?>, Type.TypeVar> map = new HashMap<>();
        int idx = 0;
        if (cand instanceof java.lang.reflect.Constructor<?>) {
            for (TypeVariable<?> tp : cand.getDeclaringClass().getTypeParameters()) {
                map.put(tp, new Type.TypeVar(null, tp.getName(), idx++));
            }
        }
        for (TypeVariable<?> tp : cand.getTypeParameters()) {
            map.putIfAbsent(tp, new Type.TypeVar(null, tp.getName(), idx++));
        }
        return map;
    }

    private <E extends Executable> Type expectedGenericJavaArgumentType(
            List<E> candidates, int index, BabbleParser.ExpressionContext argument,
            Map<TypeVariable<?>, Type> receiverEnv) {
        if (candidates.isEmpty()) {
            return null;
        }
        Set<Type> paramTypes = new LinkedHashSet<>();
        for (E candidate : candidates) {
            java.lang.reflect.Type genericParam = candidate.getGenericParameterTypes()[index];
            Map<TypeVariable<?>, Type> env = new HashMap<>(receiverEnv);
            candidateSolvingVars(candidate).forEach(env::putIfAbsent);
            Type resolved = fromJavaType(genericParam, env, false, true);
            if (genericParam instanceof Class<?> raw && !raw.isPrimitive()) {
                resolved = Type.nullable(resolved);
            }
            paramTypes.add(resolved);
        }
        if (paramTypes.size() == 1) {
            return paramTypes.iterator().next();
        }
        if (unwrapFunctionLiteral(argument) != null) {
            Type samType = null;
            for (Type candidateType : paramTypes) {
                Type base = Type.withoutNull(candidateType);
                if (base instanceof Type.JavaInstance ji && JavaTypes.isFunctionalInterface(ji.type())) {
                    if (samType != null && !samType.equals(base)) {
                        return null;
                    }
                    samType = base;
                }
            }
            return samType;
        }
        return null;
    }

    private boolean applicableGeneric(Executable candidate, int index, Type argument, Map<TypeVariable<?>, Type> receiverEnv) {
        if (!applicable(candidate.getParameterTypes()[index], argument)) {
            return false;
        }
        if (isUnresolved(argument)) {
            return true;
        }
        java.lang.reflect.Type genericParam = candidate.getGenericParameterTypes()[index];
        if (genericParam instanceof Class<?>) {
            return true;
        }
        Map<TypeVariable<?>, Type.TypeVar> tvToSyn = candidateSolvingVars(candidate);
        Map<TypeVariable<?>, Type> env = new HashMap<>(receiverEnv);
        env.putAll(tvToSyn);
        Type paramPattern = fromJavaType(genericParam, env, false, true);
        if (!containsSyntheticTypeVar(paramPattern)) {
            return assignable(argument, paramPattern);
        }
        Set<Type.TypeVar> solving = Set.copyOf(tvToSyn.values());
        return unify(paramPattern, argument, solving, new HashMap<>(), true);
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
        if (isUnresolved(argument)) {
            return true;
        }
        if (argument instanceof Type.Union union) {
            return union.members().stream().allMatch(member -> applicable(parameter, member));
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
        if (op == BabbleLexer.EQ || op == BabbleLexer.NEQ) {
            return Type.BOOL;
        }
        boolean seeding = !inferenceStack.isEmpty() && recursiveFunctions.contains(inferenceStack.peek());
        String method = Operators.method(operator);
        if (method != null && left instanceof Type.Instance) {
            return operatorCall(left, method, rightAt, right, ctx, leftText);
        }
        if (method != null && left instanceof Type.Union union
                && !Type.containsNull(union)
                && union.members().stream().allMatch(m -> m instanceof Type.Instance)) {
            List<Type> results = new ArrayList<>(union.members().size());
            for (Type m : union.members()) {
                Type r = operatorCall(m, method, rightAt, right, ctx, leftText);
                if (r == Type.ERROR) {
                    return Type.ERROR;
                }
                results.add(r);
            }
            return Type.union(results);
        }
        switch (op) {
            case BabbleLexer.PLUS:
                if (left == Type.STRING) {
                    return Type.STRING;
                }
                if (isUnresolved(left) && !(seeding && left == Type.NOTHING && isNumber(right))) {
                    return left;
                }
                // Fall through : anything but a string on the left adds numbers.
            case BabbleLexer.MINUS:
            case BabbleLexer.MUL:
            case BabbleLexer.DIV:
                if (left == Type.ERROR) {
                    // Already failed : suppress cascading operator errors.
                    return Type.ERROR;
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
                if (!numbers || isUnresolved(left) || isUnresolved(right)) {
                    return Type.ERROR;
                }
                return arithmeticResult(left, right);
            default:
                if (!comparable(left, right)) {
                    warn("Not comparable : " + leftText + " and " + rightText, ctx);
                }
                return Type.BOOL;
        }
    }

    private static Type arithmeticResult(Type left, Type right) {
        List<Type> results = new ArrayList<>();
        for (Type l : Type.membersOf(left)) {
            for (Type r : Type.membersOf(right)) {
                results.add(l == Type.INT && r == Type.INT ? Type.INT : Type.DOUBLE);
            }
        }
        return Type.union(results);
    }

    private static final Type.Function INT_CONVERSION = new Type.Function(List.of(new Type.Parameter("value", Type.ANY, false)), Type.INT);
    private static final Type.Function DOUBLE_CONVERSION = new Type.Function(List.of(new Type.Parameter("value", Type.ANY, false)), Type.DOUBLE);
    private static final Type.Function ASSERT_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("c", Type.BOOL, false), new Type.Parameter("message", Type.NULLABLE_ANY, true)), Type.BOOL);
    private static final Type.Function SAME_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("a", Type.NULLABLE_ANY, false), new Type.Parameter("b", Type.NULLABLE_ANY, false)), Type.BOOL);
    private static final Type.Function CLASSOF_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("v", Type.NULLABLE_ANY, false)), Type.NULLABLE_ANY);
    private static final Type.Function IMPORT_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("name", Type.STRING, false)), Type.ANY);
    private static final Type STDOUT_TYPE = new Type.JavaInstance(java.io.PrintWriter.class);
    private static final Type ARGS_TYPE = new Type.JavaInstance(java.util.List.class, List.of(Type.STRING));

    /**
     * A name the analysis doesn't declare : a root Java package, a number conversion
     * ({@code int(x)}, {@code double(x)}), implicit signatures and {@code any} bindings.
     */
    private Type implicitType(String name) {
        return switch (name) {
            case "java", "javax" -> new Type.JavaPackage(name);
            case "int" -> INT_CONVERSION;
            case "double" -> DOUBLE_CONVERSION;
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
        if (type instanceof Type.Union union) {
            return union.members().stream().allMatch(TypeChecker::isNumber);
        }
        return type == Type.INT || type == Type.DOUBLE;
    }

    private static boolean isUnresolved(Type type) {
        return type == Type.ERROR || type == Type.NOTHING;
    }

    /** Whether an operand can be a number, reporting it when it can't. */
    private boolean number(Type type, BabbleParser.ExpressionContext operand) {
        return number(type, operand.getText(), operand);
    }

    private static boolean isNumeric(Type type) {
        if (type instanceof Type.Union union) {
            return union.members().stream().allMatch(TypeChecker::isNumeric);
        }
        return type == Type.INT || type == Type.DOUBLE
                || type instanceof Type.JavaInstance instance && Number.class.isAssignableFrom(instance.type());
    }

    private boolean number(Type type, String text, ParserRuleContext at) {
        if (isUnresolved(type) || isNumeric(type)) {
            return true;
        }
        warn("Not a number : " + text, at);
        return false;
    }

    /** {@code Operators.compare} : two numbers, or a Comparable and a value of its class. */
    private static boolean comparable(Type left, Type right) {
        if (isUnresolved(left) || isUnresolved(right)) {
            return true;
        }
        if (left instanceof Type.Union uLeft) {
            return uLeft.members().stream().allMatch(m -> comparable(m, right));
        }
        if (right instanceof Type.Union uRight) {
            return uRight.members().stream().allMatch(m -> comparable(left, m));
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
            if (!isUnresolved(callee) && !isFunction(callee)) {
                warn(receiverText + "." + method + " is not callable", at);
            }
            return Type.ERROR;
        }
        List<Type.Parameter> parameters = function.parameters();
        int given = argument == null ? 0 : 1;
        if (given < parameters.size() && !parameters.get(given).optional()) {
            warn("Missing parameter : " + parameters.get(given).name(), at);
        } else if (given > parameters.size()) {
            warn("Too many parameters : " + parameters.size() + " declared, " + given + " given", at);
        } else if (argument != null) {
            Type expectedParam = parameters.get(0).type();
            List<BabbleParser.ParameterDeclarationContext> decls = parameterDecls(function);
            BabbleParser.ParameterDeclarationContext decl = decls != null && !decls.isEmpty() ? decls.get(0) : null;
            if (decl != null && p7Params.contains(decl)) {
                Type inferredParam = inferredParams.get(decl);
                boolean substitutedClassTypeVar = inferredParam != null && containsTypeVar(inferredParam)
                        && !expectedParam.equals(inferredParam);
                if (substitutedClassTypeVar) {
                    expect(argumentType, expectedParam, argument);
                    if (needsDoubleWidening(argumentType, expectedParam)) {
                        widened.add(argument);
                    }
                } else {
                    recordInferredParam(decl, argumentType, argument);
                    if (inferredParam != null && needsDoubleWidening(argumentType, inferredParam)) {
                        widened.add(argument);
                    }
                }
            } else {
                expect(argumentType, expectedParam, argument);
                if (needsDoubleWidening(argumentType, expectedParam)) {
                    widened.add(argument);
                }
            }
        }
        return function.result();
    }

    // ---------------------------------------------------------------- generics & substitution

    private Map<Type.TypeVar, Type> instanceEnv(Type.Instance instance) {
        List<Type.TypeVar> params = typeVarsOf(instance.node());
        List<Type> args = instance.typeArguments();
        if (params.isEmpty() || args.isEmpty()) {
            return Map.of();
        }
        Map<Type.TypeVar, Type> env = new HashMap<>(params.size());
        for (int i = 0; i < Math.min(params.size(), args.size()); i++) {
            env.put(params.get(i), args.get(i));
        }
        return env;
    }

    private Type substitute(Type type, Map<Type.TypeVar, Type> env) {
        if (type == null || env.isEmpty()) {
            return type;
        }
        if (type instanceof Type.TypeVar tv) {
            return env.getOrDefault(tv, tv);
        }
        if (type instanceof Type.Union union) {
            List<Type> members = new ArrayList<>(union.members().size());
            for (Type member : union.members()) {
                members.add(substitute(member, env));
            }
            return Type.union(members);
        }
        if (type instanceof Type.Function fn) {
            Map<Type.TypeVar, Type> active = env;
            if (!fn.typeParameters().isEmpty()) {
                active = new HashMap<>(env);
                fn.typeParameters().forEach(active::remove);
                if (active.isEmpty()) {
                    return fn;
                }
            }
            List<Type.Parameter> params = new ArrayList<>(fn.parameters().size());
            for (Type.Parameter p : fn.parameters()) {
                params.add(new Type.Parameter(p.name(), substitute(p.type(), active), p.optional()));
            }
            Type.Function substituted = new Type.Function(fn.typeParameters(), params, substitute(fn.result(), active));
            BabbleParser.FunctionLiteralContext lit = functionLiterals.get(fn);
            if (lit != null) {
                functionLiterals.put(substituted, lit);
            }
            return substituted;
        }
        if (type instanceof Type.Instance inst) {
            if (inst.typeArguments().isEmpty()) {
                return inst;
            }
            List<Type> args = new ArrayList<>(inst.typeArguments().size());
            for (Type arg : inst.typeArguments()) {
                args.add(substitute(arg, env));
            }
            return new Type.Instance(inst.node(), args);
        }
        if (type instanceof Type.JavaInstance ji) {
            if (ji.typeArguments().isEmpty()) {
                return ji;
            }
            List<Type> args = new ArrayList<>(ji.typeArguments().size());
            for (Type arg : ji.typeArguments()) {
                args.add(substitute(arg, env));
            }
            return new Type.JavaInstance(ji.type(), args);
        }
        if (type instanceof Type.JavaArray ja) {
            return new Type.JavaArray(ja.javaClass(), substitute(ja.element(), env));
        }
        return type;
    }

    private Type projectToBabble(Type.Instance source, TypeNode targetNode) {
        if (source.node() == targetNode) {
            return source;
        }
        Map<Type.TypeVar, Type> env = instanceEnv(source);
        for (BabbleParser.TypeContext parentType : source.node().parentTypes) {
            Type parent = substitute(parentAnnotation(source.node(), parentType), env);
            if (parent instanceof Type.Instance parentInst) {
                Type projected = projectToBabble(parentInst, targetNode);
                if (projected != null) {
                    return projected;
                }
            }
        }
        return null;
    }

    private Type projectToJava(Type source, Class<?> targetClass) {
        if (targetClass == Object.class) {
            return Type.ANY;
        }
        if (source instanceof Type.JavaInstance ji) {
            if (ji.type() == targetClass) {
                return ji;
            }
            if (!targetClass.isAssignableFrom(ji.type())) {
                return null;
            }
            TypeVariable<?>[] targetParams = targetClass.getTypeParameters();
            if (targetParams.length == 0) {
                return fromJava(targetClass);
            }
            Map<TypeVariable<?>, Type> env = javaTypeEnv(ji.type(), ji.typeArguments());
            List<Type> args = new ArrayList<>(targetParams.length);
            for (TypeVariable<?> tp : targetParams) {
                args.add(env.getOrDefault(tp, Type.NULLABLE_ANY));
            }
            return new Type.JavaInstance(targetClass, args);
        }
        if (source instanceof Type.Instance inst) {
            Map<Type.TypeVar, Type> env = instanceEnv(inst);
            for (BabbleParser.TypeContext parentType : inst.node().parentTypes) {
                Type parent = substitute(parentAnnotation(inst.node(), parentType), env);
                Type projected = projectToJava(parent, targetClass);
                if (projected != null) {
                    return projected;
                }
            }
            return null;
        }
        if (source instanceof Type.Simple simple && simple.javaClass() != null) {
            return projectToJava(new Type.JavaInstance(simple.javaClass()), targetClass);
        }
        if (source instanceof Type.JavaArray array) {
            return targetClass.isAssignableFrom(array.javaClass()) ? fromJava(targetClass) : null;
        }
        return null;
    }

    private boolean unify(Type pattern, Type actual, Set<Type.TypeVar> solving, Map<Type.TypeVar, Type> inferred,
                          boolean allowNumericWidening) {
        if (pattern == Type.ERROR || actual == Type.ERROR || actual == Type.NOTHING) {
            return true;
        }
        if (actual instanceof Type.TypeVar actualTv && actualTv.owner() == null && !solving.contains(actualTv)) {
            return true;
        }
        if (pattern instanceof Type.TypeVar tv && solving.contains(tv)) {
            Type existing = inferred.get(tv);
            if (existing == null) {
                inferred.put(tv, actual);
                return true;
            }
            Type joined = allowNumericWidening ? join(existing, actual) : unifyJoinNoWidening(existing, actual);
            if (joined == null) {
                return false;
            }
            inferred.put(tv, joined);
            return true;
        }
        if (Type.containsNull(pattern)) {
            Type patternNonNull = Type.withoutNull(pattern);
            Type actualNonNull = Type.withoutNull(actual);
            if (actualNonNull == Type.NOTHING) {
                return true;
            }
            return unify(patternNonNull, actualNonNull, solving, inferred, allowNumericWidening);
        }
        if (actual instanceof Type.Union u && !(pattern instanceof Type.Union)) {
            for (Type member : u.members()) {
                if (!unify(pattern, member, solving, inferred, false)) {
                    return false;
                }
            }
            return true;
        }
        if (pattern instanceof Type.Instance pInst) {
            if (!(actual instanceof Type.Instance aInst)) {
                return false;
            }
            Type projected = projectToBabble(aInst, pInst.node());
            if (!(projected instanceof Type.Instance proj)) {
                return false;
            }
            int count = Math.min(pInst.typeArguments().size(), proj.typeArguments().size());
            for (int i = 0; i < count; i++) {
                if (!unify(pInst.typeArguments().get(i), proj.typeArguments().get(i), solving, inferred, false)) {
                    return false;
                }
            }
            return true;
        }
        if (pattern instanceof Type.JavaInstance pJava) {
            if (actual instanceof Type.Function aFn && JavaTypes.isFunctionalInterface(pJava.type())) {
                Type.Function sam = samFunctionType(pJava);
                return sam == null || unify(sam, aFn, solving, inferred, allowNumericWidening);
            }
            Type projected = projectToJava(actual, pJava.type());
            if (!(projected instanceof Type.JavaInstance proj)) {
                return false;
            }
            int count = Math.min(pJava.typeArguments().size(), proj.typeArguments().size());
            for (int i = 0; i < count; i++) {
                if (!unify(pJava.typeArguments().get(i), proj.typeArguments().get(i), solving, inferred, false)) {
                    return false;
                }
            }
            return true;
        }
        if (pattern instanceof Type.JavaArray pArr && actual instanceof Type.JavaArray aArr) {
            return unify(pArr.element(), aArr.element(), solving, inferred, false);
        }
        if (pattern instanceof Type.Function pFn && actual instanceof Type.Function aFn) {
            int count = Math.min(pFn.parameters().size(), aFn.parameters().size());
            for (int i = 0; i < count; i++) {
                if (!unify(pFn.parameters().get(i).type(), aFn.parameters().get(i).type(), solving, inferred, allowNumericWidening)) {
                    return false;
                }
            }
            return unify(pFn.result(), aFn.result(), solving, inferred, allowNumericWidening);
        }
        return true;
    }

    private static Type unifyJoinNoWidening(Type a, Type b) {
        if (a.equals(b)) {
            return a;
        }
        return Type.union(a, b);
    }

    private void unifyReturn(Type resultPattern, Type actualExpected, Set<Type.TypeVar> solving, Map<Type.TypeVar, Type> inferred) {
        if (resultPattern == null || actualExpected == null || actualExpected == Type.ERROR || resultPattern == Type.ERROR) {
            return;
        }
        Map<Type.TypeVar, Type> retInferred = new HashMap<>();
        unifyReturnSingle(resultPattern, actualExpected, solving, retInferred);
        retInferred.forEach(inferred::putIfAbsent);
    }

    private void unifyReturnSingle(Type pattern, Type expected, Set<Type.TypeVar> solving, Map<Type.TypeVar, Type> inferred) {
        if (pattern instanceof Type.TypeVar tv && solving.contains(tv)) {
            unify(pattern, expected, solving, inferred, false);
            return;
        }
        Type expBase = Type.containsNull(pattern) ? expected : Type.withoutNull(expected);
        if (expBase == Type.NOTHING || expBase instanceof Type.Union) {
            return;
        }
        if (pattern instanceof Type.Instance pInst && expBase instanceof Type.Instance eInst) {
            Type projected = pInst.node() == eInst.node() ? pInst : projectToBabble(pInst, eInst.node());
            if (projected instanceof Type.Instance) {
                unify(projected, eInst, solving, inferred, false);
            }
            return;
        }
        if (pattern instanceof Type.JavaInstance pJava && expBase instanceof Type.JavaInstance eJava) {
            Type projected = pJava.type() == eJava.type() ? pJava : projectToJava(pJava, eJava.type());
            if (projected instanceof Type.JavaInstance) {
                unify(projected, eJava, solving, inferred, false);
            }
            return;
        }
        if (pattern instanceof Type.JavaInstance pJava && expBase instanceof Type.Function eFn
                && JavaTypes.isFunctionalInterface(pJava.type())) {
            Type.Function sam = samFunctionType(pJava);
            if (sam != null) {
                unify(sam, eFn, solving, inferred, false);
            }
            return;
        }
        if (pattern instanceof Type.Function && expBase instanceof Type.Function) {
            unify(pattern, expBase, solving, inferred, false);
        }
    }

    // ---------------------------------------------------------------- assignability

    /** Covariant assignability inside parameterized containers (forbids implicit int -> double widening). */
    private boolean covariantAssignable(Type from, Type to) {
        return assignable(from, to, false);
    }

    /** Whether a value of type {@code from} can be used where {@code to} is declared. */
    boolean assignable(Type from, Type to) {
        return assignable(from, to, true);
    }

    private boolean assignable(Type from, Type to, boolean allowWidening) {
        if (from == Type.ERROR || to == Type.ERROR || from.equals(to) || from == Type.NOTHING) {
            return true;
        }
        if (from instanceof Type.Union union) {
            return union.members().stream().allMatch(member -> assignable(member, to, allowWidening));
        }
        if (from instanceof Type.TypeVar) {
            return to.equals(Type.NULLABLE_ANY)
                    || (to instanceof Type.Union union && union.members().stream().anyMatch(member -> assignable(from, member, allowWidening)));
        }
        if (to instanceof Type.Union union) {
            return union.members().stream().anyMatch(member -> assignable(from, member, allowWidening));
        }
        if (from == Type.NULL) {
            return to == Type.NULL;
        }
        if (to == Type.ANY) {
            return true;
        }
        if (allowWidening && from == Type.INT && to == Type.DOUBLE) {
            return true;
        }
        if (to instanceof Type.JavaInstance java) {
            Type projected = projectToJava(from, java.type());
            if (projected instanceof Type.JavaInstance proj) {
                if (proj.typeArguments().size() != java.typeArguments().size()) {
                    return java.type().isAssignableFrom(proj.type());
                }
                for (int i = 0; i < java.typeArguments().size(); i++) {
                    if (!covariantAssignable(proj.typeArguments().get(i), java.typeArguments().get(i))) {
                        return false;
                    }
                }
                return true;
            }
            if (projected != null) {
                return true;
            }
            if (from instanceof Type.Function fn && JavaTypes.isFunctionalInterface(java.type())) {
                return samFunctionAssignable(fn, java);
            }
            return isFunction(from) && !(from instanceof Type.Function) && JavaTypes.isFunctionalInterface(java.type());
        }
        if (to instanceof Type.JavaArray target) {
            return from instanceof Type.JavaArray array
                    && target.javaClass().isAssignableFrom(array.javaClass())
                    && covariantAssignable(array.element(), target.element());
        }
        if (to instanceof Type.Instance target) {
            if (!(from instanceof Type.Instance instance)) {
                return false;
            }
            Type projected = projectToBabble(instance, target.node());
            if (!(projected instanceof Type.Instance proj)) {
                return false;
            }
            if (proj.typeArguments().size() != target.typeArguments().size()) {
                return true;
            }
            for (int i = 0; i < target.typeArguments().size(); i++) {
                if (!covariantAssignable(proj.typeArguments().get(i), target.typeArguments().get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (to instanceof Type.Function target) {
            if (from instanceof Type.Function function) {
                return functionAssignable(function, target);
            }
            return isFunction(from);
        }
        return false;
    }

    private boolean samFunctionAssignable(Type.Function from, Type.JavaInstance java) {
        if (!from.typeParameters().isEmpty()) {
            return false;
        }
        Method samMethod = JavaTypes.functionalMethod(java.type());
        if (samMethod == null) {
            return false;
        }
        Type.Function sam = samFunctionType(java);
        if (sam == null) {
            return false;
        }
        if (samMethod.getReturnType() == void.class) {
            sam = new Type.Function(sam.parameters(), Type.NULLABLE_ANY);
        }
        return functionAssignable(from, sam);
    }

    private static boolean isErasedSubtype(Type from, Type to) {
        if (to == Type.ANY) {
            return true;
        }
        if (from instanceof Type.Instance f && to instanceof Type.Instance t) {
            return isSubtype(f.node(), t.node());
        }
        if (from instanceof Type.Instance f && to instanceof Type.JavaInstance t) {
            return implementsJava(f.node(), t.type());
        }
        if (to instanceof Type.JavaInstance t) {
            Class<?> fc = javaClassOf(from);
            return fc != null && t.type().isAssignableFrom(fc);
        }
        if (to instanceof Type.JavaArray t) {
            return from instanceof Type.JavaArray a && t.javaClass().isAssignableFrom(a.javaClass());
        }
        return false;
    }

    /** Whether {@code from as to} can succeed at runtime (static impossibility check for {@code as}). */
    private boolean canCast(Type from, Type to) {
        if (from == Type.ERROR || to == Type.ERROR || from == Type.NOTHING) {
            return true;
        }
        if (from instanceof Type.Union union) {
            return union.members().stream().anyMatch(member -> canCast(member, to));
        }
        if (to instanceof Type.Union union) {
            return union.members().stream().anyMatch(member -> canCast(from, member));
        }
        if (from == Type.NULL || to == Type.NULL) {
            return from == to;
        }
        if (from instanceof Type.TypeVar || to instanceof Type.TypeVar) {
            return true;
        }
        if (from == Type.ANY || to == Type.ANY) {
            return true;
        }
        if (from == Type.INT && to == Type.DOUBLE) {
            return true;
        }
        if (from == Type.DOUBLE && to == Type.INT) {
            return false;
        }
        if (assignable(from, to) || assignable(to, from)
                || isErasedSubtype(from, to) || isErasedSubtype(to, from)) {
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
    private boolean alwaysIs(Type from, Type to) {
        return from != Type.ERROR && to != Type.ERROR && from != Type.NOTHING && from != Type.NULL
                && !needsDoubleWidening(from, to)
                && assignable(from, to);
    }

    private boolean isRuntimeSubtype(Type from, Type to) {
        if (from == Type.ERROR || to == Type.ERROR || from.equals(to) || from == Type.NOTHING) {
            return true;
        }
        if (from instanceof Type.Union union) {
            return union.members().stream().allMatch(member -> isRuntimeSubtype(member, to));
        }
        if (from instanceof Type.TypeVar) {
            return to.equals(Type.NULLABLE_ANY)
                    || (to instanceof Type.Union union && union.members().stream().anyMatch(member -> isRuntimeSubtype(from, member)));
        }
        if (to instanceof Type.Union union) {
            return union.members().stream().anyMatch(member -> isRuntimeSubtype(from, member));
        }
        if (from == Type.NULL) {
            return to == Type.NULL;
        }
        if (to == Type.ANY) {
            return true;
        }
        if (to instanceof Type.Instance target) {
            if (!(from instanceof Type.Instance instance)) {
                return false;
            }
            Type projected = projectToBabble(instance, target.node());
            if (!(projected instanceof Type.Instance proj)) {
                return false;
            }
            if (proj.typeArguments().size() != target.typeArguments().size()) {
                return true;
            }
            for (int i = 0; i < target.typeArguments().size(); i++) {
                if (!covariantAssignable(proj.typeArguments().get(i), target.typeArguments().get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (to instanceof Type.JavaInstance java) {
            Type projected = projectToJava(from, java.type());
            if (projected instanceof Type.JavaInstance proj) {
                if (proj.typeArguments().size() != java.typeArguments().size()) {
                    return java.type().isAssignableFrom(proj.type());
                }
                for (int i = 0; i < java.typeArguments().size(); i++) {
                    if (!covariantAssignable(proj.typeArguments().get(i), java.typeArguments().get(i))) {
                        return false;
                    }
                }
                return true;
            }
            return projected != null;
        }
        if (to instanceof Type.JavaArray target) {
            return from instanceof Type.JavaArray array
                    && target.javaClass().isAssignableFrom(array.javaClass())
                    && covariantAssignable(array.element(), target.element());
        }
        return false;
    }

    private boolean canMatchAtRuntime(Type a, Type b) {
        if (a == Type.ERROR || b == Type.ERROR || a == Type.NOTHING || a.equals(b)) {
            return true;
        }
        if (a == Type.NULL || b == Type.NULL) {
            return a == b;
        }
        if (a instanceof Type.TypeVar || b instanceof Type.TypeVar) {
            return true;
        }
        if (a == Type.ANY || b == Type.ANY) {
            return true;
        }
        if (a instanceof Type.Union u) {
            return u.members().stream().anyMatch(m -> canMatchAtRuntime(m, b));
        }
        if (b instanceof Type.Union u) {
            return u.members().stream().anyMatch(m -> canMatchAtRuntime(a, m));
        }
        if (isRuntimeSubtype(a, b) || isRuntimeSubtype(b, a)
                || isErasedSubtype(a, b) || isErasedSubtype(b, a)) {
            return true;
        }
        if (a instanceof Type.Instance f && b instanceof Type.Instance t) {
            return f.node().isInterface() && t.node().isInterface();
        }
        if (a instanceof Type.Instance f && b instanceof Type.JavaInstance t) {
            return f.node().isInterface() && t.type().isInterface();
        }
        if (a instanceof Type.JavaInstance f && b instanceof Type.Instance t) {
            return f.type().isInterface() && t.node().isInterface();
        }
        Class<?> ca = javaClassOf(a);
        Class<?> cb = javaClassOf(b);
        if (ca != null && cb != null) {
            return (ca.isInterface() && cb.isInterface())
                    || (ca.isInterface() && !Modifier.isFinal(cb.getModifiers()))
                    || (cb.isInterface() && !Modifier.isFinal(ca.getModifiers()));
        }
        return false;
    }

    private Type narrowTarget(Type current, Type target) {
        List<Type> narrowed = new ArrayList<>();
        for (Type member : Type.membersOf(current)) {
            if (member == Type.NULL) {
                continue;
            }
            if (isRuntimeSubtype(member, target)) {
                narrowed.add(member);
            } else if (canMatchAtRuntime(member, target)) {
                narrowed.add(target);
            }
        }
        return narrowed.isEmpty() ? target : Type.union(narrowed);
    }

    private Type subtractType(Type current, Type target) {
        List<Type> remaining = new ArrayList<>();
        for (Type member : Type.membersOf(current)) {
            if (!isRuntimeSubtype(member, target)) {
                remaining.add(member);
            }
        }
        return remaining.isEmpty() ? Type.NOTHING : Type.union(remaining);
    }

    private static boolean needsDoubleWidening(Type from, Type to) {
        return from != Type.ERROR && to != Type.ERROR
                && containsType(from, Type.INT)
                && containsType(to, Type.DOUBLE)
                && !containsType(to, Type.INT);
    }

    private static boolean containsType(Type type, Type target) {
        return type.equals(target) || (type instanceof Type.Union union && union.members().contains(target));
    }

    private static boolean canBeNull(Type type) {
        if (type == Type.NULL || type instanceof Type.TypeVar) {
            return true;
        }
        return type instanceof Type.Union union && union.members().stream().anyMatch(TypeChecker::canBeNull);
    }

    private static boolean containsFunction(Type type) {
        if (type instanceof Type.Function) {
            return true;
        }
        return type instanceof Type.Union union && union.members().stream().anyMatch(TypeChecker::containsFunction);
    }

    private static boolean isIntOrDouble(Type type) {
        return Type.membersOf(type).stream().allMatch(m -> m == Type.INT || m == Type.DOUBLE || m == Type.NULL);
    }

    /**
     * A function can stand for another when it accepts the other's arguments by position,
     * at types at least as wide, needs no more of them, and returns a narrower result.
     * Parameter names aren't compared : a callback is normally called by position.
     */
    private boolean functionAssignable(Type.Function from, Type.Function to) {
        if (!from.typeParameters().isEmpty() || !to.typeParameters().isEmpty()) {
            return false;
        }
        if (from.parameters().size() < to.parameters().size()) {
            return false;
        }
        for (int i = 0; i < from.parameters().size(); i++) {
            Type.Parameter parameter = from.parameters().get(i);
            if (i < to.parameters().size()) {
                if (!assignable(to.parameters().get(i).type(), parameter.type(), true)) {
                    return false;
                }
            } else if (!parameter.optional()) {
                return false;
            }
        }
        return assignable(from.result(), to.result(), false);
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

    /** The type of a value that is one of two : {@code if} branches, a loop or null ; null when an error prevents joining. */
    private static Type join(Type a, Type b) {
        if (a == Type.ERROR || b == Type.ERROR) {
            return null;
        }
        if (a.equals(b) || b == Type.NOTHING) {
            return a;
        }
        if (a == Type.NOTHING) {
            return b;
        }
        if (isIntOrDouble(a) && isIntOrDouble(b) && (containsType(a, Type.DOUBLE) || containsType(b, Type.DOUBLE))
                && !(containsType(a, Type.INT) && containsType(a, Type.DOUBLE))
                && !(containsType(b, Type.INT) && containsType(b, Type.DOUBLE))) {
            return (Type.containsNull(a) || Type.containsNull(b)) ? Type.nullable(Type.DOUBLE) : Type.DOUBLE;
        }
        return Type.union(a, b);
    }

    /** Widening allowed across reassignments of an unannotated def or member ({@code int -> double} and {@code T <-> null}). */
    private Type reassignmentJoin(Type current, Type value) {
        if (current == Type.ERROR || value == Type.ERROR) {
            return null;
        }
        if (value == Type.NOTHING || current.equals(value)) {
            return current;
        }
        if (current == Type.NOTHING) {
            return value;
        }
        if (assignable(value, current)) {
            return current;
        }
        if (current == Type.NULL) {
            return Type.nullable(value);
        }
        if (value == Type.NULL) {
            return Type.nullable(current);
        }
        if (Type.withoutNull(current).equals(Type.withoutNull(value))) {
            return Type.union(current, value);
        }
        if (isIntOrDouble(current) && isIntOrDouble(value)
                && (containsType(current, Type.INT) || containsType(value, Type.INT))
                && (containsType(current, Type.DOUBLE) || containsType(value, Type.DOUBLE))
                && !(containsType(current, Type.INT) && containsType(current, Type.DOUBLE))
                && !(containsType(value, Type.INT) && containsType(value, Type.DOUBLE))) {
            return (Type.containsNull(current) || Type.containsNull(value)) ? Type.nullable(Type.DOUBLE) : Type.DOUBLE;
        }
        return null;
    }
}
