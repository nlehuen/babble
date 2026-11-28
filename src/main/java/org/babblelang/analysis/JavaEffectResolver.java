package org.babblelang.analysis;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.babblelang.effects.Associative;
import org.babblelang.effects.Commutative;
import org.babblelang.effects.Effect;
import org.babblelang.effects.IO;
import org.babblelang.effects.Idempotent;
import org.babblelang.effects.Injective;
import org.babblelang.effects.Monotone;
import org.babblelang.effects.Mut;
import org.babblelang.effects.Nothrow;
import org.babblelang.effects.Pure;
import org.babblelang.effects.Shared;
import org.babblelang.effects.Unique;
import org.babblelang.effects.Volatile;
import org.babblelang.runtime.ClassShape;

/**
 * ANTLR-free 7-tier Java effect resolver, reflection bridge, and built-in type effect profiles
 * extracted from {@link EffectChecker} ({@code BIP027-I3}).
 *
 * <p>All {@code java.lang.reflect.*} access for effect checking lives here, and redundant-axiom
 * reporting state is scoped to this resolver instance rather than held in a JVM-global static set.
 */
final class JavaEffectResolver {
    private final HostEffects hostEffects;
    private final TypedProgram checker;
    private final Supplier<AxiomData.AxiomTable> localAxioms;
    private final Function<TypeNode.Member, EffectChecker.CallableSummary> memberSummaryLookup;
    private final Set<String> reportedRedundantAxioms = new HashSet<>();

    JavaEffectResolver(
            HostEffects hostEffects,
            TypedProgram checker,
            Supplier<AxiomData.AxiomTable> localAxioms,
            Function<TypeNode.Member, EffectChecker.CallableSummary> memberSummaryLookup) {
        this.hostEffects = hostEffects;
        this.checker = checker;
        this.localAxioms = localAxioms;
        this.memberSummaryLookup = memberSummaryLookup;
    }

    EffectChecker.JavaCallProfile resolveJavaMethodProfile(
            Class<?> owner, String methodName, Method exactMethod) {
        AxiomData.AxiomTable axioms = localAxioms.get();
        // Tier 1: File / module / session axioms (BIP019, BIP030).
        if (!axioms.isEmpty()) {
            AxiomData.MemberAxiom localAx =
                    AxiomData.lookupMethod(owner, methodName, exactMethod, axioms::directMembers);
            if (localAx != null && !localAx.inferredEffects()) {
                return localAx.toProfile(exactMethod);
            }
        }
        // Tier 2: Engine method assumptions (host axioms naming the method, BIP027-D6).
        AxiomData.AxiomTable host = hostEffects.hostAxioms();
        if (!host.isEmpty()) {
            AxiomData.MemberAxiom hostAx =
                    AxiomData.lookupMethod(owner, methodName, exactMethod, c -> named(host, c));
            if (hostAx != null) {
                return hostAx.toProfile(exactMethod);
            }
        }
        // Tier 3: Java method annotations (@Pure, @Hermetic, @Mut, ...).
        if (exactMethod != null && hasAnyEffectAnnotation(exactMethod)) {
            return profileFromAnnotations(exactMethod, exactMethod.getDeclaringClass(), false);
        }
        // Tier 4: Engine class assumptions (exact host wildcards on the owner or the declaring
        // class).
        Class<?> declOwner = exactMethod != null ? exactMethod.getDeclaringClass() : owner;
        if (!host.isEmpty()) {
            AxiomData.MemberAxiom hostClass =
                    AxiomData.lookupMethod(owner, methodName, exactMethod, c -> wildcards(host, c));
            if (hostClass != null) {
                return hostClass.toProfile(exactMethod);
            }
        }
        // Tier 5: Java class-level annotations on owner / declaring class.
        if (hasClassEffectAnnotation(owner)) {
            return profileFromAnnotations(exactMethod, owner, true);
        }
        if (declOwner != owner && hasClassEffectAnnotation(declOwner)) {
            return profileFromAnnotations(exactMethod, declOwner, true);
        }
        // Tier 6: Curated axioms (BIP019, BIP030).
        AxiomData.MemberAxiom curatedAx =
                hostEffects.builtinAxioms().methodAxiom(owner, methodName, exactMethod);
        if (curatedAx != null) {
            if (exactMethod != null && Boolean.getBoolean("babble.axioms.report")) {
                reportRedundantCuratedAxiom(exactMethod, curatedAx);
            }
            if (!curatedAx.inferredEffects()) {
                // An inherited supertype axiom (e.g. Object.hashCode/toString) is a floor contract:
                // if a concrete Java subclass overrides the method with strictly stronger proven
                // bytecode effects (e.g. deterministic hashCode/toString), keep the stronger proof.
                if (exactMethod != null
                        && curatedAx.ownerClass() != exactMethod.getDeclaringClass()
                        && curatedAx.ownerClass().isAssignableFrom(exactMethod.getDeclaringClass())
                        && hostEffects.bytecodeEffectAnalysis()) {
                    BytecodeEffectAnalyzer.Result analyzed =
                            BytecodeEffectAnalyzer.analyzeRaw(
                                    exactMethod, hostEffects.builtinAxioms());
                    if (BytecodeEffectAnalyzer.isAtLeastAsStrongAs(
                            analyzed, curatedAx, exactMethod)) {
                        return analyzed.toProfile(exactMethod);
                    }
                }
                return curatedAx.toProfile(exactMethod);
            }
        }
        // Tier 7: ASM bytecode static analysis (on by default, BIP019 §5).
        if (exactMethod != null && hostEffects.bytecodeEffectAnalysis()) {
            BytecodeEffectAnalyzer.Result analyzed =
                    BytecodeEffectAnalyzer.analyze(exactMethod, hostEffects.builtinAxioms());
            if (!analyzed.effects().isUnconstrained()) {
                return analyzed.toProfile(exactMethod);
            }
        }
        // Tier 8: Unconstrained default.
        int paramCount = exactMethod != null ? exactMethod.getParameterCount() : 0;
        return new EffectChecker.JavaCallProfile(
                EffectSet.EMPTY,
                false,
                false,
                new boolean[paramCount],
                new boolean[paramCount],
                false);
    }

