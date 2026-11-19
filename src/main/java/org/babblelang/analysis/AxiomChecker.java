package org.babblelang.analysis;

import org.babblelang.effects.Effect;
import org.babblelang.parser.BabbleParser;
import org.babblelang.runtime.BabbleClass;
import org.babblelang.runtime.Callable;
import org.babblelang.runtime.ClassShape;
import org.babblelang.runtime.ScriptRuntime;
import org.babblelang.runtime.Slot;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Property-based axiom fuzzing for {@code axiom}-declared Babble functions
 * ({@code docs/design/bip007_effects.md §5.4}, {@code BIP007-P3}).
 *
 * <p>Activated via {@code babble.checkAxioms=true} (engine attribute or system property) or
 * invoked programmatically via {@link #check(ScopeAnalysis, BabbleParser.FileContext, ScriptRuntime)}.
 */
public final class AxiomChecker {

    public static final String PROPERTY = "babble.checkAxioms";
    public static final long DEFAULT_SEED = 0xBABB1E07L;
    public static final int DEFAULT_SAMPLES = 32;

    public record Failure(String functionName, Effect axiom, String detail) {
        @Override
        public String toString() {
            return "Axiom violation in " + functionName + " (" + EffectSet.keywordOf(axiom) + ") : " + detail;
        }
    }

    public record Skipped(String functionName, String reason) {
    }

    public record Report(int checkedFunctions, int checkedProperties, List<Failure> failures, List<Skipped> skipped) {
        public boolean ok() {
            return failures.isEmpty();
        }
    }

    private final long seed;
    private final int samples;
    private final Map<String, Function<Random, Object>> customGenerators = new HashMap<>();

    public AxiomChecker() {
        this(DEFAULT_SEED, DEFAULT_SAMPLES);
    }

    public AxiomChecker(long seed, int samples) {
        this.seed = seed;
        this.samples = Math.max(1, samples);
    }

    /** Registers a custom generator keyed by Babble type name (e.g. a class or Java type name). */
    public AxiomChecker registerGenerator(String typeName, Function<Random, Object> generator) {
        customGenerators.put(Objects.requireNonNull(typeName, "typeName"), Objects.requireNonNull(generator, "generator"));
        return this;
    }

    private static Object lookupTopLevel(ScriptRuntime rt, String name) {
        if (rt == null || rt.root() == null) {
            return null;
        }
        Slot<Object> slot = rt.root().get(name);
        return slot != null ? slot.get() : null;
    }

    /** Fuzz-checks all top-level {@code axiom}-declared Babble functions in {@code file}. */
    public Report check(ScopeAnalysis analysis, BabbleParser.FileContext file, ScriptRuntime runtime) {
        Random rng = new Random(seed);
        int checkedFunctions = 0;
        int checkedProperties = 0;
        List<Failure> failures = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();

        for (BabbleParser.ExpressionContext expr : file.expression()) {
            if (!(expr instanceof BabbleParser.DefExpressionContext def)
                    || !(def.value instanceof BabbleParser.FunctionLiteralContext fn)) {
                continue;
            }
            EffectSet effects = analysis.callableEffects(fn);
            if (!effects.axiomatic()) {
                continue;
            }
            String name = def.name.getText();
            Object val = lookupTopLevel(runtime, name);
            if (!(val instanceof Callable callable)) {
                skipped.add(new Skipped(name, "binding not callable"));
                continue;
            }
            Type rawType = analysis.callableType(fn);
            if (!(rawType instanceof Type.Function fnType)) {
                skipped.add(new Skipped(name, "unknown function type"));
                continue;
            }
            List<Type> paramTypes = fnType.parameters().stream().map(Type.Parameter::type).toList();
            String unsupported = firstUnsupportedParam(paramTypes, analysis, runtime);
            if (unsupported != null) {
                skipped.add(new Skipped(name, "unsupported parameter type : " + unsupported));
                continue;
            }
            checkedFunctions++;
            Set<Effect> effs = effects.effects();
            if (effs.contains(Effect.NOTHROW)) {
                checkedProperties++;
                checkNothrow(name, callable, paramTypes, analysis, runtime, rng, failures);
            }
            if (effs.contains(Effect.DETERMINISTIC)) {
                checkedProperties++;
                checkDeterministic(name, callable, paramTypes, analysis, runtime, rng, failures);
            }
            if (effs.contains(Effect.COMMUTATIVE) && paramTypes.size() == 2) {
                checkedProperties++;
                checkCommutative(name, callable, paramTypes, analysis, runtime, rng, failures);
            }
            if (effs.contains(Effect.ASSOCIATIVE) && paramTypes.size() == 2) {
                checkedProperties++;
                checkAssociative(name, callable, paramTypes, analysis, runtime, rng, failures);
            }
            if (effs.contains(Effect.IDEMPOTENT) && paramTypes.size() == 1) {
                checkedProperties++;
                checkIdempotent(name, callable, paramTypes.get(0), analysis, runtime, rng, failures);
            }
            if (effs.contains(Effect.MONOTONE) && paramTypes.size() == 1) {
                checkedProperties++;
                checkMonotone(name, callable, paramTypes.get(0), analysis, runtime, rng, failures);
            }
            if (effs.contains(Effect.INJECTIVE) && paramTypes.size() == 1) {
                checkedProperties++;
                checkInjective(name, callable, paramTypes.get(0), analysis, runtime, rng, failures);
            }
        }
        return new Report(checkedFunctions, checkedProperties, List.copyOf(failures), List.copyOf(skipped));
    }

    private void checkNothrow(String name, Callable fn, List<Type> paramTypes, ScopeAnalysis analysis,
                              ScriptRuntime rt, Random rng, List<Failure> failures) {
        for (int i = 0; i < samples; i++) {
            Object[] args = generateArgs(paramTypes, i, analysis, rt, rng);
            try {
                fn.call(rt, args, null, null);
            } catch (Throwable t) {
                failures.add(new Failure(name, Effect.NOTHROW,
                        "threw " + t.getClass().getSimpleName() + "(" + t.getMessage() + ") on " + Arrays.toString(args)));
                return;
            }
        }
    }

    private void checkDeterministic(String name, Callable fn, List<Type> paramTypes, ScopeAnalysis analysis,
                                    ScriptRuntime rt, Random rng, List<Failure> failures) {
        for (int i = 0; i < samples; i++) {
            Object[] args1 = generateArgs(paramTypes, i, analysis, rt, rng);
            Object[] args2 = deepCopyArgs(args1);
            try {
                Object r1 = fn.call(rt, args1, null, null);
                Object r2 = fn.call(rt, args2, null, null);
                if (!valuesEqual(r1, r2)) {
                    failures.add(new Failure(name, Effect.DETERMINISTIC,
                            "returned " + r1 + " then " + r2 + " for identical input " + Arrays.toString(args1)));
                    return;
                }
            } catch (Throwable ignored) {
                // Non-nothrow functions may throw on some inputs; deterministic check compares normal outputs.
            }
        }
    }

    private void checkCommutative(String name, Callable fn, List<Type> paramTypes, ScopeAnalysis analysis,
                                  ScriptRuntime rt, Random rng, List<Failure> failures) {
        for (int i = 0; i < samples; i++) {
            Object a = generate(paramTypes.get(0), i, analysis, rt, rng, 0);
            Object b = generate(paramTypes.get(1), i + 1, analysis, rt, rng, 0);
            try {
                Object ab = fn.call(rt, new Object[]{deepCopy(a), deepCopy(b)}, null, null);
                Object ba = fn.call(rt, new Object[]{deepCopy(b), deepCopy(a)}, null, null);
                if (!valuesEqual(ab, ba)) {
                    failures.add(new Failure(name, Effect.COMMUTATIVE,
                            "f(" + a + ", " + b + ") = " + ab + " != f(" + b + ", " + a + ") = " + ba));
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void checkAssociative(String name, Callable fn, List<Type> paramTypes, ScopeAnalysis analysis,
                                  ScriptRuntime rt, Random rng, List<Failure> failures) {
        Type t = paramTypes.get(0);
        for (int i = 0; i < samples; i++) {
            Object a = generate(t, i, analysis, rt, rng, 0);
            Object b = generate(t, i + 1, analysis, rt, rng, 0);
            Object c = generate(t, i + 2, analysis, rt, rng, 0);
            try {
                Object ab = fn.call(rt, new Object[]{deepCopy(a), deepCopy(b)}, null, null);
                Object lhs = fn.call(rt, new Object[]{ab, deepCopy(c)}, null, null);
                Object bc = fn.call(rt, new Object[]{deepCopy(b), deepCopy(c)}, null, null);
                Object rhs = fn.call(rt, new Object[]{deepCopy(a), bc}, null, null);
                if (!valuesEqual(lhs, rhs)) {
                    failures.add(new Failure(name, Effect.ASSOCIATIVE,
                            "f(f(" + a + ", " + b + "), " + c + ") = " + lhs
                                    + " != f(" + a + ", f(" + b + ", " + c + ")) = " + rhs));
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void checkIdempotent(String name, Callable fn, Type paramType, ScopeAnalysis analysis,
                                 ScriptRuntime rt, Random rng, List<Failure> failures) {
        for (int i = 0; i < samples; i++) {
            Object x = generate(paramType, i, analysis, rt, rng, 0);
            try {
                Object fx = fn.call(rt, new Object[]{deepCopy(x)}, null, null);
                Object ffx = fn.call(rt, new Object[]{deepCopy(fx)}, null, null);
                if (!valuesEqual(fx, ffx)) {
                    failures.add(new Failure(name, Effect.IDEMPOTENT,
                            "f(" + x + ") = " + fx + " != f(f(" + x + ")) = " + ffx));
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void checkMonotone(String name, Callable fn, Type paramType, ScopeAnalysis analysis,
                               ScriptRuntime rt, Random rng, List<Failure> failures) {
        for (int i = 0; i < samples; i++) {
            Object a = generate(paramType, i, analysis, rt, rng, 0);
            Object b = generate(paramType, i + 1, analysis, rt, rng, 0);
            if (!(a instanceof Comparable ca) || !(b instanceof Comparable cb)) {
                continue;
            }
            if (ca.compareTo(cb) > 0) {
                Object tmp = a;
                a = b;
                b = tmp;
            }
            try {
                Object fa = fn.call(rt, new Object[]{deepCopy(a)}, null, null);
                Object fb = fn.call(rt, new Object[]{deepCopy(b)}, null, null);
                if (fa instanceof Comparable cfa && fb instanceof Comparable cfb && cfa.compareTo(cfb) > 0) {
                    failures.add(new Failure(name, Effect.MONOTONE,
                            a + " <= " + b + " but f(" + a + ") = " + fa + " > f(" + b + ") = " + fb));
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void checkInjective(String name, Callable fn, Type paramType, ScopeAnalysis analysis,
                                ScriptRuntime rt, Random rng, List<Failure> failures) {
        Map<Object, Object> seenOutputs = new HashMap<>();
        for (int i = 0; i < samples; i++) {
            Object x = generate(paramType, i, analysis, rt, rng, 0);
            try {
                Object fx = fn.call(rt, new Object[]{deepCopy(x)}, null, null);
                Object prev = seenOutputs.get(fx);
                if (prev != null && !valuesEqual(prev, x)) {
                    failures.add(new Failure(name, Effect.INJECTIVE,
                            "f(" + prev + ") == f(" + x + ") == " + fx));
                    return;
                }
                if (fx != null) {
                    seenOutputs.put(fx, x);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- generators

    private String firstUnsupportedParam(List<Type> types, ScopeAnalysis analysis, ScriptRuntime rt) {
        for (Type t : types) {
            if (!isSupported(t, analysis, rt, 0)) {
                return t.toString();
            }
        }
        return null;
    }

    private boolean isSupported(Type t, ScopeAnalysis analysis, ScriptRuntime rt, int depth) {
        if (depth > 4 || t == null || t == Type.ERROR) {
            return false;
        }
        if (t instanceof Type.Frozen f) {
            return isSupported(f.of(), analysis, rt, depth);
        }
        if (Type.isCoreScalar(t) || t == Type.NULL) {
            return true;
        }
        if (customGenerators.containsKey(t.toString())) {
            return true;
        }
        if (t instanceof Type.Union u) {
            return u.members().stream().allMatch(m -> isSupported(m, analysis, rt, depth + 1));
        }
        if (t instanceof Type.JavaInstance ji) {
            Class<?> cls = ji.type();
            if (cls == List.class || cls == Set.class || cls == SortedSet.class) {
                return ji.typeArguments().size() == 1 && isSupported(ji.typeArguments().get(0), analysis, rt, depth + 1);
            }
            if (cls == Map.class || cls == SortedMap.class || cls == Map.Entry.class) {
                return ji.typeArguments().size() == 2
                        && isSupported(ji.typeArguments().get(0), analysis, rt, depth + 1)
                        && isSupported(ji.typeArguments().get(1), analysis, rt, depth + 1);
            }
            return customGenerators.containsKey(cls.getName());
        }
        if (t instanceof Type.Instance inst && inst.node().kind == ClassShape.Kind.VALUE_CLASS) {
            if (customGenerators.containsKey(inst.node().name)) {
                return true;
            }
            if (!(lookupTopLevel(rt, inst.node().name) instanceof BabbleClass)) {
                return false;
            }
            List<Type> paramTypes = recordParamTypes(inst.node(), analysis);
            if (paramTypes == null) {
                return false;
            }
            for (Type pt : paramTypes) {
                if (!isSupported(pt, analysis, rt, depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private List<Type> recordParamTypes(TypeNode node, ScopeAnalysis analysis) {
        TypedModuleSummary summary = analysis.pass().summary;
        if (summary == null) {
            return null;
        }
        List<Type> out = new ArrayList<>(node.parameters.size());
        for (String paramName : node.parameters) {
            TypeNode.Member m = node.members.get(paramName);
            if (m == null) {
                return null;
            }
            Type mt = summary.memberType(node, paramName);
            if (mt == null) {
                return null;
            }
            out.add(mt);
        }
        return out;
    }

    private Object[] generateArgs(List<Type> paramTypes, int sampleIdx, ScopeAnalysis analysis,
                                  ScriptRuntime rt, Random rng) {
        Object[] args = new Object[paramTypes.size()];
        for (int i = 0; i < paramTypes.size(); i++) {
            args[i] = generate(paramTypes.get(i), sampleIdx + i, analysis, rt, rng, 0);
        }
        return args;
    }

    private Object generate(Type t, int sampleIdx, ScopeAnalysis analysis,
                            ScriptRuntime rt, Random rng, int depth) {
        if (t instanceof Type.Frozen f) {
            return generate(f.of(), sampleIdx, analysis, rt, rng, depth);
        }
        Function<Random, Object> custom = customGenerators.get(t.toString());
        if (custom != null) {
            return custom.apply(rng);
        }
        if (t == Type.NULL) {
            return null;
        }
        if (t == Type.BOOL) {
            return sampleIdx == 0 ? Boolean.FALSE : (sampleIdx == 1 ? Boolean.TRUE : rng.nextBoolean());
        }
        if (t == Type.BYTE) {
            return switch (sampleIdx) {
                case 0 -> (byte) 0;
                case 1 -> (byte) 1;
                case 2 -> (byte) -1;
                case 3 -> Byte.MIN_VALUE;
                case 4 -> Byte.MAX_VALUE;
                default -> (byte) (rng.nextInt(41) - 20);
            };
        }
        if (t == Type.SHORT) {
            return switch (sampleIdx) {
                case 0 -> (short) 0;
                case 1 -> (short) 1;
                case 2 -> (short) -1;
                case 3 -> Short.MIN_VALUE;
                case 4 -> Short.MAX_VALUE;
                default -> (short) (rng.nextInt(201) - 100);
            };
        }
        if (t == Type.INT) {
            return switch (sampleIdx) {
                case 0 -> 0;
                case 1 -> 1;
                case 2 -> -1;
                case 3 -> 42;
                case 4 -> -42;
                default -> rng.nextInt(201) - 100;
            };
        }
        if (t == Type.LONG) {
            return switch (sampleIdx) {
                case 0 -> 0L;
                case 1 -> 1L;
                case 2 -> -1L;
                case 3 -> 1000L;
                case 4 -> -1000L;
                default -> (long) (rng.nextInt(2001) - 1000);
            };
        }
        if (t == Type.FLOAT) {
            return switch (sampleIdx) {
                case 0 -> 0.0f;
                case 1 -> 1.0f;
                case 2 -> -1.0f;
                case 3 -> 0.5f;
                default -> (float) (rng.nextInt(41) - 20) / 2.0f;
            };
        }
        if (t == Type.DOUBLE) {
            return switch (sampleIdx) {
                case 0 -> 0.0;
                case 1 -> 1.0;
                case 2 -> -1.0;
                case 3 -> 0.5;
                default -> (rng.nextInt(41) - 20) / 2.0;
            };
        }
        if (t == Type.CHAR) {
            return switch (sampleIdx) {
                case 0 -> 'a';
                case 1 -> 'Z';
                case 2 -> '0';
                default -> (char) ('a' + rng.nextInt(26));
            };
        }
        if (t == Type.STRING) {
            return switch (sampleIdx) {
                case 0 -> "";
                case 1 -> "a";
                case 2 -> "  hi  ";
                case 3 -> "abc";
                default -> {
                    int len = rng.nextInt(6);
                    StringBuilder sb = new StringBuilder(len);
                    for (int k = 0; k < len; k++) {
                        sb.append((char) ('a' + rng.nextInt(6)));
                    }
                    yield sb.toString();
                }
            };
        }
        if (t instanceof Type.Union u) {
            List<Type> members = new ArrayList<>(u.members());
            Type pick = members.get(Math.floorMod(sampleIdx, members.size()));
            return generate(pick, sampleIdx / Math.max(1, members.size()), analysis, rt, rng, depth + 1);
        }
        if (t instanceof Type.JavaInstance ji) {
            Class<?> cls = ji.type();
            Function<Random, Object> clsGen = customGenerators.get(cls.getName());
            if (clsGen != null) {
                return clsGen.apply(rng);
            }
            int size = depth >= 2 ? 0 : rng.nextInt(4);
            if (cls == List.class) {
                List<Object> list = new ArrayList<>(size);
                Type elem = ji.typeArguments().get(0);
                for (int k = 0; k < size; k++) {
                    list.add(generate(elem, sampleIdx + k, analysis, rt, rng, depth + 1));
                }
                return list;
            }
            if (cls == Set.class) {
                Set<Object> set = new LinkedHashSet<>();
                Type elem = ji.typeArguments().get(0);
                for (int k = 0; k < size; k++) {
                    set.add(generate(elem, sampleIdx + k, analysis, rt, rng, depth + 1));
                }
                return set;
            }
            if (cls == SortedSet.class) {
                SortedSet<Object> set = new TreeSet<>();
                Type elem = ji.typeArguments().get(0);
                for (int k = 0; k < size; k++) {
                    Object v = generate(elem, sampleIdx + k, analysis, rt, rng, depth + 1);
                    if (v != null) {
                        set.add(v);
                    }
                }
                return set;
            }
            if (cls == Map.class) {
                Map<Object, Object> map = new LinkedHashMap<>();
                Type kt = ji.typeArguments().get(0);
                Type vt = ji.typeArguments().get(1);
                for (int k = 0; k < size; k++) {
                    map.put(generate(kt, sampleIdx + k, analysis, rt, rng, depth + 1),
                            generate(vt, sampleIdx + k + 7, analysis, rt, rng, depth + 1));
                }
                return map;
            }
            if (cls == SortedMap.class) {
                SortedMap<Object, Object> map = new TreeMap<>();
                Type kt = ji.typeArguments().get(0);
                Type vt = ji.typeArguments().get(1);
                for (int k = 0; k < size; k++) {
                    Object key = generate(kt, sampleIdx + k, analysis, rt, rng, depth + 1);
                    if (key != null) {
                        map.put(key, generate(vt, sampleIdx + k + 7, analysis, rt, rng, depth + 1));
                    }
                }
                return map;
            }
            if (cls == Map.Entry.class) {
                Type kt = ji.typeArguments().get(0);
                Type vt = ji.typeArguments().get(1);
                return new AbstractMap.SimpleImmutableEntry<>(
                        generate(kt, sampleIdx, analysis, rt, rng, depth + 1),
                        generate(vt, sampleIdx + 1, analysis, rt, rng, depth + 1));
            }
        }
        if (t instanceof Type.Instance inst && inst.node().kind == ClassShape.Kind.VALUE_CLASS) {
            Function<Random, Object> recGen = customGenerators.get(inst.node().name);
            if (recGen != null) {
                return recGen.apply(rng);
            }
            BabbleClass cls = (BabbleClass) lookupTopLevel(rt, inst.node().name);
            List<Type> pTypes = recordParamTypes(inst.node(), analysis);
            Object[] ctorArgs = new Object[pTypes.size()];
            for (int k = 0; k < pTypes.size(); k++) {
                ctorArgs[k] = generate(pTypes.get(k), sampleIdx + k, analysis, rt, rng, depth + 1);
            }
            return cls.call(rt, ctorArgs, null, null);
        }
        return null;
    }

    private static Object[] deepCopyArgs(Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = deepCopy(args[i]);
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object deepCopy(Object v) {
        if (v instanceof List<?> l) {
            List<Object> copy = new ArrayList<>(l.size());
            for (Object e : l) {
                copy.add(deepCopy(e));
            }
            return copy;
        }
        if (v instanceof SortedSet<?> ss) {
            SortedSet<Object> copy = new TreeSet<>((java.util.Comparator) ss.comparator());
            for (Object e : ss) {
                copy.add(deepCopy(e));
            }
            return copy;
        }
        if (v instanceof Set<?> s) {
            Set<Object> copy = new LinkedHashSet<>();
            for (Object e : s) {
                copy.add(deepCopy(e));
            }
            return copy;
        }
        if (v instanceof SortedMap<?, ?> sm) {
            SortedMap<Object, Object> copy = new TreeMap<>((java.util.Comparator) sm.comparator());
            for (Map.Entry<?, ?> e : sm.entrySet()) {
                copy.put(deepCopy(e.getKey()), deepCopy(e.getValue()));
            }
            return copy;
        }
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                copy.put(deepCopy(e.getKey()), deepCopy(e.getValue()));
            }
            return copy;
        }
        return v;
    }

    private static boolean valuesEqual(Object a, Object b) {
        if (a instanceof Double da && b instanceof Double db) {
            return Double.compare(da, db) == 0 || Math.abs(da - db) < 1e-9;
        }
        if (a instanceof Float fa && b instanceof Float fb) {
            return Float.compare(fa, fb) == 0 || Math.abs(fa - fb) < 1e-5f;
        }
        return Objects.equals(a, b);
    }
}
