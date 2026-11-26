package org.babblelang.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ANTLR-free polynomial-time Size-Change Termination ({@code SCP}) analyzer after
 * Lee–Jones–Ben-Amram ({@code [LJB01]}) and Ben-Amram–Lee ({@code [BAL07]}) ({@code BIP027-I6}).
 *
 * <p>Certifies termination of a strongly connected call component when every recursive call cycle
 * can be iteratively eliminated via sound well-founded rankings over non-negative integer or
 * acyclic structural measures:
 *
 * <ol>
 *   <li>Parameter-SCC sum / multiset / lexicographic ranking (handles direct recursion, mutual
 *       recursion, lexicographic Ackermann, and parameter-permuting {@code gcd}/swap patterns).
 *   <li>Maximum-measure greatest fixed point (handles fan-out decreases).
 *   <li>Minimum-measure greatest fixed point (handles fan-in decreases).
 * </ol>
 */
final class SizeChangeTermination {

    private SizeChangeTermination() {}

    enum Relation {
        /** Strictly smaller in a well-founded order ({@code <}). */
        DOWN,
        /** Non-increasing in a well-founded order ({@code <=}). */
        SAME
    }

    record Edge(int srcParam, int dstParam, Relation rel) {}

    record CallGraph(int from, int to, List<Edge> edges) {
        static CallGraph of(int from, int to, List<Edge> rawEdges) {
            if (rawEdges.isEmpty()) {
                return new CallGraph(from, to, List.of());
            }
            // Deduplicate (src, dst) pairs, preferring strict DOWN over SAME.
            Map<Long, Relation> best = new HashMap<>();
            for (Edge e : rawEdges) {
                long key = (((long) e.srcParam()) << 32) | (e.dstParam() & 0xFFFF_FFFFL);
                best.merge(
                        key,
                        e.rel(),
                        (a, b) ->
                                (a == Relation.DOWN || b == Relation.DOWN)
                                        ? Relation.DOWN
                                        : Relation.SAME);
            }
            List<Edge> dedup = new ArrayList<>(best.size());
            for (Map.Entry<Long, Relation> entry : best.entrySet()) {
                int src = (int) (entry.getKey() >> 32);
                int dst = (int) entry.getKey().longValue();
                dedup.add(new Edge(src, dst, entry.getValue()));
            }
            return new CallGraph(from, to, List.copyOf(dedup));
        }

        Relation relation(int src, int dst) {
            for (Edge e : edges) {
                if (e.srcParam() == src && e.dstParam() == dst) {
                    return e.rel();
                }
            }
            return null;
        }
    }

    /**
     * Returns {@code true} iff every infinite call sequence over {@code calls} is impossible under
     * well-founded orders on the tracked parameters.
     */
    static boolean isTerminating(int[] arities, List<CallGraph> calls) {
        int n = arities.length;
        if (n == 0 || calls.isEmpty()) {
            return true;
        }
        List<CallGraph> active = new ArrayList<>(calls);
        while (!active.isEmpty()) {
            List<List<Integer>> sccs = nonTrivialSccs(n, active);
            if (sccs.isEmpty()) {
                return true;
            }
            Set<CallGraph> toRemove = new HashSet<>();
            for (List<Integer> sccNodes : sccs) {
                Set<Integer> nodeSet = new HashSet<>(sccNodes);
                List<CallGraph> sccEdges = new ArrayList<>();
                for (CallGraph g : active) {
                    if (nodeSet.contains(g.from()) && nodeSet.contains(g.to())) {
                        sccEdges.add(g);
                    }
                }
                List<CallGraph> elim = findEliminableEdges(arities, sccNodes, sccEdges);
                if (elim.isEmpty()) {
                    return false;
                }
                toRemove.addAll(elim);
            }
            // Keep only edges inside non-trivial SCCs that were not eliminated.
            Set<Integer> inAnyScc = new HashSet<>();
            for (List<Integer> scc : sccs) {
                inAnyScc.addAll(scc);
            }
            List<CallGraph> next = new ArrayList<>();
            for (CallGraph g : active) {
                if (inAnyScc.contains(g.from())
                        && inAnyScc.contains(g.to())
                        && !toRemove.contains(g)) {
                    next.add(g);
                }
            }
            active = next;
        }
        return true;
    }

    private static List<CallGraph> findEliminableEdges(
            int[] arities, List<Integer> sccNodes, List<CallGraph> sccEdges) {
        List<CallGraph> elim = trySumRanking(arities, sccNodes, sccEdges);
        if (!elim.isEmpty()) {
            return elim;
        }
        elim = tryMaxRanking(arities, sccNodes, sccEdges);
        if (!elim.isEmpty()) {
            return elim;
        }
        return tryMinRanking(arities, sccNodes, sccEdges);
    }

    // ---------------------------------------------------------------- Criterion 1 : Parameter-SCC
    // sum/multiset ranking

