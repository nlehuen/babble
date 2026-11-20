package org.babblelang.analysis;

import org.babblelang.axioms.Axioms;
import org.babblelang.axioms.BytecodeSeed;
import org.babblelang.axioms.Foldable;
import org.babblelang.axioms.FreshResult;
import org.babblelang.axioms.Profile;
import org.babblelang.axioms.ShallowMut;
import org.babblelang.axioms.View;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The built-in axioms (BIP019) : what the analysis assumes about Java classes it cannot see into,
 * read from the stubs of {@code org.babblelang.axioms}. This class only finds and matches them ;
 * every assumption is in a stub.
 */
public final class BuiltinAxioms {
    /** The package of the stubs, listed on the class path (BIP019-D7). */
    private static final String PACKAGE = "org/babblelang/axioms";

    private static volatile BuiltinAxioms loaded;
    /** Axioms that replace the class path's, while a test runs them (see {@link #runWith}). */
    private static volatile BuiltinAxioms override;

    /** No axioms at all : the analysis then knows nothing about any Java class. */
    static final BuiltinAxioms EMPTY = new BuiltinAxioms(List.of());

    /** A stub : its target, and its methods by name. */
    private record Stub(Class<?> target, boolean inherited, Class<?> type, Profile allMethods, Profile allConstructors,
                        Axioms.NullMarked nullMarked, Map<String, List<Method>> methods, List<Method> constructors) {
        boolean appliesTo(Class<?> owner) {
            return target != null && (target == owner || inherited && target.isAssignableFrom(owner));
        }
    }

    /**
     * What a stub says about a method : the stub's method, when it lists it, and its profile (its
     * own, or the stub's profile of all its target's methods).
     */
    record Match(Method stubMethod, Profile profile) {
        boolean shallowMut() {
            return stubMethod != null && stubMethod.isAnnotationPresent(ShallowMut.class);
        }

        boolean view() {
            return stubMethod != null && stubMethod.isAnnotationPresent(View.class);
        }

        boolean freshResult() {
            return stubMethod != null && stubMethod.isAnnotationPresent(FreshResult.class);
        }
    }

    private final List<Stub> stubs;
    private final Map<Class<?>, List<Stub>> candidates = new ConcurrentHashMap<>();
    /** The meaning of annotations, by their class's name. */
    private final Map<String, org.babblelang.axioms.AnnotationMeaning> meanings = new HashMap<>();

    private BuiltinAxioms(List<Stub> stubs) {
        this.stubs = stubs;
        for (Stub stub : stubs) {
            Axioms axioms = stub.type().getAnnotation(Axioms.class);
            org.babblelang.axioms.AnnotationMeaning meaning = stub.type().getAnnotation(org.babblelang.axioms.AnnotationMeaning.class);
            if (!axioms.targetName().isEmpty() && meaning != null) {
                meanings.put(axioms.targetName(), meaning);
            }
        }
    }

    /** The axioms of the class path, loaded once. */
    static BuiltinAxioms get() {
        BuiltinAxioms replaced = override;
        if (replaced != null) {
            return replaced;
        }
        BuiltinAxioms axioms = loaded;
        if (axioms == null) {
            synchronized (BuiltinAxioms.class) {
                axioms = loaded;
                if (axioms == null) {
                    axioms = new BuiltinAxioms(load(BuiltinAxioms.class.getClassLoader()));
                    loaded = axioms;
                }
            }
        }
        return axioms;
    }

    /**
     * For tests : runs {@code action} with {@code axioms} instead of the class path's, such as
     * {@link #EMPTY} to check that the analysis then assumes nothing (BIP019-P5).
     */
    static void runWith(BuiltinAxioms axioms, Runnable action) {
        override = axioms;
        BytecodeEffectAnalyzer.clearCache();
        try {
            action.run();
        } finally {
            override = null;
            BytecodeEffectAnalyzer.clearCache();
        }
    }

    /** The stub types found, for tests and documentation. */
    List<Class<?>> stubTypes() {
        return stubs.stream().<Class<?>>map(Stub::type).toList();
    }

    /**
     * The axiom about {@code owner}'s method {@code name}, with these parameter types, or any
     * overload when {@code parameterTypes} is null ; null when no stub says anything about it.
     * The most specific stub that lists the method, or states a profile of all the methods of
     * its target, wins.
     */
    Match method(Class<?> owner, String name, Class<?>[] parameterTypes) {
        if (owner == null || name == null) {
            return null;
        }
        for (Stub stub : candidates(owner)) {
            List<Method> named = stub.methods().get(name);
            if (named != null) {
                Method found = parameterTypes == null ? named.get(0) : find(named, parameterTypes);
                if (found != null) {
                    Profile own = found.getAnnotation(Profile.class);
                    return new Match(found, own != null ? own : stub.allMethods());
                }
            }
            if (stub.allMethods() != null) {
                return new Match(null, stub.allMethods());
            }
        }
        return null;
    }

