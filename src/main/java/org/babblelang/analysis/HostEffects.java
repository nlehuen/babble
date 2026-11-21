package org.babblelang.analysis;

import org.babblelang.effects.Effect;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-engine host effect policy and Java target overrides (BIP007 §3.1, §6.2 ; BIP016-D8).
 *
 * <p>Also captures target keys shared by file-level, module-exported, and REPL-session
 * {@code axiom} / {@code io} tables.
 */
public final class HostEffects {

    /** Kind of Java target addressed by an {@code axiom}, {@code io}, or host registration. */
    public enum TargetKind {
        METHOD,
        CONSTRUCTOR,
        CLASS
    }

    /** Canonical key for a Java class, constructor family, or named method family. */
    public record JavaTarget(Class<?> owner, TargetKind kind, String methodName) {
        public JavaTarget {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(kind, "kind");
            if (kind == TargetKind.METHOD) {
                Objects.requireNonNull(methodName, "methodName");
            } else {
                methodName = null;
            }
        }

        public static JavaTarget ofClass(Class<?> owner) {
            return new JavaTarget(owner, TargetKind.CLASS, null);
        }

        public static JavaTarget ofConstructor(Class<?> owner) {
            return new JavaTarget(owner, TargetKind.CONSTRUCTOR, null);
        }

        public static JavaTarget ofMethod(Class<?> owner, String methodName) {
            return new JavaTarget(owner, TargetKind.METHOD, methodName);
        }
    }

    /** Resolved override entry for a {@link JavaTarget}. */
    public record OverrideEntry(Set<Effect> effects,
                                boolean mutReceiver,
                                boolean borrowReceiver,
                                boolean uniqueResult,
                                boolean io,
                                boolean volatileEffect) {
        public OverrideEntry(Set<Effect> effects, boolean mutReceiver, boolean borrowReceiver, boolean io) {
            this(effects, mutReceiver, borrowReceiver, false, io, false);
        }

        public OverrideEntry(Set<Effect> effects, boolean mutReceiver, boolean borrowReceiver, boolean uniqueResult, boolean io) {
            this(effects, mutReceiver, borrowReceiver, uniqueResult, io, false);
        }

        public OverrideEntry {
            EnumSet<Effect> copy = effects.isEmpty() ? EnumSet.noneOf(Effect.class) : EnumSet.copyOf(effects);
            copy.remove(Effect.MUT);
            copy.remove(Effect.BORROW);
            copy.remove(Effect.SHARED);
            copy.remove(Effect.UNIQUE);
            copy.remove(Effect.VOLATILE);
            copy.remove(Effect.IO);
            if (io) {
                copy.clear();
                mutReceiver = false;
                borrowReceiver = false;
                uniqueResult = false;
                volatileEffect = false;
            } else {
                if (copy.contains(Effect.PURE)) {
                    copy.add(Effect.HERMETIC);
                }
                if (volatileEffect) {
                    copy.add(Effect.HERMETIC);
                    copy.remove(Effect.DETERMINISTIC);
                }
                if (mutReceiver) {
                    copy.remove(Effect.PURE);
                }
            }
            effects = Collections.unmodifiableSet(copy);
        }

        public static OverrideEntry ioEntry() {
            return new OverrideEntry(EnumSet.noneOf(Effect.class), false, false, false, true, false);
        }

        public static OverrideEntry fromEffects(TargetKind kind, Effect... rawEffects) {
            Objects.requireNonNull(rawEffects, "effects");
            if (rawEffects.length == 0) {
                throw new IllegalArgumentException("Expected at least one Effect");
            }
            EnumSet<Effect> set = EnumSet.noneOf(Effect.class);
            for (Effect e : rawEffects) {
                Objects.requireNonNull(e, "effect");
                set.add(e);
            }
            if (set.contains(Effect.IO)) {
                if (set.size() > 1) {
                    throw new IllegalArgumentException("Effect.IO cannot be combined with positive effects");
                }
                return ioEntry();
            }
            if (set.contains(Effect.PURE) && set.contains(Effect.MUT)) {
                throw new IllegalArgumentException("Effect.PURE and Effect.MUT are mutually exclusive : use Effect.MUT (or Effect.VOLATILE, Effect.MUT)");
            }
            if (set.contains(Effect.PURE) && set.contains(Effect.VOLATILE)) {
                throw new IllegalArgumentException("Effect.PURE and Effect.VOLATILE are mutually exclusive");
            }
            if (set.contains(Effect.BORROW) && set.contains(Effect.SHARED)) {
                throw new IllegalArgumentException("Effect.BORROW and Effect.SHARED are mutually exclusive");
            }
            if (kind == TargetKind.CONSTRUCTOR && set.contains(Effect.MUT)) {
                throw new IllegalArgumentException("Constructors cannot declare Effect.MUT");
            }
            if (kind == TargetKind.CLASS && set.contains(Effect.MUT)) {
                throw new IllegalArgumentException("Bare class overrides cannot declare Effect.MUT : register per method instead");
            }
            boolean mutReceiver = set.remove(Effect.MUT);
            boolean explicitShared = set.remove(Effect.SHARED);
            set.remove(Effect.BORROW);
            boolean uniqueResult = set.remove(Effect.UNIQUE);
            boolean volatileEffect = set.remove(Effect.VOLATILE);
            if (set.contains(Effect.PURE)) {
                set.add(Effect.HERMETIC);
                set.add(Effect.DETERMINISTIC);
            } else if (volatileEffect) {
                set.add(Effect.HERMETIC);
                set.remove(Effect.DETERMINISTIC);
                if (!mutReceiver) {
                    set.add(Effect.PURE);
                }
            } else if (mutReceiver && !set.contains(Effect.HERMETIC)) {
                set.add(Effect.HERMETIC);
                set.add(Effect.DETERMINISTIC);
            }
            boolean borrowReceiver = !explicitShared;
            return new OverrideEntry(set, mutReceiver, borrowReceiver, uniqueResult, false, volatileEffect);
        }

        public EffectSet toEffectSet(boolean axiomatic) {
            if (io) {
                return EffectSet.IO_DECLARED;
            }
            return EffectSet.ofDeclared(effects, mutReceiver, borrowReceiver, !borrowReceiver, uniqueResult,
                    false, volatileEffect, axiomatic);
        }
    }

