package org.babblelang.analysis;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;

/**
 * Java bytecode nullability annotation scanner ({@code BIP011} §6.3) and built-in
 * {@code java.base} parametric-nullness overlay ({@code BIP011} §6.4).
 *
 * <p>All annotation classes are matched by binary name so Babble requires zero third-party
 * annotation jars on its classpath.
 */
final class JavaNullability {

    enum Nullness {
        /** Explicitly non-null (e.g. {@code @NonNull}, {@code @NullMarked}, or overlay non-null). */
        NON_NULL,
        /** Explicitly nullable (e.g. {@code @Nullable}, or overlay nullable like {@code Map.get}). */
        NULLABLE,
        /** Parametric nullness: preserves the substituted type argument's own nullability (e.g. {@code List.get -> E}). */
        PARAMETRIC,
        /** Unannotated legacy Java reference type: conservatively treated as nullable ({@code T?}). */
        UNSPECIFIED
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
            "reactor.util.annotation.NonNull"
    );

    private static final Set<String> NULLABLE_ANNOTATIONS = Set.of(
            "org.jspecify.annotations.Nullable",
            "org.jetbrains.annotations.Nullable",
            "jakarta.annotation.Nullable",
            "javax.annotation.Nullable",
            "javax.annotation.CheckForNull",
            "android.annotation.Nullable",
            "androidx.annotation.Nullable",
            "edu.umd.cs.findbugs.annotations.Nullable",
            "io.micronaut.core.annotation.Nullable",
            "reactor.util.annotation.Nullable"
    );

    private static final String JSPECIFY_NULL_MARKED = "org.jspecify.annotations.NullMarked";
    private static final String JSPECIFY_NULL_UNMARKED = "org.jspecify.annotations.NullUnmarked";

    private static final ThreadLocal<AxiomData.AxiomTable> LOCAL_AXIOMS = new ThreadLocal<>();

    private JavaNullability() {
    }

    static void runWithLocalAxioms(AxiomData.AxiomTable table, Runnable action) {
        if (table == null || table.isEmpty()) {
            action.run();
            return;
        }
        AxiomData.AxiomTable prev = LOCAL_AXIOMS.get();
        LOCAL_AXIOMS.set(table);
        try {
            action.run();
        } finally {
            if (prev == null) {
                LOCAL_AXIOMS.remove();
            } else {
                LOCAL_AXIOMS.set(prev);
            }
        }
    }

    /** Nullness of a Java method's return value (local/curated axioms first, then legacy overlay, annotations, {@code @NullMarked}). */
    static Nullness returnNullness(Method method) {
        if (method.getReturnType().isPrimitive()) {
            return Nullness.NON_NULL;
        }
        AxiomData.AxiomTable local = LOCAL_AXIOMS.get();
        if (local != null && !local.isEmpty()) {
            AxiomData.MemberAxiom ax = AxiomData.lookupMethod(method.getDeclaringClass(), method.getName(), method, local::directMembers);
            if (ax != null && ax.hasSignature() && ax.returnNullness() != null) {
                return ax.returnNullness();
            }
        }
        Nullness curated = BuiltinAxioms.current().returnNullness(method);
        if (curated != null) {
            return curated;
        }
        Nullness overlay = overlayReturnNullness(method);
        if (overlay != null) {
            return overlay;
        }
        Nullness annotated = scanTypeAndDecl(method.getAnnotatedReturnType(), method);
        if (annotated != Nullness.UNSPECIFIED) {
            return annotated;
        }
        return isNullMarked(method) ? Nullness.NON_NULL : Nullness.UNSPECIFIED;
    }