    /**
     * The seed of the bytecode analyzer for a method it reaches (BIP019-P2) : what it assumes
     * instead of analysing it, or null.
     */
    public static EffectSet bytecodeSeed(Class<?> owner, String name, Class<?>[] parameterTypes) {
        Match match = get().method(owner, name, parameterTypes);
        BytecodeSeed seed = match != null && match.stubMethod() != null
                ? match.stubMethod().getAnnotation(BytecodeSeed.class) : null;
        if (seed == null) {
            return null;
        }
        java.util.EnumSet<org.babblelang.effects.Effect> effects = java.util.EnumSet.noneOf(org.babblelang.effects.Effect.class);
        Collections.addAll(effects, seed.value());
        return EffectSet.ofInferred(effects, false, true, false, true);
    }

    /** Whether the optimizer may treat a call of {@code method} as pure, nothrow and terminating, and fold it (BIP019-P2). */
    public static boolean isFoldable(Method method) {
        return stubAnnotation(method, Foldable.class) != null;
    }

    /** Whether {@code method} is idempotent, as the optimizer assumes (BIP019-P2). */
    public static boolean isIdempotent(Method method) {
        return stubAnnotation(method, org.babblelang.effects.Idempotent.class) != null;
    }

    private static <A extends java.lang.annotation.Annotation> A stubAnnotation(Method method, Class<A> annotation) {
        if (method == null) {
            return null;
        }
        Match match = get().method(method.getDeclaringClass(), method.getName(), method.getParameterTypes());
        return match != null && match.stubMethod() != null ? match.stubMethod().getAnnotation(annotation) : null;
    }

