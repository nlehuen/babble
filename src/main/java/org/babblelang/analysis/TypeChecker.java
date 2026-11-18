package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleBaseVisitor;
import org.babblelang.parser.BabbleLexer;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.JavaTypes;
import org.babblelang.runtime.Operators;

import static org.babblelang.analysis.TypeAlgebra.canBeNull;
import static org.babblelang.analysis.TypeAlgebra.containsFunction;
import static org.babblelang.analysis.TypeAlgebra.containsType;
import static org.babblelang.analysis.TypeAlgebra.isCallable;
import static org.babblelang.analysis.TypeAlgebra.needsDoubleWidening;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
    record NarrowingInfo(Map<ParserRuleContext, Type> whenTrue, Map<ParserRuleContext, Type> whenFalse) {
    }

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
    private final Set<Diagnostic> effectDiagnostics = new TreeSet<>(ORDER);
    private final Set<Diagnostic> warnings = new TreeSet<>(ORDER);
    private final Set<ParserRuleContext> widened = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ParserRuleContext, String> callChecks = new IdentityHashMap<>();
    private final Map<BabbleParser.DefExpressionContext, List<Type.TypeVar>> defTypeVars = new IdentityHashMap<>();
    private final Typer typer = new Typer();
    private final Map<BabbleParser.FunctionLiteralContext, Type> contextualReturnTypes = new IdentityHashMap<>();
    private final Map<BabbleParser.FunctionLiteralContext, List<BabbleParser.ReturnExpressionContext>> functionReturns = new IdentityHashMap<>();
    private final Deque<BabbleParser.FunctionLiteralContext> inferenceStack = new ArrayDeque<>();
    private final Set<BabbleParser.FunctionLiteralContext> inferringResult = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BabbleParser.FunctionLiteralContext> recursiveFunctions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<BabbleParser.FunctionLiteralContext, Type> provisionalResults = new IdentityHashMap<>();
    private TypeEnv env = TypeEnv.EMPTY;
    private Type currentExpected;
    private Role currentRole = Role.VALUE;
    private final Map<ParserRuleContext, Type> inferredDefs = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> nextInferredDefs = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> inferredMembers = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> nextInferredMembers = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> inferredParams = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Type> nextInferredParams = new IdentityHashMap<>();
    private record SiteKey(int tokenIndex, int line) implements Comparable<SiteKey> {
        @Override
        public int compareTo(SiteKey other) {
            int cmp = Integer.compare(this.tokenIndex, other.tokenIndex);
            return cmp != 0 ? cmp : Integer.compare(this.line, other.line);
        }
    }

    private final class SlotCell {
        final TreeMap<SiteKey, Type> contributors = new TreeMap<>();
        final Set<Integer> readers = new LinkedHashSet<>();
        Type value;
        int widenedLine = -1;

        boolean record(ParserRuleContext at, Type contributor, int widenLine) {
            int tok = at != null && at.getStart() != null ? at.getStart().getTokenIndex() : 0;
            SiteKey key = new SiteKey(tok, widenLine);
            Type prev = contributors.put(key, contributor);
            if (contributor.equals(prev)) {
                return false;
            }
            Type folded = null;
            int newWidenedLine = -1;
            for (Map.Entry<SiteKey, Type> e : contributors.entrySet()) {
                if (folded == null) {
                    folded = e.getValue();
                } else {
                    Type joined = algebra.reassignmentJoin(folded, e.getValue());
                    if (joined == null) {
                        continue;
                    }
                    if (!joined.equals(folded)) {
                        if ((containsType(joined, Type.DOUBLE) && !containsType(folded, Type.DOUBLE))
                                || (Type.containsNull(joined) && !Type.containsNull(folded))) {
                            if (e.getKey().line() >= 0) {
                                newWidenedLine = e.getKey().line();
                            }
                        }
                        folded = joined;
                    }
                }
            }
            boolean changed = !Objects.equals(this.value, folded) || this.widenedLine != newWidenedLine;
            this.value = folded;
            this.widenedLine = newWidenedLine;
            return changed;
        }

        void addReader(Integer unitIdx) {
            if (unitIdx != null) {
                readers.add(unitIdx);
            }
        }
    }

    private final Map<ParserRuleContext, SlotCell> defCells = new IdentityHashMap<>();
    private final Map<ParserRuleContext, SlotCell> memberCells = new IdentityHashMap<>();
    private final Map<ParserRuleContext, SlotCell> paramCells = new IdentityHashMap<>();
    private final Map<BabbleParser.WhileExpressionContext, Set<ParserRuleContext>> assignedWhileLocals = new IdentityHashMap<>();
    private final Deque<Integer> activeUnitStack = new ArrayDeque<>();
    private boolean inferenceMode;
    private Map<ParserRuleContext, Integer> topUnitIndex = new IdentityHashMap<>();
    private int[] sccRank;
    private PriorityQueue<Integer> worklist;
    private boolean[] inQueue;
    private int[] evalCount;

    private final Map<ParserRuleContext, Widening> defWidenings = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Widening> memberWidenings = new IdentityHashMap<>();
    private final Map<ParserRuleContext, Widening> paramWidenings = new IdentityHashMap<>();
    private final Set<ParserRuleContext> escapingTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> nextEscapingTargets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> p7Params = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Type.Function, BabbleParser.FunctionLiteralContext> functionLiterals = new IdentityHashMap<>();
    private final Set<TypeNode> exportedTypeNodes = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<ParserRuleContext> failedDefs = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<BabbleParser.DefExpressionContext, Type.SymbolId> defSymbolIds = new IdentityHashMap<>();
    private final Map<List<BabbleParser.PackageExpressionContext>, Type.SymbolId> packageSymbolIds = new IdentityHashMap<>();
    private final Map<Type.Package, List<BabbleParser.PackageExpressionContext>> localPackages = new IdentityHashMap<>();

    private final CallSolver callSolver = new CallSolver(this);

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
            return typeVarsOf(node);
        }

        @Override
        public Type parentType(TypeNode node, int index) {
            return parentAnnotation(node, index);
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

    Set<ParserRuleContext> widened() {
        return widened;
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
        return annotations;
    }

    Set<BabbleParser.FunctionLiteralContext> functions() {
        return functions;
    }

    Map<ParserRuleContext, Type> inferredDefs() {
        return inferredDefs;
    }

    /** The package expressions a package type of this file stands for, or null for another file's. */
    List<BabbleParser.PackageExpressionContext> localPackageDeclarations(Type.Package pkg) {
        return localPackages.get(pkg);
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
        buildTarjanRanks(file);
        runInferenceWorklist(file);
        effectChecker.inferAll(file);
        resetPass(false);
        walk(file);
        for (BabbleParser.FunctionLiteralContext function : functions) {
            checkResult(function);
        }
        commitInferred();
        this.checkingEffects = true;
        resetPass(true);
        walk(file);
        for (BabbleParser.FunctionLiteralContext function : functions) {
            checkResult(function);
        }
        checkInterfaces();
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

    private void buildTarjanRanks(BabbleParser.FileContext file) {
        List<BabbleParser.ExpressionContext> units = file.expression();
        int n = units.size();
        topUnitIndex = new IdentityHashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            topUnitIndex.put(units.get(i), i);
        }
        List<Set<Integer>> deps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            deps.add(new LinkedHashSet<>());
        }
        for (Map.Entry<ParserRuleContext, ScopeNode> entry : occurrenceScopes.entrySet()) {
            ParserRuleContext occ = entry.getKey();
            if (!inFile(occ)) {
                continue;
            }
            Integer uIdx = topUnitOf(occ);
            if (uIdx == null) {
                continue;
            }
            String refName = null;
            if (occ instanceof BabbleParser.SelectorContext sel && sel.expression() == null) {
                refName = sel.ID().getText();
            } else if (occ instanceof BabbleParser.AssignExpressionContext assign && assign.namespace == null) {
                refName = assign.name.getText();
            }
            if (refName != null && resolveName(null, refName, entry.getValue()) instanceof ParserRuleContext decl) {
                Integer vIdx = topUnitOf(decl);
                if (vIdx != null && !vIdx.equals(uIdx)) {
                    deps.get(uIdx).add(vIdx);
                }
            }
        }
        sccRank = new int[n];
        int[] index = new int[n];
        int[] lowlink = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(index, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        int[] nextIndex = {0};
        int[] nextRank = {0};
        for (int i = 0; i < n; i++) {
            if (index[i] == -1) {
                tarjanStrongConnect(i, deps, index, lowlink, onStack, stack, nextIndex, nextRank);
            }
        }
    }

    private void tarjanStrongConnect(int v, List<Set<Integer>> deps, int[] index, int[] lowlink,
                                     boolean[] onStack, Deque<Integer> stack, int[] nextIndex, int[] nextRank) {
        index[v] = lowlink[v] = nextIndex[0]++;
        stack.push(v);
        onStack[v] = true;
        for (int w : deps.get(v)) {
            if (index[w] == -1) {
                tarjanStrongConnect(w, deps, index, lowlink, onStack, stack, nextIndex, nextRank);
                lowlink[v] = Math.min(lowlink[v], lowlink[w]);
            } else if (onStack[w]) {
                lowlink[v] = Math.min(lowlink[v], index[w]);
            }
        }
        if (lowlink[v] == index[v]) {
            int rank = nextRank[0]++;
            int w;
            do {
                w = stack.pop();
                onStack[w] = false;
                sccRank[w] = rank;
            } while (w != v);
        }
    }

    private Integer topUnitOf(ParserRuleContext ctx) {
        for (ParserRuleContext p = ctx; p != null; p = p.getParent()) {
            Integer idx = topUnitIndex.get(p);
            if (idx != null) {
                return idx;
            }
        }
        return null;
    }

    private void runInferenceWorklist(BabbleParser.FileContext file) {
        List<BabbleParser.ExpressionContext> units = file.expression();
        int n = units.size();
        if (n == 0) {
            return;
        }
        worklist = new PriorityQueue<>(Comparator.comparingInt((Integer i) -> sccRank[i]).thenComparingInt(i -> i));
        inQueue = new boolean[n];
        evalCount = new int[n];
        for (int i = 0; i < n; i++) {
            worklist.add(i);
            inQueue[i] = true;
        }
        inferenceMode = true;
        try {
            while (!worklist.isEmpty()) {
                int uIdx = worklist.poll();
                inQueue[uIdx] = false;
                if (evalCount[uIdx]++ >= 64) {
                    continue;
                }
                BabbleParser.ExpressionContext unit = units.get(uIdx);
                clearSubtreeTypes(unit);
                activeUnitStack.push(uIdx);
                try {
                    walk(unit);
                } finally {
                    activeUnitStack.pop();
                }
                if (!nextEscapingTargets.isEmpty()) {
                    for (ParserRuleContext target : nextEscapingTargets) {
                        if (escapingTargets.add(target)) {
                            wakeParamReadersForOwner(target);
                        }
                    }
                    nextEscapingTargets.clear();
                }
            }
        } finally {
            inferenceMode = false;
        }
    }

    private void wakeReaders(SlotCell cell) {
        if (!inferenceMode || worklist == null) {
            return;
        }
        for (int readerIdx : cell.readers) {
            if (!inQueue[readerIdx] && evalCount[readerIdx] < 64) {
                worklist.add(readerIdx);
                inQueue[readerIdx] = true;
            }
        }
    }

    private void wakeParamReadersForOwner(ParserRuleContext owner) {
        for (Map.Entry<ParserRuleContext, SlotCell> entry : paramCells.entrySet()) {
            if (entry.getKey() instanceof BabbleParser.ParameterDeclarationContext param
                    && ownerOfParam(param) == owner) {
                inferredParams.remove(param);
                wakeReaders(entry.getValue());
            }
        }
    }

    private void registerReader(Map<ParserRuleContext, SlotCell> cells, ParserRuleContext decl) {
        if (inferenceMode && !activeUnitStack.isEmpty()) {
            cells.computeIfAbsent(decl, k -> new SlotCell()).addReader(activeUnitStack.peek());
        }
    }

    private void resetPass(boolean clearAnnotations) {
        types.clear();
        declarationTypes.clear();
        if (clearAnnotations) {
            annotations.clear();
        }
        inProgress.clear();
        functions.clear();
        diagnostics.clear();
        warnings.clear();
        widened.clear();
        callChecks.clear();
        currentExpected = null;
        currentRole = Role.VALUE;
        contextualReturnTypes.clear();
        inferenceStack.clear();
        inferringResult.clear();
        recursiveFunctions.clear();
        provisionalResults.clear();
        env = TypeEnv.EMPTY;
        nextInferredDefs.clear();
        nextInferredMembers.clear();
        nextInferredParams.clear();
        nextEscapingTargets.clear();
        p7Params.clear();
        functionLiterals.clear();
        failedDefs.clear();
        callSolver.reset();
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

    boolean inFile(ParserRuleContext ctx) {
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
                || block.getParent() instanceof BabbleParser.ObjectExpressionContext
                || block.getParent() instanceof BabbleParser.PackageExpressionContext)) {
            stmt(block);
        } else if (tree instanceof BabbleParser.ExpressionContext || tree instanceof BabbleParser.BlockContext) {
            synth((ParserRuleContext) tree);
        }
        if (tree instanceof BabbleParser.ParameterDeclarationContext parameter && parameter.defaultValue != null && parameter.type() != null) {
            Type declared = annotation(parameter.type(), scopeOf(parameter));
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
        Type projectedIface = algebra.projectToBabble(selfInst, iface);
        Map<Type.TypeVar, Type> ifaceEnv = projectedIface instanceof Type.Instance proj ? algebra.instanceEnv(proj) : Map.of();
        for (TypeNode.Member req : iface.members.values()) {
            if (req.declaration != null && !req.hasFunction && declaredType(req.declaration) == null) {
                continue;
            }
            Type required = algebra.substitute(memberType(iface, req), ifaceEnv);
            if (isUnresolved(required)) {
                continue;
            }
            TypeNode.Member impl = type.members.get(req.name);
            if (impl != null) {
                if (impl.isPrivate()) {
                    continue;
                }
                Type actual = memberType(type, impl);
                if (!isUnresolved(actual) && !algebra.subtype(actual, required)) {
                    reportTypeMismatch(actual, required, impl.declaration);
                }
            } else {
                TypeChecks.Provider provider = defaults.get(req.name);
                if (provider != null && provider.owner() instanceof TypeNode owner
                        && (!type.isInterface() || owner != iface)) {
                    TypeNode.Member defaultMember = owner.members.get(req.name);
                    if (defaultMember != null) {
                        Type projectedOwner = algebra.projectToBabble(selfInst, owner);
                        Map<Type.TypeVar, Type> ownerEnv = projectedOwner instanceof Type.Instance proj ? algebra.instanceEnv(proj) : Map.of();
                        Type actual = algebra.substitute(memberType(owner, defaultMember), ownerEnv);
                        if (!isUnresolved(actual) && !algebra.subtype(actual, required)) {
                            reportTypeMismatch(actual, required, type.ctx);
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

    private static ScopeNode callableScope(ScopeNode scope) {
        ScopeNode s = scope;
        while (s != null && s.kind == ScopeNode.Kind.BLOCK) {
            s = s.parent;
        }
        return s;
    }

    // ---------------------------------------------------------------- scopes and names

    /** Whether a construct is part of what this pass analysed, rather than of an imported file. */
    private boolean isLocal(ParserRuleContext ctx) {
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
     * What a name refers to from a scope : a def or parameter declaration, the list of
     * package expressions of that name, or null for a name the analysis doesn't know (the
     * script's bindings and the implicits).
     */
    static Object resolveName(ParserRuleContext occurrence, String name, ScopeNode scope) {
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
            registerReader(paramCells, parameter);
            Type inferredParam = inferredParams.get(parameter);
            if (inferredParam != null && isInferrableCallable(ownerOfParam(parameter))) {
                declarationTypes.put(parameter, inferredParam);
                return inferredParam;
            }
        }
        registerReader(defCells, declaration);
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

    /** The annotation a def or parameter declares, or null. */
    Type declaredType(ParserRuleContext declaration) {
        if (!isLocal(declaration)) {
            return null;
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
                return new Type.Function(typeVarsOf(def), fn.parameters(), fn.result(), fn.effects());
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
            Type.SymbolId id = defSymbolIds.computeIfAbsent(d, k -> new Type.SymbolId(k.name.getText()));
            List<org.antlr.v4.runtime.tree.TerminalNode> ids = d.typeParameters().ID();
            List<Type.TypeVar> vars = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                vars.add(new Type.TypeVar(id, ids.get(i).getText(), i));
            }
            return List.copyOf(vars);
        });
    }

    List<Type.TypeVar> typeVarsOf(TypeNode node) {
        if (node == null || node.typeParameters.isEmpty()) {
            return List.of();
        }
        if (!isLocal(node.ctx)) {
            if (pass.session != null) {
                List<Type.TypeVar> fromSession = pass.session.typeNodeVars.get(node);
                if (fromSession != null) {
                    return fromSession;
                }
            }
            for (ModuleInterface mod : allLoadedModules()) {
                TypedModuleSummary s = mod.summary();
                if (s != null) {
                    List<Type.TypeVar> remote = s.typeVarsOf(node);
                    if (remote != null) {
                        return remote;
                    }
                }
            }
            return List.of();
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

    Type parentAnnotation(TypeNode node, int index) {
        if (node == null || index < 0) {
            return Type.ERROR;
        }
        if (!isLocal(node.ctx)) {
            if (pass.session != null) {
                List<Type> fromSession = pass.session.parentTypes.get(node);
                if (fromSession != null && index < fromSession.size()) {
                    return fromSession.get(index);
                }
            }
            for (ModuleInterface mod : allLoadedModules()) {
                TypedModuleSummary s = mod.summary();
                if (s != null) {
                    List<Type> remote = s.parentTypesOf(node);
                    if (remote != null && index < remote.size()) {
                        return remote.get(index);
                    }
                }
            }
            return Type.ERROR;
        }
        if (index >= node.parentTypes.size()) {
            return Type.ERROR;
        }
        BabbleParser.TypeContext parentType = node.parentTypes.get(index);
        return annotation(parentType, scopeOf(parentType));
    }

    // ---------------------------------------------------------------- annotations

    Type annotation(BabbleParser.TypeContext type, ScopeNode scope) {
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
            EffectSet effs = effectChecker != null
                    ? effectChecker.parseFunctionTypePrefix(function, checkingEffects).toEffectSet()
                    : EffectSet.EMPTY;
            return new Type.Function(functionTypeParameters(function.parametersDeclaration(), scope),
                    function.type() != null ? annotation(function.type(), scope) : Type.NULLABLE_ANY,
                    effs);
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
            EffectChecker.ParamMods pm = effectChecker != null
                    ? effectChecker.parseParamMods(parameter, checkingEffects)
                    : EffectChecker.ParamMods.NONE;
            result.add(new Type.Parameter(parameter.ID().getText(), type, parameter.defaultValue != null,
                    pm.mut(), pm.borrow(), pm.borrow()));
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
                    Class<?> java = JavaTypeBridge.loadClass(imported + "." + String.join(".", path.subList(1, path.size())));
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
            Class<?> java = JavaTypeBridge.loadClass(String.join(".", path));
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
        int expected = JavaTypeBridge.typeParameterCount(java);
        BabbleParser.TypeArgumentsContext argCtx = simple.typeArguments();
        if (argCtx == null) {
            if (expected == 0) {
                return JavaTypeBridge.fromJava(java);
            }
            if (allowRaw) {
                return JavaTypeBridge.rawJavaInstance(java);
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
        int badArg = JavaTypeBridge.checkJavaTypeArguments(java, args, algebra);
        if (badArg >= 0) {
            warn("Type error : type argument " + args.get(badArg) + " does not satisfy bound "
                    + JavaTypeBridge.typeParameterName(java, badArg) + " of " + java.getName(),
                    simple.typeArguments().type(badArg));
            return Type.ERROR;
        }
        return new Type.JavaInstance(java, args);
    }

    private static Type.Instance rawInstance(TypeNode node) {
        if (node.typeParameters.isEmpty()) {
            return new Type.Instance(node);
        }
        return new Type.Instance(node, Collections.nCopies(node.typeParameters.size(), Type.NULLABLE_ANY));
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
                if (module.module().summary() == null && module.module().exports().contains(path.get(i))) {
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

    Type currentExpected() {
        return currentExpected;
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
        Type prevExpected = currentExpected;
        Role prevRole = currentRole;
        currentExpected = expected != Type.ERROR ? expected : null;
        currentRole = role;
        try {
            Type type = typer.visit(ctx);
            if (type == null) {
                type = Type.ERROR;
            }
            types.put(ctx, type);
            return type;
        } finally {
            currentExpected = prevExpected;
            currentRole = prevRole;
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
        return recordFunctionLiteral(
                new Type.Function(typeVarsOf(owningDef(literal)), params, result, literalEffectSet(literal)), literal);
    }

    private Type.Function recursiveSignature(BabbleParser.FunctionLiteralContext literal) {
        List<Type.TypeVar> tparams = typeVarsOf(owningDef(literal));
        List<Type.Parameter> params = functionParameters(literal);
        EffectSet effs = literalEffectSet(literal);
        if (provisionalResults.containsKey(literal)) {
            return recordFunctionLiteral(new Type.Function(tparams, params, provisionalResults.get(literal), effs), literal);
        }
        if (!inferenceStack.isEmpty() && inferenceStack.peek() == literal) {
            recursiveFunctions.add(literal);
            return recordFunctionLiteral(new Type.Function(tparams, params, Type.NOTHING, effs), literal);
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
                        ? annotation(parameter.type(), scope)
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

    private void applyContextualType(BabbleParser.FunctionLiteralContext literal) {
        Type expected = currentExpected;
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
        TypeEnv savedEnv = env;
        env = savedEnv.enterCallable(reassigned);
        try {
            pass1 = computeBodyResult(literal, expectedReturn);
        } finally {
            env = savedEnv;
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
                savedEnv = env;
                env = savedEnv.enterCallable(reassigned);
                try {
                    Type pass2 = computeBodyResult(literal, expectedReturn);
                    candidate = inferred(pass2);
                } finally {
                    env = savedEnv;
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
        Type expected = annotation(function.type(), scopeOf(function));
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
        if (widenSite != null && needsDoubleWidening(actual, expected)) {
            widened.add(widenSite);
        }
    }

    private void reportTypeMismatch(Type actual, Type expected, ParserRuleContext at) {
        if (actual instanceof Type.Function aFn && expected instanceof Type.Function eFn
                && algebra.assignableIgnoringEffects(aFn, eFn)) {
            warn("Type error : expected " + eFn.describeAgainst(eFn) + ", got " + aFn.describeAgainst(eFn), at);
        } else {
            warn("Type error : expected " + expected + ", got " + actual, at);
        }
    }

    // ---------------------------------------------------------------- flow-sensitive narrowing

    private Set<ParserRuleContext> assignedLocals(BabbleParser.WhileExpressionContext ctx) {
        return assignedWhileLocals.computeIfAbsent(ctx, node -> {
            Set<ParserRuleContext> assigned = Collections.newSetFromMap(new IdentityHashMap<>());
            collectAssignedLocals(node, assigned);
            return Collections.unmodifiableSet(assigned);
        });
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
        return env.lookup(declaration);
    }

    private Type preferMoreSpecific(Type a, Type b) {
        return algebra.runtimeSubtype(a, b) && !algebra.runtimeSubtype(b, a) ? a : b;
    }

    private TypeEnv.Refinement extractRefinement(BabbleParser.ExpressionContext expr) {
        if (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            return extractRefinement(block.block().expression(0));
        }
        if (expr instanceof BabbleParser.BooleanNotContext notCtx) {
            return extractRefinement(notCtx.expression()).invert();
        }
        if (expr instanceof BabbleParser.BooleanOpContext opCtx) {
            TypeEnv.Refinement left = extractRefinement(opCtx.left);
            TypeEnv.Refinement right = extractRefinement(opCtx.right);
            return opCtx.op.getType() == BabbleLexer.AND
                    ? left.and(right, this::preferMoreSpecific)
                    : left.or(right, this::preferMoreSpecific);
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
                    Type current = synth(other);
                    if (!isUnresolved(current) && Type.containsNull(current)) {
                        Type nonNull = Type.withoutNull(current);
                        Map<ParserRuleContext, Type> whenNotNull = nonNull != Type.NOTHING ? Map.of(decl, nonNull) : Map.of();
                        TypeEnv.Refinement eqNull = new TypeEnv.Refinement(Map.of(decl, Type.NULL), whenNotNull);
                        return opCtx.op.getType() == BabbleLexer.EQ ? eqNull : eqNull.invert();
                    }
                }
            }
            return TypeEnv.Refinement.EMPTY;
        }
        if (expr instanceof BabbleParser.IsExpressionContext isCtx) {
            ParserRuleContext decl = narrowableDeclaration(isCtx.left);
            if (decl == null) {
                return TypeEnv.Refinement.EMPTY;
            }
            Type target = isBuiltinTarget(isCtx);
            if (target == null) {
                target = instanceTypeOfTypeValue(synth(isCtx.right, Role.TYPE_TEST_RIGHT));
            }
            Type current = synth(isCtx.left);
            if (target == null || target == Type.ERROR
                    || (!isUnresolved(current) && algebra.runtimeSubtype(current, target))) {
                return TypeEnv.Refinement.EMPTY;
            }
            Type narrowedTrue = !isUnresolved(current) ? algebra.narrowTarget(current, target) : target;
            Type subtracted = !isUnresolved(current) ? algebra.subtractType(current, target) : Type.NOTHING;
            Map<ParserRuleContext, Type> whenFalse = (subtracted != Type.NOTHING && !subtracted.equals(current))
                    ? Map.of(decl, subtracted) : Map.of();
            return new TypeEnv.Refinement(Map.of(decl, narrowedTrue), whenFalse);
        }
        ParserRuleContext decl = narrowableDeclaration(expr);
        if (decl != null) {
            Type current = synth(expr);
            if (!isUnresolved(current) && Type.containsNull(current)) {
                Type nonNull = Type.withoutNull(current);
                if (nonNull != Type.NOTHING) {
                    return new TypeEnv.Refinement(Map.of(decl, nonNull), Map.of());
                }
            }
        }
        return TypeEnv.Refinement.EMPTY;
    }

    private static boolean isNullLiteral(BabbleParser.ExpressionContext expr) {
        while (expr instanceof BabbleParser.BlockExpressionContext block && block.block().expression().size() == 1) {
            expr = block.block().expression(0);
        }
        return expr instanceof BabbleParser.NullContext;
    }

    Type.Simple isBuiltinTarget(BabbleParser.IsExpressionContext ctx) {
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
            return JavaTypeBridge.rawJavaInstance(jcv.type());
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

    private void recordInferredDef(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        if (inferenceMode) {
            recordSlotCell(defCells, inferredDefs, defWidenings, decl, valueType, at, false);
            return;
        }
        recordInferred(nextInferredDefs, defWidenings, decl, valueType, at);
    }

    private void recordInferredMember(ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        if (inferenceMode) {
            recordSlotCell(memberCells, inferredMembers, memberWidenings, decl, valueType, at, false);
            return;
        }
        recordInferred(nextInferredMembers, memberWidenings, decl, valueType, at);
    }

    private void recordSlotCell(Map<ParserRuleContext, SlotCell> cells, Map<ParserRuleContext, Type> targetMap,
                                Map<ParserRuleContext, Widening> widenMap, ParserRuleContext decl,
                                Type valueType, ParserRuleContext at, boolean isParam) {
        if (isUnresolved(valueType)) {
            failedDefs.add(decl);
            return;
        }
        int widenLine = -1;
        if (isParam) {
            ParserRuleContext site = at.getParent() instanceof BabbleParser.CallParameterContext cp
                    && cp.getParent() instanceof BabbleParser.CallParametersContext cps
                    && cps.getParent() instanceof BabbleParser.CallContext call ? call : at;
            widenLine = site.getStart().getLine();
        } else {
            ParserRuleContext site = at instanceof BabbleParser.AssignExpressionContext ? at
                    : at.getParent() instanceof BabbleParser.AssignExpressionContext assign ? assign : null;
            if (site != null) {
                widenLine = site.getStart().getLine();
            }
        }
        SlotCell cell = cells.computeIfAbsent(decl, k -> new SlotCell());
        boolean changed = cell.record(at, valueType, widenLine);
        if (cell.value != null && cell.value != Type.NULL
                && (!isParam || !(decl instanceof BabbleParser.ParameterDeclarationContext param)
                || !escapingTargets.contains(ownerOfParam(param)))) {
            targetMap.put(decl, cell.value);
            if (cell.widenedLine >= 0) {
                widenMap.put(decl, new Widening(cell.value, cell.widenedLine));
            }
        }
        if (changed) {
            wakeReaders(cell);
        }
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
        if (inferenceMode) {
            recordSlotCell(paramCells, inferredParams, paramWidenings, decl, valueType, at, true);
            return;
        }
        Type current = nextInferredParams.get(decl);
        if (current == null) {
            nextInferredParams.put(decl, valueType);
            return;
        }
        Type joined = algebra.reassignmentJoin(current, valueType);
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
            if (p instanceof BabbleParser.DefExpressionContext def && defSymbolIds.get(def) == tv.owner()) {
                return true;
            }
            if (p.getParent() instanceof BabbleParser.ClassExpressionContext cls && cls.staticBlock == p) {
                BabbleParser.DefExpressionContext owningDef = owningDefExpr(cls);
                if (owningDef != null && defSymbolIds.get(owningDef) == tv.owner()) {
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
        Type joined = algebra.reassignmentJoin(current, valueType);
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

    private final class Typer extends BabbleBaseVisitor<Type> {
        @Override
        public Type visitPackageExpression(BabbleParser.PackageExpressionContext ctx) {
            return Type.NULL;
        }

        @Override
        public Type visitIfExpression(BabbleParser.IfExpressionContext ctx) {
            Type expected = currentExpected;
            Role role = currentRole;
            synth(ctx.test);
            TypeEnv.Refinement refinement = extractRefinement(ctx.test);
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
            } else if (kind == ScopeAnalysis.DefKind.MEMBER || kind == ScopeAnalysis.DefKind.STATIC) {
                recordInferredMember(ctx, value, errorSite);
                Type inferred = inferredMembers.get(ctx);
                if (inferred != null && needsDoubleWidening(value, inferred)) {
                    widened.add(ctx);
                }
            } else {
                recordInferredDef(ctx, value, errorSite);
                Type inferred = inferredDefs.get(ctx);
                if (inferred != null && needsDoubleWidening(value, inferred)) {
                    widened.add(ctx);
                }
            }
            return value;
        }

        @Override
        public Type visitWhileExpression(BabbleParser.WhileExpressionContext ctx) {
            Type expected = currentExpected;
            Role role = currentRole;
            env = env.evictAll(assignedLocals(ctx));
            synth(ctx.test);
            TypeEnv afterTest = env;
            TypeEnv.Refinement refinement = extractRefinement(ctx.test);
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
            if (needsDoubleWidening(bodyType, joined)) {
                widened.add(ctx.whileBlock);
            }
            return joined;
        }

        @Override
        public Type visitClassExpression(BabbleParser.ClassExpressionContext ctx) {
            if (ctx.parametersDeclaration() != null) {
                constructorParameters(pass.typeNodes.get(ctx));
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
                    && inFile(cls) && !currentRole.isClassSafe()) {
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
            Role role = currentRole;
            Type expected = currentExpected;
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
                    return localPackage(name, list);
                }
                return implicitType(name);
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
                return member(target, name, receiver instanceof BabbleParser.ThisExpressionContext, ctx, receiver);
            }
            return member(synth(receiver, Role.SELECTOR_RECEIVER), name, receiver instanceof BabbleParser.ThisExpressionContext, ctx, receiver);
        }

        @Override
        public Type visitCall(BabbleParser.CallContext ctx) {
            if (ctx.expression() instanceof BabbleParser.SelectorContext safeSelector && safeSelector.safe != null) {
                Type callee = typer.selectUninstantiated(safeSelector);
                types.put(safeSelector, callee == Type.ERROR ? Type.ERROR : Type.nullable(callee));
                Type result = callSolver.call(callee, ctx, true);
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
                    && ctx.callParameters().callParameter(0).ID() == null
                    && ctx.callParameters().callParameter(0).expression() instanceof BabbleParser.StringContext string) {
                synth(string);
                String target = org.babblelang.engine.impl.Interpreter.unescape(string.getText());
                result = target.endsWith(".ba") ? Type.ANY : new Type.JavaPackage(target);
            } else {
                result = callSolver.call(callee, ctx, true);
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

        @Override
        public Type visitBlockExpression(BabbleParser.BlockExpressionContext ctx) {
            Role role = ctx.block().expression().size() == 1
                    ? currentRole
                    : (currentRole == Role.STATEMENT ? Role.STATEMENT : Role.VALUE);
            return type(ctx.block(), currentExpected, role);
        }

        @Override
        public Type visitBlock(BabbleParser.BlockContext ctx) {
            Type expected = currentExpected;
            Role role = currentRole;
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
                            env = env.apply(extractRefinement(ifCtx.test).whenFalse());
                        } else if (elseType == Type.NOTHING && thenType != Type.NOTHING) {
                            env = env.apply(extractRefinement(ifCtx.test).whenTrue());
                        }
                    }
                }
                return type;
            } finally {
                env = TypeEnv.joinBranches(blockEntryEnv, env, blockEntryEnv);
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
            Type operand = synth(ctx.operand);
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
            synth(ctx.expression());
            return Type.BOOL;
        }

        @Override
        public Type visitBinaryOp(BabbleParser.BinaryOpContext ctx) {
            return binaryOp(ctx, synth(ctx.left), synth(ctx.right));
        }

        @Override
        public Type visitIsExpression(BabbleParser.IsExpressionContext ctx) {
            synth(ctx.left);
            Type.Simple builtin = isBuiltinTarget(ctx);
            if (builtin != null) {
                pass.analysis.setIsBuiltin(ctx, builtin.name());
            } else {
                synth(ctx.right, Role.TYPE_TEST_RIGHT);
            }
            return Type.BOOL;
        }

        @Override
        public Type visitAsExpression(BabbleParser.AsExpressionContext ctx) {
            Type source = synth(ctx.left);
            Type target = annotation(ctx.right, scopeOf(ctx), true);
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

        @Override
        public Type visitBooleanOp(BabbleParser.BooleanOpContext ctx) {
            synth(ctx.left);
            TypeEnv saved = env;
            TypeEnv.Refinement leftRef = extractRefinement(ctx.left);
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

        @Override
        public Type visitFunctionLiteral(BabbleParser.FunctionLiteralContext ctx) {
            if (inFile(ctx)) {
                functions.add(ctx);
            }
            Type.Function sig = signature(ctx);
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
            String name = ctx.name.getText();
            if (ctx.namespace == null) {
                Object resolved = resolveName(ctx, name, occurrenceScopes.get(ctx));
                if (resolved instanceof ParserRuleContext declaration) {
                    Type current = Type.ERROR;
                    if (readCurrent) {
                        Type narrowed = narrowedType(declaration);
                        current = narrowed != null ? narrowed : typeOfDeclaration(declaration);
                    }
                    Type declared = declaration instanceof BabbleParser.ParameterDeclarationContext
                            ? typeOfDeclaration(declaration)
                            : declaredType(declaration);
                    return new LValue.Local(declaration, declared, current, declared == null);
                }
                return new LValue.Implicit(readCurrent ? implicitType(name) : Type.ERROR);
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
            Type declared = assignedMemberType(receiver, name, own, ctx);
            TypeNode.Member member = assignedBabbleMember(receiver, name, own);
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
                    String error = jf.field().functionError(value, ctx.name.getText());
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
                        recordInferredDef(local.declaration(), value, errorSite);
                        Type inferred = inferredDefs.get(local.declaration());
                        if (inferred != null && needsDoubleWidening(value, inferred)) {
                            widened.add(ctx);
                        }
                    }
                    env = env.evict(local.declaration());
                }
                case LValue.Member member -> {
                    if (member.declaredType() != null) {
                        subsumes(errorSite, ctx, value, member.declaredType());
                    } else if (member.inferred() && member.declaration() != null) {
                        recordInferredMember(member.declaration(), value, errorSite);
                        Type inferred = inferredMembers.get(member.declaration());
                        if (inferred != null && needsDoubleWidening(value, inferred)) {
                            widened.add(ctx);
                        }
                    }
                }
                case LValue.Implicit ignored -> {
                }
            }
        }

        @Override
        public Type visitAssignExpression(BabbleParser.AssignExpressionContext ctx) {
            String operator = Operators.compound(ctx.op.getText());
            if (operator != null) {
                return compoundAssignment(ctx, operator);
            }
            LValue target = resolveLValue(ctx, false);
            Type expected = switch (target) {
                case LValue.JavaField jf -> jf.declaredType();
                case LValue.Local local -> local.declaredType();
                case LValue.Member member -> member.declaredType();
                case LValue.Implicit ignored -> null;
            };
            Type value = check(ctx.value, expected);
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
            Type result = binaryOp(arithmeticToken(operator), operator, ctx, org.babblelang.engine.impl.Interpreter.targetText(ctx), ctx, current,
                    ctx.value.getText(), ctx.value, value);
            if (target instanceof LValue.JavaField jf) {
                subsumes(ctx, result, jf.declaredType());
            } else {
                writeLValue(ctx, target, result, ctx);
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
                return Type.ERROR;
            }
            if (inFile(function) && !currentRole.isCallee()) {
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
            Type value = ctx.expression() != null ? check(ctx.expression(), expected) : Type.NULL;
            if (function != null && function.type() != null) {
                Type declared = annotation(function.type(), scopeOf(function));
                subsumes(ctx.expression() != null ? ctx.expression() : ctx, ctx, value, declared);
            }
            return Type.NOTHING;
        }
    }

    // ---------------------------------------------------------------- members

    private record ResolvedMember(TypeNode owner, TypeNode.Member member, Map<Type.TypeVar, Type> env) {
    }

    /** {@code receiver.name}, read. */
    private Type member(Type receiver, String name, boolean own, ParserRuleContext at, BabbleParser.ExpressionContext receiverExpression) {
        return member(receiver, name, own, at, receiverExpression.getText());
    }

    /** {@code receiver.name}, read, given the receiver's source text. */
    Type member(Type receiver, String name, boolean own, ParserRuleContext at, String receiverText) {
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
                return algebra.substitute(memberType(resolved.owner(), resolved.member()), resolved.env());
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
                return memberType(value.node(), member);
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
            if (module.module().summary() == null && module.module().exports().contains(name)) {
                warn("Missing type : " + name + " (import cycle with " + module.module().name() + ")", at);
                return Type.ERROR;
            }
            // A missing export is TypeChecks' to report.
            Type export = exportType(module.module(), name);
            return export != null ? export : Type.ERROR;
        }
        if (receiver instanceof Type.JavaPackage pkg) {
            String qualified = pkg.name() + "." + name;
            Class<?> java = JavaTypeBridge.loadTopLevel(qualified);
            return java != null ? new Type.JavaClassValue(java) : new Type.JavaPackage(qualified);
        }
        if (receiver instanceof Type.JavaClassValue value) {
            Class<?> memberClass = JavaTypeBridge.publicMemberClass(value.type(), name);
            if (memberClass != null) {
                return new Type.JavaClassValue(memberClass);
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
        TypeNode ownerNode = null;
        TypeNode.Member member = null;
        Map<Type.TypeVar, Type> env = Map.of();
        Type.Instance receiverInstance = null;
        if (receiver instanceof Type.Instance instance) {
            receiverInstance = instance;
            ResolvedMember resolved = findMember(instance, name);
            if (resolved != null) {
                ownerNode = resolved.owner();
                member = resolved.member();
                env = resolved.env();
            } else if (inheritedMember(instance, name) == null) {
                warn("No such member : " + name, at);
            }
        } else if (receiver instanceof Type.ClassValue value) {
            ownerNode = value.node();
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
            Type closed = memberType(ownerNode, member);
            return closed == Type.ERROR ? null : algebra.substitute(closed, env);
        }
        if (member.declaration instanceof BabbleParser.ParameterDeclarationContext) {
            Type type = typeOfDeclaration(member.declaration);
            return type == Type.ERROR ? null : algebra.substitute(type, env);
        }
        if (member.hasFunction) {
            return algebra.substitute(memberType(ownerNode, member), env);
        }
        Type declared = declaredType(member.declaration);
        if (declared != null) {
            return algebra.substitute(declared, env);
        }
        if (receiverInstance != null && !receiverInstance.node().typeParameters.isEmpty() && !own) {
            Type closed = memberType(ownerNode, member);
            if (closed != Type.ERROR && closed.hasTypeVar()) {
                return algebra.substitute(closed, env);
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
        Map<Type.TypeVar, Type> env = algebra.instanceEnv(instance);
        TypeNode.Member member = node.members.get(name);
        if (member != null || !node.isInterface()) {
            return member != null ? new ResolvedMember(node, member, env) : null;
        }
        for (int i = 0; i < node.parents.size(); i++) {
            Type parent = algebra.substitute(parentAnnotation(node, i), env);
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
        Map<Type.TypeVar, Type> env = algebra.instanceEnv(instance);
        for (int i = 0; i < node.parents.size(); i++) {
            Type parent = algebra.substitute(parentAnnotation(node, i), env);
            if (parent instanceof Type.Instance parentInst) {
                TypeNode.Member member = parentInst.node().members.get(name);
                if (member != null && (member.hasFunction || node.isInterface())) {
                    return algebra.substitute(memberType(parentInst.node(), member), algebra.instanceEnv(parentInst));
                }
                Type inherited = inheritedMember(parentInst, name);
                if (inherited != null) {
                    return inherited;
                }
            } else if (parent instanceof Type.JavaInstance javaParent
                    && JavaTypeBridge.hasJavaMethod(javaParent.type(), name)) {
                return new Type.JavaMethod(javaParent.type(), javaParent.typeArguments(), name);
            }
        }
        return null;
    }

    /** A member's annotation, method signature, or inferred type. */
    Type memberType(TypeNode owner, TypeNode.Member member) {
        if (!isLocal(member.declaration)) {
            if (owner != null) {
                if (pass.session != null) {
                    Map<String, Type> map = pass.session.typeMemberTypes.get(owner);
                    if (map != null && map.containsKey(member.name)) {
                        return map.get(member.name);
                    }
                }
                for (ModuleInterface mod : allLoadedModules()) {
                    TypedModuleSummary s = mod.summary();
                    if (s != null) {
                        Type remote = s.memberType(owner, member.name);
                        if (remote != null) {
                            return remote;
                        }
                    }
                }
            }
            return Type.ERROR;
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
            return synth(member.function);
        }
        if (member.declaration instanceof BabbleParser.DefExpressionContext def) {
            registerReader(memberCells, def);
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

    /**
     * The type of a module's export : from its published {@link TypedModuleSummary}. Null when the
     * module doesn't export the name ; error when its file can't be typed (a cycle).
     */
    private static Type exportType(ModuleInterface module, String name) {
        if (!module.exports().contains(name)) {
            return null;
        }
        TypedModuleSummary summary = module.summary();
        if (summary == null) {
            return Type.ERROR;
        }
        Type t = summary.exportType(name);
        return t != null ? t : Type.ERROR;
    }

    Type.Package localPackage(String name, List<BabbleParser.PackageExpressionContext> list) {
        Type.SymbolId id = packageSymbolIds.computeIfAbsent(list, k -> new Type.SymbolId(name));
        Type.Package pkg = new Type.Package(name, id);
        localPackages.put(pkg, list);
        return pkg;
    }

    private Type packageMember(Type.Package pkg, String name) {
        List<BabbleParser.PackageExpressionContext> list = localPackages.get(pkg);
        if (list == null) {
            return pkg.members().get(name);
        }
        List<BabbleParser.PackageExpressionContext> nested = new ArrayList<>();
        for (BabbleParser.PackageExpressionContext expression : list) {
            for (BabbleParser.ExpressionContext statement : expression.packageBlock.expression()) {
                if (statement instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(name)) {
                    return typeOfDeclaration(def);
                }
                if (statement instanceof BabbleParser.PackageExpressionContext inner && inner.name.getText().equals(name)) {
                    nested.add(inner);
                }
            }
        }
        return nested.isEmpty() ? null : localPackage(name, nested);
    }

    /** A method or field of a Java class or object, through {@link JavaTypeBridge#javaMember}. */
    private Type javaMember(Class<?> java, List<Type> typeArgs, String name, boolean onClass, ParserRuleContext at) {
        JavaTypeBridge.JavaMember found = JavaTypeBridge.javaMember(java, typeArgs, name, onClass, algebra);
        if (found.error() != null) {
            warn(found.error(), at);
            return Type.ERROR;
        }
        if (found.checked()) {
            String desc = LoweringAnalyzer.descriptor(found.type());
            if (desc != null) {
                callChecks.put(at, desc);
            }
        }
        return found.type();
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
            if (pass.session != null) {
                List<Type.Parameter> fromSession = pass.session.constructorParams.get(node);
                if (fromSession != null) {
                    return fromSession;
                }
            }
            for (ModuleInterface mod : allLoadedModules()) {
                TypedModuleSummary s = mod.summary();
                if (s != null) {
                    List<Type.Parameter> remote = s.constructorParams(node);
                    if (remote != null) {
                        return remote;
                    }
                }
            }
            return List.of();
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

    private static final EffectSet PURE_DET = EffectSet.ofInferred(
            EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, false);
    private static final EffectSet PURE_DET_NOTHROW = EffectSet.ofInferred(
            EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, false);
    private static final EffectSet SAME_EFFECTS = EffectSet.ofInferred(
            EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW, Effect.COMMUTATIVE), false, false);

    private static final Type.Function INT_CONVERSION = new Type.Function(
            List.of(new Type.Parameter("value", Type.ANY, false, false, true, false)), Type.INT, PURE_DET);
    private static final Type.Function DOUBLE_CONVERSION = new Type.Function(
            List.of(new Type.Parameter("value", Type.ANY, false, false, true, false)), Type.DOUBLE, PURE_DET);
    private static final Type.Function ASSERT_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("c", Type.BOOL, false, false, true, false),
                    new Type.Parameter("message", Type.NULLABLE_ANY, true, false, true, false)),
            Type.BOOL, PURE_DET);
    private static final Type.Function SAME_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("a", Type.NULLABLE_ANY, false, false, true, false),
                    new Type.Parameter("b", Type.NULLABLE_ANY, false, false, true, false)),
            Type.BOOL, SAME_EFFECTS);
    private static final Type.Function CLASSOF_FUNCTION = new Type.Function(
            List.of(new Type.Parameter("v", Type.NULLABLE_ANY, false, false, true, false)),
            Type.NULLABLE_ANY, PURE_DET_NOTHROW);
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

    static boolean isUnresolved(Type type) {
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
        Class<?> leftClass = JavaTypeBridge.javaClassOf(left);
        Class<?> rightClass = JavaTypeBridge.javaClassOf(right);
        return leftClass != null && rightClass != null && Comparable.class.isAssignableFrom(leftClass) && leftClass.isAssignableFrom(rightClass);
    }

    /**
     * {@code a + b}, or {@code -a}, on an instance of a class or interface : typed as the call
     * of its operator method, {@code a.plus(b)} ({@code argument} null for {@code a.neg()}).
     */
    private Type operatorCall(Type receiver, String method, ParserRuleContext argument, Type argumentType,
                              ParserRuleContext at, String receiverText) {
        return callSolver.operatorCall(receiver, method, argument, argumentType, at, receiverText);
    }

    // ---------------------------------------------------------------- generics & substitution

    // ---------------------------------------------------------------- assignability

    // ---------------------------------------------------------------- EffectChecker bridge

    void effectDiagnostic(String message, ParserRuleContext at) {
        if (!inFile(at)) {
            return;
        }
        effectDiagnostics.add(new Diagnostic(at.getStart().getLine(), at.getStart().getCharPositionInLine() + 1, message));
    }

    Type rawTypeOf(ParserRuleContext ctx) {
        if (ctx == null) {
            return Type.ERROR;
        }
        Type t = types.get(ctx);
        return t != null ? t : synth(ctx);
    }

    Type annotationTypeOf(BabbleParser.FunctionTypeContext ft) {
        return primaryAnnotation(ft, scopeOf(ft), false);
    }

    ScopeNode occurrenceScopeOf(ParserRuleContext ctx) {
        ScopeNode occ = occurrenceScopes.get(ctx);
        return occ != null ? occ : scopeOf(ctx);
    }

    BabbleParser.FunctionLiteralContext enclosingFunctionOf(ParserRuleContext ctx) {
        return enclosingFunction(ctx);
    }

    boolean isReassigned(ParserRuleContext decl) {
        return reassigned.contains(decl);
    }

    boolean isExportedDef(BabbleParser.DefExpressionContext def) {
        return isExported(def);
    }

    Class<?> findJavaClass(String qualifiedName) {
        return JavaTypeBridge.loadTopLevel(qualifiedName);
    }

    Class<?> findMemberJavaClass(Class<?> enclosing, String simpleName) {
        return JavaTypeBridge.publicMemberClass(enclosing, simpleName);
    }

    Object resolveNameForEffects(String name, ScopeNode scope) {
        return resolveName(null, name, scope);
    }

    Type typeOfDeclarationForEffects(ParserRuleContext decl) {
        return typeOfDeclaration(decl);
    }

    Type implicitTypeForEffects(String name) {
        Type t = implicitType(name);
        return t == Type.ANY ? null : t;
    }

    Type packageMemberTypeForEffects(Type.Package pkg, String name) {
        return packageMember(pkg, name);
    }

    Type moduleMemberTypeForEffects(ModuleInterface module, String name) {
        return exportType(module, name);
    }

    private Type remoteMemberType(TypeNode owner, String name, Set<TypeNode> visited) {
        if (!visited.add(owner)) {
            return null;
        }
        if (pass.session != null) {
            Map<String, Type> map = pass.session.typeMemberTypes.get(owner);
            if (map != null && map.containsKey(name)) {
                return map.get(name);
            }
        }
        for (ModuleInterface mod : allLoadedModules()) {
            TypedModuleSummary s = mod.summary();
            if (s != null) {
                Type remote = s.memberType(owner, name);
                if (remote != null) {
                    return remote;
                }
            }
        }
        for (Object parent : owner.parents) {
            if (parent instanceof TypeNode parentNode) {
                Type inherited = remoteMemberType(parentNode, name, visited);
                if (inherited != null) {
                    return inherited;
                }
            }
        }
        return null;
    }

    private List<ModuleInterface> allLoadedModules() {
        List<ModuleInterface> result = new ArrayList<>();
        Set<ModuleInterface> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<ModuleInterface> queue = new ArrayDeque<>(pass.modules.values());
        if (pass.session != null) {
            queue.addAll(pass.session.modules.values());
        }
        while (!queue.isEmpty()) {
            ModuleInterface mod = queue.poll();
            if (mod != null && seen.add(mod)) {
                result.add(mod);
                queue.addAll(mod.dependencies());
            }
        }
        return result;
    }

    Object resolveNamespaceMemberDecl(Type nsType, String name) {
        if (nsType instanceof Type.Package pkg) {
            List<BabbleParser.PackageExpressionContext> list = localPackages.get(pkg);
            if (list != null) {
                for (BabbleParser.PackageExpressionContext p : list) {
                    for (BabbleParser.ExpressionContext st : p.packageBlock.expression()) {
                        if (st instanceof BabbleParser.DefExpressionContext def && def.name.getText().equals(name)) {
                            return def;
                        }
                    }
                }
            }
            return null;
        }
        return null;
    }

    boolean isModuleExportImmutable(ModuleInterface module, String name, Type resType) {
        return effectChecker != null && effectChecker.isImmutableConstantValue(resType);
    }

    EffectChecker.CallableSummary remoteConstructorSummary(TypeNode node) {
        if (pass.session != null) {
            EffectChecker.CallableSummary s = pass.session.constructorSummaries.get(node);
            if (s != null) {
                return s;
            }
        }
        for (ModuleInterface mod : allLoadedModules()) {
            TypedModuleSummary summary = mod.summary();
            if (summary != null) {
                EffectChecker.CallableSummary s = summary.constructorSummary(node);
                if (s != null) {
                    return s;
                }
            }
        }
        return null;
    }

    TypedModuleSummary.CallableEffectTemplate remoteCallableTemplate(Type.Function fn) {
        if (pass.session != null) {
            TypedModuleSummary.CallableEffectTemplate t = pass.session.callableTemplates.get(fn);
            if (t != null) {
                return t;
            }
        }
        for (ModuleInterface mod : allLoadedModules()) {
            TypedModuleSummary summary = mod.summary();
            if (summary != null) {
                TypedModuleSummary.CallableEffectTemplate t = summary.callableTemplate(fn);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    BabbleParser.FunctionLiteralContext literalOf(Type.Function fn) {
        return functionLiterals.get(fn);
    }

    int parameterIndexForArg(List<Type.Parameter> parameters,
                             List<BabbleParser.CallParameterContext> arguments,
                             BabbleParser.CallParameterContext targetArg) {
        if (targetArg.ID() != null) {
            String argName = targetArg.ID().getText();
            for (int i = 0; i < parameters.size(); i++) {
                if (parameters.get(i).name().equals(argName)) {
                    return i;
                }
            }
            return -1;
        }
        Type.Parameter[] mapped = CallSolver.mapArgumentsToParameters(parameters, arguments);
        int argPos = arguments.indexOf(targetArg);
        if (argPos < 0 || argPos >= mapped.length || mapped[argPos] == null) {
            return -1;
        }
        return parameters.indexOf(mapped[argPos]);
    }

    boolean hasCallCheck(ParserRuleContext ctx) {
        return callChecks.containsKey(ctx);
    }

    List<Type.Parameter> constructorParamsForEffects(TypeNode node) {
        List<Type.Parameter> params = constructorParameters(node);
        return params != null ? params : List.of();
    }

    TypeNode.Member findMemberOnNode(TypeNode node, String name) {
        return findMember(node, name);
    }

    Type memberTypeForEffects(TypeNode owner, TypeNode.Member member) {
        return memberType(owner, member);
    }

    Type instanceMemberTypeForEffects(Type.Instance inst, TypeNode.Member member) {
        return algebra.substitute(memberType(inst.node(), member), algebra.instanceEnv(inst));
    }

    boolean isBuiltinFunction(Type.Function fn) {
        return fn == INT_CONVERSION
                || fn == DOUBLE_CONVERSION
                || fn == ASSERT_FUNCTION
                || fn == SAME_FUNCTION
                || fn == CLASSOF_FUNCTION
                || fn == IMPORT_FUNCTION;
    }

    NarrowingInfo extractNarrowingForEffects(BabbleParser.ExpressionContext expr) {
        TypeEnv.Refinement r = extractRefinement(expr);
        return new NarrowingInfo(r.whenTrue(), r.whenFalse());
    }

    Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> exportedJavaEffects() {
        return effectChecker != null ? effectChecker.exportedJavaEffects() : Map.of();
    }

    Map<HostEffects.JavaTarget, HostEffects.OverrideEntry> committedJavaEffects() {
        return effectChecker != null ? effectChecker.committedJavaEffects() : Map.of();
    }

    Map<ParserRuleContext, EffectChecker.CallableSummary> committedCallableSummaries() {
        return effectChecker != null ? effectChecker.committedCallableSummaries() : Map.of();
    }
}
