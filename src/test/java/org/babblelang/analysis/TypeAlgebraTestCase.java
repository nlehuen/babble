package org.babblelang.analysis;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Focused unit tests for {@link Type#anyMatch}, {@link Type#mapTypes}, and {@link TypeAlgebra}
 * ({@code BIP010-P2}).
 */
public class TypeAlgebraTestCase {
    private static final TypeAlgebra.Env TEST_ENV = new TypeAlgebra.Env() {
        @Override
        public Type projectToBabble(Type.Instance source, TypeNode targetNode) {
            return source.node() == targetNode ? source : null;
        }

        @Override
        public Type projectToJava(Type source, Class<?> targetClass) {
            if (source instanceof Type.JavaInstance ji && targetClass.isAssignableFrom(ji.type())) {
                return ji.type() == targetClass ? ji : new Type.JavaInstance(targetClass);
            }
            return null;
        }

        @Override
        public boolean isSubtype(TypeNode sub, TypeNode sup) {
            return sub == sup;
        }

        @Override
        public boolean implementsJava(TypeNode node, Class<?> iface) {
            return false;
        }

        @Override
        public Class<?> javaClassOf(Type type) {
            if (type instanceof Type.Simple s) {
                return s.javaClass();
            }
            if (type instanceof Type.JavaInstance ji) {
                return ji.type();
            }
            if (type instanceof Type.JavaArray ja) {
                return ja.javaClass();
            }
            return null;
        }

        @Override
        public Type.Function samFunctionType(Type.JavaInstance java) {
            return null;
        }

        @Override
        public boolean isFunctionalInterface(Class<?> java) {
            return false;
        }

        @Override
        public Method functionalMethod(Class<?> java) {
            return null;
        }

        @Override
        public boolean isCallable(Type type) {
            return type instanceof Type.Function;
        }

        @Override
        public boolean checkingEffects() {
            return false;
        }

        @Override
        public boolean nonFunctionCallableAssignable(Type from, Type.Function target) {
            return false;
        }

        @Override
        public boolean functionAssignableWithoutEffects(Type.Function from, Type.Function to) {
            return TypeAlgebra.relate(from, to, TypeAlgebra.RelateMode.ASSIGNABLE, null, this);
        }
    };

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
        Assertions.assertTrue(TypeAlgebra.relate(Type.INT, Type.DOUBLE, TypeAlgebra.RelateMode.ASSIGNABLE, null, TEST_ENV));
        Assertions.assertFalse(TypeAlgebra.relate(Type.INT, Type.DOUBLE, TypeAlgebra.RelateMode.SUBTYPE, null, TEST_ENV));
        Assertions.assertFalse(TypeAlgebra.relate(Type.INT, Type.DOUBLE, TypeAlgebra.RelateMode.RUNTIME_EXACT, null, TEST_ENV));

        Type.TypeVar tv = new Type.TypeVar(null, "T", 0);
        TypeAlgebra.ConstraintStore store = new TypeAlgebra.ConstraintStore(Set.of(tv), true, TEST_ENV);
        // Contravariant function parameter: (any) -> int <: (T) -> int => T <= any
        Type fnActual = new Type.Function(List.of(new Type.Parameter("x", Type.ANY, false)), Type.INT);
        Type fnPattern = new Type.Function(List.of(new Type.Parameter("x", tv, false)), Type.INT);
        Assertions.assertTrue(TypeAlgebra.relate(fnActual, fnPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store, TEST_ENV));
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
        Assertions.assertFalse(TypeAlgebra.overlapsErased(Type.INT, Type.DOUBLE, TEST_ENV));
        Assertions.assertTrue(TypeAlgebra.canCast(Type.INT, Type.DOUBLE, TEST_ENV));
        Assertions.assertFalse(TypeAlgebra.canCast(Type.DOUBLE, Type.INT, TEST_ENV));

        Type union = Type.union(Type.INT, Type.STRING, Type.NULL);
        Assertions.assertEquals(Type.INT, TypeAlgebra.narrowTarget(union, Type.INT, TEST_ENV));
        Assertions.assertEquals(Type.union(Type.STRING, Type.NULL), TypeAlgebra.subtractType(union, Type.INT, TEST_ENV));
        // 1 is not a runtime subtype of double, so subtractType preserves int
        Assertions.assertEquals(union, TypeAlgebra.subtractType(union, Type.DOUBLE, TEST_ENV));
    }

    @Test
    public void joinsDistinguishExpressionAndReassignment() {
        Assertions.assertEquals(Type.DOUBLE, TypeAlgebra.join(Type.INT, Type.DOUBLE, TypeAlgebra.JoinPolicy.EXPRESSION, TEST_ENV));
        Assertions.assertEquals(Type.union(Type.INT, Type.STRING),
                TypeAlgebra.join(Type.INT, Type.STRING, TypeAlgebra.JoinPolicy.EXPRESSION, TEST_ENV));
        Assertions.assertNull(TypeAlgebra.join(Type.INT, Type.STRING, TypeAlgebra.JoinPolicy.REASSIGNMENT, TEST_ENV));
        Assertions.assertEquals(Type.nullable(Type.INT),
                TypeAlgebra.join(Type.INT, Type.NULL, TypeAlgebra.JoinPolicy.REASSIGNMENT, TEST_ENV));
    }
}