    /** The stub method mirroring {@code owner}'s constructor with these parameter types, or null. */
    Method constructorMirror(Class<?> owner, Class<?>[] parameterTypes) {
        if (owner == null) {
            return null;
        }
        for (Stub stub : candidates(owner)) {
            if (!stub.constructors().isEmpty()) {
                Method found = find(stub.constructors(), parameterTypes);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * What an axiom says of the deep immutability of {@code type}'s instances (its own stub's
     * {@code @DeeplyImmutable}, never a supertype's), or null (BIP019-P3).
     */
    org.babblelang.axioms.DeeplyImmutable deeplyImmutable(Class<?> type) {
        for (Stub stub : stubs) {
            if (stub.target() != null && stub.target() == type) {
                return stub.type().getAnnotation(org.babblelang.axioms.DeeplyImmutable.class);
            }
        }
        return null;
    }

    /**
     * What the annotation named {@code annotationClassName} means, by an axiom about it
     * (BIP019-D10), or null when no axiom gives it a meaning.
     */
    org.babblelang.axioms.AnnotationMeaning annotationMeaning(String annotationClassName) {
        return meanings.get(annotationClassName);
    }

    /** Whether the signatures of {@code owner} are null-marked by an axiom (BIP019-P3). */
    boolean nullMarked(Class<?> owner) {
        for (Stub stub : stubs) {
            if (stub.target() == null) {
                continue;
            }
            if (stub.nullMarked() == Axioms.NullMarked.TARGET && stub.target() == owner
                    || stub.nullMarked() == Axioms.NullMarked.SUBTYPES && stub.target().isAssignableFrom(owner)) {
                return true;
            }
        }
        return false;
    }

    /** The nullness an axiom states for {@code method}'s result, or null. */
    JavaNullability.Nullness returnNullness(Method method) {
        Match match = method(method.getDeclaringClass(), method.getName(), method.getParameterTypes());
        return match != null && match.stubMethod() != null ? nullness(match.stubMethod().getAnnotations()) : null;
    }

    /** The nullness an axiom states for parameter {@code index} of {@code executable}, or null. */
    JavaNullability.Nullness parameterNullness(java.lang.reflect.Executable executable, int index) {
        Method mirror;
        if (executable instanceof Method m) {
            Match match = method(m.getDeclaringClass(), m.getName(), m.getParameterTypes());
            mirror = match != null ? match.stubMethod() : null;
        } else {
            mirror = constructorMirror(executable.getDeclaringClass(), executable.getParameterTypes());
        }
        if (mirror == null || index < 0 || index >= mirror.getParameterCount()) {
            return null;
        }
        return nullness(mirror.getParameterAnnotations()[index]);
    }

    private static JavaNullability.Nullness nullness(java.lang.annotation.Annotation[] annotations) {
        for (java.lang.annotation.Annotation a : annotations) {
            if (a instanceof org.babblelang.axioms.NonNull) {
                return JavaNullability.Nullness.NON_NULL;
            }
            if (a instanceof org.babblelang.axioms.Nullable) {
                return JavaNullability.Nullness.NULLABLE;
            }
            if (a instanceof org.babblelang.axioms.Parametric) {
                return JavaNullability.Nullness.PARAMETRIC;
            }
        }
        return null;
    }

    /** The profile of {@code owner}'s constructors, or null when no stub states one. */
    Profile constructor(Class<?> owner) {
        if (owner == null) {
            return null;
        }
        for (Stub stub : candidates(owner)) {
            if (stub.allConstructors() != null) {
                return stub.allConstructors();
            }
        }
        return null;
    }

    /** An exact match first, then a method the given one overrides (its parameters' types are narrower). */
    private static Method find(List<Method> named, Class<?>[] parameterTypes) {
        Method override = null;
        for (Method m : named) {
            Class<?>[] declared = m.getParameterTypes();
            if (declared.length != parameterTypes.length) {
                continue;
            }
            boolean exact = true;
            boolean compatible = true;
            for (int i = 0; i < declared.length; i++) {
                if (declared[i] != parameterTypes[i]) {
                    exact = false;
                    if (declared[i].isPrimitive() || parameterTypes[i].isPrimitive()
                            || !declared[i].isAssignableFrom(parameterTypes[i])) {
                        compatible = false;
                    }
                }
            }
            if (exact) {
                return m;
            }
            if (compatible && override == null) {
                override = m;
            }
        }
        return override;
    }

    /** The stubs that apply to {@code owner}, the most specific first. */
    private List<Stub> candidates(Class<?> owner) {
        return candidates.computeIfAbsent(owner, o -> {
            List<Stub> applicable = new ArrayList<>();
            for (Stub stub : stubs) {
                if (stub.appliesTo(o)) {
                    applicable.add(stub);
                }
            }
            // A stub about a subtype of another's target comes first ; otherwise by name, for stability.
            Map<Stub, Integer> depth = new HashMap<>();
            for (Stub s : applicable) {
                int d = 0;
                for (Stub t : applicable) {
                    if (t != s && t.target() != s.target() && t.target().isAssignableFrom(s.target())) {
                        d++;
                    }
                }
                depth.put(s, d);
            }
            applicable.sort(Comparator.comparingInt((Stub s) -> -depth.get(s))
                    .thenComparing(s -> s.type().getName()));
            return List.copyOf(applicable);
        });
    }

    // ---------------------------------------------------------------- loading

    private static List<Stub> load(ClassLoader loader) {
        List<Stub> stubs = new ArrayList<>();
        for (String className : classNames(loader)) {
            Class<?> type;
            try {
                type = Class.forName(className, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                throw new IllegalStateException("Cannot load axioms " + className, e);
            }
            Axioms axioms = type.getAnnotation(Axioms.class);
            if (axioms == null || type.isAnnotation()) {
                continue;
            }
            Map<String, List<Method>> methods = new LinkedHashMap<>();
            List<Method> constructors = new ArrayList<>();
            for (Method m : type.getDeclaredMethods()) {
                if (m.isSynthetic()) {
                    continue;
                }
                if (m.isAnnotationPresent(org.babblelang.axioms.Constructor.class)) {
                    constructors.add(m);
                } else {
                    methods.computeIfAbsent(mirroredName(m), n -> new ArrayList<>()).add(m);
                }
            }
            methods.replaceAll((n, l) -> List.copyOf(l));
            Class<?> target = axioms.value() == void.class ? null : axioms.value();
            stubs.add(new Stub(target, axioms.inherited(), type,
                    axioms.allMethods().length > 0 ? axioms.allMethods()[0] : null,
                    axioms.allConstructors().length > 0 ? axioms.allConstructors()[0] : null,
                    axioms.nullMarked(), Collections.unmodifiableMap(methods), List.copyOf(constructors)));
        }
        stubs.sort(Comparator.comparing(s -> s.type().getName()));
        return List.copyOf(stubs);
    }

    /** The name of the target's method a stub method mirrors. */
    static String mirroredName(Method stubMethod) {
        org.babblelang.axioms.MethodName name = stubMethod.getAnnotation(org.babblelang.axioms.MethodName.class);
        return name != null ? name.value() : stubMethod.getName();
    }

    /** The names of the classes under {@link #PACKAGE}, in a directory or a jar of the class path. */
    private static List<String> classNames(ClassLoader loader) {
        List<String> names = new ArrayList<>();
        try {
            Enumeration<URL> roots = loader.getResources(PACKAGE);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    Path dir = Path.of(root.toURI());
                    try (Stream<Path> files = Files.walk(dir)) {
                        files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                            String relative = dir.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                            addClassName(names, PACKAGE + "/" + relative);
                        });
                    }
                } else if ("jar".equals(root.getProtocol())) {
                    JarURLConnection connection = (JarURLConnection) root.openConnection();
                    connection.setUseCaches(false);
                    try (JarFile jar = connection.getJarFile()) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            String entry = entries.nextElement().getName();
                            if (entry.startsWith(PACKAGE + "/") && entry.endsWith(".class")) {
                                addClassName(names, entry);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the axioms", e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot list the axioms", e);
        }
        Collections.sort(names);
        return names;
    }

    private static void addClassName(List<String> names, String path) {
        if (path.contains("$")) {
            return;
        }
        String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        if (!names.contains(name)) {
            names.add(name);
        }
    }
}
