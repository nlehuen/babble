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

/**
 * Java bytecode nullability scanner ({@code BIP011} §6.3, {@code BIP019} §2.4–§2.5).
 *
 * <p>All built-in {@code java.base} nullability assumptions and third-party annotation meanings
 * live in {@code org.babblelang.axioms} ({@link BuiltinAxioms}); this class holds only the
 * lookup mechanism, plus bytecode return-nullness inference when enabled.
 */
final class JavaNullability {

    enum Nullness {
        /** Explicitly non-null (e.g. {@code @NonNull}, {@code @NullMarked}, or curated non-null). */
        NON_NULL,
        /** Explicitly nullable (e.g. {@code @Nullable}, or curated nullable like {@code Map.get}). */
        NULLABLE,
        /** Parametric nullness: preserves the substituted type argument's own nullability (e.g. {@code List.get -> E}). */
        PARAMETRIC,
        /** Unannotated legacy Java reference type: conservatively treated as nullable ({@code T?}). */
        UNSPECIFIED
    }

    private static final ThreadLocal<AxiomData.AxiomTable> LOCAL_AXIOMS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> BYTECODE_ANALYSIS = new ThreadLocal<>();

    private JavaNullability() {
    }

    static void runWithContext(AxiomData.AxiomTable table, boolean bytecodeAnalysis, Runnable action) {
        AxiomData.AxiomTable prevTable = LOCAL_AXIOMS.get();
        Boolean prevBytecode = BYTECODE_ANALYSIS.get();
        if (table == null || table.isEmpty()) {
            LOCAL_AXIOMS.remove();
        } else {
            LOCAL_AXIOMS.set(table);
        }
        BYTECODE_ANALYSIS.set(bytecodeAnalysis);
        try {
            action.run();
        } finally {
            if (prevTable == null) {
                LOCAL_AXIOMS.remove();
            } else {
                LOCAL_AXIOMS.set(prevTable);
            }
            if (prevBytecode == null) {
                BYTECODE_ANALYSIS.remove();
            } else {
                BYTECODE_ANALYSIS.set(prevBytecode);
            }
        }
    }

    private static boolean isBytecodeAnalysisEnabled() {
        Boolean flag = BYTECODE_ANALYSIS.get();
        return flag == null || flag;
    }

    /** Nullness of a Java method's return value (local/curated axioms, annotations, {@code @NullMarked}, bytecode inference). */
    static Nullness returnNullness(Method method) {
        if (method.getReturnType().isPrimitive()) {
            return Nullness.NON_NULL;
        }
        AxiomData.AxiomTable local = LOCAL_AXIOMS.get();
        if (local != null && !local.isEmpty()) {
            AxiomData.MemberAxiom ax = AxiomData.lookupMethod(method.getDeclaringClass(), method.getName(), method, local::directMembers);
            if (ax != null && ax.returnNullness() != null) {
                return ax.returnNullness();
            }
        }
        Nullness curated = BuiltinAxioms.current().returnNullness(method);
        if (curated != null) {
            return curated;
        }
        Nullness annotated = scanTypeAndDecl(method.getAnnotatedReturnType(), method);
        if (annotated != Nullness.UNSPECIFIED) {
            return annotated;
        }
        if (isNullMarked(method)) {
            return Nullness.NON_NULL;
        }
        if (isBytecodeAnalysisEnabled()) {
            BytecodeEffectAnalyzer.Result analyzed = BytecodeEffectAnalyzer.analyze(method, BuiltinAxioms.current());
            if (analyzed.returnNonNull()) {
                return Nullness.NON_NULL;
            }
        }
        return Nullness.UNSPECIFIED;
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

    /** Array-element nullness of a Java executable's array parameter from local or curated axioms, or {@code null}. */
    static Nullness parameterElementNullness(Executable exec, int index) {
        if (exec == null || index < 0 || index >= exec.getParameterCount()) {
            return null;
        }
        AxiomData.AxiomTable local = LOCAL_AXIOMS.get();
        if (local != null && !local.isEmpty()) {
            AxiomData.MemberAxiom ax = exec instanceof Method m
                    ? AxiomData.lookupMethod(m.getDeclaringClass(), m.getName(), m, local::directMembers)
                    : exec instanceof Constructor<?> c
                    ? AxiomData.lookupConstructor(c.getDeclaringClass(), c, local::directMembers)
                    : null;
            if (ax != null && ax.hasSignature() && index < ax.paramElementNullness().length) {
                return ax.paramElementNullness()[index];
            }
        }
        return BuiltinAxioms.current().parameterElementNullness(exec, index);
    }

    /** Nullness of a Java field's value (static-final constant inspection / annotations / {@code @NullMarked}). */
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

    /** Whether nested type arguments in this executable's signature default to non-null. */
    static boolean isNullMarked(Executable exec) {
        AxiomData.AxiomTable local = LOCAL_AXIOMS.get();
        if (local != null && !local.isEmpty()
                && AxiomData.lookupClassFlag(exec.getDeclaringClass(), local::directClassAxiom, AxiomData.ClassAxiom::nullMarked)) {
            return true;
        }
        if (BuiltinAxioms.current().nullMarked(exec)) {
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
        BuiltinAxioms axioms = BuiltinAxioms.current();
        for (Annotation ann : annotations) {
            String name = ann.annotationType().getName();
            Nullness meaning = axioms.annotationNullness(name);
            if (meaning == Nullness.NULLABLE) {
                return Nullness.NULLABLE;
            }
            if (meaning == Nullness.NON_NULL) {
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
        BuiltinAxioms axioms = BuiltinAxioms.current();
        for (Annotation ann : element.getAnnotations()) {
            String name = ann.annotationType().getName();
            Nullness scope = axioms.annotationNullMarkedScope(name);
            if (scope != Nullness.UNSPECIFIED) {
                return scope;
            }
        }
        return Nullness.UNSPECIFIED;
    }
}
