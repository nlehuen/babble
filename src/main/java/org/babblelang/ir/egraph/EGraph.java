package org.babblelang.ir.egraph;

import org.babblelang.analysis.PrimitiveKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Compact, zero-dependency pure-expression E-graph with union-find, hash-consing,
 * congruence-closure rebuilding, eager constant-folding e-class analysis, bounded
 * equality saturation, and cost-based extraction ({@code BIP027-D1}).
 */
public final class EGraph {
    public static final int MAX_FOLDED_STRING_LENGTH = 4096;

    /** Algebraic properties attached to pure call symbols ({@link ENode.Op#CALL}). */
    public record CallProps(boolean idempotent, boolean associative, boolean commutative, boolean involution) {
        public static final CallProps NONE = new CallProps(false, false, false, false);
    }

    /** A declarative rewrite rule applied during equality saturation. */
    @FunctionalInterface
    public interface RewriteRule {
        /**
         * Inspects {@code node} in canonical e-class {@code classId} and adds any discovered
         * equivalent e-nodes/unions via {@code recorder}.
         */
        void apply(EGraph graph, int classId, ENode node, MatchRecorder recorder);
    }

    /** Collects rewrites discovered during a read-only rule-matching pass before applying them. */
    public interface MatchRecorder {
        void union(int classId, int targetClassId);

        void addAndUnion(int classId, ENode replacement);
    }

    /** Extracted minimum-cost expression tree. */
    public record ExtractedTree(ENode node, List<ExtractedTree> children) {
    }

    private final List<Integer> parent = new ArrayList<>();
    private final List<Set<ENode>> classes = new ArrayList<>();
    private final List<List<ParentEdge>> parents = new ArrayList<>();
    private final Map<ENode, Integer> hashcons = new HashMap<>();
    private final Map<Integer, ENode> constOf = new HashMap<>();
    private final Set<Integer> reflexiveEqClasses = new HashSet<>();
    private final Map<String, CallProps> callProps = new HashMap<>();
    private final Deque<Integer> worklist = new ArrayDeque<>();
    private int totalNodes = 0;

    private record ParentEdge(ENode node, int classId) {
    }

    public void registerCallProps(String symbol, CallProps props) {
        if (symbol != null && props != null) {
            callProps.put(symbol, props);
        }
    }

    public CallProps callProps(String symbol) {
        return callProps.getOrDefault(symbol, CallProps.NONE);
    }

    public int totalNodes() {
        return totalNodes;
    }

    public int find(int id) {
        int root = id;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        int cur = id;
        while (cur != root) {
            int next = parent.get(cur);
            parent.set(cur, root);
            cur = next;
        }
        return root;
    }

    public boolean areEquivalent(int a, int b) {
        return find(a) == find(b);
    }

    public ENode constantOf(int classId) {
        return constOf.get(find(classId));
    }

    /** Marks an e-class as having guaranteed reflexive equality (e.g. {@code string} opaque leaves). */
    public void markReflexiveEq(int classId) {
        reflexiveEqClasses.add(find(classId));
    }

    /** True when {@code a == a -> true} and {@code a != a -> false} are sound for {@code classId}. */
    public boolean isReflexiveEq(int classId) {
        return reflexiveEqClasses.contains(find(classId));
    }

    public Collection<ENode> nodesIn(int classId) {
        return new ArrayList<>(classes.get(find(classId)));
    }

    private ENode canonicalize(ENode node) {
        int n = node.arity();
        if (n == 0) {
            return node;
        }
        int[] kids = new int[n];
        boolean changed = false;
        for (int i = 0; i < n; i++) {
            int c = node.child(i);
            int fc = find(c);
            kids[i] = fc;
            if (fc != c) {
                changed = true;
            }
        }
        return changed ? node.withChildren(kids) : node;
    }

