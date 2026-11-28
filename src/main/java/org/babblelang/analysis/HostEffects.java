package org.babblelang.analysis;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.babblelang.effects.Effect;

/**
 * Per-engine host effect policy (BIP007 §3.1, §6.2 ; BIP016-D8).
 *
 * <p>The assumptions a host registers on {@code BabbleScriptEngine} ({@code assumeEffects}, {@code
 * assumeConstructorEffects}, {@code markIO}) are lowered by {@link Registry} into an {@link
 * AxiomData.AxiomTable}, the same table {@code axiom X.m: fn ...} declarations build ({@code
 * BIP027-D6}) : a method assumption is a named member axiom applying to the method's overrides, a
 * constructor assumption is a {@code new} axiom, and a class assumption is an {@code exact}
 * wildcard ({@code def *}) on the class itself.
 */
public final class HostEffects {

    public static final HostEffects DEFAULT =
            new HostEffects(
                    true, true, AxiomData.AxiomTable.EMPTY, BuiltinAxioms.standard(), Set.of());

    private final boolean allowAxioms;
    private final boolean bytecodeEffectAnalysis;
    private final AxiomData.AxiomTable hostAxioms;
    private final BuiltinAxioms builtinAxioms;
    private final Set<String> hostBindings;

    HostEffects(
            boolean allowAxioms,
            boolean bytecodeEffectAnalysis,
            AxiomData.AxiomTable hostAxioms,
            BuiltinAxioms builtinAxioms) {
        this(allowAxioms, bytecodeEffectAnalysis, hostAxioms, builtinAxioms, Set.of());
    }

    HostEffects(
            boolean allowAxioms,
            boolean bytecodeEffectAnalysis,
            AxiomData.AxiomTable hostAxioms,
            BuiltinAxioms builtinAxioms,
            Set<String> hostBindings) {
        this.allowAxioms = allowAxioms;
        this.bytecodeEffectAnalysis = bytecodeEffectAnalysis;
        this.hostAxioms = hostAxioms != null ? hostAxioms : AxiomData.AxiomTable.EMPTY;
        this.builtinAxioms = builtinAxioms != null ? builtinAxioms : BuiltinAxioms.standard();
        this.hostBindings =
                hostBindings == null || hostBindings.isEmpty()
                        ? Set.of()
                        : Set.copyOf(hostBindings);
    }

    public boolean allowAxioms() {
        return allowAxioms;
    }

    public boolean bytecodeEffectAnalysis() {
        return bytecodeEffectAnalysis;
    }

    public BuiltinAxioms builtinAxioms() {
        return builtinAxioms;
    }

    /**
     * External binding names currently present in the host's {@code ENGINE_SCOPE}, for {@code
     * eval}.
     */
    public Set<String> hostBindings() {
        return hostBindings;
    }

