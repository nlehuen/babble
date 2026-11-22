package org.babblelang.analysis;

import org.babblelang.effects.Effect;
import org.babblelang.engine.BabbleScriptEngine;
import org.babblelang.parser.BabbleParser;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Loader and immutable query registry for Babble's curated axioms ({@code org.babblelang.axioms},
 * {@code docs/design/bip019_refactoring-axioms.md} §2.4, §3.4).
 *
 * <p>Each Java package's {@code curated.ba} is loaded lazily on first use and cached per package.
 * {@link #EMPTY} knows nothing and is used by tests to verify that no assumption survives outside
 * the axioms directory.
 */
public final class BuiltinAxioms {

    static final String AXIOMS_RESOURCE_ROOT = "org/babblelang/axioms/";

    /** Empty axiom set: knows nothing about Java classes, nullness, folding, or built-ins. */
    public static final BuiltinAxioms EMPTY = new BuiltinAxioms(false, AxiomData.AxiomTable.EMPTY, Map.of());

    private static final BuiltinAxioms STANDARD = new BuiltinAxioms(true, null, null);

    private static final ThreadLocal<BuiltinAxioms> ACTIVE = new ThreadLocal<>();

    private final boolean loadFromClasspath;
    private final AxiomData.AxiomTable fixedTable;
    private final Map<String, Type.Function> fixedBuiltins;
    private final Map<String, AxiomData.AxiomTable> packageCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Boolean> nullMarkedCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Boolean> immutableCache = new ConcurrentHashMap<>();
    private volatile Map<String, Type.Function> builtinsCache;
    private volatile Set<Type.Function> builtinFnSet;
    private volatile AnnotationStubs annotationStubs;

    private record AnnotationStubs(
            Map<String, JavaNullability.Nullness> valueNullness,
            Map<String, JavaNullability.Nullness> scopeNullness) {
    }

    private BuiltinAxioms(boolean loadFromClasspath, AxiomData.AxiomTable fixedTable, Map<String, Type.Function> fixedBuiltins) {
        this.loadFromClasspath = loadFromClasspath;
        this.fixedTable = fixedTable;
        this.fixedBuiltins = fixedBuiltins;
    }

    /** The standard immutable built-in axioms loaded lazily from {@code org/babblelang/axioms/}. */
    public static BuiltinAxioms standard() {
        return STANDARD;
    }

    /** Returns the currently active {@link BuiltinAxioms} (defaults to {@link #standard()}). */
    public static BuiltinAxioms current() {
        BuiltinAxioms active = ACTIVE.get();
        return active != null ? active : STANDARD;
    }

    /** Executes {@code action} with {@code axioms} bound as {@link #current()}. */
    public static <T> T withCurrent(BuiltinAxioms axioms, Supplier<T> action) {
        BuiltinAxioms prev = ACTIVE.get();
        if (axioms == null || axioms == STANDARD) {
            if (prev == null) {
                return action.get();
            }
            ACTIVE.remove();
        } else {
            ACTIVE.set(axioms);
        }
        try {
            return action.get();
        } finally {
            if (prev == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(prev);
            }
        }
    }

    /** Executes {@code action} with {@code axioms} bound as {@link #current()}. */
    public static void runWithCurrent(BuiltinAxioms axioms, Runnable action) {
        withCurrent(axioms, () -> {
            action.run();
            return null;
        });
    }

    /** Builds a standalone {@link BuiltinAxioms} from Babble axiom source text (for tests). */
    public static BuiltinAxioms fromSource(String source, String sourceName) {
        Map<String, Type.Function> builtins = new LinkedHashMap<>();
        AxiomData.AxiomTable table = parseAxiomFile(source, sourceName, true, builtins);
        return new BuiltinAxioms(false, table, Collections.unmodifiableMap(builtins));
    }

    public boolean isEmpty() {
        return this == EMPTY || (!loadFromClasspath
                && (fixedTable == null || fixedTable.isEmpty())
                && (fixedBuiltins == null || fixedBuiltins.isEmpty()));
    }

    // ================================================================ Queries (§2.4)

    /** Curated {@link AxiomData.MemberAxiom} for a Java method, or {@code null} if none applies. */
    AxiomData.MemberAxiom methodAxiom(Class<?> owner, String methodName, Method exactMethod) {
        if (isEmpty() || owner == null) {
            return null;
        }
        return AxiomData.lookupMethod(owner, methodName, exactMethod, this::directMembers);
    }

    /** Curated {@link AxiomData.MemberAxiom} for a Java constructor, or {@code null} if none applies. */
    AxiomData.MemberAxiom constructorAxiom(Class<?> owner, Constructor<?> exactCtor) {
        if (isEmpty() || owner == null) {
            return null;
        }
        return AxiomData.lookupConstructor(owner, exactCtor, this::directMembers);
    }

    /** Inherited non-exact curated contract for Babble override checking (§3.2), or {@code null}. */
    AxiomData.MemberAxiom inheritedContract(Collection<Class<?>> javaSupertypes, String methodName, int arity) {
        if (isEmpty() || javaSupertypes == null || javaSupertypes.isEmpty()) {
            return null;
        }
        return AxiomData.lookupInheritedContract(javaSupertypes, methodName, arity, this::directMembers);
    }

    /** Curated return nullness for a Java method, or {@code null} if no curated signature specifies it. */
    JavaNullability.Nullness returnNullness(Method method) {
        if (isEmpty() || method == null) {
            return null;
        }
        AxiomData.MemberAxiom ax = methodAxiom(method.getDeclaringClass(), method.getName(), method);
        if (ax != null && ax.hasSignature() && ax.returnNullness() != null) {
            return ax.returnNullness();
        }
        return null;
    }

    /** Curated parameter nullness for a Java method/constructor, or {@code null} if none specifies it. */
    JavaNullability.Nullness parameterNullness(Executable exec, int index) {
        if (isEmpty() || exec == null || index < 0) {
            return null;
        }
        AxiomData.MemberAxiom ax = exec instanceof Method m
                ? methodAxiom(m.getDeclaringClass(), m.getName(), m)
                : exec instanceof Constructor<?> c
                ? constructorAxiom(c.getDeclaringClass(), c)
                : null;
        if (ax != null && ax.hasSignature() && index < ax.paramNullness().length) {
            return ax.paramNullness()[index];
        }
        return null;
    }

    /** Curated array-element nullness for a Java method/constructor array parameter, or {@code null} if unspecified. */
    JavaNullability.Nullness parameterElementNullness(Executable exec, int index) {
        if (isEmpty() || exec == null || index < 0) {
            return null;
        }
        AxiomData.MemberAxiom ax = exec instanceof Method m
                ? methodAxiom(m.getDeclaringClass(), m.getName(), m)
                : exec instanceof Constructor<?> c
                ? constructorAxiom(c.getDeclaringClass(), c)
                : null;
        if (ax != null && ax.hasSignature() && index < ax.paramElementNullness().length) {
            return ax.paramElementNullness()[index];
        }
        return null;
    }

    /** Whether a Java executable's declaring class is {@code nullmarked} by a curated axiom. */
    public boolean nullMarked(Executable exec) {
        return exec != null && nullMarked(exec.getDeclaringClass());
    }

    /** Whether a Java class is {@code nullmarked} by a curated axiom. */
    public boolean nullMarked(Class<?> cls) {
        if (isEmpty() || cls == null) {
            return false;
        }
        return nullMarkedCache.computeIfAbsent(cls,
                c -> AxiomData.lookupClassFlag(c, this::directClassAxiom, AxiomData.ClassAxiom::nullMarked));
    }

    /** Whether a Java class is declared {@code immutable} (deeply immutable when type args are) by a curated axiom. */
    public boolean isDeeplyImmutable(Class<?> cls) {
        if (isEmpty() || cls == null) {
            return false;
        }
        return immutableCache.computeIfAbsent(cls,
                c -> AxiomData.lookupClassFlag(c, this::directClassAxiom, AxiomData.ClassAxiom::immutable));
    }

    /** Whether a Java method is marked {@code foldable} by a curated axiom (safe to invoke at compile time). */
    public boolean isFoldable(Method method) {
        if (isEmpty() || method == null) {
            return false;
        }
        AxiomData.MemberAxiom ax = methodAxiom(method.getDeclaringClass(), method.getName(), method);
        return ax != null && ax.foldable();
    }

    /** Whether a Java method is marked {@code idempotent} by a curated axiom. */
    public boolean isIdempotent(Method method) {
        if (isEmpty() || method == null) {
            return false;
        }
        AxiomData.MemberAxiom ax = methodAxiom(method.getDeclaringClass(), method.getName(), method);
        return ax != null && ax.effects().has(Effect.IDEMPOTENT);
    }

    /** Built-in Babble function signatures from {@code builtins.ba} (empty when {@link #EMPTY}). */
    public Map<String, Type.Function> builtins() {
        if (this == EMPTY) {
            return Map.of();
        }
        if (!loadFromClasspath) {
            return fixedBuiltins != null ? fixedBuiltins : Map.of();
        }
        Map<String, Type.Function> cached = builtinsCache;
        if (cached != null) {
            return cached;
        }
        String resourcePath = AXIOMS_RESOURCE_ROOT + "builtins.ba";
        ClassLoader cl = BuiltinAxioms.class.getClassLoader();
        Map<String, Type.Function> loaded = new LinkedHashMap<>();
        try (InputStream in = cl != null ? cl.getResourceAsStream(resourcePath) : ClassLoader.getSystemResourceAsStream(resourcePath)) {
            if (in != null) {
                String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                parseAxiomFile(source, resourcePath, true, loaded);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read built-in axioms resource " + resourcePath, e);
        }
        Map<String, Type.Function> unmod = Collections.unmodifiableMap(loaded);
        Set<Type.Function> idSet = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        idSet.addAll(unmod.values());
        builtinFnSet = idSet;
        builtinsCache = unmod;
        return unmod;
    }

    /** Whether {@code fn} is a built-in function type loaded from {@code builtins.ba}. */
    boolean isBuiltinFunction(Type.Function fn) {
        if (fn == null || this == EMPTY) {
            return false;
        }
        if (!loadFromClasspath) {
            return fixedBuiltins != null && fixedBuiltins.containsValue(fn);
        }
        if (builtinFnSet == null) {
            builtins();
        }
        return builtinFnSet != null && builtinFnSet.contains(fn);
    }

    // ================================================================ Lazy Package Loading (§3.4)

    private AxiomData.ClassAxiom directClassAxiom(Class<?> cls) {
        return tableFor(cls).directClassAxiom(cls);
    }

    private List<AxiomData.MemberAxiom> directMembers(Class<?> cls) {
        return tableFor(cls).directMembers(cls);
    }

    private AxiomData.AxiomTable tableFor(Class<?> cls) {
        if (!loadFromClasspath) {
            return fixedTable != null ? fixedTable : AxiomData.AxiomTable.EMPTY;
        }
        String pkg = cls.getPackageName();
        return loadPackage(pkg);
    }

    AxiomData.AxiomTable loadPackage(String packageName) {
        if (!loadFromClasspath) {
            return fixedTable != null ? fixedTable : AxiomData.AxiomTable.EMPTY;
        }
        String key = packageName == null ? "" : packageName;
        return packageCache.computeIfAbsent(key, this::readPackageResource);
    }

    private AxiomData.AxiomTable readPackageResource(String packageName) {
        String resourcePath = packageName.isEmpty()
                ? AXIOMS_RESOURCE_ROOT + "curated.ba"
                : AXIOMS_RESOURCE_ROOT + packageName.replace('.', '/') + "/curated.ba";
        ClassLoader cl = BuiltinAxioms.class.getClassLoader();
        try (InputStream in = cl != null ? cl.getResourceAsStream(resourcePath) : ClassLoader.getSystemResourceAsStream(resourcePath)) {
            if (in == null) {
                return AxiomData.AxiomTable.EMPTY;
            }
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseAxiomFile(source, resourcePath, true, null);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read curated axioms resource " + resourcePath, e);
        }
    }

    static AxiomData.AxiomTable parseAxiomFile(String source, String sourceName, boolean curated,
                                               Map<String, Type.Function> babbleBuiltinsOut) {
        BabbleParser.FileContext file;
        try {
            file = BabbleScriptEngine.parseSource(source, sourceName);
        } catch (javax.script.ScriptException e) {
            throw new IllegalStateException("Failed to parse axiom file " + sourceName + " : " + e.getMessage(), e);
        }
        AxiomData.Builder builder = new AxiomData.Builder();
        List<String> errors = new ArrayList<>();
        AxiomData.ErrorSink sink = (msg, at) -> {
            int line = at != null && at.getStart() != null ? at.getStart().getLine() : 0;
            errors.add(sourceName + ":" + line + " : " + msg);
        };
        for (BabbleParser.ExpressionContext expr : file.expression()) {
            if (!(expr instanceof BabbleParser.AxiomExpressionContext ax)) {
                sink.report("Expected axiom declaration", expr);
                continue;
            }
            if (AxiomData.isBabbleNameAxiom(ax)) {
                String bName = AxiomData.babbleName(ax);
                Type.Function fn = AxiomData.parseBabbleAxiomFunction(ax, t -> AxiomData.resolveStandaloneType(t, sink), sink);
                if (fn != null && babbleBuiltinsOut != null) {
                    if (babbleBuiltinsOut.containsKey(bName)) {
                        sink.report("Duplicate built-in axiom : " + bName, ax);
                    } else {
                        babbleBuiltinsOut.put(bName, fn);
                    }
                }
                continue;
            }
            AxiomData.ParsedAxiom parsed = AxiomData.parse(ax, null, curated, sink);
            if (parsed == null) {
                continue;
            }
            if (parsed.classAxiom() != null) {
                if (builder.hasClassAxiom(parsed.classAxiom().targetClass())) {
                    sink.report("Duplicate class axiom : " + parsed.classAxiom().targetClass().getName(), ax);
                } else {
                    for (AxiomData.MemberAxiom m : parsed.classAxiom().members()) {
                        if (builder.hasMemberConflict(m)) {
                            sink.report("Duplicate axiom for member : " + m.ownerClass().getName() + "." + m.name(), ax);
                        }
                    }
                    builder.addClassAxiom(parsed.classAxiom());
                }
            } else if (parsed.memberAxiom() != null) {
                if (builder.hasMemberConflict(parsed.memberAxiom())) {
                    sink.report("Duplicate axiom for member : " + parsed.memberAxiom().ownerClass().getName()
                            + "." + parsed.memberAxiom().name(), ax);
                } else {
                    builder.addMemberAxiom(parsed.memberAxiom());
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Invalid curated axiom file " + sourceName + ":\n" + String.join("\n", errors));
        }
        return builder.build();
    }

    /** Nullness meaning of a third-party annotation binary name, or {@code UNSPECIFIED}. */
    JavaNullability.Nullness annotationNullness(String annotationBinaryName) {
        if (!loadFromClasspath || annotationBinaryName == null) {
            return JavaNullability.Nullness.UNSPECIFIED;
        }
        return loadAnnotationStubs().valueNullness().getOrDefault(annotationBinaryName, JavaNullability.Nullness.UNSPECIFIED);
    }

    /** Null-marked scope meaning of a third-party annotation binary name, or {@code UNSPECIFIED}. */
    JavaNullability.Nullness annotationNullMarkedScope(String annotationBinaryName) {
        if (!loadFromClasspath || annotationBinaryName == null) {
            return JavaNullability.Nullness.UNSPECIFIED;
        }
        return loadAnnotationStubs().scopeNullness().getOrDefault(annotationBinaryName, JavaNullability.Nullness.UNSPECIFIED);
    }

    private AnnotationStubs loadAnnotationStubs() {
        AnnotationStubs cached = annotationStubs;
        if (cached != null) {
            return cached;
        }
        Map<String, JavaNullability.Nullness> values = new HashMap<>();
        Map<String, JavaNullability.Nullness> scopes = new HashMap<>();
        try {
            ClassLoader cl = BuiltinAxioms.class.getClassLoader();
            for (String clsName : discoverAnnotationStubs()) {
                Class<?> stub = Class.forName(clsName, false, cl);
                org.babblelang.axioms.Axioms ax = stub.getAnnotation(org.babblelang.axioms.Axioms.class);
                if (ax == null || ax.targetName().isEmpty()) {
                    continue;
                }
                String target = ax.targetName();
                if (stub.isAnnotationPresent(org.babblelang.axioms.MeansNonNull.class)) {
                    values.put(target, JavaNullability.Nullness.NON_NULL);
                } else if (stub.isAnnotationPresent(org.babblelang.axioms.MeansNullable.class)) {
                    values.put(target, JavaNullability.Nullness.NULLABLE);
                }
                if (stub.isAnnotationPresent(org.babblelang.axioms.MeansNullMarked.class)) {
                    scopes.put(target, JavaNullability.Nullness.NON_NULL);
                } else if (stub.isAnnotationPresent(org.babblelang.axioms.MeansNullUnmarked.class)) {
                    scopes.put(target, JavaNullability.Nullness.NULLABLE);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to discover annotation axiom stubs", e);
        }
        AnnotationStubs built = new AnnotationStubs(Collections.unmodifiableMap(values), Collections.unmodifiableMap(scopes));
        annotationStubs = built;
        return built;
    }

    /** Lists binary names of all {@code *Axioms} Java stub classes under {@code org/babblelang/axioms/}. */
    public static Set<String> discoverAnnotationStubs() throws IOException {
        Set<String> stubs = new TreeSet<>();
        ClassLoader cl = BuiltinAxioms.class.getClassLoader();
        Enumeration<URL> roots = cl.getResources(AXIOMS_RESOURCE_ROOT);
        while (roots.hasMoreElements()) {
            URL url = roots.nextElement();
            if ("file".equals(url.getProtocol())) {
                try {
                    Path dir = Path.of(url.toURI());
                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.filter(p -> {
                            String fn = p.getFileName().toString();
                            return fn.endsWith("Axioms.class") && !fn.equals("Axioms.class");
                        }).forEach(p -> {
                            Path rel = dir.relativize(p);
                            String s = rel.toString().replace('\\', '/');
                            String cls = "org.babblelang.axioms." + s.substring(0, s.length() - ".class".length()).replace('/', '.');
                            stubs.add(cls);
                        });
                    }
                } catch (Exception e) {
                    throw new IOException("Failed to scan annotation axiom stubs in " + url, e);
                }
            } else if ("jar".equals(url.getProtocol())) {
                JarURLConnection conn = (JarURLConnection) url.openConnection();
                try (JarFile jar = conn.getJarFile()) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith(AXIOMS_RESOURCE_ROOT) && name.endsWith("Axioms.class")
                                && !name.equals(AXIOMS_RESOURCE_ROOT + "Axioms.class")) {
                            String cls = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                            stubs.add(cls);
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableSet(stubs);
    }
}