    public int add(ENode raw) {
        ENode node = canonicalize(raw);
        Integer existing = hashcons.get(node);
        if (existing != null) {
            return find(existing);
        }
        int id = parent.size();
        parent.add(id);
        Set<ENode> set = new LinkedHashSet<>();
        set.add(node);
        classes.add(set);
        parents.add(new ArrayList<>());
        hashcons.put(node, id);
        totalNodes++;
        for (int i = 0; i < node.arity(); i++) {
            parents.get(node.child(i)).add(new ParentEdge(node, id));
        }
        if (node.isReflexiveEq()) {
            reflexiveEqClasses.add(id);
        }
        if (node.isConst()) {
            constOf.put(id, node);
        } else {
            ENode folded = tryConstantFold(node);
            if (folded != null) {
                int constId = add(folded);
                union(id, constId);
            }
        }
        return find(id);
    }

    public int union(int id1, int id2) {
        int r1 = find(id1);
        int r2 = find(id2);
        if (r1 == r2) {
            return r1;
        }
        // Keep the larger class as root (preferring the one with a constant if sizes tie).
        if (classes.get(r1).size() < classes.get(r2).size()) {
            int tmp = r1;
            r1 = r2;
            r2 = tmp;
        }
        parent.set(r2, r1);
        classes.get(r1).addAll(classes.get(r2));
        classes.get(r2).clear();
        parents.get(r1).addAll(parents.get(r2));
        parents.get(r2).clear();
        ENode c2 = constOf.remove(r2);
        if (c2 != null && !constOf.containsKey(r1)) {
            constOf.put(r1, c2);
        }
        if (reflexiveEqClasses.remove(r2)) {
            reflexiveEqClasses.add(r1);
        }
        worklist.add(r1);
        return r1;
    }

    public void rebuild() {
        while (!worklist.isEmpty()) {
            Set<Integer> todo = new LinkedHashSet<>();
            while (!worklist.isEmpty()) {
                todo.add(find(worklist.poll()));
            }
            for (int id : todo) {
                repair(id);
            }
        }
    }

    private void repair(int classId) {
        int root = find(classId);
        List<ParentEdge> edgeList = parents.get(root);
        parents.set(root, new ArrayList<>());
        Map<ENode, Integer> newParents = new LinkedHashMap<>();

        for (ParentEdge edge : edgeList) {
            ENode oldNode = edge.node();
            int oldClass = find(edge.classId());
            hashcons.remove(oldNode);
            ENode canon = canonicalize(oldNode);
            hashcons.put(canon, oldClass);
            if (!canon.equals(oldNode)) {
                Set<ENode> cls = classes.get(oldClass);
                cls.remove(oldNode);
                cls.add(canon);
            }
            Integer prev = newParents.get(canon);
            if (prev != null) {
                union(oldClass, prev);
            }
            newParents.put(canon, find(oldClass));

            if (!constOf.containsKey(find(oldClass))) {
                ENode folded = tryConstantFold(canon);
                if (folded != null) {
                    int constId = add(folded);
                    union(oldClass, constId);
                }
            }
        }

        int finalRoot = find(root);
        List<ParentEdge> targetParents = parents.get(finalRoot);
        for (Map.Entry<ENode, Integer> e : newParents.entrySet()) {
            targetParents.add(new ParentEdge(e.getKey(), find(e.getValue())));
        }
    }

    private ENode tryConstantFold(ENode node) {
        int n = node.arity();
        if (n == 0 || n > 2 || node.op() == ENode.Op.CALL || node.op() == ENode.Op.OPAQUE) {
            return null;
        }
        ENode c0 = constOf.get(find(node.child(0)));
        if (c0 == null) {
            return null;
        }
        if (n == 1) {
            return foldUnary(node, c0);
        }
        ENode c1 = constOf.get(find(node.child(1)));
        if (c1 == null) {
            return null;
        }
        return foldBinary(node, c0, c1);
    }

