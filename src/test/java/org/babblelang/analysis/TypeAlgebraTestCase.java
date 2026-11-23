package org.babblelang.analysis;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * Focused unit tests for {@link Type#anyMatch}, {@link Type#mapTypes}, and {@link TypeAlgebra}
 * ({@code BIP010-I2}).
 */
public class TypeAlgebraTestCase {
    private static final TypeAlgebra ALGEBRA = new TypeAlgebra(new TypeAlgebra.Env() {
        @Override
        public List<Type.TypeVar> typeParameters(TypeNode node) {
            return List.of();
        }

        @Override
        public Type parentType(TypeNode node, int index) {
            return Type.ERROR;
        }

        @Override
        public Type.Function samFunctionType(Type.JavaInstance java) {
            return null;
        }

        @Override
        public boolean checkingEffects() {
            return false;
        }

        @Override
        public boolean callableSatisfies(Type from, Type.Function target) {
            return false;
        }

        @Override
        public boolean isDeeplyImmutable(Type type) {
            return type == null || Type.isCoreScalar(type) || type == Type.NULL || type instanceof Type.Frozen;
        }
    });

    @Test
    public void catamorphismsPreserveIdentityWhenUnchanged() {
        Type.SymbolId id = new Type.SymbolId("f");
        Type.TypeVar tv = new Type.TypeVar(id, "T", 0);
        Type fn = new Type.Function(List.of(new Type.Parameter("x", tv, false)), Type.nullable(tv));

        Assertions.assertTrue(fn.anyMatch(t -> t.equals(tv)));
        Assertions.assertFalse(fn.anyMatch(t -> t == Type.DOUBLE));
        Assertions.assertSame(fn, fn.mapTypes(t -> null));

        Type mapped = fn.mapTypes(t -> t.equals(tv) ? Type.INT : null);
        Assertions.assertEquals(
                new Type.Function(List.of(new Type.Parameter("x", Type.INT, false)), Type.nullable(Type.INT)),
                mapped);
    }

    @Test
    public void relateModesAndConstraintStore() {
        Assertions.assertTrue(ALGEBRA.relate(Type.INT, Type.DOUBLE, TypeAlgebra.RelateMode.ASSIGNABLE, null));
        Assertions.assertFalse(ALGEBRA.relate(Type.INT, Type.DOUBLE, TypeAlgebra.RelateMode.SUBTYPE, null));
        Assertions.assertFalse(ALGEBRA.relate(Type.INT, Type.DOUBLE, TypeAlgebra.RelateMode.RUNTIME_EXACT, null));

        Type.TypeVar tv = new Type.TypeVar(null, "T", 0);
        TypeAlgebra.ConstraintStore store = ALGEBRA.store(Set.of(tv), true);
        // Contravariant function parameter: (any) -> int <: (T) -> int => T <= any
        Type fnActual = new Type.Function(List.of(new Type.Parameter("x", Type.ANY, false)), Type.INT);
        Type fnPattern = new Type.Function(List.of(new Type.Parameter("x", tv, false)), Type.INT);
        Assertions.assertTrue(ALGEBRA.relate(fnActual, fnPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store));
        Assertions.assertEquals(List.of(Type.ANY), store.uppers(tv));
        Assertions.assertNull(store.lower(tv));

        // Speculative right-union rollback
        int mark = store.mark();
        Assertions.assertTrue(store.addLower(tv, Type.STRING));
        Assertions.assertEquals(Type.STRING, store.lower(tv));
        store.reset(mark);
        Assertions.assertNull(store.lower(tv));
    }

    @Test
    public void overlapsErasedCastAndUnionElimination() {
        Assertions.assertFalse(ALGEBRA.overlapsErased(Type.INT, Type.DOUBLE));
        Assertions.assertTrue(ALGEBRA.canCast(Type.INT, Type.DOUBLE));
        Assertions.assertFalse(ALGEBRA.canCast(Type.DOUBLE, Type.INT));

        Type union = Type.union(Type.INT, Type.STRING, Type.NULL);
        Assertions.assertEquals(Type.INT, ALGEBRA.narrowTarget(union, Type.INT));
        Assertions.assertEquals(Type.union(Type.STRING, Type.NULL), ALGEBRA.subtractType(union, Type.INT));
        // 1 is not a runtime subtype of double, so subtractType preserves int
        Assertions.assertEquals(union, ALGEBRA.subtractType(union, Type.DOUBLE));
    }

    @Test
    public void joinsDistinguishExpressionAndReassignment() {
        Assertions.assertEquals(Type.DOUBLE, ALGEBRA.join(Type.INT, Type.DOUBLE));
        Assertions.assertEquals(Type.union(Type.INT, Type.STRING),
                ALGEBRA.join(Type.INT, Type.STRING));
        Assertions.assertNull(ALGEBRA.reassignmentJoin(Type.INT, Type.STRING));
        Assertions.assertEquals(Type.nullable(Type.INT),
                ALGEBRA.reassignmentJoin(Type.INT, Type.NULL));
    }
}
