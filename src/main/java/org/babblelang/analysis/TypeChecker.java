package org.babblelang.analysis;

import static org.babblelang.analysis.TypeAlgebra.canBeNull;
import static org.babblelang.analysis.TypeAlgebra.containsFunction;

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
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.Operators;

/**
 * The type checker (docs/design/bip008_static-typing.md) : the fourth pass of the analysis, after
 * {@link Resolver}. It types every expression of the file and reports, as errors, what contradicts
 * the annotations, leaves a declaration without a static type, or is bound to fail at runtime : a
 * value of the wrong type where a type is declared, calls that don't match a known signature,
 * members that don't exist, operators applied to values they reject.
 *
 * <p>Typing is static : an unannotated def takes the type of its value and widens along
 * reassignments via {@code join}, unannotated function literals infer their result from their body
 * and receive parameter types from context where an expected function or functional interface type
 * is known, and {@code is}, {@code == null}, {@code != null}, and truthiness checks narrow lexical
 * bindings along control-flow branches (T8). Use {@code any} or {@code any?} with {@code as} casts
 * where heterogeneity is intentional. Declared types are also recorded so the backends check them
 * at runtime where {@code any} or untyped script bindings cross into typed code (T9).
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

    record Diagnostic(int line, int column, String message) {}

    record Widening(Type type, int line) {}

    private static final Comparator<Diagnostic> ORDER =
            Comparator.comparingInt(Diagnostic::line)
                    .thenComparingInt(Diagnostic::column)
                    .thenComparing(Diagnostic::message);

    final BlockScopes pass;
    final Map<ParserRuleContext, ScopeNode> occurrenceScopes = new HashMap<>();
    private final Set<ParserRuleContext> capturedReassigned = new HashSet<>();
    final Map<ParserRuleContext, Type> types = new HashMap<>();
    final Map<ParserRuleContext, Type> declarationTypes = new HashMap<>();
    private final Set<ParserRuleContext> inProgress = new HashSet<>();
    final Set<BabbleParser.FunctionLiteralContext> functions = new LinkedHashSet<>();
    final Set<Diagnostic> diagnostics = new TreeSet<>(ORDER);
    private final Set<Diagnostic> effectDiagnostics = new TreeSet<>(ORDER);
    final Set<Diagnostic> warnings = new TreeSet<>(ORDER);
    final Map<ParserRuleContext, Integer> widened = new IdentityHashMap<>();
    final Map<ParserRuleContext, Object> resolvedLiterals = new IdentityHashMap<>();
    final Map<ParserRuleContext, String> callChecks = new IdentityHashMap<>();
    final Typer typer = new Typer();
    final Map<BabbleParser.FunctionLiteralContext, Type> contextualReturnTypes =
            new IdentityHashMap<>();
    final Map<BabbleParser.FunctionLiteralContext, List<Type.TypeVar>> contextualTypeParams =
            new IdentityHashMap<>();

    record ForIterableInfo(
            LoweringTargets.ForKind kind,
            Class<?> arrayClass,
            Type elemType,
            Type.Instance babbleIterable,
            Type.Instance babbleIterator,
            Type.Function iteratorFn,
            Type.Function hasNextFn,
            Type.Function nextFn) {}

    private static final class LoopContext {
        final Type expected;
        final List<BabbleParser.BreakExpressionContext> breaks = new ArrayList<>();
        final List<Type> breakTypes = new ArrayList<>();
        boolean hasBareBreak;

        LoopContext(Type expected) {
            this.expected = expected;
        }
    }

    private final Map<BabbleParser.ForExpressionContext, ForIterableInfo> forInfos =
            new IdentityHashMap<>();
    private final Deque<LoopContext> loopStack = new ArrayDeque<>();

    ForIterableInfo forIterableInfo(BabbleParser.ForExpressionContext ctx) {
        return forInfos.get(ctx);
    }

    final Map<BabbleParser.FunctionLiteralContext, List<BabbleParser.ReturnExpressionContext>>
            functionReturns = new IdentityHashMap<>();
    final Deque<BabbleParser.FunctionLiteralContext> inferenceStack = new ArrayDeque<>();
    final Set<BabbleParser.FunctionLiteralContext> inferringResult =
            Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<BabbleParser.FunctionLiteralContext> recursiveFunctions =
            Collections.newSetFromMap(new IdentityHashMap<>());
    final Map<BabbleParser.FunctionLiteralContext, Type> provisionalResults =
            new IdentityHashMap<>();
    TypeEnv env = TypeEnv.EMPTY;
    final Map<ParserRuleContext, Type> inferredDefs = new IdentityHashMap<>();
    final Map<ParserRuleContext, Type> inferredMembers = new IdentityHashMap<>();
    final Map<ParserRuleContext, Type> inferredParams = new IdentityHashMap<>();
    final Map<BabbleParser.IsExpressionContext, Type> isTargetTypes = new IdentityHashMap<>();
    final Map<BabbleParser.CatchClauseContext, Type> catchTargetTypes = new IdentityHashMap<>();

    Type catchTargetType(BabbleParser.CatchClauseContext ctx) {
        return catchTargetTypes.get(ctx);
    }

    final Map<ParserRuleContext, Widening> defWidenings = new IdentityHashMap<>();
    final Map<ParserRuleContext, Widening> memberWidenings = new IdentityHashMap<>();
    final Map<ParserRuleContext, Widening> paramWidenings = new IdentityHashMap<>();
    final Set<ParserRuleContext> escapingTargets =
            Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<ParserRuleContext> nextEscapingTargets =
            Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<ParserRuleContext> p7Params = Collections.newSetFromMap(new IdentityHashMap<>());
    final Map<Type.Function, BabbleParser.FunctionLiteralContext> functionLiterals =
            new IdentityHashMap<>();
    final Set<TypeNode> exportedTypeNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<ParserRuleContext> failedDefs = Collections.newSetFromMap(new IdentityHashMap<>());

    /** One branch of a statically verified union-split overload call (BIP021-D11 #4). */
    record ResolvedSplitBranch(
            List<LoweringTargets.OverloadSplitCheck> checks,
            int altIndex,
            Type.Function altType,
            boolean exactSet,
            BabbleParser.FunctionLiteralContext directTarget,
            Type resultType,
            LoweringTargets.SpreadAdapt[] spreadAdapts,
            PrimitiveKind restKind) {}

    /** Resolution result for a call to an overload set (single alternative or union split). */
    record ResolvedOverloadCall(
            int altIndex,
            Type.Function altType,
            boolean exactSet,
            BabbleParser.FunctionLiteralContext directTarget,
            Type resultType,
            List<ResolvedSplitBranch> splitBranches) {
        boolean isSplit() {
            return splitBranches != null && !splitBranches.isEmpty();
        }
    }

    final Map<BabbleParser.OverloadExpressionContext, List<BabbleParser.FunctionLiteralContext>>
            overloadAltLiterals = new IdentityHashMap<>();
    final Map<ParserRuleContext, LoweringTargets.OverloadSelection> overloadSelections =
            new IdentityHashMap<>();
    final Map<ParserRuleContext, ResolvedOverloadCall> resolvedOverloadCalls =
            new IdentityHashMap<>();
    final Map<ParserRuleContext, LoweringTargets.JavaSamTarget> samTargets =
            new IdentityHashMap<>();
    final Map<BabbleParser.ForExpressionContext, LoweringTargets.JavaSamTarget> forElemSamTargets =
            new IdentityHashMap<>();
    final Map<BabbleParser.AsExpressionContext, LoweringTargets.JavaSamTarget> asSamTargets =
            new IdentityHashMap<>();
    final List<Diagnostic> demoteDiagnostics = new ArrayList<>();
    boolean needsReResolve;

    Map<ParserRuleContext, LoweringTargets.OverloadSelection> overloadSelections() {
        return overloadSelections;
    }

    Map<ParserRuleContext, LoweringTargets.JavaSamTarget> samTargets() {
        return samTargets;
    }

    Map<BabbleParser.ForExpressionContext, LoweringTargets.JavaSamTarget> forElemSamTargets() {
        return forElemSamTargets;
    }

    Map<BabbleParser.AsExpressionContext, LoweringTargets.JavaSamTarget> asSamTargets() {
        return asSamTargets;
    }

    Map<ParserRuleContext, ResolvedOverloadCall> resolvedOverloadCalls() {
        return resolvedOverloadCalls;
    }

    void recordResolvedOverloadCall(ParserRuleContext ctx, ResolvedOverloadCall call) {
        resolvedOverloadCalls.put(ctx, call);
    }

    final CallSolver callSolver = new CallSolver(this);
    final SlotInference slots = new SlotInference(this);
    final TypeAnnotations annotator = new TypeAnnotations(this);
    final MemberTypes members = new MemberTypes(this);
    final OperatorTypes operators = new OperatorTypes(this);
    final Narrowing narrowing = new Narrowing(this);
    final MatchTyper matchTyper = new MatchTyper(this);
    final ExceptionTyper exceptionTyper = new ExceptionTyper(this);
    final TypeCheckDiagnostics diag = new TypeCheckDiagnostics(this);
    final CallableTyper callableTyper = new CallableTyper(this);
    private ImportedSummaries imported;

    MatchTyper matchTyper() {
        return matchTyper;
    }

    CallSolver callSolver() {
        return callSolver;
    }

    EffectChecker effectChecker;
    boolean checkingEffects;
    BabbleParser.FileContext file;
    private TypedModuleSummary builtSummary;
    final TypeAlgebra algebra =
            new TypeAlgebra(
                    new TypeAlgebra.Env() {
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
                            return effectChecker == null
                                    || effectChecker.callableSatisfies(from, target);
                        }

                        @Override
                        public boolean isDeeplyImmutable(Type type) {
                            return TypeChecker.this.isDeeplyImmutable(type);
                        }

                        @Override
                        public void derived(Type.Function from, Type.Function to) {
                            BabbleParser.FunctionLiteralContext literal =
                                    functionLiterals.get(from);
                            if (literal != null) {
                                functionLiterals.put(to, literal);
                            }
                        }
                    });

    /** Deep immutability, by mode crossing (BIP027-I5) : what the effect checker also asks. */
    private final ModalKindTable modalKinds =
            new ModalKindTable(
                    cls ->
                            effectChecker != null
                                    ? effectChecker.isJavaClassDeeplyImmutable(cls)
                                    : BuiltinAxioms.current().isDeeplyImmutable(cls),
                    this);

    boolean isDeeplyImmutable(Type type) {
        return modalKinds.isDeeplyImmutable(type);
    }

    ModalKindTable modalKinds() {
        return modalKinds;
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
        int endCol =
                stmt.getStop().getCharPositionInLine()
                        + Math.max(1, stmt.getStop().getText().length());
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

    /**
     * Whether the walk is seeding a self-recursive function's result (operators on its provisional
     * result stay quiet).
     */
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
        return slots == SlotInference.DEFS
                ? defWidenings
                : slots == SlotInference.MEMBERS ? memberWidenings : paramWidenings;
    }

    Map<ParserRuleContext, Type> inferred(int slots) {
        return slots == SlotInference.DEFS
                ? inferredDefs
                : slots == SlotInference.MEMBERS ? inferredMembers : inferredParams;
    }

    Narrowing narrowing() {
        return narrowing;
    }

    TypeEnv env() {
        return env;
    }

    void setEnv(TypeEnv next) {
        this.env = next;
    }

    Set<ParserRuleContext> capturedReassigned() {
        return capturedReassigned;
    }

    boolean isCapturedReassigned(ParserRuleContext decl) {
        return capturedReassigned.contains(decl)
                || (pass.session != null && ScopeAnalysis.isVar(decl) && !inFile(decl));
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
        }
        for (BlockScopes.Occurrence occurrence : pass.occurrences) {
            occurrenceScopes.put(occurrence.ctx(), occurrence.scope());
        }
        for (BlockScopes.PendingAssignment assignment : pass.assignments) {
            if (resolveName(null, assignment.name(), assignment.scope())
                    instanceof ParserRuleContext declaration) {
                if (!Objects.equals(
                        callableScope(assignment.scope()), callableScope(scopeOf(declaration)))) {
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
        JavaNullability.runWithContext(
                effectChecker.localAxioms(),
                pass.hostEffects.bytecodeEffectAnalysis(),
                () -> {
                    if (demoteSamMemberFields()) {
                        pass.analysis.setResolution(new Resolver(pass).run());
                    }
                    slots.rank(file);
                    slots.converge(file);
                    // Fields have settled : record kinds can be cached while effects are inferred.
                    modalKinds.setCaching(true);
                    effectChecker.inferAll(file);
                    modalKinds.setCaching(false);
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
                    modalKinds.setCaching(true);
                    effectChecker.verify(file);
                    modalKinds.setCaching(false);
                    diagnostics.addAll(effectDiagnostics);
                    for (Diagnostic warning : warnings) {
                        pass.analysis.warn(warning.message(), warning.line(), warning.column());
                    }
                    for (Diagnostic diagnostic : diagnostics) {
                        pass.analysis.typeDiagnostic(
                                diagnostic.message(), diagnostic.line(), diagnostic.column());
                    }
                    LoweringAnalyzer.publish(pass, file, this);
                    TailCalls.publish(
                            pass.analysis,
                            file,
                            effectChecker::inferredNothrow,
                            effectChecker::loopNothrow);
                    this.builtSummary =
                            TypedModuleSummary.build(pass, this, new StaticRefs(pass, this));
                });
    }

    /**
     * The type of the file's last statement, for {@code :type} in the interactive mode
     * (docs/design/bip004_repl.md §9).
     */
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
        catchTargetTypes.clear();
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
        matchTyper.reset();
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
        diag.warn(message, at);
    }

    void analysisWarn(String message, ParserRuleContext at) {
        diag.analysisWarn(message, at);
    }

    boolean inFile(ParserRuleContext ctx) {
        return diag.inFile(ctx);
    }

    void walk(ParseTree tree) {
        diag.walk(tree);
    }

    private void checkMissingTypes(BabbleParser.FileContext file) {
        diag.checkMissingTypes(file);
    }

    Type isTargetType(BabbleParser.IsExpressionContext ctx) {
        return diag.isTargetType(ctx);
    }

    Type resolveIsTarget(BabbleParser.IsExpressionContext ctx) {
        return diag.resolveIsTarget(ctx);
    }

    boolean isUncheckedArrayCheck(Type source, Type target) {
        return diag.isUncheckedArrayCheck(source, target);
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
            if (pass.scopeNodes.containsKey(p)
                    || pass.session != null && pass.session.scopeNodes.containsKey(p)) {
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

    private final Map<BabbleParser.AxiomExpressionContext, Type.Function> babbleAxiomTypes =
            new IdentityHashMap<>();

    /**
     * Resolves a scoped Babble-name axiom ({@code axiom foo: fn ...}) in {@code scope} or an
     * enclosing scope.
     */
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

    private Type.Function parseBabbleAxiom(
            BabbleParser.AxiomExpressionContext ctx, ScopeNode scope, boolean reportErrors) {
        boolean isWasm = AxiomData.isWasmImportAxiom(ctx);
        if (!AxiomData.isBabbleNameAxiom(ctx) && !isWasm) {
            return null;
        }
        if (!reportErrors && babbleAxiomTypes.containsKey(ctx)) {
            return babbleAxiomTypes.get(ctx);
        }
        AxiomData.ErrorSink sink = reportErrors ? this::warn : (msg, at) -> {};
        Type.Function fn =
                AxiomData.parseBabbleAxiomFunction(ctx, t -> annotator.annotation(t, scope), sink);
        if (fn != null) {
            babbleAxiomTypes.put(ctx, fn);
            if (isWasm) {
                recordWasmImportSpec(ctx, fn);
            }
        }
        return fn;
    }

    private void recordWasmImportSpec(BabbleParser.AxiomExpressionContext ctx, Type.Function fn) {
        String mod = AxiomData.wasmImportModule(ctx);
        String name = AxiomData.wasmImportName(ctx);
        int n = fn.parameters().size();
        PrimitiveKind[] pKinds = new PrimitiveKind[n];
        boolean[] pStrings = new boolean[n];
        for (int i = 0; i < n; i++) {
            Type pt = fn.parameters().get(i).type();
            pKinds[i] = PrimitiveKind.of(pt);
            pStrings[i] = pt == Type.STRING || Type.withoutNull(pt) == Type.STRING;
        }
        Type rt = fn.result();
        PrimitiveKind rKind = PrimitiveKind.of(rt);
        boolean rStr = rt == Type.STRING || Type.withoutNull(rt) == Type.STRING;
        boolean rVoid = rt == Type.NULL;
        int line = ctx.getStart() != null ? ctx.getStart().getLine() : 0;
        LoweringTargets.WasmImportSpec spec =
                new LoweringTargets.WasmImportSpec(
                        mod, name, pKinds, pStrings, rKind, rStr, rVoid, line);
        LoweringTargets.WasmImportSpec existing = pass.analysis.lowering().wasmImport(mod, name);
        if (existing != null && !existing.sameSignature(spec)) {
            warn("Conflicting wasm import signature : wasm." + mod + "." + name, ctx);
            return;
        }
        pass.analysis.lowering().addWasmImport(spec);
    }

    /**
     * Resolves {@code wasm} to a synthetic typed module namespace populated from scoped {@code
     * axiom wasm.<mod>.<name>} declarations ({@code BIP025-D9}).
     */
    Type resolveWasmNamespace(ScopeNode scope) {
        Map<String, Map<String, Type>> mods = new java.util.LinkedHashMap<>();
        for (ScopeNode s = scope; s != null; s = s.parent) {
            for (var modEntry : s.wasmAxioms.entrySet()) {
                Map<String, Type> fnMap =
                        mods.computeIfAbsent(
                                modEntry.getKey(), k -> new java.util.LinkedHashMap<>());
                for (var fnEntry : modEntry.getValue().entrySet()) {
                    if (!fnMap.containsKey(fnEntry.getKey())) {
                        Type.Function fn = parseBabbleAxiom(fnEntry.getValue(), s, false);
                        if (fn != null) {
                            fnMap.put(fnEntry.getKey(), fn);
                        }
                    }
                }
            }
        }
        if (mods.isEmpty()) {
            return null;
        }
        Map<String, Type> topMembers = new java.util.LinkedHashMap<>();
        for (var e : mods.entrySet()) {
            topMembers.put(
                    e.getKey(),
                    new Type.Module(ModuleInterface.synthetic("wasm." + e.getKey(), e.getValue())));
        }
        return new Type.Module(ModuleInterface.synthetic("wasm", topMembers));
    }

    /**
     * What a name refers to from a scope : a def or parameter declaration, or null for a name the
     * analysis doesn't know (the script's bindings and the implicits).
     */
    static Object resolveName(ParserRuleContext occurrence, String name, ScopeNode scope) {
        for (ScopeNode s = scope; s != null; s = s.parent) {
            ParserRuleContext declaration = s.names.get(name);
            if (declaration != null
                    && !(occurrence != null
                            && ScopeAnalysis.isParamDecl(declaration)
                            && notBoundYet(occurrence, s, declaration))) {
                return declaration;
            }
        }
        return null;
    }

    /** A parameter default doesn't see the parameters from its own onwards (as in Resolver). */
    private static boolean notBoundYet(
            ParserRuleContext occurrence, ScopeNode scope, ParserRuleContext declaration) {
        for (ParserRuleContext p = occurrence; p != null && p != scope.owner; p = p.getParent()) {
            if (p instanceof BabbleParser.FunctionLiteralContext) {
                return false;
            }
            if (ScopeAnalysis.isParamDecl(p) && p.getParent().getParent() == scope.owner) {
                List<? extends ParserRuleContext> parameters =
                        p instanceof BabbleParser.ClassParamContext
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
        if (ScopeAnalysis.isParamDecl(declaration)
                && ScopeAnalysis.paramType(declaration) != null) {
            Type declared =
                    annotator.annotation(
                            ScopeAnalysis.paramType(declaration), scopeOf(declaration));
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
            if (literal != null
                    && (inferringResult.contains(literal)
                            || provisionalResults.containsKey(literal))) {
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
                        && !ScopeAnalysis.isVar(def)
                        && (unwrapFunctionLiteral(def.value) != null
                                || def.value instanceof BabbleParser.OverloadExpressionContext))) {
            return inferredDefs.get(declaration);
        }
        if (!inProgress.add(declaration)) {
            if (declaration instanceof BabbleParser.DefExpressionContext def
                    && def.type() == null) {
                BabbleParser.FunctionLiteralContext literal = unwrapFunctionLiteral(def.value);
                if (literal != null) {
                    if (inferringResult.contains(literal)
                            || provisionalResults.containsKey(literal)) {
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
                    if (literal != null
                            && (inferringResult.contains(literal)
                                    || provisionalResults.containsKey(literal))) {
                        return recursiveSignature(literal);
                    }
                    Type valueType = synth(def.value);
                    type = inferred(valueType);
                }
            } else if (ScopeAnalysis.isParamDecl(declaration)
                    && ScopeAnalysis.paramDefault(declaration) != null) {
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
    Type inferred(Type type) {
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

    Type type(ParserRuleContext ctx, Type expected, Role role) {
        if (ctx == null || !isLocal(ctx)) {
            return Type.ERROR;
        }
        Type cached = types.get(ctx);
        if (cached != null) {
            return cached;
        }
        if (!inProgress.add(ctx)) {
            if (ctx instanceof BabbleParser.FunctionLiteralContext literal
                    && (inferringResult.contains(literal)
                            || provisionalResults.containsKey(literal))) {
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

    private Type.Function recordFunctionLiteral(
            Type.Function fn, BabbleParser.FunctionLiteralContext literal) {
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
                new Type.Function(
                        tparams,
                        params,
                        result,
                        literalEffectSet(literal),
                        TypeAnnotations.resolveForwardedParams(literal.effectPrefix(), params),
                        TypeAnnotations.resolveCapturedCallings(literal.effectPrefix(), params)
                                .keySet()),
                literal);
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
        Set<Integer> forwarded =
                TypeAnnotations.resolveForwardedParams(literal.effectPrefix(), params);
        Set<String> captured =
                TypeAnnotations.resolveCapturedCallings(literal.effectPrefix(), params).keySet();
        if (provisionalResults.containsKey(literal)) {
            return recordFunctionLiteral(
                    new Type.Function(
                            tparams,
                            params,
                            provisionalResults.get(literal),
                            effs,
                            forwarded,
                            captured),
                    literal);
        }
        if (!inferenceStack.isEmpty() && inferenceStack.peek() == literal) {
            recursiveFunctions.add(literal);
            Type result = slots.result(literal);
            if (result == null) {
                result = Type.NOTHING;
            }
            return recordFunctionLiteral(
                    new Type.Function(tparams, params, result, effs, forwarded, captured), literal);
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
        return recordFunctionLiteral(
                new Type.Function(tparams, params, Type.ERROR, effs, forwarded, captured), literal);
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
            EffectChecker.CallableSummary remoteSummary =
                    pass.session.callableSummaries.get(literal);
            if (remoteSummary != null) {
                return remoteSummary.effects();
            }
        }
        return effectChecker.parseFunctionPrefix(literal, checkingEffects).toEffectSet();
    }

    static BabbleParser.DefExpressionContext owningDef(
            BabbleParser.FunctionLiteralContext literal) {
        return owningDefExpr(literal);
    }

    static BabbleParser.DefExpressionContext owningDefExpr(ParserRuleContext expr) {
        ParserRuleContext child = expr;
        while (child.getParent() instanceof BabbleParser.BlockExpressionContext block
                && block.block().expression().size() == 1) {
            child = block;
        }
        return child.getParent() instanceof BabbleParser.DefExpressionContext def
                        && def.value == child
                ? def
                : null;
    }

    static ParserRuleContext ownerOfParam(ParserRuleContext parameter) {
        ParserRuleContext parent = parameter.getParent();
        return parent != null ? parent.getParent() : null;
    }

    boolean isExported(BabbleParser.DefExpressionContext def) {
        return callableTyper.isExported(def);
    }

    boolean isInferrableCallable(ParserRuleContext owner) {
        return callableTyper.isInferrableCallable(owner);
    }

    Type restElementType(BabbleParser.FnParamContext parameter, ScopeNode scope) {
        return callableTyper.restElementType(parameter, scope);
    }

    void typeDefaultValues(BabbleParser.FunctionLiteralContext literal) {
        callableTyper.typeDefaultValues(literal);
    }

    void applyContextualType(BabbleParser.FunctionLiteralContext literal, Type expected) {
        callableTyper.applyContextualType(literal, expected);
    }

    Type expectedReturnType(BabbleParser.FunctionLiteralContext function) {
        return callableTyper.expectedReturnType(function);
    }

    Type inferFunctionResult(BabbleParser.FunctionLiteralContext literal) {
        return callableTyper.inferFunctionResult(literal);
    }

    void clearSubtreeTypes(ParseTree tree) {
        callableTyper.clearSubtreeTypes(tree);
    }

    boolean isGenericFunctionSelector(BabbleParser.SelectorContext selector) {
        return callableTyper.isGenericFunctionSelector(selector);
    }

    void checkResult(BabbleParser.FunctionLiteralContext function) {
        callableTyper.checkResult(function);
    }

    void subsumes(ParserRuleContext site, Type actual, Type expected) {
        callableTyper.subsumes(site, actual, expected);
    }

    void subsumes(ParserRuleContext site, ParserRuleContext target, Type actual, Type expected) {
        callableTyper.subsumes(site, target, actual, expected);
    }

    void subsumesBorrow(ParserRuleContext site, Type actual, Type expected) {
        callableTyper.subsumesBorrow(site, actual, expected);
    }

    void coerce(ParserRuleContext site, Type actual, Type target) {
        callableTyper.coerce(site, actual, target);
    }

    void coerceSam(ParserRuleContext site, Type actual, Type target) {
        callableTyper.coerceSam(site, actual, target);
    }

    boolean demoteSamMemberFields() {
        return callableTyper.demoteSamMemberFields();
    }

    boolean isExactOverloadSetExpr(ParserRuleContext expr) {
        return callableTyper.isExactOverloadSetExpr(expr);
    }

    void flowInto(
            ParserRuleContext target,
            boolean explicit,
            Type valueType,
            ParserRuleContext valueNode,
            ParserRuleContext at) {
        callableTyper.flowInto(target, explicit, valueType, valueNode, at);
    }

    static BabbleParser.FunctionLiteralContext unwrapFunctionLiteral(
            BabbleParser.ExpressionContext expr) {
        return CallableTyper.unwrapFunctionLiteral(expr);
    }

    List<Type.Parameter> functionParameters(BabbleParser.FunctionLiteralContext literal) {
        return callableTyper.functionParameters(literal);
    }

    void demoteMemberToField(
            TypeNode owner,
            TypeNode.Member member,
            BabbleParser.DefExpressionContext def,
            boolean isStatic) {
        callableTyper.demoteMemberToField(owner, member, def, isStatic);
    }

    JavaTypeBridge.JavaSamResolution.Valid resolveSamTargetForAs(Type actual, Type target) {
        return callableTyper.resolveSamTargetForAs(actual, target);
    }

    JavaTypeBridge.JavaSamResolution.Valid resolveSamTargetForFlow(Type actual, Type target) {
        return callableTyper.resolveSamTargetForFlow(actual, target);
    }

    List<BabbleParser.FunctionLiteralContext> resolveOverloadLiterals(
            BabbleParser.ExpressionContext expr) {
        return callableTyper.resolveOverloadLiterals(expr);
    }

    void reportTypeMismatch(Type actual, Type expected, ParserRuleContext at) {
        callableTyper.reportTypeMismatch(actual, expected, at);
    }

    Type thisType(ScopeNode scope) {
        return callableTyper.thisType(scope);
    }

    void recordInferredDef(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        callableTyper.recordInferredDef(decl, valueType, at);
    }

    void recordInferredMember(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        callableTyper.recordInferredMember(decl, valueType, at);
    }

    void recordInferredParam(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        callableTyper.recordInferredParam(decl, valueType, at);
    }

    void checkSelectorEscape(BabbleParser.SelectorContext ctx, Type result, Role role) {
        callableTyper.checkSelectorEscape(ctx, result, role);
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

    /**
     * The expression rules, each typing its construct against an {@code expected} type (or null) in
     * a {@code role}.
     */
    final class Typer {
        Type typeOf(ParserRuleContext ctx, Type expected, Role role) {
            return switch (ctx) {
                case BabbleParser.IfExpressionContext c -> type(c.ifExpr(), expected, role);
                case BabbleParser.IfExprContext c -> visitIfExpr(c, expected, role);
                case BabbleParser.MatchExpressionContext c ->
                        matchTyper.typeMatch(c, expected, role);
                case BabbleParser.DefExpressionContext c -> visitDefExpression(c, expected, role);
                case BabbleParser.WhileExpressionContext c ->
                        visitWhileExpression(c, expected, role);
                case BabbleParser.TypeAliasExpressionContext c -> visitTypeAlias(c);
                case BabbleParser.AxiomExpressionContext c -> {
                    if (AxiomData.isWasmImportAxiom(c)) {
                        parseBabbleAxiom(c, scopeOf(c), checkingEffects);
                    } else if (AxiomData.isBabbleNameAxiom(c) && checkingEffects) {
                        parseBabbleAxiom(c, scopeOf(c), true);
                    }
                    yield Type.NULL;
                }
                case BabbleParser.ForExpressionContext c -> visitForExpression(c, expected, role);
                case BabbleParser.TryExpressionContext c -> visitTryExpression(c, expected, role);
                case BabbleParser.ClassExpressionContext c ->
                        visitClassExpression(c, expected, role);
                case BabbleParser.InterfaceExpressionContext c ->
                        visitInterfaceExpression(c, expected, role);
                case BabbleParser.ObjectExpressionContext c ->
                        visitObjectExpression(c, expected, role);
                case BabbleParser.ThisExpressionContext c -> visitThisExpression(c, expected, role);
                case BabbleParser.SuperExpressionContext c ->
                        visitSuperExpression(c, expected, role);
                case BabbleParser.SelectorContext c -> visitSelector(c, expected, role);
                case BabbleParser.NonNullAssertionContext c ->
                        visitNonNullAssertion(c, expected, role);
                case BabbleParser.CoreScalarRefContext c -> visitCoreScalarRef(c, expected, role);
                case BabbleParser.CoreCollectionContext c -> visitCoreCollection(c, expected, role);
                case BabbleParser.CoreExceptionRefContext c ->
                        visitCoreExceptionRef(c, expected, role);
                case BabbleParser.TypeApplyContext c -> visitTypeApply(c, expected, role);
                case BabbleParser.CallContext c -> visitCall(c, expected, role);
                case BabbleParser.BlockExpressionContext c ->
                        visitBlockExpression(c, expected, role);
                case BabbleParser.BlockContext c -> visitBlock(c, expected, role);
                case BabbleParser.NegateContext c -> visitNegate(c, expected, role);
                case BabbleParser.BooleanNotContext c -> visitBooleanNot(c, expected, role);
                case BabbleParser.BinaryOpContext c -> visitBinaryOp(c, expected, role);
                case BabbleParser.RangeExpressionContext c ->
                        visitRangeExpression(c, expected, role);
                case BabbleParser.IsExpressionContext c -> visitIsExpression(c, expected, role);
                case BabbleParser.AsExpressionContext c -> visitAsExpression(c, expected, role);
                case BabbleParser.BooleanOpContext c -> visitBooleanOp(c, expected, role);
                case BabbleParser.FunctionLiteralContext c ->
                        visitFunctionLiteral(c, expected, role);
                case BabbleParser.OverloadExpressionContext c ->
                        visitOverloadExpression(c, expected, role);
                case BabbleParser.AssignExpressionContext c ->
                        visitAssignExpression(c, expected, role);
                case BabbleParser.NullContext c -> visitNull(c, expected, role);
                case BabbleParser.BooleanContext c -> visitBoolean(c, expected, role);
                case BabbleParser.RecurseContext c -> visitRecurse(c, expected, role);
                case BabbleParser.LongLiteralContext c -> visitLongLiteral(c, expected, role);
                case BabbleParser.IntegerContext c -> visitInteger(c, expected, role);
                case BabbleParser.FloatLiteralContext c -> visitFloatLiteral(c, expected, role);
                case BabbleParser.DoubleContext c -> visitDouble(c, expected, role);
                case BabbleParser.CharLiteralContext c -> visitCharLiteral(c, expected, role);
                case BabbleParser.StringContext c -> visitString(c, expected, role);
                case BabbleParser.ReturnExpressionContext c ->
                        visitReturnExpression(c, expected, role);
                case BabbleParser.ForeverExpressionContext c ->
                        type(c.expression(), expected, role);
                case BabbleParser.BreakExpressionContext c ->
                        visitBreakExpression(c, expected, role);
                case BabbleParser.ContinueExpressionContext c ->
                        visitContinueExpression(c, expected, role);
                case BabbleParser.ThrowExpressionContext c ->
                        visitThrowExpression(c, expected, role);
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
                    warn(
                            "Type error : the branches of if have different types : "
                                    + thenType
                                    + " and "
                                    + elseType,
                            ctx);
                }
                return Type.ERROR;
            }
            coerce(ctx.thenBlock, thenType, joined);
            coerce(otherwise, elseType, joined);
            return joined;
        }

        private Type visitDefExpression(
                BabbleParser.DefExpressionContext ctx, Type expected, Role role) {
            LoweringTargets.DefKind kind = pass.analysis.lowering().defTarget(ctx).kind();
            if (kind == LoweringTargets.DefKind.REQUIRED) {
                return Type.NULL;
            }
            Type declared = ctx.type() != null ? annotator.declaredType(ctx) : null;
            Type value = ctx.value != null ? check(ctx.value, declared) : Type.NULL;
            ParserRuleContext errorSite = ctx.value != null ? ctx.value : ctx;
            if (declared != null) {
                if (kind == LoweringTargets.DefKind.DEFAULT
                        && declared != Type.ERROR
                        && ((ctx.value instanceof BabbleParser.FunctionLiteralContext
                                        && !(declared instanceof Type.Function))
                                || (ctx.value instanceof BabbleParser.OverloadExpressionContext
                                        && !(declared instanceof Type.Overloaded)))) {
                    if (algebra.assignable(value, declared)) {
                        warn(
                                "An interface default must have a function type : "
                                        + ctx.name.getText(),
                                ctx.type());
                    } else {
                        subsumes(errorSite, ctx, value, declared);
                    }
                    samTargets.remove(ctx);
                    return Type.ERROR;
                }
                subsumes(errorSite, ctx, value, declared);
                if ((kind == LoweringTargets.DefKind.MEMBER
                                || kind == LoweringTargets.DefKind.STATIC)
                        && samTargets.containsKey(ctx)) {
                    ScopeNode bodyScope = scopeOf(ctx);
                    if (bodyScope != null && bodyScope.type != null) {
                        boolean isStatic = kind == LoweringTargets.DefKind.STATIC;
                        TypeNode.Member member =
                                isStatic
                                        ? bodyScope.type.statics.get(ctx.name.getText())
                                        : bodyScope.type.members.get(ctx.name.getText());
                        if (member != null && member.isMethod()) {
                            demoteMemberToField(bodyScope.type, member, ctx, isStatic);
                            pass.analysis
                                    .hierarchy()
                                    .setShape(bodyScope.type.ctx, bodyScope.type.shape());
                            needsReResolve = true;
                        }
                    }
                }
            } else {
                flowInto(
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
                BabbleParser.WhileExpressionContext ctx, Type expected, Role role) {
            env = env.evictAll(narrowing.assignedLocals(ctx));
            synth(ctx.test);
            TypeEnv afterTest = env;
            TypeEnv.Refinement refinement = narrowing.extractRefinement(ctx.test);
            Type blockExpected =
                    (expected == Type.ANY || expected == Type.NULLABLE_ANY) ? expected : null;
            env = afterTest.apply(refinement.whenTrue());
            LoopContext loop =
                    new LoopContext(
                            expected != null && expected != Type.ERROR && role.isUsed()
                                    ? expected
                                    : null);
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

        private Type visitForExpression(
                BabbleParser.ForExpressionContext ctx, Type expected, Role role) {
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
                        JavaTypeBridge.JavaSamResolution.Valid valid =
                                resolveSamTargetForFlow(elemType, declared);
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
            Type blockExpected =
                    (expected == Type.ANY || expected == Type.NULLABLE_ANY) ? expected : null;
            LoopContext loop =
                    new LoopContext(
                            expected != null && expected != Type.ERROR && role.isUsed()
                                    ? expected
                                    : null);
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

        private Type finishLoop(
                ParserRuleContext loopCtx,
                BabbleParser.BlockContext loopBlock,
                String loopName,
                Type bodyType,
                LoopContext loop,
                Type expected,
                Role role) {
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
                    warn(
                            "Type error : the branches of "
                                    + loopName
                                    + " have different types : "
                                    + bodyType
                                    + " and null",
                            loopCtx);
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

        private ForIterableInfo resolveIterableInfo(
                Type iterType, BabbleParser.ForExpressionContext ctx) {
            if (iterType == null || iterType == Type.ERROR || iterType == Type.NOTHING) {
                return null;
            }
            if (Type.containsNull(iterType)) {
                warn("Type error : cannot iterate over nullable type " + iterType, ctx.iterable);
                return null;
            }
            ForIterableInfo resolved = members.resolveNonNullIterable(iterType);
            if (resolved == null) {
                if (Type.rangeElement(iterType) != null) {
                    warn(
                            "Not iterable : "
                                    + TypeDisplay.display(TypeAlgebra.unwrapFrozen(iterType))
                                    + " is not discrete",
                            ctx.iterable);
                } else {
                    warn("Not iterable : " + iterType, ctx.iterable);
                }
            }
            return resolved;
        }

        private Type visitClassExpression(
                BabbleParser.ClassExpressionContext ctx, Type expected, Role role) {
            if (ctx.classParameters() != null) {
                constructorParameters(pass.typeNodes.get(ctx));
            }
            return new Type.ClassValue(pass.typeNodes.get(ctx));
        }

        private Type visitInterfaceExpression(
                BabbleParser.InterfaceExpressionContext ctx, Type expected, Role role) {
            return new Type.ClassValue(pass.typeNodes.get(ctx));
        }

        private Type visitObjectExpression(
                BabbleParser.ObjectExpressionContext ctx, Type expected, Role role) {
            return new Type.Instance(pass.typeNodes.get(ctx));
        }

        private Type visitThisExpression(
                BabbleParser.ThisExpressionContext ctx, Type expected, Role role) {
            Type result = thisType(occurrenceScopes.get(ctx));
            if (result instanceof Type.ClassValue cv
                    && cv.node().ctx instanceof BabbleParser.ClassExpressionContext cls
                    && inFile(cls)
                    && !role.isClassSafe()) {
                nextEscapingTargets.add(cls);
            }
            return result;
        }

        private Type visitSuperExpression(
                BabbleParser.SuperExpressionContext ctx, Type expected, Role role) {
            return Type.ERROR;
        }

        /**
         * {@code def name = type T} (BIP023) : only as a def's value, which takes no annotation of
         * its own.
         */
        private Type visitTypeAlias(BabbleParser.TypeAliasExpressionContext ctx) {
            if (!(ctx.getParent() instanceof BabbleParser.DefExpressionContext def)
                    || def.value != ctx) {
                warn(
                        "A type alias is the value of a def : "
                                + ctx.getText().replaceFirst("^type", "type "),
                        ctx);
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
            if (result instanceof Type.Function fn
                    && !fn.typeParameters().isEmpty()
                    && !role.isCallee()) {
                result = callSolver.instantiateFunctionValue(fn, ctx, expected);
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
                Object resolved = resolveName(ctx, name, occurrenceScopes.get(ctx));
                if (resolved instanceof ParserRuleContext declaration) {
                    Type narrowed = narrowing.narrowedType(declaration);
                    return narrowed != null ? narrowed : typeOfDeclaration(declaration);
                }
                Type.Function babbleAx = resolveBabbleAxiom(name, occurrenceScopes.get(ctx));
                if (babbleAx != null) {
                    return babbleAx;
                }
                if ("wasm".equals(name)) {
                    Type wasmNs = resolveWasmNamespace(occurrenceScopes.get(ctx));
                    if (wasmNs != null) {
                        return wasmNs;
                    }
                }
                return operators.implicitType(name);
            }
            if (receiver instanceof BabbleParser.SuperExpressionContext superExpr) {
                TypeChecks.Provider provider = pass.superProviders.get(superExpr);
                if (provider != null) {
                    Type selfType = thisType(occurrenceScopes.get(ctx));
                    if (provider.hasFunction() && provider.owner() instanceof TypeNode parentNode) {
                        TypeNode.Member defaultMember = parentNode.members.get(provider.name());
                        Type fnType =
                                defaultMember != null
                                        ? memberType(parentNode, defaultMember)
                                        : Type.ERROR;
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
                Type receiverType = synth(receiver, Role.SELECTOR_RECEIVER);
                if (isUnresolved(receiverType)) {
                    return Type.ERROR;
                }
                if (!canBeNull(receiverType)) {
                    warn("Not nullable : " + receiver.getText(), ctx);
                    return Type.ERROR;
                }
                Type target =
                        receiverType == Type.NULL ? Type.NULL : Type.withoutNull(receiverType);
                return members.member(
                        target,
                        name,
                        receiver instanceof BabbleParser.ThisExpressionContext,
                        ctx,
                        receiver);
            }
            return members.member(
                    synth(receiver, Role.SELECTOR_RECEIVER),
                    name,
                    receiver instanceof BabbleParser.ThisExpressionContext,
                    ctx,
                    receiver);
        }

        private Type visitCall(BabbleParser.CallContext ctx, Type expected, Role role) {
            if (ctx.expression() instanceof BabbleParser.SelectorContext safeSelector
                    && safeSelector.safe != null) {
                Type callee = typer.selectUninstantiated(safeSelector);
                types.put(safeSelector, callee == Type.ERROR ? Type.ERROR : Type.nullable(callee));
                Type result = callSolver.call(callee, ctx, expected, true);
                for (BabbleParser.CallParameterContext argument :
                        ctx.callParameters().callParameter()) {
                    synth(argument.expression());
                }
                return result == Type.ERROR ? Type.ERROR : Type.nullable(result);
            }
            Type callee = synth(ctx.expression(), Role.CALLEE);
            ModuleInterface module = pass.modules.get(ctx);
            Type result;
            if (module != null) {
                for (BabbleParser.CallParameterContext argument :
                        ctx.callParameters().callParameter()) {
                    synth(argument.expression());
                }
                result = new Type.Module(module);
            } else if (isUnshadowedImport(ctx.expression())
                    && ctx.callParameters().callParameter().size() == 1
                    && ScopeAnalysis.callParamName(ctx.callParameters().callParameter(0)) == null
                    && ctx.callParameters().callParameter(0).expression()
                            instanceof BabbleParser.StringContext string) {
                synth(string);
                String target = org.babblelang.parser.StringLiterals.decode(string);
                result = target.endsWith(".ba") ? Type.ANY : new Type.JavaPackage(target);
            } else {
                result = callSolver.call(callee, ctx, expected, true);
                for (BabbleParser.CallParameterContext argument :
                        ctx.callParameters().callParameter()) {
                    synth(argument.expression());
                }
            }
            return result;
        }

        private boolean isUnshadowedImport(BabbleParser.ExpressionContext expression) {
            if (expression instanceof BabbleParser.SelectorContext sel
                    && sel.expression() == null) {
                String name = sel.ID().getText();
                return (name.equals("import") || name.equals("importe"))
                        && resolveName(sel, name, occurrenceScopes.get(sel)) == null;
            }
            return false;
        }

        private Type visitBlockExpression(
                BabbleParser.BlockExpressionContext ctx, Type expected, Role role) {
            Role blockRole =
                    ctx.block().expression().size() == 1
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
                    type =
                            i == statements.size() - 1
                                    ? type(statement, expected, role)
                                    : stmt(statement);
                    if (i < statements.size() - 1
                            && statement instanceof BabbleParser.IfExpressionContext ifExprCtx) {
                        BabbleParser.IfExprContext ifCtx = ifExprCtx.ifExpr();
                        Type thenType = types.getOrDefault(ifCtx.thenBlock, Type.ERROR);
                        ParserRuleContext otherwise =
                                ifCtx.elseBlock != null ? ifCtx.elseBlock : ifCtx.elseIf;
                        Type elseType =
                                otherwise != null
                                        ? types.getOrDefault(otherwise, Type.ERROR)
                                        : Type.NULL;
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

        /**
         * As {@code Operators.negate} : byte/short/int -> int, long -> long, float -> float, double
         * -> double; folds negated literals.
         */
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
                return operators.operatorCall(
                        operand, Operators.NEGATE, null, null, ctx, ctx.operand.getText());
            }
            if (operand instanceof Type.Union union) {
                if (Type.containsNull(union)) {
                    operators.number(operand, ctx.operand);
                    return Type.ERROR;
                }
                boolean allNumericOrInstance =
                        union.members().stream()
                                .allMatch(
                                        m -> m instanceof Type.Instance || operators.isNumeric(m));
                if (!allNumericOrInstance) {
                    operators.number(operand, ctx.operand);
                    return Type.ERROR;
                }
                List<Type> results = new ArrayList<>(union.members().size());
                for (Type m : union.members()) {
                    if (m instanceof Type.Instance) {
                        Type r =
                                operators.operatorCall(
                                        m,
                                        Operators.NEGATE,
                                        null,
                                        null,
                                        ctx,
                                        ctx.operand.getText());
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

        private Type visitRangeExpression(
                BabbleParser.RangeExpressionContext ctx, Type expected, Role role) {
            Type expectedElem = null;
            if (expected != null && expected != Type.ERROR && expected != Type.NOTHING) {
                expectedElem = Type.rangeElement(Type.withoutNull(expected));
            }
            Type left = expectedElem != null ? check(ctx.left, expectedElem) : synth(ctx.left);
            // §3.4 : `1..10.step(5)` / `0..<n.reversed()` parses as `1..(10.step(5))` because
            // member
            // access binds tighter than `..`/`..<`. When the bound's receiver lacks the member and
            // `range of T` has it, suggest parenthesizing the range.
            BabbleParser.SelectorContext unparenSel = findUnparenthesizedRangeSelector(ctx.right);
            if (unparenSel != null) {
                String mName = ScopeAnalysis.selectorName(unparenSel);
                if (MemberTypes.RANGE_MEMBER_NAMES.contains(mName)) {
                    Type recvType = synth(unparenSel.expression());
                    if (recvType != Type.ERROR
                            && !isUnresolved(recvType)
                            && !members.hasMember(recvType, mName)) {
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
                                "("
                                        + ctx.left.getText()
                                        + ctx.op.getText()
                                        + recvText
                                        + ")"
                                        + suffix;
                        warn(
                                "No such member : "
                                        + mName
                                        + ". Parenthesize the range : "
                                        + suggestion,
                                ctx.right);
                        BabbleParser.ExpressionContext mark = ctx.right;
                        while (mark != null) {
                            types.put(mark, Type.ERROR);
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
            Type right = expectedElem != null ? check(ctx.right, expectedElem) : synth(ctx.right);
            if (left == Type.ERROR
                    || right == Type.ERROR
                    || isUnresolved(left)
                    || isUnresolved(right)) {
                return Type.ERROR;
            }
            if (Type.containsNull(left)) {
                warn("Range bound cannot be null : " + left, ctx.left);
                return Type.ERROR;
            }
            if (Type.containsNull(right)) {
                warn("Range bound cannot be null : " + right, ctx.right);
                return Type.ERROR;
            }
            if (Type.isNumericScalar(left) && Type.isNumericScalar(right)) {
                int rank = Math.max(Type.numericRank(left), Type.numericRank(right));
                Type elem = Type.numericOfRank(rank);
                coerce(ctx.left, left, elem);
                coerce(ctx.right, right, elem);
                return new Type.JavaInstance(org.babblelang.runtime.Range.class, List.of(elem));
            }
            if (left == Type.CHAR && right == Type.CHAR) {
                return new Type.JavaInstance(
                        org.babblelang.runtime.Range.class, List.of(Type.CHAR));
            }
            if (OperatorTypes.comparable(left, right)) {
                Type elem;
                if (left.equals(right)) {
                    elem = left;
                } else if (algebra.assignable(left, right)) {
                    elem = right;
                } else if (algebra.assignable(right, left)) {
                    elem = left;
                } else {
                    elem = algebra.join(left, right);
                }
                if (elem != null && elem != Type.ERROR) {
                    return new Type.JavaInstance(org.babblelang.runtime.Range.class, List.of(elem));
                }
            }
            warn("Not comparable : " + left + " and " + right, ctx);
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
                BabbleParser.IsExpressionContext ctx, Type expected, Role role) {
            Type source = synth(ctx.left);
            Type target = resolveIsTarget(ctx);
            if (target != Type.ERROR && isUncheckedArrayCheck(source, target)) {
                analysisWarn("Unchecked cast : " + target, ctx);
            }
            return Type.BOOL;
        }

        private Type visitNonNullAssertion(
                BabbleParser.NonNullAssertionContext ctx, Type expected, Role role) {
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
            pass.analysis.lowering().setNonNullAssertionTarget(ctx, nonNull.toString());
            return nonNull;
        }

        private Type visitCoreScalarRef(
                BabbleParser.CoreScalarRefContext ctx, Type expected, Role role) {
            String name = ctx.coreScalarKeyword().getText();
            Type.Function babbleAx = resolveBabbleAxiom(name, scopeOf(ctx));
            return babbleAx != null ? babbleAx : operators.implicitType(name);
        }

        private Type visitCoreCollection(
                BabbleParser.CoreCollectionContext ctx, Type expected, Role role) {
            return new Type.CoreCollection(ctx.coreCollectionKeyword().getText());
        }

        private Type visitCoreExceptionRef(
                BabbleParser.CoreExceptionRefContext ctx, Type expected, Role role) {
            return new Type.JavaClassValue(org.babblelang.runtime.BabbleException.class);
        }

        private Type visitTypeApply(BabbleParser.TypeApplyContext ctx, Type expected, Role role) {
            Type target = synth(ctx.expression(), Role.CALLEE);
            return callSolver.applyTypes(ctx, target);
        }

        private Type visitAsExpression(
                BabbleParser.AsExpressionContext ctx, Type expected, Role role) {
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
                JavaTypeBridge.JavaSamResolution.Valid valid =
                        resolveSamTargetForAs(source, target);
                if (valid != null) {
                    asSamTargets.put(ctx, valid.toTarget());
                }
            }
            pass.analysis.lowering().setCheckedType(ctx, LoweringAnalyzer.descriptor(target));
            return target;
        }

        private Type visitBooleanOp(BabbleParser.BooleanOpContext ctx, Type expected, Role role) {
            synth(ctx.left);
            TypeEnv saved = env;
            TypeEnv.Refinement leftRef = narrowing.extractRefinement(ctx.left);
            Map<ParserRuleContext, Type> rightGuard =
                    ctx.op.getType() == BabbleLexer.AND ? leftRef.whenTrue() : leftRef.whenFalse();
            if (!rightGuard.isEmpty() && inFile(ctx)) {
                pass.analysis.lowering().markLogicRightNarrowed(ctx);
            }
            env = saved.apply(rightGuard);
            try {
                synth(ctx.right);
            } finally {
                env = TypeEnv.joinBranches(saved, env, saved);
            }
            return Type.BOOL;
        }

        private Type visitFunctionLiteral(
                BabbleParser.FunctionLiteralContext ctx, Type expected, Role role) {
            if (inFile(ctx)) {
                functions.add(ctx);
            }
            Type.Function sig = signature(ctx, expected);
            types.put(ctx, sig);
            if (ctx.functionBlock != null
                    && !types.containsKey(ctx.functionBlock)
                    && (ctx.type() == null || inferenceStack.isEmpty())) {
                TypeEnv saved = env;
                env = saved.enterCallable();
                try {
                    check(ctx.functionBlock, expectedReturnType(ctx));
                } finally {
                    env = saved;
                }
            }
            return sig;
        }

        private Type visitOverloadExpression(
                BabbleParser.OverloadExpressionContext ctx, Type expected, Role role) {
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
                        List<BabbleParser.FunctionLiteralContext> nestedLits =
                                resolveOverloadLiterals(op);
                        if (nestedLits != null
                                && nestedLits.size() == nested.alternatives().size()) {
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
            // Type literal bodies after registering the overload set type so recursive calls to the
            // set resolve.
            for (BabbleParser.FunctionLiteralContext lit : pendingBodies) {
                if (lit.functionBlock != null && !types.containsKey(lit.functionBlock)) {
                    TypeEnv saved = env;
                    env = saved.enterCallable();
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
            if (ctx.getParent() instanceof BabbleParser.DefExpressionContext def
                    && def.value == ctx) {
                return def.name.getText();
            }
            return "overload";
        }

        private LValue resolveLValue(
                BabbleParser.AssignExpressionContext ctx, boolean readCurrent) {
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
                    Type declared =
                            ScopeAnalysis.isParamDecl(declaration)
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
                    TypeNode ownerNode =
                            receiver instanceof Type.Instance inst
                                    ? inst.node()
                                    : (receiver instanceof Type.ClassValue cv ? cv.node() : null);
                    current =
                            receiver instanceof Type.Instance inst
                                    ? algebra.substitute(
                                            memberType(ownerNode, member),
                                            algebra.instanceEnv(inst))
                                    : memberType(ownerNode, member);
                }
            }
            if (declared == null
                    && member != null
                    && !member.hasFunction
                    && member.declaration instanceof BabbleParser.DefExpressionContext def
                    && isLocal(def)
                    && inFile(def)) {
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
                case LValue.Implicit ignored -> {}
            }
        }

        private Type visitAssignExpression(
                BabbleParser.AssignExpressionContext ctx, Type expected, Role role) {
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
            Type value = check(ctx.value, declared);
            writeLValue(ctx, target, value, ctx.value);
            return value;
        }

        /**
         * {@code x op= v} : typed as {@code x = x op v}, the operator's result checked against what
         * the target declares.
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
            Type value = synth(ctx.value);
            Type result =
                    operators.binaryOp(
                            operators.arithmeticToken(operator),
                            operator,
                            ctx,
                            SyntaxText.targetText(ctx),
                            ctx,
                            current,
                            ctx.value.getText(),
                            ctx.value,
                            value);
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

        private Type checkIntLiteral(
                ParserRuleContext ctx, String digits, boolean negated, Type expected) {
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

        private Type checkDoubleLiteral(
                ParserRuleContext ctx, String raw, boolean negated, Type expected) {
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

        private Type visitLongLiteral(
                BabbleParser.LongLiteralContext ctx, Type expected, Role role) {
            return checkLongLiteral(ctx, ctx.getText(), false);
        }

        private Type visitInteger(BabbleParser.IntegerContext ctx, Type expected, Role role) {
            return checkIntLiteral(ctx, ctx.getText(), false, expected);
        }

        private Type visitFloatLiteral(
                BabbleParser.FloatLiteralContext ctx, Type expected, Role role) {
            return checkFloatLiteral(ctx, ctx.getText(), false);
        }

        private Type visitDouble(BabbleParser.DoubleContext ctx, Type expected, Role role) {
            return checkDoubleLiteral(ctx, ctx.getText(), false, expected);
        }

        private Type visitCharLiteral(
                BabbleParser.CharLiteralContext ctx, Type expected, Role role) {
            return Type.CHAR;
        }

        private Type visitString(BabbleParser.StringContext ctx, Type expected, Role role) {
            return Type.STRING;
        }

        private Type visitReturnExpression(
                BabbleParser.ReturnExpressionContext ctx, Type expected, Role role) {
            BabbleParser.FunctionLiteralContext function = enclosingFunction(ctx);
            Type returnType = function != null ? expectedReturnType(function) : null;
            Type value = ctx.expression() != null ? check(ctx.expression(), returnType) : Type.NULL;
            if (function != null && function.type() != null) {
                Type declared = annotator.annotation(function.type(), scopeOf(function));
                subsumes(ctx.expression() != null ? ctx.expression() : ctx, ctx, value, declared);
            }
            return Type.NOTHING;
        }

        private Type visitBreakExpression(
                BabbleParser.BreakExpressionContext ctx, Type expected, Role role) {
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

        private Type visitContinueExpression(
                BabbleParser.ContinueExpressionContext ctx, Type expected, Role role) {
            return Type.NOTHING;
        }

        private Type visitThrowExpression(
                BabbleParser.ThrowExpressionContext ctx, Type expected, Role role) {
            return exceptionTyper.typeThrow(ctx, expected, role);
        }

        private Type visitTryExpression(
                BabbleParser.TryExpressionContext ctx, Type expected, Role role) {
            return exceptionTyper.typeTry(ctx, expected, role);
        }
    }

    static boolean isThrowableType(Type type) {
        return ExceptionTyper.isThrowableType(type);
    }

    static boolean mayBeJavaError(Type type) {
        return ExceptionTyper.mayBeJavaError(type);
    }

    static List<Type> flattenCatchUnion(Type type) {
        return ExceptionTyper.flattenCatchUnion(type);
    }

    boolean isCatchSubtype(Type a, Type b) {
        return exceptionTyper.isCatchSubtype(a, b);
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
        if (member.isOverload
                && member.declaration instanceof BabbleParser.DefExpressionContext def
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
        return node.ctx instanceof BabbleParser.ClassExpressionContext cls
                        && cls.classParameters() != null
                ? cls.classParameters().classParam()
                : null;
    }

    /**
     * A class's constructor parameters, or null when they are another file's that can't be typed.
     */
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
        EffectChecker.CallableSummary cs =
                (effectChecker != null && node != null)
                        ? effectChecker.constructorSummary(node)
                        : null;
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
            EffectChecker.ParamMods pm =
                    effectChecker != null
                            ? effectChecker.parseParamMods(parameter, checkingEffects)
                            : EffectChecker.ParamMods.NONE;
            boolean mut = cs != null && i < cs.mutParams().length ? cs.mutParams()[i] : pm.mut();
            boolean borrow =
                    cs != null && i < cs.borrowParams().length ? cs.borrowParams()[i] : false;
            boolean unique =
                    cs != null && i < cs.uniqueParams().length ? cs.uniqueParams()[i] : pm.unique();
            result.add(
                    new Type.Parameter(
                            parameter.ID().getText(),
                            type,
                            parameter.defaultValue != null,
                            i >= kwStart,
                            mut,
                            borrow,
                            pm.shared(),
                            unique));
        }
        return result;
    }

    Type parameterType(ParserRuleContext parameter, ScopeNode scope) {
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
        effectDiagnostics.add(
                new Diagnostic(
                        at.getStart().getLine(),
                        at.getStart().getCharPositionInLine() + 1,
                        message));
    }

    ScopeNode occurrenceScopeOf(ParserRuleContext ctx) {
        ScopeNode occ = occurrenceScopes.get(ctx);
        return occ != null ? occ : scopeOf(ctx);
    }

    boolean isReassigned(ParserRuleContext decl) {
        return ScopeAnalysis.isVar(decl);
    }

    AxiomData.AxiomTable exportedAxioms() {
        return effectChecker != null ? effectChecker.exportedAxioms() : AxiomData.AxiomTable.EMPTY;
    }
}