    private static ENode foldUnary(ENode node, ENode c0) {
        if (node.op() == ENode.Op.NOT && c0.literal() instanceof Boolean b) {
            return ENode.constVal(!b, PrimitiveKind.BOOL);
        }
        if (node.op() == ENode.Op.NEG && c0.literal() instanceof Number n) {
            Object neg = switch (node.kind()) {
                case INT -> -n.intValue();
                case LONG -> -n.longValue();
                case FLOAT -> -n.floatValue();
                case DOUBLE -> -n.doubleValue();
                default -> null;
            };
            return neg != null ? ENode.constVal(neg, node.kind()) : null;
        }
        return null;
    }

    private static ENode foldBinary(ENode node, ENode c0, ENode c1) {
        ENode.Op op = node.op();
        if (op == ENode.Op.STR_ADD && c0.literal() instanceof String s0 && c1.literal() instanceof String s1) {
            if (s0.length() + s1.length() <= MAX_FOLDED_STRING_LENGTH) {
                return ENode.constVal(s0 + s1, PrimitiveKind.NONE);
            }
            return null;
        }
        if ((op == ENode.Op.AND || op == ENode.Op.OR)
                && c0.literal() instanceof Boolean b0 && c1.literal() instanceof Boolean b1) {
            return ENode.constVal(op == ENode.Op.AND ? (b0 && b1) : (b0 || b1), PrimitiveKind.BOOL);
        }
        if (op == ENode.Op.EQ || op == ENode.Op.NEQ || op == ENode.Op.LT
                || op == ENode.Op.LTE || op == ENode.Op.GT || op == ENode.Op.GTE) {
            return foldComparison(op, c0, c1);
        }
        if (!(c0.literal() instanceof Number n0) || !(c1.literal() instanceof Number n1)) {
            return null;
        }
        PrimitiveKind rk = node.kind();
        Object res = switch (rk) {
            case INT -> switch (op) {
                case ADD -> n0.intValue() + n1.intValue();
                case SUB -> n0.intValue() - n1.intValue();
                case MUL -> n0.intValue() * n1.intValue();
                case FLOOR_DIV -> n1.intValue() != 0 ? Math.floorDiv(n0.intValue(), n1.intValue()) : null;
                case MOD -> n1.intValue() != 0 ? Math.floorMod(n0.intValue(), n1.intValue()) : null;
                default -> null;
            };
            case LONG -> switch (op) {
                case ADD -> n0.longValue() + n1.longValue();
                case SUB -> n0.longValue() - n1.longValue();
                case MUL -> n0.longValue() * n1.longValue();
                case FLOOR_DIV -> n1.longValue() != 0L ? Math.floorDiv(n0.longValue(), n1.longValue()) : null;
                case MOD -> n1.longValue() != 0L ? Math.floorMod(n0.longValue(), n1.longValue()) : null;
                default -> null;
            };
            case FLOAT -> switch (op) {
                case ADD -> n0.floatValue() + n1.floatValue();
                case SUB -> n0.floatValue() - n1.floatValue();
                case MUL -> n0.floatValue() * n1.floatValue();
                case DIV -> n0.floatValue() / n1.floatValue();
                default -> null;
            };
            case DOUBLE -> switch (op) {
                case ADD -> n0.doubleValue() + n1.doubleValue();
                case SUB -> n0.doubleValue() - n1.doubleValue();
                case MUL -> n0.doubleValue() * n1.doubleValue();
                case DIV -> n0.doubleValue() / n1.doubleValue();
                default -> null;
            };
            default -> null;
        };
        return res != null ? ENode.constVal(res, rk) : null;
    }