    private static List<CallGraph> trySumRanking(
            int[] arities, List<Integer> sccNodes, List<CallGraph> sccEdges) {
        // Assign dense indices 0..totalParams-1 to all (fn, param) pairs in this SCC.
        Map<Integer, Integer> fnBase = new HashMap<>();
        int totalParams = 0;
        for (int f : sccNodes) {
            fnBase.put(f, totalParams);
            totalParams += arities[f];
        }
        if (totalParams == 0) {
            return List.of();
        }
        int[] ownerFn = new int[totalParams];
        int[] ownerParam = new int[totalParams];
        for (int f : sccNodes) {
            int base = fnBase.get(f);
            for (int i = 0; i < arities[f]; i++) {
                ownerFn[base + i] = f;
                ownerParam[base + i] = i;
            }
        }
        List<List<Integer>> paramAdj = new ArrayList<>(totalParams);
        for (int i = 0; i < totalParams; i++) {
            paramAdj.add(new ArrayList<>());
        }
        for (CallGraph g : sccEdges) {
            int fromBase = fnBase.get(g.from());
            int toBase = fnBase.get(g.to());
            for (Edge e : g.edges()) {
                if (e.srcParam() >= 0
                        && e.srcParam() < arities[g.from()]
                        && e.dstParam() >= 0
                        && e.dstParam() < arities[g.to()]) {
                    paramAdj.get(fromBase + e.srcParam()).add(toBase + e.dstParam());
                }
            }
        }

        List<List<Integer>> paramSccs = tarjanSccs(totalParams, paramAdj);
        for (List<Integer> comp : paramSccs) {
            // Check non-triviality of parameter SCC (has at least one internal edge).
            if (comp.size() == 1) {
                int v = comp.get(0);
                if (!paramAdj.get(v).contains(v)) {
                    continue;
                }
            }
            Map<Integer, List<Integer>> selected = new HashMap<>();
            for (int v : comp) {
                selected.computeIfAbsent(ownerFn[v], k -> new ArrayList<>()).add(ownerParam[v]);
            }
            if (selected.size() != sccNodes.size()) {
                continue;
            }
            boolean allNonIncreasing = true;
            List<CallGraph> strictEdges = new ArrayList<>();
            for (CallGraph g : sccEdges) {
                List<Integer> srcSet = selected.get(g.from());
                List<Integer> dstSet = selected.get(g.to());
                if (!hasInjection(g, srcSet, dstSet, -1, -1)) {
                    allNonIncreasing = false;
                    break;
                }
                if (hasStrictInjection(g, srcSet, dstSet)) {
                    strictEdges.add(g);
                }
            }
            if (allNonIncreasing && !strictEdges.isEmpty()) {
                return strictEdges;
            }
        }
        return List.of();
    }

    /**
     * Checks whether there is an injection {@code m : dstSet -> srcSet} using edges of {@code g}
     * (optionally skipping one pre-matched pair {@code (skipSrc, skipDst)}).
     */
    private static boolean hasInjection(
            CallGraph g, List<Integer> srcSet, List<Integer> dstSet, int skipSrc, int skipDst) {
        int m = dstSet.size() - (skipDst >= 0 ? 1 : 0);
        int n = srcSet.size() - (skipSrc >= 0 ? 1 : 0);
        if (m == 0) {
            return true;
        }
        if (m > n) {
            return false;
        }
        if (m == 1 && n == 1) {
            int dst = dstSet.get(0) == skipDst ? dstSet.get(1) : dstSet.get(0);
            int src = srcSet.get(0) == skipSrc ? srcSet.get(1) : srcSet.get(0);
            return g.relation(src, dst) != null;
        }
        int[] matchSrc = new int[srcSet.size()];
        Arrays.fill(matchSrc, -1);
        for (int di = 0; di < dstSet.size(); di++) {
            int dst = dstSet.get(di);
            if (dst == skipDst) {
                continue;
            }
            boolean[] visited = new boolean[srcSet.size()];
            if (!augment(g, srcSet, dstSet, di, skipSrc, visited, matchSrc)) {
                return false;
            }
        }
        return true;
    }

    private static boolean augment(
            CallGraph g,
            List<Integer> srcSet,
            List<Integer> dstSet,
            int di,
            int skipSrc,
            boolean[] visited,
            int[] matchSrc) {
        int dst = dstSet.get(di);
        for (int si = 0; si < srcSet.size(); si++) {
            int src = srcSet.get(si);
            if (src == skipSrc || visited[si] || g.relation(src, dst) == null) {
                continue;
            }
            visited[si] = true;
            if (matchSrc[si] == -1
                    || augment(g, srcSet, dstSet, matchSrc[si], skipSrc, visited, matchSrc)) {
                matchSrc[si] = di;
                return true;
            }
        }
        return false;
    }

