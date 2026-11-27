package org.babblelang.analysis;

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
import org.babblelang.parser.BabbleParser;

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
    final ExpressionTyper typer = new ExpressionTyper(this);
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

    static final class LoopContext {
        final Type expected;
        final List<BabbleParser.BreakExpressionContext> breaks = new ArrayList<>();
        final List<Type> breakTypes = new ArrayList<>();
        boolean hasBareBreak;

        LoopContext(Type expected) {
            this.expected = expected;
        }
    }

    final Map<BabbleParser.ForExpressionContext, ForIterableInfo> forInfos =
            new IdentityHashMap<>();
    final Deque<LoopContext> loopStack = new ArrayDeque<>();

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
    final Map<BabbleParser.CallContext, Integer> resolvedConstructorIndices =
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

    void recordConstructorCall(BabbleParser.CallContext ctx, int ctorIndex, boolean delegating) {
        resolvedConstructorIndices.put(ctx, ctorIndex);
        pass.analysis.calls().setConstructorIndex(ctx, ctorIndex, delegating);
    }

    Integer resolvedConstructorIndex(BabbleParser.CallContext ctx) {
        return resolvedConstructorIndices.get(ctx);
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
    ImportedSummaries imported;

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
    final TypeVariance variance =
            new TypeVariance(
                    new TypeVariance.Lookup() {
                        @Override
                        public List<Type.TypeVar> typeParameters(TypeNode node) {
                            return annotator.typeVarsOf(node);
                        }

                        @Override
                        public Type parentType(TypeNode node, int index) {
                            return annotator.parentAnnotation(node, index);
                        }

                        @Override
                        public Type memberType(TypeNode owner, TypeNode.Member member) {
                            return varianceMemberType(owner, member);
                        }

                        @Override
                        public boolean isJavaMethodMut(
                                Class<?> owner, java.lang.reflect.Method method) {
                            return isJavaMethodMutForVariance(owner, method);
                        }
                    });
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

                        @Override
                        public boolean isBabbleParamCovariant(
                                TypeNode node, int paramIndex, boolean readOnly) {
                            return variance.isBabbleParamCovariant(node, paramIndex, readOnly);
                        }

                        @Override
                        public boolean isJavaParamCovariant(
                                Class<?> raw, int paramIndex, boolean readOnly) {
                            return variance.isJavaParamCovariant(raw, paramIndex, readOnly);
                        }
                    });

    private Type varianceMemberType(TypeNode owner, TypeNode.Member member) {
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
            Type cached = types.get(member.function);
            if (cached != null) {
                return cached;
            }
            return inProgress.contains(member.declaration) ? null : synth(member.function);
        }
        if (member.isOverload
                && member.declaration instanceof BabbleParser.DefExpressionContext def
                && def.value instanceof BabbleParser.OverloadExpressionContext ov) {
            Type cached = types.get(ov);
            if (cached != null) {
                return cached;
            }
            return inProgress.contains(def) ? null : synth(ov);
        }
        if (member.declaration instanceof BabbleParser.DefExpressionContext def) {
            return inferredMembers.get(def);
        }
        return Type.ERROR;
    }

    private boolean isJavaMethodMutForVariance(Class<?> owner, java.lang.reflect.Method method) {
        if (method.isAnnotationPresent(org.babblelang.effects.Mut.class)) {
            return true;
        }
        AxiomData.MemberAxiom ax =
                pass.hostEffects.builtinAxioms().methodAxiom(owner, method.getName(), method);
        if (ax != null && !ax.inferredEffects()) {
            return ax.effects().mutThis();
        }
        if (pass.hostEffects.bytecodeEffectAnalysis()) {
            BytecodeEffectAnalyzer.Result r =
                    BytecodeEffectAnalyzer.analyze(method, pass.hostEffects.builtinAxioms());
            return !r.effects().isUnconstrained() && r.effects().mutThis();
        }
        return false;
    }

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

    boolean capturesEnclosing(TypeNode node) {
        Resolution res = pass.analysis.resolution();
        return res != null && node != null && node.ctx != null && !res.captured(node.ctx).isEmpty();
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
                    FieldInitAnalysis.run(pass, file, this);
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
        resolvedConstructorIndices.clear();
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

    static BabbleParser.FunctionLiteralContext enclosingFunction(ParserRuleContext ctx) {
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

    Type.Function parseBabbleAxiom(
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
    static boolean notBoundYet(
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
    Type.Function signature(BabbleParser.FunctionLiteralContext literal, Type expected) {
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

    Type.Function recursiveSignature(BabbleParser.FunctionLiteralContext literal) {
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
        return parameterDecls(node, 0);
    }

    static List<? extends ParserRuleContext> parameterDecls(TypeNode node, int ctorIndex) {
        if (node == null) {
            return null;
        }
        if (ctorIndex >= 0 && ctorIndex < node.constructors.size()) {
            return node.constructors.get(ctorIndex).paramDecls;
        }
        return node.ctx instanceof BabbleParser.ClassExpressionContext cls
                        && cls.classParameters() != null
                ? cls.classParameters().classParam()
                : null;
    }

    int constructorCount(TypeNode node) {
        if (node == null) {
            return 0;
        }
        if (!node.constructors.isEmpty()) {
            return node.constructors.size();
        }
        List<TypedModuleSummary.ConstructorSignature> sigs = imported().constructorSignatures(node);
        if (sigs != null && !sigs.isEmpty()) {
            return sigs.size();
        }
        return 1;
    }

    boolean constructorIsPrivate(TypeNode node, int ctorIndex) {
        if (node == null || ctorIndex < 0) {
            return false;
        }
        if (ctorIndex < node.constructors.size()) {
            return node.constructors.get(ctorIndex).isPrivate;
        }
        List<TypedModuleSummary.ConstructorSignature> sigs = imported().constructorSignatures(node);
        if (sigs != null && ctorIndex < sigs.size()) {
            return sigs.get(ctorIndex).isPrivate();
        }
        return false;
    }

    /** A class's primary/index-0 constructor parameters, or empty when none. */
    List<Type.Parameter> constructorParameters(TypeNode node) {
        return constructorParameters(node, 0);
    }

    /** Parameters of constructor {@code ctorIndex} of {@code node} ({@code BIP040}). */
    List<Type.Parameter> constructorParameters(TypeNode node, int ctorIndex) {
        if (node == null) {
            return List.of();
        }
        if (!(node.ctx instanceof BabbleParser.ClassExpressionContext cls) || !isLocal(cls)) {
            List<TypedModuleSummary.ConstructorSignature> sigs =
                    imported().constructorSignatures(node);
            if (sigs != null && ctorIndex >= 0 && ctorIndex < sigs.size()) {
                return sigs.get(ctorIndex).params();
            }
            if (ctorIndex == 0) {
                List<Type.Parameter> imported = imported().constructorParams(node);
                return imported != null ? imported : List.of();
            }
            return List.of();
        }
        TypeNode.Constructor ctor =
                ctorIndex >= 0 && ctorIndex < node.constructors.size()
                        ? node.constructors.get(ctorIndex)
                        : null;
        if (ctor == null || ctor.isPrimary) {
            if (cls.classParameters() == null) {
                return List.of();
            }
            ScopeNode scope = scopeOf(cls);
            EffectChecker.CallableSummary cs =
                    effectChecker != null
                            ? effectChecker.constructorSummary(node, Math.max(0, ctorIndex))
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
                boolean mut =
                        cs != null && i < cs.mutParams().length ? cs.mutParams()[i] : pm.mut();
                boolean borrow =
                        cs != null && i < cs.borrowParams().length ? cs.borrowParams()[i] : false;
                boolean unique =
                        cs != null && i < cs.uniqueParams().length
                                ? cs.uniqueParams()[i]
                                : pm.unique();
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
        // Parameterized `init (params) -> (...)` constructor:
        BabbleParser.InitExpressionContext initCtx = ctor.initBlock;
        ScopeNode scope = scopeOf(initCtx);
        EffectChecker.CallableSummary cs =
                effectChecker != null ? effectChecker.constructorSummary(node, ctorIndex) : null;
        List<BabbleParser.FnParamContext> decls = initCtx.fnParameters().fnParam();
        int kwStart = ScopeAnalysis.keywordOnlyStart(initCtx.fnParameters());
        List<Type.Parameter> result = new ArrayList<>(decls.size());
        for (int i = 0; i < decls.size(); i++) {
            BabbleParser.FnParamContext parameter = decls.get(i);
            boolean rest = ScopeAnalysis.isParamRest(parameter);
            Type type = rest ? restElementType(parameter, scope) : parameterType(parameter, scope);
            if (parameter.defaultValue != null && !types.containsKey(parameter.defaultValue)) {
                Type actual = check(parameter.defaultValue, type != Type.ERROR ? type : null);
                if (type != Type.ERROR) {
                    subsumes(parameter.defaultValue, null, actual, type);
                    coerceSam(parameter.defaultValue, actual, type);
                }
            }
            EffectChecker.ParamMods pm =
                    effectChecker != null
                            ? effectChecker.parseParamMods(
                                    parameter, checkingEffects && inFile(initCtx))
                            : EffectChecker.ParamMods.NONE;
            boolean mut = cs != null && i < cs.mutParams().length ? cs.mutParams()[i] : pm.mut();
            boolean borrow =
                    cs != null && i < cs.borrowParams().length
                            ? cs.borrowParams()[i]
                            : (!pm.shared() && !pm.unique());
            boolean unique =
                    cs != null && i < cs.uniqueParams().length ? cs.uniqueParams()[i] : pm.unique();
            result.add(
                    new Type.Parameter(
                            parameter.ID().getText(),
                            type,
                            parameter.defaultValue != null,
                            i >= kwStart,
                            rest,
                            mut,
                            borrow,
                            pm.shared(),
                            unique));
        }
        return result;
    }

    Type.Function constructorFunctionType(
            TypeNode node,
            int ctorIndex,
            List<Type.TypeVar> classVars,
            List<Type> explicitTypeArgs) {
        List<Type.Parameter> params = constructorParameters(node, ctorIndex);
        Type result;
        List<Type.TypeVar> freeVars;
        if (explicitTypeArgs != null && !explicitTypeArgs.isEmpty()) {
            Map<Type.TypeVar, Type> env = new HashMap<>();
            for (int i = 0; i < explicitTypeArgs.size() && i < classVars.size(); i++) {
                env.put(classVars.get(i), explicitTypeArgs.get(i));
            }
            List<Type.Parameter> substituted = new ArrayList<>(params.size());
            for (Type.Parameter p : params) {
                substituted.add(p.withType(algebra.substitute(p.type(), env)));
            }
            params = substituted;
            freeVars = List.of();
            result = new Type.Instance(node, explicitTypeArgs);
        } else if (classVars.isEmpty()) {
            freeVars = List.of();
            result = new Type.Instance(node);
        } else {
            freeVars = classVars;
            result = new Type.Instance(node, List.copyOf(classVars));
        }
        EffectChecker.CallableSummary cs =
                effectChecker != null ? effectChecker.constructorSummary(node, ctorIndex) : null;
        EffectSet effs = cs != null ? cs.effects() : EffectSet.EMPTY;
        Set<Integer> forwarded = Set.of();
        if (ctorIndex >= 0
                && ctorIndex < node.constructors.size()
                && node.constructors.get(ctorIndex).initBlock != null) {
            forwarded =
                    TypeAnnotations.resolveForwardedParams(
                            node.constructors.get(ctorIndex).initBlock.effectPrefix(), params);
        }
        return new Type.Function(freeVars, params, result, effs, forwarded, Set.of());
    }

    void checkConstructorOverloadConflicts(TypeNode node) {
        if (node == null || node.constructors.size() < 2) {
            return;
        }
        List<Type.TypeVar> classVars = annotator.typeVarsOf(node);
        List<Type.Function> alts = new ArrayList<>(node.constructors.size());
        for (int i = 0; i < node.constructors.size(); i++) {
            alts.add(constructorFunctionType(node, i, classVars, List.of()));
        }
        String err = OverloadSolver.checkWellFormed(algebra, node.name, alts);
        if (err != null) {
            ParserRuleContext at = node.constructors.get(1).ctx;
            warn(err, at != null ? at : node.ctx);
        }
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