    private static ENode foldComparison(ENode.Op op, ENode c0, ENode c1) {
        if (c0.kind() == PrimitiveKind.BOOL && c1.kind() == PrimitiveKind.BOOL
                && c0.literal() instanceof Boolean b0 && c1.literal() instanceof Boolean b1) {
            if (op == ENode.Op.EQ) {
                return ENode.constVal(b0.booleanValue() == b1.booleanValue(), PrimitiveKind.BOOL);
            }
            if (op == ENode.Op.NEQ) {
                return ENode.constVal(b0.booleanValue() != b1.booleanValue(), PrimitiveKind.BOOL);
            }
            return null;
        }
        if (c0.literal() instanceof String s0 && c1.literal() instanceof String s1) {
            if (op == ENode.Op.EQ) {
                return ENode.constVal(s0.equals(s1), PrimitiveKind.BOOL);
            }
            if (op == ENode.Op.NEQ) {
                return ENode.constVal(!s0.equals(s1), PrimitiveKind.BOOL);
            }
            return null;
        }
        int cmp;
        if (c0.kind() == PrimitiveKind.CHAR && c1.kind() == PrimitiveKind.CHAR
                && c0.literal() instanceof Character ch0 && c1.literal() instanceof Character ch1) {
            cmp = Character.compare(ch0, ch1);
        } else if (c0.literal() instanceof Number n0 && c1.literal() instanceof Number n1) {
            PrimitiveKind common = numericOrder(c0.kind()) >= numericOrder(c1.kind()) ? c0.kind() : c1.kind();
            cmp = switch (common) {
                case BYTE, SHORT, INT -> Integer.compare(n0.intValue(), n1.intValue());
                case LONG -> Long.compare(n0.longValue(), n1.longValue());
                case FLOAT -> Float.compare(n0.floatValue(), n1.floatValue());
                case DOUBLE -> Double.compare(n0.doubleValue(), n1.doubleValue());
                default -> Integer.MIN_VALUE;
            };
            if (cmp == Integer.MIN_VALUE) {
                return null;
            }
        } else {
            return null;
        }
        boolean res = switch (op) {
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
            case EQ -> cmp == 0;
            case NEQ -> cmp != 0;
            case GTE -> cmp >= 0;
            case GT -> cmp > 0;
            default -> throw new IllegalStateException();
        };
        return ENode.constVal(res, PrimitiveKind.BOOL);
    }

    private static int numericOrder(PrimitiveKind kind) {
        return switch (kind) {
            case BYTE -> 0;
            case SHORT -> 1;
            case INT -> 2;
            case LONG -> 3;
            case FLOAT -> 4;
            case DOUBLE -> 5;
            default -> -1;
        };
    }

    // ---------------------------------------------------------------- equality saturation

    private record PendingUnion(int a, int b) {
    }

    private record PendingAdd(int classId, ENode node) {
    }

    /**
     * Runs equality saturation for at most {@code maxIterations} rounds or until the e-graph
     * reaches {@code maxNodes} e-nodes or hits a fixed point.
     */
    public void saturate(List<RewriteRule> rules, int maxIterations, int maxNodes) {
        rebuild();
        for (int iter = 0; iter < maxIterations && totalNodes < maxNodes; iter++) {
            List<PendingUnion> pendingUnions = new ArrayList<>();
            List<PendingAdd> pendingAdds = new ArrayList<>();
            MatchRecorder recorder = new MatchRecorder() {
                @Override
                public void union(int classId, int targetClassId) {
                    if (find(classId) != find(targetClassId)) {
                        pendingUnions.add(new PendingUnion(classId, targetClassId));
                    }
                }

                @Override
                public void addAndUnion(int classId, ENode replacement) {
                    if (totalNodes + pendingAdds.size() < maxNodes) {
                        pendingAdds.add(new PendingAdd(classId, replacement));
                    }
                }
            };

            int classCount = parent.size();
            for (int id = 0; id < classCount; id++) {
                if (parent.get(id) != id) {
                    continue;
                }
                List<ENode> snapshot = new ArrayList<>(classes.get(id));
                for (ENode node : snapshot) {
                    for (RewriteRule rule : rules) {
                        rule.apply(this, id, node, recorder);
                        if (totalNodes + pendingAdds.size() >= maxNodes) {
                            break;
                        }
                    }
                }
            }

            boolean changed = false;
            for (PendingUnion pu : pendingUnions) {
                if (find(pu.a()) != find(pu.b())) {
                    union(pu.a(), pu.b());
                    changed = true;
                }
            }
            for (PendingAdd pa : pendingAdds) {
                int before = totalNodes;
                int added = add(pa.node());
                if (totalNodes != before || find(pa.classId()) != find(added)) {
                    union(pa.classId(), added);
                    changed = true;
                }
            }
            rebuild();
            if (!changed) {
                break;
            }
        }
    }