    /** Nullness of a Java executable's parameter at {@code index}. */
    static Nullness parameterNullness(Executable exec, int index) {
        Class<?>[] rawParams = exec.getParameterTypes();
        if (index < 0 || index >= rawParams.length) {
            return Nullness.UNSPECIFIED;
        }
        if (rawParams[index].isPrimitive()) {
            return Nullness.NON_NULL;
        }
        AxiomData.AxiomTable local = LOCAL_AXIOMS.get();
        if (local != null && !local.isEmpty()) {
            AxiomData.MemberAxiom ax = exec instanceof Method m
                    ? AxiomData.lookupMethod(m.getDeclaringClass(), m.getName(), m, local::directMembers)
                    : exec instanceof Constructor<?> c
                    ? AxiomData.lookupConstructor(c.getDeclaringClass(), c, local::directMembers)
                    : null;
            if (ax != null && ax.hasSignature() && index < ax.paramNullness().length) {
                return ax.paramNullness()[index];
            }
        }
        Nullness curated = BuiltinAxioms.current().parameterNullness(exec, index);
        if (curated != null) {
            return curated;
        }
        Nullness overlay = overlayParameterNullness(exec, index);
        if (overlay != null) {
            return overlay;
        }
        AnnotatedType[] annTypes = exec.getAnnotatedParameterTypes();
        Parameter[] params = exec.getParameters();
        AnnotatedType annType = index < annTypes.length ? annTypes[index] : null;
        Parameter param = index < params.length ? params[index] : null;
        Nullness annotated = scanTypeAndDecl(annType, param);
        if (annotated != Nullness.UNSPECIFIED) {
            return annotated;
        }
        return isNullMarked(exec) ? Nullness.NON_NULL : Nullness.UNSPECIFIED;
    }

    /** Nullness of a Java field's value (overlay / static-final constant inspection / annotations / {@code @NullMarked}). */
    static Nullness fieldNullness(Field field) {
        if (field.getType().isPrimitive()) {
            return Nullness.NON_NULL;
        }
        int mods = field.getModifiers();
        if (Modifier.isStatic(mods) && Modifier.isFinal(mods) && Modifier.isPublic(mods)) {
            try {
                if (field.get(null) != null) {
                    return Nullness.NON_NULL;
                }
            } catch (IllegalAccessException | LinkageError ignored) {
                // Fall through to annotation scan.
            }
        }
        Nullness annotated = scanTypeAndDecl(field.getAnnotatedType(), field);
        if (annotated != Nullness.UNSPECIFIED) {
            return annotated;
        }
        return isNullMarkedClass(field.getDeclaringClass()) ? Nullness.NON_NULL : Nullness.UNSPECIFIED;
    }

    /**
     * Whether nested type arguments in this executable's signature default to non-null
     * (via local/curated axioms, {@code @NullMarked}, or the legacy {@code java.base} overlay).
     */
    static boolean isNullMarked(Executable exec) {
        AxiomData.AxiomTable local = LOCAL_AXIOMS.get();
        if (local != null && !local.isEmpty()
                && AxiomData.lookupClassFlag(exec.getDeclaringClass(), local::directClassAxiom, AxiomData.ClassAxiom::nullMarked)) {
            return true;
        }
        if (BuiltinAxioms.current().nullMarked(exec)) {
            return true;
        }
        if (isOverlayNullMarked(exec)) {
            return true;
        }
        Nullness own = scanNullMarkedScope(exec);
        if (own != Nullness.UNSPECIFIED) {
            return own == Nullness.NON_NULL;
        }
        return isNullMarkedClass(exec.getDeclaringClass());
    }

    /** Direct annotation nullness on a {@code TYPE_USE} {@link AnnotatedType}. */
    static Nullness scanAnnotatedType(AnnotatedType type) {
        return type == null ? Nullness.UNSPECIFIED : scanAnnotations(type.getAnnotations());
    }

    private static Nullness scanTypeAndDecl(AnnotatedType type, AnnotatedElement decl) {
        Nullness fromType = scanAnnotatedType(type);
        if (fromType != Nullness.UNSPECIFIED) {
            return fromType;
        }
        return decl == null ? Nullness.UNSPECIFIED : scanAnnotations(decl.getAnnotations());
    }

