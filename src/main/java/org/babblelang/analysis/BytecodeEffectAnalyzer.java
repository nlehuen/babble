package org.babblelang.analysis;

import org.babblelang.effects.Effect;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in ASM bytecode static effect suggester for closed Java helper methods
 * ({@code docs/design/bip007_effects.md §6.4}, {@code BIP007-P3}).
 *
 * <p>Conservative by construction: any unseeded {@code native} method, open virtual/interface
 * dispatch, synchronization, field/array/static mutation, or non-final static read drops the
 * corresponding effect guarantees and records the reason.
 */
public final class BytecodeEffectAnalyzer {

    public record Result(EffectSet effects, List<String> reasons) {
        public static final Result UNCONSTRAINED = new Result(EffectSet.EMPTY, List.of("unconstrained"));
    }

    private static final int MAX_DEPTH = 8;

    private record MethodKey(String ownerInternalName, String name, String descriptor) {
    }

    /** Seeded JVM native / JDK intrinsics known to be pure deterministic. */
    private static final Map<String, EffectSet> SEEDED_INTRINSICS = buildSeededIntrinsics();

    private static final Map<MethodKey, Result> CACHE = new ConcurrentHashMap<>();

    private BytecodeEffectAnalyzer() {
    }

    private static Map<String, EffectSet> buildSeededIntrinsics() {
        Map<String, EffectSet> map = new HashMap<>();
        EffectSet pureDetNothrow = EffectSet.ofInferred(
                EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW), false, true, false, true);
        EffectSet pureDet = EffectSet.ofInferred(
                EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC), false, true, false, true);

        for (String m : List.of("abs", "min", "max", "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
                "exp", "log", "log10", "log1p", "expm1", "sqrt", "cbrt", "pow", "hypot", "ceil", "floor",
                "rint", "round", "signum", "copySign", "ulp", "sinh", "cosh", "tanh", "toRadians", "toDegrees",
                "fma", "scalb", "nextUp", "nextDown", "IEEEremainder")) {
            map.put("java/lang/Math#" + m, pureDetNothrow);
            map.put("java/lang/StrictMath#" + m, pureDetNothrow);
        }
        for (String m : List.of("addExact", "subtractExact", "multiplyExact", "negateExact",
                "floorDiv", "floorMod", "toIntExact")) {
            map.put("java/lang/Math#" + m, pureDet);
            map.put("java/lang/StrictMath#" + m, pureDet);
        }
        for (String m : List.of("doubleToRawLongBits", "doubleToLongBits", "longBitsToDouble", "isNaN", "isInfinite", "isFinite", "compare")) {
            map.put("java/lang/Double#" + m, pureDetNothrow);
        }
        for (String m : List.of("floatToRawIntBits", "floatToIntBits", "intBitsToFloat", "isNaN", "isInfinite", "isFinite", "compare")) {
            map.put("java/lang/Float#" + m, pureDetNothrow);
        }
        for (String m : List.of("valueOf", "compare", "compareUnsigned", "bitCount", "highestOneBit",
                "lowestOneBit", "numberOfLeadingZeros", "numberOfTrailingZeros", "rotateLeft", "rotateRight",
                "reverse", "reverseBytes", "signum", "sum", "max", "min", "intValue", "longValue", "doubleValue")) {
            map.put("java/lang/Integer#" + m, pureDetNothrow);
            map.put("java/lang/Long#" + m, pureDetNothrow);
        }
        for (String m : List.of("length", "isEmpty", "isBlank", "trim", "strip", "toLowerCase", "toUpperCase",
                "startsWith", "endsWith", "contains", "indexOf", "lastIndexOf", "equals", "equalsIgnoreCase",
                "compareTo", "hashCode", "toString", "concat", "valueOf")) {
            map.put("java/lang/String#" + m, pureDetNothrow);
        }
        for (String m : List.of("charAt", "substring", "codePointAt")) {
            map.put("java/lang/String#" + m, pureDet);
        }
        return Collections.unmodifiableMap(map);
    }

    /** Analyzes a single Java {@link Method} and returns its conservative inferred {@link EffectSet} and reasons. */
    public static Result analyze(Method method) {
        if (method == null) {
            return Result.UNCONSTRAINED;
        }
        Class<?> owner = method.getDeclaringClass();
        String ownerInternal = Type.getInternalName(owner);
        String desc = Type.getMethodDescriptor(method);
        MethodKey key = new MethodKey(ownerInternal, method.getName(), desc);
        Result cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Result computed = analyzeInternal(ownerInternal, method.getName(), desc, owner.getClassLoader(),
                new HashSet<>(), 0);
        CACHE.put(key, computed);
        return computed;
    }

    /** Analyzes all declared public/package methods of {@code cls}. */
    public static Map<Method, Result> analyzeClass(Class<?> cls) {
        Map<Method, Result> results = new LinkedHashMap<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.isSynthetic() && !m.isBridge()) {
                results.put(m, analyze(m));
            }
        }
        return results;
    }

    /** Formats a ready-to-paste Babble {@code axiom ... on Class.method} suggestion, or {@code null} if unconstrained. */
    public static String suggestAxiom(Method method) {
        Result r = analyze(method);
        EffectSet eff = r.effects();
        if (eff.isUnconstrained()) {
            return null;
        }
        String prefix = eff.formatFullPrefix(false).trim();
        if (prefix.isEmpty()) {
            return null;
        }
        return "axiom " + prefix + " on " + method.getDeclaringClass().getName() + "." + method.getName();
    }

    private static Result analyzeInternal(String ownerInternal, String name, String desc,
                                          ClassLoader loader, Set<MethodKey> visiting, int depth) {
        EffectSet seeded = SEEDED_INTRINSICS.get(ownerInternal + "#" + name);
        if (seeded != null) {
            return new Result(seeded, List.of());
        }
        MethodKey key = new MethodKey(ownerInternal, name, desc);
        if (depth > MAX_DEPTH) {
            return new Result(EffectSet.EMPTY, List.of("max call depth exceeded"));
        }
        if (!visiting.add(key)) {
            // Recursive cycle: keep pure/hermetic/deterministic/nothrow neutral, drop @TERMINATING.
            return new Result(EffectSet.ofInferred(
                    EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                    false, true, false, false), List.of("recursive cycle"));
        }
        try {
            byte[] bytecode = loadClassBytes(ownerInternal, loader);
            if (bytecode == null) {
                return new Result(EffectSet.EMPTY, List.of("bytecode unavailable for " + ownerInternal));
            }
            MethodScan scan = scanMethod(bytecode, name, desc, loader, visiting, depth);
            if (!scan.found) {
                return new Result(EffectSet.EMPTY, List.of("method not found in bytecode: " + ownerInternal + "." + name));
            }
            if (scan.isNative || scan.isAbstract) {
                return new Result(EffectSet.EMPTY, List.of(scan.isNative ? "native method" : "abstract method"));
            }
            EnumSet<Effect> effs = EnumSet.noneOf(Effect.class);
            if (scan.hermetic) {
                effs.add(Effect.HERMETIC);
                if (!scan.mutatesObject) {
                    effs.add(Effect.PURE);
                }
            }
            if (scan.deterministic) {
                effs.add(Effect.DETERMINISTIC);
            }
            if (scan.nothrow) {
                effs.add(Effect.NOTHROW);
            }
            EffectSet set = EffectSet.ofInferred(effs, false, scan.hermetic && !scan.mutatesObject, false, scan.terminating);
            return new Result(set, List.copyOf(scan.reasons));
        } finally {
            visiting.remove(key);
        }
    }

    private static byte[] loadClassBytes(String ownerInternal, ClassLoader loader) {
        String resource = ownerInternal + ".class";
        ClassLoader cl = loader != null ? loader : BytecodeEffectAnalyzer.class.getClassLoader();
        try (InputStream in = cl != null ? cl.getResourceAsStream(resource) : ClassLoader.getSystemResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static final class MethodScan {
        boolean found;
        boolean isNative;
        boolean isAbstract;
        boolean hermetic = true;
        boolean mutatesObject = false;
        boolean deterministic = true;
        boolean nothrow = true;
        boolean terminating = true;
        final List<String> reasons = new ArrayList<>();
    }

    private static MethodScan scanMethod(byte[] bytecode, String targetName, String targetDesc,
                                         ClassLoader loader, Set<MethodKey> visiting, int depth) {
        MethodScan scan = new MethodScan();
        ClassReader reader = new ClassReader(bytecode);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!name.equals(targetName) || !descriptor.equals(targetDesc)) {
                    return null;
                }
                scan.found = true;
                if ((access & Opcodes.ACC_NATIVE) != 0) {
                    scan.isNative = true;
                    return null;
                }
                if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                    scan.isAbstract = true;
                    return null;
                }
                if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                    scan.hermetic = false;
                    scan.deterministic = false;
                    scan.nothrow = false;
                    scan.reasons.add("synchronized method");
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    private final Set<org.objectweb.asm.Label> seenLabels = new HashSet<>();

                    @Override
                    public void visitLabel(org.objectweb.asm.Label label) {
                        seenLabels.add(label);
                    }

                    @Override
                    public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        if (seenLabels.contains(label)) {
                            // Backward branch -> loop -> cannot prove @TERMINATING.
                            scan.terminating = false;
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        switch (opcode) {
                            case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
                                 Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> {
                                scan.hermetic = false;
                                scan.mutatesObject = true;
                                scan.nothrow = false;
                                scan.reasons.add("array store");
                            }
                            case Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD,
                                 Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD, Opcodes.SALOAD,
                                 Opcodes.ARRAYLENGTH -> {
                                scan.nothrow = false;
                                scan.reasons.add("array access may throw");
                            }
                            case Opcodes.IDIV, Opcodes.IREM, Opcodes.LDIV, Opcodes.LREM -> {
                                scan.nothrow = false;
                                scan.reasons.add("integer division/remainder may throw");
                            }
                            case Opcodes.ATHROW -> {
                                scan.nothrow = false;
                                scan.reasons.add("explicit throw");
                            }
                            case Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> {
                                scan.hermetic = false;
                                scan.deterministic = false;
                                scan.nothrow = false;
                                scan.reasons.add("monitor synchronization");
                            }
                            default -> {
                            }
                        }
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.CHECKCAST) {
                            scan.nothrow = false;
                            scan.reasons.add("checkcast may throw");
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                        switch (opcode) {
                            case Opcodes.PUTSTATIC -> {
                                scan.hermetic = false;
                                scan.mutatesObject = true;
                                scan.deterministic = false;
                                scan.reasons.add("static field write " + owner + "." + fieldName);
                            }
                            case Opcodes.PUTFIELD -> {
                                scan.hermetic = false;
                                scan.mutatesObject = true;
                                scan.nothrow = false;
                                scan.reasons.add("instance field write " + owner + "." + fieldName);
                            }
                            case Opcodes.GETSTATIC -> {
                                if (!isFinalStaticField(owner, fieldName, loader)) {
                                    scan.deterministic = false;
                                    scan.reasons.add("non-final static field read " + owner + "." + fieldName);
                                }
                            }
                            case Opcodes.GETFIELD -> {
                                // Conservative on NPE unless proven non-null.
                                scan.nothrow = false;
                            }
                            default -> {
                            }
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
                        EffectSet seeded = SEEDED_INTRINSICS.get(owner + "#" + mName);
                        if (seeded != null) {
                            mergeCallee(scan, seeded);
                            return;
                        }
                        boolean closed = opcode == Opcodes.INVOKESTATIC
                                || opcode == Opcodes.INVOKESPECIAL
                                || (opcode == Opcodes.INVOKEVIRTUAL && isClosedVirtualTarget(owner, mName, loader));
                        if (!closed) {
                            scan.hermetic = false;
                            scan.deterministic = false;
                            scan.nothrow = false;
                            scan.terminating = false;
                            scan.reasons.add("open dispatch " + owner + "." + mName);
                            return;
                        }
                        if ("<init>".equals(mName) && "java/lang/Object".equals(owner)) {
                            return;
                        }
                        Result sub = analyzeInternal(owner, mName, mDesc, loader, visiting, depth + 1);
                        mergeCallee(scan, sub.effects());
                        if (!sub.reasons().isEmpty()) {
                            scan.reasons.addAll(sub.reasons());
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String mName, String mDesc, org.objectweb.asm.Handle bsm, Object... bsmArgs) {
                        // Allow StringConcatFactory (`makeConcatWithConstants`) as pure deterministic nothrow.
                        if ("java/lang/invoke/StringConcatFactory".equals(bsm.getOwner())) {
                            return;
                        }
                        scan.hermetic = false;
                        scan.deterministic = false;
                        scan.nothrow = false;
                        scan.terminating = false;
                        scan.reasons.add("invokedynamic " + mName);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return scan;
    }

    private static void mergeCallee(MethodScan scan, EffectSet callee) {
        if (!callee.isHermetic()) {
            scan.hermetic = false;
        }
        if (!callee.isPure()) {
            scan.mutatesObject = true;
        }
        if (!callee.isDeterministic()) {
            scan.deterministic = false;
        }
        if (!callee.isNothrow()) {
            scan.nothrow = false;
        }
        if (!callee.terminating()) {
            scan.terminating = false;
        }
    }

    private static boolean isFinalStaticField(String ownerInternal, String fieldName, ClassLoader loader) {
        try {
            Class<?> cls = loadClass(ownerInternal, loader);
            if (cls == null) {
                return false;
            }
            Field f = cls.getDeclaredField(fieldName);
            return Modifier.isFinal(f.getModifiers()) && Modifier.isStatic(f.getModifiers());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isClosedVirtualTarget(String ownerInternal, String methodName, ClassLoader loader) {
        try {
            Class<?> cls = loadClass(ownerInternal, loader);
            if (cls == null) {
                return false;
            }
            if (Modifier.isFinal(cls.getModifiers())) {
                return true;
            }
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && Modifier.isFinal(m.getModifiers())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
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
}
