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
 * Per-engine host effect policy and Java target overrides (BIP007 §3.1, §6.2).
 *
 * <p>Also captures target keys shared by file-level, module-exported, and REPL-session
 * {@code axiom} / {@code impure} tables.
 */
public final class HostEffects {

    /** Kind of Java target addressed by an {@code axiom}, {@code impure}, or host registration. */
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
    public record OverrideEntry(Set<Effect> effects, boolean mutReceiver, boolean borrowReceiver, boolean uniqueResult, boolean impure) {
        public OverrideEntry(Set<Effect> effects, boolean mutReceiver, boolean borrowReceiver, boolean impure) {
            this(effects, mutReceiver, borrowReceiver, false, impure);
        }

        public OverrideEntry {
            EnumSet<Effect> copy = effects.isEmpty() ? EnumSet.noneOf(Effect.class) : EnumSet.copyOf(effects);
            copy.remove(Effect.MUT);
            copy.remove(Effect.BORROW);
            copy.remove(Effect.UNIQUE);
            copy.remove(Effect.IMPURE);
            if (impure) {
                copy.clear();
                mutReceiver = false;
                borrowReceiver = false;
                uniqueResult = false;
            } else if (copy.contains(Effect.PURE)) {
                copy.add(Effect.HERMETIC);
            }
            effects = Collections.unmodifiableSet(copy);
        }

        public static OverrideEntry impureEntry() {
            return new OverrideEntry(EnumSet.noneOf(Effect.class), false, false, false, true);
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
            if (set.contains(Effect.IMPURE)) {
                if (set.size() > 1) {
                    throw new IllegalArgumentException("Effect.IMPURE cannot be combined with positive effects");
                }
                return impureEntry();
            }
            if (set.contains(Effect.PURE) && set.contains(Effect.MUT)) {
                throw new IllegalArgumentException("Effect.PURE and Effect.MUT are mutually exclusive : use Effect.HERMETIC with Effect.MUT");
            }
            if (kind == TargetKind.CONSTRUCTOR && set.contains(Effect.MUT)) {
                throw new IllegalArgumentException("Constructors cannot declare Effect.MUT");
            }
            if (kind == TargetKind.CLASS && set.contains(Effect.MUT)) {
                throw new IllegalArgumentException("Bare class overrides cannot declare Effect.MUT : register per method instead");
            }
            boolean mutReceiver = set.remove(Effect.MUT);
            boolean borrowReceiver = set.remove(Effect.BORROW);
            boolean uniqueResult = set.remove(Effect.UNIQUE);
            if (set.contains(Effect.PURE)) {
                set.add(Effect.HERMETIC);
            }
            return new OverrideEntry(set, mutReceiver, borrowReceiver, uniqueResult, false);
        }

        public EffectSet toEffectSet(boolean axiomatic) {
            if (impure) {
                return EffectSet.IMPURE_DECLARED;
            }
            return EffectSet.ofDeclared(effects, mutReceiver, borrowReceiver, uniqueResult, axiomatic);
        }
    }

    public static final HostEffects DEFAULT = new HostEffects(true, Map.of());

    private final boolean allowAxioms;
    private final Map<JavaTarget, OverrideEntry> overrides;

    public HostEffects(boolean allowAxioms, Map<JavaTarget, OverrideEntry> overrides) {
        this.allowAxioms = allowAxioms;
        this.overrides = overrides.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
    }

    public boolean allowAxioms() {
        return allowAxioms;
    }

    public Map<JavaTarget, OverrideEntry> overrides() {
        return overrides;
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
