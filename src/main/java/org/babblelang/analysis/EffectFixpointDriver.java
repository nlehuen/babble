package org.babblelang.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * ANTLR-free Bourdoncle Weak Topological Ordering (WTO, {@code [Bou93]}) and recursive
 * fixpoint solver for interprocedural effect inference ({@code BIP027-P4}).
 *
 * <p>Decomposes a call graph {@code caller -> callees} into a bottom-up hierarchical WTO
 * (callees before callers, inner recursive components stabilized before outer heads) so
 * every monotone summary over a finite-height lattice converges cleanly without arbitrary
 * flat-SCC cutoff caps.
 */
final class EffectFixpointDriver {
    private static final int SAFETY_ITER_CAP = 128;

    private EffectFixpointDriver() {
    }

    sealed interface WtoElement<N> permits Vertex, Component {
    }

    record Vertex<N>(N node) implements WtoElement<N> {
    }

    record Component<N>(N head, List<WtoElement<N>> body) implements WtoElement<N> {
    }

    /**
     * Builds a bottom-up Bourdoncle WTO of {@code nodes} under {@code edges} ({@code caller -> callees}).
     * Acyclic callees precede callers; cyclic components nest inner cycles inside their outer head.
     */
    static <N> List<WtoElement<N>> buildWto(List<N> nodes, Map<N, Set<N>> edges) {
        Map<N, Integer> dfn = new IdentityHashMap<>();
        Deque<N> stack = new ArrayDeque<>();
        List<WtoElement<N>> partition = new ArrayList<>();
        int[] num = {0};

        class Builder {
            int visit(N vertex, List<WtoElement<N>> out) {
                stack.push(vertex);
                int vertexDfn = ++num[0];
                dfn.put(vertex, vertexDfn);
                int head = vertexDfn;
                boolean loop = false;
                for (N succ : edges.getOrDefault(vertex, Set.of())) {
                    int succDfn = dfn.getOrDefault(succ, 0);
                    int min = succDfn == 0 ? visit(succ, out) : succDfn;
                    if (min <= head) {
                        head = min;
                        loop = true;
                    }
                }
                if (head == vertexDfn) {
                    dfn.put(vertex, Integer.MAX_VALUE);
                    N element = stack.pop();
                    if (loop) {
                        while (element != vertex) {
                            dfn.put(element, 0);
                            element = stack.pop();
                        }
                        List<WtoElement<N>> compBody = new ArrayList<>();
                        for (N succ : edges.getOrDefault(vertex, Set.of())) {
                            if (dfn.getOrDefault(succ, 0) == 0) {
                                visit(succ, compBody);
                            }
                        }
                        out.add(new Component<>(vertex, compBody));
                    } else {
                        out.add(new Vertex<>(vertex));
                    }
                }
                return head;
            }
        }

        Builder builder = new Builder();
        for (N n : nodes) {
            if (dfn.getOrDefault(n, 0) == 0) {
                builder.visit(n, partition);
            }
        }
        return partition;
    }

    /** Collects all nodes that belong to any cycle (any {@link Component}) in {@code wto}. */
    static <N> Set<N> cyclicNodes(List<WtoElement<N>> wto) {
        Set<N> cyclic = Collections.newSetFromMap(new IdentityHashMap<>());
        for (WtoElement<N> el : wto) {
            if (el instanceof Component<N> comp) {
                collectComponentNodes(comp, cyclic);
            }
        }
        return cyclic;
    }

    private static <N> void collectComponentNodes(Component<N> comp, Set<N> out) {
        out.add(comp.head());
        for (WtoElement<N> child : comp.body()) {
            if (child instanceof Vertex<N> v) {
                out.add(v.node());
            } else if (child instanceof Component<N> inner) {
                collectComponentNodes(inner, out);
            }
        }
    }

    /**
     * Runs Bourdoncle's recursive iteration strategy over {@code wto}, updating {@code summaries}
     * via {@code transfer} until every component reaches a fixed point.
     */
    static <N, S> void solve(List<WtoElement<N>> wto,
                             Map<N, S> summaries,
                             Predicate<N> isFixed,
                             BiFunction<N, S, S> transfer) {
        for (WtoElement<N> el : wto) {
            solveElement(el, summaries, isFixed, transfer);
        }
    }

    private static <N, S> boolean solveElement(WtoElement<N> el,
                                               Map<N, S> summaries,
                                               Predicate<N> isFixed,
                                               BiFunction<N, S, S> transfer) {
        if (el instanceof Vertex<N> v) {
            return updateNode(v.node(), summaries, isFixed, transfer);
        }
        Component<N> comp = (Component<N>) el;
        boolean everChanged = false;
        int iter = 0;
        boolean changed;
        do {
            changed = false;
            for (WtoElement<N> child : comp.body()) {
                changed |= solveElement(child, summaries, isFixed, transfer);
            }
            changed |= updateNode(comp.head(), summaries, isFixed, transfer);
            everChanged |= changed;
        } while (changed && ++iter < SAFETY_ITER_CAP);
        return everChanged;
    }

    private static <N, S> boolean updateNode(N node,
                                             Map<N, S> summaries,
                                             Predicate<N> isFixed,
                                             BiFunction<N, S, S> transfer) {
        if (isFixed.test(node)) {
            return false;
        }
        S prev = summaries.get(node);
        S next = transfer.apply(node, prev);
        if (!next.equals(prev)) {
            summaries.put(node, next);
            return true;
        }
        return false;
    }
}