    private static Nullness scanAnnotations(Annotation[] annotations) {
        for (Annotation ann : annotations) {
            String name = ann.annotationType().getName();
            if (NULLABLE_ANNOTATIONS.contains(name)) {
                return Nullness.NULLABLE;
            }
            if (NON_NULL_ANNOTATIONS.contains(name)) {
                if ("javax.annotation.Nonnull".equals(name) && !isJavaxNonnullAlways(ann)) {
                    continue;
                }
                return Nullness.NON_NULL;
            }
        }
        return Nullness.UNSPECIFIED;
    }

    private static boolean isJavaxNonnullAlways(Annotation ann) {
        try {
            Method when = ann.annotationType().getMethod("when");
            Object val = when.invoke(ann);
            return val == null || "ALWAYS".equals(val.toString());
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    static boolean isNullMarkedClass(Class<?> cls) {
        AxiomData.AxiomTable local = LOCAL_AXIOMS.get();
        if (local != null && !local.isEmpty()
                && AxiomData.lookupClassFlag(cls, local::directClassAxiom, AxiomData.ClassAxiom::nullMarked)) {
            return true;
        }
        if (BuiltinAxioms.current().nullMarked(cls)) {
            return true;
        }
        for (Class<?> c = cls; c != null; c = c.getEnclosingClass()) {
            Nullness scope = scanNullMarkedScope(c);
            if (scope != Nullness.UNSPECIFIED) {
                return scope == Nullness.NON_NULL;
            }
        }
        Package pkg = cls.getPackage();
        if (pkg != null) {
            Nullness scope = scanNullMarkedScope(pkg);
            if (scope != Nullness.UNSPECIFIED) {
                return scope == Nullness.NON_NULL;
            }
        }
        return false;
    }

    private static Nullness scanNullMarkedScope(AnnotatedElement element) {
        for (Annotation ann : element.getAnnotations()) {
            String name = ann.annotationType().getName();
            if (JSPECIFY_NULL_UNMARKED.equals(name)) {
                return Nullness.NULLABLE;
            }
            if (JSPECIFY_NULL_MARKED.equals(name)) {
                return Nullness.NON_NULL;
            }
        }
        return Nullness.UNSPECIFIED;
    }

    // ---- Built-in java.base parametric-nullness overlay (BIP011 §6.4) ----

    private static boolean isOverlayNullMarked(Executable exec) {
        Class<?> owner = exec.getDeclaringClass();
        return owner == String.class
                || owner == StringBuilder.class
                || owner == StringBuffer.class
                || owner == CharSequence.class
                || owner == Object.class
                || owner == Optional.class
                || Iterable.class.isAssignableFrom(owner)
                || Iterator.class.isAssignableFrom(owner)
                || Map.class.isAssignableFrom(owner)
                || Map.Entry.class.isAssignableFrom(owner)
                || java.util.Comparator.class.isAssignableFrom(owner)
                || java.util.stream.BaseStream.class.isAssignableFrom(owner)
                || owner == java.util.stream.StreamSupport.class
                || owner == java.util.regex.Pattern.class
                || owner == ProcessBuilder.class
                || "java.util.function".equals(owner.getPackageName())
                || isBoxedWrapper(owner);
    }

    private static boolean isBoxedWrapper(Class<?> c) {
        return c == Byte.class || c == Short.class || c == Integer.class || c == Long.class
                || c == Float.class || c == Double.class || c == Boolean.class || c == Character.class;
    }

    private static Nullness overlayReturnNullness(Method m) {
        Class<?> owner = m.getDeclaringClass();
        String name = m.getName();
        int arity = m.getParameterCount();

        // java.lang.Object essentials
        if ("toString".equals(name) && arity == 0) {
            return Nullness.NON_NULL;
        }
        if ("getClass".equals(name) && arity == 0) {
            return Nullness.NON_NULL;
        }

        // java.lang.String
        if (owner == String.class) {
            return Nullness.NON_NULL;
        }

        // java.lang.StringBuilder / StringBuffer
        if (owner == StringBuilder.class || owner == StringBuffer.class) {
            return Nullness.NON_NULL;
        }

        // java.lang.CharSequence
        if (owner == CharSequence.class) {
            if ("subSequence".equals(name) || "toString".equals(name)) {
                return Nullness.NON_NULL;
            }
        }

        // Boxed wrappers: valueOf/toString/decode are non-null; getInteger/getLong remain nullable
        if (isBoxedWrapper(owner)) {
            if ("valueOf".equals(name) || "toString".equals(name) || "decode".equals(name)
                    || "toHexString".equals(name) || "toBinaryString".equals(name) || "toOctalString".equals(name)
                    || "toUnsignedString".equals(name)) {
                return Nullness.NON_NULL;
            }
            if ("getInteger".equals(name) || "getLong".equals(name)) {
                return arity == 2 ? Nullness.NON_NULL : Nullness.NULLABLE;
            }
        }

        // java.lang.System
        if (owner == System.class) {
            if ("lineSeparator".equals(name)) {
                return Nullness.NON_NULL;
            }
            if ("getenv".equals(name)) {
                return arity == 0 ? Nullness.NON_NULL : Nullness.NULLABLE;
            }
            if ("getProperty".equals(name)) {
                return arity == 2 ? Nullness.NON_NULL : Nullness.NULLABLE;
            }
        }

        // java.util.Optional
        if (owner == Optional.class) {
            return switch (name) {
                case "of", "ofNullable", "empty", "filter", "map", "flatMap", "or", "stream" -> Nullness.NON_NULL;
                case "get", "orElseThrow" -> Nullness.PARAMETRIC;
                case "orElse" -> Nullness.PARAMETRIC;
                default -> null;
            };
        }

        // java.util.Map.Entry
        if (Map.Entry.class.isAssignableFrom(owner)) {
            if ("getKey".equals(name) || "getValue".equals(name) || "setValue".equals(name)) {
                return Nullness.PARAMETRIC;
            }
        }

        // java.util.Map / SortedMap / NavigableMap
        if (Map.class.isAssignableFrom(owner)) {
            if (Modifier.isStatic(m.getModifiers())) {
                if ("of".equals(name) || "ofEntries".equals(name) || "copyOf".equals(name) || "entry".equals(name)) {
                    return Nullness.NON_NULL;
                }
            }
            switch (name) {
                case "get", "put", "putIfAbsent", "remove", "replace",
                     "computeIfAbsent", "computeIfPresent", "compute", "merge" -> {
                    return Nullness.NULLABLE;
                }
                case "getOrDefault" -> {
                    return Nullness.PARAMETRIC;
                }
                case "keySet", "values", "entrySet", "headMap", "tailMap", "subMap", "reversed",
                     "descendingMap", "navigableKeySet", "descendingKeySet" -> {
                    return Nullness.NON_NULL;
                }
                case "firstKey", "lastKey" -> {
                    if (SortedMap.class.isAssignableFrom(owner)) {
                        return Nullness.PARAMETRIC;
                    }
                }
                case "lowerKey", "floorKey", "ceilingKey", "higherKey",
                     "lowerEntry", "floorEntry", "ceilingEntry", "higherEntry",
                     "firstEntry", "lastEntry", "pollFirstEntry", "pollLastEntry" -> {
                    if (NavigableMap.class.isAssignableFrom(owner)) {
                        return Nullness.NULLABLE;
                    }
                }
                case "comparator" -> {
                    return Nullness.NULLABLE;
                }
                default -> {
                }
            }
        }

        // java.util.Iterator
        if (Iterator.class.isAssignableFrom(owner)) {
            if ("next".equals(name) && arity == 0) {
                return Nullness.PARAMETRIC;
            }
        }

        // java.lang.Iterable / Collection / List / Set / SortedSet / NavigableSet
        if (Iterable.class.isAssignableFrom(owner)) {
            if (Modifier.isStatic(m.getModifiers())) {
                if ("of".equals(name) || "copyOf".equals(name)) {
                    return Nullness.NON_NULL;
                }
            }
            switch (name) {
                case "iterator", "spliterator", "stream", "parallelStream", "toArray",
                     "subList", "reversed", "listIterator",
                     "headSet", "tailSet", "subSet", "descendingSet", "descendingIterator" -> {
                    return Nullness.NON_NULL;
                }
                case "get", "getFirst", "getLast", "set", "removeFirst", "removeLast" -> {
                    if (List.class.isAssignableFrom(owner)) {
                        return Nullness.PARAMETRIC;
                    }
                }
                case "remove" -> {
                    if (List.class.isAssignableFrom(owner) && arity == 1 && m.getParameterTypes()[0] == int.class) {
                        return Nullness.PARAMETRIC;
                    }
                }
                case "first", "last" -> {
                    if (SortedSet.class.isAssignableFrom(owner)) {
                        return Nullness.PARAMETRIC;
                    }
                }
                case "lower", "floor", "ceiling", "higher", "pollFirst", "pollLast" -> {
                    if (NavigableSet.class.isAssignableFrom(owner)) {
                        return Nullness.NULLABLE;
                    }
                }
                case "comparator" -> {
                    return Nullness.NULLABLE;
                }
                default -> {
                }
            }
        }

        // Common java.util helpers (Arrays, Collections, Objects, Pattern, Stream)
        if (owner == java.util.Arrays.class) {
            if ("asList".equals(name) || "copyOf".equals(name) || "copyOfRange".equals(name)
                    || "toString".equals(name) || "stream".equals(name)) {
                return Nullness.NON_NULL;
            }
        }
        if (owner == java.util.Collections.class) {
            if (name.startsWith("empty") || name.startsWith("singleton") || name.startsWith("unmodifiable")
                    || name.startsWith("synchronized") || "nCopies".equals(name) || "reverseOrder".equals(name)) {
                return Nullness.NON_NULL;
            }
        }
        if (owner == java.util.regex.Pattern.class) {
            if ("compile".equals(name) || "matcher".equals(name) || "split".equals(name) || "pattern".equals(name)) {
                return Nullness.NON_NULL;
            }
        }
        if (owner == java.util.stream.Stream.class || java.util.stream.BaseStream.class.isAssignableFrom(owner)) {
            if ("of".equals(name) || "empty".equals(name) || "range".equals(name) || "rangeClosed".equals(name)
                    || "generate".equals(name) || "iterate".equals(name) || "concat".equals(name)
                    || "builder".equals(name) || "map".equals(name) || "filter".equals(name)
                    || "flatMap".equals(name) || "sorted".equals(name) || "distinct".equals(name)
                    || "limit".equals(name) || "skip".equals(name) || "peek".equals(name)
                    || "takeWhile".equals(name) || "dropWhile".equals(name)
                    || "sequential".equals(name) || "parallel".equals(name) || "unordered".equals(name) || "onClose".equals(name)
                    || "toList".equals(name) || "toArray".equals(name)
                    || "findFirst".equals(name) || "findAny".equals(name) || "collect".equals(name)
                    || "mapToInt".equals(name) || "mapToLong".equals(name) || "mapToDouble".equals(name)
                    || "mapToObj".equals(name) || "asLongStream".equals(name) || "asDoubleStream".equals(name)
                    || "summaryStatistics".equals(name) || "average".equals(name) || "boxed".equals(name)
                    || (("min".equals(name) || "max".equals(name) || "reduce".equals(name)) && m.getReturnType() != Object.class)) {
                return Nullness.NON_NULL;
            }
        }
        if (owner == java.util.stream.StreamSupport.class
                && ("stream".equals(name) || "intStream".equals(name) || "longStream".equals(name) || "doubleStream".equals(name))) {
            return Nullness.NON_NULL;
        }
        if (java.util.Comparator.class.isAssignableFrom(owner)) {
            if ("reversed".equals(name) || name.startsWith("thenComparing") || name.startsWith("comparing")
                    || "naturalOrder".equals(name) || "reverseOrder".equals(name)
                    || "nullsFirst".equals(name) || "nullsLast".equals(name)) {
                return Nullness.NON_NULL;
            }
        }
        if ("java.util.function".equals(owner.getPackageName())
                || java.util.function.Predicate.class.isAssignableFrom(owner)
                || java.util.function.Function.class.isAssignableFrom(owner)
                || java.util.function.IntPredicate.class.isAssignableFrom(owner)
                || java.util.function.LongPredicate.class.isAssignableFrom(owner)
                || java.util.function.DoublePredicate.class.isAssignableFrom(owner)
                || java.util.function.BiPredicate.class.isAssignableFrom(owner)
                || java.util.function.BiFunction.class.isAssignableFrom(owner)
                || java.util.function.IntUnaryOperator.class.isAssignableFrom(owner)
                || java.util.function.LongUnaryOperator.class.isAssignableFrom(owner)
                || java.util.function.DoubleUnaryOperator.class.isAssignableFrom(owner)
                || java.util.function.Consumer.class.isAssignableFrom(owner)
                || java.util.function.BiConsumer.class.isAssignableFrom(owner)) {
            if ("negate".equals(name) || "and".equals(name) || "or".equals(name) || "not".equals(name)
                    || "andThen".equals(name) || "compose".equals(name) || "identity".equals(name)
                    || "isEqual".equals(name) || "minBy".equals(name) || "maxBy".equals(name)) {
                return Nullness.NON_NULL;
            }
        }
        if (owner == Runtime.class && "getRuntime".equals(name)) {
            return Nullness.NON_NULL;
        }
        if (owner == ProcessBuilder.class && "start".equals(name)) {
            return Nullness.NON_NULL;
        }
        if (owner == java.io.StringWriter.class && "getBuffer".equals(name)) {
            return Nullness.NON_NULL;
        }
        if (owner == java.math.BigInteger.class) {
            if ("valueOf".equals(name) || "add".equals(name) || "subtract".equals(name) || "multiply".equals(name)
                    || "divide".equals(name) || "remainder".equals(name) || "mod".equals(name) || "pow".equals(name)
                    || "abs".equals(name) || "negate".equals(name) || "min".equals(name) || "max".equals(name)
                    || "gcd".equals(name) || "and".equals(name) || "or".equals(name) || "xor".equals(name)
                    || "not".equals(name) || "shiftLeft".equals(name) || "shiftRight".equals(name) || "toString".equals(name)) {
                return Nullness.NON_NULL;
            }
        }
        if (owner == java.util.StringTokenizer.class && "nextToken".equals(name)) {
            return Nullness.NON_NULL;
        }
        if (owner == java.lang.reflect.Proxy.class
                && ("getInvocationHandler".equals(name) || "newProxyInstance".equals(name) || "getProxyClass".equals(name))) {
            return Nullness.NON_NULL;
        }
        if (owner == java.time.LocalDate.class
                && ("of".equals(name) || "now".equals(name) || "parse".equals(name)
                || name.startsWith("plus") || name.startsWith("minus") || name.startsWith("with"))) {
            return Nullness.NON_NULL;
        }
        if (owner == Thread.class && ("currentThread".equals(name) || "getStackTrace".equals(name) || "getName".equals(name))) {
            return Nullness.NON_NULL;
        }
        if (owner == Class.class && ("forName".equals(name) || "getName".equals(name) || "getSimpleName".equals(name) || "getCanonicalName".equals(name))) {
            return Nullness.NON_NULL;
        }
        if (owner == java.nio.CharBuffer.class && "wrap".equals(name)) {
            return Nullness.NON_NULL;
        }

        return null;
    }

    private static Nullness overlayParameterNullness(Executable exec, int index) {
        Class<?> owner = exec.getDeclaringClass();
        if (exec instanceof Constructor<?>) {
            if ((owner == StringBuilder.class || owner == StringBuffer.class) && exec.getParameterTypes()[index] == String.class) {
                return Nullness.NON_NULL;
            }
            return null;
        }
        Method m = (Method) exec;
        String name = m.getName();
        int arity = m.getParameterCount();

        if ("equals".equals(name) && arity == 1) {
            return Nullness.NULLABLE;
        }

        if (owner == String.class) {
            if ("equalsIgnoreCase".equals(name) || "valueOf".equals(name) || "formatted".equals(name) || "format".equals(name)) {
                return Nullness.NULLABLE;
            }
            if ("startsWith".equals(name) || "endsWith".equals(name) || "contains".equals(name)
                    || "matches".equals(name) || "concat".equals(name) || "replace".equals(name)
                    || "replaceAll".equals(name) || "replaceFirst".equals(name)
                    || "compareTo".equals(name) || "compareToIgnoreCase".equals(name)
                    || "indexOf".equals(name) || "lastIndexOf".equals(name) || "split".equals(name)) {
                return Nullness.NON_NULL;
            }
        }

        if (owner == Optional.class) {
            if ("of".equals(name)) {
                return Nullness.NON_NULL;
            }
            if ("ofNullable".equals(name)) {
                return Nullness.NULLABLE;
            }
            if ("orElse".equals(name)) {
                return Nullness.PARAMETRIC;
            }
        }

        if (owner == System.class) {
            if (("getenv".equals(name) || "getProperty".equals(name)) && index == 0) {
                return Nullness.NON_NULL;
            }
            if ("getProperty".equals(name) && arity == 2 && index == 1) {
                return Nullness.NON_NULL;
            }
        }

        // Collection / List / Set / SortedSet
        if (Collection.class.isAssignableFrom(owner)) {
            if (Modifier.isStatic(m.getModifiers()) && ("of".equals(name) || "copyOf".equals(name))) {
                return Nullness.NON_NULL;
            }
            if ("contains".equals(name) || "remove".equals(name) && m.getParameterTypes()[0] == Object.class
                    || "indexOf".equals(name) || "lastIndexOf".equals(name)) {
                return Nullness.NULLABLE;
            }
            if ("add".equals(name) || "addFirst".equals(name) || "addLast".equals(name)
                    || ("set".equals(name) && index == 1)
                    || "headSet".equals(name) || "tailSet".equals(name) || "subSet".equals(name)) {
                return Nullness.PARAMETRIC;
            }
            if ("addAll".equals(name) || "removeAll".equals(name) || "retainAll".equals(name)) {
                return Nullness.NON_NULL;
            }
        }

        // Map / SortedMap
        if (Map.class.isAssignableFrom(owner)) {
            if (Modifier.isStatic(m.getModifiers()) && ("of".equals(name) || "ofEntries".equals(name) || "copyOf".equals(name) || "entry".equals(name))) {
                return Nullness.NON_NULL;
            }
            if ("containsKey".equals(name) || "containsValue".equals(name) || "get".equals(name) || "remove".equals(name)) {
                return Nullness.NULLABLE;
            }
            if ("getOrDefault".equals(name)) {
                return index == 0 ? Nullness.NULLABLE : Nullness.PARAMETRIC;
            }
            if ("put".equals(name) || "putIfAbsent".equals(name) || "replace".equals(name)
                    || "headMap".equals(name) || "tailMap".equals(name) || "subMap".equals(name)) {
                return Nullness.PARAMETRIC;
            }
            if ("putAll".equals(name)) {
                return Nullness.NON_NULL;
            }
        }

        return null;
    }
}
