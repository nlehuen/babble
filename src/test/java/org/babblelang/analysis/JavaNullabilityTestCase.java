package org.babblelang.analysis;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What JavaNullability makes of nullness annotations, whose meaning is an axiom (BIP019-D10),
 * and of the java.base overlay (BIP019-P3).
 */
class JavaNullabilityTestCase {
    public static class Fixture {
        public @Nullable String maybe() { return null; }
        public @NonNull String surely() { return ""; }
        public String plain() { return ""; }
        @javax.annotation.Nonnull public String jsr305() { return ""; }
        @javax.annotation.Nonnull(when = javax.annotation.Nonnull.When.MAYBE) public String jsr305Maybe() { return ""; }
        public void take(@Nullable String a, @NonNull String b, String c) { }
    }

    @NullMarked
    public static class Marked {
        public String plain() { return ""; }

        @NullUnmarked
        public String unmarked() { return ""; }
    }

    private static java.lang.reflect.Method method(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        return c.getMethod(name, params);
    }

    @Test
    void annotationsMeanWhatTheirAxiomsSay() throws Exception {
        assertEquals(JavaNullability.Nullness.NULLABLE, JavaNullability.returnNullness(method(Fixture.class, "maybe")));
        assertEquals(JavaNullability.Nullness.NON_NULL, JavaNullability.returnNullness(method(Fixture.class, "surely")));
        assertEquals(JavaNullability.Nullness.UNSPECIFIED, JavaNullability.returnNullness(method(Fixture.class, "plain")));
        assertEquals(JavaNullability.Nullness.NON_NULL, JavaNullability.returnNullness(method(Fixture.class, "jsr305")));
        assertEquals(JavaNullability.Nullness.UNSPECIFIED, JavaNullability.returnNullness(method(Fixture.class, "jsr305Maybe")));
        java.lang.reflect.Method take = method(Fixture.class, "take", String.class, String.class, String.class);
        assertEquals(JavaNullability.Nullness.NULLABLE, JavaNullability.parameterNullness(take, 0));
        assertEquals(JavaNullability.Nullness.NON_NULL, JavaNullability.parameterNullness(take, 1));
        assertEquals(JavaNullability.Nullness.UNSPECIFIED, JavaNullability.parameterNullness(take, 2));
    }

    @Test
    void nullMarkedScopes() throws Exception {
        assertTrue(JavaNullability.isNullMarked(method(Marked.class, "plain")));
        assertEquals(JavaNullability.Nullness.NON_NULL, JavaNullability.returnNullness(method(Marked.class, "plain")));
        assertFalse(JavaNullability.isNullMarked(method(Marked.class, "unmarked")));
        assertFalse(JavaNullability.isNullMarked(method(Fixture.class, "plain")));
    }

    @Test
    void javaBaseOverlay() throws Exception {
        assertEquals(JavaNullability.Nullness.NULLABLE, JavaNullability.returnNullness(method(Map.class, "get", Object.class)));
        assertEquals(JavaNullability.Nullness.PARAMETRIC, JavaNullability.returnNullness(method(java.util.List.class, "get", int.class)));
        assertEquals(JavaNullability.Nullness.NON_NULL, JavaNullability.returnNullness(method(String.class, "trim")));
        assertEquals(JavaNullability.Nullness.NON_NULL, JavaNullability.returnNullness(method(Object.class, "getClass")));
        assertEquals(JavaNullability.Nullness.NULLABLE,
                JavaNullability.parameterNullness(method(Object.class, "equals", Object.class), 0));
        assertEquals(JavaNullability.Nullness.NON_NULL,
                JavaNullability.parameterNullness(StringBuilder.class.getConstructor(String.class), 0));
        assertTrue(JavaNullability.isNullMarked(method(java.util.ArrayList.class, "size")));
        assertFalse(JavaNullability.isNullMarked(method(java.io.File.class, "getName")));
    }
}
