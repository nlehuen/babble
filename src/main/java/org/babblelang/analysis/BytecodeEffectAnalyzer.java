package org.babblelang.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.babblelang.effects.Effect;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * ASM bytecode static effect, alias, reads, and nullness analyzer for Java methods and constructors
 * ({@code docs/design/bip019_refactoring-axioms.md} §5).
 *
 * <p>Runs lazily on demand as tier 7 (below curated axioms, on by default), uses an ASM {@link
 * Analyzer} provenance + nullness value lattice over a budget-bounded Tarjan SCC call-graph fixed
 * point, applies curated axioms to open/native calls it encounters, and caches converged results in
 * memory per {@code (BuiltinAxioms, ClassLoader)}.
 */
public final class BytecodeEffectAnalyzer {

    /** Provenance bit flags on a JVM value. */
    private static final long PROV_THIS = 1L << 0;

    private static final long PROV_FRESH = 1L << 1;
    private static final long PROV_EXTERNAL = 1L << 2;
    private static final long PROV_CONST = 1L << 3;
    private static final long PROV_STORED_ELEM = 1L << 4;
    private static final int PROV_PARAM_SHIFT = 5;
    private static final int MAX_TRACKED_PARAMS = 58;

    private static final int MAX_CALL_GRAPH_NODES = 64;
    private static final int MAX_CALL_DEPTH = 12;
    private static final int MAX_SCC_ITERATIONS = 8;

    /** Full inferred summary for one Java method or constructor. */
    public record Result(
            EffectSet effects,
            boolean mutReceiver,
            boolean borrowReceiver,
            boolean[] mutParams,
            boolean[] borrowParams,
            boolean readsReceiver,
            boolean[] readParams,
            boolean returnNonNull,
            boolean fresh,
            boolean view,
            boolean shallow,
            List<String> reasons) {

        public static final Result UNCONSTRAINED =
                new Result(
                        EffectSet.EMPTY,
                        false,
                        false,
                        new boolean[0],
                        new boolean[0],
                        true,
                        new boolean[0],
                        false,
                        false,
                        false,
                        false,
                        List.of("unconstrained"));

        public Result(EffectSet effects, List<String> reasons) {
            this(
                    effects,
                    false,
                    effects.isPure(),
                    new boolean[0],
                    new boolean[0],
                    true,
                    new boolean[0],
                    false,
                    effects.uniqueResult(),
                    false,
                    false,
                    reasons);
        }

        static Result unconstrained(int paramCount, boolean isStatic, List<String> reasons) {
            boolean[] rp = new boolean[paramCount];
            Arrays.fill(rp, true);
            return new Result(
                    EffectSet.EMPTY,
                    false,
                    false,
                    new boolean[paramCount],
                    new boolean[paramCount],
                    !isStatic,
                    rp,
                    false,
                    false,
                    false,
                    false,
                    List.copyOf(reasons));
        }

        EffectChecker.JavaCallProfile toProfile(Executable exec) {
            int pCount = exec != null ? exec.getParameterCount() : mutParams.length;
            boolean isCtor = exec instanceof Constructor<?>;
            boolean isStatic = isCtor || (exec != null && Modifier.isStatic(exec.getModifiers()));
            boolean[] mp = padOrCopy(mutParams, pCount, false);
            boolean[] bp =
                    padOrCopy(borrowParams, pCount, !effects.isUnconstrained() && effects.isPure());
            boolean[] rp = padOrCopy(readParams, pCount, true);
            boolean[] up = new boolean[pCount];
            return new EffectChecker.JavaCallProfile(
                    effects,
                    !isStatic && mutReceiver,
                    !isStatic && borrowReceiver,
                    mp,
                    bp,
                    up,
                    effects.isNothrow(),
                    view,
                    shallow,
                    fresh,
                    !isStatic && readsReceiver,
                    rp);
        }

        private static boolean[] padOrCopy(boolean[] src, int len, boolean fill) {
            boolean[] out = new boolean[len];
            int n = Math.min(src.length, len);
            System.arraycopy(src, 0, out, 0, n);
            if (fill && n < len) {
                Arrays.fill(out, n, len, true);
            }
            return out;
        }

        boolean sameSummary(Result other) {
            if (other == null) return false;
            return mutReceiver == other.mutReceiver
                    && borrowReceiver == other.borrowReceiver
                    && readsReceiver == other.readsReceiver
                    && returnNonNull == other.returnNonNull
                    && fresh == other.fresh
                    && view == other.view
                    && shallow == other.shallow
                    && effects.equals(other.effects)
                    && Arrays.equals(mutParams, other.mutParams)
                    && Arrays.equals(borrowParams, other.borrowParams)
                    && Arrays.equals(readParams, other.readParams);
        }
    }

    private record MethodKey(String ownerInternalName, String name, String descriptor)
            implements Comparable<MethodKey> {
        @Override
        public int compareTo(MethodKey o) {
            int c = ownerInternalName.compareTo(o.ownerInternalName);
            if (c != 0) return c;
            c = name.compareTo(o.name);
            return c != 0 ? c : descriptor.compareTo(o.descriptor);
        }
    }

    /** Per-(BuiltinAxioms, ClassLoader) cache for converged bytecode analysis results. */
    private static final Map<BuiltinAxioms, Map<ClassLoader, Map<MethodKey, Result>>> CACHES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /** Lock-free fast-path cache for standard axioms + bootstrap class loader (java.base). */
    private static final ConcurrentHashMap<MethodKey, Result> STANDARD_BOOTSTRAP_CACHE =
            new ConcurrentHashMap<>();

    /** ClassNode cache per ClassLoader so each class file is parsed with ASM only once. */
    private static final Map<ClassLoader, Map<String, ClassNode>> CLASS_NODE_CACHES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Lock-free fast-path ClassNode cache for bootstrap class loader (java.base). */
    private static final ConcurrentHashMap<String, ClassNode> BOOTSTRAP_CLASS_NODES =
            new ConcurrentHashMap<>();

    private static final ClassLoader NULL_LOADER_SENTINEL = new ClassLoader(null) {};
    private static final ClassNode MISSING_CLASS_NODE = new ClassNode();

    private BytecodeEffectAnalyzer() {}

    private static Map<MethodKey, Result> cacheFor(BuiltinAxioms axioms, ClassLoader loader) {
        BuiltinAxioms keyAxioms = axioms != null ? axioms : BuiltinAxioms.standard();
        if (loader == null && keyAxioms == BuiltinAxioms.standard()) {
            return STANDARD_BOOTSTRAP_CACHE;
        }
        ClassLoader keyLoader = loader != null ? loader : NULL_LOADER_SENTINEL;
        Map<ClassLoader, Map<MethodKey, Result>> byLoader;
        synchronized (CACHES) {
            byLoader =
                    CACHES.computeIfAbsent(
                            keyAxioms, k -> Collections.synchronizedMap(new WeakHashMap<>()));
        }
        synchronized (byLoader) {
            return byLoader.computeIfAbsent(keyLoader, k -> new ConcurrentHashMap<>());
        }
    }

    /** Analyzes a single Java {@link Method} using the active {@link BuiltinAxioms}. */
    public static Result analyze(Method method) {
        return analyze((Executable) method, BuiltinAxioms.current());
    }

