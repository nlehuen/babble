package org.babblelang.analysis;

import org.antlr.v4.runtime.ParserRuleContext;
import org.babblelang.parser.BabbleParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

import static org.babblelang.analysis.TypeAlgebra.containsType;

/**
 * The hybrid Tarjan SCC and monotone {@code SlotCell} driver ({@code BIP010} §4.5). Every
 * unannotated def, member, and call-site inferred parameter has a slot whose type joins the
 * values flowing into it, folded in source order ({@code BIP010-D2}), and every self-recursive
 * unannotated function a result slot. The top-level units are evaluated in reverse topological
 * order of their references; a unit that read a slot is evaluated again when the slot changes.
 */
final class SlotInference {
    static final int DEFS = 0;
    static final int MEMBERS = 1;
    static final int PARAMS = 2;

    record SiteKey(int tokenIndex, int line) implements Comparable<SiteKey> {
        @Override
        public int compareTo(SiteKey other) {
            int cmp = Integer.compare(this.tokenIndex, other.tokenIndex);
            return cmp != 0 ? cmp : Integer.compare(this.line, other.line);
        }
    }

    final class SlotCell {
        final TreeMap<SiteKey, Type> contributors = new TreeMap<>();
        final Set<Integer> readers = new LinkedHashSet<>();
        Type value;
        int widenedLine = -1;