    // ---------------------------------------------------------------- cost-based extraction

    private record BestNode(int cost, ENode node) {
    }

    /** Extracts the minimum-cost expression tree rooted at {@code rootId}. */
    public ExtractedTree extract(int rootId, ToIntFunction<ENode> nodeCost) {
        rebuild();
        Map<Integer, BestNode> best = new HashMap<>();
        boolean changed = true;
        int guard = 0;
        int limit = Math.max(parent.size() * 4, 32);
        while (changed && guard++ < limit) {
            changed = false;
            for (int id = 0; id < parent.size(); id++) {
                if (parent.get(id) != id) {
                    continue;
                }
                for (ENode node : classes.get(id)) {
                    int cost = computeNodeCost(node, best, nodeCost);
                    if (cost < 0) {
                        continue;
                    }
                    BestNode prev = best.get(id);
                    if (prev == null || isBetter(cost, node, prev.cost(), prev.node())) {
                        best.put(id, new BestNode(cost, node));
                        changed = true;
                    }
                }
            }
        }
        return buildTree(find(rootId), best);
    }

    private int computeNodeCost(ENode node, Map<Integer, BestNode> best, ToIntFunction<ENode> nodeCost) {
        int sum = nodeCost.applyAsInt(node);
        for (int i = 0; i < node.arity(); i++) {
            BestNode childBest = best.get(find(node.child(i)));
            if (childBest == null) {
                return -1;
            }
            sum += childBest.cost();
            if (sum < 0 || sum > 1_000_000) {
                return 1_000_000;
            }
        }
        return sum;
    }

    private boolean isBetter(int cost, ENode candidate, int prevCost, ENode prev) {
        if (cost != prevCost) {
            return cost < prevCost;
        }
        // Prefer constants over non-constants, and canonical `x + c` (const on right) over `c + x`.
        if (candidate.isConst() != prev.isConst()) {
            return candidate.isConst();
        }
        if (candidate.arity() == 2 && prev.arity() == 2 && candidate.op() == prev.op()) {
            boolean candConstRight = constantOf(candidate.child(1)) != null && constantOf(candidate.child(0)) == null;
            boolean prevConstRight = constantOf(prev.child(1)) != null && constantOf(prev.child(0)) == null;
            if (candConstRight != prevConstRight) {
                return candConstRight;
            }
        }
        return false;
    }

    private ExtractedTree buildTree(int classId, Map<Integer, BestNode> best) {
        BestNode b = best.get(classId);
        if (b == null) {
            throw new IllegalStateException("E-class #" + classId + " has no extractable acyclic node");
        }
        ENode node = b.node();
        if (node.arity() == 0) {
            return new ExtractedTree(node, List.of());
        }
        List<ExtractedTree> kids = new ArrayList<>(node.arity());
        for (int i = 0; i < node.arity(); i++) {
            kids.add(buildTree(find(node.child(i)), best));
        }
        return new ExtractedTree(node, kids);
    }

    public static int defaultCost(ENode node) {
        return switch (node.op()) {
            case CONST -> 1;
            case OPAQUE -> 2;
            case NOT, NEG -> 3;
            case ADD, SUB, MUL, DIV, FLOOR_DIV, MOD, STR_ADD, EQ, NEQ, LT, LTE, GT, GTE, AND, OR -> 4;
            case ITE -> 5;
            case CALL -> 10;
        };
    }
}
