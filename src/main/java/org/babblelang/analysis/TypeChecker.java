package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Operators;

import static org.babblelang.analysis.TypeAlgebra.canBeNull;
import static org.babblelang.analysis.TypeAlgebra.containsFunction;
import static org.babblelang.analysis.TypeAlgebra.needsDoubleWidening;

import java.lang.reflect.Method;
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
    private final Map<BabbleParser.FunctionLiteralContext, List<Type.TypeVar>> contextualTypeParams = new IdentityHashMap<>();
    record ForIterableInfo(
            ScopeAnalysis.ForKind kind,
            Class<?> arrayClass,
            Type elemType,
            Type.Instance babbleIterable,
            Type.Instance babbleIterator,
            Type.Function iteratorFn,
            Type.Function hasNextFn,
            Type.Function nextFn
    ) {
    }

    private static final class LoopContext {
        final Type expected;
        final List<BabbleParser.BreakExpressionContext> breaks = new ArrayList<>();
        final List<Type> breakTypes = new ArrayList<>();
        boolean hasBareBreak;

        LoopContext(Type expected) {
            this.expected = expected;
        }
    }

    private final Map<BabbleParser.ForExpressionContext, ForIterableInfo> forInfos = new IdentityHashMap<>();
    private final Deque<LoopContext> loopStack = new ArrayDeque<>();

    ForIterableInfo forIterableInfo(BabbleParser.ForExpressionContext ctx) {
        return forInfos.get(ctx);
    }

    private final Map<BabbleParser.FunctionLiteralContext, List<BabbleParser.ReturnExpressionContext>> functionReturns = new IdentityHashMap<>();
    private final Deque<BabbleParser.FunctionLiteralContext> inferenceStack = new ArrayDeque<>();
    private final Set<BabbleParser.FunctionLiteralContext> inferringResult = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BabbleParser.FunctionLiteralContext> recursiveFunctions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<BabbleParser.FunctionLiteralContext, Type> provisionalResults = new IdentityHashMap<>();
    private TypeEnv env = TypeEnv.EMPTY;
    private final Map<ParserRuleContext, Type> inferredDefs = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> inferredMembers = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> inferredParams = new IdentityHashMap<>();
    private final Map<BabbleParser.IsExpressionContext, Type> isTargetTypes = new IdentityHashMap<>();


    private final Map<ParserRuleContext, Widening> defWidenings = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Widening> memberWidenings = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Widening> paramWidenings = new IdentityHashMap<>();
    private final Set<ParserRuleContext> escapingTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> nextEscapingTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> p7Params = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Type.Function, BabbleParser.FunctionLiteralContext> functionLiterals = new IdentityHashMap<>();
    private final Set<TypeNode> exportedTypeNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> failedDefs = Collections.newSetFromMap(new IdentityHashMap<>());

    /** One branch of a statically verified union-split overload call (BIP021-D11 #4). */
    record ResolvedSplitBranch(List<ScopeAnalysis.OverloadSplitCheck> checks, int altIndex,
                               Type.Function altType, boolean exactSet,
                               BabbleParser.FunctionLiteralContext directTarget, Type resultType,
                               ScopeAnalysis.SpreadAdapt[] spreadAdapts, ScopeAnalysis.PrimitiveKind restKind) {
    }

    /** Resolution result for a call to an overload set (single alternative or union split). */
    record ResolvedOverloadCall(int altIndex, Type.Function altType, boolean exactSet,
                                BabbleParser.FunctionLiteralContext directTarget, Type resultType,
                                List<ResolvedSplitBranch> splitBranches) {
        boolean isSplit() {
            return splitBranches != null && !splitBranches.isEmpty();
        }
    }

    private final Map<BabbleParser.OverloadExpressionContext, List<BabbleParser.FunctionLiteralContext>> overloadAltLiterals = new IdentityHashMap<>();
    private final Map<ParserRuleContext, ScopeAnalysis.OverloadSelection> overloadSelections = new IdentityHashMap<>();
    private final Map<ParserRuleContext, ResolvedOverloadCall> resolvedOverloadCalls = new IdentityHashMap<>();
    private final Map<ParserRuleContext, ScopeAnalysis.JavaSamTarget> samTargets = new IdentityHashMap<>();
    private final Map<BabbleParser.ForExpressionContext, ScopeAnalysis.JavaSamTarget> forElemSamTargets = new IdentityHashMap<>();
    private final Map<BabbleParser.AsExpressionContext, ScopeAnalysis.JavaSamTarget> asSamTargets = new IdentityHashMap<>();
    private final List<Diagnostic> demoteDiagnostics = new ArrayList<>();
    private boolean needsReResolve;

    Map<ParserRuleContext, ScopeAnalysis.OverloadSelection> overloadSelections() {
        return overloadSelections;
    }

    Map<ParserRuleContext, ScopeAnalysis.JavaSamTarget> samTargets() {
        return samTargets;
    }

    Map<BabbleParser.ForExpressionContext, ScopeAnalysis.JavaSamTarget> forElemSamTargets() {
        return forElemSamTargets;
    }

    Map<BabbleParser.AsExpressionContext, ScopeAnalysis.JavaSamTarget> asSamTargets() {
        return asSamTargets;
    }

    Map<ParserRuleContext, ResolvedOverloadCall> resolvedOverloadCalls() {
        return resolvedOverloadCalls;
    }

    void recordResolvedOverloadCall(ParserRuleContext ctx, ResolvedOverloadCall call) {
        resolvedOverloadCalls.put(ctx, call);
    }

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
        public boolean isDeeplyImmutable(Type type) {
            return TypeChecker.this.isDeeplyImmutable(type, Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        @Override
        public void derived(Type.Function from, Type.Function to) {
            BabbleParser.FunctionLiteralContext literal = functionLiterals.get(from);
            if (literal != null) {
                functionLiterals.put(to, literal);
            }
        }
    });

    boolean isDeeplyImmutable(Type type) {
        return isDeeplyImmutable(type, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private boolean isDeeplyImmutable(Type type, Set<TypeNode> visiting) {
        if (type == null || Type.isCoreScalar(type) || type == Type.NULL || type instanceof Type.Frozen) {
            return true;
        }
        if (type instanceof Type.Union u) {
            return u.members().stream().allMatch(m -> isDeeplyImmutable(m, visiting));
        }
        if (type instanceof Type.JavaInstance ji) {
            boolean axiomaticImmutable = effectChecker != null
                    ? effectChecker.isJavaClassDeeplyImmutable(ji.type())
                    : BuiltinAxioms.current().isDeeplyImmutable(ji.type());
            if (axiomaticImmutable) {
                if (ji.type().getTypeParameters().length > 0 && ji.typeArguments().isEmpty()) {
                    return false;
                }
                return ji.typeArguments().isEmpty()
                        || ji.typeArguments().stream().allMatch(a -> isDeeplyImmutable(a, visiting));
            }
        }
        if (type instanceof Type.Instance inst && inst.node().kind == ClassShape.Kind.OBJECT) {
            for (TypeNode.Member member : inst.node().members.values()) {
                if (!member.isMethod() && !member.hasFunction) {
                    return false;
                }
            }
            return true;
        }
        if (type instanceof Type.Instance inst && inst.node().kind == ClassShape.Kind.VALUE_CLASS) {
            if (!visiting.add(inst.node())) {
                return true;
            }
            try {
                Map<Type.TypeVar, Type> env = algebra.instanceEnv(inst);
                for (TypeNode.Member member : inst.node().members.values()) {
                    if (member.isMethod() || member.hasFunction) {
                        continue;
                    }
                    Type mt = memberType(inst.node(), member);
                    if (mt == Type.ERROR) {
                        return false;
                    }
                    mt = algebra.substitute(mt, env);
                    if (!isDeeplyImmutable(mt, visiting)) {
                        return false;
                    }
                }
                return true;
            } finally {
                visiting.remove(inst.node());
            }
        }
        return false;
    }

    TypeChecker(BlockScopes pass) {
        this.pass = pass;
    }

    TypeAlgebra algebra() {
        return algebra;
    }

    EffectChecker effectChecker() {
        return effectChecker;
    }

    boolean hasDiagnosticIn(ParserRuleContext stmt) {
        if (stmt == null || stmt.getStart() == null || stmt.getStop() == null) {
            return false;
        }
        int startLine = stmt.getStart().getLine();
        int startCol = stmt.getStart().getCharPositionInLine() + 1;
        int endLine = stmt.getStop().getLine();
        int endCol = stmt.getStop().getCharPositionInLine() + Math.max(1, stmt.getStop().getText().length());
        for (Diagnostic d : diagnostics) {
            if ((d.line() > startLine || (d.line() == startLine && d.column() >= startCol))
                    && (d.line() < endLine || (d.line() == endLine && d.column() <= endCol))) {
                return true;
            }
        }
        return false;
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
        BuiltinAxioms.runWithCurrent(pass.hostEffects.builtinAxioms(), () -> runWithAxioms(file));
    }

    private void runWithAxioms(BabbleParser.FileContext file) {
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
        this.demoteDiagnostics.clear();
        this.needsReResolve = false;
        this.checkingEffects = false;
        this.effectChecker = new EffectChecker(pass, new TypedProgram(this));
        JavaNullability.runWithContext(effectChecker.localAxioms(), pass.hostEffects.bytecodeEffectAnalysis(), () -> {
            if (demoteSamMemberFields()) {
                pass.analysis.setResolution(new Resolver(pass).run());
            }
            slots.rank(file);
            slots.converge(file);
            effectChecker.inferAll(file);
            // The function types the slots hold now carry their inferred effects.
            slots.converge(file);
            this.checkingEffects = true;
            resetPass(true);
            diagnostics.addAll(demoteDiagnostics);
            demoteDiagnostics.clear();
            walk(file);
            diagnostics.addAll(demoteDiagnostics);
            demoteDiagnostics.clear();
            if (needsReResolve) {
                pass.analysis.setResolution(new Resolver(pass).run());
            }
            publishResultType(file);
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
            TailCalls.publish(pass.analysis, file, effectChecker::inferredNothrow, effectChecker::loopNothrow);
            this.builtSummary = TypedModuleSummary.build(pass, this, new StaticRefs(pass, this));
        });
    }

    /** The type of the file's last statement, for {@code :type} in the interactive mode (docs/design/bip004_repl.md §9). */
    private void publishResultType(BabbleParser.FileContext file) {
        List<BabbleParser.ExpressionContext> statements = file.expression();
        if (!statements.isEmpty()) {
            Type last = types.get(statements.getLast());
            if (last != null && last != Type.ERROR) {
                pass.analysis.setResultType(TypeDisplay.display(last));
            }
        }
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
        isTargetTypes.clear();
        forInfos.clear();
        loopStack.clear();
        warnings.clear();
        widened.clear();
        resolvedLiterals.clear();
        callChecks.clear();
        contextualReturnTypes.clear();
        contextualTypeParams.clear();
        inferenceStack.clear();
        inferringResult.clear();
        recursiveFunctions.clear();
        provisionalResults.clear();
        env = TypeEnv.EMPTY;
        nextEscapingTargets.clear();
        p7Params.clear();
        functionLiterals.clear();
        failedDefs.clear();
        overloadAltLiterals.clear();
        overloadSelections.clear();
        resolvedOverloadCalls.clear();
        samTargets.clear();
        forElemSamTargets.clear();
        asSamTargets.clear();
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
            for (BabbleParser.FnParamContext param : lit.fnParameters().fnParam()) {
                String note = wideningNote(param.ID().getText(), paramWidenings.get(param), null, diagnosticLine);
                if (note != null) {
                    return note;
                }
            }
        }
        return null;
    }

    private String assignmentWideningNote(BabbleParser.AssignExpressionContext assign, int diagnosticLine) {
        return noteForName(assign, ScopeAnalysis.assignName(assign), ScopeAnalysis.assignReceiver(assign), diagnosticLine);
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

    void analysisWarn(String message, ParserRuleContext at) {
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
        if (tree instanceof BabbleParser.AxiomExpressionContext axiom) {
            synth(axiom);
            return;
        }
        if (tree instanceof BabbleParser.TypeContext type) {
            // Only annotations are walked to : implements lists, super qualifiers, and is/as targets are handled elsewhere.
            if (type.getParent() instanceof BabbleParser.AsExpressionContext asCtx && asCtx.right == type
                    || type.getParent() instanceof BabbleParser.IsExpressionContext isCtx && isCtx.right == type) {
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
        if (tree instanceof ParserRuleContext parameter && ScopeAnalysis.isParamDecl(parameter)
                && ScopeAnalysis.paramDefault(parameter) != null && ScopeAnalysis.paramType(parameter) != null) {
            Type declared = annotator.annotation(ScopeAnalysis.paramType(parameter), scopeOf(parameter));
            BabbleParser.ExpressionContext defVal = ScopeAnalysis.paramDefault(parameter);
            Type actual = check(defVal, declared);
            subsumes(defVal, null, actual, declared);
            coerceSam(defVal, actual, declared);
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

            private void checkParam(ParserRuleContext parameter) {
                Type type = declarationTypes.get(parameter);
                if (ScopeAnalysis.paramType(parameter) == null && (type == null || isUnresolved(type)) && !failedDefs.contains(parameter)) {
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
        return isTargetTypes.get(ctx);
    }

    Type resolveIsTarget(BabbleParser.IsExpressionContext ctx) {
        if (isTargetTypes.containsKey(ctx)) {
            return isTargetTypes.get(ctx);
        }
        BabbleParser.SimpleTypeContext singleSimple = null;
        if (ctx.right.nullableType().size() == 1
                && ctx.right.nullableType(0) instanceof BabbleParser.NonParenTypeContext nonParen
                && nonParen.nonParenNullableType() instanceof BabbleParser.SimpleTypeContext simple) {
            singleSimple = simple;
            if ("void".equals(simple.typeName().getText())) {
                warn("Type error : cannot test 'is void'", simple);
                isTargetTypes.put(ctx, Type.ERROR);
                return Type.ERROR;
            }
            if (simple.OF() != null && !"array".equals(simple.typeName().getText())
                    && !annotator.resolvesToArrayAlias(simple, scopeOf(ctx))) {
                warn("Cannot test erased type arguments with 'is' : use 'is " + simple.typeName().getText() + "'", simple);
                isTargetTypes.put(ctx, Type.ERROR);
                return Type.ERROR;
            }
        }
        if (singleSimple != null && singleSimple.frozen != null) {
            warn("Cannot test 'frozen' with 'is'", singleSimple);
            isTargetTypes.put(ctx, Type.ERROR);
            return Type.ERROR;
        }
        Type target = annotator.annotation(ctx.right, scopeOf(ctx), true);
        if (target == Type.ERROR) {
            isTargetTypes.put(ctx, Type.ERROR);
            return Type.ERROR;
        }
        if (target.anyMatch(Type.Frozen.class::isInstance)) {
            warn("Cannot test 'frozen' with 'is'", ctx.right);
            isTargetTypes.put(ctx, Type.ERROR);
            return Type.ERROR;
        }
        if (target.hasTypeVar()) {
            warn("Cannot test type parameter with 'is' : " + target, ctx.right);
            isTargetTypes.put(ctx, Type.ERROR);
            return Type.ERROR;
        }
        if (containsFunction(target)) {
            warn("Cannot test a function type with 'is'", ctx.right);
            isTargetTypes.put(ctx, Type.ERROR);
            return Type.ERROR;
        }
        if (singleSimple != null && singleSimple.typeName().coreTypeKeyword() != null && singleSimple.question == null
                && singleSimple.OF() == null) {
            pass.analysis.setIsBuiltin(ctx, singleSimple.typeName().getText());
        }
        pass.analysis.setCheckedType(ctx, LoweringAnalyzer.descriptor(target));
        isTargetTypes.put(ctx, target);
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
        if (target == null || target == Type.ERROR || target == Type.NOTHING || target == Type.NULL) {
            return false;
        }
        return switch (target) {
            case Type.Array a -> isArrayTypeErasedAtRuntime(a);
            case Type.Union u -> u.members().stream().anyMatch(TypeChecker::containsErasedArray);
            case Type.Intersection i -> i.members().stream().anyMatch(TypeChecker::containsErasedArray);
            case Type.Frozen f -> containsErasedArray(f.of());
            default -> false;
        };
    }

    private boolean isUncheckedArrayCheck(Type source, Type target) {
        if (!containsErasedArray(target)) {
            return false;
        }
        for (Type m : TypeAlgebra.alternatives(source)) {
            if (m != Type.NULL && algebra.overlapsErased(m, target) && !algebra.assignable(m, target)) {
                return true;
            }
        }
        return false;
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

    private final Map<BabbleParser.AxiomExpressionContext, Type.Function> babbleAxiomTypes = new IdentityHashMap<>();

    /** Resolves a scoped Babble-name axiom ({@code axiom foo: fn ...}) in {@code scope} or an enclosing scope. */
    Type.Function resolveBabbleAxiom(String name, ScopeNode scope) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            BabbleParser.AxiomExpressionContext ax = s.babbleAxioms.get(name);
            if (ax != null) {
                return parseBabbleAxiom(ax, s, false);
            }
            if (s.names.containsKey(name)) {
                return null;
            }
        }
        return null;
    }

    private Type.Function parseBabbleAxiom(BabbleParser.AxiomExpressionContext ctx, ScopeNode scope, boolean reportErrors) {
        if (!AxiomData.isBabbleNameAxiom(ctx)) {
            return null;
        }
        if (!reportErrors && babbleAxiomTypes.containsKey(ctx)) {
            return babbleAxiomTypes.get(ctx);
        }
        AxiomData.ErrorSink sink = reportErrors ? this::warn : (msg, at) -> {};
        Type.Function fn = AxiomData.parseBabbleAxiomFunction(ctx, t -> annotator.annotation(t, scope), sink);
        if (fn != null) {
            babbleAxiomTypes.put(ctx, fn);
        }
        return fn;
    }

    /**
     * What a name refers to from a scope : a def or parameter declaration, or null for a
     * name the analysis doesn't know (the script's bindings and the implicits).
     */
    static Object resolveName(ParserRuleContext occurrence, String name, ScopeNode scope) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            ParserRuleContext declaration = s.names.get(name);
            if (declaration != null && !(occurrence != null && ScopeAnalysis.isParamDecl(declaration)
                    && notBoundYet(occurrence, s, declaration))) {
                return declaration;
            }
        }
        return null;
    }

    /** A parameter default doesn't see the parameters from its own onwards (as in Resolver). */
    private static boolean notBoundYet(ParserRuleContext occurrence, ScopeNode scope, ParserRuleContext declaration) {
        for (ParserRuleContext p = occurrence; p != null && p != scope.owner; p = p.getParent()) {
            if (p instanceof BabbleParser.FunctionLiteralContext) {
                return false;
            }
            if (ScopeAnalysis.isParamDecl(p) && p.getParent().getParent() == scope.owner) {
                List<? extends ParserRuleContext> parameters = p instanceof BabbleParser.ClassParamContext
                        ? ((BabbleParser.ClassParametersContext) p.getParent()).classParam()
                        : ((BabbleParser.FnParametersContext) p.getParent()).fnParam();
                return parameters.indexOf(p) <= parameters.indexOf(declaration);
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
        if (ScopeAnalysis.isParamDecl(declaration) && ScopeAnalysis.paramType(declaration) != null) {
            Type declared = annotator.annotation(ScopeAnalysis.paramType(declaration), scopeOf(declaration));
            if (ScopeAnalysis.isParamRest(declaration) && declared != Type.ERROR) {
                if (ScopeAnalysis.isParamRestNullable(declaration)) {
                    declared = Type.nullable(declared);
                }
                declared = new Type.JavaInstance(Iterable.class, List.of(declared));
            }
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
        if (ScopeAnalysis.isParamDecl(declaration)) {
            slots.read(SlotInference.PARAMS, declaration);
            Type inferredParam = inferredParams.get(declaration);
            if (inferredParam != null && isInferrableCallable(ownerOfParam(declaration))) {
                declarationTypes.put(declaration, inferredParam);
                return inferredParam;
            }
        }
        slots.read(SlotInference.DEFS, declaration);
        if (inferredDefs.containsKey(declaration)
                && !(declaration instanceof BabbleParser.DefExpressionContext def
                && !reassigned.contains(def) && (unwrapFunctionLiteral(def.value) != null
                || def.value instanceof BabbleParser.OverloadExpressionContext))) {
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
                if (def.value instanceof BabbleParser.OverloadExpressionContext ov) {
                    Type ovCached = types.get(ov);
                    if (ovCached != null) {
                        return ovCached;
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
                    if (def.effectClause() != null && effectChecker != null && valueType instanceof Type.Function fn) {
                        EffectSet override = effectChecker.defOverrideEffects(def);
                        if (override != null) {
                            valueType = fn.withEffects(override, fn.parameters());
                        }
                    }
                    type = inferred(valueType);
                }
            } else if (ScopeAnalysis.isParamDecl(declaration) && ScopeAnalysis.paramDefault(declaration) != null) {
                Type valueType = synth(ScopeAnalysis.paramDefault(declaration));
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
        List<Type.TypeVar> tparams = effectiveTypeParams(literal);
        return recordFunctionLiteral(
                new Type.Function(tparams, params, result, literalEffectSet(literal),
                        TypeAnnotations.resolveForwardedParams(literal.effectPrefix(), params),
                        TypeAnnotations.resolveCapturedCallings(literal.effectPrefix(), params).keySet()), literal);
    }

    private List<Type.TypeVar> effectiveTypeParams(BabbleParser.FunctionLiteralContext literal) {
        List<Type.TypeVar> declared = annotator.typeVarsOf(literal);
        if (!declared.isEmpty()) {
            return declared;
        }
        return contextualTypeParams.getOrDefault(literal, List.of());
    }

    private Type.Function recursiveSignature(BabbleParser.FunctionLiteralContext literal) {
        List<Type.TypeVar> tparams = effectiveTypeParams(literal);
        List<Type.Parameter> params = functionParameters(literal);
        EffectSet effs = literalEffectSet(literal);
        Set<Integer> forwarded = TypeAnnotations.resolveForwardedParams(literal.effectPrefix(), params);
        Set<String> captured = TypeAnnotations.resolveCapturedCallings(literal.effectPrefix(), params).keySet();
        if (provisionalResults.containsKey(literal)) {
            return recordFunctionLiteral(new Type.Function(tparams, params, provisionalResults.get(literal), effs, forwarded, captured), literal);
        }
        if (!inferenceStack.isEmpty() && inferenceStack.peek() == literal) {
            recursiveFunctions.add(literal);
            Type result = slots.result(literal);
            if (result == null) {
                result = Type.NOTHING;
            }
            return recordFunctionLiteral(new Type.Function(tparams, params, result, effs, forwarded, captured), literal);
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
        return recordFunctionLiteral(new Type.Function(tparams, params, Type.ERROR, effs, forwarded, captured), literal);
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

    static ParserRuleContext ownerOfParam(ParserRuleContext parameter) {
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
        boolean untyped = effectChecker != null && effectChecker.isCompletelyUntyped(literal);
        List<BabbleParser.FnParamContext> decls = literal.fnParameters().fnParam();
        int kwStart = ScopeAnalysis.keywordOnlyStart(literal.fnParameters());
        List<Type.Parameter> result = new ArrayList<>(decls.size());
        for (int i = 0; i < decls.size(); i++) {
            BabbleParser.FnParamContext parameter = decls.get(i);
            boolean rest = ScopeAnalysis.isParamRest(parameter);
            Type type = rest ? restElementType(parameter, scope) : parameterType(parameter, scope);
            EffectChecker.ParamMods pm = effectChecker != null
                    ? effectChecker.parseParamMods(parameter, checkingEffects && inFile(literal))
                    : EffectChecker.ParamMods.NONE;
            boolean mut = summary != null && i < summary.mutParams().length ? summary.mutParams()[i] : pm.mut();
            boolean borrow = summary != null && i < summary.borrowParams().length
                    ? summary.borrowParams()[i]
                    : (effectChecker != null && !untyped && !pm.shared() && !pm.unique());
            boolean unique = summary != null && i < summary.uniqueParams().length ? summary.uniqueParams()[i] : pm.unique();
            result.add(new Type.Parameter(parameter.ID().getText(), type, parameter.defaultValue != null,
                    i >= kwStart, rest, mut, borrow, pm.shared(), unique));
        }
        return result;
    }

    private Type restElementType(BabbleParser.FnParamContext parameter, ScopeNode scope) {
        Type elem = annotator.annotation(parameter.type(), scope);
        if (elem != Type.ERROR && ScopeAnalysis.isParamRestNullable(parameter)) {
            elem = Type.nullable(elem);
        }
        return elem;
    }

    private void typeDefaultValues(BabbleParser.FunctionLiteralContext literal) {
        ScopeNode scope = scopeOf(literal);
        for (BabbleParser.FnParamContext parameter : literal.fnParameters().fnParam()) {
            if (parameter.defaultValue != null) {
                Type expected = parameter.type() != null
                        ? annotator.annotation(parameter.type(), scope)
                        : declarationTypes.get(parameter);
                if (!types.containsKey(parameter.defaultValue)) {
                    Type actual = check(parameter.defaultValue, expected);
                    if (expected != null && expected != Type.ERROR) {
                        subsumes(parameter.defaultValue, null, actual, expected);
                        coerceSam(parameter.defaultValue, actual, expected);
                    }
                } else if (expected != null && expected != Type.ERROR) {
                    Type actual = types.get(parameter.defaultValue);
                    subsumes(parameter.defaultValue, null, actual, expected);
                    coerceSam(parameter.defaultValue, actual, expected);
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
        } else if (JavaTypeBridge.resolveJavaSam(base) instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
            Type.Function sam = JavaTypeBridge.samFunctionType(valid.samType(), effectChecker);
            if (sam != null) {
                expectedParamTypes = sam.parameters().stream().map(Type.Parameter::type).toList();
                expectedResult = sam.result();
            }
        }
        if (expectedParamTypes == null) {
            return;
        }
        List<BabbleParser.FnParamContext> params = literal.fnParameters().fnParam();
        int required = 0;
        boolean hasRest = false;
        for (BabbleParser.FnParamContext param : params) {
            if (param.rest != null) {
                hasRest = true;
            } else if (param.defaultValue == null) {
                required++;
            }
        }
        boolean appliedAny = false;
        if (required <= expectedParamTypes.size() && (hasRest || params.size() >= expectedParamTypes.size())) {
            int limit = Math.min(params.size(), expectedParamTypes.size());
            for (int i = 0; i < limit; i++) {
                BabbleParser.FnParamContext param = params.get(i);
                if (param.rest != null) {
                    break;
                }
                if (param.type() == null && !declarationTypes.containsKey(param)) {
                    Type paramType = expectedParamTypes.get(i);
                    if (paramType != null && paramType != Type.ERROR && !paramType.hasSyntheticTypeVar()) {
                        declarationTypes.put(param, paramType);
                        appliedAny = true;
                    }
                }
            }
        }
        if (literal.type() == null && expectedResult != null
                && expectedResult != Type.NULL && !isUnresolved(expectedResult)
                && !expectedResult.hasSyntheticTypeVar()) {
            contextualReturnTypes.putIfAbsent(literal, expectedResult);
            appliedAny = true;
        }
        if (appliedAny && base instanceof Type.Function fn && !fn.typeParameters().isEmpty()
                && literal.typeParameters() == null && annotator.typeVarsOf(owningDef(literal)).isEmpty()) {
            contextualTypeParams.putIfAbsent(literal, fn.typeParameters());
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
            contextualTypeParams.remove(ctx);
            widened.remove(ctx);
            samTargets.remove(ctx);
            if (ctx instanceof BabbleParser.ForExpressionContext forCtx) {
                forElemSamTargets.remove(forCtx);
            }
            if (ctx instanceof BabbleParser.AsExpressionContext asCtx) {
                asSamTargets.remove(asCtx);
            }
            resolvedLiterals.remove(ctx);
            if (ctx instanceof BabbleParser.DefExpressionContext || ScopeAnalysis.isParamDecl(ctx)) {
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
            if (resolved instanceof BabbleParser.DefExpressionContext def) {
                if (def.typeParameters() != null) {
                    return true;
                }
                BabbleParser.FunctionLiteralContext lit = unwrapFunctionLiteral(def.value);
                if (lit != null && lit.typeParameters() != null) {
                    return true;
                }
            }
            if (resolved instanceof ParserRuleContext decl) {
                Type declType = annotator.declaredType(decl);
                return declType instanceof Type.Function fn && !fn.typeParameters().isEmpty();
            }
            return false;
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
        if (site != null && actual instanceof Type.Overloaded ov && target != null) {
            Type targetBase = Type.withoutNull(target);
            int idx = -1;
            if (targetBase instanceof Type.Function targetFn) {
                idx = OverloadSolver.selectAlternativeForFunction(algebra, ov, targetFn, checkingEffects);
            } else if (JavaTypeBridge.resolveJavaSam(targetBase) instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
                idx = OverloadSolver.selectAlternativeForSam(algebra, ov, valid.samType());
            }
            if (idx >= 0) {
                Type.Function chosen = ov.alternatives().get(idx);
                ParserRuleContext exprSite = site instanceof BabbleParser.DefExpressionContext def && def.value != null
                        ? def.value
                        : site instanceof BabbleParser.AssignExpressionContext assign && assign.value != null
                        ? assign.value
                        : site instanceof BabbleParser.ReturnExpressionContext ret && ret.expression() != null
                        ? ret.expression()
                        : site;
                boolean exactSet = isExactOverloadSetExpr(exprSite);
                overloadSelections.put(exprSite, new ScopeAnalysis.OverloadSelection(
                        idx, OverloadSolver.signatureKey(chosen), exactSet));
            }
        }
        coerceSam(site, actual, target);
    }

    void coerceSam(ParserRuleContext site, Type actual, Type target) {
        if (site == null || !TypeAlgebra.mayContainBabbleCallable(actual)) {
            return;
        }
        JavaTypeBridge.JavaSamResolution.Valid valid = resolveSamTargetForFlow(actual, target);
        if (valid != null) {
            samTargets.put(site, valid.toTarget());
        }
    }

    private static List<Type> callableAlternatives(Type type) {
        List<Type> result = new ArrayList<>();
        for (Type alt : TypeAlgebra.alternatives(type)) {
            Type unwrapped = TypeAlgebra.unwrapFrozen(alt);
            if (TypeAlgebra.isCallable(unwrapped) || unwrapped == Type.ANY) {
                result.add(alt);
            }
        }
        return result;
    }

    private JavaTypeBridge.JavaSamResolution.Valid resolveSamTargetForFlow(Type actual, Type target) {
        if (target == null || target == Type.ERROR || target == Type.NOTHING) {
            return null;
        }
        JavaTypeBridge.JavaSamResolution direct = JavaTypeBridge.resolveJavaSam(target);
        if (direct instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
            return valid;
        }
        if (Type.withoutNull(target) instanceof Type.Union u) {
            List<Type> callableAlts = callableAlternatives(actual);
            if (callableAlts.isEmpty()) {
                return null;
            }
            for (Type member : u.members()) {
                Type mb = Type.withoutNull(member);
                if (mb instanceof Type.Function || mb instanceof Type.Overloaded || mb == Type.ANY) {
                    for (Type c : callableAlts) {
                        if (algebra.assignable(c, member)) {
                            return null;
                        }
                    }
                }
            }
            JavaTypeBridge.JavaSamResolution.Valid match = null;
            for (Type member : u.members()) {
                if (JavaTypeBridge.resolveJavaSam(member) instanceof JavaTypeBridge.JavaSamResolution.Valid v) {
                    boolean matchesAny = false;
                    for (Type c : callableAlts) {
                        if (TypeAlgebra.unwrapFrozen(c) == Type.ANY || algebra.assignable(c, member)) {
                            matchesAny = true;
                            break;
                        }
                    }
                    if (matchesAny) {
                        if (match != null && !match.equals(v)) {
                            return null;
                        }
                        match = v;
                    }
                }
            }
            return match;
        }
        return null;
    }

    private JavaTypeBridge.JavaSamResolution.Valid resolveSamTargetForAs(Type source, Type target) {
        if (target == null || target == Type.ERROR || target == Type.NOTHING
                || !TypeAlgebra.mayContainBabbleCallable(source)) {
            return null;
        }
        JavaTypeBridge.JavaSamResolution direct = JavaTypeBridge.resolveJavaSam(target);
        if (direct instanceof JavaTypeBridge.JavaSamResolution.Valid valid) {
            return valid;
        }
        if (Type.withoutNull(target) instanceof Type.Union u) {
            for (Type member : u.members()) {
                if (Type.withoutNull(member) == Type.ANY) {
                    return null;
                }
            }
            List<Type> callableAlts = callableAlternatives(source);
            if (callableAlts.isEmpty()) {
                return null;
            }
            JavaTypeBridge.JavaSamResolution.Valid match = null;
            for (Type member : u.members()) {
                if (JavaTypeBridge.resolveJavaSam(member) instanceof JavaTypeBridge.JavaSamResolution.Valid v) {
                    boolean matchesAny = false;
                    for (Type c : callableAlts) {
                        if (TypeAlgebra.unwrapFrozen(c) == Type.ANY || algebra.assignable(c, member)) {
                            matchesAny = true;
                            break;
                        }
                    }
                    if (matchesAny) {
                        if (match != null && !match.equals(v)) {
                            return null;
                        }
                        match = v;
                    }
                }
            }
            return match;
        }
        return null;
    }

    private boolean demoteSamMemberFields() {
        boolean demotedAny = false;
        for (TypeNode node : pass.types) {
            if (node.ctx == null || !inFile(node.ctx)) {
                continue;
            }
            boolean nodeDemoted = false;
            if (!node.isInterface()) {
                for (TypeNode.Member member : node.members.values()) {
                    if (maybeDemoteSamMember(node, member, false)) {
                        demotedAny = true;
                        nodeDemoted = true;
                    }
                }
            }
            for (TypeNode.Member member : node.statics.values()) {
                if (maybeDemoteSamMember(node, member, true)) {
                    demotedAny = true;
                    nodeDemoted = true;
                }
            }
            if (nodeDemoted) {
                pass.analysis.setShape(node.ctx, node.shape());
            }
        }
        return demotedAny;
    }

    private boolean maybeDemoteSamMember(TypeNode owner, TypeNode.Member member, boolean isStatic) {
        if (!member.isMethod() || !(member.declaration instanceof BabbleParser.DefExpressionContext def)
                || def.type() == null) {
            return false;
        }
        Type declared = annotator.declaredType(def);
        if (!isSamAdapterFieldType(declared)) {
            return false;
        }
        demoteMemberToField(owner, member, def, isStatic);
        return true;
    }

    private boolean isSamAdapterFieldType(Type declared) {
        if (declared == null || declared == Type.ERROR || declared == Type.NOTHING) {
            return false;
        }
        JavaTypeBridge.JavaSamResolution res = JavaTypeBridge.resolveJavaSam(declared);
        if (res instanceof JavaTypeBridge.JavaSamResolution.Valid
                || res instanceof JavaTypeBridge.JavaSamResolution.NotFunctional) {
            return true;
        }
        if (Type.withoutNull(declared) instanceof Type.Union u) {
            boolean hasSam = false;
            for (Type member : u.members()) {
                Type mb = Type.withoutNull(member);
                if (mb instanceof Type.Function || mb instanceof Type.Overloaded || mb == Type.ANY) {
                    return false;
                }
                if (JavaTypeBridge.resolveJavaSam(member) instanceof JavaTypeBridge.JavaSamResolution.Valid) {
                    hasSam = true;
                }
            }
            return hasSam;
        }
        return false;
    }

    private void demoteMemberToField(TypeNode owner, TypeNode.Member member, BabbleParser.DefExpressionContext def,
                                     boolean isStatic) {
        List<BabbleParser.FunctionLiteralContext> lits = new ArrayList<>();
        if (member.function != null) {
            lits.add(member.function);
        }
        lits.addAll(member.overloadFunctions);
        for (BabbleParser.FunctionLiteralContext lit : lits) {
            ScopeNode fnScope = scopeOf(lit);
            if (fnScope != null) {
                fnScope.memberOf = null;
                fnScope.staticMethod = false;
                for (BlockScopes.PendingSuper pending : pass.supers) {
                    if (isScopeInside(pending.scope(), fnScope)) {
                        demoteDiagnostics.add(new Diagnostic(
                                pending.ctx().getStart().getLine(),
                                pending.ctx().getStart().getCharPositionInLine() + 1,
                                "super outside of a method"));
                    }
                }
            }
        }
        member.demoteSamField();
        if (!isStatic) {
            Set<Class<?>> javaParents = new LinkedHashSet<>();
            collectJavaParents(owner, javaParents, Collections.newSetFromMap(new IdentityHashMap<>()));
            for (Class<?> jc : javaParents) {
                for (Method m : JavaTypes.abstractMethods(jc)) {
                    if (m.getName().equals(member.name)
                            && !hasBabbleDefault(owner, member.name, Collections.newSetFromMap(new IdentityHashMap<>()))) {
                        int line = owner.ctx != null ? owner.ctx.getStart().getLine() : def.getStart().getLine();
                        int col = owner.ctx != null ? owner.ctx.getStart().getCharPositionInLine() + 1 : 1;
                        demoteDiagnostics.add(new Diagnostic(line, col,
                                owner.name + " does not implement " + jc.getName() + "." + member.name));
                        break;
                    }
                }
            }
        }
    }

    private static boolean isScopeInside(ScopeNode inner, ScopeNode target) {
        for (ScopeNode s = inner; s != null; s = s.parent) {
            if (s == target) {
                return true;
            }
            if (s.kind == ScopeNode.Kind.CLASS_BODY
                    || s.kind == ScopeNode.Kind.INTERFACE_BODY
                    || s.kind == ScopeNode.Kind.STATIC_BODY) {
                return false;
            }
        }
        return false;
    }

    private static void collectJavaParents(TypeNode node, Set<Class<?>> out, Set<TypeNode> seen) {
        if (!seen.add(node)) {
            return;
        }
        for (Object parent : node.parents) {
            if (parent instanceof Class<?> jc) {
                out.add(jc);
            } else if (parent instanceof TypeNode pn) {
                collectJavaParents(pn, out, seen);
            }
        }
    }

    private static boolean hasBabbleDefault(TypeNode owner, String memberName, Set<TypeNode> seen) {
        for (Object parent : owner.parents) {
            if (parent instanceof TypeNode pn && seen.add(pn)) {
                if (pn.defaults != null && pn.defaults.containsKey(memberName)) {
                    return true;
                }
                if (hasBabbleDefault(pn, memberName, seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether {@code expr} is statically known to evaluate to the exact overload set of its static
     * type (rather than a wider runtime set passed through a set-typed parameter or interface member).
     */
    boolean isExactOverloadSetExpr(ParserRuleContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b && b.block().expression().size() == 1) {
            expr = b.block().expression(0);
        }
        if (expr instanceof BabbleParser.OverloadExpressionContext) {
            return true;
        }
        if (expr instanceof BabbleParser.SelectorContext sel) {
            if (sel.expression() == null) {
                ScopeNode scope = occurrenceScopes.get(sel);
                Object resolved = scope != null ? resolveName(sel, sel.ID().getText(), scope) : null;
                if (resolved instanceof BabbleParser.DefExpressionContext def && !reassigned.contains(def)) {
                    return def.type() == null && def.value instanceof BabbleParser.OverloadExpressionContext;
                }
                return false;
            }
            Type recv = types.get(sel.expression());
            if (recv instanceof Type.Module) {
                return true;
            }
            String memberName = ScopeAnalysis.selectorName(sel);
            TypeNode.Member member = null;
            if (recv instanceof Type.Instance inst && !inst.node().isInterface()) {
                member = inst.node().members.get(memberName);
            } else if (recv instanceof Type.ClassValue cv) {
                member = cv.node().statics.get(memberName);
            }
            if (member != null && member.declaration instanceof BabbleParser.DefExpressionContext def) {
                return def.type() == null && def.value instanceof BabbleParser.OverloadExpressionContext;
            }
        }
        return false;
    }

    /**
     * Resolves the flattened list of local {@link BabbleParser.FunctionLiteralContext} alternatives
     * for an immutable local overload set expression (entries may be {@code null} for non-literal
     * or imported alternatives), or {@code null} if {@code expr} is not a known local overload set.
     */
    List<BabbleParser.FunctionLiteralContext> resolveOverloadLiterals(ParserRuleContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext b && b.block().expression().size() == 1) {
            expr = b.block().expression(0);
        }
        if (expr instanceof BabbleParser.OverloadExpressionContext ov) {
            return overloadAltLiterals.get(ov);
        }
        if (expr instanceof BabbleParser.SelectorContext sel) {
            if (sel.expression() == null) {
                ScopeNode scope = occurrenceScopes.get(sel);
                Object resolved = scope != null ? resolveName(sel, sel.ID().getText(), scope) : null;
                if (resolved instanceof BabbleParser.DefExpressionContext def && !reassigned.contains(def)
                        && def.value instanceof BabbleParser.OverloadExpressionContext ov && inFile(ov)) {
                    return overloadAltLiterals.get(ov);
                }
                return null;
            }
            Type recv = types.get(sel.expression());
            String memberName = ScopeAnalysis.selectorName(sel);
            TypeNode.Member member = null;
            if (recv instanceof Type.Instance inst && !inst.node().isInterface()) {
                member = inst.node().members.get(memberName);
            } else if (recv instanceof Type.ClassValue cv) {
                member = cv.node().statics.get(memberName);
            }
            if (member != null && member.declaration instanceof BabbleParser.DefExpressionContext def
                    && def.value instanceof BabbleParser.OverloadExpressionContext ov && inFile(ov)) {
                return overloadAltLiterals.get(ov);
            }
        }
        return null;
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
        if (TypeAlgebra.isCallable(actual)
                && JavaTypeBridge.resolveJavaSam(expected) instanceof JavaTypeBridge.JavaSamResolution.NotFunctional nf) {
            warn("Not a functional interface : " + nf.intersection(), at);
        } else if (actual instanceof Type.Function aFn && expected instanceof Type.Function eFn
                && (algebra.assignableIgnoringEffects(aFn, eFn)
                        || !eFn.typeParameters().isEmpty() || !aFn.typeParameters().isEmpty())) {
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
                case BabbleParser.IfExpressionContext c -> type(c.ifExpr(), expected, role);
                case BabbleParser.IfExprContext c -> visitIfExpr(c, expected, role);
                case BabbleParser.DefExpressionContext c -> visitDefExpression(c, expected, role);
                case BabbleParser.WhileExpressionContext c -> visitWhileExpression(c, expected, role);
                case BabbleParser.TypeAliasExpressionContext c -> visitTypeAlias(c);
                case BabbleParser.AxiomExpressionContext c -> {
                    if (AxiomData.isBabbleNameAxiom(c) && checkingEffects) {
                        parseBabbleAxiom(c, scopeOf(c), true);
                    }
                    yield Type.NULL;
                }
                case BabbleParser.ForExpressionContext c -> visitForExpression(c, expected, role);
                case BabbleParser.ClassExpressionContext c -> visitClassExpression(c, expected, role);
                case BabbleParser.InterfaceExpressionContext c -> visitInterfaceExpression(c, expected, role);
                case BabbleParser.ObjectExpressionContext c -> visitObjectExpression(c, expected, role);
                case BabbleParser.ThisExpressionContext c -> visitThisExpression(c, expected, role);
                case BabbleParser.SuperExpressionContext c -> visitSuperExpression(c, expected, role);
                case BabbleParser.SelectorContext c -> visitSelector(c, expected, role);
                case BabbleParser.NonNullAssertionContext c -> visitNonNullAssertion(c, expected, role);
                case BabbleParser.CoreScalarRefContext c -> visitCoreScalarRef(c, expected, role);
                case BabbleParser.CoreCollectionContext c -> visitCoreCollection(c, expected, role);
                case BabbleParser.TypeApplyContext c -> visitTypeApply(c, expected, role);
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
                case BabbleParser.OverloadExpressionContext c -> visitOverloadExpression(c, expected, role);
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
                case BabbleParser.ForeverExpressionContext c -> type(c.expression(), expected, role);
                case BabbleParser.BreakExpressionContext c -> visitBreakExpression(c, expected, role);
                case BabbleParser.ContinueExpressionContext c -> visitContinueExpression(c, expected, role);
                default -> {
                    Type last = null;
                    for (int i = 0; i < ctx.getChildCount(); i++) {
                        last = ctx.getChild(i) instanceof ParserRuleContext child ? typeOf(child, expected, role) : null;
                    }
                    yield last;
                }
            };
        }

        private Type visitIfExpr(BabbleParser.IfExprContext ctx, Type expected, Role role) {
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
            if (ctx.effectClause() != null && effectChecker != null && value instanceof Type.Function fn) {
                EffectSet override = effectChecker.defOverrideEffects(ctx);
                if (override != null) {
                    value = fn.withEffects(override, fn.parameters());
                }
            }
            ParserRuleContext errorSite = ctx.value != null ? ctx.value : ctx;
            if (declared != null) {
                if (kind == ScopeAnalysis.DefKind.DEFAULT && declared != Type.ERROR
                        && ((ctx.value instanceof BabbleParser.FunctionLiteralContext && !(declared instanceof Type.Function))
                        || (ctx.value instanceof BabbleParser.OverloadExpressionContext && !(declared instanceof Type.Overloaded)))) {
                    if (algebra.assignable(value, declared)) {
                        warn("An interface default must have a function type : " + ctx.name.getText(), ctx.type());
                    } else {
                        subsumes(errorSite, ctx, value, declared);
                    }
                    samTargets.remove(ctx);
                    return Type.ERROR;
                }
                subsumes(errorSite, ctx, value, declared);
                if ((kind == ScopeAnalysis.DefKind.MEMBER || kind == ScopeAnalysis.DefKind.STATIC)
                        && samTargets.containsKey(ctx)) {
                    ScopeNode bodyScope = scopeOf(ctx);
                    if (bodyScope != null && bodyScope.type != null) {
                        boolean isStatic = kind == ScopeAnalysis.DefKind.STATIC;
                        TypeNode.Member member = isStatic
                                ? bodyScope.type.statics.get(ctx.name.getText())
                                : bodyScope.type.members.get(ctx.name.getText());
                        if (member != null && member.isMethod()) {
                            demoteMemberToField(bodyScope.type, member, ctx, isStatic);
                            pass.analysis.setShape(bodyScope.type.ctx, bodyScope.type.shape());
                            needsReResolve = true;
                        }
                    }
                }
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
            LoopContext loop = new LoopContext(expected != null && expected != Type.ERROR && role.isUsed() ? expected : null);
            loopStack.push(loop);
            Type bodyType;
            try {
                bodyType = type(ctx.whileBlock, blockExpected, role);
            } finally {
                loopStack.pop();
            }
            env = TypeEnv.joinBranches(afterTest, env, afterTest);
            return finishLoop(ctx, ctx.whileBlock, "while", bodyType, loop, expected, role);
        }

        private Type visitForExpression(BabbleParser.ForExpressionContext ctx, Type expected, Role role) {
            Type iterType = synth(ctx.iterable);
            ForIterableInfo info = resolveIterableInfo(iterType, ctx);
            if (info != null) {
                forInfos.put(ctx, info);
            }
            Type elemType = info != null ? info.elemType() : Type.ERROR;
            Type loopVarType = elemType;
            if (ctx.type() != null) {
                Type declared = annotator.annotation(ctx.type(), scopeOf(ctx));
                if (declared != Type.ERROR) {
                    if (elemType != Type.ERROR && !algebra.assignable(elemType, declared)) {
                        reportTypeMismatch(elemType, declared, ctx.type());
                    }
                    int targetRank = TypeAlgebra.numericWideningTargetRank(elemType, declared);
                    if (targetRank >= 0) {
                        widened.merge(ctx, targetRank, Math::max);
                    }
                    if (TypeAlgebra.mayContainBabbleCallable(elemType)) {
                        JavaTypeBridge.JavaSamResolution.Valid valid = resolveSamTargetForFlow(elemType, declared);
                        if (valid != null) {
                            forElemSamTargets.put(ctx, valid.toTarget());
                        }
                    }
                    loopVarType = declared;
                } else {
                    loopVarType = Type.ERROR;
                }
            }
            declarationTypes.put(ctx, loopVarType);
            env = env.evictAll(narrowing.assignedLocals(ctx));
            TypeEnv beforeLoop = env;
            Type blockExpected = (expected == Type.ANY || expected == Type.NULLABLE_ANY) ? expected : null;
            LoopContext loop = new LoopContext(expected != null && expected != Type.ERROR && role.isUsed() ? expected : null);
            loopStack.push(loop);
            Type bodyType;
            try {
                bodyType = type(ctx.forBlock, blockExpected, role);
            } finally {
                loopStack.pop();
            }
            env = TypeEnv.joinBranches(beforeLoop, env, beforeLoop);
            return finishLoop(ctx, ctx.forBlock, "for", bodyType, loop, expected, role);
        }

        private Type finishLoop(ParserRuleContext loopCtx, BabbleParser.BlockContext loopBlock, String loopName,
                                Type bodyType, LoopContext loop, Type expected, Role role) {
            boolean used = role.isUsed();
            if (expected != null && expected != Type.ERROR && used) {
                if (bodyType != Type.NOTHING) {
                    subsumes(loopBlock, bodyType, expected);
                }
                for (int i = 0; i < loop.breaks.size(); i++) {
                    subsumes(loop.breaks.get(i).expression(), loop.breakTypes.get(i), expected);
                }
                if (!algebra.assignable(Type.NULL, expected)) {
                    warn("Type error : expected " + expected + ", got null", loopCtx);
                }
                return expected;
            }
            Type joined = Type.NULL;
            if (bodyType != Type.NOTHING) {
                joined = algebra.join(bodyType, joined);
            }
            if (joined != null) {
                for (Type bt : loop.breakTypes) {
                    joined = algebra.join(bt, joined);
                    if (joined == null) {
                        break;
                    }
                }
            }
            if (joined == null) {
                if (!used) {
                    return Type.NULL;
                }
                if (!isUnresolved(bodyType)) {
                    warn("Type error : the branches of " + loopName + " have different types : " + bodyType + " and null", loopCtx);
                }
                return Type.ERROR;
            }
            if (bodyType != Type.NOTHING) {
                coerce(loopBlock, bodyType, joined);
            }
            for (int i = 0; i < loop.breaks.size(); i++) {
                coerce(loop.breaks.get(i).expression(), loop.breakTypes.get(i), joined);
            }
            return joined;
        }

        private ForIterableInfo resolveIterableInfo(Type iterType, BabbleParser.ForExpressionContext ctx) {
            if (iterType == null || iterType == Type.ERROR || iterType == Type.NOTHING) {
                return null;
            }
            if (Type.containsNull(iterType)) {
                warn("Type error : cannot iterate over nullable type " + iterType, ctx.iterable);
                return null;
            }
            ForIterableInfo resolved = members.resolveNonNullIterable(iterType);
            if (resolved == null) {
                warn("Not iterable : " + iterType, ctx.iterable);
            }
            return resolved;
        }

        private Type visitClassExpression(BabbleParser.ClassExpressionContext ctx, Type expected, Role role) {
            if (ctx.classParameters() != null) {
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

        /** {@code def name = type T} (BIP023) : only as a def's value, which takes no annotation of its own. */
        private Type visitTypeAlias(BabbleParser.TypeAliasExpressionContext ctx) {
            if (!(ctx.getParent() instanceof BabbleParser.DefExpressionContext def) || def.value != ctx) {
                warn("A type alias is the value of a def : " + ctx.getText().replaceFirst("^type", "type "), ctx);
                return Type.ERROR;
            }
            if (def.type() != null) {
                warn("A type alias takes no annotation : " + def.name.getText(), def.type());
                return Type.ERROR;
            }
            return annotator.aliasOf(def);
        }

        private Type visitSelector(BabbleParser.SelectorContext ctx, Type expected, Role role) {
            Type result = selectUninstantiated(ctx);
            if (result instanceof Type.TypeAlias) {
                warn("A type alias is not a value : " + ctx.getText(), ctx);
                return Type.ERROR;
            }
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
                Type.Function babbleAx = resolveBabbleAxiom(name, occurrenceScopes.get(ctx));
                if (babbleAx != null) {
                    return babbleAx;
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
                String target = org.babblelang.parser.StringLiterals.decode(string);
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
                    if (i < statements.size() - 1 && statement instanceof BabbleParser.IfExpressionContext ifExprCtx) {
                        BabbleParser.IfExprContext ifCtx = ifExprCtx.ifExpr();
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
            Type source = synth(ctx.left);
            Type target = resolveIsTarget(ctx);
            if (target != Type.ERROR && isUncheckedArrayCheck(source, target)) {
                analysisWarn("Unchecked cast : " + target, ctx);
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
            String name = ctx.coreScalarKeyword().getText();
            Type.Function babbleAx = resolveBabbleAxiom(name, scopeOf(ctx));
            return babbleAx != null ? babbleAx : operators.implicitType(name);
        }

        private Type visitCoreCollection(BabbleParser.CoreCollectionContext ctx, Type expected, Role role) {
            return new Type.CoreCollection(ctx.coreCollectionKeyword().getText());
        }

        private Type visitTypeApply(BabbleParser.TypeApplyContext ctx, Type expected, Role role) {
            Type target = synth(ctx.expression(), Role.CALLEE);
            return callSolver.applyTypes(ctx, target);
        }

        private Type visitAsExpression(BabbleParser.AsExpressionContext ctx, Type expected, Role role) {
            Type source = synth(ctx.left);
            Type target = annotator.annotation(ctx.right, scopeOf(ctx), true);
            if (target == Type.ERROR) {
                return Type.ERROR;
            }
            if (target != null && target.hasTypeVar()) {
                warn("Cannot cast to type parameter : " + target, ctx.right);
                return Type.ERROR;
            }
            if (containsFunction(target)) {
                warn("Cannot cast to a function type", ctx.right);
                return target;
            }
            if (!algebra.canCast(source, target)) {
                reportTypeMismatch(source, target, ctx.left);
            } else {
                if (algebra.alwaysIs(source, target)) {
                    analysisWarn("Redundant cast : " + target, ctx);
                } else if (isUncheckedArrayCheck(source, target)) {
                    analysisWarn("Unchecked cast : " + target, ctx);
                }
                JavaTypeBridge.JavaSamResolution.Valid valid = resolveSamTargetForAs(source, target);
                if (valid != null) {
                    asSamTargets.put(ctx, valid.toTarget());
                }
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

        private Type visitOverloadExpression(BabbleParser.OverloadExpressionContext ctx, Type expected, Role role) {
            String ownerName = overloadExprOwnerName(ctx);
            List<Type.Function> alts = new ArrayList<>();
            List<BabbleParser.FunctionLiteralContext> flatLiterals = new ArrayList<>();
            List<BabbleParser.FunctionLiteralContext> pendingBodies = new ArrayList<>();
            boolean failed = false;
            for (BabbleParser.ExpressionContext op : ctx.expression()) {
                if (op instanceof BabbleParser.FunctionLiteralContext lit) {
                    if (inFile(lit)) {
                        functions.add(lit);
                    }
                    boolean litMissing = false;
                    for (BabbleParser.FnParamContext param : lit.fnParameters().fnParam()) {
                        if (param.type() == null) {
                            warn("Missing type : " + param.ID().getText(), param);
                            litMissing = true;
                        }
                    }
                    if (lit.type() == null) {
                        warn("Missing type : return type of overload alternative", lit);
                        litMissing = true;
                    }
                    if (litMissing) {
                        failed = true;
                        continue;
                    }
                    Type.Function sig = signature(lit, null);
                    types.put(lit, sig);
                    alts.add(sig);
                    flatLiterals.add(inFile(lit) ? lit : null);
                    pendingBodies.add(lit);
                } else {
                    Type t = synth(op);
                    if (t == Type.ERROR) {
                        failed = true;
                    } else if (t instanceof Type.Function fn) {
                        alts.add(fn);
                        BabbleParser.FunctionLiteralContext lit = functionLiterals.get(fn);
                        flatLiterals.add(lit != null && inFile(lit) ? lit : null);
                    } else if (t instanceof Type.Overloaded nested) {
                        alts.addAll(nested.alternatives());
                        List<BabbleParser.FunctionLiteralContext> nestedLits = resolveOverloadLiterals(op);
                        if (nestedLits != null && nestedLits.size() == nested.alternatives().size()) {
                            flatLiterals.addAll(nestedLits);
                        } else {
                            for (int i = 0; i < nested.alternatives().size(); i++) {
                                flatLiterals.add(null);
                            }
                        }
                    } else {
                        warn("Type error : expected function, got " + t, op);
                        failed = true;
                    }
                }
            }
            Type result = Type.ERROR;
            if (!failed) {
                String ambig = OverloadSolver.checkWellFormed(algebra, ownerName, alts);
                if (ambig != null) {
                    warn(ambig, ctx);
                } else {
                    Type.Overloaded ovType = new Type.Overloaded(alts);
                    types.put(ctx, ovType);
                    overloadAltLiterals.put(ctx, Collections.unmodifiableList(flatLiterals));
                    result = ovType;
                }
            }
            // Type literal bodies after registering the overload set type so recursive calls to the set resolve.
            for (BabbleParser.FunctionLiteralContext lit : pendingBodies) {
                if (lit.functionBlock != null && !types.containsKey(lit.functionBlock)) {
                    TypeEnv saved = env;
                    env = saved.enterCallable(reassigned);
                    try {
                        check(lit.functionBlock, expectedReturnType(lit));
                    } finally {
                        env = saved;
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
                Object resolved = resolveName(ctx, name, occurrenceScopes.get(ctx));
                if (resolved instanceof ParserRuleContext declaration) {
                    Type current = Type.ERROR;
                    if (readCurrent) {
                        Type narrowed = narrowing.narrowedType(declaration);
                        current = narrowed != null ? narrowed : typeOfDeclaration(declaration);
                    }
                    Type declared = ScopeAnalysis.isParamDecl(declaration)
                            ? typeOfDeclaration(declaration)
                            : annotator.declaredType(declaration);
                    return new LValue.Local(declaration, declared, current, declared == null);
                }
                return new LValue.Implicit(readCurrent ? operators.implicitType(name) : Type.ERROR);
            }
            Type receiver = synth(receiverExpr, Role.SELECTOR_RECEIVER);
            Object field = JavaTypeBridge.assignedField(receiver, name);
            if (field instanceof String error) {
                warn(error, ctx);
            } else if (field instanceof JavaTypeBridge.AssignedField assigned) {
                Type declared = assigned.type(receiver, false, algebra);
                Type current = readCurrent ? assigned.type(receiver, true, algebra) : declared;
                return new LValue.JavaField(assigned, declared, current);
            }
            boolean own = receiverExpr instanceof BabbleParser.ThisExpressionContext;
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
            if (declared == null && member != null && !member.hasFunction
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
            Type result = operators.binaryOp(operators.arithmeticToken(operator), operator, ctx, SyntaxText.targetText(ctx), ctx, current,
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

        private Type visitBreakExpression(BabbleParser.BreakExpressionContext ctx, Type expected, Role role) {
            LoopContext loop = loopStack.peek();
            if (ctx.expression() != null) {
                Type value = check(ctx.expression(), loop != null ? loop.expected : null);
                if (loop != null) {
                    loop.breaks.add(ctx);
                    loop.breakTypes.add(value);
                }
            } else if (loop != null) {
                loop.hasBareBreak = true;
            }
            return Type.NOTHING;
        }

        private Type visitContinueExpression(BabbleParser.ContinueExpressionContext ctx, Type expected, Role role) {
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
        if (ScopeAnalysis.isParamDecl(member.declaration)) {
            return typeOfDeclaration(member.declaration);
        }
        if (member.function != null) {
            return synth(member.function);
        }
        if (member.isOverload && member.declaration instanceof BabbleParser.DefExpressionContext def
                && def.value instanceof BabbleParser.OverloadExpressionContext ov) {
            Type ready = types.get(ov);
            return ready != null ? ready : synth(ov);
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

    List<? extends ParserRuleContext> parameterDecls(Type.Function fn) {
        BabbleParser.FunctionLiteralContext lit = functionLiterals.get(fn);
        return lit != null ? lit.fnParameters().fnParam() : null;
    }

    static List<? extends ParserRuleContext> parameterDecls(TypeNode node) {
        return node.ctx instanceof BabbleParser.ClassExpressionContext cls && cls.classParameters() != null
                ? cls.classParameters().classParam() : null;
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
        if (cls.classParameters() == null) {
            return List.of();
        }
        ScopeNode scope = scopeOf(cls);
        EffectChecker.CallableSummary cs = (effectChecker != null && node != null) ? effectChecker.constructorSummary(node) : null;
        List<BabbleParser.ClassParamContext> decls = cls.classParameters().classParam();
        int kwStart = ScopeAnalysis.keywordOnlyStart(cls.classParameters());
        List<Type.Parameter> result = new ArrayList<>(decls.size());
        for (int i = 0; i < decls.size(); i++) {
            BabbleParser.ClassParamContext parameter = decls.get(i);
            Type type = parameterType(parameter, scope);
            if (parameter.defaultValue != null && !types.containsKey(parameter.defaultValue)) {
                Type actual = check(parameter.defaultValue, type != Type.ERROR ? type : null);
                if (type != Type.ERROR) {
                    subsumes(parameter.defaultValue, null, actual, type);
                    coerceSam(parameter.defaultValue, actual, type);
                }
            }
            EffectChecker.ParamMods pm = effectChecker != null
                    ? effectChecker.parseParamMods(parameter, checkingEffects) : EffectChecker.ParamMods.NONE;
            boolean mut = cs != null && i < cs.mutParams().length ? cs.mutParams()[i] : pm.mut();
            boolean borrow = cs != null && i < cs.borrowParams().length ? cs.borrowParams()[i] : false;
            boolean unique = cs != null && i < cs.uniqueParams().length ? cs.uniqueParams()[i] : pm.unique();
            result.add(new Type.Parameter(parameter.ID().getText(), type, parameter.defaultValue != null,
                    i >= kwStart, mut, borrow, pm.shared(), unique));
        }
        return result;
    }

    private Type parameterType(ParserRuleContext parameter, ScopeNode scope) {
        BabbleParser.TypeContext paramType = ScopeAnalysis.paramType(parameter);
        if (paramType != null) {
            return annotator.annotation(paramType, scope);
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
        BabbleParser.ExpressionContext defVal = ScopeAnalysis.paramDefault(parameter);
        if (defVal != null) {
            defType = synth(defVal);
            if (defType != Type.NULL && !isUnresolved(defType)) {
                declarationTypes.put(parameter, defType);
                return defType;
            }
        }
        if (defVal == null || defType == Type.NULL) {
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

    AxiomData.AxiomTable exportedAxioms() {
        return effectChecker != null ? effectChecker.exportedAxioms() : AxiomData.AxiomTable.EMPTY;
    }

}