    EffectChecker.JavaCallProfile resolveJavaConstructorProfile(
            Class<?> owner, Constructor<?> exactCtor) {
        AxiomData.AxiomTable axioms = localAxioms.get();
        // Tier 1: File / module / session constructor axioms (BIP019, BIP030).
        if (!axioms.isEmpty()) {
            AxiomData.MemberAxiom localAx =
                    AxiomData.lookupConstructor(owner, exactCtor, axioms::directMembers);
            if (localAx != null && !localAx.inferredEffects()) {
                return localAx.toProfile(exactCtor);
            }
        }
        // Tier 2: Engine constructor assumptions.
        AxiomData.AxiomTable host = hostEffects.hostAxioms();
        if (!host.isEmpty()) {
            AxiomData.MemberAxiom hostCtor =
                    AxiomData.lookupConstructor(owner, exactCtor, c -> named(host, c));
            if (hostCtor != null) {
                return hostCtor.toProfile(exactCtor);
            }
        }
        // Tier 3: Java constructor annotations.
        if (exactCtor != null && hasAnyEffectAnnotation(exactCtor)) {
            return profileFromAnnotations(exactCtor, owner, false);
        }
        // Tier 4: Engine class assumptions.
        if (!host.isEmpty()) {
            AxiomData.MemberAxiom hostClass =
                    AxiomData.lookupConstructor(owner, exactCtor, c -> wildcards(host, c));
            if (hostClass != null) {
                return hostClass.toProfile(exactCtor);
            }
        }
        // Tier 5: Java class-level annotations.
        if (hasClassEffectAnnotation(owner)) {
            return profileFromAnnotations(exactCtor, owner, true);
        }
        // Tier 6: Curated axioms (BIP019, BIP030).
        AxiomData.MemberAxiom curatedAx =
                hostEffects.builtinAxioms().constructorAxiom(owner, exactCtor);
        if (curatedAx != null) {
            if (exactCtor != null && Boolean.getBoolean("babble.axioms.report")) {
                reportRedundantCuratedAxiom(exactCtor, curatedAx);
            }
            if (!curatedAx.inferredEffects()) {
                return curatedAx.toProfile(exactCtor);
            }
        }
        int paramCount = exactCtor != null ? exactCtor.getParameterCount() : 0;
        // Tier 7: ASM bytecode static analysis of constructors (on by default, BIP019 §5).
        if (exactCtor != null && hostEffects.bytecodeEffectAnalysis()) {
            BytecodeEffectAnalyzer.Result analyzed =
                    BytecodeEffectAnalyzer.analyze(exactCtor, hostEffects.builtinAxioms());
            if (!analyzed.effects().isUnconstrained()) {
                return analyzed.toProfile(exactCtor);
            }
        }
        // Tier 8: Unconstrained default.
        return new EffectChecker.JavaCallProfile(
                EffectSet.EMPTY,
                false,
                false,
                new boolean[paramCount],
                new boolean[paramCount],
                false);
    }