    /** Analyzes a Java {@link Method} or {@link Constructor} using {@code axioms}. */
    public static Result analyze(Executable exec, BuiltinAxioms axioms) {
        if (exec == null) {
            return Result.UNCONSTRAINED;
        }
        Class<?> owner = exec.getDeclaringClass();
        String ownerInternal = Type.getInternalName(owner);
        String name = exec instanceof Constructor<?> ? "<init>" : exec.getName();
        String desc =
                exec instanceof Constructor<?> c
                        ? Type.getConstructorDescriptor(c)
                        : Type.getMethodDescriptor((Method) exec);
        MethodKey root = new MethodKey(ownerInternal, name, desc);
        BuiltinAxioms effectiveAxioms = axioms != null ? axioms : BuiltinAxioms.standard();
        Map<MethodKey, Result> cache = cacheFor(effectiveAxioms, owner.getClassLoader());
        Result cached = cache.get(root);
        if (cached != null) {
            return cached;
        }
        Session session = new Session(effectiveAxioms, owner.getClassLoader(), cache, false);
        return session.solveRoot(root);
    }

    /**
     * Analyzes {@code exec}'s own bytecode directly (ignoring any curated axiom on {@code exec}
     * itself, while still applying curated axioms to callees), for {@code babble.axioms.report} and
     * {@code -axioms} dumps (§5.1).
     */
    public static Result analyzeRaw(Executable exec, BuiltinAxioms axioms) {
        if (exec == null) {
            return Result.UNCONSTRAINED;
        }
        Class<?> owner = exec.getDeclaringClass();
        String ownerInternal = Type.getInternalName(owner);
        String name = exec instanceof Constructor<?> ? "<init>" : exec.getName();
        String desc =
                exec instanceof Constructor<?> c
                        ? Type.getConstructorDescriptor(c)
                        : Type.getMethodDescriptor((Method) exec);
        MethodKey root = new MethodKey(ownerInternal, name, desc);
        BuiltinAxioms effectiveAxioms = axioms != null ? axioms : BuiltinAxioms.standard();
        Map<MethodKey, Result> cache = cacheFor(effectiveAxioms, owner.getClassLoader());
        Session session = new Session(effectiveAxioms, owner.getClassLoader(), cache, true);
        return session.solveRoot(root);
    }