    /** Returns a copy of this policy seeded with the given host binding names. */
    public HostEffects withHostBindings(Set<String> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return hostBindings.isEmpty()
                    ? this
                    : new HostEffects(
                            allowAxioms,
                            bytecodeEffectAnalysis,
                            hostAxioms,
                            builtinAxioms,
                            Set.of());
        }
        return new HostEffects(
                allowAxioms, bytecodeEffectAnalysis, hostAxioms, builtinAxioms, bindings);
    }

    /** The host's assumptions, as axioms. */
    AxiomData.AxiomTable hostAxioms() {
        return hostAxioms;
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

    /**
     * An engine's host registrations, the last one about a target replacing the previous one. Not
     * thread-safe : the engine guards it.
     */
    public static final class Registry {
        private record Key(Class<?> owner, String member) {}

        private final Map<Key, List<AxiomData.MemberAxiom>> entries = new LinkedHashMap<>();

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        /** All methods and constructors of {@code target}. */
        public void assumeClass(Class<?> target, Effect... effects) {
            Objects.requireNonNull(target, "target");
            Lowered lowered = lower(Kind.CLASS, effects);
            putClass(target, lowered);
        }

        /** All overloads of {@code methodName} on {@code owner} and their overrides. */
        public void assumeMethod(Class<?> owner, String methodName, Effect... effects) {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(methodName, "methodName");
            Lowered lowered = lower(Kind.METHOD, effects);
            entries.put(
                    new Key(owner, methodName),
                    List.of(member(owner, methodName, AxiomData.MemberKind.ANY, false, lowered)));
        }

        /** The constructors of {@code target}. */
        public void assumeConstructor(Class<?> target, Effect... effects) {
            Objects.requireNonNull(target, "target");
            Lowered lowered = lower(Kind.CONSTRUCTOR, effects);
            entries.put(
                    new Key(target, "new"),
                    List.of(member(target, "new", AxiomData.MemberKind.STATIC, false, lowered)));
        }

        /** All methods and constructors of {@code target} perform I/O. */
        public void markIO(Class<?> target) {
            Objects.requireNonNull(target, "target");
            putClass(target, Lowered.IO);
        }

        /** The methods named {@code methodName} on {@code owner} perform I/O. */
        public void markIO(Class<?> owner, String methodName) {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(methodName, "methodName");
            entries.put(
                    new Key(owner, methodName),
                    List.of(
                            member(
                                    owner,
                                    methodName,
                                    AxiomData.MemberKind.ANY,
                                    false,
                                    Lowered.IO)));
        }

        /** The engine's policy with these registrations. */
        public HostEffects toHostEffects(
                boolean allowAxioms, boolean bytecodeEffectAnalysis, BuiltinAxioms builtinAxioms) {
            if (allowAxioms
                    && bytecodeEffectAnalysis
                    && entries.isEmpty()
                    && builtinAxioms == BuiltinAxioms.standard()) {
                return DEFAULT;
            }
            AxiomData.Builder builder = new AxiomData.Builder();
            for (List<AxiomData.MemberAxiom> members : entries.values()) {
                for (AxiomData.MemberAxiom m : members) {
                    builder.addMemberAxiom(m);
                }
            }
            return new HostEffects(
                    allowAxioms, bytecodeEffectAnalysis, builder.build(), builtinAxioms);
        }

        private void putClass(Class<?> target, Lowered lowered) {
            // An exact wildcard on the class itself : the instance one for its methods, the static
            // one for its static methods and constructors.
            entries.put(
                    new Key(target, "*"),
                    List.of(
                            member(target, "*", AxiomData.MemberKind.INSTANCE, true, lowered),
                            member(target, "*", AxiomData.MemberKind.STATIC, true, lowered)));
        }

        private static AxiomData.MemberAxiom member(
                Class<?> owner,
                String name,
                AxiomData.MemberKind kind,
                boolean exact,
                Lowered lowered) {
            return AxiomData.MemberAxiom.host(
                    owner,
                    name,
                    kind,
                    exact,
                    lowered.effects(),
                    lowered.mutReceiver(),
                    lowered.sharedReceiver(),
                    lowered.nothrow());
        }
    }

    private enum Kind {
        METHOD,
        CONSTRUCTOR,
        CLASS
    }

    /** A host registration's {@link Effect}s, normalized into the effect set its axiom states. */
    private record Lowered(
            EffectSet effects, boolean mutReceiver, boolean sharedReceiver, boolean nothrow) {
        static final Lowered IO = new Lowered(EffectSet.IO_DECLARED, false, false, false);
    }

    private static Lowered lower(Kind kind, Effect... rawEffects) {
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
                throw new IllegalArgumentException(
                        "Effect.IO cannot be combined with positive effects");
            }
            return Lowered.IO;
        }
        if (set.contains(Effect.PURE) && set.contains(Effect.MUT)) {
            throw new IllegalArgumentException(
                    "Effect.PURE and Effect.MUT are mutually exclusive : use Effect.MUT (or Effect.VOLATILE, Effect.MUT)");
        }
        if (set.contains(Effect.PURE) && set.contains(Effect.VOLATILE)) {
            throw new IllegalArgumentException(
                    "Effect.PURE and Effect.VOLATILE are mutually exclusive");
        }
        if (set.contains(Effect.BORROW) && set.contains(Effect.SHARED)) {
            throw new IllegalArgumentException(
                    "Effect.BORROW and Effect.SHARED are mutually exclusive");
        }
        if (kind == Kind.CONSTRUCTOR && set.contains(Effect.MUT)) {
            throw new IllegalArgumentException("Constructors cannot declare Effect.MUT");
        }
        if (kind == Kind.CLASS && set.contains(Effect.MUT)) {
            throw new IllegalArgumentException(
                    "Bare class overrides cannot declare Effect.MUT : register per method instead");
        }
        boolean mutReceiver = set.remove(Effect.MUT);
        boolean sharedReceiver = set.remove(Effect.SHARED);
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
        if (mutReceiver) {
            set.remove(Effect.PURE);
        }
        EffectSet effects =
                EffectSet.ofDeclared(
                        set,
                        mutReceiver,
                        !sharedReceiver,
                        sharedReceiver,
                        uniqueResult,
                        false,
                        volatileEffect,
                        true);
        return new Lowered(effects, mutReceiver, sharedReceiver, set.contains(Effect.NOTHROW));
    }
}
