package org.babblelang.analysis;

import org.babblelang.axioms.Axioms;
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
final class BuiltinAxioms {
    /** The package of the stubs, listed on the class path (BIP019-D7). */
    private static final String PACKAGE = "org/babblelang/axioms";

    private static volatile BuiltinAxioms loaded;

    /** No axioms at all : the analysis then knows nothing about any Java class. */
    static final BuiltinAxioms EMPTY = new BuiltinAxioms(List.of());

    /** A stub : its target, and its methods by name. */
    private record Stub(Class<?> target, boolean inherited, Class<?> type, Profile allMethods, Profile allConstructors,
                        Map<String, List<Method>> methods) {
        boolean appliesTo(Class<?> owner) {
            return target == owner || inherited && target.isAssignableFrom(owner);
        }
    }

    /** What a stub says about a method : its profile, and whether its result is a view or fresh. */
    record Match(Profile profile, boolean shallowMut, boolean view, boolean freshResult) {
    }

    private final List<Stub> stubs;
    private final Map<Class<?>, List<Stub>> candidates = new ConcurrentHashMap<>();

    private BuiltinAxioms(List<Stub> stubs) {
        this.stubs = stubs;
    }

    /** The axioms of the class path, loaded once. */
    static BuiltinAxioms get() {
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
                    return new Match(found.getAnnotation(Profile.class), found.isAnnotationPresent(ShallowMut.class),
                            found.isAnnotationPresent(View.class), found.isAnnotationPresent(FreshResult.class));
                }
            }
            if (stub.allMethods() != null) {
                return new Match(stub.allMethods(), false, false, false);
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
            for (Method m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Profile.class) || m.isAnnotationPresent(FreshResult.class)
                        || m.isAnnotationPresent(View.class) || m.isAnnotationPresent(ShallowMut.class)) {
                    methods.computeIfAbsent(m.getName(), n -> new ArrayList<>()).add(m);
                }
            }
            methods.replaceAll((n, l) -> List.copyOf(l));
            stubs.add(new Stub(axioms.value(), axioms.inherited(), type,
                    axioms.allMethods().length > 0 ? axioms.allMethods()[0] : null,
                    axioms.allConstructors().length > 0 ? axioms.allConstructors()[0] : null,
                    Collections.unmodifiableMap(methods)));
        }
        stubs.sort(Comparator.comparing(s -> s.type().getName()));
        return List.copyOf(stubs);
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