    /** Analyzes all declared non-synthetic methods of {@code cls}. */
    public static Map<Method, Result> analyzeClass(Class<?> cls) {
        Map<Method, Result> results = new LinkedHashMap<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.isSynthetic() && !m.isBridge()) {
                results.put(m, analyze(m));
            }
        }
        return results;
    }

    /**
     * Whether {@code inferred} is at least as strong as {@code curated} on effects, mutation,
     * borrowing, and return nullness (for {@code babble.axioms.report} and {@code ;; curated,
     * inferred the same}).
     */
    static boolean isAtLeastAsStrongAs(
            Result inferred, AxiomData.MemberAxiom curated, Executable exec) {
        if (inferred == null || curated == null) {
            return false;
        }
        // Check curated nullness for every curated axiom (including io and ... inferred effects,
        // BIP030-D6).
        if (curated.returnNullness() == JavaNullability.Nullness.NON_NULL
                && exec instanceof Method m
                && !m.getReturnType().isPrimitive()
                && !inferred.returnNonNull()) {
            return false;
        }
        if (curated.returnNullness() == JavaNullability.Nullness.PARAMETRIC
                && exec instanceof Method m
                && m.getGenericReturnType() instanceof java.lang.reflect.TypeVariable<?>) {
            return false;
        }
        if (curated.hasSignature() && exec != null) {
            Class<?>[] pts = exec.getParameterTypes();
            JavaNullability.Nullness[] pNulls = curated.paramNullness();
            for (int i = 0; i < Math.min(pts.length, pNulls.length); i++) {
                if (!pts[i].isPrimitive()
                        && pNulls[i] != null
                        && pNulls[i] != JavaNullability.Nullness.UNSPECIFIED) {
                    return false;
                }
            }
        }
        EffectSet req = curated.effects();
        if (curated.inferredEffects() || req.isIO()) {
            return true;
        }
        // Foldable and algebraic properties cannot be inferred from bytecode alone.
        if (curated.foldable()) {
            return false;
        }
        for (Effect e : req.effects()) {
            if (e == Effect.IDEMPOTENT
                    || e == Effect.COMMUTATIVE
                    || e == Effect.ASSOCIATIVE
                    || e == Effect.MONOTONE
                    || e == Effect.INJECTIVE) {
                return false;
            }
        }
        EffectSet inf = inferred.effects();
        if (req.isHermetic() && !inf.isHermetic()) return false;
        if (req.isPure() && !inf.isPure()) return false;
        if (req.isDeterministic() && !inf.isDeterministic()) return false;
        if (req.isNothrow() && !inf.isNothrow()) return false;
        if ((curated.fresh() || req.uniqueResult()) && !(inferred.fresh() || inf.uniqueResult()))
            return false;
        if (curated.view() && !inferred.view()) return false;
        if (curated.shallow() && !inferred.shallow()) return false;
        boolean isStatic =
                exec instanceof Constructor<?>
                        || (exec != null && Modifier.isStatic(exec.getModifiers()));
        if (!isStatic) {
            if (!curated.mutReceiver() && inferred.mutReceiver()) return false;
            if (curated.borrowReceiver() && !inferred.borrowReceiver()) return false;
        }
        int pCount = exec != null ? exec.getParameterCount() : curated.paramDescriptors().size();
        for (int i = 0; i < pCount; i++) {
            boolean cMut =
                    curated.hasSignature()
                            && i < curated.mutParams().length
                            && curated.mutParams()[i];
            boolean cBorrow =
                    curated.hasSignature()
                            ? (i < curated.borrowParams().length && curated.borrowParams()[i])
                            : !curated.sharedReceiver();
            boolean iMut = i < inferred.mutParams().length && inferred.mutParams()[i];
            boolean iBorrow = i < inferred.borrowParams().length && inferred.borrowParams()[i];
            if (!cMut && iMut) return false;
            if (cBorrow && !iBorrow) return false;
        }
        return true;
    }

    /**
     * Formats a ready-to-paste Babble {@code axiom Class.method: fn ...} suggestion, or {@code
     * null} if unconstrained.
     */
    public static String suggestAxiom(Method method) {
        Result r = analyze(method);
        EffectSet eff = r.effects();
        if (eff.isUnconstrained()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (eff.isPure() && eff.isDeterministic()) {
            sb.append("pure ");
        } else if (eff.isHermetic() && !eff.isDeterministic()) {
            sb.append("volatile ");
        }
        if (r.mutReceiver()) {
            sb.append("mut ");
        }
        if (!Modifier.isStatic(method.getModifiers()) && !r.borrowReceiver()) {
            sb.append("shared ");
        }
        if (eff.isNothrow()) {
            sb.append("nothrow ");
        }
        if (r.view()) {
            sb.append("view ");
        }
        if (r.shallow()) {
            sb.append("shallow ");
        }
        if (r.fresh() || eff.uniqueResult()) {
            sb.append("fresh ");
        }
        String prefix = sb.toString().trim();
        if (prefix.isEmpty()) {
            return null;
        }
        return "axiom "
                + method.getDeclaringClass().getName()
                + "."
                + method.getName()
                + ": fn "
                + prefix;
    }

    // ================================================================ Value Lattice (§5.2)

    private static final class ProvValue implements Value {
        static final ProvValue UNINITIALIZED = new ProvValue(1, 0L, true);
        static final ProvValue CONST_1 = new ProvValue(1, PROV_CONST, false);
        static final ProvValue CONST_2 = new ProvValue(2, PROV_CONST, false);
        static final ProvValue NULL_VAL = new ProvValue(1, PROV_CONST, true);
        static final ProvValue THIS_VAL = new ProvValue(1, PROV_THIS, false);
        static final ProvValue FRESH_VAL = new ProvValue(1, PROV_FRESH, false);
        static final ProvValue EXTERNAL_NULLABLE = new ProvValue(1, PROV_EXTERNAL, true);
        static final ProvValue EXTERNAL_NONNULL = new ProvValue(1, PROV_EXTERNAL, false);

        final int size;
        final long provBits;
        final boolean mayBeNull;

        ProvValue(int size, long provBits, boolean mayBeNull) {
            this.size = size;
            this.provBits = provBits;
            this.mayBeNull = mayBeNull;
        }

        static ProvValue of(int size, long provBits, boolean mayBeNull) {
            if (size == 2 && provBits == PROV_CONST && !mayBeNull) return CONST_2;
            if (size == 1 && provBits == PROV_CONST && !mayBeNull) return CONST_1;
            if (size == 1 && provBits == PROV_THIS && !mayBeNull) return THIS_VAL;
            if (size == 1 && provBits == PROV_FRESH && !mayBeNull) return FRESH_VAL;
            if (size == 1 && provBits == PROV_EXTERNAL)
                return mayBeNull ? EXTERNAL_NULLABLE : EXTERNAL_NONNULL;
            return new ProvValue(size, provBits, mayBeNull);
        }

        static ProvValue ofParam(int paramIndex, Type type) {
            int sz = type.getSize();
            if (isPrimitive(type)) {
                return sz == 2 ? CONST_2 : CONST_1;
            }
            long bit =
                    paramIndex < MAX_TRACKED_PARAMS
                            ? (1L << (PROV_PARAM_SHIFT + paramIndex))
                            : PROV_EXTERNAL;
            return new ProvValue(1, bit, true);
        }

        boolean isPurelyFresh() {
            return (provBits & ~(PROV_FRESH | PROV_CONST)) == 0 && (provBits & PROV_FRESH) != 0;
        }

        boolean hasThis() {
            return (provBits & PROV_THIS) != 0;
        }

        boolean hasExternal() {
            return (provBits & PROV_EXTERNAL) != 0;
        }

        boolean hasStoredElem() {
            return (provBits & PROV_STORED_ELEM) != 0;
        }

        @Override
        public int getSize() {
            return size;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProvValue that)) return false;
            return size == that.size && provBits == that.provBits && mayBeNull == that.mayBeNull;
        }

        @Override
        public int hashCode() {
            int r = Long.hashCode(provBits);
            r = 31 * r + size;
            r = 31 * r + (mayBeNull ? 1 : 0);
            return r;
        }
    }

    private static boolean isPrimitive(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN,
                    Type.CHAR,
                    Type.BYTE,
                    Type.SHORT,
                    Type.INT,
                    Type.FLOAT,
                    Type.LONG,
                    Type.DOUBLE,
                    Type.VOID ->
                    true;
            default -> false;
        };
    }

    // ================================================================ Fixed-Point Analysis Session

    private static final class Session {
        final BuiltinAxioms axioms;
        final ClassLoader loader;
        final Map<MethodKey, Result> globalCache;
        final boolean ignoreRootCurated;
        final Map<MethodKey, MethodNode> methodNodes = new HashMap<>();
        final Map<MethodKey, Set<MethodKey>> callGraph = new LinkedHashMap<>();
        final Set<MethodKey> visited = new HashSet<>();
        final Map<MethodKey, Result> localResults = new HashMap<>();
        boolean budgetExceeded = false;

        Session(
                BuiltinAxioms axioms,
                ClassLoader loader,
                Map<MethodKey, Result> globalCache,
                boolean ignoreRootCurated) {
            this.axioms = axioms;
            this.loader = loader;
            this.globalCache = globalCache;
            this.ignoreRootCurated = ignoreRootCurated;
        }

        Result solveRoot(MethodKey root) {
            discover(root, 0, true);
            List<List<MethodKey>> sccs = tarjanSccs(root);
            for (List<MethodKey> scc : sccs) {
                solveScc(scc, root);
            }
            Result out = localResults.getOrDefault(root, Result.UNCONSTRAINED);
            if (!ignoreRootCurated && !budgetExceeded) {
                for (Map.Entry<MethodKey, Result> e : localResults.entrySet()) {
                    globalCache.putIfAbsent(e.getKey(), e.getValue());
                }
            }
            return out;
        }

        private void discover(MethodKey key, int depth, boolean isRoot) {
            if (!visited.add(key)) {
                return;
            }
            if (!isRoot && globalCache.containsKey(key)) {
                localResults.put(key, globalCache.get(key));
                callGraph.put(key, Set.of());
                return;
            }
            if (!isRoot) {
                Result preset = lookupCuratedOrSeeded(key);
                if (preset != null) {
                    localResults.put(key, preset);
                    callGraph.put(key, Set.of());
                    return;
                }
            }
            if (depth > MAX_CALL_DEPTH || methodNodes.size() >= MAX_CALL_GRAPH_NODES) {
                budgetExceeded = true;
                Type[] args = Type.getArgumentTypes(key.descriptor());
                localResults.put(
                        key,
                        Result.unconstrained(
                                args.length, false, List.of("analysis budget exceeded")));
                callGraph.put(key, Set.of());
                return;
            }
            MethodNode mn = findMethodNode(key);
            if (mn == null) {
                Type[] args = Type.getArgumentTypes(key.descriptor());
                localResults.put(
                        key,
                        Result.unconstrained(
                                args.length,
                                false,
                                List.of("bytecode unavailable for " + key.ownerInternalName)));
                callGraph.put(key, Set.of());
                return;
            }
            methodNodes.put(key, mn);
            Set<MethodKey> callees = new TreeSet<>();
            if ((mn.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0
                    && mn.instructions != null) {
                for (AbstractInsnNode insn = mn.instructions.getFirst();
                        insn != null;
                        insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode mi) {
                        if ("<init>".equals(mi.name) && "java/lang/Object".equals(mi.owner)) {
                            continue;
                        }
                        MethodKey calleeKey = new MethodKey(mi.owner, mi.name, mi.desc);
                        if (lookupCuratedOrSeeded(calleeKey) != null) {
                            continue;
                        }
                        if (isClosedCall(mi.getOpcode(), mi.owner, mi.name, mi.desc)) {
                            callees.add(calleeKey);
                        }
                    }
                }
            }
            callGraph.put(key, callees);
            for (MethodKey callee : callees) {
                discover(callee, depth + 1, false);
            }
        }

        private List<List<MethodKey>> tarjanSccs(MethodKey root) {
            Map<MethodKey, Integer> indexMap = new HashMap<>();
            Map<MethodKey, Integer> lowlink = new HashMap<>();
            Deque<MethodKey> stack = new ArrayDeque<>();
            Set<MethodKey> onStack = new HashSet<>();
            List<List<MethodKey>> sccs = new ArrayList<>();
            int[] nextIndex = {0};

            class Dfs {
                void visit(MethodKey v) {
                    indexMap.put(v, nextIndex[0]);
                    lowlink.put(v, nextIndex[0]);
                    nextIndex[0]++;
                    stack.push(v);
                    onStack.add(v);
                    for (MethodKey w : callGraph.getOrDefault(v, Set.of())) {
                        if (!indexMap.containsKey(w)) {
                            visit(w);
                            lowlink.put(v, Math.min(lowlink.get(v), lowlink.get(w)));
                        } else if (onStack.contains(w)) {
                            lowlink.put(v, Math.min(lowlink.get(v), indexMap.get(w)));
                        }
                    }
                    if (lowlink.get(v).equals(indexMap.get(v))) {
                        List<MethodKey> comp = new ArrayList<>();
                        while (true) {
                            MethodKey w = stack.pop();
                            onStack.remove(w);
                            comp.add(w);
                            if (w.equals(v)) break;
                        }
                        comp.sort(Comparator.naturalOrder());
                        sccs.add(comp);
                    }
                }
            }
            new Dfs().visit(root);
            return sccs;
        }

        private void solveScc(List<MethodKey> scc, MethodKey root) {
            if (scc.size() == 1 && localResults.containsKey(scc.get(0))) {
                return;
            }
            boolean cyclic =
                    scc.size() > 1
                            || callGraph.getOrDefault(scc.get(0), Set.of()).contains(scc.get(0));
            for (MethodKey k : scc) {
                if (localResults.containsKey(k)) continue;
                MethodNode mn = methodNodes.get(k);
                if (mn == null) continue;
                int pCount = Type.getArgumentTypes(k.descriptor()).length;
                boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
                boolean[] bp = new boolean[pCount];
                Arrays.fill(bp, true);
                EffectSet opt =
                        EffectSet.ofInferred(
                                EnumSet.of(
                                        Effect.PURE,
                                        Effect.HERMETIC,
                                        Effect.DETERMINISTIC,
                                        Effect.NOTHROW),
                                false,
                                !isStatic,
                                false,
                                !cyclic);
                localResults.put(
                        k,
                        new Result(
                                opt,
                                false,
                                !isStatic,
                                new boolean[pCount],
                                bp,
                                false,
                                new boolean[pCount],
                                true,
                                true,
                                false,
                                true,
                                List.of()));
            }
            for (int iter = 0; iter < MAX_SCC_ITERATIONS; iter++) {
                boolean changed = false;
                for (MethodKey k : scc) {
                    MethodNode mn = methodNodes.get(k);
                    if (mn == null) continue;
                    Result prev = localResults.get(k);
                    Result next =
                            analyzeSingleMethod(k, mn, cyclic, k.equals(root) && ignoreRootCurated);
                    if (!next.sameSummary(prev)) {
                        localResults.put(k, next);
                        changed = true;
                    }
                }
                if (!changed) {
                    break;
                }
            }
        }

        private Result analyzeSingleMethod(
                MethodKey key, MethodNode mn, boolean cyclic, boolean skipSelfCurated) {
            boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
            boolean isCtor = "<init>".equals(key.name());
            Type[] argTypes = Type.getArgumentTypes(key.descriptor());
            int pCount = argTypes.length;
            Type retType = Type.getReturnType(key.descriptor());
            boolean overlayNonNull =
                    !skipSelfCurated
                            && !isCtor
                            && !isPrimitive(retType)
                            && curatedReturnNonNull(key, true);

            if ((mn.access & Opcodes.ACC_NATIVE) != 0) {
                return unconstrainedWithNonNull(
                        pCount, isStatic || isCtor, overlayNonNull, List.of("native method"));
            }
            if ((mn.access & Opcodes.ACC_ABSTRACT) != 0
                    || mn.instructions == null
                    || mn.instructions.size() == 0) {
                return unconstrainedWithNonNull(
                        pCount, isStatic || isCtor, overlayNonNull, List.of("abstract method"));
            }

            if (!skipSelfCurated) {
                Result preset = lookupCuratedOrSeeded(key);
                if (preset != null) {
                    return preset;
                }
            }

            // Run ASM Analyzer<ProvValue> for sound control-flow value lattice.
            ProvInterpreter interp = new ProvInterpreter(this, isStatic, isCtor, argTypes);
            Frame<ProvValue>[] frames;
            try {
                Analyzer<ProvValue> analyzer = new Analyzer<>(interp);
                frames = analyzer.analyze(key.ownerInternalName(), mn);
            } catch (AnalyzerException | RuntimeException e) {
                return Result.unconstrained(
                        pCount, isStatic || isCtor, List.of("bytecode verification failed"));
            }

            // Initialize summary state from analyzed frames.
            boolean hermetic = true;
            boolean deterministic = true;
            boolean nothrow = true;
            boolean terminating = !cyclic;
            boolean mutReceiver = false;
            boolean borrowReceiver = !(isStatic || isCtor);
            boolean[] mutParams = new boolean[pCount];
            boolean[] borrowParams = new boolean[pCount];
            Arrays.fill(borrowParams, true);
            boolean readsReceiver = false;
            boolean[] readParams = new boolean[pCount];
            boolean returnNonNull = !isPrimitive(retType);
            boolean hasObjectReturn = false;
            boolean allReturnsFresh = !isPrimitive(retType);
            boolean allReturnsAliasThis = !(isStatic || isCtor) && !isPrimitive(retType);
            boolean touchedStoredElemCall = false;
            Set<String> reasons = new LinkedHashSet<>();

            if ((mn.access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                hermetic = false;
                deterministic = false;
                nothrow = false;
                reasons.add("synchronized method");
            }
            if (cyclic) {
                reasons.add("recursive cycle");
            }

            // Map labels to instruction indices for backward-branch loop detection.
            Map<LabelNode, Integer> labelIndex = new HashMap<>();
            AbstractInsnNode[] insns = mn.instructions.toArray();
            for (int i = 0; i < insns.length; i++) {
                if (insns[i] instanceof LabelNode ln) {
                    labelIndex.put(ln, i);
                }
            }

            for (int i = 0; i < insns.length; i++) {
                Frame<ProvValue> frame = frames[i];
                if (frame == null) {
                    continue; // unreachable instruction
                }
                AbstractInsnNode insn = insns[i];
                int op = insn.getOpcode();
                if (op < 0) {
                    continue;
                }
                if (insn instanceof JumpInsnNode jn) {
                    Integer targetIdx = labelIndex.get(jn.label);
                    if (targetIdx != null && targetIdx <= i) {
                        terminating = false;
                    }
                    continue;
                }
                switch (op) {
                    case Opcodes.PUTSTATIC -> {
                        FieldInsnNode fi = (FieldInsnNode) insn;
                        hermetic = false;
                        deterministic = false;
                        reasons.add("writes static " + fi.owner + "." + fi.name);
                    }
                    case Opcodes.GETSTATIC -> {
                        FieldInsnNode fi = (FieldInsnNode) insn;
                        if (!isFinalStaticField(fi.owner, fi.name)) {
                            deterministic = false;
                            reasons.add("reads mutable static " + fi.owner + "." + fi.name);
                        }
                    }
                    case Opcodes.PUTFIELD -> {
                        FieldInsnNode fi = (FieldInsnNode) insn;
                        int top = frame.getStackSize();
                        ProvValue obj = frame.getStack(top - 2);
                        ProvValue val = frame.getStack(top - 1);
                        if (obj.mayBeNull) {
                            nothrow = false;
                        }
                        if (!obj.isPurelyFresh()) {
                            if (obj.hasThis() && !(isCtor && (obj.provBits & ~PROV_THIS) == 0)) {
                                mutReceiver = true;
                            }
                            markParamBits(obj.provBits, mutParams);
                            if (obj.hasExternal()) {
                                hermetic = false;
                                reasons.add("writes external field " + fi.owner + "." + fi.name);
                            }
                            // Escape of stored value:
                            if (val.hasThis()
                                    && !(obj.hasThis() && (obj.provBits & ~PROV_THIS) == 0)) {
                                borrowReceiver = false;
                            }
                            clearParamBits(val.provBits, borrowParams);
                        }
                    }
                    case Opcodes.GETFIELD -> {
                        FieldInsnNode fi = (FieldInsnNode) insn;
                        ProvValue obj = frame.getStack(frame.getStackSize() - 1);
                        if (obj.mayBeNull) {
                            nothrow = false;
                        }
                        if (!isFinalInstanceField(fi.owner, fi.name)) {
                            if (obj.hasThis() && !isCtor) {
                                readsReceiver = true;
                            }
                            markParamBits(obj.provBits, readParams);
                            if (obj.hasExternal()) {
                                deterministic = false;
                                reasons.add("reads external field " + fi.owner + "." + fi.name);
                            }
                        }
                    }
                    case Opcodes.IASTORE,
                            Opcodes.LASTORE,
                            Opcodes.FASTORE,
                            Opcodes.DASTORE,
                            Opcodes.AASTORE,
                            Opcodes.BASTORE,
                            Opcodes.CASTORE,
                            Opcodes.SASTORE -> {
                        nothrow = false;
                        int top = frame.getStackSize();
                        ProvValue arr = frame.getStack(top - 3);
                        ProvValue val = frame.getStack(top - 1);
                        if (!arr.isPurelyFresh()) {
                            if (arr.hasThis() && !(isCtor && (arr.provBits & ~PROV_THIS) == 0)) {
                                mutReceiver = true;
                            }
                            markParamBits(arr.provBits, mutParams);
                            if (arr.hasExternal()) {
                                hermetic = false;
                                reasons.add("writes external array");
                            }
                            if (op == Opcodes.AASTORE) {
                                if (val.hasThis()
                                        && !(arr.hasThis() && (arr.provBits & ~PROV_THIS) == 0)) {
                                    borrowReceiver = false;
                                }
                                clearParamBits(val.provBits, borrowParams);
                            }
                        }
                    }
                    case Opcodes.IALOAD,
                            Opcodes.LALOAD,
                            Opcodes.FALOAD,
                            Opcodes.DALOAD,
                            Opcodes.AALOAD,
                            Opcodes.BALOAD,
                            Opcodes.CALOAD,
                            Opcodes.SALOAD -> {
                        nothrow = false;
                        ProvValue arr = frame.getStack(frame.getStackSize() - 2);
                        if (!arr.isPurelyFresh()) {
                            if (arr.hasThis() && !isCtor) {
                                readsReceiver = true;
                            }
                            markParamBits(arr.provBits, readParams);
                            if (arr.hasExternal()) {
                                deterministic = false;
                                reasons.add("reads external array");
                            }
                        }
                    }
                    case Opcodes.ARRAYLENGTH -> {
                        ProvValue arr = frame.getStack(frame.getStackSize() - 1);
                        if (arr.mayBeNull) {
                            nothrow = false;
                        }
                    }
                    case Opcodes.IDIV, Opcodes.IREM, Opcodes.LDIV, Opcodes.LREM -> nothrow = false;
                    case Opcodes.ATHROW -> nothrow = false;
                    case Opcodes.CHECKCAST -> nothrow = false;
                    case Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> {
                        hermetic = false;
                        deterministic = false;
                        nothrow = false;
                        reasons.add("monitor synchronization");
                    }
                    case Opcodes.ARETURN -> {
                        hasObjectReturn = true;
                        ProvValue ret = frame.getStack(frame.getStackSize() - 1);
                        if (ret.mayBeNull) {
                            returnNonNull = false;
                        }
                        if (!ret.isPurelyFresh()) {
                            allReturnsFresh = false;
                        }
                        if (!ret.hasThis()) {
                            allReturnsAliasThis = false;
                        }
                    }
                    case Opcodes.INVOKESTATIC,
                            Opcodes.INVOKESPECIAL,
                            Opcodes.INVOKEVIRTUAL,
                            Opcodes.INVOKEINTERFACE -> {
                        MethodInsnNode mi = (MethodInsnNode) insn;
                        if ("<init>".equals(mi.name) && "java/lang/Object".equals(mi.owner)) {
                            break;
                        }
                        Type[] cArgs = Type.getArgumentTypes(mi.desc);
                        boolean cStatic = op == Opcodes.INVOKESTATIC;
                        int totalPop = cArgs.length + (cStatic ? 0 : 1);
                        int base = frame.getStackSize() - totalPop;
                        ProvValue cRecv = cStatic ? null : frame.getStack(base);
                        ProvValue[] cArgVals = new ProvValue[cArgs.length];
                        for (int a = 0; a < cArgs.length; a++) {
                            cArgVals[a] = frame.getStack(base + (cStatic ? 0 : 1) + a);
                        }
                        if (cRecv != null && cRecv.mayBeNull) {
                            nothrow = false;
                        }

                        MethodKey calleeKey = new MethodKey(mi.owner, mi.name, mi.desc);
                        Result calleeRes = resolveCalleeResult(op, mi, calleeKey, reasons);
                        if (calleeRes == null) {
                            hermetic = false;
                            deterministic = false;
                            nothrow = false;
                            terminating = false;
                            borrowReceiver = false;
                            Arrays.fill(borrowParams, false);
                            reasons.add("open dispatch " + mi.owner + "." + mi.name);
                            break;
                        }
                        EffectSet ce = calleeRes.effects();
                        if (!ce.isHermetic()) hermetic = false;
                        if (!ce.isDeterministic()) deterministic = false;
                        if (!ce.isNothrow()) nothrow = false;
                        if (!ce.terminating()) terminating = false;

                        if (cRecv != null) {
                            if (calleeRes.mutReceiver() && !cRecv.isPurelyFresh()) {
                                if (cRecv.hasThis()
                                        && !(isCtor && (cRecv.provBits & ~PROV_THIS) == 0)) {
                                    mutReceiver = true;
                                }
                                markParamBits(cRecv.provBits, mutParams);
                                if (cRecv.hasExternal()) {
                                    hermetic = false;
                                }
                            }
                            if (calleeRes.readsReceiver() && !cRecv.isPurelyFresh()) {
                                if (cRecv.hasThis() && !isCtor) {
                                    readsReceiver = true;
                                }
                                markParamBits(cRecv.provBits, readParams);
                                if (cRecv.hasExternal()) {
                                    deterministic = false;
                                }
                            }
                            if (!calleeRes.borrowReceiver() && !ce.isPure()) {
                                if (cRecv.hasThis()) {
                                    borrowReceiver = false;
                                }
                                clearParamBits(cRecv.provBits, borrowParams);
                            }
                            if (cRecv.hasStoredElem()) {
                                touchedStoredElemCall = true;
                            }
                        }
                        for (int a = 0; a < cArgVals.length; a++) {
                            ProvValue av = cArgVals[a];
                            boolean cMutP =
                                    a < calleeRes.mutParams().length && calleeRes.mutParams()[a];
                            boolean cReadP =
                                    a < calleeRes.readParams().length && calleeRes.readParams()[a];
                            boolean cBorrowP =
                                    a < calleeRes.borrowParams().length
                                            && calleeRes.borrowParams()[a];
                            if (cMutP && !av.isPurelyFresh()) {
                                if (av.hasThis() && !(isCtor && (av.provBits & ~PROV_THIS) == 0)) {
                                    mutReceiver = true;
                                }
                                markParamBits(av.provBits, mutParams);
                                if (av.hasExternal()) {
                                    hermetic = false;
                                }
                            }
                            if (cReadP && !av.isPurelyFresh()) {
                                if (av.hasThis() && !isCtor) {
                                    readsReceiver = true;
                                }
                                markParamBits(av.provBits, readParams);
                                if (av.hasExternal()) {
                                    deterministic = false;
                                }
                            }
                            if (!cBorrowP) {
                                if (av.hasThis()) {
                                    borrowReceiver = false;
                                }
                                clearParamBits(av.provBits, borrowParams);
                            }
                            if (av.hasStoredElem() && !ce.isPure()) {
                                touchedStoredElemCall = true;
                            }
                        }
                        if (!calleeRes.reasons().isEmpty()
                                && (!ce.isHermetic() || !ce.isDeterministic())) {
                            reasons.addAll(calleeRes.reasons());
                        }
                    }
                    case Opcodes.INVOKEDYNAMIC -> {
                        InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                        String bsmOwner = indy.bsm != null ? indy.bsm.getOwner() : "";
                        if ("java/lang/invoke/StringConcatFactory".equals(bsmOwner)) {
                            // Pure, deterministic, nothrow string concatenation!
                        } else if ("java/lang/invoke/LambdaMetafactory".equals(bsmOwner)) {
                            // Creating a lambda captures its arguments (shared) without running the
                            // body.
                            Type[] cArgs = Type.getArgumentTypes(indy.desc);
                            int base = frame.getStackSize() - cArgs.length;
                            for (int a = 0; a < cArgs.length; a++) {
                                ProvValue av = frame.getStack(base + a);
                                if (av.hasThis()) borrowReceiver = false;
                                clearParamBits(av.provBits, borrowParams);
                            }
                        } else {
                            hermetic = false;
                            deterministic = false;
                            nothrow = false;
                            terminating = false;
                            reasons.add("invokedynamic " + indy.name);
                        }
                    }
                    default -> {}
                }
            }

            boolean nonNullRet = isCtor || (hasObjectReturn && returnNonNull) || overlayNonNull;
            if (!hermetic) {
                boolean[] allReads = new boolean[pCount];
                Arrays.fill(allReads, true);
                return new Result(
                        EffectSet.EMPTY,
                        false,
                        false,
                        new boolean[pCount],
                        new boolean[pCount],
                        !(isStatic || isCtor),
                        allReads,
                        nonNullRet,
                        false,
                        false,
                        false,
                        List.copyOf(reasons));
            }
            boolean anyMutParam = false;
            for (boolean mp : mutParams) {
                anyMutParam |= mp;
            }
            EnumSet<Effect> effs = EnumSet.noneOf(Effect.class);
            effs.add(Effect.HERMETIC);
            if (!mutReceiver && !anyMutParam) {
                effs.add(Effect.PURE);
            }
            if (deterministic) {
                effs.add(Effect.DETERMINISTIC);
            }
            if (nothrow) {
                effs.add(Effect.NOTHROW);
            }
            boolean isFresh = hasObjectReturn && allReturnsFresh;
            boolean isView = hasObjectReturn && allReturnsAliasThis && !mutReceiver;
            boolean isShallow = mutReceiver && !anyMutParam && !touchedStoredElemCall;
            if (isCtor) {
                mutReceiver = false;
                borrowReceiver = false;
                readsReceiver = false;
                isFresh = allParamsBorrowed(borrowParams);
            }
            EffectSet effectSet =
                    EffectSet.ofInferred(effs, mutReceiver, borrowReceiver, isFresh, terminating);
            return new Result(
                    effectSet,
                    mutReceiver,
                    borrowReceiver,
                    mutParams,
                    borrowParams,
                    readsReceiver,
                    readParams,
                    nonNullRet,
                    isFresh,
                    isView,
                    isShallow,
                    List.copyOf(reasons));
        }

        private Result unconstrainedWithNonNull(
                int pCount, boolean isStaticOrCtor, boolean nonNullRet, List<String> reasons) {
            if (!nonNullRet) {
                return Result.unconstrained(pCount, isStaticOrCtor, reasons);
            }
            boolean[] rp = new boolean[pCount];
            Arrays.fill(rp, true);
            return new Result(
                    EffectSet.EMPTY,
                    false,
                    false,
                    new boolean[pCount],
                    new boolean[pCount],
                    !isStaticOrCtor,
                    rp,
                    true,
                    false,
                    false,
                    false,
                    List.copyOf(reasons));
        }

        Result resolveCalleeResult(
                int opcode, MethodInsnNode mi, MethodKey calleeKey, Set<String> reasons) {
            if (mi.owner.startsWith("[")
                    && "clone".equals(mi.name)
                    && "()Ljava/lang/Object;".equals(mi.desc)) {
                EffectSet es =
                        EffectSet.ofInferred(
                                EnumSet.of(
                                        Effect.PURE,
                                        Effect.HERMETIC,
                                        Effect.DETERMINISTIC,
                                        Effect.NOTHROW),
                                false,
                                true,
                                true,
                                true);
                return new Result(
                        es,
                        false,
                        true,
                        new boolean[0],
                        new boolean[0],
                        true,
                        new boolean[0],
                        true,
                        true,
                        false,
                        false,
                        List.of());
            }
            Result curated = lookupCuratedOrSeeded(calleeKey);
            if (curated != null) {
                if (reasons != null && !curated.reasons().isEmpty()) {
                    reasons.addAll(curated.reasons());
                }
                return curated;
            }
            if (isClosedCall(opcode, mi.owner, mi.name, mi.desc)) {
                return localResults.get(calleeKey);
            }
            return null;
        }

        Result lookupCuratedOrSeeded(MethodKey key) {
            // 1. Curated axioms (BIP019 §5.1–§5.2; BIP030 skips ... inferred-effect axioms here)
            if (axioms != null && !axioms.isEmpty()) {
                Class<?> cls = loadClass(key.ownerInternalName(), loader);
                if (cls != null) {
                    if ("<init>".equals(key.name())) {
                        Constructor<?> ctor = findReflectedConstructor(cls, key.descriptor());
                        AxiomData.MemberAxiom ax = axioms.constructorAxiom(cls, ctor);
                        if (ax != null && !ax.inferredEffects()) {
                            return resultFromAxiom(
                                    ax,
                                    ctor,
                                    "curated " + ax.ownerClass().getSimpleName() + ".new");
                        }
                    } else {
                        Method m = findReflectedMethod(cls, key.name(), key.descriptor());
                        AxiomData.MemberAxiom ax = axioms.methodAxiom(cls, key.name(), m);
                        if (ax != null && !ax.inferredEffects()) {
                            return resultFromAxiom(
                                    ax,
                                    m,
                                    "curated " + ax.ownerClass().getSimpleName() + "." + ax.name());
                        }
                    }
                }
            }
            return null;
        }

        boolean curatedReturnNonNull(MethodKey key, boolean allowExact) {
            if (axioms == null || axioms.isEmpty() || "<init>".equals(key.name())) {
                return false;
            }
            Class<?> cls = loadClass(key.ownerInternalName(), loader);
            if (cls == null) {
                return false;
            }
            Method m = findReflectedMethod(cls, key.name(), key.descriptor());
            AxiomData.MemberAxiom ax = axioms.methodAxiom(cls, key.name(), m);
            return ax != null
                    && (allowExact || !ax.exact())
                    && ax.returnNullness() == JavaNullability.Nullness.NON_NULL;
        }

        private Result resultFromAxiom(AxiomData.MemberAxiom ax, Executable exec, String reason) {
            EffectChecker.JavaCallProfile prof = ax.toProfile(exec);
            boolean retNonNull =
                    exec instanceof Constructor<?>
                            || ax.returnNullness() == JavaNullability.Nullness.NON_NULL;
            return new Result(
                    prof.effects(),
                    prof.mutReceiver(),
                    prof.borrowReceiver(),
                    prof.mutParams(),
                    prof.borrowParams(),
                    prof.readsReceiver(),
                    prof.readParams(),
                    retNonNull,
                    prof.fresh(),
                    prof.view(),
                    prof.shallow(),
                    List.of(reason));
        }

        private boolean isClosedCall(
                int opcode, String ownerInternal, String methodName, String methodDesc) {
            if (opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL) {
                return true;
            }
            if (opcode == Opcodes.INVOKEVIRTUAL) {
                Class<?> cls = loadClass(ownerInternal, loader);
                if (cls == null) {
                    return false;
                }
                if (Modifier.isFinal(cls.getModifiers())) {
                    return true;
                }
                Method m = findReflectedMethod(cls, methodName, methodDesc);
                if (m != null
                        && (Modifier.isFinal(m.getModifiers())
                                || Modifier.isPrivate(m.getModifiers()))) {
                    return true;
                }
            }
            return false;
        }

        private MethodNode findMethodNode(MethodKey key) {
            ClassNode cn = loadClassNode(key.ownerInternalName(), loader);
            if (cn == null) {
                return null;
            }
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals(key.name()) && mn.desc.equals(key.descriptor())) {
                    return mn;
                }
            }
            // Method may be inherited from superclass (e.g. ArrayList.isEmpty inherited or
            // superclass helper).
            if (cn.superName != null && !"<init>".equals(key.name())) {
                return findMethodNode(new MethodKey(cn.superName, key.name(), key.descriptor()));
            }
            return null;
        }

        private boolean isFinalStaticField(String ownerInternal, String fieldName) {
            try {
                Class<?> cls = loadClass(ownerInternal, loader);
                if (cls == null) return false;
                for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField(fieldName);
                        return Modifier.isFinal(f.getModifiers())
                                && Modifier.isStatic(f.getModifiers());
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                return false;
            } catch (Throwable t) {
                return false;
            }
        }

        private boolean isFinalInstanceField(String ownerInternal, String fieldName) {
            try {
                Class<?> cls = loadClass(ownerInternal, loader);
                if (cls == null) return false;
                for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField(fieldName);
                        return Modifier.isFinal(f.getModifiers())
                                && !Modifier.isStatic(f.getModifiers());
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                return false;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    // ================================================================ ASM Interpreter for
    // ProvValue

    private static final class ProvInterpreter extends Interpreter<ProvValue> {
        private final Session session;
        private final boolean isStatic;
        private final boolean isCtor;
        private final Type[] argTypes;
        private int nextParamLocal = 0;

        ProvInterpreter(Session session, boolean isStatic, boolean isCtor, Type[] argTypes) {
            super(Opcodes.ASM9);
            this.session = session;
            this.isStatic = isStatic;
            this.isCtor = isCtor;
            this.argTypes = argTypes;
        }

        @Override
        public ProvValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
            if (isInstanceMethod && local == 0) {
                nextParamLocal = 1;
                return ProvValue.THIS_VAL;
            }
            int paramIdx = findParamIndex(isInstanceMethod, local);
            return ProvValue.ofParam(paramIdx, type);
        }

        private int findParamIndex(boolean isInstanceMethod, int local) {
            int slot = isInstanceMethod ? 1 : 0;
            for (int i = 0; i < argTypes.length; i++) {
                if (slot == local) {
                    return i;
                }
                slot += argTypes[i].getSize();
            }
            return MAX_TRACKED_PARAMS;
        }

        @Override
        public ProvValue newReturnTypeValue(Type type) {
            if (type == Type.VOID_TYPE) return null;
            return newValue(type);
        }

        @Override
        public ProvValue newEmptyValue(int local) {
            return ProvValue.UNINITIALIZED;
        }

        @Override
        public ProvValue newValue(Type type) {
            if (type == null) return ProvValue.UNINITIALIZED;
            if (type == Type.VOID_TYPE) return null;
            if (isPrimitive(type)) {
                return type.getSize() == 2 ? ProvValue.CONST_2 : ProvValue.CONST_1;
            }
            return ProvValue.EXTERNAL_NULLABLE;
        }

        @Override
        public ProvValue newOperation(AbstractInsnNode insn) {
            return switch (insn.getOpcode()) {
                case Opcodes.ACONST_NULL -> ProvValue.NULL_VAL;
                case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 ->
                        ProvValue.CONST_2;
                case Opcodes.LDC -> {
                    Object cst = ((LdcInsnNode) insn).cst;
                    if (cst instanceof Long || cst instanceof Double) yield ProvValue.CONST_2;
                    yield ProvValue.CONST_1; // non-null String/Class/Number constant
                }
                case Opcodes.GETSTATIC -> {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    Type ft = Type.getType(fi.desc);
                    if (isPrimitive(ft))
                        yield ft.getSize() == 2 ? ProvValue.CONST_2 : ProvValue.CONST_1;
                    boolean nonNull = isNonNullFinalStatic(fi.owner, fi.name);
                    yield nonNull ? ProvValue.EXTERNAL_NONNULL : ProvValue.EXTERNAL_NULLABLE;
                }
                case Opcodes.NEW -> ProvValue.FRESH_VAL;
                default -> ProvValue.CONST_1;
            };
        }

        private boolean isNonNullFinalStatic(String ownerInternal, String name) {
            try {
                Class<?> cls = loadClass(ownerInternal, session.loader);
                if (cls == null) return false;
                Field f = cls.getDeclaredField(name);
                if (Modifier.isStatic(f.getModifiers())
                        && Modifier.isFinal(f.getModifiers())
                        && Modifier.isPublic(f.getModifiers())) {
                    return f.get(null) != null;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        @Override
        public ProvValue copyOperation(AbstractInsnNode insn, ProvValue value) {
            return value;
        }

        @Override
        public ProvValue unaryOperation(AbstractInsnNode insn, ProvValue value) {
            return switch (insn.getOpcode()) {
                case Opcodes.LNEG,
                        Opcodes.DNEG,
                        Opcodes.I2L,
                        Opcodes.I2D,
                        Opcodes.F2L,
                        Opcodes.F2D,
                        Opcodes.L2D,
                        Opcodes.D2L ->
                        ProvValue.CONST_2;
                case Opcodes.NEWARRAY, Opcodes.ANEWARRAY -> ProvValue.FRESH_VAL;
                case Opcodes.CHECKCAST -> value; // preserves provenance and nullness
                case Opcodes.GETFIELD -> {
                    FieldInsnNode fi = (FieldInsnNode) insn;
                    Type ft = Type.getType(fi.desc);
                    if (isPrimitive(ft))
                        yield ft.getSize() == 2 ? ProvValue.CONST_2 : ProvValue.CONST_1;
                    // Field loaded from `this` carries PROV_THIS | PROV_STORED_ELEM.
                    long bits = value.provBits;
                    if ((bits & PROV_THIS) != 0) {
                        bits |= PROV_STORED_ELEM;
                    }
                    yield ProvValue.of(1, bits, true);
                }
                default -> ProvValue.CONST_1;
            };
        }

        @Override
        public ProvValue binaryOperation(
                AbstractInsnNode insn, ProvValue value1, ProvValue value2) {
            return switch (insn.getOpcode()) {
                case Opcodes.LALOAD,
                        Opcodes.DALOAD,
                        Opcodes.LADD,
                        Opcodes.DADD,
                        Opcodes.LSUB,
                        Opcodes.DSUB,
                        Opcodes.LMUL,
                        Opcodes.DMUL,
                        Opcodes.LDIV,
                        Opcodes.DDIV,
                        Opcodes.LREM,
                        Opcodes.DREM,
                        Opcodes.LSHL,
                        Opcodes.LSHR,
                        Opcodes.LUSHR,
                        Opcodes.LAND,
                        Opcodes.LOR,
                        Opcodes.LXOR ->
                        ProvValue.CONST_2;
                case Opcodes.AALOAD -> {
                    long bits = value1.provBits;
                    if ((bits & PROV_THIS) != 0) {
                        bits |= PROV_STORED_ELEM;
                    }
                    yield ProvValue.of(1, bits, true);
                }
                case Opcodes.PUTFIELD -> null;
                default -> ProvValue.CONST_1;
            };
        }

        @Override
        public ProvValue ternaryOperation(
                AbstractInsnNode insn, ProvValue value1, ProvValue value2, ProvValue value3) {
            return null;
        }

        @Override
        public ProvValue naryOperation(AbstractInsnNode insn, List<? extends ProvValue> values) {
            int op = insn.getOpcode();
            if (op == Opcodes.MULTIANEWARRAY) {
                return ProvValue.FRESH_VAL;
            }
            if (op == Opcodes.INVOKEDYNAMIC) {
                InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                Type ret = Type.getReturnType(indy.desc);
                if (ret == Type.VOID_TYPE) return null;
                if (isPrimitive(ret))
                    return ret.getSize() == 2 ? ProvValue.CONST_2 : ProvValue.CONST_1;
                String bsmOwner = indy.bsm != null ? indy.bsm.getOwner() : "";
                if ("java/lang/invoke/StringConcatFactory".equals(bsmOwner)
                        || "java/lang/invoke/LambdaMetafactory".equals(bsmOwner)) {
                    return ProvValue.FRESH_VAL;
                }
                return ProvValue.EXTERNAL_NULLABLE;
            }
            MethodInsnNode mi = (MethodInsnNode) insn;
            Type ret = Type.getReturnType(mi.desc);
            if (ret == Type.VOID_TYPE) return null;
            if (isPrimitive(ret)) return ret.getSize() == 2 ? ProvValue.CONST_2 : ProvValue.CONST_1;

            MethodKey calleeKey = new MethodKey(mi.owner, mi.name, mi.desc);
            Result res = session.resolveCalleeResult(op, mi, calleeKey, null);
            if (res == null) {
                return session.curatedReturnNonNull(calleeKey, false)
                        ? ProvValue.EXTERNAL_NONNULL
                        : ProvValue.EXTERNAL_NULLABLE;
            }
            boolean mayBeNull = !res.returnNonNull();
            if (res.fresh() || res.effects().uniqueResult()) {
                return ProvValue.of(1, PROV_FRESH, mayBeNull);
            }
            if (res.effects().isHermetic() && res.effects().isDeterministic()) {
                long bits = 0L;
                for (ProvValue v : values) {
                    bits |= v.provBits;
                }
                if (bits == 0L) {
                    bits = PROV_FRESH;
                }
                return ProvValue.of(1, bits, mayBeNull);
            }
            return ProvValue.of(1, PROV_EXTERNAL, mayBeNull);
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, ProvValue value, ProvValue expected) {}

        @Override
        public ProvValue merge(ProvValue v1, ProvValue v2) {
            if (v1.equals(v2)) return v1;
            int sz = v1.size == v2.size ? v1.size : 1;
            return ProvValue.of(sz, v1.provBits | v2.provBits, v1.mayBeNull | v2.mayBeNull);
        }
    }

    // ================================================================ Helpers

    private static void markParamBits(long provBits, boolean[] target) {
        long pBits = provBits >>> PROV_PARAM_SHIFT;
        int idx = 0;
        while (pBits != 0L && idx < target.length) {
            if ((pBits & 1L) != 0L) {
                target[idx] = true;
            }
            pBits >>>= 1;
            idx++;
        }
    }

    private static void clearParamBits(long provBits, boolean[] target) {
        long pBits = provBits >>> PROV_PARAM_SHIFT;
        int idx = 0;
        while (pBits != 0L && idx < target.length) {
            if ((pBits & 1L) != 0L) {
                target[idx] = false;
            }
            pBits >>>= 1;
            idx++;
        }
    }

    private static boolean allParamsBorrowed(boolean[] borrowParams) {
        for (boolean b : borrowParams) {
            if (!b) return false;
        }
        return true;
    }

    private static ClassNode loadClassNode(String ownerInternal, ClassLoader loader) {
        Map<String, ClassNode> byName;
        if (loader == null) {
            byName = BOOTSTRAP_CLASS_NODES;
        } else {
            synchronized (CLASS_NODE_CACHES) {
                byName = CLASS_NODE_CACHES.computeIfAbsent(loader, k -> new ConcurrentHashMap<>());
            }
        }
        ClassNode cached = byName.get(ownerInternal);
        if (cached != null) {
            return cached == MISSING_CLASS_NODE ? null : cached;
        }
        byte[] bytes = loadClassBytes(ownerInternal, loader);
        if (bytes == null) {
            byName.put(ownerInternal, MISSING_CLASS_NODE);
            return null;
        }
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        byName.put(ownerInternal, cn);
        return cn;
    }

    private static byte[] loadClassBytes(String ownerInternal, ClassLoader loader) {
        String resource = ownerInternal + ".class";
        ClassLoader cl = loader != null ? loader : BytecodeEffectAnalyzer.class.getClassLoader();
        try (InputStream in =
                cl != null
                        ? cl.getResourceAsStream(resource)
                        : ClassLoader.getSystemResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static Class<?> loadClass(String internalName, ClassLoader loader) {
        try {
            String binary = internalName.replace('/', '.');
            return loader != null ? Class.forName(binary, false, loader) : Class.forName(binary);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findReflectedMethod(Class<?> cls, String name, String desc) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) {
                return m;
            }
        }
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static Constructor<?> findReflectedConstructor(Class<?> cls, String desc) {
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (Type.getConstructorDescriptor(c).equals(desc)) {
                return c;
            }
        }
        return null;
    }
}
