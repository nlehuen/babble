package org.babblelang.analysis;

import org.babblelang.effects.Effect;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Direct unit tests for the ANTLR-free {@code BIP027-P5} cores : {@link ModalKindTable} (mode
 * crossing), the shallow modality of {@link Reachability.Origin}, the {@link Purity} lattice, and
 * the lowering of host registrations into axioms ({@link HostEffects.Registry}).
 */
public class ModalKindsAndPurityTestCase {

    // ---------------------------------------------------------------- ModalKindTable

    private static ModalKindTable table(Set<Class<?>> immutableClasses) {
        return new ModalKindTable(new ModalKindTable.Types() {
            @Override
            public boolean isJavaClassImmutable(Class<?> cls) {
                return immutableClasses.contains(cls);
            }

            @Override
            public Type memberType(TypeNode node, TypeNode.Member member) {
                return Type.ERROR;
            }

            @Override
            public Map<Type.TypeVar, Type> instanceEnv(Type.Instance instance) {
                return Map.of();
            }
        });
    }

    @Test
    public void testScalarsFrozenAndUnionsCrossEveryAxis() {
        ModalKindTable kinds = table(Set.of());
        for (Type t : List.of(Type.INT, Type.STRING, Type.NULL, new Type.Frozen(new Type.JavaInstance(StringBuilder.class)),
                new Type.Union(List.of(Type.INT, Type.NULL)))) {
            Assertions.assertTrue(kinds.isDeeplyImmutable(t), t.toString());
            Assertions.assertTrue(kinds.crosses(t, ModalKindTable.Axis.PORTABILITY), t.toString());
        }
        Type mutable = new Type.JavaInstance(StringBuilder.class);
        Assertions.assertTrue(kinds.kindOf(mutable).isNever());
        Assertions.assertFalse(kinds.isDeeplyImmutable(new Type.Union(List.of(Type.INT, mutable))));
    }

    @Test
    public void testImmutableJavaClassDependsOnItsTypeArguments() {
        ModalKindTable kinds = table(Set.of(Map.Entry.class));
        Type sb = new Type.JavaInstance(StringBuilder.class);
        Assertions.assertTrue(kinds.isDeeplyImmutable(new Type.JavaInstance(Map.Entry.class, List.of(Type.STRING, Type.INT))));
        Assertions.assertFalse(kinds.isDeeplyImmutable(new Type.JavaInstance(Map.Entry.class, List.of(Type.STRING, sb))));
        // Raw : its type arguments are unknown.
        Assertions.assertFalse(kinds.isDeeplyImmutable(new Type.JavaInstance(Map.Entry.class)));

        // A type variable is a dependency, not a verdict : the kind crosses once it is instantiated.
        Type.TypeVar k = new Type.TypeVar(new Type.SymbolId("Entry"), "K", 0);
        ModalKindTable.ModalKind kind = kinds.kindOf(new Type.JavaInstance(Map.Entry.class, List.of(k, Type.INT)));
        Assertions.assertFalse(kind.isNever());
        Assertions.assertEquals(Set.of(k), kind.deps());
        Assertions.assertFalse(kind.crossesUnconditionally(ModalKindTable.Axis.CONTENTION));
    }

    @Test
    public void testModalKindMeet() {
        Type.TypeVar a = new Type.TypeVar(new Type.SymbolId("P"), "A", 0);
        Type.TypeVar b = new Type.TypeVar(new Type.SymbolId("P"), "B", 1);
        ModalKindTable.ModalKind ka = ModalKindTable.ModalKind.dependingOn(Set.of(a));
        ModalKindTable.ModalKind kb = ModalKindTable.ModalKind.dependingOn(Set.of(b));
        Assertions.assertEquals(Set.of(a, b), ka.meet(kb).deps());
        Assertions.assertSame(ka, ka.meet(ModalKindTable.ModalKind.ALWAYS));
        Assertions.assertTrue(ka.meet(ModalKindTable.ModalKind.NEVER).isNever());
    }

    // ---------------------------------------------------------------- Shallow modality

    @Test
    public void testShallowModalityKeepsTheShellFreshAndFlattensOnEscape() {
        Reachability.Origin fresh = Reachability.Origin.ofUnique("xs", "xs", false);
        Assertions.assertTrue(fresh.unique);
        Assertions.assertTrue(fresh.localFresh());
        Assertions.assertTrue(fresh.owned());
        Assertions.assertFalse(fresh.hasElementTaint());

        // Storing a parameter's value into the fresh container : the shell stays owned, the element is tracked.
        Reachability.Origin param = Reachability.Origin.ofParam(1, "p", false);
        Reachability.Origin filled = fresh.absorbElement(param);
        Assertions.assertFalse(filled.unique);
        Assertions.assertTrue(filled.localFresh());
        Assertions.assertTrue(filled.owned());
        Assertions.assertTrue(filled.params.isEmpty(), "the shell itself reaches no parameter");
        Assertions.assertTrue(filled.elements().params().get(1));

        // Reading, escaping, or joining with an unowned value flattens the elements into direct provenance.
        Reachability.Origin flat = filled.flattenElements();
        Assertions.assertFalse(flat.localFresh());
        Assertions.assertTrue(flat.params.get(1));
        Reachability.Origin joined = filled.join(param);
        Assertions.assertFalse(joined.owned());
        Assertions.assertTrue(joined.params.get(1));

        // Two fresh shells join into a fresh shell whose elements reach both.
        Reachability.Origin other = Reachability.Origin.ofUnique("xs", "xs", false)
                .absorbElement(Reachability.Origin.ofParam(2, "q", false));
        Reachability.Origin both = filled.join(other);
        Assertions.assertTrue(both.localFresh());
        BitSet expected = new BitSet();
        expected.set(1);
        expected.set(2);
        Assertions.assertEquals(expected, both.elements().params());
        Assertions.assertEquals(both, filled.join(other));
    }

