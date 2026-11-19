package org.babblelang.analysis;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.util.Map;
import java.util.Set;

/**
 * What Java says of the nullness of what a method returns or a field holds (BIP011-D6, D7) :
 * nullability annotations matched by name (JSpecify, JetBrains, Jakarta, JSR 305, Android,
 * Lombok... and {@code org.babblelang.effects.NotNull}, so no annotation jar is needed),
 * {@code @NullMarked} scopes, and a built-in overlay of {@code java.base} contracts, which
 * ships no annotations. What none of them settles is nullable.
 * <p>
 * A non-null result is soft : Java can still return null, so the backends check it where it
 * enters Babble ({@code JavaTypes.checkNotNull}).
 */
final class JavaNullness {
    private JavaNullness() {
    }

    enum Nullness {
        /** Never null : {@code String.trim()}, a primitive. */
        NON_NULL,
        /** Maybe null : {@code Map.get}, anything unannotated. */
        NULLABLE,
        /** As null as the type argument it returns : {@code List.get} gives {@code T}. */
        PARAMETRIC
    }

    private static final Set<String> NON_NULL_ANNOTATIONS = Set.of(
            "org.babblelang.effects.NotNull",
            "org.jspecify.annotations.NonNull",
            "org.jetbrains.annotations.NotNull",
            "jakarta.annotation.Nonnull",
            "javax.annotation.Nonnull",
            "lombok.NonNull",
            "android.annotation.NonNull",
            "androidx.annotation.NonNull",
            "edu.umd.cs.findbugs.annotations.NonNull",
            "io.micronaut.core.annotation.NonNull",
            "reactor.util.annotation.NonNull");

    private static final Set<String> NULLABLE_ANNOTATIONS = Set.of(
            "org.babblelang.effects.Nullable",
            "org.jspecify.annotations.Nullable",
            "org.jetbrains.annotations.Nullable",
            "jakarta.annotation.Nullable",
            "javax.annotation.Nullable",
            "javax.annotation.CheckForNull",
            "android.annotation.Nullable",
            "androidx.annotation.Nullable",
            "edu.umd.cs.findbugs.annotations.Nullable",
            "io.micronaut.core.annotation.Nullable",
            "reactor.util.annotation.Nullable");

    private static final String NULL_MARKED = "org.jspecify.annotations.NullMarked";
    private static final String NULL_UNMARKED = "org.jspecify.annotations.NullUnmarked";

    /** What a method returns. */
    static Nullness ofReturn(Method method) {
        if (method.getReturnType().isPrimitive()) {
            return Nullness.NON_NULL;
        }
        Nullness annotated = annotated(method.getAnnotatedReturnType(), method);
        if (annotated != null) {
            return annotated;
        }
        Nullness overlay = overlay(method);
        if (overlay != null) {
            return overlay;
        }
        if (nullMarked(method.getDeclaringClass(), method)) {
            return method.getGenericReturnType() instanceof TypeVariable<?> ? Nullness.PARAMETRIC : Nullness.NON_NULL;
        }
        return Nullness.NULLABLE;
    }

