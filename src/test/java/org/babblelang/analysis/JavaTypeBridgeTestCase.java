package org.babblelang.analysis;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.TypeVariable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link JavaTypeBridge} multi-bound {@code TypeVariable} translation and F-bound
 * cycle guards ({@code BIP022-D7}).
 */
public class JavaTypeBridgeTestCase {
    @SuppressWarnings("unused")
    private static <T extends Number & Comparable<T>> T numberAndComparable(T x) {
        return x;
    }

    @SuppressWarnings("unused")
    private static <T extends Runnable & Serializable> T runnableAndSerializable(T x) {
        return x;
    }

    @SuppressWarnings("unused")
    private static <T extends Comparable<T>> T selfComparable(T x) {
        return x;
    }

    @Test
    public void translatesCollectionsMaxBoundWithoutCycle() throws NoSuchMethodException {
        Method max = Collections.class.getMethod("max", Collection.class);
        TypeVariable<Method> tv = max.getTypeParameters()[0];
        Type translated = JavaTypeBridge.fromJavaType(tv, Map.of(), false, false);
        Assertions.assertEquals(
                new Type.JavaInstance(Comparable.class, List.of(Type.NULLABLE_ANY)),
                translated);
    }

    @Test
    public void keepsLeadingNonObjectClassAlone() throws NoSuchMethodException {
        Method m = JavaTypeBridgeTestCase.class.getDeclaredMethod("numberAndComparable", Number.class);
        TypeVariable<Method> tv = m.getTypeParameters()[0];
        Type translated = JavaTypeBridge.fromJavaType(tv, Map.of(), false, false);
        Assertions.assertEquals(new Type.JavaInstance(Number.class), translated);
    }

    @Test
    public void intersectsMultiInterfaceBounds() throws NoSuchMethodException {
        Method m = JavaTypeBridgeTestCase.class.getDeclaredMethod("runnableAndSerializable", Runnable.class);
        TypeVariable<Method> tv = m.getTypeParameters()[0];
        Type translated = JavaTypeBridge.fromJavaType(tv, Map.of(), false, false);
        Assertions.assertEquals(
                Type.intersection(new Type.JavaInstance(Runnable.class), new Type.JavaInstance(Serializable.class)),
                translated);
    }

    @Test
    public void guardsRecursiveFBoundCycle() throws NoSuchMethodException {
        Method m = JavaTypeBridgeTestCase.class.getDeclaredMethod("selfComparable", Comparable.class);
        TypeVariable<Method> tv = m.getTypeParameters()[0];
        Type translated = JavaTypeBridge.fromJavaType(tv, Map.of(), false, false);
        Assertions.assertEquals(
                new Type.JavaInstance(Comparable.class, List.of(Type.NULLABLE_ANY)),
                translated);
    }
}