    public static final HostEffects DEFAULT = new HostEffects(true, true, Map.of(), BuiltinAxioms.standard());

    private final boolean allowAxioms;
    private final boolean bytecodeEffectAnalysis;
    private final Map<JavaTarget, OverrideEntry> overrides;
    private final BuiltinAxioms builtinAxioms;

    public HostEffects(boolean allowAxioms, Map<JavaTarget, OverrideEntry> overrides) {
        this(allowAxioms, true, overrides, BuiltinAxioms.standard());
    }

    public HostEffects(boolean allowAxioms, boolean bytecodeEffectAnalysis, Map<JavaTarget, OverrideEntry> overrides) {
        this(allowAxioms, bytecodeEffectAnalysis, overrides, BuiltinAxioms.standard());
    }

    public HostEffects(boolean allowAxioms, boolean bytecodeEffectAnalysis, Map<JavaTarget, OverrideEntry> overrides,
                       BuiltinAxioms builtinAxioms) {
        this.allowAxioms = allowAxioms;
        this.bytecodeEffectAnalysis = bytecodeEffectAnalysis;
        this.overrides = overrides.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
        this.builtinAxioms = builtinAxioms != null ? builtinAxioms : BuiltinAxioms.standard();
    }

    public boolean allowAxioms() {
        return allowAxioms;
    }

    public boolean bytecodeEffectAnalysis() {
        return bytecodeEffectAnalysis;
    }

    public Map<JavaTarget, OverrideEntry> overrides() {
        return overrides;
    }

    public BuiltinAxioms builtinAxioms() {
        return builtinAxioms;
    }

    public OverrideEntry lookup(JavaTarget target) {
        return overrides.get(target);
    }

    public static boolean hasMethodNamed(Class<?> owner, String methodName) {
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(methodName)) {
                return true;
            }
        }
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