    private void reportRedundantCuratedAxiom(Executable exec, AxiomData.MemberAxiom curated) {
        String key =
                exec.getDeclaringClass().getName()
                        + "."
                        + (exec instanceof Constructor<?> ? "new" : exec.getName())
                        + Arrays.toString(exec.getParameterTypes());
        if (!reportedRedundantAxioms.add(key)) {
            return;
        }
        BytecodeEffectAnalyzer.Result inferred =
                BytecodeEffectAnalyzer.analyzeRaw(exec, hostEffects.builtinAxioms());
        if (BytecodeEffectAnalyzer.isAtLeastAsStrongAs(inferred, curated, exec)) {
            System.err.println(
                    "Axiom report : " + key + " curated axiom can be inferred as is or stronger");
        }
    }

    EffectChecker.JavaCallProfile resolveJavaMethodForCallableSatisfies(
            Type.JavaMethod jm, List<Type.Parameter> targetParams) {
        Method exact =
                JavaTypeBridge.resolveMethodForTarget(
                        jm.owner(), jm.name(), targetParams, checker::assignable);
        return resolveJavaMethodProfile(jm.owner(), jm.name(), exact);
    }

    boolean isJavaClassDeeplyImmutable(Class<?> cls) {
        if (cls == null) {
            return false;
        }
        AxiomData.AxiomTable axioms = localAxioms.get();
        if (!axioms.isEmpty()
                && AxiomData.lookupClassFlag(
                        cls, axioms::directClassAxiom, AxiomData.ClassAxiom::immutable)) {
            return true;
        }
        return hostEffects.builtinAxioms().isDeeplyImmutable(cls);
    }

    boolean isDeeplyImmutable(Type type) {
        return checker.isDeeplyImmutable(type);
    }

    boolean isImmutableConstantValue(Type type) {
        if (isDeeplyImmutable(type)) {
            return true;
        }
        return type instanceof Type.Function
                || type instanceof Type.Overloaded
                || type instanceof Type.ClassValue
                || type instanceof Type.JavaClassValue
                || type instanceof Type.JavaPackage
                || type instanceof Type.JavaMethod
                || type instanceof Type.Module;
    }

    Type.Function applySamJavaEffects(Method samMethod, Type.Function rawSam) {
        EffectChecker.JavaCallProfile profile =
                resolveJavaMethodProfile(
                        samMethod.getDeclaringClass(), samMethod.getName(), samMethod);
        if (profile.effects().isUnconstrained()
                && !anyTrue(profile.mutParams())
                && !anyTrue(profile.borrowParams())
                && !anyTrue(profile.uniqueParams())) {
            return rawSam;
        }
        if (!profile.effects().hasExplicitAnnotations()
                && !anyTrue(profile.mutParams())
                && !anyTrue(profile.borrowParams())
                && !anyTrue(profile.uniqueParams())) {
            return rawSam;
        }
        List<Type.Parameter> updated = new ArrayList<>(rawSam.parameters().size());
        for (int i = 0; i < rawSam.parameters().size(); i++) {
            Type.Parameter p = rawSam.parameters().get(i);
            boolean mut = i < profile.mutParams().length && profile.mutParams()[i];
            boolean borrow = i < profile.borrowParams().length && profile.borrowParams()[i];
            boolean unique = i < profile.uniqueParams().length && profile.uniqueParams()[i];
            updated.add(p.withModes(mut, borrow, !borrow && !unique, unique));
        }
        EffectSet fnEffects =
                new EffectSet(
                        profile.effects().effects(),
                        profile.effects().declared(),
                        false,
                        false,
                        false,
                        profile.effects().uniqueResult(),
                        profile.effects().explicitUniqueResult(),
                        profile.effects().terminating(),
                        profile.effects().isIO(),
                        profile.effects().explicitVolatile(),
                        false);
        return new Type.Function(rawSam.typeParameters(), updated, rawSam.result(), fnEffects);
    }

