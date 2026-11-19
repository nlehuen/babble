package org.babblelang.analysis;

import org.babblelang.runtime.JavaTypes;

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

/**
 * Reflection and JLS type-bridge helpers ({@code docs/design/bip010_type-checker-simplification.md} §4.6):
 * {@code java.lang.reflect} translation (with F-bounded wildcard cycle guards), generic supertype
 * environments, wildcard bound checks, SAM extraction, JPMS/bytecode accessibility, and single-walk
 * boxed/widening overload applicability and {@code mostSpecific} selection.
 */
final class JavaTypeBridge {
    private JavaTypeBridge() {
    }

    /** The Babble type of a Java class: Babble's 9 core scalars are Java's primitives/wrappers/String. */
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
            return new Type.JavaArray(java, fromJava(java.getComponentType()));
        }
        if (java == Object.class) {
            return Type.ANY;
        }
        if (java.isPrimitive()) {
            return new Type.JavaInstance(JavaTypes.box(java));
        }
        if (java.getTypeParameters().length > 0) {
            return new Type.JavaInstance(java, Collections.nCopies(java.getTypeParameters().length, Type.NULLABLE_ANY));
        }
        return new Type.JavaInstance(java);
    }

    /** Translates a reflected Java class according to its nullability (unannotated references become {@code T?}). */
    static Type fromJavaReflected(Class<?> java, JavaNullability.Nullness nullness) {
        if (java == void.class) {
            return Type.NULL;
        }
        if (java.isArray()) {
            Type comp = fromJavaReflected(java.getComponentType(),
                    java.getComponentType().isPrimitive() ? JavaNullability.Nullness.NON_NULL : JavaNullability.Nullness.UNSPECIFIED);
            Type arr = new Type.JavaArray(java, comp);
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

    /** The only public method of {@code owner} named {@code name} with {@code arity} parameters, or null. */
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
                if (sup instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw) {
                    TypeVariable<?>[] rawParams = raw.getTypeParameters();
                    java.lang.reflect.Type[] rawArgs = pt.getActualTypeArguments();
                    for (int i = 0; i < Math.min(rawParams.length, rawArgs.length); i++) {
                        env.putIfAbsent(rawParams[i], fromJavaType(rawArgs[i], null, env, false, false,
                                JavaNullability.Nullness.UNSPECIFIED, true, activeWildcards));
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

    static Type fromJavaType(java.lang.reflect.Type type, Map<TypeVariable<?>, Type> env,
                             boolean widenTopTypeVar, boolean forParam) {
        return fromJavaType(type, null, env, widenTopTypeVar, forParam,
                JavaNullability.Nullness.UNSPECIFIED, false,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    static Type fromJavaType(java.lang.reflect.Type type, AnnotatedType annType, Map<TypeVariable<?>, Type> env,
                             boolean widenTopTypeVar, boolean forParam,
                             JavaNullability.Nullness topNullness, boolean nullMarked) {
        return fromJavaType(type, annType, env, widenTopTypeVar, forParam, topNullness, nullMarked,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static JavaNullability.Nullness effectiveNullness(JavaNullability.Nullness explicit,
                                                              AnnotatedType annType, boolean nullMarked) {
        if (explicit != null && explicit != JavaNullability.Nullness.UNSPECIFIED) {
            return explicit;
        }
        JavaNullability.Nullness scanned = JavaNullability.scanAnnotatedType(annType);
        if (scanned != JavaNullability.Nullness.UNSPECIFIED) {
            return scanned;
        }
        return nullMarked ? JavaNullability.Nullness.NON_NULL : JavaNullability.Nullness.UNSPECIFIED;
    }

    private static Type fromJavaType(java.lang.reflect.Type type, Map<TypeVariable<?>, Type> env,
                                     boolean widenTopTypeVar, boolean forParam,
                                     Set<WildcardType> activeWildcards) {
        return fromJavaType(type, null, env, widenTopTypeVar, forParam,
                JavaNullability.Nullness.UNSPECIFIED, false, activeWildcards);
    }

    private static Type fromJavaType(java.lang.reflect.Type type, AnnotatedType annType, Map<TypeVariable<?>, Type> env,
                                     boolean widenTopTypeVar, boolean forParam,
                                     JavaNullability.Nullness topNullness, boolean nullMarked,
                                     Set<WildcardType> activeWildcards) {
        JavaNullability.Nullness eff = effectiveNullness(topNullness, annType, nullMarked);
        if (type instanceof TypeVariable<?> tv) {
            Type resolved = env.get(tv);
            if (resolved == null) {
                resolved = Type.NULLABLE_ANY;
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
                    resolved = fromJavaType(wt.getLowerBounds()[0], null, env, false, false,
                            JavaNullability.Nullness.UNSPECIFIED, nullMarked, activeWildcards);
                } else if (wt.getUpperBounds().length > 0 && wt.getUpperBounds()[0] != Object.class) {
                    resolved = fromJavaType(wt.getUpperBounds()[0], null, env, false, false,
                            JavaNullability.Nullness.UNSPECIFIED, nullMarked, activeWildcards);
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
            AnnotatedType[] annArgs = annType instanceof AnnotatedParameterizedType apt
                    ? apt.getAnnotatedActualTypeArguments() : null;
            List<Type> args = new ArrayList<>(rawArgs.length);
            for (int i = 0; i < rawArgs.length; i++) {
                AnnotatedType argAnn = annArgs != null && i < annArgs.length ? annArgs[i] : null;
                args.add(fromJavaType(rawArgs[i], argAnn, env, false, forParam,
                        JavaNullability.Nullness.UNSPECIFIED, nullMarked, activeWildcards));
            }
            Type base = raw.getTypeParameters().length == args.size()
                    ? new Type.JavaInstance(raw, args)
                    : fromJava(raw);
            return eff == JavaNullability.Nullness.NON_NULL ? base : Type.nullable(base);
        }
        if (type instanceof GenericArrayType gat) {
            AnnotatedType compAnn = annType instanceof AnnotatedArrayType aat ? aat.getAnnotatedGenericComponentType() : null;
            Type component = fromJavaType(gat.getGenericComponentType(), compAnn, env, false, false,
                    JavaNullability.Nullness.UNSPECIFIED, nullMarked, activeWildcards);
            Class<?> arrayClass = Array.newInstance(erasedClass(gat.getGenericComponentType()), 0).getClass();
            Type arr = new Type.JavaArray(arrayClass, component);
            return eff == JavaNullability.Nullness.NON_NULL ? arr : Type.nullable(arr);
        }
        if (type instanceof Class<?> cls) {
            if (cls.isArray()) {
                AnnotatedType compAnn = annType instanceof AnnotatedArrayType aat ? aat.getAnnotatedGenericComponentType() : null;
                Class<?> compCls = cls.getComponentType();
                Type comp = compCls.isPrimitive()
                        ? fromJava(compCls)
                        : fromJavaReflected(compCls, effectiveNullness(JavaNullability.Nullness.UNSPECIFIED, compAnn, nullMarked));
                Type arr = new Type.JavaArray(cls, comp);
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

    static boolean satisfiesJavaBounds(Type actual, TypeVariable<?> param,
                                       Map<TypeVariable<?>, Type> env, TypeAlgebra algebra) {
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

    private static boolean satisfiesJavaBound(Type actual, java.lang.reflect.Type bound,
                                            Map<TypeVariable<?>, Type> env, TypeAlgebra algebra) {
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
                        Type lower = fromJavaType(wt.getLowerBounds()[0], null, env, false, false,
                                JavaNullability.Nullness.UNSPECIFIED, true);
                        if (!algebra.relate(lower, a, TypeAlgebra.RelateMode.ASSIGNABLE, null)) {
                            return false;
                        }
                    } else if (wt.getUpperBounds().length > 0 && wt.getUpperBounds()[0] != Object.class) {
                        Type upper = fromJavaType(wt.getUpperBounds()[0], null, env, false, false,
                                JavaNullability.Nullness.UNSPECIFIED, true);
                        if (!algebra.relate(a, upper, TypeAlgebra.RelateMode.SUBTYPE, null)) {
                            return false;
                        }
                    }
                } else {
                    Type expectedArg = fromJavaType(ptArg, null, env, false, false,
                            JavaNullability.Nullness.UNSPECIFIED, true);
                    if (!algebra.relate(a, expectedArg, TypeAlgebra.RelateMode.SUBTYPE, null)) {
                        return false;
                    }
                }
            }
            return true;
        }
        Type expectedBound = fromJavaType(bound, null, env, false, false,
                JavaNullability.Nullness.UNSPECIFIED, true);
        return algebra.relate(actual, expectedBound, TypeAlgebra.RelateMode.ASSIGNABLE, null);
    }

    /**
     * The type of what a Java method returns, or a Java field holds: an instance of its
     * class (whose members are checked), or the closest accessible supertype / {@code any}
     * when the concrete class is package-private or non-public.
     */
    static Type javaResult(java.lang.reflect.Type genericType, Map<TypeVariable<?>, Type> env, TypeAlgebra algebra) {
        return makeAccessible(fromJavaType(genericType, env, true, false), algebra);
    }

    static Type javaMethodResult(Method method, Map<TypeVariable<?>, Type> env, TypeAlgebra algebra) {
        Type raw = fromJavaType(method.getGenericReturnType(), method.getAnnotatedReturnType(), env,
                true, false, JavaNullability.returnNullness(method), JavaNullability.isNullMarked(method));
        return makeAccessible(raw, algebra);
    }

    static Type javaExecutableParamType(Executable exec, int index, Map<TypeVariable<?>, Type> env) {
        java.lang.reflect.Type[] genericParams = exec.getGenericParameterTypes();
        AnnotatedType[] annParams = exec.getAnnotatedParameterTypes();
        AnnotatedType ann = index < annParams.length ? annParams[index] : null;
        return fromJavaType(genericParams[index], ann, env, false, true,
                JavaNullability.parameterNullness(exec, index), JavaNullability.isNullMarked(exec));
    }

    static Type makeAccessible(Type type, TypeAlgebra algebra) {
        if (type instanceof Type.Union union) {
            return Type.union(union.members().stream().map(m -> makeAccessible(m, algebra)).toList());
        }
        if (type instanceof Type.JavaInstance ji && !ji.type().isInterface() && !JavaTypes.isAccessible(ji.type())) {
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
        Type returnType = fromJavaType(sam.getGenericReturnType(), sam.getAnnotatedReturnType(), env,
                false, false, JavaNullability.returnNullness(sam), JavaNullability.isNullMarked(sam));
        Type.Function raw = new Type.Function(params, returnType);
        return effectChecker != null ? effectChecker.applySamJavaEffects(sam, raw) : raw;
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

    /** A class by its qualified name, trying each trailing dot as a nested class ({@code a.B.C} as {@code a.B$C}) ; null if none. */
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
     * A Java member read through a class or an instance : its {@code type}, or the {@code error}
     * to report ; {@code checked} when it is a field whose generic type needs a runtime check
     * ({@code T9}), and the static {@code fieldTarget} of a public, accessible field.
     */
    record JavaMember(Type type, String error, boolean checked, ScopeAnalysis.JavaFieldTarget fieldTarget) {
    }

    /**
     * A method or a public field of a Java class, as {@code JavaClass} looks them up : through
     * the class itself, only a static field.
     */
    static JavaMember javaMember(Class<?> java, List<Type> typeArgs, String name, boolean onClass, TypeAlgebra algebra) {
        if (hasJavaMethod(java, name)) {
            return new JavaMember(new Type.JavaMethod(java, onClass ? List.of() : typeArgs, name), null, false, null);
        }
        if (java.isInterface() && !onClass && hasJavaMethod(Object.class, name)) {
            return new JavaMember(new Type.JavaMethod(Object.class, List.of(), name), null, false, null);
        }
        Field field = javaField(java, name);
        if (field == null) {
            return new JavaMember(Type.ERROR, "No such name in " + java.getCanonicalName() + " : " + name, false, null);
        }
        boolean isStatic = Modifier.isStatic(field.getModifiers());
        if (onClass && !isStatic) {
            return new JavaMember(Type.ERROR, "Not a static field : " + java.getCanonicalName() + "." + name, false, null);
        }
        Map<TypeVariable<?>, Type> env = onClass ? Map.of() : javaTypeEnv(java, typeArgs);
        java.lang.reflect.Type genericType = field.getGenericType();
        Type fieldType = makeAccessible(fromJavaType(genericType, field.getAnnotatedType(), env,
                true, false, JavaNullability.fieldNullness(field), false), algebra);
        boolean checked = !field.getType().isPrimitive() && !Type.containsNull(fieldType);
        ScopeAnalysis.JavaFieldTarget target = Modifier.isPublic(field.getModifiers()) && JavaTypes.isAccessible(field.getDeclaringClass())
                ? new ScopeAnalysis.JavaFieldTarget(field, isStatic, !isStatic) : null;
        return new JavaMember(fieldType, null, checked, target);
    }

    /** A Java field an assignment writes. */
    record AssignedField(Field field) {
        /** Its type read through {@code receiver} ; {@code widenTopTypeVar} for the value read before a compound assignment. */
        Type type(Type receiver, boolean widenTopTypeVar, TypeAlgebra algebra) {
            return javaFieldType(receiver, field, widenTopTypeVar, algebra);
        }

        /** The error assigning a function to a functional-interface field, which needs an explicit conversion ; null otherwise. */
        String functionError(Type value, String name) {
            if (TypeAlgebra.isCallable(value) && JavaTypes.isFunctionalInterface(field.getType())) {
                return "Cannot assign a function to " + field.getDeclaringClass().getCanonicalName() + "." + name
                        + " : convert it first, as " + field.getType().getSimpleName() + "(f)";
            }
            return null;
        }

        /** The static write target for a value of that type, or null when it must go through {@code JavaClass.assign}. */
        ScopeAnalysis.JavaFieldTarget target(Type value) {
            if (Modifier.isFinal(field.getModifiers()) || !Modifier.isPublic(field.getModifiers())
                    || !JavaTypes.isAccessible(field.getDeclaringClass())
                    || !guaranteedApplicable(field.getType(), value) || !supportedStaticJavaArg(field.getType(), value)) {
                return null;
            }
            boolean isStatic = Modifier.isStatic(field.getModifiers());
            return new ScopeAnalysis.JavaFieldTarget(field, isStatic, !isStatic);
        }
    }

    /**
     * The field {@code receiver.name = value} assigns when the receiver is a Java class, object or
     * array : the field, or the error {@code JavaClass.assign} would report (as {@code [error]}).
     * Null when the receiver isn't Java.
     */
    static Object assignedField(Type receiver, String name) {
        if (receiver instanceof Type.JavaArray array) {
            String owner = array.javaClass().getCanonicalName();
            return "length".equals(name) ? "Cannot assign final field : " + owner + "." + name
                    : "No such field in " + owner + " : " + name;
        }
        boolean onClass = receiver instanceof Type.JavaClassValue;
        Class<?> java = onClass ? ((Type.JavaClassValue) receiver).type()
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

    static Type javaFieldType(Type receiver, Field field, boolean widenTopTypeVar, TypeAlgebra algebra) {
        Map<TypeVariable<?>, Type> env = receiver instanceof Type.JavaInstance ji
                ? javaTypeEnv(ji.type(), ji.typeArguments())
                : Map.of();
        return makeAccessible(fromJavaType(field.getGenericType(), field.getAnnotatedType(), env,
                widenTopTypeVar, false, JavaNullability.fieldNullness(field), false), algebra);
    }

    static Class<?> javaClassOf(Type type) {
        if (type instanceof Type.Simple simple) {
            return simple.javaClass();
        }
        if (type instanceof Type.JavaArray array) {
            return array.javaClass();
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
            List<E> candidates, int index, boolean isLambdaArg, Map<TypeVariable<?>, Type> receiverEnv) {
        if (candidates.isEmpty()) {
            return null;
        }
        Set<Type> paramTypes = new LinkedHashSet<>();
        for (E candidate : candidates) {
            Map<TypeVariable<?>, Type> env = new HashMap<>(receiverEnv);
            candidateSolvingVars(candidate).forEach(env::putIfAbsent);
            paramTypes.add(javaExecutableParamType(candidate, index, env));
        }
        if (paramTypes.size() == 1) {
            return paramTypes.iterator().next();
        }
        if (isLambdaArg) {
            Type samType = null;
            for (Type candidateType : paramTypes) {
                Type base = Type.withoutNull(candidateType);
                if (base instanceof Type.JavaInstance ji && JavaTypes.isFunctionalInterface(ji.type())) {
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

    static boolean applicableGeneric(Executable candidate, int index, Type argument,
                                     Map<TypeVariable<?>, Type> receiverEnv, TypeAlgebra algebra) {
        if (!applicable(candidate.getParameterTypes()[index], argument, algebra)) {
            return false;
        }
        if (TypeChecker.isUnresolved(argument)) {
            return true;
        }
        java.lang.reflect.Type genericParam = candidate.getGenericParameterTypes()[index];
        if (genericParam instanceof Class<?>) {
            return true;
        }
        Map<TypeVariable<?>, Type.TypeVar> tvToSyn = candidateSolvingVars(candidate);
        Map<TypeVariable<?>, Type> env = new HashMap<>(receiverEnv);
        env.putAll(tvToSyn);
        Type paramPattern = javaExecutableParamType(candidate, index, env);
        if (!paramPattern.anyMatch(t -> t instanceof Type.TypeVar tv && tv.owner() == null)) {
            if (genericParam instanceof ParameterizedType pt && hasWildcard(pt)
                    && !(Type.withoutNull(argument) instanceof Type.Function)) {
                if (!Type.containsNull(paramPattern) && Type.containsNull(argument)) {
                    return false;
                }
                return satisfiesJavaBound(Type.withoutNull(argument), pt, env, algebra);
            }
            return algebra.relate(argument, paramPattern, TypeAlgebra.RelateMode.ASSIGNABLE, null);
        }
        Type unwrappedPattern = Type.withoutNull(paramPattern);
        if (argument instanceof Type.Function aFn && unwrappedPattern instanceof Type.JavaInstance pJava
                && JavaTypes.isFunctionalInterface(pJava.type())) {
            Type.Function sam = samFunctionType(pJava, null);
            if (sam != null) {
                if (aFn.parameters().size() < sam.parameters().size()) {
                    return false;
                }
                for (int i = sam.parameters().size(); i < aFn.parameters().size(); i++) {
                    if (!aFn.parameters().get(i).optional()) {
                        return false;
                    }
                }
                return true;
            }
        }
        Set<Type.TypeVar> solving = Set.copyOf(tvToSyn.values());
        TypeAlgebra.ConstraintStore store = algebra.store(solving, true);
        return algebra.relate(argument, paramPattern, TypeAlgebra.RelateMode.ASSIGNABLE, store);
    }

    private static boolean hasWildcard(ParameterizedType pt) {
        for (java.lang.reflect.Type arg : pt.getActualTypeArguments()) {
            if (arg instanceof WildcardType || (arg instanceof ParameterizedType nested && hasWildcard(nested))) {
                return true;
            }
        }
        return false;
    }

    /** The overload more specific than all the others, as {@code Overloads.resolve} finds it, or null. */
    static <E extends Executable> E mostSpecific(List<E> applicable) {
        for (E candidate : applicable) {
            if (applicable.stream().allMatch(other -> other == candidate || isMoreSpecific(candidate, other))) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isMoreSpecific(Executable candidate, Executable other) {
        Class<?>[] candidateTypes = candidate.getParameterTypes();
        Class<?>[] otherTypes = other.getParameterTypes();
        for (int i = 0; i < candidateTypes.length; i++) {
            Class<?> from = JavaTypes.box(candidateTypes[i]);
            Class<?> to = JavaTypes.box(otherTypes[i]);
            if (!to.isAssignableFrom(from) && !JavaTypes.isWidening(from, to)) {
                return false;
            }
        }
        return true;
    }

    /** Whether an argument of a static type can be passed to a Java parameter (as {@code Overloads} decides at runtime). */
    static boolean applicable(Class<?> parameter, Type argument, TypeAlgebra algebra) {
        if (TypeChecker.isUnresolved(argument)) {
            return true;
        }
        if (argument instanceof Type.Union union) {
            return union.members().stream().allMatch(member -> applicable(parameter, member, algebra));
        }
        if (argument == Type.NULL) {
            return !parameter.isPrimitive();
        }
        Class<?> java = javaClassOf(argument);
        if (java != null) {
            return JavaTypes.box(parameter).isAssignableFrom(java)
                    || parameter.isPrimitive() && JavaTypes.isWidening(java, JavaTypes.box(parameter));
        }
        if (argument instanceof Type.Instance instance) {
            return parameter == Object.class || TypeAlgebra.implementsJava(instance.node(), parameter);
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
        if (type == null || TypeChecker.isUnresolved(type) || type == Type.ANY || type instanceof Type.TypeVar) {
            return true;
        }
        if (rejectCallables && (type instanceof Type.JavaMethod || type == Type.PRINT)) {
            return true;
        }
        if (type instanceof Type.Union union) {
            return union.members().stream().anyMatch(m -> containsDynamicOrTypeVar(m, rejectCallables));
        }
        return false;
    }
}
