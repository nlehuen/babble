package org.babblelang.analysis;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.effects.Effect;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for the ANTLR-free {@code BIP027-I4} algorithmic cores: {@link
 * EffectFixpointDriver} (Bourdoncle WTO), {@link Reachability} (depth-bounded {@code PathKey}
 * widening &amp; {@code BIP027-D5} prefix overlap), and {@link SymbolicEffectSummary} (single-pass
 * {@code O(k)} HOF instantiation).
 */
public class EffectCoreTestCase {

    @Test
    public void testBourdoncleWtoAcyclicOrderAndNestedCycles() {
        String a = new String("A");
        String b = new String("B");
        String c = new String("C");
        String d = new String("D");

        // Call graph: A -> {B, D}, B -> {A, C}, C -> {B}, D -> {}
        // D is acyclic callee; {A, B, C} has nested cycle B <-> C inside outer cycle A <-> B.
        Map<String, Set<String>> edges = new IdentityHashMap<>();
        edges.put(a, Set.of(b, d));
        edges.put(b, Set.of(a, c));
        edges.put(c, Set.of(b));
        edges.put(d, Set.of());

        List<EffectFixpointDriver.WtoElement<String>> wto =
                EffectFixpointDriver.buildWto(List.of(a, b, c, d), edges);
        Set<String> cyclic = EffectFixpointDriver.cyclicNodes(wto);
        Assertions.assertTrue(cyclic.contains(a));
        Assertions.assertTrue(cyclic.contains(b));
        Assertions.assertTrue(cyclic.contains(c));
        Assertions.assertFalse(cyclic.contains(d));

        // Monotone fixpoint: propagate integer level from C (2) and D (1) upward via max.
        Map<String, Integer> vals = new IdentityHashMap<>();
        vals.put(a, 0);
        vals.put(b, 0);
        vals.put(c, 2);
        vals.put(d, 1);
        List<String> evalOrder = new ArrayList<>();

        EffectFixpointDriver.solve(
                wto,
                vals,
                n -> n == d,
                (node, prev) -> {
                    evalOrder.add(node);
                    int max = prev;
                    for (String callee : edges.getOrDefault(node, Set.of())) {
                        max = Math.max(max, vals.get(callee));
                    }
                    return max;
                });

        Assertions.assertEquals(2, vals.get(a));
        Assertions.assertEquals(2, vals.get(b));
        Assertions.assertEquals(2, vals.get(c));
        Assertions.assertEquals(1, vals.get(d));
        Assertions.assertFalse(evalOrder.contains(d), "Fixed node should not be re-evaluated");
    }

    @Test
    public void testBourdoncleWtoSelfLoop() {
        String rec = new String("rec");
        String caller = new String("caller");
        Map<String, Set<String>> edges = new IdentityHashMap<>();
        edges.put(caller, Set.of(rec));
        edges.put(rec, Set.of(rec));

        List<EffectFixpointDriver.WtoElement<String>> wto =
                EffectFixpointDriver.buildWto(List.of(caller, rec), edges);
        Set<String> cyclic = EffectFixpointDriver.cyclicNodes(wto);
        Assertions.assertTrue(cyclic.contains(rec));
        Assertions.assertFalse(cyclic.contains(caller));

        Map<String, Integer> vals = new IdentityHashMap<>();
        vals.put(rec, 0);
        vals.put(caller, 0);
        EffectFixpointDriver.solve(
                wto,
                vals,
                n -> false,
                (node, prev) -> {
                    if (node == rec) {
                        return prev < 3 ? prev + 1 : prev;
                    }
                    return vals.get(rec);
                });
        Assertions.assertEquals(3, vals.get(rec));
        Assertions.assertEquals(3, vals.get(caller));
    }

    @Test
    public void testReachabilityPathKeyDepthBoundOverlapAndWidening() {
        Object rootA = new Object();
        Object rootB = new Object();

        Reachability.PathKey pRoot = new Reachability.PathKey(rootA, List.of());
        Reachability.PathKey pA = pRoot.append("a");
        Reachability.PathKey pB = pRoot.append("b");
        Reachability.PathKey pAX = pA.append("x");
        Reachability.PathKey pAXY = pAX.append("y");
        Reachability.PathKey pAXYZ = pAXY.append("z");
        Reachability.PathKey pOther = new Reachability.PathKey(rootB, List.of("a"));

        // Depth bound k = 3: 4th segment truncates at depth 3.
        Assertions.assertFalse(pAXY.truncated());
        Assertions.assertEquals(3, pAXY.segments().size());
        Assertions.assertTrue(pAXYZ.truncated());
        Assertions.assertEquals(List.of("a", "x", "y"), pAXYZ.segments());

        // Prefix-overlap exclusivity (BIP027-D5).
        Assertions.assertTrue(pRoot.overlaps(pA));
        Assertions.assertTrue(pA.overlaps(pRoot));
        Assertions.assertTrue(pA.overlaps(pAX));
        Assertions.assertTrue(pAXY.overlaps(pAXYZ));
        Assertions.assertFalse(pA.overlaps(pB), "Disjoint sibling fields must not overlap");
        Assertions.assertFalse(pA.overlaps(pOther), "Different roots must not overlap");

        // Widening converges to longest common prefix and is idempotent.
        Reachability.PathKey pAZ = pA.append("z");
        Reachability.PathKey widened = pAX.widen(pAZ);
        Assertions.assertEquals(new Reachability.PathKey(rootA, List.of("a"), true), widened);
        Assertions.assertEquals(widened, widened.widen(pAZ));
        Assertions.assertNull(pA.widen(pOther));

        // Origin.staticallySameObject enforces prefix overlap and alias-root overlap.
        Reachability.Origin origPair = Reachability.Origin.ofParam(0, rootA, false);
        Reachability.Origin origPairA = origPair.deriveMember("a", false);
        Reachability.Origin origPairB = origPair.deriveMember("b", false);
        Assertions.assertTrue(Reachability.staticallySameObject(origPair, origPairA));
        Assertions.assertTrue(Reachability.staticallySameObject(origPairA, origPair));
        Assertions.assertFalse(Reachability.staticallySameObject(origPairA, origPairB));
        Assertions.assertFalse(
                Reachability.staticallySameObject(origPair, Reachability.Origin.IMMUTABLE));
    }