    /** What a field holds. A static final field whose value isn't null is never null. */
    static Nullness ofField(Field field) {
        if (field.getType().isPrimitive()) {
            return Nullness.NON_NULL;
        }
        Nullness annotated = annotated(field.getAnnotatedType(), field);
        if (annotated != null) {
            return annotated;
        }
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)) {
            try {
                return field.get(null) != null ? Nullness.NON_NULL : Nullness.NULLABLE;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return Nullness.NULLABLE;
            }
        }
        if (nullMarked(field.getDeclaringClass(), field)) {
            return field.getGenericType() instanceof TypeVariable<?> ? Nullness.PARAMETRIC : Nullness.NON_NULL;
        }
        return Nullness.NULLABLE;
    }

    /** A type-use or declaration nullability annotation, or null. */
    private static Nullness annotated(AnnotatedType type, AnnotatedElement declaration) {
        Nullness fromType = type != null ? annotated(type.getAnnotations()) : null;
        return fromType != null ? fromType : annotated(declaration.getAnnotations());
    }

    private static Nullness annotated(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String name = annotation.annotationType().getName();
            if (NULLABLE_ANNOTATIONS.contains(name)) {
                return Nullness.NULLABLE;
            }
            if (NON_NULL_ANNOTATIONS.contains(name) && !whenMaybe(annotation)) {
                return Nullness.NON_NULL;
            }
        }
        return null;
    }

    /** {@code javax.annotation.Nonnull(when = MAYBE)} and the like do not promise anything. */
    private static boolean whenMaybe(Annotation annotation) {
        try {
            Method when = annotation.annotationType().getMethod("when");
            Object value = when.invoke(annotation);
            return value != null && !"ALWAYS".equals(value.toString());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Whether a member is in a {@code @NullMarked} scope : itself, its classes, or its package. */
    private static boolean nullMarked(Class<?> owner, AnnotatedElement member) {
        Boolean marked = marking(member);
        for (Class<?> c = owner; marked == null && c != null; c = c.getEnclosingClass()) {
            marked = marking(c);
        }
        if (marked == null && owner.getPackage() != null) {
            marked = marking(owner.getPackage());
        }
        if (marked == null && owner.getModule() != null) {
            marked = marking(owner.getModule());
        }
        return Boolean.TRUE.equals(marked);
    }

    private static Boolean marking(AnnotatedElement element) {
        for (Annotation annotation : element.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (NULL_MARKED.equals(name)) {
                return true;
            }
            if (NULL_UNMARKED.equals(name)) {
                return false;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- java.base overlay (§6.4)

    /** Classes all of whose methods return non-null references, but for the exceptions listed. */
    private static final Map<String, Set<String>> NON_NULL_CLASSES = Map.ofEntries(
            Map.entry("java.lang.Object", Set.of()),
            Map.entry("java.lang.String", Set.of()),
            Map.entry("java.lang.CharSequence", Set.of()),
            Map.entry("java.lang.StringBuilder", Set.of()),
            Map.entry("java.lang.StringBuffer", Set.of()),
            Map.entry("java.lang.AbstractStringBuilder", Set.of()),
            Map.entry("java.lang.Math", Set.of()),
            Map.entry("java.lang.StrictMath", Set.of()),
            Map.entry("java.lang.Number", Set.of()),
            Map.entry("java.lang.Byte", Set.of()),
            Map.entry("java.lang.Short", Set.of()),
            Map.entry("java.lang.Integer", Set.of("getInteger")),
            Map.entry("java.lang.Long", Set.of("getLong")),
            Map.entry("java.lang.Float", Set.of()),
            Map.entry("java.lang.Double", Set.of()),
            Map.entry("java.lang.Boolean", Set.of()),
            Map.entry("java.lang.Character", Set.of("getName")),
            Map.entry("java.lang.Enum", Set.of()),
            Map.entry("java.lang.Runtime", Set.of()),
            Map.entry("java.lang.Thread", Set.of("getUncaughtExceptionHandler", "getContextClassLoader")),
            Map.entry("java.lang.System", Set.of("getenv", "getProperty", "console", "getSecurityManager", "clearProperty", "setProperty")),
            Map.entry("java.lang.Iterable", Set.of()),
            Map.entry("java.lang.StackTraceElement", Set.of("getFileName", "getModuleName", "getModuleVersion", "getClassLoaderName")),
            Map.entry("java.util.Objects", Set.of("toString", "requireNonNullElse", "requireNonNullElseGet")),
            Map.entry("java.util.Arrays", Set.of()),
            Map.entry("java.util.Collections", Set.of()),
            Map.entry("java.util.Random", Set.of()),
            Map.entry("java.util.Scanner", Set.of("findInLine", "findWithinHorizon")),
            Map.entry("java.util.Comparator", Set.of()),
            Map.entry("java.util.Optional", Set.of("orElse")),
            Map.entry("java.util.OptionalInt", Set.of()),
            Map.entry("java.util.OptionalLong", Set.of()),
            Map.entry("java.util.OptionalDouble", Set.of()),
            Map.entry("java.util.UUID", Set.of()),
            Map.entry("java.util.StringJoiner", Set.of()),
            Map.entry("java.util.regex.Pattern", Set.of()),
            Map.entry("java.util.regex.Matcher", Set.of("group")),
            Map.entry("java.math.BigInteger", Set.of()),
            Map.entry("java.math.BigDecimal", Set.of()),
            Map.entry("java.io.File", Set.of("getParent", "getParentFile", "list", "listFiles")),
            Map.entry("java.nio.file.Path", Set.of("getParent", "getFileName", "getRoot")),
            Map.entry("java.nio.file.Paths", Set.of()),
            Map.entry("java.nio.file.Files", Set.of("probeContentType")),
            Map.entry("java.io.BufferedReader", Set.of("readLine")),
            Map.entry("java.io.PrintStream", Set.of()),
            Map.entry("java.io.PrintWriter", Set.of()),
            Map.entry("java.io.Writer", Set.of()),
            Map.entry("java.io.StringWriter", Set.of()),
            Map.entry("java.nio.Buffer", Set.of()),
            Map.entry("java.nio.CharBuffer", Set.of()),
            Map.entry("java.nio.ByteBuffer", Set.of()),
            Map.entry("java.lang.Throwable", Set.of("getMessage", "getLocalizedMessage", "getCause")),
            Map.entry("java.lang.Class", Set.of("getSuperclass", "getEnclosingClass", "getDeclaringClass", "getComponentType",
                    "getPackage", "getCanonicalName", "getClassLoader", "getResource", "getResourceAsStream",
                    "getEnumConstants", "getNestHost", "getEnclosingMethod", "getEnclosingConstructor", "getSigners")));

    /** Packages all of whose classes' methods return non-null references. */
    private static final Set<String> NON_NULL_PACKAGES = Set.of(
            "java.util.stream", "java.util.function", "java.time", "java.util.concurrent.atomic");

    /** Methods of {@code java.util} collections that return one of their elements, as null as it is. */
    private static final Set<String> ELEMENT_METHODS = Set.of(
            "get", "getFirst", "getLast", "set", "remove", "removeFirst", "removeLast", "next", "previous",
            "first", "last", "firstKey", "lastKey", "getKey", "getValue", "getOrDefault", "element", "pop");

    /** Methods of {@code java.util} collections that return null for what is missing. */
    private static final Set<String> MISSING_METHODS = Set.of(
            "peek", "poll", "pollFirst", "pollLast", "peekFirst", "peekLast", "floor", "ceiling", "lower", "higher",
            "floorKey", "ceilingKey", "lowerKey", "higherKey", "floorEntry", "ceilingEntry", "lowerEntry", "higherEntry",
            "firstEntry", "lastEntry", "pollFirstEntry", "pollLastEntry", "comparator", "put", "putIfAbsent",
            "replace", "compute", "computeIfAbsent", "computeIfPresent", "merge", "setValue");

    /** The overlay's say on a method of {@code java.base}, or null. */
    static Nullness overlay(Method method) {
        Class<?> owner = method.getDeclaringClass();
        String name = method.getName();
        Set<String> exceptions = NON_NULL_CLASSES.get(owner.getName());
        if (exceptions != null) {
            return exceptions.contains(name) ? Nullness.NULLABLE : Nullness.NON_NULL;
        }
        if (owner.getPackage() != null && NON_NULL_PACKAGES.contains(owner.getPackageName())) {
            return Nullness.NON_NULL;
        }
        if (isCollection(owner)) {
            boolean map = java.util.Map.class.isAssignableFrom(owner);
            if (map && (name.equals("get") || name.equals("remove"))) {
                // Map.get(k) and Map.remove(k) are null for a missing key.
                return Nullness.NULLABLE;
            }
            if (MISSING_METHODS.contains(name)) {
                return Nullness.NULLABLE;
            }
            if (ELEMENT_METHODS.contains(name)) {
                return method.getGenericReturnType() instanceof TypeVariable<?> ? Nullness.PARAMETRIC : Nullness.NON_NULL;
            }
            return Nullness.NON_NULL;
        }
        return null;
    }

    /** A collection, map, iterator or entry of {@code java.util}. */
    private static boolean isCollection(Class<?> owner) {
        return owner.getName().startsWith("java.util.")
                && (java.util.Collection.class.isAssignableFrom(owner) || java.util.Map.class.isAssignableFrom(owner)
                || java.util.Iterator.class.isAssignableFrom(owner) || java.util.Map.Entry.class.isAssignableFrom(owner)
                || java.util.ListIterator.class.isAssignableFrom(owner));
    }
}