    // ---------------------------------------------------------------- Purity lattice

    @Test
    public void testPurityLattice() {
        EffectSet pure = EffectSet.ofDeclared(EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, true, true);
        EffectSet vol = EffectSet.ofDeclared(EnumSet.of(Effect.PURE, Effect.HERMETIC), false, true, true);
        Assertions.assertEquals(Purity.PURE, Purity.of(pure, false));
        Assertions.assertEquals(Purity.CONTEXTUALLY_PURE, Purity.of(pure, new boolean[]{false, true}));
        Assertions.assertEquals(Purity.SIDE_EFFECT_FREE, Purity.of(vol, false));
        Assertions.assertEquals(Purity.CONTEXTUALLY_SIDE_EFFECT_FREE, Purity.of(vol, true));
        Assertions.assertEquals(Purity.IMPURE, Purity.of(EffectSet.IO_DECLARED, false));
        Assertions.assertEquals(Purity.IMPURE, Purity.of(EffectSet.EMPTY, false));

        Assertions.assertEquals(Purity.CONTEXTUALLY_SIDE_EFFECT_FREE, Purity.SIDE_EFFECT_FREE.join(Purity.CONTEXTUALLY_PURE));
        for (Purity p : Purity.values()) {
            Assertions.assertEquals(p, p.join(Purity.PURE));
            Assertions.assertEquals(Purity.IMPURE, p.join(Purity.IMPURE));
            Assertions.assertTrue(Purity.PURE.atLeastAsPureAs(p));
            Assertions.assertTrue(p.atLeastAsPureAs(Purity.IMPURE));
        }
        Assertions.assertFalse(Purity.SIDE_EFFECT_FREE.atLeastAsPureAs(Purity.CONTEXTUALLY_PURE));
        Assertions.assertTrue(Purity.PURE.mutatesNothing() && Purity.SIDE_EFFECT_FREE.mutatesNothing());
        Assertions.assertFalse(Purity.CONTEXTUALLY_PURE.mutatesNothing());
        Assertions.assertTrue(Purity.CONTEXTUALLY_PURE.isDeterministic());
        Assertions.assertFalse(Purity.SIDE_EFFECT_FREE.isDeterministic());
    }

    // ---------------------------------------------------------------- Host registrations as axioms

    public static final class Host {
        public static int calc(int x) {
            return x + 1;
        }

        public int bump(int x) {
            return x;
        }
    }

    @Test
    public void testHostRegistrationsLowerIntoAnAxiomTable() throws Exception {
        HostEffects.Registry registry = new HostEffects.Registry();
        Assertions.assertSame(HostEffects.DEFAULT, registry.toHostEffects(true, true, BuiltinAxioms.standard()));

        registry.assumeMethod(Host.class, "calc", Effect.PURE, Effect.NOTHROW);
        registry.assumeConstructor(Host.class, Effect.PURE);
        registry.markIO(Host.class);
        AxiomData.AxiomTable table = registry.toHostEffects(true, true, BuiltinAxioms.standard()).hostAxioms();
        List<AxiomData.MemberAxiom> members = table.directMembers(Host.class);
        Assertions.assertEquals(4, members.size(), members.toString());

        Method calc = Host.class.getMethod("calc", int.class);
        AxiomData.MemberAxiom named = AxiomData.lookupMethod(Host.class, "calc", calc,
                c -> table.directMembers(c).stream().filter(m -> !m.isWildcard()).toList());
        Assertions.assertNotNull(named);
        EffectChecker.JavaCallProfile profile = named.toProfile(calc);
        Assertions.assertEquals(Purity.PURE, profile.purity());
        Assertions.assertTrue(profile.explicitNothrow());

        // The class registration is an exact wildcard : it applies to the class's own methods.
        Method bump = Host.class.getMethod("bump", int.class);
        AxiomData.MemberAxiom wildcard = AxiomData.lookupMethod(Host.class, "bump", bump,
                c -> table.directMembers(c).stream().filter(AxiomData.MemberAxiom::isWildcard).toList());
        Assertions.assertNotNull(wildcard);
        Assertions.assertTrue(wildcard.exact());
        Assertions.assertTrue(wildcard.toProfile(bump).effects().isIO());
        Assertions.assertNotNull(AxiomData.lookupConstructor(Host.class, Host.class.getConstructor(), table::directMembers));

        // A later registration about the same target replaces the earlier one.
        registry.assumeMethod(Host.class, "calc", Effect.VOLATILE);
        AxiomData.AxiomTable replaced = registry.toHostEffects(true, true, BuiltinAxioms.standard()).hostAxioms();
        AxiomData.MemberAxiom volatileCalc = AxiomData.lookupMethod(Host.class, "calc", calc,
                c -> replaced.directMembers(c).stream().filter(m -> !m.isWildcard()).toList());
        Assertions.assertEquals(Purity.SIDE_EFFECT_FREE, volatileCalc.toProfile(calc).purity());

        Assertions.assertThrows(IllegalArgumentException.class, () -> registry.assumeClass(Host.class, Effect.MUT));
        Assertions.assertThrows(IllegalArgumentException.class, () -> registry.assumeConstructor(Host.class, Effect.MUT));
        Assertions.assertThrows(IllegalArgumentException.class, () -> registry.assumeMethod(Host.class, "calc", Effect.IO, Effect.PURE));
        Assertions.assertThrows(IllegalArgumentException.class, () -> registry.assumeMethod(Host.class, "calc"));
    }
}