        boolean record(SiteKey key, Type contributor) {
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
                    Type joined = checker.algebra().reassignmentJoin(folded, e.getValue());
                    if (joined == null) {
                        continue;
                    }
                    if (!joined.equals(folded)) {
                        if (TypeAlgebra.needsNumericWidening(folded, joined)
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

        /** The fold of the contributions before {@code key} in the source, or null if none. */
        Type foldBefore(SiteKey key) {
            Type folded = null;
            for (Type contribution : contributors.headMap(key, false).values()) {
                Type joined = folded == null ? contribution : checker.algebra().reassignmentJoin(folded, contribution);
                if (joined != null) {
                    folded = joined;
                }
            }
            return folded;
        }
        void addReader(Integer unitIdx) {
            if (unitIdx != null) {
                readers.add(unitIdx);
            }
        }
    }

    private final TypeChecker checker;
    private final List<Map<ParserRuleContext, SlotCell>> cells = List.of(new IdentityHashMap<>(), new IdentityHashMap<>(), new IdentityHashMap<>());
    private final Map<BabbleParser.FunctionLiteralContext, SlotCell> resultCells = new IdentityHashMap<>();
    private final Deque<Integer> activeUnitStack = new ArrayDeque<>();
    private boolean inferenceMode;
    private Map<ParserRuleContext, Integer> topUnitIndex = new IdentityHashMap<>();
    private int[] sccRank;
    private PriorityQueue<Integer> worklist;
    private boolean[] inQueue;
    private int[] evalCount;

    SlotInference(TypeChecker checker) {
        this.checker = checker;
    }

    /** Whether the slots are converging (rather than the final walk verifying). */
    boolean converging() {
        return inferenceMode;
    }

    /** The converged result of a self-recursive function, or null. */
    Type result(BabbleParser.FunctionLiteralContext literal) {
        SlotCell cell = resultCells.get(literal);
        return cell != null ? cell.value : null;
    }

    /** The unit being evaluated reads a function's result ; its current value, or null. */
    Type readResult(BabbleParser.FunctionLiteralContext literal) {
        SlotCell cell = resultCells.get(literal);
        if (cell != null && inferenceMode) {
            cell.addReader(activeUnitStack.peek());
        }
        return result(literal);
    }

    /** Records the result an evaluation of a self-recursive function found. */
    void recordResult(BabbleParser.FunctionLiteralContext literal, Type candidate) {
        if (inferenceMode) {
            SlotCell cell = resultCells.computeIfAbsent(literal, k -> new SlotCell());
            cell.addReader(activeUnitStack.peek());
            if (cell.record(new SiteKey(literal.getStart().getTokenIndex(), -1), candidate)) {
                wakeReaders(cell);
            }
        }
    }

    /** Ranks the top-level units in reverse topological order of their references (Tarjan), the order the worklist evaluates them in. */
    void rank(BabbleParser.FileContext file) {
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
        for (Map.Entry<ParserRuleContext, ScopeNode> entry : checker.occurrenceScopes().entrySet()) {
            ParserRuleContext occ = entry.getKey();
            if (!checker.inFile(occ)) {
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
            if (refName != null && TypeChecker.resolveName(null, refName, entry.getValue()) instanceof ParserRuleContext decl) {
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

    /** Evaluates every unit, then the units whose slots changed, until no slot changes (each at most 64 times). */
    void converge(BabbleParser.FileContext file) {
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
                checker.clearSubtreeTypes(unit);
                activeUnitStack.push(uIdx);
                try {
                    checker.walk(unit);
                } finally {
                    activeUnitStack.pop();
                }
                if (!checker.nextEscapingTargets().isEmpty()) {
                    for (ParserRuleContext target : checker.nextEscapingTargets()) {
                        if (checker.escapingTargets().add(target)) {
                            wakeParamReadersForOwner(target);
                        }
                    }
                    checker.nextEscapingTargets().clear();
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
        for (Map.Entry<ParserRuleContext, SlotCell> entry : cells.get(PARAMS).entrySet()) {
            if (entry.getKey() instanceof BabbleParser.ParameterDeclarationContext param
                    && TypeChecker.ownerOfParam(param) == owner) {
                checker.inferredParams().remove(param);
                wakeReaders(entry.getValue());
            }
        }
    }

    /** The unit being evaluated reads the slot of {@code decl}. */
    void read(int slots, ParserRuleContext decl) {
        if (inferenceMode && !activeUnitStack.isEmpty()) {
            cells.get(slots).computeIfAbsent(decl, k -> new SlotCell()).addReader(activeUnitStack.peek());
        }
    }

    /**
     * A value contributed to the slot of an unannotated def, member, or inferred parameter.
     * While the slots converge, it is recorded in the slot's cell (waking the units that read
     * it when the slot changes) ; in the final walk, it is checked against the contributions
     * that precede it in the source, which must join with it.
     */
    void contribute(int slots, ParserRuleContext decl, Type valueType, ParserRuleContext at) {
        Map<ParserRuleContext, SlotCell> cells = this.cells.get(slots);
        Map<ParserRuleContext, Type> targetMap = checker.inferred(slots);
        Map<ParserRuleContext, TypeChecker.Widening> widenMap = checker.widenings(slots);
        boolean isParam = slots == PARAMS;
        if (TypeChecker.isUnresolved(valueType)) {
            checker.failedDefs().add(decl);
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
        SiteKey key = new SiteKey(at.getStart() != null ? at.getStart().getTokenIndex() : 0, widenLine);
        if (!inferenceMode) {
            SlotCell cell = cells.get(decl);
            Type before = cell != null ? cell.foldBefore(key) : null;
            if (before != null && checker.algebra().reassignmentJoin(before, valueType) == null) {
                checker.warn("Type error : expected " + before + ", got " + valueType, at);
            }
            return;
        }
        SlotCell cell = cells.computeIfAbsent(decl, k -> new SlotCell());
        boolean changed = cell.record(key, valueType);
        if (cell.value != null && cell.value != Type.NULL
                && (!isParam || !(decl instanceof BabbleParser.ParameterDeclarationContext param)
                || !checker.escapingTargets().contains(TypeChecker.ownerOfParam(param)))) {
            targetMap.put(decl, cell.value);
            if (cell.widenedLine >= 0) {
                widenMap.put(decl, new TypeChecker.Widening(cell.value, cell.widenedLine));
            }
        }
        if (changed) {
            wakeReaders(cell);
        }
    }
}
