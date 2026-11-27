package org.babblelang.analysis;

import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.babblelang.runtime.JavaTypes;

/**
 * Reflection and JLS type-bridge helpers ({@code docs/design/bip010_type-checker-simplification.md}
 * §4.6): {@code java.lang.reflect} translation (with F-bounded wildcard cycle guards), generic
 * supertype environments, wildcard bound checks, SAM extraction, JPMS/bytecode accessibility, and
 * single-walk boxed/widening overload applicability and {@code mostSpecific} selection.
 */
final class JavaTypeBridge {
    private JavaTypeBridge() {}

    /**
     * The Babble type of a Java class: Babble's 9 core scalars are Java's
     * primitives/wrappers/String.
     */
    static Type fromJava(Class<?> java) {
        if (java == byte.class || java == Byte.class) {
            return Type.BYTE;
        }
        if (java == short.class || java == Short.class) {
            return Type.SHORT;
        }
        if (java == int.class || java == Integer.class) {
            return Type.INT;
        }
        if (java == long.class || java == Long.class) {
            return Type.LONG;
        }
        if (java == float.class || java == Float.class) {
            return Type.FLOAT;
        }
        if (java == double.class || java == Double.class) {
            return Type.DOUBLE;
        }
        if (java == boolean.class || java == Boolean.class) {
            return Type.BOOL;
        }
        if (java == char.class || java == Character.class) {
            return Type.CHAR;
        }
        if (java == String.class) {
            return Type.STRING;
        }
        if (java == void.class) {
            return Type.NULL;
        }
        if (java.isArray()) {
            return new Type.Array(fromJavaArrayComponent(java.getComponentType()));
        }
        if (java == Object.class) {
            return Type.ANY;
        }
        if (java.isPrimitive()) {
            return new Type.JavaInstance(JavaTypes.box(java));
        }
        if (java.getTypeParameters().length > 0) {
            return new Type.JavaInstance(
                    java, Collections.nCopies(java.getTypeParameters().length, Type.NULLABLE_ANY));
        }
        return new Type.JavaInstance(java);
    }

    private static boolean isBoxedPrimitive(Class<?> java) {
        return java == Byte.class
                || java == Short.class
                || java == Integer.class
                || java == Long.class
                || java == Float.class
                || java == Double.class
                || java == Boolean.class
                || java == Character.class;
    }

    static Type fromJavaArrayComponent(Class<?> comp) {
        if (comp.isPrimitive()) {
            return fromJava(comp);
        }
        if (isBoxedPrimitive(comp)) {
            return Type.nullable(fromJava(comp));
        }
        if (comp.isArray()) {
            return new Type.Array(fromJavaArrayComponent(comp.getComponentType()));
        }
        return fromJava(comp);
    }

    /**
     * Translates a reflected Java class according to its nullability (unannotated references become
     * {@code T?}).
     */
    static Type fromJavaReflected(Class<?> java, JavaNullability.Nullness nullness) {
        if (java == void.class) {
            return Type.NULL;
        }
        if (java.isArray()) {
            Class<?> compCls = java.getComponentType();
            Type comp;
            if (compCls.isPrimitive()) {
                comp = fromJava(compCls);
            } else if (isBoxedPrimitive(compCls)) {
                comp = Type.nullable(fromJava(compCls));
            } else {
                comp = fromJavaReflected(compCls, JavaNullability.Nullness.UNSPECIFIED);
            }
            Type arr = new Type.Array(comp);
            return nullness == JavaNullability.Nullness.NON_NULL ? arr : Type.nullable(arr);
        }
        Type base = fromJava(java);
        if (java.isPrimitive() || nullness == JavaNullability.Nullness.NON_NULL) {
            return base;
        }
        return Type.nullable(base);
    }

    static Type rawJavaInstance(Class<?> java) {
        TypeVariable<?>[] params = java.getTypeParameters();
        if (params.length == 0) {
            return fromJava(java);
        }
        return new Type.JavaInstance(java, Collections.nCopies(params.length, Type.NULLABLE_ANY));
    }

