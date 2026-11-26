package org.babblelang.ir.mir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CFG, Reverse Post-Order (RPO), dominator-tree (Cooper–Harvey–Kennedy), and natural-loop analysis
 * over {@link MirFunction} ({@code BIP014-I3}).
 *
 * <p>All CFG traversals include both normal terminator edges and exception-handler edges so
 * dominance, liveness, verification, and optimization passes stay sound under {@code BIP028} {@code
 * try}/{@code catch}/{@code finally}.
 */
public final class MirCfg {
    private final MirFunction fn;
    private final Map<Integer, MirBlock> blocksById;
    private final List<MirBlock> rpo;
    private final Map<Integer, Integer> rpoIndex;
    private final Map<Integer, List<Integer>> succs;
    private final Map<Integer, List<Integer>> preds;
    private final int[] idomRpo;
    private final List<List<Integer>> domChildrenRpo;

    private MirCfg(MirFunction fn) {
        this.fn = fn;
        this.blocksById = fn.blockMap();
        this.succs = new LinkedHashMap<>();
        this.preds = new LinkedHashMap<>();
        for (MirBlock b : fn.blocks()) {
            succs.put(b.id(), computeSuccessors(b));
            preds.put(b.id(), new ArrayList<>());
        }

        // Iterative post-order DFS from entryBlockId (avoids stack overflow on very large CFGs).
        List<MirBlock> postOrder = new ArrayList<>(fn.blocks().size());
        Set<Integer> visited = new HashSet<>();
        MirBlock entry = blocksById.get(fn.entryBlockId());
        if (entry != null) {
            record Frame(int blockId, int nextSucc) {}
            Deque<Frame> stack = new ArrayDeque<>();
            visited.add(entry.id());
            stack.push(new Frame(entry.id(), 0));
            while (!stack.isEmpty()) {
                Frame top = stack.pop();
                List<Integer> sList = succs.getOrDefault(top.blockId, List.of());
                if (top.nextSucc < sList.size()) {
                    stack.push(new Frame(top.blockId, top.nextSucc + 1));
                    int nextId = sList.get(top.nextSucc);
                    if (blocksById.containsKey(nextId) && visited.add(nextId)) {
                        stack.push(new Frame(nextId, 0));
                    }
                } else {
                    postOrder.add(blocksById.get(top.blockId));
                }
            }
        }
        Collections.reverse(postOrder);
        this.rpo = List.copyOf(postOrder);
        this.rpoIndex = new HashMap<>(rpo.size() * 2);
        for (int i = 0; i < rpo.size(); i++) {
            rpoIndex.put(rpo.get(i).id(), i);
        }
        // Populate predecessors restricted to reachable blocks so dominance is exact.
        for (MirBlock b : rpo) {
            for (int sid : succs.getOrDefault(b.id(), List.of())) {
                if (rpoIndex.containsKey(sid)) {
                    preds.get(sid).add(b.id());
                }
            }
        }

        // Shared by every user of a cached analysis : read-only from now on.
        preds.replaceAll((id, list) -> List.copyOf(list));

        // Cooper-Harvey-Kennedy iterative dominator algorithm on RPO indices.
        int n = rpo.size();
        this.idomRpo = new int[n];
        for (int i = 0; i < n; i++) {
            idomRpo[i] = -1;
        }
        if (n > 0) {
            idomRpo[0] = 0;
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int b = 1; b < n; b++) {
                    int newIdom = -1;
                    for (int predId : preds.get(rpo.get(b).id())) {
                        int p = rpoIndex.get(predId);
                        if (idomRpo[p] != -1) {
                            newIdom = (newIdom == -1) ? p : intersect(p, newIdom, idomRpo);
                        }
                    }
                    if (newIdom != -1 && idomRpo[b] != newIdom) {
                        idomRpo[b] = newIdom;
                        changed = true;
                    }
                }
            }
        }
        this.domChildrenRpo = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            domChildrenRpo.add(new ArrayList<>());
        }
        for (int i = 1; i < n; i++) {
            int p = idomRpo[i];
            if (p >= 0 && p < n) {
                domChildrenRpo.get(p).add(i);
            }
        }
    }

    /** The analyses of the functions of the current {@link #withCache} scope, by identity. */
    private static final ThreadLocal<Map<MirFunction, MirCfg>> CACHE = new ThreadLocal<>();

    public static MirCfg analyze(MirFunction fn) {
        Map<MirFunction, MirCfg> cache = CACHE.get();
        return cache == null ? new MirCfg(fn) : cache.computeIfAbsent(fn, MirCfg::new);
    }

    /**
     * Runs {@code body} with {@link #analyze} keeping the analysis of each function it sees :
     * functions are immutable, so the passes of a pipeline that leave a function as is share one
     * analysis of it instead of computing it again each.
     */
    public static <T> T withCache(java.util.function.Supplier<T> body) {
        if (CACHE.get() != null) {
            return body.get();
        }
        CACHE.set(new java.util.IdentityHashMap<>());
        try {
            return body.get();
        } finally {
            CACHE.remove();
        }
    }

    private static List<Integer> computeSuccessors(MirBlock b) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int s : b.terminator().successorIds()) {
            out.add(s);
        }
        for (MirHandler h : b.handlers()) {
            out.add(h.handlerBlockId());
        }
        return List.copyOf(out);
    }

    private static int intersect(int b1, int b2, int[] doms) {
        int f1 = b1;
        int f2 = b2;
        while (f1 != f2) {
            while (f1 > f2) {
                f1 = doms[f1];
            }
            while (f2 > f1) {
                f2 = doms[f2];
            }
        }
        return f1;
    }

    public MirFunction function() {
        return fn;
    }

    public List<MirBlock> rpo() {
        return rpo;
    }

    public boolean isReachable(int blockId) {
        return rpoIndex.containsKey(blockId);
    }

    public int rpoIndexOf(int blockId) {
        return rpoIndex.getOrDefault(blockId, -1);
    }

    public List<Integer> successors(int blockId) {
        return succs.getOrDefault(blockId, List.of());
    }

    public List<Integer> predecessors(int blockId) {
        return preds.getOrDefault(blockId, List.of());
    }

    /**
     * Immediate dominator block ID, or {@code -1} for unreachable blocks (entry returns itself).
     */
    public int immediateDominator(int blockId) {
        Integer idx = rpoIndex.get(blockId);
        if (idx == null || idomRpo[idx] < 0) {
            return -1;
        }
        return rpo.get(idomRpo[idx]).id();
    }

    /** Dominator-tree child block IDs in deterministic RPO order. */
    public List<Integer> domChildren(int blockId) {
        Integer idx = rpoIndex.get(blockId);
        if (idx == null) {
            return List.of();
        }
        List<Integer> childRpos = domChildrenRpo.get(idx);
        List<Integer> out = new ArrayList<>(childRpos.size());
        for (int c : childRpos) {
            out.add(rpo.get(c).id());
        }
        return out;
    }

    /** Whether block {@code domBlockId} dominates block {@code targetBlockId} (reflexive). */
    public boolean dominates(int domBlockId, int targetBlockId) {
        Integer dIdx = rpoIndex.get(domBlockId);
        Integer tIdx = rpoIndex.get(targetBlockId);
        if (dIdx == null || tIdx == null) {
            return false;
        }
        int cur = tIdx;
        while (cur > dIdx) {
            int parent = idomRpo[cur];
            if (parent == cur || parent < 0) {
                return false;
            }
            cur = parent;
        }
        return cur == dIdx;
    }

    /**
     * Natural loop with its header, body blocks (including header), back-edge latches, and outside
     * predecessors.
     */
    public record NaturalLoop(
            int headerId,
            Set<Integer> bodyIds,
            Set<Integer> latchIds,
            List<Integer> outsidePredecessors) {}

    /** Finds all natural loops (grouped by header, inner-to-outer order by body size). */
    public List<NaturalLoop> findNaturalLoops() {
        Map<Integer, Set<Integer>> bodies = new LinkedHashMap<>();
        Map<Integer, Set<Integer>> latches = new LinkedHashMap<>();
        for (MirBlock b : rpo) {
            for (int succId : b.terminator().successorIds()) {
                if (isReachable(succId) && dominates(succId, b.id())) {
                    Set<Integer> body = bodies.computeIfAbsent(succId, k -> new LinkedHashSet<>());
                    latches.computeIfAbsent(succId, k -> new LinkedHashSet<>()).add(b.id());
                    body.add(succId);
                    if (body.add(b.id())) {
                        Deque<Integer> work = new ArrayDeque<>();
                        work.push(b.id());
                        while (!work.isEmpty()) {
                            int cur = work.pop();
                            for (int p : predecessors(cur)) {
                                if (body.add(p)) {
                                    work.push(p);
                                }
                            }
                        }
                    }
                }
            }
        }
        List<NaturalLoop> result = new ArrayList<>(bodies.size());
        for (Map.Entry<Integer, Set<Integer>> e : bodies.entrySet()) {
            int header = e.getKey();
            Set<Integer> body = e.getValue();
            List<Integer> outside = new ArrayList<>();
            for (int p : predecessors(header)) {
                if (!body.contains(p)) {
                    outside.add(p);
                }
            }
            result.add(
                    new NaturalLoop(
                            header,
                            Collections.unmodifiableSet(body),
                            Collections.unmodifiableSet(latches.get(header)),
                            List.copyOf(outside)));
        }
        // Sort innermost loops first (smallest body first) for clean bottom-up LICM.
        result.sort((a, b) -> Integer.compare(a.bodyIds().size(), b.bodyIds().size()));
        return result;
    }
}