    @Test
    public void testSymbolicEffectSummaryInstantiation() {
        EffectSet pureEff =
                EffectSet.ofInferred(
                        EnumSet.of(
                                Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                        false,
                        true,
                        false,
                        true);
        EffectChecker.CallableSummary base =
                new EffectChecker.CallableSummary(
                        EffectSet.EMPTY, new boolean[] {false, false}, new boolean[] {true, true});

        BitSet called = new BitSet();
        called.set(0);
        long[][] paramFlow =
                new long[][] {new long[] {1L << 1}, null}; // param 1 flows into arg 0 of cb 0
        boolean[][] thisFlow = new boolean[][] {new boolean[] {false}, null};
        boolean[][] extFlow = new boolean[][] {new boolean[] {false}, null};

        SymbolicEffectSummary sym =
                new SymbolicEffectSummary(
                        base,
                        false,
                        pureEff,
                        new boolean[] {false, false},
                        new boolean[] {true, true},
                        new boolean[] {false, false},
                        false,
                        called,
                        new EffectSet[] {EffectSet.EMPTY, null},
                        paramFlow,
                        thisFlow,
                        extFlow);

        // 1. Instantiate with pure callback reading arg 0 -> pure, readParams[1] = true.
        Type.Parameter readParam =
                new Type.Parameter("b", Type.ANY, false, false, false, true, false, false);
        Type.Function pureReader = new Type.Function(List.of(readParam), Type.INT, pureEff);
        EffectChecker.CallableSummary pureSum =
                new EffectChecker.CallableSummary(
                        pureEff,
                        new boolean[] {false},
                        new boolean[] {true},
                        new boolean[] {false},
                        Set.of(),
                        Set.of(),
                        false,
                        EffectChecker.EMPTY_SUMMARY_BITS,
                        false,
                        false,
                        false,
                        EffectChecker.EMPTY_SUMMARY_BITS,
                        new boolean[] {true},
                        false);
        EffectChecker.CallableSummary instPure =
                sym.instantiate(
                        new Type.Function[] {pureReader, null},
                        new EffectChecker.CallableSummary[] {pureSum, null});
        Assertions.assertTrue(instPure.effects().isPure());
        Assertions.assertFalse(instPure.mutParams()[1]);
        Assertions.assertTrue(instPure.readParams()[1]);

        // 2. Instantiate with mut+borrow callback on arg 0 -> mutParams[1] = true, borrowParams[1]
        // stays true, not pure.
        EffectSet mutEff =
                EffectSet.ofInferred(
                        EnumSet.of(Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                        false,
                        true,
                        false,
                        true);
        Type.Parameter mutBorrowParam =
                new Type.Parameter("b", Type.ANY, false, false, true, true, false, false);
        Type.Function mutBorrowCb = new Type.Function(List.of(mutBorrowParam), Type.INT, mutEff);
        EffectChecker.CallableSummary instMut =
                sym.instantiate(new Type.Function[] {mutBorrowCb, null}, null);
        Assertions.assertFalse(instMut.effects().isPure());
        Assertions.assertTrue(instMut.effects().isHermetic());
        Assertions.assertTrue(instMut.mutParams()[1]);
        Assertions.assertTrue(instMut.borrowParams()[1]);

        // 3. Instantiate with shared (non-borrow) callback on arg 0 -> borrowParams[1] = false.
        Type.Parameter sharedParam =
                new Type.Parameter("b", Type.ANY, false, false, false, false, true, false);
        Type.Function sharedCb = new Type.Function(List.of(sharedParam), Type.INT, pureEff);
        EffectChecker.CallableSummary instShared =
                sym.instantiate(new Type.Function[] {sharedCb, null}, null);
        Assertions.assertFalse(instShared.borrowParams()[1]);

        // 3. Instantiate with IO callback -> IO.
        Type.Function ioCb = new Type.Function(List.of(readParam), Type.INT, EffectSet.IO_DECLARED);
        EffectChecker.CallableSummary instIo =
                sym.instantiate(new Type.Function[] {ioCb, null}, null);
        Assertions.assertTrue(instIo.effects().isIO());
        Assertions.assertFalse(instIo.effects().isHermetic());
    }
}