    private static boolean hasStrictInjection(
            CallGraph g, List<Integer> srcSet, List<Integer> dstSet) {
        for (int dst : dstSet) {
            for (int src : srcSet) {
                if (g.relation(src, dst) == Relation.DOWN
                        && hasInjection(g, srcSet, dstSet, src, dst)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- Criterion 2 : Max-measure
    // greatest fixed point

    private static List<CallGraph> tryMaxRanking(
            int[] arities, List<Integer> sccNodes, List<CallGraph> sccEdges) {
        Map<Integer, Set<Integer>> kept = initParamSets(arities, sccNodes);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CallGraph g : sccEdges) {
                Set<Integer> srcSet = kept.get(g.from());
                Set<Integer> dstSet = kept.get(g.to());
                List<Integer> drop = new ArrayList<>();
                for (int dst : dstSet) {
                    boolean supported = false;
                    for (int src : srcSet) {
                        if (g.relation(src, dst) != null) {
                            supported = true;
                            break;
                        }
                    }
                    if (!supported) {
                        drop.add(dst);
                    }
                }
                if (!drop.isEmpty()) {
                    dstSet.removeAll(drop);
                    changed = true;
                }
            }
        }
        for (int f : sccNodes) {
            if (kept.get(f).isEmpty()) {
                return List.of();
            }
        }
        List<CallGraph> strict = new ArrayList<>();
        for (CallGraph g : sccEdges) {
            Set<Integer> srcSet = kept.get(g.from());
            Set<Integer> dstSet = kept.get(g.to());
            boolean allStrict = true;
            for (int dst : dstSet) {
                boolean hasDown = false;
                for (int src : srcSet) {
                    if (g.relation(src, dst) == Relation.DOWN) {
                        hasDown = true;
                        break;
                    }
                }
                if (!hasDown) {
                    allStrict = false;
                    break;
                }
            }
            if (allStrict) {
                strict.add(g);
            }
        }
        return strict;
    }

    // ---------------------------------------------------------------- Criterion 3 : Min-measure
    // greatest fixed point

    private static List<CallGraph> tryMinRanking(
            int[] arities, List<Integer> sccNodes, List<CallGraph> sccEdges) {
        Map<Integer, Set<Integer>> kept = initParamSets(arities, sccNodes);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (CallGraph g : sccEdges) {
                Set<Integer> srcSet = kept.get(g.from());
                Set<Integer> dstSet = kept.get(g.to());
                List<Integer> drop = new ArrayList<>();
                for (int src : srcSet) {
                    boolean supported = false;
                    for (int dst : dstSet) {
                        if (g.relation(src, dst) != null) {
                            supported = true;
                            break;
                        }
                    }
                    if (!supported) {
                        drop.add(src);
                    }
                }
                if (!drop.isEmpty()) {
                    srcSet.removeAll(drop);
                    changed = true;
                }
            }
        }
        for (int f : sccNodes) {
            if (kept.get(f).isEmpty()) {
                return List.of();
            }
        }
        List<CallGraph> strict = new ArrayList<>();
        for (CallGraph g : sccEdges) {
            Set<Integer> srcSet = kept.get(g.from());
            Set<Integer> dstSet = kept.get(g.to());
            boolean allStrict = true;
            for (int src : srcSet) {
                boolean hasDown = false;
                for (int dst : dstSet) {
                    if (g.relation(src, dst) == Relation.DOWN) {
                        hasDown = true;
                        break;
                    }
                }
                if (!hasDown) {
                    allStrict = false;
                    break;
                }
            }
            if (allStrict) {
                strict.add(g);
            }
        }
        return strict;
    }

    private static Map<Integer, Set<Integer>> initParamSets(int[] arities, List<Integer> sccNodes) {
        Map<Integer, Set<Integer>> sets = new HashMap<>();
        for (int f : sccNodes) {
            Set<Integer> s = new HashSet<>();
            for (int i = 0; i < arities[f]; i++) {
                s.add(i);
            }
            sets.put(f, s);
        }
        return sets;
    }

    // ---------------------------------------------------------------- Tarjan SCC helpers

    private static List<List<Integer>> nonTrivialSccs(int n, List<CallGraph> edges) {
        List<List<Integer>> adj = new ArrayList<>(n);
        boolean[] selfLoop = new boolean[n];
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (CallGraph g : edges) {
            adj.get(g.from()).add(g.to());
            if (g.from() == g.to()) {
                selfLoop[g.from()] = true;
            }
        }
        List<List<Integer>> all = tarjanSccs(n, adj);
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> comp : all) {
            if (comp.size() > 1 || (comp.size() == 1 && selfLoop[comp.get(0)])) {
                out.add(comp);
            }
        }
        return out;
    }

    private static List<List<Integer>> tarjanSccs(int n, List<List<Integer>> adj) {
        int[] index = new int[n];
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(index, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        List<List<Integer>> result = new ArrayList<>();
        int[] nextIdx = {0};

        class Tarjan {
            void dfs(int v) {
                index[v] = low[v] = nextIdx[0]++;
                stack.push(v);
                onStack[v] = true;
                for (int w : adj.get(v)) {
                    if (index[w] == -1) {
                        dfs(w);
                        low[v] = Math.min(low[v], low[w]);
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                }
                if (low[v] == index[v]) {
                    List<Integer> comp = new ArrayList<>();
                    while (true) {
                        int w = stack.pop();
                        onStack[w] = false;
                        comp.add(w);
                        if (w == v) {
                            break;
                        }
                    }
                    result.add(comp);
                }
            }
        }

        Tarjan t = new Tarjan();
        for (int i = 0; i < n; i++) {
            if (index[i] == -1) {
                t.dfs(i);
            }
        }
        return result;
    }
}