    /**
     * Profile of converting {@code type} to string in {@code string + x} (bip007_effects.md §4.3,
     * §4.6).
     */
    EffectSet toStringProfile(Type type) {
        return toStringProfile(type, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private EffectSet toStringProfile(Type type, Set<TypeNode> visiting) {
        if (type instanceof Type.Frozen f) {
            return toStringProfile(f.of(), visiting);
        }
        if (type == null || type == Type.ERROR) {
            return EffectSet.ofInferred(
                    EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                    false,
                    true,
                    false,
                    true);
        }
        if (Type.isCoreScalar(type) || type == Type.NULL) {
            return EffectSet.ofInferred(
                    EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                    false,
                    true,
                    false,
                    true);
        }
        if (type instanceof Type.Union u) {
            EffectSet acc =
                    EffectSet.ofInferred(
                            EnumSet.of(
                                    Effect.PURE,
                                    Effect.HERMETIC,
                                    Effect.DETERMINISTIC,
                                    Effect.NOTHROW),
                            false,
                            true,
                            false,
                            true);
            for (Type m : u.members()) {
                acc = acc.intersect(toStringProfile(m, visiting));
            }
            return acc;
        }
        if (type instanceof Type.Instance inst) {
            TypeNode node = inst.node();
            TypeNode.Member custom = checker.findMemberOnNode(node, "toString");
            if (custom != null && custom.hasFunction) {
                EffectChecker.CallableSummary cs = memberSummaryLookup.apply(custom);
                if (cs != null) {
                    return cs.effects();
                }
                Type mt = checker.memberTypeForEffects(node, custom);
                if (mt instanceof Type.Function tf) {
                    return tf.effects();
                }
            }
            if (node.kind == ClassShape.Kind.VALUE_CLASS) {
                EffectSet acc =
                        EffectSet.ofInferred(
                                EnumSet.of(
                                        Effect.PURE,
                                        Effect.HERMETIC,
                                        Effect.DETERMINISTIC,
                                        Effect.NOTHROW),
                                false,
                                true,
                                false,
                                true);
                if (!visiting.add(node)) {
                    return acc;
                }
                try {
                    for (TypeNode.Member m : node.members.values()) {
                        if (!m.hasFunction) {
                            Type mt = checker.instanceMemberTypeForEffects(inst, m);
                            if (mt == null) {
                                return EffectSet.EMPTY;
                            }
                            acc = acc.intersect(toStringProfile(mt, visiting));
                        }
                    }
                    return acc;
                } finally {
                    visiting.remove(node);
                }
            }
            return EffectSet.ofInferred(
                    EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.NOTHROW),
                    false,
                    true,
                    false,
                    true);
        }
        if (type instanceof Type.JavaInstance ji) {
            return resolveJavaMethodProfile(
                            ji.type(), "toString", lookupPublicMethod(ji.type(), "toString"))
                    .effects()
                    .intersect(containerElementsProfile(ji, "toString"));
        }
        if (type instanceof Type.Array arr) {
            return toStringProfile(arr.element(), visiting);
        }
        return EffectSet.EMPTY;
    }

    private static final EffectSet PURE_DETERMINISTIC_NOTHROW =
            EffectSet.ofInferred(
                    EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                    false,
                    true,
                    false,
                    true);

    /**
     * Profile of {@code type}'s own {@code hashCode} : identity hashes ({@code Object}, {@code
     * any}, classes and closures without their own) follow the curated {@code Object.hashCode},
     * records and arrays combine their fields' or elements', and containers add their elements'
     * (BIP032 {@code J2}).
     */
    EffectSet hashCodeProfile(Type type) {
        return hashCodeProfile(type, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Profile of the {@code toString} or {@code hashCode} of the elements a container's own method
     * delegates to ({@code Collection}, {@code Map}, {@code Map.Entry}, {@code Optional}) : a
     * {@code list(java.lang.Object())} prints and hashes by identity, whatever the curated {@code
     * List} methods say (BIP032 {@code J2}). Other types delegate to nothing.
     */
    EffectSet containerElementsProfile(Type type, String method) {
        EffectSet acc = PURE_DETERMINISTIC_NOTHROW;
        for (Type element : delegatedElementTypes(type)) {
            Type base = Type.withoutNull(TypeAlgebra.unwrapFrozen(element));
            EffectSet elementProfile =
                    base == Type.ANY
                            ? objectMethodProfile(method)
                            : "hashCode".equals(method)
                                    ? hashCodeProfile(element)
                                    : toStringProfile(element);
            acc = acc.intersect(elementProfile);
        }
        return acc;
    }

    private static List<Type> delegatedElementTypes(Type type) {
        if (Type.withoutNull(TypeAlgebra.unwrapFrozen(type)) instanceof Type.JavaInstance ji) {
            Class<?> cls = ji.type();
            if (java.util.Collection.class.isAssignableFrom(cls)
                    || java.util.Map.class.isAssignableFrom(cls)
                    || java.util.Map.Entry.class.isAssignableFrom(cls)
                    || cls == java.util.Optional.class) {
                return ji.typeArguments();
            }
        }
        return List.of();
    }

    private EffectSet objectMethodProfile(String method) {
        return resolveJavaMethodProfile(
                        Object.class, method, lookupPublicMethod(Object.class, method))
                .effects();
    }

    private EffectSet hashCodeProfile(Type type, Set<TypeNode> visiting) {
        if (type instanceof Type.Frozen f) {
            return hashCodeProfile(f.of(), visiting);
        }
        if (type == null || type == Type.ERROR || Type.isCoreScalar(type) || type == Type.NULL) {
            return PURE_DETERMINISTIC_NOTHROW;
        }
        if (type instanceof Type.Union u) {
            EffectSet acc = PURE_DETERMINISTIC_NOTHROW;
            for (Type m : u.members()) {
                acc = acc.intersect(hashCodeProfile(m, visiting));
            }
            return acc;
        }
        if (type instanceof Type.Instance inst) {
            TypeNode node = inst.node();
            TypeNode.Member custom = checker.findMemberOnNode(node, "hashCode");
            if (custom != null && custom.hasFunction) {
                EffectChecker.CallableSummary cs = memberSummaryLookup.apply(custom);
                if (cs != null) {
                    return cs.effects();
                }
                Type mt = checker.memberTypeForEffects(node, custom);
                if (mt instanceof Type.Function tf) {
                    return tf.effects();
                }
            }
            if (node.kind != ClassShape.Kind.VALUE_CLASS) {
                return objectMethodProfile("hashCode");
            }
            if (!visiting.add(node)) {
                return PURE_DETERMINISTIC_NOTHROW;
            }
            try {
                EffectSet acc = PURE_DETERMINISTIC_NOTHROW;
                for (TypeNode.Member m : node.members.values()) {
                    if (!m.hasFunction) {
                        Type mt = checker.instanceMemberTypeForEffects(inst, m);
                        if (mt == null) {
                            return EffectSet.EMPTY;
                        }
                        acc = acc.intersect(hashCodeProfile(mt, visiting));
                    }
                }
                return acc;
            } finally {
                visiting.remove(node);
            }
        }
        if (type instanceof Type.JavaInstance ji) {
            return resolveJavaMethodProfile(
                            ji.type(), "hashCode", lookupPublicMethod(ji.type(), "hashCode"))
                    .effects()
                    .intersect(containerElementsProfile(ji, "hashCode"));
        }
        if (type instanceof Type.Array arr) {
            return hashCodeProfile(arr.element(), visiting);
        }
        return objectMethodProfile("hashCode");
    }

    EffectSet equalityProfile(Type left, Type right) {
        return singleEqualityProfile(left).intersect(singleEqualityProfile(right));
    }

    EffectSet singleEqualityProfile(Type type) {
        return singleEqualityProfile(type, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private EffectSet singleEqualityProfile(Type type, Set<TypeNode> visiting) {
        if (type instanceof Type.Frozen f) {
            return singleEqualityProfile(f.of(), visiting);
        }
        if (type == null
                || type == Type.ERROR
                || Type.isCoreScalar(type)
                || type == Type.NULL
                || type instanceof Type.Function
                || type instanceof Type.ClassValue
                || type instanceof Type.JavaClassValue) {
            return EffectSet.ofInferred(
                    EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                    false,
                    true,
                    false,
                    true);
        }
        if (type instanceof Type.Union u) {
            EffectSet acc =
                    EffectSet.ofInferred(
                            EnumSet.of(
                                    Effect.PURE,
                                    Effect.HERMETIC,
                                    Effect.DETERMINISTIC,
                                    Effect.NOTHROW),
                            false,
                            true,
                            false,
                            true);
            for (Type m : u.members()) {
                acc = acc.intersect(singleEqualityProfile(m, visiting));
            }
            return acc;
        }
        if (type instanceof Type.Instance inst) {
            if (inst.node().kind == ClassShape.Kind.VALUE_CLASS) {
                EffectSet acc =
                        EffectSet.ofInferred(
                                EnumSet.of(
                                        Effect.PURE,
                                        Effect.HERMETIC,
                                        Effect.DETERMINISTIC,
                                        Effect.NOTHROW),
                                false,
                                true,
                                false,
                                true);
                if (!visiting.add(inst.node())) {
                    return acc;
                }
                try {
                    for (TypeNode.Member m : inst.node().members.values()) {
                        if (!m.hasFunction) {
                            Type mt = checker.instanceMemberTypeForEffects(inst, m);
                            if (mt != null) {
                                acc = acc.intersect(singleEqualityProfile(mt, visiting));
                            }
                        }
                    }
                    return acc;
                } finally {
                    visiting.remove(inst.node());
                }
            }
            return EffectSet.ofInferred(
                    EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                    false,
                    true,
                    false,
                    true);
        }
        if (type instanceof Type.JavaInstance ji) {
            return resolveJavaMethodProfile(
                            ji.type(),
                            "equals",
                            lookupPublicMethod(ji.type(), "equals", Object.class))
                    .effects();
        }
        if (type instanceof Type.Array arr) {
            return singleEqualityProfile(arr.element(), visiting);
        }
        return EffectSet.ofInferred(
                EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC),
                false,
                true,
                false,
                true);
    }

    EffectSet comparisonProfile(Type left, Type right) {
        if (left instanceof Type.Union lu) {
            EffectSet acc =
                    EffectSet.ofInferred(
                            EnumSet.of(
                                    Effect.PURE,
                                    Effect.HERMETIC,
                                    Effect.DETERMINISTIC,
                                    Effect.NOTHROW),
                            false,
                            true,
                            false,
                            true);
            for (Type m : lu.members()) {
                acc = acc.intersect(comparisonProfile(m, right));
            }
            return acc;
        }
        if (right instanceof Type.Union ru) {
            EffectSet acc =
                    EffectSet.ofInferred(
                            EnumSet.of(
                                    Effect.PURE,
                                    Effect.HERMETIC,
                                    Effect.DETERMINISTIC,
                                    Effect.NOTHROW),
                            false,
                            true,
                            false,
                            true);
            for (Type m : ru.members()) {
                acc = acc.intersect(comparisonProfile(left, m));
            }
            return acc;
        }
        if (Type.isCoreScalar(left) && Type.isCoreScalar(right)) {
            return EffectSet.ofInferred(
                    EnumSet.of(Effect.PURE, Effect.HERMETIC, Effect.DETERMINISTIC, Effect.NOTHROW),
                    false,
                    true,
                    false,
                    true);
        }
        if (left instanceof Type.JavaInstance ji) {
            Method cmp = lookupPublicMethod(ji.type(), "compareTo", ji.type());
            if (cmp == null) {
                cmp = lookupPublicMethod(ji.type(), "compareTo", Object.class);
            }
            return resolveJavaMethodProfile(ji.type(), "compareTo", cmp).effects();
        }
        return EffectSet.EMPTY;
    }

    static Method lookupPublicMethod(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) {
            return null;
        }
        try {
            return cls.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    static Field findJavaField(Class<?> cls, String name) {
        try {
            return cls.getField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    static Set<Class<?>> collectJavaSupertypes(TypeNode node) {
        Set<Class<?>> result = new LinkedHashSet<>();
        result.add(Object.class);
        collectJavaSupertypesInto(node, result, Collections.newSetFromMap(new IdentityHashMap<>()));
        return result;
    }

    private static void collectJavaSupertypesInto(
            TypeNode node, Set<Class<?>> out, Set<TypeNode> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        for (Object parent : node.parents) {
            if (parent instanceof Class<?> cls) {
                out.add(cls);
            } else if (parent instanceof TypeNode tn) {
                collectJavaSupertypesInto(tn, out, visited);
            }
        }
    }

    static boolean hasJavaMethodWithArity(Class<?> owner, String name, int arity) {
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity) {
                return true;
            }
        }
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity) {
                return true;
            }
        }
        return false;
    }

    static boolean isCollectionFactoryMethod(Class<?> owner, String methodName) {
        if (owner == null || methodName == null) {
            return false;
        }
        if (owner == java.util.List.class
                || owner == java.util.Set.class
                || owner == java.util.Map.class) {
            return "of".equals(methodName) || "copyOf".equals(methodName);
        }
        if (owner == java.util.Arrays.class) {
            return "asList".equals(methodName);
        }
        if (owner == java.util.Collections.class) {
            return "nCopies".equals(methodName)
                    || "singletonList".equals(methodName)
                    || "singleton".equals(methodName)
                    || "singletonMap".equals(methodName)
                    || "emptyList".equals(methodName)
                    || "emptySet".equals(methodName)
                    || "emptyMap".equals(methodName);
        }
        return false;
    }

    /** The host axioms naming a member of {@code owner} (tier 2). */
    private static List<AxiomData.MemberAxiom> named(AxiomData.AxiomTable host, Class<?> owner) {
        List<AxiomData.MemberAxiom> members = host.directMembers(owner);
        return members.isEmpty() ? members : members.stream().filter(m -> !m.isWildcard()).toList();
    }

    /** The host wildcards about all of {@code owner}'s members (tier 4). */
    private static List<AxiomData.MemberAxiom> wildcards(
            AxiomData.AxiomTable host, Class<?> owner) {
        List<AxiomData.MemberAxiom> members = host.directMembers(owner);
        return members.isEmpty()
                ? members
                : members.stream().filter(AxiomData.MemberAxiom::isWildcard).toList();
    }

    private static boolean allParamsPrimitiveOrString(Executable exec) {
        for (Class<?> pt : exec.getParameterTypes()) {
            if (!pt.isPrimitive() && pt != String.class) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAnyEffectAnnotation(Executable exec) {
        if (hasElementEffectAnnotation(exec)) {
            return true;
        }
        for (Parameter p : exec.getParameters()) {
            if (p.isAnnotationPresent(Mut.class)
                    || p.isAnnotationPresent(Shared.class)
                    || p.isAnnotationPresent(Unique.class)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasClassEffectAnnotation(Class<?> cls) {
        return cls != null && hasElementEffectAnnotation(cls);
    }

    private static boolean hasElementEffectAnnotation(AnnotatedElement el) {
        return el.isAnnotationPresent(Pure.class)
                || el.isAnnotationPresent(Volatile.class)
                || el.isAnnotationPresent(Mut.class)
                || el.isAnnotationPresent(Shared.class)
                || el.isAnnotationPresent(Unique.class)
                || el.isAnnotationPresent(Nothrow.class)
                || el.isAnnotationPresent(Idempotent.class)
                || el.isAnnotationPresent(Commutative.class)
                || el.isAnnotationPresent(Associative.class)
                || el.isAnnotationPresent(Monotone.class)
                || el.isAnnotationPresent(Injective.class)
                || el.isAnnotationPresent(IO.class);
    }

    private static EffectChecker.JavaCallProfile profileFromAnnotations(
            Executable exec, Class<?> classSource, boolean classLevelOnly) {
        int paramCount = exec != null ? exec.getParameterCount() : 0;
        boolean[] mutParams = new boolean[paramCount];
        boolean[] borrowParams = new boolean[paramCount];
        boolean[] uniqueParams = new boolean[paramCount];
        AnnotatedElement primary = (!classLevelOnly && exec != null) ? exec : classSource;
        if (primary.isAnnotationPresent(IO.class)) {
            return new EffectChecker.JavaCallProfile(
                    EffectSet.IO_DECLARED,
                    false,
                    false,
                    mutParams,
                    borrowParams,
                    uniqueParams,
                    false);
        }
        EnumSet<Effect> effects = EnumSet.noneOf(Effect.class);
        collectAnnotationsInto(classSource, effects);
        if (!classLevelOnly && exec != null) {
            collectAnnotationsInto(exec, effects);
        }
        boolean isStaticOrCtor =
                exec instanceof Constructor<?>
                        || (exec != null && Modifier.isStatic(exec.getModifiers()));
        boolean mutReceiver =
                !classLevelOnly
                        && exec instanceof Method
                        && !isStaticOrCtor
                        && exec.isAnnotationPresent(Mut.class)
                        && exec.getDeclaringClass() != String.class;
        boolean explicitVolatile =
                (!classLevelOnly && exec != null && exec.isAnnotationPresent(Volatile.class))
                        || (classSource != null
                                && classSource.isAnnotationPresent(Volatile.class)
                                && (classLevelOnly
                                        || exec == null
                                        || !exec.isAnnotationPresent(Pure.class)));
        boolean explicitShared =
                (!classLevelOnly && exec != null && exec.isAnnotationPresent(Shared.class))
                        || (classSource != null && classSource.isAnnotationPresent(Shared.class));
        boolean uniqueResult =
                !classLevelOnly
                        && exec != null
                        && (exec.isAnnotationPresent(Unique.class)
                                || (exec instanceof Constructor<?>
                                        && effects.contains(Effect.HERMETIC)
                                        && allParamsPrimitiveOrString(exec)));
        if (exec != null) {
            Parameter[] params = exec.getParameters();
            for (int i = 0; i < params.length; i++) {
                if (params[i].isAnnotationPresent(Mut.class)
                        && params[i].getType() != String.class) {
                    mutParams[i] = true;
                }
                if (params[i].isAnnotationPresent(Unique.class)) {
                    uniqueParams[i] = true;
                    borrowParams[i] = false;
                } else {
                    borrowParams[i] =
                            !params[i].isAnnotationPresent(Shared.class) && !explicitShared;
                }
            }
        }
        if (explicitVolatile) {
            effects.add(Effect.HERMETIC);
            effects.remove(Effect.DETERMINISTIC);
            if (!mutReceiver && !anyTrue(mutParams)) {
                effects.add(Effect.PURE);
            }
        } else if ((mutReceiver || anyTrue(mutParams)) && !effects.contains(Effect.HERMETIC)) {
            effects.add(Effect.HERMETIC);
            effects.add(Effect.DETERMINISTIC);
        }
        if (mutReceiver || anyTrue(mutParams)) {
            effects.remove(Effect.PURE);
        }
        boolean borrowReceiver = !isStaticOrCtor && !explicitShared;
        boolean explicitNothrow = effects.contains(Effect.NOTHROW);
        EffectSet set =
                EffectSet.ofDeclared(
                        effects,
                        mutReceiver,
                        isStaticOrCtor || !explicitShared,
                        !isStaticOrCtor && explicitShared,
                        uniqueResult,
                        false,
                        explicitVolatile,
                        true);
        return new EffectChecker.JavaCallProfile(
                set,
                mutReceiver,
                borrowReceiver,
                mutParams,
                borrowParams,
                uniqueParams,
                explicitNothrow);
    }

    private static void collectAnnotationsInto(AnnotatedElement el, EnumSet<Effect> out) {
        if (el == null || el.isAnnotationPresent(IO.class)) {
            return;
        }
        if (el.isAnnotationPresent(Pure.class)) {
            out.add(Effect.PURE);
            out.add(Effect.HERMETIC);
            out.add(Effect.DETERMINISTIC);
        }
        if (el.isAnnotationPresent(Volatile.class)) {
            out.add(Effect.HERMETIC);
            out.remove(Effect.DETERMINISTIC);
        }
        if (el.isAnnotationPresent(Nothrow.class)) {
            out.add(Effect.NOTHROW);
        }
        if (el.isAnnotationPresent(Idempotent.class)) {
            out.add(Effect.IDEMPOTENT);
        }
        if (el.isAnnotationPresent(Commutative.class)) {
            out.add(Effect.COMMUTATIVE);
        }
        if (el.isAnnotationPresent(Associative.class)) {
            out.add(Effect.ASSOCIATIVE);
        }
        if (el.isAnnotationPresent(Monotone.class)) {
            out.add(Effect.MONOTONE);
        }
        if (el.isAnnotationPresent(Injective.class)) {
            out.add(Effect.INJECTIVE);
        }
    }

    private static boolean anyTrue(boolean[] arr) {
        for (boolean b : arr) {
            if (b) {
                return true;
            }
        }
        return false;
    }
}