    /**
     * The only public method of {@code owner} named {@code name} with {@code arity} parameters, or
     * null.
     */
    static Method uniqueArityMethod(Class<?> owner, String name, int arity) {
        Method found = null;
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity) {
                if (found != null) {
                    return null;
                }
                found = m;
            }
        }
        return found;
    }

    /**
     * Resolves a unique public overload of {@code owner.name} matching {@code targetParams} (using
     * Java overload specificity when multiple overloads share the arity, e.g. {@code
     * String.valueOf} assigned to {@code fn (n: int): string}).
     */
    static Method resolveMethodForTarget(
            Class<?> owner,
            String name,
            List<Type.Parameter> targetParams,
            java.util.function.BiPredicate<Type, Type> assignable) {
        int arity = targetParams.size();
        Method byArity = uniqueArityMethod(owner, name, arity);
        if (byArity != null) {
            return byArity;
        }
        List<Method> applicable = new ArrayList<>();
        for (Method m : owner.getMethods()) {
            if (!m.getName().equals(name)
                    || m.getParameterCount() != arity
                    || m.isBridge()
                    || m.isSynthetic()) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < arity; i++) {
                Type argType = targetParams.get(i).type();
                if (argType == null || argType == Type.ERROR) {
                    ok = false;
                    break;
                }
                Type paramType =
                        pts[i].isPrimitive() ? fromJava(pts[i]) : Type.nullable(fromJava(pts[i]));
                if (!assignable.test(argType, paramType)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                applicable.add(m);
            }
        }
        if (applicable.size() == 1) {
            return applicable.get(0);
        }
        return applicable.isEmpty() ? null : mostSpecific(applicable);
    }

    static int checkJavaTypeArguments(Class<?> java, List<Type> args, TypeAlgebra algebra) {
        TypeVariable<?>[] params = java.getTypeParameters();
        Map<TypeVariable<?>, Type> env = javaTypeEnv(java, args);
        for (int i = 0; i < params.length; i++) {
            if (!satisfiesJavaBounds(args.get(i), params[i], env, algebra)) {
                return i;
            }
        }
        return -1;
    }

    static Map<TypeVariable<?>, Type> javaTypeEnv(Class<?> cls, List<Type> typeArgs) {
        Map<TypeVariable<?>, Type> env = new HashMap<>();
        if (cls == null) {
            return env;
        }
        TypeVariable<?>[] ownParams = cls.getTypeParameters();
        for (int i = 0; i < Math.min(ownParams.length, typeArgs.size()); i++) {
            env.put(ownParams[i], typeArgs.get(i));
        }
        populateSuperJavaEnv(cls, env);
        return env;
    }

    private static void populateSuperJavaEnv(Class<?> cls, Map<TypeVariable<?>, Type> env) {
        ArrayDeque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> visited = new HashSet<>();
        Set<WildcardType> activeWildcards = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<TypeVariable<?>> activeTypeVars = new HashSet<>();
        queue.add(cls);
        visited.add(cls);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            List<java.lang.reflect.Type> supers = new ArrayList<>();
            if (current.getGenericSuperclass() != null) {
                supers.add(current.getGenericSuperclass());
            }
            Collections.addAll(supers, current.getGenericInterfaces());
            for (java.lang.reflect.Type sup : supers) {
                if (sup instanceof ParameterizedType pt
                        && pt.getRawType() instanceof Class<?> raw) {
                    TypeVariable<?>[] rawParams = raw.getTypeParameters();
                    java.lang.reflect.Type[] rawArgs = pt.getActualTypeArguments();
                    for (int i = 0; i < Math.min(rawParams.length, rawArgs.length); i++) {
                        env.putIfAbsent(
                                rawParams[i],
                                fromJavaType(
                                        rawArgs[i],
                                        null,
                                        env,
                                        false,
                                        false,
                                        JavaNullability.Nullness.UNSPECIFIED,
                                        true,
                                        activeWildcards,
                                        activeTypeVars));
                    }
                    if (visited.add(raw)) {
                        queue.add(raw);
                    }
                } else if (sup instanceof Class<?> raw && visited.add(raw)) {
                    queue.add(raw);
                }
            }
        }
    }

    static Type fromJavaType(
            java.lang.reflect.Type type,
            Map<TypeVariable<?>, Type> env,
            boolean widenTopTypeVar,
            boolean forParam) {
        return fromJavaType(
                type,
                null,
                env,
                widenTopTypeVar,
                forParam,
                JavaNullability.Nullness.UNSPECIFIED,
                false,
                Collections.newSetFromMap(new IdentityHashMap<>()),
                new HashSet<>());
    }

    static Type fromJavaType(
            java.lang.reflect.Type type,
            AnnotatedType annType,
            Map<TypeVariable<?>, Type> env,
            boolean widenTopTypeVar,
            boolean forParam,
            JavaNullability.Nullness topNullness,
            boolean nullMarked) {
        return fromJavaType(
                type,
                annType,
                env,
                widenTopTypeVar,
                forParam,
                topNullness,
                nullMarked,
                Collections.newSetFromMap(new IdentityHashMap<>()),
                new HashSet<>());
    }

    private static JavaNullability.Nullness effectiveNullness(
            JavaNullability.Nullness explicit, AnnotatedType annType, boolean nullMarked) {
        if (explicit != null && explicit != JavaNullability.Nullness.UNSPECIFIED) {
            return explicit;
        }
        JavaNullability.Nullness scanned = JavaNullability.scanAnnotatedType(annType);
        if (scanned != JavaNullability.Nullness.UNSPECIFIED) {
            return scanned;
        }
        return nullMarked
                ? JavaNullability.Nullness.NON_NULL
                : JavaNullability.Nullness.UNSPECIFIED;
    }

    static Type translateTypeVarBounds(TypeVariable<?> tv, Map<TypeVariable<?>, Type> env) {
        return translateTypeVarBounds(
                tv, env, true, Collections.newSetFromMap(new IdentityHashMap<>()), new HashSet<>());
    }

    private static Type translateTypeVarBounds(
            TypeVariable<?> tv,
            Map<TypeVariable<?>, Type> env,
            boolean nullMarked,
            Set<WildcardType> activeWildcards,
            Set<TypeVariable<?>> activeTypeVars) {
        if (!activeTypeVars.add(tv)) {
            return Type.NULLABLE_ANY;
        }
        try {
            java.lang.reflect.Type[] bounds = tv.getBounds();
            List<Type> translated = new ArrayList<>(bounds.length);
            for (java.lang.reflect.Type bound : bounds) {
                if (bound == Object.class) {
                    continue;
                }
                Type bt =
                        fromJavaType(
                                bound,
                                null,
                                env,
                                false,
                                false,
                                JavaNullability.Nullness.NON_NULL,
                                nullMarked,
                                activeWildcards,
                                activeTypeVars);
                Type base = Type.withoutNull(bt);
                if (base == Type.ANY) {
                    continue;
                }
                if (!isInterfaceIntersectable(base)) {
                    if (translated.isEmpty()) {
                        return bt;
                    }
                    continue;
                }
                translated.add(base);
            }
            if (translated.isEmpty()) {
                return Type.NULLABLE_ANY;
            }
            return translated.size() == 1 ? translated.get(0) : Type.intersection(translated);
        } finally {
            activeTypeVars.remove(tv);
        }
    }

    private static boolean isInterfaceIntersectable(Type base) {
        return (base instanceof Type.JavaInstance ji && ji.type().isInterface())
                || base instanceof Type.Intersection
                || (base instanceof Type.Instance inst && inst.node().isInterface());
    }

    private static Type fromJavaType(
            java.lang.reflect.Type type,
            AnnotatedType annType,
            Map<TypeVariable<?>, Type> env,
            boolean widenTopTypeVar,
            boolean forParam,
            JavaNullability.Nullness topNullness,
            boolean nullMarked,
            Set<WildcardType> activeWildcards,
            Set<TypeVariable<?>> activeTypeVars) {
        JavaNullability.Nullness eff = effectiveNullness(topNullness, annType, nullMarked);
        if (type instanceof TypeVariable<?> tv) {
            Type resolved = env.get(tv);
            if (resolved == null) {
                resolved =
                        translateTypeVarBounds(
                                tv, env, nullMarked, activeWildcards, activeTypeVars);
            }
            if (topNullness == JavaNullability.Nullness.PARAMETRIC) {
                return resolved;
            }
            if (topNullness == JavaNullability.Nullness.NON_NULL) {
                return Type.withoutNull(resolved);
            }
            if (topNullness == JavaNullability.Nullness.NULLABLE) {
                return Type.nullable(resolved);
            }
            JavaNullability.Nullness scanned = JavaNullability.scanAnnotatedType(annType);
            if (scanned == JavaNullability.Nullness.NON_NULL) {
                return Type.withoutNull(resolved);
            }
            if (scanned == JavaNullability.Nullness.NULLABLE) {
                return Type.nullable(resolved);
            }
            if (nullMarked || !widenTopTypeVar) {
                return resolved;
            }
            return Type.nullable(resolved);
        }
        if (type instanceof WildcardType wt) {
            if (!activeWildcards.add(wt)) {
                return Type.NULLABLE_ANY;
            }
            try {
                Type resolved;
                if (forParam && wt.getLowerBounds().length > 0) {
                    resolved =
                            fromJavaType(
                                    wt.getLowerBounds()[0],
                                    null,
                                    env,
                                    false,
                                    false,
                                    JavaNullability.Nullness.UNSPECIFIED,
                                    nullMarked,
                                    activeWildcards,
                                    activeTypeVars);
                } else if (wt.getUpperBounds().length > 0
                        && wt.getUpperBounds()[0] != Object.class) {
                    resolved =
                            fromJavaType(
                                    wt.getUpperBounds()[0],
                                    null,
                                    env,
                                    false,
                                    false,
                                    JavaNullability.Nullness.UNSPECIFIED,
                                    nullMarked,
                                    activeWildcards,
                                    activeTypeVars);
                } else {
                    resolved = Type.NULLABLE_ANY;
                }
                if (eff == JavaNullability.Nullness.NON_NULL) {
                    return resolved;
                }
                if (eff == JavaNullability.Nullness.NULLABLE || widenTopTypeVar) {
                    return Type.nullable(resolved);
                }
                return resolved;
            } finally {
                activeWildcards.remove(wt);
            }
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            java.lang.reflect.Type[] rawArgs = pt.getActualTypeArguments();
            AnnotatedType[] annArgs =
                    annType instanceof AnnotatedParameterizedType apt
                            ? apt.getAnnotatedActualTypeArguments()
                            : null;
            List<Type> args = new ArrayList<>(rawArgs.length);
            for (int i = 0; i < rawArgs.length; i++) {
                AnnotatedType argAnn = annArgs != null && i < annArgs.length ? annArgs[i] : null;
                args.add(
                        fromJavaType(
                                rawArgs[i],
                                argAnn,
                                env,
                                false,
                                forParam,
                                JavaNullability.Nullness.UNSPECIFIED,
                                nullMarked,
                                activeWildcards,
                                activeTypeVars));
            }
            Type base =
                    raw.getTypeParameters().length == args.size()
                            ? new Type.JavaInstance(raw, args)
                            : fromJava(raw);
            return eff == JavaNullability.Nullness.NON_NULL ? base : Type.nullable(base);
        }
        if (type instanceof GenericArrayType gat) {
            AnnotatedType compAnn =
                    annType instanceof AnnotatedArrayType aat
                            ? aat.getAnnotatedGenericComponentType()
                            : null;
            Type component =
                    fromJavaType(
                            gat.getGenericComponentType(),
                            compAnn,
                            env,
                            false,
                            false,
                            JavaNullability.Nullness.UNSPECIFIED,
                            nullMarked,
                            activeWildcards,
                            activeTypeVars);
            Type arr = new Type.Array(component);
            return eff == JavaNullability.Nullness.NON_NULL ? arr : Type.nullable(arr);
        }
        if (type instanceof Class<?> cls) {
            if (cls.isArray()) {
                AnnotatedType compAnn =
                        annType instanceof AnnotatedArrayType aat
                                ? aat.getAnnotatedGenericComponentType()
                                : null;
                Class<?> compCls = cls.getComponentType();
                Type comp;
                if (compCls.isPrimitive()) {
                    comp = fromJava(compCls);
                } else if (isBoxedPrimitive(compCls)) {
                    comp = Type.nullable(fromJava(compCls));
                } else {
                    comp =
                            fromJavaType(
                                    compCls,
                                    compAnn,
                                    env,
                                    false,
                                    false,
                                    JavaNullability.Nullness.UNSPECIFIED,
                                    nullMarked,
                                    activeWildcards,
                                    activeTypeVars);
                }
                Type arr = new Type.Array(comp);
                return eff == JavaNullability.Nullness.NON_NULL ? arr : Type.nullable(arr);
            }
            return fromJavaReflected(cls, eff);
        }
        return Type.NULLABLE_ANY;
    }

    static Class<?> erasedClass(java.lang.reflect.Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof GenericArrayType gat) {
            return Array.newInstance(erasedClass(gat.getGenericComponentType()), 0).getClass();
        }
        if (type instanceof TypeVariable<?> tv && tv.getBounds().length > 0) {
            return erasedClass(tv.getBounds()[0]);
        }
        return Object.class;
    }

    static boolean satisfiesJavaBounds(
            Type actual,
            TypeVariable<?> param,
            Map<TypeVariable<?>, Type> env,
            TypeAlgebra algebra) {
        if (actual == Type.ERROR || actual == Type.NOTHING || actual instanceof Type.TypeVar) {
            return true;
        }
        for (java.lang.reflect.Type bound : param.getBounds()) {
            if (bound == Object.class) {
                continue;
            }
            if (!satisfiesJavaBound(actual, bound, env, algebra)) {
                return false;
            }
        }
        return true;
    }

    private static boolean satisfiesJavaBound(
            Type actual,
            java.lang.reflect.Type bound,
            Map<TypeVariable<?>, Type> env,
            TypeAlgebra algebra) {
        if (bound == Object.class) {
            return true;
        }
        if (bound instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
            Type projected = algebra.projectToJava(actual, raw);
            if (!(projected instanceof Type.JavaInstance proj)) {
                return false;
            }
            java.lang.reflect.Type[] ptArgs = pt.getActualTypeArguments();
            List<Type> actualArgs = proj.typeArguments();
            if (ptArgs.length != actualArgs.size()) {
                return true;
            }
            for (int i = 0; i < ptArgs.length; i++) {
                java.lang.reflect.Type ptArg = ptArgs[i];
                Type a = actualArgs.get(i);
                if (ptArg instanceof WildcardType wt) {
                    if (wt.getLowerBounds().length > 0) {
                        Type lower =
                                fromJavaType(
                                        wt.getLowerBounds()[0],
                                        null,
                                        env,
                                        false,
                                        false,
                                        JavaNullability.Nullness.UNSPECIFIED,
                                        true);
                        if (!algebra.relate(lower, a, TypeAlgebra.RelateMode.ASSIGNABLE, null)) {
                            return false;
                        }
                    } else if (wt.getUpperBounds().length > 0
                            && wt.getUpperBounds()[0] != Object.class) {
                        Type upper =
                                fromJavaType(
                                        wt.getUpperBounds()[0],
                                        null,
                                        env,
                                        false,
                                        false,
                                        JavaNullability.Nullness.UNSPECIFIED,
                                        true);
                        if (!algebra.relate(a, upper, TypeAlgebra.RelateMode.SUBTYPE, null)) {
                            return false;
                        }
                    }
                } else {
                    Type expectedArg =
                            fromJavaType(
                                    ptArg,
                                    null,
                                    env,
                                    false,
                                    false,
                                    JavaNullability.Nullness.UNSPECIFIED,
                                    true);
                    if (!algebra.relate(a, expectedArg, TypeAlgebra.RelateMode.SUBTYPE, null)) {
                        return false;
                    }
                }
            }
            return true;
        }
        Type expectedBound =
                fromJavaType(
                        bound, null, env, false, false, JavaNullability.Nullness.UNSPECIFIED, true);
        return algebra.relate(actual, expectedBound, TypeAlgebra.RelateMode.ASSIGNABLE, null);
    }

    record BadJavaArrayTypeArg(int index, String message) {}

    /**
     * Checks BIP020-D13 (§2.6) on a generic Java class instantiation : rejects primitive scalar
     * type arguments for any class type parameter used directly as an array element {@code T[]}.
     */
    static BadJavaArrayTypeArg checkJavaClassArrayTypeArgs(
            Class<?> java, List<Type> args, TypeAlgebra algebra) {
        if (args.isEmpty() || args.stream().noneMatch(Type::isPrimitiveScalar)) {
            return null;
        }
        TypeVariable<?>[] tps = java.getTypeParameters();
        if (tps.length != args.size()) {
            return null;
        }
        Map<TypeVariable<?>, Type> synEnv = new HashMap<>();
        Map<Type.TypeVar, Type> subEnv = new HashMap<>();
        List<Type.TypeVar> synVars = new ArrayList<>(tps.length);
        for (int i = 0; i < tps.length; i++) {
            Type.TypeVar tv = new Type.TypeVar(null, tps[i].getName(), i);
            synVars.add(tv);
            synEnv.put(tps[i], tv);
            subEnv.put(tv, args.get(i));
        }
        TypeAlgebra.BadArraySubstitution bad =
                scanClassHierarchyForArrayTypeVars(java, synEnv, subEnv, algebra, new HashSet<>());
        if (bad == null) {
            return null;
        }
        int idx = bad.cause() != null ? synVars.indexOf(bad.cause()) : 0;
        return new BadJavaArrayTypeArg(Math.max(0, idx), bad.message());
    }

    private static TypeAlgebra.BadArraySubstitution scanClassHierarchyForArrayTypeVars(
            Class<?> cls,
            Map<TypeVariable<?>, Type> synEnv,
            Map<Type.TypeVar, Type> subEnv,
            TypeAlgebra algebra,
            Set<Class<?>> visited) {
        if (cls == null || cls == Object.class || !visited.add(cls)) {
            return null;
        }
        for (Constructor<?> ctor : cls.getConstructors()) {
            TypeAlgebra.BadArraySubstitution bad =
                    scanExecutableWithEnv(ctor, synEnv, subEnv, algebra);
            if (bad != null) {
                return bad;
            }
        }
        for (Field f : cls.getDeclaredFields()) {
            if (!Modifier.isPublic(f.getModifiers()) || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (!(f.getGenericType() instanceof Class<?>)) {
                Type pat =
                        fromJavaType(
                                f.getGenericType(),
                                f.getAnnotatedType(),
                                synEnv,
                                false,
                                false,
                                JavaNullability.fieldNullness(f),
                                JavaNullability.isNullMarkedClass(cls));
                TypeAlgebra.BadArraySubstitution bad =
                        algebra.findPrimitiveArraySubstitution(pat, subEnv);
                if (bad != null) {
                    return bad;
                }
            }
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())
                    || Modifier.isStatic(m.getModifiers())
                    || JavaTypes.isObjectMethod(m)) {
                continue;
            }
            TypeAlgebra.BadArraySubstitution bad =
                    scanExecutableWithEnv(m, synEnv, subEnv, algebra);
            if (bad != null) {
                return bad;
            }
        }
        java.lang.reflect.Type genericSuper = cls.getGenericSuperclass();
        if (genericSuper != null) {
            TypeAlgebra.BadArraySubstitution bad =
                    scanSuperTypeForArrayTypeVars(genericSuper, synEnv, subEnv, algebra, visited);
            if (bad != null) {
                return bad;
            }
        }
        for (java.lang.reflect.Type genericIface : cls.getGenericInterfaces()) {
            TypeAlgebra.BadArraySubstitution bad =
                    scanSuperTypeForArrayTypeVars(genericIface, synEnv, subEnv, algebra, visited);
            if (bad != null) {
                return bad;
            }
        }
        return null;
    }

    private static TypeAlgebra.BadArraySubstitution scanSuperTypeForArrayTypeVars(
            java.lang.reflect.Type superType,
            Map<TypeVariable<?>, Type> synEnv,
            Map<Type.TypeVar, Type> subEnv,
            TypeAlgebra algebra,
            Set<Class<?>> visited) {
        if (!(superType instanceof ParameterizedType pt)
                || !(pt.getRawType() instanceof Class<?> rawSuper)) {
            return null;
        }
        TypeVariable<?>[] superTps = rawSuper.getTypeParameters();
        java.lang.reflect.Type[] actualArgs = pt.getActualTypeArguments();
        if (superTps.length != actualArgs.length) {
            return null;
        }
        Map<TypeVariable<?>, Type> parentSynEnv = new HashMap<>();
        for (int i = 0; i < superTps.length; i++) {
            parentSynEnv.put(
                    superTps[i],
                    fromJavaType(
                            actualArgs[i],
                            null,
                            synEnv,
                            false,
                            false,
                            JavaNullability.Nullness.UNSPECIFIED,
                            true));
        }
        return scanClassHierarchyForArrayTypeVars(rawSuper, parentSynEnv, subEnv, algebra, visited);
    }

    private static TypeAlgebra.BadArraySubstitution scanExecutableWithEnv(
            Executable exec,
            Map<TypeVariable<?>, Type> synEnv,
            Map<Type.TypeVar, Type> subEnv,
            TypeAlgebra algebra) {
        return scanExecutableWithEnv(exec, synEnv, subEnv, algebra, false);
    }

    private static TypeAlgebra.BadArraySubstitution scanExecutableWithEnv(
            Executable exec,
            Map<TypeVariable<?>, Type> synEnv,
            Map<Type.TypeVar, Type> subEnv,
            TypeAlgebra algebra,
            boolean varArgs) {
        java.lang.reflect.Type[] gParams = exec.getGenericParameterTypes();
        int fixed = varArgs ? gParams.length - 1 : gParams.length;
        for (int i = 0; i < fixed; i++) {
            if (!(gParams[i] instanceof Class<?>)) {
                Type pat = javaExecutableParamType(exec, i, synEnv);
                TypeAlgebra.BadArraySubstitution bad =
                        algebra.findPrimitiveArraySubstitution(pat, subEnv);
                if (bad != null) {
                    return bad;
                }
            }
        }
        if (varArgs && gParams.length > 0 && !(gParams[fixed] instanceof Class<?>)) {
            Type compPat = javaVarArgParamType(exec, fixed, synEnv);
            TypeAlgebra.BadArraySubstitution bad =
                    algebra.findPrimitiveArraySubstitution(compPat, subEnv);
            if (bad != null) {
                return bad;
            }
        }
        if (exec instanceof Method m && !(m.getGenericReturnType() instanceof Class<?>)) {
            Type pat =
                    fromJavaType(
                            m.getGenericReturnType(),
                            m.getAnnotatedReturnType(),
                            synEnv,
                            true,
                            false,
                            JavaNullability.returnNullness(m),
                            JavaNullability.isNullMarked(m));
            return algebra.findPrimitiveArraySubstitution(pat, subEnv);
        }
        return null;
    }

    /**
     * Checks BIP020-D13 (§2.6) on a generic Java method or constructor call with solved/explicit
     * type arguments : rejects primitive scalars bound to a type parameter used in {@code T[]}.
     */
    static BadJavaArrayTypeArg checkJavaExecutableArrayTypeArgs(
            Executable exec,
            List<TypeVariable<?>> orderedVars,
            Map<TypeVariable<?>, Type> fullSolvedEnv,
            TypeAlgebra algebra) {
        return checkJavaExecutableArrayTypeArgs(exec, orderedVars, fullSolvedEnv, algebra, false);
    }

    static BadJavaArrayTypeArg checkJavaExecutableArrayTypeArgs(
            Executable exec,
            List<TypeVariable<?>> orderedVars,
            Map<TypeVariable<?>, Type> fullSolvedEnv,
            TypeAlgebra algebra,
            boolean varArgs) {
        if (fullSolvedEnv.isEmpty()
                || fullSolvedEnv.values().stream().noneMatch(Type::isPrimitiveScalar)) {
            return null;
        }
        Map<TypeVariable<?>, Type> synEnv = new HashMap<>(fullSolvedEnv);
        Map<Type.TypeVar, Type> subEnv = new HashMap<>();
        List<Type.TypeVar> synVars = new ArrayList<>(orderedVars.size());
        for (int i = 0; i < orderedVars.size(); i++) {
            TypeVariable<?> tp = orderedVars.get(i);
            Type.TypeVar tv = new Type.TypeVar(null, tp.getName(), i);
            synVars.add(tv);
            synEnv.put(tp, tv);
            subEnv.put(tv, fullSolvedEnv.get(tp));
        }
        TypeAlgebra.BadArraySubstitution bad =
                scanExecutableWithEnv(exec, synEnv, subEnv, algebra, varArgs);
        if (bad == null && exec instanceof Constructor<?>) {
            Class<?> cls = exec.getDeclaringClass();
            TypeVariable<?>[] clsTps = cls.getTypeParameters();
            if (clsTps.length > 0) {
                List<Type> clsArgs = new ArrayList<>(clsTps.length);
                for (TypeVariable<?> tp : clsTps) {
                    clsArgs.add(fullSolvedEnv.get(tp));
                }
                return checkJavaClassArrayTypeArgs(cls, clsArgs, algebra);
            }
        }
        if (bad == null) {
            return null;
        }
        int idx = bad.cause() != null ? synVars.indexOf(bad.cause()) : 0;
        return new BadJavaArrayTypeArg(Math.max(0, idx), bad.message());
    }

    /**
     * The type of what a Java method returns, or a Java field holds: an instance of its class
     * (whose members are checked), or the closest accessible supertype / {@code any} when the
     * concrete class is package-private or non-public.
     */
    static Type javaResult(
            java.lang.reflect.Type genericType,
            Map<TypeVariable<?>, Type> env,
            TypeAlgebra algebra) {
        return makeAccessible(fromJavaType(genericType, env, true, false), algebra);
    }

    static Type javaMethodResult(
            Method method, Map<TypeVariable<?>, Type> env, TypeAlgebra algebra) {
        Type raw =
                fromJavaType(
                        method.getGenericReturnType(),
                        method.getAnnotatedReturnType(),
                        env,
                        true,
                        false,
                        JavaNullability.returnNullness(method),
                        JavaNullability.isNullMarked(method));
        return makeAccessible(raw, algebra);
    }

    static Type javaExecutableParamType(
            Executable exec, int index, Map<TypeVariable<?>, Type> env) {
        java.lang.reflect.Type[] genericParams = exec.getGenericParameterTypes();
        AnnotatedType[] annParams = exec.getAnnotatedParameterTypes();
        AnnotatedType ann = index < annParams.length ? annParams[index] : null;
        Type param =
                fromJavaType(
                        genericParams[index],
                        ann,
                        env,
                        false,
                        true,
                        JavaNullability.parameterNullness(exec, index),
                        JavaNullability.isNullMarked(exec));
        JavaNullability.Nullness elemNullness =
                JavaNullability.parameterElementNullness(exec, index);
        if (elemNullness != null
                && elemNullness != JavaNullability.Nullness.UNSPECIFIED
                && Type.withoutNull(param) instanceof Type.Array arr
                && !Type.isPrimitiveScalar(arr.element())) {
            Type nextElem =
                    elemNullness == JavaNullability.Nullness.NULLABLE
                            ? Type.nullable(arr.element())
                            : Type.withoutNull(arr.element());
            if (!nextElem.equals(arr.element())) {
                Type rebuilt = new Type.Array(nextElem);
                param = Type.containsNull(param) ? Type.nullable(rebuilt) : rebuilt;
            }
        }
        return param;
    }

    /**
     * Parameter type for argument {@code argIndex} in a Phase 3 variable-arity Java call ({@code
     * BIP021-D8}): fixed parameters use {@link #javaExecutableParamType}, trailing arguments use
     * the varargs array's component type.
     */
    static Type javaVarArgParamType(Executable exec, int argIndex, Map<TypeVariable<?>, Type> env) {
        int fixed = exec.getParameterCount() - 1;
        if (argIndex < fixed) {
            return javaExecutableParamType(exec, argIndex, env);
        }
        java.lang.reflect.Type[] genericParams = exec.getGenericParameterTypes();
        java.lang.reflect.Type varParam = genericParams[fixed];
        AnnotatedType[] annParams = exec.getAnnotatedParameterTypes();
        AnnotatedType varAnn = fixed < annParams.length ? annParams[fixed] : null;
        AnnotatedType compAnn =
                varAnn instanceof AnnotatedArrayType aat
                        ? aat.getAnnotatedGenericComponentType()
                        : null;
        java.lang.reflect.Type compGeneric;
        if (varParam instanceof GenericArrayType gat) {
            compGeneric = gat.getGenericComponentType();
        } else if (varParam instanceof Class<?> cls && cls.isArray()) {
            compGeneric = cls.getComponentType();
        } else {
            return Type.ANY;
        }
        boolean nullMarked = JavaNullability.isNullMarked(exec);
        JavaNullability.Nullness compNullness;
        if (compGeneric instanceof Class<?> c && c.isPrimitive()) {
            compNullness = JavaNullability.Nullness.NON_NULL;
        } else if (compGeneric instanceof TypeVariable<?>) {
            compNullness = JavaNullability.Nullness.PARAMETRIC;
        } else {
            JavaNullability.Nullness elemNullness =
                    JavaNullability.parameterElementNullness(exec, fixed);
            if (elemNullness != null && elemNullness != JavaNullability.Nullness.UNSPECIFIED) {
                compNullness = elemNullness;
            } else {
                JavaNullability.Nullness paramNullness =
                        JavaNullability.parameterNullness(exec, fixed);
                compNullness =
                        paramNullness == JavaNullability.Nullness.NULLABLE
                                ? JavaNullability.Nullness.NULLABLE
                                : (nullMarked
                                        ? JavaNullability.Nullness.NON_NULL
                                        : JavaNullability.Nullness.UNSPECIFIED);
            }
        }
        return fromJavaType(compGeneric, compAnn, env, false, true, compNullness, nullMarked);
    }

    static Type makeAccessible(Type type, TypeAlgebra algebra) {
        if (type instanceof Type.Union union) {
            return Type.union(
                    union.members().stream().map(m -> makeAccessible(m, algebra)).toList());
        }
        if (type instanceof Type.JavaInstance ji
                && !ji.type().isInterface()
                && !JavaTypes.isAccessible(ji.type())) {
            Class<?> accessible = accessibleType(ji.type());
            if (accessible == null) {
                return Type.ANY;
            }
            Type projected = algebra.projectToJava(ji, accessible);
            return projected != null ? projected : fromJava(accessible);
        }
        return type;
    }

    static Class<?> accessibleType(Class<?> type) {
        ArrayDeque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(type);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            if (current != type && current != Object.class && JavaTypes.isAccessible(current)) {
                return current;
            }
            if (current.getSuperclass() != null && current.getSuperclass() != Object.class) {
                queue.add(current.getSuperclass());
            }
            queue.addAll(Arrays.asList(current.getInterfaces()));
        }
        return null;
    }

    sealed interface JavaSamResolution
            permits JavaSamResolution.Valid,
                    JavaSamResolution.NotFunctional,
                    JavaSamResolution.None {
        record Valid(Type.JavaInstance samType, Method samMethod, List<Class<?>> markers)
                implements JavaSamResolution {
            LoweringTargets.JavaSamTarget toTarget() {
                return new LoweringTargets.JavaSamTarget(
                        samType.type(), markers.toArray(Class<?>[]::new));
            }
        }

        record NotFunctional(Type.Intersection intersection) implements JavaSamResolution {}

        record None() implements JavaSamResolution {}

        JavaSamResolution NONE = new None();
    }

    static JavaSamResolution resolveJavaSam(Type type) {
        if (type == null || type == Type.ERROR || type == Type.NOTHING) {
            return JavaSamResolution.NONE;
        }
        Type base = TypeAlgebra.unwrapFrozen(Type.withoutNull(type));
        if (base instanceof Type.JavaInstance ji) {
            Method m = JavaTypes.functionalMethod(ji.type());
            return m != null
                    ? new JavaSamResolution.Valid(ji, m, List.of())
                    : JavaSamResolution.NONE;
        }
        if (!(base instanceof Type.Intersection inter)) {
            return JavaSamResolution.NONE;
        }
        int abstractCount = 0;
        boolean allJava = true;
        Type.JavaInstance primary = null;
        Method primaryMethod = null;
        List<Class<?>> extraInterfaces = new ArrayList<>();
        for (Type member : inter.members()) {
            if (member instanceof Type.JavaInstance ji && ji.type().isInterface()) {
                List<Method> abs = JavaTypes.abstractMethods(ji.type());
                if (abs.isEmpty()) {
                    extraInterfaces.add(ji.type());
                    continue;
                }
                Method m = JavaTypes.functionalMethod(ji.type());
                if (m == null) {
                    abstractCount += abs.size();
                    if (abstractCount >= 2) {
                        return new JavaSamResolution.NotFunctional(inter);
                    }
                    allJava = false;
                    continue;
                }
                if (primary == null) {
                    primary = ji;
                    primaryMethod = m;
                    abstractCount++;
                } else if (sameSamSignature(primaryMethod, m)) {
                    extraInterfaces.add(ji.type());
                } else {
                    abstractCount++;
                    return new JavaSamResolution.NotFunctional(inter);
                }
            } else if (member instanceof Type.Instance inst && inst.node().isInterface()) {
                allJava = false;
                abstractCount +=
                        countBabbleInterfaceAbstractMethods(
                                inst.node(), Collections.newSetFromMap(new IdentityHashMap<>()));
                if (abstractCount >= 2) {
                    return new JavaSamResolution.NotFunctional(inter);
                }
            } else {
                allJava = false;
            }
        }
        if (abstractCount >= 2) {
            return new JavaSamResolution.NotFunctional(inter);
        }
        if (allJava && primary != null) {
            return new JavaSamResolution.Valid(
                    primary, primaryMethod, List.copyOf(extraInterfaces));
        }
        return JavaSamResolution.NONE;
    }

    static boolean sameSamSignature(Method a, Method b) {
        return a.getName().equals(b.getName())
                && Arrays.equals(a.getParameterTypes(), b.getParameterTypes())
                && a.getReturnType().equals(b.getReturnType());
    }

    private static int countBabbleInterfaceAbstractMethods(TypeNode node, Set<TypeNode> visited) {
        if (!visited.add(node)) {
            return 0;
        }
        int count = 0;
        for (TypeNode.Member m : node.members.values()) {
            if (!m.hasFunction) {
                TypeChecks.Provider def = node.defaults != null ? node.defaults.get(m.name) : null;
                if (def == null || !def.hasFunction()) {
                    count++;
                }
            }
        }
        for (Object parent : node.parents) {
            if (parent instanceof TypeNode pNode && pNode.isInterface()) {
                count += countBabbleInterfaceAbstractMethods(pNode, visited);
            } else if (parent instanceof Class<?> javaParent) {
                for (Method jm : JavaTypes.abstractMethods(javaParent)) {
                    TypeChecks.Provider def =
                            node.defaults != null ? node.defaults.get(jm.getName()) : null;
                    if (def == null || !def.hasFunction()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    static Type.Function samFunctionType(Type.JavaInstance java, EffectChecker effectChecker) {
        Method sam = JavaTypes.functionalMethod(java.type());
        if (sam == null) {
            return null;
        }
        Map<TypeVariable<?>, Type> env = javaTypeEnv(java.type(), java.typeArguments());
        java.lang.reflect.Type[] genericParams = sam.getGenericParameterTypes();
        List<Type.Parameter> params = new ArrayList<>(genericParams.length);
        for (int i = 0; i < genericParams.length; i++) {
            params.add(new Type.Parameter("p" + i, javaExecutableParamType(sam, i, env), false));
        }
        Type returnType =
                fromJavaType(
                        sam.getGenericReturnType(),
                        sam.getAnnotatedReturnType(),
                        env,
                        false,
                        false,
                        JavaNullability.returnNullness(sam),
                        JavaNullability.isNullMarked(sam));
        Type.Function raw = new Type.Function(params, returnType);
        return effectChecker != null
                ? effectChecker.javaResolver().applySamJavaEffects(sam, raw)
                : raw;
    }

    static boolean hasJavaMethod(Class<?> iface, String name) {
        for (Method method : iface.getMethods()) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    static boolean isPublic(Class<?> cls) {
        return Modifier.isPublic(cls.getModifiers());
    }

    static boolean isBytecodeAccessible(Class<?> type) {
        while (type.isArray()) {
            type = type.getComponentType();
        }
        return type.isPrimitive() || JavaTypes.isAccessible(type);
    }

    static boolean supportedStaticJavaArg(Class<?> paramType, Type argType) {
        if (paramType.isPrimitive()) {
            if (paramType == int.class) {
                return argType == Type.INT;
            }
            if (paramType == boolean.class) {
                return argType == Type.BOOL;
            }
            if (paramType == double.class) {
                return argType == Type.INT || argType == Type.DOUBLE;
            }
            if (paramType == long.class) {
                return argType == Type.INT || argType == Type.LONG;
            }
            if (paramType == float.class) {
                return argType == Type.INT || argType == Type.FLOAT;
            }
            return false;
        }
        return isBytecodeAccessible(paramType);
    }

    /** A public field Babble can reach, static or not, or null. */
    static Field javaField(Class<?> java, String name) {
        try {
            Field field = java.getField(name);
            return JavaTypes.isAccessible(field.getDeclaringClass()) ? field : null;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /**
     * A class by its qualified name, trying each trailing dot as a nested class ({@code a.B.C} as
     * {@code a.B$C}) ; null if none.
     */
    static Class<?> loadClass(String name) {
        String candidate = name;
        while (true) {
            Class<?> found = loadTopLevel(candidate);
            if (found != null) {
                return found;
            }
            int dot = candidate.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
        }
    }

    /** As {@code JavaPackage} finds them : a class with that exact name, or null. */
    static Class<?> loadTopLevel(String name) {
        try {
            return Class.forName(name, false, JavaTypeBridge.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** The public member class of {@code enclosing} with that simple name, or null. */
    static Class<?> publicMemberClass(Class<?> enclosing, String simpleName) {
        for (Class<?> c : enclosing.getClasses()) {
            if (c.getSimpleName().equals(simpleName) && Modifier.isPublic(c.getModifiers())) {
                return c;
            }
        }
        return null;
    }

    static int typeParameterCount(Class<?> java) {
        return java.getTypeParameters().length;
    }

    static String typeParameterName(Class<?> java, int index) {
        return java.getTypeParameters()[index].getName();
    }

    /**
     * A Java member read through a class or an instance : its {@code type}, or the {@code error} to
     * report ; {@code checked} when it is a field whose generic type needs a runtime check ({@code
     * T9}), and the static {@code fieldTarget} of a public, accessible field.
     */
    record JavaMember(
            Type type,
            String error,
            boolean checked,
            LoweringTargets.JavaFieldTarget fieldTarget) {}

    /**
     * A method or a public field of a Java class, as {@code JavaClass} looks them up : through the
     * class itself, only a static field.
     */
    static JavaMember javaMember(
            Class<?> java, List<Type> typeArgs, String name, boolean onClass, TypeAlgebra algebra) {
        if (hasJavaMethod(java, name)) {
            return new JavaMember(
                    new Type.JavaMethod(java, onClass ? List.of() : typeArgs, name),
                    null,
                    false,
                    null);
        }
        if (java.isInterface() && !onClass && hasJavaMethod(Object.class, name)) {
            return new JavaMember(
                    new Type.JavaMethod(Object.class, List.of(), name), null, false, null);
        }
        Field field = javaField(java, name);
        if (field == null) {
            return new JavaMember(
                    Type.ERROR,
                    "No such name in " + java.getCanonicalName() + " : " + name,
                    false,
                    null);
        }
        boolean isStatic = Modifier.isStatic(field.getModifiers());
        if (onClass && !isStatic) {
            return new JavaMember(
                    Type.ERROR,
                    "Not a static field : " + java.getCanonicalName() + "." + name,
                    false,
                    null);
        }
        Map<TypeVariable<?>, Type> env = onClass ? Map.of() : javaTypeEnv(java, typeArgs);
        java.lang.reflect.Type genericType = field.getGenericType();
        Type fieldType =
                makeAccessible(
                        fromJavaType(
                                genericType,
                                field.getAnnotatedType(),
                                env,
                                true,
                                false,
                                JavaNullability.fieldNullness(field),
                                false),
                        algebra);
        boolean checked = !field.getType().isPrimitive() && !Type.containsNull(fieldType);
        LoweringTargets.JavaFieldTarget target =
                Modifier.isPublic(field.getModifiers())
                                && JavaTypes.isAccessible(field.getDeclaringClass())
                        ? new LoweringTargets.JavaFieldTarget(field, isStatic, !isStatic)
                        : null;
        return new JavaMember(fieldType, null, checked, target);
    }

    /** A Java field an assignment writes. */
    record AssignedField(Field field) {
        /**
         * Its type read through {@code receiver} ; {@code widenTopTypeVar} for the value read
         * before a compound assignment.
         */
        Type type(Type receiver, boolean widenTopTypeVar, TypeAlgebra algebra) {
            return javaFieldType(receiver, field, widenTopTypeVar, algebra);
        }

        /**
         * The error assigning a function to a functional-interface field, which needs an explicit
         * conversion ; null otherwise.
         */
        String functionError(Type value, String name) {
            if (TypeAlgebra.isCallable(value) && JavaTypes.isFunctionalInterface(field.getType())) {
                return "Cannot assign a function to "
                        + field.getDeclaringClass().getCanonicalName()
                        + "."
                        + name
                        + " : convert it first, as "
                        + field.getType().getSimpleName()
                        + "(f)";
            }
            return null;
        }

        /**
         * The static write target for a value of that type, or null when it must go through {@code
         * JavaClass.assign}.
         */
        LoweringTargets.JavaFieldTarget target(Type value) {
            if (Modifier.isFinal(field.getModifiers())
                    || !Modifier.isPublic(field.getModifiers())
                    || !JavaTypes.isAccessible(field.getDeclaringClass())
                    || !guaranteedApplicable(field.getType(), value)
                    || !supportedStaticJavaArg(field.getType(), value)) {
                return null;
            }
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            return new LoweringTargets.JavaFieldTarget(field, isStatic, !isStatic);
        }
    }

    /**
     * The field {@code receiver.name = value} assigns when the receiver is a Java class, object or
     * array : the field, or the error {@code JavaClass.assign} would report (as {@code [error]}).
     * Null when the receiver isn't Java.
     */
    static Object assignedField(Type receiver, String name) {
        if (receiver instanceof Type.Array array) {
            String owner = array.toString();
            return "length".equals(name)
                    ? "No such field in " + owner + " : length (use size())"
                    : "No such field in " + owner + " : " + name;
        }
        boolean onClass = receiver instanceof Type.JavaClassValue;
        Class<?> java =
                onClass
                        ? ((Type.JavaClassValue) receiver).type()
                        : receiver instanceof Type.JavaInstance instance ? instance.type() : null;
        if (java == null) {
            return null;
        }
        Field field = javaField(java, name);
        String description = java.getCanonicalName() + "." + name;
        if (field == null) {
            return "No such field in " + java.getCanonicalName() + " : " + name;
        }
        if (onClass && !Modifier.isStatic(field.getModifiers())) {
            return "Not a static field : " + description;
        }
        if (Modifier.isFinal(field.getModifiers())) {
            return "Cannot assign final field : " + description;
        }
        return new AssignedField(field);
    }

    static Type javaFieldType(
            Type receiver, Field field, boolean widenTopTypeVar, TypeAlgebra algebra) {
        Map<TypeVariable<?>, Type> env =
                receiver instanceof Type.JavaInstance ji
                        ? javaTypeEnv(ji.type(), ji.typeArguments())
                        : Map.of();
        return makeAccessible(
                fromJavaType(
                        field.getGenericType(),
                        field.getAnnotatedType(),
                        env,
                        widenTopTypeVar,
                        false,
                        JavaNullability.fieldNullness(field),
                        false),
                algebra);
    }

    /**
     * The JVM array class checked at runtime for {@code array} ({@code BIP020-D5}, {@code
     * BIP020-D9}) : primitive arrays, boxed arrays for nullable primitives, {@code C[]} for core
     * scalars and Java classes, and {@code Object[]} for Babble-defined elements, intersections,
     * unions, functions, {@code any} and type variables.
     */
    static Class<?> checkedArrayClass(Type.Array array) {
        return checkedArrayClassForElement(array.element());
    }

    private static Class<?> checkedArrayClassForElement(Type elem) {
        elem = TypeAlgebra.unwrapFrozen(elem);
        if (elem == Type.BYTE) return byte[].class;
        if (elem == Type.SHORT) return short[].class;
        if (elem == Type.INT) return int[].class;
        if (elem == Type.LONG) return long[].class;
        if (elem == Type.FLOAT) return float[].class;
        if (elem == Type.DOUBLE) return double[].class;
        if (elem == Type.BOOL) return boolean[].class;
        if (elem == Type.CHAR) return char[].class;
        Type base = TypeAlgebra.unwrapFrozen(Type.withoutNull(elem));
        if (base == Type.BYTE) return Byte[].class;
        if (base == Type.SHORT) return Short[].class;
        if (base == Type.INT) return Integer[].class;
        if (base == Type.LONG) return Long[].class;
        if (base == Type.FLOAT) return Float[].class;
        if (base == Type.DOUBLE) return Double[].class;
        if (base == Type.BOOL) return Boolean[].class;
        if (base == Type.CHAR) return Character[].class;
        if (base == Type.STRING) return String[].class;
        if (base instanceof Type.JavaInstance ji) {
            return Array.newInstance(ji.type(), 0).getClass();
        }
        if (base instanceof Type.Array inner) {
            return Array.newInstance(checkedArrayClass(inner), 0).getClass();
        }
        return Object[].class;
    }

    static Class<?> javaClassOf(Type type) {
        if (type instanceof Type.Simple simple) {
            return simple.javaClass();
        }
        if (type instanceof Type.Array array) {
            return checkedArrayClass(array);
        }
        return type instanceof Type.JavaInstance instance ? instance.type() : null;
    }

    static Map<TypeVariable<?>, Type.TypeVar> candidateSolvingVars(Executable cand) {
        Map<TypeVariable<?>, Type.TypeVar> map = new HashMap<>();
        int idx = 0;
        if (cand instanceof Constructor<?>) {
            for (TypeVariable<?> tp : cand.getDeclaringClass().getTypeParameters()) {
                map.put(tp, new Type.TypeVar(null, tp.getName(), idx++));
            }
        }
        for (TypeVariable<?> tp : cand.getTypeParameters()) {
            map.putIfAbsent(tp, new Type.TypeVar(null, tp.getName(), idx++));
        }
        return map;
    }

    static <E extends Executable> Type expectedGenericJavaArgumentType(
            List<E> candidates,
            int index,
            boolean isLambdaArg,
            Map<TypeVariable<?>, Type> receiverEnv) {
        return expectedGenericJavaArgumentType(candidates, index, isLambdaArg, receiverEnv, false);
    }

    static <E extends Executable> Type expectedGenericJavaArgumentType(
            List<E> candidates,
            int index,
            boolean isLambdaArg,
            Map<TypeVariable<?>, Type> receiverEnv,
            boolean varArgs) {
        if (candidates.isEmpty()) {
            return null;
        }
        Set<Type> paramTypes = new LinkedHashSet<>();
        for (E candidate : candidates) {
            Map<TypeVariable<?>, Type> env = new HashMap<>(receiverEnv);
            candidateSolvingVars(candidate).forEach(env::putIfAbsent);
            Type paramType =
                    varArgs
                            ? javaVarArgParamType(candidate, index, env)
                            : javaExecutableParamType(candidate, index, env);
            if (isLambdaArg && Type.withoutNull(paramType) instanceof Type.TypeVar) {
                int fixed = candidate.getParameterCount() - 1;
                java.lang.reflect.Type genericParam =
                        varArgs && index >= fixed
                                ? (candidate.getGenericParameterTypes()[fixed]
                                                instanceof GenericArrayType gat
                                        ? gat.getGenericComponentType()
                                        : Object.class)
                                : candidate.getGenericParameterTypes()[index];
                if (genericParam instanceof TypeVariable<?> tv && !receiverEnv.containsKey(tv)) {
                    Map<TypeVariable<?>, Type> boundEnv = new HashMap<>(env);
                    boundEnv.remove(tv);
                    Type bound =
                            translateTypeVarBounds(
                                    tv,
                                    boundEnv,
                                    true,
                                    Collections.newSetFromMap(new IdentityHashMap<>()),
                                    new HashSet<>());
                    if (resolveJavaSam(bound) instanceof JavaSamResolution.Valid) {
                        paramType = bound;
                    }
                }
            }
            paramTypes.add(paramType);
        }
        if (paramTypes.size() == 1) {
            return paramTypes.iterator().next();
        }
        if (isLambdaArg) {
            Type samType = null;
            for (Type candidateType : paramTypes) {
                Type base = Type.withoutNull(candidateType);
                if (resolveJavaSam(base) instanceof JavaSamResolution.Valid) {
                    if (samType != null && !samType.equals(base)) {
                        return null;
                    }
                    samType = base;
                }
            }
            return samType;
        }
        return null;
    }

    static boolean applicableGeneric(
            Executable candidate,
            int index,
            Type argument,
            Map<TypeVariable<?>, Type> receiverEnv,
            TypeAlgebra algebra) {
        return applicableGeneric(
                candidate, index, argument, receiverEnv, algebra, false, false, false);
    }

    static boolean applicableGeneric(
            Executable candidate,
            int index,
            Type argument,
            Map<TypeVariable<?>, Type> receiverEnv,
            TypeAlgebra algebra,
            boolean borrowParam,
            boolean mutParam) {
        return applicableGeneric(
                candidate, index, argument, receiverEnv, algebra, borrowParam, mutParam, false);
    }

    static boolean applicableGeneric(
            Executable candidate,
            int index,
            Type argument,
            Map<TypeVariable<?>, Type> receiverEnv,
            TypeAlgebra algebra,
            boolean borrowParam,
            boolean mutParam,
            boolean varArgs) {
        int fixed = candidate.getParameterCount() - 1;
        boolean inVarTail = varArgs && index >= fixed;
        Class<?> rawParam =
                inVarTail
                        ? candidate.getParameterTypes()[fixed].getComponentType()
                        : candidate.getParameterTypes()[index];
        java.lang.reflect.Type genericParam;
        if (inVarTail) {
            java.lang.reflect.Type vp = candidate.getGenericParameterTypes()[fixed];
            genericParam =
                    vp instanceof GenericArrayType gat
                            ? gat.getGenericComponentType()
                            : (vp instanceof Class<?> c && c.isArray()
                                    ? c.getComponentType()
                                    : Object.class);
        } else {
            genericParam = candidate.getGenericParameterTypes()[index];
        }
        if (genericParam instanceof TypeVariable<?> tv && TypeAlgebra.isCallable(argument)) {
            Type boundPattern;
            if (receiverEnv.containsKey(tv)) {
                boundPattern = receiverEnv.get(tv);
            } else {
                Map<TypeVariable<?>, Type> tvEnv = new HashMap<>(receiverEnv);
                candidateSolvingVars(candidate)
                        .forEach((k, v) -> {
                            if (k != tv) {
                                tvEnv.putIfAbsent(k, v);
                            }
                        });
                boundPattern =
                        translateTypeVarBounds(
                                tv,
                                tvEnv,
                                true,
                                Collections.newSetFromMap(new IdentityHashMap<>()),
                                new HashSet<>());
            }
            Type baseBound = Type.withoutNull(boundPattern);
            if (baseBound == Type.ANY) {
                return true;
            }
            if (resolveJavaSam(baseBound) instanceof JavaSamResolution.Valid valid) {
                if (!baseBound.anyMatch(t -> t instanceof Type.TypeVar)) {
                    Type.Function sam = samFunctionType(valid.samType(), null);
                    if (sam != null && !matchesCallableSamArity(argument, sam)) {
                        return false;
                    }
                }
                return true;
            }
            if (!receiverEnv.containsKey(tv)) {
                return false;
            }
        }
        if (!applicable(rawParam, argument, algebra)) {
            return false;
        }
        if (TypeChecker.isUnresolved(argument)) {
            return true;
        }
        if (genericParam instanceof Class<?> && !rawParam.isArray()) {
            return true;
        }
        Map<TypeVariable<?>, Type.TypeVar> tvToSyn = candidateSolvingVars(candidate);
        Map<TypeVariable<?>, Type> env = new HashMap<>(receiverEnv);
        env.putAll(tvToSyn);
        Type paramPattern =
                inVarTail
                        ? javaVarArgParamType(candidate, index, env)
                        : javaExecutableParamType(candidate, index, env);
        if (!paramPattern.anyMatch(t -> t instanceof Type.TypeVar tv && tv.owner() == null)) {
            if (genericParam instanceof ParameterizedType pt
                    && hasWildcard(pt)
                    && !(Type.withoutNull(argument) instanceof Type.Function)
                    && !(Type.withoutNull(argument) instanceof Type.Overloaded)) {
                if (!Type.containsNull(paramPattern) && Type.containsNull(argument)) {
                    return false;
                }
                return satisfiesJavaBound(Type.withoutNull(argument), pt, env, algebra);
            }
            return algebra.relate(
                    argument,
                    paramPattern,
                    TypeAlgebra.RelateMode.ASSIGNABLE,
                    null,
                    borrowParam,
                    !mutParam);
        }
        Type unwrappedPattern = Type.withoutNull(paramPattern);
        if (resolveJavaSam(unwrappedPattern) instanceof JavaSamResolution.Valid valid) {
            Type.Function sam = samFunctionType(valid.samType(), null);
            if (sam != null) {
                return matchesCallableSamArity(argument, sam);
            }
        }
        Set<Type.TypeVar> solving = Set.copyOf(tvToSyn.values());
        TypeAlgebra.ConstraintStore store = algebra.store(solving, true);
        return algebra.relate(
                argument,
                paramPattern,
                TypeAlgebra.RelateMode.ASSIGNABLE,
                store,
                borrowParam,
                !mutParam);
    }

    private static boolean matchesCallableSamArity(Type argument, Type.Function sam) {
        if (argument instanceof Type.Function aFn) {
            return matchesSamArity(aFn, sam);
        }
        if (argument instanceof Type.Overloaded aOv) {
            for (Type.Function alt : aOv.alternatives()) {
                if (matchesSamArity(alt, sam)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean matchesSamArity(Type.Function aFn, Type.Function sam) {
        int aRest = aFn.restIndex();
        if (aRest < 0 && aFn.parameters().size() < sam.parameters().size()) {
            return false;
        }
        for (int i = sam.parameters().size(); i < aFn.parameters().size(); i++) {
            Type.Parameter p = aFn.parameters().get(i);
            if (!p.optional() && !p.rest()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasWildcard(ParameterizedType pt) {
        for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
            if (arg instanceof WildcardType
                    || (arg instanceof ParameterizedType nested && hasWildcard(nested))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The overload more specific than all the others, as {@code Overloads.resolve} finds it, or
     * null.
     */
    static <E extends Executable> E mostSpecific(List<E> applicable) {
        return mostSpecific(applicable, false);
    }

    static <E extends Executable> E mostSpecific(List<E> applicable, boolean varArgs) {
        for (E candidate : applicable) {
            boolean beatsAll = true;
            for (E other : applicable) {
                if (other == candidate) {
                    continue;
                }
                boolean more =
                        varArgs
                                ? org.babblelang.runtime.Overloads.isVarArgsMoreSpecific(
                                        candidate, other)
                                : org.babblelang.runtime.Overloads.isMoreSpecific(candidate, other);
                if (!more) {
                    beatsAll = false;
                    break;
                }
                if (varArgs) {
                    boolean reverse =
                            org.babblelang.runtime.Overloads.isVarArgsMoreSpecific(
                                    other, candidate);
                    boolean sameParams =
                            Arrays.equals(candidate.getParameterTypes(), other.getParameterTypes());
                    if (reverse && !sameParams) {
                        beatsAll = false;
                        break;
                    }
                }
            }
            if (beatsAll) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether an argument of a static type can be passed to a Java parameter (as {@code Overloads}
     * decides at runtime).
     */
    static boolean applicable(Class<?> parameter, Type argument, TypeAlgebra algebra) {
        if (TypeChecker.isUnresolved(argument)) {
            return true;
        }
        if (argument instanceof Type.Frozen f) {
            return applicable(parameter, f.of(), algebra);
        }
        if (argument instanceof Type.Union union) {
            return union.members().stream()
                    .allMatch(member -> applicable(parameter, member, algebra));
        }
        if (argument == Type.NULL) {
            return !parameter.isPrimitive();
        }
        if (argument instanceof Type.Array array) {
            if (parameter == Object.class
                    || parameter == Cloneable.class
                    || parameter == java.io.Serializable.class) {
                return true;
            }
            if (!parameter.isArray()) {
                return false;
            }
            return TypeAlgebra.jvmArrayAssignable(
                    array.element(), fromJavaArrayComponent(parameter.getComponentType()));
        }
        Class<?> java = javaClassOf(argument);
        if (java != null) {
            return JavaTypes.box(parameter).isAssignableFrom(java)
                    || parameter.isPrimitive()
                            && JavaTypes.isWidening(java, JavaTypes.box(parameter));
        }
        if (argument instanceof Type.Instance instance) {
            return parameter == Object.class
                    || TypeAlgebra.implementsJava(instance.node(), parameter);
        }
        if (TypeAlgebra.isCallable(argument)) {
            return parameter == Object.class || JavaTypes.isFunctionalInterface(parameter);
        }
        return !parameter.isPrimitive();
    }

    static boolean guaranteedApplicable(Class<?> parameter, Type argument) {
        if (parameter == Object.class) {
            return true;
        }
        return !containsDynamicOrTypeVar(argument, JavaTypes.isFunctionalInterface(parameter));
    }

    private static boolean containsDynamicOrTypeVar(Type type, boolean rejectCallables) {
        if (type == null
                || TypeChecker.isUnresolved(type)
                || type == Type.ANY
                || type instanceof Type.TypeVar) {
            return true;
        }
        if (rejectCallables && type instanceof Type.JavaMethod) {
            return true;
        }
        if (type instanceof Type.Union union) {
            return union.members().stream()
                    .anyMatch(m -> containsDynamicOrTypeVar(m, rejectCallables));
        }
        return false;
    }
}
