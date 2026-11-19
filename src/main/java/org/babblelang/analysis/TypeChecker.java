package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.Operators;

import static org.babblelang.analysis.TypeAlgebra.canBeNull;
import static org.babblelang.analysis.TypeAlgebra.containsFunction;
import static org.babblelang.analysis.TypeAlgebra.needsDoubleWidening;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The type checker (docs/design/bip008_static-typing.md) : the fourth pass of the analysis, after
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

    enum Role {
        VALUE,
        STATEMENT,
        CALLEE,
        SELECTOR_RECEIVER,
        TYPE_TEST_RIGHT;

        boolean isUsed() {
            return this != STATEMENT;
        }

        boolean isCallee() {
            return this == CALLEE;
        }

        boolean isClassSafe() {
            return this == CALLEE || this == SELECTOR_RECEIVER || this == TYPE_TEST_RIGHT;
        }
    }

    private record Diagnostic(int line, int column, String message) {
    }

    record Widening(Type type, int line) {
    }

    private static final Comparator<Diagnostic> ORDER = Comparator.comparingInt(Diagnostic::line)
            .thenComparingInt(Diagnostic::column).thenComparing(Diagnostic::message);

    private final BlockScopes pass;
    private final Map<ParserRuleContext, ScopeNode> occurrenceScopes = new HashMap<>();
    private final Set<ParserRuleContext> reassigned = new HashSet<>();
    private final Set<ParserRuleContext> capturedReassigned = new HashSet<>();
    private final Map<ParserRuleContext, Type> types = new HashMap<>();
    private final Map<ParserRuleContext, Type> declarationTypes = new HashMap<>();
    private final Set<ParserRuleContext> inProgress = new HashSet<>();
    private final Set<BabbleParser.FunctionLiteralContext> functions = new LinkedHashSet<>();
    private final Set<Diagnostic> diagnostics = new TreeSet<>(ORDER);
    private final Set<Diagnostic> effectDiagnostics = new TreeSet<>(ORDER);
    private final Set<Diagnostic> warnings = new TreeSet<>(ORDER);
    private final Map<ParserRuleContext, Integer> widened = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Object> resolvedLiterals = new IdentityHashMap<>();
    private final Map<ParserRuleContext, String> callChecks = new IdentityHashMap<>();
    private final Typer typer = new Typer();
    private final Map<BabbleParser.FunctionLiteralContext, Type> contextualReturnTypes = new IdentityHashMap<>();
    private final Map<BabbleParser.FunctionLiteralContext, List<BabbleParser.ReturnExpressionContext>> functionReturns = new IdentityHashMap<>();
    private final Deque<BabbleParser.FunctionLiteralContext> inferenceStack = new ArrayDeque<>();
    private final Set<BabbleParser.FunctionLiteralContext> inferringResult = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BabbleParser.FunctionLiteralContext> recursiveFunctions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<BabbleParser.FunctionLiteralContext, Type> provisionalResults = new IdentityHashMap<>();
    private TypeEnv env = TypeEnv.EMPTY;
    private final Map<ParserRuleContext, Type> inferredDefs = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> inferredMembers = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> inferredParams = new IdentityHashMap<>();


    private final Map<ParserRuleContext, Widening> defWidenings = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Widening> memberWidenings = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Widening> paramWidenings = new IdentityHashMap<>();
    private final Set<ParserRuleContext> escapingTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> nextEscapingTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> p7Params = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Type.Function, BabbleParser.FunctionLiteralContext> functionLiterals = new IdentityHashMap<>();
    private final Set<TypeNode> exportedTypeNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> failedDefs = Collections.newSetFromMap(new IdentityHashMap<>());

    private final CallSolver callSolver = new CallSolver(this);
    private final SlotInference slots = new SlotInference(this);
    private final TypeAnnotations annotator = new TypeAnnotations(this);
    private final MemberTypes members = new MemberTypes(this);
    private final OperatorTypes operators = new OperatorTypes(this);
    private final Narrowing narrowing = new Narrowing(this);
    private ImportedSummaries imported;

    CallSolver callSolver() {
        return callSolver;
    }

    EffectChecker effectChecker;
    private boolean checkingEffects;
    private BabbleParser.FileContext file;
    private TypedModuleSummary builtSummary;
    private final TypeAlgebra algebra = new TypeAlgebra(new TypeAlgebra.Env() {
        @Override
        public List<Type.TypeVar> typeParameters(TypeNode node) {
            return annotator.typeVarsOf(node);
        }

        @Override
        public Type parentType(TypeNode node, int index) {
            return annotator.parentAnnotation(node, index);
        }

        @Override
        public Type.Function samFunctionType(Type.JavaInstance java) {
            return JavaTypeBridge.samFunctionType(java, effectChecker);
        }

        @Override
        public boolean checkingEffects() {
            return checkingEffects;
        }

        @Override
        public boolean callableSatisfies(Type from, Type.Function target) {
            return effectChecker == null || effectChecker.callableSatisfies(from, target);
        }

        @Override
        public void derived(Type.Function from, Type.Function to) {
            BabbleParser.FunctionLiteralContext literal = functionLiterals.get(from);
            if (literal != null) {
                functionLiterals.put(to, literal);
            }
        }
    });

    TypeChecker(BlockScopes pass) {
        this.pass = pass;
    }

    TypeAlgebra algebra() {
        return algebra;
    }

    EffectChecker effectChecker() {
        return effectChecker;
    }

    Set<ParserRuleContext> p7Params() {
        return p7Params;
    }

    Map<ParserRuleContext, Type> inferredParams() {
        return inferredParams;
    }

    Map<ParserRuleContext, Integer> widened() {
        return widened;
    }

    Map<ParserRuleContext, Object> resolvedLiterals() {
        return resolvedLiterals;
    }

    Map<ParserRuleContext, String> callChecks() {
        return callChecks;
    }

    Map<ParserRuleContext, Type> types() {
        return types;
    }

    Map<Type.Function, BabbleParser.FunctionLiteralContext> functionLiterals() {
        return functionLiterals;
    }

    Map<ParserRuleContext, Type> declarationTypes() {
        return declarationTypes;
    }

    Map<ParserRuleContext, Type> inferredMembers() {
        return inferredMembers;
    }

    Map<BabbleParser.TypeContext, Type> annotations() {
        return annotator.annotations();
    }

    /** Whether the walk is seeding a self-recursive function's result (operators on its provisional result stay quiet). */
    boolean seedingRecursion() {
        return !inferenceStack.isEmpty() && recursiveFunctions.contains(inferenceStack.peek());
    }

    boolean checkingEffects() {
        return checkingEffects;
    }

    /** The summaries of the session's earlier inputs and of the loaded modules. */
    ImportedSummaries imported() {
        if (imported == null) {
            imported = new ImportedSummaries(pass);
        }
        return imported;
    }

    BlockScopes pass() {
        return pass;
    }

    /** The scope a name occurrence was recorded in, or null. */
    ScopeNode occurrenceScope(ParserRuleContext ctx) {
        return occurrenceScopes.get(ctx);
    }

    Map<ParserRuleContext, ScopeNode> occurrenceScopes() {
        return occurrenceScopes;
    }

    Set<ParserRuleContext> escapingTargets() {
        return escapingTargets;
    }

    Set<ParserRuleContext> nextEscapingTargets() {
        return nextEscapingTargets;
    }

    Set<ParserRuleContext> failedDefs() {
        return failedDefs;
    }

    Map<ParserRuleContext, Widening> widenings(int slots) {
        return slots == SlotInference.DEFS ? defWidenings : slots == SlotInference.MEMBERS ? memberWidenings : paramWidenings;
    }

    Map<ParserRuleContext, Type> inferred(int slots) {
        return slots == SlotInference.DEFS ? inferredDefs : slots == SlotInference.MEMBERS ? inferredMembers : inferredParams;
    }

    Narrowing narrowing() {
        return narrowing;
    }

    TypeEnv env() {
        return env;
    }

    Set<ParserRuleContext> capturedReassigned() {
        return capturedReassigned;
    }

    OperatorTypes operators() {
        return operators;
    }

    MemberTypes members() {
        return members;
    }

    TypeAnnotations annotator() {
        return annotator;
    }

    Set<BabbleParser.FunctionLiteralContext> functions() {
        return functions;
    }

    Map<ParserRuleContext, Type> inferredDefs() {
        return inferredDefs;
    }

    TypedModuleSummary summary() {
        return builtSummary;
    }

    void run(BabbleParser.FileContext file) {
        this.file = file;
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
        this.checkingEffects = false;
        this.effectChecker = new EffectChecker(pass, new TypedProgram(this));
        slots.rank(file);
        slots.converge(file);
        effectChecker.inferAll(file);
        // The function types the slots hold now carry their inferred effects.
        slots.converge(file);
        this.checkingEffects = true;
        resetPass(true);
        walk(file);
        for (BabbleParser.FunctionLiteralContext function : functions) {
            checkResult(function);
        }
        members.checkInterfaces();
        checkMissingTypes(file);
        effectChecker.verify(file);
        diagnostics.addAll(effectDiagnostics);
        for (Diagnostic warning : warnings) {
            pass.analysis.warn(warning.message(), warning.line(), warning.column());
        }
        for (Diagnostic diagnostic : diagnostics) {
            pass.analysis.typeDiagnostic(diagnostic.message(), diagnostic.line(), diagnostic.column());
        }
        LoweringAnalyzer.publish(pass, file, this);
        this.builtSummary = TypedModuleSummary.build(pass, this, new StaticRefs(pass, this));
    }

    private void resetPass(boolean clearAnnotations) {
        types.clear();
        declarationTypes.clear();
        if (clearAnnotations) {
            annotator.clear();
        }
        inProgress.clear();
        functions.clear();
        diagnostics.clear();
        warnings.clear();
        widened.clear();
        resolvedLiterals.clear();
        callChecks.clear();
        contextualReturnTypes.clear();
        inferenceStack.clear();
        inferringResult.clear();
        recursiveFunctions.clear();
        provisionalResults.clear();
        env = TypeEnv.EMPTY;
        nextEscapingTargets.clear();
        p7Params.clear();
        functionLiterals.clear();
        failedDefs.clear();
        callSolver.reset();
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

    void warn(String message, ParserRuleContext at) {
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

    /** The first note found in {@code tree}, innermost names first, then the parameters of a call's function. */
    private String findWideningNote(ParseTree tree, int diagnosticLine) {
        if (tree instanceof BabbleParser.SelectorContext selector) {
            String note = noteForName(selector, ScopeAnalysis.selectorName(selector), selector.expression(), diagnosticLine);
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
        if (tree instanceof BabbleParser.CallContext call && types.get(call.expression()) instanceof Type.Function fn
                && functionLiterals.get(fn) instanceof BabbleParser.FunctionLiteralContext lit) {
            for (BabbleParser.ParameterDeclarationContext param : lit.parametersDeclaration().parameterDeclaration()) {
                String note = wideningNote(param.ID().getText(), paramWidenings.get(param), null, diagnosticLine);
                if (note != null) {
                    return note;
                }
            }
        }
        return null;
    }

    private String assignmentWideningNote(BabbleParser.AssignExpressionContext assign, int diagnosticLine) {
        return noteForName(assign, ScopeAnalysis.assignName(assign), assign.namespace, diagnosticLine);
    }

    /**
     * Why {@code name} (a local, or a member of {@code receiver}) has a type wider than at the
     * line of the diagnostic : a later call widened the parameter, or a later assignment the slot.
     */
    private String noteForName(ParserRuleContext occurrence, String name, BabbleParser.ExpressionContext receiver, int diagnosticLine) {
        ParserRuleContext declaration;
        Map<ParserRuleContext, Widening> assignments;
        if (receiver == null) {
            ScopeNode scope = occurrenceScopes.get(occurrence);
            declaration = scope != null && resolveName(occurrence, name, scope) instanceof ParserRuleContext decl ? decl : null;
            assignments = defWidenings;
        } else {
            TypeNode.Member member = members.targetMember(types.get(receiver), name);
            declaration = member != null ? member.declaration : null;
            assignments = memberWidenings;
        }
        return declaration == null ? null
                : wideningNote(name, paramWidenings.get(declaration), assignments.get(declaration), diagnosticLine);
    }

    private static String wideningNote(String name, Widening call, Widening assignment, int diagnosticLine) {
        if (call != null && call.line() > diagnosticLine) {
            return " (" + name + " is " + call.type() + " because of the call at line " + call.line() + ")";
        }
        if (assignment != null && assignment.line() > diagnosticLine) {
            return " (" + name + " is " + assignment.type() + " because of the assignment at line " + assignment.line() + ")";
        }
        return null;
    }

    private void analysisWarn(String message, ParserRuleContext at) {
        if (!inFile(at)) {
            return;
        }
        warnings.add(new Diagnostic(at.getStart().getLine(), at.getStart().getCharPositionInLine() + 1, message));
    }

    boolean inFile(ParserRuleContext ctx) {
        ParserRuleContext root = ctx;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        return root == file;
    }

    /** Types every expression and block, and resolves every annotation, in source order. */
    void walk(ParseTree tree) {
        if (tree instanceof BabbleParser.TypeContext type) {
            // Only annotations are walked to : implements lists, super qualifiers, and as targets are handled elsewhere.
            if (type.getParent() instanceof BabbleParser.AsExpressionContext asCtx && asCtx.right == type) {
                return;
            }
            annotator.annotation(type, scopeOf(type));
            return;
        }
        if (tree instanceof BabbleParser.TypeListContext list) {
            for (BabbleParser.TypeContext parentType : list.type()) {
                annotator.annotation(parentType, scopeOf(parentType));
            }
            return;
        }
        if (tree instanceof BabbleParser.SuperExpressionContext sup) {
            if (sup.qualifier != null) {
                annotator.annotation(sup.qualifier, scopeOf(sup), true);
            }
            return;
        }
        if (tree instanceof BabbleParser.BlockContext block
                && block.getParent() instanceof BabbleParser.FunctionLiteralContext fn
                && fn.functionBlock == block) {
            TypeEnv saved = env;
            env = saved.enterCallable(reassigned);
            try {
                check(block, expectedReturnType(fn));
            } finally {
                env = saved;
            }
        } else if (tree instanceof BabbleParser.ExpressionContext expr && expr.getParent() instanceof BabbleParser.FileContext) {
            stmt(expr);
        } else if (tree instanceof BabbleParser.BlockContext block
                && (block.getParent() instanceof BabbleParser.ClassExpressionContext
                || block.getParent() instanceof BabbleParser.InterfaceExpressionContext
                || block.getParent() instanceof BabbleParser.ObjectExpressionContext)) {
            stmt(block);
        } else if (tree instanceof BabbleParser.ExpressionContext || tree instanceof BabbleParser.BlockContext) {
            synth((ParserRuleContext) tree);
        }
        if (tree instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.defaultValue != null && parameter.type() != null) {
            Type declared = annotator.annotation(parameter.type(), scopeOf(parameter));
            subsumes(parameter.defaultValue, null, check(parameter.defaultValue, declared), declared);
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

    static ScopeNode callableScope(ScopeNode scope) {
        ScopeNode s = scope;
        while (s != null && s.kind == ScopeNode.Kind.BLOCK) {
            s = s.parent;
        }
        return s;
    }

    // ---------------------------------------------------------------- scopes and names

    /** Whether a construct is part of what this pass analysed, rather than of an imported file. */
    boolean isLocal(ParserRuleContext ctx) {
        if (ctx == null) {
            return false;
        }
        for (ParserRuleContext p = ctx; p != null; p = p.getParent()) {
            if (pass.scopeNodes.containsKey(p) || pass.session != null && pass.session.scopeNodes.containsKey(p)) {
                return true;
            }
        }
        return false;
    }

    ScopeNode scopeOf(ParserRuleContext ctx) {
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
     * What a name refers to from a scope : a def or parameter declaration, or null for a
     * name the analysis doesn't know (the script's bindings and the implicits).
     */
    static Object resolveName(ParserRuleContext occurrence, String name, ScopeNode scope) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            ParserRuleContext declaration = s.names.get(name);
            if (declaration != null && !(occurrence != null && declaration instanceof BabbleParser.ParameterDeclarationContext parameter
                    && notBoundYet(occurrence, s, parameter))) {
                return declaration;
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
    Type typeOfDeclaration(ParserRuleContext declaration) {
        if (!isLocal(declaration)) {
            return Type.ERROR;
        }
        if (!inFile(declaration) && pass.session != null) {
            Type frozen = pass.session.declarationTypes.get(declaration);
            if (frozen != null) {
                return frozen;
            }
        }
        if (declaration instanceof BabbleParser.DefExpressionContext def && def.type() != null) {
            Type declared = annotator.declaredType(def);
            declarationTypes.put(declaration, declared);
            return declared;
        }
        if (declaration instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.type() != null) {
            Type declared = annotator.annotation(parameter.type(), scopeOf(parameter));
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
            slots.read(SlotInference.PARAMS, parameter);
            Type inferredParam = inferredParams.get(parameter);
            if (inferredParam != null && isInferrableCallable(ownerOfParam(parameter))) {
                declarationTypes.put(parameter, inferredParam);
                return inferredParam;
            }
        }
        slots.read(SlotInference.DEFS, declaration);
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
                    Type valueType = synth(def.value);
                    if (def.targetEffectPrefix() != null && effectChecker != null && valueType instanceof Type.Function fn) {
                        EffectSet override = effectChecker.defOverrideEffects(def);
                        if (override != null) {
                            valueType = fn.withEffects(override, fn.parameters());
                        }
                    }
                    type = inferred(valueType);
                }
            } else if (declaration instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.defaultValue != null) {
                Type valueType = synth(parameter.defaultValue);
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

    // ---------------------------------------------------------------- expressions

    Type synth(ParserRuleContext ctx) {
        return type(ctx, null, Role.VALUE);
    }

    Type synth(ParserRuleContext ctx, Role role) {
        return type(ctx, null, role);
    }

    Type check(ParserRuleContext ctx, Type expected) {
        return type(ctx, expected, Role.VALUE);
    }

    Type stmt(ParserRuleContext ctx) {
        return type(ctx, null, Role.STATEMENT);
    }

    private Type type(ParserRuleContext ctx, Type expected, Role role) {
        if (ctx == null || !isLocal(ctx)) {
            return Type.ERROR;
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
            Type type = typer.typeOf(ctx, expected != Type.ERROR ? expected : null, role);
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
    private Type.Function signature(BabbleParser.FunctionLiteralContext literal, Type expected) {
        applyContextualType(literal, expected);
        List<Type.Parameter> params = functionParameters(literal);
        typeDefaultValues(literal);
        Type result;
        if (literal.type() != null) {
            result = annotator.annotation(literal.type(), scopeOf(literal));
        } else if (provisionalResults.containsKey(literal)) {
            result = provisionalResults.get(literal);
        } else if (inferringResult.contains(literal)) {
            return recursiveSignature(literal);
        } else {
            result = inferFunctionResult(literal);
        }
        return recordFunctionLiteral(
                new Type.Function(annotator.typeVarsOf(owningDef(literal)), params, result, literalEffectSet(literal)), literal);
    }

    private Type.Function recursiveSignature(BabbleParser.FunctionLiteralContext literal) {
        List<Type.TypeVar> tparams = annotator.typeVarsOf(owningDef(literal));
        List<Type.Parameter> params = functionParameters(literal);
        EffectSet effs = literalEffectSet(literal);
        if (provisionalResults.containsKey(literal)) {
            return recordFunctionLiteral(new Type.Function(tparams, params, provisionalResults.get(literal), effs), literal);
        }
        if (!inferenceStack.isEmpty() && inferenceStack.peek() == literal) {
            recursiveFunctions.add(literal);
            Type result = slots.result(literal);
            if (result == null) {
                result = Type.NOTHING;
            }
            return recordFunctionLiteral(new Type.Function(tparams, params, result, effs), literal);
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
        return recordFunctionLiteral(new Type.Function(tparams, params, Type.ERROR, effs), literal);
    }

    private EffectSet literalEffectSet(BabbleParser.FunctionLiteralContext literal) {
        if (effectChecker == null) {
            return EffectSet.EMPTY;
        }
        EffectChecker.CallableSummary summary = effectChecker.visibleSummary(literal);
        if (summary != null) {
            return summary.effects();
        }
        if (!inFile(literal) && pass.session != null) {
            EffectChecker.CallableSummary remoteSummary = pass.session.callableSummaries.get(literal);
            if (remoteSummary != null) {
                return remoteSummary.effects();
            }
        }
        return effectChecker.parseFunctionPrefix(literal, checkingEffects).toEffectSet();
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

    static ParserRuleContext ownerOfParam(BabbleParser.ParameterDeclarationContext parameter) {
        ParserRuleContext parent = parameter.getParent();
        return parent != null ? parent.getParent() : null;
    }

    boolean isExported(BabbleParser.DefExpressionContext def) {
        return def != null && inFile(def) && def.EXPORT() != null;
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
                if (JavaTypeBridge.hasJavaMethod(javaIface, name)) {
                    return true;
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
        EffectChecker.CallableSummary summary = effectChecker != null ? effectChecker.visibleSummary(literal) : null;
        if (summary == null && !inFile(literal) && pass.session != null) {
            summary = pass.session.callableSummaries.get(literal);
        }
        if (effectChecker != null && checkingEffects && inFile(literal)) {
            effectChecker.parseFunctionPrefix(literal, true);
        }
        List<BabbleParser.ParameterDeclarationContext> decls = literal.parametersDeclaration().parameterDeclaration();
        List<Type.Parameter> result = new ArrayList<>(decls.size());
        for (int i = 0; i < decls.size(); i++) {
            BabbleParser.ParameterDeclarationContext parameter = decls.get(i);
            Type type = parameterType(parameter, scope);
            EffectChecker.ParamMods pm = effectChecker != null
                    ? effectChecker.parseParamMods(parameter, checkingEffects && inFile(literal))
                    : EffectChecker.ParamMods.NONE;
            boolean mut = summary != null && i < summary.mutParams().length ? summary.mutParams()[i] : pm.mut();
            boolean borrow = summary != null && i < summary.borrowParams().length ? summary.borrowParams()[i] : pm.borrow();
            result.add(new Type.Parameter(parameter.ID().getText(), type, parameter.defaultValue != null, mut, borrow, pm.borrow()));
        }
        return result;
    }

    private void typeDefaultValues(BabbleParser.FunctionLiteralContext literal) {
        ScopeNode scope = scopeOf(literal);
        for (BabbleParser.ParameterDeclarationContext parameter : literal.parametersDeclaration().parameterDeclaration()) {
            if (parameter.defaultValue != null) {
                Type expected = parameter.type() != null
                        ? annotator.annotation(parameter.type(), scope)
                        : declarationTypes.get(parameter);
                if (!types.containsKey(parameter.defaultValue)) {
                    Type actual = check(parameter.defaultValue, expected);
                    if (expected != null && expected != Type.ERROR) {
                        subsumes(parameter.defaultValue, null, actual, expected);
                    }
                } else if (expected != null && expected != Type.ERROR) {
                    subsumes(parameter.defaultValue, null, types.get(parameter.defaultValue), expected);
                }
            }
        }
    }

    private void applyContextualType(BabbleParser.FunctionLiteralContext literal, Type expected) {
        if (expected == null && literal.getParent() instanceof BabbleParser.DefExpressionContext def
                && def.value == literal && def.type() != null) {
            expected = annotator.declaredType(def);
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
            Type.Function sam = JavaTypeBridge.samFunctionType(java, effectChecker);
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
                    if (paramType != null && paramType != Type.ERROR && !paramType.hasSyntheticTypeVar()) {
                        declarationTypes.put(param, paramType);
                    }
                }
            }
        }
        if (literal.type() == null && expectedResult != null
                && expectedResult != Type.NULL && !isUnresolved(expectedResult)
                && !expectedResult.hasSyntheticTypeVar()) {
            contextualReturnTypes.putIfAbsent(literal, expectedResult);
        }
    }

    private Type expectedReturnType(BabbleParser.FunctionLiteralContext function) {
        if (function.type() != null) {
            return annotator.annotation(function.type(), scopeOf(function));
        }
        return contextualReturnTypes.get(function);
    }

    /**
     * The inferred result of an unannotated function. A self-recursive function reads its own
     * result from its result cell, NOTHING at first : while the slots converge, each evaluation
     * records the result it finds, waking the unit until the result is stable, and the final walk
     * types the body once against the converged result. A function whose result comes only from
     * its recursive calls has none.
     */
    private Type inferFunctionResult(BabbleParser.FunctionLiteralContext literal) {
        if (literal.functionBlock == null) {
            return Type.ERROR;
        }
        Type converged = slots.readResult(literal);
        boolean provisional = !slots.converging() && converged != null;
        if (provisional) {
            provisionalResults.put(literal, converged);
        }
        inferringResult.add(literal);
        inferenceStack.push(literal);
        TypeEnv savedEnv = env;
        env = savedEnv.enterCallable(reassigned);
        Type result;
        try {
            result = computeBodyResult(literal, contextualReturnTypes.get(literal));
        } finally {
            env = savedEnv;
            inferenceStack.pop();
            inferringResult.remove(literal);
            if (provisional) {
                provisionalResults.remove(literal);
            }
        }
        Type candidate = inferred(result);
        if (recursiveFunctions.remove(literal)) {
            if (candidate != Type.ERROR) {
                slots.recordResult(literal, candidate);
            } else if (result == Type.NOTHING) {
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
        Type bodyType = check(literal.functionBlock, expectedReturn);
        Type result = bodyType;
        List<BabbleParser.ReturnExpressionContext> returns = functionReturns.getOrDefault(literal, List.of());
        List<Type> returnTypes = new ArrayList<>(returns.size());
        for (BabbleParser.ReturnExpressionContext ret : returns) {
            Type retType = ret.expression() != null ? check(ret.expression(), expectedReturn) : Type.NULL;
            returnTypes.add(retType);
            Type next = algebra.join(result, retType);
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
            coerce(literal.functionBlock, bodyType, target);
            for (int i = 0; i < returns.size(); i++) {
                BabbleParser.ReturnExpressionContext ret = returns.get(i);
                if (ret.expression() != null) {
                    coerce(ret, returnTypes.get(i), target);
                }
            }
            if (expectedReturn != null && expectedReturn != Type.ERROR) {
                int targetRank = TypeAlgebra.numericWideningTargetRank(result, expectedReturn);
                if (targetRank >= 0) {
                    result = widenNumericType(result, targetRank);
                }
            }
        }
        return result;
    }

    private static Type widenNumericType(Type type, int targetRank) {
        Type target = Type.numericOfRank(targetRank);
        if (Type.isNumericScalar(type) && Type.numericRank(type) < targetRank) {
            return target;
        }
        if (type instanceof Type.Union union) {
            List<Type> updated = new ArrayList<>(union.members().size());
            for (Type member : union.members()) {
                updated.add(Type.isNumericScalar(member) && Type.numericRank(member) < targetRank ? target : member);
            }
            return Type.union(updated);
        }
        return type;
    }

    void clearSubtreeTypes(ParseTree tree) {
        if (tree instanceof ParserRuleContext ctx) {
            types.remove(ctx);
            contextualReturnTypes.remove(ctx);
            widened.remove(ctx);
            resolvedLiterals.remove(ctx);
            if (ctx instanceof BabbleParser.DefExpressionContext || ctx instanceof BabbleParser.ParameterDeclarationContext) {
                declarationTypes.remove(ctx);
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            clearSubtreeTypes(tree.getChild(i));
        }
    }

    static BabbleParser.FunctionLiteralContext unwrapFunctionLiteral(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            expr = block.block().expression(0);
        }
        return expr instanceof BabbleParser.FunctionLiteralContext literal ? literal : null;
    }

    boolean isGenericFunctionSelector(BabbleParser.SelectorContext selector) {
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

    /** A function's value, its last statement, must match its annotated result. */
    private void checkResult(BabbleParser.FunctionLiteralContext function) {
        if (function.type() == null) {
            return;
        }
        Type expected = annotator.annotation(function.type(), scopeOf(function));
        List<BabbleParser.ExpressionContext> statements = function.functionBlock.expression();
        if (statements.isEmpty()) {
            subsumes(function.functionBlock, null, Type.NULL, expected);
        } else {
            BabbleParser.ExpressionContext last = statements.get(statements.size() - 1);
            subsumes(last, function.functionBlock, synth(last), expected);
        }
    }

    void subsumes(ParserRuleContext site, Type actual, Type expected) {
        subsumes(site, site, actual, expected);
    }

    void subsumes(ParserRuleContext errorSite, ParserRuleContext widenSite, Type actual, Type expected) {
        if (actual != Type.ERROR && expected != Type.ERROR && !algebra.assignable(actual, expected)) {
            reportTypeMismatch(actual, expected, errorSite);
        }
        coerce(widenSite, actual, expected);
    }

    void subsumesBorrow(ParserRuleContext site, Type actual, Type expected) {
        if (actual != Type.ERROR && expected != Type.ERROR && !algebra.borrowAssignable(actual, expected)) {
            reportTypeMismatch(actual, expected, site);
        }
        coerce(site, actual, expected);
    }

    /**
     * The one coercion point : records that the value {@code site} produces is widened numerically
     * along {@code byte < short < int < long < float < double} where {@code target} is expected.
     */
    void coerce(ParserRuleContext site, Type actual, Type target) {
        int targetRank = TypeAlgebra.numericWideningTargetRank(actual, target);
        if (site != null && targetRank >= 0) {
            widened.merge(site, targetRank, Math::max);
        }
    }

    /** A value flowing into an unannotated def or member : recorded in its slot, and coerced to the slot's type. */
    private void flowInto(ParserRuleContext declaration, boolean member, Type value, ParserRuleContext errorSite, ParserRuleContext widenSite) {
        if (member) {
            recordInferredMember(declaration, value, errorSite);
        } else {
            recordInferredDef(declaration, value, errorSite);
        }
        Type inferred = (member ? inferredMembers : inferredDefs).get(declaration);
        if (inferred != null) {
            coerce(widenSite, value, inferred);
        }
    }

    void reportTypeMismatch(Type actual, Type expected, ParserRuleContext at) {
        if (actual instanceof Type.Function aFn && expected instanceof Type.Function eFn
                && algebra.assignableIgnoringEffects(aFn, eFn)) {
            warn("Type error : expected " + eFn.describeAgainst(eFn) + ", got " + aFn.describeAgainst(eFn), at);
        } else {
            warn("Type error : expected " + expected + ", got " + actual, at);
        }
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
        List<Type.TypeVar> vars = annotator.typeVarsOf(thisScope.type);
        return vars.isEmpty() ? new Type.Instance(thisScope.type)
                : new Type.Instance(thisScope.type, List.copyOf(vars));
    }

    private void recordInferredDef(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        slots.contribute(SlotInference.DEFS, decl, valueType, at);
    }

    private void recordInferredMember(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        slots.contribute(SlotInference.MEMBERS, decl, valueType, at);
    }

    void recordInferredParam(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        if (isUnresolved(valueType)) {
            return;
        }
        if (containsUnresolved(valueType)) {
            return;
        }
        if (hasForeignTypeVar(valueType, decl)) {
            Type currentInferred = inferredParams.get(decl);
            if (currentInferred != null) {
                subsumes(at, null, valueType, currentInferred);
            }
            return;
        }
        slots.contribute(SlotInference.PARAMS, decl, valueType, at);
    }

    private static boolean containsUnresolved(Type type) {
        return type == null || type.anyMatch(t -> isUnresolved(t) || (t instanceof Type.TypeVar tv && tv.owner() == null));
    }

    private boolean hasForeignTypeVar(Type type, ParserRuleContext decl) {
        return type != null && type.anyMatch(t -> t instanceof Type.TypeVar tv && !isTypeVarInScope(tv, decl));
    }

    private boolean isTypeVarInScope(Type.TypeVar tv, ParserRuleContext decl) {
        if (tv.owner() == null) {
            return false;
        }
        for (ParserRuleContext p = decl; p != null; p = p.getParent()) {
            if (p instanceof BabbleParser.DefExpressionContext def && annotator.symbolOf(def) == tv.owner()) {
                return true;
            }
            if (p.getParent() instanceof BabbleParser.ClassExpressionContext cls && cls.staticBlock == p) {
                BabbleParser.DefExpressionContext owningDef = owningDefExpr(cls);
                if (owningDef != null && annotator.symbolOf(owningDef) == tv.owner()) {
                    return false;
                }
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

    private void checkSelectorEscape(BabbleParser.SelectorContext ctx, Type result, Role role) {
        boolean callee = role.isCallee();
        boolean classSafe = role.isClassSafe();
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

    private sealed interface LValue {
        record Local(ParserRuleContext declaration, Type declaredType, Type currentType, boolean inferred) implements LValue {
        }

        record Member(BabbleParser.DefExpressionContext declaration, Type declaredType, Type currentType, boolean inferred) implements LValue {
        }

        record JavaField(JavaTypeBridge.AssignedField field, Type declaredType, Type currentType) implements LValue {
        }

        record Implicit(Type currentType) implements LValue {
        }
    }

    /** The expression rules, each typing its construct against an {@code expected} type (or null) in a {@code role}. */
    private final class Typer {
        Type typeOf(ParserRuleContext ctx, Type expected, Role role) {
            return switch (ctx) {
                case BabbleParser.IfExpressionContext c -> visitIfExpression(c, expected, role);
                case BabbleParser.DefExpressionContext c -> visitDefExpression(c, expected, role);
                case BabbleParser.WhileExpressionContext c -> visitWhileExpression(c, expected, role);
                case BabbleParser.ClassExpressionContext c -> visitClassExpression(c, expected, role);
                case BabbleParser.InterfaceExpressionContext c -> visitInterfaceExpression(c, expected, role);
                case BabbleParser.ObjectExpressionContext c -> visitObjectExpression(c, expected, role);
                case BabbleParser.ThisExpressionContext c -> visitThisExpression(c, expected, role);
                case BabbleParser.SuperExpressionContext c -> visitSuperExpression(c, expected, role);
                case BabbleParser.SelectorContext c -> visitSelector(c, expected, role);
                case BabbleParser.NonNullAssertionContext c -> visitNonNullAssertion(c, expected, role);
                case BabbleParser.CoreScalarRefContext c -> visitCoreScalarRef(c, expected, role);
                case BabbleParser.CoreCollectionCallContext c -> visitCoreCollectionCall(c, expected, role);
                case BabbleParser.TypeApplyCallContext c -> visitTypeApplyCall(c, expected, role);
                case BabbleParser.CallContext c -> visitCall(c, expected, role);
                case BabbleParser.BlockExpressionContext c -> visitBlockExpression(c, expected, role);
                case BabbleParser.BlockContext c -> visitBlock(c, expected, role);
                case BabbleParser.NegateContext c -> visitNegate(c, expected, role);
                case BabbleParser.BooleanNotContext c -> visitBooleanNot(c, expected, role);
                case BabbleParser.BinaryOpContext c -> visitBinaryOp(c, expected, role);
                case BabbleParser.IsExpressionContext c -> visitIsExpression(c, expected, role);
                case BabbleParser.AsExpressionContext c -> visitAsExpression(c, expected, role);
                case BabbleParser.BooleanOpContext c -> visitBooleanOp(c, expected, role);
                case BabbleParser.FunctionLiteralContext c -> visitFunctionLiteral(c, expected, role);
                case BabbleParser.AssignExpressionContext c -> visitAssignExpression(c, expected, role);
                case BabbleParser.NullContext c -> visitNull(c, expected, role);
                case BabbleParser.BooleanContext c -> visitBoolean(c, expected, role);
                case BabbleParser.RecurseContext c -> visitRecurse(c, expected, role);
                case BabbleParser.LongLiteralContext c -> visitLongLiteral(c, expected, role);
                case BabbleParser.IntegerContext c -> visitInteger(c, expected, role);
                case BabbleParser.FloatLiteralContext c -> visitFloatLiteral(c, expected, role);
                case BabbleParser.DoubleContext c -> visitDouble(c, expected, role);
                case BabbleParser.CharLiteralContext c -> visitCharLiteral(c, expected, role);
                case BabbleParser.StringContext c -> visitString(c, expected, role);
                case BabbleParser.ReturnExpressionContext c -> visitReturnExpression(c, expected, role);
                default -> {
                    Type last = null;
                    for (int i = 0; i < ctx.getChildCount(); i++) {
                        last = ctx.getChild(i) instanceof ParserRuleContext child ? typeOf(child, expected, role) : null;
                    }
                    yield last;
                }
            };
        }

        private Type visitIfExpression(BabbleParser.IfExpressionContext ctx, Type expected, Role role) {
            synth(ctx.test);
            TypeEnv.Refinement refinement = narrowing.extractRefinement(ctx.test);
            TypeEnv baseEnv = env;

            env = baseEnv.apply(refinement.whenTrue());
            Type thenType = type(ctx.thenBlock, expected, role);
            TypeEnv thenEnv = env;

            ParserRuleContext otherwise = ctx.elseBlock != null ? ctx.elseBlock : ctx.elseIf;
            Type elseType;
            TypeEnv elseEnv;
            if (otherwise != null) {
                env = baseEnv.apply(refinement.whenFalse());
                elseType = type(otherwise, expected, role);
                elseEnv = env;
            } else {
                elseType = Type.NULL;
                elseEnv = baseEnv;
            }
            env = TypeEnv.joinBranches(baseEnv, thenEnv, elseEnv);

            boolean used = role.isUsed();
            if (expected != null && expected != Type.ERROR && used) {
                subsumes(ctx.thenBlock, thenType, expected);
                if (otherwise != null) {
                    subsumes(otherwise, elseType, expected);
                } else if (!algebra.assignable(Type.NULL, expected)) {
                    warn("Type error : expected " + expected + ", got null", ctx);
                }
                return expected;
            }
            Type joined = algebra.join(thenType, elseType);
            if (joined == null) {
                if (!used) {
                    return Type.NULL;
                }
                if (!isUnresolved(thenType) && !isUnresolved(elseType)) {
                    warn("Type error : the branches of if have different types : " + thenType + " and " + elseType, ctx);
                }
                return Type.ERROR;
            }
            coerce(ctx.thenBlock, thenType, joined);
            coerce(otherwise, elseType, joined);
            return joined;
        }

        private Type visitDefExpression(BabbleParser.DefExpressionContext ctx, Type expected, Role role) {
            ScopeAnalysis.DefKind kind = pass.analysis.defTarget(ctx).kind();
            if (kind == ScopeAnalysis.DefKind.REQUIRED) {
                return Type.NULL;
            }
            Type declared = ctx.type() != null ? annotator.declaredType(ctx) : null;
            Type value = ctx.value != null ? check(ctx.value, declared) : Type.NULL;
            if (ctx.targetEffectPrefix() != null && effectChecker != null && value instanceof Type.Function fn) {
                EffectSet override = effectChecker.defOverrideEffects(ctx);
                if (override != null) {
                    value = fn.withEffects(override, fn.parameters());
                }
            }
            ParserRuleContext errorSite = ctx.value != null ? ctx.value : ctx;
            if (declared != null) {
                subsumes(errorSite, ctx, value, declared);
            } else {
                flowInto(ctx, kind == ScopeAnalysis.DefKind.MEMBER || kind == ScopeAnalysis.DefKind.STATIC, value, errorSite, ctx);
            }
            return value;
        }

        private Type visitWhileExpression(BabbleParser.WhileExpressionContext ctx, Type expected, Role role) {
            env = env.evictAll(narrowing.assignedLocals(ctx));
            synth(ctx.test);
            TypeEnv afterTest = env;
            TypeEnv.Refinement refinement = narrowing.extractRefinement(ctx.test);
            Type blockExpected = (expected == Type.ANY || expected == Type.NULLABLE_ANY) ? expected : null;
            env = afterTest.apply(refinement.whenTrue());
            Type bodyType = type(ctx.whileBlock, blockExpected, role);
            env = TypeEnv.joinBranches(afterTest, env, afterTest);
            boolean used = role.isUsed();
            if (expected != null && expected != Type.ERROR && used) {
                subsumes(ctx.whileBlock, bodyType, expected);
                if (!algebra.assignable(Type.NULL, expected)) {
                    warn("Type error : expected " + expected + ", got null", ctx);
                }
                return expected;
            }
            Type joined = algebra.join(bodyType, Type.NULL);
            if (joined == null) {
                if (!used) {
                    return Type.NULL;
                }
                if (!isUnresolved(bodyType)) {
                    warn("Type error : the branches of while have different types : " + bodyType + " and null", ctx);
                }
                return Type.ERROR;
            }
            coerce(ctx.whileBlock, bodyType, joined);
            return joined;
        }

        private Type visitClassExpression(BabbleParser.ClassExpressionContext ctx, Type expected, Role role) {
            if (ctx.parametersDeclaration() != null) {
                constructorParameters(pass.typeNodes.get(ctx));
            }
            return new Type.ClassValue(pass.typeNodes.get(ctx));
        }

        private Type visitInterfaceExpression(BabbleParser.InterfaceExpressionContext ctx, Type expected, Role role) {
            return new Type.ClassValue(pass.typeNodes.get(ctx));
        }

        private Type visitObjectExpression(BabbleParser.ObjectExpressionContext ctx, Type expected, Role role) {
            return new Type.Instance(pass.typeNodes.get(ctx));
        }

        private Type visitThisExpression(BabbleParser.ThisExpressionContext ctx, Type expected, Role role) {
            Type result = thisType(occurrenceScopes.get(ctx));
            if (result instanceof Type.ClassValue cv && cv.node().ctx instanceof BabbleParser.ClassExpressionContext cls
                    && inFile(cls) && !role.isClassSafe()) {
                nextEscapingTargets.add(cls);
            }
            return result;
        }

        private Type visitSuperExpression(BabbleParser.SuperExpressionContext ctx, Type expected, Role role) {
            return Type.ERROR;
        }

        private Type visitSelector(BabbleParser.SelectorContext ctx, Type expected, Role role) {
            Type result = selectUninstantiated(ctx);
            checkSelectorEscape(ctx, result, role);
            if (result instanceof Type.Function fn && !fn.typeParameters().isEmpty() && !role.isCallee()) {
                result = callSolver.instantiateFunctionValue(fn, ctx, expected);
            }
            if (ctx.safe != null && result != Type.ERROR) {
                return Type.nullable(result);
            }
            return result;
        }

        private Type selectUninstantiated(BabbleParser.SelectorContext ctx) {
            String name = ScopeAnalysis.selectorName(ctx);
            BabbleParser.ExpressionContext receiver = ctx.expression();
            if (receiver == null) {
                Object resolved = resolveName(ctx, name, occurrenceScopes.get(ctx));
                if (resolved instanceof ParserRuleContext declaration) {
                    Type narrowed = narrowing.narrowedType(declaration);
                    return narrowed != null ? narrowed : typeOfDeclaration(declaration);
                }
                return operators.implicitType(name);
            }
            if (receiver instanceof BabbleParser.SuperExpressionContext superExpr) {
                TypeChecks.Provider provider = pass.superProviders.get(superExpr);
                if (provider != null) {
                    Type selfType = thisType(occurrenceScopes.get(ctx));
                    if (provider.hasFunction() && provider.owner() instanceof TypeNode parentNode) {
                        TypeNode.Member defaultMember = parentNode.members.get(provider.name());
                        Type fnType = defaultMember != null ? memberType(parentNode, defaultMember) : Type.ERROR;
                        if (selfType instanceof Type.Instance selfInst) {
                            Type projected = algebra.projectToBabble(selfInst, parentNode);
                            if (projected instanceof Type.Instance projInst) {
                                return algebra.substitute(fnType, algebra.instanceEnv(projInst));
                            }
                        }
                        return fnType;
                    }
                    if (provider.owner() instanceof Class<?> javaInterface) {
                        Type projected = algebra.projectToJava(selfType, javaInterface);
                        List<Type> typeArgs = projected instanceof Type.JavaInstance ji ? ji.typeArguments() : List.of();
                        return new Type.JavaMethod(javaInterface, typeArgs, provider.name());
                    }
                }
                return Type.ERROR;
            }
            if (ctx.safe != null) {
                Type receiverType = synth(receiver, Role.SELECTOR_RECEIVER);
                if (isUnresolved(receiverType)) {
                    return Type.ERROR;
                }
                if (!canBeNull(receiverType)) {
                    warn("Not nullable : " + receiver.getText(), ctx);
                    return Type.ERROR;
                }
                Type target = receiverType == Type.NULL ? Type.NULL : Type.withoutNull(receiverType);
                return members.member(target, name, receiver instanceof BabbleParser.ThisExpressionContext, ctx, receiver);
            }
            return members.member(synth(receiver, Role.SELECTOR_RECEIVER), name, receiver instanceof BabbleParser.ThisExpressionContext, ctx, receiver);
        }

        private Type visitCall(BabbleParser.CallContext ctx, Type expected, Role role) {
            if (ctx.expression() instanceof BabbleParser.SelectorContext safeSelector && safeSelector.safe != null) {
                Type callee = typer.selectUninstantiated(safeSelector);
                types.put(safeSelector, callee == Type.ERROR ? Type.ERROR : Type.nullable(callee));
                Type result = callSolver.call(callee, ctx, expected, true);
                for (BabbleParser.CallParameterContext argument : ctx.callParameters().callParameter()) {
                    synth(argument.expression());
                }
                return result == Type.ERROR ? Type.ERROR : Type.nullable(result);
            }
            Type callee = synth(ctx.expression(), Role.CALLEE);
            ModuleInterface module = pass.modules.get(ctx);
            Type result;
            if (module != null) {
                for (BabbleParser.CallParameterContext argument : ctx.callParameters().callParameter()) {
                    synth(argument.expression());
                }
                result = new Type.Module(module);
            } else if (isUnshadowedImport(ctx.expression())
                    && ctx.callParameters().callParameter().size() == 1
                    && ScopeAnalysis.callParamName(ctx.callParameters().callParameter(0)) == null
                    && ctx.callParameters().callParameter(0).expression() instanceof BabbleParser.StringContext string) {
                synth(string);
                String target = org.babblelang.engine.impl.Interpreter.unescape(string.getText());
                result = target.endsWith(".ba") ? Type.ANY : new Type.JavaPackage(target);
            } else {
                result = callSolver.call(callee, ctx, expected, true);
                for (BabbleParser.CallParameterContext argument : ctx.callParameters().callParameter()) {
                    synth(argument.expression());
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

        private Type visitBlockExpression(BabbleParser.BlockExpressionContext ctx, Type expected, Role role) {
            Role blockRole = ctx.block().expression().size() == 1
                    ? role
                    : (role == Role.STATEMENT ? Role.STATEMENT : Role.VALUE);
            return type(ctx.block(), expected, blockRole);
        }

        private Type visitBlock(BabbleParser.BlockContext ctx, Type expected, Role role) {
            List<BabbleParser.ExpressionContext> statements = ctx.expression();
            if (statements.isEmpty()) {
                return Type.NULL;
            }
            TypeEnv blockEntryEnv = env;
            Type type = Type.NULL;
            try {
                for (int i = 0; i < statements.size(); i++) {
                    BabbleParser.ExpressionContext statement = statements.get(i);
                    type = i == statements.size() - 1 ? type(statement, expected, role) : stmt(statement);
                    if (i < statements.size() - 1 && statement instanceof BabbleParser.IfExpressionContext ifCtx) {
                        Type thenType = types.getOrDefault(ifCtx.thenBlock, Type.ERROR);
                        ParserRuleContext otherwise = ifCtx.elseBlock != null ? ifCtx.elseBlock : ifCtx.elseIf;
                        Type elseType = otherwise != null ? types.getOrDefault(otherwise, Type.ERROR) : Type.NULL;
                        if (thenType == Type.NOTHING && elseType != Type.NOTHING) {
                            env = env.apply(narrowing.extractRefinement(ifCtx.test).whenFalse());
                        } else if (elseType == Type.NOTHING && thenType != Type.NOTHING) {
                            env = env.apply(narrowing.extractRefinement(ifCtx.test).whenTrue());
                        }
                    }
                }
                return type;
            } finally {
                env = TypeEnv.joinBranches(blockEntryEnv, env, blockEntryEnv);
            }
        }

        /** As {@code Operators.negate} : byte/short/int -> int, long -> long, float -> float, double -> double; folds negated literals. */
        private Type visitNegate(BabbleParser.NegateContext ctx, Type expected, Role role) {
            if (ctx.operand instanceof BabbleParser.IntegerContext literal) {
                Type resolved = checkIntLiteral(ctx, literal.getText(), true, expected);
                types.put(literal, resolved);
                return resolved;
            }
            if (ctx.operand instanceof BabbleParser.LongLiteralContext literal) {
                Type resolved = checkLongLiteral(ctx, literal.getText(), true);
                types.put(literal, resolved);
                return resolved;
            }
            if (ctx.operand instanceof BabbleParser.DoubleContext literal) {
                Type resolved = checkDoubleLiteral(ctx, literal.getText(), true, expected);
                types.put(literal, resolved);
                return resolved;
            }
            if (ctx.operand instanceof BabbleParser.FloatLiteralContext literal) {
                Type resolved = checkFloatLiteral(ctx, literal.getText(), true);
                types.put(literal, resolved);
                return resolved;
            }
            Type operand = synth(ctx.operand);
            if (operand == Type.NOTHING) {
                return Type.NOTHING;
            }
            if (operand instanceof Type.Instance) {
                return operators.operatorCall(operand, Operators.NEGATE, null, null, ctx, ctx.operand.getText());
            }
            if (operand instanceof Type.Union union) {
                if (Type.containsNull(union)) {
                    operators.number(operand, ctx.operand);
                    return Type.ERROR;
                }
                boolean allNumericOrInstance = union.members().stream()
                        .allMatch(m -> m instanceof Type.Instance || operators.isNumeric(m));
                if (!allNumericOrInstance) {
                    operators.number(operand, ctx.operand);
                    return Type.ERROR;
                }
                List<Type> results = new ArrayList<>(union.members().size());
                for (Type m : union.members()) {
                    if (m instanceof Type.Instance) {
                        Type r = operators.operatorCall(m, Operators.NEGATE, null, null, ctx, ctx.operand.getText());
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
            if (!operators.number(operand, ctx.operand) || isUnresolved(operand)) {
                return Type.ERROR;
            }
            return OperatorTypes.negateResult(operand);
        }

        private Type visitBooleanNot(BabbleParser.BooleanNotContext ctx, Type expected, Role role) {
            synth(ctx.expression());
            return Type.BOOL;
        }

        private Type visitBinaryOp(BabbleParser.BinaryOpContext ctx, Type expected, Role role) {
            return operators.binaryOp(ctx, synth(ctx.left), synth(ctx.right));
        }

        private Type visitIsExpression(BabbleParser.IsExpressionContext ctx, Type expected, Role role) {
            synth(ctx.left);
            Type builtin = narrowing.isBuiltinTarget(ctx);
            if (builtin != null) {
                pass.analysis.setIsBuiltin(ctx, ctx.coreType.getText());
            } else if (ctx.coreType != null) {
                if (ctx.typeArguments() != null) {
                    warn("Cannot test erased type arguments with 'is' : use 'is " + ctx.coreType.getText() + "'", ctx.coreType);
                } else {
                    warn("Type error : cannot test 'is " + ctx.coreType.getText() + "'", ctx.coreType);
                }
            } else {
                synth(ctx.right, Role.TYPE_TEST_RIGHT);
            }
            return Type.BOOL;
        }

        private Type visitNonNullAssertion(BabbleParser.NonNullAssertionContext ctx, Type expected, Role role) {
            Type source = synth(ctx.expression());
            if (source == Type.ERROR || source == Type.NOTHING) {
                return source;
            }
            if (source == Type.NULL) {
                warn("Type error : expected any, got null", ctx.expression());
                return Type.ERROR;
            }
            if (!Type.containsNull(source)) {
                analysisWarn("Redundant non-null assertion : " + source, ctx);
                return source;
            }
            Type nonNull = Type.withoutNull(source);
            pass.analysis.setNonNullAssertionTarget(ctx, nonNull.toString());
            return nonNull;
        }

        private Type visitCoreScalarRef(BabbleParser.CoreScalarRefContext ctx, Type expected, Role role) {
            return operators.implicitType(ctx.coreScalarKeyword().getText());
        }

        private Type visitCoreCollectionCall(BabbleParser.CoreCollectionCallContext ctx, Type expected, Role role) {
            return callSolver.coreCollectionCall(ctx, expected);
        }

        private Type visitTypeApplyCall(BabbleParser.TypeApplyCallContext ctx, Type expected, Role role) {
            Type callee = synth(ctx.expression(), Role.CALLEE);
            boolean genericCallee = (callee instanceof Type.Function f && !f.typeParameters().isEmpty())
                    || (callee instanceof Type.ClassValue cv && !annotator.typeVarsOf(cv.node()).isEmpty())
                    || (callee instanceof Type.JavaClassValue jcv && jcv.type().getTypeParameters().length > 0)
                    || (callee instanceof Type.JavaMethod jm
                            && org.babblelang.runtime.JavaTypes.methods(jm.owner(), jm.name()).stream().anyMatch(m -> m.getTypeParameters().length > 0));
            boolean hasTypeOnlySyntax = false;
            for (BabbleParser.TypeContext t : ctx.typeArguments().type()) {
                BabbleParser.SimpleTypeContext simple = ScopeAnalysis.simpleType(t);
                if (simple == null || simple.coreTypeKeyword() != null || simple.typeArguments() != null) {
                    hasTypeOnlySyntax = true;
                    break;
                }
            }
            if (genericCallee || hasTypeOnlySyntax) {
                pass.analysis.markTypeApplyCall(ctx);
                return callSolver.typeApplyCall(ctx, callee, expected);
            }
            // Curried value call: f(a)(b) where f is a non-generic callable returning a callable.
            List<Type> firstArgTypes = new ArrayList<>();
            for (BabbleParser.TypeContext t : ctx.typeArguments().type()) {
                BabbleParser.SimpleTypeContext simple = ScopeAnalysis.simpleType(t);
                List<String> path = ScopeAnalysis.simpleTypePath(simple);
                Object resolved = resolveName(simple, path.get(0), occurrenceScopes.get(simple));
                Type argType;
                if (resolved instanceof ParserRuleContext decl) {
                    Type narrowed = narrowing.narrowedType(decl);
                    argType = narrowed != null ? narrowed : typeOfDeclaration(decl);
                } else {
                    argType = operators.implicitType(path.get(0));
                }
                for (int i = 1; i < path.size(); i++) {
                    argType = members.member(argType, path.get(i), false, simple, String.join(".", path.subList(0, i)));
                }
                firstArgTypes.add(argType);
            }
            Type intermediate = Type.ANY;
            if (callee instanceof Type.Function fn) {
                for (int i = 0; i < Math.min(fn.parameters().size(), firstArgTypes.size()); i++) {
                    subsumes(ctx.typeArguments().type(i), null, firstArgTypes.get(i), fn.parameters().get(i).type());
                }
                intermediate = fn.result();
            }
            for (BabbleParser.CallParameterContext arg : ctx.callParameters().callParameter()) {
                synth(arg.expression());
            }
            if (intermediate instanceof Type.Function fn2) {
                return fn2.result();
            }
            return Type.ANY;
        }

        private Type visitAsExpression(BabbleParser.AsExpressionContext ctx, Type expected, Role role) {
            Type source = synth(ctx.left);
            Type target = annotator.annotation(ctx.right, scopeOf(ctx), true);
            if (target == Type.ERROR) {
                return Type.ERROR;
            }
            if (target != null && target.hasTypeVar()) {
                warn("Cannot cast to type parameter : " + ctx.right.getText(), ctx.right);
                return Type.ERROR;
            }
            if (containsFunction(target)) {
                warn("Cannot cast to a function type", ctx.right);
                return target;
            }
            if (!algebra.canCast(source, target)) {
                warn("Type error : expected " + target + ", got " + source, ctx.left);
            } else if (algebra.alwaysIs(source, target)) {
                analysisWarn("Redundant cast : " + target, ctx);
            }
            pass.analysis.setCheckedType(ctx, LoweringAnalyzer.descriptor(target));
            return target;
        }

        private Type visitBooleanOp(BabbleParser.BooleanOpContext ctx, Type expected, Role role) {
            synth(ctx.left);
            TypeEnv saved = env;
            TypeEnv.Refinement leftRef = narrowing.extractRefinement(ctx.left);
            Map<ParserRuleContext, Type> rightGuard =
                    ctx.op.getType() == BabbleLexer.AND ? leftRef.whenTrue() : leftRef.whenFalse();
            env = saved.apply(rightGuard);
            try {
                synth(ctx.right);
            } finally {
                env = TypeEnv.joinBranches(saved, env, saved);
            }
            return Type.BOOL;
        }

        private Type visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx, Type expected, Role role) {
            if (inFile(ctx)) {
                functions.add(ctx);
            }
            Type.Function sig = signature(ctx, expected);
            types.put(ctx, sig);
            if (ctx.functionBlock != null && !types.containsKey(ctx.functionBlock)
                    && (ctx.type() == null || inferenceStack.isEmpty())) {
                TypeEnv saved = env;
                env = saved.enterCallable(reassigned);
                try {
                    check(ctx.functionBlock, expectedReturnType(ctx));
                } finally {
                    env = saved;
                }
            }
            return sig;
        }

        private LValue resolveLValue(BabbleParser.AssignExpressionContext ctx, boolean readCurrent) {
            String name = ScopeAnalysis.assignName(ctx);
            if (ctx.namespace == null) {
                Object resolved = resolveName(ctx, name, occurrenceScopes.get(ctx));
                if (resolved instanceof ParserRuleContext declaration) {
                    Type current = Type.ERROR;
                    if (readCurrent) {
                        Type narrowed = narrowing.narrowedType(declaration);
                        current = narrowed != null ? narrowed : typeOfDeclaration(declaration);
                    }
                    Type declared = declaration instanceof BabbleParser.ParameterDeclarationContext
                            ? typeOfDeclaration(declaration)
                            : annotator.declaredType(declaration);
                    return new LValue.Local(declaration, declared, current, declared == null);
                }
                return new LValue.Implicit(readCurrent ? operators.implicitType(name) : Type.ERROR);
            }
            Type receiver = synth(ctx.namespace, Role.SELECTOR_RECEIVER);
            Object field = JavaTypeBridge.assignedField(receiver, name);
            if (field instanceof String error) {
                warn(error, ctx);
            } else if (field instanceof JavaTypeBridge.AssignedField assigned) {
                Type declared = assigned.type(receiver, false, algebra);
                Type current = readCurrent ? assigned.type(receiver, true, algebra) : declared;
                return new LValue.JavaField(assigned, declared, current);
            }
            boolean own = ctx.namespace instanceof BabbleParser.ThisExpressionContext;
            Type declared = members.assignedMemberType(receiver, name, own, ctx);
            TypeNode.Member member = members.assignedBabbleMember(receiver, name, own);
            if (member != null && member.function != null && inFile(member.function)) {
                nextEscapingTargets.add(member.function);
            }
            Type current = Type.ERROR;
            if (readCurrent) {
                if (declared != null) {
                    current = declared;
                } else if (member != null) {
                    TypeNode ownerNode = receiver instanceof Type.Instance inst ? inst.node()
                            : (receiver instanceof Type.ClassValue cv ? cv.node() : null);
                    current = receiver instanceof Type.Instance inst
                            ? algebra.substitute(memberType(ownerNode, member), algebra.instanceEnv(inst))
                            : memberType(ownerNode, member);
                }
            }
            if (declared == null && member != null && member.function == null
                    && member.declaration instanceof BabbleParser.DefExpressionContext def
                    && isLocal(def) && inFile(def)) {
                return new LValue.Member(def, null, current, true);
            }
            return new LValue.Member(null, declared, current, false);
        }

        private void writeLValue(BabbleParser.AssignExpressionContext ctx, LValue target, Type value, ParserRuleContext errorSite) {
            switch (target) {
                case LValue.JavaField jf -> {
                    String error = jf.field().functionError(value, ScopeAnalysis.assignName(ctx));
                    if (error != null) {
                        warn(error, errorSite);
                    } else {
                        subsumes(errorSite, ctx, value, jf.declaredType());
                    }
                }
                case LValue.Local local -> {
                    if (local.declaredType() != null) {
                        subsumes(errorSite, ctx, value, local.declaredType());
                    } else if (local.inferred()) {
                        flowInto(local.declaration(), false, value, errorSite, ctx);
                    }
                    env = env.evict(local.declaration());
                }
                case LValue.Member member -> {
                    if (member.declaredType() != null) {
                        subsumes(errorSite, ctx, value, member.declaredType());
                    } else if (member.inferred() && member.declaration() != null) {
                        flowInto(member.declaration(), true, value, errorSite, ctx);
                    }
                }
                case LValue.Implicit ignored -> {
                }
            }
        }

        private Type visitAssignExpression(BabbleParser.AssignExpressionContext ctx, Type expected, Role role) {
            String operator = Operators.compound(ctx.op.getText());
            if (operator != null) {
                return compoundAssignment(ctx, operator);
            }
            LValue target = resolveLValue(ctx, false);
            Type declared = switch (target) {
                case LValue.JavaField jf -> jf.declaredType();
                case LValue.Local local -> local.declaredType();
                case LValue.Member member -> member.declaredType();
                case LValue.Implicit ignored -> null;
            };
            Type value = check(ctx.value, declared);
            writeLValue(ctx, target, value, ctx.value);
            return value;
        }

        /**
         * {@code x op= v} : typed as {@code x = x op v}, the operator's result checked against
         * what the target declares.
         */
        private Type compoundAssignment(BabbleParser.AssignExpressionContext ctx, String operator) {
            LValue target = resolveLValue(ctx, true);
            Type current = switch (target) {
                case LValue.JavaField jf -> jf.currentType();
                case LValue.Local local -> local.currentType();
                case LValue.Member member -> member.currentType();
                case LValue.Implicit imp -> imp.currentType();
            };
            Type value = synth(ctx.value);
            Type result = operators.binaryOp(operators.arithmeticToken(operator), operator, ctx, org.babblelang.engine.impl.Interpreter.targetText(ctx), ctx, current,
                    ctx.value.getText(), ctx.value, value);
            if (target instanceof LValue.JavaField jf) {
                subsumes(ctx, result, jf.declaredType());
            } else {
                writeLValue(ctx, target, result, ctx);
            }
            return result;
        }

        private Type visitNull(BabbleParser.NullContext ctx, Type expected, Role role) {
            return Type.NULL;
        }

        private Type visitBoolean(BabbleParser.BooleanContext ctx, Type expected, Role role) {
            return Type.BOOL;
        }

        private Type visitRecurse(BabbleParser.RecurseContext ctx, Type expected, Role role) {
            BabbleParser.FunctionLiteralContext function = enclosingFunction(ctx);
            if (function == null) {
                return Type.ERROR;
            }
            if (inFile(function) && !role.isCallee()) {
                nextEscapingTargets.add(function);
            }
            if (inferringResult.contains(function) || provisionalResults.containsKey(function)) {
                return recursiveSignature(function);
            }
            return signature(function, expected);
        }

        private Type contextualNumericTarget(Type expected) {
            if (expected == null || expected == Type.ERROR) {
                return null;
            }
            Type base = Type.withoutNull(expected);
            return Type.isNumericScalar(base) ? base : null;
        }

        private Type checkIntLiteral(ParserRuleContext ctx, String digits, boolean negated, Type expected) {
            String full = negated ? "-" + digits : digits;
            Type target = contextualNumericTarget(expected);
            if (target == Type.BYTE) {
                try {
                    resolvedLiterals.put(ctx, Byte.parseByte(full));
                } catch (NumberFormatException e) {
                    warn("Out of byte range : " + full, ctx);
                }
                return Type.BYTE;
            }
            if (target == Type.SHORT) {
                try {
                    resolvedLiterals.put(ctx, Short.parseShort(full));
                } catch (NumberFormatException e) {
                    warn("Out of short range : " + full, ctx);
                }
                return Type.SHORT;
            }
            if (target == Type.LONG) {
                try {
                    resolvedLiterals.put(ctx, Long.parseLong(full));
                } catch (NumberFormatException e) {
                    warn("Out of long range : " + full, ctx);
                }
                return Type.LONG;
            }
            try {
                int val = Integer.parseInt(full);
                if (negated) {
                    resolvedLiterals.put(ctx, val);
                }
            } catch (NumberFormatException e) {
                warn("Integer out of range : " + full, ctx);
            }
            return Type.INT;
        }

        private Type checkLongLiteral(ParserRuleContext ctx, String raw, boolean negated) {
            String digits = raw.substring(0, raw.length() - 1);
            String full = negated ? "-" + digits : digits;
            try {
                long val = Long.parseLong(full);
                if (negated) {
                    resolvedLiterals.put(ctx, val);
                }
            } catch (NumberFormatException e) {
                warn("Out of long range : " + (negated ? "-" : "") + raw, ctx);
            }
            return Type.LONG;
        }

        private Type checkFloatLiteral(ParserRuleContext ctx, String raw, boolean negated) {
            String digits = raw.substring(0, raw.length() - 1);
            String full = negated ? "-" + digits : digits;
            float val = Float.parseFloat(full);
            if (Float.isInfinite(val)) {
                warn("Out of float range : " + (negated ? "-" : "") + raw, ctx);
            } else if (negated) {
                resolvedLiterals.put(ctx, val);
            }
            return Type.FLOAT;
        }

        private Type checkDoubleLiteral(ParserRuleContext ctx, String raw, boolean negated, Type expected) {
            boolean explicitDouble = raw.endsWith("d") || raw.endsWith("D");
            String digits = explicitDouble ? raw.substring(0, raw.length() - 1) : raw;
            String full = negated ? "-" + digits : digits;
            if (!explicitDouble && contextualNumericTarget(expected) == Type.FLOAT) {
                float fv = Float.parseFloat(full);
                if (Float.isInfinite(fv)) {
                    warn("Out of float range : " + (negated ? "-" : "") + raw, ctx);
                } else {
                    resolvedLiterals.put(ctx, fv);
                }
                return Type.FLOAT;
            }
            double dv = Double.parseDouble(full);
            if (Double.isInfinite(dv)) {
                warn("Out of double range : " + (negated ? "-" : "") + raw, ctx);
            } else if (negated) {
                resolvedLiterals.put(ctx, dv);
            }
            return Type.DOUBLE;
        }

        private Type visitLongLiteral(BabbleParser.LongLiteralContext ctx, Type expected, Role role) {
            return checkLongLiteral(ctx, ctx.getText(), false);
        }

        private Type visitInteger(BabbleParser.IntegerContext ctx, Type expected, Role role) {
            return checkIntLiteral(ctx, ctx.getText(), false, expected);
        }

        private Type visitFloatLiteral(BabbleParser.FloatLiteralContext ctx, Type expected, Role role) {
            return checkFloatLiteral(ctx, ctx.getText(), false);
        }

        private Type visitDouble(BabbleParser.DoubleContext ctx, Type expected, Role role) {
            return checkDoubleLiteral(ctx, ctx.getText(), false, expected);
        }

        private Type visitCharLiteral(BabbleParser.CharLiteralContext ctx, Type expected, Role role) {
            return Type.CHAR;
        }

        private Type visitString(BabbleParser.StringContext ctx, Type expected, Role role) {
            return Type.STRING;
        }

        private Type visitReturnExpression(BabbleParser.ReturnExpressionContext ctx, Type expected, Role role) {
            BabbleParser.FunctionLiteralContext function = enclosingFunction(ctx);
            Type returnType = function != null ? expectedReturnType(function) : null;
            Type value = ctx.expression() != null ? check(ctx.expression(), returnType) : Type.NULL;
            if (function != null && function.type() != null) {
                Type declared = annotator.annotation(function.type(), scopeOf(function));
                subsumes(ctx.expression() != null ? ctx.expression() : ctx, ctx, value, declared);
            }
            return Type.NOTHING;
        }
    }

    // ---------------------------------------------------------------- members

    /** A member's annotation, method signature, or inferred type. */
    Type memberType(TypeNode owner, TypeNode.Member member) {
        if (!isLocal(member.declaration)) {
            Type imported = owner != null ? imported().memberType(owner, member.name) : null;
            return imported != null ? imported : Type.ERROR;
        }
        if (!inFile(member.declaration) && pass.session != null) {
            Type frozen = pass.session.memberTypes.get(member.declaration);
            if (frozen != null) {
                return frozen;
            }
        }
        Type declared = annotator.declaredType(member.declaration);
        if (declared != null) {
            return declared;
        }
        if (member.declaration instanceof BabbleParser.ParameterDeclarationContext) {
            return typeOfDeclaration(member.declaration);
        }
        if (member.function != null) {
            return synth(member.function);
        }
        if (member.declaration instanceof BabbleParser.DefExpressionContext def) {
            slots.read(SlotInference.MEMBERS, def);
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
                Type value = synth(def.value);
                return value == Type.NOTHING ? Type.ERROR : value;
            } finally {
                inProgress.remove(def);
            }
        }
        return Type.ERROR;
    }

    // ---------------------------------------------------------------- calls

    List<BabbleParser.ParameterDeclarationContext> parameterDecls(Type.Function fn) {
        BabbleParser.FunctionLiteralContext lit = functionLiterals.get(fn);
        return lit != null ? lit.parametersDeclaration().parameterDeclaration() : null;
    }

    static List<BabbleParser.ParameterDeclarationContext> parameterDecls(TypeNode node) {
        return node.ctx instanceof BabbleParser.ClassExpressionContext cls && cls.parametersDeclaration() != null
                ? cls.parametersDeclaration().parameterDeclaration() : null;
    }

    /** A class's constructor parameters, or null when they are another file's that can't be typed. */
    List<Type.Parameter> constructorParameters(TypeNode node) {
        if (node == null) {
            return List.of();
        }
        if (!(node.ctx instanceof BabbleParser.ClassExpressionContext cls) || !isLocal(cls)) {
            List<Type.Parameter> imported = imported().constructorParams(node);
            return imported != null ? imported : List.of();
        }
        if (cls.parametersDeclaration() == null) {
            return List.of();
        }
        ScopeNode scope = scopeOf(cls);
        EffectChecker.CallableSummary cs = (effectChecker != null && node != null) ? effectChecker.constructorSummary(node) : null;
        List<BabbleParser.ParameterDeclarationContext> decls = cls.parametersDeclaration().parameterDeclaration();
        List<Type.Parameter> result = new ArrayList<>(decls.size());
        for (int i = 0; i < decls.size(); i++) {
            BabbleParser.ParameterDeclarationContext parameter = decls.get(i);
            Type type = parameterType(parameter, scope);
            if (parameter.defaultValue != null && !types.containsKey(parameter.defaultValue)) {
                Type actual = check(parameter.defaultValue, type != Type.ERROR ? type : null);
                if (type != Type.ERROR) {
                    subsumes(parameter.defaultValue, null, actual, type);
                }
            }
            EffectChecker.ParamMods pm = effectChecker != null
                    ? effectChecker.parseParamMods(parameter, checkingEffects) : EffectChecker.ParamMods.NONE;
            boolean mut = cs != null && i < cs.mutParams().length ? cs.mutParams()[i] : pm.mut();
            boolean borrow = cs != null && i < cs.borrowParams().length ? cs.borrowParams()[i] : pm.borrow();
            result.add(new Type.Parameter(parameter.ID().getText(), type, parameter.defaultValue != null, mut, borrow, pm.borrow()));
        }
        return result;
    }

    private Type parameterType(BabbleParser.ParameterDeclarationContext parameter, ScopeNode scope) {
        if (parameter.type() != null) {
            return annotator.annotation(parameter.type(), scope);
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
            defType = synth(parameter.defaultValue);
            if (defType != Type.NULL && !isUnresolved(defType)) {
                declarationTypes.put(parameter, defType);
                return defType;
            }
        }
        if (parameter.defaultValue == null || defType == Type.NULL) {
            if (isInferrableCallable(ownerOfParam(parameter))) {
                p7Params.add(parameter);
                if (defType == Type.NULL) {
                    slots.contribute(SlotInference.PARAMS, parameter, Type.NULL, parameter);
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

    static boolean isUnresolved(Type type) {
        return type == Type.ERROR || type == Type.NOTHING;
    }

    void effectDiagnostic(String message, ParserRuleContext at) {
        if (!inFile(at)) {
            return;
        }
        effectDiagnostics.add(new Diagnostic(at.getStart().getLine(), at.getStart().getCharPositionInLine() + 1, message));
    }

    ScopeNode occurrenceScopeOf(ParserRuleContext ctx) {
        ScopeNode occ = occurrenceScopes.get(ctx);
        return occ != null ? occ : scopeOf(ctx);
    }

    boolean isReassigned(ParserRuleContext decl) {
        return reassigned.contains(decl);
    }

    Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects() {
        return effectChecker != null ? effectChecker.exportedJavaEffects() : Map.of();
    }

}
