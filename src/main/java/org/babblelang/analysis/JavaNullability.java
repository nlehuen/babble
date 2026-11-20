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


    private JavaNullability() {
    }

    /** Nullness of a Java method's return value (overlay first, then annotations, then {@code @NullMarked}). */
    static Nullness returnNullness(Method method) {
        if (method.getReturnType().isPrimitive()) {
            return Nullness.NON_NULL;
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
     * (either via {@code @NullMarked} or via the built-in {@code java.base} overlay).
     */
    static boolean isNullMarked(Executable exec) {
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
        // What each annotation means is an axiom (BIP019-D10).
        for (Annotation ann : annotations) {
            org.babblelang.axioms.AnnotationMeaning meaning = BuiltinAxioms.get().annotationMeaning(ann.annotationType().getName());
            if (meaning == null) {
                continue;
            }
            if (meaning.value() == org.babblelang.axioms.AnnotationMeaning.Meaning.NULLABLE) {
                return Nullness.NULLABLE;
            }
            if (meaning.value() == org.babblelang.axioms.AnnotationMeaning.Meaning.NON_NULL) {
                if (meaning.onlyWhenAlways() && !isWhenAlways(ann)) {
                    continue;
                }
                return Nullness.NON_NULL;
            }
        }
        return Nullness.UNSPECIFIED;
    }

    private static boolean isWhenAlways(Annotation ann) {
        try {
            Method when = ann.annotationType().getMethod("when");
            Object val = when.invoke(ann);
            return val == null || "ALWAYS".equals(val.toString());
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    private static boolean isNullMarkedClass(Class<?> cls) {
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
            org.babblelang.axioms.AnnotationMeaning meaning = BuiltinAxioms.get().annotationMeaning(ann.annotationType().getName());
            if (meaning != null && meaning.value() == org.babblelang.axioms.AnnotationMeaning.Meaning.NULL_UNMARKED) {
                return Nullness.NULLABLE;
            }
            if (meaning != null && meaning.value() == org.babblelang.axioms.AnnotationMeaning.Meaning.NULL_MARKED) {
                return Nullness.NON_NULL;
            }
        }
        return Nullness.UNSPECIFIED;
    }

    // ---- The java.base nullness overlay (BIP011 §6.4) : axioms of org.babblelang.axioms (BIP019-P3) ----

    private static boolean isOverlayNullMarked(Executable exec) {
        return BuiltinAxioms.get().nullMarked(exec.getDeclaringClass());
    }

    private static Nullness overlayReturnNullness(Method m) {
        return BuiltinAxioms.get().returnNullness(m);
    }

    private static Nullness overlayParameterNullness(Executable exec, int index) {
        return BuiltinAxioms.get().parameterNullness(exec, index);
    }
}
